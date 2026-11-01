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
}
