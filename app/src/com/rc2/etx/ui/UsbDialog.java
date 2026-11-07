package com.rc2.etx.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** OpenRC2 风格的 USB 选择弹窗（自绘，不用系统 AlertDialog 外观）。 */
public class UsbDialog extends Dialog {

    public interface Pick { void onPick(int which); }

    private static final String[] TITLE = {
            "虚拟手柄（电脑端用，无需 root）",
            "游戏手柄（USB HID，需 root）",
            "文件存储 / USB 调试",
            "串口（暂不支持）"
    };
    private static final String[] DESC = {
            "摇杆直连电脑，模拟器直接认成手柄",
            "把遥控器变成 USB 手柄（要一次 root）",
            "手机助手 / 传文件 / adb（默认）",
            "还没做"
    };
    private static final int C_BG = Ui.CARD;
    private static final int C_LINE = Ui.LINE;
    private static final int C_TXT = Ui.TEXT;
    private static final int C_DIM = Ui.DIM;
    private static final int C_ACC = Ui.ACC;
    private static final int C_WARN = Ui.BAD;

    private final float d;
    private final Pick pick;
    private final RectF[] rows = new RectF[4];
    private final RectF cancelR = new RectF();
    private int pressed = -1;

    public UsbDialog(Context ctx, Pick p) {
        super(ctx);
        this.pick = p;
        this.d = ctx.getResources().getDisplayMetrics().density;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(new Body(ctx));
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            w.setAttributes(lp);
        }
        setCanceledOnTouchOutside(true);
    }

    private float dp(float v) { return v * d; }

    private class Body extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

        Body(Context c) { super(c); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = dp(16);
            float cardW = Math.min(dp(330), w - dp(60));
            float left = (w - cardW) / 2f;
            float top = dp(20);
            float y = top + dp(74);
            float rowH = dp(56);
            for (int i = 0; i < 4; i++) {
                rows[i] = new RectF(left + pad, y, left + cardW - pad, y + rowH);
                y += rowH + dp(5);
            }
            cancelR.set(left + pad, y + dp(8), left + cardW - pad, y + dp(8) + dp(32));
            card = new RectF(left, top, left + cardW, cancelR.bottom + dp(10));
        }

        private RectF card = new RectF();

        @Override protected void onDraw(Canvas c) {
            p.setColor(0xFF000000); p.setAlpha(0);
            // 卡片
            p.setColor(C_BG); p.setAlpha(250);
            c.drawRoundRect(card, dp(16), dp(16), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(C_LINE);
            c.drawRoundRect(card, dp(16), dp(16), p); p.setStyle(Paint.Style.FILL);

            // 标题
            tp.setColor(C_TXT); tp.setTextSize(dp(16.5f)); tp.setFakeBoldText(true);
            c.drawText("USB 已连接", card.left + dp(16), card.top + dp(30), tp);
            tp.setFakeBoldText(false); tp.setColor(C_DIM); tp.setTextSize(dp(11.5f));
            c.drawText("选择这根线现在要做什么", card.left + dp(16), card.top + dp(47), tp);
            p.setColor(C_ACC); p.setAlpha(40);
            c.drawRoundRect(new RectF(card.left + dp(16), card.top + dp(57), card.right - dp(16), card.top + dp(58.5f)), 0, 0, p);

            // 四行：0=虚拟手柄 1=USB HID 2=存储/adb 3=串口
            for (int i = 0; i < 4; i++) {
                RectF r = rows[i];
                boolean hot = (pressed == i);
                p.setColor(hot ? Ui.CARD2 : Ui.CARD);
                c.drawRoundRect(r, dp(12), dp(12), p);
                // 左侧色条
                p.setColor(i == 0 ? Ui.OK : (i == 1 ? C_ACC : (i == 3 ? C_WARN : Ui.BLUE)));
                c.drawRoundRect(new RectF(r.left + dp(8), r.top + dp(10), r.left + dp(11), r.bottom - dp(10)), dp(2), dp(2), p);
                tp.setColor(C_TXT); tp.setTextSize(dp(12.5f)); tp.setFakeBoldText(true);
                c.drawText(TITLE[i], r.left + dp(18), r.top + dp(23), tp);
                tp.setFakeBoldText(false); tp.setColor(C_DIM); tp.setTextSize(dp(9.5f));
                c.drawText(DESC[i], r.left + dp(18), r.top + dp(41), tp);
            }

            // 取消
            tp.setColor(C_DIM); tp.setTextSize(dp(12.5f));
            c.drawText("取消", cancelR.centerX() - tp.measureText("取消") / 2f, cancelR.centerY() + dp(4), tp);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                pressed = -1;
                for (int i = 0; i < 4; i++) if (rows[i] != null && rows[i].contains(x, y)) pressed = i;
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int hit = pressed;
                pressed = -1;
                invalidate();
                if (hit >= 0) {
                    dismiss();
                    if (pick != null) pick.onPick(hit);
                } else if (cancelR.contains(x, y)) {
                    dismiss();
                    if (pick != null) pick.onPick(-1);
                }
                return true;
            }
            return true;
        }
    }
}
