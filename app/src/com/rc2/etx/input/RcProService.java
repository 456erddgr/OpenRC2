package com.rc2.etx.input;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑在 android:process="com.dpad.fuli" 里的采集进程。
 *
 * 这个进程名是 DJI 的白名单名（实测有效）：只有用它才能从 ServiceManager 拿到
 * "protocol" 服务的 binder。拿到后按 RM510 对接包给出的常量拼 Parcel，
 * transact(4) 查询 cmdSet=6 / cmdId=4（摇杆+波轮），回调里按小端 uint16 解 6 个通道。
 */
public class RcProService extends Service {

    private static final String TAG = "ETXRcProSvc";

    public static final int MSG_REGISTER = 1;
    public static final int MSG_VALUES = 2;
    public static final int MSG_ERROR = 3;
    public static final String KEY_VALUES = "values";

    private static final String IFACE_PROTOCOL = "com.dji.protocol.IProtocolManager";
    private static final String IFACE_LISTENER = "com.dji.protocol.IPackListener";

    /** 原包常量（仅下标 13/14 需要改写，其余照抄）。 */
    private static final int[] MCU_PACK = {
            85, 1, 0, 0, 4, 2, 0, 6, -1, 0, 2, 0, 0, 6, 4, 0, 0, 0, 300, 0
    };

    private final List<Messenger> clients = new ArrayList<Messenger>();
    private volatile int[] lastValues;
    private volatile long lastAt;
    private volatile boolean running;
    private boolean okLogged, failLogged;

    private int[] packWithCmd(int cmdSet, int cmdId) {
        int[] p = new int[MCU_PACK.length];
        System.arraycopy(MCU_PACK, 0, p, 0, MCU_PACK.length);
        p[13] = cmdSet;
        p[14] = cmdId;
        return p;
    }

    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 0x5f4e5446) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(IFACE_LISTENER);
                int head = data.readInt();
                if (head == 0) return true;
                for (int i = 0; i < 15; i++) data.readInt();
                int n = data.readInt();
                if (n <= 0) return true;
                byte[] bytes = data.createByteArray();
                if (bytes == null || bytes.length < 12) return true;
                int[] vals = new int[6];
                for (int k = 0; k < 6; k++) {
                    vals[k] = ((bytes[k * 2] & 0xFF) | ((bytes[k * 2 + 1] & 0xFF) << 8));
                }
                lastValues = vals;
                lastAt = System.currentTimeMillis();
                publish(vals);
            } catch (Throwable t) {
                publishError(t.toString());
            }
            return true;
        }
    };

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what == MSG_REGISTER && msg.replyTo != null) {
                synchronized (clients) { clients.add(msg.replyTo); }
                int[] v = lastValues;
                if (v != null) sendTo(msg.replyTo, v);
            }
        }
    });

    private void publish(int[] v) {
        Messenger[] snapshot;
        synchronized (clients) { snapshot = clients.toArray(new Messenger[0]); }
        for (Messenger m : snapshot) sendTo(m, v);
    }

    private void sendTo(Messenger m, int[] v) {
        try {
            Bundle b = new Bundle();
            b.putIntArray(KEY_VALUES, v);
            Message out = Message.obtain(null, MSG_VALUES);
            out.obj = b;
            m.send(out);
        } catch (Throwable ignore) { }
    }

    private void publishError(String s) {
        if (failLogged) return;
        failLogged = true;
        Log.w(TAG, "poll 失败: " + s);
        Messenger[] snapshot;
        synchronized (clients) { snapshot = clients.toArray(new Messenger[0]); }
        for (Messenger m : snapshot) {
            try {
                Message out = Message.obtain(null, MSG_ERROR);
                out.obj = s;
                m.send(out);
            } catch (Throwable ignore) { }
        }
    }

    private void pollOnce() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object o = sm.getMethod("getService", String.class).invoke(null, "protocol");
            if (o == null) {
                publishError("getService(\"protocol\") = null（进程名可能不在白名单）");
                return;
            }
            IBinder binder = (IBinder) o;
            data.writeInterfaceToken(IFACE_PROTOCOL);
            data.writeInt(1);
            int[] pack = packWithCmd(6, 4);
            for (int v : pack) data.writeInt(v);
            data.writeStrongBinder(callback);
            boolean ok = binder.transact(4, data, reply, 0);
            if (!ok) {
                publishError("transact(4) 返回 false");
                return;
            }
            reply.readException();
            if (!okLogged) { okLogged = true; Log.i(TAG, "protocol 轮询已建立"); }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            publishError(c.getClass().getSimpleName() + ": " + c.getMessage());
        } finally {
            try { data.recycle(); } catch (Throwable ignore) { }
            try { reply.recycle(); } catch (Throwable ignore) { }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        pollOnce();
                        Thread.sleep(100);      // README: 建议 ≤150ms，再快 binder 掉包
                    } catch (Throwable t) {
                        try { Thread.sleep(200); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "etx-rcpro-poll");
        t.setDaemon(true);
        t.start();
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }
}
