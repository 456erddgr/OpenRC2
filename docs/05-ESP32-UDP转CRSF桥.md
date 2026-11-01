# ESP32 桥：RC2 (WiFi/UDP) → CRSF UART → ELRS TX 模块

## 为什么是这条路
- `04-UDP端到端验证.md` 已经证明 RC2 发出的 26 字节 CRSF 帧**逐字节正确**（独立 CRC 交叉验证）。
- 桥接板只要「收 UDP → 透传 UART」，**完全不需要理解 CRSF 语义**，也就不可能改坏帧。
- 不依赖 RC2 的 USB host 模式（那条还没验证），今天就能飞。

## 硬件（约 40–60 元）
| 件 | 说明 |
|---|---|
| ESP32 开发板 | ESP32-WROOM-32 / NodeMCU-32S 任意一块，需带 WiFi |
| ELRS TX 模块 | 必须有 **CRSF 输入**（如 BetaFPV Micro TX、Happymodel ES24TX Slim Pro、RadioMaster Ranger Nano） |
| 供电 | 模块 5V 峰值 500mA+，**不要**从 ESP32 的 3.3V 取；用 USB 5V 或机载 BEC |
| 线 | 4 根：5V / GND / ESP32_TX→模块RX / 模块TX→ESP32_RX |

## 接线（模块侧 "CRSF/串口" 口，通常是 5pin 排针：5V GND TX RX ...）
```
ELRS 模块 5V   ←  5V（独立供电，和 ESP32 共地）
ELRS 模块 GND  ←  GND
ELRS 模块 RX   ←  ESP32 GPIO17（UART2 TX）   ★ 只接这一根也能出信号（单向遥控）
ELRS 模块 TX   →  ESP32 GPIO16（UART2 RX）   ← 接上才能收到遥测/Link stats
```
- 3.3V 电平：ESP32 与 ELRS 模块都是 3.3V，**不用电平转换**。
- **波特率**：多数 ELRS TX 模块 CRSF 口是 **420000**；老固件或某些模块是 400000 / 115200。三种都试，接上后如果模块屏幕/指示灯显示 "no serial" 就是波特率不对。

## 网络拓扑（两种都支持）
1. **同路由（推荐）**：RC2 和 ESP32 都连家里 2.4G WiFi，RC2 用 DHCP 地址（现在是 192.168.1.54），固件里填 RC2 的 IP 或干脆广播。
2. **ESP32 自建 AP**：RC2 连 ESP32 的热点（SSID `RC2-ELRS`，密码 `rc2etx1234`），RC2 侧 UDP 目标填 `192.168.4.1:8888`。适合外场没路由。

## 固件行为（`bridge/esp32_crsf_bridge/esp32_crsf_bridge.ino`）
- 启动后连 WiFi（STA 模式，凭据写在顶部常量），失败自动退回 AP 模式。
- UDP 监听 **8888**，只接收**恰好 26 字节、首字节 0xC8、第 3 字节 0x16** 的帧，校验 CRC8（poly 0xD5），通过才转发到 UART2。
- **失效保护**：连续 500ms 没收到合法帧 → 把所有通道写成 failsafe 值（默认 CH3 油门 = 172，其余 = 992），并拉低一个状态 LED。这条必须有，否则 RC2 一断 WiFi 飞机就保持最后杆位。
- **回传（可选）**：把 UART2 收到的 CRSF 帧（类型 0x14 LinkStatistics 等）原样 UDP 发回 RC2 的 IP:8889，方便 App 以后显示 LQ/RSSI。
- 状态 LED：慢闪=等 WiFi，常亮=收到帧，快闪=UART 有数据但 UDP 断。

## 烧写与验证
```bash
# Arduino IDE：开发板选 "ESP32 Dev Module"，分区默认，波特率 921600
# 或 arduino-cli：
arduino-cli core install esp32:esp32
arduino-cli compile -b esp32:esp32:esp32 bridge/esp32_crsf_bridge
arduino-cli upload -p COM5 -b esp32:esp32:esp32 bridge/esp32_crsf_bridge
```
验证顺序（**先不接 ELRS 模块，避免误触发**）：
1. 串口监视器 115200 看 ESP32 打印的 IP。
2. RC2 输出改到该 IP:8888，看 ESP32 打印 `rx frame ch3=...` 递增、CRC 错计数为 0。
3. 再接 ELRS 模块，模块上电应显示频点/LQ，摇 RC2 摇杆看模块的 channel monitor 跟着动。
4. 最后接接收机对频，飞机上电前先量舵机方向。

## 延迟与适用性（诚实说）
- RC2 控制环 100Hz（10ms）+ UDP 发送节流（实测 ~60Hz）+ WiFi 5–15ms + ELRS 空口 2–4ms ⇒ **端到端约 20–35ms**。
- 穿越机日常飞行够用，**竞速/花飞不建议**；要低延迟就得走 USB-CDC 有线（等 USB host 实验结论）或换 RC2 直插 ELRS 模块的硬件方案。

## 已知坑
- ESP32 的 WiFi 省电模式会带来 100ms 级抖动 → 固件里已 `WiFi.setSleep(false)`。
- UDP 丢包不会重传：丢 1 帧 = 这 10ms 用上一帧值，ELRS 侧本来就有自己的重传/合并，实际影响很小。
- 别用 ESP8266：单核 + 中断延迟大，420000 波特率下容易丢字节。
