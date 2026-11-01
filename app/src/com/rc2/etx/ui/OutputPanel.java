package com.rc2.etx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.rc2.etx.radio.FileSink;
import com.rc2.etx.radio.NullSink;
import com.rc2.etx.radio.OutputSink;
import com.rc2.etx.radio.Radio;
import com.rc2.etx.radio.UdpSink;
import com.rc2.etx.usb.UsbSerial;
import com.rc2.etx.usb.UsbSerialSink;

/** 输出设置页：UDP / USB 串口 / 录帧 / 空。 */
public final class OutputPanel extends Panel {

    public static final String[] KIND = {"空", "UDP", "USB串口", "录帧"};
    private static final int[] BAUD = {420000, 400000, 115200};

    private final Context ctx;
    private final Host host;
    private final RectF[] kindR = new RectF[4];
    private final RectF[] baudR = new RectF[3];
    private final RectF openR = new RectF();
    private final RectF closeR = new RectF();
    private final RectF targetR = new RectF();
    private final RectF testR = new RectF();
    private final RectF infoR = new RectF();
    private final RectF usbR = new RectF();
    private int kind;
    private int baudIdx;
    private String target = "255.255.255.255:8888";
    private String lastInfo = "";

    public OutputPanel(Radio r, Context c, Host h) {
        super(r);
        ctx = c.getApplicationContext();
        host = h;
        for (int i = 0; i < 4; i++) kindR[i] = new RectF();
        for (int i = 0; i < 3; i++) baudR[i] = new RectF();
    }

    @Override
    public String title() {
        return "输出";
    }

    @Override
    public void draw(Canvas c, Ui ui, RectF a) {
        float pad = ui.d(10);
        RectF left = new RectF(a.left + pad, a.top + pad, a.left + a.width() * 0.40f, a.bottom - pad);
        RectF right = new RectF(left.right + pad, a.top + pad, a.right - pad, a.bottom - pad);
        ui.card(c, left, Ui.CARD);
        ui.card(c, right, Ui.CARD);

        ui.text(c, "输出方式", left.left + ui.d(12), left.top + ui.d(24), 14, Ui.TEXT, true);
        for (int i = 0; i < 4; i++) {
            kindR[i].set(left.left + ui.d(12), left.top + ui.d(36) + i * ui.d(46),
                    left.right - ui.d(12), left.top + ui.d(36) + i * ui.d(46) + ui.d(38));
            boolean on = (i == kind);
            ui.button(c, kindR[i], KIND[i], on ? Ui.ACC : Ui.BLUE, on, 14);
        }
        ui.text(c, "波特率（USB）", left.left + ui.d(12), left.top + ui.d(244), 12, Ui.DIM, false);
        for (int i = 0; i < 3; i++) {
            baudR[i].set(left.left + ui.d(12) + i * ui.d(90), left.top + ui.d(254),
                    left.left + ui.d(12) + i * ui.d(90) + ui.d(84), left.top + ui.d(254) + ui.d(36));
            boolean on = (i == baudIdx);
            ui.button(c, baudR[i], String.valueOf(BAUD[i]), on ? Ui.ACC : Ui.BLUE, on, 12);
        }
        openR.set(left.left + ui.d(12), left.bottom - ui.d(96), left.left + ui.d(12) + ui.d(140), left.bottom - ui.d(56));
        closeR.set(left.left + ui.d(12), left.bottom - ui.d(50), left.left + ui.d(12) + ui.d(140), left.bottom - ui.d(10));
        ui.button(c, openR, "应用并打开", Ui.ACC, true, 14);
        ui.button(c, closeR, "关闭输出", Ui.BAD, false, 14);

        float x = right.left + ui.d(12);
        float y = right.top + ui.d(26);
        OutputSink s = radio.sink();
        ui.text(c, "当前：" + s.name(), x, y, 15, Ui.TEXT, true);
        ui.textR(c, s.isOpen() ? "已打开" : "未打开", right.right - ui.d(12), y, 13,
                s.isOpen() ? Ui.ACC : Ui.WARN, true);
        y += ui.d(22);
        ui.text(c, "已发 " + s.frames() + " 帧   错误 " + s.errors(), x, y, 12, Ui.DIM, false);
        y += ui.d(20);
        ui.text(c, "状态：" + s.status(), x, y, 12, Ui.TEXT, false);
        y += ui.d(18);
        ui.text(c, "细节：" + s.detail(), x, y, 11, Ui.DIM, false);

        y += ui.d(28);
        ui.text(c, "UDP 目标", x, y, 12, Ui.DIM, false);
        targetR.set(x + ui.d(88), y - ui.d(17), right.right - ui.d(12), y + ui.d(13));
        ui.card(c, targetR, Ui.CARD2);
        ui.textC(c, target, targetR.centerX(), targetR.centerY() + ui.d(5), 13, Ui.TEXT, false);

        y += ui.d(34);
        testR.set(x, y - ui.d(16), x + ui.d(120), y + ui.d(14));
        usbR.set(x + ui.d(130), y - ui.d(16), x + ui.d(130) + ui.d(140), y + ui.d(14));
        ui.button(c, testR, "发送 100 帧", Ui.BLUE, false, 12);
        ui.button(c, usbR, "扫描 USB 设备", Ui.BLUE, false, 12);

        y += ui.d(30);
        infoR.set(x, y, right.right - ui.d(12), right.bottom - ui.d(12));
        ui.card(c, infoR, 0xFF121820);
        drawWrapped(c, ui, lastInfo, infoR.left + ui.d(8), infoR.top + ui.d(18), infoR.width() - ui.d(16), 11);
    }

    private void drawWrapped(Canvas c, Ui ui, String text, float x, float y0, float maxW, float sp) {
        if (text == null || text.length() == 0) return;
        float y = y0;
        for (String raw : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                line.append(raw.charAt(i));
                if (ui.w(line.toString(), sp, false) > maxW) {
                    line.setLength(line.length() - 1);
                    ui.text(c, line.toString(), x, y, sp, Ui.DIM, false);
                    line.setLength(0);
                    line.append(raw.charAt(i));
                    y += ui.d(sp * 1.5f);
                    if (y > infoR.bottom - ui.d(4)) return;
                }
            }
            ui.text(c, line.toString(), x, y, sp, Ui.DIM, false);
            y += ui.d(sp * 1.5f);
            if (y > infoR.bottom - ui.d(4)) return;
        }
    }

    @Override
    public boolean down(int id, float x, float y) {
        for (int i = 0; i < 4; i++) {
            if (Ui.in(kindR[i], x, y)) {
                kind = i;
                return true;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (Ui.in(baudR[i], x, y)) {
                baudIdx = i;
                OutputSink s = radio.sink();
                if (s instanceof UsbSerialSink) ((UsbSerialSink) s).setBaud(BAUD[i]);
                return true;
            }
        }
        if (Ui.in(targetR, x, y)) {
            host.prompt("UDP 目标（IP:端口，广播用 255.255.255.255）", target, false, new Host.PromptCb() {
                @Override
                public void onResult(String text) {
                    if (text != null && text.trim().length() > 0) target = text.trim();
                }
            });
            return true;
        }
        if (Ui.in(testR, x, y)) {
            testSend();
            return true;
        }
        if (Ui.in(usbR, x, y)) {
            lastInfo = UsbSerial.list(ctx);
            return true;
        }
        if (Ui.in(openR, x, y)) {
            applyAndOpen();
            return true;
        }
        if (Ui.in(closeR, x, y)) {
            radio.setSink(new NullSink());
            lastInfo = "输出已关闭（空输出）。控制环仍在跑，通道照常计算。";
            host.onSettingsChanged();
            return true;
        }
        return true;
    }

    private void applyAndOpen() {
        OutputSink s = null;
        String arg = null;
        if (kind == 0) {
            s = new NullSink();
        } else if (kind == 1) {
            s = new UdpSink();
            arg = target;
        } else if (kind == 2) {
            UsbSerialSink u = new UsbSerialSink(ctx);
            u.setBaud(BAUD[baudIdx]);
            s = u;
            arg = "auto";
        } else {
            FileSink f = new FileSink();
            String dir = ctx.getExternalFilesDir(null) != null
                    ? ctx.getExternalFilesDir(null).getAbsolutePath()
                    : ctx.getFilesDir().getAbsolutePath();
            s = f;
            arg = dir;
        }
        boolean ok = s.open(arg);
        radio.setSink(s);
        StringBuilder sb = new StringBuilder();
        sb.append(s.name()).append(" -> ").append(s.status()).append('\n');
        sb.append(s.detail()).append('\n');
        if (s instanceof UsbSerialSink) {
            sb.append("---- 控制传输轨迹 ----\n").append(((UsbSerialSink) s).trace()).append('\n');
        }
        if (s instanceof FileSink && ((FileSink) s).file() != null) {
            sb.append("录制文件: ").append(((FileSink) s).file().getAbsolutePath()).append('\n');
        }
        if (kind == 2 && !ok) {
            sb.append("\n失败排查：\n");
            sb.append("1) RC2 是否已进入 USB host 模式（默认是从机）\n");
            sb.append("2) 转接板是否为原生 USB 设备（CDC-ACM），CH340/CP210x 这类桥接芯片暂不支持\n");
            sb.append("3) 插拔后是否点了系统 USB 授权弹窗\n");
        }
        lastInfo = sb.toString();
        host.toast(ok ? ("输出已打开：" + s.name()) : "打开失败，见右侧信息");
        host.onSettingsChanged();
    }

    private void testSend() {
        OutputSink s = radio.sink();
        if (!s.isOpen()) {
            lastInfo = "当前输出未打开，先点「应用并打开」。";
            return;
        }
        int[] us = radio.usSnapshot();
        byte[] f = new byte[20];
        byte[] frame = new byte[26];
        com.rc2.etx.radio.CrsfEncoder.pack(us, frame);
        long before = s.frames();
        long errBefore = s.errors();
        for (int i = 0; i < 100; i++) {
            s.send(frame);
        }
        lastInfo = "已向 [" + s.name() + "] 连发 100 帧\n"
                + "帧数 " + before + " -> " + s.frames() + "，错误 " + errBefore + " -> " + s.errors()
                + "\n上一帧: " + com.rc2.etx.radio.CrsfEncoder.hex(frame);
    }
}
