package com.rc2.etx.input;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * DJI RC Pro (RM510) 摇杆源：走 binder 到 DJI 私有服务 "protocol"。
 *
 * 为什么需要单独的进程：DJI 的白名单是按**进程名**匹配的（不是 uid）。
 * 实测把组件放到 android:process="com.dpad.fuli" 就能拿到 IProtocolManager 的 binder。
 * 所以真正拼 Parcel 的代码在 RcProService（跑在那个进程里），这里只负责绑它、收值。
 *
 * 数据口径（与 RM510 对接包一致）：6 个通道，0..2048，中位 1024，物理行程 ±660。
 *   ch0 右摇杆水平 / ch1 右摇杆竖直 / ch2 左摇杆竖直 / ch3 左摇杆水平 / ch4 左波轮 / ch5 右波轮
 *
 * 2026-11-03 修复「RC Pro 移植系统六轴全 -100%、怎么动都不动」：
 *  1) raw 初值原来是 new int[6]（**全 0**）。Radio 的桥线程只要 connected==true 就把
 *     raw 整体拷进 duml.raw，而 duml 的归一化是 (raw-1024)/660 ——
 *     全 0 正好等于「六轴全部推到底」= 每个通道 -100%，
 *     而且之后每一帧都还是 0，所以杆子怎么动都不动。现在初值是中位 1024。
 *  2) 新增"载荷可信度"校验：6 个通道同时精确为 0 在物理上不可能（回中值是 1024），
 *     那是**载荷没解析出来**的签名。这种帧记 badFrames 但不发布，
 *     免得把"解析失败"伪装成"摇杆到底"。
 *  3) 新增 lastFrameMs / fresh(ms)：上层判断数据源存活不能再依赖 connected
 *     （RC Pro 的 TCP 是"连一次给一帧就断"，connected 绝大部分时间是 false）。
 */
public final class RcProStick {

    private static final String TAG = "ETXRcPro";
    public static final int AXES = 6;
    /** DJI 通道中位。 */
    public static final int MID = 1024;

    /** 最近一帧通道值（0..2048）。整体替换，避免读到半帧。 */
    public volatile int[] raw = new int[AXES];
    public volatile boolean connected;
    public volatile long frames;
    /** 被判为"载荷不可信"而丢弃的帧数（诊断用）。 */
    public volatile long badFrames;
    /** 最近一次收到**可信**通道帧的时间（SystemClock.elapsedRealtime，单调、含休眠）。 */
    public volatile long lastFrameMs;
    /** 最近一帧的 6 通道（原始值，便于诊断页/日志直接对照）。 */
    public volatile String lastValues = "-";
    /** 旁键（06/174）最近一次载荷 p0/p1/p2 与年龄。 */
    public volatile int btn0, btn1, btn2;
    public volatile long btnMs;
    public volatile long btnFrames;
    public volatile String lastBtnHex = "-";
    public volatile String error = "-";

    private Context ctx;
    private Messenger remote;
    private boolean bound;

    private final Messenger inbox = new Messenger(new Handler() {
        @Override public void handleMessage(Message msg) {
            if (msg.what == RcProService.MSG_VALUES && msg.obj instanceof Bundle) {
                Bundle b = (Bundle) msg.obj;
                int[] v = b.getIntArray(RcProService.KEY_VALUES);
                if (v != null && v.length >= AXES) {
                    int[] copy = new int[AXES];
                    System.arraycopy(v, 0, copy, 0, AXES);
                    // 全 0 = 载荷没解析出来的签名（回中应该是 1024），丢弃不发布。
                    // 详见类注释第 2 条：这是"六轴全 -100%"的直接成因。
                    if (isAllZero(copy)) {
                        badFrames++;
                        if (badFrames == 1 || badFrames % 500 == 0) {
                            Log.w(TAG, "丢弃全 0 通道帧 x" + badFrames
                                    + "（载荷未解析，长度/布局可能变了）");
                        }
                        return;
                    }
                    raw = copy;
                    lastFrameMs = android.os.SystemClock.elapsedRealtime();
                    connected = true;
                    frames++;
                    if (frames <= 3 || frames % 600 == 0) {
                        lastValues = copy[0] + "," + copy[1] + "," + copy[2] + ","
                                + copy[3] + "," + copy[4] + "," + copy[5];
                        Log.i(TAG, "chan=" + lastValues + " frames=" + frames);
                    }
                }
            } else if (msg.what == RcProService.MSG_BTN && msg.obj instanceof Bundle) {
                Bundle b = (Bundle) msg.obj;
                btn0 = b.getInt(RcProService.KEY_B0, 0);
                btn1 = b.getInt(RcProService.KEY_B1, 0);
                btn2 = b.getInt(RcProService.KEY_B2, 0);
                lastBtnHex = b.getString(RcProService.KEY_HEX, "-");
                btnMs = android.os.SystemClock.elapsedRealtime();
                btnFrames++;
                if (btnFrames <= 3) {
                    Log.i(TAG, "btn p0=" + btn0 + " p1=" + btn1 + " p2=" + btn2
                            + " hex=" + lastBtnHex);
                }
            } else if (msg.what == RcProService.MSG_ERROR) {
                error = msg.obj == null ? "?" : String.valueOf(msg.obj);
                // 注意：这里不再把 connected 置 false。
                // 一次 transact 失败不等于数据源死了（RC Pro 上偶尔会失败一帧），
                // 用 fresh() 的时间窗口来判断才准；否则会出现"抖一下就连带把
                // 整条输入链判死"（= 又回到六轴全 -100% 的老症状）。
            }
        }
    });

    /** 服务是否已连上（探针要等它才能在服务进程里跑扫描）。 */
    public boolean isBound() { return remote != null && bound; }

    /** 探针：让服务进程执行一次 cmdId 扫描（主进程拿不到 protocol binder）。 */
    public boolean requestScan(int cs, int from, int to) {
        return sendCmd(RcProService.MSG_SCAN, cs, from, 0, to);
    }

    /** 探针：只注册一次回调，静默 sec 秒等推送。 */
    public boolean requestListen(int cs, int id, int sec) {
        if (remote == null) { Log.w(TAG, "requestListen: 服务未连接"); return false; }
        try {
            Message m = Message.obtain(null, RcProService.MSG_LISTEN);
            Bundle b = new Bundle();
            b.putInt(RcProService.KEY_CS, cs);
            b.putInt(RcProService.KEY_ID, id);
            b.putInt(RcProService.KEY_SEC, sec);
            m.setData(b);
            remote.send(m);
            Log.i(TAG, "requestListen cs=" + cs + " id=" + id + " sec=" + sec);
            return true;
        } catch (Throwable t) { Log.e(TAG, "requestListen 失败: " + t); return false; }
    }

    /** 探针：让服务进程对某个 id 连发 n 次并打印原始载荷。 */
    public boolean requestRaw(int cs, int id, int n) {
        return sendCmd(RcProService.MSG_RAW, cs, id, n, 0);
    }

    private boolean sendCmd(int what, int cs, int a, int n, int to) {
        if (remote == null) { Log.w(TAG, "sendCmd: 服务未连接"); return false; }
        try {
            Message m = Message.obtain(null, what);
            Bundle b = new Bundle();
            b.putInt(RcProService.KEY_CS, cs);
            b.putInt(RcProService.KEY_FROM, a);
            b.putInt(RcProService.KEY_TO, to);
            b.putInt(RcProService.KEY_ID, a);
            b.putInt(RcProService.KEY_N, n);
            m.setData(b);
            remote.send(m);
            Log.i(TAG, "sendCmd what=" + what + " cs=" + cs + " a=" + a + " n=" + n + " to=" + to);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "sendCmd 失败: " + t);
            return false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            remote = new Messenger(b);
            try {
                Message m = Message.obtain(null, RcProService.MSG_REGISTER);
                m.replyTo = inbox;
                remote.send(m);
                bound = true;
                Log.i(TAG, "RcProService 已连接");
            } catch (RemoteException e) {
                error = e.toString();
            }
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; remote = null; }
    };

    /** 6 个通道同时为 0 = 不可能的回中值 → 判为坏帧。 */
    private static boolean isAllZero(int[] v) {
        for (int i = 0; i < AXES; i++) if (v[i] != 0) return false;
        return true;
    }

    /** 数据是否新鲜：最近 ms 毫秒内收到过可信帧。 */
    public boolean fresh(long ms) {
        long t = lastFrameMs;
        if (t <= 0) return false;
        // 单调时钟 + 下溢保护：d 为负（时间源混用/时钟异常）时只判"不新鲜"，
        // 绝不像 `d < ms` 那样恒为 true 把死源当活源。理由见 DumlStick.stickFresh()。
        long d = android.os.SystemClock.elapsedRealtime() - t;
        return d >= 0 && d < ms;
    }

    /** 旁键载荷是否新鲜。 */
    public boolean btnFresh(long ms) {
        long t = btnMs;
        if (t <= 0) return false;
        long d = android.os.SystemClock.elapsedRealtime() - t;
        return d >= 0 && d < ms;
    }

    /** 能否用这条路（有 protocol 服务才行）。 */
    public static boolean available(Context c) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object o = sm.getMethod("getService", String.class).invoke(null, "protocol");
            return o != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public void start(Context c) {
        if (bound) return;
        ctx = c.getApplicationContext();
        try {
            Intent i = new Intent(ctx, RcProService.class);
            ctx.startService(i);
            ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            error = t.toString();
            Log.w(TAG, "bind 失败: " + t);
        }
    }

    public void stop() {
        if (ctx != null && bound) {
            try { ctx.unbindService(conn); } catch (Throwable ignore) { }
        }
        bound = false;
    }

    public int[] snapshot() { return raw; }
}
