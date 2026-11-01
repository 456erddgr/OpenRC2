package com.rc2.etx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.rc2.etx.jni.EtxNative;
import com.rc2.etx.radio.OutputSink;
import com.rc2.etx.radio.Radio;

/** 根视图：顶栏（标题/页签/状态）+ 页面区，多指触摸分发给页面。 */
public final class RadioView extends View {

    private final Ui ui;
    private final Radio radio;
    private final Panel[] panels;
    private final RectF[] tabR;
    private final RectF topBar = new RectF();
    private final RectF content = new RectF();
    private final SparseArray<Panel> owners = new SparseArray<Panel>();
    private final SparseArray<Float> lastX = new SparseArray<Float>();
    private int page;
    private int downId = -1;
    /** 触摸/按键 -> 下一帧 onDraw 的延迟测量（纳秒） */
    public static volatile long lastInputNs = 0;
    public static volatile long lastDrawNs = 0;
    public static volatile int drawCount = 0;
    public static volatile boolean skipIdleRedraw = false;
    private int errCount;
    private int dbgN;
    private float downX;
    private float downY;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                // In full screen we DRAW the native panel, so we must TICK the
                // native panel too - ticking panels[page] here left the native core
                // frozen (no input push, no mixer, no LVGL tick) whenever the user
                // entered full screen from another tab.
                Panel pp = full ? panels[IDX_NATIVE] : panels[page];
                pp.tick();
                if (pp.dirty() && !skipIdleRedraw) invalidate();
            } catch (Throwable t) {
                errCount++;
                if (errCount <= 3 || errCount % 300 == 0) android.util.Log.e("ETX-Main", "tick#" + errCount, t);
            }
            h.postDelayed(this, 16);
        }
    };

    public RadioView(Context c, Radio r, Host host) {
        super(c);
        ui = new Ui(getResources().getDisplayMetrics().density);
        radio = r;
        panels = new Panel[]{
                new NativePanel(r, c),
                new ChannelPanel(r),
                new MixPanel(r),
                new OutputPanel(r, c, host),
                new CalibPanel(r),
                new DiagPanel(r, c, host),
                new SetPage(r)
        };
        if (panels.length > 6 && panels[6] instanceof SetPage) {
            final SetPage sp = (SetPage) panels[6];
            sp.setOnCard(new SetPage.OnCard() {
                public void onCard(int idx) {
                    if (idx == SetPage.C_MODEL) {
                        int m = (com.rc2.etx.radio.Store.loadModel(getContext()) + 1) % 3;
                        com.rc2.etx.radio.Store.saveModel(getContext(), m);
                        sp.setValue(SetPage.C_MODEL, com.rc2.etx.radio.Store.modelName(m));
                        if (host != null) host.toast("型号已切换为 " + com.rc2.etx.radio.Store.modelName(m));
                    } else if (idx == SetPage.C_THEME) {
                        String now = com.rc2.etx.sd.ThemePick.next(getContext());
                        sp.setValue(SetPage.C_THEME, now);
                        if (host != null) host.toast("主题已改为 " + now + "，正在重启内核");
                        if (nativePanelRef != null) nativePanelRef.restartCore();
                    } else if (idx == SetPage.C_CAL) {
                        if (panels.length > 4 && panels[4] instanceof CalibPanel) {
                            ((CalibPanel) panels[4]).begin();
                            sp.setValue(SetPage.C_CAL, "校准中");
                        }
                        setPage(4);
                    } else if (idx == SetPage.C_EXPORT) {
                        if (host != null) host.toast("请到「诊断」页点「保存诊断」导出");
                        setPage(5);
                    }
                    invalidate();
                }
            });
            sp.setValue(SetPage.C_MODEL, com.rc2.etx.radio.Store.modelName(
                    com.rc2.etx.radio.Store.loadModel(getContext())));
        }
        if (panels.length > IDX_NATIVE && panels[IDX_NATIVE] instanceof NativePanel) {
            nativePanelRef = (NativePanel) panels[IDX_NATIVE];
        }

        tabR = new RectF[panels.length];
        for (int i = 0; i < tabR.length; i++) tabR[i] = new RectF();
        setBackgroundColor(Ui.BG);
        setFocusable(true);
    }

    public int page() {
        return page;
    }

    /** 自动化调试钩子：直接触发设置页某张卡片（0型号 1主题 2校准 3导出）。 */
    public void fireCard(int idx) {
        if (panels.length > 6 && panels[6] instanceof SetPage) {
            android.util.Log.i("ETX-Set", "fireCard " + idx);
            ((SetPage) panels[6]).fireCardForTest(idx);
        }
        setPage(6);
    }

    public void setPage(int p) {
        if (p >= 0 && p < panels.length) page = p;
        invalidate();
    }

    public Panel panel(int i) {
        return panels[i];
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        h.postDelayed(ticker, 33);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    private float topH;
    private float tabW;
    /** 原生页全屏模式：整个屏幕只显示 libetx.so 的 480x272 画面 */
    public static final int IDX_NATIVE = 0;
    private static final int OPEN_COUNT = 8;
    private boolean full;
    private final RectF exitR = new RectF();
    /** 全屏时的「复位」按钮：原生核心卡死（如 Lua 报错页）时的逃生口。 */
    private final RectF resetR = new RectF();
    /** 非全屏时显示的"全屏"按钮（否则退出全屏后没有路回去）。 */
    private final RectF fullR = new RectF();
    /** 全屏模式下的合成按键条（RC2 没有方向键，只能软件补） */
    private final String[] KEY_LAB = {"↑", "↓", "←", "→", "ENT", "RTN", "PG↑", "PG↓"};
    private final int[] KEY_CODE = {0, 1, 2, 3, 4, 5, 6, 7};
    private final RectF[] keyR = new RectF[8];
    private float stripTop;

    public boolean isFull() { return full; }

    public void setFullNative(boolean v) {
        full = v;
        if (panels.length > IDX_NATIVE && panels[IDX_NATIVE] instanceof NativePanel) {
            ((NativePanel) panels[IDX_NATIVE]).full = v;
        }
        if (v) page = IDX_NATIVE;
        onSizeChanged(getWidth(), getHeight(), 0, 0);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int hh, int oldw, int oldh) {
        super.onSizeChanged(w, hh, oldw, oldh);
        topH = ui.d(46);
        topBar.set(0, 0, w, topH);
        if (full) content.set(0, 0, w, hh); else content.set(0, topH, w, hh);
        exitR.set(w - ui.d(96), ui.d(6), w - ui.d(10), ui.d(38));
        resetR.set(w - ui.d(96), ui.d(44), w - ui.d(10), ui.d(76));
        // 按键改成"左四个 + 右四个"的竖排，**不再占用底部**（底部会挡 EdgeTX 自己的通道条/菜单底边）。
        // 左侧：方向键；右侧：ENT / RTN / PG↑ / PG↓。
        final int kh = (int) ui.d(46);
        final int kw = (int) ui.d(56);
        final int gap = (int) ui.d(8);
        stripTop = hh;
        int totalH = 4 * kh + 3 * gap;
        int y0 = (hh - totalH) / 2;
        for (int i = 0; i < 4; i++) {
            keyR[i] = new RectF(ui.d(6), y0 + i * (kh + gap), ui.d(6) + kw, y0 + i * (kh + gap) + kh);
            int j = i + 4;
            keyR[j] = new RectF(w - ui.d(6) - kw, y0 + i * (kh + gap), w - ui.d(6), y0 + i * (kh + gap) + kh);
        }
        if (false) for (int i = 0; i < KEY_LAB.length; i++) {
            keyR[i] = new RectF(0, 0, 0, 0);
        }
        tabW = Math.min(ui.d(92), (w - ui.d(392)) / panels.length);
        float x0 = ui.d(150);
        for (int i = 0; i < panels.length; i++) {
            tabR[i].set(x0 + i * tabW, ui.d(6), x0 + (i + 1) * tabW - ui.d(6), topH - ui.d(6));
        }
        fullR.set(w - ui.d(86), content.top + ui.d(40), w - ui.d(8), content.top + ui.d(68));
        for (Panel p : panels) p.layout(content);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        drawCount++;
        long now = System.nanoTime();
        if (lastInputNs != 0) { lastDrawNs = (now - lastInputNs) / 1000000L; lastInputNs = 0; }
        if (full) {
            ui.fill(c, new RectF(0, 0, getWidth(), getHeight()), 0xFF000000);
            panels[IDX_NATIVE].draw(c, ui, content);
            // 合成按键条：半透明，压在核心画面底部
            for (int i = 0; i < OPEN_COUNT; i++) {
                if (keyR[i] == null) continue;
                ui.card(c, keyR[i], 0xB31E2A24);
                ui.textC(c, KEY_LAB[i], keyR[i].centerX(), keyR[i].centerY() + ui.d(6), 14, Ui.ACC, true);
            }
            ui.card(c, exitR, 0xCC20312C);
            ui.textC(c, "退出全屏", exitR.centerX(), exitR.centerY() + ui.d(5), 11, Ui.ACC, true);
            ui.card(c, resetR, 0xCC3A2A18);
            ui.textC(c, "复位", resetR.centerX(), resetR.centerY() + ui.d(5), 11, Ui.WARN, true);
            return;
        }
        drawTop(c);
        panels[page].draw(c, ui, content);
        ui.card(c, fullR, 0xCC20312C);
        ui.textC(c, "全屏", fullR.centerX(), fullR.centerY() + ui.d(5), 12, Ui.ACC, true);
    }

    /** 页签图标：用简单几何图形，不依赖图片资源。 */
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void tabIcon(Canvas c, int idx, float cx, float cy, boolean on) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(ui.d(1.6f));
        p.setColor(on ? Ui.ACC : Ui.DIM);
        float r = ui.d(6);
        switch (idx) {
            case 0:  // 原生：屏幕
                c.drawRect(cx - r, cy - r * 0.72f, cx + r, cy + r * 0.72f, p);
                c.drawLine(cx - r * 0.45f, cy - r * 0.72f, cx - r * 0.45f, cy + r * 0.72f, p);
                break;
            case 1:  // 通道：三根竖条
                for (int k = -1; k <= 1; k++)
                    c.drawLine(cx + k * r * 0.7f, cy + r * 0.7f, cx + k * r * 0.7f,
                            cy + (k == 0 ? -r * 0.7f : r * 0.1f), p);
                break;
            case 2:  // 混控：两条滑杆 + 旋钮
                c.drawLine(cx - r, cy - r * 0.45f, cx + r, cy - r * 0.45f, p);
                c.drawLine(cx - r, cy + r * 0.45f, cx + r, cy + r * 0.45f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - r * 0.25f, cy - r * 0.45f, ui.d(2), p);
                c.drawCircle(cx + r * 0.3f, cy + r * 0.45f, ui.d(2), p);
                break;
            case 3:  // 输出：方框 + 箭头
                c.drawRect(cx - r, cy - r * 0.7f, cx + r * 0.3f, cy + r * 0.7f, p);
                c.drawLine(cx + r * 0.1f, cy, cx + r, cy, p);
                c.drawLine(cx + r * 0.55f, cy - r * 0.4f, cx + r, cy, p);
                c.drawLine(cx + r * 0.55f, cy + r * 0.4f, cx + r, cy, p);
                break;
            case 4:  // 校准：十字准星
                c.drawCircle(cx, cy, r * 0.8f, p);
                c.drawLine(cx - r, cy, cx - r * 0.35f, cy, p);
                c.drawLine(cx + r * 0.35f, cy, cx + r, cy, p);
                c.drawLine(cx, cy - r, cx, cy - r * 0.35f, p);
                c.drawLine(cx, cy + r * 0.35f, cx, cy + r, p);
                break;
            default: // 诊断：扳手 -> 斜线 + 圆头
                c.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.4f, cy - r * 0.4f, p);
                c.drawCircle(cx + r * 0.55f, cy - r * 0.55f, r * 0.35f, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** adb 钩子：直接开始推杆校准。 */
    public void debugBeginCalib() {
        for (Panel pn : panels)
            if (pn instanceof CalibPanel) { setPage(indexOf(pn)); ((CalibPanel) pn).begin(); break; }
        invalidate();
    }

    private int indexOf(Panel pn) {
        for (int i = 0; i < panels.length; i++) if (panels[i] == pn) return i;
        return 0;
    }

    private void drawTop(Canvas c) {
        ui.fill(c, topBar, 0xFF12171E);
        ui.line(c, 0, topH, getWidth(), topH, Ui.LINE, 1f);
        ui.text(c, "OpenRC2", ui.d(10), topH * 0.60f, 15, Ui.ACC, true);
        ui.text(c, "开源遥控 · 16CH", ui.d(10), topH * 0.60f + ui.d(13), 9, Ui.DIM, false);
        for (int i = 0; i < panels.length; i++) {
            boolean on = (i == page);
            if (on) ui.card(c, tabR[i], 0xFF1F3A34);
            float icx = tabR[i].centerX() - ui.d(24);
            tabIcon(c, i, icx, tabR[i].centerY(), on);
            ui.text(c, panels[i].title(), icx + ui.d(12), tabR[i].centerY() + ui.d(5), 13,
                    on ? Ui.ACC : Ui.DIM, on);
        }
        OutputSink s = radio.sink();
        String st = s.name() + (s.isOpen() ? " 开 " : " 关 ") + s.frames() + "帧";
        ui.textR(c, st, getWidth() - ui.d(10), topH * 0.42f, 11, s.isOpen() ? Ui.ACC : Ui.WARN, false);
        boolean dumlOk = radio.isDumlOn() && radio.duml.connected;
        String src = !radio.isDumlOn() ? "触摸"
                : (dumlOk ? "机内" + radio.duml.stickHz + "Hz" : "机内连…");
        String st2 = (radio.isRunning() ? radio.hz + "Hz" : "停")
                + " 抖" + radio.maxGapMs + "ms " + src;
        ui.textR(c, st2, getWidth() - ui.d(10), topH * 0.42f + ui.d(13), 10,
                (radio.isDumlOn() && !dumlOk) ? Ui.WARN : Ui.DIM, false);
    }

    /** C 路：把外接手柄的轴事件喂给 GamepadReader（只读，不拦截）。 */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e != null && e.getSource() != 0
                && (e.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) != 0) {
            com.rc2.etx.jni.GamepadReader g = gamepadReader();
            if (g != null) {
                g.noteAxis(e);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    private com.rc2.etx.jni.GamepadReader gamepadReader() {
        if (nativePanelRef != null) return nativePanelRef.gamepadRef();
        return null;
    }

    private com.rc2.etx.ui.NativePanel nativePanelRef;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (full) {
                    if (Ui.in(exitR, x, y)) { setFullNative(false); return true; }
                    if (Ui.in(resetR, x, y)) {
                        for (Panel pn : panels) if (pn instanceof NativePanel) { ((NativePanel) pn).restartCore(); break; }
                        invalidate();
                        return true;
                    }
                    for (int i = 0; i < OPEN_COUNT; i++) {
                        if (keyR[i] != null && Ui.in(keyR[i], x, y)) {
                            NativePanel.queueKey(KEY_CODE[i]);
                            if (panels[IDX_NATIVE] instanceof NativePanel) {
                                ((NativePanel) panels[IDX_NATIVE]).fullPending = true;
                            }
                            invalidate();
                            return true;
                        }
                    }
                    downId = id; downX = x; downY = y;
                    Panel fp = panels[IDX_NATIVE];
                    fp.down(id, x, y);
                    owners.put(id, fp); lastX.put(id, x);
                    return true;
                }
                if (Ui.in(fullR, x, y)) { setFullNative(true); invalidate(); return true; }
                if (y <= topH) {
                    for (int i = 0; i < tabR.length; i++) {
                        if (Ui.in(tabR[i], x, y)) {
                            page = i;
                            invalidate();
                            break;
                        }
                    }
                    return true;
                }
                downId = id;
                downX = x;
                downY = y;
                Panel p = panels[page];
                p.down(id, x, y);
                owners.put(id, p);
                lastX.put(id, x);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    Panel p = owners.get(id);
                    if (p != null) p.move(id, e.getX(i), e.getY(i));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                Panel p = owners.get(id);
                if (p != null) {
                    p.up(id);
                    owners.remove(id);
                }
                lastX.remove(id);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = 0; i < owners.size(); i++) {
                    Panel p = owners.valueAt(i);
                    if (p != null) p.up(owners.keyAt(i));
                }
                owners.clear();
                lastX.clear();
                return true;
            }
            default:
                return true;
        }
    }
}
