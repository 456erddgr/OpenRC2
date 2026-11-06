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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跑在 android:process="com.dpad.fuli" 里的采集进程。
 *
 * 这个进程名是 DJI 的白名单名（实测有效）：只有用它才能从 ServiceManager 拿到
 * "protocol" 服务的 binder。拿到后按 RM510 对接包给出的常量拼 Parcel，
 * transact(4) 查询 cmdSet=6 / cmdId=4（摇杆+波轮），回调里按小端 uint16 解 6 个通道。
 *
 * 2026-11-03 新增「旁键（物理按键）走 binder」（修 RC Pro 旁键不可用）：
 * 依据是 RCPro-资料/d3 的参照实现（cjz 的 RM510 键位 app，在 RC Pro 上实测可用）：
 *   · 摇杆/波轮：transact(4) + cmdSet=6/cmdId=4  → 6×u16le
 *   · 按键/挡位：cmdSet=6 / cmdId=174(0xAE)（参照实现每 500ms 轮一次 174）
 * 我们在同一进程里补一条 174 轮询（默认 250ms），把载荷前 3 字节当 p0/p1/p2 上报。
 * 这样即使 RC Pro 的 40007 一条帧都不给，旁键也能从 binder 拿到。
 *
 * 诚实边界：174 的**载荷布局**是从「40007 上的 06/174 帧」实测出来的
 * （offset 11/12/13 = p0/p1/p2，见 docs/19 与 RCPro-资料/d3:857-866），
 * 而 binder 回调拿到的 byte[] 是否与 TCP 载荷逐字节相同，**尚未真机验证**。
 * 所以这里把原始 hex 一起回传，真机上一眼就能对照确认。
 */
public class RcProService extends Service {

    private static final String TAG = "ETXRcProSvc";

    public static final int MSG_REGISTER = 1;
    public static final int MSG_VALUES = 2;
    public static final int MSG_ERROR = 3;
    /** 旁键（06/174）载荷。2026-11-03 新增。 */
    public static final int MSG_BTN = 4;
    /** 主进程请求服务进程执行一次 cmdId 扫描（探针用）。 */
    public static final int MSG_SCAN = 5;
    public static final int MSG_RAW = 6;
    /** 只注册一次回调然后静默等推送（验证"我们是不是在打断订阅"）。 */
    public static final int MSG_LISTEN = 7;
    public static final String KEY_SEC = "sec";
    public static final String KEY_CS = "cs";
    public static final String KEY_FROM = "from";
    public static final String KEY_TO = "to";
    public static final String KEY_ID = "id";
    public static final String KEY_N = "n";
    public static final String KEY_VALUES = "values";
    public static final String KEY_B0 = "b0";
    public static final String KEY_B1 = "b1";
    public static final String KEY_B2 = "b2";
    public static final String KEY_HEX = "hex";

    private static final String IFACE_PROTOCOL = "com.dji.protocol.IProtocolManager";
    private static final String IFACE_LISTENER = "com.dji.protocol.IPackListener";

    /** 原包常量（仅下标 13/14 需要改写，其余照抄）。 */
    private static final int[] MCU_PACK = {
            85, 1, 0, 0, 4, 2, 0, 6, -1, 0, 2, 0, 0, 6, 4, 0, 0, 0, 300, 0
    };

    /**
     * 跨线程的"binder 事务在飞"标志。
     * 以前是普通 boolean，只够单线程用；现在有摇杆(100Hz) 和旁键(4Hz) 两条轮询线程，
     * 必须原子化，否则两边同时 transact 会把 binder 服务端队列塞满、
     * 表现就是"推几下整个输入卡住"（1.24.0 修过一次的同一个坑）。
     */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** 轮询间隔（ms）。默认 8ms，可用 --es action bindrate --ei ms N 改；0 表示"背压自适应"。 */
    public static volatile int pollIntervalMs = 0;
    /** 旁键轮询间隔（ms）。参照实现用 500ms；这里取 250ms 兼顾手感与开销。 */
    public static volatile int btnPollMs = 250;
    private android.content.SharedPreferences sp;
    private static volatile long statFrom, statPolls, statSkips, statReplies, statChanges;
    private int cbLog;
    private final List<Messenger> clients = new ArrayList<Messenger>();
    private volatile int[] lastValues;
    private volatile long lastAt;
    private volatile boolean running;
    private boolean okLogged, failLogged;
    /** 旁键：最近一次载荷（用于"变了才上报"与诊断）。 */
    private volatile String lastBtnHex = "";
    private volatile int btnCount;
    private boolean btnOkLogged, btnFailLogged;
    /** 最近一次旁键三元组（重新注册的客户端要能立刻拿到当前状态）。 */
    private final int[] lastBtn = new int[]{0, 0, 0};

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
                // 头部的 15 个 int 里有 cmd_set / cmd_id / 返回长度，扫描模式下要原样留证。
                int[] hdr = new int[15];
                for (int i = 0; i < 15; i++) hdr[i] = data.readInt();
                int n = data.readInt();
                if (n <= 0) return true;
                byte[] bytes = data.createByteArray();
                if (scanner != null && bytes != null) scanner.onReply(hdr, bytes);
                if (bytes == null || bytes.length < 12) return true;
                int[] vals = new int[6];
                for (int k = 0; k < 6; k++) {
                    vals[k] = ((bytes[k * 2] & 0xFF) | ((bytes[k * 2 + 1] & 0xFF) << 8));
                }
                if (cbLog < 6) {
                    cbLog++;
                    Log.i(TAG, "chan=" + vals[0] + "," + vals[1] + "," + vals[2]
                            + "," + vals[3] + "," + vals[4] + "," + vals[5] + " bytes=" + bytes.length);
                }
                statReplies++;
                int[] prev = lastValues;
                if (prev == null || prev.length != vals.length) statChanges++;
                else {
                    for (int k = 0; k < vals.length; k++) {
                        if (prev[k] != vals[k]) { statChanges++; break; }
                    }
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

    /**
     * 旁键（06/174）的回调。**必须是独立 Binder**：摇杆回调是按 6×u16 解通道值的，
     * 174 的载荷却是"位图 + 挡位"（前 3 字节 p0/p1/p2），
     * 两条查询共用一个回调就会互相污染（把按键位当成摇杆值）。
     */
    private final Binder btnCallback = new Binder() {
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
                if (bytes == null || bytes.length < 3) return true;
                int b0 = bytes[0] & 0xFF;
                int b1 = bytes[1] & 0xFF;
                int b2 = bytes[2] & 0xFF;
                btnCount++;
                // 只在前 8 字节变化时打日志（按键是事件型，静止时不变，不会刷屏）
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 8 && k < bytes.length; k++) {
                    sb.append(String.format("%02x", bytes[k] & 0xFF));
                }
                String hex = sb.toString();
                boolean changed = !hex.equals(lastBtnHex);
                lastBtnHex = hex;
                if (btnCount <= 3 || changed) {
                    Log.i(TAG, "btn #" + btnCount + " p0=" + b0 + " p1=" + b1 + " p2=" + b2
                            + " len=" + bytes.length + " hex=" + hex);
                }
                publishBtn(b0, b1, b2, hex);
            } catch (Throwable t) {
                if (!btnFailLogged) {
                    btnFailLogged = true;
                    Log.w(TAG, "btn 回调失败: " + t);
                }
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
                if (lastBtnHex.length() > 0) {
                    sendBtnTo(msg.replyTo, lastBtn[0], lastBtn[1], lastBtn[2], lastBtnHex);
                }
            } else if (msg.what == MSG_SCAN) {
                Bundle b = msg.getData();
                int cs = b.getInt(KEY_CS, 6), from = b.getInt(KEY_FROM, 0), to = b.getInt(KEY_TO, 40);
                Log.i(TAG_SCAN, "MSG_SCAN received cs=" + cs + " " + from + ".." + to);
                startScan(null, cs, from, to);
            } else if (msg.what == MSG_LISTEN) {
                Bundle b = msg.getData();
                int cs = b.getInt(KEY_CS, 6), id = b.getInt(KEY_ID, 4), sec = b.getInt(KEY_SEC, 30);
                Log.i(TAG_SCAN, "MSG_LISTEN cs=" + cs + " id=" + id + " sec=" + sec);
                startListen(cs, id, sec);
            } else if (msg.what == MSG_RAW) {
                Bundle b = msg.getData();
                int cs = b.getInt(KEY_CS, 6), id = b.getInt(KEY_ID, 4), n = b.getInt(KEY_N, 8);
                Log.i(TAG_SCAN, "MSG_RAW received cs=" + cs + " id=" + id + " n=" + n);
                startRawDump(null, cs, id, n);
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

    private void publishBtn(int b0, int b1, int b2, String hex) {
        lastBtn[0] = b0; lastBtn[1] = b1; lastBtn[2] = b2;
        Messenger[] snapshot;
        synchronized (clients) { snapshot = clients.toArray(new Messenger[0]); }
        for (Messenger m : snapshot) sendBtnTo(m, b0, b1, b2, hex);
    }

    private void sendBtnTo(Messenger m, int b0, int b1, int b2, String hex) {
        try {
            Bundle b = new Bundle();
            b.putInt(KEY_B0, b0);
            b.putInt(KEY_B1, b1);
            b.putInt(KEY_B2, b2);
            b.putString(KEY_HEX, hex);
            Message out = Message.obtain(null, MSG_BTN);
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

    /** 一次 transact(4)。cmdSet/cmdId 决定服务端返回什么包；cb 决定用哪个回调解。 */
    private boolean transactOnce(int cmdSet, int cmdId, Binder cb) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object o = sm.getMethod("getService", String.class).invoke(null, "protocol");
            if (o == null) {
                publishError("getService(\"protocol\") = null（进程名可能不在白名单）");
                return false;
            }
            IBinder binder = (IBinder) o;
            data.writeInterfaceToken(IFACE_PROTOCOL);
            data.writeInt(1);
            int[] pack = packWithCmd(cmdSet, cmdId);
            for (int v : pack) data.writeInt(v);
            data.writeStrongBinder(cb);
            boolean ok = binder.transact(4, data, reply, 0);
            if (!ok) {
                publishError("transact(4) 返回 false");
                return false;
            }
            reply.readException();
            return true;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            publishError(c.getClass().getSimpleName() + ": " + c.getMessage());
            return false;
        } finally {
            try { data.recycle(); } catch (Throwable ignore) { }
            try { reply.recycle(); } catch (Throwable ignore) { }
        }
    }

    private void pollOnce() {
        // 审计修复：重入保护必须放在最前面。以前写在 try 里，早退时 finally 仍会把
        // transactBusy 清成 false —— 等于把**另一个线程**的锁放掉，双份 transact 又回来了。
        // 2026-11-03：改成 AtomicBoolean.compareAndSet，跨线程也安全（旁键线程共用同一个闸门）。
        statPolls++;
        if (paused) { statSkips++; return; }
        if (!inFlight.compareAndSet(false, true)) { statSkips++; return; }
        try {
            // 防重入：轮询定时器与重连线程可能同时进来，binder 服务端是单队列，
            // 并发 transact 会把队列塞满，表现就是"推几下就卡住不动"。
            if (transactOnce(6, 4, callback) && !okLogged) {
                okLogged = true;
                Log.i(TAG, "protocol 轮询已建立");
            }
        } finally {
            inFlight.set(false);
        }
    }

    /**
     * 旁键轮询：cmdSet=6 / cmdId=174(0xAE) = BUTTON_PHY_STATUS。
     * 参照实现（RCPro-资料/d3 的 mcuPoll(6,174)）也是这么拉的。
     * 抢不到闸门就直接跳过这一拍，绝不阻塞摇杆那条 100Hz 的线程。
     */
    private void pollBtnOnce() {
        if (paused) return;
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            if (transactOnce(6, 174, btnCallback) && !btnOkLogged) {
                btnOkLogged = true;
                Log.i(TAG, "旁键(06/174) 轮询已建立");
            }
        } finally {
            inFlight.set(false);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        inst = this;
        sp = getSharedPreferences("etx", 0);
        pollIntervalMs = sp.getInt("rcproPollMs", pollIntervalMs);
        Thread t = new Thread(new Runnable() {
            public void run() {
                statFrom = System.currentTimeMillis();
                while (running) {
                    try {
                        pollOnce();
                        if (pollIntervalMs <= 0) {
                            // 0 = 高频模式：Thread.sleep(1) 在这台机器上实际会睡 ~50ms（实测只有 20Hz），
                            // 所以用忙等的纳米计时做 ~100Hz 节流，既比 sleep 准，又不至于 400Hz 满负荷。
                            long until = System.nanoTime() + 10_000_000L;
                            while (System.nanoTime() < until) Thread.yield();
                        } else {
                            Thread.sleep(pollIntervalMs);
                        }
                        long now = System.currentTimeMillis();
                        if (now - statFrom >= 2000) {
                            Log.i("ETXRcPro", "stat pollHz=" + (statPolls * 1000 / (now - statFrom))
                                    + " replyHz=" + (statReplies * 1000 / (now - statFrom))
                                    + " changeHz=" + (statChanges * 1000 / (now - statFrom))
                                    + " skip=" + statSkips + " poll=" + statPolls
                                    + " iv=" + pollIntervalMs
                                    + " btn=" + btnCount);
                            int iv = sp.getInt("rcproPollMs", pollIntervalMs);
                            pollIntervalMs = iv;
                            statFrom = now; statPolls = 0; statSkips = 0; statReplies = 0; statChanges = 0;
                        }
                    } catch (Throwable t) {
                        try { Thread.sleep(200); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "etx-rcpro-poll");
        t.setDaemon(true);
        t.start();

        // 旁键轮询线程：低频（250ms）即可，按键不会有 100Hz 需求，
        // 但每多占一次 binder 就少给摇杆一帧，所以刻意压低频率。
        Thread bt = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        pollBtnOnce();
                        Thread.sleep(btnPollMs);
                    } catch (Throwable t) {
                        try { Thread.sleep(500); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "etx-rcpro-btn");
        bt.setDaemon(true);
        bt.start();
    }

    @Override public void onDestroy() { running = false; if (inst == this) inst = null; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    // ================= 探针：cmdId 扫描 / 原始载荷导出 =================
    //
    // 目的：找出「哪一组 cmdSet/cmdId 能让固件把真实摇杆值推上来」。
    // 现状（2026-11-06 实测）：cmdSet=6/cmdId=4 能 60Hz 轮询成功、回调也进，
    // 但 6 个通道恒为 1024 —— 说明这个 id 只回"中点占位"，真值在别的 id 里。
    // 扫描器把每个 id 的回包长度 + 前若干字节打出来，一眼能看出哪个 id 回了变化的数据。

    /** 当前扫描器；null = 未在扫描。callback 每次回包都会喂给它。 */
    private static volatile Scan scanner;

    /**
     * 扫描/raw 期间暂停主轮询。
     * 原因：transact(4) 每次都会把「回包回调」注册成传入的那个 Binder ——
     * 主轮询 60Hz 不停地拿 `callback` 重新注册，扫描器的 `scanCb` 刚注册上就被顶掉，
     * 于是回包永远进不到 scanner 里（第一次跑扫描时就是这么静默失败的）。
     */
    private static volatile boolean paused;

    /** 当前活着的实例（扫描器是静态方法，需要一个 binder 回调去接回包）。 */
    private static volatile RcProService inst;

    /** 扫描专用的回包 Binder：只把 (hdr, body) 交给 scanner，不做任何状态更新。 */
    private static final Binder scanCb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 0x5f4e5446) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(IFACE_LISTENER);
                int head = data.readInt();
                if (head == 0) return true;
                int[] hdr = new int[15];
                for (int i = 0; i < 15; i++) hdr[i] = data.readInt();
                int n = data.readInt();
                if (n <= 0) return true;
                byte[] body = data.createByteArray();
                Scan sc = scanner;
                if (sc != null && body != null) sc.onReply(hdr, body);
            } catch (Throwable ignore) { }
            return true;
        }
    };

    private static final String TAG_SCAN = "ETXRcProScan";

    private interface Scan { void onReply(int[] hdr, byte[] body); }

    /** 扫描：对 cmdSet 下 [from,to] 的每个 cmdId 各发一次，把回包打出来。 */
    public static void startScan(final android.content.Context ctx,
                                 final int cmdSet, final int from, final int to) {
        runOnBinderThread("scan", new Runnable() {
            public void run() {
                IBinder binder = getProtocolBinder();
                if (binder == null) { Log.e(TAG_SCAN, "getService(protocol) = null"); return; }
                paused = true;
                try {
                  for (int id = from; id <= to; id++) {
                    scanOne(binder, cmdSet, id);
                    try { Thread.sleep(60); } catch (InterruptedException ignore) { }
                  }
                } finally { paused = false; }
                scanner = null;
                Log.i(TAG_SCAN, "scan done cs=" + cmdSet + " ids=" + from + ".." + to);
            }
        });
    }

    /** 对单个 id 发一次，并等最多 300ms 收一个回包。 */
    private static void scanOne(IBinder binder, final int cmdSet, int cmdId) {
        final String[] got = new String[1];
        final int[] hdrGot = new int[1];
        scanner = new Scan() {
            public void onReply(int[] hdr, byte[] body) {
                if (got[0] != null) return;
                hdrGot[0] = 1;
                got[0] = hex(body, 64);
            }
        };
        try {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            data.writeInterfaceToken(IFACE_PROTOCOL);
            data.writeInt(1);
            int[] pack = packOf(cmdSet, cmdId);
            for (int v : pack) data.writeInt(v);
            data.writeStrongBinder(scanCb);
            boolean ok = binder.transact(4, data, reply, 0);
            data.recycle(); reply.recycle();
            long t0 = System.currentTimeMillis();
            while (got[0] == null && System.currentTimeMillis() - t0 < 300) {
                try { Thread.sleep(10); } catch (InterruptedException ignore) { }
            }
            Log.i(TAG_SCAN, String.format("cs=%d id=%3d ok=%s waited=%s body=%s",
                    cmdSet, cmdId, ok, hdrGot[0] == 1 ? "reply" : "none",
                    got[0] == null ? "-" : got[0]));
        } catch (Throwable t) {
            Log.e(TAG_SCAN, "cs=" + cmdSet + " id=" + cmdId + " ex=" + t);
        } finally {
            scanner = null;
        }
    }

    /**
     * 「只注册一次，然后闭嘴等」—— 验证我们是不是自己打断了服务端的推送订阅。
     *
     * 动机：主轮询每 16ms 就 transact(4) 一次，而 transact(4) 每次都会重新注册
     * IPackListener。如果固件把「注册」当成「重置订阅」，那么服务端永远只会回
     * 初始占位值（我们观察到的 6×1024），真实采样则永远不会推过来。
     *
     * 这个探针只发一次 transact(4)，之后完全静默 sec 秒，只统计回调次数与变化次数：
     *   · 如果回调持续在涨      → 订阅是活的，我们之前确实在打断它 → 改主轮询节流即可
     *   · 如果只有一次性回调    → 服务端本来就不推，得另找推流入口
     */
    public static void startListen(final int cmdSet, final int cmdId, final int sec) {
        runOnBinderThread("listen", new Runnable() {
            public void run() {
                IBinder binder = getProtocolBinder();
                if (binder == null) { Log.e(TAG_SCAN, "LISTEN: getService(protocol)=null"); return; }
                paused = true;
                final int[] n = new int[1];
                final int[] changes = new int[1];
                final String[] last = new String[1];
                scanner = new Scan() {
                    public void onReply(int[] hdr, byte[] body) {
                        n[0]++;
                        String h = hex(body, 32);
                        if (last[0] == null) last[0] = h;
                        else if (!last[0].equals(h)) { changes[0]++; last[0] = h; }
                    }
                };
                try {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    data.writeInterfaceToken(IFACE_PROTOCOL);
                    data.writeInt(1);
                    for (int v : packOf(cmdSet, cmdId)) data.writeInt(v);
                    data.writeStrongBinder(scanCb);
                    binder.transact(4, data, reply, 0);
                    data.recycle(); reply.recycle();
                    Log.i(TAG_SCAN, "LISTEN registered, now silent for " + sec + "s");
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < sec * 1000L) {
                        Thread.sleep(1000);
                        Log.i(TAG_SCAN, "LISTEN t=" + ((System.currentTimeMillis() - t0) / 1000)
                                + "s replies=" + n[0] + " changes=" + changes[0]
                                + " last=" + (last[0] == null ? "-" : last[0]));
                    }
                } catch (Throwable t) {
                    Log.e(TAG_SCAN, "LISTEN ex=" + t);
                } finally {
                    paused = false;
                    scanner = null;
                }
                Log.i(TAG_SCAN, "LISTEN done total=" + n[0] + " changes=" + changes[0]);
            }
        });
    }

    /** 原始导出：同一个 id 连发 n 次，把每次的回包完整打出来（找可变字段用）。 */
    public static void startRawDump(final android.content.Context ctx,
                                    final int cmdSet, final int cmdId, final int n) {
        runOnBinderThread("raw", new Runnable() {
            public void run() {
                IBinder binder = getProtocolBinder();
                if (binder == null) { Log.e(TAG_SCAN, "getService(protocol) = null"); return; }
                paused = true;
                final int[] cnt = new int[1];
                scanner = new Scan() {
                    public void onReply(int[] hdr, byte[] body) {
                        cnt[0]++;
                        Log.i(TAG_SCAN, "raw#" + cnt[0] + " hdr=" + join(hdr)
                                + " len=" + body.length + " body=" + hex(body, 200));
                    }
                };
                try {
                for (int k = 0; k < n; k++) {
                    try {
                        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                        data.writeInterfaceToken(IFACE_PROTOCOL);
                        data.writeInt(1);
                        for (int v : packOf(cmdSet, cmdId)) data.writeInt(v);
                        data.writeStrongBinder(scanCb);
                        binder.transact(4, data, reply, 0);
                        data.recycle(); reply.recycle();
                    } catch (Throwable t) {
                        Log.e(TAG_SCAN, "raw ex=" + t);
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ignore) { }
                }
                } finally { paused = false; }
                scanner = null;
                Log.i(TAG_SCAN, "raw done cs=" + cmdSet + " id=" + cmdId + " replies=" + cnt[0]);
            }
        });
    }

    private static void runOnBinderThread(String name, final Runnable r) {
        Thread t = new Thread(r, "etx-bind-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static IBinder getProtocolBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, "protocol");
        } catch (Throwable t) { return null; }
    }

    private static int[] packOf(int cmdSet, int cmdId) {
        int[] p = new int[MCU_PACK.length];
        System.arraycopy(MCU_PACK, 0, p, 0, MCU_PACK.length);
        p[13] = cmdSet;
        p[14] = cmdId;
        return p;
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) sb.append(String.format("%02x", b[i] & 0xFF));
        if (b.length > max) sb.append("..(").append(b.length).append("B)");
        return sb.toString();
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
        return sb.append(']').toString();
    }
}
