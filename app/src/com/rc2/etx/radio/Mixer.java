package com.rc2.etx.radio;

/** 16 通道混控器：输入状态 -> 每通道单位值。 */
public final class Mixer {

    public static final int CH_COUNT = 16;

    private final ChannelConfig[] cfg = new ChannelConfig[CH_COUNT];
    private final float[] out = new float[CH_COUNT];

    public Mixer() {
        for (int i = 0; i < CH_COUNT; i++) {
            cfg[i] = new ChannelConfig();
        }
    }

    public ChannelConfig cfg(int ch) {
        return cfg[ch];
    }

    public void setConfig(ChannelConfig[] c) {
        for (int i = 0; i < CH_COUNT; i++) {
            cfg[i] = (c != null && i < c.length && c[i] != null) ? c[i] : new ChannelConfig();
        }
    }

    /** 出厂默认：AETR + 4 个三档开关 + 2 个旋钮。 */
    public static ChannelConfig[] defaultConfig() {
        ChannelConfig[] c = new ChannelConfig[CH_COUNT];
        c[0] = new ChannelConfig(ChannelConfig.SRC_AIL, "横滚");
        c[1] = new ChannelConfig(ChannelConfig.SRC_ELV, "俯仰");
        c[2] = new ChannelConfig(ChannelConfig.SRC_THR, "油门");
        c[3] = new ChannelConfig(ChannelConfig.SRC_RUD, "偏航");
        for (int i = 4; i <= 7; i++) {
            c[i] = new ChannelConfig(ChannelConfig.SRC_AUX1 + (i - 4), "开关" + (i - 3));
        }
        c[8] = new ChannelConfig(ChannelConfig.SRC_KNOB1, "旋钮1");
        c[9] = new ChannelConfig(ChannelConfig.SRC_KNOB2, "旋钮2");
        // 2026-11-06：物理按键/档位的独立通道（用户要求"这些按钮为通道"）
        c[10] = new ChannelConfig(ChannelConfig.SRC_SW_REC, "录像键");
        c[11] = new ChannelConfig(ChannelConfig.SRC_SW_SHOT, "拍照键");
        c[12] = new ChannelConfig(ChannelConfig.SRC_SW_S, "调档S");
        c[13] = new ChannelConfig(ChannelConfig.SRC_SW_C, "调档C");
        for (int i = 14; i < CH_COUNT; i++) {
            c[i] = new ChannelConfig(ChannelConfig.SRC_NONE, "CH" + (i + 1));
        }
        return c;
    }

    public float[] compute(InputState in, boolean applyMixer) {
        for (int i = 0; i < CH_COUNT; i++) {
            float raw = sourceValue(cfg[i].src, in);
            out[i] = applyMixer ? cfg[i].mix(raw) : raw;
        }
        return out;
    }

    public static float sourceValue(int src, InputState in) {
        switch (src) {
            case ChannelConfig.SRC_AIL: return in.rx;
            case ChannelConfig.SRC_ELV: return in.ry;
            case ChannelConfig.SRC_THR: return in.ly;
            case ChannelConfig.SRC_RUD: return in.lx;
            case ChannelConfig.SRC_AUX1: return in.aux1;
            case ChannelConfig.SRC_AUX2: return in.aux2;
            case ChannelConfig.SRC_AUX3: return in.aux3;
            case ChannelConfig.SRC_AUX4: return in.aux4;
            case ChannelConfig.SRC_KNOB1: return in.knob1;
            case ChannelConfig.SRC_KNOB2: return in.knob2;
            // 2026-11-06 新增的物理按键开关源
            case ChannelConfig.SRC_SW_S: return in.swS;
            case ChannelConfig.SRC_SW_C: return in.swC;
            case ChannelConfig.SRC_SW_REC: return in.swRec;
            case ChannelConfig.SRC_SW_SHOT: return in.swShot;
            default: return 0f;
        }
    }
}
