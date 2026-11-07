package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.ChannelConfig;
import com.rc2.etx.radio.OutputSink;
import com.rc2.etx.radio.Radio;

/**
 * 通道监视页：16 通道实时值 + 控制环统计 + 最新 CRSF 帧。
 *
 * 2026-11-07 重做。原来的问题是"16 行一模一样"，眼睛无法在 3 秒内找到异常通道，
 * 而且所有数字都在跳，看不清哪个是活的。改法（照 DJI 的克制路线）：
 *  - 通道按**类别分组**（摇杆 / 旋钮 / 按键 / 档位），组间留白更大，组内紧凑；
 *  - 数值列一律等宽右对齐，避免跳动时左右抖；
 *  - 只有"偏离中位超过 5%"的通道才上蓝色，其余保持灰 —— 一眼就能看出谁在动；
 *  - 条形图从"两侧填充"换成"中心刻度 + 当前值"，中位画一根细线做基准；
 *  - 顶部统计从两句长文字改成 4 个指标块，扫读更快。
 */
public final class ChannelPanel extends Panel {

    private final RectF head = new RectF();
    private final RectF body = new RectF();
    private final RectF row = new RectF();
    private final RectF barBg = new RectF();
    private final RectF tmp = new RectF();
    private final RectF headCard = new RectF();

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
        head.set(a.left + pad, a.top + pad, a.right - pad, a.top + pad + ui.d(86));
        drawHead(c, ui);

        body.set(a.left + pad, head.bottom + pad, a.right - pad, a.bottom - pad);
        ui.card(c, body, Ui.CARD);
        drawChannels(c, ui);
    }

    /** 顶部统计：4 个指标块，每个"标签在上、数值在下"，比长句子快得多。 */
    private void drawHead(Canvas c, Ui ui) {
        ui.cardRaised(c, head, Ui.CARD);
        OutputSink s = radio.sink();
        long ticks = radio.ticks;

        // 4 等分（右侧留 6dp，避免最右一块贴边）
        float w = (head.width() - ui.d(6)) / 4f;
        float top = head.top + ui.d(8);

        metric(c, ui, head.left + w * 0, top, w, "控制环",
                radio.isRunning() ? radio.hz + " Hz" : "已停止",
                radio.isRunning() ? Ui.OK : Ui.BAD);
        metric(c, ui, head.left + w * 1, top, w, "抖动",
                radio.maxGapMs + " ms",
                radio.maxGapMs > 40 ? Ui.WARN : Ui.TEXT);
        metric(c, ui, head.left + w * 2, top, w, "输出",
                s.isOpen() ? s.name() + " 开" : s.name() + " 关",
                s.isOpen() ? Ui.OK : Ui.WARN);
        metric(c, ui, head.left + w * 3, top, w, "总帧",
                String.valueOf(ticks), Ui.TEXT);

        // 底部一行：CRSF 最新帧（等宽，压暗，只做"确实在发"的凭证）
        ui.divider(c, new RectF(head.left + ui.d(8), 0, head.right - ui.d(8), 0),
                head.top + ui.d(56), Ui.LINE);
        ui.text(c, radio.crsfHex, head.left + ui.d(10), head.top + ui.d(74), 10, Ui.FAINT, false);
        String tail = (radio.isMixerOn() ? "混控 开" : "混控 关")
                + "   已发 " + s.frames() + "   错误 " + s.errors();
        ui.textR(c, tail, head.right - ui.d(10), head.top + ui.d(74), 10,
                s.errors() > 0 ? Ui.WARN : Ui.FAINT, false);
    }

    private void metric(Canvas c, Ui ui, float x, float top, float w, String label,
                        String value, int color) {
        // 2026-11-07：原来 value 用 16sp 右对齐到 x+w-10，第 4 块（总帧）会顶出卡片右边缘。
        // 现在：数值左对齐到标题下方，并且按可用宽度自动降字号，宁小不溢出。
        ui.text(c, label, x + ui.d(10), top + ui.d(11), 9, Ui.FAINT, false);
        float sp = 16f;
        float maxW = w - ui.d(16);
        while (sp > 10f && ui.monoW(value, sp) > maxW) sp -= 1f;
        ui.monoR(c, value, x + w - ui.d(8), top + ui.d(32), sp, color);
    }

    /** 通道列表：按类别分组，组间留白。 */
    private void drawChannels(Canvas c, Ui ui) {
        int n = Radio.CH_COUNT;
        float inner = body.height() - ui.d(10);
        // 组头占位：4 组各一小行
        float groupHeadH = ui.d(15);
        int groups = 4;
        float rowH = (inner - groupHeadH * groups) / n;

        int[] pct = radio.pctSnapshot();
        int[] us = radio.usSnapshot();

        float y = body.top + ui.d(5);
        int lastGroup = -1;
        for (int i = 0; i < n; i++) {
            int g = groupOf(i);
            if (g != lastGroup) {
                lastGroup = g;
                drawGroupHead(c, ui, y, groupName(g));
                y += groupHeadH;
            }

            float top = y;
            row.set(body.left + ui.d(6), top, body.right - ui.d(6), top + rowH - ui.d(1));
            ChannelConfig cc = radio.mixer().cfg(i);
            boolean active = Math.abs(pct[i]) >= 5;

            // 行底：只有奇数行给极淡的底，形成"斑马纹"但几乎看不见（DJI 不用重底）
            if ((i % 2) == 0) ui.card(c, row, Ui.CARD2);

            float cy = row.centerY() + ui.d(4);
            // 通道号：等宽 + 压暗；名字：正文色
            ui.monoR(c, String.format("%02d", i + 1), row.left + ui.d(24), cy, 11, Ui.FAINT);
            ui.text(c, cc.name, row.left + ui.d(32), cy, 12,
                    active ? Ui.TEXT : Ui.DIM, active);
            ui.text(c, ChannelConfig.srcName(cc.src), row.left + ui.d(120), cy, 10, Ui.FAINT, false);

            // 数值：us 等宽右对齐，pct 紧跟，只有活跃通道上色
            ui.monoR(c, String.valueOf(us[i]), row.left + ui.d(250), cy, 12,
                    active ? Ui.TEXT : Ui.DIM);
            String p = (pct[i] > 0 ? "+" : "") + pct[i];
            ui.monoR(c, p, row.left + ui.d(292), cy, 11,
                    pct[i] > 0 ? Ui.ACC : (pct[i] < 0 ? Ui.BLUE : Ui.FAINT));

            // 条形：中心细线做基准，填充只在偏离时出现
            float bx = row.left + ui.d(306);
            float bw = row.right - bx - ui.d(10);
            float midY = row.centerY();
            barBg.set(bx, midY - ui.d(4), bx + bw, midY + ui.d(4));
            ui.cardR(c, barBg, ui.R_SM, Ui.CARD2);
            float mid = bx + bw / 2f;
            ui.line(c, mid, barBg.top - ui.d(1), mid, barBg.bottom + ui.d(1), Ui.LINE, 1f);
            float v = Math.max(-1f, Math.min(1f, pct[i] / 100f));
            if (Math.abs(v) > 0.02f) {
                float half = (bw / 2f) * v;
                tmp.set(v >= 0 ? mid : mid + half, barBg.top,
                        v >= 0 ? mid + half : mid, barBg.bottom);
                ui.cardR(c, tmp, ui.R_SM, v >= 0 ? Ui.ACC : Ui.BLUE);
            }
            y += rowH;
        }
    }

    private void drawGroupHead(Canvas c, Ui ui, float y, String name) {
        ui.text(c, name, body.left + ui.d(12), y + ui.d(11), 9, Ui.FAINT, true);
        // 组头右侧一条极淡的线，把组和内容连起来
        float tw = ui.w(name, 9, true);
        ui.line(c, body.left + ui.d(14) + tw, y + ui.d(8),
                body.right - ui.d(12), y + ui.d(8), Ui.LINE, 1f);
    }

    /** 通道类别：0=摇杆 1=旋钮 2=按键 3=档位。 */
    private static int groupOf(int i) {
        if (i < 4) return 0;
        if (i < 6) return 1;
        if (i < 10) return 2;
        return 3;
    }

    private static String groupName(int g) {
        switch (g) {
            case 0: return "摇杆";
            case 1: return "旋钮";
            case 2: return "按键";
            default: return "档位";
        }
    }
}
