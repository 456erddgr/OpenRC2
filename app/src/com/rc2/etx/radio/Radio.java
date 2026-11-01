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
    /** 原生核心算出来的真实通道 us（172..1811），由 NativePanel 每帧刷新；HID 用它当唯一真相源。 */
    public final int[] hidUs = new int[CH_COUNT];
    public volatile boolean hidUsValid = false;
    public volatile boolean hidOn = false;
    /** 是否启用手内摇杆输入。 */
    public volatile boolean dumlOn;
    /** 最近一次从系统电池服务读到的电压(mV)，0=未知。 */
    public volatile int vbatMv = 0;
    /** 是否按 7 轴（RH,RV,LV,LH,P1,P2,P3）喂原生核心。 */
    public volatile boolean nativeAxes7 = true;
    private final StickSmooth smooth = new StickSmooth();
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
        mixer.setConfig(model.ch);
        input.reset();
        dumlOn = Store.loadDumlOn(c);
        // HID 手柄：开机时若 gadget 节点还在（用户选过"游戏手柄"），自动续开，
        // 否则 PC 能认出设备却收不到任何报文 —— 就是"模拟器识别但摇杆不动"。
        if (com.rc2.etx.usb.UsbGadget.hasHidNode()) {
            hidOn = true;
            hid.open();
        }
        lastStickFrameAt = SystemClock.elapsedRealtime();
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
            axes7[0] = djiToEtxAdc(rawSnap[3]); // LH
            axes7[1] = djiToEtxAdc(rawSnap[2]); // LV
            axes7[2] = djiToEtxAdc(rawSnap[1]); // RV
            axes7[3] = djiToEtxAdc(rawSnap[0]); // RH
            axes7[4] = djiToEtxAdc(rawSnap[4]); // P1
            axes7[5] = djiToEtxAdc(rawSnap[5]); // P2
            axes7[6] = 1024;                    // P3（无硬件）
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
    private static final int DJI_CENTER_DEAD = 22;

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

    public void setDumlOn(boolean on) {
        dumlOn = on;
        Store.saveDumlOn(ctx, on);
        lastStickFrameAt = SystemClock.elapsedRealtime();
        if (on) {
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
            if (!hid.isOpen()) hid.open();
            if (hid.isOpen()) {
                // AETR：CH1 横滚→X，CH2 俯仰→Y，CH3 油门→Z，CH4 偏航→Rz（0..1023）
                // 优先用原生核心的真实通道值（EdgeTX 自己算的），没有再退回 Java 混控
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
                hid.send(x, y, z, rz, btn, 0);
            }
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
}
