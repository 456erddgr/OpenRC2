package com.rc2.etx.usb;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 虚拟手柄数据流（学 RM510 那套：PC 端用 ViGEmBus 造一个 Xbox360 手柄，
 * 遥控器只负责把摇杆/开关按行推过去，走 adb reverse 的本地 TCP，不需要 root、不碰 USB gadget）。
 *
 * 通道：PC 跑 `OpenRC2-虚拟手柄.py` 监听 0.0.0.0:9999，并执行
 *       adb reverse tcp:9999 tcp:9999
 *       于是 App 连 127.0.0.1:9999 就等于连到了 PC。
 *
 * 协议：一行一个 JSON，8ms 一帧
 *       {"t":123456,"ax":[1024,1024,1024,1024,1024,1024],"us":[992,...]}
 *       ax = 六路原始值（0..2048，中位 1024，与 DJI 摇杆同尺度）
 */
public class PadStream {

    public static final int PORT = 9999;
    private static final int HZ_MS = 4;

    private volatile boolean want = false;
    private volatile boolean running = false;
    private volatile long frames = 0;
    private volatile String lastErr = "-";
    private Thread th;
    private android.content.Context lastCtx;
    private Socket sock;
    private BufferedWriter out;

    /** 开关文件：/sdcard/Android/data/<pkg>/files/padbridge.on 存在即开机自启。 */
    public static File flagFile(Context c) {
        return new File(c.getExternalFilesDir(null), "padbridge.on");
    }

    public boolean isWanted() { return want; }
    public boolean isRunning() { return running; }
    public long frameCount() { return frames; }
    public String lastError() { return lastErr; }

    public synchronized void start(Context c) {
        want = true;
        // 落盘开关文件：不写的话重启后没人再把它拉起来，用户看到的就是
        // “一重启就不好使”（他报的那个）。
        try {
            File f = flagFile(c.getApplicationContext());
            if (!f.exists()) { f.getParentFile().mkdirs(); f.createNewFile(); }
        } catch (Throwable t) {
            lastErr = "flag: " + t;
        }
        if (th != null && th.isAlive()) return;
        final Context ctx = c.getApplicationContext();
        lastCtx = ctx;
        th = new Thread(new Runnable() {
            public void run() { loop(ctx); }
        }, "etx-padstream");
        th.setDaemon(true);
        th.start();
    }

    public synchronized void stop() {
        want = false;
        try { flagFile(lastCtx).delete(); } catch (Throwable ignore) { }
        lastCtx = null;
        closeQuietly();
    }

    private void loop(Context ctx) {
        BufferedWriter w = null;
        while (want) {
            try {
                if (sock == null || sock.isClosed() || !sock.isConnected()) {
                    closeQuietly();
                    Socket s = new Socket();
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
                    sock = s;
                    w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    out = w;
                    running = true;
                    lastErr = "-";
                }
                String line = com.rc2.etx.radio.Radio.get(ctx).padFrameJson();
                out.write(line);
                out.write("\n");
                out.flush();
                frames++;
                Thread.sleep(HZ_MS);
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                            new java.io.File(ctx.getExternalFilesDir(null), "pad_err.txt"), true);
                    fw.write(lastErr + String.valueOf((char) 10));
                    fw.close();
                } catch (Throwable ignore) { }
                running = false;
                closeQuietly();
                try { Thread.sleep(1000); } catch (InterruptedException ignore) { }
            }
        }
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (out != null) out.close(); } catch (Throwable ignore) { }
        out = null;
        try { if (sock != null) sock.close(); } catch (Throwable ignore) { }
        sock = null;
    }
}
