package com.rc2.etx.radio;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/** 把每帧 CRSF 的十六进制录到文件，用于离线验证编码是否自洽。 */
public final class FileSink implements OutputSink {

    private BufferedWriter w;
    private File file;
    private volatile String status = "未打开";
    private volatile boolean open;
    private volatile long frames;
    private volatile long errors;

    @Override
    public String name() {
        return "录帧文件";
    }

    @Override
    public boolean open(String arg) {
        close();
        try {
            File dir = new File(arg);
            if (!dir.isAbsolute()) {
                dir = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"));
            }
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, "crsf-" + System.currentTimeMillis() + ".txt");
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            open = true;
            status = "录制中 " + file.getName();
            frames = 0;
            errors = 0;
            return true;
        } catch (Throwable t) {
            open = false;
            status = "打开失败: " + t;
            return false;
        }
    }

    public static FileSink forApp(Context c) {
        return new FileSink();
    }

    public File file() {
        return file;
    }

    @Override
    public void send(byte[] frame) {
        if (!open || w == null) return;
        try {
            if (frames % 20 == 0) {
                w.write(CrsfEncoder.hex(frame));
                w.write("\n");
                if (frames % 2000 == 0) w.flush();
            }
            frames++;
        } catch (Throwable t) {
            errors++;
        }
    }

    @Override
    public void close() {
        open = false;
        status = "已关闭";
        if (w != null) {
            try {
                w.flush();
                w.close();
            } catch (Throwable ignore) {
            }
            w = null;
        }
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public long frames() {
        return frames;
    }

    @Override
    public long errors() {
        return errors;
    }

    @Override
    public String detail() {
        return file == null ? "无文件" : file.getAbsolutePath();
    }
}
