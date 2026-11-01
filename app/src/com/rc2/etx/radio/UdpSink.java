package com.rc2.etx.radio;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** CRSF 帧走 UDP 单播：PC 端/ESP32 桥接器最容易接的一条路。 */
public final class UdpSink implements OutputSink {

    private DatagramSocket sock;
    private InetAddress addr;
    private int port = 8888;
    private volatile String status = "未打开";
    private volatile boolean open;
    private volatile long frames;
    private volatile long errors;
    private volatile String lastErr = "";

    @Override
    public String name() {
        return "UDP";
    }

    @Override
    public boolean open(String arg) {
        close();
        String host = "255.255.255.255";
        int p = 8888;
        try {
            if (arg != null && arg.length() > 0) {
                String s = arg.trim();
                int i = s.lastIndexOf(':');
                if (i > 0) {
                    host = s.substring(0, i);
                    p = Integer.parseInt(s.substring(i + 1).trim());
                } else {
                    host = s;
                }
            }
            addr = InetAddress.getByName(host);
            port = p;
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            open = true;
            status = "已打开 " + host + ":" + port;
            frames = 0;
            errors = 0;
            return true;
        } catch (Throwable t) {
            open = false;
            status = "打开失败: " + t;
            lastErr = String.valueOf(t);
            return false;
        }
    }

    @Override
    public void send(byte[] frame) {
        if (!open || sock == null) return;
        try {
            sock.send(new DatagramPacket(frame, frame.length, addr, port));
            frames++;
        } catch (Throwable t) {
            errors++;
            lastErr = String.valueOf(t);
        }
    }

    @Override
    public void close() {
        open = false;
        status = "已关闭";
        if (sock != null) {
            try {
                sock.close();
            } catch (Throwable ignore) {
            }
            sock = null;
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
        return "目标 " + (addr == null ? "-" : addr.getHostAddress()) + ":" + port
                + (lastErr.length() == 0 ? "" : " | 最近错误 " + lastErr);
    }
}
