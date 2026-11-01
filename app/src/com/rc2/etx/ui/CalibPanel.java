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
    private volatile String hint = "点「开始校准」，然后照着提示推杆";

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

    @Override public void draw(Canvas c, Ui ui, RectF a) { layout2(a); drawBody(c, ui, a); }

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
        hint = "所有摇杆回中，别碰";
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
        if (!running || idx >= steps.length) return;
        Calib c = r.calib;
        Step s = steps[idx];
        if (!has[idx]) {           // 第一帧：记下起始值，避免把旧样本当成本步结果
            for (int ax = 0; ax < 4; ax++) {
                if (s.axis < 0) midRaw[ax] = c.raw[ax];
                if (s.axis == ax && s.dir < 0) mnRaw[ax] = c.raw[ax];
                if (s.axis == ax && s.dir > 0) mxRaw[ax] = c.raw[ax];
            }
            has[idx] = true;
        }
        if (s.axis < 0) {
            midRaw[AX_LX] += (c.raw[AX_LX] - midRaw[AX_LX]) * 0.08f;
            midRaw[AX_LY] += (c.raw[AX_LY] - midRaw[AX_LY]) * 0.08f;
            midRaw[AX_RX] += (c.raw[AX_RX] - midRaw[AX_RX]) * 0.08f;
            midRaw[AX_RY] += (c.raw[AX_RY] - midRaw[AX_RY]) * 0.08f;
            if (holdStart == 0) holdStart = System.currentTimeMillis();
            if (System.currentTimeMillis() - holdStart > 900) next();
            return;
        }
        float v = c.raw[s.axis];
        if (s.dir < 0) mnRaw[s.axis] = Math.min(mnRaw[s.axis], v);
        else mxRaw[s.axis] = Math.max(mxRaw[s.axis], v);
        float span = s.dir < 0 ? (midRaw[s.axis] - v) : (v - midRaw[s.axis]);
        if (span > 0.72f) {
            if (holdStart == 0) holdStart = System.currentTimeMillis();
            if (System.currentTimeMillis() - holdStart > 150) next();
        } else holdStart = 0;
    }

    private void next() {
        idx++;
        holdStart = 0;
        if (idx >= steps.length) finish();
        else hint = steps[idx].text;
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
        int axis = running ? steps[Math.min(idx, steps.length - 1)].axis : -1;
        int dir = running ? steps[Math.min(idx, steps.length - 1)].dir : 0;
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
}
