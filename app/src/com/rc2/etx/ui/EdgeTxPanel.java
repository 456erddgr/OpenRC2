package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.Mixer;
import com.rc2.etx.radio.Radio;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * EdgeTX colorlcd 外观复刻主屏：顶部状态条 + 双摇杆方格 + 开关徽章 + 底部 16 通道条。
 * 说明：这是按 EdgeTX 2.9 colorlcd 的视觉语言复刻的界面（不是运行 EdgeTX 固件本体）。
 * 直接在这个页面上拖动摇杆方格也能操控，方便"看着原版界面飞"。
 */
public final class EdgeTxPanel extends Panel {

    private static final int BG = 0xFF0A0F0C;
    private static final int HEAD = 0xFF1C2B26;
    private static final int LIMEGREEN = 0xFFA8E05F;
    private static final int TEAL = 0xFF22C3A6;
    private static final int TXT = 0xFFF0F4F0;
    private static final int DIMT = 0xFF7E8C82;
    private static final int WARN = 0xFFF0A030;

    private final RectF head = new RectF();
    private final RectF stickL = new RectF();
    private final RectF stickR = new RectF();
    private final RectF bars = new RectF();
    private final RectF[] auxR = new RectF[4];
    private final RectF k1R = new RectF();
    private final RectF k2R = new RectF();
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.US);

    private float dens = 2.5f;
    private int dragL = -2;   // -2 无, 0 = 左摇杆, 1 = 右摇杆
    private int dragR = -2;

    public EdgeTxPanel(Radio r) {
        super(r);
        for (int i = 0; i < 4; i++) auxR[i] = new RectF();
    }

    @Override
    public String title() {
        return "原版";
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        dens = ui.density;
        ui.fill(c, a, BG);
        float pad = ui.d(12);
        float headH = ui.d(40);
        head.set(a.left + pad, a.top + ui.d(6), a.right - pad, a.top + ui.d(6) + headH);
        ui.card(c, head, HEAD);

        // ---- 顶部状态条 ----
        ui.text(c, "EDGETX", head.left + ui.d(12), head.top + ui.d(20), 17, LIMEGREEN, true);
        ui.text(c, "2.9 colorlcd 外观复刻", head.left + ui.d(12), head.top + ui.d(33), 9, DIMT, false);
        String model = radio.model.name == null ? "RC2" : radio.model.name;
        ui.textC(c, model, head.centerX(), head.top + ui.d(18), 15, TXT, true);
        ui.textC(c, "RC2 · 16CH · CRSF/ELRS 就绪", head.centerX(), head.top + ui.d(32), 9, DIMT, false);
        ui.textR(c, hhmm.format(new Date()), head.right - ui.d(12), head.top + ui.d(17), 15, TXT, true);
        ui.textR(c, "TX 100%  " + radio.hz + "Hz", head.right - ui.d(12), head.top + ui.d(32), 9, DIMT, false);

        // ---- 中部：双摇杆方格 + 右侧开关 ----
        float top = head.bottom + ui.d(10);
        float midH = a.bottom - top - ui.d(96);
        float sq = Math.min(midH, (a.width() * 0.52f - pad * 3) / 2f);
        stickL.set(a.left + pad, top, a.left + pad + sq, top + sq);
        stickR.set(stickL.right + pad, top, stickL.right + pad + sq, top + sq);

        drawStick(c, ui, stickL, radio.input.lx, radio.input.ly, "THR / RUD", "T", "R");
        drawStick(c, ui, stickR, radio.input.rx, radio.input.ry, "ELE / AIL", "E", "A");

        float rx = stickR.right + pad;
        float rightW = a.right - pad - rx;
        float rowH = Math.min(ui.d(46), (midH - ui.d(16)) / 6f);
        String[] auxName = {"SA", "SB", "SC", "SD"};
        float[] auxVal = {radio.input.aux1, radio.input.aux2, radio.input.aux3, radio.input.aux4};
        for (int i = 0; i < 4; i++) {
            auxR[i].set(rx, top + i * (rowH + ui.d(4)), rx + rightW, top + i * (rowH + ui.d(4)) + rowH);
            ui.card(c, auxR[i], 0xFF141E19);
            ui.text(c, auxName[i], auxR[i].left + ui.d(10), auxR[i].centerY() + ui.d(6), 15, TXT, true);
            int st = auxVal[i] > 0.35f ? 2 : (auxVal[i] < -0.35f ? 0 : 1);
            String[] lab = {"DN", "MID", "UP"};
            int col = st == 2 ? TEAL : (st == 0 ? WARN : DIMT);
            ui.textR(c, lab[st], auxR[i].right - ui.d(10), auxR[i].centerY() + ui.d(6), 14, col, true);
            float frac = (auxVal[i] + 1f) * 0.5f;
            float bw = ui.d(56);
            RectF tr = new RectF(auxR[i].right - ui.d(90) - bw, auxR[i].centerY() - ui.d(4),
                    auxR[i].right - ui.d(90), auxR[i].centerY() + ui.d(4));
            ui.card(c, tr, 0xFF243028);
            ui.card(c, new RectF(tr.left, tr.top, tr.left + bw * frac, tr.bottom), col);
        }
        float ky = auxR[3].bottom + ui.d(8);
        k1R.set(rx, ky, rx + rightW, ky + rowH);
        k2R.set(rx, ky + rowH + ui.d(4), rx + rightW, ky + rowH * 2 + ui.d(4));
        drawKnob(c, ui, k1R, "S1", radio.input.knob1);
        drawKnob(c, ui, k2R, "S2", radio.input.knob2);

        // ---- 底部：16 通道条 ----
        bars.set(a.left + pad, a.bottom - ui.d(88), a.right - pad, a.bottom - ui.d(8));
        drawBars(c, ui);
    }

    private void drawStick(Canvas c, Ui ui, RectF b, float x, float y, String name, String vn, String hn) {
        ui.card(c, b, 0xFF111A15);
        ui.cardStroke(c, b, 0xFF2A3B31, 1f);
        float cx = b.centerX();
        float cy = b.centerY();
        float s = b.width() * 0.40f;
        ui.line(c, cx - s, cy, cx + s, cy, 0xFF2E4237, 1f);
        ui.line(c, cx, cy - s, cx, cy + s, 0xFF2E4237, 1f);
        ui.circle(c, cx, cy, b.width() * 0.16f, 0xFF233C31, true, 1f);
        float dx = cx + x * s;
        float dy = cy - y * s;
        ui.line(c, cx, cy, dx, dy, TEAL, 1.4f);
        ui.circle(c, dx, dy, ui.d(13), TEAL, false, 0);
        ui.textC(c, name, cx, b.top + ui.d(18), 12, DIMT, false);
        ui.textR(c, vn + " " + pct(y), b.right - ui.d(10), b.bottom - ui.d(24), 11, TXT, true);
        ui.textR(c, hn + " " + pct(x), b.right - ui.d(10), b.bottom - ui.d(10), 11, TXT, true);
    }

    private void drawKnob(Canvas c, Ui ui, RectF b, String name, float v) {
        ui.card(c, b, 0xFF141E19);
        ui.text(c, name, b.left + ui.d(10), b.centerY() + ui.d(6), 15, TXT, true);
        float t = (v + 1f) * 0.5f;
        RectF tr = new RectF(b.left + ui.d(46), b.centerY() - ui.d(5), b.right - ui.d(56), b.centerY() + ui.d(5));
        ui.card(c, tr, 0xFF243028);
        ui.card(c, new RectF(tr.left, tr.top, tr.left + tr.width() * t, tr.bottom), LIMEGREEN);
        ui.textR(c, pct(v), b.right - ui.d(10), b.centerY() + ui.d(6), 13, TXT, true);
    }

    private void drawBars(Canvas c, Ui ui) {
        ui.card(c, bars, 0xFF0F1611);
        int n = Radio.CH_COUNT;
        float w = bars.width() / n;
        int[] pctv = radio.pctSnapshot();
        for (int i = 0; i < n; i++) {
            float x = bars.left + i * w + w * 0.22f;
            float bw = w * 0.56f;
            float top = bars.top + ui.d(8);
            float bot = bars.bottom - ui.d(20);
            float mid = (top + bot) / 2f;
            ui.card(c, new RectF(x, top, x + bw, bot), 0xFF1B2620);
            float p = Math.max(-1f, Math.min(1f, pctv[i] / 100f));
            if (p >= 0) {
                ui.card(c, new RectF(x, mid - (mid - top) * p, x + bw, mid), TEAL);
            } else {
                ui.card(c, new RectF(x, mid, x + bw, mid + (bot - mid) * (-p)), WARN);
            }
            ui.textC(c, String.valueOf(i + 1), bars.left + i * w + w * 0.5f, bars.bottom - ui.d(7), 9, DIMT, false);
        }
    }

    private static String pct(float v) {
        int p = Math.round(v * 100f);
        return (p > 0 ? "+" : "") + p;
    }

    @Override
    public boolean down(int id, float x, float y) {
        if (Ui.in(stickL, x, y)) {
            dragL = id;
            moveStick(0, x, y);
            return true;
        }
        if (Ui.in(stickR, x, y)) {
            dragR = id;
            moveStick(1, x, y);
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (Ui.in(auxR[i], x, y)) {
                float[] a = {radio.input.aux1, radio.input.aux2, radio.input.aux3, radio.input.aux4};
                float nv = a[i] > 0.35f ? -1f : (a[i] < -0.35f ? 0f : 1f);
                radio.input.touch(4 + i, nv);
                return true;
            }
        }
        if (Ui.in(k1R, x, y)) {
            setKnob(8, k1R, x);
            return true;
        }
        if (Ui.in(k2R, x, y)) {
            setKnob(9, k2R, x);
            return true;
        }
        return false;
    }

    private void setKnob(int idx, RectF b, float x) {
        float left = b.left + uiPx(46);
        float right = b.right - uiPx(56);
        float t = (x - left) / Math.max(1f, right - left);
        t = Math.max(0f, Math.min(1f, t));
        radio.input.touch(idx, t * 2f - 1f);
    }

    private float uiPx(float dp) {
        return dp * dens;
    }

    @Override
    public void move(int id, float x, float y) {
        if (id == dragL) {
            moveStick(0, x, y);
        } else if (id == dragR) {
            moveStick(1, x, y);
        } else if (Ui.in(k1R, x, y)) {
            setKnob(8, k1R, x);
        } else if (Ui.in(k2R, x, y)) {
            setKnob(9, k2R, x);
        }
    }

    private void moveStick(int which, float x, float y) {
        RectF b = which == 0 ? stickL : stickR;
        float s = b.width() * 0.40f;
        float nx = (x - b.centerX()) / s;
        float ny = -(y - b.centerY()) / s;
        nx = Math.max(-1f, Math.min(1f, nx));
        ny = Math.max(-1f, Math.min(1f, ny));
        radio.input.touch(which == 0 ? 0 : 2, nx);
        radio.input.touch(which == 0 ? 1 : 3, ny);
    }

    @Override
    public void up(int id) {
        if (id == dragL) dragL = -2;
        if (id == dragR) dragR = -2;
    }
}
