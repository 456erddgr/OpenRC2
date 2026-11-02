package com.rc2.etx.audio;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * 应用内提示音（按键音 / 开机音）。
 *
 * 说明：EdgeTX 自己的声音引擎需要喇叭驱动，移植核心里没接；
 * 这里走 Android 的 ToneGenerator，只做“操作有反馈”这一层，不碰核心。
 * 开关存在 Store 里（key = sfx），关掉就完全静音。
 */
public class Sfx {

    private static ToneGenerator tg;
    private static volatile boolean enabled = true;
    private static long lastClick = 0;

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled; }

    private static ToneGenerator tg() {
        if (tg == null) {
            try {
                tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            } catch (Throwable t) {
                tg = null;
            }
        }
        return tg;
    }

    /** 按键音：32ms 短促一声，40ms 内不重复触发，免得连点糊成一片。 */
    public static void click() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastClick < 40) return;
        lastClick = now;
        try {
            ToneGenerator g = tg();
            if (g != null) g.startTone(ToneGenerator.TONE_PROP_BEEP, 32);
        } catch (Throwable ignore) { }
    }

    /** 开机音：三声上行，异步不挡主线程。 */
    public static void boot() {
        if (!enabled) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ToneGenerator g = tg();
                    if (g == null) return;
                    int[] notes = {ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9};
                    for (int i = 0; i < notes.length; i++) {
                        g.startTone(notes[i], 90);
                        Thread.sleep(120);
                    }
                } catch (Throwable ignore) { }
            }
        }, "etx-sfx-boot").start();
    }

    public static void release() {
        try { if (tg != null) tg.release(); } catch (Throwable ignore) { }
        tg = null;
    }
}
