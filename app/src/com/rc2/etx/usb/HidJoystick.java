package com.rc2.etx.usb;

import android.util.Log;

import java.io.FileOutputStream;

/**
 * 把 RC2 变成一个 USB HID 手柄（给 PC 上的模拟器用）。
 *
 * 前提：设备侧要用 root 把 gadget 建好并绑定（见 RC2-EdgeTX/usbjoy/setup.sh），
 *      内核在本 ROM 上开了 CONFIG_USB_CONFIGFS_F_HID=y，绑好后会生成 /dev/hidg0。
 *
 * 报文格式（10 字节，见 usbjoy/desc.b64 里的报表描述符）：
 *   b0..b1  X  (0..1023)
 *   b2..b3  Y
 *   b4..b5  Z
 *   b6..b7  Rz
 *   b8      8 个按键（bit0..bit7）
 *   b9      hat（0..7，0=居中）
 */
public final class HidJoystick {
    private static final String TAG = "ETX-HID";
    public static final String DEV = "/dev/hidg0";

    private FileOutputStream out;
    private volatile boolean open;
    private volatile long reports;
    private volatile String err = "-";
    private volatile int mnx = 9999, mxx = -1, mny = 9999, mxy = -1, mnz = 9999, mxz = -1, mnrz = 9999, mxrz = -1;
    private volatile int lx, ly, lz, lrz;

    public synchronized boolean isOpen() { return open; }
    public long reports() { return reports; }
    public String error() { return err; }

    /** 用 root 把 gadget 建起来（幂等）。脚本在 /data/local/tmp/etx_usbjoy.sh。 */
    public static String ensureGadget() {
        java.io.File f = new java.io.File(DEV);
        if (f.exists()) return "exists";
        String r = com.rc2.etx.diag.RootShell.run("sh /data/local/tmp/etx_usbjoy.sh");
        Log.i(TAG, "ensureGadget: " + r);
        return r == null ? "null" : r.trim();
    }

    public synchronized boolean open() {
        if (open) return true;
        // 审计修复：写失败时只把 open 置 false 而没关流，out 会一直挂着。
        // 每次重开都新建 FileOutputStream → fd 泄漏，攒够了就"设备识别但不动"。
        // 这里先确保旧流关闭，再开新的。
        if (out != null) {
            try { out.close(); } catch (Throwable ignore) { }
            out = null;
        }
        if (!new java.io.File(DEV).exists()) ensureGadget();
        try {
            out = new FileOutputStream(DEV);
            open = true;
            err = "-";
            Log.i(TAG, "opened " + DEV);
            return true;
        } catch (Throwable t) {
            err = t.toString();
            Log.w(TAG, "open " + DEV + " failed: " + err);
        }
        // 节点权限经常被重建成 600 root（gadget 重新绑定时会掉），
        // 用 root 修一次权限再试，避免"有节点、写不进去"。
        try {
            com.rc2.etx.diag.RootShell.run("chmod 666 " + DEV);
            out = new FileOutputStream(DEV);
            open = true;
            err = "-";
            Log.i(TAG, "opened " + DEV + " after chmod 666");
            return true;
        } catch (Throwable t2) {
            err = t2.toString();
            Log.w(TAG, "open " + DEV + " failed after chmod: " + err);
            return false;
        }
    }

    public synchronized void close() {
        open = false;
        if (out != null) {
            try { out.close(); } catch (Throwable ignore) { }
            out = null;
        }
    }

    /** 四个轴用量程 0..1023，静止时应在 512 附近。 */
    public void send(int x, int y, int z, int rz, int buttons, int hat) {
        if (!open) return;
        byte[] b = new byte[10];
        put16(b, 0, x);
        put16(b, 2, y);
        put16(b, 4, z);
        put16(b, 6, rz);
        b[8] = (byte) (buttons & 0xFF);
        b[9] = (byte) (hat & 0xFF);
        lx = x; ly = y; lz = z; lrz = rz;
        if (x < mnx) mnx = x; if (x > mxx) mxx = x;
        if (y < mny) mny = y; if (y > mxy) mxy = y;
        if (z < mnz) mnz = z; if (z > mxz) mxz = z;
        if (rz < mnrz) mnrz = rz; if (rz > mxrz) mxrz = rz;
        synchronized (this) {
            if (out == null) return;
            try {
                out.write(b);
                out.flush();
                reports++;
            } catch (Throwable t) {
                err = t.toString();
                open = false;
                // 关掉坏流，交给 startHidLoop 的 800ms 重试重新 open()。
                try { if (out != null) out.close(); } catch (Throwable ignore) { }
                out = null;
                Log.w(TAG, "write failed: " + err);
            }
        }
    }

    /** 诊断用：四个轴的观测范围与最后一次值。 */
    public String stats() {
        return "x=" + lx + "[" + mnx + ".." + mxx + "] y=" + ly + "[" + mny + ".." + mxy + "]"
                + " z=" + lz + "[" + mnz + ".." + mxz + "] rz=" + lrz + "[" + mnrz + ".." + mxrz + "]";
    }

    private static void put16(byte[] b, int off, int v) {
        if (v < 0) v = 0;
        if (v > 1023) v = 1023;
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
