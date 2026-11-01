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
 */
public final class RcProStick {

    private static final String TAG = "ETXRcPro";
    public static final int AXES = 6;

    /** 最近一帧通道值（0..2048）。整体替换，避免读到半帧。 */
    public volatile int[] raw = new int[AXES];
    public volatile boolean connected;
    public volatile long frames;
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
                    raw = copy;
                    connected = true;
                    frames++;
                }
            } else if (msg.what == RcProService.MSG_ERROR) {
                error = msg.obj == null ? "?" : String.valueOf(msg.obj);
                connected = false;
            }
        }
    });

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
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; remote = null; connected = false; }
    };

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
        connected = false;
    }

    public int[] snapshot() { return raw; }
}
