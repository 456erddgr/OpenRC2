package com.rc2.etx.sd;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 首次运行时把 EdgeTX 需要的 SD 骨架铺出来。
 *
 * 为什么必须有：真机实测（DJI RC Pro / RM510）新装 App 后 sd/ 目录是空的，
 * EdgeTX 原生 init() 直接返回 -1，界面永远停在占位页。RC2 上之所以没事，
 * 是因为我们早就手工 push 过 sd/ 目录。手机上同理——所以这是"换机就白屏"的根因。
 *
 * 策略：只补缺，不覆盖。用户改过的 radio.yml / 模型文件永远优先。
 */
public final class SdSeed {

    private static final String TAG = "ETXSd";
    private static final String[] DIRS = {
            "RADIO", "MODELS", "SCRIPTS", "SCRIPTS/TOOLS", "SCRIPTS/MIXES",
            "IMAGES", "SOUNDS", "LOGS", "SCREENSHOTS", "THEMES"
    };

    private SdSeed() { }

    /** 返回 sd 根目录（顺便保证目录存在）。 */
    public static File ensure(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) ext = ctx.getFilesDir();
        File sd = new File(ext, "sd");
        mkdirs(sd);
        for (String d : DIRS) mkdirs(new File(sd, d));
        copyIfMissing(ctx, sd, "RADIO/radio.yml");
        copyIfMissing(ctx, sd, "MODELS/model1.yml");
        copyIfMissing(ctx, sd, "MODELS/labels.yml");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/elrsV3.lua");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/elrsgyro.lua");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/rf2.lua");
        return sd;
    }

    private static void mkdirs(File f) {
        if (!f.exists() && !f.mkdirs()) Log.w(TAG, "mkdirs 失败: " + f);
    }

    private static void copyIfMissing(Context ctx, File sd, String rel) {
        File dst = new File(sd, rel);
        if (dst.exists() && dst.length() > 0) return;
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getAssets().open("sd_seed/" + rel);
            File parent = dst.getParentFile();
            if (parent != null) mkdirs(parent);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Log.i(TAG, "seed " + rel + " " + dst.length() + "B");
        } catch (IOException e) {
            Log.w(TAG, "seed 失败 " + rel + ": " + e);
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignore) { }
            try { if (in != null) in.close(); } catch (IOException ignore) { }
        }
    }
    private static final String NL = String.valueOf((char) 10);

    /** 把 SD 骨架与初始化结果写成 sd_probe.txt，方便在无 adb 的机器上一眼看出 init 为什么失败。 */
    public static void writeProbe(java.io.File sd, int initRc, String extra) {
        StringBuilder b = new StringBuilder();
        b.append("sd=").append(sd == null ? "null" : sd.getAbsolutePath()).append(NL);
        b.append("sd_exists=").append(sd != null && sd.exists())
         .append(" canRead=").append(sd != null && sd.canRead())
         .append(" canWrite=").append(sd != null && sd.canWrite()).append(NL);
        b.append("init_rc=").append(initRc).append(NL);
        if (extra != null) b.append("extra=").append(extra).append(NL);
        if (sd != null) walk(b, sd, "", 0);
        try {
            java.io.File out = new java.io.File(sd == null ? new java.io.File("/sdcard") : sd.getParentFile(), "sd_probe.txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out, false);
            fo.write(b.toString().getBytes("UTF-8"));
            fo.close();
            android.util.Log.i("SdSeed", "probe -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            android.util.Log.w("SdSeed", "probe write failed: " + t);
        }
    }

    private static void walk(StringBuilder b, java.io.File f, String ind, int depth) {
        if (depth > 2) return;
        java.io.File[] kids = f.listFiles();
        if (kids == null) { b.append(ind).append("(no listing) ").append(f.getName()).append(NL); return; }
        java.util.Arrays.sort(kids, new java.util.Comparator<java.io.File>() {
            public int compare(java.io.File x, java.io.File y) { return x.getName().compareTo(y.getName()); }
        });
        for (java.io.File k : kids) {
            b.append(ind).append(k.getName());
            if (k.isDirectory()) {
                b.append("/").append(NL);
                if (depth < 2) walk(b, k, ind + "  ", depth + 1);
            } else {
                b.append(" ").append(k.length()).append(NL);
            }
        }
    }
}
