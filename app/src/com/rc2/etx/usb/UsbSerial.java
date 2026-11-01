package com.rc2.etx.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.HashMap;

/** USB 设备枚举 / 权限 / 串口能力判断。 */
public final class UsbSerial {

    public static final String ACTION_PERMISSION = "com.rc2.etx.USB_PERMISSION";

    private UsbSerial() {
    }

    public static PendingIntent permissionIntent(Context c) {
        Intent i = new Intent(ACTION_PERMISSION);
        i.setPackage(c.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        try {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        } catch (Throwable ignore) {
        }
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    public static HashMap<String, UsbDevice> devices(Context c) {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        if (um == null) return new HashMap<String, UsbDevice>();
        return um.getDeviceList();
    }

    /** DJI 遥控器内部那条私有 USB 链路（C5, 2ca3:1020）的厂商 ID，绝不能被当成串口外设。 */
    public static final int VENDOR_DJI_INTERNAL = 0x2CA3;

    public static boolean isDjiInternal(UsbDevice d) {
        return d != null && d.getVendorId() == VENDOR_DJI_INTERNAL;
    }

    /** 是否具备标准 CDC-ACM 形态（class 2 通信 + class 10 数据）。 */
    public static boolean hasCdc(UsbDevice d) {
        if (d == null) return false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int cls = d.getInterface(i).getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_COMM || cls == 0x0A) return true;
        }
        return false;
    }

    /** 是否像"能当串口用"的设备：CDC 类，或（非 DJI 内部设备时）同时具备 bulk IN/OUT 的厂商接口。 */
    public static boolean isSerialCandidate(UsbDevice d) {
        if (d == null || isDjiInternal(d)) return false;
        if (hasCdc(d)) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            boolean in = false;
            boolean out = false;
            for (int e = 0; e < d.getInterface(i).getEndpointCount(); e++) {
                if (d.getInterface(i).getEndpoint(e).getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (d.getInterface(i).getEndpoint(e).getDirection() == UsbConstants.USB_DIR_IN) in = true;
                else out = true;
            }
            if (in && out) return true;
        }
        return false;
    }

    public static String describe(UsbDevice d) {
        if (d == null) return "无";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04x:%04x", d.getVendorId(), d.getProductId()));
        if (d.getProductName() != null) sb.append(' ').append(d.getProductName());
        sb.append(" 接口").append(d.getInterfaceCount());
        return sb.toString();
    }

    public static String list(Context c) {
        HashMap<String, UsbDevice> map = devices(c);
        if (map.isEmpty()) return "（未检测到任何 USB 外设）\n提示：RC2 默认是 USB 从机模式，需要先让系统进入 host 模式。";
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : map.values()) {
            sb.append("• ").append(describe(d)).append('\n');
            sb.append("    串口可用: ").append(isSerialCandidate(d) ? "是" : "否").append('\n');
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                sb.append("    接口").append(d.getInterface(i).getId())
                        .append(" class=").append(d.getInterface(i).getInterfaceClass())
                        .append(" sub=").append(d.getInterface(i).getInterfaceSubclass())
                        .append(" ep=").append(d.getInterface(i).getEndpointCount()).append('\n');
            }
        }
        return sb.toString();
    }

    public static UsbDevice pick(Context c, String nameOrNull) {
        HashMap<String, UsbDevice> map = devices(c);
        if (map.isEmpty()) return null;
        if (nameOrNull != null && nameOrNull.length() > 0 && !"auto".equals(nameOrNull)) {
            for (UsbDevice d : map.values()) {
                if (nameOrNull.equals(d.getDeviceName())) return d;
            }
        }
        UsbDevice fallback = null;
        for (UsbDevice d : map.values()) {
            if (isSerialCandidate(d)) {
                boolean cdc = false;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_COMM) cdc = true;
                }
                if (cdc) return d;
                if (fallback == null) fallback = d;
            }
        }
        return fallback;
    }
}
