package com.rc2.etx.radio;

/** 所有输入源的统一目标：摇杆单位值 [-1,+1]、开关三态、滚轮。 */
public final class InputState {
    public static final int STICK_L_X = 0;
    public static final int STICK_L_Y = 1;
    public static final int STICK_R_X = 2;
    public static final int STICK_R_Y = 3;

    public volatile float lx, ly, rx, ry;
    public volatile float aux1, aux2, aux3, aux4;
    public volatile float knob1, knob2;
    public volatile long lastInputMs;
    public volatile String lastSource = "-";
    /** 校准器（可为空）。非空时所有输入先喂给校准器记录原始值，再按校准曲线映射。 */
    public Calib calib;

    public void reset() {
        lx = 0; rx = 0; ry = 0;
        // 油门默认压到底（-1）：开机/无摇杆数据时必须是"油门最低"，不能是中位，否则接上电调会直接转
        ly = -1f;
        aux1 = -1; aux2 = -1; aux3 = -1; aux4 = -1;
        knob1 = 0; knob2 = 0;
    }

    public void touch(int idx, float v) {
        Calib c = calib;
        if (c != null) {
            c.feed(idx, v);
            v = c.map(idx, v);
        }
        if (v < -1f) v = -1f;
        if (v > 1f) v = 1f;
        switch (idx) {
            case 0: lx = v; break;
            case 1: ly = v; break;
            case 2: rx = v; break;
            case 3: ry = v; break;
            case 4: aux1 = v; break;
            case 5: aux2 = v; break;
            case 6: aux3 = v; break;
            case 7: aux4 = v; break;
            case 8: knob1 = v; break;
            case 9: knob2 = v; break;
            default: break;
        }
        lastInputMs = System.currentTimeMillis();
    }
}
