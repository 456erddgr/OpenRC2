<div align="center">

# OpenRC2

<!-- 分享卡片描述 -->
<!-- 把大疆 RC2 / RC Pro / RC Plus 2 遥控器改造成 EdgeTX 开源遥控器。真 C++ 内核、原装摇杆、USB 手柄。 -->

**把大疆遥控器，改成一台 EdgeTX 遥控器。**

不是套壳，也不是仿个界面。这里跑的是 EdgeTX 2.12.2 的真实 C++ 内核 ——
混控、曲线、逻辑开关、飞行模式、Lua 脚本，全是上游代码；界面是 LVGL 画出来的原版界面。
遥控器原来的摇杆、触摸屏、实体键、旋钮，一个不落。

[![release](https://img.shields.io/github/v/release/456erddgr/OpenRC2?label=release&color=2ea043)](https://github.com/456erddgr/OpenRC2/releases/latest)
[![downloads](https://img.shields.io/github/downloads/456erddgr/OpenRC2/total?label=downloads&color=0366d6)](https://github.com/456erddgr/OpenRC2/releases)
[![license](https://img.shields.io/github/license/456erddgr/OpenRC2?color=red)](LICENSE)
[![platform](https://img.shields.io/badge/Android-11-3DDC84?logo=android&logoColor=white)](#支持哪些遥控器)
[![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-orange)](#自己编-libetx-so)

**[下载](#下载)　·　[长什么样](#长什么样)　·　[支持哪些遥控器](#支持哪些遥控器)　·　[怎么装](#怎么装)　·　[踩过的坑](#踩过的坑)　·　[自己编 so](#自己编-libetx-so)　·　[文档](#文档索引)**

</div>

![真机](docs/img/00-hardware.jpg)

<p align="center"><i>DJI RC2（已 root）。屏幕上是这个 App，左右那八颗是遥控器自带的屏幕键。</i></p>

---

## 为什么有这玩意

大疆的遥控器就是台安卓机，硬件素质不差 —— 只是软件把你锁死了：只能飞大疆的机器，摇杆数据只在它自己的进程里转。

有人试过在安卓上跑一个"长得像"的 EdgeTX 界面，看着像，但混控是假的，通道也对不上。
这里走的是另一条路：把 `radio/src` 整套编成 `libetx.so` 塞进 APK，让真内核在遥控器的屏幕上跑起来，
摇杆数据从大疆的私有链路里抠出来喂给它。

从最开始只有一块黑屏，到现在能当主力控用，中间是两年份的周末。

---

## 长什么样

| | |
|---|---|
| ![主屏](docs/img/04-etx-mainscreen.png)<br>**原版主页** —— 电池、模型名、通道条，都是 EdgeTX 自己画的 | ![飞行页](docs/img/02-fly.png)<br>**飞行页** —— 换主题、换机型，跟真控一样 |
| ![菜单](docs/img/07-menu-cn.png)<br>**中文菜单** —— 全中文，不是机翻 | ![通道](docs/img/03-channels.png)<br>**通道监测** —— 每根通道的实时值与 CRSF 输出 |
| ![校准](docs/img/08-calib.png)<br>**校准向导** —— 推哪根认哪根，不用手选反向 | ![USB](docs/img/09-usb-dialog.png)<br>**USB 弹窗** —— 插电脑就变 Xbox 手柄 |

---

## 支持哪些遥控器

| 遥控器 | 系统 | 摇杆从哪来 | 现在什么状态 |
|---|---|---|---|
| **DJI RC2**（`rc331`） | Android 11 / arm64 | DUML `127.0.0.1:40007`，摇杆帧约 10 Hz，总帧约 24 Hz | ✅ 主力机型，功能全开 |
| **DJI RC Pro**（`rm510`） | Android 10 / 骁龙 865 | DJI 私有 binder（`protocol` 服务）+ `06/174` 按键帧 | ⚠️ 摇杆和按键接通了，但**没 root，USB 手柄用不了** |
| **DJI RC Plus 2** | Android 11 / arm64 | 同一套 binder（`transact(4)`）+ 无障碍服务收物理键 | ⚠️ 传输层已和参照包逐字节对上，**还没上机验** |
| 普通手机 / 平板 | arm64 + 32 位兼容 | 没有实体摇杆，只能用触摸 | ⚠️ 能起内核，但要 32 位 ABI |

> 这三台的按键通路是**不一样**的，坑过不少人：
> RC2 的拨杆走 DUML `06/AE` 帧；RC Pro 走 binder `06/174`；RC Plus 2 的物理键在内核里是普通输入设备事件，
> 只有 `AccessibilityService.onKeyEvent` 能拿到。所以 App 里留了三条独立通路，最后都汇到同一个 `press/release`。

---

## 下载

| | |
|---|---|
| **最新版** | [RC2ETX-1.46.44.apk](https://github.com/456erddgr/OpenRC2/releases/latest)（13.2 MB，调试签名，可直接覆盖升级） |
| 全部版本 | [Releases 页面](https://github.com/456erddgr/OpenRC2/releases) |
| 国内镜像 | [123 云盘](https://1843626542.share.123pan.cn/123pan/VX07Td-v8SoA?pwd=nRmH)　提取码 `nRmH` |

> 换签名（调试 ↔ 正式）必须先卸载，否则报"应用未安装"。卸载会连带清掉 SD 目录，先备份。

---

## 怎么装

1. 用手机助手装、`adb install`，或者直接点 APK；
2. **第一次启动会自动铺 SD 卡骨架**：`RADIO/`、`MODELS/`、`SCRIPTS/TOOLS/`、`THEMES/`（含 v15 金色主题）、`IMAGES/`。只补缺，不动你改过的配置；
3. 想要出厂干净状态，删掉 `Android/data/com.rc2.etx/files/sd` 再启动。

**前提**：遥控器得先 root、BL 已解。没 root 也能跑，但 USB 手柄和一部分系统级功能用不了。

装完照着下面的清单走一遍，五分钟就能看出装对没装对。

### 五分钟验收清单

1. **启动**应该看到七个页签：`原生 | 通道 | 混控 | 输出 | 校准 | 诊断 | 设置`。
   如果一进来是全屏，点右上角「退出全屏」—— 那是你上次手点的，现在不会再自动弹。
2. **设置页四张卡**：点「遥控器型号」循环 `自动识别 → RC2 → RC Pro`；点「界面主题」换皮肤并重启内核；
   「摇杆校准」直接跳校准页；「诊断导出」跳诊断页。
3. **校准**：点「开始校准」→ 四根杆各转圈推到顶（不用死摁一点）→ 顶部四卡变绿「满行程 ✓」→
   全部松手回中 1.5 秒 → 自动完成。回到「通道」页看：CH1~CH4 应该回到 `0%±1%`，**不该有通道卡在 −100%**。
4. **摇杆手感**：拨 15 秒，看还有没有"卡住一秒再跳一下"。RST 还在（每 3~6 秒一次），但空档已经用 binder 补帧填上。
5. **USB 手柄**：插线 → 弹窗选「游戏手柄」。**第一次会弹 root 授权，必须点允许**。
   之后 PC 设备管理器里应该多出一个 HID-compliant game controller。

---

## 能做什么

### 真内核，不是皮

`libetx.so` 是把 `radio/src` 整套编出来的，导出了 17 个 `Java_*` 入口和 43 个 `etx_*` 桥接符号。
混控、曲线、GVars、逻辑开关、飞行模式、模型管理、Lua 引擎、主题系统 —— 全是上游代码。
我们干的活只有一件：在安卓和内核之间搬数据。

### 原装摇杆直接驱动混控

- 摇杆不走 evdev，走大疆的私有 TCP（`40007`）。DUML 封装，`cmd_set=6 / cmd_id=174`，6×u16 小端，中点 1024、行程 ±660；
- 解出来按 `RH,RV,LV,LH,P1,P2,P3` 七轴喂内核，量程 `0..4096`、中位 `2048`；
- 每 10 ms 调一次 `doMixerCalculations()` —— EdgeTX 自己的混控真的在算，不是画给你看的；
- 断连重连单线程做，快照整帧换引用防撕裂，中位 2% 死区防漂移。

### 触摸、实体键、旋钮

点可聚焦控件、按钮类可以直接点；方向键 / ENT / RTN / PG↑ / PG↓ 映射到 EdgeTX 菜单导航；
旋钮一格 = 改值 1%。列表滚动走 LVGL 的 `act_obj`，不会滚错层。

### 摇杆平滑（10 Hz 源 → 100 Hz 手感）

源只有 10 Hz，直接喂进去手感是"抽搐"的。`StickSmooth` 在两帧真值之间**插值**，窗口 60 ms，**不外推** ——
外推会冲出再回拉。再配三段低通（小幅才滤、大幅直通）。实测单拍最大跳变从 539 降到 98。

### USB HID 手柄（要一次性 root 授权）

把遥控器变成标准 USB 手柄，插电脑模拟器直接认，不用装驱动。
发包跑独立 4 ms 线程（约 250 Hz），比塞在控制环里稳得多。

> 建 USB gadget 是内核操作，**必须 root**。RC Pro 没 root，这条用不了。

### 自动校准

开始先自动取中点，然后你推哪根杆就绑哪一根（油门 → 副翼 → 俯仰 → 偏航）。
反向不用手选，min/max 都采，写盘时自然反向。最后 5 秒满行程测试，记录真实行程并留 2% 余量。
**100% 由你实际推到哪决定**，不是写死 ±1.0 —— 所以 RC Pro 这种行程偏小的机器推到底也能显示 100%。

### 诊断与一键导出

自检、设备信息、切型号，一键导出 `sd_probe.txt`（SD 路径 + 目录清单）、`hid.txt`、`native.txt` 到 `files/exports/`。

---

## 把遥控器变成电脑手柄（PC 端，不用 root）

思路和 RM510 那套一样：**遥控器不伪装 USB 设备**，只把摇杆数据用普通 TCP 发到电脑，
由电脑上的 ViGEmBus 造一个标准 Xbox 360 手柄。所以不需要 root、不碰 `/dev/hidg0`。

| 文件 | 用途 |
|---|---|
| `OpenRC2-Setup.exe` | 图形安装包，装到用户目录 + 桌面快捷方式，装完自动启动（推荐） |
| `OpenRC2-Pad.exe` | 绿色单文件，双击就用，不写注册表 |

**轴序（最容易搞错的地方）**：遥控器送上来的是 DUML 六轴 `[0]副翼 [1]俯仰 [2]油门 [3]偏航 [4][5]波轮`；
PC 端映射成 **左杆 = 偏航(X) + 油门(Y)**、**右杆 = 副翼(X) + 俯仰(Y)**。
早期版本把 `[0]` 当左杆 X，所以"推油门模拟器没反应"。

**用法**

1. 先装一次 ViGEmBus 驱动 —— [这里](https://github.com/nefarius/ViGEmBus/releases)
2. 遥控器插 USB，或者 `adb connect <IP>:5555`，程序启动会自己建 `adb reverse tcp:9999 tcp:9999`
3. 点「启动」→ 遥控器端开数据流（设置 → 诊断 → 虚拟手柄数据流），
   或者 `adb shell am start -n com.rc2.etx/.MainActivity --es action pad --ei on 1`
4. 用「打开 joy.cpl 验证」看 `Xbox 360 Controller for Windows` 动不动

**协议**（想自己写客户端可以照这个来）：TCP `9999`，一行一个 JSON，实测约 116 fps。

```json
{"t":1793584460143,"ax":[1024,1024,1024,1024,1024,1024],"us":[1500,1500,1500,1500,1500,1500,1500,1500]}
```

`ax` 是六路原始值，`0..2048`，中位 1024，满行程 `1024±660`。

---

## 自己编 libetx so

APK 只是个壳，真正的内容在 `libetx.so` 里。上游源码和构建脚本在单独那个仓库（`RC2-EdgeTX-Native`），
流程是：EdgeTX 2.12.2 → armv7a 静态库 → 和桥接层一起链接成 `libetx.so` → 塞进 APK。

```bash
export NDK=.../android-ndk-r26
ETX_SO=/path/to/libetx.so ./scripts/build-apk.sh 1.46.44
```

几个坑先说，免得你再踩：

- **ABI 只能出 `armeabi-v7a`**。这台 ROM 对新装 App 强塞 32 位，带 arm64 反而 `INSTALL_FAILED_INTERNAL_ERROR`；
- 链接参数必须 `-shared -fPIC -static-libstdc++ -llog -Wl,-soname,libetx.so`；
- 构建脚本默认吃 `keep/libetx.so` 当基线，换 so 得显式 `ETX_SO=`，并且会对基线做未定义符号比对
  （基线没有、libc/libm 也提供不了的符号，直接判坏构建）。

---

## 踩过的坑

这几年最花时间的不是写功能，是这些。记下来给下一个人省点事。

**① 摇杆每 3~6 秒被 `dji_link` 重置一次。**
`connected → 收 2 帧 → Connection reset`，稳定复现。抓包证明服务端从不发心跳，
我们发的字节它一个都不读（`Recv-Q` 一直堆着）就直接 RST —— 说明这条连接在它眼里只是"已订阅的数据通道"。
缓解办法是双源：TCP 超过 220 ms 没帧，就用 binder 通道补上（约 6 Hz，中位 1024）。RST 还在，但用户侧不卡了。

**② 模型管理页一开就闪退，崩在 `std::set::insert`。**
`signal 11 addr 0x10` —— 解引用了一个空的 `std::set` 内部指针。追下去是两件事叠在一起：
启动约 70 ms 后，`modelslist` 所在的**一整段 1888 字节 BSS 被外部清零**（加了 `clear()` 钩子，确认不是 `ModelsList::clear()` 干的）；
然后 `setSelected(getLabels().size() - 1)` 在空标签表上无符号下溢成 `0xFFFFFFFF`，在空 set 上插入，崩。
现在进页前会重建模型库、空表直接短路，还加了运行时自愈。**但那个 memset 的写入者到现在还没揪出来。**

**③ RC Pro 六轴全 −100%，怎么动都不动。**
`stickSourceAlive()` 用 `duml.connected` 判活，而 RC Pro 的 40007 是"连一次收一帧就 EOF"，
这个标志几乎恒 false → 从不喂混控 → 走合成假值（正好每轴 −100%），旁键那段代码也不执行。
改成"最近一帧有多新"（单调时钟，400 ms 窗口）就好了。

**④ `RcProStick.raw` 初值全 0，是个完美的骗局。**
`(0 - 1024) / 660` 正好等于 −100%。所以"载荷没解析出来"看起来和"摇杆被按到底"一模一样。
初值改成中位 1024，6 通道同时为 0 判坏帧不发布。

**⑤ 注释写 10 ms，代码是 5000 ms。**
`DumlStick` 的快照模式重连间隔，注释写"10 ms 重连"，代码是 `wait = 5000L`，于是快照模式只有 0.2 Hz。
信注释不信代码，浪费了一晚上。

**⑥ 构建静默用了旧 so。**
`build-apk.sh` 只认 `keep/libetx.so`，而重链的产物在 `RC2-EdgeTX-Native/out/libetx/`。
重链完忘了 `cp`，打出来的包是旧内核 —— 但版本号变了、sha 也变了，看着像成功的构建。连续踩了四版。
现在脚本会打印 so 的 mtime 和 sha16，并且 `out/` 比 `keep/` 新就直接 `[FATAL]` 退出。

**⑦ 两个不同纪元的时钟相减。**
`tick()` 用 `elapsedRealtime()`，桥线程却把 `lastBinderMs` 写成 `currentTimeMillis()`，
差值约 −1.7e12，`binderFresh` 恒为 true。统一成 `elapsedRealtime()` ——
`currentTimeMillis` 会被对时跳变，`uptimeMillis` 深度休眠时停走，只有 `elapsedRealtime` 又单调又含休眠。

**⑧ 退出菜单时炸在 `lv_table_set_cell_value`。**
`Window::deleteLater()` 是同步的，退出菜单时 table 可能已经析构，而 `addLine` / `updateLines` 还在往里写。
在 `libui/table.cpp` 和 `libui/menu.cpp` 各补了一行空检查。

**⑨ 这台机器的蓝牙只有收没有发。**
`dumpsys bluetooth_manager` 里只有 `HidHostService`，没有 `HidDeviceService`，`registerApp()` 根本注册不了。
装 priv-app 授 `BLUETOOTH_PRIVILEGED` 也没用 —— 得换整个蓝牙栈。所以蓝牙手柄这条路，本机判死。

**⑩ EdgeTX 只在离开页面时才写 `radio.yml`。**
所以"改了设置又回去"不是 bug，是它没落盘。App 在 `onPause` 里补了一次刷新。

---

## 这一版改了什么（1.46.44）

> **说清楚：下面这些还没在真机上验过。** 开发到一半遥控器 USB 掉线，
> 无线 adb 也连不上（把本机所在的 `192.168.3.0/24` 扫了一遍，5555 端口一个都没响应）。
> 所以这是"改了什么"，不是"修好了什么"。

| | 改动 | 文件 |
|---|---|---|
| 模型管理闪退 | 进页前空库先重建、空标签表短路、运行时自愈探针 | `model_select.cpp`、`etx_store_diag.cpp`（新） |
| RC Pro 六轴 −100% | 判活改新鲜度、快照重连 5 s → 10 ms、raw 初值 1024 | `DumlStick`、`RcProStick`、`Radio` |
| 旁键 / 拨轮 | binder `06/174` 轮询 + 无障碍服务 | `RcProService`、`KeysService`（新） |
| 时钟混用 | `lastBinderMs` 统一到 `elapsedRealtime()` | `Radio` |
| 构建防护 | 陈旧 so 检测 + 打印 mtime/sha16 | `build-apk.sh` |

包：`RC2ETX-1.46.44.apk`，`13855339` B，
sha256 `ca0fb6f7f567649bddc3f80658744ec77526796135f77c044d9271a185e79b5a`。

> 1.46.41 到 1.46.44 这四版的 `libetx.so` 是**逐字节相同**的，APK 大小也全是 `13855339` ——
> 想确认某个补丁到底进没进包，**只能看 sha256**。看大小、看版本号都会认错。

---

## 已知问题

| 问题 | 现状 |
|---|---|
| 模型管理页根因 | 1888 字节 BSS 是被谁 memset 的，还没定位。现在只有检测 + 自愈 |
| 1.46.44 全部修复 | **未真机验证**（设备中途掉线） |
| `dji_link` 每 3~6 秒 RST | 协议层的订阅细节还没攻下，靠 binder 补帧缓解 |
| 机身射频发 ELRS | 不可行。DJI 私有 SDR + 定制 ASIC，没有公开接口，也没有可重编程的 SX1280 |
| ELRS 接收机改发射 | 不推荐。TX 固件按 TX 目标编译（PA/LNA、引脚不同），RX 板没 PA，刷错必砖 |
| USB-C 四芯焊 TX/RX | 电气上不成立。D+/D− 是 480 Mbps 差分对，不是 3.3 V UART |
| 蓝牙 HID 手柄 | 本机只有 `HidHostService`，判死 |
| 脚本页入口 | QuickMenu 第 9 个磁贴（`APP/LUA脚本`）没建出来，待查 gate 条件 |
| 64 位 | 要另编 aarch64 `libetx.so` 并适配 HAL，属独立排期 |
| 声音 | 开机动画是原厂的（没动过）；系统本来就没有开机音；EdgeTX 的告警音还静着 —— so 侧缺一个 `AudioDriver` 桩 |

**ELRS 的正确出口**（已经验证过的方案）：

```
RC2 USB-C ──数据线──> ESP32-S3（USB CDC 设备）──UART 420000──> ELRS TX 模块 CRSF 口
```

APK 侧的 CDC-ACM 已经实现好了，桥接固件在 `lab/firmware/esp32s3-crsf-bridge/`。

---

## 文档索引

| 想知道什么 | 看哪份 |
|---|---|
| 本轮所有改动 + 遗留项优先级 | [`docs/20-本轮修复总记录-20261103.md`](docs/20-本轮修复总记录-20261103.md) |
| RC Plus 2 的逆向与验收清单 | [`docs/18-rcplus2-兼容分析.md`](docs/18-rcplus2-兼容分析.md) |
| RC Pro 的 binder / 按键通路 | [`docs/19-rcpro-修复记录.md`](docs/19-rcpro-修复记录.md) |
| 版本变更史 | [`docs/08-版本变更与回归.md`](docs/08-版本变更与回归.md) |
| 桥接接口约定（9 个符号、量程、线程模型） | [`docs/07-桥接接口约定.md`](docs/07-桥接接口约定.md) |
| 项目交接（版本基线、目录结构、移植思路） | [`docs/RC2项目交接文档.md`](docs/RC2项目交接文档.md) |
| 怎么把 EdgeTX 装进安卓遥控器（原理） | [`docs/next-ai-porting.md`](docs/next-ai-porting.md) |
| 怎么把遥控器变成电脑手柄（原理） | [`docs/next-ai-pc-pad-principles.md`](docs/next-ai-pc-pad-principles.md) |
| 用户手册 | [`docs/17-用户手册.md`](docs/17-用户手册.md) |
| 桥接接口约定与改动登记 | [`docs/01-改动登记表.md`](docs/01-改动登记表.md) |

---

## 反馈

- 有问题、有建议：[开个 Issue](https://github.com/456erddgr/OpenRC2/issues)
- 想聊、想贴自己的机器：[Discussions](https://github.com/456erddgr/OpenRC2/discussions)
- 机型适配的实测数据最值钱 —— 尤其是 RC Pro 和 RC Plus 2，我手上只有 RC2 能反复折腾

如果这玩意帮到你了，点个 Star 就是最好的鼓励。

---

## 许可

本项目把 EdgeTX（**GPLv2**）编进 APK 分发，所以**整个 App 都以 GPLv2 发布**，源码就在这个仓库里。全文见 [LICENSE](LICENSE)。

和 DJI 没有任何关系，不包含大疆的任何代码或签名。改机、root、刷机的风险自负。
无线电发射请遵守所在地区的法规和执照要求。按"现状"提供，没有任何担保。

作者：**软糖布丁**

<details>
<summary>第三方组件</summary>

- [EdgeTX](https://github.com/EdgeTX/edgetx) — GPLv2，`libetx.so` 的来源
- [LVGL](https://github.com/lvgl/lvgl) — 走 EdgeTX 自己的 fork（release/v8.2）
- [ViGEmBus](https://github.com/nefarius/ViGEmBus) — PC 端虚拟手柄

</details>
