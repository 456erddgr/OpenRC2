package com.rc2.etx.sd;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;

/** 主题循环：读 sd/THEMES 下的目录名，写 RADIO/radio.yml 的 selectedTheme。 */
public class ThemePick {
    public static File sdDir(Context c) {
        return new File(c.getExternalFilesDir(null), "sd");
    }
    public static ArrayList<String> list(Context c) {
        ArrayList<String> out = new ArrayList<>();
        File[] fs = new File(sdDir(c), "THEMES").listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) out.add(f.getName());
        java.util.Collections.sort(out);
        return out;
    }
    public static String current(Context c) {
        try {
            File f = new File(new File(sdDir(c), "RADIO"), "radio.yml");
            // 先备份：整文件重写一旦格式出错，EdgeTX 会把 radio.yml 改名成
            // radio_error.yml 并退回占位页（实测踩过），备份便于自动回滚。
            File bak = new File(f.getParentFile(), "radio.yml.bak");
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.FileOutputStream bo = new java.io.FileOutputStream(bak);
                byte[] bb = new byte[8192];
                int n;
                while ((n = in.read(bb)) > 0) bo.write(bb, 0, n);
                in.close(); bo.close();
            } catch (Throwable ign) { }
            if (!f.exists()) return "?";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.startsWith("selectedTheme:")) {
                    r.close();
                    return l.substring(14).trim().replace("\"", "");
                }
            }
            r.close();
        } catch (Throwable t) { }
        return "?";
    }
    /** 切到下一个主题并写回 radio.yml，返回新主题名。 */
    public static String next(Context c) {
        ArrayList<String> all = list(c);
        if (all.isEmpty()) return current(c);
        String cur = current(c);
        int i = all.indexOf(cur);
        String nxt = all.get((i + 1) % all.size());
        try {
            File f = new File(new File(sdDir(c), "RADIO"), "radio.yml");
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String l;
            while ((l = r.readLine()) != null) {
                if (l.trim().startsWith("selectedTheme:")) l = "selectedTheme: \"" + nxt + "\"";
                sb.append(l).append((char) 10);
            }
            r.close();
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(sb.toString());
            w.close();
        } catch (Throwable t) { }
        return nxt;
    }
}
