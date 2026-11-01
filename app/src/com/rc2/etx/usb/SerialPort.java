package com.rc2.etx.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/** 一层薄薄的 USB 串口抽象。 */
public interface SerialPort {

    boolean open(UsbManager um, UsbDevice dev);

    boolean setBaud(int baud);

    int write(byte[] buf, int len);

    int read(byte[] buf, int timeoutMs);

    void close();

    String driverName();

    /** 逐条控制传输的结果，排障用。 */
    String trace();
}
