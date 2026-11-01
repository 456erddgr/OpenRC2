package com.rc2.etx.radio;

/** 空输出：只跑控制环、不出帧（用于界面调试）。 */
public final class NullSink implements OutputSink {
    private volatile long frames;

    @Override
    public String name() {
        return "无输出";
    }

    @Override
    public boolean open(String arg) {
        return true;
    }

    @Override
    public void send(byte[] frame) {
        frames++;
    }

    @Override
    public void close() {
    }

    @Override
    public String status() {
        return "空输出";
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public long frames() {
        return frames;
    }

    @Override
    public long errors() {
        return 0;
    }

    @Override
    public String detail() {
        return "帧在内部生成但不外发";
    }
}
