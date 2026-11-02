package com.rc2.etx.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Locale;

import com.rc2.etx.jni.EtxNative;
import com.rc2.etx.radio.Radio;

/** 原生页：把 libetx.so（真 EdgeTX 核心 + LVGL）渲染出来的帧贴到这块画布上。
 *  so 没就位时显示占位说明，不影响其它页。 */
public class NativePanel extends Panel {

    private final Context ctx;
    private Bitmap bmp;
    private int[] argb;
    // 2 倍超采样（EPX/Scale2x）：480x272 -> 960x544 再做边缘保持放大，比纯最近邻少锯齿、比双线性不发虚
    private int[] ss2;
    private Bitmap bmpSS;
    private int nwSS, nhSS;
    private int nw, nh;
    private boolean inited, first = true;
    private String note = "点一下初始化";
    /** 全屏模式由 RadioView 置 true：画面铺满，不画统计行 */
    public boolean full;
    private long tickCostNs, renderCostNs;
    private int frames;
    private int tapCount;
    private long maxTickNs, maxRenderNs, maxFullNs;
    /** 本帧像素是否变化 */
    private boolean dirtyFrame = false;
    /** C 路：外接 USB/蓝牙手柄读取器（只读，不拦截系统输入）。 */
    private com.rc2.etx.jni.GamepadReader gamepad;
    /** 手柄轴是否优先于机内摇杆。 */
    public boolean gamepadWins = false;
    /** 上一帧的墙上时钟，用来算真实经过的毫秒数 */
    private long lastTickWall = 0;
    /** 累计传给原生的 tick 毫秒（用于核对与真实时间的偏差） */
    public static volatile long fedMs = 0;
    private int fullCount;
    /** 触摸发出时刻（纳秒），用来量 touch->新帧 的延迟 */
    public static volatile long pendingTouchT0 = 0;
    /** 最近一次 touch->新帧 的实测延迟（毫秒） */
    public volatile long lastTouchLatencyMs = -1;
    /** 实例值的静态镜像，供 MainActivity 读 */
    public static volatile long lastLatencyMs = -1;
    /** 是否强制整屏重绘（可切换做 A/B） */
    /** 是否允许进入原生全屏。默认关：以前默认开，导致每 120 帧自动弹进全屏，
     *  用户看到的「点不出去 / 一直卡在全屏」就是它。只有点「全屏」按钮才进。 */
    public static boolean FORCE_FULL = false;

    /**
     * 原生触摸开关。
     * 2026-11-01 上午：默认关闭，因为当时 so 里"点完不重绘"，用户看起来是"触摸完全没反应"。
     * 下午上游修好两件事后重新打开：
     *  1) 行号改为从 EdgeTX 自己的 Table 取（不再按几何猜），点哪行 → Table 选中行 = 命中行；
     *  2) 命中后 lv_obj_invalidate，画面会重绘（焦点框跳到手指那一行）。
     * 语义仍是 B：**点行 = 只选中，不进入**；进入与改值走 ENTER/方向键/旋钮（etx_rotary）。
     */
    public static boolean NATIVE_TOUCH = true;

    /** 排队的按键：先 down，等 releaseIn 帧后 up —— 避免"同帧 down+up 被轮询漏掉"。 */
    private static int heldKey = -1;
    private static int releaseIn = 0;

    public static void queueKey(int k) {
        if (heldKey >= 0) EtxNative.release(heldKey);
        EtxNative.press(k);
        heldKey = k;
        releaseIn = 2;
    }

    private static void pumpHeldKey() {
        if (heldKey < 0) return;
        if (--releaseIn <= 0) {
            EtxNative.release(heldKey);
            heldKey = -1;
        }
    }
    private int vbatTick = 29;  // 首帧就推一次电压，别让状态栏先显示 0.0V
    /** 下一次 tick 是否强制整屏重绘（切屏/按键/触摸后）。 */
    public volatile boolean fullPending = false;
    private final int[] axes7 = new int[7];

    private final RectF dst = new RectF();
    private final Paint paint = new Paint();   // 最近邻：480x272 放大到 1920x1080 保持像素锐利（双线性会糊）

    private final int[] axes = new int[7];
    private final int[] sw = new int[4];
    private final int[] knobs = new int[2];
    private final int[] knobsLocal = new int[2];
    private final int[] chUs = new int[16];
    private float lastX, lastY;

    public NativePanel(Radio r, Context c) {
        super(r);
        ctx = c;
    }

    @Override
    public String title() {
        return "原生";
    }

    private int toAdc(float v) {
        int x = (int) (1024f + v * 660f);
        if (x < 0) x = 0;
        if (x > 2047) x = 2047;
        return x;
    }


    /** 把 EdgeTX 的当前设置写回 radio.yml（3 次 RTN + 跑帧，让 EdgeTX 自己落盘）。 */
    public void flushSettings() {
        try {
            for (int i = 0; i < 3; i++) {
                queueKey(5);
                tick();
                Thread.sleep(40);
            }
            android.util.Log.i("ETX-Flush", "settings flushed to radio.yml");
        } catch (Throwable t) {
            android.util.Log.w("ETX-Flush", "flush failed: " + t);
        }
    }

    /** 强制重启原生核心：退出 Lua 报错页、或界面卡死时的兜底入口。 */
    public void restartCore() {
        // 先把设置刷盘：EdgeTX 是在“离开页面/按 RTN”时写 radio.yml 的，
        // 直接 shutdown 会让用户在系统设置里改的东西丢掉（用户报的就是这个）。
        try {
            for (int i = 0; i < 3; i++) {
                queueKey(5);          // 5 = RTN
                tick();
                Thread.sleep(40);
            }
        } catch (Throwable ignored) { }
        try { EtxNative.shutdown(); } catch (Throwable ignored) { }
        inited = false;
        bmp = null;
        argb = null;
        bmpSS = null;
        ss2 = null;
        note = "正在重启原生核心…";
        ensureInit();
    }

    private void ensureInit() {
        if (inited) return;
        inited = true;
        if (!EtxNative.available()) {
            note = "libetx.so 还没编好\n" + EtxNative.error();
            return;
        }
        try {
            // 首次运行先把 sd 骨架铺出来：新机器（RC Pro / 手机）sd/ 是空的，
            // 空目录会让 etx_init 直接返回 -1，界面卡在占位页。
            String root = ctx.getExternalFilesDir(null).getAbsolutePath();
            com.rc2.etx.sd.SdSeed.ensure(ctx);
            int rc = EtxNative.init(root + "/sd");
            com.rc2.etx.sd.SdSeed.writeProbe(new java.io.File(root + "/sd"), rc,
                    "abi=" + android.os.Build.SUPPORTED_ABIS[0] + " model=" + android.os.Build.MODEL);
            nw = EtxNative.screenW();
            nh = EtxNative.screenH();
            if (rc != 0 || nw <= 0 || nh <= 0) {
                note = "etx_init 失败 rc=" + rc + "  屏幕=" + nw + "x" + nh;
                return;
            }
            argb = new int[nw * nh];
            bmp = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
            nwSS = nw * 2; nhSS = nh * 2;
            ss2 = new int[nwSS * nhSS];
            bmpSS = Bitmap.createBitmap(nwSS, nhSS, Bitmap.Config.ARGB_8888);
            note = "EdgeTX " + EtxNative.version() + "  " + nw + "x" + nh;
            first = true;
            try {
                gamepad = new com.rc2.etx.jni.GamepadReader();
            } catch (Throwable t2) {
                gamepad = null;
            }
        } catch (Throwable t) {
            note = "初始化异常: " + t.getClass().getSimpleName();
        }
    }

    private int dbgTick;
    private int excN;

    @Override
    public void tick() {
        ensureInit();
        dirtyFrame = false;
        if (bmp == null) return;
        // 直接给硬件原始 11bit ADC（0..2047），由原生核心按 radio.yml 自行校准
        radio.fillNativeAxes(axes, knobsLocal);
        sw[0] = radio.input.aux1 > 0.3f ? 1 : 0;
        sw[1] = radio.input.aux2 > 0.3f ? 1 : 0;
        sw[2] = radio.input.aux3 > 0.3f ? 1 : 0;
        sw[3] = radio.input.aux4 > 0.3f ? 1 : 0;
        knobs[0] = knobsLocal[0];
        knobs[1] = knobsLocal[1];
        // 每约 1 秒把系统电池电压推给核心（V15 状态栏要显示真实电压）
        if (++vbatTick >= 30) {
            vbatTick = 0;
            EtxNative.setVbat(radio.vbatMv);
        }
        try {
            long t0 = System.nanoTime();
            if (gamepad != null && gamepad.hasDevice()) {
                // C 路：外接手柄优先（轴已按固件校准窗口换算过）
                gamepad.pollAndPush();
                gamepadWins = true;
            } else {
                if (gamepad != null) gamepadWins = false;
                if (radio.nativeAxes7 && axes7 != null) {
                    radio.fillNativeAxes7(axes7);
                    EtxNative.setInputs(axes7, 7, sw, 4, knobs, 2);
                } else {
                    EtxNative.setInputs(axes, 4, sw, 4, knobs, 2);
                }
            }
            // 传真实经过的毫秒数：原来硬编码 33，ticker 改 16ms 后会让 EdgeTX 内部时钟跑 2 倍速，
            // 而且每帧会跑 3 次 per10ms()（=每帧 30ms 的工作量），白白多花 CPU。
            long wall = System.currentTimeMillis();
            int elapsed = 33;
            if (lastTickWall != 0) {
                long d = wall - lastTickWall;
                if (d > 0 && d < 250) elapsed = (int) d;
            }
            lastTickWall = wall;
            fedMs += elapsed;
            int newFrame = EtxNative.tick(elapsed);
            long t1 = System.nanoTime();
            // 首帧不走 full：必须先有至少一次 tick 让 LVGL 建立刷新状态，否则 lv_obj_invalidate(NULL) 会崩
            boolean wantFull = FORCE_FULL && frames > 0 && fullPending;
            if (wantFull) {
                first = false;
                fullPending = false;
                long f0 = System.nanoTime();
                EtxNative.full(argb, nw, nh);
                bmp.setPixels(argb, 0, nw, 0, 0, nw, nh);
                dirtyFrame = true;
                long f1 = System.nanoTime();
                if (f1 - f0 > maxFullNs) maxFullNs = f1 - f0;
                fullCount++;
                frames++;
                if (pendingTouchT0 != 0) { lastTouchLatencyMs = (f1 - pendingTouchT0) / 1000000L; lastLatencyMs = lastTouchLatencyMs; pendingTouchT0 = 0; }
            } else if (newFrame != 0) {
                long r0 = System.nanoTime();
                boolean drew = EtxNative.render(argb, nw, nh) != 0;
                if (drew && ss2 != null) { scale2x(argb, nw, nh, ss2); bmpSS.setPixels(ss2, 0, nwSS, 0, 0, nwSS, nhSS); }
                if (drew) {
                    bmp.setPixels(argb, 0, nw, 0, 0, nw, nh);
                    dirtyFrame = true;
                }
                long r1 = System.nanoTime();
                if (r1 - r0 > maxRenderNs) maxRenderNs = r1 - r0;
                frames++;
                if (drew && pendingTouchT0 != 0) { lastTouchLatencyMs = (r1 - pendingTouchT0) / 1000000L; lastLatencyMs = lastTouchLatencyMs; pendingTouchT0 = 0; }
            }
            try {
                EtxNative.channelsUs(radio.hidUs);
                radio.hidUsValid = true;
            } catch (Throwable ignore) { }
            pumpHeldKey();
        // Plan D：核心只报"点在第几行"，按键由这里（Java 主线程、render 之后）发，
            // 与现在能开菜单的调用路径完全一致；一次点击只消费一次。
            int tapRow = EtxNative.pendingTap();
            if (tapRow >= 0) {
                EtxNative.tapKey(4);
                tapCount++;
            }
            long t2 = System.nanoTime();
            // 指数滑动平均，避免数字跳动
            if (t1 - t0 > maxTickNs) maxTickNs = t1 - t0;
            tickCostNs = tickCostNs == 0 ? (t1 - t0) : (tickCostNs * 7 + (t1 - t0)) / 8;
            renderCostNs = renderCostNs == 0 ? (t2 - t1) : (renderCostNs * 7 + (t2 - t1)) / 8;
            note = String.format(Locale.US, "EdgeTX %s  %dx%d  tick %.2f/%.2fms  render %.2f/%.2fms  full %.2fms x%d  触摸延迟 %dms  %d 帧",
                    EtxNative.version(), nw, nh, tickCostNs / 1e6f, maxTickNs / 1e6f,
                    renderCostNs / 1e6f, maxRenderNs / 1e6f, maxFullNs / 1e6f, fullCount,
                    lastTouchLatencyMs, frames);
        } catch (Throwable t) {
            note = "tick 异常: " + t.getClass().getSimpleName();
            if (excN++ < 3) android.util.Log.e("ETX-Main", "tick exc " + frames, t);
        }
    }

    /** 只有原生像素变了才需要重画（否则每 16ms 白白缩放一次位图）。
     *  非全屏时右上角统计行每帧都在变，所以那种模式下始终重画。 */
    /** 供 RadioView 的 onGenericMotionEvent 拿到同一个 reader 实例。 */
    public com.rc2.etx.jni.GamepadReader gamepadRef() { return gamepad; }

    /** 只有原生像素变了才需要重画（否则每 16ms 白白缩放一次位图）。
     *  非全屏时右上角统计行每帧都在变，所以那种模式下始终重画。 */
    @Override
    public boolean dirty() {
        return dirtyFrame || !full;
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        ui.card(c, a, Ui.CARD);
        float pad = ui.d(10);
        RectF body = new RectF(a.left + pad, a.top + pad, a.right - pad, a.bottom - pad);
        if (bmp == null) {
            ui.textC(c, "EdgeTX 原生核心", body.centerX(), body.top + ui.d(60), 22, Ui.TEXT, true);
            ui.textC(c, "这条路是把 radio/src 的真 C++ 混控与 LVGL 界面编成 libetx.so", body.centerX(),
                    body.top + ui.d(96), 12, Ui.DIM, false);
            ui.textC(c, note, body.centerX(), body.top + ui.d(124), 13,
                    note.startsWith("libetx") ? Ui.WARN : Ui.BAD, true);
            ui.textC(c, "放好 so 后点此页任意处初始化", body.centerX(), body.top + ui.d(154), 12, Ui.DIM, false);
            return;
        }
        // 全屏时铺满整块（cover），非全屏时等比留边（fit）
        float sx = body.width() / nw;
        float sy = body.height() / nh;
        // 全屏才 cover（铺满不留边）；分屏必须 fit（等比缩放留边），否则会溢出盖住页签栏
        float s = full ? Math.max(sx, sy) : Math.min(sx, sy);
        float w = nw * s, h = nh * s;
        float l = body.centerX() - w / 2, t = body.centerY() - h / 2;
        dst.set(l, t, l + w, t + h);
        // 一律最近邻：480x272 放大到 1920x1080 绝对不能开双线性，否则一定糊
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        // 硬裁到卡片内，任何缩放都不能画到页签栏/状态栏上
        int save = c.save();
        c.clipRect(body);
        Bitmap src = (bmpSS != null) ? bmpSS : bmp;
        c.drawBitmap(src, null, dst, paint);
        c.restoreToCount(save);
        ui.cardStroke(c, dst, Ui.LINE, 1);
        if (!full) ui.textR(c, note, a.right - ui.d(8), a.top + ui.d(14), 10, Ui.DIM, false);
    }

    /** EPX / Scale2x：每个像素按 4 邻域生成 2x2，斜边更平滑，但不像双线性那样糊。 */
    private static void scale2x(int[] src, int w, int h, int[] out) {
        final int W = w * 2;
        for (int y = 0; y < h; y++) {
            final int yUp = (y > 0 ? y - 1 : 0) * w;
            final int yDn = (y < h - 1 ? y + 1 : h - 1) * w;
            final int yc = y * w;
            for (int x = 0; x < w; x++) {
                final int xL = x > 0 ? x - 1 : 0;
                final int xR = x < w - 1 ? x + 1 : w - 1;
                final int P = src[yc + x];
                final int A = src[yUp + x];
                final int B = src[yc + xR];
                final int C = src[yc + xL];
                final int D = src[yDn + x];
                int e0 = P, e1 = P, e2 = P, e3 = P;
                if (C == A && C != D && A != B) e0 = A;
                if (A == B && A != C && B != D) e1 = B;
                if (D == C && D != B && C != A) e2 = C;
                if (B == D && B != A && D != C) e3 = D;
                final int o = (y * 2) * W + x * 2;
                out[o] = e0; out[o + 1] = e1;
                out[o + W] = e2; out[o + W + 1] = e3;
            }
        }
    }

    private boolean hit(float x, float y, float[] out) {
        if (dst.isEmpty()) return false;
        if (x < dst.left || x > dst.right || y < dst.top || y > dst.bottom) return false;
        out[0] = (x - dst.left) / dst.width() * nw;
        out[1] = (y - dst.top) / dst.height() * nh;
        return true;
    }

    private final float[] pt = new float[2];

    @Override
    public boolean down(int id, float x, float y) {
        ensureInit();
        if (bmp == null) {
            inited = false;
            ensureInit();
            return true;
        }
        if (!NATIVE_TOUCH) return false;
        if (!hit(x, y, pt)) return false;
        lastX = pt[0];
        lastY = pt[1];
        try {
            EtxNative.touch(0, (int) pt[0], (int) pt[1]);
            pendingTouchT0 = System.nanoTime();
            com.rc2.etx.ui.RadioView.lastInputNs = System.nanoTime();
            fullPending = true;
        } catch (Throwable ignore) {
        }
        return true;
    }

    @Override
    public void move(int id, float x, float y) {
        if (bmp == null) return;
        if (!NATIVE_TOUCH) return;
        if (!hit(x, y, pt)) return;
        lastX = pt[0];
        lastY = pt[1];
        try {
            EtxNative.touch(1, (int) pt[0], (int) pt[1]);
            fullPending = true;
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void up(int id) {
        if (!NATIVE_TOUCH) return;
        if (bmp == null) return;
        try {
            EtxNative.touch(2, (int) lastX, (int) lastY);
            fullPending = true;
        } catch (Throwable ignore) {
        }
    }
}
