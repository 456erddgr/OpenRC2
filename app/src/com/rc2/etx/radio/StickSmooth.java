package com.rc2.etx.radio;

/**
 * 机内摇杆(DUMl 06/AE)只有 ~9.6Hz，直接喂 100Hz 控制环会让手感一顿一顿。
 * 这里做两件事：
 *  1) 线性插值 —— 在两个真实样本之间按时间比例给出中间值；
 *  2) 有限外推 —— 只往前推一个采样周期，且按速度阻尼，避免过冲抖动。
 * 结果：等效输入延迟从"最多一个采样周期"降到"约半个采样周期"。
 */
public final class StickSmooth {
    public static final int N = 6;

    private final float[] prev = new float[N];
    private final float[] cur = new float[N];
    private long prevMs, curMs;
    private boolean has;
    private long samples;
    private long lastArriveMs;

    /** 外推增益：1.0 = 完全线性外推，0.0 = 不外推。0.8 折中。 */
    /** 外推增益。0 = 纯粹在上一帧与本帧之间插值（不过冲）。
     *  以前 0.8 会把"本帧 - 上帧"再多推 80%，新样本一到就猛地弹回 —— 这就是"抽搐"。 */
    private static final float EXTRA_GAIN = 0.35f;

    // ---- 10Hz → 100Hz 的预测插值参数（1.39.0）----
    /** 每轴速度（单位/ms），由连续两帧差分得到，带一阶平滑。 */
    private final float[] vel = new float[N];
    /** 最近一步的步长（单位），用来限制预测的过冲幅度。 */
    private final float[] step = new float[N];
    /** 预测上限：超过这个时间不再往前推（10Hz 一帧 100ms，推 90ms 已到下一帧门口）。 */
    private static final float PRED_CAP_MS = 90f;
    /** 超过 PRED_CAP 后的衰减时间常数：从"冻结的预测值"缓慢回到真实值，避免突然定住。 */
    private static final float HOLD_DECAY_MS = 140f;
    /** 允许的最大过冲 = 最近一步的 1.15 倍（快速满杆时也不越过端点）。 */
    private static final float OVERSHOOT = 1.15f;
    /** 速度平滑：新速度权重（0.4 = 响应快但抖，0.2 = 稳但钝）。 */
    private static final float VEL_BLEND = 0.45f;
    /** 外推窗口：样本到达后只在这个时间内外推，超过就锁住当前值（防过冲）。 */
    private static final float EXTRAP_WINDOW_MS = 40f;
    /** 速度死区：两次样本差小于这个值就当作没动，防止手抖被放大。 */
    private static final float VEL_DEAD = 0.012f;

    public synchronized void reset() {
        has = false;
        samples = 0;
    }

    public synchronized long samples() {
        return samples;
    }

    /** 收到一个新样本时调用一次（单位 -1..1）。 */
    public synchronized void sample(long nowMs, float a0, float a1, float a2, float a3, float a4, float a5) {
        // 断线重连会产生 100~500ms 的空档。若沿用旧 prev，新样本一到就会
        // 从"旧位置"插值过来，看起来像猛地弹一下（用户反馈的"卡住"）。
        // 空档超过 250ms 时，把 prev 设成本次 cur，直接从新位置起步。
        long gap = (has && curMs > 0) ? (nowMs - curMs) : 0;
        boolean bridge = gap > 250;
        if (!bridge) {
            for (int i = 0; i < N; i++) prev[i] = cur[i];
            prevMs = curMs;
        }
        float[] nv = { a0, a1, a2, a3, a4, a5 };
        // 先算速度，再写 cur：dt 用 prev→cur 的真实间隔
        long dtMs = Math.max(8L, nowMs - prevMs);
        for (int i = 0; i < N; i++) {
            float d = nv[i] - cur[i];
            step[i] = Math.abs(d);
            float vNow = d / dtMs;
            if (Math.abs(d) < VEL_DEAD) vNow = 0f;
            // 方向反转：立刻停住预测，并直接把 old vel 归零（这是"抽搐"的唯一来源）
            if (vel[i] != 0f && vNow != 0f && (vNow > 0f) != (vel[i] > 0f)) vNow = 0f;
            vel[i] = (has && !bridge) ? (vel[i] * (1f - VEL_BLEND) + vNow * VEL_BLEND) : vNow;
        }
        cur[0] = a0; cur[1] = a1; cur[2] = a2; cur[3] = a3; cur[4] = a4; cur[5] = a5;
        curMs = nowMs;
        lastArriveMs = nowMs;
        if (!has || bridge) {
            for (int i = 0; i < N; i++) prev[i] = cur[i];
            prevMs = nowMs - 100L;
            has = true;
        }
        samples++;
    }

    /** 在 nowMs 时刻取值；返回 false 表示还没有样本。 */
    public synchronized boolean value(long nowMs, float[] out) {
        if (!has) return false;
        float dt = nowMs - curMs;
        if (dt < 0f) dt = 0f;
        // 预测的时间量：只推 PRED_CAP_MS，再往后按指数衰减回真实值
        float ahead = dt <= PRED_CAP_MS ? dt : PRED_CAP_MS;
        float decay = 1f;
        if (dt > PRED_CAP_MS) {
            decay = (float) Math.exp(-(dt - PRED_CAP_MS) / HOLD_DECAY_MS);
        }
        for (int i = 0; i < N; i++) {
            float p = cur[i] + vel[i] * ahead * decay;
            // 过冲限制：预测点离 cur 不允许超过"最近一步 × OVERSHOOT"（再加 6% 的绝对余量防死区）
            float cap = step[i] * OVERSHOOT + 0.06f;
            float lo = cur[i] - cap, hi = cur[i] + cap;
            if (p < lo) p = lo;
            else if (p > hi) p = hi;
            if (p > 1f) p = 1f;
            else if (p < -1f) p = -1f;
            out[i] = p;
        }
        return true;
    }

    /** 当前样本已经"旧"了多少毫秒（给界面显示用）。 */
    public synchronized long lagMs(long nowMs) {
        if (!has) return -1L;
        return nowMs - curMs;
    }

    /** 实测样本间隔（毫秒）。 */
    public synchronized long intervalMs() {
        if (!has) return -1L;
        return curMs - prevMs;
    }
}
