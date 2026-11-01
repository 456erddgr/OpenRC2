# -*- coding: utf-8 -*-
"""监听 RC2 EdgeTX APK 的 UDP 输出，按 CRSF 帧解码 16 通道。
用法: python udp_listen.py [port] [seconds]   (默认 8888, 10 秒)
"""
import socket
import sys
import time

US_MIN, US_MAX, US_MID = 172, 1811, 992


def crc8(buf):
    crc = 0
    for b in buf:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0xD5) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def decode(frame):
    if len(frame) != 26 or frame[0] != 0xC8 or frame[2] != 0x16:
        return None
    if crc8(frame[2:25]) != frame[25]:
        return None
    bits = 0
    nbits = 0
    us = []
    for b in frame[3:25]:
        bits |= b << nbits
        nbits += 8
        while nbits >= 11 and len(us) < 16:
            us.append(bits & 0x7FF)
            bits >>= 11
            nbits -= 11
    return us


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
    secs = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.settimeout(0.5)
    print("listening UDP :%d for %.0fs" % (port, secs))
    t0 = time.time()
    total = bad = 0
    last = None
    last_report = t0
    while time.time() - t0 < secs:
        try:
            data, addr = s.recvfrom(2048)
        except socket.timeout:
            continue
        total += 1
        us = decode(data)
        if us is None:
            bad += 1
        else:
            last = (us, addr)
            for i, v in enumerate(us):
                if not (US_MIN <= v <= US_MAX):
                    bad += 1
                    break
        now = time.time()
        if now - last_report >= 1.0:
            last_report = now
            hz = total / max(0.001, now - t0)
            if last:
                us, addr = last
                pct = [int(round((v - US_MID) / ((US_MAX - US_MID) if v >= US_MID else (US_MID - US_MIN)) * 100))
                       for v in us]
                print("pkts=%d bad=%d %.1fHz from %s" % (total, bad, hz, addr[0]))
                print("  us =", us)
                print("  pct=", pct)
            else:
                print("pkts=%d bad=%d %.1fHz (还没解出合法帧)" % (total, bad, hz))
    print("---- 汇总 ----")
    print("总包 %d, 坏包 %d, 平均 %.2f Hz" % (total, bad, total / max(0.001, time.time() - t0)))


if __name__ == "__main__":
    main()
