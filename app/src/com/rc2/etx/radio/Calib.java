package com.rc2.etx.radio;

/**
 * 通道校准：把输入源在 [-1,+1] 归一域里实际测到的行程，映射成满行程 [-1,+1]。
 * 同时负责「拨动摇杆自动识别是哪根轴」——给通道分配源用。
 */
public final class Calib {

    public static final int AXES = 10;
    public static final String[] NAMES = {
            "左摇杆 横向", "左摇杆 纵向", "右摇杆 横向", "右摇杆 纵向",
            "AUX1 开关", "AUX2 开关", "AUX3 开关", "AUX4 开关",
            "K1 旋钮", "K2 旋钮"
    };
    public static final String[] SHORT = {"LX", "LY", "RX", "RY", "A1", "A2", "A3", "A4", "K1", "K2"};

    /** 是否启用校准映射。关掉时输入原样透传。 */
    public volatile boolean enabled = false;
    /** 是否正在采集。 */
    public volatile boolean capturing = false;

    public final float[] min = new float[AXES];
    public final float[] mid = new float[AXES];
    public final float[] max = new float[AXES];
    public final boolean[] reverse = new boolean[AXES];
    /** 本次采集中该轴是否真的动过（用于判断采集是否有效）。 */
    public final boolean[] moved = new boolean[AXES];
    /** 每轴最近一次原始值。 */
    public final float[] raw = new float[AXES];
    /** 自动识别结果：最近一次"大幅移动"的轴，-1 表示无。 */
    public volatile int lastMovedAxis = -1;
    public volatile long lastMovedMs;

    private static final float MOVE_THRESHOLD = 0.25f;

    public Calib() {
        defaults();
    }

    public void defaults() {
        for (int i = 0; i < AXES; i++) {
            boolean sw = (i >= 4 && i <= 7);
            min[i] = -1f;
            mid[i] = 0f;
            max[i] = 1f;
            reverse[i] = false;
            moved[i] = false;
            // 2026-11-03：开关/旋钮的"没信号"值必须是 0（中位）。
            // 原来 switch 轴 raw=-1，于是 CH5..CH8 在混控里恒为 -100%，
            // 并且校准页把这些 -1 当成中点写回（设备上的 calib JSON 里 mid 第 4..7 位
            // 就是 -1.000），校准后依然全负。
            raw[i] = 0f;
        }
        enabled = false;
        capturing = false;
        lastMovedAxis = -1;
    }

    /** 开始采集：把当前原始值当成中点，min/max 也以当前值起步。 */
    public void beginCapture() {
        for (int i = 0; i < AXES; i++) {
            min[i] = raw[i];
            max[i] = raw[i];
            mid[i] = raw[i];
            moved[i] = false;
        }
        lastMovedAxis = -1;
        capturing = true;
    }

    public void endCapture() {
        capturing = false;
        boolean any = false;
        for (int i = 0; i < AXES; i++) {
            if (moved[i] && Math.abs(max[i] - min[i]) > 0.20f) any = true;
        }
        enabled = any;
    }

    /** 采集模式下喂入原始值（每次触摸/输入都会调）。 */
    public void feed(int axis, float v) {
        if (axis < 0 || axis >= AXES) return;
        float prev = raw[axis];
        raw[axis] = v;
        if (Math.abs(v - prev) > MOVE_THRESHOLD) {
            lastMovedAxis = axis;
            lastMovedMs = System.currentTimeMillis();
        }
        if (!capturing) return;
        if (v < min[axis]) min[axis] = v;
        if (v > max[axis]) max[axis] = v;
        if (Math.abs(v - mid[axis]) > 0.20f) moved[axis] = true;
    }

    /** 原始值 -> 满行程单位值 [-1,+1]。 */
    public float map(int axis, float v) {
        if (!enabled || axis < 0 || axis >= AXES) return clamp(v);
        float lo = min[axis];
        float hi = max[axis];
        float c = mid[axis];
        if (hi - lo < 0.15f) return clamp(v);           // 行程太小，别乱缩放
        if (c <= lo + 0.05f) c = (lo + hi) * 0.5f;
        if (c >= hi - 0.05f) c = (lo + hi) * 0.5f;
        float u;
        if (v >= c) {
            u = (v - c) / (hi - c);
        } else {
            u = (v - c) / (c - lo);
        }
        if (reverse[axis]) u = -u;
        return clamp(u);
    }

    public void setMidFromRaw(int axis) {
        mid[axis] = raw[axis];
        moved[axis] = true;
    }

    public float travel(int axis) {
        return Math.abs(max[axis] - min[axis]);
    }

    public int movedCount() {
        int n = 0;
        for (int i = 0; i < AXES; i++) if (moved[i]) n++;
        return n;
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    // ---------- 存档 ----------
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"en\":").append(enabled);
        sb.append(",\"min\":[");
        for (int i = 0; i < AXES; i++) sb.append(i == 0 ? "" : ",").append(round(min[i]));
        sb.append("],\"mid\":[");
        for (int i = 0; i < AXES; i++) sb.append(i == 0 ? "" : ",").append(round(mid[i]));
        sb.append("],\"max\":[");
        for (int i = 0; i < AXES; i++) sb.append(i == 0 ? "" : ",").append(round(max[i]));
        sb.append("],\"rev\":[");
        for (int i = 0; i < AXES; i++) sb.append(i == 0 ? "" : ",").append(reverse[i]);
        sb.append("]}");
        return sb.toString();
    }

    private static String round(float f) {
        return String.format(java.util.Locale.US, "%.3f", f);
    }

    /** 极简解析：只认自己写出来的格式，任何异常都回落默认值。 */
    public void fromJson(String s) {
        if (s == null || s.length() < 8) return;
        try {
            for (int i = 0; i < AXES; i++) {
                min[i] = (float) num(s, "min", i);
                mid[i] = (float) num(s, "mid", i);
                max[i] = (float) num(s, "max", i);
            }
            int p = s.indexOf("\"rev\":[");
            if (p >= 0) {
                String rest = s.substring(p + 7, s.indexOf(']', p));
                String[] f = rest.split(",");
                for (int i = 0; i < AXES && i < f.length; i++) reverse[i] = "true".equals(f[i].trim());
            }
            enabled = s.contains("\"en\":true");
        } catch (Throwable ignore) {
        }
    }

    private static double num(String s, String key, int idx) {
        int p = s.indexOf("\"" + key + "\":[");
        if (p < 0) return 0;
        String rest = s.substring(p + key.length() + 4, s.indexOf(']', p));
        String[] f = rest.split(",");
        if (idx >= f.length) return 0;
        return Double.parseDouble(f[idx].trim());
    }
}
