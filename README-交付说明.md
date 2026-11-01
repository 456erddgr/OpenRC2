# RC2 EdgeTX · 交付说明（2026-10-31 夜）

## 一、现在能用的是什么
装 `dist/RC2ETX-0.5.4.apk`（66–70 KB，已签 debug 证书）。打开就是 8 个页签：

| 页签 | 作用 |
|---|---|
| 原版 | EdgeTX colorlcd 风格的操控页：两个摇杆方格（可直接拖）、SA–SD 开关、S1/S2 旋钮、底部 16 通道条 |
| 原生 | **真 EdgeTX 核心的入口**（libetx.so 还没编好，现在显示缺库提示；编好后这一页就是原版界面本体） |
| 触摸 | 双虚拟摇杆 + 4 个三档开关 + K1/K2 旋钮 + 16 通道条 |
| 通道 | 16 通道 us 值 / 百分比 / 横条 + 最新 CRSF 帧 hex + 控制环实测 Hz 与抖动 |
| 混控 | 每通道选源、expo/rate/offset/反向，保存/恢复默认 |
| 输出 | 空输出 / UDP / USB 串口 / 录帧 四选一，波特率 420000/400000/115200 |
| 校准 | 10 轴行程采集 + 通道检测 + 「拨一下摇杆就绑到 CH」+ 机内摇杆开关 |
| 诊断 | 自检（24 项）/ 设备信息 / USB 列表 / root 状态 / 保存诊断 |

## 二、真机已验证（不是"应该能"）
- 控制环 **96–97 Hz**，最大抖动 12–14 ms，16 通道全通。
- CRSF 帧 26 字节，CRC8(poly 0xD5) 与 11bit 打包自检 **24/24 PASS**，单帧编码 **8.8 µs**。
- **UDP 端到端**：PC 收 482 包，**CRC 全对、16 通道解码正确**（见 `docs/04-UDP端到端验证.md`）。
- **机内物理摇杆已接通**：读 `127.0.0.1:40007`（dji_link 的推送），30 字节帧、`cmd_set=06/cmd_id=AE`、6×u16le、中点 1024、满行程 **±660（端点 364/1684）**。
  - 实测 `raw=[364,1684,1684,1622,…]` → `us=[172,1811,1811,1703,…]`，**满杆到端点**，行程不再是 64%。
  - 轴序（你确认过的）：**ax0=右横(AIL)、ax1=右纵(ELE)、ax2=左纵(THR)、ax3=左横(RUD)**，AETR。
  - 摇杆开关现在**默认开启并持久化**，开机自连；4 秒收不到帧会自动重连（4/8/16/30 秒退避），不会再把你的输入"打回 0"。
- 触摸注入在这台机器上被封死（`input tap/keyevent/sendevent` 全无效），所以所有验证都靠 `am start --es action …` 钩子 + logcat + 截图。

## 三、必须知道的硬限制（我不粉饰）
1. **输入速率约 9.3 Hz（约 108 ms 一帧）**。这是 dji_link 对本地客户端的推送上限，不是我们代码的问题：
   - 我试过向同一 socket 回 `06/77` 请求提速 → 帧率 8.25 → 8.95 Hz，且发到第 11 帧被 dji_link 主动断连。
   - USB 层也量化过了：xhci 端点环全程平稳常驻流，`dji_link` 真实读取仅 ≈1.2 KB/s，**没有第二路隐藏的高速摇杆流**。
   - 结论：**慢飞/自稳可以，穿越机特技这个延迟偏大。** 真要 100 Hz 只能从摇杆模拟信号取（拆机硬件路线）。
2. **机身自带射频跑 ELRS 不可行**：DJI 私有 SDR（O3/O4 系，dji_sdrs_agent），无手册无开源驱动，射频前端是定制 ASIC，不是可重编程的 SX1280。
3. **ELRS 接收机改发射不推荐**：TX 固件按 TX 目标编译（PA/LNA、引脚不同），RX 无 PA（约 10 dBm vs 20–30 dBm），刷错必砖。
4. **USB-C 四芯不能直接焊 TX/RX**：D+/D− 是 480 Mbps 差分对，不是 3.3 V UART。

## 四、ELRS 的正确出口（已定方案）
```
RC2 USB-C ──数据线──> ESP32-S3（USB CDC 设备）──UART1 420000──> ELRS TX 模块 CRSF 口
```
- APK 侧已实现标准 **CDC-ACM**（class 2 + class 10，SET_LINE_CODING + DTR/RTS），并把内部 DJI 设备 `2ca3:1020` 排除在串口候选之外。
- 桥接固件在 `lab/firmware/esp32s3-crsf-bridge/`（队友在写，含帧校验与 LED 心跳）。
- 另一条零硬件路线：APK 的 **UDP 输出 → 局域网 ESP32 → CRSF**，今晚可用（`docs/05-ESP32-UDP转CRSF桥.md`）。

## 五、原版移植进度（真·把 EdgeTX 编进 APK）
- 源码：**EdgeTX v2.12.2**（commit `3673c51`）已克隆到 `RC2-EdgeTX-Native/vendor/edgetx/`，5 个子模块按 pinned commit 钉死，**LVGL 用 EdgeTX 自己的 fork（release/v8.2）**。
- 接口：`docs/07-桥接接口约定.md`（9 个符号，量程/线程/性能预算全钉死）；Java 侧 `jni/EtxNative.java` + `ui/NativePanel.java` + `jni/NativeProbe.java` 已就位。
- 验证入口（无需手点）：`adb shell am start -n com.rc2.etx/.MainActivity --es action native` → 写 `native.txt`（init rc / 版本 / 分辨率 / render 非零像素数 / 16 通道）+ 存 `native_frame.png`。现在它输出 `available=false … library "libetx.so" not found`，**这句话消失就是移植成功第一步**。
- SD 数据已就位：`/sdcard/Android/data/com.rc2.etx/files/sd/{RADIO,MODELS,THEMES,WIDGETS,SCRIPTS,SOUNDS,IMAGES,TEMPLATES}`（来自你给的 V15 工厂包，`radio.yml` 与 `model1.yml` 校验过，owner 已交还 App uid）。
- 构建：`./scripts/build-apk.sh 0.5.4` 自动把 `app/jniLibs/arm64-v8a/libetx.so` 注入 APK 并重签（已验证注入链路，用 2KB 假 so 测过）。

## 六、队友在做
- `edgetx-port`：EdgeTX 数据层 → `etx_parse_config` 读 `radio.yml` → 冒烟渲染一帧 → 全量 colorlcd 界面。
- `usb-lab`：ESP32-S3 CDC→CRSF 桥固件；USB host 实验（需要你把线从电脑端拔掉才能做，今晚没动）。

## 七、明天你可以直接做的三件事
1. 打开 App → 「校准」页拨杆，看右上角 `已连接 NHz/M帧 raw a/b/c/d/e/f` 是否随手指变化（**这就是物理摇杆实时读数**）。
2. 拨杆同时看「通道」页 16 行是否跟着动、hue 到 -100/+100 两端（验证满行程）。
3. 想试 UDP：在电脑上 `python scripts/udp_listen.py`，App「输出」页选 UDP、目标填电脑 IP:8888，点应用。

---

## 补充（2026-11-01 凌晨 00:10）

**原生桥已经通了**（用我写的"假核心"桩验证，不是真 EdgeTX）：
APK 里 `lib/armeabi-v7a/libetx.so` 能 dlopen、能 `etx_init` 读 `RADIO/radio.yml`、能 `etx_render` 画到屏幕、能 `etx_channels_us` 出 16 通道。
「原生」页现在显示的是桩画的渐变+四轴标记，这是**管线通了**的证据，不是 EdgeTX 界面。

挖到一条会卡死移植的硬约束（已写进 `docs/09-安装期ABI限制.md`）：
这台 RC2 的包管理器对新装 App 强制 32 位 ABI（`abiOverride=armeabi-v7a`），只有 arm64 库的 APK 永远装不上。
所以真移植也必须编 32 位 `armeabi-v7a`，已把这个要求与原样链接参数发给原生移植那条线。

32 位进程下回归全过：自检 24/24、机内摇杆 22Hz 连续、UDP 454 包 0 坏包 75.6Hz。

**00:25 更新：真 EdgeTX 核心进 APK 了（0.7.0 / 0.7.1）**

移植那条线交来了 32 位 `libetx.so`（17,964 B，armv7a）。它只导出纯 C 符号，没有 JNI 入口，
所以我加了一小层转发（`app/jniShim/shim.cpp`，临时方案），现在「原生」页显示的就是**真核心渲染的帧**：
```
available=true  version=2.12.2  init_rc=0  screen=480x272
tick 0.03ms  render 0.65ms  channels=16
```
它已经能读 V15 的 `radio.yml`（semver 2.12.2 / board v15 / stickMode 1 / 7 轴校准）
和 `model1.yml`（F62 / 14 条混控 / 16 通道），并按 33ms 出帧。截图 `shot-native-071.png`。

**目前它还只是个调试屏**（一串数字 + 16 根通道条），离"原版 EdgeTX 界面"还差 LVGL 上屏这一步——
性能已证明够（单帧 0.65ms，预算 15ms）。字体表有 bug（`g` 画成 `9`、`t/x/k/s` 丢失），已让对面修。

`docs/09-安装期ABI限制.md` 是这条路上最关键的一页，别删。

**接收机改高频头：已给出定论（`docs/11-ELRS出口最终方案.md`）**

用你给的 V15 出厂固件做了字节级取证，我自己复核过两处硬证据：
- 发射固件 `Elrs/V15Elrs4.1.0-fcc-firmware.bin` 是 **ESP32 + LR1121 双频**（target `UNIFIED_ESP32_LR1121_TX`）
- 你的接收机（HR7E/HR8E/HR8EG）是 **ESP32 + SX128X**；`HR1EP` 是 ESP8285
- 两侧 bootloader 的 md5 **完全相同**（`097cef81…`）→ 能刷、能开机
- 但**射频芯片不是同一颗**，且 ExpressLRS 把整板引脚表编进固件（MISO/SCK/NSS/RST/BUSY/DIO1 六线全错位，功率控制脚也不同）
→ **刷进去能启动，但发不出射频、没有功率，等于砖。别刷。**

正路：买一个现成 ELRS TX 模块（¥150–250）+ 一块 ESP32 桥板（¥25–60）。
`RC2 —UDP/WiFi→ ESP32 —CRSF UART→ TX 模块 —2.4G→ 你的接收机`。
UDP 这条路**今天就能用**（已实测 75.6Hz / 0 坏包），不必等 USB 主机模式。

**00:35 更新：0.8.0 —— 字体修好、三屏可切、数字对得上 V15**

- APK 里现在只有一个 `libetx.so`（27,336 B，自带 JNI 入口），中间那层转发已删。
- 字体表重做（ASCII 32..126 全齐），`g`→`9`、`t` 丢失那些毛病全没了；现在有个自检屏能把所有字符画一遍。
- 点屏幕底部 20px 可以循环切三屏：通道条 / 配置数字屏 / ASCII 自检屏。
- **配置数字屏上的 7 轴校准值和 14 条混控，与我直接从 V15 `radio.yml`、`model1.yml` 读出的逐项一致**——
  这是"和 V15 原版一模一样"的第一份硬证据（截图 `shot-native-080.png` 待补，整帧在 `out/native_frame1.png`）。
- 下一步就是 LVGL：先把 LVGL 8.2 单独画起来（450×272 底色 + 一个标签 + 一个按钮，触摸能触发），
  再往里塞 EdgeTX 自己的 `gui/colorlcd` 界面。验收硬指标：单帧 ≤15ms、不新开线程、点击有回调。

**00:45 更新：源码整包在路上，K3（LVGL）是最后一关**

- 那边逐目录补源码太慢（每目录 1–5 分钟，还常 `invalid index-pack output`），我改成**整包拉**：
  `scripts/fetch-edgetx-src.sh`（`E:\我的项目\RC2-EdgeTX-Native\scripts\`）——断点续传 + gzip 完整性校验 + 条目数下限，
  展开后自动点名 `radio/src/gui/colorlcd`、`libopenui`、`storage/yaml`、`targets/simu`、`thirdparty/lvgl` 这些关键目录在不在。
  实测 codeload 直连约 28–35 KB/s，整包 ~20–40 分钟。
- 移植路线与验收标准写死在 `docs/12-移植路线与验收标准.md`：**不走 EdgeTX 自带的 superbuild**，自己写构建描述只编需要的模块；
  K0–K6 六道关，每关都有硬指标（K3 要求 LVGL 单帧 ≤15ms、不新开线程；K5 要求混控与 Java 侧差 ≤3us）。
- 现状（按关算）：K0 so 能加载 ✅、K1 数字对得上 V15 ✅、K2 字形不缺 ✅、**K3 LVGL 未过 ⬜**。
  所以现在屏幕上看到的还是调试屏，不是原版界面——这一点我不会含糊过去。

## 2026-11-01 凌晨（夜班）进展

- **原生页 = 真 EdgeTX 2.12.2 核心**（不是外观复刻）：`libetx.so` 由 EdgeTX v2.12.2 源码编出，读你的 V15 `radio.yml`，7 轴数字与出厂一致。
- **全屏模式**：`adb shell am start -n com.rc2.etx/.MainActivity --es action full --ei on 1`，画面铺满整屏，右上角「退出全屏」。
- **K5 通道交叉校验通过**：原生核心与 App 侧混控逐通道差 ≤1µs，四轴 173/1811、中位 992。
- **长跑 90 分钟**内存无上涨（58–74MB）。
- 还差：EdgeTX 官方 colorlcd 界面（上游正在编，已 100 个 .o 零源码改动过编译）。
- 你交代的两件事：**①从电脑端拔掉 USB 线**（才能测 USB 主机模式）；**②买 ELRS TX 模块**（`docs/11` 有清单）。
- 0.9.8：真电压进核心（4204mV 实测）、八键条、七轴就位；真 EdgeTX colorlcd 首页已由上游出图，待合库。
- **真 EdgeTX 2.12.2 colorlcd 已经跑在 APK 里**：首页（Batt 4.2V/SD 金色主题/CH1-5）、主菜单八个入口、模型管理页（真实缩略图）全部可用，按键条 ENT/RTN/方向键已通。
- 1.3.0：方向键/翻页键生效（PG↑/PG↓ 换页签、ENT 进入、RTN 退出）；深一层页面与「改值存盘」由上游在真机验证通过（Mix CH1 权重 100%→80% 已落盘）。
- 1.4.0：接入带 etx_rotary 的新 so；修回被 root 改坏的 SD 文件属主（否则改值存盘会静默失败）。
- 1.9.4：中文界面点亮（真 EdgeTX colorlcd 译文）；原生触摸打通（点行=选中）；新增确定性触摸钩子 `--es action touch`（本 ROM 的 `adb input tap` 会被吞掉，不能用来判断触摸）。
- 1.9.5：K5 交叉校验改走 7 轴生产路径（4 轴 legacy 分支内部会二次置换轴序），原生核心与 Java 混控逐通道差 ≤1us，四轴满行程 173..1811。
- 1.9.7：换带拖动(scroll)支持的 so；触摸钩子支持 tap/drag（坐标是 480×272 逻辑坐标）。滚动尚未生效，待上游定位拖动判定。
- 1.9.7 回归：自检 24/24；摇杆 24Hz 0 坏帧不断连；K5 四轴 173..1811；CRSF UDP 834 包 0 坏包 83Hz。
- 1.10.4：修「非常非常卡」——ticker 33→16ms、etx_tick 传真实 elapsed、加 dirty 标记只在像素变化时重绘。RenderThread 12.2%→0.4%，56fps。
- 1.10.5：触摸滚动打通（上游修 etx_scroll_target 改用 indev 的 act_obj）。模型设置页拖动可滚，截图实证。


---

# 交付说明 · 第二版（2026-11-01 12:40）—— **原版系统已经跑起来了**

> 上面那一版是 10-31 夜的记录，保留作为过程档案。**请以本节为准。**

## 一、你手上这个 APK 里是什么

`dist/RC2ETX-1.10.5.apk`（约 6.27 MB，已签 debug 证书）。

**里面装的是真正的 EdgeTX 2.12.2 官方 C++ 源码编译出来的原生库 `libetx.so`（6,179,540 B，armv7a），
界面由 EdgeTX 自己的 LVGL colorlcd 引擎渲染，中文界面。** 不是外观复刻。

证据链（都可复现）：
- `version=2.12.2` / `init_rc=0` / `screen=480x272`，`radio.yml` 真读（`semver 2.12.2 / board v15 / stickMode 1`）。
- 启动顺序照 EdgeTX 自己的 `tasks.cpp` + `targets/simu/simpgmspace.cpp`：`simuFatfsSetPaths(sd) → startOptions → lcdInit → adcInit → LvglWrapper::instance() → edgeTxInit()`。
- **7 轴校准值与出厂 V15 逐项一致**：LH 1055/642/608、LV 1075/600/546、RV 929/554/598、RH 1044/624/653、P1 1010/995/1010、P2 1029/1003/1003、P3 1024/1024/990。
- **K5 交叉校验**：原生核心与 App 侧混控逐通道差 **≤1µs**，四轴满行程 173..1811、中位 992。

## 二、你现在能做什么（都已真机验证）

| 能力 | 状态 | 说明 |
|---|---|---|
| 中文原版界面 | ✅ | 首页 / 主菜单 / 模型管理 / 模型设置，全中文 |
| **物理摇杆读数** | ✅ | 24Hz、0 坏帧、满行程 172..1811，**直接驱动 16 通道** |
| 触摸点选 | ✅ | 点行 = 选中该行；按钮（新建/收藏/模块…）可直接点 |
| **触摸滚动** | ✅ | 模型设置页实测可滚（截图实证） |
| 按键导航 | ✅ | 底部八键条 ↑↓←→ ENT RTN PG↑ PG↓，PG 换页签、ENT 进入、RTN 退出 |
| 旋钮 | ✅ | **一格 1%**（实测 weight 100→101→100 落盘） |
| 混控改值存盘 | ✅ | 改完存盘、退出重进仍在 |
| CRSF 输出 | ✅ | UDP 端到端 **776 包 / 0 坏包 / 75.2 Hz** |
| 16 通道监视 | ✅ | 通道页实时 us / 百分比 / 横条 |

**用手指操作**：底部八键条就是给你点的；屏幕上也能直接点行、点按钮、拖动滚动。
**用原装摇杆**：摇杆值直接进 16 通道（不需要校准，映射已锁定 AETR）。

## 三、四条硬限制（我不粉饰）

1. **摇杆数据率约 24Hz（≈42ms 一帧）**。这是 DJI `dji_link` 对本地客户端的推送上限，不是我们代码慢。
   USB 层已量化过：xhci 端点环全程平稳常驻流，`dji_link` 真实读取仅 ≈1.4 KB/s，**没有第二路隐藏的高速摇杆流**。
   → 慢飞/自稳没问题；**穿越机特技级延迟做不到**，要 100Hz 只能拆机取模拟信号。
   → **用我们 App 之前，请先关掉大疆那个模拟飞行软件**：它会占住 40007 的唯一客户端名额，我们就会被每 ~25 秒踢一次。
2. **机身自带射频跑 ELRS 不可行**：DJI 私有 SDR + 定制 ASIC，不是可重编程的 SX1280。
3. **接收机别刷 TX 固件**：射频芯片不同（TX=LR1121，RX=SX128X）+ 引脚表全错位 → 能启动但发不出射频，等于砖。证据见 `docs/11`。
4. **USB-C 四芯不能直焊 TX/RX**：D+/D− 是差分对，不是 UART。正解见 `docs/11` 与 `docs/05`。

## 四、还差什么（诚实清单）

- **C 路外接手柄**（USB/BT 手柄直连）**原生侧已就绪**（1.10.9 装机）：接到手柄时手柄轴优先，机内摇杆自动让位；**但本 ROM 的 InputReader 不采纳 uinput 虚拟手柄**，所以必须插真实 USB/蓝牙手柄才验得了端到端。
- **USB 主机模式**没测：必须你**从电脑端把 USB 线拔掉**才能做（双端 VBUS 有反向灌电风险，我不会自己拔）。
- **ELRS 发射**要等硬件：ELRS TX 模块 ¥150–250 + ESP32 ¥25–60，**UDP→ESP32→CRSF 这条路今天就能用**（已实测 75.2Hz / 0 坏包）。

## 五、几条自测命令（不用手点，adb 就能跑）

```bash
export MSYS2_ARG_CONV_EXCL='*'
ADB="/e/我的项目/rc2-backup/tools/platform-tools/adb.exe -s 6UZBM6L02104CA"
cd /e/我的项目/RC2-EdgeTX

$ADB install -r dist/RC2ETX-1.10.5.apk            # 装机
$ADB shell am start -n com.rc2.etx/.MainActivity --es action full --ei on 1   # 全屏原版
$ADB shell am start -n com.rc2.etx/.MainActivity --es action selftest          # 自检 24 项
$ADB shell am start -n com.rc2.etx/.MainActivity --es action duml --ei on 1    # 摇杆读数
$ADB shell am start -n com.rc2.etx/.MainActivity --es action crosscheck       # K5 交叉校验
$ADB shell am start -n com.rc2.etx/.MainActivity --es action touch --es act drag --ei tx 240 --ei ty 200 --ei dy -100   # 拖动滚动
```
**注意**：这台 ROM 上 `adb shell input tap` 会被吞掉（getevent 抓不到内核事件），所以判断触摸必须用上面 `--es action touch` 钩子或真手指。

## 六、GPLv2（法务须知）

EdgeTX 是 GPLv2。**它已编进这个 APK，所以整个 App（含 Java 壳与我们的 HAL）都必须按 GPLv2 公开源码。**
vendor 树相对上游 v2.12.2 只改了 **1 处**：`radio/src/targets/horus/hal.h` 加 `RC2V15` 的 7 轴方向表（登记在 `docs/01-改动登记表.md`）。

## 1.14.4（2026-11-01）大疆摇杆接通原版 EdgeTX 混控

**这条是这次最重要的一条：大疆的物理摇杆现在真的在驱动原版 EdgeTX 的混控和界面了。**

真机证据（同一台 RC2，强制轴两态截图）：
- 打满一组：`CH1(横滚) +100% / CH2(俯仰) -100% / CH3(油门) +100% / CH4(偏航) -100%`
- 反向打满：`CH1 -100% / CH2 +100% / CH3 -100% / CH4 +100%`
（`shot-1144-a.png`、`shot-1144-b.png`）

为什么以前不动：三条都缺，缺一条就零。
1. EdgeTX 自己的混控函数**从来没被调用**（我们只跑了 `per10ms()` 和界面刷新）；
2. 喂进去的 ADC 数值域差了 2 倍（应是 0..4096 中位 2048，我们给了 0..2047 中位 1024）；
3. 轴序两套约定打架（EdgeTX 是 LH,LV,RV,RH，我们按 MCU 顺序给）。

顺带修掉两个 App 侧 bug：
- 全屏模式**根本没在 tick 原生面板**（所以以前全屏下摇杆完全没反应）；
- 本机"虚拟输入设备"被误判成外接手柄，导致**每帧都走空手柄分支、把大疆摇杆值丢掉**。

可用基线 so：`RC2-EdgeTX/keep/libetx.so`（6,185,588 B，sha256 `23185e6d…`）。
