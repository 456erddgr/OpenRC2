package com.rc2.etx.diag;

import com.rc2.etx.radio.ChannelConfig;
import com.rc2.etx.radio.CrsfEncoder;
import com.rc2.etx.radio.InputState;
import com.rc2.etx.radio.Mixer;

import java.util.Random;

/**
 * 无 UI 依赖的自检。真机上触摸注入被封死，所以"功能是否真的对"必须靠这些断言回答。
 * 结果同时写日志、写文件、显示在诊断页。
 */
public final class SelfTest {

    private final StringBuilder sb = new StringBuilder();
    private int pass;
    private int fail;

    private SelfTest() {
    }

    public static String run() {
        SelfTest t = new SelfTest();
        t.crc();
        t.frameStructure();
        t.roundTrip();
        t.independence();
        t.mixerMath();
        t.rateLoop();
        t.summary();
        return t.sb.toString();
    }

    private void check(String name, boolean ok, String info) {
        if (ok) {
            pass++;
            sb.append("PASS  ").append(name);
        } else {
            fail++;
            sb.append("FAIL  ").append(name);
        }
        if (info != null && info.length() > 0) sb.append("  (").append(info).append(')');
        sb.append('\n');
    }

    private void crc() {
        byte[] z = new byte[]{0x16, 0, 0};
        int c = CrsfEncoder.crc8(z, 0, 3);
        check("crc8 非零且范围合法", c >= 0 && c <= 255 && c != 0, "crc=" + c);
        byte[] z2 = new byte[]{0x16, 1, 0};
        check("crc8 对单比特变化敏感", CrsfEncoder.crc8(z2, 0, 3) != c, "");
        check("crc8 覆盖区间一致", CrsfEncoder.crc8(new byte[]{9, 0x16, 0x22}, 1, 2)
                == CrsfEncoder.crc8(new byte[]{0x16, 0x22}, 0, 2), "");
    }

    private void frameStructure() {
        int[] us = new int[16];
        for (int i = 0; i < 16; i++) us[i] = CrsfEncoder.US_MID;
        byte[] f = new byte[CrsfEncoder.FRAME_LEN];
        CrsfEncoder.pack(us, f);
        check("帧长 26", f.length == 26, "");
        check("地址 0xC8", (f[0] & 0xFF) == 0xC8, "");
        check("长度 0x18", (f[1] & 0xFF) == 0x18, "");
        check("类型 0x16", (f[2] & 0xFF) == 0x16, "");
        check("CRC 自洽", (f[25] & 0xFF) == CrsfEncoder.crc8(f, 2, 23), "");
        // 16 通道全为 992 时，11bit 边界上应出现 88bit(=11字节) 的周期
        boolean period = true;
        for (int k = 0; k < 11; k++) {
            if (f[3 + k] != f[3 + 11 + k]) period = false;
        }
        check("11bit 打包对齐(8 通道 11 字节周期)", period, "");
        check("首字节 = 992&0xFF = 0xE0", (f[3] & 0xFF) == 0xE0, "got 0x"
                + Integer.toHexString(f[3] & 0xFF));
    }

    private void roundTrip() {
        Random r = new Random(20261031L);
        boolean ok = true;
        String bad = "";
        for (int n = 0; n < 200; n++) {
            int[] in = new int[16];
            for (int i = 0; i < 16; i++) {
                in[i] = CrsfEncoder.US_MIN + r.nextInt(CrsfEncoder.US_MAX - CrsfEncoder.US_MIN + 1);
            }
            byte[] f = new byte[CrsfEncoder.FRAME_LEN];
            CrsfEncoder.pack(in, f);
            int[] out = new int[16];
            if (!CrsfEncoder.unpack(f, out)) {
                ok = false;
                bad = "unpack 失败";
                break;
            }
            for (int i = 0; i < 16; i++) {
                if (out[i] != in[i]) {
                    ok = false;
                    bad = "ch" + (i + 1) + " " + in[i] + "->" + out[i];
                    break;
                }
            }
            if (!ok) break;
        }
        check("200 组随机值 编码->解码 无损", ok, bad);
        int[] e = new int[16];
        boolean edge = true;
        for (int i = 0; i < 16; i++) e[i] = (i % 2 == 0) ? CrsfEncoder.US_MIN : CrsfEncoder.US_MAX;
        byte[] f = new byte[CrsfEncoder.FRAME_LEN];
        CrsfEncoder.pack(e, f);
        int[] o = new int[16];
        CrsfEncoder.unpack(f, o);
        for (int i = 0; i < 16; i++) if (o[i] != e[i]) edge = false;
        check("端值 172/1811 往返", edge, "");
    }

    private void independence() {
        InputState in = new InputState();
        Mixer m = new Mixer();
        m.setConfig(Mixer.defaultConfig());
        in.reset();
        float[] a = m.compute(in, true).clone();
        in.touch(2, 1f); // 右摇杆 X = 横滚
        float[] b = m.compute(in, true).clone();
        boolean onlyCh1 = Math.abs(b[0] - a[0]) > 0.5f;
        boolean others = true;
        for (int i = 1; i < Mixer.CH_COUNT; i++) if (Math.abs(b[i] - a[i]) > 1e-6) others = false;
        check("移动横滚只影响 CH1", onlyCh1 && others, "");
        in.touch(1, 1f); // 左摇杆 Y = 油门（reset() 已置 -1，这里必须换到另一端）
        float[] c = m.compute(in, true).clone();
        check("油门只影响 CH3", Math.abs(c[2] - b[2]) > 0.5f && Math.abs(c[0] - b[0]) < 1e-6, "");
    }

    private void mixerMath() {
        ChannelConfig cc = new ChannelConfig(ChannelConfig.SRC_AIL, "t");
        cc.expo = 0f;
        check("expo=0 线性", Math.abs(cc.mix(0.5f) - 0.5f) < 1e-4, "");
        cc.expo = 1f;
        float e = ChannelConfig.applyExpo(0.5f, 1f);
        check("expo=1 得三次曲线", Math.abs(e - 0.125f) < 1e-4, "got " + e);
        check("expo 不改端点", Math.abs(ChannelConfig.applyExpo(1f, 1f) - 1f) < 1e-6, "");
        cc.expo = 0f;
        cc.rate = 0.5f;
        check("rate 缩放", Math.abs(cc.mix(1f) - 0.5f) < 1e-4, "");
        cc.rate = 1f;
        cc.reverse = true;
        check("反向", Math.abs(cc.mix(0.4f) + 0.4f) < 1e-4, "");
        cc.reverse = false;
        cc.offset = 0.8f;
        check("偏移后限幅 ±1", cc.mix(0.5f) <= 1f && Math.abs(cc.mix(0.1f) - 0.9f) < 1e-4, "");
        check("单位->us 端点", CrsfEncoder.usFromUnit(-1f) == CrsfEncoder.US_MIN
                && CrsfEncoder.usFromUnit(1f) == CrsfEncoder.US_MAX, "");
        check("单位->us 中心", Math.abs(CrsfEncoder.usFromUnit(0f) - CrsfEncoder.US_MID) <= 1, "");
        check("us->单位 往返", Math.abs(CrsfEncoder.unitFromUs(CrsfEncoder.usFromUnit(0.37f)) - 0.37f) < 0.005, "");
    }

    private void rateLoop() {
        // 纯算力测试：估算 100Hz 下每帧的编码开销，确认在 174MB 内存的机器上也毫无压力
        int[] us = new int[16];
        for (int i = 0; i < 16; i++) us[i] = CrsfEncoder.US_MID + i * 7;
        byte[] f = new byte[CrsfEncoder.FRAME_LEN];
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) CrsfEncoder.pack(us, f);
        long dt = System.nanoTime() - t0;
        float usPerFrame = dt / 1000f / 1000f;
        check("单帧编码 < 200us", usPerFrame < 200f, String.format("%.1f us/帧", usPerFrame));
    }

    private void summary() {
        sb.append('\n');
        sb.append(fail == 0 ? "结论：全部通过 " : "结论：有失败项 ");
        sb.append('(').append(pass).append(" 通过 / ").append(fail).append(" 失败)\n");
    }
}
