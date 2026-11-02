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
