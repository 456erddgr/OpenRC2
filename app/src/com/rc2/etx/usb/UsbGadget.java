package com.rc2.etx.usb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * USB gadget 自举：脚本与报表描述符写进应用私有目录，再用 root 执行。
 * 内核开了 CONFIG_USB_CONFIGFS_F_HID，绑到 a600000.dwc3 后 PC 就认我们作 HID 手柄。
 * 代价是 Android 自己的 USB 复合设备（adb/mtp）被顶掉，restore() 可还原。
 */
public final class UsbGadget {
    private static final String TAG = "ETX-HID";

    private static final String SETUP_SH =
            "#!/system/bin/sh\n# RC2 -> USB HID joystick gadget.  No double quotes anywhere (this file is\n# inlined into a Java string literal in UsbGadget.java).\n# 关键：先让 Android 自己把 g1 干净摘掉（setprop sys.usb.config none），\n# 再去绑定我们的 gadget —— 直接写 g1/UDC 会在 ffs 还持有 fd 时把内核搞崩（实测会重启）。\nG=/config/usb_gadget/etxjoy\nD=$(dirname $0)\nDESC=$D/desc.b64\nUDC=a600000.dwc3\n\nsetprop sys.usb.config none\nsleep 2\n# 只在没有 hidg0 时才重建；已有说明已经是我们的 gadget，直接退出\n[ -c /dev/hidg0 ] && { echo already; exit 0; }\n\n[ -d $G ] && {\n  : > $G/UDC 2>/dev/null\n  rm -f $G/configs/c.1/hid.usb0\n  rmdir $G/functions/hid.usb0 $G/configs/c.1/strings/0x409 $G/configs/c.1 $G/strings/0x409 $G 2>/dev/null\n}\n\nmkdir -p $G || exit 1\necho 0x1209 > $G/idVendor\necho 0x4F54 > $G/idProduct\necho 0x0100 > $G/bcdDevice\necho 0x0200 > $G/bcdUSB\nmkdir -p $G/strings/0x409\necho EdgeTX > $G/strings/0x409/manufacturer\necho RC2_Joystick > $G/strings/0x409/product\necho 0001 > $G/strings/0x409/serialnumber\nmkdir -p $G/functions/hid.usb0\necho 0 > $G/functions/hid.usb0/protocol\necho 0 > $G/functions/hid.usb0/subclass\necho 10 > $G/functions/hid.usb0/report_length\nbase64 -d $DESC > $G/functions/hid.usb0/report_desc || exit 2\nmkdir -p $G/configs/c.1\necho 500 > $G/configs/c.1/MaxPower\nln -s $G/functions/hid.usb0 $G/configs/c.1/hid.usb0 || exit 3\nmkdir -p $G/configs/c.1/strings/0x409\necho hid > $G/configs/c.1/strings/0x409/configuration\n\necho $UDC > $G/UDC || { echo bind_failed; exit 4; }\nsleep 1\nchmod 666 /dev/hidg0 2>/dev/null\n\nST=$(cat /sys/class/udc/$UDC/state 2>/dev/null)\necho state=$ST\n[ $ST = configured ] || { echo not_configured_restoring; sh $D/restore.sh; exit 5; }\nls -l /dev/hidg0\n";

    private static final String RESTORE_SH =
            "#!/system/bin/sh\nG=/config/usb_gadget/etxjoy\nUDC=a600000.dwc3\n[ -d $G ] && {\n  : > $G/UDC 2>/dev/null\n  rm -f $G/configs/c.1/hid.usb0\n  rmdir $G/functions/hid.usb0 $G/configs/c.1/strings/0x409 $G/configs/c.1 $G/strings/0x409 $G 2>/dev/null\n  sleep 1\n}\nsetprop sys.usb.config mtp,adb\nsleep 2\necho state=$(cat /sys/class/udc/$UDC/state 2>/dev/null)\n";

    private static final String DESC_B64 = "BQEJBKEBFQAm/wN1EJUECTAJMQkyCTWBAgUJGQEpCBUAJQF1AZUIgQIFAQk5FQAlBzUARjsBZRR1CJUBgULA";

    private UsbGadget() { }

    public static File ensureScripts(Context c) {
        File dir = new File(c.getFilesDir(), "usbjoy");
        if (!dir.exists()) dir.mkdirs();
        write(new File(dir, "setup.sh"), SETUP_SH);
        write(new File(dir, "restore.sh"), RESTORE_SH);
        write(new File(dir, "desc.b64"), DESC_B64);
        return dir;
    }

    private static void write(File f, String s) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            o.write(s.getBytes("UTF-8"));
            o.close();
            f.setExecutable(true, false);
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "write " + f + " failed: " + t);
        }
    }

    public static String setup(Context c) {
        File dir = ensureScripts(c);
        String r = com.rc2.etx.diag.RootShell.run("sh " + dir.getAbsolutePath() + "/setup.sh");
        Log.i(TAG, "setup -> " + r);
        return r == null ? "" : r;
    }

    public static String restore(Context c) {
        File dir = ensureScripts(c);
        String r = com.rc2.etx.diag.RootShell.run("sh " + dir.getAbsolutePath() + "/restore.sh");
        Log.i(TAG, "restore -> " + r);
        return r == null ? "" : r;
    }

    public static boolean hasHidNode() {
        return new File(HidJoystick.DEV).exists();
    }
}
