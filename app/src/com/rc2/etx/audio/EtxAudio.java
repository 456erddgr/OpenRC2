package com.rc2.etx.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/** EdgeTX 声音出口：原生侧按 32000Hz/单声道/S16 推 PCM，这里写进 AudioTrack。 */
public final class EtxAudio {
    private static final String TAG = "ETX-Audio";
    private static final int RATE = 32000;
    private static AudioTrack track;
    private static int wrote;

    private EtxAudio() {}

    static synchronized AudioTrack ensure() {
        if (track != null) return track;
        int buf = AudioTrack.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (buf < RATE / 2) buf = RATE / 2;              // ~0.5s 缓冲，避免欠载卡顿
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            Log.i(TAG, "AudioTrack ok buf=" + buf);
        } catch (Throwable t) {
            Log.e(TAG, "AudioTrack 创建失败: " + t);
            track = null;
        }
        return track;
    }

    public static void write(short[] data, int count) {
        if (data == null || count <= 0) return;
        AudioTrack t = ensure();
        if (t == null) return;
        try {
            int n = t.write(data, 0, Math.min(count, data.length));
            wrote += Math.max(0, n);
            if ((wrote & 0x3FFF) < n) Log.i(TAG, "wrote=" + wrote);
        } catch (Throwable e) {
            Log.e(TAG, "write 失败: " + e);
        }
    }

    public static synchronized void release() {
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
        track = null;
    }
}
