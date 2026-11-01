package com.rc2.etx.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.rc2.etx.radio.OutputSink;

/** CRSF 帧走 USB 串口（CDC-ACM）。 */
public final class UsbSerialSink implements OutputSink {

    private final Context ctx;
    private SerialPort port;
    private String devName = "auto";
    private volatile int baud = 420000;
    private volatile String status = "未打开";
    private volatile boolean open;
    private volatile long frames;
    private volatile long errors;
    private volatile String lastErr = "";

    public UsbSerialSink(Context c) {
        ctx = c.getApplicationContext();
    }

    public void setBaud(int b) {
        baud = b;
    }

    public int baud() {
        return baud;
    }

    @Override
    public String name() {
        return "USB 串口";
    }

    @Override
    public boolean open(String arg) {
        close();
        if (arg != null && arg.length() > 0) devName = arg;
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            status = "系统没有 USB 服务";
            return false;
        }
        UsbDevice dev = UsbSerial.pick(ctx, devName);
        if (dev == null) {
            status = "没有找到可用 USB 串口设备（先让 RC2 进入 USB host 模式并插好转接板）";
            return false;
        }
        devName = dev.getDeviceName();
        if (!um.hasPermission(dev)) {
            try {
                um.requestPermission(dev, UsbSerial.permissionIntent(ctx));
                status = "已弹出 USB 授权，请点允许后重试打开";
            } catch (Throwable t) {
                status = "申请权限失败: " + t;
            }
            return false;
        }
        port = new CdcAcmPort();
        if (!port.open(um, dev)) {
            status = "打开失败: " + port.driverName() + " → " + cap(port.trace());
            port = null;
            return false;
        }
        port.setBaud(baud);
        open = true;
        frames = 0;
        errors = 0;
        status = "已打开 " + UsbSerial.describe(dev) + " @" + baud;
        return true;
    }

    @Override
    public void send(byte[] frame) {
        if (!open || port == null) return;
        int n = port.write(frame, frame.length);
        if (n < 0) {
            errors++;
            lastErr = "bulkTransfer 失败(" + n + ")";
        } else {
            frames++;
        }
    }

    @Override
    public void close() {
        open = false;
        status = "已关闭";
        if (port != null) {
            try {
                port.close();
            } catch (Throwable ignore) {
            }
            port = null;
        }
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public long frames() {
        return frames;
    }

    @Override
    public long errors() {
        return errors;
    }

    @Override
    public String detail() {
        return "设备 " + devName + " @" + baud
                + (port == null ? "" : " | " + port.driverName())
                + (lastErr.length() == 0 ? "" : " | " + lastErr);
    }

    public String trace() {
        return port == null ? "（未打开）" : port.trace();
    }

    private static String cap(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
