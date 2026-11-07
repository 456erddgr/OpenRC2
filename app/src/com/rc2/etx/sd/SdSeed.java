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
            "THEMES", "IMAGES", "SOUNDS", "LOGS", "SCREENSHOTS",
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
        copyTree(ctx, sd, "THEMES");
        copyTree(ctx, sd, "IMAGES");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/elrsV3.lua");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/elrsgyro.lua");
        copyIfMissing(ctx, sd, "SCRIPTS/TOOLS/rf2.lua");
        migrateModel1GearToCh9(sd);
        return sd;
    }

    /**
     * 2026-11-06：把默认模型的第 9 输入源从 SE 换成 SG。
     *
     * 背景：用户报「拨到 C 档 CH9 没反应，拨到 S 档 CH10 有反应」。
     * 原生 model1.yml 里输入 8（→CH9）原来绑的是 SE（录像键），输入 9（→CH10）
     * 绑的是 SF（调档S）。所以 S 档能动、C 档永远不动。
     * 现在把输入 8 改绑 SG（调档C），CH9 跟着 C 档动，CH10 继续跟 S 档。
     *
     * 这是"只改一行"的定点迁移：整个文件里 `srcRaw: "SE"` 只出现一次
     * （`mixData` 段用的是 `I8`）。改完再跑不会命中，天然幂等。
     * 改之前先备份成 model1.yml.bak，用户真改过也能回退。
     */
    private static void migrateModel1GearToCh9(File sd) {
        File f = new File(sd, "MODELS/model1.yml");
        if (!f.exists() || f.length() == 0) return;
        try {
            byte[] raw = readAll(f);
            String s = new String(raw, "UTF-8");
            final String from = "srcRaw: \"SE\"";
            final String to = "srcRaw: \"SG\"";
            if (s.indexOf(from) < 0) return;   // 已经迁移过 / 用户自建模型
            File bak = new File(sd, "MODELS/model1.yml.bak");
            if (!bak.exists()) writeAll(bak, raw);
            writeAll(f, s.replace(from, to).getBytes("UTF-8"));
            Log.i(TAG, "migrate model1: 输入8 SE -> SG（CH9 改跟随 C 档）");
        } catch (Throwable t) {
            Log.w(TAG, "migrate model1 失败: " + t);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } finally {
            try { in.close(); } catch (IOException ignore) { }
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(data);
        } finally {
            try { out.close(); } catch (IOException ignore) { }
        }
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
    /** 递归复制 assets 下的整棵目录（只补缺，不覆盖用户改动）。 */
    private static void copyTree(Context ctx, File sd, String rel) {
        try {
            String[] kids = ctx.getAssets().list("sd_seed/" + rel);
            if (kids == null) return;
            for (String k : kids) {
                copyTree0(ctx, sd, rel + "/" + k);
            }
        } catch (Throwable t) {
            android.util.Log.w("SdSeed", "copyTree " + rel + " failed: " + t);
        }
    }

    private static void copyTree0(Context ctx, File sd, String rel) {
        try {
            String[] kids = ctx.getAssets().list("sd_seed/" + rel);
            if (kids != null && kids.length > 0) {
                for (String k : kids) copyTree0(ctx, sd, rel + "/" + k);
                return;
            }
            copyIfMissing(ctx, sd, rel);
        } catch (Throwable ignore) {
        }
    }

}
