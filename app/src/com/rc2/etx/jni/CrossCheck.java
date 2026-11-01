package com.rc2.etx.jni;

import android.content.Context;

import java.io.File;
import java.util.Locale;

import com.rc2.etx.radio.CrsfEncoder;
import com.rc2.etx.radio.Radio;

/** K5 交叉校验：同一组输入，比较 Java 混控与原生核心（EdgeTX 真源码）输出的 16 通道 us。
 *  目的不是"完全相等"（两边模型来源不同：Java 用 app 内的 ChannelConfig 表，原生读 model1.yml），
 *  而是回答三件事：
 *   1) 原生核心的通道输出是否随输入单调、方向是否符合 AETR；
 *   2) 两边在同一轴满行程时的跨度是否同量级（172..1811 口径）；
 *   3) 原生核心在满行程端点是否夹住（不越界、不抖）。
 *
 *  重要：必须走**7 轴**通道（setInputs(nAxes=7)），与生产路径（radio.nativeAxes7=true）一致。
 *  4 轴 legacy 路径在 so 内部还会再做一次 LH/LV/RV/RH→RH/RV/LV/LH 置换，
 *  而 synthNativeAxes 输出的已经是 MCU 物理序，两者叠加会二次互换（曾导致跨度恒为 0）。
 */
public final class CrossCheck {

    private CrossCheck() {}

    /** App 输入轴 -> 原生通道下标：lx(RUD)->ch4(LH) ly(THR)->ch3(LV) rx(AIL)->ch1(RH) ry(ELE)->ch2(RV) */
    private static final int[] CH_OF = {3, 2, 0, 1};

    private static final int[][] VEC = {
        // lx, ly, rx, ry  (单位：-1..1；ly=-1 是油门最低)
        {0, -1, 0, 0}, {0, 1, 0, 0},
        {-1, 0, 0, 0}, {1, 0, 0, 0},
        {0, 0, -1, 0}, {0, 0, 1, 0},
        {0, 0, 0, -1}, {0, 0, 0, 1},
        {0, -1, 0, 0}
    };

    public static String run(Context ctx, Radio r) {
        StringBuilder sb = new StringBuilder();
        sb.append("== K5 交叉校验（Java 混控 vs 原生核心）==\n");
        if (!EtxNative.available()) {
            sb.append("原生核心不可用: ").append(EtxNative.error()).append('\n');
            return sb.toString();
        }
        int rc = EtxNative.init(new File(ctx.getExternalFilesDir(null), "sd").getAbsolutePath());
        sb.append("init_rc=").append(rc).append(" version=").append(EtxNative.version()).append('\n');
        if (rc != 0) return sb.toString();

        int nw = EtxNative.screenW(), nh = EtxNative.screenH();
        int[] argb = new int[nw * nh];
        int[] nat = new int[16];
        int[] jav = new int[16];
        int[] axes7 = new int[7];
        int[] sw = new int[4];
        int[] kn = new int[2];

        // 100Hz 控制环会把真实摇杆值写回 input.*，把测试输入冲掉；扫描期间冻结 live 注入。
        r.suppressLiveInput = true;
        try {
            sb.append("vec  lx ly rx ry | nat1..4 | jav1..4\n");
            int worstSpan = 0;
            boolean monotonicOk = true;
            for (int i = 0; i < VEC.length; i++) {
                float lx = VEC[i][0], ly = VEC[i][1], rx = VEC[i][2], ry = VEC[i][3];
                r.input.lx = lx; r.input.ly = ly; r.input.rx = rx; r.input.ry = ry;
                r.synthNativeAxes7(axes7);
                EtxNative.setInputs(axes7, 7, sw, 4, kn, 0);
                EtxNative.tick(33);
                EtxNative.render(argb, nw, nh);
                EtxNative.tick(33);
                EtxNative.channelsUs(nat);

                float[] mch = r.mixer().compute(r.input, r.isMixerOn());
                for (int c = 0; c < 16; c++) jav[c] = CrsfEncoder.usFromUnit(mch[c]);

                sb.append(String.format(Locale.US, "%2d %+d %+d %+d %+d |", i, (int) lx, (int) ly, (int) rx, (int) ry));
                for (int c = 0; c < 4; c++) sb.append(' ').append(nat[c]);
                sb.append(" |");
                for (int c = 0; c < 4; c++) sb.append(' ').append(jav[c]);
                sb.append('\n');

                int span = Math.abs(nat[0] - nat[1]);
                if (span > worstSpan) worstSpan = span;
                for (int c = 0; c < 4; c++) {
                    if (nat[c] < 172 || nat[c] > 1811) monotonicOk = false;
                }
            }
            sb.append("原生 4 通道幅度上界(端点差)=").append(worstSpan)
              .append("  端点未越界=").append(monotonicOk).append('\n');

            sb.append("轴扫描(每个 App 输入轴 -> 原生对应通道):\n");
            for (int a = 0; a < 4; a++) {
                int mn = 99999, mx = -99999;
                for (int k = 0; k <= 4; k++) {
                    float v = -1f + 0.5f * k;
                    r.input.lx = 0f; r.input.ly = 0f; r.input.rx = 0f; r.input.ry = 0f;
                    if (a == 0) r.input.lx = v; else if (a == 1) r.input.ly = v;
                    else if (a == 2) r.input.rx = v; else r.input.ry = v;
                    r.synthNativeAxes7(axes7);
                    EtxNative.setInputs(axes7, 7, sw, 4, kn, 0);
                    EtxNative.tick(33);
                    EtxNative.render(argb, nw, nh);
                    EtxNative.tick(33);
                    EtxNative.channelsUs(nat);
                    int c = CH_OF[a];
                    if (nat[c] < mn) mn = nat[c];
                    if (nat[c] > mx) mx = nat[c];
                }
                String[] nm = {"lx(RUD)->ch4", "ly(THR)->ch3", "rx(AIL)->ch1", "ry(ELE)->ch2"};
                sb.append(String.format(Locale.US, "  %-14s min=%d max=%d 跨度=%d\n", nm[a], mn, mx, mx - mn));
            }
        } finally {
            r.suppressLiveInput = false;
        }
        EtxNative.shutdown();
        return sb.toString();
    }
}
