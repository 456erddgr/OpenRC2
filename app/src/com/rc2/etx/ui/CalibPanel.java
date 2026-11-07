package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.rc2.etx.radio.Calib;
import com.rc2.etx.radio.Radio;

/**
 * 推杆校准（向导式）。
 *
 * 不再让人对着 10 行数字按按钮，而是像正经遥控器那样：
 * 屏幕上画一个摇杆示意 + 箭头脉冲动画，提示"把左摇杆推到最左"，
 * 你推到位并停住 150ms，它自己采样 min/max 并跳到下一步；
 * 四根轴（左横/左竖/右横/右竖）的 min/mid/max 全部采完，自动保存。
 */
public final class CalibPanel extends Panel {

    private static final int AX_LX = 0, AX_LY = 1, AX_RX = 2, AX_RY = 3;
    private static final String[] AX_NAME = {"左摇杆 左右", "左摇杆 上下", "右摇杆 左右", "右摇杆 上下"};

    /** 每个步骤要么采中点，要么采某根轴的一个极端。 */
    private static final class Step {
        final int axis;        // -1 = 采中点
        final int dir;         // -1 采最小, +1 采最大, 0 采中点
        final String text;
        Step(int axis, int dir, String text) { this.axis = axis; this.dir = dir; this.text = text; }
    }

    private final Radio r;
    private final Step[] steps;
    private final float[] midRaw = new float[4];
    private final float[] mnRaw = new float[4];
    private final float[] mxRaw = new float[4];
    private boolean[] has;

    private volatile int idx;        // 当前步骤
    private volatile boolean running;
    private volatile long holdStart;
    private volatile boolean done;
    private volatile String hint = "点「开始校准」：推哪根杆就自动绑哪根";
    // 自动校准：order = 绑定顺序（0油门 1副翼 2俯仰 3偏航），bind[slot] = 物理轴号
    private static final String[] SLOT_NAME = {"油门", "副翼", "俯仰", "偏航"};
    private final int[] bind = {-1, -1, -1, -1};
    private int slot;            // 当前要绑的槽位
    private final long[] holdMs = new long[4];
    private long centerMs;
    private int phase;           // 0=采中点 1=自动绑定 2=满行程测试
    private long phaseT0;
    private int curAxis = -1, curDir = 0;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF btnStart, btnCancel, box;

    public CalibPanel(Radio radio) {
        super(radio);
        r = radio;
        steps = new Step[]{
                new Step(-1, 0, "所有摇杆回中，别碰"),
                new Step(AX_LX, -1, "左摇杆推到最左"),
                new Step(AX_LX, +1, "左摇杆推到最右"),
                new Step(AX_LY, -1, "左摇杆拉到最下（油门最低）"),
                new Step(AX_LY, +1, "左摇杆推到最上（油门最高）"),
                new Step(AX_RX, -1, "右摇杆推到最左"),
                new Step(AX_RX, +1, "右摇杆推到最右"),
                new Step(AX_RY, -1, "右摇杆拉到最下"),
                new Step(AX_RY, +1, "右摇杆推到最上"),
        };
        has = new boolean[steps.length];
    }

    @Override public String title() { return "校准"; }

    private static final float DP = 2.5f;   // 320dpi -> 2.5x

    private static float d(float v) { return v * DP; }

    @Override public void draw(Canvas c, Ui ui, RectF a) { layout2(a); drawBody(c, ui, a); drawAuto(c, ui, a); }

    private void layout2(RectF a) {
        float w = a.width(), h = a.height();
        float bw = Math.min(w * 0.30f, d(220)), bh = d(52);
        float y = a.bottom - d(20) - bh;
        btnStart = new RectF(a.left + d(24), y, a.left + d(24) + bw, y + bh);
        btnCancel = new RectF(a.right - d(24) - bw, y, a.right - d(24), y + bh);
        box = new RectF(a.left + d(24), a.top + d(60), a.right - d(24), y - d(20));
    }

    /** 供 adb 钩子/自动化调用。 */
    public void begin() { start(); }

    public boolean isRunning() { return running; }

    private void start() {
        for (int i = 0; i < 4; i++) { midRaw[i] = 0; mnRaw[i] = 9f; mxRaw[i] = -9f; }
        has = new boolean[steps.length];
        idx = 0; running = true; done = false; holdStart = 0;
        for (int i = 0; i < 4; i++) bind[i] = -1;
        slot = 0; phase = 0; phaseT0 = System.currentTimeMillis();
        curAxis = -1; curDir = 0;
        hint = "松手、所有摇杆回中（1 秒）";
    }

    private void finish() {
        Calib c = r.calib;
        for (int ax = 0; ax < 4; ax++) {
            c.mid[ax] = midRaw[ax];
            c.min[ax] = Math.min(-0.999f, mnRaw[ax] - (midRaw[ax] - mnRaw[ax]) * 0.02f);
            c.max[ax] = Math.max(0.999f, mxRaw[ax] + (mxRaw[ax] - midRaw[ax]) * 0.02f);
        }
        c.enabled = true;
        r.save();
        running = false; done = true;
        hint = "校准完成并已保存";
        android.util.Log.i("ETX-Main", "calib done");
    }

    @Override public void tick() {
        if (!running) return;
        Calib c = r.calib;
        long now = System.currentTimeMillis();
        if (phase == 0) {                       // 采中点
            for (int ax = 0; ax < 4; ax++) midRaw[ax] += (c.raw[ax] - midRaw[ax]) * 0.15f;
            if (now - phaseT0 > 900) {
                phase = 1; phaseT0 = now;
                hint = "推「" + SLOT_NAME[0] + "」杆到任意一边（自动绑定第 1/4 根）";
            }
            return;
        }
        if (phase == 1) {                       // 自动绑定：哪个轴动得最多就绑它
            int best = -1; float bestD = 0;
            for (int ax = 0; ax < 4; ax++) {
                if (isBound(ax)) continue;
                float dd = Math.abs(c.raw[ax] - midRaw[ax]);
                if (dd > bestD) { bestD = dd; best = ax; }
            }
            if (best >= 0 && bestD > 0.35f) {
                bind[slot] = best;
                curAxis = best;
                // 方向不用问：min/max 都采，反向会在满行程阶段自然被写进 min/max
                curDir = c.raw[best] > midRaw[best] ? 1 : -1;
                mnRaw[best] = Math.min(mnRaw[best], c.raw[best]);
                mxRaw[best] = Math.max(mxRaw[best], c.raw[best]);
                slot++;
                if (slot >= 4) {
                    phase = 2; phaseT0 = now;
                    hint = "已绑定四轴。现在把每根杆都打满、乱摇 5 秒";
                } else {
                    hint = "已绑「" + SLOT_NAME[slot - 1] + "」→ 再推「" + SLOT_NAME[slot] + "」";
                }
            }
            return;
        }
        // phase 2：满行程采集 —— 每根杆都要"顶到满行程并保持 2 秒"，逐根计数
        float[] v = new float[6];
        for (int ax = 0; ax < 4; ax++) {
            // c.raw 已经是 -1..1 归一化值，直接用差值判定行程；
            // 早前在这里又除了一次 0.66，导致"推到底也到不了 1.0"，界面永远显示不满。
            float d = c.raw[ax] - midRaw[ax];
            v[ax] = d > 1f ? 1f : (d < -1f ? -1f : d);
        }
        if (phase == 2) {
            for (int ax = 0; ax < 4; ax++) {
                mnRaw[ax] = Math.min(mnRaw[ax], c.raw[ax]);
                mxRaw[ax] = Math.max(mxRaw[ax], c.raw[ax]);
            }
            int done = 0;
            for (int i = 0; i < 4; i++) {
                int ax = bind[i];
                if (ax < 0) continue;
                boolean full = Math.abs(v[ax]) >= 0.92f;
                if (full) holdMs[i] += 33; else holdMs[i] = 0;
                if (holdMs[i] >= 2000) done++;
            }
            hint = "推满并保持 2 秒：" + done + " / 4 根完成";
            if (done >= 4) { phase = 3; phaseT0 = now; hint = "全部推满完成，现在把四根杆松手回中"; }
            return;
        }
        // phase 3：回中确认 —— 四轴都回到中位并保持 1.5 秒
        if (phase == 3) {
            boolean centered = Math.abs(v[0]) < 0.08f && Math.abs(v[1]) < 0.08f
                    && Math.abs(v[2]) < 0.08f && Math.abs(v[3]) < 0.08f;
            if (centered) centerMs += 33; else centerMs = 0;
            hint = "回中保持：" + (centerMs / 1000f) + " / 1.5 秒";
            if (centerMs >= 1500) finish();
            return;
        }
    }

    private boolean isBound(int ax) {
        for (int i = 0; i < 4; i++) if (bind[i] == ax) return true;
        return false;
    }

    private void drawBody(Canvas c, Ui ui, RectF a) {
        ui.card(c, box, Ui.CARD);
        float cx = box.centerX();
        float cy = box.top + d(96);
        float rad = Math.min(box.width(), box.height()) * 0.22f;

        // 标题 + 提示
        ui.textC(c, running ? ("第 " + (Math.min(idx, steps.length - 1) + 1) + " / " + steps.length + " 步")
                : "推杆校准", cx, box.top + d(34), 16, Ui.ACC, true);
        ui.textC(c, hint, cx, box.top + d(60), 14, Ui.TEXT, false);

        // 摇杆示意圈
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(2));
        p.setColor(Ui.LINE);
        c.drawCircle(cx, cy + d(30), rad, p);
        p.setColor(Ui.CARD2);
        c.drawCircle(cx, cy + d(30), rad * 0.55f, p);

        // 目标方向脉冲箭头
        float anim = (float) ((System.currentTimeMillis() % 1000) / 1000.0);
        float k = 0.55f + 0.45f * (float) Math.abs(Math.sin(anim * Math.PI));
        int axis = running ? curAxis : -1;
        int dir = running ? curDir : 0;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.ACC);
        float ax = cx, ay = cy + d(30);
        if (axis >= 0 && dir != 0) {
            float dx = (axis == AX_LX || axis == AX_RX) ? dir : 0f;
            float dy = (axis == AX_LY || axis == AX_RY) ? dir : 0f;
            // 左摇杆往左画，右摇杆往右画，避免箭头重叠
            float off = d(10) * k;
            float ex = ax + dx * (rad * 0.9f + off);
            float ey = ay + dy * (rad * 0.9f + off);
            drawArrow(c, ax + dx * (rad * 0.25f), ay + dy * (rad * 0.25f), ex, ey);
        } else if (running) {
            c.drawCircle(cx, cy + d(30), d(9) * k, p);
        }

        // 实时值
        Calib cal = r.calib;
        String v0 = String.format("LX %+.2f   LY %+.2f", cal.raw[AX_LX], cal.raw[AX_LY]);
        String v1 = String.format("RX %+.2f   RY %+.2f", cal.raw[AX_RX], cal.raw[AX_RY]);
        ui.textC(c, v0, cx, box.bottom - d(56), 13, Ui.DIM, false);
        ui.textC(c, v1, cx, box.bottom - d(34), 13, Ui.DIM, false);

        // 进度条
        RectF bar = new RectF(box.left + d(40), box.bottom - d(20),
                box.right - d(40), box.bottom - d(14));
        ui.fill(c, bar, Ui.CARD2);
        if (running) {
            RectF on = new RectF(bar.left, bar.top,
                    bar.left + bar.width() * (idx / (float) steps.length), bar.bottom);
            ui.fill(c, on, Ui.ACC);
        } else if (done) ui.fill(c, bar, Ui.ACC);

        ui.button(c, btnStart, running ? "重新开始" : (done ? "重新校准" : "开始校准"), Ui.ACC, true, 14);
        ui.button(c, btnCancel, "取消", Ui.DIM, false, 14);
    }

    private void drawArrow(Canvas c, float x0, float y0, float x1, float y1) {
        p.setColor(Ui.ACC);
        p.setStrokeWidth(d(5));
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x0, y0, x1, y1, p);
        double ang = Math.atan2(y1 - y0, x1 - x0);
        float len = d(16);
        for (int s = -1; s <= 1; s += 2) {
            double a2 = ang + s * 2.5;
            c.drawLine(x1, y1, x1 + (float) (Math.cos(a2) * len), y1 + (float) (Math.sin(a2) * len), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override public boolean down(int id, float x, float y) {
        if (btnStart != null && Ui.in(btnStart, x, y)) { start(); return true; }
        if (btnCancel != null && Ui.in(btnCancel, x, y)) {
            running = false; hint = "已取消（原有校准不变）"; return true;
        }
        return false;
    }
    /** 自动校准的可视化：四轴胶囊状态 + 每轴满行程条 + 阶段倒计时环。 */
    private void drawAuto(Canvas c, Ui ui, RectF a) {
        if (!running && !done) return;
        float x = a.left + ui.d(16);
        float y = a.top + ui.d(10);
        float cw = (a.width() - ui.d(48)) / 4f;
        float ch = ui.d(26);
        p.setAntiAlias(true);
        p.setTextSize(ui.d(12));
        for (int i = 0; i < 4; i++) {
            boolean bound = bind[i] >= 0;
            RectF rr = new RectF(x + i * (cw + ui.d(6)), y, x + i * (cw + ui.d(6)) + cw, y + ch);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bound ? 0xFF1E7A5A : 0xFF1A2230);
            c.drawRoundRect(rr, ui.d(8), ui.d(8), p);
            if (bound) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(ui.d(2));
                p.setColor(0xFF5AD1A5);
                c.drawRoundRect(rr, ui.d(8), ui.d(8), p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setColor(bound ? 0xFF8FF0CC : 0xFF5B6B7C);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText((bound ? "✓ " : "○ ") + SLOT_NAME[i], rr.centerX(), rr.centerY() + ui.d(4), p);
        }
        float by = y + ch + ui.d(14);
        float bw = a.width() - ui.d(32);
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < 4; i++) {
            int ax = bind[i];
            if (ax < 0) continue;
            float span = Math.min(1f, (mxRaw[ax] - mnRaw[ax]) / 1.35f);
            RectF bar = new RectF(a.left + ui.d(16), by + i * ui.d(20), a.left + ui.d(16) + bw, by + i * ui.d(20) + ui.d(13));
            p.setColor(0xFF18202B); c.drawRoundRect(bar, ui.d(6), ui.d(6), p);
            p.setColor(span > 0.92f ? 0xFF5AD1A5 : (span > 0.7f ? Ui.ACC : 0xFFE0653B));
            bar.right = bar.left + bw * span;
            c.drawRoundRect(bar, ui.d(6), ui.d(6), p);
            p.setColor(0xFFC7D3DF);
            p.setTextSize(ui.d(11));
            c.drawText(SLOT_NAME[i] + "  " + (int) (span * 100) + "%",
                    a.left + ui.d(22), bar.centerY() + ui.d(4), p);
        }
        if (phase == 2) {
            float t = Math.min(1f, (System.currentTimeMillis() - phaseT0) / 5000f);
            RectF ring = new RectF(a.centerX() - ui.d(22), a.bottom - ui.d(70), a.centerX() + ui.d(22), a.bottom - ui.d(26));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ui.d(4));
            p.setColor(Ui.LINE);
            c.drawArc(ring, 0, 360, false, p);
            p.setColor(Ui.ACC);
            c.drawArc(ring, -90, 360 * t, false, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

}
