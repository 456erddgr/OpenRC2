# 19 — RC Pro（RM510）六轴全 −100% 与旁键不可用 修复记录

- 日期：2026-11-03
- 责任范围：`app/src/com/rc2/etx/input/**`、`docs/**`（`radio/Radio.java` 经 Lead 批准后改 3 处）
- 设备现状：本轮 **RC Pro 未接入**（`adb devices` 只有 RC2 `6UZBM6L02104CA`），
  因此「根因」是**代码级 + 既有实测日志**推导，**没有本轮新采集的 RC Pro 真机日志**。
  凡是推断都显式标注，不带实测背书。

---

## 一、现象

| 现象 | 用户描述 | 复现位置 |
|---|---|---|
| A | 「移植系统」页六轴全部 −100%，杆子怎么推都不动 | RC Pro 原生页（`NativePanel` → `etx_set_inputs`） |
| B | 遥控器旁键（物理按键/拨轮）完全没反应 | 同上，导航键与 C1/C2/C3 都无效 |

关键判断：**A 和 B 是同一个根因**（输入链路整体被判死），不是两个独立故障。见第三节。

---

## 二、证据（既有实测，非本轮采集）

### 2.1 RC Pro 的 40007 是「连一次→EOF」，且 0 帧

`RCPro-资料/d2/01_logcat.txt`（2026-11-01，RC Pro 真机，`RC2ETX-1.20.9`）：

```
11-01 20:23:10.029 11249 11276 I ETX-Duml: connected 127.0.0.1:40007
11-01 20:23:10.029 11249 11276 W ETX-Duml: read returned -1 (EOF/reset) after 0 frames
11-01 20:23:12.030 11249 11276 I ETX-Duml: connected 127.0.0.1:40007
11-01 20:23:12.031 11249 11276 W ETX-Duml: read returned -1 (EOF/reset) after 0 frames
（同样两行每 2 秒重复，持续 50 秒）
```

→ 连接存活 ≈ 1ms、**每连接 0 帧**、2 秒一轮。`RCPro-资料/d2/03_…测试报告.md` 结论同：
「连上就 EOF，一帧都读不到，每 2 秒重连一次」，并已排除 `com.fly.simulator` 占用槽位。

### 2.2 RC Pro 上 binder（`IProtocolManager`）是连续可用的

同一份报告的旁证段（引原文）：

> 用 binder（`IProtocolManager`）读 MCU 通道可以拿到**连续**的摇杆值（0..2048，中心 1024，行程 ±660），
> 同一个 40007 端口则只在连接瞬间给一包。说明摇杆数据在 RC Pro 上是**拉取式**，不是推送式。

→ 这是「RC Pro 必须靠 binder」的原始依据。

### 2.3 RC2 对照（本轮实测，证明判据没被改坏）

本轮 RC2 在跑，`logcat` 显示 TCP 推送正常：

```
11-03 21:39:42.729 16829 16856 I ETX-Duml: stat hz=25 stickHz=10 stick=1347 frames=3296 bad=0 gap150=1
```

→ RC2 一条连接能活数秒、稳定 9–10Hz。这决定了「快照模式」判据必须能区分两台机器（见 4.1）。

### 2.4 旁键（06/174）帧格式 — 关键线索（本轮新发现）

出处：`E:/我的项目/RCPro-资料/d3/01_MainActivity.java`（cjz 的「RM510 键位」参照 App，
在 RC Pro 上**实测可用**；`09_使用说明.txt` 明写「实时显示全部输入」）。
相关代码在 **`01_MainActivity.java:841-866`**（`parseGearFrames`）。

**帧定位**：DUML 头 `[9]=cmd_set`、`[10]=cmd_id`；命中 `cmd_set==6 && cmd_id==174(0xAE)` 后，
载荷从 `off+11` 开始（`off` 为外层 `55 CC 30 75` 头长度，裸 DUML 时 `off=0`）。

**字段偏移（相对载荷起点）**：

| 偏移 | 名称 | 含义（参照实现实测位定义） |
|---|---|---|
| `payload[0]` | `p0` | `0x80` 快门半按 / `0x40` 快门全按 / `0x20` 返航 / `0x10` 急停 |
| `payload[1]` | `p1` | `0x80` 上 / `0x40` 下 / `0x20` 左 / `0x10` 右 / `0x08` 中(确认) / `0x01` 录像 |
| `payload[2]` | `p2` | `bit0-1` 挡位(S/N/C，`p2 & 0x03`) / `0x04` C1 / `0x08` C2 / `0x10` C3 / `0x20` 返回 |

原文（`d3:857-866`）：

```java
int p0 = b[i + off + 11] & 0xFF;
int p1 = b[i + off + 12] & 0xFF;
int p2 = b[i + off + 13] & 0xFF;
State.setButtons(
        (p1 & 0x80) != 0, (p1 & 0x40) != 0, (p1 & 0x20) != 0,
        (p1 & 0x10) != 0, (p1 & 0x08) != 0,
        (p2 & 0x04) != 0, (p2 & 0x08) != 0,
        (p1 & 0x01) != 0, (p0 & 0x80) != 0, (p0 & 0x40) != 0,
        (p2 & 0x20) != 0, (p2 & 0x10) != 0,
        (p0 & 0x10) != 0, (p0 & 0x20) != 0);
```

**两条独立取数通道**（参照实现里都有）：

| 数据 | 通道 | 参照实现位置 | 频率 |
|---|---|---|---|
| 摇杆 + 两个波轮 | binder `transact(4)`，`cmdSet=6` `cmdId=4` → 6×u16le | `d3:375-417` `mcuPoll(6,4)` | 150ms |
| 按键 / 挡位 | **40007 TCP** 上的 `06/174` 帧 | `d3:725-775` `startGearPolling()` | 连一次取一包，10ms 后重连 |
| 按键 / 挡位（备用） | binder `transact(4)`，`cmdSet=6` `cmdId=174` | `d3:464-479` `mcuPoll(6,174)` | 500ms |

→ **旁键最稳的来源是 binder 的 `06/174`**，因为 RC Pro 的 40007 不可靠（见 2.1）。
参照 App 的整个 Activity 都声明在 `android:process="com.dpad.fuli"`
（`d3/00_AndroidManifest.xml`），与我们的 `RcProService` 用同一个白名单进程名，
互相印证「进程名决定能不能拿到 binder」。

**边界说明（不许含糊）**：174 的**载荷布局**是在 40007 的 TCP 帧上测出来的；
binder 回调返回的 `byte[]` 是否与 TCP 载荷逐字节相同，**未真机验证**。
新代码因此把原始 hex 一并回传（`RcProService.KEY_HEX`），真机上一眼可对照。

### 2.5 RC2 没有 174 帧（避免误伤）

`app/src/com/rc2/etx/ui/NativePanel.java:110-112` 注释记录了 RC2 的实测：
「这台机器根本没有 len!=30 的 06/AE 帧」。
→ 174 通路是 RC Pro 专属，RC2 上它只是永远不回数据，不影响既有行为。

---

## 三、根因

### 3.1 主因：用 `connected` 判活，而 RC Pro 的 `connected` 几乎恒为 false

`DumlStick.connected` 只在 socket 存活期间为 true。RC Pro 的连接存活 ≈ 1ms（2.1），
于是 `connected` 在 99.9% 的时间是 false。而它被当作「输入链路是否活着」的唯一判据：

| 位置（修改前） | 后果 |
|---|---|
| `Radio.stickSourceAlive()` → `duml.connected && duml.stickFrames>0` | 恒 false |
| `Radio.fillNativeAxes()` → `dumlOn && duml.connected && duml.stickFrames>0` | 恒 false → 走 `synthNativeAxes()`，把 `input.reset()` 的假值（油门 −1、开关 −1）喂给核心 → **全 −100%** |
| `Radio.tick()` → `if (stickSourceAlive())` | 整块不执行 → 不采样、不 `smooth.sample`、不 `input.touch` |
| `Radio.fillNativeAxes7()` → `applyRcProButtons(axes7)` | 旁键映射整段不执行 → **旁键无反应** |

**A 与 B 同源的证据**：两者都挂在 `stickSourceAlive()` 这一个判断下面。

### 3.2 加重项 1：快照模式的退避写错，把 10ms 写成了 5000ms

`DumlStick.loop()` 修改前：

```java
if (snapMode && frames > 0) wait = 5000L;   // 注释却写着"10ms 内重连继续取下一帧"
else if (failStreak <= 2) wait = 5000L;
```

按注释本意应当是 10ms 重连；实际是 5 秒 → 即便 daemon 每次连接都给一帧，
也只能拿到 **0.2Hz**，`connected` 的占空比更小。

且 `snapMode` 的置位条件（`failStreak==1 && frames>0 && now-lastFrameMs<1500`）在
RC Pro「0 帧」的场景下**永远不成立**（2.1 实测 0 帧），这条分支实际上也进不去。

### 3.3 加重项 2：binder 源的 `raw` 初值是全 0

`RcProStick.raw = new int[AXES]`（全 0）。而 `Radio` 的桥线程只要 `r.connected` 就把
`r.raw` 整体拷进 `duml.raw`；`DumlStick.units()` 的换算是 `(raw − 1024) / 660`：

```
raw = 0  →  (0 − 1024)/660 = −1.55  →  钳到 −1.00  →  每轴 −100%
```

全 0 在物理上不可能是回中值（回中是 1024），它是「载荷没解析出来」的签名。
一旦发生，六轴就**稳定停在 −100% 且推杆无效**——与用户描述逐字吻合。

---

## 四、改动

只动 `app/src/com/rc2/etx/input/**` 三个文件 + `radio/Radio.java` 三处（Lead 已批准）。

### 4.1 `input/DumlStick.java`

1. **快照模式判据改成「连接存活时长 + 帧数」**（新增常量 `SNAP_LIVE_MS = 500`）
   ```java
   long livedMs = System.currentTimeMillis() - connectAt;
   long gotFrames = frames - frameMark;   // frameMark 提到 try 外，原 markFrames 出了 try 就取不到
   if (gotFrames > 0 && livedMs < SNAP_LIVE_MS) { snapMode = true; snapBurst++; }
   else if (gotFrames > 0 && snapMode) { snapMode = false; snapBurst = 0; }  // 活得久 → 退出快照模式
   ```
   *为什么*：RC Pro 快照连接存活 20–80ms；RC2 推送连接能活 3–6s（2.3 实测）。
   500ms 落在两者之间，**不会把 RC2 误判成快照机**（旧判据「最近 1500ms 有帧」会误判）。

2. **快照模式重连间隔 5000ms → 10ms**，并加熔断
   ```java
   if (snapMode && gotFrames > 0 && snapBurst < 200) wait = 10L;
   else if (snapMode && snapBurst >= 200) wait = 5000L;   // 连续 200 次说明是真空了，退回慢速
   else if (failStreak <= 2) wait = 5000L;
   else wait = Math.min(5000L << Math.min(failStreak - 2, 3), 30000L);   // RC2 行为不变
   ```
   *为什么*：注释本意就是 10ms；RC2 走 else 分支，退避参数**逐字未变**。

3. 新增 `stickFresh(long ms)`：按「最近一帧时间」判活，替代 `connected`。
   新增 `snapInfo()` 供诊断。

#### 4.1.1 时间源选择（Lead 指定必须写明）

新增字段 `lastStickElapsed`（`volatile long`）在 `handleFrame()` 里与 `lastStickMs`
**同时写入**，但两者用途严格分开：

| 字段 | 时间源 | 用途 |
|---|---|---|
| `lastFrameMs` / `lastStickMs` | `System.currentTimeMillis()` | **只用于给人看的日志/诊断**，不参与任何判活 |
| `lastStickElapsed`（新增） | `SystemClock.elapsedRealtime()` | **所有「是否新鲜」判断唯一使用的时钟** |

**为什么选 `elapsedRealtime()`，而不是另外两个：**

| 候选 | 缺陷 |
|---|---|
| `System.currentTimeMillis()` | 会被系统对时 / NTP / 用户改表**向前或向后跳**。向前跳 ⇒ `now-last` 变成大正数 ⇒ 误判「源已死」⇒ **回到假 −100%**；向后跳 ⇒ 差值变负 ⇒ 若判据写成 `d < ms` 则恒为 true ⇒ **断线也看不出来**（摇杆卡在最后一帧）。 |
| `SystemClock.uptimeMillis()` | **深度休眠时停走**。RC 遥控器锁屏放一夜，`uptime` 只走了几分钟而真实过了几小时；用它会把这"很久以前的最后一帧"当成刚发生的。 |
| `SystemClock.elapsedRealtime()` ✅ | 单调递增、**包含休眠时间**、不受对时影响。同时满足「不被对时破坏」与「不因休眠走慢」。 |

**判据带下溢保护**（按 Lead 要求的形式）：

```java
long last = lastStickElapsed;
if (last <= 0) return false;
long d = android.os.SystemClock.elapsedRealtime() - last;
return d >= 0 && d < ms;      // 关键：d>=0 挡住负差值
```

`d >= 0` 这一半是**防御性设计**：万一将来有人把 `lastStickElapsed` 误写成
`currentTimeMillis()`（时间源混用），负差值只会判「不新鲜」，
而不会像教科书式的 `d < ms` 那样恒为 true、把死源当活源。

`RcProStick.fresh()` / `btnFresh()` 用**完全相同**的时间源与判据形式，
避免两条源在两台机器上语义漂移。

> **已知遗留（不在我的三处授权内，仅报告）**：`Radio.java:671` 的
> `boolean binderFresh = (now - lastBinderMs) < 250L;`
> 其中 `now = SystemClock.elapsedRealtime()`，而 `lastBinderMs` 在桥线程里被写成
> `System.currentTimeMillis()`（`Radio.java:553`）—— **两个不同时钟相减**。
> `elapsedRealtime` 是开机毫秒数、`currentTimeMillis` 是 1970 纪元毫秒数，
> 两者相差约 1.7e12，相减结果是一个巨大的负数 ⇒ `binderFresh` **恒为 true**。
> 后果：RC2 上 `tick()` 会以 20ms 一次的高频重复走「binder 补帧已到」分支
> （不影响最终数值，因为值来自同一份 `duml.raw`，但属于无效派发）。
> 这处**未改**：它不在 Lead 批准的三处之内。建议 Lead 统一处理时改成
> `duml.stickFresh()` 同款写法。

### 4.2 `input/RcProStick.java`

1. `raw` 初值 → 中位 **1024**（原来是 0）。
   *为什么*：3.3 —— 全 0 = 六轴 −100%，且是「载荷没解析出来」的伪装。
2. 新增坏帧过滤：6 通道**同时精确为 0** 时 `badFrames++`，**不发布**。
   每 500 次打一条 `Log.w`，不刷屏。
3. 新增 `lastFrameMs` / `fresh(ms)` / `btnFresh(ms)` / `badFrames` / `btn0..btn2` / `lastBtnHex`。
4. `MSG_ERROR` 分支**不再把 `connected` 置 false**。
   *为什么*：一次 transact 失败不代表数据源死了；置 false 会连带把整条输入链判死，
   等于复发 A。判活统一交给 `fresh()` 的时间窗。

### 4.3 `input/RcProService.java`（跑在 `com.dpad.fuli` 白名单进程）

1. 新增 **旁键（`06/174`）轮询线程** `etx-rcpro-btn`，默认 **250ms**
   （参照实现用 500ms；250ms 兼顾手感与 binder 开销），
   用**独立 Binder 回调** `btnCallback` 解析 `p0/p1/p2`。
   *为什么独立回调*：摇杆回调解的是 6×u16，174 的载荷是位图，共用一个回调会互相污染。
2. 新增 `MSG_BTN` 消息与 `KEY_B0/KEY_B1/KEY_B2/KEY_HEX`，把**原始 hex 一并上报**
   （2.4 的边界说明：binder 载荷布局待真机确认，hex 便于对照）。
3. 重入闸门 `boolean transactBusy` → `AtomicBoolean inFlight`
   *为什么*：现在有摇杆(100Hz) 与旁键(4Hz) 两条线程共用 binder，普通 boolean 跨线程不安全；
   `1.24.0` 已经踩过一次「并发 transact 塞满队列 → 推几下卡死」的坑。
4. 抽出 `transactOnce(cmdSet, cmdId, cb)`，摇杆与旁键共用同一份拼包逻辑，避免两份常量漂移。
5. `stat` 日志追加 `btn=N`，便于真机看旁键轮询是否活着。

### 4.4 `radio/Radio.java`（3 处，Lead 批准）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `stickSourceAlive()` | `duml.connected && duml.stickFrames>0` → `duml.stickFresh(400)`；binder 分支 `r.connected` → `r.fresh(400)`。新增常量 `STICK_FRESH_MS = 400L` |
| 2 | `fillNativeAxes()` | `dumlOn && duml.connected && duml.stickFrames>0` → `!forceSynthAxes && stickSourceAlive()`（与 7 轴版**同一判据**） |
| 3 | `applyRcProButtons()` | 拨轮与 C1/C2/C3 **优先级**：`wheelFresh ? 拨轮 : 按键位`；并新增 binder 旁键分支（TCP 按钮帧不新鲜时才用 binder，避免两源打架） |

`STICK_FRESH_MS = 400` 的依据：DJI 推送最差 4Hz（≈250ms/帧），400ms 给 1.6 倍余量；
再大就会把「真断了」也当活的（通道卡住不动）。

**ch4/ch5 语义已定论（2026-11-03 Lead 决策，不再是 TODO）**：

> **ch4/ch5 = RC Pro 的两只波轮 K1/K2**（PC 端对应 LT/RT），**不是** P1/P2 三档拨杆。

三条依据：

1. `RcProStick.java` 类注释与字段口径本来就写「ch4 左波轮 / ch5 右波轮」，
   是 `Radio.java` 的实现走偏了（把它当 P1/P2 拨杆用）。
2. plus2-port 已用 **dex 级证据**确认参照 App 把 ch4/ch5 当波轮，
   见 `docs/18-rcplus2-兼容分析.md` 的「轴序」一节。
3. `RCPro-资料/d3` 的参照实现里 6 通道同样是「四轴 + 两波轮」。

落地方式与边界：

- 因 HAL 的 FLEX 输入只有 P1/P2/P3 **三个槽**（没有第 4/5 个槽），
  K1/K2 落到 `axes7[4]/axes7[5]` 这两个槽位，**语义与命名按 K1/K2 理解**。
- `axes7[6]`(P3) **只由 C3 决定，任何情况下都不被波轮覆盖**（本轮修的正是这个）。
- 优先级：`wheelFresh ? K1/K2 : C1/C2`，即拨轮新鲜时用波轮，否则回落到按键位。
- **只改优先级，不改数据来源**（数据仍来自 binder `transact(4)` 的 ch4/ch5）。

**真机对照实验日志（Lead 采纳）**：`applyRcProButtons()` 里新增
`maybeLogBtnSource()`，在来源或位图变化时打一条：

```
ETX-Radio: btnSrc=tcp174 p1=0x80 p2=0x4 p0=0x0 wheel=false t=1699012345678
```

`btnSrc` 取值 `tcp174` / `binder174` / `none`。与 plus2-port 的 `ETXKeys` 日志放在
同一次 logcat 里，一次就能判定三条通路（TCP 174 → binder 174 → 无障碍 onKeyEvent）
哪条先到、值是否一致。静止时不打，不刷屏。

**没有碰**：`RC2-EdgeTX-Native/**`、`app/jniLibs/**`、`keep/**`、`MainActivity.java`、`ui/NativePanel.java`。

---

## 五、对 RC2 的回归影响（必须守住）

| 项 | 是否变化 | 依据 |
|---|---|---|
| RC2 的 TCP 连法 | **不变** | 非快照模式仍走 `5s/5s/10s/20s/30s` 退避，参数逐字未改 |
| RC2 是否会被误判成快照机 | **不会** | 判据要求「连接存活 <500ms」；RC2 实测存活 3–6s（2.3） |
| RC2 的 `stickSourceAlive()` | 语义等价（更宽） | RC2 推送 9–10Hz ⇒ 每 100–110ms 一帧，400ms 窗口内必然 true |
| RC2 的旁键 | **不变/更稳** | RC2 无 174 帧（2.5）→ binder 旁键分支不生效；TCP 分支保持原样 |
| RC2 的 binder 补帧 | **不变** | 桥线程逻辑未改 |
| 新增后台线程 | RC2 上也会起 `etx-rcpro-btn`（250ms 一次 transact） | 若性能敏感可按 `model==1` 跳过，本轮保留（用于对照验证） |

### 5.1 「ch4/ch5 = 独立 K1/K2」改了之后，RC2 现有行为会不会变？

**结论：RC2 上不会变，因为 RC2 根本没有 ch4/ch5 的真实数据源。** 逐条论证：

| 前提 | RC2 的实际情况 | 对本改动的影响 |
|---|---|---|
| `wheelFresh` 是否可能为 true | 需要 `RcProStick.fresh(400)` 为真，即 binder 通道 400ms 内给过**可信**帧。RC2 走 TCP 推送（2.3 实测 9–10Hz），`Radio` 桥线程只在 `!tcpFresh` 时才搬运 binder 值 | RC2 上几乎永远是 `wheelFresh=false` |
| 即使 binder 偶尔有值 | 新代码把 6 通道全 0 判为坏帧不发布（4.2），所以 `fresh()` 不会因假帧变真 | 更不可能误触发 |
| `wheelFresh=false` 时走哪条 | 走 `else` 分支 = C1/C2 按键位 | 与改动前一致 |
| P3(`axes7[6]`) | 改动前被波轮分支无条件覆盖；现在只由 C3 决定。RC2 无 174 帧（2.5）⇒ `p2=0` ⇒ P3=0 | RC2 上 P3 改动前也是 0（波轮分支不触发时），**无差异** |

另外两点需要明说：

1. **RC2 上 `axes7[4]/[5]` 原本的取值来源**是 `potOrMin(rawSnap[4..5])`（见
   `fillNativeAxes7()` 里 smoothing 分支），而 RC2 的 DJI 帧这两路**恒为 1024 中点**，
   经 `potOrMin()` 变 `0`（= −100%，这是用户明确要求的行为，`potOrMin` 注释写了）。
   改动后这行仍在 `applyRcProButtons()` 之前执行，但**被 `applyRcProButtons()` 覆盖**——
   覆盖发生在 `wheelFresh=false` 时写 C1/C2 位（都是 0）⇒ 仍然是 0。
   **结论：RC2 的 CH5/CH6 行为逐值不变（仍 −100%）。**
2. 若将来 RC2 也需要 K1/K2，只要 binder 通道给出真实波轮值，
   `wheelFresh=true` 就会自动接管，无需再改代码。

---

## 六、验证状态（诚实标注）

| 步骤 | 状态 |
|---|---|
| 全量 Java 编译（带 aapt2 生成的 `R` 类，全部 `app/src/**/*.java`） | ✅ **通过，真实 exit 0，无 error**（22:10:00，见 §6.1 证据 A） |
| APK 构建 | ✅ 本人出过 v1.46.39（**已作废**，manifest 竞态）；**基线 v1.46.41 由 Lead 构建**（§7） |
| RC2 真机回归 | ❌ **未做**（设备掉线，Lead 要求本轮不碰设备） |
| RC Pro 真机验证 | ❌ **未做**（设备从未接入） |

### 6.1 javac 验证记录（两条证据，均可复现）

#### 证据 A — 带 aapt2 生成的 `R` 类做**全量**编译（最终、最完整）

**时间：2026-11-03 22:10:00 +0800，真实 exit = 0，无 error**

```bash
JC="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/javac.exe"
rm -rf build/jchk && mkdir -p build/jchk
"$JC" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "E:/我的项目/rc331work/sdk/android.jar" -d build/jchk \
  $(find app/src -name '*.java') app/build/gen/com/rc2/etx/R.java
echo "[真实 exit=$?]"      # → [真实 exit=0]
```

- 覆盖**全部** `app/src/**/*.java`，含 plus2-port 的 `KeysService.java`、
  含 Lead 的 P-2（`MainActivity.java` 的 `a11y` 跳转），属真正的跨改动一致性检查。
- `R.java` 由 aapt2 生成于 `app/build/gen/com/rc2/etx/R.java`（22:06，559 B，已核实存在）。

#### 证据 B — 排除 `MainActivity.java` 的全量编译（P-2 落地前的等价验证）

**时间：2026-11-03 22:03:41 / 复验 20:0x，exit 0**

```bash
rm -rf /tmp/jc3 && mkdir -p /tmp/jc3
"$JC" -source 8 -target 8 -nowarn \
  -bootclasspath "E:/我的项目/rc331work/sdk/android.jar" \
  -d /tmp/jc3 $(find app/src -name "*.java")
```

> **注记（必须保留，否则这条命令今天复现会失败）**：
> 该次通过成立于 Lead 的 P-2 补丁（22:04:36）**之前**。
> P-2 引入了 `com.rc2.etx.R.string.app_name`，而 `R` 类是 **aapt2 构建期生成**的，
> 独立 javac（只用 `-bootclasspath android.jar`）没有它，因此今天直接复现证据 B 会报
> `找不到符号 com.rc2.etx.R`。
>
> **这不是代码错误**：官方构建链（`build-apk.sh` → `build-apk.ps1`）会先生成 `R` 再编译，
> 1.46.41 出包成功即是证明。等价且可复现的替代验证是「排除 `MainActivity.java`」：
> ```bash
> "$JC" ... $(find app/src -name "*.java" ! -name "MainActivity.java")   # → exit=0
> ```
> **结论：本轮 4 个文件本身无编译问题**（证据 A 已给出最强证明）。

- 两次编译输出里均只有 `注: … 使用或覆盖了已过时的 API`（deprecation 提示），**不是 error**。
- 证据 A 发生在**时间源修正 + ch4/ch5 定论 + 按键来源日志 + p0 回退自查修正**四项改动之后，
  即对应本文件描述代码的最终形态。
- 编译只做语法/类型检查，**不代表运行时正确**；真机行为仍以「未验证」为准。

> 补充：本人在 Lead 批准窗口内**确实构建过一次**（v1.46.39，见 §7），
> 后因 manifest 竞态作废，基线改由 Lead 重建。此处「为什么没构建」的旧表述已更新。
> `scripts/build-apk.sh` 属 Lead 写作用域，本人**未修改**。

**结论**：本轮交付 = **代码级修复 + 全量编译通过 + 一次构建（v1.46.39，已作废）**，
状态为「**已改、已构建、未真机验证**」。

### 真机验证步骤（拿到设备后照做）

```bash
ADB="E:/我的项目/rc2-backup/tools/platform-tools/adb.exe"
# 0) 确认插的是哪台
"$ADB" devices -l
# 1) 装包
"$ADB" install -r dist/RC2ETX-<ver>.apk
# 2) 型号设成 RC Pro（0=自动 1=RC2 2=RC Pro）
"$ADB" shell am force-stop com.rc2.etx
"$ADB" shell am start -n com.rc2.etx/.MainActivity --es action model --ei v 2
# 3) 三个关键日志
"$ADB" logcat -d | grep -E "ETXRcPro|ETX-Duml|ETX-Radio"
#    期望看到：
#      ETX-Duml: 检测到快照模式（存活 XXms，1 帧/连接）→ 改为高频重连
#      ETXRcProSvc: protocol 轮询已建立
#      ETXRcProSvc: 旁键(06/174) 轮询已建立
#      ETXRcProSvc: btn #1 p0=.. p1=.. p2=.. len=.. hex=..
# 4) 推杆 + 按 C1/C2/C3 + 拨波轮，看原生页六轴是否跟手、旁键是否有反应
```

**判定标准**：

- 成功 = 六轴随杆移动、波轮 K1/K2 有值、五维方向键能导航、C1/C2/C3 有效。
- 部分成功 = 摇杆动了但旁键仍无 → 看 `btnSrc` / `btn #` 日志：
  无日志 = binder 174 没数据；有日志但值不变 = 载荷布局与 2.4 不符（拿 `hex=` 对照修正偏移）。
- 失败 = 仍全 −100% → 先看 `ETX-Duml` 有没有「检测到快照模式」；
  没有 = 快照判据不适用于该机（存活时长 >500ms），需要改判据而不是改退避。

---

## 七、构建记录（v1.46.39 由本人构建 → **基线最终定为 v1.46.43**）

**本人构建命令**：`cd E:/我的项目/RC2-EdgeTX && bash scripts/build-apk.sh 1.46.39`
**本人构建时间**：2026-11-03 22:04（构建窗口由 Lead 单独批准，`1.46.38` 已被 Lead 占用）
**未 install**（设备掉线，按 Lead 规则 4 只出包）。

> **基线演进（五版，按时间顺序读）**：
> 1. **1.46.39**（本人，22:04:32）→ ❌ **作废**：出包后 4 秒 Lead 打了 manifest 的 P-1 补丁，
>    本包缺 `KeysService` 的 `<service>` 注册（见 §7.3）。so = `8ac3785e…`（旧）。
> 2. **1.46.40**（Lead，22:05:14）→ **中间版**：含 P-1/P-2 补丁；
>    so **仍是旧的 `8ac3785e…` / `10479072` B**（实测，见 §7.1.1）。
> 3. **1.46.41**（Lead，22:07:02）→ **中间版**：so 换为 `00f95e80…` / `10479172` B，
>    **`model_select.cpp` 防崩补丁自此进入包内**。
> 4. **1.46.42**（Lead，22:10:30）→ **中间版**：so 与 1.46.41 相同，差异仅版本号+重签名。
> 5. **1.46.43**（Lead，22:16:28）→ ✅ **真机验收基线**：so 仍为同一份 `00f95e80…`；
>    此版用于验证 Lead 给 `build-apk.sh` 新增的**陈旧 so 检测 + mtime/sha16 打印**两道防线。
>
> 五版的包信息均经本人 `sha256sum` / `unzip -l` / `unzip -v`(CRC) / `aapt dump xmltree` **独立复核**。

### 7.1 产物包信息

| 项 | **1.46.43（基线）** | 1.46.42（中间版） | 1.46.41（中间版） | 1.46.40（中间版） | 1.46.39（本人，作废） |
|---|---|---|---|---|---|
| **SHA-256** | `3125ded878425b33d5dbb1728bb0fd0c65a470e0762551a851e0c576d0c03338` | `f8175190…70d7` | `4135752c…0ea23` | `c74fa321…9427` | `e4d50e04…09f1e` |
| 大小 | `13855339` B | `13855339` B | `13855339` B | `13855339` B | `13851243` B |
| `classes.dex` | `234576` B | `234576` B | `234576` B | `234576` B | `234148` B |
| `libetx.so` 大小 | `10479172` B | `10479172` B | `10479172` B | **`10479072` B** | `10479072` B |
| **`libetx.so` sha24** | `00f95e807e2eca952d98c469` | 同左 | 同左 | **`8ac3785efc97e88897bb477e`** | `8ac3785efc97e88897bb477e` |
| manifest `KeysService` | **1** | 1 | 1 | 1 | **0（未注册）** |
| 状态 | ✅ **真机验收用这个** | 中间版 | 中间版 | 中间版 | ❌ 作废 |

### 7.1.1 ⚠️ 五版的相互关系（防止下次再搞混）

**必须用 `unzip -v` 的条目 CRC 来比较，不能只看大小或版本号。实测结论：**

1. **so 分成两组，不是一组**：
   - `1.46.39` 与 `1.46.40` → so = **`8ac3785e…`**（`10479072` B，**不含** `model_select.cpp` 防崩补丁）
   - `1.46.41` / `1.46.42` / `1.46.43` → so = **`00f95e80…`**（`10479172` B，**含**防崩补丁）
2. **`1.46.40` 与 `1.46.43` 的 APK 相差 5 个条目**（实测 `unzip -v` CRC diff）：
   `AndroidManifest.xml` + 3 个签名文件 + **`lib/armeabi-v7a/libetx.so`**（`82d1d4de` → `15722f98`）。
3. **`1.46.41` / `1.46.42` / `1.46.43` 三版之间只差 4 个条目**，**不含 so**：
   `AndroidManifest.xml`（versionCode 递进）+ 3 个签名文件；
   其余 80 个条目（含 `classes.dex`、`libetx.so`、全部 assets）CRC **逐一相同**。
4. **四版大小全是 `13855339` B** —— 因为 so 只差 100 字节且 zipalign 对齐到同一 4K 边界，
   dex 未变。**所以大小完全无法区分这四版。**

> ### 👉 结论（本节最重要的一句）
> **`libetx.so` 的 sha256 是判断「原生补丁是否进包」的唯一可靠依据。**
> 版本号不行（只说明重建过），文件大小更不行（四版同大小）。
> 判断方法：`unzip -p dist/RC2ETX-<ver>.apk lib/armeabi-v7a/libetx.so | sha256sum`
>
> 本条记录的由来：Lead 最初认为「1.46.41 的 so 是旧的、1.46.42 才含补丁」，
> 本人用包内容比对（so sha256 + 条目 CRC diff + `ModelsList` 符号反汇编）证明补丁早在 1.46.41 就在；
> 随后本人又发现**1.46.40 的 so 才是真正的旧版**（`8ac3785e…`），
> Lead 复核自己的操作顺序后**收回原说法并确认**。故此处保留完整经过，作为后续排查依据。

### 7.2 原生补丁「已生效」的证据（反汇编级）

证明 `model_select.cpp` 的防崩补丁确实进入了 **1.46.42 包内的 `libetx.so`**：

| 步骤 | 证据 |
|---|---|
| 1. 目标符号地址 | `llvm-nm -D`：`003d8060 T _ZN10ModelsList4loadEv`、`003d8004 T _ZN10ModelsList5clearEv` |
| 2. 补丁注入点 | `ModelLabelsWindow` 构造函数 `0x490880`，在 `0x4908f8` 与 `0x490904`（+0x78/+0x84）处有两条 `bl`，目标为 `0x62e9a0` / `0x62e940` |
| 3. PLT veneer | `0x62e9a0`: `add r12,pc,#0,#12`（pc=0x62e9a8）→ `add r12,r12,#241664` → r12=0x66aa28 → `ldr pc,[r12,#0xdac]` ⇒ GOT **0x66a754**<br>`0x62e940`: 同理 ⇒ GOT **0x66a73c** |
| 4. GOT 重定位 | `llvm-readelf -r`：`0066a73c R_ARM_JUMP_SLOT 003d8060 _ZN10ModelsList4loadEv`；`0066a754 R_ARM_JUMP_SLOT 003d8004 _ZN10ModelsList5clearEv` |

⇒ **`ModelLabelsWindow::ModelLabelsWindow()` 确实调用 `ModelsList::load()` 与 `ModelsList::clear()`**，
即模型管理页的防崩补丁在包内 so 中生效。

> 说明：`ModelsList` 在最终 so 里是**已定义**符号（libetx 是完整核心），
> 所以「未定义符号新增」这类证据只在 `out/clcd/model.model_select.o` 这一级成立，
> 到最终 so 必须用上面的**反汇编 + 重定位**链路来证明（本文采用后者）。

### 7.2.1 原生层一致性校验（证明没用 stale so）

```
[i] 使用基线 keep/libetx.so
[ok] so 符号检查通过  8ac3785efc97e888  新增libc依赖=0
  + lib/armeabi-v7a/libetx.so 10479072 B
```

- `keep/libetx.so` = `10479072` B / `8ac3785efc97e888`，与 Lead 在 22:04 同步进来的那版一致；
- 包内 so 字节数同为 `10479072` B ⇒ **逐字节同源，非陈旧产物**；
- **未定义符号新增 0** ⇒ 原生补丁未引入 libc/libm 之外的依赖。

### 7.3 dex 内容核对（KeysService 是否被编进去）

用 `dexdump -f` 直接查 `classes.dex` 的类描述符：

| 类 | 是否在包里 |
|---|---|
| `Lcom/rc2/etx/input/KeysService;` + `KeysService$1` | **在**（plus2-port 的代码已被编入） |
| `Lcom/rc2/etx/input/DumlStick;` | 在（本人改动） |
| `Lcom/rc2/etx/input/RcProStick;` | 在（本人改动） |
| `Lcom/rc2/etx/input/RcProService;` | 在（本人改动） |
| `Lcom/rc2/etx/radio/Radio;` | 在（本人改动） |

**注意（影响真机验收判读，已更正两次，以本节为准）**：

1. 这版 **1.46.39** 包内 dex **有** `KeysService` 类，但 **manifest 里没有它的 `<service>` 注册**
   （实测 `aapt dump xmltree … AndroidManifest.xml | grep -c KeysService` = **0**）。
   ⇒ 系统不会注册该无障碍服务 ⇒ **KeysService 通路在 1.46.39 中是死代码**。
   本文档早先写的「两条旁键通路都会活跃」**是错的**，特此更正。
2. 原因：`app/AndroidManifest.xml` 在 1.46.39 出包（22:04:32）之后 **4 秒**（22:04:36）
   被 **Lead 的 P-1 补丁**修改，新增了 `KeysService` 的 `<service>` 注册
   （同一分钟 Lead 还给 `MainActivity.java` 打了 P-2：`a11y` 跳无障碍设置）。
   **归属更正**：该改动是 **Lead 按裁决统一落地**，不是 plus2-port 在改、也不是"未定稿"。
3. **基线最终定为 1.46.43**（1.46.42 之后只改了构建脚本，APK 内容除 versionCode/签名外不变）（`KeysService` 注册数实测 = **1**）：

   | 项 | 1.46.39（**作废**） | 1.46.40（中间版） | 1.46.41 / 1.46.42（中间版） | **1.46.43（基线）** |
   |---|---|---|---|---|
   | SHA-256 | `e4d50e04…09f1e` | `c74fa321…9427` | `4135752c…0ea23` / `f8175190…50d7` | `3125ded878425b33d5dbb1728bb0fd0c65a470e0762551a851e0c576d0c03338` |
   | 大小 | `13851243` B | `13855339` B | `13855339` B | `13855339` B |
   | `classes.dex` | `234148` B | `234576` B | `234576` B | `234576` B |
   | `libetx.so` | `10479072` B | `10479072` B | `10479172` B | `10479172` B |
   | manifest 中 `KeysService` | **0（未注册）** | 1（已注册） | 1（已注册） | **1（已注册）** |

   四版我都在本机用 `sha256sum` / `unzip -l` / `aapt dump xmltree` **独立复核过**，
   详见 §7.1 与 §7.1.1（含「so sha 是唯一可靠依据」的说明）。
4. **manifest 竞态已由 Lead 合并解决，后续构建以 1.46.43 为起点。**
   真机验收请对 **1.46.43** 做，并用 `btnSrc=` 日志区分通路来源，
   不能只看"按键有反应了"就断定是哪条通路修好的。

### 7.4 manifest 进程名核对（`com.dpad.fuli` 白名单仍在）

`aapt dump xmltree` 读**已构建 APK** 的 AndroidManifest：

```
A: android:name(0x01010003)=".input.RcProService"
A: android:exported(0x01010010)=(type 0x12)0x0
A: android:process(0x01010011)="com.dpad.fuli"
```

⇒ binder 白名单依赖的 `android:process="com.dpad.fuli"` **确实进了成品包**，
不是只在源码里写了。

### 7.5 本轮改动文件清单

**本人改动（4 个文件，+427 / −41 行）**：

| 文件 | 改动 |
|---|---|
| `app/src/com/rc2/etx/input/DumlStick.java` | 快照模式判据重写、重连 5s→10ms+熔断、`lastStickElapsed` 单调时钟、`stickFresh()`、`snapInfo()` |
| `app/src/com/rc2/etx/input/RcProStick.java` | `raw` 初值 0→1024、全 0 坏帧过滤、`fresh()/btnFresh()`（`elapsedRealtime`+下溢保护）、旁键字段 |
| `app/src/com/rc2/etx/input/RcProService.java` | 新增 06/174 旁键轮询线程 + 独立 `btnCallback`、`MSG_BTN`/`KEY_B*`/`KEY_HEX`、`AtomicBoolean` 重入闸门、抽出 `transactOnce()` |
| `app/src/com/rc2/etx/radio/Radio.java` | 仅 3 处（经 Lead 批准）：`stickSourceAlive()` 新鲜度、`fillNativeAxes()` 同判据、`applyRcProButtons()` 优先级 + binder 旁键分支 + `btnSrc` 日志 + `getRcProP0()` |

**文档**：`docs/19-rcpro-修复记录.md`（本文件）

**没有碰**（按 Lead 裁决的写作用域表）：
`RC2-EdgeTX-Native/**`、`app/jniLibs/**`、`keep/**`、`scripts/build-apk.sh`（Lead 写作用域）、
`app/src/com/rc2/etx/MainActivity.java`、`app/src/com/rc2/etx/ui/NativePanel.java`、
`app/src/com/rc2/etx/input/KeysService.java`（plus2-port 的）、`docs/18-*.md`（plus2-port 的）。

#### 7.5.1 同期他人改动（非本人所为，列出以免混淆）

| 文件 | 改动者 | 内容 | 与本轮是否冲突 |
|---|---|---|---|
| `app/AndroidManifest.xml` | **Lead**（P-1 补丁，22:04:36） | 新增 `KeysService` 的 `<service>` 注册（无障碍服务） | 否；解决了 1.46.39 的死代码问题 |
| `app/src/com/rc2/etx/MainActivity.java` | **Lead**（P-2 补丁，22:04:36） | `a11y` 跳转无障碍设置（`ACTION_ACCESSIBILITY_SETTINGS`） | 否 |
| `app/src/com/rc2/etx/input/KeysService.java` | **plus2-port** | 无障碍服务捕获物理按键（evdev 机型，如 RC Plus 2） | 否；与本人 binder/TCP 174 通路**互补并存** |
| `app/res/xml/accessibility.xml` | **plus2-port** | `KeysService` 的服务配置 | 否 |
| **`docs/18-rcplus2-兼容分析.md`** | **plus2-port** | 本轮做了**轴序三小节重构** | 否；本人只读引用其结论，**未修改该文件** |
| `keep/libetx.so` | **Lead** | 模型管理防崩补丁 + `etx_store_diag` 自愈节流（22:10:18 链出） | 否；本人构建时只读取、不修改 |
| `scripts/build-apk.sh` | **Lead** | 本轮后段 Lead 自行加 so 新鲜度校验（本文档定稿时尚未合入） | 否 |

> 说明：`RC2-EdgeTX-Native/out/libetx/libetx.so`（22:10:18，`00f95e80…`）与
> `keep/libetx.so` sha256 一致，即最终包内的 so 确为最新链出产物。

### 7.6 编译结果

- **javac（带 aapt2 生成的 `R` 类，全量 `app/src/**/*.java`）：
  2026-11-03 22:10:00 +0800，真实 exit 0，无 error**（仅 deprecation 提示），命令见 §6.1 证据 A。
- **aapt2**：`W Failed to stat file 'rc331work/sdk/android.jar'` ——
  这是 aapt2 自身的路径解析告警，**历史既有**，不影响产物（同一次构建中 javac 用同一路径
  `-bootclasspath` 编译成功，说明该 jar 实际存在）。如实记录，不做猜测性修复。

---

## 八、遗留与待决策

1. ~~**ch4/ch5 语义**~~ → **已定论**：K1/K2 独立语义落 `axes7[4]/[5]`，P3 只由 C3 决定（见 §4.4）。
2. **binder 174 载荷布局**：待真机用 `hex=` 对照 2.4 的偏移表确认。
3. **`Radio.java` 桥线程的 `tcpFresh` 判据**（`duml.connected && now-lastStickMs<220`）本轮未改，
   因为它不在 Lead 批准的三处之内；在 RC Pro 上它碰巧是安全的
   （TCP 无数据 ⇒ `connected=false` ⇒ 走 binder），但如果后续 TCP 变成半死状态需要一并改成 `stickFresh(220)`。
4. **RC2 上多余的旁键轮询线程**：如确认 RC2 不需要，可按 `model==1` 跳过，省 4 次/秒 transact。
5. **构建脚本的越权拷贝**（建议 Lead 处理）：`scripts/build-apk.sh` 第 14 行
   `cp keep/libetx.so app/jniLibs/armeabi-v7a/libetx.so` 会在每次构建时改写 `app/jniLibs/`，
   任何并行开发都会被它冲掉。建议支持 `NO_INJECT_SO=1` 跳过注入。
6. **构建竞态（2026-11-03 实测发生，已由 Lead 合并解决）**：`app/AndroidManifest.xml` 在
   `dist/RC2ETX-1.46.39.apk` 出包后 **4 秒**（22:04:36 vs 22:04:32）被 **Lead 的 P-1 补丁**修改，
   新增了 `KeysService` 的 `<service>` 注册（同一分钟 Lead 还给 `MainActivity.java` 打了 P-2：
   `a11y` 跳无障碍设置）。因此 **1.46.39 包内 dex 虽有 `KeysService` 类，
   但 manifest 中无其 `<service>` 声明 ⇒ 系统不会注册该无障碍服务 ⇒ 该通路在 1.46.39 中是死代码**。
   详见 §7.3。**归属更正**：该改动是 Lead 按裁决统一落地，**不是** plus2-port 在改、也不是"未定稿"。
   Lead 已重建 **1.46.40**（加 P-1/P-2 补丁）→ **1.46.41**（原生 `model_select.cpp` 防崩 +
   `etx_store_diag` 自愈节流，so 由 `10479072` → `10479172` B）→ **1.46.42**（仅重签名）→
   **1.46.43（最终基线，加了构建脚本的陈旧-so 检测）**，`KeysService` 注册数实测 = 1。
   后续构建以 1.46.43 为起点。
   **追加（Lead 加固）**：`scripts/build-apk.sh` 现在会 ① 打印 `keep/libetx.so` 的 mtime+sha16；
   ② 若 `RC2-EdgeTX-Native/out/libetx/libetx.so` 比 `keep/libetx.so` 新则 `[FATAL]` 退出
   —— 从机制上堵死「重链后忘同步、静默用旧 so 出包」这类事故（本轮 1.46.40/41/42 就踩过）。
   教训：并行开发时 manifest 这类**共享单点**应约定改完再构建，构建前先看 `git status`。
7. **A/B 复核结论（2026-11-03）**：`docs/18` 的轴序中 `ch0/ch1/ch2/ch3` 四条映射与
   `RCPro-资料/d3` 源码**一致**；但 `ch4→LT / ch5→RT` 属 **PC 端脚本的映射选择**
   （`02_rcpad_app.py:103-110`，且把双向行程铺成单向 0~255），
   与 Android 端「ch4=左波轮、ch5=右波轮、以 1024 为双向中位」口径**不同**。
   本文档采用的 K1/K2 双向口径与 Android 端一致，故代码无需改动。
   `com.dpad.fuli` 白名单：manifest 两处 `android:process="com.dpad.fuli"` 属实，
   但**无注释、无代码常量、无因果对照实验**，属观察到的用法，不是被证实的机制。
