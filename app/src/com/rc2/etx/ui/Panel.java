package com.rc2.etx.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.Radio;

/** 一个页面。所有绘制/触摸都在这个抽象上，RadioView 只负责分发。 */
public abstract class Panel {

    protected final Radio radio;
    protected final RectF area = new RectF();

    protected Panel(Radio r) {
        radio = r;
    }

    public abstract String title();

    public final void layout(RectF a) {
        area.set(a);
    }

    public abstract void draw(Canvas c, Ui ui, RectF a);

    /** 返回 true 表示这个 pointer 归本页处理。 */
    public boolean down(int id, float x, float y) {
        return false;
    }

    public void move(int id, float x, float y) {
    }

    public void up(int id) {
    }

    /** 30fps 回调。 */
    public void tick() {
    }

    /**
     * 本帧是否需要重画。默认 true（保持自绘页的动画）。
     * 重页面（原生位图）覆写成 false，只有像素真的变了才置 true，
     * 省掉每 16ms 一次的 1920x1080 Canvas + 位图缩放。
     */
    public boolean dirty() {
        return true;
    }
}
