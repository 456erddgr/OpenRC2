// [RC2-EdgeTX-Native] 外接 USB / 蓝牙手柄 -> EdgeTX 七轴输入（C 路，只读）
//
// 用法（在 NativePanel 的帧循环里）：
//     GamepadReader g = new GamepadReader();
//     g.pollAndPush();            // 每帧一次；无外设时喂中立值，不留残留
//     Log.i("ETX", g.report());   // 需要设备清单时
//
// 设计要求（与原版语义一致，勿擅改）：
//  * 只读 InputDevice，不改系统输入、不注册按键拦截、不申请额外权限。
//  * 轴序固定为 EdgeTX MCU 序：RH, RV, LV, LH, P1, P2, P3（与 etx_set_inputs 一致）。
//  * 量纲是原始 ADC：用固件自己的校准窗口（EtxNative.axisCalib）算中点与行程，
//    不要硬编码 1024 / 364 / 1684 —— 各轴 calib.mid 并不相同（RH 1044, RV 929, ...）。
//  * 不做反相：方向差异请在模型的 mix 里解决。
package com.rc2.etx.jni;

import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.Arrays;

public final class GamepadReader {

    public static final int AXIS_COUNT = 7;   // RH, RV, LV, LH, P1, P2, P3
    private static final float DEADBAND = 0.02f;
    private static final int AXIS_SLOTS = 64;

    private final int[] axes = new int[AXIS_COUNT];
    private final int[][] calib = new int[AXIS_COUNT][3];   // min, mid, max
    private final float[] lastAxis = new float[AXIS_SLOTS]; // 由 noteAxis() 每帧填入
    private InputDevice device;

    public GamepadReader() {
        Arrays.fill(axes, 1024);
        for (int i = 0; i < AXIS_COUNT; i++) {
            calib[i][0] = 364;      // 兜底：满行程端点（40007 实测值）
            calib[i][1] = 1024;
            calib[i][2] = 1684;
            int[] out = new int[3];
            if (EtxNative.axisCalib(i, out)) {          // 固件校准表优先
                calib[i][0] = out[0];
                calib[i][1] = out[1];
                calib[i][2] = out[2];
            }
        }
    }

    /** 在 View.onGenericMotionEvent 里调用一次，把事件里的轴值缓存下来。 */
    public void noteAxis(MotionEvent e) {
        Arrays.fill(lastAxis, 0f);
        put(MotionEvent.AXIS_X, e);
        put(MotionEvent.AXIS_Y, e);
        put(MotionEvent.AXIS_Z, e);
        put(MotionEvent.AXIS_RZ, e);
        put(MotionEvent.AXIS_RX, e);
        put(MotionEvent.AXIS_RY, e);
        put(MotionEvent.AXIS_HAT_X, e);
    }

    private void put(int androidAxis, MotionEvent e) {
        if ((androidAxis & 0xFF) < AXIS_SLOTS) lastAxis[androidAxis & 0xFF] = e.getAxisValue(androidAxis);
    }

    /** 找第一个手柄/摇杆设备。 */
    private InputDevice findDevice() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            int src = d.getSources();
            int cls = src & InputDevice.SOURCE_CLASS_MASK;
            // SOURCE_GAMEPAD (0x401) itself contains SOURCE_CLASS_BUTTON (0x1), so a
            // plain `src & SOURCE_GAMEPAD` matches *any* device with a button class -
            // on the RC2 that is the virtual input device (sources=0x301), which made
            // hasDevice() true and silently routed every frame into this (empty)
            // gamepad path instead of the DJI stick stream.
            // Require the real gamepad bit (0x400) or the joystick class, never a
            // pointer device, and insist on actual analog axes.
            if (cls == InputDevice.SOURCE_CLASS_POINTER) continue;
            if ((src & 0x0400) == 0 && cls != InputDevice.SOURCE_CLASS_JOYSTICK) continue;
            if (d.getMotionRange(MotionEvent.AXIS_X) == null) continue;
            if (d.getMotionRange(MotionEvent.AXIS_Y) == null) continue;
            return d;
        }
        return null;
    }

    /** 把某个轴的归一化值喂进 axes[idx]。range 可能是 ±1，也可能是别的，先归一化。 */
    private void feed(int idx, InputDevice d, int androidAxis) {
        InputDevice.MotionRange r = d.getMotionRange(androidAxis, InputDevice.SOURCE_JOYSTICK);
        if (r == null) r = d.getMotionRange(androidAxis);
        if (r == null) return;                       // 该手柄没有这个轴 -> 保持中立
        float raw = lastAxis[androidAxis & 0xFF];
        float min = r.getMin(), max = r.getMax();
        float v = (raw - min) / Math.max(1e-6f, (max - min));   // 0..1
        v = v * 2f - 1f;                                        // -1..1
        if (Math.abs(v) < DEADBAND) v = 0f;
        int mid = calib[idx][1];
        int span = v > 0f ? (calib[idx][2] - mid) : (mid - calib[idx][0]);
        axes[idx] = Math.round(mid + v * span);
    }

    /** 每帧调用：有外设就按轴喂，没有就保持中立（不残留）。 */
    public void pollAndPush() {
        if (device == null) device = findDevice();
        if (device == null) {
            Arrays.fill(axes, 1024);
        } else {
            feed(0, device, MotionEvent.AXIS_X);      // RH
            feed(1, device, MotionEvent.AXIS_Y);      // RV
            feed(2, device, MotionEvent.AXIS_RZ);     // LV
            feed(3, device, MotionEvent.AXIS_Z);      // LH
            feed(4, device, MotionEvent.AXIS_RX);     // P1
            feed(5, device, MotionEvent.AXIS_RY);     // P2
            feed(6, device, MotionEvent.AXIS_HAT_X);  // P3（可按需换）
        }
        EtxNative.setInputs(axes, AXIS_COUNT, new int[0], 0, new int[0], 0);
    }

    public boolean hasDevice() {
        if (device == null) device = findDevice();
        return device != null;
    }

    /** 设备清单报告（对应 `am start ... --es action devices`）。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            int src = d.getSources();
            if ((src & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) == 0) continue;
            n++;
            sb.append("device ").append(n)
              .append(" name=").append(d.getName())
              .append(" vendor=").append(d.getVendorId())
              .append(" product=").append(d.getProductId())
              .append(" source=0x").append(Integer.toHexString(src)).append('\n');
            for (InputDevice.MotionRange r : d.getMotionRanges()) {
                sb.append("   axis=0x").append(Integer.toHexString(r.getAxis()))
                  .append(" min=").append(r.getMin())
                  .append(" max=").append(r.getMax())
                  .append(" flat=").append(r.getFlat())
                  .append(" fuzz=").append(r.getFuzz()).append('\n');
            }
        }
        return n == 0 ? "no joystick/gamepad" : sb.toString();
    }
}
