package com.rc2.etx.jni;

/** libetx.so 的 Java 侧门面：把真 EdgeTX 的 C++ 核心拉进进程。
 *  约定见 docs/07-桥接接口约定.md；so 不存在时 available() 为 false，UI 走占位。 */
public final class EtxNative {
    private static boolean tried = false;
    private static boolean ok = false;
    private static String err = "未加载";

    public static synchronized boolean available() {
        if (tried) return ok;
        tried = true;
        try {
            System.loadLibrary("etx");
            ok = true;
            err = "已加载 libetx.so";
        } catch (Throwable t) {
            ok = false;
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return ok;
    }

    public static String error() { return err; }

    // ---- 生命周期 ----
    public static native int init(String sdPath);      // 0 = OK
    public static native void shutdown();
    public static native String version();             // "2.12.2"

    // ---- 屏幕 ----
    public static native int screenW();
    public static native int screenH();

    // ---- 输入：11bit ADC(0..2047) 与开关/旋钮 ----
    public static native void setInputs(int[] axes, int nAxes, int[] sw, int nSw, int[] knobs, int nKnobs);
    public static native int tick(int ms);             // 1 = 有新帧
    public static native int touch(int action, int x, int y);

    /** 2026-11-01 新增：合成 EdgeTX 按键事件（核心侧第 10 个导出）。 */
    private static native int etxKey(int key, int down);
    private static native int etxUiPendingTap();
    private static native int rotary(int steps);

    /** C 路：取固件自己的每轴校准窗口。out[0]=min out[1]=mid out[2]=max。符号缺失时 false。 */
    public static native boolean axisCalib(int axis, int[] out);

    private static native int etxSetVbat(int mV);

    /** 把系统电池服务的真实电压(mV)推给核心；symbol 不在时返回 -1。 */
    public static int setVbat(int mV) {
        try { return etxSetVbat(mV); } catch (Throwable t) { return -1; }
    }

    /** 旋钮：+n / -n 格（内核里一格 = 1% 或一行）。符号缺失时返回 -1。 */
    public static int turn(int steps) {
        try { return rotary(steps); } catch (Throwable t) { return -1; }
    }

    /** Plan D：核心只报"这一下点在第几行"，按键由 Java 主线程发。返回 <0 = 无。 */
    public static int pendingTap() {
        try { return etxUiPendingTap(); } catch (Throwable t) { return -1; }
    }

    /** 核心还没导出 etx_key 时返回 -1，不抛异常。 */
    public static int key(int k, boolean down) {
        try {
            return etxKey(k, down ? 1 : 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 确定性按键：按下后必须在后续帧里 release（同一帧 down+up 会被 keysPollingCycle 漏掉）。 */
    public static void press(int k) { key(k, true); }

    public static void release(int k) { key(k, false); }

    public static void tapKey(int k) {
        key(k, true);
        key(k, false);
    }
  // 0 down / 1 move / 2 up

    // ---- 输出 ----
    // ---- 声音（EdgeTX 警告音/提示音）----
    /** 原生侧要发声：freq 频率(Hz)，len 时长(ms)，pause 之后静音(ms)。 */
    public static native boolean audioTone(int freq, int len, int pause);

    /** 原生→Java：送一块 PCM（S16 单声道 32000Hz，一块 320 样本）。 */
    public static void pcm(short[] data, int count) {
        com.rc2.etx.audio.EtxAudio.write(data, count);
    }

    public static native int channelsUs(int[] out16);  // 返回写了几路
    public static native int render(int[] argb, int w, int h);  // 1 = 画面变了

    private static native int renderFull(int[] argb, int w, int h);

    /** 强制整屏重绘（不判脏区）；so 没有该符号时退化为普通 render。 */
    public static int full(int[] argb, int w, int h) {
        try { return renderFull(argb, w, h); } catch (Throwable t) { return render(argb, w, h); }
    }
}
