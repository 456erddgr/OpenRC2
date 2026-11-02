package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.rc2.etx.radio.Calib;
import com.rc2.etx.radio.Radio;

/**
 * 校准页 v2（反复重写版，不复用任何旧校准代码）。
 *
 * 设计要点（按用户口径）：
 *  1. 左右摇杆都有独立圆盘，都能看、都能校；
 *  2. 「推满」不是死摁一个位置，而是让用户**来回转圈**，系统在转圈过程中
 *     记每根轴真正能到多大，用它当 100%（这样 RC Pro 那类行程偏小的机器
 *     也能显示满 100%）；
 *  3. 每根轴只要出现过一次 >= 0.90 的峰值就算这根“够到了”；
 *  4. 最后四根全部回中保持 1.5 秒才算完成并落盘。
 */
public class CalibPage extends Panel {

    private static final String[] NAME = {"油门", "副翼", "俯仰", "偏航"};
    private static final float PEAK = 0.90f;      // 视为“够到满行程”的阈值
    private static final float CEN = 0.08f;
    private static final long CEN_MS = 1500L;
    private static final long MID_MS = 900L;
    private static final int PEAKS = 2;           // 每根轴至少出现 2 次峰值

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int step;            // 0 采中点；1 认轴；2 转圈采满；3 回中；4 完成
    private boolean running;
    private long t0, cenT;
    private final int[] bind = {-1, -1, -1, -1};
    private final float[] peak = new float[4];
    private final int[] hits = new int[4];
    private final RectF btnA = new RectF(), btnB = new RectF(), btnC = new RectF();
    private final double[] cenSum = new double[10];
    private int cenN;
    private float[] testRaw;   // 仅测试：合成摇杆数据
    private String hint = "点「开始校准」";
    private boolean snapArmed = false;
    // 冲程校准：0 空闲；1 采集中；2 已完成
    private int tcal;
    private long tcalT0;
    private final float[] tmin = new float[6];
    private final float[] tmax = new float[6];

    public CalibPage(Radio r) { super(r); }
    @Override public String title() { return "校准"; }

    public void begin() {
        Calib c = radio.calib;
        c.enabled = false;
        for (int i = 0; i < 4; i++) { bind[i] = -1; peak[i] = 0; hits[i] = 0; }
        step = 0; running = true; t0 = System.currentTimeMillis(); cenT = 0;
        for (int i = 0; i < 10; i++) cenSum[i] = 0; cenN = 0;
        hint = "静置 1 秒采中点…";
    }

    public void cancel() { running = false; hint = "已取消"; }

    private float ax(int a) {
        float v = testRaw != null ? testRaw[a] : radio.calib.raw[a];
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    @Override public void tick() {
        if (!running) return;
        long now = System.currentTimeMillis();
        Calib c = radio.calib;

        if (step == 0) {
            if (now - t0 >= MID_MS) {
                for (int i = 0; i < 10; i++) c.mid[i] = c.raw[i];
                step = 1; hint = "推一下「" + NAME[0] + "」那根杆（随便推一下，自动认轴）";
            }
            return;
        }

        if (step == 1) {
            int slot = 0;
            for (int i = 0; i < 4; i++) if (bind[i] < 0) { slot = i; break; }
            int best = -1; float bd = 0.35f;
            for (int a = 0; a < 4; a++) {
                boolean used = false;
                for (int i = 0; i < 4; i++) if (bind[i] == a) used = true;
                if (used) continue;
                float d = Math.abs(ax(a) - c.mid[a]);
                if (d > bd) { bd = d; best = a; }
            }
            if (best >= 0) {
                bind[slot] = best;
                if (slot == 3) { step = 2; hint = "两根摇杆各转三圈，把每个方向都推到顶"; }
                else hint = "已认「" + NAME[slot] + "」→ 再推「" + NAME[slot + 1] + "」那根";
            }
            return;
        }

        if (step == 2) {
            boolean allDone = true;
            for (int s = 0; s < 4; s++) {
                int a = bind[s];
                if (a < 0) continue;
                float v = Math.abs(ax(a) - c.mid[a]);
                if (v > peak[s]) peak[s] = v;
                if (v >= PEAK) hits[s]++;
                if (peak[s] < PEAK || hits[s] < PEAKS) allDone = false;
            }
            if (allDone) { step = 3; cenT = 0; hint = "四根都够到顶了，全部松手回中"; }
            return;
        }

        if (step == 3) {
            boolean centered = true;
            for (int s = 0; s < 4; s++) {
                int a = bind[s];
                if (a >= 0 && Math.abs(ax(a) - c.mid[a]) >= CEN) centered = false;
            }
            if (centered) {
                if (cenT == 0) cenT = now;
                // 审计修复：以前这里对全部 10 轴取平均并回写 c.mid[]，
                // 旋钮/拨盘常年停在 -1/1，被平均成 -1 后 EdgeTX 就有一个通道卡在 -1
                // —— 正是用户说的「回中后还有一个是 -1」。只累计已绑定的四根轴。
                for (int s = 0; s < 4; s++) {
                    int a = bind[s];
                    if (a >= 0) cenSum[a] += c.raw[a];
                }
                cenN++;
                if (now - cenT >= CEN_MS) finish();
            } else cenT = 0;
        }
    }


    /** 仅测试：跳过物理推杆，用给定峰值直接完成一次校准（验证落盘与同步）。 */
    public void forceFinishForTest(float m) {
        Calib c = radio.calib;
        int[] auto = {1, 2, 3, 0};
        for (int s0 = 0; s0 < 4; s0++) c.mid[auto[s0]] = c.raw[auto[s0]];   // 只写四根轴      // 油门/LY 副翼/RX 俯仰/RY 偏航/LX 的常见绑定
        for (int s = 0; s < 4; s++) { bind[s] = auto[s]; peak[s] = m; hits[s] = PEAKS; }
        finish();
    }


    /** 仅测试：注入合成摇杆值，用来驱动 0..9 状态机截图验证。 */
    public void setTestRaw(float[] v) { testRaw = v; }

    private void finish() {
        Calib c = radio.calib;
        for (int s = 0; s < 4; s++) {
            int a = bind[s];
            if (a < 0) continue;
            float m = Math.max(0.55f, peak[s]);        // 用实测峰值当满行程（下限防饱和）
            c.min[a] = -m; c.max[a] = m;
            c.mid[a] = cenN > 0 ? (float) (cenSum[a] / cenN) : c.raw[a];
            c.reverse[a] = false;
            c.moved[a] = true;
        }
        // 只写已绑定轴的中位（其余轴保持出厂值，避免把旋钮写成 -1）
        c.enabled = true;
        // 同步进核心：①缩小 raw 行程，让实测峰值成为新的满行程（RC Pro 推不满的根因）
        // ②重设输入缓存，让 EdgeTX 立刻用上新中位/新行程
        int[] mcu = {3, 2, 0, 1};      // calib 轴 LX/LY/RX/RY -> MCU 通道 LH/LV/RH/RV
        for (int s = 0; s < 4; s++) {
            int a = bind[s];
            if (a < 0 || a > 3) continue;
            radio.duml.scaleSpanToPeak(mcu[a], Math.max(0.55f, peak[s]));
        }
        radio.input.reset();
        radio.save();
        step = 4; running = false;
        hint = "校准完成：100% = 你实际推到的最大行程";
    }

    @Override public void draw(Canvas c, Ui ui, RectF a) {
        float w = a.width(), h = a.height();
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF0E1319);
        c.drawRect(a, p);

        // 四轴卡
        float pad = w * 0.02f, cw = (w - pad * 5) / 4f, cy = a.top + h * 0.02f, ch = h * 0.105f;
        for (int i = 0; i < 4; i++) {
            float x = a.left + pad + i * (cw + pad);
            RectF r = new RectF(x, cy, x + cw, cy + ch);
            boolean done = step >= 3 || (step == 2 && hits[i] >= PEAKS && peak[i] >= PEAK);
            boolean bnd = bind[i] >= 0;
            p.setColor(done ? 0xFF17351F : 0xFF151A22);
            c.drawRoundRect(r, h * 0.02f, h * 0.02f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(h * 0.004f);
            p.setColor(done ? 0xFF3DDC84 : (bnd ? 0xFFF5B942 : 0xFF2A3442));
            c.drawRoundRect(r, h * 0.02f, h * 0.02f, p); p.setStyle(Paint.Style.FILL);
            txt(c, NAME[i], r.centerX(), r.top + ch * 0.40f, h * 0.042f,
                    bnd ? 0xFFF2F5F8 : 0xFF8A97A6, true);
            String sub = done ? "满行程 ✓" : (bnd ? String.format(java.util.Locale.US, "%d%%", (int) (peak[i] * 100)) : "未绑定");
            txt(c, sub, r.centerX(), r.top + ch * 0.78f, h * 0.030f,
                    done ? 0xFF3DDC84 : 0xFF8A97A6, false);
            // 进度条：本轴到达峰值比例
            RectF pr = new RectF(r.left + r.width() * 0.06f, r.bottom - ch * 0.26f,
                    r.left + r.width() * 0.06f + r.width() * 0.88f * Math.min(1f, peak[i] / PEAK),
                    r.bottom - ch * 0.18f);
            p.setColor(done ? 0xFF3DDC84 : 0xFFF5B942);
            c.drawRoundRect(pr, pr.height() * 0.5f, pr.height() * 0.5f, p);
        }

        // 两个圆盘（左 LY/LX，右 RY/RX）
        float rad = Math.min(w, h) * 0.115f;
        float ly = a.top + h * 0.47f;
        disk(c, a.left + w * 0.30f, ly, rad, 1, 0, "左摇杆");
        disk(c, a.left + w * 0.70f, ly, rad, 3, 2, "右摇杆");

        // 文案
        String big = step <= 1 ? "第 1 步 · 认轴" : (step == 2 ? "第 2 步 · 转圈推到顶" : (step == 3 ? "第 3 步 · 全部回中" : "已完成"));
        txt(c, big, a.centerX(), a.top + h * 0.19f, h * 0.050f, 0xFFF5B942, true);
        txt(c, hint, a.centerX(), a.top + h * 0.255f, h * 0.034f, 0xFFF2F5F8, false);
        if (step == 3 && cenT > 0) {
            txt(c, String.format(java.util.Locale.US, "%.1f / 1.5 秒", (System.currentTimeMillis() - cenT) / 1000f),
                    a.centerX(), a.top + h * 0.335f, h * 0.045f, 0xFF3DDC84, true);
        }
        if (step == 2) {
            txt(c, String.format(java.util.Locale.US, "LX %+.2f  LY %+.2f    RX %+.2f  RY %+.2f",
                    ax(0), ax(1), ax(2), ax(3)),
                    a.centerX(), a.top + h * 0.60f, h * 0.032f, 0xFF8A97A6, false);
        }

        pumpTcal();

        // 按钮
        float bw = w * 0.28f, bh = h * 0.10f, by = a.top + h * 0.80f;
        btnA.set(a.centerX() - bw - w * 0.02f, by, a.centerX() - w * 0.02f, by + bh);
        btnB.set(a.centerX() + w * 0.02f, by, a.centerX() + bw + w * 0.02f, by + bh);
        btn(c, btnA, running ? "重新开始" : (step == 4 ? "再校一次" : "开始校准"), true, h);
        btn(c, btnB, "取消", false, h);

        // 插帧校准：把当前姿态锁成插帧零点（用户要求“校准里带插帧校准”）
        btnC.set(a.centerX() - bw * 0.95f, by - bh * 1.45f, a.centerX() + bw * 0.95f, by - bh * 0.45f);
        btn(c, btnC, tcal == 1 ? "采集中…把每根杆都推到顶" : (tcal == 2 ? "冲程校准完成 ✓（点此重做）" : "冲程校准（推满即当 100%）"), true, h);
    }

    private void disk(Canvas c, float cx, float cy, float rad, int axV, int axH, String label) {
        Calib cal = radio.calib;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(rad * 0.03f); p.setColor(0xFF2A3442);
        c.drawCircle(cx, cy, rad, p);
        c.drawCircle(cx, cy, rad * 0.5f, p);
        c.drawLine(cx - rad, cy, cx + rad, cy, p);
        c.drawLine(cx, cy - rad, cx, cy + rad, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFF5B942);
        c.drawCircle(cx + ax(axH) * rad, cy - ax(axV) * rad, rad * 0.14f, p);
        p.setColor(0xFF8A97A6); p.setTextSize(rad * 0.30f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, cx, cy + rad * 1.55f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 冲程校准：8 秒内记录每轴 min/max，结束时把实测满杆写成一轴的 100%。 */
    private void pumpTcal() {
        if (tcal != 1) return;
        com.rc2.etx.radio.StickSmooth sm = radio.stickSmooth();
        for (int i = 0; i < 6; i++) {
            float v = sm.lastRaw(i);
            if (v < tmin[i]) tmin[i] = v;
            if (v > tmax[i]) tmax[i] = v;
        }
        long el = System.currentTimeMillis() - tcalT0;
        if (el < 8000) return;
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            float lo = tmin[i] > 8f ? 0f : tmin[i];
            float hi = tmax[i] < -8f ? 0f : tmax[i];
            // 必须真的推过半程才认，否则静止时的微小抖动会被当成满行程，增益炸掉
            if (hi - lo < 0.6f) continue;
            ok++;
            float mid = (lo + hi) * 0.5f;
            float neg = mid - lo;
            float pos = hi - mid;
            if (neg > 1.6f) neg = 1.6f;
            if (pos > 1.6f) pos = 1.6f;
            sm.setAxisCal(i, mid, neg, pos);
        }
        tcal = 2;
        hint = ok == 0 ? "没检测到推杆：请把每根杆推到四角再来一次"
                : ("冲程校准已写入（" + ok + " 轴，实测满杆=100%）");
    }

    private void btn(Canvas c, RectF r, String s, boolean primary, float h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(primary ? 0xFFF5B942 : 0xFF1B2430);
        c.drawRoundRect(r, h * 0.02f, h * 0.02f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(h * 0.004f);
        p.setColor(primary ? 0xFFF5B942 : 0xFF39506B);
        c.drawRoundRect(r, h * 0.02f, h * 0.02f, p);
        p.setStyle(Paint.Style.FILL);
        txt(c, s, r.centerX(), r.centerY() + h * 0.018f, h * 0.042f,
                primary ? 0xFF101720 : 0xFFD6DEE8, true);
    }

    @Override public boolean down(int id, float x, float y) {
        if (btnC.contains(x, y)) {
            if (tcal == 1) return true;          // 采集期间不重入
            tcal = 1;
            tcalT0 = System.currentTimeMillis();
            for (int i = 0; i < 6; i++) { tmin[i] = 9f; tmax[i] = -9f; }
            hint = "把每根杆都推到四角，8 秒后自动完成";
            return true;
        }
        if (btnA.contains(x, y)) { begin(); return true; }
        if (btnB.contains(x, y)) { cancel(); return true; }
        return false;
    }

    private void txt(Canvas c, String s, float x, float y, float sp, int col, boolean bold) {
        p.setTextSize(sp); p.setColor(col); p.setFakeBoldText(bold);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);
    }
}
