# CRSF over UDP 端到端验证（2026-10-31 23:07 实测通过）

## 方法
- PC（Windows，192.168.1.14）跑一个**独立的** Python UDP 监听器（不复用我自己的代码，独立实现 CRC8 与 11bit 解包，用来交叉验证）。
- RC2（192.168.1.54，WiFi）用 App 的 UDP 输出指向 `192.168.1.14:8888`：
  `am start -n com.rc2.etx/.MainActivity --es action sink --es kind udp --es target 192.168.1.14:8888`
- 监听 8 秒。

## 结果（原始输出）
```
packets=482 bad=0 from=192.168.1.54
hex=c8 18 16 e0 03 df 6b c1 c7 0a 56 b0 82 15 e0 03 1f f8 c0 07 3e f0 81 0f 7c 4e
crc_ok=True (calc=78 frame=78)
channels=992 992 1455 992 172 172 172 172 992 992 992 992 992 992 992 992
```
- 8 秒 482 包 ≈ **60 包/秒**（这是 UDP 发送节流后的实际速率，控制环本身 97Hz）
- **bad=0**：每一包长度都是 26、首字节 0xC8/0x18/0x16 全对
- **CRC 交叉验证通过**：PC 侧独立用 poly=0xD5 MSB-first 算 `bytes[2..24]` 的 CRC8 = 78，与帧内 `bytes[25]` 完全一致
- **11bit 解包正确**：16 通道全部解出，CH3=1455 是当时油门被触摸拉到的位置（非 992），其余符合预期

## 这条验证的意义
1. App 侧 **CRSF 编码不是"自己觉得自己对"**——被一份独立实现逐字节确认了。
2. `RC2 --WiFi/UDP--> 任意桥接板` 这条路**今天就能用**，不依赖还没验证的 USB host 模式。
3. 桥接板只需要做一件事：**把 26 字节原样透传到 UART**，不需要懂 CRSF 语义。

## 复现
```bash
# PC 侧（任意 python3）
python udp_listen.py 8888 8      # 脚本见 docs/scripts/udp_listen.py
# RC2 侧
adb -s 6UZBM6L02104CA shell "am start -n com.rc2.etx/.MainActivity \
  --es action sink --es kind udp --es target <PC_IP>:8888"
```
