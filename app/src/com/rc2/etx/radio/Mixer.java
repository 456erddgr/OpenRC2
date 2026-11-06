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
        // ===== 2026-11-06 重排：同类别连在一起，不再东一个西一个 =====
        // ① 摇杆四轴
        c[0] = new ChannelConfig(ChannelConfig.SRC_AIL, "横滚");
        c[1] = new ChannelConfig(ChannelConfig.SRC_ELV, "俯仰");
        c[2] = new ChannelConfig(ChannelConfig.SRC_THR, "油门");
        c[3] = new ChannelConfig(ChannelConfig.SRC_RUD, "偏航");
        // ② 旋钮（K1/K2）
        c[4] = new ChannelConfig(ChannelConfig.SRC_KNOB1, "旋钮K1");
        c[5] = new ChannelConfig(ChannelConfig.SRC_KNOB2, "旋钮K2");
        // ③ 自定义按键（C1/C2 是后面那两个，与 AUX2/AUX3 同源）
        c[6] = new ChannelConfig(ChannelConfig.SRC_AUX2, "按键C1");
        c[7] = new ChannelConfig(ChannelConfig.SRC_AUX3, "按键C2");
        // ④ 拍照 / 录像（独立源，不再和 AUX4 抢槽）
        c[8] = new ChannelConfig(ChannelConfig.SRC_SW_SHOT, "按键拍照");
        c[9] = new ChannelConfig(ChannelConfig.SRC_SW_REC, "按键录像");
        // ⑤ 调档键：S / N / C 三个独立通道（N 档默认 +100%）
        c[10] = new ChannelConfig(ChannelConfig.SRC_AUX1, "调档主");   // N=+100 C=-100
        c[11] = new ChannelConfig(ChannelConfig.SRC_SW_S, "调档S");
        c[12] = new ChannelConfig(ChannelConfig.SRC_SW_C, "调档C");
        // ⑥ 其余留给用户
        for (int i = 13; i < CH_COUNT; i++) {
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
