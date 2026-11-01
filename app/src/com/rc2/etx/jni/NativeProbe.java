package com.rc2.etx.jni;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.Arrays;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** libetx.so 的无人值守自检：init → 喂一次输入 → tick → render → 存 png → 读 16 通道。
 *  给 adb 用：am start ... --es action native  →  报告写 files/native.txt + native_frame.png */
public final class NativeProbe {

    private static final String TAG = "ETX-Native";

    private NativeProbe() {
    }

    /** 逐键试：0..9 每个键按一次，若画面变化就存 key_<k>.png。 */
    public static String runKeys(Context c) {
        StringBuilder sb = new StringBuilder();
        File sd = new File(c.getExternalFilesDir(null), "sd");
        if (!EtxNative.available()) return "so 未就位: " + EtxNative.error() + (char) 10;
        if (EtxNative.init(sd.getAbsolutePath()) != 0) return "init 失败" + (char) 10;
        int w = EtxNative.screenW(), h = EtxNative.screenH();
        int[] axes = {1024, 1024, 1024, 1024, 1024, 1024, 1024};
        int[] sw = {0, 0, 0, 0};
        int[] kn = {1024, 1024};
        int[] cur = new int[w * h];
        int[] prev = new int[w * h];
        for (int f = 0; f < 40; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); }
        EtxNative.full(prev, w, h);
        for (int k = 0; k < 10; k++) {
            EtxNative.key(k, true);
            EtxNative.key(k, false);
            for (int f = 0; f < 25; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); }
            EtxNative.full(cur, w, h);
            boolean diff = false;
            for (int i = 0; i < cur.length; i += 7) if (cur[i] != prev[i]) { diff = true; break; }
            sb.append("key ").append(k).append(" changed=").append(diff).append((char) 10);
            if (diff) {
                try {
                    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bm.setPixels(cur, 0, w, 0, 0, w, h);
                    File png = new File(c.getExternalFilesDir(null), "key_" + k + ".png");
                    FileOutputStream fos = new FileOutputStream(png);
                    bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                } catch (Throwable t) { sb.append("png ").append(k).append(" 失败").append((char) 10); }
                System.arraycopy(cur, 0, prev, 0, cur.length);
            }
        }
        return sb.toString();
    }

    /** 只验证按键：从首页按 ENTER 进主菜单，抓一帧。 */
    public static String runMenu(Context c) {
        StringBuilder sb = new StringBuilder();
        File sd = new File(c.getExternalFilesDir(null), "sd");
        if (!EtxNative.available()) return "so 未就位: " + EtxNative.error() + (char) 10;
        int irc;
        try { irc = EtxNative.init(sd.getAbsolutePath()); }
        catch (Throwable t) { return "init 抛异常: " + t + (char) 10; }
        if (irc != 0) return "init rc=" + irc + (char) 10;
        int w = EtxNative.screenW(), h = EtxNative.screenH();
        int[] axes = {1024, 1024, 1024, 1024, 1024, 1024, 1024};
        int[] sw = {0, 0, 0, 0};
        int[] kn = {1024, 1024};
        int[] argb = new int[w * h];
        EtxNative.setVbat(4200);
        for (int f = 0; f < 8; f++) {
            EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
            EtxNative.tick(33);
            EtxNative.render(argb, w, h);
        }
        // 先用 10ms 步进跑 2 秒，确保 per10ms 走到位
        for (int f = 0; f < 200; f++) {
            EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
            EtxNative.tick(10);
            EtxNative.render(argb, w, h);
        }
        sb.append("vbat_rc=").append(EtxNative.setVbat(4206)).append((char) 10);
        int r1 = EtxNative.key(4, true), r2 = EtxNative.key(4, false);
        sb.append("key_rc down=").append(r1).append(" up=").append(r2).append((char) 10);
        for (int f = 0; f < 30; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); }
        EtxNative.tapKey(4);
        for (int f = 0; f < 30; f++) {
            EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
            EtxNative.tick(10);
            EtxNative.full(argb, w, h);
        }
        try {
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.setPixels(argb, 0, w, 0, 0, w, h);
            File png = new File(c.getExternalFilesDir(null), "native_menu.png");
            FileOutputStream fos = new FileOutputStream(png);
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            sb.append("menu_png bytes=").append(png.length()).append((char) 10);
        } catch (Throwable t) {
            sb.append("png 失败 ").append(t).append((char) 10);
        }
        return sb.toString();
    }

    public static String run(Context c) { return run(c, null); }

    /** 用真实摇杆轴喂原生（与生产路径同口径），并额外打印轴源与通道中立偏差。 */
    public static String run(Context c, com.rc2.etx.radio.Radio radio) {
        StringBuilder sb = new StringBuilder();
        if (!EtxNative.available()) {
            sb.append("available=false").append((char) 10);
            sb.append("error=").append(EtxNative.error()).append((char) 10);
            sb.append("提示：把 lib/arm64-v8a/libetx.so 打进 APK 后重试").append((char) 10);
            return sb.toString();
        }
        File ext = c.getExternalFilesDir(null);
        com.rc2.etx.sd.SdSeed.ensure(c);
        File sd = new File(ext, "sd");
        int rc;
        try {
            rc = EtxNative.init(sd.getAbsolutePath());
        } catch (Throwable t) {
            return "init 抛异常: " + t.getClass().getSimpleName() + " " + t.getMessage() + (char) 10;
        }
        sb.append("available=true").append((char) 10);
        sb.append("sd=").append(sd.getAbsolutePath()).append((char) 10);
        sb.append("radio_yml=").append(new File(sd, "RADIO/radio.yml").exists()).append((char) 10);
        sb.append("init_rc=").append(rc).append((char) 10);
        if (rc != 0) {
            sb.append("init 失败，后面的渲染跳过").append((char) 10);
            return sb.toString();
        }
        try {
            sb.append("version=").append(EtxNative.version()).append((char) 10);
        } catch (Throwable t) {
            sb.append("version 抛异常: ").append(t.toString()).append((char) 10);
        }
        int w = 0, h = 0;
        try {
            w = EtxNative.screenW();
            h = EtxNative.screenH();
        } catch (Throwable t) {
            sb.append("screen 抛异常: ").append(t.toString()).append((char) 10);
        }
        sb.append("screen=").append(w).append("x").append(h).append((char) 10);
        if (w <= 0 || h <= 0) return sb.toString();

        try {
            int[] axes = {1024, 1024, 1024, 1024, 1024, 1024, 1024};
            int[] sw = {0, 0, 0, 0};
            int[] kn = {1024, 1024};
            EtxNative.setVbat(4200);
            // 用真实摇杆轴喂（与生产路径同口径），并打印轴源，便于核对中立偏差
            if (radio != null) {
                radio.fillNativeAxes7(axes);
                sb.append("axes_src=").append(radio.dumlOn && radio.duml.connected ? "live-dji" : "synth")
                  .append(" raw7=").append(Arrays.toString(axes)).append((char) 10);
            }
            int[] argb = new int[w * h];
            int nf = 0, changed = 0;
            // 跑 30 帧再抓图：colorlcd 首帧只画脏区，后续帧才把整屏填满
            for (int f = 0; f < 30; f++) {
                EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
                nf = EtxNative.tick(33);
                int r = EtxNative.render(argb, w, h);
                if (r != 0) changed = r;
            }
            EtxNative.full(argb, w, h);   // 抓图前强制整屏
            sb.append("tick_newframe=").append(nf).append((char) 10);
            sb.append("render=").append(changed).append((char) 10);
            sb.append("render=").append(changed).append((char) 10);
            int nonZero = 0;
            for (int i = 0; i < argb.length; i += 97) if (argb[i] != 0) nonZero++;
            sb.append("argb_sampled_nonzero=").append(nonZero).append(" / ").append(argb.length / 97 + 1)
                    .append((char) 10);
            // 依次抓 4 屏：核心约定"点底部 20px 循环切屏"（第 4 屏是 LVGL 直通屏）
            for (int scr = 0; scr < 4; scr++) {
                if (scr > 0) {
                    EtxNative.touch(0, w / 2, h - 5);
                    EtxNative.touch(2, w / 2, h - 5);
                }
                for (int f2 = 0; f2 < 4; f2++) {
                    EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
                    EtxNative.tick(33);
                    EtxNative.full(argb, w, h);
                }
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bm.setPixels(argb, 0, w, 0, 0, w, h);
                File png = new File(ext, scr == 0 ? "native_frame.png" : ("native_frame" + scr + ".png"));
                FileOutputStream fos = new FileOutputStream(png);
                bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                sb.append("png").append(scr).append("=").append(png.getName())
                        .append(" bytes=").append(png.length()).append((char) 10);
            }
            EtxNative.tapKey(4);            // ENTER -> 主菜单
            int[] argb2 = argb;
            for (int f3 = 0; f3 < 6; f3++) {
                EtxNative.setInputs(axes, 7, sw, 4, kn, 0);
                EtxNative.tick(33);
                EtxNative.full(argb2, w, h);
            }
            Bitmap bm2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm2.setPixels(argb2, 0, w, 0, 0, w, h);
            File pngM = new File(ext, "native_menu.png");
            FileOutputStream fosM = new FileOutputStream(pngM);
            bm2.compress(Bitmap.CompressFormat.PNG, 100, fosM);
            fosM.close();
            sb.append("menu_png=").append(pngM.getName()).append(" bytes=").append(pngM.length()).append((char) 10);

            int[] us = new int[16];
            int n = EtxNative.channelsUs(us);
            sb.append("channels=").append(n).append(" us=[");
            for (int i = 0; i < 16; i++) sb.append(i == 0 ? "" : ",").append(us[i]);
            sb.append("]").append((char) 10);
        } catch (Throwable t) {
            sb.append("渲染/通道抛异常: ").append(t.toString()).append((char) 10);
            Log.e(TAG, "probe fail", t);
        }
        return sb.toString();
    }
}
