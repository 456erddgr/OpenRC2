# -*- coding: utf-8 -*-
"""OpenRC2 手柄核心（无界面）：收遥控器 9999 JSON -> ViGEm -> 给网页 UI 推 SSE。
网页 UI 走 60fps 的 CSS/Canvas 渲染，Python 只做数据与手柄，不再画界面。
"""
import json, os, socket, sys, threading, time
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

    def snap(self):
        with self.lock:
            return {"ax": predict(), "raw": list(self.ax), "frames": self.frames, "fps": round(self.fps, 1),
                    "connected": self.connected, "src": self.src, "mode": self.mode,
                    "model": self.model, "padOk": self.pad_ok, "padErr": self.pad_err,
                    "running": self.running}


ST = State()
PAD = None


PRED_CAP_MS = 260          # 最多外推 260ms（再长就贴住最新采样，避免过冲）
PRED_MAX_V = 2200.0        # 每秒最大变化（单位/秒），防止抖动放大


def predict():
    """用最近两次采样算速度，按经过时间外推，喂给 UI 做 60/120fps 平滑。
    源只有约 3Hz，这一步把"卡顿感"变成"跟手"，但不会创造真实带宽。"""
    now = time.time()
    if not ST.t_last:
        return list(ST.ax)
    age = now - ST.t_last
    if age > PRED_CAP_MS / 1000.0 or ST.dt_src <= 0:
        return list(ST.ax)
    out = []
    for i in range(6):
        v = (ST.ax[i] - ST.prev[i]) / ST.dt_src
        v = max(-PRED_MAX_V, min(PRED_MAX_V, v))
        out.append(int(max(0, min(2047, ST.ax[i] + v * age))))
    return out


def on_frame(ax):
    now = time.time()
    with ST.lock:
        ST.prev = ST.ax
        ST.ax = list(ax)
        ST.dt_src = max(0.02, min(0.6, now - ST.t_last)) if ST.t_last else 0.1
        ST.t_last = now
        ST.frames += 1
        dt = time.time() - ST.t0
        if dt >= 0.5:
            ST.fps = (ST.frames - ST.n0) / dt
            ST.t0, ST.n0 = time.time(), ST.frames
    if PAD is not None and PAD.ok:
        now = time.time()
        if now - getattr(on_frame, "_t", 0.0) >= 0.008:      # 限 125Hz
            on_frame._t = now
            PAD.mode = ST.mode
            PAD.push(ax)


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
        cmd = req.get("cmd")
        ok, msg = True, "ok"
        if cmd in ("start", "stop"):
            ok, msg = toggle(cmd == "start")
        elif cmd == "mode":
            ST.mode = req.get("v", "xbox")
        elif cmd == "model":
            ST.model = str(req.get("v", "0"))
            ok, msg = send_model(ST.model)
        body = json.dumps({"ok": ok, "msg": msg}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


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
