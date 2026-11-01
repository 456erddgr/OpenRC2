package com.rc2.etx.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * 标准 CDC-ACM 串口（USB 类 2/2/1）。
 * 只要是"原生 USB 的 ESP32-S3 / STM32 / SAMD21 / RP2040"，插上就是这个类，
 * 不需要任何厂商私有协议 —— 这是最稳的一条 USB 方案。
 */
public final class CdcAcmPort implements SerialPort {

    private static final String TAG = "ETX-Cdc";
    private UsbDeviceConnection conn;
    private UsbInterface commIface;
    private UsbInterface dataIface;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private final StringBuilder tr = new StringBuilder();

    @Override
    public String driverName() {
        return "CDC-ACM";
    }

    @Override
    public String trace() {
        return tr.toString();
    }

    private void log(String s) {
        Log.i(TAG, s);
        if (tr.length() < 4000) tr.append(s).append('\n');
    }

    @Override
    public boolean open(UsbManager um, UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface it = dev.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                if (commIface == null) commIface = it;
            }
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA
                    || it.getInterfaceClass() == 0x0A) {
                if (dataIface == null) dataIface = it;
            }
        }
        // 退路：任何带 bulk in/out 的接口
        if (dataIface == null) {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface it = dev.getInterface(i);
                UsbEndpoint bi = null;
                UsbEndpoint bo = null;
                for (int e = 0; e < it.getEndpointCount(); e++) {
                    UsbEndpoint ep = it.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) bi = ep;
                    else bo = ep;
                }
                if (bi != null && bo != null) {
                    dataIface = it;
                    epIn = bi;
                    epOut = bo;
                    break;
                }
            }
        }
        if (dataIface == null) {
            log("找不到 bulk 数据接口");
            return false;
        }
        if (epIn == null || epOut == null) {
            for (int e = 0; e < dataIface.getEndpointCount(); e++) {
                UsbEndpoint ep = dataIface.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
                else epOut = ep;
            }
        }
        if (epIn == null || epOut == null) {
            log("数据接口缺 bulk 端点");
            return false;
        }
        conn = um.openDevice(dev);
        if (conn == null) {
            log("openDevice 返回 null（多半是没有 USB 权限）");
            return false;
        }
        boolean ok = false;
        try {
            ok = conn.claimInterface(dataIface, true);
            log("claim data iface#" + dataIface.getId() + " -> " + ok);
            if (commIface != null) {
                boolean ok2 = conn.claimInterface(commIface, true);
                log("claim comm iface#" + commIface.getId() + " -> " + ok2);
            }
        } catch (Throwable t) {
            log("claim 异常: " + t);
        }
        if (!ok) {
            log("抢占数据接口失败（可能被系统或其它进程占用）");
        }
        return true;
    }

    @Override
    public boolean setBaud(int baud) {
        if (conn == null) return false;
        int idx = (commIface != null) ? commIface.getId() : 0;
        byte[] lc = new byte[7];
        lc[0] = (byte) (baud & 0xFF);
        lc[1] = (byte) ((baud >> 8) & 0xFF);
        lc[2] = (byte) ((baud >> 16) & 0xFF);
        lc[3] = (byte) ((baud >> 24) & 0xFF);
        lc[4] = 0; // 1 stop bit
        lc[5] = 0; // no parity
        lc[6] = 8; // 8 data bits
        int r1 = conn.controlTransfer(0x21, 0x20, 0, idx, lc, lc.length, 500);
        int r2 = conn.controlTransfer(0x21, 0x22, 0x03, idx, null, 0, 500); // DTR+RTS
        log("SET_LINE_CODING(" + baud + ")=" + r1 + " SET_CONTROL_LINE_STATE=" + r2);
        return r1 >= 0;
    }

    @Override
    public int write(byte[] buf, int len) {
        if (conn == null || epOut == null) return -1;
        return conn.bulkTransfer(epOut, buf, len, 100);
    }

    @Override
    public int read(byte[] buf, int timeoutMs) {
        if (conn == null || epIn == null) return -1;
        return conn.bulkTransfer(epIn, buf, buf.length, timeoutMs);
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                if (dataIface != null) conn.releaseInterface(dataIface);
                if (commIface != null) conn.releaseInterface(commIface);
            } catch (Throwable ignore) {
            }
            try {
                conn.close();
            } catch (Throwable ignore) {
            }
            conn = null;
        }
        log("closed");
    }
}
