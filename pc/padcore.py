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
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "OpenRC2-Pad.py")
    spec = importlib.util.spec_from_file_location("padmod", p)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


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
        self.t0 = time.time()
        self.n0 = 0
        # 三点历史 + 估计量（预测插值用）
        self.h0, self.h1, self.h2 = [1024]*6, [1024]*6, [1024]*6
        self.t_hist = [0.0, 0.0, 0.0]
        self.vel = [0.0]*6
        self.acc = [0.0]*6
        self.step = [0.0]*6
        self.pred = 2          # 0关 1弱 2中 3强
        self.hz = 0.0          # 源采样率（平滑后）
        self.deadzone = 6
        self.expo = 1.0

    def snap(self):
        with self.lock:
            lag = int((time.time() - self.t_last) * 1000) if self.t_last else -1
            return {"ax": predict(), "raw": list(self.ax), "frames": self.frames, "fps": round(self.fps, 1),
                    "pred": int(self.pred), "lagMs": lag, "srcHz": round(self.hz, 1),
                    "connected": self.connected, "src": self.src, "mode": self.mode,
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
        m = _load_pad_module()
        ok, out = m._adb_run(["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                              "--es", "action", "model", "--ei", "v", str(v)], timeout=12)
        return ok, ("型号已同步" if ok else out.strip()[:60])
    except Exception as ex:
        return False, str(ex)


def toggle(start):
    global PAD
    full = _load_pad_module()
    if start:
        if PAD is None:
            PAD = full.Pad()
            with ST.lock:
                ST.pad_ok, ST.pad_err = PAD.ok, PAD.err
        full._adb_run(["reverse", "tcp:%d" % PORT_LINK, "tcp:%d" % PORT_LINK])
        full._adb_run(["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                       "--es", "action", "pad", "--ei", "on", "1"], timeout=15)
        if LN is None:
            start_link()
        with ST.lock:
            ST.running = True
        return (PAD.ok, "已启动" if PAD.ok else PAD.err)
    with ST.lock:
        ST.running = False
    full._adb_run(["reverse", "--remove", "tcp:%d" % PORT_LINK])
    return True, "已停止"


LN = None


def start_link():
    global LN
    m = _load_pad_module()
    LN = m.Link(on_frame, on_state)
    LN.start()


def main():
    start_link()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT_HTTP), H)
    print("UI: http://127.0.0.1:%d/" % PORT_HTTP)
    srv.serve_forever()


if __name__ == "__main__":
    main()
