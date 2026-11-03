# 18 · RC Plus 2（RC PLUS 2）兼容分析

> 分析对象：`C:\Users\刘子墨\Downloads\Plus2 Xbox手柄_1.0.apk.1`
> SHA-256 `4808f89823f020637bc672fcf538094eb765144457647b71d1280a954aa0b455`，包名 `local.plus2.xbox`，versionName 1.0
> 分析方式：静态反编译（apktool 2.11.1 出 smali + androguard 4.1.4 出 Java）。**全程只读，未在真机运行，未做运行时抓包。**
> 编写时间：2026-11-03。作者：plus2-port（Teammate）。

---

## 0. 一句话结论

**RC Plus 2 的摇杆/拨轮走的是和 RC Pro 完全相同的 DJI binder：`ServiceManager.getService("protocol")` → `com.dji.protocol.IProtocolManager` → `transact(4)` + select 包 → `com.dji.protocol.IPackListener` 回调 → 6×u16 小端，中点 1024、行程 ±660。**

**我们的 `input/RcProService.java` 在传输层已经逐字节兼容它**（`MCU_PACK` 20 个 int 我核对过，与参照包一字不差）。**差距不在「怎么读」，而在「读出来怎么用」**：参照包把 6 个通道映射成 XInput 手柄，轴序是 **ch3→lx、ch2→ly(取反)、ch0→rx、ch1→ry(取反)**（这 4 条已被 `RCPro-资料/d3` 源码独立证实）；
**ch4/ch5 在 Android 端是「左/右波轮」（双向、中位 1024）**，只有 PC 端接收器才把它们映射成 LT/RT 扳机（且做了双向→单向变换，见 §3.2.2）。
我们自己的 `Radio.applyRcProButtons()` 把 ch4/ch5 当成「P1/P2 开关」覆盖通道——这与两端口径（Android 端双向波轮 / PC 端扳机）**都不一致**，是「拨杆没有适配」的可疑原因，**但尚需用户确认手感后才能定论**（见 §6 P0-1）。

按键是第二条独立通路：**AccessibilityService 的 `onKeyEvent` 过滤 `InputDevice` 名字**（`contains("DJI") || equals("gpio-keys") || contains("qpnp")`），**不是 `/dev/input`，不是 TCP，不是私有 HAL**。

---

## 1. 它到底怎么读到 RC Plus 2 的摇杆/拨轮？

### 1.1 通路 = DJI 私有 binder（证据链完整）

`smali/local/plus2/controls/McuReader.smali`：

| 行号 | 证据原文 |
|---|---|
| 113 | `const-string v0, "android.os.ServiceManager"` |
| 131 | `const-string v1, "protocol"` |
| 247 | `const-string v3, "com.dji.protocol.IProtocolManager"` |
| 249 | `invoke-virtual {v1, v3}, Landroid/os/Parcel;->writeInterfaceToken(...)` |
| 253 | `invoke-virtual {v1, v3(1)}, Landroid/os/Parcel;->writeInt(I)V` |
| 295 | `const/4 v4, 0x4` → `invoke-interface {v3, v4, v1, v2, v0}, Landroid/os/IBinder;->transact(...)` |
| 388-409 | `:array_0 .array-data 4` → 20 个 int（见下） |
| 161 | `const-string v1, "protocol service unavailable"`（服务拿不到时的报错） |

`McuReader$1.smali`（回调）：

| 行号 | 证据原文 |
|---|---|
| 46 | `const-string v1, "com.dji.protocol.IPackListener"` |
| 44 | `const v0, 0x5f4e5446`（= `'$NTE'`，`INTERFACE_TRANSACTION`，标准 AIDL 桩） |
| 80 | `invoke-virtual {p2, v1}, Landroid/os/Parcel;->enforceInterface(...)` |
| 101-104 | `:goto_1` 循环 `const/16 p3, 0xf` —— 跳过 **15 个 int** |
| 114 | `invoke-virtual {p2}, Landroid/os/Parcel;->readInt()` → 后面当长度用 |
| 120 | `invoke-virtual {p2}, Landroid/os/Parcel;->createByteArray()[B` |
| 137 | `const/16 p3, 0xc` → `if-lt p2, p3`（要求 `bytes.length >= 12`） |
| 146-160 | 小端解码：`aget-byte` / `and-int/lit16 0xff` / `shl-int/lit8 0x8` 后 `or-int` |
| 139 | `const/4 p2, 0x6` → `new-array`，即 **6 个通道** |

### 1.2 select 包的 20 个 int（原文，McuReader.smali:388-409）

```
0x55, 0x1, 0x0, 0x0, 0x4, 0x2, 0x0, 0x6, -0x1, 0x0,
0x2, 0x0, 0x0, 0x6, 0x4, 0x0, 0x0, 0x0, 0x12c, 0x0
```

与 `app/src/com/rc2/etx/input/RcProService.java:39-41` 的 `MCU_PACK` **逐项一致**（`0x12c = 300`）。`RcProService` 还额外把 `p[13]/p[14]` 改写成 `packWithCmd(6,4)`——而参照包原文里 index 13/14 本来就是 `6,4`，所以**默认路径下两边的 Parcel 完全相同**。

### 1.3 轮询节奏

- `McuReader.smali:366` `const-wide/16 v2, 0x28` → **40ms** 重投递
- `McuReader.smali:226` `const-wide/16 v3, 0x2bc` → **700ms** 超时后才允许重发（防堆积）
- 所以名义 25Hz，实际受 MCU 回复速度约束。

对比我们 `RcProService.pollOnce()`：忙等 10ms（~100Hz）无等待背压控制。**这不是错误，但 RC Plus 2 上建议先降到 25-40ms 观察**（见 §6 P0-2）。

### 1.4 排除掉的其它通路（都是「查了、没有」，不是「没查」）

对 `classes.dex`（49988 B）做了字节级检索，命中数如下：

| 模式 | 命中 |
|---|---|
| `/dev/input` | **0** |
| `ABS_`（任何 ABS_ 轴常量） | **0** |
| `getevent` | **0** |
| `40007` | **0** |
| `dev/uhid` | **0** |
| `su` / `root` 提权 | **0** |
| `calib` / `offset` / `center` / `trim` / `zero` | **0** |

dex 里出现的**全部**绝对路径只有 3 条：

```
/data/local/tmp/plus2-xbox.pid
/system/bin/hid
/system/bin/sh
```

三条都属于 §4 的「本机虚拟手柄」辅助进程，**不参与输入采集**。

> 结论：**没有 `/dev/input` 直读，没有 TCP 40007，没有私有 HAL/so（APK 里根本没有 `lib/` 目录，只有 7 个条目）。** 唯一的采集通路就是 §1.1 的 binder + §3.6 的 AccessibilityService。

---

## 2. 进程名 / `android:process` —— 同样的白名单诡计，一字不差

`AndroidManifest.xml`（apktool 解出，原文）：

```xml
<manifest ... package="local.plus2.xbox" ...>
  <uses-permission android:name="android.permission.INTERNET"/>
  <application android:allowBackup="false" android:label="Plus2 Xbox手柄"
               android:process="com.dpad.fuli"
               android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <activity android:name="local.plus2.controls.MainActivity" .../>
    <service  android:name="local.plus2.controls.ControlService"
              android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" .../>
  </application>
</manifest>
```

**`android:process="com.dpad.fuli"` 挂在 `<application>` 上** —— 整个 App（Activity + AccessibilityService + McuReader 线程）**全部**跑在 `com.dpad.fuli` 这一个进程里。

这与我们 `app/AndroidManifest.xml:57` 的写法不同：我们只把 `RcProService` 放进 `com.dpad.fuli`，主进程仍是 `com.rc2.etx`。

**这条差异很关键**：参照包的做法说明「白名单进程名」不只是一个可用路径，而是 **DJI 只认这个进程名**。如果 RC Plus 2 上 `getService("protocol")` 在 `com.rc2.etx` 里返回 null，那就意味着 **App 里任何直接调用（比如诊断页、校准页）都必须跨进程走 `RcProService`**，不能图省事在主进程里 `transact`。我们目前正好是这个结构，**这点是对的，别改**。

其他 manifest 事实：
- 只有 `INTERNET` 一个权限，**没有** `android.permission.READ_INPUT_STATE` 之类；
- `minSdkVersion=29`、`targetSdkVersion=30`；
- 没有 `<uses-feature android:name="android.hardware.usb.host">`，**没有 USB 路径**；
- 没有 `<service>` 之外的进程声明。

> 待真机确认：`com.dpad.fuli` 是否仍然准确（参照包与我们的 RC Pro 实现都验证过这个字符串有效，但 RC Plus 2 固件未实测）。

---

## 3. 轴序 / 取值范围 / 校准

### 3.1 归一化公式（`ControlService.smali:887-925` 的 `private float norm(int)`，原文）

```smali
add-int/lit16 p1, p1, -0x400        # p1 -= 1024          → 中点 1024
int-to-float p1, p1
const/high16 v0, 0x44250000         # 660.0f              → 半行程 ±660
div-float/2addr p1, v0
min(1.0f, x) ; max(-1.0f, x)                            # 夹到 [-1,1]
abs(x) < 0x3d75c28f (0.06f) → 0                          # 6% 死区
```

- **中点 `0x400` = 1024**
- **半行程 `660.0f`** ⇒ 物理端点 364 / 1684
- **死区 6%**（`0.06f`）
- 类型：**u16 小端**，取值 0..2047（11bit ADC）

这套数字和我们 `DumlStick.java:30-32`（`ADC_MID=1024`、`ADC_SPAN=660f`）、`Radio.djiToEtxAdc()`（`(raw-1024)*1024/660`）**完全一致**。

> `DashboardView` 里有**第二份** `norm()`（`DashboardView.smali:1828`），同样 `-0x400` / `660.0f`，但**没有 6% 死区**。两份不一致是参照包自己的瑕疵，不影响我们——**以 `ControlService.norm()` 有死区的那份为准**。

### 3.2 轴序（`ControlService.report()`，androguard 反编译，原文）

```java
.put("lx", norm(v4_0[3]))
.put("ly", -norm(v4_0[2]))     // 取反
.put("rx", norm(v4_0[0]))
.put("ry", -norm(v4_0[1]))     // 取反
.put("lt", v9_0)               // 默认 |norm(ch[4])|；按住 LT 标签时强制 1.0
.put("rt", v11_0)              // 默认 |norm(ch[5])|；按住 RT 标签时强制 1.0
.put("buttons", v15)           // bitmask，见 §3.4
.put("hat", v6_4)              // 0..8，见 §3.4
.put("active", v1_2)           // 采样新鲜度：now - sampleAt < 300ms
```

`DashboardView.smali:2207-2250` 交叉验证了轴→控件的对应关系：

- `smooth[3]` / `-smooth[2]` → 一个 `stick(...)`（左杆）
- `smooth[0]` / `-smooth[1]` → 另一个 `stick(...)`（右杆）
- `smooth[4]` → 第一个 `dial(...)`（**是"表盘"，不是扳机**）
- `smooth[5]` → 第二个 `dial(...)`（**是"表盘"，不是扳机**）

#### 3.2.1 四个摇杆通道（**已被第三方源码独立证实**）

| index | 含义 | 映射 |
|---|---|---|
| **ch0** | 右摇杆 X | `rx`（不取反） |
| **ch1** | 右摇杆 Y | `ry`（**取反**） |
| **ch2** | 左摇杆 Y | `ly`（**取反**） |
| **ch3** | 左摇杆 X | `lx`（不取反） |

**这 4 条与 `RCPro-资料/d3` 的真实源码逐条吻合**（`d3/01_MainActivity.java:2074-2077`）：

```java
float lx = mcu ? State.mcuNorm(3) : State.x;
float ly = mcu ? -State.mcuNorm(2) : State.y;
float rx = mcu ? State.mcuNorm(0) : State.rx;
float ry = mcu ? -State.mcuNorm(1) : State.ry;
```

归一化也一致（`d3/01_MainActivity.java:1058-1060`）：
`mcuNorm(i) = (mcu[i] - center[i]) / 660f`，`center[]` 默认 `{1024,1024,1024,1024,1024,1024}`。
→ **4 条轴序是可信结论，不是推测。**

#### 3.2.2 ch4 / ch5 = 左/右波轮 —— **但"映射到 LT/RT"只是 PC 端的取舍**

> ⚠️ **这一条此前写得不够精确，2026-11-03 由 rcpro-fix 只读复核后修正。**
> 原先写成「ch4→LT、ch5→RT」像是"实测轴序结论"，**这是错的**。
> 准确说法是：**Android 端只认它们是波轮；把它们当扳机是 PC 端接收器的映射选择，且两端语义不同。**

**（A）Android 端实测口径 —— 波轮，双向，中位 1024**

`d3/01_MainActivity.java:1451-1452`（绘制左右波轮）：

```java
float lwv = mcu ? clamp1((State.mcu[4] - 1024f) / 660f) : State.z;
float rwv = mcu ? clamp1((State.mcu[5] - 1024f) / 660f) : State.rz;
wheelArc(cv, ..., lwv, "左波轮");
wheelArc(cv, ..., rwv, "右波轮");
```

- `clamp1()`（`:1294-1296`）= 夹到 **`[-1, +1]`**，**双向**；
- 中位 `1024`，行程 `±660`；
- 界面标签就写「左波轮」「右波轮」（`:1632-1633` 自检页、`:1903-1904` 通道表同样标注）。

→ **Android 端从未把它们当扳机。**

**（B）PC 端映射选择 —— 当扳机，且做了双向→单向变换**

`d3/02_rcpad_app.py:96-110`：

```python
wmin = float(self.cfg.get("wheel_min", 366))
wmax = float(self.cfg.get("wheel_max", 1684))
span = max(1.0, wmax - wmin)

def trig(i):
    t = (mcu[i] - wmin) / span
    return int(max(0.0, min(1.0, t)) * 255)

lt = trig(4)
rt = trig(5)
```

两端语义对照：

| | Android 端 | PC 端 |
|---|---|---|
| 物理对象 | 左波轮 / 右波轮 | 同左（同一路 MCU 数据） |
| 取值范围 | **双向 `-1..+1`** | **单向 `0..255`** |
| 中点 1024 的读数 | **0（中点）** | **≈127**（`(1024-366)/1318≈0.499`） |
| 变换 | `(v-1024)/660` | `(v-366)/1318`，再夹到 0..1 |

> **关键差异**：PC 端把 ±660 的**双向**行程**整体平铺**成 `366..1684 → 0..255` 的**单向**值。
> 因此**静止时扳机停在约 127 而不是 0**，往负方向推也只会读到 0（被钳住）。
> 这是一个"能用但不精确"的映射。

#### 3.2.3 对我们实现的结论

- 我们自己的 `Radio.java` 里 K1/K2 用的是 **Android 端口径：双向、中位 1024** —— **与（A）一致，不需要改**。
- **若要复刻 PC 端扳机行为，必须自己加双向→单向变换**（`(v-366)/1318` 再钳位），
  **不能直接把 Android 端的 ±1 值搬过去**，否则中点会从 0 变成 127 的错位、
  且负向行程会全部丢成 0。
- `Radio.applyRcProButtons()`（`Radio.java:816-820`）把 `raw[4]`/`raw[5]` 当 P1/P2 **开关**
  写进 `axes7[4]/[5]` —— 这个写法**仍待商榷**（详见 §6 P0-1），但**不是**"和参照包矛盾"那么简单：
  参照包 Android 端也只是把它们画成**表盘/波轮**（`dial(...)`），并没有当开关用。

### 3.3 校准数据存哪 —— **不存在**

全 dex 检索 `calib` / `offset` / `center` / `trim` / `zero` 命中 **0**。

归一化是**硬编码 1024/660**，没有 per-device 校准、没有零点漂移补偿、没有存盘。唯一的「可调」是按键映射表（SharedPreferences）。

> 含义：RC Plus 2 上没有可抄的校准算法。我们自己的 `radio/Calib.java` **不能指望从参照包学到什么**，只能自研（而它本来就已经在 `InputState.touch()` 里接好了）。

### 3.4 buttons bitmask（`ControlService.buttonBit()`，`ControlService.smali:177-258`）

标签数组顺序 = bit 序：

```
bit0=A  bit1=B  bit2=X  bit3=Y  bit4=UP  bit5=DOWN  bit6=LEFT  bit7=RIGHT
bit8=START bit9=BACK bit10=LS bit11=RS bit12=LB bit13=RB bit14=GUIDE bit15=(空串,永为0)
```

查找方式：`for (i=0..15) if (label.equals(NAMES[i])) return 1<<i;`（线性、区分大小写、空串直接跳过）。

hat 的 0..8 由 `pressed` 里是否含 UP/DOWN/LEFT/RIGHT 组合推出：

```
0=UP 1=UP+RIGHT 2=RIGHT 3=DOWN+RIGHT 4=DOWN 5=DOWN+LEFT 6=LEFT 7=UP+LEFT 8=无
```

### 3.5 物理按键 → KeyCode（`ControlService.defaultLabel()`，`ControlService.smali:296-390`）

sparse-switch 原文映射：

| Android KeyCode | 十进制 | 标签 |
|---|---|---|
| `0x13` KEYCODE_DPAD_UP | 19 | 上 |
| `0x14` KEYCODE_DPAD_DOWN | 20 | 下 |
| `0x15` KEYCODE_DPAD_LEFT | 21 | 左 |
| `0x16` KEYCODE_DPAD_RIGHT | 22 | 右 |
| `0x17` KEYCODE_DPAD_CENTER | 23 | 中 |
| `0x1b` KEYCODE_CAMERA | 27 | 拍照 |
| `0x66` KEYCODE_BUTTON_L1 | 102 | L1 |
| `0x67` KEYCODE_BUTTON_R1 | 103 | R1 |
| `0x68` KEYCODE_BUTTON_L2 | 104 | L2 |
| `0x69` KEYCODE_BUTTON_R2 | 105 | R2 |
| `0x6a` KEYCODE_BUTTON_THUMBL | 106 | L3 |
| `0x6b` KEYCODE_BUTTON_THUMBR | 107 | R3 |
| `0x82` KEYCODE_MEDIA_RECORD | 130 | 录像 |

> **这是 RC Plus 2 按键的关键答案**：它把物理键**声明成标准 gamepad KeyCode**（L1/L2/R1/R2/L3/R3/DPAD_*）。
> 这比 RC Pro 的「自定义 keycode + 私有位域」容易得多——**我们的按键适配可以直接抄这张表。**

### 3.6 按键通路 = AccessibilityService（`res/xml/accessibility.xml` 原文）

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true" />
```

`ControlService.onKeyEvent()`（`:2877-2950`）的设备名白名单：`contains("DJI") || equals("gpio-keys") || contains("qpnp")`。

`identity(KeyEvent)`（`:532-585`）优先用 **scanCode**：`scanCode>0 ? "scan:"+scanCode : "key:"+keyCode`。

`safe()`（`:1942-1995`）屏蔽 5 个键（注意 `0x1a`=26 是 **KEYCODE_POWER**，不是 CAMERA；CAMERA 是 27/0x1b，它在§3.5 的标签表里）：`KEYCODE_POWER(26)`、`KEYCODE_HOME(3)`、`KEYCODE_BACK(4)`、`KEYCODE_VOLUME_UP(24)`、`KEYCODE_VOLUME_DOWN(25)`。

按键映射存 `SharedPreferences("controls")` 的 `"labels"`（JSON `{"scan:123":"L1", ...}`）。学习流程：UI 点 label → `beginLearn()` 给 10 秒窗口 → 实体按键触发 → 写入。

`observed`（LinkedHashMap）记录所有见过的 scan/key 及其 `keyCode`/`keyName`/`scanCode`，落盘到 `SharedPreferences("controls")/observed` 与 `getExternalFilesDir(null)/latest-input.json`（`persistRecords()`，`ControlService.smali:927-1040`）。

---

## 4. 本机虚拟手柄（uhid）—— 能抄，但**不是 RC Plus 2 兼容所必需的**

`GamepadBridgeMain.java`（943 行 smali）做的是：把 JSON 转成 HID 报文，喂给系统 `hid` 命令注册的虚拟手柄。

证据原文：

| 位置 | 内容 |
|---|---|
| `GamepadBridgeMain.smali:251` | `const-string v2, "plus2.xbox.uhid.v1"`（`LocalServerSocket`，ABSTRACT namespace） |
| `:258` | `const-string v3, "/data/local/tmp/plus2-xbox.pid"` |
| `:281` | `const-string v3, "/system/bin/hid"` |
| `:285-286` | `"/system/bin/sh"` + `"-"` → `ProcessBuilder(new String[]{"/system/bin/sh","/system/bin/hid","-"})` |
| `:370` | `"RC Plus 2 Virtual Xbox Layout"` |
| `:378-380` | `vid = 0x45e`（1118 = Microsoft） |
| `:384-386` | `pid = 0x28e`（654） |
| `:392-394` | `bus = "usb"` |
| `:19-126` | `DESC:[I`，长度 `0x51 = 81` 字节的标准 XInput HID report descriptor |

描述符解码（81 字节）：Usage Page Generic Desktop / Usage Gamepad / **4×16bit 轴 -32767..32767**（X,Y,Rx,Ry）/ **hat switch 9 向** / **16×1bit 按钮**。

关键限制（`GamepadBridgeMain.main` 反编译）：

```java
if ((sock.getPeerCredentials().getUid() != v9_3) &&
    (sock.getPeerCredentials().getUid() != 2000)) break;   // 2000 = shell
```

**只接受「自己 App 的 uid」或「shell(2000)」连接。** 而 `/system/bin/hid` 需要 **root/shell 权限**才能注册 uhid 设备。`MainActivity` 里的原话是「本机虚拟手柄（**重启后需运行激活脚本**）」（`MainActivity.smali:847` 附近）。

> **判定：这条路在 RC Plus 2 上大概率做不到。** 理由：
> 1. 需要 `/system/bin/hid` + root/shell 权限，DJI 的 RC 固件默认不开放；
> 2. 参照包自己都承认要「重启后运行激活脚本」，说明不是开箱即用；
> 3. 即便打通，它产出的是一个**虚拟 Xbox 手柄**，对 EdgeTX/CRSF 出口毫无用处。
>
> **不要投入资源复刻 uhid。**

---

## 5. 与现有实现的差距清单

### 5.1 能直接复用（**零改动**）

| 项目 | 我们的位置 | 理由 |
|---|---|---|
| binder 服务名 `"protocol"` | `RcProService.java:155` | 参照包 `McuReader.smali:131` 同 |
| 接口名 `com.dji.protocol.IProtocolManager` | `RcProService.java:35` | 参照包 `McuReader.smali:247` 同 |
| 接口名 `com.dji.protocol.IPackListener` | `RcProService.java:36` | 参照包 `McuReader$1.smali:46` 同 |
| `MCU_PACK` 20 个 int | `RcProService.java:39-41` | 与 `McuReader.smali:388-409` **逐项相同** |
| `transact(4)` | `RcProService.java:168` | 参照包 `McuReader.smali:295` 同 |
| 15×int 跳过 + readInt + createByteArray | `RcProService.java:72-75` | 参照包 `McuReader$1.smali:101-120` 同 |
| 6×u16 **小端** 解码 | `RcProService.java:78-80` | 参照包 `McuReader$1.smali:146-160` 同 |
| 中点 1024 / 行程 660 | `DumlStick.java:30-32`、`Radio.djiToEtxAdc()` | 参照包 `norm()` 同 |
| 白名单进程名 `com.dpad.fuli` | `app/AndroidManifest.xml:57` | 参照包 `<application android:process>` 同 |

> **传输层不用动一行。** 这是本次分析最省力也最重要的结论。

### 5.2 必须新增/改写

| # | 差距 | 现状 | 参照包行为 | 要改的文件 |
|---|---|---|---|---|
| **G1** | **ch4/ch5 语义不一致（开关 vs 双向波轮）** | `Radio.applyRcProButtons()` 把 `raw[4]`/`raw[5]` 直接写 `axes7[4]/[5]`（P1/P2 两档开关） | **Android 端：左/右波轮，双向 ±1，中位 1024（画成 `dial`）**；PC 端才是 LT/RT 扳机（`(v-366)/1318` 双→单向）。见 §3.2.2 | `radio/Radio.java:816-820` |
| **G2** | **没有按键输入源** | 全项目 `grep AccessibilityService` = **0 命中** | AccessibilityService + `onKeyEvent` + 设备名白名单 | 新增 `input/KeysService.java` + Manifest |
| **G3** | **物理键码表缺失** | `applyRcProButtons()` 用的是 RC Pro 的私有位域（`p1&0x80`…） | 标准 gamepad KeyCode 表（§3.5） | 新增映射表 |
| **G4** | **hat/按键 → EdgeTX 键不足** | `EtxNative.press(nav)` 只有 6 个 nav | 16 键位图 + 9 向 hat | `Radio.java:793-807`（+JNI） |
| **G5** | 轮询太快 | 忙等 10ms（~100Hz） | 40ms 重投递 + 700ms 超时 | `RcProService.java:198-213` |
| **G6** | 没有 `active` 新鲜度语义 | 只有 `connected` 布尔 | `active = (now - sampleAt) < 300ms` | `RcProStick.java` / `Radio.java` |
| **G7** | 校准 | 有 `Calib.java` | 参照包**没有**校准 | ——（我们更强，保持） |

### 5.3 做不到 / 不建议做

| 项目 | 判定 | 证据 |
|---|---|---|
| 复刻 uhid 虚拟手柄 | **不做** | 需 root/shell 跑 `/system/bin/hid`；参照包自述「重启后需运行激活脚本」；且产出的 Xbox 手柄对 CRSF 出口无用 |
| 从参照包抄校准算法 | **做不到** | 全 dex 无 `calib/offset/center`，硬编码 1024/660 |
| `/dev/input` 直读 | **不存在这条路** | dex 里 `/dev/input`、`ABS_`、`getevent` 命中均为 0 |
| TCP 40007 | **参照包不用** | dex 里 `40007` 命中 0；它用 binder。我们 RC Pro 的 40007 是 DJI `dji_link` 的另一条路，**与参照包无关** |

---

## 6. 按优先级排序的适配清单

> 真机条件：**当前设备是 RC2，RC Plus 2 可能不在线**。「能真机验证」一列如实标注。
> 我的可写范围只有 `RC2-EdgeTX\docs\`，以下改动均**未执行**，交 Lead 分派。

### P0-1 · 修正 ch4/ch5 语义（波轮，不是拨杆）★最高优先

- **改哪个文件**：`app/src/com/rc2/etx/radio/Radio.java`，`applyRcProButtons()` 第 816-820 行
- **改什么**：不要再把 `raw[4]/raw[5]` 当成**两档开关**塞进 `axes7[4]/[5]`。
  **准确口径见 §3.2.2**：Android 端它们是**双向波轮**（`(v-1024)/660`，±1，中位 1024），
  最自然的目标是 `InputState.knob1/knob2`（连续量），而不是 `aux`（开关量）。
  **不要照 PC 端的 LT/RT 搬**：PC 端是 `(v-366)/1318` 的**单向 0..255** 扳机，
  静止时读数约 127，负向行程被钳成 0 —— 直接照搬会让中位/行程全错。
- **风险**：**会改变 RC Pro 现有行为**。RC Pro 上 ch4/ch5 按我们自己的注释也是波轮（`RcProStick.java:23`），当前写法本就与注释矛盾。改之前先确认用户在 RC2/RC Pro 上看到的「拨杆」到底来自哪里（很可能是 `duml.btnRaw(1/2)` 的 `p1&0x80` 位域，而不是 binder 的 ch4/ch5）。
- **能真机验证**：⚠️ **部分**。RC2 可验回归（不能变差）；RC Plus 2 需真机。

### P0-2 · 降速 + 加背压（稳定性）

- **改哪个文件**：`app/src/com/rc2/etx/input/RcProService.java:198-213`
- **改什么**：把忙等 10ms 改成「等回调再发下一发」，并加 700ms 超时重发（照 `McuReader.smali:226/366`）。参照包实测 25Hz 就够，我们 100Hz 空转可能把 DJI 服务端队列塞满。
- **风险**：低。`stat*` 计数器已有，可对比 `replyHz`。
- **能真机验证**：✅ RC2 可验（`logcat -s ETXRcPro` 看 `replyHz/skip`）。

### P0-3 · 新增按键服务（AccessibilityService）★「拨杆没适配」的主因之一

- **改哪个文件**：新增 `app/src/com/rc2/etx/input/KeysService.java` + `app/res/xml/accessibility.xml` + `app/AndroidManifest.xml`
- **改什么**：
  1. `accessibility.xml` 照抄 §3.6（`canRequestFilterKeyEvents="true"` + `flagRequestFilterKeyEvents`，**这一条不能少，否则收不到 KeyEvent**）；
  2. `onKeyEvent` 照抄设备名白名单 `contains("DJI") || equals("gpio-keys") || contains("qpnp")`；
  3. `identity()` 照抄 `scanCode>0 ? "scan:"+scanCode : "key:"+keyCode`；
  4. `safe()` 照抄屏蔽 POWER/HOME/BACK/VOLUME_UP/VOLUME_DOWN（实测 keycode 26/3/4/24/25）；
  5. keyCode→标签表照抄 §3.5。
- **风险**：**中高**。
  - 进程问题：参照包把 Service 放在 `com.dpad.fuli`（`<application android:process>`），**我们的 AccessibilityService 如果放主进程 `com.rc2.etx`，可能收不到 DJI 按键**——但也可能 `onKeyEvent` 与进程名无关（Android 侧由系统分发）。**这条必须真机二分验证。**
  - 我们的 manifest `targetSdk=29`，且注释说「targetSdk 必须 <=29 否则 DJI 的 ReviewPermissionsActivity 会劫持启动」——加 Service 时**不要动 targetSdk**。
  - 加 AccessibilityService 会带来「无障碍服务需要用户手动开启」的 UX 成本，以及 `BIND_ACCESSIBILITY_SERVICE` 权限。
- **能真机验证**：⚠️ **RC2 上可验证「能不能收到 DJI 摇杆/按键的 KeyEvent」**（如果 RC2 的摇杆也走 InputDevice，这条能立刻验证一半）；RC Plus 2 专属键需真机。

### P1-1 · hat/按键位图 → `EtxNative.press()`

- **改哪个文件**：`radio/Radio.java:793-807`
- **改什么**：把现在的 6-nav 位域逻辑换成 §3.4 的 16 位 bitmask + 9 向 hat。保留 RC Pro 的 `duml.btnRaw()` 作为 fallback（用「哪个源有数据用哪个」判定）。
- **风险**：中。`EtxNative.press(nav)` 只接受 0..5，若要支持 L1/L2/R1/R2/拍照/录像，需要**改 JNI 侧**——但 `app/jniLibs/**` 和 `RC2-EdgeTX-Native/**` **不在我的可写范围**，需要 Lead 或 native 口的 teammate 配合。
- **能真机验证**：⚠️ 需 JNI 配合，RC2 只能验 nav 部分。

### P1-2 · `active` 新鲜度语义

- **改哪个文件**：`input/RcProStick.java`、`radio/Radio.java`
- **改什么**：照抄 `active = (SystemClock.uptimeMillis() - sampleAt) < 300`（`ControlService.smali:1521-1530`，常量 `0x12c`）。
- **风险**：低。比现在的 `connected` 布尔更准，能避免「刚拔掉还显示连着」。
- **能真机验证**：✅ RC2 可验。

### P2-1 · 跨进程边界纪律（不要在主进程 `transact`）

- **改什么**：**不改代码，立规矩**。参照包整个 App 都在 `com.dpad.fuli`；我们在主进程 `com.rc2.etx`。任何新加的诊断/校准页**必须**通过 `RcProService` 的 Messenger 拿数据，不能自己 `ServiceManager.getService("protocol")`。
- **风险**：低（但违反会导致「在我机器上好的」这类诡异 bug）。
- **能真机验证**：✅ 可验。

### P2-2 · 白名单进程名诊断日志（RC Plus 2 到货后第一条要看的东西）

- **改什么**：在 `RcProService.onCreate()` 里加一条日志，打印 `getService("protocol")` 是否 null + 当前进程名/PID。
- **风险**：无。
- **能真机验证**：✅ **这是 RC Plus 2 到货后第一条要看的日志**。

---

## 7. 真机验收清单（按命令排序）

> 给下一个接手的人：**从上往下照着做，每条都有「期望输出」和「不对时改哪里」**。
> 前置：设备已连上（`adb devices` 能看到 `6UZBM6L02104CA`）。
> 本文档不假设是 RC2 还是 RC Plus 2 —— 命令通用，**期望值分机型注明**。

### 0. 先分清机型（决定后面看哪几行）

```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.display.id
```

| 期望 | 含义 |
|---|---|
| 型号里带 `RM510` 之类 | RC Pro |
| 型号含 `RC Plus`/其他 | RC Plus 2（**注意：Plus2 的真实型号串我们尚未记录，第一次连上请补录到本节**） |

> ⚠️ **未验证**：RC Plus 2 的 `ro.product.model` 实际字符串没见过。接手人第一次连上请把
> 这两条的实际输出补进上表，后面的人就不用猜了。

### 1. 枚举输入设备（**最关键的一条**，决定按键服务是否可用）

```bash
adb shell dumpsys input | grep -E "^  Device [0-9]+:"
```

**RC2 的实测输出**（已确认，2026-11-03）：
```
  Device 3: himax-touchscreen
  Device 4: qpnp_pon
  Device 2: gpio-keys
```
→ RC2 上**没有 DJI 设备**，按键只会命中 `gpio-keys` 的 5 个键
（`gpio-keys.kl`：VOLUME_UP/DOWN、HOME、FOCUS、CAMERA），
对照 `KeysService.defaultMap()` **只有 CAMERA(27)→K_MODEL 有映射**。
**这是已知结果，不是 bug。**

**RC Plus 2 的判读**：

| 输出里出现 | 结论 | 要做什么 |
|---|---|---|
| 名字含 `DJI` 的设备 | 参照包口径命中，开箱即用 | **什么都不用改** |
| `gpio-keys` / `qpnp`，且 §2 里键码是标准 gamepad 码 | 开箱即用 | 什么都不用改 |
| 名字是别的（如 `dji-rc`、`rc_plus2_keys`…） | 白名单不命中 | **改 prefs**：`keysAnyDevice=true`（见 §7.6） |

顺手把 Plus2 的设备名列出来存档：
```bash
adb shell dumpsys input | grep -E "^  Device [0-9]+:"   # 连同下面这条一起贴进文档
adb shell dumpsys input | grep -iE "KeyLayoutFile|Location:"
```

### 2. 抓真实键码（决定映射表要不要改）

装包并**手动开启**无障碍服务后：

```bash
adb shell logcat -c
adb shell logcat -s ETX-Keys
# 然后在遥控器上把所有物理键/拨杆按一遍
```

**期望输出**（每个键一行）：
```
I ETX-Keys: key KEYCODE_BUTTON_L1(102) -> etx 6 down dev=<设备名>
I ETX-Keys: key KEYCODE_BUTTON_L1(102) -> etx 6 up dev=<设备名>
```

**未映射的键会打**（这是**正常**的诊断，不是错误）：
```
D ETX-Keys: 未映射 keyCode=132 name=<设备名> scan=99
```

| 输出情况 | 结论 | 要做什么 |
|---|---|---|
| 一行都没有 | 服务没连上 / 事件没进来 | 先做 §7.3 和 §7.4 |
| 有 `未映射 keyCode=X` | 键码不在默认表 | **改 prefs** `keysMap`，例如 `keysMap="132:4"`（132→ENTER）。改完重启服务 |
| 有 `-> etx N` 但界面不动 | 映射对、链路断 | 看 §7.5 |

> 注意：RC2 上**只会有 CAMERA(27) 一行**（见 §7.1），别期待更多。

### 3. 确认无障碍服务已注册

```bash
adb shell dumpsys accessibility | grep -i -A3 "com.rc2.etx"
adb shell settings get secure enabled_accessibility_services
```

**期望**：
```
com.rc2.etx/com.rc2.etx.input.KeysService
```
出现在 `enabled_accessibility_services` 里，且 `dumpsys accessibility` 里该服务状态为
`bound`/`connected`。

| 输出不对 | 结论 | 要做什么 |
|---|---|---|
| 空 / 不含 `com.rc2.etx` | 服务没开 | 用户手动开；或 `am start --es action a11y`（需 P-2 补丁） |
| 有但 `status` 不是 bound | 服务崩了 | `adb logcat -s ETX-Keys AndroidRuntime` 找异常 |

### 4. 确认 `com.dpad.fuli` 白名单进程名是否仍有效（§7 原开放问题 #1）

```bash
adb shell logcat -c
# 冷启动 App（让 RcProService 起来），然后：
adb shell logcat -s ETXRcPro ETXRcProSvc
```

**期望**（有效）：
```
I ETXRcProSvc: protocol 轮询已建立
I ETXRcProSvc: chan=1024,1024,1024,1024,1024,1024 bytes=...
```

**失效的样子**：
```
W ETXRcProSvc: poll 失败: getService("protocol") = null（进程名可能不在白名单）
```
（原文见 `RcProService.java:254`）

| 输出 | 结论 | 要做什么 |
|---|---|---|
| `protocol 轮询已建立` | `com.dpad.fuli` 在 Plus2 上**仍然有效** | 无需改动，把结论补进 §2 |
| `= null（进程名可能不在白名单）` | 白名单名变了 | **改代码**：`AndroidManifest.xml` 第 44 行 `android:process="com.dpad.fuli"` 换名。**新名字需要逆向 RC Plus 2 的固件/DJI app 才能知道，本轮给不出** |

### 5. 确认按键真的进了 EdgeTX 状态机

```bash
adb shell logcat -c
adb shell logcat -s ETX
# 按一个键
```

**期望**：出现 `[plan] key N queued` 或界面焦点移动。

若 `ETX-Keys` 有 `-> etx N` 但这里毫无反应：
- 检查 `EtxNative.available()`（so 是否加载）：`adb logcat -s ETX-Main | grep -i libetx`
- 原生 `etx_key()` 在 `key<0||key>=10` 时**静默 return 0**（`etx_api.cpp:507`），
  所以越界索引不会报错只会没反应 → 确认映射的 idx 在 0..9。

### 6. 兜底开关速查（全部走同一个 prefs 文件 `etx.xml`）

| prefs key | 类型 | 默认 | 何时改它 |
|---|---|---|---|
| `keysOn` | boolean | `true` | 想临时关掉按键注入 |
| `keysAnyDevice` | boolean | `false` | §7.1 设备名不在白名单时 → 改 `true` |
| `keysMap` | string | `""` | §7.2 出现 `未映射 keyCode=X` 时 → `"X:索引"` |

改法（App 未加密 prefs，需 run-as）：
```bash
adb shell run-as com.rc2.etx cat /data/data/com.rc2.etx/shared_prefs/etx.xml
# 用 sed 改完值后重启 App（或重启无障碍服务）
```

### 7. 摇杆/轴序（§7 原开放问题 #2/#4）

```bash
adb shell logcat -s ETXRcProSvc | grep chan=
```
把摇杆分别推到四个端点，观察哪一位在变：

| 现象 | 结论 |
|---|---|
| 推**左杆横**时 `chan[3]` 变 | 与参照包一致，无需改 |
| 推**左杆横**时 `chan[0]` 变 | 轴序与 RC Pro 不同 → 需改 `Radio.fillNativeAxes7()` 的 `rawSnap[]` 下标 |

`chan[4]`/`chan[5]`（§7 原开放问题 #4）—— **注意，"模拟 vs 开关" 这个二分法本身已经被 §3.2.2 更正了**：
Android 端（`d3`）就是当**双向波轮**用的，所以命令要分辨的是三种而不是两种：

| 类型 | 现象 | 结论 |
|---|---|---|
| 双向波轮（Android 端口径） | 中位停在 **1024**，两个方向都能连续变化 | 与 `d3`/`Radio` 一致，**不需要改** |
| 单向扳机（PC 端口径） | 原始值横跨 **366..1684**，但止于 1024 时读数约 **127/255**（非 0） | 要复刻 PC 行为必须加双→单向变换（§3.2.2） |
| 两档开关 | 只有 0/2047 两个值 | 才是用户期待的"拨杆"语义 → 见 §6 P0-1 |

**最终仍需用户确认手感**：命令能区分"双向/单向/两档"，但"用户希望它当什么用"只有用户能回答。

### 8. 验收通过的最低标准

- **RC2**：§7.1 复现（无 DJI 设备）+ §7.3 服务 bound + §7.2 至少 CAMERA 那一行 → **框架通过**
- **RC Plus 2**：§7.1 命中白名单 + §7.2 所有物理键都有 `-> etx N` + §7.4 `protocol 轮询已建立`
  + §7.7 四轴顺序与 §3.2.1 一致 → **功能通过**

### 9. 本轮未能验证的（诚实清单）

- adb 在分析中途彻底掉线（USB 与无线都连不上，Lead 已确认是网络/物理问题），
  §7.1–§7.8 **一条都没在真机上跑过**，全部是**待执行**。
- RC Plus 2 的 `ro.product.model` 字符串、InputDevice 名、真实键码：**均未知**。
- 轮询节奏从 100Hz 改 25–40Hz（§6 P0-2）**未做**，仍待落地后实测。

---

## 8. 与 rcpro-fix 的分工边界（防撞车）

> 本节写给下一个接手的人。**改动前先看这张表，不要跨线。**

### 写作用域归属

| 文件 | 归属 | 说明 |
|---|---|---|
| `app/src/com/rc2/etx/input/KeysService.java` | **plus2-port（我）** | 新增，321 行 |
| `app/res/xml/accessibility.xml` | **plus2-port（我）** | 新增，24 行 |
| `app/src/com/rc2/etx/input/DumlStick.java` | **rcpro-fix** | TCP 40007 / 06-AE 帧 / btnRaw |
| `app/src/com/rc2/etx/input/RcProStick.java` | **rcpro-fix** | binder 客户端门面 |
| `app/src/com/rc2/etx/input/RcProService.java` | **rcpro-fix** | binder 服务端（`com.dpad.fuli` 进程） |
| `app/src/com/rc2/etx/radio/Radio.java` | **rcpro-fix** | 其中 **3 处**是争用点，见下 |
| `app/AndroidManifest.xml` | **Lead** | 收尾统一打 §11.2 补丁 |
| `app/src/com/rc2/etx/MainActivity.java` | **Lead** | 收尾统一打 §11.3 补丁 |
| `app/jniLibs/**`、`RC2-EdgeTX-Native/**` | **Lead / native 口** | 我全程只读 |

### `radio/Radio.java` 的 3 处争用点（rcpro-fix 独占）

1. **`applyRcProButtons()`（约 777–821 行）** —— ch4/ch5 语义、`p1&0x80` 位域导航、P1/P2/P3 开关写值。
   与本文档 §6 的 **P0-1** 直接相关；**plus2-port 未改，也未建议抢改**。
2. **`fillNativeAxes7()`（约 223–268 行）** —— 轴序 `rawSnap[0..3]` 映射、`potOrMin()`、`axes7[6]`。
   与 §7.7 的轴序验收相关。
3. **`stickSourceAlive()`（约 217–222 行）** —— `duml.connected` 与 `rcpro.connected` 的"或"判定。

### 三者的依赖方向（改之前想清楚）

```
KeysService.java  ──独立──>  EtxNative.press/release  （不经过 Radio）
DumlStick ─┐
RcProStick ─┴─> Radio.fillNativeAxes7/applyRcProButtons ──> EtxNative.setInputs
```

**关键：`KeysService` 是**完全独立**的一条支路**，只调 `EtxNative.press/release`，
**不碰 `Radio`，不被 `Radio` 调用**。所以：
- rcpro-fix 改 `Radio` **不会**影响按键服务；
- 我改 `KeysService` **不会**影响摇杆链路；
- 唯一的交集是**同一个原生无锁队列 `s_keyq`**（`etx_api.cpp:436`）——
  `setInputs` 走的是 `g_analog`（另一套变量），**不经过 `s_keyq`**，所以目前无冲突。

### 已取消 / 不再需要的项

- **P1-1（hat → `EtxNative.press`）已按 Lead 指示取消**：`KeysService` 已覆盖
  DPAD_UP/DOWN/LEFT/RIGHT → 0/1/2/3 的按键面，不需要再改 `applyRcProButtons()` 的导航部分。
- **§6 P0-1（ch4/ch5 语义）仍归属 rcpro-fix**，plus2-port 只提供证据与建议，不落地。

---

## 9. 复现步骤

```bash
# 1. 规范化扩展名（REA/jadx 按扩展名判类型，.apk.1 会被当成 zip）
cp "C:\Users\刘子墨\Downloads\Plus2 Xbox手柄_1.0.apk.1" /tmp/plus2/plus2.apk

# 2. 出 smali（本次用的）
java -jar E:\我的项目\rc331work\apktool.jar d -f plus2.apk -o dec

# 3. 出 Java（本次用的，androguard 4.1.4）
#    bundled python: C:/Users/刘子墨/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe
#    pip install androguard  然后 AnalyzeDex('dec/classes.dex') -> c.get_source()

# 4. 通路排除（字节级）
#    grep /dev/input | ABS_ | getevent | 40007 | dev/uhid  →  全部 0 命中
```

**关于 REA**：本会话 `mcp__rea__inspect_android_package` 报 `REA_JADX_MCP_JAR` 未设置（`E:\我的项目\逆向工程工具REA\third_party\jadx-headless-mcp\` 是**空目录**），所以 REA 的 APK 工具链不可用，已按预案退回 `apktool` + `androguard`。结论不受影响（smali + Java 双重交叉验证）。

**关于 `Plus2提取_1.0.apk.1`**（SHA-256 `4004ebc2…`）：按用户指示**未使用**。抽查发现它与参照包同源（同 `PROBE.RSA`、同一 `classes.dex` 时间戳 2026-10-31 16:51，仅 `classes.dex` 小 4252 B、manifest 大 4 B），大概率是同一 App 的另一版构建，不影响本报告结论。

---

## 10. 证据索引

| # | 结论 | 出处 |
|---|---|---|
| E1 | binder 服务名 `"protocol"` | `McuReader.smali:131` |
| E2 | `transact(4)` + 20 int select 包 | `McuReader.smali:295`, `:388-409` |
| E3 | `IProtocolManager` / `IPackListener` | `McuReader.smali:247`, `McuReader$1.smali:46` |
| E4 | 15×int 跳过 + byte[] + 6×u16LE | `McuReader$1.smali:101-160` |
| E5 | `norm()`：中点 0x400、行程 660、死区 6% | `ControlService.smali:887-925` |
| E6 | 轴序 ch3→lx / ch2→ly / ch0→rx / ch1→ry | `ControlService.report()`（androguard 反编译） |
| E6a | 上述 4 轴序得到第三方源码独立证实 | `RCPro-资料/d3/01_MainActivity.java:2074-2077` |
| E6b | ch4/ch5 在 Android 端是**双向波轮**（`(v-1024)/660`） | `d3/01_MainActivity.java:1451-1452`, `:1294-1296` |
| E6c | PC 端才把它们当扳机（`(v-366)/1318` 双→单向） | `d3/02_rcpad_app.py:96-110` |
| E7 | `android:process="com.dpad.fuli"`（application 级） | `AndroidManifest.xml` |
| E8 | AccessibilityService + `canRequestFilterKeyEvents` | `res/xml/accessibility.xml` |
| E9 | 设备名白名单 `DJI` / `gpio-keys` / `qpnp` | `ControlService.smali:2877-2900` |
| E10 | KeyCode → 标签表 | `ControlService.smali:296-390` |
| E11 | `identity()` scan: / key: | `ControlService.smali:532-585` |
| E12 | 无校准（`calib`/`offset`/`center` 命中 0） | 全 dex 字节检索 |
| E13 | 无 `/dev/input`、`ABS_`、`getevent`、`40007` | 全 dex 字节检索（命中 0） |
| E14 | uhid 需 root + `/system/bin/hid` | `GamepadBridgeMain.smali:281-286` |
| E15 | uhid 只接受自身 uid 或 shell(2000) | `GamepadBridgeMain.main`（反编译） |
| E16 | 40ms 重投递 / 700ms 超时 | `McuReader.smali:366`, `:226` |
| E17 | `active` = 300ms 新鲜度 | `ControlService.smali:1521-1530` |
| E18 | 我们 `MCU_PACK` 与参照包逐项相同 | `RcProService.java:39-41` vs `McuReader.smali:388-409` |
| E19 | 我们 `applyRcProButtons()` 的 ch4/ch5 误用 | `Radio.java:816-820` |
| E20 | 全项目无 AccessibilityService | `grep -rn AccessibilityService app/src` = 0 |

---

## 11. 落地补丁（P0-3 按键输入源）

> 本节由 plus2-port 编写。**我实际写盘的新文件只有 2 个**（见 §11.1），其余都是
> 需要 Lead / rcpro-fix 在自己写作用域里落地的补丁。**我没有改** `AndroidManifest.xml`、
> `MainActivity.java`、`input/RcPro*.java`、`radio/Radio.java`。

### 11.1 已写盘的新文件（我的写作用域内，可直接用）

| 文件 | 行数 | 说明 |
|---|---|---|
| `app/src/com/rc2/etx/input/KeysService.java` | 321 | AccessibilityService，物理按键 → EdgeTX 10 键 |
| `app/res/xml/accessibility.xml` | 26 | `canRequestFilterKeyEvents="true"` + `flagRequestFilterKeyEvents`（**无** `android:description`，见下方说明） |

**已验证**（不是"看起来对"）：
- `javac -encoding UTF-8 -source 8 -target 8 -classpath android.jar` 全量编译 `app/src` **通过**，
  产出 `KeysService.class`(8589 B) + `KeysService$1.class`(730 B)，无 error（只有工程原有的 deprecation 提示）。
- `aapt2 compile --dir res` **通过**，产出 `xml_accessibility.xml.flat`。
- `aapt2 link`（带 §11.2 的 manifest 补丁）**通过**，`exit 0`，产出 56591 B 的 APK，
  新增的 `<service>` 与 `@xml/accessibility` 均正确解析。
- ⚠️ **踩到一个真坑（已修）**：`android:description` **必须**是 `@string` 引用。
  一开始我写字面量，`aapt2 compile` 能过，但 `aapt2 link` 报
  `'...' is incompatible with attribute description (attr) reference`。
  为了不碰公共的 `res/values/strings.xml`，最终**直接省掉 `description`**：
  该属性是可选的，服务在无障碍列表里的名字来自 manifest `<service>` 的 `android:label`。
  → 这样本功能只新增 2 个文件，零共享文件改动。
- 大括号配平 47/47。

**未验证**（诚实标注）：
- **没有真机跑过**。adb 在分析中途掉线（`adb devices` 从有 1 台变成空），重连 30s 未恢复。
  → 装包 + 开无障碍 + 按键的实际行为**尚未实测**。
- `onKeyEvent` 是否真能收到 RC2 的物理键 **未实测**（见 §11.5 的 RC2 设备名风险）。

### 11.2 补丁 P-1：`app/AndroidManifest.xml`（必须，否则服务不生效）

**位置**：`app/AndroidManifest.xml` 共 **48 行**。插在第 **45** 行与第 **47** 行之间
（即 `<service .../>` 结束之后、`</application>` 之前；第 46 行是空行）。

原文（第 45–47 行）：
```xml
            android:exported="false" />

    </application>
```

替换为：
```xml
            android:exported="false" />

        <!-- 物理按键/拨杆：必须无障碍服务才能收到 KeyEvent。
             注意：**不要**加 android:process —— 必须和 libetx 同进程（见 KeysService 类注释）。 -->
        <service
            android:name=".input.KeysService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility" />
        </service>

    </application>
```

**风险**：
- `android:exported="true"` 是**系统要求**的（`BIND_ACCESSIBILITY_SERVICE` 保护它，不是漏洞）。
- 不要动第 6 行的 `targetSdkVersion="29"`。
- 不新增任何 `uses-permission`。

### 11.3 补丁 P-2：`MainActivity.java` —— 一键跳到无障碍设置（强烈建议）

**为什么**：无障碍服务必须由用户在系统设置里手动开启，App 内不能自动开。
没有入口的话用户根本找不到，功能等于没做。

**位置**：`MainActivity.java`，插在第 **443** 行与第 **444** 行之间。

原文（第 443–444 行，**这是精确锚点，落地方请原样匹配**）：
```java
            Log.i(TAG, "press " + k + " (release in " + hold + "ms)");
        } else if ("card".equals(act)) {
```

替换为：
```java
            Log.i(TAG, "press " + k + " (release in " + hold + "ms)");
        } else if ("a11y".equals(act)) {
            // 打开系统无障碍设置，让用户手动开启「RC2 EdgeTX 按键」。
            // 无障碍服务不能由 App 自己开，只能把用户送到系统页面。
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("请开启「RC2 EdgeTX 按键」无障碍服务");
            } catch (Throwable t) {
                toast("打不开无障碍设置: " + t);
            }
            Log.i(TAG, "open accessibility settings");
        } else if ("card".equals(act)) {
```

> 注意：`} else if ("a11y"...)` 里的 `}` 闭合的是上面 `"key"` 分支；
> 原来第 444 行的 `} else if ("card"...)` **必须保留**，否则 `card` 分支丢失。

**风险**：低。只是新增一个 `else if` 分支。
需要确认 `MainActivity` 已 `import android.content.Intent`（第 444 行附近已有 `i.getIntExtra`，
说明 `Intent` 已导入）与 `toast(...)` 方法已存在（第 443 行 `Log.i` 同级已有 `toast` 调用）。

**已实测验证（我没动你的仓库，是在 /tmp 的副本上做的）**：把上面这段打到一个
`app/src` 的完整副本上，再用工程同款 `javac -source 8 -target 8 -classpath android.jar`
编译**整棵树** → `exit 0`，无 error。锚点在副本里**恰好匹配 1 次**（脚本 `assert count==1`），
不会误伤。落地方可以放心套用。

**验证通道**（装包后）：
```bash
adb shell am start -n com.rc2.etx/.MainActivity -a android.intent.action.MAIN   --es action a11y
```

### 11.4 补丁 P-3：诊断面板显示按键状态（可选，建议）

**位置**：`ui/DiagPanel.java` 任意一处文本拼装处。
**内容**：追加一行 `com.rc2.etx.input.KeysService.status()`
**风险**：无（`status()` 是纯静态只读）。

### 11.5 ⚠️ RC2 真机预检结论：`DJI` 设备名在 RC2 上**不存在**

这是本次最有价值的真机发现。掉线前抓到的 `adb shell dumpsys input`（RC2，`6UZBM6L02104CA`）显示，
整机**只有 3 个输入设备，没有一个叫 DJI**：

```
  Device 3: himax-touchscreen
  Device 4: qpnp_pon
  Device 2: gpio-keys
```

`gpio-keys` 的 keylayout 是 `/system/usr/keylayout/gpio-keys.kl`，内容只有 5 个键：

```
key 115   VOLUME_UP
key 114   VOLUME_DOWN
key 102   HOME
key 528   FOCUS
key 766   CAMERA
```

**含义（务必理解，别误判成 bug）**：
1. 参照包的 `contains("DJI")` 分支在 **RC2** 上永远不会命中——RC2 的摇杆/按键在 DJI 私有
   binder 和 `gpio-keys` 里，不在一个叫 "DJI" 的 InputDevice 里。
2. 所以 RC2 上真正能命中的只有 **`gpio-keys`**，而它只报 **HOME(3) / CAMERA(27) /
   VOLUME_UP(24) / VOLUME_DOWN(25) / FOCUS(528)**。
3. 对照 `KeysService.defaultMap()`：**只有 CAMERA(27) → K_MODEL 有映射**；HOME/VOLUME 故意
   不映射（映射了会把系统音量/主页键吞掉，风险很大）。
4. **结论：在 RC2 上这套按键服务大概率"只能收到 1 个键（CAMERA）"**，达不到"操作 EdgeTX 界面"的目标。
   RC2 的物理拨杆走的是别的路（`DumlStick` 的 `06/AE` 按键帧 + `Radio.applyRcProButtons()` 的 `btnRaw` 位域）。

**因此这个服务的真实目标是 RC Plus 2**，在 RC2 上它的价值是：
- 验证"无障碍服务能不能装上、能不能连上、`onKeyEvent` 会不会被调用"（框架正确性）；
- 用 `adb shell logcat -s ETX-Keys` 看 `lastDevice` 到底是哪个设备名。

**RC Plus 2 到货后第一件事**（决定性）：
```bash
adb shell dumpsys input | grep -E "^  Device [0-9]+:"
```
把设备名贴回来。三种情况：
- 出现含 `DJI` 的名字 → **开箱即用**，什么都不用改；
- 出现 `gpio-keys`/`qpnp` 且键码是标准 gamepad 码 → **开箱即用**；
- 出现别的名字 → 用 `adb shell settings` 改不了，走下面的**兜底开关**。

### 11.6 兜底开关（不需改代码，改 SharedPreferences 即可）

`KeysService` 读 `SharedPreferences("etx")`（和 `radio/Store.java` 同一个文件）：

| key | 默认 | 作用 |
|---|---|---|
| `keysOn` | `true` | 总开关 |
| `keysAnyDevice` | `false` | **true = 接受任意输入设备**（RC Plus 2 设备名不认识时的救命开关） |
| `keysMap` | `""` | 键码覆盖，格式 `"keyCode:etxIdx,..."` |

开启兜底：
```bash
adb shell run-as com.rc2.etx \
  sed -i 's|<boolean name="keysAnyDevice" value="false" />|<boolean name="keysAnyDevice" value="true" />|' \
  /data/data/com.rc2.etx/shared_prefs/etx.xml
```
（或让 App 里加个勾选框，改 `Store` 加一个 getter/setter 更规矩——但那要动 `Store.java`，
**在 rcpro-fix 的写作用域里**，我没动。）

装好服务后先用这个把"设备名"和"键码"抓出来，再决定要不要写死映射：
```bash
adb shell logcat -s ETX-Keys
# 未映射的键会打：未映射 keyCode=XXX name=YYY scan=ZZZ
```

### 11.7 与 P0-1 / P1-1 的关系（避免重复劳动）

- **P0-1（ch4/ch5 语义）**：按 Lead 指示**我没动**。补丁建议已写在 §6 P0-1。
- **P1-1（hat → EtxNative.press）**：本节的 `KeysService` **已经覆盖了 hat 的按键面**
  （DPAD_UP/DOWN/LEFT/RIGHT 直接映射 0/1/2/3），**不需要再改 `Radio.applyRcProButtons()` 的
  导航部分**。唯一剩下的工作是 `Radio` 里那套 `p1&0x80` 位域（RC Pro 的 `duml.btnRaw`），
  它是**另一条通路**，如果 RC Plus 2 的按键能从无障碍拿到，那条就可以不用管了。
- **结论：P1-1 可以降级/取消**，先看 §11.5 的真机设备名再定。
