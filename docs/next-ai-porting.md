# 把 EdgeTX 装进一台 Android 遥控器 —— 原理与做法（交接给下一个 AI）

> 作者：软糖布丁 ｜ 基线：1.46.18 ｜ 机型：DJI RC2（rc331）、RC Pro（RM510）
> 读者：接手本项目的 AI / 开发者。本文讲**怎么成的、为什么这么成、和「复刻」差在哪、换模块要改哪几处**。
> 逐步操作手册见同目录 `RC2项目交接文档.md`；本文是它的「原理篇」。

---

## 0. 一句话结论

这不是重画一个长得像 EdgeTX 的界面，而是**把 EdgeTX 官方 C++ 源码（v2.12.2）编译成 Android 的 libetx.so，原样跑它的混控器、LVGL 界面、模型文件、主题和 Lua 脚本**；我们只补了它原本由单片机负责的那一层（HAL：摇杆采样、屏幕刷新、按键、音频、SD 卡），再用一层很薄的 Java（com.rc2.etx）接到 Android 屏幕上。

**判据**：把 APK 里的 libetx.so 换回官方源码重编，界面、混控、模型页、radio.yml、THEMES/*.yml、SCRIPTS/TOOLS/ExpressLRS.lua 全都照旧工作 —— 这就是「真移植」和「复刻」的分水岭。

---

## 1. 整体分层

```
Android 应用层（Java，我们写的）
  MainActivity / RadioView（七页签：原生/通道/混控/输出/校准/诊断/设置）
    NativePanel   480x272 位图 -> Canvas 放大铺满 1920x1080
    Radio.java    10 ms 心跳：拉摇杆、算插帧、喂核心、取通道、推输出
    DumlStick     127.0.0.1:40007 收 DUML 摇杆帧（6 x u16le）
    StickSmooth   只在两个原生帧之间线性插值（不预测、不放大）
    PadStream     JSON 一行一帧 -> PC 端 ViGEm 虚拟手柄
        |  JNI（etx_init / etx_tick / etx_touch / etx_key / etx_render ...）
libetx.so = EdgeTX v2.12.2（targets/horus）x LVGL 8
  mixer / 模型读写 / 主题 / Lua / 设置页 / 快速菜单（官方原码）
  HAL（我们补的）：摇杆 ADC、LCD 帧缓冲、按键、音频、SD 路径
```

EdgeTX 认为自己在跑一块「彩色 Horus 类遥控器」，只通过 HAL 的若干函数与外界打交道。把那些函数落到 Android 上，它就活了。

---

## 2. 四个技术支点（缺一不可）

### 2.1 编译：把固件编成 .so

- 目标板选 horus（480x272 彩色屏 + 按键式），这样 EdgeTX 自带的硬件页、模型标签、快速菜单全部可用。
- 必须带 **armeabi-v7a**：ROM 的 PackageManager 对新装应用强制 abiOverride=armeabi-v7a（logcat: `[DJIDEBUG] installPackagesLI add abiOverride:armeabi-v7a`），只带 arm64-v8a 会 `INSTALL_FAILED_NO_MATCHING_ABIS(-113)`。
- APK 必须 minSdk 29 / targetSdk 29，否则被 DJI 的 ReviewPermissionsActivity 劫持。
- 音频：**不要把 targets/simu/no_audio.cpp 链进去**（它静默吞掉所有声音）；改在 JNI_OnLoad 里 RegisterNatives 注册 audioTone(III)Z，由 Java 侧 AudioTrack 出声。

### 2.2 输入：摇杆值怎么进核心

1. RC2 的摇杆不在标准输入设备里，而在 DJI 的 dji_link 服务上：本机 127.0.0.1:40007，DUML 帧 cmdSet 6 / cmdId 0xAE，6 路 u16le。
2. **硬上限 9–10 Hz**（实测 hz=8.85，帧间隔中位 101.4 ms），这是后面所有延迟/抖动讨论的地基。
3. 轴序 lx=RUD, ly=THR, rx=AIL, ry=ELE；中点 1024，行程 364..1684。
4. 喂核心时换成它的 7 轴 MCU 顺序 RH, RV, LV, LH, P1, P2, P3，值域 0..2047（EtxNative.setInputs(axes7, 7, sw, 4, knobs, 2)）。
5. **插帧只做一件事**：在两个真实样本之间按时间线性插值（窗口 clamp(tLast-tPrev, 20, 45) ms），把 10 Hz 台阶抹平到屏幕刷新率；**不预测、不外推、不放大** —— 用户明确要求，也是唯一不会自激的做法。

> 反面教材（已删除）：早期做过速度/加速度外推、「极速档」增益、行程放大，结果推满打不满、回中过冲、抖动。10 Hz 数据上做预测必然震荡。

### 2.3 输出：画面怎么显示

- EdgeTX 每帧把 LVGL 渲染进一块 480x272 的 ARGB 缓冲（etx_render(px, w, h)）。
- NativePanel.onDraw() 用 Canvas.drawBitmap 放大到全屏：`float s = full ? Math.max(sx, sy) : Math.min(sx, sy)`，clipRect 裁掉溢出；最近邻保持像素锐利，另加一层 2x EPX 超采样抗锯齿。
- **做不到真正的 2x 逻辑分辨率**：radio/src/targets/horus/hal.h 里 LCD_W 480 / LCD_H 272 是编译期常量，字体与布局按像素排。想更清晰只能换字体资源或换目标板。

### 2.4 交互：触摸与按键

- EdgeTX 彩色 UI 是给**五向键 + 旋钮**设计的，原语是「聚焦 + ENTER 进入 + 旋钮改值」。
- 触摸语义定成 B 方案：点某一行只让它获得焦点、**不进入**；进入与改值仍走 ENTER / 方向键。实现是从 EdgeTX 自己的 Table 取行号（不按几何猜），命中后 lv_obj_invalidate 让焦点框跟随手指。
- 按键注入 EtxNative.press/release -> native etx_key(key, down)。**键码以 radio/src/hal/key_driver.h 的 enum EnumKeys 为唯一真值**：
  KEY_MENU=0, KEY_EXIT=1, KEY_ENTER=2, KEY_PAGEUP=3, KEY_PAGEDN=4, KEY_UP=5, KEY_DOWN=6, KEY_LEFT=7, KEY_RIGHT=8, KEY_PLUS=9, KEY_MINUS=10, KEY_MODEL=11, KEY_TELE=12, KEY_SYS=13, KEY_SHIFT=14, KEY_BIND=15。
  屏幕底部那排 ↑↓←→ENT RTN PG↑ PG↓ 只是**按钮下标 0..7**，不是键码（早期搞错，浪费一天）。另外 keys.cpp 里 EVT_KEY_LONG(KEY_PAGEDN) 会被翻译成 KEY_SYS，所以「长按 PAGE DN」= 开系统菜单。

---

## 3. 和「复刻」到底差在哪

| 维度 | 复刻（Replica） | 本项目（真移植） |
|---|---|---|
| 界面 | 自己用 Android/Flutter 重画，长得像 | EdgeTX 原版 LVGL 渲染，主题走 THEMES/*.yml |
| 混控 | 自己写 AETR 与混控 | EdgeTX 官方 mixer，支持 MODELS/model*.yml 全部特性 |
| 模型文件 | 私有格式 | 直接读写 radio.yml / model1.yml，与真机可互换 |
| Lua | 不支持 | 支持 SCRIPTS/，TOOLS 里 ExpressLRS、ExpressLRS gyro、Rotorflight 2 实测可开 |
| 升级 | 跟自己的代码走 | 上游发新版，重编一个 .so 就能换（ETX_SO=xxx.so ./scripts/build-apk.sh <ver>） |
| 许可证 | 自己定 | **必须 GPLv2**：EdgeTX 被编进 APK，整个 App 都得开源 |

复刻是「仿一个遥控器 App」；我们是「给真 EdgeTX 造一副新的内脏和外壳」。

---

## 4. 换到别的模块/机型要改哪几处

只动这些，**别动 EdgeTX 源码**：

1. **输入源**（DumlStick 或等价类）
   新机型摇杆从哪来？binder（RC Pro 用 ServiceManager.getService("protocol") + IProtocolManager.transact(4) + IPackListener）？串口？标准 InputDevice？
   只要最终产出「6 路归一化到 -1..+1」的样本序列，其余全不变。
2. **屏幕与渲染**（NativePanel）
   换目标板就改 hal.h 的 LCD_W/LCD_H 与缩放策略；分辨率不变则一行不改。
3. **按键映射**（MainActivity 的 action=key + NativePanel.queueKey）
   新机型有哪些物理键、映射到哪几个 EnumKeys；缺的用屏幕按键条补。
4. **SD 路径与属主**
   /sdcard/Android/data/<pkg>/files/sd，**铺完必须 chown -R <uid>:<gid> 成应用自身用户**（本机 10047:1078），否则 EdgeTX 静默写不进去（表现为「设置保存不了」）。
5. **音频**（etx_audio.cpp + Java AudioTrack）
   保持「EdgeTX 混音 -> 回调给 Java 出声」，别链 no_audio.cpp。
6. **对外输出**（CRSF / USB HID / 虚拟手柄）
   通道统一从 etx_channels_us(int*) 取 16 路 us，再分发到 CRSF 编码器、/dev/hidg0 报告或 JSON 推流。

**JNI 契约（换壳照抄）**

| 函数 | 语义 |
|---|---|
| etx_init(const char* sdPath) | 装载 SD、初始化 EdgeTX 与 LVGL，0 表示成功 |
| etx_tick(int ms) | 推进时钟、混控、UI 事件；**必须固定节奏（10 ms）** |
| etx_set_inputs(axes, nAxes, sw, nSw, knobs, nKnobs) | 喂 7 轴 / 4 开关 / 2 旋钮，域 0..2047 |
| etx_touch(int action, int x, int y) | 触摸，坐标是 **480x272 逻辑坐标** |
| etx_key(int key, int down) | 键按下/抬起，键码见 EnumKeys |
| etx_render(int* argb, int w, int h) | 取当前帧 ARGB |
| etx_channels_us(int* out16) | 取 16 路输出（us） |
| etx_play_tone(int freq, int len, int pause) | 走 EdgeTX 自己的音频混音器 |

---

## 5. 踩过的坑（照抄能省几天）

1. **DUML 槽位**：9C47 最多 2 个客户端，断开不立刻释放，**重连必须退避 >=5 秒**；DJI Fly（dji.go.v6）会抢槽，必要时 am force-stop。
2. **DUML 有两种帧形态**：带 8 字节 hp 头的 38 字节帧、裸 DUML 30 字节帧，解析器要同时认（判 buf[1..3] == CC 30 75）。
3. **不要在 10 Hz 数据上做预测**（见 2.2）。
4. **触摸语义**：点行只聚焦，进入/改值走 ENTER，否则一碰就误入子页。
5. **App 悬浮按钮抢事件**：原生画面右上角 y≈140 一带是「退出全屏/复位」，点页签要避开或改用按键。
6. **长路径写文件可能报 EISDIR**：脚本用 python heredoc 落盘（注意别让 heredoc 被截断），改 Python 源码前先 ast.parse 再写（本项目曾两次写坏 padcore.py）。文件名带全角括号时 write 工具也会报 EISDIR，所以本文文件名用 ASCII。
7. **GitHub 推送**：Windows schannel 报 CRYPT_E_NO_REVOCATION_CHECK 时用
   git -c http.schannelCheckRevoke=false -c http.sslVerify=false push。
8. **发布必须两端同包**：遥控器端 APK/安装器与电脑端安装器打进同一个 OpenRC2-OneClick.zip、同一个 tag。

---

## 6. 已知限制（别当 bug 反复查）

- 摇杆源 9-10 Hz、屏幕 96 Hz：**延迟下界由硬件决定**，插帧只让运动看起来连续，不减真实延迟。
- 480x272 是编译期常量：**全屏放大后必然偏软**。
- EdgeTX 部分页面对 HAL 完整性要求高（硬件页、通道/混控的轴页），在精简 HAL 上可能崩；取证见第 7 节。
- 蓝牙 HID 手柄不可行：本 ROM 只有 HidHostService，没有 HidDeviceService。
- 机身射频不能跑 ELRS：DJI 用私有 SDR + ASIC，不是通用 2.4G 收发芯片。

---

## 7. 崩溃取证（接手者必读）

```
adb shell cat /sdcard/Android/data/com.rc2.etx/files/crash_native.txt   # signal + 出错地址 + 时间
adb shell cat /sdcard/Android/data/com.rc2.etx/files/crash_java.txt     # 完整 Java 栈
```

注意区分真崩溃与测试程序崩溃：/data/tombstones/tombstone_* 里若 `name: probe_so`，那是离线探针（src/tests/probe_so.cpp）跑出来的，与本 App 无关（历史 ModelLabelsWindow::buildBody 的 fault addr 0x4 全是它）。

---

## 8. 源码地图

| 想找 | 位置 |
|---|---|
| 摇杆解析 / DUML | app/src/com/rc2/etx/input/DumlStick.java、re/ 下的逆向记录 |
| 插帧 | app/src/com/rc2/etx/radio/StickSmooth.java（value() / sample() / setAxisCal()） |
| 主心跳 | app/src/com/rc2/etx/radio/Radio.java（tick()、fillNativeAxes7()） |
| 画面 | app/src/com/rc2/etx/ui/NativePanel.java |
| 七页签 | app/src/com/rc2/etx/ui/RadioView.java |
| 校准页 | app/src/com/rc2/etx/ui/CalibPage.java |
| JNI 胶水 | RC2-EdgeTX-Native/src/jni/etx_jni.cpp |
| HAL 实现 | RC2-EdgeTX-Native/src/hal/（etx_adc.cpp、etx_board.cpp、etx_audio.cpp、etx_mixer.cpp ...） |
| EdgeTX 源码 | RC2-EdgeTX-Native/vendor/edgetx-src-2.12.2/ |
| PC 端 | pc/padcore.py、pc/web/index.html、pc/device_setup.py |

---

## 9. 如果继续做（建议顺序）

1. 用第 7 节的取证文件定位「点硬件页 / 自带校准 / 轴页闪退」，补齐 HAL 里缺失的 hw_inputs / hw_intmodule / hw_serial 表。
2. 渲染降耗：把 2x 超采样改成按需（静止帧不超采样），压掉 render 偶发 170 ms 尖峰。
3. 「延迟优化」入口提到设置页显眼位置（用户多次要求）。
4. 串口（USB CDC）通路未实现；要做 ELRS 直连时再补。
5. 真实告警音的端到端验证（目前只验证了音频链路能出声）。

---

*与代码同源：参数冲突时以仓库代码和 docs/08-版本变更与回归.md 为准。*
