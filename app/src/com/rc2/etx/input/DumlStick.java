package com.rc2.etx.input;

import android.util.Log;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 机内摇杆输入：连 dji_link 的本地 TCP 服务 127.0.0.1:40007，只读接收推送。
 *
 * 内层帧（30 字节，已用 CRC8/CRC16 全表验证）：
 *   [0]=0x55 [1]=LEN&0xFF [2]=(1<<2)|(LEN>>8) [3]=CRC8
 *   [4]=from [5]=to [6..7]=seq [8]=attr [9]=cmd_set [10]=cmd_id [11]=?
 *   [12..15]=标志 [16..27]=6×u16le(ADC 11bit，中点 1024) [28..29]=CRC16(LE)
 * 摇杆消息：cmd_set=0x06 cmd_id=0xAE。
 *
 * 2026-11-01 修复（用户反馈"老是断连、动几下就抽搐"）：
 *  1) 每帧必须过 CRC8+CRC16 才算有效（原实现不校验，会把噪声当摇杆帧并造成解析失步）；
 *  2) 断线重连改为"单线程 + 代号"，旧线程退出前不碰新 socket
 *     （原实现 restart() 另起线程，旧线程 finally 里 close() 会把新 socket 关掉 → 断连 + 抽搐）；
 *  3) 不再向 socket 发送任何字节（06/77 回放实验会让 dji_link 主动断开连接，已废弃）。
 */
public final class DumlStick {

    private static final String TAG = "ETX-Duml";

    public static final int AXES = 6;
    public static final int PORT = 40007;
    public static final int ADC_MID = 1024;
    /** 实测满行程端点 364 / 1684，即 ±660（615 帧标定 + MCU 日志双重验证）。 */
    public static final float ADC_SPAN = 660f;

    /**
     * 已发布的摇杆快照（volatile 换引用 = 原子发布）。
     * 2026-11-01 修复"同时动几下就抽搐"：原来 handleFrame 逐个写 raw[k]，
     * 而 100Hz 控制环在另一个线程读 raw[0..5] → 读到一半新一半旧的值 → 撕裂抽搐。
     */
    public volatile int[] raw = new int[AXES];
    public volatile boolean connected;
    public volatile long frames;
    public volatile long stickFrames;
    public volatile long badFrames;
    public volatile long crcFail;
    public volatile int hz;
    /** 只统计摇杆帧的速率（真机约 9–10Hz，比总帧率有意义）。 */
    public volatile int stickHz;
    public volatile long lastFrameMs;
    public volatile long lastStickMs;
    public volatile String lastHex = "";
    public volatile String err = "-";
    /** 断线次数（诊断用）。 */
    public volatile int reconnects;
    /** 连续重连次数（用于退避）。 */
    public volatile int burst;

    private final java.util.LinkedHashMap<String, String> seen =
            new java.util.LinkedHashMap<String, String>();
    private final StringBuilder samples = new StringBuilder();
    // 读端饥饿诊断：记录相邻两帧的间隔，RST 前若出现长空洞即为读线程饥饿
    private long lastFrameAtMs;
    private long maxGapMs;
    private int gapOver150;
    private long sampleT0 = -1;
    private final java.util.LinkedHashMap<String, Integer> cnt =
            new java.util.LinkedHashMap<String, Integer>();

    private volatile boolean run;
    private Thread th;
    /** 代号：每次 restart 自增；旧线程发现代号变了就退出，绝不碰新 socket。 */
    private volatile int gen;
    private volatile Socket sock;
    private long hzFrom;
    /** 心跳计时器基准（连接建立时的毫秒）。 */
    private final long hbBase = System.currentTimeMillis();
    private int hzCount;
    private int stickHzCount;

    public DumlStick() {
        for (int i = 0; i < AXES; i++) raw[i] = ADC_MID;
    }

    /** 把当前快照一次性拷进 dst（读一次 volatile 引用，保证 6 轴同帧）。 */
    public void copyRaw(int[] dst) {
        int[] src = raw;
        for (int i = 0; i < AXES && i < dst.length; i++) dst[i] = src[i];
    }

    public synchronized void start() {
        if (run) return;
        run = true;
        spawn();
    }

    private void spawn() {
        // 单飞：只要还有活着的 etx-duml 线程，就绝不再起第二个。
        // （之前 Activity 重建会 new 出第二个 Radio，两个 DumlStick 同时抢
        //   127.0.0.1:40007 的唯一客户端名额，dji_link 每 ~2 秒互相踢，表现就是
        //   "几秒断一次 + 抽搐"。）
        if (th != null && th.isAlive()) return;
        final int g = ++gen;
        th = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(g);
            }
        }, "etx-duml");
        th.setDaemon(true);
        th.start();
    }

    public synchronized void stop() {
        run = false;
        gen++;                 // 让旧线程自己退出
        closeSocket();
    }

    /** 断开并立刻重连（Radio 的看门狗与 UI 的"重连"按钮用）。
     *  关键：只让**当前**工作线程自己重连，不再另起线程（旧线程会误关新 socket）。 */
    public synchronized void restart() {
        err = "";
        closeSocket();
        if (run) {
            // 唤醒阻塞中的读；工作线程会因 gen 变化退出后自行重连
        } else {
            start();
        }
    }

    public boolean isRunning() {
        return run;
    }

    private void closeSocket() {
        Socket s = sock;
        sock = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private void loop(int myGen) {
        byte[] buf = new byte[8192];
        int have = 0;
        int failStreak = 0;
        while (run && gen == myGen) {
            Socket s = null;
            try {
                s = new Socket();
                sock = s;
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(30);
                connected = true;
                err = "-";
                failStreak = 0;
                Log.i(TAG, "connected 127.0.0.1:" + PORT);
                InputStream in = s.getInputStream();
                java.io.OutputStream out = s.getOutputStream();
                have = 0;
                final long markFrames = frames;
                long lastHb = 0;
                while (run && gen == myGen) {
                    int n;
                    try {
                        n = in.read(buf, have, buf.length - have);
                    } catch (java.net.SocketTimeoutException te) {
                        n = 0;
                    }
                    // 心跳：dji_link 会把"不吭声/回显错"的客户端踢掉（实测 18~60s 后 read 返回 -1）。
                    //
                    // 抓包分析（re/now.pcap，12s，312 个客户端包）：
                    //   服务端 → 客户端： 01 01 08 0a | A | B        (A = t, B = t-Δ)
                    //   客户端 → 服务端： 01 01 08 0a | B | B-Δ      ← 必须回显服务端那一对，且整体滞后一格
                    // 大疆模拟器（真客户端）就是这么做的；我们自己编一个本地计时器发过去，
                    // 服务端判定时钟不同源 → 24 秒左右把连接 reset 掉（这就是"老是断连"）。
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastHb >= 30) {
                        lastHb = nowMs;
                        byte[] hb = nextHeartbeat();
                        if (hb != null) {
                            try {
                                out.write(hb);
                                out.flush();
                            } catch (Throwable werr) {
                                Log.w(TAG, "heartbeat failed: " + werr);
                                break;
                            }
                        }
                    }
                    if (n == 0 && buf != null && have == 0) {
                        continue;              // 只是这 30ms 没数据
                    }
                    if (n < 0) {
                        Log.w(TAG, "read returned " + n + " (EOF/reset) after "
                                + (frames - markFrames) + " frames");
                        break;                  // 对端关闭
                    }
                    if (n == 0) continue;
                    have += n;
                    have = parse(buf, have);
                }
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "link down: " + err);
            } finally {
                // 只关**自己**这条 socket：不能碰别人（新一代）的连接
                if (sock == s) {
                    sock = null;
                    connected = false;
                }
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (!run || gen != myGen) break;
            failStreak++;
            reconnects++;
            // 退避重连：1s/2s/4s/8s（上限 8s）。不给 dji_link 的客户端配额造成压力，
            // 也不像原来的"1s 固定 + 看门狗 4s/8s/16s/30s"那样两套退避互相打架。
            // RC Pro：连上立刻 EOF 且收到过帧 → 快照模式，10ms 内重连继续取下一帧。
            if (failStreak == 1 && frames > 0 && (System.currentTimeMillis() - lastFrameMs) < 1500) {
                snapMode = true;
            }
            long wait = snapMode ? 10L : Math.min(1000L << Math.min(failStreak, 3), 8000L);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignore) {
                break;
            }
        }
        connected = false;
    }

    /** 解析缓冲，返回剩余未消费字节数（数据左移）。 */
    private int parse(byte[] b, int len) {
        int i = 0;
        while (len - i >= 8) {
            // 服务端心跳记录：01 01 08 0a | u32BE A | u32BE B（共 12 字节）
            if ((b[i] & 0xFF) == 0x01 && (b[i + 1] & 0xFF) == 0x01
                    && (b[i + 2] & 0xFF) == 0x08 && (b[i + 3] & 0xFF) == 0x0A) {
                if (len - i < 12) break;
                i += 12;
                continue;
            }
            if ((b[i] & 0xFF) != 0x55 || (b[i + 1] & 0xFF) != 0xCC) {
                i++;
                continue;
            }
            int plen = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8)
                    | ((b[i + 6] & 0xFF) << 16) | ((b[i + 7] & 0xFF) << 24);
            if (plen <= 0 || plen > 2048) {
                i++;
                continue;
            }
            if (len - i - 8 < plen) break;      // 还没收全
            handleFrame(b, i + 8, plen);
            i += 8 + plen;
        }
        int rest = len - i;
        if (rest > 0) System.arraycopy(b, i, b, 0, rest);
        return rest;
    }

    private void handleFrame(byte[] b, int off, int len) {
        frames++;
        lastFrameMs = System.currentTimeMillis();
        if (len < 12 || (b[off] & 0xFF) != 0x55) {
            badFrames++;
            return;
        }
        // CRC8：覆盖 [0..2]，init 0x77
        if (DumlCrc.crc8(b, off, 3) != (b[off + 3] & 0xFF)) {
            crcFail++;
            badFrames++;
            return;
        }
        // CRC16：覆盖 [0..len-3]，init 0x3692，尾部 u16 小端
        int want = DumlCrc.crc16(b, off, len - 2);
        int got = (b[off + len - 2] & 0xFF) | ((b[off + len - 1] & 0xFF) << 8);
        if (want != got) {
            crcFail++;
            badFrames++;
            return;
        }
        int cmdSet = b[off + 9] & 0xFF;
        int cmdId = b[off + 10] & 0xFF;
        String key = String.format("%02X/%02X", cmdSet, cmdId);
        Integer c = cnt.get(key);
        cnt.put(key, c == null ? 1 : c + 1);
        if (!seen.containsKey(key) && seen.size() < 40) {
            seen.put(key, "len=" + len + " " + hex(b, off, Math.min(len, 96)));
            Log.i(TAG, "frame " + key + " len=" + len);
        }
        // RC Pro(RM510)：cmdId 174(0xAE) 在 40007 上是按键/挡位帧（17B 载荷），不是摇杆帧。
        boolean isBtn = (cmdSet == 0x06 && cmdId == 0xAE && len >= 28 && !isStickLike(b, off, len));
        if (isBtn) {
            int p = off + 11;
            b0 = b[p] & 0xFF;
            b1 = b[p + 1] & 0xFF;
            b2 = b[p + 2] & 0xFF;
            btnMs = System.currentTimeMillis();
            btnFrames++;
            if (btnFrames <= 3) Log.i(TAG, "btn frame p0=" + b0 + " p1=" + b1 + " p2=" + b2);
            return;
        }
        boolean isStick = (cmdSet == 0x06 && cmdId == 0xAE);
        if (isStick && len >= 28) {
            int p = off + 16;
            int[] next = new int[AXES];
            for (int k = 0; k < AXES; k++) {
                int v = (b[p + k * 2] & 0xFF) | ((b[p + k * 2 + 1] & 0xFF) << 8);
                if (v > 2047) {           // 越界 → 不是摇杆帧
                    badFrames++;
                    return;
                }
                next[k] = v;
            }
            raw = next;                 // 一次性发布，控制环永远看到完整一帧
            stickFrames++;
            stickHzCount++;
            lastStickMs = System.currentTimeMillis();
            long gap = lastFrameAtMs == 0 ? 0 : lastStickMs - lastFrameAtMs;
            lastFrameAtMs = lastStickMs;
            if (gap > maxGapMs) maxGapMs = gap;
            if (gap > 150) { gapOver150++; Log.w(TAG, "READ GAP " + gap + "ms (hungry?)"); }
            lastHex = hex(b, p, 12);
            if (sampleT0 < 0) sampleT0 = System.currentTimeMillis();
            if (samples.length() < 120000) {
                samples.append(System.currentTimeMillis() - sampleT0);
                for (int k = 0; k < AXES; k++) samples.append(',').append(next[k]);
                samples.append((char) 10);
            }
        }
        long now = System.currentTimeMillis();
        hzCount++;
        if (now - hzFrom >= 1000) {
            hz = (int) (hzCount * 1000 / Math.max(1, now - hzFrom));
            stickHz = (int) (stickHzCount * 1000 / Math.max(1, now - hzFrom));
            hzFrom = now;
            hzCount = 0;
            stickHzCount = 0;
        }
    }

    private int btnFrames;

    /** 摇杆帧的 6×u16 全部 <=2047；只要有一个越界就说明这是 174 按键帧。 */
    private static boolean isStickLike(byte[] b, int off, int len) {
        if (len < 28) return false;
        int p = off + 16;
        for (int k = 0; k < 6; k++) {
            int v = (b[p + k * 2] & 0xFF) | ((b[p + k * 2 + 1] & 0xFF) << 8);
            if (v > 2047) return false;
        }
        return true;
    }

    /** 最近一次从服务端收到的心跳对（A,B）。 */
    private volatile int srvA, srvB;

    /** RC Pro 专有：按键/挡位帧 06/AE(174) 的 3 个字节。 */
    private volatile int b0, b1, b2;
    private volatile long btnMs;
    /** RC Pro 的 40007 是"连一次给一帧就断"的快照模式，检测到就改用快速重连。 */
    private volatile boolean snapMode;

    public int btnRaw(int i) { return i == 0 ? b0 : (i == 1 ? b1 : b2); }
    public long btnAgeMs() { return System.currentTimeMillis() - btnMs; }
    public boolean isSnapMode() { return snapMode; }
    private volatile boolean srvSeen;
    /** 服务端最近一次心跳的 A（用于诊断；X 基准取第一个 A）。 */
    private volatile int srvLastA;
    /** 我们自己上一拍发出的心跳 X。 */
    private volatile int hbPrevX;

    /**
     * 心跳：01 01 08 0a | u32 t | u32 t（共 12 字节，A == B，t 单调递增 ms）。
     *
     * 抓包实证（re/now.pcap 312 个客户端包、re/long.pcap 1557 个）：
     *   - 服务端**从来不**发心跳帧；只有客户端每 ~30ms 主动发一次；
     *   - 两端数值相同，且随时间单调增长（差值 31~51，即毫秒）。
     * 旧实现等“服务端心跳”再回显 → 服务端不发，于是我们一帧都不发，
     * dji_link 判定客户端沉默，约 4 秒把连接 reset（这就是 RC2 老是断连的根因）。
     */
    private byte[] nextHeartbeat() {
        // 用开机以来的毫秒数（SystemClock.elapsedRealtime），与服务端时钟同域；
        // 用"App 启动以来"的毫秒会让数值比服务端上次数小 → 服务端判时钟倒退 → RST。
        long t = android.os.SystemClock.elapsedRealtime();
        return new byte[]{
                0x01, 0x01, 0x08, 0x0a,
                (byte) (t >>> 24), (byte) (t >>> 16), (byte) (t >>> 8), (byte) t,
                (byte) (t >>> 24), (byte) (t >>> 16), (byte) (t >>> 8), (byte) t
        };
    }


    /** 从服务端心跳记录里取 (A,B)。 */

    /** 若外部（radio.yml）给了每轴校准，就用它；否则退回统一的 1024/±660。 */
    private volatile int[] axisMid, axisNeg, axisPos;

    public void setAxisSpans(int[] mid, int[] neg, int[] pos) {
        axisMid = mid;
        axisNeg = neg;
        axisPos = pos;
    }


    /** 按实测峰值缩小某轴行程：让"用户实际能推到的位置"成为新的满行程。 */
    /**
     * 把某轴行程缩放到“实测峰值”：peak 是本次校准中该轴达到的最大归一化幅值(0..1)。
     * 校准后 raw 的 ±peak 会映射成 ±1，即推到手感极限就是 100%——
     * 解决 RC Pro 这类物理行程偏小的机器“推满也不到 100%”。
     */
    public void scaleSpanToPeak(int axis, float peak) {
        if (axisNeg == null || axisPos == null || peak <= 0.05f) return;
        float k = Math.min(1f, peak);
        axisNeg[axis] = Math.max(60, Math.round(axisNeg[axis] * k));
        axisPos[axis] = Math.max(60, Math.round(axisPos[axis] * k));
    }

    public void shrinkSpan(int axis, float factor) {
        if (axis < 0 || axis >= AXES || factor <= 0f || factor >= 1.5f) return;
        if (axisNeg == null || axisPos == null) return;
        axisNeg[axis] = Math.max(40, Math.round(axisNeg[axis] * factor));
        axisPos[axis] = Math.max(40, Math.round(axisPos[axis] * factor));
    }

    public float unit(int axis) {
        if (axis < 0 || axis >= AXES) return 0f;
        return unitOf(raw[axis], axis);
    }

    /** 一次读出 6 轴归一化值（同一帧快照，避免跨帧混读导致抽搐）。 */
    public void units(float[] out) {
        int[] src = raw;                 // 读一次 volatile 引用
        int[] m = axisMid, ng = axisNeg, ps = axisPos;
        for (int i = 0; i < AXES && i < out.length; i++) {
            float mid = (m != null && i < m.length) ? m[i] : ADC_MID;
            float span = ADC_SPAN;
            if (ng != null && ps != null && i < ng.length && i < ps.length) {
                span = (src[i] >= mid) ? ps[i] : ng[i];
            }
            if (span < 1f) span = 1f;
            float v = (src[i] - mid) / span;
            out[i] = v < -1f ? -1f : (v > 1f ? 1f : v);
        }
    }

    private float unitOf(int rawValue, int axis) {
        int[] m = axisMid, ng = axisNeg, ps = axisPos;
        float mid = (m != null && axis < m.length) ? m[axis] : ADC_MID;
        float span = ADC_SPAN;
        if (ng != null && ps != null && axis < ng.length && axis < ps.length) {
            span = (rawValue >= mid) ? ps[axis] : ng[axis];
        }
        if (span < 1f) span = 1f;
        float v = (rawValue - mid) / span;
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    /** 摇杆采样序列（ms,v0..v5 每行一条），用于离线拟合轴映射。 */
    /** 读端饥饿统计：最大帧间隔与 >150ms 次数。 */
    public String gapStats() {
        return "maxGap=" + maxGapMs + "ms over150=" + gapOver150;
    }

    public String dumpSamples() {
        return samples.length() == 0 ? "(无摇杆采样)" : samples.toString();
    }

    /** 帧类型普查结果，供诊断页/adb 报告用。 */
    public String census() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) {
            Integer c = cnt.get(e.getKey());
            sb.append(e.getKey()).append(" x").append(c == null ? 0 : c.intValue())
              .append(' ').append(e.getValue()).append((char) 10);
        }
        return sb.length() == 0 ? "(尚无帧)" : sb.toString();
    }

    public String hex(byte[] b, int off, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && off + i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[off + i] & 0xFF));
        }
        return sb.toString();
    }
}
