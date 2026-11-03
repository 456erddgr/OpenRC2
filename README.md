# OpenRC2

把 **EdgeTX 2.12.2 的原生内核**（真 C++ 混控 + LVGL 界面）跑进大疆遥控器的安卓系统里，
原装摇杆、触摸屏、实体按键、旋钮**全部可用**；不用另外带控，开机就是 EdgeTX。

![真机](docs/img/00-hardware.jpg)

> 真机：DJI RC2（已 root），屏幕上跑的就是这个 App —— 左右八颗屏幕键、「退出全屏 / 复位」两个逃生键都在。

## 早上验收清单（5 分钟）

1. **装最新包**：`RC2ETX-1.46.44.apk`（下面 Release 链接）。
2. **启动应看到七页签**：`原生 | 通道 | 混控 | 输出 | 校准 | 诊断 | 设置`。若一进来是全屏，点右上「退出全屏」——那是你上次点的全屏，不再是自动的。
3. **设置页四张卡**：点「遥控器型号」应循环 `自动识别 → RC2 → RC Pro` 并弹提示；点「界面主题」应换皮肤并重启核心；点「摇杆校准」直接进校准页；点「诊断导出」跳诊断页。
4. **校准**：校准页点「开始校准」→ 四根杆各**转圈推到顶**（不用死摁一点）→ 顶部四卡变绿「满行程 ✓」→ 全部松手回中 1.5 秒 → 自动完成。完成后到「通道」页看：CH1~CH4 应回到 0%±1%，**不应有任何通道卡在 -100%**。
5. **摇杆卡顿**：拨动摇杆 15 秒，观察是否还有"卡住一秒再跳一下"。RST 仍在（每 3~6 秒一次），但空档已由 binder 补帧填满。
6. **USB 手柄**：插线 → 弹窗选「游戏手柄」。**首次会弹 root 授权，必须点允许**（否则会提示"已被拒绝超级用户权限"）。之后 PC 设备管理器应出现 HID-compliant game controller。

## 本轮修复（1.46.44，2026-11-03）

> ⚠️ **先说结论：本版全部修复尚未经过真机验证。** 开发过程中遥控器 USB 掉线，
> 无线 adb 也未能连上（扫描本机所在的 `192.168.3.0/24` 全网段 5555 端口无响应）。
> 下面是改动内容，不是"已修复"。

### 1. 模型管理页闪退 + 列表空白

**崩溃证据**（设备端 `crash_native.txt`）：

```
signal 11 addr 0x10 pc 0xcacbf4b8 base 0xca782000 off 0x43d4b8
signal 6  addr 0x5d3f pc 0xeb03cee0 base 0xeb004000 off 0x38ee0
```

`off 0x43d4b8` 落在 `std::__ndk1::set<unsigned int>::insert` 内，`addr 0x10` = 解引用了
一个空的 `std::set` 内部指针（结构体偏移 0x10 处）。

**两条原因，互相叠加：**

1. `modelslist` 所在的一整段 1888 字节 BSS 在启动后约 70 ms 被外部清零
   （已加 `clear()` 日志钩子，确认不是 `ModelsList::clear()` 干的）。被波及的对象包括
   `s_anaFilt` / `modelslist` / `modelslabels` / `g_FATFS_Obj` 等。
   → **这个写入者尚未定位**，目前只能检测 + 自愈。
2. `ModelLabelsWindow` 构造里 `lblselector->setSelected(getLabels().size() - 1);`，
   标签表为空时 `size()-1` 无符号下溢成 `0xFFFFFFFF`，随后在空 `std::set` 上插入 → 崩在 0x10。

**修复：**

- 原生侧 `model_select.cpp`：进页前若 `modelslist.size()==0` 先 `clear()+load()`；
  标签表为空则跳过「选中第一个标签」直接返回 —— 彻底消除下溢。
- 新增 `src/ui/etx_store_diag.cpp` 运行时探针：模型数/标签数/当前模型指针变化时打日志，
  检测到「库空了但 SD 上有模型」就自动重建（2 秒节流）。

### 2. RC Pro 六轴全 −100%、怎么动都不动

**根因：整条输入链被一个判据判死。** `stickSourceAlive()` 用 `duml.connected` 判活，
而 RC Pro 的 `40007` 是「连一次收一帧就 EOF」，这个标志几乎恒为 false，于是：

- `tick()` 从不把摇杆喂进混控；
- `fillNativeAxes()` 全走合成假值 —— 正好每轴 −100%；
- `applyRcProButtons()` 整段不执行 —— 旁键全废。

**三个加重项与修复：**

| 问题 | 修复 |
|---|---|
| 判活判据是 `connected` 而不是「最近一帧有多新」 | 改用单调时钟 `SystemClock.elapsedRealtime()` + 新鲜度（400 ms），`d = now-last; return d>=0 && d<ms`（防下溢） |
| `DumlStick` 快照模式重连间隔注释写 10 ms、代码是 **5000 ms** | 改 10 ms，连续失败 200 次熔断回 5 s |
| `RcProStick.raw` 初值全 0，`(0-1024)/660` 正好 = 每轴 −100%（"载荷没解析出来"的伪装） | 初值改 1024 中位；6 通道同时为 0 判坏帧不发布 |

### 3. 旁键 / 拨杆没适配（RC Plus 2）

新增两条通路，与 RC2 原有那条并列：

- **binder `06/174` 帧轮询**（`RcProService`）：250 ms 一次，独立回调，原始 hex 一并上报；
- **无障碍服务**（`input/KeysService.java` + `res/xml/accessibility.xml`）：
  `onKeyEvent` 收标准 gamepad 键码 → `EtxNative.press/release`。
  参照包用 `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents`，缺一条都收不到 KeyEvent。

**铁律（改这个别违反）：**

1. `KeysService` **必须与 libetx 同进程**，绝不加 `android:process` —— 核心状态是进程内的，
   换进程等于加载一份空核心，按了没人看见。
2. 原生按键队列 `s_keyq` **没有锁**。若以后把 `EtxNative.tick()` 挪出主线程，必须同步加锁。
   当前 tick 在 Java 主线程、无障碍回调也在主线程，天然串行。

### 4. 构建脚本加固

踩过一个坑：重链完 `libetx.so` 忘了同步到 `keep/`，`build-apk.sh` 会**静默用旧 so 出包**，
而版本号一变，APK 的 sha 看起来"变了"，极难察觉。现在：

- 每次构建打印 `keep/libetx.so` 的 mtime + sha16；
- 若 `RC2-EdgeTX-Native/out/libetx/libetx.so` 比 `keep/libetx.so` 新，直接 `[FATAL]` 退出。

### 5. 时钟混用修正

`Radio.tick()` 用 `elapsedRealtime()` 判活，桥线程却把 `lastBinderMs` 写成 `currentTimeMillis()`，
两者相减约 −1.7e12 ⇒ `binderFresh` 恒为 true（每 20 ms 空转）。已统一为 `elapsedRealtime()`。

## 支持机型

| 机型 | 系统 | 摇杆来源 | 状态 |
|---|---|---|---|
| DJI RC2（`rc331`） | Android 11 / arm64 | DUML `127.0.0.1:40007` 持续流，约 24Hz | ✅ 主目标机型，全功能 |
| DJI RC Pro（`rm510`） | Android 10 / 骁龙865 | DJI 私有 binder（`protocol` 服务）+ `06/174` 按键帧 | ⚠️ 摇杆/按键已接（1.46.44 修判活与旁键），HID 手柄不可用（无 root） |
| DJI RC Plus 2 | Android 11 / arm64 | **同一套 binder**（`transact(4)`）+ 无障碍服务收物理按键 | ⚠️ 传输层已逐字节对齐，**未真机验证**；轴序/键码待上机确认 |
| 普通手机 / 平板 | arm64 + 32位兼容 | 无物理摇杆，只能用触摸 | ⚠️ 能起核心，需 32 位 ABI 支持 |

> 三台机器的按键通路**不同**：RC2 的拨杆走 DUML `06/AE` 帧；RC Pro 走 binder `06/174`；
> RC Plus 2 的物理按键在内核里是普通输入设备事件，只有 `AccessibilityService.onKeyEvent` 拿得到。
> 所以 App 里留了**三条独立通路**（TCP 174 → binder 174 → 无障碍服务），都只调 `EtxNative.press/release`。

## 能做什么

### 1. 原生 EdgeTX 内核，不是皮肤

`libetx.so` 是把 `radio/src` 整套编出来的：混控、曲线、逻辑开关、飞行模式、模型管理、Lua 脚本引擎、
主题系统全部是上游代码，界面渲染走 LVGL 8.2，我们只做「安卓 ↔ 内核」的搬运。

![主屏](docs/img/04-etx-mainscreen.png)

### 2. 原装摇杆直接驱动原版混控

- 摇杆不走 evdev，走大疆私有 TCP（`40007`，DUML 封装，`cmd_set=6 / cmd_id=174` 摇杆帧，6×u16 小端，中点 1024、行程 ±660）；
- 解析后按 `RH,RV,LV,LH,P1,P2,P3` 七轴喂给内核，`0..4096`、中位 `2048`；
- 每 10ms 调一次 `doMixerCalculations()`，所以 EdgeTX 自己的混控真的在跑，不是摆设；
- 断连重连单线程做、快照整体替换防撕裂、中位死区 2% 防漂移。

![飞行页](docs/img/02-fly.png)

### 3. 触摸 + 实体键 + 旋钮

点击可聚焦控件、按钮类可直接点；方向键/ENT/RTN/PG↑/PG↓ 映射到 EdgeTX 菜单导航；
旋钮一格 = 改值 1%。列表滚动走 LVGL 的 `act_obj`，不会滚错层。

### 4. USB HID 游戏手柄（需一次性 root 授权）

把遥控器变成标准 USB 手柄（4 轴 + 按键），插电脑后模拟器直接认，不用装驱动。
发包跑独立 4ms 线程（约 250Hz），比塞在控制环里稳得多。

> 注意：建 USB gadget 是内核操作，**必须 root**。没 root 的机型（如 RC Pro）这条不可用。

![USB 弹窗](docs/img/09-usb-dialog.png)

### 5. 自动校准（推哪根认哪根）

开始后先自动取中点，然后你推哪根杆，它就绑哪一根（顺序：油门 → 副翼 → 俯仰 → 偏航），
反向不用手选——min/max 都采，写盘时自然反向；最后 5 秒满行程测试，记录真实行程并留 2% 余量。

![校准](docs/img/08-calib.png)

### 6. 诊断与一键导出

诊断页可以跑自检、看设备信息、切遥控器型号（自动 / RC2 / RC Pro），
一键导出 `sd_probe.txt`（SD 路径 + `init_rc` + 目录清单）、`hid.txt`、`native.txt` 到 `files/exports/`。

![诊断](docs/img/03-channels.png)

## 安装

1. 下载 `RC2ETX-<版本>.apk`（调试签名，可直接覆盖升级）或 `-release.apk`（正式签名，发给别人用）；
2. **首次运行会自动铺 SD 骨架**：`RADIO/`、`MODELS/`、`SCRIPTS/TOOLS/`、`THEMES/`（含 v15 金色主题）、`IMAGES/`；
3. 只有补缺、不覆盖你改过的配置 —— 想恢复出厂就在设置里清掉 `Android/data/com.rc2.etx/files/sd`。

> 换签名（调试 ↔ 正式）必须先卸载，否则报「应用未安装」。卸载会清掉上面那个 `sd` 目录，先备份。

## 构建

```bash
export NDK=.../android-ndk-r26    # 与 RC2-EdgeTX-Native 相同
ETX_SO=/path/to/libetx.so ./scripts/build-apk.sh 1.23.0
```

- **ABI 只出 `armeabi-v7a`**：RC2 这台 ROM 对新装 App 强制 32 位，带 arm64 反而 `INSTALL_FAILED_INTERNAL_ERROR`；
- 链接参数 `-shared -fPIC -static-libstdc++ -llog -Wl,-soname,libetx.so`；
- 构建脚本默认用 `keep/libetx.so` 基线，换 so 必须显式 `ETX_SO=`，并对基线做未定义符号比对。

## EdgeTX 侧改动（vendor 3 处）

| 文件 | 改动 | 原因 |
|---|---|---|
| `radio/src/targets/horus/hal.h` | 加 `#if defined(RC2V15)` + `ADC_DIRECTION {1,1,1,1,1,1,1}` | 让 v15 板级 ADC 方向与 RC2 的摇杆序一致 |
| `libui/table.cpp` | `setRowCount` / `select` 首行 `if (!lvobj) return;` | `Window::deleteLater()` 是同步的，退出菜单时 table 可能已析构 |
| `libui/menu.cpp` | `addLine` 判 `if (update && lvobj)`、`updateLines` 首行空检查 | 同上，真正炸的是 `updateLines()` 里的 `lv_table_set_cell_value` |

## RC Pro 专章

- 摇杆锁在 DJI 私有服务里：`ServiceManager.getService("protocol")` → `transact(4)` 发 `cmdSet=6/cmdId=4`，
  回调按小端解 6×uint16（`右水平/右竖直/左竖直/左水平/左波轮/右波轮`）；
- **白名单按进程名匹配**，采集代码必须跑在 `android:process="com.dpad.fuli"`；
- 按键/挡位走 `40007` **快照模式**（连一次给一帧就断），`06/174` 帧 17 字节载荷：五维、C1–C3、挡位、快门、返航；
- 没有 root，所以 USB HID 手柄不可用。

## 已知限制

| 项 | 说明 |
|---|---|
| 机身射频发 ELRS | 不可行：DJI 私有 SDR + 定制 ASIC，没有公开接口 |
| 接收机改发射 | 不可行：TX/RX 固件 target 与 PA/LNA 引脚表不同 |
| 脚本页入口 | QuickMenu 第 9 个磁贴（`APP/LUA脚本`）未创建，待查 gate 条件 |
| 模型管理页 | 1.46.44 已加原生防线（空表短路 + 进页前重建）与运行时自愈；**根因（1888 字节 BSS 被外部 memset 清零）尚未定位**，未真机验证 |
| 64 位 | 需另编 aarch64 `libetx.so` 并适配 HAL，属独立排期 |

## 合规与声明

- 本项目把 EdgeTX（**GPLv2**）编进 APK，因此**整个 App 以 GPLv2 发布**，源码见本仓库；
- 与 DJI 无任何关联，不包含大疆的任何代码或签名；改机、root、刷机风险自负；
- 无线电发射须遵守所在国家/地区的法规与执照要求；
- 无任何担保，按「现状」提供。作者：**软糖布丁**。

## 下载

- GitHub Releases：<https://github.com/456erddgr/OpenRC2/releases>
- 国内镜像（123 云盘）：<https://1843626542.share.123pan.cn/123pan/VX07Td-v8SoA?pwd=nRmH>　提取码 `nRmH`

## 路线图

- [x] 原生内核移植 / 摇杆 / 触摸 / 按键 / 旋钮
- [x] USB HID 手柄（250Hz）
- [x] 自动校准 / 诊断导出 / 型号设置
- [x] RC Pro binder 摇杆 + 174 按键
- [x] 可视化设置页（型号/主题/校准/导出 四张卡片）
- [x] RC Plus 2 逆向与传输层对齐（`docs/18`）
- [x] 物理按键无障碍服务（`KeysService`，需用户在系统里手动开启）
- [ ] **1.46.44 全量真机验证**（模型管理页 / RC Pro 六轴 / 旁键三通路 / 无障碍注册）
- [ ] 定位 1888 字节 BSS memset 的写入者（当前只有检测 + 自愈）
- [ ] 脚本页入口
- [ ] aarch64 构建

## 校准（v2，1.25.x 起）

校准页为**独立重写实现**（`app/src/com/rc2/etx/ui/CalibPage.java`），不再沿用旧 `CalibPanel`。

流程：

1. **采中点（0.9 s）**：静置，记录 10 根轴基准；
2. **认轴**：推一下哪根杆就自动绑定哪根（油门→副翼→俯仰→偏航），不需要手动选反向；
3. **转圈采满**：两根摇杆各转几圈，逐轴记录**实测峰值**，每根轴累计 ≥2 次 ≥0.90 即通过；
4. **回中（1.5 s）**：四根全部回中并保持，取**该窗口平均值**作为最终中点；
5. **落盘并同步**：`calib.min/max` 用实测峰值（下限 0.55 防止饱和成 ±100%），
   写 `Store.saveCalib` → `radio.save()`；同时 `DumlStick.shrinkSpan()` 把原始行程按峰值缩小，
   `input.reset()` 让 EdgeTX 立刻生效。

关键设计：**100% 由你实际推到的位置决定**，而不是写死 ±1.0。这样 RC Pro 这类行程偏小的机器推到底也能显示 100%。

## 已知问题：RC2 摇杆数据链路约每 6 秒被 `dji_link` 重置

现象：`connected → 收 2 帧 → Connection reset`，约 6 秒一次；界面上表现为“拨一会儿卡一下”。

已排除：

- **不是大疆模拟器抢连接**：`ps`/`ss` 确认当时只有 `com.rc2.etx` 一个客户端；
- **不是心跳缺失**：抓包（`re/now.pcap` 312 帧、`re/long.pcap` 1557 帧）证明**服务端从不发心跳**，
  只有客户端每 ~30 ms 主动发 `01 01 08 0a | u32 t | u32 t`；我们已按此实现，
  但 A/B 测试显示“发 / 不发心跳都会 6 秒断”；
- **不是时钟域**：`t` 改成 `SystemClock.elapsedRealtime()`（与服务端同域）后行为不变。

当前最强线索：服务端 socket 的 `Recv-Q` 持续堆积（实测 84 字节 = 7 帧心跳未读），
`dji_link` **不消费**我们发出的任何字节就 RST —— 说明这条 40007 连接在它看来只是
“已订阅的数据通道”，而**订阅/注册**动作发生在我们尚未定位的另一条连接或 Binder 上。

影响面：只影响手感连贯性（`StickSmooth` 有断线桥接，不会乱跳），不影响通道数值正确性。

## 蓝牙 HID 手柄：本机不可行（结论）

`dumpsys bluetooth_manager` 的 profile 列表只有 `HidHostService`（收），**没有 `HidDeviceService`（发）**；
`pm list features` 也没有 hid 相关特性。因此 `BluetoothHidDevice.registerApp()` 无法注册，
装 priv-app 授 `BLUETOOTH_PRIVILEGED` 同样无效 —— 需要替换系统蓝牙栈（BlueZ/Bumble 走 HCI 直通），不在本项目范围。

USB HID 手柄（`/dev/hidg0`）不受影响，仍是模拟器首选方案。

### 断连实测（1.26.7，24 秒窗口）

```
$ adb logcat -d | grep ETX-Duml | grep -c connected      → 5
$ adb logcat -d | grep ETX-Duml | grep -cE "EOF|reset"   → 4
11-02 02:34:07.485  W ETX-Duml: link down: SocketException: Connection reset
11-02 02:34:07.498  I ETX-Duml: connected 127.0.0.1:40007
```

结论：稳定复现 **~6 秒一次 RST**，客户端 **13 ms** 内重连成功。
由于每次重连都能立刻收到摇杆帧，数据本身没丢，影响集中在“帧率在 6 秒边界上掉一帧”，
`StickSmooth` 的断线桥接（`gap > 250ms` 时从新位置起步）负责不产生跳变。

### 开机落在哪一页

冷启动默认进「原生」页（EdgeTX 实时画面）+ 顶部七页签；
App 已在运行时再 `am start` 只走 `onNewIntent`，**不会重置当前页签**——
所以用 adb 钩子连续操作时会停在你上次停留的页，这是设计行为，不是 bug。

### 3~6 秒 RST 的缓解（1.27.0）：TCP + Binder 双源

RC2 上同样存在大疆的 `com.dji.protocol.IProtocolManager`（`service list | grep protocol` 可见）。
本版把摇杆做成**双源**：

- 正常情况下 `127.0.0.1:40007` 的 TCP 帧优先（约 9 Hz，延迟最低）；
- 一旦 TCP 超过 **220 ms** 没有新帧（`dji_link` 每次 RST 的空档），立刻用 binder 通道
  （`transact(4)` → `IPackListener` 回调，实测 6 通道、中位 1024、约 6 Hz）补上。

实测日志：

```
I ETX-Radio: binder 补帧 #2
I ETX-Radio: binder 补帧 #3
```

意义：RST 仍然存在（协议层还没攻下），但**用户侧不再感到“卡住不动”**——空档由 binder 填满。

## 代码审计记录（2026-11-02 夜间）

逐模块检查后修掉的真实缺陷（每条都有真机复现或代码级证据）：

| # | 位置 | 缺陷 | 现象 | 修复版本 |
|---|---|---|---|---|
| 1 | `MainActivity:73` | 冷启动无条件 `setFullNative(true)` | 一进去就全屏，页签/设置点不到，像“出不来” | 1.26.6 |
| 2 | `NativePanel:205` | `frames % 120 == 0` 每 ≈2 秒强制全屏 | 用着用着自己弹回全屏 | 1.26.5 |
| 3 | `Radio.startRcProIfAny` | 构造与 `setDumlOn` 各调一次，桥线程/`RcProStick` 双份 | 双份 `transact(4)` 抢 binder，推几下卡死 | 1.27.2 |
| 4 | `Radio` 补帧分支 | 写 `duml.connected=true` / `lastStickMs` | 补一帧就被误判“TCP 新鲜”，binder 停手；状态栏假在线 | 1.27.2 |
| 5 | `SetPage.draw` | 首行 `drawColor` 擦全画布 | 设置页盖掉页签栏 | 1.23.x |
| 6 | adb 钩子 | 全屏态下打不进 UI | 自动化点击全部无效 | 1.26.3 |
| 7 | `StickSmooth.value` | `d*k + d*ex` 会越过 `cur` | **快速推杆来回抽搐** | 1.27.4 |
| 8 | `HidJoystick` | 写失败只置 `open=false`，`out` 不关；`open()` 又新建流 | **fd 泄漏 → PC 认设备但摇杆不动** | 1.27.4 |
| 9 | `RadioView.onDraw` | 每帧 `new RectF(...)` | 全屏下持续分配，GC 抖动=偶发“卡一下” | 1.27.5 |

已知但未修（如实记录）：

- `127.0.0.1:40007` 每 3~6 秒被 `dji_link` RST，协议层订阅细节未攻下；已用 binder 补帧缓解（见上）。
- 蓝牙 HID 设备模式：本机蓝牙栈只有 `HidHostService`，无 `HidDeviceService`，**不可行**。
- HID 手柄需一次性 root 授权（建 USB gadget 是内核操作）；授权被拒时右下角会提示「已被拒绝超级用户权限」。

## 虚拟手柄 · PC 端（EXE，无需 root）

> 思路和 RM510 那套一致：**遥控器不伪装 USB 设备**，只把摇杆数据用普通 TCP 发到电脑，
> 由电脑上的 ViGEmBus 生成一个标准 Xbox 360 手柄 —— 所以**不需要 root、不碰 `/dev/hidg0`**。

**下载（二选一）**

| 文件 | 用途 |
| --- | --- |
| `OpenRC2-Setup.exe` | 图形安装包：装到本用户目录 + 建桌面快捷方式，装完自动启动（推荐） |
| `OpenRC2-Pad.exe` | 绿色单文件，双击即用，不写注册表 |

**摇杆轴序（重要）**：遥控器送上来的是 DUML 原始六轴 `[0]副翼 [1]俯仰 [2]油门 [3]偏航 [4][5]波轮`；
PC 端映射为 **左摇杆 = 偏航(X) + 油门(Y)**、**右摇杆 = 副翼(X) + 俯仰(Y)**。
早期版本把 `[0]` 当左杆 X、`[3]` 当右杆 X，所以「推油门模拟器没反应」——1.30.0 已修正。

**界面**：磨砂玻璃卡片风、深色半透明、内置 3 张背景图可一键切换（也能选自己的图）；
左栏切「Xbox 360 手柄 / 穿越机手感(FVP)」、调死区与 Expo 曲线；右栏是六路摇杆实时条 + 帧率。

**用法**

1. 装 ViGEmBus 驱动（`http://127.0.0.1` 那套不需要，只要一次装驱动）：<https://github.com/nefarius/ViGEmBus/releases>
2. 遥控器插 USB 或 `adb connect <IP>:5555`，程序启动时会自动执行 `adb reverse tcp:9999 tcp:9999`
3. 点「启动」→ 遥控器端开数据流：**设置 → 诊断 → 虚拟手柄数据流**（或 `adb shell am start -n com.rc2.etx/.MainActivity --es action pad --ei on 1`）
4. 打开「打开 joy.cpl 验证」能看到 `Xbox 360 Controller for Windows` 在动

**协议**：遥控器 → PC，TCP `9999`，一行一个 JSON：`{"t":毫秒,"ax":[6路,0..2048],"us":[8路,988..2012]}`，约 **116 fps**（比 dji_link 的 9 Hz 快一个数量级）。

## 虚拟手柄（旧：命令行 Python 版，无需 root）

这条路是照 RM510 那套「遥控器 → adb reverse → PC 造手柄」的做法实现的，和上面的 USB HID 是**两条独立的路**：

| | USB HID（内置） | 虚拟手柄（ViGEm） |
|---|---|---|
| 需要 root | **是**（建 gadget、绑 UDC） | **否** |
| 传输 | USB 线，`/dev/hidg0` 写报告 | `adb reverse` 的本地 TCP，或同一 WiFi |
| PC 侧 | 免装驱动，系统自带 Xbox 兼容 HID | 需装 ViGEmBus 驱动 + `pip install vgamepad` |
| 稳定性 | 绑 gadget 会顶掉 USB adb，换线要重建 | 不断线，断了自动重连 |
| 适用 | 只想插线玩游戏机/模拟器 | 不想 root、要调试、要无线 |

### 用法

1. 遥控器：`设置 → 诊断` 里能看到数据流状态；或命令行打开
   ```bash
   adb shell am start -n com.rc2.etx/.MainActivity --es action pad --ei on 1
   ```
   （关掉把 `--ei on 1` 换成 `--ei on 0`）

2. PC：
   ```bash
   pip install vgamepad
   # 装一次 ViGEmBus 驱动：https://github.com/nefarius/ViGEmBus/releases
   python pc/OpenRC2-虚拟手柄.py
   ```
   脚本会自己 `adb reverse tcp:9999 tcp:9999`，然后在系统里造出一个 **Xbox 360 手柄**。
   验证：`Win+R → joy.cpl → Xbox 360 Controller → 属性`，推摇杆看十字线。

3. 只想验证数据有没有通：`python pc/OpenRC2-虚拟手柄.py --dry`，会打印收到的帧和帧率。

### 协议（想自己写客户端可以照着实现）

设备端连 `127.0.0.1:9999`，**8ms 一帧、一行一个 JSON**：

```json
{"t":1793584460143,"ax":[1024,1024,1024,1024,1024,1024],"us":[1500,1500,1500,1500,1500,1500,1500,1500]}
```

- `t`：设备毫秒时间戳
- `ax`：六路原始值，`0..2048`，中位 `1024`，满行程 `1024±660`（顺序：左水平、左竖直、右竖直、右水平、左波轮、右波轮）
- `us`：八路输出 PWM（`1000..2000`，HID 未启用时为 1500）

实测：6 秒收 694 帧、**≈116 fps**、66 KB（`pad_test.txt`）。

## 常见问题（1.30.1 修掉的坑）

| 现象 | 原因 | 现在怎么样 |
|---|---|---|
| 一重启软件就“不好使”，要手动开一次 | 数据流只在开关文件存在时自启，而这个文件从来没人写过 | 默认常开 + 开关落盘 `padbridge.on`；实测重启后自动 477 帧/5 秒（≈95 fps） |
| 推油门模拟器没反应 | 六轴映射错位（DUML 序 `[0]副翼 [1]俯仰 [2]油门 [3]偏航`） | 左杆=偏航(X)+油门(Y)，右杆=副翼(X)+俯仰(Y) |
| 系统设置改了又回去 | EdgeTX 只在“离开页面/按 RTN”时写 `radio.yml` | 新增 `onPause` 落盘：切走、回桌面、退出前都会刷写 |
| PC 端打不开 | Windows 拦未签名 exe | 用附带 `OpenRC2-启动.bat`（先 Unblock 再启动）；日志在 `%LOCALAPPDATA%\OpenRC2\log.txt` |

## 声音（开机音 / 告警音）现状——实测结论

| 项目 | 现状 | 证据 |
|---|---|---|
| 系统开机动画 | **原厂，未改动** | `/product/media/bootanimation.zip` = 3,619,610 B，md5 `b559b3accd82bea293ca6407dd40e7d0`，与出厂备份逐字节一致 |
| 系统开机声音 | **这台机器本来就没有** | `/product/media/audio/ui/` 里只有 `ChargingStarted/Dock/Effect_Tick/InCallNotification/Keypress*` 等 OGG，**没有任何 boot/poweron 音频**；`getprop ro.config.poweron_sound`、`persist.sys.boot.sound` 均为空 |
| App 按键音 | 有（`app/src/com/rc2/etx/audio/Sfx.java`，ToneGenerator 走 `STREAM_MUSIC`，32 ms 短音，40 ms 节流） | 真机按键可闻 |
| EdgeTX 告警音（低电量/开关/摇杆位置…） | **目前静音** | `libetx.so` 里 `audio` 模块没有后端驱动：EdgeTX 的 `audioTask` 调 `audioDriver.start()`，我们移植时没有实现这个 driver，也没有把音量/波形回调到 Java |

要让 EdgeTX 自己的告警音响起来，需要在 so 侧补一个 `AudioDriver` 桩：把 `audioQueue` 里的 `(freq, len, pause)` 通过 JNI 回调给 Java，由 Java 用 `ToneGenerator`/`AudioTrack` 发声。这属于 so 改动，需要重新编 `libetx.so` 再发版；不是改 Java 就能解决的。

## 1.37.x 关键修复（摇杆与声音）

| 版本 | 内容 |
|---|---|
| 1.37.0 | 删除非法上行心跳（`01 01 08 0a`），退避改 ≥6s；连接不再被自己毒死 |
| 1.37.1 | **DUML 双形态切帧**：支持裸 DUML（无 `55 CC 30 75` 头），修复“帧错位导致摇杆帧几乎全丢” |
| 1.37.2 | 摇杆帧判定收紧 `len==30 && cmdSet==6 && cmdId==0xAE`；每秒 `stat` 日志（hz/stickHz/bad/gap150） |
| 1.37.3 | 新增 `--es action vbat --ei mv <mV>` 自测钩子；原生音频 `RegisterNatives(audioTone)=0` 确认注册成功 |

真机实测（1.37.2）：`stickHz=9~10 / bad=0 / gap150=0`，22 秒内 196 帧（修复前 1 帧）。
摇杆数据源为 `127.0.0.1:40007` 的 dji_link，`8.8–9.6 Hz` 是硬上限（详见 `re/04-速率上限与槽位结论.md`）。

### 1.39.0 摇杆预测插值（10Hz → 100Hz 手感）

`app/src/com/rc2/etx/radio/StickSmooth.java` 不再"钳在上一帧与本帧之间"，改为**速度预测 + 过冲钳制**：

```java
ahead = min(dt, 90ms);  decay = dt>90 ? exp(-(dt-90)/140) : 1;
p = cur + vel * ahead * decay;                 // 按速度往前推
cap = step * 1.15 + 0.06;                      // 过冲上限 = 最近一步的 1.15 倍
p = clamp(p, cur - cap, cur + cap);            // 反向即停：vel 在方向反转时清零
```

离线仿真（1.5Hz 正弦、10Hz 采样、100Hz 输出）：

| 方案 | MAE | 过冲峰值 | 等效延迟 |
|---|---|---|---|
| 旧（钳在 prev→cur） | 0.7684 | 1.0000 | ≈140 ms |
| 新（预测插值） | **0.2652** | 1.0000 | **≈50 ms** |

方向反转时 `vel` 立刻归零，这是历史上"抽搐"的唯一来源，因此这次不会重现。
