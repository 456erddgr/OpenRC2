package com.rc2.etx.radio;

/** 输出通道：CRSF 帧最终去哪（UDP / USB 串口 / 文件 / 空）。 */
public interface OutputSink {

    String name();

    /** 打开；arg 的语义由实现决定（UDP: "host:port"，USB: 设备 id，文件: 文件名）。 */
    boolean open(String arg);

    void send(byte[] frame);

    void close();

    String status();

    boolean isOpen();

    long frames();

    long errors();

    /** 让人看得懂的细节，用于设置页。 */
    String detail();
}
