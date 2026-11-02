# -*- coding: utf-8 -*-
"""OpenRC2 手柄核心（无界面）：收遥控器 9999 JSON -> ViGEm -> 给网页 UI 推 SSE。
网页 UI 走 60fps 的 CSS/Canvas 渲染，Python 只做数据与手柄，不再画界面。
"""
import json, math, os, socket, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util

PORT_LINK = 9999
PORT_HTTP = 9998


def _load_pad_module():
    p = _res("OpenRC2-Pad.py")
    if not os.path.exists(p):
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "OpenRC2-Pad.py")
    spec = importlib.util.spec_from_file_location("padmod", p)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def _res(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def adb_exe():
    cands = [_res(os.path.join("platform-tools", "adb.exe")),
             os.path.join(os.path.dirname(os.path.abspath(__file__)), "platform-tools", "adb.exe")]
    la = os.environ.get("LOCALAPPDATA", "")
    if la:
        cands.append(os.path.join(la, "Android", "Sdk", "platform-tools", "adb.exe"))
    for c in cands:
        if os.path.exists(c):
            return c
    return "adb"


def _run_adb(args, timeout=8):
    import subprocess
    try:
        r = subprocess.run([adb_exe()] + args, capture_output=True, timeout=timeout,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return r.returncode, (r.stdout or b"").decode("utf-8", "ignore"), (r.stderr or b"").decode("utf-8", "ignore")
    except Exception as e:
        return -1, "", str(e)


def adb_devices():
    rc, out, err = _run_adb(["devices"])
    if rc != 0:
        return [], (err or "adb 不可用")
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or "	" not in line:
            continue
        name, state = line.split("	", 1)
        if state.strip() == "device":
            devs.append(name.strip())
    return devs, ""


def _reverse_present(dev):
    """adbd 的 reverse 表里已经有 9999 了吗（幂等检查，避免每轮重建把连接踢断）。"""
    want = "tcp:%d tcp:%d" % (PORT_LINK, PORT_LINK)
    rc, out, err = _run_adb(["-s", dev, "reverse", "--list"])
    if rc != 0:
        return False
    return want in (out or "")


def ensure_reverse(verbose=False, force=False):
    """建 adb 反向端口并把遥控器端的推流打开。返回 (ok, msg)。

    2026-11-02：原来每 5 秒无条件 `adb reverse` + `am start` 一次，
    每轮都会拆掉已有转发并把 App 重新拉到前台 —— 用户看到的就是
    「手柄连电脑几秒断一下、几秒断一下」。改成：端口已在就绝不动它，
    推流已开就绝不重复拉起 App。
    """
    devs, err = adb_devices()
    if not devs:
        return False, "没找到已连接的遥控器（" + (err or "请插 USB 或开无线调试") + "）"
    dev = devs[0]
    if force or not _reverse_present(dev):
        rc, out, err = _run_adb(["-s", dev, "reverse", "tcp:%d" % PORT_LINK, "tcp:%d" % PORT_LINK])
        if rc != 0 and "already" not in (err or "").lower() and "already" not in (out or "").lower():
            return False, "adb reverse 失败：" + (err or out or "未知错误")[:80]
    if not ST.pad_on:
        _run_adb(["-s", dev, "shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                  "--es", "action", "pad", "--ei", "on", "1"])
        ST.pad_on = True
    ST.adb_dev = dev
    ST.adb_msg = "已连接 " + dev
    return True, ST.adb_msg


class Link(threading.Thread):
    """遥控器推流接收端 0.0.0.0:9999，每行一个 JSON {ax:6 个 0..2047}。"""

    NL = bytes([10])

    def __init__(self, on_frame, on_state):
        super().__init__(daemon=True)
        self.on_frame = on_frame
        self.on_state = on_state
        self.srv = None
        self.stop_flag = False
        self.frames = 0
        self.connected = False
        self.last_frame_at = 0.0
        self.latest = [1024] * 6

    def run(self):
        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", PORT_LINK))
            srv.listen(4)
            srv.settimeout(1.0)
            self.srv = srv
            self.on_state("等待遥控器连接 0.0.0.0:%d" % PORT_LINK)
        except Exception as e:
            self.on_state("端口被占用：%s" % e)
            return
        while not self.stop_flag:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            except Exception:
                break
            self.connected = True
            self.on_state("已连接 %s" % (addr[0],))
            buf = bytes()
            try:
                while not self.stop_flag:
                    d = conn.recv(8192)
                    if not d:
                        break
                    buf += d
                    while self.NL in buf:
                        line, buf = buf.split(self.NL, 1)
                        if not line.strip():
                            continue
                        try:
                            f = json.loads(line.decode("utf-8", "replace"))
                        except Exception:
                            continue
                        ax = f.get("ax") or [1024] * 6
                        self.latest = ax
                        self.frames += 1
                        self.last_frame_at = time.time()
                        self.on_frame(ax)
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
            self.connected = False
            self.on_state("遥控器断开，等待重连 …")

    def stop(self):
        self.stop_flag = True
        try:
            if self.srv:
                self.srv.close()
        except Exception:
            pass


class VPad(object):
    """自带的最小虚拟手柄（不依赖 GUI 模块，避免 tkinter 依赖）。
    轴序 = 遥控器 DUML 原始 6 轴：[0]副翼 [1]俯仰 [2]油门 [3]偏航 [4][5]波轮。
    左摇杆 = 偏航(ax3,X) + 油门(ax2,Y)；右摇杆 = 副翼(ax0,X) + 俯仰(ax1,Y)。"""

    def __init__(self):
        self.pad = None
        self.mode = "xbox"
        self.deadzone = 0.02
        self.expo = 1.0
        self.ok = False
        self.err = ""
        try:
            import vgamepad as vg
        except Exception as e:
            self.err = "缺少 vgamepad 模块：" + str(e)[:120]
            return
        try:
            self.pad = vg.VX360Gamepad()
            self.ok = True
        except Exception as e:
            self.err = "ViGEmBus 未就绪：" + str(e)[:120]

    def _n(self, v):
        x = (float(v) - 1024.0) / 1024.0
        if abs(x) < self.deadzone:
            x = 0.0
        else:
            s = 1.0 if x > 0 else -1.0
            x = s * pow(abs(x), max(0.2, self.expo))
        return max(-1.0, min(1.0, x))

    def push(self, ax):
        if not self.ok or not ax or len(ax) < 6:
            return
        try:
            self.pad.left_joystick_float(x_value_float=self._n(ax[3]), y_value_float=-self._n(ax[2]))
            self.pad.right_joystick_float(x_value_float=self._n(ax[0]), y_value_float=-self._n(ax[1]))
            self.pad.left_trigger(value=0)
            self.pad.right_trigger(value=0)
            self.pad.update()
        except Exception as e:
            self.ok = False
            self.err = "推手柄失败：" + str(e)[:120]



class State(object):
    def __init__(self):
        self.lock = threading.Lock()
        self.ax = [1024] * 6
        self.prev = [1024] * 6
        self.t_last = 0.0
        self.dt_src = 0.0
        self.frames = 0
        self.fps = 0.0
        self.connected = False
        self.src = "等待遥控器"
        self.mode = "xbox"
        self.model = "0"
        self.pad_ok = False
        self.pad_err = ""
        self.running = False
        self.pad_on = False
        self.t0 = time.time()
        self.n0 = 0
        # 三点历史 + 估计量（预测插值用）
        self.h0, self.h1, self.h2 = [1024]*6, [1024]*6, [1024]*6
        self.t_hist = [0.0, 0.0, 0.0]
        self.vel = [0.0]*6
        self.acc = [0.0]*6
        self.step = [0.0]*6
        self.pred = 2
        self.adb_dev = ""
        self.adb_msg = "未连接 adb"          # 0关 1弱 2中 3强
        self.hz = 0.0          # 源采样率（平滑后）
        self.deadzone = 6
        self.expo = 1.0

    def snap(self):
        with self.lock:
            lag = int((time.time() - self.t_last) * 1000) if self.t_last else -1
            return {"ax": predict(), "raw": list(self.ax), "frames": self.frames, "fps": round(self.fps, 1),
                    "pred": int(self.pred), "lagMs": lag, "srcHz": round(self.hz, 1),
                    "connected": self.connected, "adb": {"dev": self.adb_dev, "msg": self.adb_msg}, "src": self.src, "mode": self.mode,
                    "model": self.model, "padOk": self.pad_ok, "padErr": self.pad_err,
                    "running": self.running}


ST = State()
PAD = None
NOTHIST = [0]      # 是否已收到过一帧


# ── 预测参数（档位 0..3）──────────────────────────────────────────────
# 源只有约 10Hz（100ms/帧），ViGEm 每 8ms 推一次：不预测就等于永远推 100ms 前的值。
# 这里用「三点拟合（速度+加速度）」，并强制反向即停 + 过冲钳制，避免甩尾。
PRED_CAP_MS = (0.0, 50.0, 100.0, 160.0)
PRED_BLEND  = (1.0, 0.35, 0.55, 0.75)
PRED_ACCW   = (0.0, 0.20, 0.45, 0.70)
PRED_OVER   = (1.0, 1.05, 1.15, 1.30)
VEL_DEAD    = 0.008
HOLD_DECAY  = 140.0


def predict():
    """返回 0..2047 的预测值列表；level=0 时原样返回最新采样。"""
    lv = int(ST.pred)
    lv = 0 if lv < 0 else (3 if lv > 3 else lv)
    if lv == 0 or NOTHIST[0] == 0:
        return list(ST.ax)
    now = time.time()
    age_ms = (now - ST.t_last) * 1000.0
    cap = PRED_CAP_MS[lv]
    ahead = min(age_ms, cap)
    decay = 1.0 if age_ms <= cap else math.exp(-(age_ms - cap) / HOLD_DECAY)
    out = [0] * 6
    for i in range(6):
        cur, v, a = ST.h2[i], ST.vel[i], ST.acc[i]
        # 钳制：预测不许超过「最近一步 × over + 6」
        step = abs(ST.step[i])
        capd = step * PRED_OVER[lv] + 6.0
        p = cur + v * ahead + 0.5 * a * (ahead / 1000.0) ** 2 * 1000.0 * PRED_ACCW[lv] * 0.35
        if decay < 1.0:
            p = cur + (p - cur) * decay
        if p > cur + capd: p = cur + capd
        elif p < cur - capd: p = cur - capd
        out[i] = int(max(0, min(2047, p)))
    return out


def on_frame(ax):
    now = time.time()
    with ST.lock:
        lv = int(ST.pred)
        lv = 0 if lv < 0 else (3 if lv > 3 else lv)
        if NOTHIST[0] == 0:
            ST.h0, ST.h1, ST.h2 = list(ax), list(ax), list(ax)
            ST.t_hist = [now - 0.1, now - 0.05, now]
            ST.vel = [0.0] * 6
            ST.acc = [0.0] * 6
            ST.step = [0.0] * 6
            NOTHIST[0] = 1
        else:
            ST.h0, ST.h1, ST.h2 = ST.h1, ST.h2, list(ax)
            ST.t_hist = [ST.t_hist[1], ST.t_hist[2], now]
        dt1 = max(8.0, (ST.t_hist[1] - ST.t_hist[0]) * 1000.0)
        dt2 = max(8.0, (ST.t_hist[2] - ST.t_hist[1]) * 1000.0)
        bl = PRED_BLEND[lv]
        for i in range(6):
            d = ST.h2[i] - ST.h1[i]
            ST.step[i] = abs(d)
            v_now = 0.0 if abs(d) / 2047.0 < VEL_DEAD else d / dt2
            reverse = ST.vel[i] != 0 and v_now != 0 and ((v_now > 0) != (ST.vel[i] > 0))
            if reverse:
                ST.vel[i] = 0.0
                ST.acc[i] = 0.0
            ST.vel[i] = ST.vel[i] * (1 - bl) + v_now * bl
            if lv >= 2:
                v_prev = (ST.h1[i] - ST.h0[i]) / dt1
                a_now = (ST.vel[i] - v_prev) / (0.5 * (dt1 + dt2) / 1000.0)
                if (a_now > 0) != (ST.vel[i] > 0) or abs(ST.vel[i]) < VEL_DEAD * 2047:
                    a_now = 0.0
                ST.acc[i] = ST.acc[i] * 0.5 + a_now * 0.5
            else:
                ST.acc[i] = 0.0

        ST.prev = ST.ax
        ST.ax = list(ax)
        ST.dt_src = max(0.02, min(0.6, now - ST.t_last)) if ST.t_last else 0.1
        ST.t_last = now
        ST.frames += 1
        ST.hz = 0.9 * ST.hz + 0.1 * (1.0 / ST.dt_src)
        dt = time.time() - ST.t0
        if dt >= 0.5:
            ST.fps = (ST.frames - ST.n0) / dt
            ST.t0, ST.n0 = time.time(), ST.frames

    # 推给 ViGEm 的是「预测值」而不是原始采样：这是压延迟的关键一步
    if PAD is not None and PAD.ok and ST.running:
        now = time.time()
        if now - getattr(on_frame, "_t", 0.0) >= 0.008:      # 125Hz
            on_frame._t = now
            PAD.mode = ST.mode
            PAD.push(predict())


def on_state(msg):
    with ST.lock:
        ST.connected = "已连接" in msg
        ST.src = msg


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/state"):
            body = json.dumps(ST.snap()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body); return
        if self.path.startswith("/events"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            try:
                while True:
                    self.wfile.write(b"data: " + json.dumps(ST.snap()).encode() + b"\n\n")
                    self.wfile.flush()
                    time.sleep(1 / 60.0)
            except Exception:
                return
        if self.path.startswith("/bg"):
            bdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backgrounds")
            cands = []
            try:
                cands = [os.path.join(bdir, f) for f in sorted(os.listdir(bdir))
                         if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
            except Exception:
                pass
            if cands:
                data = open(cands[0], "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self.send_response(404); self.end_headers(); return
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
        f = "index.html" if self.path in ("/", "") else self.path.lstrip("/")
        p = os.path.join(root, f)
        if not os.path.isfile(p):
            self.send_response(404); self.end_headers(); return
        data = open(p, "rb").read()
        ctype = "text/html; charset=utf-8" if f.endswith(".html") else \
                "text/css" if f.endswith(".css") else "application/javascript"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(raw.decode() or "{}")
        except Exception:
            req = {}
        # 两种调用风格都接受：{cmd:"start",v:true} 与 {start:true} / {pred:2} / {deadzone:6}
        cmd = req.get("cmd")
        ok, msg = True, "ok"
        if cmd in ("start", "stop"):
            ok, msg = toggle(cmd == "start")
        elif cmd == "mode":
            ST.mode = req.get("v", "xbox")
        elif cmd == "model":
            ST.model = str(req.get("v", "0"))
            ok, msg = send_model(ST.model)
        if "start" in req:
            ok, msg = toggle(bool(req.get("start")))
        if "mode" in req:
            ST.mode = str(req.get("mode"))
        if "model" in req:
            ST.model = str(req.get("model"))
            ok2, msg2 = send_model(ST.model)
            ok, msg = (ok and ok2), msg2
        if req.get("connect") or cmd == "connect":
            ok, msg = ensure_reverse()
            if ok and not ST.running:
                try:
                    toggle(True)          # 连上就自动开始推手柄，用户无需再点“启动”
                except Exception:
                    pass
        if "pred" in req:
            v = int(req.get("pred") or 0)
            ST.pred = 0 if v < 0 else (3 if v > 3 else v)
            msg = "预测档=%d" % ST.pred
        if "deadzone" in req:
            ST.deadzone = int(req.get("deadzone") or 0)
            msg = "死区=%d%%" % ST.deadzone
        if "expo" in req:
            ST.expo = float(req.get("expo") or 1.0)
            msg = "曲线=%.2f" % ST.expo
        if req.get("selftest"):
            ok, msg = selftest()
        if req.get("joycpl"):
            try:
                os.startfile("control", "joy.cpl"); msg = "已打开 joy.cpl"
            except Exception as ex:
                ok, msg = False, str(ex)
        body = json.dumps({"ok": ok, "msg": msg}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def selftest(seconds=4.0):
    """4 秒内记录六轴最大变化量，判断"软件收得到摇杆"。"""
    t0 = time.time()
    lo = list(ST.ax); hi = list(ST.ax)
    while time.time() - t0 < seconds:
        with ST.lock:
            for i in range(6):
                lo[i] = min(lo[i], ST.ax[i]); hi[i] = max(hi[i], ST.ax[i])
        time.sleep(0.05)
    rng = [hi[i] - lo[i] for i in range(6)]
    worst = max(rng)
    names = ["左水平", "左竖直", "右水平", "右竖直", "左波轮", "右波轮"]
    if worst < 20:
        return True, "自检：%.0fs 内六轴几乎没动（最大变化 %d）—— 请推杆再试" % (seconds, worst)
    moved = [names[i] for i in range(6) if rng[i] >= 20]
    return True, "自检通过：%s 有变化，最大 %d" % ("、".join(moved), worst)


def send_model(v):
    try:
        ok, out = _run_adb(["-s", ST.adb_dev] if ST.adb_dev else [],
                           ["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                            "--es", "action", "model", "--ei", "v", str(v)] if False else [], 12)
        return ok, ("型号已同步" if ok else out.strip()[:60])
    except Exception as ex:
        return False, str(ex)


def toggle(start):
    global PAD
    if start:
        if PAD is None:
            PAD = VPad()
            with ST.lock:
                ST.pad_ok, ST.pad_err = PAD.ok, PAD.err
        # 只在缺端口时补，避免每次「启动」都把转发重建一遍（会踢断正在跑的连接）
        try:
            devs, _e = adb_devices()
            need = True if not devs else (not _reverse_present(devs[0]))
        except Exception:
            need = True
        if need:
            full._adb_run(["reverse", "tcp:%d" % PORT_LINK, "tcp:%d" % PORT_LINK])
        full._adb_run(["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                       "--es", "action", "pad", "--ei", "on", "1"], timeout=15)
        ST.pad_on = True
        if LN is None:
            start_link()
        with ST.lock:
            ST.running = True
        return (PAD.ok, "已启动" if PAD.ok else PAD.err)
    with ST.lock:
        ST.running = False
    full._adb_run(["reverse", "--remove", "tcp:%d" % PORT_LINK])
    ST.pad_on = False
    return True, "已停止"


LN = None


def start_link():
    global LN
    LN = Link(on_frame, on_state)
    LN.start()


def _link_healthy():
    if LN is None or not LN.connected:
        return False
    return (time.time() - LN.last_frame_at) < 2.0


def _auto_connect():
    """后台线程：只在链路真的断了才去修 adb 反向，健康时绝不打扰（否则每 5 秒踢一次连接）。"""
    time.sleep(1.0)
    while True:
        try:
            if _link_healthy():
                time.sleep(10.0)
                continue
            ok, _ = ensure_reverse()
            time.sleep(3.0 if not ok else 1.5)
        except Exception:
            time.sleep(5.0)


def main():
    start_link()
    threading.Thread(target=_auto_connect, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT_HTTP), H)
    print("UI: http://127.0.0.1:%d/" % PORT_HTTP)
    srv.serve_forever()


if __name__ == "__main__":
    main()
