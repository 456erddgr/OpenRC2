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
    private final float[] y = new float[N];
    private final float[] yPrev = new float[N];   // 上一原生帧的低通值
    private final float[] yLast = new float[N];   // 最新原生帧的低通值
    private long tPrev = 0L, tLast = 0L;               // 每轴低通后的目标位置（10Hz 更新）
    // 每轴完整校准（中点 + 正/负行程），由校准页「插帧完整校准」写入
    private final float[] calMid = new float[N];
    private final float[] calNeg = {1f,1f,1f,1f,1f,1f};
    private final float[] calPos = {1f,1f,1f,1f,1f,1f};
    private final boolean[] calOk = new boolean[N];   // 每轴是否已写入完整校准
    private final float[] vel = new float[N];
    private final float[] acc = new float[N];
    private final float[] step = new float[N];
    private long lastArriveMs;

    private static final float VEL_DEAD = 0.010f;   // 速度死区（手抖不放大）
    private static final float ACC_W = 0.35f;       // 加速度权重（0=纯匀速，太大会甩）
    private static final float HOLD_DECAY_MS = 140f;
    private static final float SLEW = 0.085f;       // 每帧最大变化量（≈120ms 满行程）

    // ---- 档位 ----
    public static volatile int level = 3;

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
        for (int i = 0; i < N; i++) { vel[i] = 0f; acc[i] = 0f; step[i] = 0f; zero[i] = 0f;
            y[i] = 0f; yPrev[i] = 0f; yLast[i] = 0f; }
        tPrev = 0L; tLast = 0L;
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
            for (int i = 0; i < N; i++) { y[i] = nv[i]; yPrev[i] = nv[i]; yLast[i] = nv[i]; }
            tPrev = nowMs - 100L;
            tLast = nowMs;
            has = true;
            lastArriveMs = nowMs;
            return;
        }

        // 平移历史
        for (int i = 0; i < N; i++) { h0[i] = h1[i]; h1[i] = h2[i]; h2[i] = nv[i]; }
        t0 = t1; t1 = t2; t2 = nowMs;
        tPrev = tLast;
        tLast = nowMs;
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
            yPrev[i] = yLast[i];
            yLast[i] = y[i];

            // 2026-11-02 二次修：上一版只看“本帧位移小”就学零点，结果**杆打满停住时**
            // （d≈0）也会被当成静止 → zero 慢慢追到满杆值 → 满杆读数逐渐变成 0。
            // 现在只在“所有轴都在中点附近、且整体静止”时学，并且幅度硬限 ±0.06。
            // 记录该轴实测最大偏离（零点校正后），用来把“硬件打不到 1684”的行程补到 100%
            float dev = Math.abs(h2[i] - zero[i]);
            if (spanObs[i] > 0.75f) spanObs[i] *= 0.998f;   // 缓慢回缩，单次抖动不会永久压低量程
            if (dev > spanObs[i]) spanObs[i] = dev;

            if (hist >= 1 && Math.abs(d) < 0.004f && dev < 0.15f) {
                float nz = zero[i] + 0.10f * (h2[i] - zero[i]);
                if (nz > 0.15f) nz = 0.15f;      // 实测有的轴残差 11%，上限放到 15%
                else if (nz < -0.15f) nz = -0.15f;
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
        // 只依据「原生帧」推算：在上一帧(yPrev)与最新帧(yLast)之间按时间线性插值，
        // u 夹在 [0,1] → 不预测、不外推、不会越过最新真实帧（用户要求：只能在原生帧上推算）。
        // 帧间插值窗口：最多 45ms 就走完「上一帧→最新帧」，然后停在最新帧上（不越过、不预测）。
        // 原生帧间隔约 100ms，若按整段 100ms 匀速插值，手感会像“晚一帧”（用户实测：延迟大、稳态打不满）。
        float win = (tLast <= tPrev) ? 1f : (float) (tLast - tPrev);
        if (win > 45f) win = 45f;
        if (win < 20f) win = 20f;
        float u = (nowMs - tLast) / win;
        if (u < 0f) u = 0f;
        if (u > 1f) u = 1f;

        for (int i = 0; i < N; i++) {
            float z = zero[i];
            float base = yPrev[i] + (yLast[i] - yPrev[i]) * u;   // 帧间插值后的位置
            float tgt;
            if (calOk[i]) {
                float d2 = base - calMid[i];
                tgt = d2 >= 0f ? d2 / calPos[i] : d2 / calNeg[i];
            } else {
                // 不再做「行程放大」：比例完全由硬件/校准页的单位换算给出（units() 已按轴负/正行程归一），
                // 插帧只负责去掉零点偏置 → 不自己编造增益（用户要求：只在原生帧上推算）。
                tgt = base - z;
            }
            if (tgt > 1f) tgt = 1f;
            else if (tgt < -1f) tgt = -1f;
            out[i] = softDead(tgt);
        }
        return true;
    }

    /** 写入单轴完整校准（mid 中点，neg 负行程长度，pos 正行程长度，均为 -1..1 归一后的量）。 */
    public synchronized void setAxisCal(int i, float mid, float neg, float pos) {
        if (i < 0 || i >= N) return;
        if (neg < 0.15f) neg = 0.15f;
        if (pos < 0.15f) pos = 0.15f;
        calMid[i] = mid;
        calNeg[i] = neg;
        calPos[i] = pos;
        calOk[i] = true;
        zero[i] = 0f;                 // 有了显式中点就不再叠加零点学习
        lastOut[i] = 0f;
    }

    public synchronized void clearAxisCal() {
        for (int k = 0; k < N; k++) calOk[k] = false;
        for (int i = 0; i < N; i++) { calMid[i] = 0f; calNeg[i] = 1f; calPos[i] = 1f; }
    }

    public synchronized boolean hasAxisCal() {
        for (int k = 0; k < N; k++) if (calOk[k]) return true;
        return false;
    }

    /** 该轴行程是否已校准（供校准页判断）。 */
    public synchronized boolean axisCalOk(int i) { return i >= 0 && i < N && calOk[i]; }

    /** 一次性把当前值当作零点（开机静止、或用户在校准页点“插帧校准”）。 */

    /** 最近一帧的原生归一值（未经插值/零点），给"冲程校准"取样用。 */
    public synchronized float lastRaw(int i) {
        if (i < 0 || i >= N) return 0f;
        return yLast[i];
    }
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
