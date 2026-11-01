package com.rc2.etx.radio;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/** 读 V15 原版 `RADIO/radio.yml` 里的 7 轴校准（mid/spanNeg/spanPos），
 *  用于把摇杆原始 11bit ADC 值换算成 -1..+1。
 *
 *  为什么不能统一用 ±660：EdgeTX 是按 spanNeg / spanPos 分别算满量程的，
 *  例如本机 LH 是 spanNeg=642、spanPos=608（不对称）。用统一 660 会让一侧先到顶、另一侧到不了顶。
 */
public final class RadioYml {

    public static final String[] AXES = {"LH", "LV", "RV", "RH", "P1", "P2", "P3"};

    public final int[] mid = new int[7];
    public final int[] spanNeg = new int[7];
    public final int[] spanPos = new int[7];
    public boolean ok;
    public String err = "";
    public String semver = "", board = "";
    public int stickMode = -1;

    public RadioYml() {
        for (int i = 0; i < 7; i++) {
            mid[i] = 1024;
            spanNeg[i] = 660;
            spanPos[i] = 660;
        }
    }

    /** 默认文件位置：<externalFilesDir>/sd/RADIO/radio.yml */
    public static File defaultFile(File ext) {
        return new File(new File(ext, "sd"), "RADIO/radio.yml");
    }

    public static RadioYml load(File f) {
        RadioYml r = new RadioYml();
        if (f == null || !f.exists()) {
            r.err = "没有 radio.yml: " + f;
            return r;
        }
        int cur = -1;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("semver:")) r.semver = val(t);
                else if (t.startsWith("board:")) r.board = val(t);
                else if (t.startsWith("stickMode:")) r.stickMode = (int) num(val(t), -1);
                for (int i = 0; i < 7; i++) {
                    if (t.equals(AXES[i] + ":") || t.startsWith(AXES[i] + ": ")) {
                        cur = i;
                        break;
                    }
                    if (t.equals(AXES[i].toLowerCase(Locale.US) + ":")) {
                        cur = i;
                        break;
                    }
                }
                if (cur >= 0) {
                    if (t.startsWith("mid:")) r.mid[cur] = (int) num(val(t), 1024);
                    else if (t.startsWith("spanNeg:")) r.spanNeg[cur] = (int) num(val(t), 660);
                    else if (t.startsWith("spanPos:")) r.spanPos[cur] = (int) num(val(t), 660);
                }
            }
            br.close();
            r.ok = true;
        } catch (Throwable e) {
            r.err = String.valueOf(e);
        }
        for (int i = 0; i < 7; i++) {
            if (r.spanNeg[i] <= 0) r.spanNeg[i] = 660;
            if (r.spanPos[i] <= 0) r.spanPos[i] = 660;
        }
        return r;
    }

    private static String val(String line) {
        int p = line.indexOf(':');
        String v = p < 0 ? "" : line.substring(p + 1).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
        return v;
    }

    private static double num(String s, double dflt) {
        try {
            return Double.parseDouble(s);
        } catch (Throwable e) {
            return dflt;
        }
    }

    /** 把 0..2047 的原始值按本轴 span 换成 -1..+1（夹住）。 */
    public float unit(int axis, int raw) {
        if (axis < 0 || axis >= 7) return 0f;
        float m = mid[axis];
        float v = raw - m;
        float span = v >= 0 ? spanPos[axis] : spanNeg[axis];
        if (span < 1f) span = 1f;
        float u = v / span;
        if (u > 1f) u = 1f;
        if (u < -1f) u = -1f;
        return u;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("radio.yml ok=").append(ok).append(" semver=").append(semver)
          .append(" board=").append(board).append(" stickMode=").append(stickMode);
        if (!ok) sb.append(" err=").append(err);
        for (int i = 0; i < 7; i++) {
            sb.append('\n').append(AXES[i]).append(" mid=").append(mid[i])
              .append(" neg=").append(spanNeg[i]).append(" pos=").append(spanPos[i]);
        }
        return sb.toString();
    }
}
