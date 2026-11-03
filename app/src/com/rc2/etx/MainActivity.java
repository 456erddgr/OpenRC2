package com.rc2.etx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import com.rc2.etx.diag.DeviceInfo;
import com.rc2.etx.diag.RootShell;
import com.rc2.etx.diag.SelfTest;
import com.rc2.etx.jni.CrossCheck;
import com.rc2.etx.jni.NativeProbe;
import com.rc2.etx.radio.NullSink;
import com.rc2.etx.radio.Radio;
import com.rc2.etx.radio.Store;
import com.rc2.etx.radio.UdpSink;
import com.rc2.etx.ui.Host;
import com.rc2.etx.ui.RadioView;
import com.rc2.etx.usb.UsbSerial;
import com.rc2.etx.usb.UsbSerialSink;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity implements Host {
    private boolean padStreamOn = false;


    private static final String TAG = "ETX-Main";
    private Radio radio;
    private RadioView view;

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (UsbSerial.ACTION_PERMISSION.equals(i.getAction())) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                toast(ok ? "USB 已授权，可以点「应用并打开」了" : "USB 授权被拒绝");
            }
        }
    };

    /** 把任何未捕获的 Java 异常写进 files/crash_java.txt，方便用户复现「点一下就闪退」后取证。 */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.File f = new java.io.File(getFilesDir(), "crash_java.txt");
                    java.io.PrintWriter w = new java.io.PrintWriter(
                            new java.io.FileWriter(f, true), true);
                    w.println("=== " + System.currentTimeMillis() + " thread=" + t.getName());
                    e.printStackTrace(w);
                    w.close();
                    Log.e("ETX-Main", "crash logged", e);
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLogger();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        radio = Radio.get(this);
        radio.start();
        // 真实电池电压：Android 电池服务（adbshell dumpsys battery 里也能看到，实测 4206mV）
        IntentFilter bf = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent bs = registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                radio.vbatMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            }
        }, bf);
        if (bs != null) radio.vbatMv = bs.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        Log.i("ETX-Main", "vbat=" + radio.vbatMv + "mV");
        view = new RadioView(this, radio, this);
        // 开机进七页签视图（不再自动全屏）：以前这里强制全屏，用户看到的就是
        // “进去出不来、页签/设置点不到”。想看原版 EdgeTX 就点右上角「全屏」。
        initUsbWatcher();
        view.setFullNative(false);
        view.setPage(0);           // 0 = 原生页（EdgeTX 实时画面）
        setContentView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 沉浸式全屏：隐藏状态栏与导航栏，画面真正铺满 1920x1080（用户：现在不是全屏、还糊）
        applyImmersive();

        // 开机音 + 启动画面（应用级，不动系统 bootanimation）
        com.rc2.etx.audio.Sfx.setEnabled(com.rc2.etx.radio.Store.loadSfx(this));
        com.rc2.etx.audio.Sfx.boot();
        try {
            registerReceiver(usbRx, new IntentFilter(UsbSerial.ACTION_PERMISSION));
        } catch (Throwable t) {
            Log.w(TAG, "注册 USB 广播失败: " + t);
        }
        handleIntent(getIntent());
        startLiveDump();
    }

    /** 每 15 秒把最新摇杆采样落盘，这样用户什么时候拨杆都能事后取到。 */
    private void startLiveDump() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (radio.dumlOn) {
                        writeReport("duml_live.txt",
                                "stick=" + radio.duml.stickFrames
                                + " frames=" + radio.duml.frames
                                + " bad=" + radio.duml.badFrames
                                + " connected=" + radio.duml.connected
                                + (char) 10 + radio.duml.dumpSamples());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "live dump: " + t);
                }
                h.postDelayed(this, 15000);
            }
        }, 15000);
    }

    @Override
    public void onBackPressed() {
        // 全屏原生画面时，返回键先退出全屏，避免「进了全屏出不来」
        if (view != null && view.isFull()) { view.setFullNative(false); return; }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!radio.isRunning()) radio.start();
    }

    /** 沉浸式全屏（切回来时也重新隐藏，避免被系统"拉出"状态栏）。 */
    private void applyImmersive() {
        try {
            View dv = getWindow().getDecorView();
            dv.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable t) { android.util.Log.w("ETX", "immersive fail", t); }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    protected void onPause() {
        // 用户切走/按 Home 时也要落盘，否则“系统设置改了又回去”就丢了
        if (view != null) view.flushNativeSettings();
        super.onPause();
    }

    protected void onDestroy() {
        try { if (usbStateRx != null) unregisterReceiver(usbStateRx); } catch (Throwable ignore) { }
        try {
            unregisterReceiver(usbRx);
        } catch (Throwable ignore) {
        }
        radio.save();
        radio.stop();
        super.onDestroy();
    }

    /** adb 触发的无界面入口：--es action selftest|page|sink|info|root */
    /** 汇总一个输入设备：EV 掩码 + 摇杆轴解码。 */
    private static void flushDev(StringBuilder sb, String name, String handler, long ev, long abs) {
        if ("?".equals(name)) return;                 // 文件开头的伪块
        // Linux 输入子系统 EV 位： SYN=0x01 KEY=0x02 REL=0x04 ABS=0x08 MSC=0x10
        boolean hasAbs = (ev & 0x08L) != 0;
        // ABS 位： X=0 Y=1 Z=2 RX=3 RY=4 RZ=5 THROTTLE=19 RUDDER=20 WHEEL=21 GAS=22 BRAKE=23 HAT_X=16 HAT_Y=17
        int[] codes = {0, 1, 2, 3, 4, 5, 16, 17, 19, 20, 21, 22, 23,
                       47, 48, 49, 50, 53, 54, 57, 58};
        String[] ax = {"X", "Y", "Z", "RX", "RY", "RZ", "HAT_X", "HAT_Y", "THR", "RUD", "WHEEL", "GAS", "BRAKE",
                       "MT_SLOT", "MT_TOUCH_MAJOR", "MT_ORIENTATION", "MT_WIDTH_MAJOR",
                       "MT_POSITION_X", "MT_POSITION_Y", "MT_TRACKING_ID", "MT_PRESSURE"};
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (abs >= 0 && (abs & (1L << codes[i])) != 0) {
                if (got.length() > 0) got.append(',');
                got.append(ax[i]);
            }
        }
        boolean analog = abs >= 0 && (abs & 0x3FL) != 0;   // X,Y,Z,RX,RY,RZ
        boolean stickLike = hasAbs && analog;
        sb.append("  >> ").append(stickLike ? "STICK-CAPABLE " : "")
          .append("name=").append(name)
          .append(" ev=").append(ev < 0 ? "?" : Long.toHexString(ev))
          .append(" hasABS=").append(hasAbs)
          .append(" axes=").append(got.length() == 0 ? "(none)" : got.toString())
          .append((char) 10);
    }

    // ===== USB 插入 -> 三选项（对齐原版 EdgeTX 的"手柄/存储/串口"对话框）=====
    private android.content.BroadcastReceiver usbStateRx;
    private volatile int usbConnected = -1;
    private boolean usbDialogShown;
    private com.rc2.etx.ui.UsbDialog usbDlg;

    private void initUsbWatcher() {
        usbStateRx = new android.content.BroadcastReceiver() {
            public void onReceive(Context c, Intent it) {
                int cs = Intent.ACTION_POWER_CONNECTED.equals(it.getAction())
                        ? 1 : it.getIntExtra("connected", 0);
                if (usbConnected < 0) { usbConnected = cs; return; }   // 首次只记状态，不弹
                if (cs == 0) usbDialogShown = false;                    // 拔线复位
                if (cs == 1) showUsbModeDialog();                        // 插上就弹（不再要求 0->1）
                usbConnected = cs;
            }
        };
        IntentFilter f = new IntentFilter("android.hardware.usb.action.USB_STATE");
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        registerReceiver(usbStateRx, f);
        // 已经插着线时（App 后启动）也要弹一次：线一直插着不会有 0->1 广播，
        // 之前只等广播导致"插着永远不弹"（m05206）。
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        // 注意：绑上 HID gadget 之后系统不再报"在充电"，只靠 isCharging() 会永远不弹（回归 bug）。
        // 所以再加两条：①/dev/hidg0 在（说明线接着且已经是手柄模式）②USB 供电属性非 0。
        boolean gadget = false;
        try { gadget = com.rc2.etx.usb.UsbGadget.hasHidNode(); } catch (Throwable ignore) { }
        // 2026-11-03 追加：真正可靠的"USB 在位"信号是内核的供电在线标志。
        // isCharging() 在部分 ROM 上电池自放电时也会误报 true，导致没插线也弹。
        final boolean vbus = usbVbusOnline();
        // 2026-11-03 修正（用户报「没连 USB 却一直弹 USB 已连接」）：
        // 原来这里还有一条 powered = BATTERY_PROPERTY_STATUS != 0 —— 这个属性
        // 表示的是**电池状态**（放电时也是非 0 的 CHARGING/UNKNOWN 之类），
        // 跟 USB 有没有插线毫无关系，所以没插线也会被判成 plugged 并弹窗。
        // 现在只认两件真正可靠的事：①正在充电（外部供电）②HID gadget 节点在
        // （说明已经切成手柄模式，线肯定是接着的）。
        // 判定口径（从严到宽）：①VBUS 在线（最可靠）②HID gadget 节点在
        // ③充电中 **且** 设备树里能读到 usb 供电节点。三者任一即可。
        final boolean charging = bm != null && bm.isCharging() && vbus;
        final boolean plugged = (vbus || gadget || charging)
                && getIntent() != null && getIntent().getStringExtra("action") == null;
        // 2026-11-03 关键修正（用户报「系统操作不了了、旁边的按键全控制不了」）：
        // 原来 App 一启动、只要线插着就 1.5 秒后自动弹这个模态窗，
        // 之后 20 秒又自动关、再复位 usbDialogShown —— 结果是**绝大多数时间画面被
        // 这个遮罩盖着**，点按键条全被它吃掉，看起来就是「按键全控制不了」。
        // 改为：**启动时绝不自动弹**，只在真的发生「插线事件」的那一次弹。
        Log.i(TAG, "usb: vbus=" + vbus + " gadget=" + gadget + " charging=" + charging
                + " -> 启动不自动弹窗");
    }

    private static final String NL = String.valueOf((char) 10);

    /** 真正可靠的"USB 在位"信号：/sys/class/power_supply 下任一 type=Mains 的 online==1。
     *  本机（RC2）节点叫 aprimary-ac，不是通用的 usb，所以必须扫目录而不是写死路径。
     *  用 isCharging() 判断会在电池自放电时误报 → 没插线也弹「USB 已连接」。*/
    private boolean usbVbusOnline() {
        final java.io.File dir = new java.io.File("/sys/class/power_supply");
        final java.io.File[] list = dir.listFiles();
        if (list == null) return false;
        final String NL = String.valueOf((char) 10);
        for (java.io.File d : list) {
            try {
                String type = readSmall(new java.io.File(d, "type")).trim();
                if (!"Mains".equalsIgnoreCase(type) && !"USB".equalsIgnoreCase(type)
                        && !"Usb".equalsIgnoreCase(type)) continue;
                if ("1".equals(readSmall(new java.io.File(d, "online")).trim())) return true;
            } catch (Throwable ignore) { }
        }
        return false;
    }

    private static String readSmall(java.io.File f) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String ln = br.readLine(); br.close();
            return ln == null ? "" : ln;
        } catch (Throwable t) { return ""; }
    }

    private void showHidHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("把遥控器变成 USB 游戏手柄")
                .setMessage("这一步只需要做一次（建 USB 手柄需要 root 层，Android 侧没有公开接口）。" + NL + "" + NL + "方法一（推荐，什么都不用装）：" + NL + "  双击软件目录里的 pc - RC2-USB手柄-开启.bat（电脑需能跑 adb devices）。" + NL + "" + NL + "方法二（命令行）：" + NL + "  adb shell su -c sh /data/local/tmp/usbjoy/setup.sh" + NL + "" + NL + "成功标志：电脑「设备和打印机」里出现 HID-compliant game controller，" + NL + "之后每次插线在这里选「游戏手柄」就能直接用（不必再 root）。" + NL + "" + NL + "想恢复 USB 传文件/调试：选「文件存储 / USB 调试」，" + NL + "或双击 pc - RC2-USB手柄-还原.bat。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("仍要尝试 root 执行", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String r = com.rc2.etx.usb.UsbGadget.setup(MainActivity.this);
                        toast("结果：" + (r.length() > 120 ? r.substring(0, 120) : r));
                    }
                })
                .show();
    }

    /** 叠一层启动画面，1.6 秒后自动移除；按任意位置也能立刻跳过。 */
    private void showSplash() {
        try {
            final ViewGroup root = (ViewGroup) view.getParent();
            if (root == null) { view.post(new Runnable() { public void run() { showSplash(); } }); return; }
            android.graphics.drawable.Drawable ic = null;
            try { ic = getPackageManager().getApplicationIcon(getPackageName()); } catch (Throwable ignore) { }
            final com.rc2.etx.ui.Splash sp = new com.rc2.etx.ui.Splash(this, ic, null);
            sp.setOnFinish(new Runnable() {
                public void run() { try { root.removeView(sp); } catch (Throwable ignore) { } }
            });
            sp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { try { root.removeView(sp); } catch (Throwable ignore) { } }
            });
            root.addView(sp, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignore) { }
    }

    private void showUsbModeDialog() {
        if (usbDialogShown || isFinishing()) return;
        usbDialogShown = true;
        usbDlg = new com.rc2.etx.ui.UsbDialog(this, new com.rc2.etx.ui.UsbDialog.Pick() {
            public void onPick(int which) {
                usbDialogShown = false;
                if (which == 0) {
                    // 虚拟手柄：不碰 root、不建 gadget。设备端只负责把摇杆数据发到 127.0.0.1:9999，
                    // 电脑端（OpenRC2-Pad）执行 adb reverse 后就能收；模拟器里表现为一个 Xbox 手柄。
                    radio.setPadStreamOn(true);
                    toast("虚拟手柄已开：电脑端打开 OpenRC2-Pad 点「开启连接」即可（无需 root）");
                } else if (which == 1) {
                    // 绝不在 App 里直接 su：这个 ROM 上 su 会弹授权框，
                    // 且构建 gadget 期间若有脚本去抢 UDC 会导致内核重启。
                    // 只做两件事：①已有 /dev/hidg0 就直接用；②否则给出电脑端一键脚本的指引。
                    if (com.rc2.etx.usb.UsbGadget.hasHidNode()) {
                        radio.setHidOn(true);
                        toast("已是手柄模式，直接在模拟器里选 HID-compliant game controller");
                    } else {
                        showHidHelpDialog();
                    }
                } else if (which == 2) {
                    radio.setHidOn(false);
                    radio.setPadStreamOn(false);
                    com.rc2.etx.usb.UsbGadget.restore(MainActivity.this);
                    toast("已还原为文件存储 / USB 调试");
                } else if (which == 3) {
                    toast("串口模式暂未实现");
                }
            }
        });
        usbDlg.show();
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String act = i.getStringExtra("action");
        if (act == null) return;
        Log.i(TAG, "intent action=" + act);
        if ("selftest".equals(act)) {
            runSelfTest();
        } else if ("info".equals(act)) {
            String info = DeviceInfo.collect(this);
            Log.i(TAG, info);
            writeReport("devices.txt", info);
            view.setPage(4);
            toast("设备信息已写入文件");
        } else if ("root".equals(act)) {
            RootShell.runAsync("id; uname -a; getprop ro.build.version.release", new RootShell.Cb() {
                @Override
                public void done(String out) {
                    Log.i(TAG, "root: " + out);
                    writeReport("root.txt", out);
                }
            });
        } else if ("duml".equals(act)) {
            boolean on = i.getIntExtra("on", 1) != 0;
            final long sec = Math.max(3, i.getIntExtra("sec", 5));
            radio.setDumlOn(on);
            view.setPage(5);
            Log.i(TAG, "duml on=" + on);
            if (on) {
                // 5 秒后把摇杆状态写文件，方便 adb 无界面验证
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        String rep = "connected=" + radio.duml.connected
                                + " err=" + radio.duml.err
                                + " frames=" + radio.duml.frames
                                + " stick=" + radio.duml.stickFrames
                                + " bad=" + radio.duml.badFrames
                                + " hz=" + radio.duml.hz
                                + " raw=" + rawList(radio.duml.raw)
                                + " sec=" + sec
                                + " hex=" + radio.duml.lastHex
                                + "\nus=" + usList(radio.usSnapshot());
                        Log.i(TAG, "DUML-REPORT " + rep);
                        writeReport("duml.txt", rep + (char) 10 + "---- 帧类型普查 ----" + (char) 10
                                + radio.duml.census() + (char) 10 + "---- 摇杆采样 ----" + (char) 10
                                + radio.duml.dumpSamples());
                    }
                }, sec * 1000L);
            }
        } else if ("native".equals(act)) {
            view.setPage(1);
            final String rep = NativeProbe.run(this, radio);
            Log.i(TAG, "NATIVE-REPORT " + rep);
            writeReport("native.txt", rep);
            toast("原生核心自检已写入 native.txt");
        } else if ("yml".equals(act)) {
            final String rep = (radio.yml == null ? "(未加载)" : radio.yml.summary());
            Log.i(TAG, "YML-REPORT " + rep);
            writeReport("radioyml.txt", rep);
            toast("radio.yml 解析结果已写入 radioyml.txt");
        } else if ("key".equals(act)) {
            final int k = i.getIntExtra("k", 4);
            // hold=按下保持时长(ms)。短按 60ms；>400ms 才会被 EdgeTX 识别为 LONG_PRESS
            // （KEY_MODEL 长按 = 模型管理 ModelLabelsWindow，见 datastructs_radio.cpp:108）
            final int hold = Math.max(20, i.getIntExtra("hold", 60));
            com.rc2.etx.jni.EtxNative.press(k);
            view.postDelayed(new Runnable() {
                public void run() { com.rc2.etx.jni.EtxNative.release(k); }
            }, hold);
            Log.i(TAG, "press " + k + " (release in " + hold + "ms)");
        } else if ("a11y".equals(act)) {
            // 跳系统「无障碍」设置页，让用户手动勾选本 App 的按键服务。
            // 无障碍服务只能由用户手动开启，没有入口等于没做（plus2-port §11.3）。
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("请在本列表里开启「" + getString(com.rc2.etx.R.string.app_name) + "」的按键服务");
            } catch (Throwable e) {
                toast("打开无障碍设置失败: " + e);
            }
            Log.i(TAG, "intent action=a11y");
        } else if ("card".equals(act)) {
            int ci = i.getIntExtra("i", 0);
            if (view != null) view.fireCard(ci);
            Log.i(TAG, "intent action=card i=" + ci);
        } else if ("rotary".equals(act)) {
            // 旋钮钩子：n>0 顺时针 n 格，n<0 逆时针 |n| 格（供命令行验页内滚动/改值）
            final int n = i.getIntExtra("n", 1);
            final int step = n >= 0 ? 1 : -1;
            for (int q = 0; q < Math.abs(n); q++) {
                com.rc2.etx.jni.EtxNative.turn(step);
            }
            Log.i(TAG, "rotary n=" + n);
        } else if ("usbmenu".equals(act)) {
            usbDialogShown = false;
            showUsbModeDialog();
        } else if ("hid".equals(act)) {
            final boolean on = i.getIntExtra("on", 1) != 0;
            radio.setHidOn(on);
            android.util.Log.i(TAG, "hid on=" + on + " open=" + radio.hid.isOpen()
                    + " err=" + radio.hid.error());
            writeReport("hid.txt", "on=" + on + " | open=" + radio.hid.isOpen()
                    + " | reports=" + radio.hid.reports() + " | " + radio.hid.stats() + " | err=" + radio.hid.error());
        } else if ("fullmode".equals(act)) {
            com.rc2.etx.ui.NativePanel.FORCE_FULL = i.getIntExtra("on", 1) != 0;
            Log.i(TAG, "FORCE_FULL=" + com.rc2.etx.ui.NativePanel.FORCE_FULL);
            toast("整屏重绘 " + (com.rc2.etx.ui.NativePanel.FORCE_FULL ? "开" : "关"));
        } else if ("tab".equals(act)) {
            if (view != null) { view.setFullNative(false); view.setPageForTest(i.getIntExtra("i", 0)); }
        } else if ("vtap".equals(act)) {
            // 把合成点击派发到 RadioView 自己（页签/卡片/按钮），
            // 与 "touch" 不同：后者只进 libetx 原生画面。
            final float vx = i.getIntExtra("tx", 0), vy = i.getIntExtra("ty", 0);
            if (view != null) view.setFullNative(false);
            final long t0 = android.os.SystemClock.uptimeMillis();
            view.post(new Runnable() { public void run() {
                android.view.MotionEvent d = android.view.MotionEvent.obtain(
                        t0, t0, android.view.MotionEvent.ACTION_DOWN, vx, vy, 0);
                view.dispatchTouchEvent(d);
                d.recycle();
                view.postDelayed(new Runnable() { public void run() {
                    long t1 = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent u = android.view.MotionEvent.obtain(
                            t0, t1, android.view.MotionEvent.ACTION_UP, vx, vy, 0);
                    view.dispatchTouchEvent(u);
                    u.recycle();
                    Log.i(TAG, "vtap " + vx + "," + vy);
                } }, 60);
            } });
        } else if ("fulltap".equals(act)) {
            // 全屏原生页里的合成点击：与 vtap 一样派发给 RadioView，
            // 但**不**退出全屏（vtap 会 setFullNative(false)，没法测按键条）。
            final float vx = i.getIntExtra("tx", 0), vy = i.getIntExtra("ty", 0);
            if (view != null) {
                final long t0 = android.os.SystemClock.uptimeMillis();
                view.post(new Runnable() { public void run() {
                    android.view.MotionEvent d = android.view.MotionEvent.obtain(
                            t0, t0, android.view.MotionEvent.ACTION_DOWN, vx, vy, 0);
                    view.dispatchTouchEvent(d);
                    d.recycle();
                    view.postDelayed(new Runnable() { public void run() {
                        long t1 = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent u = android.view.MotionEvent.obtain(
                                t0, t1, android.view.MotionEvent.ACTION_UP, vx, vy, 0);
                        view.dispatchTouchEvent(u);
                        u.recycle();
                        Log.i(TAG, "fulltap " + vx + "," + vy);
                    } }, 60);
                } });
            }
        } else if ("touch".equals(act)) {
            // 可复现的触摸注入钩子：本 ROM 上 `adb shell input tap` 经常被吞掉
            // （getevent 抓不到内核事件），所以提供一条确定能到 libetx 的路。
            final int tx = i.getIntExtra("tx", 240);
            final int ty = i.getIntExtra("ty", 88);
            // action 码与 libetx 约定一致：0=DOWN 1=MOVE 2=UP
            // 额外提供 act=tap（DOWN 40ms 后 UP）与 act=drag（DOWN→N×MOVE→UP）
            final String mode = i.getStringExtra("act");
            final int kind = i.getIntExtra("kind", -1);
            final int moves = i.getIntExtra("moves", 0);
            final int dy = i.getIntExtra("dy", 0);
            try {
                if ("tap".equals(mode)) {
                    com.rc2.etx.jni.EtxNative.touch(0, tx, ty);
                    view.postDelayed(new Runnable() { public void run() {
                        com.rc2.etx.jni.EtxNative.touch(2, tx, ty); } }, 40);
                    Log.i(TAG, "touch tap tx=" + tx + " ty=" + ty);
                } else if ("drag".equals(mode)) {
                    com.rc2.etx.jni.EtxNative.touch(0, tx, ty);
                    final int n = moves > 0 ? moves : 6;
                    for (int k = 1; k <= n; k++) {
                        final int yy = ty + dy * k / n;
                        view.postDelayed(new Runnable() { public void run() {
                            com.rc2.etx.jni.EtxNative.touch(1, tx, yy); } }, 16 * k);
                    }
                    view.postDelayed(new Runnable() { public void run() {
                        com.rc2.etx.jni.EtxNative.touch(2, tx, ty + dy); } }, 16 * (n + 1));
                    Log.i(TAG, "touch drag tx=" + tx + " ty=" + ty + " dy=" + dy);
                } else {
                    int a = kind >= 0 ? kind : 0;
                    com.rc2.etx.jni.EtxNative.touch(a, tx, ty);
                    Log.i(TAG, "touch injected tx=" + tx + " ty=" + ty + " kind=" + a);
                }
            } catch (Throwable t) {
                Log.w(TAG, "touch inject failed: " + t);
            }
        } else if ("tap".equals(act)) {
            final String rep = com.rc2.etx.jni.TapProbe.run(this, i.getIntExtra("tx", 240), i.getIntExtra("ty", 88));
            writeReport("tap.txt", rep);
            Log.i(TAG, "TAP-REPORT " + rep.replace((char) 10, '|'));
        } else if ("keys".equals(act)) {
            final String rep = com.rc2.etx.jni.NativeProbe.runKeys(this);
            writeReport("keys.txt", rep);
        } else if ("menu".equals(act)) {
            final String rep = com.rc2.etx.jni.NativeProbe.runMenu(this);
            writeReport("menu.txt", rep);
        } else if ("devices".equals(act)) {
            // 列出本机可枚举的输入设备（判断有没有外接手柄）
            StringBuilder sb = new StringBuilder();
            sb.append("=== Android InputDevice (public API) ===").append((char) 10);
            try {
                int[] ids = android.view.InputDevice.getDeviceIds();
                sb.append("count=").append(ids == null ? 0 : ids.length).append((char) 10);
                if (ids != null) {
                    for (int id : ids) {
                        android.view.InputDevice d = android.view.InputDevice.getDevice(id);
                        if (d == null) continue;
                        int sc = d.getSources();
                        // 注意：SOURCE_GAMEPAD 含 SOURCE_CLASS_BUTTON/KEYBOARD 位，
                        // 用它会把键盘/电源键判成手柄；只认 class 级 JOYSTICK / GAMEPAD。
                        boolean pad = (sc & (0x00000010 | 0x00000400)) != 0;
                        sb.append(pad ? "GAMEPAD " : "        ")
                          .append("id=").append(d.getId())
                          .append(" name=").append(d.getName())
                          .append(" sources=0x").append(Integer.toHexString(sc))
                          .append((char) 10);
                    }
                }
            } catch (Throwable t) {
                sb.append("ERR ").append(t.toString()).append((char) 10);
            }
            sb.append("=== /proc/bus/input/devices ===").append((char) 10);
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream("/proc/bus/input/devices")));
                String ln;
                String curName = "?";
                String curHandler = "";
                long evMask = -1, absMask = -1;
                while ((ln = br.readLine()) != null) {
                    String t2 = ln.trim();
                    if (t2.startsWith("N:")) {
                        flushDev(sb, curName, curHandler, evMask, absMask);
                        int q1 = t2.indexOf('"');
                        int q2 = q1 >= 0 ? t2.indexOf('"', q1 + 1) : -1;
                        curName = (q1 >= 0 && q2 > q1) ? t2.substring(q1 + 1, q2) : t2;
                        curHandler = "";
                        evMask = -1; absMask = -1;
                    } else if (t2.startsWith("H:")) {
                        curHandler = t2;
                    } else if (t2.startsWith("B: EV=")) {
                        try { evMask = Long.parseLong(t2.substring(6).trim(), 16); } catch (Throwable ig) { evMask = -1; }
                    } else if (t2.startsWith("B: ABS=")) {
                        // 内核打印的是两个 32 位字，**高位在前**："B: ABS=6658000 0"
                        // → 高字 0x06658000（位 15,16,18,21,22,25,26）+ 低字 0
                        //   = ABS 位 47,48,50,53,54,57,58（MT_SLOT/TOUCH_MAJOR/…/PRESSURE）
                        try {
                            String[] w = t2.substring(8).trim().split("[ \t]+");
                            long hi = Long.parseLong(w[0], 16);
                            long lo = w.length > 1 ? Long.parseLong(w[1], 16) : 0L;
                            absMask = (hi << 32) | lo;
                        } catch (Throwable ig) { absMask = -1; }
                    }
                    sb.append(ln).append((char) 10);
                }
                flushDev(sb, curName, curHandler, evMask, absMask);
                br.close();
            } catch (Throwable t) {
                sb.append("ERR ").append(t.toString()).append((char) 10);
            }
            view.setPage(1);
            writeReport("devices.txt", sb.toString());
        } else if ("calibstart".equals(act)) {
            view.debugBeginCalib();
        } else if ("crosscheck".equals(act)) {
            view.setPage(1);
            final String rep = CrossCheck.run(this, radio);
            Log.i(TAG, "K5-REPORT " + rep);
            writeReport("crosscheck.txt", rep);
            toast("K5 交叉校验已写入 crosscheck.txt");
        } else if ("calibsim".equals(act)) {
            if (view != null) view.debugCalibSim(i.getIntExtra("p", 0));
        } else if ("calibforce".equals(act)) {
            float mm = i.getFloatExtra("m", 0.85f);
            if (view != null) view.debugCalibForce(mm);
        } else if ("full".equals(act)) {
            final boolean on = i.getIntExtra("on", 0) != 0;
            view.post(new Runnable() { public void run() { view.setFullNative(on); } });
            Log.i(TAG, "intent action=full on=" + on);
        } else if ("pad".equals(act)) {
            // 虚拟手柄数据流开关：--es action pad --ei on 1/0
            boolean on = i.getIntExtra("on", 1) != 0;
            // 2026-11-02：PC 端巡检会反复 am start，原来无脑弹 toast -> 隔几秒弹一次盖住画面。
            // 现在只在状态真的变化时提示，重复请求静默。
            if (padStreamOn != on) {
                padStreamOn = on;
                radio.setPadStreamOn(on);
                toast("虚拟手柄数据流 " + (on ? "已开（PC 端 adb reverse + ViGEm）" : "已关"));
            } else {
                Log.i(TAG, "intent action=pad on=" + on + " (no change)");
            }
        } else if ("bindrate".equals(act)) {
            // 自测：--es action bindrate --ei ms 8   调整 RC Pro binder 轮询间隔（0=尽量快）
            int ms = i.getIntExtra("ms", 8);
            getSharedPreferences("etx", 0).edit()
                    .putInt("rcproPollMs", ms).apply();
            com.rc2.etx.input.RcProService.pollIntervalMs = ms;
            Log.i(TAG, "intent action=bindrate ms=" + ms);
        } else if ("vbat".equals(act)) {
            // 自测预警音：--es action vbat --ei mv 3000（低于 radio.yml 的电池警告阈值即触发）
            int mv = i.getIntExtra("mv", 3000);
            int rc = -1;
            try { rc = com.rc2.etx.jni.EtxNative.setVbat(mv); } catch (Throwable t) {
                Log.e(TAG, "setVbat 异常: " + t);
            }
            Log.i(TAG, "intent action=vbat mv=" + mv + " rc=" + rc);
        } else if ("audio".equals(act)) {
            // 自测：--es action audio [--ei freq 440 --ei len 200]
            int fq = i.getIntExtra("freq", 440), ln = i.getIntExtra("len", 200);
            boolean ok = false;
            try { ok = com.rc2.etx.jni.EtxNative.audioTone(fq, ln, 0); } catch (Throwable t) {
                Log.e(TAG, "audioTone 异常: " + t);
            }
            Log.i(TAG, "intent action=audio freq=" + fq + " len=" + ln + " rc=" + (ok ? 1 : 0));
            if (!ok) {
                // 原生 JNI 入口还没接上时，用 Java 侧方波自证音频通路（AudioTrack 32k/单声道）
                int n = 32000 * ln / 1000;
                short[] pcm = new short[n];
                for (int k = 0; k < n; k++) {
                    double ph = (k * (double) fq / 32000.0) % 1.0;
                    pcm[k] = (short) (ph < 0.5 ? 9000 : -9000);
                }
                for (int k = 0; k < n; k += 320)
                    com.rc2.etx.audio.EtxAudio.write(java.util.Arrays.copyOfRange(pcm, k, Math.min(n, k + 320)), Math.min(320, n - k));
                Log.i(TAG, "Java 兜底方波已写入 " + n + " 样本");
            }
            toast("测试音 " + fq + "Hz rc=" + (ok ? 1 : 0));
        } else if ("model".equals(act)) {
            // 遥控器型号：--es action model --ei v 0(自动)/1(RC2)/2(RC Pro)
            int mv = i.getIntExtra("v", 0);
            com.rc2.etx.radio.Store.saveModel(this, mv);
            Log.i(TAG, "intent action=model v=" + mv);
            toast("型号已切换为 " + (mv == 0 ? "自动识别" : mv == 1 ? "RC2" : "RC Pro"));
        } else if ("page".equals(act)) {
            view.setPage(i.getIntExtra("p", 0));
        } else if ("sink".equals(act)) {
            String kind = i.getStringExtra("kind");
            String target = i.getStringExtra("target");
            int baud = i.getIntExtra("baud", 420000);
            if ("udp".equals(kind)) {
                UdpSink u = new UdpSink();
                boolean ok = u.open(target == null ? "255.255.255.255:8888" : target);
                radio.setSink(u);
                Log.i(TAG, "sink udp ok=" + ok + " " + u.status());
            } else if ("usb".equals(kind)) {
                UsbSerialSink us = new UsbSerialSink(this);
                us.setBaud(baud);
                boolean ok = us.open("auto");
                radio.setSink(us);
                Log.i(TAG, "sink usb ok=" + ok + " " + us.status() + " | " + us.trace());
            } else {
                radio.setSink(new NullSink());
            }
        }
    }

    private static String rawList(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
        return sb.append(']').toString();
    }

    private static String usList(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
        return sb.append(']').toString();
    }

    private void runSelfTest() {
        final String rep;
        rep = SelfTest.run();
        Log.i(TAG, "SELFTEST\n" + rep);
        String path = writeReport("selftest.txt", rep + "\n---- 设备 ----\n" + DeviceInfo.collect(this));
        view.setPage(4);
        int fail = 0;
        for (String line : rep.split("\n")) {
            if (line.startsWith("FAIL")) fail++;
        }
        toast(fail == 0 ? ("自检全部通过，报告 " + path) : ("自检有 " + fail + " 项失败，报告 " + path));
    }

    private String writeReport(String name, String content) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(content.getBytes("UTF-8"));
            fo.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "写报告失败: " + t);
            return "(写入失败)";
        }
    }

    @Override
    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void prompt(String title, String initial, boolean numeric, final Host.PromptCb cb) {
        final EditText et = new EditText(this);
        et.setText(initial == null ? "" : initial);
        et.setSelectAllOnFocus(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        cb.onResult(et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onSettingsChanged() {
        radio.save();
        view.invalidate();
    }

    @Override
    public void invalidateUi() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.invalidate();
            }
        });
    }
}
