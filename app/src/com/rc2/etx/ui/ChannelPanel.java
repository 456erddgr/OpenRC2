package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.ChannelConfig;
import com.rc2.etx.radio.OutputSink;
import com.rc2.etx.radio.Radio;

/** 通道监视页：16 通道实时值 + 控制环统计 + 最新 CRSF 帧。 */
public final class ChannelPanel extends Panel {

    private final RectF head = new RectF();
    private final RectF body = new RectF();
    private final RectF row = new RectF();
    private final RectF barBg = new RectF();

    public ChannelPanel(Radio r) {
        super(r);
    }

    @Override
    public String title() {
        return "通道";
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        float pad = ui.d(10);
        head.set(a.left + pad, a.top + pad, a.right - pad, a.top + pad + ui.d(78));
        ui.card(c, head, Ui.CARD);

        long ticks = radio.ticks;
        OutputSink s = radio.sink();
        String line1 = "控制环 " + (radio.isRunning() ? "运行中" : "已停止")
                + "   实测 " + radio.hz + "Hz   最大抖动 " + radio.maxGapMs + "ms"
                + "   总帧 " + ticks + "   混控 " + (radio.isMixerOn() ? "开" : "关");
        String line2 = "输出 [" + s.name() + "] " + s.status()
                + "   已发 " + s.frames() + "   错误 " + s.errors();
        ui.text(c, line1, head.left + ui.d(10), head.top + ui.d(22), 13, Ui.TEXT, false);
        ui.text(c, line2, head.left + ui.d(10), head.top + ui.d(44), 13,
                s.isOpen() ? Ui.ACC : Ui.WARN, false);
        ui.text(c, radio.crsfHex, head.left + ui.d(10), head.top + ui.d(66), 10, Ui.DIM, false);

        body.set(a.left + pad, head.bottom + pad, a.right - pad, a.bottom - pad);
        ui.card(c, body, Ui.CARD);
        int n = Radio.CH_COUNT;
        float rowH = (body.height() - ui.d(8)) / n;
        int[] pct = radio.pctSnapshot();
        int[] us = radio.usSnapshot();
        for (int i = 0; i < n; i++) {
            float top = body.top + ui.d(4) + i * rowH;
            row.set(body.left + ui.d(6), top, body.right - ui.d(6), top + rowH - ui.d(2));
            boolean odd = (i % 2) == 0;
            if (odd) ui.card(c, row, 0xFF1B222C);
            ChannelConfig cc = radio.mixer().cfg(i);
            ui.text(c, String.format("CH%02d", i + 1), row.left + ui.d(8), row.centerY() + ui.d(5), 12, Ui.DIM, true);
            ui.text(c, cc.name, row.left + ui.d(52), row.centerY() + ui.d(5), 12, Ui.TEXT, false);
            ui.text(c, ChannelConfig.srcName(cc.src), row.left + ui.d(130), row.centerY() + ui.d(5), 11, Ui.DIM, false);
            ui.textR(c, String.valueOf(us[i]), row.left + ui.d(268), row.centerY() + ui.d(5), 12, Ui.TEXT, true);
            ui.textR(c, (pct[i] > 0 ? "+" : "") + pct[i], row.left + ui.d(310), row.centerY() + ui.d(5), 11, Ui.DIM, false);

            float bx = row.left + ui.d(330);
            float bw = row.right - bx - ui.d(10);
            float midY = row.centerY();
            barBg.set(bx, midY - ui.d(5), bx + bw, midY + ui.d(5));
            ui.card(c, barBg, 0xFF232B36);
            float mid = bx + bw / 2f;
            ui.line(c, mid, barBg.top, mid, barBg.bottom, Ui.LINE, 1f);
            float p = Math.max(-1f, Math.min(1f, pct[i] / 100f));
            if (p >= 0) {
                ui.card(c, new RectF(mid, barBg.top, mid + (bw / 2f) * p, barBg.bottom), Ui.ACC);
            } else {
                ui.card(c, new RectF(mid + (bw / 2f) * p, barBg.top, mid, barBg.bottom), Ui.BLUE);
            }
        }
    }

}
