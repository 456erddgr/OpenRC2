package com.rc2.etx.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;

/**
 * 遥控器物理按键 -> EdgeTX 界面按键。
 *
 * <h3>为什么需要它</h3>
 * 摇杆/波轮走 DJI 私有 binder（见 {@link RcProService}），但<b>物理按键不走 binder</b>：
 * 它们在内核里被当作普通输入设备事件，只有无障碍服务（AccessibilityService）的
 * onKeyEvent 才拿得到。这正是用户反复抱怨「拨杆没有适配」的根因——按键通路压根没接。
 *
 * <h3>依据</h3>
 * 参照包 local.plus2.xbox（RC Plus 2 的 Xbox 手柄适配器）实测可行，以下照抄其口径：
 * <ul>
 *   <li>res/xml/accessibility.xml 中 canRequestFilterKeyEvents="true"
 *       + accessibilityFlags="flagRequestFilterKeyEvents"（缺一收不到 KeyEvent）；</li>
 *   <li>onKeyEvent 里按 InputDevice.getName() 过滤：
 *       contains("DJI") || equals("gpio-keys") || contains("qpnp")；</li>
 *   <li>键码用标准 gamepad KeyCode（KEYCODE_BUTTON_L1/L2/R1/R2/THUMBL/THUMBR、
 *       KEYCODE_DPAD_*、KEYCODE_CAMERA、KEYCODE_MEDIA_RECORD）。</li>
 * </ul>
 *
 * <h3>关键约束（不要改错）</h3>
 * <ol>
 *   <li><b>本服务必须跑在主进程</b>（不要给它加 android:process）。原因：按键最终要进
 *       EtxNative.press()，而 libetx 的状态是<b>进程内</b>的；放到 com.dpad.fuli 只会
 *       加载一份全新的空核心，按了也没人看见。参照包把整个 App 放在 com.dpad.fuli
 *       是因为它不跑 EdgeTX 核心，我们不能照抄这一点。</li>
 *   <li>原生按键队列 s_keyq（RC2-EdgeTX-Native/src/etx_api.cpp）<b>没有加锁</b>，
 *       生产者/消费者必须在同一个线程。EtxNative.tick() 在主线程（RadioView 用
 *       Looper.getMainLooper() 的 Handler 驱动），而主进程的 AccessibilityService
 *       回调也投递在主线程 —— 二者天然串行，所以这里是安全的。<b>如果以后把 tick()
 *       挪到别的线程，必须同时给 s_keyq 加锁，否则按键会丢/错位。</b></li>
 *   <li>不要在这里改 targetSdk（manifest 注释说明必须 &lt;=29，否则 DJI 的
 *       ReviewPermissionsActivity 会劫持启动）。</li>
 * </ol>
 *
 * <h3>长按</h3>
 * EdgeTX 的长按判据（vendor/edgetx-src-2.12.2/radio/src/keys.cpp）：
 * KEY_LONG_DELAY = 32（单位 10ms）+ FILTERBITS=4 的去抖采样，且本工程<b>没有定义 SIMU</b>，
 * 所以是真机口径的 4 位去抖。合计约 360ms 才会出 EVT_KEY_LONG，因此<b>长按要 &gt;=400ms</b>。
 * 本服务只做 down/up 透传，不自己合成长按——按住够久 EdgeTX 自己会判。
 */
public class KeysService extends AccessibilityService {

    private static final String TAG = "ETX-Keys";

    /** 配置存这里（和 Store 用同一个 "etx" 文件，避免多一份 prefs）。 */
    private static final String PREFS = "etx";
    /** 总开关，默认<b>开</b>：这条链路本来就是为了修「拨杆没适配」。 */
    public static final String KEY_ENABLED = "keysOn";
    /** 额外接受任意输入设备（RC Plus 2 设备名未知时的兜底开关）。 */
    public static final String KEY_ANY_DEVICE = "keysAnyDevice";
    /** 键码映射覆盖，格式 "19:0,20:1"（keyCode:etxIndex），越界表示忽略该键。 */
    public static final String KEY_MAP = "keysMap";

    // ---- EdgeTX 按键索引，必须与 RC2-EdgeTX-Native/src/ui/etx_ui_glue.cpp 的 kKeyMap 一致 ----
    // kKeyMap[10] = { KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER,
    //                 KEY_EXIT, KEY_PAGEUP, KEY_PAGEDN, KEY_MODEL, KEY_TELE }
    public static final int K_UP = 0;
    public static final int K_DOWN = 1;
    public static final int K_LEFT = 2;
    public static final int K_RIGHT = 3;
    public static final int K_ENTER = 4;
    public static final int K_EXIT = 5;
    public static final int K_PAGEUP = 6;
    public static final int K_PAGEDN = 7;
    public static final int K_MODEL = 8;
    public static final int K_TELE = 9;
    public static final int K_NONE = -1;

    /**
     * 卡键兜底：按下超过这个时间还没收到 UP 就强制释放（ms）。
     * 防止丢 UP（切后台、服务重建）导致界面一直滚动。
     * 注意必须大于 EdgeTX 的重复触发阈值，否则会把正常的"按住连续滚动"打断。
     */
    private static final long STUCK_RELEASE_MS = 15000;

    // ---- 诊断：给 DiagPanel / logcat 看的 ----
    public static volatile boolean running;
    public static volatile String lastDevice = "-";
    public static volatile int lastKeyCode = -1;
    public static volatile String lastAction = "-";
    public static volatile long seen;
    public static volatile long delivered;
    public static volatile String error = "-";

    /** keyCode -> EdgeTX 索引。默认表见 {@link #defaultMap()}。 */
    private HashMap<Integer, Integer> map = defaultMap();

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 当前按下的 etx 索引 -> 按下时刻(ms)。 */
    private final HashMap<Integer, Long> held = new HashMap<Integer, Long>();

    private final Runnable stuckWatch = new Runnable() {
        @Override public void run() {
            releaseStuck();
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // XML 里已经声明了 flagRequestFilterKeyEvents；某些 ROM 会忽略 XML 里的 flags，
        // 这里再显式设置一遍（按位或合并，避免丢掉系统给的其它 flag）。
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            error = "setServiceInfo: " + t;
            Log.w(TAG, "设置 flags 失败（XML 里已声明，通常不影响）", t);
        }
        loadConfig();
        running = true;
        main.removeCallbacks(stuckWatch);
        main.postDelayed(stuckWatch, 1000);
        Log.i(TAG, "无障碍服务已连接，按键通路就绪");
    }

    @Override public void onInterrupt() {
        Log.w(TAG, "onInterrupt");
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacks(stuckWatch);
        releaseAll();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只要按键，不需要窗口事件；XML 里也只订阅了 typeWindowStateChanged。
    }

    // ---------------------------------------------------------------- 配置

    private SharedPreferences sp() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void loadConfig() {
        try {
            map = defaultMap();
            String s = sp().getString(KEY_MAP, "");
            if (s != null && s.length() > 0) {
                String[] parts = s.split(",");
                for (String p : parts) {
                    int c = p.indexOf(':');
                    if (c <= 0) continue;
                    try {
                        int kc = Integer.parseInt(p.substring(0, c).trim());
                        int idx = Integer.parseInt(p.substring(c + 1).trim());
                        if (idx >= 0 && idx <= 9) map.put(Integer.valueOf(kc), Integer.valueOf(idx));
                        else map.remove(Integer.valueOf(kc));
                    } catch (NumberFormatException ignore) { }
                }
            }
        } catch (Throwable t) {
            error = "loadConfig: " + t;
        }
    }

    /** 参照包 defaultLabel() 的标准 gamepad 键码表，映射到 EdgeTX 的 10 个键。 */
    public static HashMap<Integer, Integer> defaultMap() {
        HashMap<Integer, Integer> m = new HashMap<Integer, Integer>();
        // ---- 五维键（RC Plus 2 的 DPAD，参照包 defaultLabel 的 0x13..0x17）----
        m.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_UP), Integer.valueOf(K_UP));           // 19
        m.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_DOWN), Integer.valueOf(K_DOWN));       // 20
        m.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_LEFT), Integer.valueOf(K_LEFT));       // 21
        m.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_RIGHT), Integer.valueOf(K_RIGHT));     // 22
        m.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_CENTER), Integer.valueOf(K_ENTER));    // 23
        // ---- 肩键 / 扳机 / 摇杆按下（参照包 0x66..0x6b -> L1/R1/L2/R2/L3/R3）----
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_L1), Integer.valueOf(K_PAGEUP));     // 102
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_R1), Integer.valueOf(K_PAGEDN));     // 103
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_L2), Integer.valueOf(K_MODEL));      // 104（MODEL 长按 = 模型管理）
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_R2), Integer.valueOf(K_TELE));       // 105
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_THUMBL), Integer.valueOf(K_EXIT));   // 106 (L3)
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_THUMBR), Integer.valueOf(K_ENTER));  // 107 (R3)
        // ---- 拍照 / 录像（参照包 0x1b / 0x82）----
        m.put(Integer.valueOf(KeyEvent.KEYCODE_CAMERA), Integer.valueOf(K_MODEL));         // 27
        m.put(Integer.valueOf(KeyEvent.KEYCODE_MEDIA_RECORD), Integer.valueOf(K_TELE));    // 130
        // ---- 外接手柄兜底（没有物理五维键时也能操作）----
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_A), Integer.valueOf(K_ENTER));       // 96
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_B), Integer.valueOf(K_EXIT));        // 97
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_X), Integer.valueOf(K_PAGEUP));      // 99
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_Y), Integer.valueOf(K_PAGEDN));      // 100
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_START), Integer.valueOf(K_ENTER));   // 108
        m.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_SELECT), Integer.valueOf(K_EXIT));   // 109
        return m;
    }

    private boolean enabled() {
        try { return sp().getBoolean(KEY_ENABLED, true); } catch (Throwable t) { return true; }
    }

    private boolean anyDevice() {
        try { return sp().getBoolean(KEY_ANY_DEVICE, false); } catch (Throwable t) { return false; }
    }

    /**
     * 设备名白名单，照抄参照包 ControlService.onKeyEvent()：
     * contains("DJI") || equals("gpio-keys") || contains("qpnp")。
     * 另加 anyDevice 开关，供 RC Plus 2 设备名与 RC2 不同（例如 DJI 自定义名）时兜底。
     */
    private boolean acceptDevice(String name) {
        if (name == null) return false;
        if (name.contains("DJI")) return true;
        if ("gpio-keys".equals(name)) return true;
        if (name.contains("qpnp")) return true;
        return anyDevice();
    }

    // ---------------------------------------------------------------- 核心

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        try {
            seen++;
            InputDevice dev = event.getDevice();
            String name = (dev == null) ? null : dev.getName();
            lastDevice = (name == null) ? "null" : name;
            lastKeyCode = event.getKeyCode();
            lastAction = (event.getAction() == KeyEvent.ACTION_DOWN) ? "down" : "up";

            // 只有本机物理设备才处理；不在白名单里的（外接键盘、系统栏等）直接放行。
            if (!acceptDevice(name)) return false;
            // 长按的重复 DOWN 不重复下发：EdgeTX 自己按「持续按下」判长按/重复。
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) return true;
            if (!enabled()) return false;

            int kc = event.getKeyCode();
            Integer mapped = map.get(Integer.valueOf(kc));
            int idx = (mapped == null) ? K_NONE : mapped.intValue();
            if (idx < 0 || idx > 9) {
                // 没有映射的键（音量/电源/返回等）一律放行给系统，绝不吞掉。
                Log.d(TAG, "未映射 keyCode=" + kc + " name=" + name + " scan=" + event.getScanCode());
                return false;
            }

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                press(idx);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                release(idx);
            } else {
                return false;
            }
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(kc) + "(" + kc + ") -> etx " + idx
                    + " " + lastAction + " dev=" + name);
            return true;   // 吞掉，别再漏给 App 自己的 dispatchKeyEvent
        } catch (Throwable t) {
            error = "onKeyEvent: " + t;
            Log.w(TAG, "处理按键失败", t);
            return false;
        }
    }

    private void press(int idx) {
        try {
            if (held.containsKey(Integer.valueOf(idx))) return;   // 已经是按下状态，别重入
            com.rc2.etx.jni.EtxNative.press(idx);
            held.put(Integer.valueOf(idx), Long.valueOf(System.currentTimeMillis()));
            delivered++;
        } catch (Throwable t) {
            error = "press: " + t;
        }
    }

    private void release(int idx) {
        try {
            if (held.remove(Integer.valueOf(idx)) == null) return;
            com.rc2.etx.jni.EtxNative.release(idx);
        } catch (Throwable t) {
            error = "release: " + t;
        }
    }

    /**
     * 只释放「按太久」的键。绝不能无条件全放 —— 那会把用户正常的
     * "按住 DOWN 连续滚动" 每 15 秒打断一次，表现为界面自己松手。
     */
    private void releaseStuck() {
        if (held.isEmpty()) return;
        long now = System.currentTimeMillis();
        Integer[] keys = held.keySet().toArray(new Integer[0]);
        for (int i = 0; i < keys.length; i++) {
            Long at = held.get(keys[i]);
            if (at == null || now - at.longValue() > STUCK_RELEASE_MS) {
                Log.w(TAG, "按键 " + keys[i] + " 按下超过 " + STUCK_RELEASE_MS + "ms 未释放，强制放行");
                release(keys[i].intValue());
            }
        }
    }

    private void releaseAll() {
        Integer[] keys = held.keySet().toArray(new Integer[0]);
        for (int i = 0; i < keys.length; i++) release(keys[i].intValue());
    }

    // ---------------------------------------------------------------- 静态查询（诊断用）

    public static String status() {
        return "无障碍按键 " + (running ? "已就绪" : "未就绪")
                + "  seen=" + seen + " delivered=" + delivered
                + " last=" + lastDevice + "/" + lastAction + "/" + lastKeyCode
                + (("-".equals(error)) ? "" : (" err=" + error));
    }
}
