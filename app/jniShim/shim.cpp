// libetx.so 的 JNI 转发层（临时方案）：
// 上游核心库是纯 C 导出（无 Java_* 符号），这里用 dlopen/dlsym 转发
// com.rc2.etx.jni.EtxNative 的 9 个 native 方法到 libetx_core.so。
// 一旦核心库把 etx_jni.cpp 编进去，这个 shim 就能删掉。
#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>

#define TAG "ETX-Shim"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
struct Api {
    int  (*init)(const char*);
    void (*shutdown)();
    const char* (*version)();
    int  (*screen_w)();
    int  (*screen_h)();
    void (*set_inputs)(const int*, int, const int*, int, const int*, int);
    int  (*tick)(int);
    int  (*touch)(int, int, int);
    int  (*channels_us)(int*);
    int  (*render)(int*, int, int);
    bool ok;
};

Api& api() {
    static Api a = {};
    static bool tried = false;
    if (tried) return a;
    tried = true;
    void* h = dlopen("libetx_core.so", RTLD_NOW);
    if (!h) {
        LOGE("dlopen libetx_core.so 失败: %s", dlerror());
        return a;
    }
    a.init        = (int(*)(const char*))        dlsym(h, "etx_init");
    a.shutdown    = (void(*)(void))              dlsym(h, "etx_shutdown");
    a.version     = (const char*(*)(void))       dlsym(h, "etx_version");
    a.screen_w    = (int(*)(void))               dlsym(h, "etx_screen_w");
    a.screen_h    = (int(*)(void))               dlsym(h, "etx_screen_h");
    a.set_inputs  = (void(*)(const int*,int,const int*,int,const int*,int)) dlsym(h, "etx_set_inputs");
    a.tick        = (int(*)(int))                dlsym(h, "etx_tick");
    a.touch       = (int(*)(int,int,int))        dlsym(h, "etx_touch");
    a.channels_us = (int(*)(int*))               dlsym(h, "etx_channels_us");
    a.render      = (int(*)(int*,int,int))       dlsym(h, "etx_render");
    a.ok = a.init && a.shutdown && a.version && a.screen_w && a.screen_h &&
           a.set_inputs && a.tick && a.touch && a.channels_us && a.render;
    if (!a.ok) LOGE("libetx_core.so 符号不全");
    return a;
}
}  // namespace

#define JNI_FN(name) Java_com_rc2_etx_jni_EtxNative_##name
#define JNI_ARGS JNIEnv* env, jclass clazz

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(init)(JNI_ARGS, jstring sdPath) {
    Api& a = api();
    if (!a.ok) return -99;
    const char* p = sdPath ? env->GetStringUTFChars(sdPath, nullptr) : nullptr;
    int rc = a.init(p);
    if (p) env->ReleaseStringUTFChars(sdPath, p);
    return rc;
}

JNIEXPORT void JNICALL JNI_FN(shutdown)(JNI_ARGS) { Api& a = api(); if (a.ok) a.shutdown(); }

JNIEXPORT jstring JNICALL JNI_FN(version)(JNI_ARGS) {
    Api& a = api();
    const char* v = a.ok ? a.version() : "shim: core missing";
    return env->NewStringUTF(v ? v : "unknown");
}

JNIEXPORT jint JNICALL JNI_FN(screenW)(JNI_ARGS) { Api& a = api(); return a.ok ? a.screen_w() : 0; }
JNIEXPORT jint JNICALL JNI_FN(screenH)(JNI_ARGS) { Api& a = api(); return a.ok ? a.screen_h() : 0; }

JNIEXPORT void JNICALL JNI_FN(setInputs)(JNI_ARGS, jintArray axes, jint nAxes,
                                         jintArray sw, jint nSw,
                                         jintArray knobs, jint nKnobs) {
    Api& a = api();
    if (!a.ok) return;
    jint* pa = axes ? env->GetIntArrayElements(axes, nullptr) : nullptr;
    jint* ps = sw ? env->GetIntArrayElements(sw, nullptr) : nullptr;
    jint* pk = knobs ? env->GetIntArrayElements(knobs, nullptr) : nullptr;
    a.set_inputs(pa, nAxes, ps, nSw, pk, nKnobs);
    if (pa) env->ReleaseIntArrayElements(axes, pa, JNI_ABORT);
    if (ps) env->ReleaseIntArrayElements(sw, ps, JNI_ABORT);
    if (pk) env->ReleaseIntArrayElements(knobs, pk, JNI_ABORT);
}

JNIEXPORT jint JNICALL JNI_FN(tick)(JNI_ARGS, jint ms) { Api& a = api(); return a.ok ? a.tick(ms) : 0; }

JNIEXPORT jint JNICALL JNI_FN(touch)(JNI_ARGS, jint action, jint x, jint y) {
    Api& a = api();
    return a.ok ? a.touch(action, x, y) : 0;
}

JNIEXPORT jint JNICALL JNI_FN(channelsUs)(JNI_ARGS, jintArray out16) {
    Api& a = api();
    if (!a.ok || !out16) return 0;
    jsize n = env->GetArrayLength(out16);
    jint tmp[32];
    int want = n < 32 ? n : 32;
    int got = a.channels_us(tmp);
    if (got > want) got = want;
    env->SetIntArrayRegion(out16, 0, got, tmp);
    return got;
}

JNIEXPORT jint JNICALL JNI_FN(render)(JNI_ARGS, jintArray argb, jint w, jint h) {
    Api& a = api();
    if (!a.ok || !argb) return 0;
    jsize n = env->GetArrayLength(argb);
    if (n < w * h) return -1;
    jint* px = env->GetIntArrayElements(argb, nullptr);
    int rc = a.render(px, w, h);
    env->ReleaseIntArrayElements(argb, px, 0);
    return rc;
}

}  // extern "C"
