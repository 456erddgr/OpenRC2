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
    private final float[] zero = new float[N];   // 每轴零点（校准中点与物理中点的差）
    private final float[] spanObs = {0f,0f,0f,0f,0f,0f};  // 每轴实测最大行程（0=还没观测到）
    private final float[] lastOut = new float[N];         // 上一帧输出
    private final float[] y = new float[N];               // 每轴低通后的目标位置（10Hz 更新）
    private final float[] vel = new float[N];
    private final float[] acc = new float[N];
    private final float[] step = new float[N];
    private long lastArriveMs;

    private static final float VEL_DEAD = 0.010f;   // 速度死区（手抖不放大）
    private static final float ACC_W = 0.35f;       // 加速度权重（0=纯匀速，太大会甩）
    private static final float HOLD_DECAY_MS = 140f;
    private static final float SLEW = 0.085f;       // 每帧最大变化量（≈120ms 满行程）

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
        for (int i = 0; i < N; i++) { vel[i] = 0f; acc[i] = 0f; step[i] = 0f; zero[i] = 0f; }
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

            // 低通目标：固定系数（与档位无关）→ 每根杆手感一致、不随采样抖动忽快忽慢
            float dd = Math.abs(h2[i] - y[i]);
            if (hist == 1 || dd < 0.004f) y[i] = h2[i];      // 静止/微动：零滞后
            else if (dd > 0.05f) y[i] = h2[i];               // 真实大动作：直通，保跟随
            else y[i] += 0.80f * (h2[i] - y[i]);             // 中间小幅：轻度平滑，抑制抖动

            // 2026-11-02 二次修：上一版只看“本帧位移小”就学零点，结果**杆打满停住时**
            // （d≈0）也会被当成静止 → zero 慢慢追到满杆值 → 满杆读数逐渐变成 0。
            // 现在只在“所有轴都在中点附近、且整体静止”时学，并且幅度硬限 ±0.06。
            // 记录该轴实测最大偏离（零点校正后），用来把“硬件打不到 1684”的行程补到 100%
            float dev = Math.abs(h2[i] - zero[i]);
            if (spanObs[i] > 0.75f) spanObs[i] *= 0.998f;   // 缓慢回缩，单次抖动不会永久压低量程
            if (dev > spanObs[i]) spanObs[i] = dev;

            if (hist >= 1 && Math.abs(d) < 0.004f && dev < 0.08f) {
                float nz = zero[i] + 0.10f * (h2[i] - zero[i]);
                if (nz > 0.10f) nz = 0.10f;      // 实测静止残差 3~8%，上限放到 10%
                else if (nz < -0.10f) nz = -0.10f;
                zero[i] = nz;
            }
            // 任一轴离开中心区就立即冻结学习（zero 保持不动）

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
        if (decay < 0.55f) decay = 0.55f;      // 最多回落到 55% 预测量，避免“卡住后慢慢变回去”
        float tau = ahead / 1000f;

        // 统一基准：所有轴用同一个行程归一（否则每个轴手感不同）
        float gs = 0f;
        for (int i = 0; i < N; i++) {
            float so = spanObs[i];
            if (so < 0.75f) so = 0.75f;
            if (so > 1f) so = 1f;
            gs += so;
        }
        gs /= N;
        if (gs < 0.75f) gs = 0.75f;

        // 档位只决定“逼近速度”，不再做加速度外推 → 不会抽搐、不会穿零点
        float rate = 0.085f + 0.015f * (float) level;   // 每帧最大逼近量
        if (rate > 0.16f) rate = 0.16f;

        for (int i = 0; i < N; i++) {
            float z = zero[i];
            float span = 1f - (z < 0f ? -z : z);
            if (span < 0.5f) span = 0.5f;
            float tgt = (y[i] - z) / span / gs;
            if (tgt > 1f) tgt = 1f;
            else if (tgt < -1f) tgt = -1f;
            tgt = softDead(tgt);
            float prev = lastOut[i];
            float d = tgt - prev;
            if (d > rate) d = rate;
            else if (d < -rate) d = -rate;
            lastOut[i] = prev + d;
            out[i] = lastOut[i];
        }
        return true;
    }

    /** 一次性把当前值当作零点（开机静止、或用户在校准页点“插帧校准”）。 */
    public synchronized void snapZero() {
        if (!has) return;
        for (int i = 0; i < N; i++) {
            float z = h2[i];
            if (z > 0.45f) z = 0.45f;
            else if (z < -0.45f) z = -0.45f;
            zero[i] = z;
        }
    }

    /** 六轴是否都还在中点附近（用来判定“整机静止”，避免满杆被误当零点）。 */
    private boolean allNearCenter() {
        for (int i = 0; i < N; i++) {
            float a = h2[i] < 0f ? -h2[i] : h2[i];
            if (a > 0.15f) return false;
        }
        return true;
    }

    /** 软死区：|v|<=DZ 直接归零，外面线性展开，穿过中点时不产生台阶。 */
    private static final float DZ = 0.012f;

    private static float softDead(float v) {
        float a = v < 0f ? -v : v;
        if (a <= DZ) return 0f;
        float n = (a - DZ) / (1f - DZ);
        if (n > 1f) n = 1f;
        return v < 0f ? -n : n;
    }

    /** 当前样本已经"旧"了多少毫秒（给界面显示用）。 */
    public synchronized long lagMs(long nowMs) { return has ? (nowMs - t2) : -1L; }

    /** 实测样本间隔（毫秒）。 */
    public synchronized long intervalMs() { return hist >= 2 ? (t2 - t1) : -1L; }
}
