package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.rc2.etx.radio.Radio;

/** 可视化设置页：四张大卡片（机型 / 主题 / 校准 / 导出），点卡片执行。 */
public class SetPage extends Panel {
    public interface OnCard { void onCard(int idx); }
    public static final int C_PAD = 0, C_MODEL = 1, C_THEME = 2, C_SMOOTH = 3, C_CAL = 4, C_EXPORT = 5, N = 6;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF[] card = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final String[] head = {"电脑虚拟手柄", "遥控器型号", "界面主题", "摇杆插帧", "摇杆校准", "诊断导出"};
    private final String[] desc = {"开＝把摇杆送到电脑，模拟器里当手柄用",
                                   "自动识别 RC2 / RC Pro，可手动指定",
                                   "跟随 SD 卡 THEMES 目录",
                                   "关/弱/中/强/极强/极速：越往后延迟越低",
                                   "推哪根认哪根，不用选反向",
                                   "导出 sd_probe / hid / native"};
    private final String[] val = {"关", "自动", "v15", "中", "未校准", "点此导出"};
    private final int[] accent = {Ui.OK, Ui.ACC, Ui.ACC, Ui.OK, Ui.ACC, Ui.BLUE};
    private OnCard cb;
    private int pressed = -1, active = -1;

    public SetPage(Radio r) { super(r); }
    @Override public String title() { return "设置"; }
    public void setOnCard(OnCard o) { cb = o; }
    /** 给调试钩子用：等同于用户点了这张卡。 */
    public void fireCardForTest(int i) { if (cb != null) cb.onCard(i); }

    public void setValue(int i, String v) { if (i >= 0 && i < N) val[i] = v; }

    private void put(Canvas c, String s, float x, float y, float sp, int col, boolean bold) {
        txt.setTextSize(sp); txt.setColor(col); txt.setFakeBoldText(bold);
        txt.setTextAlign(Paint.Align.LEFT); c.drawText(s, x, y, txt);
    }

    @Override public void draw(Canvas c, Ui ui, RectF a) {
        float w = a.width(), h = a.height();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.BG);
        c.drawRect(a, fill);
        put(c, "设置", a.left + w * 0.035f, a.top + h * 0.20f, h * 0.095f, Ui.TEXT, true);
        put(c, "全部可视化，点卡片就能改", a.left + w * 0.035f, a.top + h * 0.265f, h * 0.048f,
                Ui.FAINT, false);
        float pad = w * 0.035f, top = a.top + h * 0.32f, gap = h * 0.022f;
        float ch = (h * 0.60f - gap * (N - 1)) / (float) N, cw = w - pad * 2;
        float rad = ui.d(Ui.R_MD);
        for (int i = 0; i < N; i++) {
            float y = top + i * (ch + gap);
            card[i].set(a.left + pad, y, a.left + pad + cw, y + ch);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(pressed == i ? Ui.CARD2 : Ui.CARD);
            c.drawRoundRect(card[i], rad, rad, fill);
            // 2026-11-07：原来左侧一条 8px 宽的彩色竖条（accident 式的"侧边色块"），
            // 设计规范明确禁止 —— 那是廉价装饰，还把卡片切成两半。
            // 改成一个 6dp 的小圆点：只承担"这是哪一类"的语义，不抢视线。
            float cy = card[i].centerY();
            ui.dot(c, card[i].left + pad * 0.62f, cy, ui.d(2.6f), accent[i]);

            float x = card[i].left + pad + ui.d(16), base = card[i].top + ch * 0.44f;
            put(c, head[i], x, base, ch * 0.30f, Ui.TEXT, true);
            put(c, desc[i], x, base + ch * 0.30f, ch * 0.22f, Ui.FAINT, false);
            String v = val[i];
            txt.setTextSize(ch * 0.26f); txt.setFakeBoldText(true);
            float tw = txt.measureText(v);
            RectF pill = new RectF(card[i].right - pad - tw - w * 0.06f, card[i].top + ch * 0.26f,
                    card[i].right - pad, card[i].bottom - ch * 0.26f);
            fill.setColor(pressed == i ? Ui.CARD3 : Ui.CARD2);
            c.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, fill);
            txt.setColor(Ui.ACC); txt.setTextAlign(Paint.Align.CENTER);
            c.drawText(v, pill.centerX(), pill.centerY() + ch * 0.09f, txt);
            txt.setTextAlign(Paint.Align.LEFT); txt.setFakeBoldText(false);
        }
    }

    @Override public boolean down(int id, float x, float y) {
        active = id; pressed = hit(x, y); return pressed >= 0;
    }
    @Override public void up(int id) {
        int p = pressed; pressed = -1;
        if (p >= 0 && p == hit(lastX, lastY) && cb != null) cb.onCard(p);
        active = -1;
    }
    private float lastX, lastY;
    @Override public void move(int id, float x, float y) { lastX = x; lastY = y; }
    private int hit(float x, float y) {
        lastX = x; lastY = y;
        for (int i = 0; i < N; i++) if (card[i].contains(x, y)) return i;
        return -1;
    }
}
