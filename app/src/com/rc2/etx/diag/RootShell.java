package com.rc2.etx.diag;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 极简 root 命令执行器（只读为主，用于诊断页展示系统状态）。 */
public final class RootShell {

    private static final String TAG = "ETX-Root";
    private static final String[] SU = {"/debug_ramdisk/su", "su", "/system/xbin/su", "/system/bin/su"};

    private RootShell() {
    }

    public interface Cb {
        void done(String output);
    }

    public static void runAsync(final String cmd, final Cb cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = RootShell.run(cmd);
                if (cb != null) cb.done(out);
            }
        }, "etx-root");
        t.setDaemon(true);
        t.start();
    }

    public static String run(String cmd) {
        for (String su : SU) {
            try {
                String out = exec(su, cmd);
                if (out != null) return out;
            } catch (Throwable t) {
                Log.w(TAG, su + " 失败: " + t);
            }
        }
        return "（无法取得 root 输出）";
    }

    private static String exec(String su, String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            long deadline = System.currentTimeMillis() + 8000;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (System.currentTimeMillis() > deadline) {
                    sb.append("...(超时截断)\n");
                    break;
                }
                if (sb.length() > 20000) {
                    sb.append("...(输出过长截断)\n");
                    break;
                }
            }
            r.close();
            int code = p.waitFor();
            if (code == 0 || sb.length() > 0) return sb.toString();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
