package com.rc2.etx.radio;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.rc2.etx.input.DumlStick;

/**
 * 遥控器核心：100Hz 控制环。
 *   input(触摸/物理) -> mixer(16ch) -> CRSF 编码 -> sink(UDP/USB/文件/空)
 */
public final class Radio {

    private static final String TAG = "ETX-Radio";
    public static final int CH_COUNT = Mixer.CH_COUNT;
    public static final int PERIOD_MS = 10;

    public final InputState input = new InputState();
    /** 通道校准 / 行程采集。 */
    public final Calib calib = new Calib();
    public RadioYml yml;
    /** 机内摇杆（dji_link 本地 TCP 推送）。 */
    /** 进程级单例：Activity 重建时复用同一个 Radio，避免出现第二条 etx-duml 线程。 */
    private static volatile Radio INSTANCE;

    public static synchronized Radio get(android.content.Context c) {
        if (INSTANCE == null) INSTANCE = new Radio(c.getApplicationContext());
        return INSTANCE;
    }

    public final DumlStick duml = new DumlStick();
    /** USB HID 手柄（PC 模拟器用）。只有在 /dev/hidg0 存在且 root 建好 gadget 后才能真正发报文。 */
    public final com.rc2.etx.usb.HidJoystick hid = new com.rc2.etx.usb.HidJoystick();

    /** 虚拟手柄数据流（PC 端 ViGEm 造手柄，走 adb reverse 本地 TCP，无需 root）。 */
    public final com.rc2.etx.usb.PadStream padStream = new com.rc2.etx.usb.PadStream();
    /** 原生核心算出来的真实通道 us（172..1811），由 NativePanel 每帧刷新；HID 用它当唯一真相源。 */
    public final int[] hidUs = new int[CH_COUNT];
    public volatile boolean hidUsValid = false;
    public volatile boolean hidOn = false;
    // HID 最新值（由 tick 写、hidLoop 读，固定节拍发包）
    private volatile int hidX = 512, hidY = 512, hidZ = 512, hidRz = 512, hidBtn = 0;
    private Thread hidThread;
    /** RC Pro(RM510) 的 binder 摇杆源；非空时由 rcBridge 把值搬进 duml.raw。 */
    private com.rc2.etx.input.RcProStick rcpro;
    private Thread rcBridge;
    private int gapFills;

    /** 是否启用手内摇杆输入。 */
    public volatile boolean dumlOn;
    /** 最近一次从系统电池服务读到的电压(mV)，0=未知。 */
    public volatile int vbatMv = 0;
    /** 是否按 7 轴（RH,RV,LV,LH,P1,P2,P3）喂原生核心。 */
    public volatile boolean nativeAxes7 = true;
    private final StickSmooth smooth = new StickSmooth();
    private long stillSince = 0L;
    private final float[] sv = new float[6];
    private long lastStickSeq = -1;
    public final Model model;
    private final Mixer mixer = new Mixer();
    private final CrsfEncoder.Encoder encoder = new CrsfEncoder.Encoder();

    private final int[] us = new int[CH_COUNT];
    private final int[] pct = new int[CH_COUNT];
    private final byte[] lastFrame = new byte[CrsfEncoder.FRAME_LEN];

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private volatile boolean mixerOn = true;
    private volatile OutputSink sink = new NullSink();

    // 统计
    public volatile long ticks;
    /** 最近一次用 binder 补帧的时间（独立于 TCP 的 lastStickMs）。 */
    public volatile long lastBinderMs;
    public volatile int hz;
    private long hzFrom;
    private int hzCount;
    public volatile long lastTickMs;
    public volatile int maxGapMs;
    public volatile String crsfHex = "";
    private volatile int hexTick;

    private final Context ctx;

    /** 看门狗：超过这个时间没收到摇杆帧就重连 socket。 */
    private final float[] du = new float[6];
    private final int[] rawSnap = new int[6];
    private static final long STICK_WD_MS = 4000;
    private long lastStickFrameAt;
    private long lastWdAt;
    private int wdInBurst;
    private int wdRestarts;

    public Radio(Context c) {
        ctx = c;
        model = Store.load(c);
        calib.fromJson(Store.loadCalib(c));
        yml = RadioYml.load(RadioYml.defaultFile(c.getExternalFilesDir(null)));
        if (yml.ok) {
            // MCU 通道 -> radio.yml 轴序：ch0=RH ch1=RV ch2=LV ch3=LH ch4=P1 ch5=P2
            int[] map = {3, 2, 1, 0, 4, 5};
            int[] m = new int[6], ng = new int[6], ps = new int[6];
            for (int i = 0; i < 6; i++) {
                m[i] = yml.mid[map[i]];
                ng[i] = yml.spanNeg[map[i]];
                ps[i] = yml.spanPos[map[i]];
            }
            duml.setAxisSpans(m, ng, ps);
        }
        input.calib = calib;
        // 校准结果在每次启动都要重新落到 raw 行程上，否则重启即失效
        if (calib.enabled) {
            int[] mcuCh = {3, 2, 0, 1};        // calib 轴 LX/LY/RX/RY -> MCU LH/LV/RH/RV
            for (int a = 0; a < 4; a++) {
                float m = calib.max[a];
                if (m > 0.3f && m < 1.5f) duml.shrinkSpan(mcuCh[a], m);
            }
        }
        mixer.setConfig(model.ch);
        input.reset();
        dumlOn = Store.loadDumlOn(c);
        // HID 手柄：开机时若 gadget 节点还在（用户选过"游戏手柄"），自动续开，
        // 否则 PC 能认出设备却收不到任何报文 —— 就是"模拟器识别但摇杆不动"。
        if (com.rc2.etx.usb.UsbGadget.hasHidNode()) {
            hidOn = true;
            hid.open();
            startHidLoop();
        }
        lastStickFrameAt = SystemClock.elapsedRealtime();
        startRcProIfAny();

        // 开关文件存在就自启虚拟手柄数据流（PC 侧 ViGEm 方案，不依赖 root）
        try {
            // 默认常开：以前只在“开关文件存在”时才启动，而没人写过这个文件，
            // 于是每次重启都要手动开一次（用户报的“一重启就不好使”）。
            boolean wantPad = com.rc2.etx.usb.PadStream.flagFile(ctx).exists();
            java.io.File offF = new java.io.File(com.rc2.etx.usb.PadStream.flagFile(ctx).getParentFile(), "padbridge.off");
            if (wantPad || !offF.exists()) padStream.start(ctx);
        } catch (Throwable ignore) { }
        if (dumlOn) {
            start();
            duml.start();
        }
    }

    /** 机内摇杆看门狗重连次数（诊断用）。 */
    public int watchdogRestarts() {
        return wdRestarts;
    }

    public Context context() {
        return ctx;
    }

    public Mixer mixer() {
        return mixer;
    }

    public OutputSink sink() {
        return sink;
    }

    public synchronized void setSink(OutputSink s) {
        OutputSink old = sink;
        sink = (s == null) ? new NullSink() : s;
        if (old != null && old != sink) {
            try {
                old.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 开关机内摇杆输入（连 127.0.0.1:40007）。 */
    /** 填给原生核心的 4 轴 + 2 旋钮，单位是硬件原始 11bit ADC（0..2047）。
     *  有真机摇杆时用 raw（原生自己按 radio.yml 校准，和真机固件行为一致）；
     *  没有硬件时用 radio.yml 的 mid/span 反算，保证两条路径口径一致。 */
    /**
     * 2026-11-01：核心改成 RC2V15 板（7 轴）后，这里输出 7 个数，顺序固定
     * RH, RV, LV, LH, P1, P2, P3 —— 与 docs/07 的约定和 HAL 的 analog[] 一一对应。
     * P3 目前固定中位（RC2 没有第三路旋钮）。
     */
    /** 交叉校验/自检期间冻结真实摇杆注入，避免 100Hz 控制环把测试输入覆盖掉。 */
    public volatile boolean suppressLiveInput = false;
    // When true the native axes are synthesised from input.* instead of the live
    // DJI stream.  Used by the --es action axes hook so a test can drive the
    // real EdgeTX mixer without touching the hardware.
    public volatile boolean forceSynthAxes = false;

    public void fillNativeAxes7(int[] axes7) {
        boolean live = !forceSynthAxes && dumlOn && duml.connected && duml.stickFrames > 0;
        if (live) {
            duml.copyRaw(rawSnap);
            // DJI 的 06/AE 帧是「中点 1024、满行程 364..1684」；而 EdgeTX 核心按 radio.yml
            // 自己的校准窗口算（RH 1044 / RV 929 / LV 1075 / LH 1055，spanNeg/spanPos 还不对称）。
            // 直接把 DJI 原始值喂进去会让四轴偏离中立 50~100us（实测喂 1024 时通道停在
            // 967/1121/923/953 而不是 992）。所以必须先换算到 EdgeTX 的 ADC 口径。
            // EdgeTX ADC order (targets/horus/hal.h ADC_DIRECTION): LH,LV,RV,RH,P1,P2,P3.
            // The DJI MCU frame is in a different order (rawSnap[0]=RH, [1]=RV,
            // [2]=LV, [3]=LH), so both the permutation and the normalisation are
            // needed here.
            // 2026-11-02：插帧必须喂进原生核心，否则 EdgeTX 内部拿到的是 10Hz 台阶，
            // 表现为「动几下卡一下、插帧像没生效」。这里优先用 StickSmooth 的插值输出，
            // 只有插帧关闭（capMs<=0）或还没采满三帧时才回落到原始 10Hz 快照。
            boolean smoothed = false;
            long nowMs = android.os.SystemClock.uptimeMillis();
            if (!suppressLiveInput && smooth.value(nowMs, sv)) {
                // axes7 顺序 LH,LV,RV,RH,P1,P2,P3；sv 顺序 lx(RUD),ly(THR),rx(AIL),ry(ELE),k1,k2
                axes7[0] = svToAdc(sv[0]); // LH 偏航
                axes7[1] = svToAdc(sv[1]); // LV 油门
                axes7[2] = svToAdc(sv[3]); // RV 俯仰
                axes7[3] = svToAdc(sv[2]); // RH 副翼
                axes7[4] = djiToEtxAdc(rawSnap[4]); // P1
                axes7[5] = djiToEtxAdc(rawSnap[5]); // P2
                smoothed = true;
            }
            if (!smoothed) {
                axes7[0] = djiToEtxAdc(rawSnap[3]); // LH
                axes7[1] = djiToEtxAdc(rawSnap[2]); // LV
                axes7[2] = djiToEtxAdc(rawSnap[1]); // RV
                axes7[3] = djiToEtxAdc(rawSnap[0]); // RH
                axes7[4] = djiToEtxAdc(rawSnap[4]); // P1
                axes7[5] = djiToEtxAdc(rawSnap[5]); // P2
            }
            axes7[6] = 1024;                    // P3（无硬件）
            applyRcProButtons(axes7);
        } else {
            axes7[0] = synthAdc(input.lx);   // LH  (yaw)
            axes7[1] = synthAdc(input.ly);   // LV  (throttle)
            axes7[2] = synthAdc(input.ry);   // RV  (elevator)
            axes7[3] = synthAdc(input.rx);   // RH  (aileron)
            axes7[4] = synthAdc(input.knob1);
            axes7[5] = synthAdc(input.knob2);
            axes7[6] = 1024;
        }
    }

    public void fillNativeAxes(int[] axes4, int[] knobs2) {
        boolean live = dumlOn && duml.connected && duml.stickFrames > 0;
        if (live) {
            // 实测：核心按 MCU 物理序 RH,RV,LV,LH 吃输入（不是 LH,LV,RV,RH）
            duml.copyRaw(rawSnap);
            axes4[0] = djiToEtxAdc(rawSnap[3]); // LH
            axes4[1] = djiToEtxAdc(rawSnap[2]); // LV
            axes4[2] = djiToEtxAdc(rawSnap[1]); // RV
            axes4[3] = djiToEtxAdc(rawSnap[0]); // RH
            knobs2[0] = djiToEtxAdc(rawSnap[4]);
            knobs2[1] = djiToEtxAdc(rawSnap[5]);
        } else {
            synthNativeAxes(axes4, knobs2);
        }
    }

    /** 7 轴版本：输出 RH, RV, LV, LH, P1, P2, P3 的 11bit ADC（无硬件时按 radio.yml 反算）。 */
    public void synthNativeAxes7(int[] axes7) {
        axes7[0] = synthAdc(input.lx);   // LH
        axes7[1] = synthAdc(input.ly);   // LV
        axes7[2] = synthAdc(input.ry);   // RV
        axes7[3] = synthAdc(input.rx);   // RH
        axes7[4] = synthAdc(input.knob1);
        axes7[5] = synthAdc(input.knob2);
        axes7[6] = 1024;                  // P3（RC2 无第三路旋钮）
    }

    /** 没有真机摇杆时，用输入量 + radio.yml 的 mid/span 反算出 11bit ADC。 */
    public void synthNativeAxes(int[] axes4, int[] knobs2) {
        axes4[0] = synthAdc(input.lx);   // LH
        axes4[1] = synthAdc(input.ly);   // LV
        axes4[2] = synthAdc(input.ry);   // RV
        axes4[3] = synthAdc(input.rx);   // RH
        knobs2[0] = synthAdc(input.knob1);
        knobs2[1] = synthAdc(input.knob2);
    }

    /** DJI 摇杆原始 11bit（中点 1024、满行程 364..1684） -> EdgeTX 校准口径的 ADC。
     *  公式：v=(raw-1024)/660；adc=mid + v*(v>0?spanPos:spanNeg)，mid/span 取 radio.yml。
     *  axis 是 RadioYml.AXES 的下标（0=LH 1=LV 2=RV 3=RH 4=P1 5=P2 6=P3）。 */
    // DJI 06/AE: centre 1024, full travel 364..1684 which is +/-660.
    // EdgeTX's own ADC path expects NORMALISED values on a 0..2048 scale with
    // 1024 as the centre (the firmware skips apply_calibration() under SIMU, so
    // no calibration window is applied inside the core).  That means the value
    // we hand to etx_set_inputs must be symmetric and centred on 1024 - the
    // asymmetric radio.yml window (RH 1044 / RV 929 / ...) is NOT used here.
    /** 中位死区（DJI 原始 ADC 单位，约 2% 行程）：静止时把零漂直接吸到中点，
     *  否则通道会在 965~992us 之间来回飘（用户说的"漂移"）。 */
    private static final int DJI_CENTER_DEAD = 4;

    /**
     * StickSmooth 输出（-1..1）直接换算成核心 ADC（0..2048，中点 1024）。
     * 注意：这里**不能**再走 djiToEtxAdc()，因为那条路径带 DJI_CENTER_DEAD 死区，
     * 插值曲线穿过中点时会被硬吸附到 1024，表现就是用户说的「动几下就卡一下」。
     */
    private int svToAdc(float v) {
        return synthAdc(v);
    }

    private int djiToEtxAdc(int djiRaw) {
        if (Math.abs(djiRaw - 1024) <= DJI_CENTER_DEAD) return 1024;
        int v = Math.round((djiRaw - 1024) * 1024f / 660f);
        if (v > 1024) v = 1024;
        if (v < -1024) v = -1024;
        return 1024 + v;
    }

    // Same normalised domain as djiToEtxAdc(): unit [-1..1] -> 0..2048, centre 1024.
    private int synthAdc(float u) {
        int v = Math.round(u * 1024f);
        if (v > 1024) v = 1024;
        if (v < -1024) v = -1024;
        return 1024 + v;
    }

    private static int clampAdc(int v) {
        return v < 0 ? 0 : (v > 2047 ? 2047 : v);
    }

    public void setHidOn(boolean on) {
        // 关键顺序：①只在 /dev/hidg0 还不存在时才建 gadget（重复建 = 在 fd 打开时摘 UDC，
        // 实测会让内核崩、整机重启）；②关闭时先 close() 再 restore()。
        if (on) {
            hidOn = true;
            Store.saveHidOn(ctx, true);
            startHidLoop();
            if (!com.rc2.etx.usb.UsbGadget.hasHidNode()) {
                com.rc2.etx.usb.UsbGadget.setup(ctx);
            }
            hid.open();
        } else {
            hidOn = false;
            Store.saveHidOn(ctx, false);
            hid.close();
            try { Thread.sleep(150); } catch (InterruptedException ignore) { }
            com.rc2.etx.usb.UsbGadget.restore(ctx);
        }
        save();
    }

    public boolean isHidOn() { return hidOn; }

    /** 一帧 JSON：6 路原始值（0..2048）+ 8 路输出 us，给 PC 侧虚拟手柄用。 */
    /** 插帧后的六轴（DJI/MCU 序 RH,RV,LV,LH,P1,P2，0..2048），电脑端也要吃到插帧值。 */
    public int[] smoothedRaw6() {
        int[] o = new int[6];
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (!suppressLiveInput && smooth.value(nowMs, sv)) {
            // sv: 0=lx(RUD) 1=ly(THR) 2=rx(AIL) 3=ry(ELE) 4=k1 5=k2；MCU 序要 RH,RV,LV,LH
            o[0] = 1024 + Math.round(sv[2] * 1024f);   // RH 副翼
            o[1] = 1024 + Math.round(sv[3] * 1024f);   // RV 俯仰
            o[2] = 1024 + Math.round(sv[1] * 1024f);   // LV 油门
            o[3] = 1024 + Math.round(sv[0] * 1024f);   // LH 偏航
            o[4] = 1024 + Math.round(sv[4] * 1024f);
            o[5] = 1024 + Math.round(sv[5] * 1024f);
            for (int i = 0; i < 6; i++) {
                if (o[i] < 0) o[i] = 0;
                else if (o[i] > 2047) o[i] = 2047;
            }
            return o;
        }
        int[] r = duml.raw;
        for (int i = 0; i < 6; i++) o[i] = i < r.length ? r[i] : 1024;
        return o;
    }

    public String padFrameJson() {
        int[] r = smoothedRaw6();
        StringBuilder b = new StringBuilder(96);
        b.append("{\"t\":").append(System.currentTimeMillis()).append(",\"ax\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) b.append(',');
            b.append(i < r.length ? r[i] : 1024);
        }
        b.append("],\"us\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) b.append(',');
            b.append(hidUsValid && i < CH_COUNT ? hidUs[i] : 1500);
        }
        b.append("]}");
        return b.toString();
    }

    public void setPadStreamOn(boolean on) {
        try {
            java.io.File f = com.rc2.etx.usb.PadStream.flagFile(ctx);
            if (on) {
                if (!f.exists()) f.createNewFile();
                padStream.start(ctx);
            } else {
                padStream.stop();
                if (f.exists()) f.delete();
            }
        } catch (Throwable ignore) { }
        save();
    }

    public boolean isPadStreamOn() { return padStream.isWanted(); }

    /** 固定 4ms 节拍把最新值发给 /dev/hidg0；节点没开就定期重试，避免"重启后第一次开失败就再也不发"。 */
    private void startHidLoop() {
        if (hidThread != null && hidThread.isAlive()) return;
        hidThread = new Thread(new Runnable() {
            public void run() {
                long lastTry = 0;
                while (true) {
                    try {
                        if (hidOn) {
                            if (!hid.isOpen()) {
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - lastTry > 800) {
                                    lastTry = now;
                                    if (!com.rc2.etx.usb.UsbGadget.hasHidNode()) {
                                        // 重启后 gadget 没了：自动重建（需 root，已授权的话静默完成）
                                        com.rc2.etx.usb.UsbGadget.setup(ctx);
                                    }
                                    hid.open();
                                }
                            }
                            if (hid.isOpen()) {
                                hid.send(hidX, hidY, hidZ, hidRz, hidBtn, 0);
                            }
                        }
                        Thread.sleep(hidOn ? 4 : 200);
                    } catch (Throwable t) {
                        try { Thread.sleep(50); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "etx-hid");
        hidThread.setDaemon(true);
        hidThread.start();
    }


    /**
     * RC Pro(RM510) 的摇杆锁在 DJI 私有服务里，TCP 40007 只给一帧快照。
     * 这里把 binder 源接上，并把 6 个通道按 MCU 序搬进 duml.raw，
     * 下游（混控/校准/HID/诊断）一行都不用改。
     *
     * RC Pro 通道序 0..3 与 RC2 的 MCU 序一致：RH, RV, LV, LH；4/5 = 左右波轮。
     */
    private void startRcProIfAny() {

        if (rcpro != null) return;
        int model = com.rc2.etx.radio.Store.loadModel(ctx);
        if (model == 2 && !padStream.isRunning()) {
            Log.i(TAG, "型号=RC Pro → 自动开启虚拟手柄数据流");
            padStream.start(ctx);
        }
        if (model == 1) {                    // 用户明确选了 RC2：不走 binder
            android.util.Log.i(TAG, "型号设置=RC2，跳过 protocol binder");
            return;
        }
        boolean avail = com.rc2.etx.input.RcProStick.available(ctx);
        if (!avail && model == 2) {          // 明确选了 RC Pro 但服务没就绪：仍然起，靠重试
            android.util.Log.w(TAG, "型号设置=RC Pro，但 protocol 服务暂不可见，稍后重试");
        }
        // 自动/RC2 也启用 binder：RC2 上同样有 com.dji.protocol.IProtocolManager，
        // 断连空档要靠它补帧；只有「服务真的不可见且用户没强指 RC Pro」时才放弃。
        if (!avail && model == 1) return;
        // 防止重复启动：构造函数和 setDumlOn() 都会调到这里，
        // 以前会起两条桥线程 + 两个 RcProStick，等于双份 transact(4) 抢 binder。
        if (rcBridge != null && rcBridge.isAlive()) return;
        if (rcpro != null && rcpro.connected) return;
        rcpro = new com.rc2.etx.input.RcProStick();
        rcpro.start(ctx);
        Log.i(TAG, "检测到 DJI protocol 服务 → 摇杆改走 binder (RC Pro 模式)");
        rcBridge = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        com.rc2.etx.input.RcProStick r = rcpro;
                        if (r != null && dumlOn) {
                            if (r.connected) {
                                // 混合源：TCP(9Hz) 新鲜时让它作主；TCP 超过 220ms 没帧
                                // （dji_link 每 3~6 秒 RST 一次的空档）就用 binder(≈6Hz) 顶上，
                                // 这样断连期间摇杆不再"卡住不动"。
                                long now2 = System.currentTimeMillis();
                                boolean tcpFresh = duml.connected
                                        && (now2 - duml.lastStickMs) < 220;
                                if (!tcpFresh) {
                                    int[] v = r.raw;
                                    int[] d = duml.raw;
                                    if (v != null && d != null) {
                                        int n = Math.min(6, Math.min(v.length, d.length));
                                        for (int i = 0; i < n; i++) d[i] = v[i];
                                    }
                                    // 注意：不能写 duml.connected / duml.lastStickMs，
                                    // 否则下一轮会被误判成 "TCP 新鲜"，binder 立刻停手，
                                    // 而且状态栏会显示已连接（实际是断的）。
                                    lastBinderMs = now2;
                                    if (duml.stickFrames == 0) duml.stickFrames = r.frames;
                                    gapFills++;
                                    if (gapFills <= 3 || gapFills % 500 == 0) {
                                        Log.i(TAG, "binder 补帧 #" + gapFills);
                                    }
                                }
                            }
                        }
                        Thread.sleep(10);
                    } catch (Throwable t) {
                        try { Thread.sleep(200); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "etx-rcpro-bridge");
        rcBridge.setDaemon(true);
        rcBridge.start();
    }

    public void setDumlOn(boolean on) {
        dumlOn = on;
        Store.saveDumlOn(ctx, on);
        lastStickFrameAt = SystemClock.elapsedRealtime();
        if (on) {
            startRcProIfAny();
            duml.start();
        } else {
            duml.stop();
        }
    }

    /** 立刻重连（UI 上的"重连"按钮 / 看门狗调用）。 */
    public void reconnectDuml() {
        wdRestarts++;
        lastStickFrameAt = SystemClock.elapsedRealtime();
        duml.restart();
    }

    public boolean isDumlOn() {
        return dumlOn;
    }

    public void setMixerOn(boolean on) {
        mixerOn = on;
    }

    public StickSmooth stickSmooth() {
        return smooth;
    }

    public boolean isMixerOn() {
        return mixerOn;
    }

    /** 模型参数改动后调用，让混控器用上新参数。 */
    public void reloadMixer() {
        mixer.setConfig(model.ch);
    }

    public void save() {
        Store.save(ctx, model);
        Store.saveCalib(ctx, calib.toJson());
    }

    public synchronized void start() {
        if (running) return;
        thread = new HandlerThread("etx-radio", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        lastTickMs = SystemClock.elapsedRealtime();
        hzFrom = lastTickMs;
        hzCount = 0;
        handler.postDelayed(loop, PERIOD_MS);
        Log.i(TAG, "control loop started @" + (1000 / PERIOD_MS) + "Hz");
    }

    public synchronized void stop() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        Log.i(TAG, "control loop stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "tick failed", t);
            }
            if (running && handler != null) {
                handler.postDelayed(this, PERIOD_MS);
            }
        }
    };

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (dumlOn && duml.connected) {
            // 6 个 ADC 轴：前 4 根当摇杆，后 2 根暂当旋钮；映射由校准页拟合后修正
            long nowMs = android.os.SystemClock.uptimeMillis();
            long sf = duml.stickFrames;
            if (sf != lastStickSeq) {
                lastStickSeq = sf;
                lastStickFrameAt = now;
                wdInBurst = 0;
                // 真机标定（用户 2026-10-31 实测）：axis0=AIL(右横) axis1=ELE(右纵)
                // axis2=THR(左纵) axis3=RUD(左横)，即 AETR 顺序，与 EdgeTX 默认一致。
                duml.units(du);
                // MCU 序 raw[0..3]=RH,RV,LV,LH → AETR: lx=RUD(LH) ly=THR(LV) rx=AIL(RH) ry=ELE(RV)
                smooth.sample(nowMs, du[3], du[2], du[0], du[1], du[4], du[5]);
            }
            if (!suppressLiveInput && smooth.value(nowMs, sv)) {
                // 静止检测：六轴都几乎不动持续 800ms → 把当前姿态当零点（一次性锁定）
                boolean still = true;
                for (int i = 0; i < 6; i++) {
                    if (sv[i] > 0.06f || sv[i] < -0.06f) { still = false; break; }
                }
                if (still) {
                    if (stillSince == 0L) stillSince = nowMs;
                    else if (nowMs - stillSince > 800L) { smooth.snapZero(); stillSince = nowMs + 5000L; }
                } else {
                    stillSince = 0L;
                }
                for (int i = 0; i < 4; i++) input.touch(i, sv[i]);
                input.touch(8, sv[4]);
                input.touch(9, sv[5]);
            }
            if (now - lastStickFrameAt > STICK_WD_MS) {
                // 只提示"该重连了"，真正的退避与重连由 DumlStick 单线程自己负责
                // （原来这里另有一套 4s/8s/16s/30s 退避，和 socket 层的 1s 退避互相打架，
                //  再叠加 restart() 另起线程 → 旧线程误关新 socket → 断连 + 抽搐）
                if (now - lastWdAt > 2000L) {
                    lastWdAt = now;
                    wdInBurst++;
                    Log.w(TAG, "no stick frames for " + (now - lastStickFrameAt) + "ms, reconnect #"
                            + wdInBurst);
                    reconnectDuml();
                }
            }
        }
        float[] u = mixer.compute(input, mixerOn);
        for (int i = 0; i < CH_COUNT; i++) {
            us[i] = CrsfEncoder.usFromUnit(u[i]);
            pct[i] = Math.round(u[i] * 100f);
        }
        if (hidOn) {
            // 只更新"最新值"，真正的发包交给 hidLoop 固定 4ms 一帧（约 250Hz）。
            // 放在 tick 里发会跟着控制环（~10ms + 抖动）走，模拟器会觉得"采样少、手感卡"。
            int x, y, z, rz;
            if (hidUsValid) {
                x = (int) Math.round((hidUs[0] - 172) * 1023.0 / 1639.0);
                y = (int) Math.round((hidUs[1] - 172) * 1023.0 / 1639.0);
                z = (int) Math.round((hidUs[2] - 172) * 1023.0 / 1639.0);
                rz = (int) Math.round((hidUs[3] - 172) * 1023.0 / 1639.0);
            } else {
                x = (int) Math.round((u[0] + 1f) * 511.5f);
                y = (int) Math.round((u[1] + 1f) * 511.5f);
                z = (int) Math.round((u[2] + 1f) * 511.5f);
                rz = (int) Math.round((u[3] + 1f) * 511.5f);
            }
            int btn = 0;
            for (int i = 4; i < 8; i++) if (u[i] > 0.35f) btn |= (1 << (i - 4));
            hidX = x; hidY = y; hidZ = z; hidRz = rz; hidBtn = btn;
        }
        byte[] f = encoder.encode(us);
        System.arraycopy(f, 0, lastFrame, 0, lastFrame.length);
        OutputSink s = sink;
        if (s != null) {
            s.send(f);
        }
        ticks++;
        if (++hzCount >= 50) {
            long dt = now - hzFrom;
            if (dt > 0) hz = (int) (hzCount * 1000L / dt);
            hzCount = 0;
            hzFrom = now;
        }
        if (++hexTick >= 10) {
            hexTick = 0;
            crsfHex = CrsfEncoder.hex(lastFrame);
        }
        if (lastTickMs != 0) {
            int gap = (int) (now - lastTickMs);
            if (gap > maxGapMs) maxGapMs = gap;
        }
        lastTickMs = now;
    }

    public int[] pctSnapshot() {
        return pct.clone();
    }

    public int[] usSnapshot() {
        return us.clone();
    }

    public byte[] frameSnapshot() {
        return lastFrame.clone();
    }

    /** 手动把一次输入喂给控制环（供自检/调试使用，非 UI 路径）。 */
    public void poke(int idx, float v) {
        input.touch(idx, v);
    }
    // ---------------- RC Pro(RM510) 按键 / 拨轮 ----------------
    /** 上一次的五维/返回按键状态，用于边沿检测（避免按住不停发键）。 */
    private int lastNav = -1;
    private int keysSent;
    private int lastBtn2 = -1;

    /**
     * RC Pro 的按键在 DUML 06/AE(174) 帧里（17 字节载荷）：
     *   p0 0x80 快门半按 / 0x40 快门全按 / 0x20 返航 / 0x10 急停
     *   p1 0x80 上 / 0x40 下 / 0x20 左 / 0x10 右 / 0x08 中(确认) / 0x01 录像
     *   p2 bit0-1 挡位(S/N/C) / 0x04 C1 / 0x08 C2 / 0x10 C3 / 0x20 返回
     * 这里把五维与返回映射成 EdgeTX 的导航键，C1..C3 映射成 P1..P3 三档开关。
     */
    private void applyRcProButtons(int[] axes7) {
        if (duml == null || !duml.isSnapMode()) return;
        if (duml.btnAgeMs() > 1500) return;          // 帧停了就别再动开关
        int p1 = duml.btnRaw(1);
        int p2 = duml.btnRaw(2);

        int nav = 0;
        if ((p1 & 0x80) != 0) nav = 0;               // ↑
        else if ((p1 & 0x40) != 0) nav = 1;          // ↓
        else if ((p1 & 0x20) != 0) nav = 2;          // ←
        else if ((p1 & 0x10) != 0) nav = 3;          // →
        else if ((p1 & 0x08) != 0) nav = 4;          // 中 = ENT
        else if ((p2 & 0x20) != 0) nav = 5;          // 返回 = RTN
        if (nav != lastNav) {
            if (nav >= 0) {
                try {
                    com.rc2.etx.jni.EtxNative.press(nav);
                    keysSent++;
                } catch (Throwable ignore) {
                }
            }
            lastNav = nav;
        }

        int b2 = p2 & 0x1C;
        if (b2 != lastBtn2) {
            axes7[4] = ((p2 & 0x04) != 0) ? 2047 : 0;    // C1 → P1
            axes7[5] = ((p2 & 0x08) != 0) ? 2047 : 0;    // C2 → P2
            axes7[6] = ((p2 & 0x10) != 0) ? 2047 : 0;    // C3 → P3
            lastBtn2 = b2;
        }
        if (nav < 0) lastNav = -1;
    }


}
