package com.rc2.etx.diag;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;

/** 只读的系统事实采集，用于诊断页与交付文档。 */
public final class DeviceInfo {

    private DeviceInfo() {
    }

    public static String collect(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 设备 ==\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("设备: ").append(Build.DEVICE).append(" / ").append(Build.PRODUCT).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("版本号: ").append(Build.DISPLAY).append('\n');
        sb.append("ABI: ");
        for (String a : Build.SUPPORTED_ABIS) sb.append(a).append(' ');
        sb.append('\n');
        sb.append("Java 堆上限: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");

        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sb.append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                    .append(" @").append(dm.densityDpi).append("dpi (")
                    .append(String.format("%.2f", dm.density)).append("x)\n");
        } catch (Throwable t) {
            sb.append("屏幕: 取不到 ").append(t).append('\n');
        }

        sb.append("\n== 内存 ==\n");
        sb.append(grepLine("/proc/meminfo", "MemTotal"));
        sb.append(grepLine("/proc/meminfo", "MemAvailable"));
        sb.append(grepLine("/proc/meminfo", "SwapFree"));

        sb.append("\n== 输入设备 ==\n");
        sb.append(inputDevices());

        sb.append("\n== USB ==\n");
        sb.append(usb(c));

        sb.append("\n== 关键节点 ==\n");
        sb.append(exists("/dev/socket/fpv_sock"));
        sb.append(exists("/dev/usb-ffs/bulk/ep1"));
        sb.append(exists("/sys/class/usb_role/a600000.ssusb-role-switch/role"));
        sb.append(readOne("/sys/class/usb_role/a600000.ssusb-role-switch/role", "usb role"));
        sb.append(readOne("/sys/devices/platform/soc/a600000.ssusb/mode", "ssusb mode"));
        return sb.toString();
    }

    private static String grepLine(String path, String key) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) return line.trim() + "\n";
            }
        } catch (Throwable ignore) {
        } finally {
            close(r);
        }
        return key + ": ?\n";
    }

    private static String inputDevices() {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/bus/input/devices"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=") || line.startsWith("H: Handlers=")) {
                    sb.append("  ").append(line.trim()).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  读不到: ").append(t).append('\n');
        } finally {
            close(r);
        }
        if (sb.length() == 0) sb.append("  （无）\n");
        return sb.toString();
    }

    private static String usb(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> map = um.getDeviceList();
            sb.append("  授权设备数/可见设备数: ").append(map.size()).append('\n');
            for (UsbDevice d : map.values()) {
                sb.append("  ").append(d.getDeviceName())
                        .append("  ").append(String.format("%04x:%04x", d.getVendorId(), d.getProductId()))
                        .append("  接口 ").append(d.getInterfaceCount());
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    sb.append(" [").append(d.getInterface(i).getInterfaceClass())
                            .append('/').append(d.getInterface(i).getInterfaceSubclass()).append(']');
                }
                sb.append('\n');
            }
            if (map.isEmpty()) sb.append("  （当前没有可作为 USB 主机外设的设备）\n");
        } catch (Throwable t) {
            sb.append("  USB 服务取不到: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String exists(String path) {
        return "  " + path + (new java.io.File(path).exists() ? " 存在" : " 不存在") + '\n';
    }

    private static String readOne(String path, String label) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            return "  " + label + " = " + (line == null ? "?" : line.trim()) + '\n';
        } catch (Throwable t) {
            return "  " + label + " = 读不到\n";
        } finally {
            close(r);
        }
    }

    private static void close(BufferedReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
