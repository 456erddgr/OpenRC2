// JNI 胶水：把 libetx.so 里的纯 C 核心（etx_*）暴露给 com.rc2.etx.jni.EtxNative。
// 真移植时这份胶水完全复用 —— EdgeTX 侧只按 docs/07 导出 C 符号，不需要认识 JNI。
#include <jni.h>
#include <cstring>

extern "C" {
int etx_init(const char* sdPath);
void etx_shutdown(void);
const char* etx_version(void);
int etx_screen_w(void);
int etx_screen_h(void);
int etx_tick(int ms);
int etx_touch(int action, int x, int y);
int etx_channels_us(int* out16);
int etx_render(int* argb, int w, int h);
void etx_set_inputs(const int* axes, int nAxes, const int* sw, int nSw,
                    const int* knobs, int nKnobs);
}

#define JNI_FN(name) Java_com_rc2_etx_jni_EtxNative_##name
#define JNI_ARGS JNIEnv* env, jclass clazz

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(init)(JNI_ARGS, jstring sdPath) {
    const char* p = nullptr;
    if (sdPath) p = env->GetStringUTFChars(sdPath, nullptr);
    int rc = etx_init(p);
    if (p) env->ReleaseStringUTFChars(sdPath, p);
    return rc;
}

JNIEXPORT void JNICALL JNI_FN(shutdown)(JNI_ARGS) { etx_shutdown(); }

JNIEXPORT jstring JNICALL JNI_FN(version)(JNI_ARGS) {
    const char* v = etx_version();
    return env->NewStringUTF(v ? v : "unknown");
}

JNIEXPORT jint JNICALL JNI_FN(screenW)(JNI_ARGS) { return etx_screen_w(); }
JNIEXPORT jint JNICALL JNI_FN(screenH)(JNI_ARGS) { return etx_screen_h(); }

JNIEXPORT void JNICALL JNI_FN(setInputs)(JNI_ARGS, jintArray axes, jint nAxes,
                                         jintArray sw, jint nSw,
                                         jintArray knobs, jint nKnobs) {
    jint* a = axes ? env->GetIntArrayElements(axes, nullptr) : nullptr;
    jint* s = sw ? env->GetIntArrayElements(sw, nullptr) : nullptr;
    jint* k = knobs ? env->GetIntArrayElements(knobs, nullptr) : nullptr;
    etx_set_inputs(a, nAxes, s, nSw, k, nKnobs);
    if (a) env->ReleaseIntArrayElements(axes, a, JNI_ABORT);
    if (s) env->ReleaseIntArrayElements(sw, s, JNI_ABORT);
    if (k) env->ReleaseIntArrayElements(knobs, k, JNI_ABORT);
}

JNIEXPORT jint JNICALL JNI_FN(tick)(JNI_ARGS, jint ms) { return etx_tick(ms); }
JNIEXPORT jint JNICALL JNI_FN(touch)(JNI_ARGS, jint action, jint x, jint y) {
    return etx_touch(action, x, y);
}

JNIEXPORT jint JNICALL JNI_FN(channelsUs)(JNI_ARGS, jintArray out16) {
    if (!out16) return 0;
    jsize n = env->GetArrayLength(out16);
    jint tmp[32];
    int want = n < 32 ? n : 32;
    int got = etx_channels_us(tmp);
    if (got > want) got = want;
    env->SetIntArrayRegion(out16, 0, got, tmp);
    return got;
}

JNIEXPORT jint JNICALL JNI_FN(render)(JNI_ARGS, jintArray argb, jint w, jint h) {
    if (!argb) return 0;
    jsize n = env->GetArrayLength(argb);
    if (n < w * h) return -1;
    jint* px = env->GetIntArrayElements(argb, nullptr);
    int rc = etx_render(px, w, h);
    env->ReleaseIntArrayElements(argb, px, 0);
    return rc;
}

}  // extern "C"
