package com.rc2.etx.jni;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;

/** Plan D 验证：模拟一次触摸 → 走 Java 主线程取 pendingTap → 发 ENTER → 抓前后两帧比像素。 */
public final class TapProbe {

    private TapProbe() {
    }

    private static void save(Context c, int[] px, int w, int h, String name) {
        try {
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.setPixels(px, 0, w, 0, 0, w, h);
            FileOutputStream fos = new FileOutputStream(new File(c.getExternalFilesDir(null), name));
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    public static String run(Context c, int tx, int ty) {
        StringBuilder sb = new StringBuilder();
        File sd = new File(c.getExternalFilesDir(null), "sd");
        if (!EtxNative.available()) return "so 未就位: " + EtxNative.error() + (char) 10;
        if (EtxNative.init(sd.getAbsolutePath()) != 0) return "init 失败" + (char) 10;
        int w = EtxNative.screenW(), h = EtxNative.screenH();
        int[] axes = {1024, 1024, 1024, 1024, 1024, 1024, 1024};
        int[] sw = {0, 0, 0, 0};
        int[] kn = {1024, 1024};
        int[] before = new int[w * h];
        int[] after = new int[w * h];
        sb.append("screen=").append(w).append('x').append(h).append((char) 10);
        for (int f = 0; f < 40; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); }
        EtxNative.full(before, w, h);
        save(c, before, w, h, "tap_before.png");
        sb.append("tap at ").append(tx).append(',').append(ty).append((char) 10);
        EtxNative.touch(0, tx, ty);
        EtxNative.touch(2, tx, ty);
        for (int f = 0; f < 6; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); EtxNative.render(after, w, h); }
        int tap = EtxNative.pendingTap();
        sb.append("pending_tap=").append(tap).append((char) 10);
        if (tap >= 0) {
            EtxNative.key(4, true);
            EtxNative.key(4, false);
            sb.append("sent ENTER from java thread").append((char) 10);
        }
        for (int f = 0; f < 10; f++) { EtxNative.setInputs(axes, 7, sw, 4, kn, 0); EtxNative.tick(10); EtxNative.render(after, w, h); }
        EtxNative.full(after, w, h);
        save(c, after, w, h, "tap_after.png");
        int diff = 0;
        for (int i = 0; i < before.length; i++) if (before[i] != after[i]) diff++;
        sb.append("diff_px=").append(diff).append(" of ").append(before.length).append((char) 10);
        return sb.toString();
    }
}
