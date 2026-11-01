// libetx.so 的"假核心"：只实现 docs/07 里那 9 个符号，用来验证
// APK 打包 -> dlopen -> init -> render -> channels 整条链，不画 EdgeTX 真界面。
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define TAG "ETX-Stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static int g_w = 480, g_h = 272;
static int g_axes[8] = {1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024};
static int g_sw[8] = {0};
static int g_kn[4] = {1024, 1024};
static int g_frame = 0;
static int g_touch = -1;
static char g_ver[32] = "stub-0.1";

extern "C" {

int etx_init(const char* sdPath) {
    LOGI("init sd=%s", sdPath ? sdPath : "(null)");
    if (!sdPath) return -1;
    char p[512];
    snprintf(p, sizeof(p), "%s/RADIO/radio.yml", sdPath);
    FILE* f = fopen(p, "rb");
    if (!f) {
        LOGI("radio.yml 读不到: %s", p);
        snprintf(g_ver, sizeof(g_ver), "stub-0.1 (no yml)");
        return -2;
    }
    int n = 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) if (strstr(line, "semver")) n++;
    fclose(f);
    snprintf(g_ver, sizeof(g_ver), "stub-0.1 (yml ok)");
    LOGI("radio.yml ok, semver 行数=%d", n);
    return 0;
}

void etx_shutdown(void) { LOGI("shutdown"); }

const char* etx_version(void) { return g_ver; }

int etx_screen_w(void) { return g_w; }
int etx_screen_h(void) { return g_h; }

void etx_set_inputs(const int* axes, int nAxes, const int* sw, int nSw, const int* knobs, int nKnobs) {
    for (int i = 0; i < nAxes && i < 8; i++) g_axes[i] = axes[i];
    for (int i = 0; i < nSw && i < 8; i++) g_sw[i] = sw[i];
    for (int i = 0; i < nKnobs && i < 4; i++) g_kn[i] = knobs[i];
}

int etx_tick(int ms) {
    (void) ms;
    g_frame++;
    return (g_frame % 3 == 0) ? 1 : 0;
}

int etx_touch(int action, int x, int y) {
    g_touch = action;
    LOGI("touch a=%d x=%d y=%d", action, x, y);
    return 1;
}

static int us_from_unit(float u) {
    if (u < -1.f) u = -1.f;
    if (u > 1.f) u = 1.f;
    return (int) (992.f + u * 819.f);   // 992 ± 819 = 173..1811
}

int etx_channels_us(int* out16) {
    if (!out16) return 0;
    for (int i = 0; i < 16; i++) out16[i] = 992;
    for (int i = 0; i < 4; i++) {
        float u = (g_axes[i] - 1024) / 660.f;
        out16[i] = us_from_unit(u);
    }
    for (int i = 0; i < 2; i++) {
        float u = (g_kn[i] - 1024) / 660.f;
        out16[8 + i] = us_from_unit(u);
    }
    return 16;
}

int etx_render(int* argb, int w, int h) {
    if (!argb || w <= 0 || h <= 0) return 0;
    int blk = (g_frame * 6) % (w > 100 ? w : 100);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            unsigned a = 0xFF000000u;
            unsigned r = (unsigned) ((x * 255) / (w > 1 ? w - 1 : 1));
            unsigned g = (unsigned) ((y * 255) / (h > 1 ? h - 1 : 1));
            unsigned b = 0x40u;
            if (x >= blk && x < blk + 40) { r = 0xFF; g = 0xFF; b = 0xFF; }
            // 顶部四条轴的位置画成小方块，肉眼可验证 set_inputs 生效
            for (int i = 0; i < 4; i++) {
                int cx = (int) ((g_axes[i] / 2047.f) * (w - 1));
                int cy = 20 + i * 24;
                if (y >= cy && y < cy + 16 && x >= cx - 8 && x < cx + 8) { r = 0x22; g = 0xC3; b = 0xA6; }
            }
            argb[y * w + x] = (int) (a | (r << 16) | (g << 8) | b);
        }
    }
    return 1;
}

}  // extern "C"
