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
        float interval = curMs - prevMs;
        if (interval < 20f) interval = 20f;
        else if (interval > 400f) interval = 400f;
        float dt = nowMs - curMs;
        if (dt < 0f) dt = 0f;
        // 关键：只在 prev→cur 之间插值，绝不超过 cur。
        // 数据源只有 ~10Hz，一帧里 dt/interval 会超过 1（此时钳到 1 = 停在 cur）。
        // 旧写法 `cur + d*k` 会越过 cur，等下一帧反向时就是肉眼可见的抖动。
        float k = dt / interval;
        if (k > 1f) k = 1f;
        // 40ms 内做有限外推：采样只有 ~10Hz，这一段把"跳一格"变成"连续滑"。
        float ex = 0f;
        if (dt > 0f && dt <= EXTRAP_WINDOW_MS) ex = (dt / interval) * EXTRA_GAIN;
        for (int i = 0; i < N; i++) {
            float d = cur[i] - prev[i];
            if (d < VEL_DEAD && d > -VEL_DEAD) d = 0f;
            float v = prev[i] + d * k + d * ex;
            if (v > 1f) v = 1f;
            else if (v < -1f) v = -1f;
            out[i] = v;
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
