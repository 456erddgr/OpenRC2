package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/** 极简主题与绘制工具（不依赖任何 UI 库）。 */
public final class Ui {

    public static final int BG = 0xFF0B0E13;
    public static final int CARD = 0xFF151A22;
    public static final int CARD2 = 0xFF1F2733;
    public static final int LINE = 0xFF2A3441;
    public static final int TEXT = 0xFFE8EEF4;
    public static final int DIM = 0xFF8A97A6;
    public static final int ACC = 0xFFF5B942;
    public static final int BLUE = 0xFF5AA9FF;
    public static final int WARN = 0xFFE6A23C;
    public static final int BAD = 0xFFE5534B;

    public final float density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Typeface cond = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    public final Typeface condBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    public final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);

    public Ui(float density) {
        this.density = density;
        tp.setTextAlign(Paint.Align.LEFT);
    }

    public float d(float v) {
        return v * density;
    }

    public void text(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, x, y, tp);
    }

    public void textR(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, tp);
    }

    public void textC(Canvas c, String s, float cx, float y, float sp, int color, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, y, tp);
    }

    public void monoC(Canvas c, String s, float cx, float y, float sp, int color) {
        tp.setTypeface(mono);
        tp.setTextSize(d(sp));
        tp.setColor(color);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, y, tp);
    }

    public float monoW(String s, float sp) {
        tp.setTypeface(mono);
        tp.setTextSize(d(sp));
        return tp.measureText(s);
    }

    public float w(String s, float sp, boolean bold) {
        tp.setTypeface(bold ? condBold : cond);
        tp.setTextSize(d(sp));
        return tp.measureText(s);
    }

    public void card(Canvas c, RectF b, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(b, d(6), d(6), p);
    }

    public void cardStroke(Canvas c, RectF b, int color, float widthDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(widthDp));
        p.setColor(color);
        c.drawRoundRect(b, d(6), d(6), p);
    }

    public void fill(Canvas c, RectF b, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRect(b, p);
    }

    public void circle(Canvas c, float cx, float cy, float r, int color, boolean stroke, float wdp) {
        p.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        p.setStrokeWidth(d(wdp));
        p.setColor(color);
        c.drawCircle(cx, cy, r, p);
    }

    public void line(Canvas c, float x1, float y1, float x2, float y2, int color, float wdp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d(wdp));
        p.setColor(color);
        c.drawLine(x1, y1, x2, y2, p);
    }

    /** 画一个按钮，返回它的矩形。 */
    public void button(Canvas c, RectF b, String label, int color, boolean filled, float sp) {
        card(c, b, filled ? color : CARD2);
        if (!filled) cardStroke(c, b, color, 1f);
        float cy = b.centerY() + d(sp) * 0.36f;
        textC(c, label, b.centerX(), cy, sp, filled ? 0xFF0B0F14 : color, true);
    }

    public static boolean in(RectF b, float x, float y) {
        return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
    }
}
