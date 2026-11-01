package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.ChannelConfig;
import com.rc2.etx.radio.Mixer;
import com.rc2.etx.radio.Radio;

/** 混控编辑页：通道源 / expo / 比率 / 偏移 / 反向。 */
public final class MixPanel extends Panel {

    private final RectF[] tiles = new RectF[Mixer.CH_COUNT];
    private final RectF edit = new RectF();
    private final RectF[] minus = new RectF[3];
    private final RectF[] plus = new RectF[3];
    private final RectF srcPrev = new RectF();
    private final RectF srcNext = new RectF();
    private final RectF revBtn = new RectF();
    private final RectF saveBtn = new RectF();
    private final RectF defBtn = new RectF();
    private final RectF r = new RectF();
    private int sel;
    private int downId = -1;
    private float downX;
    private float downY;

    public MixPanel(Radio r0) {
        super(r0);
        for (int i = 0; i < Mixer.CH_COUNT; i++) tiles[i] = new RectF();
        for (int i = 0; i < 3; i++) {
            minus[i] = new RectF();
            plus[i] = new RectF();
        }
    }

    @Override
    public String title() {
        return "混控";
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        float pad = ui.d(10);
        float listW = a.width() * 0.46f;
        RectF list = new RectF(a.left + pad, a.top + pad, a.left + pad + listW, a.bottom - pad);
        edit.set(list.right + pad, a.top + pad, a.right - pad, a.bottom - pad);
        ui.card(c, list, Ui.CARD);
        ui.card(c, edit, Ui.CARD);

        int cols = 2;
        int rows = (Mixer.CH_COUNT + cols - 1) / cols;
        float cw = (list.width() - ui.d(8)) / cols;
        float ch = (list.height() - ui.d(8)) / rows;
        for (int i = 0; i < Mixer.CH_COUNT; i++) {
            int col = i % cols;
            int row = i / cols;
            tiles[i].set(list.left + ui.d(4) + col * cw, list.top + ui.d(4) + row * ch,
                    list.left + ui.d(4) + (col + 1) * cw - ui.d(4), list.top + ui.d(4) + (row + 1) * ch - ui.d(4));
            ChannelConfig cc = radio.model.ch[i];
            boolean on = (i == sel);
            ui.card(c, tiles[i], on ? 0xFF26433C : Ui.CARD2);
            if (on) ui.cardStroke(c, tiles[i], Ui.ACC, 1.5f);
            ui.text(c, String.format("CH%02d", i + 1), tiles[i].left + ui.d(6), tiles[i].top + ui.d(15), 11,
                    on ? Ui.ACC : Ui.DIM, true);
            ui.text(c, cc.name, tiles[i].left + ui.d(6), tiles[i].top + ui.d(30), 12, Ui.TEXT, false);
            ui.text(c, ChannelConfig.srcName(cc.src), tiles[i].left + ui.d(6), tiles[i].top + ui.d(44), 10, Ui.DIM, false);
        }

        ChannelConfig cc = radio.model.ch[sel];
        float x = edit.left + ui.d(12);
        float y = edit.top + ui.d(24);
        ui.text(c, "CH" + String.format("%02d", sel + 1) + "  " + cc.name, x, y, 16, Ui.TEXT, true);
        ui.textR(c, "当前输出 " + radio.pctSnapshot()[sel], edit.right - ui.d(12), y, 13, Ui.ACC, false);

        // 源
        y += ui.d(30);
        ui.text(c, "通道源", x, y, 12, Ui.DIM, false);
        float bx = edit.left + ui.d(96);
        float bw = edit.width() - ui.d(96) - ui.d(80);
        r.set(bx, y - ui.d(16), bx + bw, y + ui.d(10));
        ui.card(c, r, Ui.CARD2);
        ui.textC(c, ChannelConfig.srcName(cc.src), r.centerX(), r.centerY() + ui.d(5), 13, Ui.TEXT, false);
        srcPrev.set(bx - ui.d(34), y - ui.d(16), bx - ui.d(4), y + ui.d(10));
        srcNext.set(r.right + ui.d(4), y - ui.d(16), r.right + ui.d(34), y + ui.d(10));
        ui.button(c, srcPrev, "<", Ui.BLUE, false, 14);
        ui.button(c, srcNext, ">", Ui.BLUE, false, 14);

        String[] lab = {"EXPO", "RATE", "偏移"};
        float[] val = {cc.expo, cc.rate, cc.offset};
        for (int i = 0; i < 3; i++) {
            y += ui.d(38);
            ui.text(c, lab[i], x, y, 12, Ui.DIM, false);
            float w2 = edit.width() - ui.d(96) - ui.d(80);
            r.set(edit.left + ui.d(96), y - ui.d(16), edit.left + ui.d(96) + w2, y + ui.d(10));
            ui.card(c, r, Ui.CARD2);
            ui.textC(c, fmt(i, val[i]), r.centerX(), r.centerY() + ui.d(5), 13, Ui.TEXT, true);
            minus[i].set(r.left - ui.d(34), r.top, r.left - ui.d(4), r.bottom);
            plus[i].set(r.right + ui.d(4), r.top, r.right + ui.d(34), r.bottom);
            ui.button(c, minus[i], "-", Ui.BLUE, false, 14);
            ui.button(c, plus[i], "+", Ui.BLUE, false, 14);
        }

        y += ui.d(40);
        revBtn.set(x, y - ui.d(18), x + ui.d(150), y + ui.d(14));
        ui.button(c, revBtn, cc.reverse ? "反向：已开" : "反向：关", cc.reverse ? Ui.WARN : Ui.BLUE, cc.reverse, 13);

        float by = edit.bottom - ui.d(52);
        saveBtn.set(edit.left + ui.d(12), by, edit.left + ui.d(12) + ui.d(120), by + ui.d(40));
        defBtn.set(saveBtn.right + ui.d(10), by, saveBtn.right + ui.d(10) + ui.d(120), by + ui.d(40));
        ui.button(c, saveBtn, "保存模型", Ui.ACC, true, 14);
        ui.button(c, defBtn, "恢复默认", Ui.DIM, false, 14);
    }

    private static String fmt(int i, float v) {
        if (i == 2) return (v > 0 ? "+" : "") + String.format("%.2f", v);
        return String.format("%.2f", v);
    }

    @Override
    public boolean down(int id, float x, float y) {
        downId = id;
        downX = x;
        downY = y;
        return true;
    }

    @Override
    public void up(int id) {
        if (id != downId) return;
        ChannelConfig cc = radio.model.ch[sel];
        for (int i = 0; i < Mixer.CH_COUNT; i++) {
            if (Ui.in(tiles[i], downX, downY)) {
                sel = i;
                return;
            }
        }
        if (Ui.in(srcPrev, downX, downY)) {
            cc.src = nextSrc(cc.src, -1);
            radio.reloadMixer();
            return;
        }
        if (Ui.in(srcNext, downX, downY)) {
            cc.src = nextSrc(cc.src, 1);
            radio.reloadMixer();
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (Ui.in(minus[i], downX, downY)) {
                adjust(cc, i, -1);
                return;
            }
            if (Ui.in(plus[i], downX, downY)) {
                adjust(cc, i, 1);
                return;
            }
        }
        if (Ui.in(revBtn, downX, downY)) {
            cc.reverse = !cc.reverse;
            radio.reloadMixer();
            return;
        }
        if (Ui.in(saveBtn, downX, downY)) {
            radio.reloadMixer();
            radio.save();
            return;
        }
        if (Ui.in(defBtn, downX, downY)) {
            ChannelConfig d = Mixer.defaultConfig()[sel];
            radio.model.ch[sel] = d;
            radio.reloadMixer();
        }
    }

    private static int nextSrc(int cur, int dir) {
        // 顺序：未使用(-1) -> AIL(0) ... KNOB2(9)
        int n = 11;
        int idx = (cur - ChannelConfig.SRC_AIL + n) % n;
        idx = (idx + dir + n) % n;
        return ChannelConfig.SRC_AIL + idx;
    }

    private static void adjust(ChannelConfig cc, int which, int dir) {
        if (which == 0) {
            cc.expo = clamp01(cc.expo + dir * 0.05f, 1f);
        } else if (which == 1) {
            cc.rate = clamp01(cc.rate + dir * 0.05f, 1.5f);
        } else {
            float v = cc.offset + dir * 0.05f;
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            cc.offset = v;
        }
    }

    private static float clamp01(float v, float max) {
        if (v < 0) return 0;
        if (v > max) return max;
        return v;
    }
}
