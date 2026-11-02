# -*- coding: utf-8 -*-
"""OpenRC2 虚拟手柄（PC 端 GUI）  作者：软糖布丁
同 RM510 思路：遥控器把摇杆数据经 adb reverse 发到 PC，PC 用 ViGEmBus 造手柄。
"""
import json, os, sys, socket, subprocess, shutil, threading, time, tkinter as tk
from tkinter import filedialog
try:
    from PIL import Image, ImageTk, ImageFilter, ImageEnhance
    HAS_PIL = True
except Exception:
    HAS_PIL = False

PORT = 9999


def _resolve(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def _set_icon(root):
    """窗口图标 = 遥控端同一张图标。"""
    p = _resolve("icon.png")
    if os.path.exists(p):
        try:
            img = tk.PhotoImage(file=p)
            root.iconphoto(True, img)
            root._icon_img = img
            return True
        except Exception:
            return False
    return False

CENTER, HALF = 1024.0, 660.0
BG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backgrounds")
INK, CARD, CARD2 = "#0b0f14", "#151b23", "#1d2530"
TXT, SUB = "#e6edf3", "#8b949e"
AMBER, TEAL = "#f5b942", "#2dd4bf"


def _adb_exe():
    """找 adb.exe：PATH -> 常见安装位置 -> 程序自带 platform-tools。"""
    for name in ("adb", "adb.exe"):
        q = shutil.which(name)
        if q:
            return q
    here = os.path.dirname(os.path.abspath(__file__))
    cands = [
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.EXE"),
        os.path.join("C:" + os.sep, "platform-tools", "adb.exe"),
        os.path.join(here, "platform-tools", "adb.exe"),
        os.path.join(getattr(sys, "_MEIPASS", here), "platform-tools", "adb.exe"),
    ]
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return None


def _adb_run(args, timeout=12):
    exe = _adb_exe()
    if not exe:
        return False, "找不到 adb.exe（把 platform-tools 放本程序同目录）"
    try:
        r = subprocess.run([exe] + args, capture_output=True, text=True, timeout=timeout,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return r.returncode == 0, ((r.stdout or "") + (r.stderr or "")).strip()
    except Exception as e:
        return False, str(e)


class Link(threading.Thread):
    def __init__(self, on_frame, on_state):
        super().__init__(daemon=True)
        self.on_frame, self.on_state = on_frame, on_state
        self.srv = None; self.stop_flag = False
        self.frames = 0; self.connected = False
        self.latest = [1024] * 6

    def run(self):
        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", PORT)); srv.listen(4); srv.settimeout(1.0)
            self.srv = srv
            self.on_state("等待遥控器连接 0.0.0.0:%d" % PORT)
        except Exception as e:
            self.on_state("端口被占用：%s" % e); return
        while not self.stop_flag:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            except Exception:
                break
            self.connected = True
            self.on_state("已连接 %s" % (addr[0],))
            buf = b""
            try:
                while not self.stop_flag:
                    d = conn.recv(8192)
                    if not d:
                        break
                    buf += d
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        if not line.strip():
                            continue
                        try:
                            f = json.loads(line.decode("utf-8", "replace"))
                        except Exception:
                            continue
                        ax = f.get("ax") or [1024] * 6
                        self.latest = ax; self.frames += 1
                        self.on_frame(ax)
            except Exception:
                pass
            finally:
                try: conn.close()
                except Exception: pass
            self.connected = False
            self.on_state("遥控器断开，等待重连 …")

    def stop(self):
        self.stop_flag = True
        try:
            if self.srv: self.srv.close()
        except Exception: pass


def _resource(rel):
    """打包后从 sys._MEIPASS 取资源，源码运行时从脚本目录取。"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def ensure_driver():
    """确保 ViGEmBus 可用：能建虚拟手柄就直接用，否则装内置驱动再试。"""
    try:
        import vgamepad  # noqa: F401
    except Exception as e:
        return False, "缺少 vgamepad 模块：%s" % e
    try:
        vgamepad.VX360Gamepad()
        return True, "ViGEmBus 已就绪"
    except Exception as first:
        exe = _resource(os.path.join("driver", "ViGEmBus_setup.exe"))
        if not os.path.isfile(exe):
            return False, "ViGEmBus 未安装，且找不到内置驱动：%s" % first
        try:
            subprocess.run([exe, "/qn", "/norestart"], timeout=180,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except Exception as e:
            return False, "安装内置驱动失败：%s" % e
        try:
            import importlib
            importlib.reload(vgamepad)
            vgamepad.VX360Gamepad()
            return True, "已用内置驱动装好 ViGEmBus"
        except Exception as e2:
            return False, "装完仍不可用：%s" % e2


class Pad(object):
    """mode: xbox=完整 Xbox360；fvp=穿越机手感（只摇杆、expo）"""
    def __init__(self):
        self.pad = None; self.mode = "xbox"
        self.deadzone = 0.02; self.expo = 1.0
        self.ok = False; self.err = ""
        ok, msg = ensure_driver()
        if not ok:
            self.ok = False; self.err = msg; return
        try:
            import vgamepad as vg
            self.pad = vg.VX360Gamepad(); self.ok = True
        except Exception as e:
            self.ok = False; self.err = str(e)

    def _n(self, raw):
        x = max(-1.0, min(1.0, (float(raw) - CENTER) / HALF))
        if abs(x) < self.deadzone:
            return 0.0
        s = 1.0 if x > 0 else -1.0
        x = s * (abs(x) - self.deadzone) / (1.0 - self.deadzone)
        if self.mode == "fvp" and self.expo != 1.0:
            x = s * (abs(x) ** self.expo)
        return x

    def push(self, ax):
        """ax 顺序 = 遥控器 DUML 原始 6 轴：[0]副翼AIL [1]俯仰ELE [2]油门THR [3]偏航RUD [4]左波轮 [5]右波轮。
        左摇杆 = 偏航(ax3, X) + 油门(ax2, Y)；右摇杆 = 副翼(ax0, X) + 俯仰(ax1, Y)。
        之前把 ax[0] 当左杆 X、ax[3] 当右杆 X，所以推油门动的是右杆竖直、模拟器里等于没反应。"""
        if not self.ok:
            return
        try:
            self.pad.left_joystick_float(x_value_float=self._n(ax[3]), y_value_float=-self._n(ax[2]))
            self.pad.right_joystick_float(x_value_float=self._n(ax[0]), y_value_float=-self._n(ax[1]))
            if self.mode == "xbox":
                self.pad.left_trigger(value=0); self.pad.right_trigger(value=0)
            self.pad.update()
        except Exception:
            self.ok = False; self.err = "写入失败（ViGEmBus 驱动没装？）"


class App(object):
    def __init__(self, root):
        self.root = root
        root.title("OpenRC2 虚拟手柄")
        root.geometry("1000x640"); root.minsize(900, 580)
        root.configure(bg=INK)
        self.bg_img = None; self.bg_index = 0; self.cands = []
        self.link = None; self.pad = Pad(); self.running = False
        self.fps_t0 = time.time(); self.fps_n0 = 0; self.fps = 0.0
        self._pending = None
        self._build()
        self._scan_bg()
        self._tick()

    def _scan_bg(self):
        if os.path.isdir(BG_DIR):
            for fn in sorted(os.listdir(BG_DIR)):
                if fn.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                    self.cands.append(os.path.join(BG_DIR, fn))
        if self.cands:
            self.set_bg(self.cands[0])

    def set_bg(self, path):
        if not HAS_PIL:
            return
        try:
            im = Image.open(path).convert("RGB").resize((1000, 640), Image.LANCZOS)
            im = im.filter(ImageFilter.GaussianBlur(3))
            im = ImageEnhance.Brightness(im).enhance(0.34)
            self.bg_img = ImageTk.PhotoImage(im)
            self.bg_label.configure(image=self.bg_img)
        except Exception:
            pass

    def pick_bg(self):
        p = filedialog.askopenfilename(title="选择背景图",
            filetypes=[("图片", "*.png *.jpg *.jpeg *.webp")])
        if p:
            self.set_bg(p)

    def next_bg(self):
        if not self.cands:
            return
        self.bg_index = (self.bg_index + 1) % len(self.cands)
        self.set_bg(self.cands[self.bg_index])

    def card(self, parent):
        return tk.Frame(parent, bg=CARD, highlightbackground="#243040",
                        highlightthickness=1)

    def _build(self):
        self.bg_label = tk.Label(self.root, bg=INK)
        self.bg_label.place(x=0, y=0, relwidth=1, relheight=1)

        head = tk.Frame(self.root, bg=INK); head.pack(fill="x", padx=20, pady=(16, 8))
        tk.Label(head, text="OpenRC2", bg=INK, fg=TXT,
                 font=("Segoe UI Semibold", 22)).pack(side="left")
        tk.Label(head, text="  虚拟手柄 · PC 端", bg=INK, fg=SUB,
                 font=("Microsoft YaHei UI", 11)).pack(side="left", pady=(8, 0))
        self.dot = tk.Label(head, text="●  未启动", bg=INK, fg=SUB,
                            font=("Microsoft YaHei UI", 11))
        self.dot.pack(side="right", pady=(6, 0))

        body = tk.Frame(self.root, bg=INK); body.pack(fill="both", expand=True, padx=20)
        left = self.card(body); left.pack(side="left", fill="y", padx=(0, 12), ipadx=16, ipady=14)
        tk.Label(left, text="手柄类型", bg=CARD, fg=TXT,
                 font=("Microsoft YaHei UI", 12, "bold")).pack(anchor="w")
        self.mode = tk.StringVar(value="xbox")
        for val, label, desc in (
                ("xbox", "Xbox 360 手柄", "摇杆+扳机+按键，模拟器/游戏通用"),
                ("fvp", "穿越机手感 (FVP)", "只摇杆，中心回弹 + Expo 曲线")):
            tk.Radiobutton(left, text=label, variable=self.mode, value=val,
                           command=self.on_mode, bg=CARD, fg=TXT, selectcolor=CARD2,
                           activebackground=CARD, activeforeground=AMBER,
                           font=("Microsoft YaHei UI", 11)).pack(anchor="w", pady=(4, 0))
            tk.Label(left, text="   " + desc, bg=CARD, fg=SUB,
                     font=("Microsoft YaHei UI", 9)).pack(anchor="w")

        tk.Label(left, text="遥控器型号", bg=CARD, fg=TXT,
                 font=("Microsoft YaHei UI", 12, "bold")).pack(anchor="w", pady=(14, 0))
        self.model = tk.StringVar(value="0")
        for val, label in (("0", "自动识别"), ("1", "RC2"), ("2", "RC Pro")):
            tk.Radiobutton(left, text=label, variable=self.model, value=val,
                           command=self.on_model, bg=CARD, fg=TXT, selectcolor=CARD2,
                           activebackground=CARD, activeforeground=AMBER,
                           font=("Microsoft YaHei UI", 11)).pack(anchor="w")
        self.model_note = tk.Label(left, text="  同步到遥控器（决定摇杆数据源）", bg=CARD,
                                   fg=SUB, font=("Microsoft YaHei UI", 9))
        self.model_note.pack(anchor="w")

        tk.Label(left, text="死区 6%", bg=CARD, fg=SUB,
                 font=("Microsoft YaHei UI", 10)).pack(anchor="w", pady=(12, 0))
        self.dz = tk.Scale(left, from_=0, to=20, orient="horizontal", bg=CARD, fg=TXT,
                           troughcolor=CARD2, highlightthickness=0, bd=0, length=196,
                           showvalue=False, command=self.on_dz)
        self.dz.set(6); self.dz.pack(anchor="w")
        tk.Label(left, text="曲线 Expo 1.00", bg=CARD, fg=SUB,
                 font=("Microsoft YaHei UI", 10)).pack(anchor="w", pady=(6, 0))
        self.ex = tk.Scale(left, from_=100, to=250, orient="horizontal", bg=CARD, fg=TXT,
                           troughcolor=CARD2, highlightthickness=0, bd=0, length=196,
                           showvalue=False, command=self.on_ex)
        self.ex.set(100); self.ex.pack(anchor="w")

        for text, cmd in (("自检：扫一遍摇杆", self.selftest),
                          ("换背景图", self.pick_bg), ("下一张背景", self.next_bg),
                          ("打开 joy.cpl 验证", lambda: subprocess.Popen("joy.cpl"))):
            tk.Button(left, text=text, command=cmd, bg=CARD2, fg=TXT, bd=0,
                      activebackground="#243040", activeforeground=AMBER,
                      font=("Microsoft YaHei UI", 10), padx=12, pady=7
                      ).pack(fill="x", pady=(8, 0))

        right = self.card(body); right.pack(side="left", fill="both", expand=True,
                                            ipadx=16, ipady=14)
        tk.Label(right, text="摇杆实时数据", bg=CARD, fg=TXT,
                 font=("Microsoft YaHei UI", 12, "bold")).pack(anchor="w")
        names = ["左水平 · 偏航", "左竖直 · 油门", "右竖直 · 俯仰",
                 "右水平 · 副翼", "左波轮", "右波轮"]
        self.bars, self.vals = [], []
        for nm in names:
            row = tk.Frame(right, bg=CARD); row.pack(fill="x", pady=3)
            tk.Label(row, text=nm, bg=CARD, fg=SUB, width=13, anchor="w",
                     font=("Microsoft YaHei UI", 10)).pack(side="left")
            cv = tk.Canvas(row, height=12, bg=CARD2, highlightthickness=0)
            cv.pack(side="left", fill="x", expand=True, padx=8)
            lbl = tk.Label(row, text="1024", bg=CARD, fg=TXT, width=6,
                           font=("Consolas", 10))
            lbl.pack(side="right")
            self.bars.append(cv); self.vals.append(lbl)
        self.log = tk.Text(right, height=6, bg=CARD2, fg=SUB, bd=0,
                           font=("Consolas", 9), insertbackground=TXT)
        self.log.pack(fill="both", expand=True, pady=(10, 0))

        foot = tk.Frame(self.root, bg=INK); foot.pack(fill="x", padx=20, pady=(0, 16))
        self.btn = tk.Button(foot, text="启 动", command=self.toggle, bg=AMBER,
                             fg="#1a1206", bd=0, font=("Microsoft YaHei UI", 12, "bold"),
                             padx=28, pady=9, activebackground="#ffd479")
        self.btn.pack(side="left")
        self.stat = tk.Label(foot, text="就绪", bg=INK, fg=SUB,
                             font=("Microsoft YaHei UI", 10))
        self.stat.pack(side="left", padx=16)
        self._chrome()
        _set_icon(self.root)
        self.root.deiconify(); self.root.lift(); self.root.focus_force()
        # 新界面：液态玻璃（苹果风格）。旧界面用 OPENRC2_UI=old 回退
        self.glass = None
        if os.environ.get("OPENRC2_UI", "glass") != "old":
            try:
                import ui_glass
                self.glass = self._build_glass(ui_glass)
            except Exception as e:
                print("[ui] glass 失败，回退旧界面：%s" % e)

    # ---------- 外观：无边框 + 圆角 + 磨砂（Win10/11 acrylic） ----------
    # ---------- 新界面（液态玻璃） ----------
    def _build_glass(self, mod):
        # 把旧界面全部藏起来，只留新画布
        keep = None
        for ch in self.root.winfo_children():
            try:
                if ch.winfo_class() == "Canvas" and keep is None and len(self.root.winfo_children()) > 0:
                    continue
                ch.pack_forget(); ch.grid_forget(); ch.place_forget()
            except Exception:
                pass
        cb = {
            "start": lambda: (self.start() if not self.running else self.stop()),
            "stop": self.stop,
            "selftest": self.selftest,
            "model": self._glass_model,
            "mode": self._glass_mode,
            "bg": lambda: self.pick_bg(),
            "joycpl": lambda: subprocess.Popen("joy.cpl"),
        }
        self._ui_mod = mod
        return mod.GlassUI(self.root, cb)

    def _glass_model(self, v):
        self.glass.set_model(v)
        ok, out = self._adb("shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                            "--es", "action", "model", "--ei", "v", str(v))
        self.glass.set_status(text=("[adb] 型号已同步" if ok else "同步失败：%s" % out.strip()[:60]))

    def _glass_mode(self, m):
        if self.pad:
            self.pad.mode = m
        self.glass.set_mode(m)
        self.glass.set_status(text=("FVP 手感：设备仍是 Xbox360，只是只推摇杆 + Expo 曲线"
                                    if m == "fvp" else "Xbox360 手柄：摇杆 + 扳机 + 按键"))

    # ---------- adb ----------
    def _adb(self, *args, **kw):
        """跑一条 adb 命令，返回 (ok, 输出)。"""
        return _adb_run(list(args), kw.get("timeout", 12))

    def _chrome(self):
        r = self.root
        # 默认用系统标准窗口：能拖、能缩放、最小化/还原不乱、图标正常显示。
        # 想看自绘标题条：设 OPENRC2_CHROME=1
        if os.environ.get("OPENRC2_CHROME", "0") != "1":
            r.title("OpenRC2 虚拟手柄")
            r.resizable(True, True)
            r.minsize(900, 620)
            return
        r.overrideredirect(True)           # 去掉系统标题栏
        r.attributes("-topmost", False)
        try:
            r.attributes("-alpha", 1.0)
        except Exception:
            pass
        r.update_idletasks()
        try:
            self._acrylic()
            self._round_corners()
        except Exception:
            pass
        # 自绘标题条：拖动 + 最小化 + 关闭
        bar = tk.Frame(r, bg=INK, height=34)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)
        tk.Label(bar, text="  OpenRC2", bg=INK, fg=AMBER,
                 font=("Microsoft YaHei UI", 10, "bold")).pack(side="left")
        tk.Button(bar, text="✕", command=self._quit, bg=INK, fg=SUB, bd=0,
                  activebackground="#2a3340", font=("Microsoft YaHei UI", 11),
                  padx=10).pack(side="right")
        tk.Button(bar, text="—", command=self._min, bg=INK, fg=SUB, bd=0,
                  activebackground="#2a3340", font=("Microsoft YaHei UI", 11),
                  padx=10).pack(side="right")
        def bind_drag(w):
            if isinstance(w, tk.Button):
                return
            w.bind("<Button-1>", self._drag0)
            w.bind("<B1-Motion>", self._drag1)
            for ch in w.winfo_children():
                bind_drag(ch)
        bind_drag(bar)
        # 小化后 overrideredirect 已关，恢复时要再打开
        r.bind("<Map>", lambda e: (r.overrideredirect(True), r.update_idletasks()))

    def _drag0(self, e):
        self._d = (e.x_root - self.root.winfo_rootx(), e.y_root - self.root.winfo_rooty())

    def _drag1(self, e):
        if getattr(self, "_d", None):
            self.root.geometry("+%d+%d" % (e.x_root - self._d[0], e.y_root - self._d[1]))

    def _min(self):
        self.root.overrideredirect(False)
        self.root.iconify()

    def _quit(self):
        try:
            self.stop()
        except Exception:
            pass
        self.root.destroy()

    def _acrylic(self):
        """Win10 1803+/Win11 的亚克力模糊。默认关闭：半透明会让背景透进来，看着像"没显示出来"。"""
        if os.environ.get("OPENRC2_ACRYLIC", "0") != "1":
            return
        import ctypes
        from ctypes import wintypes
        hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id()) or self.root.winfo_id()

        class ACCENT(ctypes.Structure):
            _fields_ = [("AccentState", ctypes.c_int), ("AccentFlags", ctypes.c_int),
                        ("GradientColor", ctypes.c_uint), ("AnimationId", ctypes.c_int)]

        class WCADATA(ctypes.Structure):
            _fields_ = [("Attribute", ctypes.c_int), ("Data", ctypes.c_void_p),
                        ("SizeOfData", ctypes.c_size_t)]

        accent = ACCENT(4, 2, 0xF21A1F26, 0)   # 4 = ACRYLICBLURBEHIND，颜色 ABGR
        data = WCADATA(19, ctypes.cast(ctypes.pointer(accent), ctypes.c_void_p),
                       ctypes.sizeof(accent))
        ctypes.windll.user32.SetWindowCompositionAttribute(hwnd, ctypes.byref(data))

    def _round_corners(self):
        import ctypes
        hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id()) or self.root.winfo_id()
        try:
            val = ctypes.c_int(2)              # DWMWCP_ROUND
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 33, ctypes.byref(val), ctypes.sizeof(val))
        except Exception:
            pass

    def selftest(self):
        """不接遥控器也能验证：合成一段摇杆扫描，直接喂虚拟手柄。
        打开 joy.cpl → 属性，两个摇杆会各画一个圆。"""
        if not self.pad or not self.pad.ok:
            self.say("[自检] 虚拟手柄不可用：%s" % (self.pad.err if self.pad else "未初始化"))
            self.say("        先装 ViGEmBus 驱动")
            return
        self.say("[自检] 开始：左右摇杆各扫一圈 …")
        def run():
            import math
            for k in range(120):
                a = k / 120.0 * 2 * math.pi
                x = int(1024 + 600 * math.cos(a)); y = int(1024 + 600 * math.sin(a))
                self.pad.push([x, y, y, x, 1024, 1024])   # AIL, ELE, THR, RUD
                time.sleep(0.012)
            self.pad.push([1024] * 6)
            self._pending = "[自检] 完成：joy.cpl 里两个摇杆应各画了一个圆"
        threading.Thread(target=run, daemon=True).start()

    def on_model(self):
        v = self.model.get()
        self.say("遥控器型号 -> %s" % {"0": "自动识别", "1": "RC2", "2": "RC Pro"}[v])
        def work():
            try:
                self._adb("shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                          "--es", "action", "model", "--ei", "v", v)
            except Exception as e:
                self.say("同步型号失败：%s" % e)
        threading.Thread(target=work, daemon=True).start()

    def on_mode(self):
        if self.pad:
            self.pad.mode = self.mode.get()
        self.say("手柄类型 -> %s" % self.mode.get())

    def on_dz(self, v):
        if self.pad:
            self.pad.deadzone = float(v) / 100.0

    def on_ex(self, v):
        if self.pad:
            self.pad.expo = float(v) / 100.0

    def say(self, s):
        self.log.insert("end", s + "\n"); self.log.see("end")
        try:
            if int(self.log.index("end-1c").split(".")[0]) > 300:
                self.log.delete("1.0", "80.0")
        except Exception:
            pass

    def toggle(self):
        self.start() if not self.running else self.stop()

    def start(self):
        ok, out = _adb_run(["reverse", "tcp:%d" % PORT, "tcp:%d" % PORT])
        self.say("[adb] reverse " + ("成功" if ok else "失败：" + out.strip()[:70]))
        if not ok:
            self.say("      先 USB 插上开调试，或 adb connect <遥控器IP>:5555")
        ok2, out2 = _adb_run(["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                              "--es", "action", "pad", "--ei", "on", "1"], timeout=15)
        self.say("[adb] 遥控器端开流 " + ("成功" if ok2 else "失败：" + out2.strip()[:70]))
        if self.pad and not self.pad.ok:
            self.say("[vigem] 虚拟手柄不可用：%s" % self.pad.err)
            self.say("        正在尝试用内置驱动修复…")
            self.root.update()
            self.pad = Pad()
            if self.pad.ok:
                self.say("[vigem] 驱动已就绪（内置 ViGEmBus 已安装）")
            else:
                self.say("[vigem] 仍不可用：%s" % self.pad.err)
                self.say("        日志：%s" % _log_path())
        self.link = Link(self.on_frame, self.on_state)
        self.link.start()
        self.running = True
        if getattr(self, "glass", None): self.glass.set_running(True)
        self.btn.configure(text="停 止")
        self.dot.configure(text="●  运行中", fg=TEAL)
        self.say("遥控器端开启「虚拟手柄数据流」：设置→诊断，或 adb shell am start -n com.rc2.etx/.MainActivity --es action pad --ei on 1")

    def stop(self):
        if self.link:
            self.link.stop(); self.link = None
        _adb_run(["shell", "am", "start", "-n", "com.rc2.etx/.MainActivity",
                  "--es", "action", "pad", "--ei", "on", "0"], timeout=15)
        okr, outr = _adb_run(["reverse", "--remove", "tcp:%d" % PORT])
        self.say("[adb] 关闭 " + ("已撤销 reverse" if okr else outr.strip()[:60]))
        self.running = False
        self.btn.configure(text="启 动")
        self.dot.configure(text="●  未启动", fg=SUB)
        self.say("已停止")

    def on_frame(self, ax):
        # 数据线程只做一件事：立刻推给 ViGEm（延迟优先）；界面由 _tick 以 16fps 读 latest
        now = time.time()
        if self.pad and (now - getattr(self, "_last_push", 0.0)) >= 0.008:   # 限到 ~125Hz，别把 ViGEm 喂爆
            self._last_push = now
            self.pad.push(ax)
        if getattr(self, "glass", None):
            self.glass.set_frame(ax)

    def on_state(self, msg):
        self._pending = msg
        if getattr(self, "glass", None):
            self.glass.set_status(text=msg)

    def draw_bar(self, cv, v):
        cv.delete("all")
        w = cv.winfo_width() or 200
        t = max(0.0, min(1.0, (float(v) - 364) / 1320.0))
        cv.create_rectangle(0, 0, w, 12, fill=CARD2, outline="")
        col = AMBER if abs(v - 1024) < 60 else TEAL
        cv.create_rectangle(0, 0, max(2, w * t), 12, fill=col, outline="")

    def _tick(self):
        g = getattr(self, "glass", None)
        if g and self.link:
            dt = time.time() - self.fps_t0
            if dt >= 0.5:
                self.fps = (self.link.frames - self.fps_n0) / max(dt, 1e-6)
                self.fps_t0, self.fps_n0 = time.time(), self.link.frames
            g.set_status(connected=self.link.connected, fps=self.fps, frames=self.link.frames)
        if self.link:
            for i, cv in enumerate(self.bars):
                v = self.link.latest[i] if i < len(self.link.latest) else 1024
                self.draw_bar(cv, v); self.vals[i].configure(text=str(v))
            dt = time.time() - self.fps_t0
            if dt >= 1.0:
                self.fps = (self.link.frames - self.fps_n0) / dt
                self.fps_t0, self.fps_n0 = time.time(), self.link.frames
            self.stat.configure(text="%s fps   ·   %d 帧" % (int(self.fps), self.link.frames))
            self.dot.configure(text="●  已连接" if self.link.connected else "●  等待中",
                               fg=TEAL if self.link.connected else AMBER)
        if self._pending:
            self.log.insert("end", self._pending + "\n"); self.log.see("end")
            self._pending = None
        self.root.after(66, self._tick)


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
