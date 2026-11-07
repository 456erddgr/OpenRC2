package com.rc2.etx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 启动画面（应用级，不改系统 bootanimation.zip）：
 * 深色底 + 图标 + 名字 + 进度条，1.6 秒后淡出并移除自己。
 * 系统开机动画要换得改 /system/media，属于 ROM 级操作，单独另说。
 */
public class Splash extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final RectF bar = new RectF();
    private final DecelerateInterpolator ease = new DecelerateInterpolator();
    private long t0 = System.currentTimeMillis();
    private final float d;
    private Runnable onDone;
    /** 外部可替换的结束回调（构造传 null 后用这个）。 */
    public void setOnFinish(Runnable r) { this.onDone = r; }
    private boolean removed = false;

    public Splash(Context c, Drawable icon, Runnable onDone) {
        super(c);
        this.icon = icon;
        this.onDone = onDone;
        this.d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Ui.BG);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = Math.min(1f, (System.currentTimeMillis() - t0) / 1600f);
        float a = t < 0.75f ? 1f : 1f - (t - 0.75f) / 0.25f;

        p.setColor(Ui.BG);
        c.drawRect(0, 0, w, h, p);

        int alpha = (int) (255 * a);
        if (icon != null) {
            int s = (int) (h * 0.22f);
            int x = (w - s) / 2, y = (int) (h * 0.32f);
            icon.setBounds(x, y, x + s, y + s);
            icon.setAlpha(alpha);
            icon.draw(c);
        }

        p.setColor(Color.WHITE);
        p.setAlpha(alpha);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(h * 0.065f);
        c.drawText("OpenRC2", w / 2f, h * 0.66f, p);

        p.setFakeBoldText(false);
        p.setColor(Ui.ACC);
        p.setTextSize(h * 0.038f);
        c.drawText("开源遥控 · EdgeTX 2.12.2", w / 2f, h * 0.72f, p);

        float bw = w * 0.30f, bh = Math.max(3f, h * 0.008f);
        bar.set((w - bw) / 2f, h * 0.80f, (w - bw) / 2f + bw * ease.getInterpolation(Math.min(1f, t * 1.15f)), h * 0.80f + bh);
        p.setColor(Ui.CARD2);
        c.drawRoundRect(new RectF((w - bw) / 2f, h * 0.80f, (w + bw) / 2f, h * 0.80f + bh), bh / 2, bh / 2, p);
        p.setColor(Ui.ACC);
        p.setAlpha(alpha);
        c.drawRoundRect(bar, bh / 2, bh / 2, p);

        if (t < 1f && !removed) {
            postInvalidateDelayed(16);
        } else if (!removed) {
            removed = true;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() { if (onDone != null) onDone.run(); }
            });
        }
    }
}
