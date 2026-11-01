# 11 · ELRS 出口：最终结论与采购/接线

> 本文替代此前所有关于"用机身射频发 ELRS""把接收机改成高频头""USB 四芯直接焊 TX/RX"的设想。
> 证据分三部分：①机身射频为何不可用（§1）②接收机改 TX 为何不可行（§2，含字节级取证）③唯一可行路径与 BOM（§3）。

## 1. 机身自带射频为什么做不了 ELRS
- RC2 内部无线是 DJI 私有链路：`/dev/bus/usb/002/006`（`2ca3:1020` "C5"）+ `dji_link`(pid 1216) + `dji_sdrs_agent`，
  走的是 DJI 的 SDR（O3/O4 系）私有协议；无寄存器手册、无开源驱动、无固件入口。
- ELRS 是 SX1280/LR1121 上的 LoRa 调制 + FHSS 跳频，射频前端是 DJI 定制 ASIC，不是可重编程的 SX1280。
- **结论：不可行，且不是"难"，是"没有接口"。**

## 2. 接收机改成高频头（TX）——不可行（字节级证据）
我们自己有三个 RX 固件 + 一个 TX 固件，全部来自用户提供的 V15 工厂 SD（`E:\我的项目\V15-factory\`）：

| 文件 | SoC | target 串 | 射频 | IDF |
| --- | --- | --- | --- | --- |
| `Elrs\V15Elrs4.1.0-fcc-firmware.bin` (1,697,376 B) | ESP32 (`chip_id=0x0000`) | `UNIFIED_ESP32_LR1121_TX` | **LR1121 双频** | v4.4.7 |
| `ElrsRx\HR7E-4.1.0-D4.bin` (1,185,872 B) | ESP32 | `UNIFIED_ESP32_2400_RX` | **SX128X** | v4.4.7 |
| `ElrsRx\HR8E-ELRS-D4-3.5.3-firmware.bin` | ESP32 | 同上 | SX128X | 3.5.3 |
| `HR8EG-RX\HR8EG4.0-D4-GYRO1.4-firmware.bin` | ESP32 | 同上 | SX128X | 4.0 |
| `ElrsRx\HELLORADIO8285_HR1EP.bin` | **ESP8285**（入口 `0x4010F480`） | — | SX128X | ESP8266 core |

独立复核（我自己跑的，不引用队友结论）：
```
md5 Elrs/bootloader.bin   = 097cef81c07c9d9963889f5dbbce8a09
md5 ElrsRx/bootloader.bin = 097cef81c07c9d9963889f5dbbce8a09   ← 完全相同
TX 固件里 "LR1121" 命中 10 次，"SX128" 命中 0 次
HR7E 固件里 "SX128" 命中 1 次，"LR1121" 命中 0 次
```
三条件判定：
- SoC 相同 ✅（bootloader md5 一致、入口 `0x400805E4`、分区表同 md5、同 4MB flash）
- **PA/LNA 引脚 ❌**：ExpressLRS 把整板引脚表以 JSON 编进固件，两边全错位
  （MISO 33/35、SCK 19/33、NSS 27/26、RST 23/25、BUSY 36/37、DIO1 37/38；TX 用 `power_apc2:26`，RX 用 `power_txen:18` + `ant_ctrl:23`）
- bootloader 入口 ✅

**所以：能刷进去、能启动、但射频起不来、没有功率 → 等于砖。别刷。**

顺带纠正一个流传说法：ESP32/ESP8285 的 ROM 下载模式在 mask ROM 里，"刷错就废"通常不成立；
真正的不可逆点是板子把 UART 焊死或 GPIO0 焊死。刷之前先 `read_flash 0 0x400000` 整片备份。

## 3. 唯一可行路径（按性价比排序）

### 方案 A（推荐）：买现成 ELRS TX 模块 + ESP32 桥
- RC2（UDP，已实测 75Hz）→ WiFi → ESP32 桥 → CRSF UART @420000 → ELRS TX 模块 → 2.4G → 你的 ELRS 接收机
- 或者 RC2（USB host）→ USB CDC → ESP32-S3 桥 → CRSF → TX 模块（需要 USB 主机模式打通，见 lab/03）

BOM（参考价）：
| 件 | 价 | 说明 |
| --- | --- | --- |
| ELRS TX 模块（2.4G，如 Ranger/Nano 类） | ¥150–250 | 认准带 CRSF 串口输入的 |
| ESP32 开发板（经典 ESP32 或 S3） | ¥25–60 | S3 才有原生 USB CDC |
| 1S 锂电池 + 充电板 | ¥20 | 给 TX 模块供电（模块 5V/几百 mA） |
| 杜邦线/洞洞板 | ¥10 | — |

### 方案 B：自编 DIY 2.4G TX（省钱但要动手）
- ESP32 + SX1280 模块，用 ExpressLRS 官方 DIY 目标自己编 TX 固件（¥60–120）。
- 缺点：无 PA，功率只有几十 mW；需要自己焊 SPI 六线 + 电源。

### 方案 C：只用接收机做"有线回传"（不发射）
- 仅用于本机测试：接收机通过 UART 把 CH1-16 回读，验证我们的编码对不对。**不能替代发射。**

### 不推荐
- 拿 HR 系列接收机刷 TX 固件（§2 已证不可行）。
- USB 四芯直接焊 TX/RX（D+/D- 是 480Mbps 差分对，不是 3.3V UART；必须经 CH340/CP2102 或原生 USB-CDC 的 MCU）。

## 4. 我们现在已经有的东西
- APK 侧：CRSF 16 通道编码（自检 24/24，单帧 10.3µs）、UDP 输出（真机 454 包 0 坏包 75.6Hz）、
  USB CDC 输出（代码就绪，等 USB host 打通）、摇杆 10Hz→100Hz 插值。
- 桥固件：`lab/firmware/esp32s3-crsf-bridge/`（130 行，`0xC8` 找头 + len 校验 + CRC8(0xD5) 才转发，
  坏帧丢 1 字节滑动续找；模块没插时丢帧不阻塞 CDC）。**未过编译器验证，等硬件到位再联调。**
