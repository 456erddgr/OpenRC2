# RC2 EdgeTX Bridge —— 工程规格（v0.1）

生成：2026-10-31 22:50 · Lead
目标设备：DJI RC2（rc331 / 高通 lahaina / Android 11 / arm64 / 已 root / BL 已解）

---

## 0. 真机侦察结论（全部实测 2026-10-31 22:41–22:50）

| 结论 | 证据 | 影响 |
|---|---|---|
| **摇杆不是 Android 输入设备** | `/proc/bus/input/devices` 只有 3 个：`qpnp_pon`、`gpio-keys`、`himax-touchscreen`；`getevent -pl` 无任何 ABS_X/Y/RX/RY | 没有 `/dev/input` 路可走，物理摇杆必须走 DJI 私有链路 |
| **内部有一路 USB 私有设备 "C5"** | `/sys/bus/usb/devices/2-1` = `2ca3:1020` DJI "C5"；11 个 vendor 接口（class 255 subnet 67），其中一个接口名直接叫 **"MA Channel"**；每接口一对 bulk IN/OUT，512B 包 | 摇杆/按键/链路数据几乎肯定在这条链路上（DUML） |
| **链路被 root 守护进程独占** | `dji_link`(pid 1216) 持有 `/dev/bus/usb/002/006` 4 个 fd、`/dev/ttyHS3`、`/dev/socket/fpv_sock*` | 不能旁听，只能抢占 / 改走 socket / 改走 UART |
| **`/dev/socket/fpv_sock` 是 0666 监听 socket** | `/proc/net/unix`：inode 39563 flags=00010000(listen)，dji_link fd33→39563 | 最省事切入点：任何 App 都能 connect |
| **USB-C 口硬件支持 host/device 双角色，当前 device** | `CONFIG_USB_DWC3_DUAL_ROLE=y`、`CONFIG_TYPEC_UCSI=y`、`CONFIG_USB_ROLE_SWITCH=y`、`CONFIG_TYPEC_GPIO_CONN=y`；`/sys/class/usb_role/a600000.ssusb-role-switch/role = device`；`/sys/devices/platform/soc/a600000.ssusb/mode` 存在 | root 下 `echo host > .../mode` 可能打开 OTG 主机模式 |
| **内核带 CDC-ACM / serial / ffs gadget** | `CONFIG_USB_CONFIGFS_ACM=y`、`F_SERIAL=y`、`F_FFS=y`；`/dev/usb-ffs/bulk/ep1,ep2` 被 dji_link 占用 | 设备端串口存在，但方向是 RC2 当从机 |
| **内存极紧** | MemTotal 1.79GB，MemAvailable ~174MB，zram 已用 906MB | App 必须轻：纯 Java + 自绘 View，无 AndroidX/Compose，目标 < 3MB |
| **targetSdk ≥ 30 被 DJI 审查劫持** | 交接文档 §9.2：ReviewPermissionsActivity 挡启动、input 注入失效 | Manifest 必须 `minSdkVersion=29 targetSdkVersion=29` |
| 触摸注入被封死 | `input tap` / `sendevent` 被 InputDispatcher 丢弃 | 验证只能靠 `am start --es action` 钩子 + logcat + 手指 |

### 0.1 对用户三条原始设想的判定

1. **"用机身自带高频跑 ELRS" —— 不可行。** RC2 链路是 `dji_sdrs_agent` 驱动的 DJI 私有 SDR（O3/O4 系），无寄存器手册、无开源驱动；ELRS 是 SX1280 LoRa 调制。调制方式/帧结构/跳频完全不同，射频前端是 DJI 定制 ASIC 而非可重编程 SX1280，换固件也无解。
2. **"ELRS 接收机改成发射" —— 硬件勉强可能、固件不通、不推荐。** TX 固件按 TX 目标编译（带 PA/LNA、不同引脚）；RX 板无 PA（约 10dBm vs 20–30dBm）、多数无 USB、刷错必砖。正解是买 ELRS TX 模块，RC2 只算通道发 CRSF。
3. **"USB 四芯直接焊 TX/RX" —— 电气错误。** USB D+/D- 是差分对（HS 480Mbps / FS 12Mbps），不是 3.3V UART。必须经 USB-串口桥（CH340/CP2102/FT232）或目标板自带 CDC-ACM：
   `RC2 USB-C ──[CH340/CP2102 小板]── TX/RX/GND/5V ── ELRS TX 模块 CRSF 口`

---

## 1. 架构

```
物理摇杆(DJI C5/DUML)  ─┐
屏幕虚拟摇杆(触摸)      ─┼─> InputSource ──> Mixer(16ch, expo/曲线/开关/微调)
EVDEV(预留)            ─┘                        │ 100Hz
                                                 v
                                       OutputSink  ┬─ CrsfUsbSink (USB主机 + CDC/CP210x/CH340 @420000)
                                                   ├─ CrsfUdpSink (WiFi UDP → ESP32 桥，最稳)
                                                   └─ CrsfLogSink (显示/录包，验证帧正确性)
```

- 输入/混控/输出三层解耦，物理摇杆打通后即插即用。
- 纯 Java 8 + 自绘 View；构建沿用 `rc331work/sdk/build-apk.ps1`（aapt2→javac→d8→zip→zipalign→apksigner）。
- 控制环 100Hz（HandlerThread），UI 30fps；共享状态用 AtomicIntegerArray/volatile。

## 2. 目录与分工（写作用域不重叠）

```
E:\我的项目\RC2-EdgeTX\
├─ SPEC.md          Lead
├─ app\             Lead（Manifest / res / src\com\rc2\etx）
├─ lab\             usb-lab（真机实验 + 脚本 + 结论）
├─ re\              duml-re（C5/DUML 逆向报告 + 协议草案）
├─ docs\            Lead（交付文档）
└─ build\           Lead（APK 产物）
```

## 3. 里程碑

- M1（本次必达）：APK 能装能跑；触摸双摇杆→16 通道混控→通道监视；CRSF 编码器自检通过；UDP 输出可用。
- M2：USB 主机模式 + CDC/CP210x/CH340 + 420000 波特率 CRSF。
- M3：物理摇杆（DUML）接入 + 校准/死区/反向。
- M4：模型管理、曲线、开关分配、计时器、CRSF 回传。

## 4. 硬性约束

1. Manifest 必须 `minSdkVersion=29 targetSdkVersion=29`。
2. Java 8 语法（`-source 8 -target 8`），不用 var/record/switch 表达式。
3. 不引第三方 jar、不引 AndroidX。
4. su 路径：`/debug_ramdisk/su`，失败回退 `su`、`sh`。
5. 真机上**不要** kill `dji_link`、不卸载 `dji.go.v5`、不动 `pwmchip0`（屏幕背光，unexport 黑屏）。
6. 改 USB 角色前先 `adb tcpip 5555` 确认无线 adb 可用，结束必须 `echo device` 复原。
7. 单文件超过 800 行就拆；面向用户文案用简体中文。

## 5. 工具链备忘（本机实测）

- 构建：`pwsh -NoProfile -ExecutionPolicy Bypass -File "E:\我的项目\rc331work\sdk\build-apk.ps1" -Root <项目> -Out <apk> -MinApi 29`
- SDK：`E:\我的项目\rc331work\sdk\`（aapt2 / android.jar(platform-34ext7) / r8.jar / debug.keystore 密码 rc2debug123，alias rc2debug）
- JDK21：`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
- adb：`E:\我的项目\rc2-backup\tools\platform-tools\adb.exe`；bash 调用前 `export MSYS2_ARG_CONV_EXCL='*'`（否则 /sys /dev 参数被改写成 Windows 路径）
- 设备远程执行：先写 .sh → `adb push` 到 `/data/local/tmp/` → `adb shell sh <脚本>`；不要在 adb shell 里塞长命令
- **E: 盘不支持硬链接**，所以 write/edit 工具会报 `EISDIR ... link`，一律用 `ops_bash` + heredoc 写文件
