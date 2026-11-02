package com.rc2.etx.radio;

/**
 * 机内摇杆(DUMl 06/AE)只有 ~9.6Hz。这里把 10Hz 补成 100Hz，供混控与界面使用。
 *
 * 2.0 版算法（1.41.0，替代 1.39 的"一阶速度外推"）：
 *  1) 保留最近 3 个真实样本 (h0,t0)(h1,t1)(h2,t2)；
 *  2) 三点拟合出速度 v 与加速度 a（二阶差分），预测 p = cur + v·τ + ½·a·τ²；
 *  3) 预测时长 τ = min(档位上限, 实测间隔×1.8)，超过上限后按指数衰减回真实值；
 *  4) 三重约束防"抽搐/发飘"：反向立即清速度与加速度、加速度只在同向加速时生效、过冲钳制到最近一步的 N 倍。
 *
 * 档位（设置页可调，持久化 smoothLevel）：
 *   0=关（直通原始 10Hz） 1=弱 2=中 3=强 4=极强
 */
public final class StickSmooth {
    public static final int N = 6;

    // ---- 历史样本（三帧）----
    private final float[] h0 = new float[N], h1 = new float[N], h2 = new float[N];
    private long t0, t1, t2;
    private int hist;                      // 已有几帧（0..3）
    private boolean has;

    // ---- 估计量 ----
    private final float[] vel = new float[N];
    private final float[] acc = new float[N];
    private final float[] step = new float[N];
    private long lastArriveMs;

    private static final float VEL_DEAD = 0.010f;   // 速度死区（手抖不放大）
    private static final float ACC_W = 0.35f;       // 加速度权重（0=纯匀速，太大会甩）
    private static final float HOLD_DECAY_MS = 140f;

    // ---- 档位 ----
    public static volatile int level = 2;

    /** 每档：(预测上限 ms, 速度平滑, 加速度权重倍数, 过冲倍数)。 */
    private static final float[] LVL_CAP   = {   0f,  50f, 100f, 150f, 220f, 100f };
    private static final float[] LVL_BLEND = {   1f, 0.35f, 0.5f, 0.65f, 0.8f, 1f };
    private static final float[] LVL_ACCW  = {   0f,  0.15f, 0.35f, 0.6f, 0.9f, 0f };
    private static final float[] LVL_OVER  = {   1f, 1.05f, 1.15f, 1.3f, 1.5f, 1.0f };

    public static void setLevel(int lv) {
        if (lv < 0) lv = 0;
        if (lv > 5) lv = 5;
        level = lv;
    }

    public static String levelName(int lv) {
        switch (lv) {
            case 0: return "关";
            case 1: return "弱";
            case 3: return "强";
            case 4: return "极强";
            case 5: return "极速";
            default: return "中";
        }
    }

    public static int levelCount() { return 6; }

    public synchronized void reset() {
        has = false;
        hist = 0;
        for (int i = 0; i < N; i++) { vel[i] = 0f; acc[i] = 0f; step[i] = 0f; }
    }

    /** 收到一个新样本时调用一次（单位 -1..1）。 */
    public synchronized void sample(long nowMs, float a0, float a1, float a2, float a3, float a4, float a5) {
        float[] nv = { a0, a1, a2, a3, a4, a5 };
        long gap = has ? (nowMs - t2) : 0L;
        boolean bridge = gap > 250;                    // 断线重连的窟窿：别拿旧历史插值

        if (bridge || !has) {
            for (int i = 0; i < N; i++) { h0[i] = nv[i]; h1[i] = nv[i]; h2[i] = nv[i]; }
            t0 = nowMs - 100L; t1 = nowMs - 50L; t2 = nowMs;
            hist = 1;
            for (int i = 0; i < N; i++) { vel[i] = 0f; acc[i] = 0f; step[i] = 0f; }
            has = true;
            lastArriveMs = nowMs;
            return;
        }

        // 平移历史
        for (int i = 0; i < N; i++) { h0[i] = h1[i]; h1[i] = h2[i]; h2[i] = nv[i]; }
        t0 = t1; t1 = t2; t2 = nowMs;
        if (hist < 3) hist++;

        float dt1 = Math.max(8f, (float) (t1 - t0));
        float dt2 = Math.max(8f, (float) (t2 - t1));
        float bl = LVL_BLEND[level];

        for (int i = 0; i < N; i++) {
            float d = h2[i] - h1[i];
            step[i] = Math.abs(d);
            float vNow = Math.abs(d) < VEL_DEAD ? 0f : d / dt2;
            // 反向即停：与上一帧速度反向时，速度与加速度一起清零（抽搐的唯一来源）
            boolean reverse = (vel[i] != 0f) && (vNow != 0f) && ((vNow > 0f) != (vel[i] > 0f));
            if (reverse) { vel[i] = 0f; acc[i] = 0f; }
            vel[i] = vel[i] * (1f - bl) + vNow * bl;

            if (hist >= 3) {
                float vPrev = (h1[i] - h0[i]) / dt1;
                float aNow = (vel[i] - vPrev) * 1000f / (0.5f * (dt1 + dt2));   // 单位/秒²
                // 加速度只在同向加速时生效，减速/停顿一律忽略，避免"甩尾"
                boolean sameSign = (aNow > 0f) == (vel[i] > 0f);
                if (!sameSign || Math.abs(vel[i]) < VEL_DEAD) aNow = 0f;
                acc[i] = acc[i] * 0.5f + aNow * 0.5f;
            } else {
                acc[i] = 0f;
            }
        }
        lastArriveMs = nowMs;
    }

    /** 在 nowMs 时刻取值；返回 false 表示还没有样本。 */
    public synchronized boolean value(long nowMs, float[] out) {
        if (!has) return false;
        final float capMs = LVL_CAP[level];
        if (capMs <= 0f) {                     // 档位=关：原始 10Hz 直通，零预测零平滑
            for (int i = 0; i < N; i++) out[i] = h2[i];
            return true;
        }
        float over = LVL_OVER[level];
        float accw = LVL_ACCW[level] * ACC_W;
        float dt = Math.max(0f, (float) (nowMs - t2));
        float ahead = Math.min(dt, capMs);
        float decay = dt <= capMs ? 1f : (float) Math.exp(-(dt - capMs) / HOLD_DECAY_MS);
        float tau = ahead / 1000f;

        for (int i = 0; i < N; i++) {
            float p = h2[i] + vel[i] * ahead + 0.5f * acc[i] * tau * tau * 1000f * accw;
            if (decay < 1f) p = h2[i] + (p - h2[i]) * decay;
            // 过冲钳制：不超过"最近一步 × over"（再加 6% 绝对余量）
            float cap = step[i] * over + 0.06f;
            if (p > h2[i] + cap) p = h2[i] + cap;
            else if (p < h2[i] - cap) p = h2[i] - cap;
            if (p > 1f) p = 1f;
            else if (p < -1f) p = -1f;
            out[i] = p;
        }
        return true;
    }

    /** 当前样本已经"旧"了多少毫秒（给界面显示用）。 */
    public synchronized long lagMs(long nowMs) { return has ? (nowMs - t2) : -1L; }

    /** 实测样本间隔（毫秒）。 */
    public synchronized long intervalMs() { return hist >= 2 ? (t2 - t1) : -1L; }
}
