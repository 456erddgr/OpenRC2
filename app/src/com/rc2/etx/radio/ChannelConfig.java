package com.rc2.etx.radio;

/** 单通道混控参数。 */
public final class ChannelConfig {
    public static final int SRC_NONE = -1;
    public static final int SRC_AIL = 0;
    public static final int SRC_ELV = 1;
    public static final int SRC_THR = 2;
    public static final int SRC_RUD = 3;
    public static final int SRC_AUX1 = 4;
    public static final int SRC_AUX2 = 5;
    public static final int SRC_AUX3 = 6;
    public static final int SRC_AUX4 = 7;
    public static final int SRC_KNOB1 = 8;
    public static final int SRC_KNOB2 = 9;

    public static final String[] SRC_NAMES = {
            "AIL 横滚", "ELV 俯仰", "THR 油门", "RUD 偏航",
            "AUX1", "AUX2", "AUX3", "AUX4", "K1 旋钮", "K2 旋钮", "未使用"
    };

    public int src = SRC_NONE;
    public float expo = 0f;      // 0..1
    public float rate = 1f;      // 0..1.5
    public float offset = 0f;    // -1..1
    public boolean reverse = false;
    public String name = "";

    public ChannelConfig() {
    }

    public ChannelConfig(int src, String name) {
        this.src = src;
        this.name = name;
    }

    public static ChannelConfig copyOf(ChannelConfig c) {
        ChannelConfig n = new ChannelConfig();
        n.src = c.src;
        n.expo = c.expo;
        n.rate = c.rate;
        n.offset = c.offset;
        n.reverse = c.reverse;
        n.name = c.name;
        return n;
    }

    public static String srcName(int src) {
        int i = src - SRC_AIL;
        if (i < 0 || i >= SRC_NAMES.length) return "未使用";
        return SRC_NAMES[i];
    }

    /** expo 曲线：e=0 线性；e=1 纯三次曲线（中点附近最柔和）。 */
    public static float applyExpo(float x, float e) {
        if (e <= 0.001f) return x;
        if (e > 1f) e = 1f;
        return (1f - e) * x + e * x * x * x;
    }

    /** 输入 -> 输出（单位值）。 */
    public float mix(float in) {
        float x = applyExpo(in, expo) * rate;
        if (reverse) x = -x;
        x += offset;
        if (x > 1f) x = 1f;
        if (x < -1f) x = -1f;
        return x;
    }
}
