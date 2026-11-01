package com.rc2.etx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.diag.DeviceInfo;
import com.rc2.etx.diag.RootShell;
import com.rc2.etx.diag.SelfTest;
import com.rc2.etx.radio.Radio;
import com.rc2.etx.radio.Store;
import com.rc2.etx.usb.UsbSerial;

import java.util.ArrayList;
import java.util.List;

/** 诊断页：自检 / 设备事实 / USB 列表 / root 状态，结果可滚动。 */
public final class DiagPanel extends Panel {

    private static final String[] LABEL = {
            "运行自检", "设备信息", "型号设置", "root 状态", "保存诊断", "导出诊断"};

    private final Context ctx;
    private final Host host;
    private final RectF[] btn = new RectF[6];
    private final RectF textR = new RectF();
    private final List<String> lines = new ArrayList<String>();
    private float lineH;
    private int scrollY;
    private boolean dragging;
    private float lastY;
    private volatile String pending = "点上面按钮开始。\n自检会验证 CRSF 帧编码/解码、混控数学、11bit 打包对齐与算力。\n";

    public DiagPanel(Radio r, Context c, Host h) {
        super(r);
        ctx = c.getApplicationContext();
        host = h;
        for (int i = 0; i < btn.length; i++) btn[i] = new RectF();
    }

    @Override
    public String title() {
        return "诊断";
    }

    private void set(String s) {
        pending = s;
        lines.clear();
        scrollY = 0;
        host.invalidateUi();
    }

    private void append(String s) {
        pending = pending + "\n" + s;
        lines.clear();
        host.invalidateUi();
    }

    @Override
    public void tick() {
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        float pad = ui.d(10);
        float bh = ui.d(38);
        float bw = (a.width() - pad * 2 - ui.d(8) * 5) / 6f;
        for (int i = 0; i < LABEL.length; i++) {
            btn[i].set(a.left + pad + i * (bw + ui.d(8)), a.top + pad, a.left + pad + i * (bw + ui.d(8)) + bw, a.top + pad + bh);
            ui.button(c, btn[i], LABEL[i], i == 0 ? Ui.ACC : Ui.BLUE, i == 0, 13);
        }
        textR.set(a.left + pad, a.top + pad + bh + ui.d(8), a.right - pad, a.bottom - pad);
        ui.card(c, textR, 0xFF10161D);

        lineH = ui.d(13);
        List<String> wrapped = wrapped(ui, textR.width() - ui.d(16));
        float contentH = wrapped.size() * lineH + ui.d(12);
        int maxScroll = (int) Math.max(0, contentH - textR.height() + ui.d(8));
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0) scrollY = 0;
        c.save();
        c.clipRect(textR);
        float y = textR.top + ui.d(14) - scrollY;
        for (String s : wrapped) {
            ui.text(c, s, textR.left + ui.d(8), y, 11, Ui.TEXT, false);
            y += lineH;
            if (y > textR.bottom + lineH) break;
        }
        c.restore();
        if (maxScroll > 0) {
            float frac = textR.height() / (float) (contentH + ui.d(8));
            float barH = Math.max(ui.d(24), textR.height() * frac);
            float t = scrollY / (float) Math.max(1, maxScroll);
            float by = textR.top + t * (textR.height() - barH);
            ui.card(c, new RectF(textR.right - ui.d(5), by, textR.right - ui.d(1), by + barH), Ui.LINE);
        }
    }

    private String cachedSrc;
    private float cachedW;
    private final List<String> cache = new ArrayList<String>();

    private List<String> wrapped(Ui ui, float maxW) {
        if (pending.equals(cachedSrc) && Math.abs(maxW - cachedW) < 1f) return cache;
        cache.clear();
        for (String raw : pending.split("\n", -1)) {
            if (raw.length() == 0) {
                cache.add("");
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                cur.append(raw.charAt(i));
                if (ui.w(cur.toString(), 11, false) > maxW) {
                    cur.setLength(cur.length() - 1);
                    cache.add(cur.toString());
                    cur.setLength(0);
                    cur.append(raw.charAt(i));
                }
            }
            cache.add(cur.toString());
        }
        cachedSrc = pending;
        cachedW = maxW;
        return cache;
    }

    @Override
    public boolean down(int id, float x, float y) {
        if (Ui.in(textR, x, y)) {
            dragging = true;
            lastY = y;
            return true;
        }
        if (Ui.in(btn[0], x, y)) {
            set("自检中…\n");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String rep = SelfTest.run();
                    append(rep + "\n---- 设备 ----\n" + DeviceInfo.collect(ctx));
                }
            }, "etx-selftest").start();
            return true;
        }
        if (Ui.in(btn[1], x, y)) {
            set("采集中…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    append(DeviceInfo.collect(ctx));
                }
            }, "etx-device").start();
            return true;
        }
        if (Ui.in(btn[2], x, y)) {
            int m = com.rc2.etx.radio.Store.loadModel(ctx);
            m = (m + 1) % 3;
            com.rc2.etx.radio.Store.saveModel(ctx, m);
            append("遥控器型号 → " + com.rc2.etx.radio.Store.modelName(m)
                    + "（下次启动摇杆源按此选择）");
            return true;
        }
        if (Ui.in(btn[3], x, y)) {
            set("读取 root 状态…");
            RootShell.runAsync("id; echo ---; getprop ro.build.version.release; echo ---; "
                    + "cat /sys/class/usb_role/a600000.ssusb-role-switch/role 2>/dev/null; "
                    + "cat /sys/devices/platform/soc/a600000.ssusb/mode 2>/dev/null; "
                    + "ls -l /dev/socket/fpv_sock 2>/dev/null", new RootShell.Cb() {
                @Override
                public void done(String out) {
                    append("== root ==\n" + out);
                }
            });
            return true;
        }
        if (Ui.in(btn[4], x, y)) {
            boolean ok = Store.exportFile(ctx, radio.model);
            String path = Store.file(ctx).getAbsolutePath();
            append(ok ? "模型已导出:\n  " + path : "导出失败");
            return true;
        }
        if (Ui.in(btn[5], x, y)) {
            String NL2 = System.lineSeparator();
            try {
                java.io.File base = ctx.getExternalFilesDir(null);
                java.io.File out = new java.io.File(base, "exports");
                out.mkdirs();
                String[] names = {"sd_probe.txt", "hid.txt", "native.txt", "devices.txt"};
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    java.io.File f = new java.io.File(base, n);
                    if (!f.exists()) continue;
                    java.io.File dst = new java.io.File(out, n);
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(dst, false);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
                    in.close(); fo.close();
                    sb.append(n).append(" ").append(dst.length()).append("B ");
                }
                append("已导出到:" + NL2 + "  " + out.getAbsolutePath() + NL2 + sb);
            } catch (Throwable e2) {
                append("导出失败: " + e2);
            }
            return true;
        }
        return true;
    }

    @Override
    public void move(int id, float x, float y) {
        if (dragging) {
            scrollY -= (int) (y - lastY);
            lastY = y;
        }
    }

    @Override
    public void up(int id) {
        dragging = false;
    }
}
