package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.SparseIntArray;

import com.rc2.etx.radio.Radio;

/** 飞行页：左右触摸摇杆 + 4 个三档开关 + 2 个旋钮 + 底部 16 通道条。 */
public final class StickPanel extends Panel {

    private static final int CTL_L = 0;
    private static final int CTL_R = 1;
    private static final int CTL_AUX1 = 2;

    private final SparseIntArray ptr = new SparseIntArray();
    private final RectF[] auxR = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF k1 = new RectF();
    private final RectF k2 = new RectF();
    private final RectF dl = new RectF();
    private final RectF dr = new RectF();
    private final RectF bars = new RectF();
    private final RectF tmp = new RectF();

    private float lcx;
    private float lcy;
    private float lrad;
    private float rcx;
    private float rcy;
    private float rrad;
    private boolean heldL;
    private boolean heldR;

    public StickPanel(Radio r) {
        super(r);
    }

    @Override
    public String title() {
        return "触摸";
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        float pad = ui.d(10);
        float boxH = a.height() * 0.60f;
        float sideW = a.width() * 0.30f;
        dl.set(a.left + pad, a.top + pad, a.left + pad + sideW, a.top + pad + boxH);
        dr.set(a.right - pad - sideW, a.top + pad, a.right - pad, a.top + pad + boxH);
        lcx = dl.centerX();
        lcy = dl.centerY();
        lrad = Math.min(dl.width(), dl.height()) * 0.36f;
        rcx = dr.centerX();
        rcy = dr.centerY();
        rrad = Math.min(dr.width(), dr.height()) * 0.36f;

        ui.card(c, dl, Ui.CARD);
        ui.card(c, dr, Ui.CARD);
        drawStick(c, ui, lcx, lcy, lrad, radio.input.lx, radio.input.ly,
                "左", "偏航", "油门", radio.input.lx, radio.input.ly);
        drawStick(c, ui, rcx, rcy, rrad, radio.input.rx, radio.input.ry,
                "右", "横滚", "俯仰", radio.input.rx, radio.input.ry);

        // 中列：4 个三档开关 + 2 个旋钮
        float midL = dl.right + ui.d(8);
        float midR = dr.left - ui.d(8);
        float midW = midR - midL;
        float midTop = a.top + pad;
        float itemH = boxH / 6f;
        String[] auxName = {"AUX1", "AUX2", "AUX3", "AUX4"};
        float[] auxVal = {radio.input.aux1, radio.input.aux2, radio.input.aux3, radio.input.aux4};
        for (int i = 0; i < 4; i++) {
            auxR[i].set(midL, midTop + i * itemH + ui.d(2), midR, midTop + (i + 1) * itemH - ui.d(2));
            drawTri(c, ui, auxR[i], auxName[i], auxVal[i]);
        }
        k1.set(midL, midTop + 4 * itemH + ui.d(2), midR, midTop + 5 * itemH - ui.d(2));
        k2.set(midL, midTop + 5 * itemH + ui.d(2), midR, midTop + 6 * itemH - ui.d(2));
        drawKnob(c, ui, k1, "K1", radio.input.knob1);
        drawKnob(c, ui, k2, "K2", radio.input.knob2);

        // 底部：16 通道条
        bars.set(a.left + pad, a.top + pad + boxH + pad, a.right - pad, a.bottom - pad);
        drawBars(c, ui);
    }

    private void drawStick(Canvas c, Ui ui, float cx, float cy, float rad, float x, float y,
                           String tag, String xn, String yn, float xv, float yv) {
        ui.circle(c, cx, cy, rad, Ui.LINE, true, 1.2f);
        ui.circle(c, cx, cy, rad * 0.5f, 0xFF232B36, true, 1f);
        ui.line(c, cx - rad, cy, cx + rad, cy, Ui.LINE, 1f);
        ui.line(c, cx, cy - rad, cx, cy + rad, Ui.LINE, 1f);
        float dx = cx + x * rad;
        float dy = cy - y * rad;
        ui.line(c, cx, cy, dx, dy, Ui.ACC, 1.5f);
        ui.circle(c, dx, dy, ui.d(11), Ui.ACC, false, 0);
        ui.circle(c, dx, dy, ui.d(11), 0xFF0B0F14, true, 2f);
        ui.textC(c, tag, cx, cy - rad - ui.d(4), 12, Ui.DIM, false);
        ui.textC(c, xn + " " + pct(xv), cx, cy + rad + ui.d(12), 11, Ui.DIM, true);
        ui.textC(c, yn + " " + pct(yv), cx, cy + rad + ui.d(24), 11, Ui.DIM, true);
    }

    private void drawTri(Canvas c, Ui ui, RectF b, String name, float v) {
        ui.card(c, b, Ui.CARD2);
        int idx = v > 0.5f ? 2 : (v < -0.5f ? 0 : 1);
        String[] lab = {"下", "中", "上"};
        float fw = b.width() / 3f;
        ui.text(c, name, b.left + ui.d(8), b.centerY() + ui.d(5), 12, Ui.DIM, false);
        float x0 = b.left + b.width() * 0.34f;
        for (int i = 0; i < 3; i++) {
            tmp.set(x0 + i * (b.width() * 0.2f), b.top + ui.d(4), x0 + (i + 1) * (b.width() * 0.2f) - ui.d(3), b.bottom - ui.d(4));
            boolean on = (i == idx);
            ui.card(c, tmp, on ? Ui.ACC : 0xFF2A323D);
            ui.textC(c, lab[i], tmp.centerX(), tmp.centerY() + ui.d(4.5f), 11, on ? 0xFF0B0F14 : Ui.DIM, on);
        }
    }

    private void drawKnob(Canvas c, Ui ui, RectF b, String name, float v) {
        ui.card(c, b, Ui.CARD2);
        ui.text(c, name, b.left + ui.d(8), b.centerY() + ui.d(5), 12, Ui.DIM, false);
        float x0 = b.left + b.width() * 0.30f;
        float x1 = b.right - ui.d(10);
        tmp.set(x0, b.centerY() - ui.d(5), x1, b.centerY() + ui.d(5));
        ui.card(c, tmp, 0xFF2A323D);
        float t = (v + 1f) * 0.5f;
        float fx = x0 + t * (x1 - x0);
        tmp.set(x0, b.centerY() - ui.d(5), fx, b.centerY() + ui.d(5));
        ui.card(c, tmp, Ui.BLUE);
        ui.circle(c, fx, b.centerY(), ui.d(6), Ui.TEXT, false, 0);
        ui.textR(c, pct(v), b.right - ui.d(6), b.centerY() + ui.d(5), 11, Ui.TEXT, true);
    }

    private void drawBars(Canvas c, Ui ui) {
        ui.card(c, bars, Ui.CARD);
        int n = Radio.CH_COUNT;
        int[] pctv = radio.pctSnapshot();
        int[] usv = radio.usSnapshot();
        float w = bars.width() / n;
        for (int i = 0; i < n; i++) {
            float x = bars.left + i * w + w * 0.18f;
            float bw = w * 0.64f;
            float top = bars.top + ui.d(14);
            float bot = bars.bottom - ui.d(14);
            float mid = (top + bot) / 2f;
            ui.card(c, new RectF(x, top, x + bw, bot), 0xFF232B36);
            float p = Math.max(-1f, Math.min(1f, pctv[i] / 100f));
            if (p >= 0) {
                ui.card(c, new RectF(x, mid - (mid - top) * p, x + bw, mid), Ui.ACC);
            } else {
                ui.card(c, new RectF(x, mid, x + bw, mid + (bot - mid) * (-p)), Ui.BLUE);
            }
            ui.textC(c, String.valueOf(i + 1), x + bw / 2, bot + ui.d(12), 9, Ui.DIM, false);
            ui.textC(c, String.valueOf(usv[i]), x + bw / 2, top - ui.d(4), 8, Ui.DIM, false);
        }
    }

    private static String pct(float v) {
        int p = Math.round(v * 100f);
        return (p > 0 ? "+" : "") + p;
    }

    @Override
    public boolean down(int id, float x, float y) {
        if (hit(lcx, lcy, lrad * 1.35f, x, y)) {
            ptr.put(id, CTL_L);
            heldL = true;
            setL(x, y);
            return true;
        }
        if (hit(rcx, rcy, rrad * 1.35f, x, y)) {
            ptr.put(id, CTL_R);
            heldR = true;
            setR(x, y);
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (Ui.in(auxR[i], x, y)) {
                ptr.put(id, CTL_AUX1 + i);
                cycleAux(i);
                return true;
            }
        }
        if (Ui.in(k1, x, y)) {
            ptr.put(id, 6);
            setKnob(0, x, k1);
            return true;
        }
        if (Ui.in(k2, x, y)) {
            ptr.put(id, 7);
            setKnob(1, x, k2);
            return true;
        }
        return false;
    }

    @Override
    public void move(int id, float x, float y) {
        int ctl = ptr.get(id, -1);
        if (ctl == CTL_L) {
            setL(x, y);
        } else if (ctl == CTL_R) {
            setR(x, y);
        } else if (ctl == 6) {
            setKnob(0, x, k1);
        } else if (ctl == 7) {
            setKnob(1, x, k2);
        }
    }

    @Override
    public void up(int id) {
        int ctl = ptr.get(id, -1);
        ptr.delete(id);
        if (ctl == CTL_L) heldL = false;
        if (ctl == CTL_R) heldR = false;
    }

    @Override
    public void tick() {
        // 摇杆松手回中；油门轴（左摇杆纵向）不回中，保持当前位置
        float k = 0.28f;
        if (!heldL) {
            radio.input.touch(0, decay(radio.input.lx, k));
        }
        if (!heldR) {
            radio.input.touch(2, decay(radio.input.rx, k));
            radio.input.touch(3, decay(radio.input.ry, k));
        }
    }

    private static float decay(float v, float k) {
        float n = v * (1f - k);
        return Math.abs(n) < 0.01f ? 0f : n;
    }

    private static boolean hit(float cx, float cy, float r, float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private void setL(float x, float y) {
        float nx = clamp((x - lcx) / lrad);
        float ny = clamp(-(y - lcy) / lrad);
        radio.input.touch(0, nx);
        radio.input.touch(1, ny);
    }

    private void setR(float x, float y) {
        float nx = clamp((x - rcx) / rrad);
        float ny = clamp(-(y - rcy) / rrad);
        radio.input.touch(2, nx);
        radio.input.touch(3, ny);
    }

    private void setKnob(int idx, float x, RectF r) {
        float t = (x - r.left) / Math.max(1f, r.width());
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        radio.input.touch(8 + idx, t * 2f - 1f);
    }

    private void cycleAux(int i) {
        float v;
        if (i == 0) v = radio.input.aux1;
        else if (i == 1) v = radio.input.aux2;
        else if (i == 2) v = radio.input.aux3;
        else v = radio.input.aux4;
        float nv = (v > 0.5f) ? -1f : (v < -0.5f ? 0f : 1f);
        radio.input.touch(4 + i, nv);
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }
}
