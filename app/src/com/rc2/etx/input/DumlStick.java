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
    /**
     * DUML 帧序号追踪（2026-11-06 新增）。
     * 用途：判断「10Hz 是源的上限，还是我们漏收了」。
     * 服务端每发一帧 seq 就 ++，如果 seq 的跳量远大于我们收到的帧数，
     * 说明中间被丢了（配额/背压/我们自己解析慢），而不是源只有 10Hz。
     */
    public volatile int lastSeq = -1;
    public volatile long seqGapSum = 0;     // 累计跳号量
    public volatile long seqGapFrames = 0;  // 发生了跳号的次数
    /** 只统计摇杆帧的速率（真机约 9–10Hz，比总帧率有意义）。 */
    public volatile int stickHz;
    public volatile long lastFrameMs;
    /**
     * 最近一帧摇杆数据的 **elapsedRealtime()** 时间戳（单调、含休眠）。
     * 与上面的 lastFrameMs(墙上时钟) 并存：lastFrameMs 只用于给人看的日志/诊断，
     * 所有"是否新鲜"的判断一律用这个 elapsed 版本（理由见 stickFresh() 的注释）。
     */
    public volatile long lastStickElapsed;
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
            // 本连接的建立时刻（0 = 这次没连上）。2026-11-03 修 RC Pro「连一次给一帧就断」：
            // 用「连接存活了多久 + 收到几帧」来区分两种 daemon：
            //   · RC2：一条长连接持续推 9~10Hz，存活 3~6 秒后偶发 RST → 存活时长 >= 500ms
            //   · RC Pro：连上立刻吐一份快照就 close，存活只有几十 ms、每连接 1 帧
            // 以前只用「最近 1500ms 有过帧」判快照模式，RC2 的 RST 也会命中 →
            // 会把一台正常机器错判成快照机（再叠加 5s 退避，等于把 9Hz 打成 0.2Hz）。
            long connectAt = 0;
            // 本连接收到的帧数基线。原来 markFrames 只声明在 try 里，
            // 出了 try 就取不到 —— 现在提到外面，供快照模式判定使用。
            final long frameMark = frames;
            try {
                s = new Socket();
                sock = s;
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(30);
                connectAt = System.currentTimeMillis();
                connected = true;
                err = "-";
                failStreak = 0;
                Log.i(TAG, "connected 127.0.0.1:" + PORT);
                InputStream in = s.getInputStream();
                java.io.OutputStream out = s.getOutputStream();
                have = 0;
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
                    // 不发任何上行字节。
                    // re/04-速率上限与槽位结论.md 实测：40007 是纯下行推送（9~10Hz），
                    // app→RC 方向外层魔数是 55 CC 49 57 + DUML，我们没有正确报文可发；
                    // 以前每 30ms 发的 01 01 08 0a 属非法帧 → 对端提前 RST，且把客户端槽位卡死。
                    if (n == 0 && buf != null && have == 0) {
                        continue;              // 只是这 30ms 没数据
                    }
                    if (n < 0) {
                        Log.w(TAG, "read returned " + n + " (EOF/reset) after "
                                + (frames - frameMark) + " frames");
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
            // ---- 快照模式判定（2026-11-03，RC Pro 修复）----
            // 判据：这次连接「活得很短」(<500ms) 且「确实收到了帧」。
            //   RC Pro: 连通 → 一包快照 → FIN/RST，典型存活 <100ms。
            //   RC2   : 长连接持续推帧，只在 3~6s 处偶发 RST，存活 >= 3000ms。
            // 500ms 这条线把两者干净分开，不会再把 RC2 误判成快照机。
            long livedMs = (connectAt == 0) ? 0 : (System.currentTimeMillis() - connectAt);
            long gotFrames = frames - frameMark;
            if (gotFrames > 0 && livedMs < SNAP_LIVE_MS) {
                if (!snapMode) Log.i(TAG, "检测到快照模式（存活 " + livedMs + "ms，"
                        + gotFrames + " 帧/连接）→ 改为高频重连");
                snapMode = true;
                snapBurst++;
            } else if (gotFrames > 0 && snapMode) {
                // 出现了一条"活得久、还持续给帧"的连接 → 说明这机器其实是推送式，
                // 立刻退出快照模式，避免把正常机器一直按 10ms 高频连（很吃 CPU/binder）。
                Log.i(TAG, "退出快照模式（存活 " + livedMs + "ms，恢复推送式连法）");
                snapMode = false;
                snapBurst = 0;
            }
            // 退避策略：
            //   · 快照模式：daemon 每次连接只 dump 一份，必须"立刻"再连才能拿到下一帧；
            //     原来这里固定 5000ms（注释还写着 10ms），等于 0.2Hz，
            //     下游 duml.connected 绝大部分时间是 false → RC Pro 全轴 -100%。
            //     改成 10ms；但连续失败 200 次说明这次不是快照而是真断了，退回 5s，
            //     免得空转占 CPU（设备只有 1.79GB RAM）。
            //   · 非快照模式：保持原来的 5s/5s/10s/20s/30s，RC2 行为不变。
            long wait;
            if (snapMode && gotFrames > 0 && snapBurst < 200) wait = 10L;
            else if (snapMode && snapBurst >= 200) wait = 5000L;
            else if (failStreak <= 2) wait = 5000L;
            else wait = Math.min(5000L << Math.min(failStreak - 2, 3), 30000L);
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
            if ((b[i] & 0xFF) != 0x55) {
                i++;
                continue;
            }
            // 流里混着两种形态：①带 hp 头 55 CC 30 75 + u32le LEN + DUML
            //                    ②裸 DUML：55 + LEN(10bit) + ver + crc8 ...
            boolean hasHp = (len - i >= 4)
                    && (b[i + 1] & 0xFF) == 0xCC && (b[i + 2] & 0xFF) == 0x30
                    && (b[i + 3] & 0xFF) == 0x75;
            if (hasHp) {
                if (len - i < 8) break;
                int plen = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8)
                        | ((b[i + 6] & 0xFF) << 16) | ((b[i + 7] & 0xFF) << 24);
                if (plen < 4 || plen > 2048) { i++; continue; }
                if (len - i - 8 < plen) break;      // 还没收全
                handleFrame(b, i + 8, plen);
                i += 8 + plen;
            } else {
                if (len - i < 4) break;
                int dlen = (b[i + 1] & 0xFF) | (((b[i + 2] & 0xFF) & 0x03) << 8);
                if (dlen < 4 || dlen > 2048) { i++; continue; }
                if (len - i < dlen) break;          // 还没收全
                handleFrame(b, i, dlen);
                i += dlen;
            }
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
        // seq 在载荷偏移 4..5（小端）。只在摇杆帧上统计，避免被别的帧型搅乱。
        {
            // 载荷布局：snd|rcv|seq(2,LE)|attr|cmd_set|cmd_id|...
            // 即 seq 在载荷偏移 2..3（= b[off+4..off+5]），不是 6..7。
            int seqNow = (b[off + 4] & 0xFF) | ((b[off + 5] & 0xFF) << 8);
            int cs0 = b[off + 9] & 0xFF, ci0 = b[off + 10] & 0xFF;
            if (cs0 == 0x06 && ci0 == 0xAE) {
                int last = lastSeq;
                if (last >= 0) {
                    int d = (seqNow - last) & 0xFFFF;
                    if (d > 1) { seqGapSum += (d - 1); seqGapFrames++; }
                }
                lastSeq = seqNow;
            }
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
        if (isStick && len == 30) {      // duml-re: 1216/1216 摇杆帧恒为 len=30
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
            // 新鲜度判断专用时间戳（单调、含休眠）。见 stickFresh() 注释。
            lastStickElapsed = android.os.SystemClock.elapsedRealtime();
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
            Log.i(TAG, "stat hz=" + hz + " stickHz=" + stickHz + " stick=" + stickFrames + " frames=" + frames + " bad=" + badFrames + " gap150=" + gapOver150
                    + " seq=" + lastSeq + " lost=" + seqGapSum + " gapN=" + seqGapFrames);
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
    /**
     * 判"快照模式"的连接存活门限（ms）。
     * RC Pro 的快照连接典型存活 20~80ms；RC2 的推送连接能活 3~6 秒才被 RST。
     * 500ms 落在两者之间，用来把两种 daemon 干净分开（判错会把 RC2 打成 0.2Hz）。
     */
    private static final long SNAP_LIVE_MS = 500L;
    /** 快照模式下连续成功重连的次数（>=200 说明不是快照而是快照端也没数据，退回慢速）。 */
    private volatile int snapBurst;

    /**
     * 摇杆数据是否"新鲜"：最近 ms 毫秒内收到过真正的摇杆帧。
     *
     * 为什么不能用 connected：RC Pro 的快照模式下 connected 在每个连接周期里
     * 只有几十毫秒是 true，其余时间都是 false —— 用它判活会让整条输入链
     * （混控采样 / 原生轴注入 / 旁键映射）几乎永远不执行，表现就是
     * 「六轴全 -100% 且怎么动都不动」。改成按"最近一帧的时间"判活。
     *
     * 时间源选择（2026-11-03，Lead 指定必须写明）：
     *   用 SystemClock.elapsedRealtime()，**不用** currentTimeMillis()，也**不用** uptimeMillis()：
     *   · currentTimeMillis 会被系统对时/NTP 跳变（挂机几小时后可能一次跳几秒到几分钟），
     *     一旦向前跳，`now - last` 变成大正数 → 误判"源已死" → 回到假 -100%；
     *     一旦向后跳，`now - last` 变成负数 → 负数恒小于 ms → 永远"新鲜" → 断线也看不出来。
     *   · uptimeMillis 在深度休眠时停走：设备锁屏放一夜，醒来后 uptime 只走了几分钟而
     *     elapsedRealtime 走了几小时，用 uptime 会让"真实已经过去很久"看起来像刚收到帧。
     *   · elapsedRealtime 含休眠时间且单调，是唯一同时满足"不被对时影响"和"不因休眠走慢"的时钟。
     *
     * 判据写法带下溢保护：`d >= 0 && d < ms`。
     * 即使两个时间源被意外混用（例如别人把 lastStickElapsed 写成 currentTimeMillis），
     * 负差值也只会判"不新鲜"，而不会像 `d < ms` 那样恒为 true 把死源当活源。
     */
    public boolean stickFresh(long ms) {
        long last = lastStickElapsed;
        if (last <= 0) return false;
        long d = android.os.SystemClock.elapsedRealtime() - last;
        return d >= 0 && d < ms;
    }

    /** 诊断用：当前是否处于快照模式、快照重连计数。 */
    public String snapInfo() {
        return "snap=" + snapMode + " burst=" + snapBurst + " live<" + SNAP_LIVE_MS + "ms";
    }

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
