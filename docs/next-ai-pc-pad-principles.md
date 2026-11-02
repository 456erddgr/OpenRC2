# 把遥控器变成电脑手柄：原理与做法（交接给下一个 AI）

> 这份讲**电脑端那套软件**（`E:/我的项目/RC2-EdgeTX/pc/`），不是遥控器端移植。
> 结论先说：它没有"模拟一个 USB 手柄"，而是 **遥控器 →(adb 反向端口)→ Python 核心 →(ViGEmBus)→ 系统一只有真实 Xbox360 手柄**
> 网页 UI 只做显示与调参，60fps 靠 SSE + CSS/Canvas，不参与手柄数据通路。

---

## 0. 一句话原理与"复刻"的界线

| | 复刻（clone / 模拟器套壳） | 本项目做法 |
|---|---|---|
| 数据来源 | 自己攒的假摇杆 / 键盘映射 / 录好的轨迹 | 遥控器真实 DUML 摇杆（9–10Hz 原始 ADC） |
| 系统看到什么 | 一个自制驱动、或键盘模拟 | **ViGEmBus 内核驱动给出的标准 XInput 手柄**，游戏/模拟器原生认 |
| 延迟怎么解决 | 只能增加采样率（做不到，源就是 10Hz） | 在 10Hz 源上做**三点拟合外推**，125Hz 灌给手柄 |
| 界面 | 自己画的窗口 | 浏览器里的网页（SSE 60fps），Python 不画界面 |
| 断连问题 | 定时重连、越重连越断 | **幂等保活**：端口在就绝不动它 |

判据：**拔掉本项目，游戏里那只手柄立刻消失；本项目里没有任何"假装按下键盘"的代码**。这就是真通路，不是复刻。

---

## 1. 数据通路（四段，每段都能单独替换）

```
遥控器 App（PadStream）
   │  TCP 127.0.0.1:9999，一行一个 JSON：{"ax":[6 个 0..2047]}
   │  ← 由 PC 侧 adb reverse tcp:9999 tcp:9999 打通（USB 或无线 adb 都行）
   ▼
PC: padcore.py  class Link        # 收帧 → ST.ax / 速度 / 加速度
   │  125Hz 定时器（每 8ms）
   ▼
PC: padcore.py  predict()          # 10Hz 源 → 外推成"现在的值"
   ▼
PC: padcore.py  class Pad          # 死区 + Expo → ViGEmBus → XInput 手柄
   │
   └─ 同一份 ST.snap() 也走 HTTP：GET /state（一次性）、GET /events（SSE 60fps）
                                    ▼
                            web/index.html（浏览器渲染）
```

要点：
- **端口方向是反的**：不是 PC 去连遥控器，而是 PC 先 `adb reverse` 把遥控器的 localhost:9999 映射到 PC，然后遥控器往自己的 127.0.0.1:9999 写，就落到 PC 上。这样遥控器端不需要知道 PC 的 IP，也不需要开端口。
- **遥控器端只做"发"**：`app/src/com/rc2/etx/usb/PadStream.java`，开关文件 `padbridge.on`，被 `am start --es action pad --ei on 1` 拉起。
- **PC 端只做"收 + 转 + 显示"**，没有任何 UI 线程参与手柄数据。

---

## 2. 三个技术支点（缺一个就退化成复刻）

### 2.1 ViGEmBus：让系统真的多出一只手柄
- 依赖：`ViGEmBus` 驱动（`pc/driver/` 里有安装包）+ `ViGEmClient.dll`。
- 代码：`pc/OpenRC2-Pad.py` 里封装 `class Pad`，`padcore.py` 通过 `_load_pad_module()` 动态加载它（找不到就降级为"只显示不推手柄"，`PAD.ok=False`）。
- 映射（0..2047 → −1..1，注意 y 取反）：
  - 左摇杆 = 油门/偏航：`x=_n(ax[3])`、`y=-_n(ax[2])`
  - 右摇杆 = 俯仰/横滚：`x=_n(ax[0])`、`y=-_n(ax[1])`
  - 扳机固定 0，按键未映射（可扩展）
- **坑**：`pad.update()` 抛异常必须捕获并把 `PAD.ok` 置 false，否则每 8ms 抛一次会把 UI 卡死。

### 2.2 10Hz 源 + 125Hz 输出 = 必须外推（本项目最核心的一段）
源只有约 10Hz（100ms 一帧），而手柄要 125Hz。不预测就等于**永远在推 100ms 前的位置**，手感是"黏"。

`predict()` 的做法（`PRED_*` 四组档位参数）：
1. `age_ms = now - t_last`（距最近一帧多久），`ahead = min(age_ms, cap)`，`cap` 按档位 0/50/100/160ms。
2. 每轴三点拟合：`p = cur + v*ahead + 0.5*a*(ahead/1000)² * 1000 * accw * 0.35`。
3. **过冲钳制**：`capd = |step| * over + 6`，预测值不许离最近一步太远 —— 这是"甩尾"的唯一有效刹车。
4. **反向即停**：`v` 与 `step` 反号时把 `ahead` 归零。
5. `age_ms > cap` 时用 `decay = exp(-(age_ms-cap)/140)` 把外推量收回，避免掉帧后越飞越远。
6. `lv==0`（关）直接返回原始 `ST.ax` —— 关档必须**完全等价于不预测**，方便 A/B。

档位含义：`0 关 / 1 弱 / 2 中 / 3 强`，实测 2 档 `预测 100ms`、3 档更跟手但快反向会略过冲。

### 2.3 SSE + 纯 CSS：网页当显示屏，不当数据通路
- `GET /events` 是 `text/event-stream`，服务端 `time.sleep(1/60)` 推 `data: {json}\n\n`；`GET /state` 给一次性快照。
- 网页**不再轮询**，也不画 canvas 摇杆（早期版本画过，掉帧）；六条摇杆条用 `.bar` 的 grid 行宽百分比表示，`backdrop-filter: blur(30px)` 做玻璃质感。
- 好处：网页刷新、换主题、加背景都不影响手柄；Python 侧永远只有一个 125Hz 定时器 + 一个 SSE 生成器。

---

## 3. 断连：一次真正的 bug 复盘（务必照抄结论）

**症状**：用户说「手柄连电脑几秒断一下、几秒断一下」。

**根因**：`_auto_connect()` 每 5 秒无条件执行一次
- `adb reverse tcp:9999 tcp:9999`（**重建转发会拆掉已有连接**）
- `am start … --es action pad --ei on 1`（把 App 拉前台，设备端还无脑弹 toast）

**修法（现行代码）**：
- `_reverse_present(dev)`：用 `adb reverse --list` 判断 `tcp:9999 tcp:9999` 是否已在，**在就绝不动**。
- `ST.pad_on`：推流已开就不再 `am start`。
- `_link_healthy()`：`LN.connected and (time.time()-LN.last_frame_at) < 2.0`，健康时 `sleep(10)` 什么都不做。
- 验证方式：跑 48s，`curl 127.0.0.1:9998/state` 采样六次，全部 `conn=True srcHz≈50 padOk=True`，日志"已连接"出现 0 次。

**通用教训**：任何"保活"都必须是**幂等检查 + 只在缺失时补**，绝不能定时无条件重建。

---

## 4. 换模块 / 移植到别的项目，只改这几处

| 改什么 | 位置 | 说明 |
|---|---|---|
| 数据来源 | `class Link` | 换成你的 UDP/串口/WebSocket 收帧，只要产出 `[6 个 0..2047]` |
| 轴序映射 | `class Pad.push()` | 本项目 `ax[3]=左X, ax[2]=左Y, ax[0]=右X, ax[1]=右Y`，换设备改这四行 |
| 归一化 | `Pad._n()` | 中心 1024、行程 ±1024；你的设备若中点/行程不同，改这里 |
| 目标手柄类型 | `class Pad` | ViGEm 可建 DS4/DualSense/360，改构造函数即可 |
| 换驱动 | `PAD` | 不想用 ViGEm：Windows 可用 `vJoy`+`pyvjoy`，Linux 用 `uinput`，接口保持 `push(ax6)` |
| 界面 | `web/index.html` | 纯静态，改样式不碰数据 |
| 设备侧开关 | `am start … action pad` | 换成你自己的"开始推流"命令 |

**JNI/协议契约（最小集）**：
```
线协议：一行一个 JSON，{"ax":[a0..a5]}，每个 0..2047（中点 1024）
频率：设备侧约 9–10Hz；PC 侧不假设频率，只用 t_last 算 age
断开：TCP close 即视为断开，ST.connected=false，UI 显示"等待连接"
```

---

## 5. 踩坑清单

1. **`adb reverse` 是"连接"，不是"路径"**：重建一次就会断一次，必须幂等。
2. **别在 10Hz 上做"平滑"**：平滑只会更黏；要么外推，要么原样。抖动来自外推过冲，用 `capd` 钳制，不要用低通滤波。
3. **y 轴符号**：屏幕/摇杆向下为正，XInput 向下为负，`y=-_n(...)` 漏掉就是反杆。
4. **`_getframe`/`inspect` 之类别放热路径**：125Hz 里任何 0.5ms 的开销都会累积成 6% CPU。
5. **`web/` 目录必须随 exe 打包**（PyInstaller `--add-data`），否则装完是白屏。
6. **端口占用**：`SO_REUSEADDR` 必开；9999 被占时给出中文提示而不是抛栈。
7. **`write` 工具在本仓库 `pc/`、`docs/` 下会报 `EISDIR`**，写文件用 `ops_bash` + 引号 heredoc。
8. **两端必须同一个 release 发布**（用户明确要求过）：`dist/OpenRC2-OneClick.zip` 里同时放 `OpenRC2-Device-Setup.exe`（遥控器端）与 `OpenRC2-Setup-Web.exe`（电脑端）。

---

## 6. 已知限制

- 源是 10Hz（DJI DUML 硬上限，实测 8.85–10Hz）→ 无论怎么外推，**快反向都会有 ~50–100ms 的手感差**；这是数据源上限，不是算法问题。
- ViGEm 需要内核驱动；无管理员权限装不了 → 安装器会检测 `vigem_installed()` 并给中文提示。
- 只映射四轴；开关/旋钮可扩到按键，但目前没做。
- 网页 UI 需要 Chromium 内核（Edge/Chrome）才有 `backdrop-filter`。

---

## 7. 源码地图

```
pc/
  padcore.py        # 核心：Link(9999) / predict() / Pad(ViGEm) / H(HTTP 9998) / toggle() / ensure_reverse()
  OpenRC2-Pad.py    # ViGEm 封装（Pad 类、驱动检测、旧版 tkinter UI，现仅作库）
  web/index.html    # 玻璃质感界面，60fps SSE 渲染
  web_launcher.py   # 起 padcore + 打开浏览器
  installer_web.py  # 装到 %LOCALAPPDATA%\OpenRC2-Web + 装 ViGEmBus + 桌面快捷方式 + 卸载器
  device_setup.py   # 遥控器端一键安装（装 APK + 铺 SD + chown + 启动）
  devinstall/       # adb.exe、RC2ETX.apk、sd_v15.zip（打包进 Device-Setup.exe）
  payload_web/      # padcore.exe、OpenRC2-Web.exe（打包进 Setup-Web.exe）
```

启动顺序（`web_launcher.py`）：起 9999 监听 → 起 9998 HTTP → `ensure_reverse()` → `toggle(True)` → 打开 `http://127.0.0.1:9998/`。

---

## 8. 验收清单（照此逐条过）

1. 插 USB/连无线 adb，双击桌面「OpenRC2 虚拟手柄」→ 浏览器自动打开，右上角变「运行中」。
2. `joy.cpl` 里能看到 Xbox 360 手柄，拨杆八方向都动、回中归零。
3. `curl 127.0.0.1:9998/state`：`conn=true`、`srcHz≈50`（设备侧 10Hz 上报、PC 统计 50 是含 8ms 推流的计数口径）、`padOk=true`、`frames` 单调增。
4. 拔掉 USB 再插：**不需要重启**，10 秒内自动恢复（幂等保活）。
5. 连续跑 10 分钟：`conn` 不应出现 false，日志不应出现「已连接」重复刷屏。

---

# 第二部分：完整接口、线程模型、打包，以及"接进别的软件"的十种做法

---

## 9. HTTP / TCP 完整接口参考

### 9.1 TCP 9999（设备 → PC，唯一的数据入口）

```
方向：遥控器 → PC（PC 只监听，不主动连）
地址：0.0.0.0:9999，由 `adb reverse tcp:9999 tcp:9999` 打通
格式：一行一个 JSON，\n 结尾（Link 逐行读）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ax` | int[6]，0..2047 | 六路原始值，中点 1024。序：`[0]=右X(横滚) [1]=右Y(俯仰) [2]=左Y(油门) [3]=左X(偏航) [4]=P1 [5]=P2` |

最小可用报文：`{"ax":[1024,1024,1024,1024,1024,1024]}\n`

容错：多余字段忽略；`ax` 少于 6 个则本帧丢弃（`PAD.push` 里 `len(ax) < 6` 直接 return）；非法 JSON 跳过该行，不断连。

### 9.2 HTTP 9998（PC → 浏览器 / 任何第三方程序）

| 方法 | 路径 | 用途 | 返回 |
|---|---|---|---|
| GET | `/state` | 一次性快照 | JSON（见 9.3） |
| GET | `/events` | **SSE 60fps 推流** | `text/event-stream`，每帧 `data: {json}\n\n` |
| GET | `/bg` | 背景图 | `backgrounds/` 里第一个 jpg/png/webp，否则 404 |
| GET | `/` 及任意静态路径 | 界面 | `web/` 下的 html/css/js |
| POST | `/` | 控制命令 | JSON `{ok, msg}` |

POST 两种写法都收，随便挑一种：
```jsonc
// 1) cmd 形式
{"cmd":"start"}   {"cmd":"stop"}
// 2) 裸键形式（可与上者混用，一次带多个也行）
{"start":true}    {"pred":2}    {"deadzone":6}    {"expo":1.0}
{"mode":"xbox"}   {"model":"0"} {"connect":true}  {"selftest":true}
```

| 键 | 取值 | 作用 |
|---|---|---|
| `start` | bool | 起/停（起=装 ViGEm 手柄+补 reverse+拉设备推流；停=移除 reverse） |
| `connect` | bool | 只做"连上"（幂等 ensure_reverse），成功后若未运行会自动 `toggle(True)` |
| `pred` | 0–3 | 预测档：0 关 / 1 弱(50ms) / 2 中(100ms) / 3 强(160ms) |
| `deadzone` | 0–30 | 死区百分比（`Pad.deadzone = v/100`） |
| `expo` | 0.2–3.0 | 曲线指数，1.0 线性 |
| `mode` | `"xbox"` | 手柄类型（目前只实现 360） |
| `model` | `"0"/"1"/"2"` | 下发到设备端切换摇杆数据源（RC2 / RC Pro / 自动） |
| `selftest` | bool | 设备侧自检（CRSF 编码/解码/混控/算力） |

### 9.3 `/state` 字段表（第三方接入主要看这个）

| 字段 | 含义 |
|---|---|
| `ax` | **预测后**的六轴 0..2047（推给手柄的就是它） |
| `raw` | 最近一帧原始值（不预测） |
| `frames` | 累计帧数（单调增，判断是否还活着） |
| `fps` | PC 侧统计的推流帧率 |
| `srcHz` | 源频率（设备侧真实约 9–10Hz；PC 侧显示的 50 是含 8ms 推流的计数口径，别拿它当源频率） |
| `lagMs` | 距最近一帧多少毫秒（`-1` = 还没收到过） |
| `pred` | 当前预测档 0..3 |
| `connected` | TCP 是否连着 |
| `adb.dev` / `adb.msg` | 当前 adb 设备号 / 中文状态 |
| `src` | 设备侧上报的状态文本 |
| `mode` / `model` | 手柄类型 / 设备型号 |
| `padOk` / `padErr` | ViGEm 是否可用 / 失败原因（中文） |
| `running` | 是否在推手柄 |

### 9.4 一段可直接跑的第三方接入示例（Python，不依赖本项目代码）

```python
import json, urllib.request
def state():
    return json.load(urllib.request.urlopen("http://127.0.0.1:9998/state", timeout=1))
def post(**kw):
    r = urllib.request.Request("http://127.0.0.1:9998/", json.dumps(kw).encode(),
                               {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=3))

post(connect=True)          # 幂等：已连就不动
s = state()
print(s["connected"], s["srcHz"], s["padOk"], s["ax"])
```

---

## 10. 线程模型与状态机（改代码前必须先看懂这张图）

```
线程 1  Link(Thread)        阻塞 accept + 逐行 readline → on_frame(ax)
线程 2  on_frame 内部        计算 vel/acc/hist，更新 ST，然后（若 running）
                            每 ≥8ms 调一次 PAD.push(predict())   ← 125Hz 节流点
线程 3  ThreadingHTTPServer 每个请求一个线程：/events 会长期占用
主线程  web_launcher 拉起 padcore 后打开浏览器，然后退出
```

状态机（`ST` 是唯一真值，带 `lock`）：

| 状态 | 触发 | 表现 |
|---|---|---|
| 未连 | 启动 | `connected=false`，`lagMs=-1`，UI 显示"等待遥控器连接 0.0.0.0:9999" |
| 已连未启 | 收到帧但 `running=false` | 摇杆条会动，手柄不动（`padOk` 可能为 false） |
| 运行 | `toggle(True)` 或 `{connect:true}` | 手柄生效，125Hz 推流 |
| 掉线 | TCP close 或 2s 无帧 | `connected=false`，UI 变"等待"；`_link_healthy()` 失效后自动补 reverse（不是重建） |
| 无驱动 | `VPad()` 抛异常 | `padOk=false`，`padErr="推手柄失败：…"`，但界面照常显示数据 |

关键不变量（改动时别破坏）：
1. **只有 `on_frame` 里会 `push`**，HTTP 线程绝不碰手柄 → 不会有两处同时 `update()`。
2. **`predict()` 是纯函数**（只读 ST），可被 `/state` 与 push 同时调用。
3. **`ensure_reverse()` 幂等**：任何地方调用都不会踢断已有连接。

---

## 11. 打包与安装（PyInstaller 全流程）

### 11.1 三个产物

| 产物 | spec | 内容 | 大小 |
|---|---|---|---|
| `padcore.exe` | `padcore.spec` | `padcore.py` + `OpenRC2-Pad.py` + `web/` + `backgrounds/`（`datas`） | ≈18.6 MB |
| `OpenRC2-Web.exe` | `OpenRC2-Web.spec` | `web_launcher.py`，`datas=[('icon.png','.')]`，`console=False` | ≈8.5 MB |
| `OpenRC2-Setup-Web.exe` | `OpenRC2-Setup-Web.spec` | `installer_web.py`，`datas=[('payload_web','payload')]` | ≈39 MB |

打包命令：
```bash
pyinstaller --noconfirm OpenRC2-Web.spec
pyinstaller --noconfirm OpenRC2-Setup-Web.spec   # 依赖 pc/payload_web/ 已放好 padcore.exe + OpenRC2-Web.exe
```

### 11.2 安装器做了什么（`installer_web.py`）

```
res("payload")            # _MEIPASS 里取解出来的 payload 目录
mkdir %LOCALAPPDATA%\OpenRC2-Web  ← 先 rmtree 再建（干净覆盖）
copy_tree(payload, DEST)
make_shortcut(桌面 + 开始菜单, DEST\OpenRC2-Web.exe)
install_driver()          # 检测 vigem_installed()，没有就静默跑 driver/ViGEmBus_setup.exe
write_uninstaller()       # 生成"卸载 OpenRC2"脚本（删目录 + 删快捷方式）
```

### 11.3 卸载与自启

- 卸载：安装目录里的卸载脚本（`write_uninstaller()` 生成）。
- 自启（可选，未默认开）：`shell:startup` 放 `OpenRC2-Web.exe` 快捷方式，或 `schtasks /create /tn OpenRC2 /tr "<DEST>\OpenRC2-Web.exe" /sc onlogon`。
- 注意：**ViGEmBus 是系统级驱动，卸载本项目不自动卸驱动**（其它工具也可能在用）。

---

## 12. 怎么接进别的软件（十种做法，按耦合从低到高）

### 12.1 只当"数据服务"用（零代码改动，最推荐）
别的软件只读 `http://127.0.0.1:9998/state`，拿 `ax` 自己用；不需要手柄、不需要界面。
```csharp
// C# 每 50ms 拉一次
var s = JsonDocument.Parse(new WebClient().DownloadString("http://127.0.0.1:9998/state"));
var ax = s.RootElement.GetProperty("ax").EnumerateArray().Select(e => e.GetInt32()).ToArray();
```

### 12.2 推模式：订阅 SSE（60fps，不需要轮询）
```js
const es = new EventSource("http://127.0.0.1:9998/events");
es.onmessage = e => { const s = JSON.parse(e.data); plot(s.ax, s.lagMs); };
```

### 12.3 把本项目的 `padcore` 当库导入（同进程）
```python
import padcore
padcore.start_link()            # 起 9999
padcore.toggle(True)            # 起手柄（需 ViGEm）
# 你自己也可以直接读 padcore.ST.ax / padcore.predict()
```
注意：`padcore` 顶层会在 `__main__` 里起 HTTP；以库方式用时只调 `start_link()`，别重复起 9998。

### 12.4 子进程模式（别的语言最省事）
```
spawn("padcore.exe")            # 它会占 9999 + 9998
然后照 12.1 / 12.2 通信
退出时 POST {"cmd":"stop"} 再 kill
```

### 12.5 换数据源：不接遥控器，接你自己的设备
只改 `class Link` 的解析，或直接自己起 9999 往 `on_frame` 灌：
```python
import padcore, time
def feed(ax6):                   # ax6: 6 个 0..2047
    padcore.on_frame(ax6)        # 内部会算 vel/acc 并 125Hz 推手柄
```
适合：把一套串口/UDP/HID 手柄数据变成"系统手柄"。

### 12.6 换成别的手柄驱动（不用 ViGEm）
保持 `push(ax6)` 这一个接口即可：
- Windows 无驱动方案：`vJoy` + `pyvjoy`（缺点：部分游戏不认）
- Linux：`uinput`（`python-evdev`）造 `/dev/uinput` 虚拟手柄
- macOS：`IOHIDUserDevice`（需 Swift/ObjC 小工具）
```python
class MyPad:
    ok = True; err = ""
    def push(self, ax): ...      # 自己实现
padcore.PAD = MyPad()            # 直接替换
```

### 12.7 让模拟器认到（XInput 轴映射表）
Xbox360 手柄在 Windows 上的标准轴号（游戏里"校准"时对照）：

| 物理动作 | XInput | 本项目来源 |
|---|---|---|
| 右摇杆 X | axis 0（左摇杆 X 是 0，右摇杆是 2/3 视 API） | `ax[3]` 取反不取反见 §2.1 |
| 左摇杆 Y（油门） | — | `ax[2]`，`y=-_n()` |
| 扳机 | LT/RT | 固定 0（未映射） |

实操：RealFlight / Liftoff / Velocidrone / DCL / DJI 飞行模拟器 都直接认 XInput，进游戏"控制器设置"里把 通道 1-4 绑到对应轴即可；**不需要装 DS4Windows/vJoy**。

### 12.8 接进 Electron / Web 应用
- 主进程：`spawn(padcore.exe)`，渲染进程直接用 12.2 的 EventSource（同源或 CORS 放行）。
- 要嵌到页面里：`<iframe src="http://127.0.0.1:9998/">`；只要数据就 `fetch(/state)`。
- 跨域：当前服务端没加 CORS 头，如需要，在 `do_GET` 里加 `Access-Control-Allow-Origin: *`（一行）。

### 12.9 接进 Unity / Unreal / Godot
它们读的是**系统手柄**（XInput），所以本项目的 ViGEm 路径天然可用：
- Unity：`Input.GetAxis("Horizontal")` 默认绑 Xbox 左摇杆；把物理杆绑到自定义 axis 即可（Project Settings → Input Manager 加 joystick axis 4/5 等）。
- UE：`Enhanced Input` 里建 `Axis1D`，设备选 `Generic USB Joystick`。
- Godot：`Input.get_joy_axis(0, JOY_AXIS_LEFT_X)`。
若不想装 ViGEm：用 12.1/12.2 直接读数据，自己在引擎里驱动模型——**延迟更低**（少一层驱动）。

### 12.10 反向：把本项目的数据喂给"别的手机/平板"
把 9998 的 `/events` 用 `adb reverse` 或局域网暴露（改 `H.server_address` 绑 `0.0.0.0`），其它设备打开 `http://<PC-IP>:9998/` 即可看到同一套界面；或直接订阅 SSE 做副屏遥测。

---

## 13. 调试与排障手册

| 现象 | 先看 | 处理 |
|---|---|---|
| UI 一直"等待遥控器连接" | `/state` 的 `adb.msg`、`adb reverse --list` | 缺 `tcp:9999 tcp:9999` 就点一次「启动」（幂等，不会踢断） |
| 摇杆条在动但游戏不动 | `padOk`/`padErr` | `padOk=false` = ViGEm 没装或 DLL 缺；跑 `driver\ViGEmBus_setup.exe` |
| 手柄识别但不动 | `running`、`frames` | `running=false` 就 `{"start":true}`；`frames` 不涨 = 设备没推流 |
| 几秒断一次 | 日志"已连接"次数 | 老 bug 回归：检查是否有人又加了无条件 `adb reverse` |
| 手感黏/过冲 | `pred` 与 `lagMs` | 黏→升档；过冲→降档或加死区 |
| 端口占用 | 启动日志"端口被占用" | 杀掉旧 `padcore.exe`；`netstat -ano \| findstr :9998` |
| 网页白屏 | 装目录下 `web\index.html` 是否存在 | spec 漏了 `datas`，重新打包 |

自检三连（每次改动后跑）：
```bash
curl -s 127.0.0.1:9998/state
curl -s -X POST -d '{"pred":0}' 127.0.0.1:9998/
joy.cpl   （Win+R 里输入 joy.cpl，看"属性"里十字线是否跟手）
```


---

## 14. 完整验收清单（16 条，逐条打勾才能发版）

1. 双击桌面快捷方式 → 浏览器自动开 127.0.0.1:9998，右上角显示「运行中」。
2. joy.cpl 里存在 Xbox 360 Controller for Windows。
3. 四轴单独拨动：十字线只在该方向移动，不串轴。
4. 回中：ax 回到 1024±20，joy.cpl 归零。
5. 死区：deadzone=6 时小幅抖动不输出。
6. 曲线：expo=1.5 时中点附近更钝、端点更陡。
7. 预测档 0→3：lagMs 不变，但游戏内跟手度递增，无甩尾。
8. 拔 USB → 10 秒内自动恢复（不点任何按钮）。
9. 插回 USB → adb.dev 自动更新。
10. 连续 10 分钟：connected 不出现 false，"已连接"日志只出现 1 次。
11. 停止（{"cmd":"stop"}）→ joy.cpl 手柄消失，reverse --list 清空。
12. 再启动 → 手柄回来，无需重启浏览器。
13. 换 model：设备端页面显示数据源切换成功。
14. selftest 返回 CRSF/混控/算力结果。
15. 卸载脚本跑一遍：目录、快捷方式都干净，驱动保留。
16. 打包体积/版本号与 release 一致，**遥控器端与电脑端同一个 zip** 发布。
