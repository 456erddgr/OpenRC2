package com.rc2.etx.radio;

/**
 * CRSF (Crossfire / ELRS) RC 帧编解码。
 *
 * 帧结构（26 字节）：
 *   [0]     0xC8      地址（FLIGHT_CONTROLLER）
 *   [1]     0x18      长度 = 1(type) + 22(payload) + 1(crc)
 *   [2]     0x16      CRSF_FRAMETYPE_RC_CHANNELS_PACKED
 *   [3..24]           16 通道 × 11bit = 176bit = 22 字节，小端位序
 *   [25]              CRC8/DVB-S2（多项式 0xD5，覆盖 [2..24]）
 *
 * 通道值即微秒值本身：172=988us .. 992=中心 .. 1811=2012us（11bit，0..2047 有效）。
 */
public final class CrsfEncoder {

    public static final int FRAME_LEN = 26;
    public static final int ADDR_FC = 0xC8;
    public static final int TYPE_RC = 0x16;
    public static final int US_MIN = 172;
    public static final int US_MAX = 1811;
    public static final int US_MID = 992;

    private CrsfEncoder() {
    }

    /** 单位值 [-1,+1] -> CRSF 微秒值 [172,1811]。 */
    public static int usFromUnit(float u) {
        if (u < -1f) u = -1f;
        if (u > 1f) u = 1f;
        int us = US_MIN + Math.round((u + 1f) * 0.5f * (US_MAX - US_MIN));
        if (us < US_MIN) us = US_MIN;
        if (us > US_MAX) us = US_MAX;
        return us;
    }

    /** CRSF 微秒值 -> 单位值。 */
    public static float unitFromUs(int us) {
        float u = ((us - US_MIN) * 2f) / (float) (US_MAX - US_MIN) - 1f;
        if (u < -1f) u = -1f;
        if (u > 1f) u = 1f;
        return u;
    }

    /** CRC8 DVB-S2：多项式 0xD5，初值 0，MSB first。 */
    public static int crc8(byte[] d, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (d[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ 0xD5) & 0xFF;
                } else {
                    crc = (crc << 1) & 0xFF;
                }
            }
        }
        return crc;
    }

    /** 把 16 个 11bit 通道值打进 out[3..24]，并写头与 CRC。 */
    public static void pack(int[] us16, byte[] out) {
        out[0] = (byte) ADDR_FC;
        out[1] = (byte) 0x18;
        out[2] = (byte) TYPE_RC;
        for (int i = 3; i < 25; i++) {
            out[i] = 0;
        }
        for (int i = 0; i < 16; i++) {
            int v = us16[i] & 0x7FF;
            for (int b = 0; b < 11; b++) {
                if (((v >> b) & 1) != 0) {
                    int bitPos = i * 11 + b;
                    out[3 + (bitPos >> 3)] |= (byte) (1 << (bitPos & 7));
                }
            }
        }
        out[25] = (byte) crc8(out, 2, 23);
    }

    /** 解出 16 通道；帧非法或 CRC 不符返回 false。 */
    public static boolean unpack(byte[] f, int[] usOut) {
        if (f == null || f.length < FRAME_LEN) return false;
        if ((f[0] & 0xFF) != ADDR_FC) return false;
        if ((f[1] & 0xFF) != 0x18) return false;
        if ((f[2] & 0xFF) != TYPE_RC) return false;
        if ((f[25] & 0xFF) != crc8(f, 2, 23)) return false;
        for (int i = 0; i < 16; i++) {
            int v = 0;
            for (int b = 0; b < 11; b++) {
                int bitPos = i * 11 + b;
                if (((f[3 + (bitPos >> 3)] >> (bitPos & 7)) & 1) != 0) {
                    v |= (1 << b);
                }
            }
            usOut[i] = v;
        }
        return true;
    }

    public static String hex(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder(d.length * 3);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v)).append(' ');
        }
        return sb.toString().trim();
    }

    /** 复用缓冲的编码器，避免 100Hz 上产生垃圾。 */
    public static final class Encoder {
        private final byte[] buf = new byte[FRAME_LEN];

        public byte[] encode(int[] us16) {
            pack(us16, buf);
            return buf;
        }
    }
}
