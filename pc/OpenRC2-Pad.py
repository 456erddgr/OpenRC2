# -*- coding: utf-8 -*-
"""OpenRC2 虚拟手柄（PC 端 GUI）  作者：软糖布丁
同 RM510 思路：遥控器把摇杆数据经 adb reverse 发到 PC，PC 用 ViGEmBus 造手柄。
"""
import json, os, socket, subprocess, threading, time, tkinter as tk
from tkinter import filedialog
try:
    from PIL import Image, ImageTk, ImageFilter, ImageEnhance
    HAS_PIL = True
except Exception:
    HAS_PIL = False

PORT = 9999
CENTER, HALF = 1024.0, 660.0
BG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backgrounds")
INK, CARD, CARD2 = "#0b0f14", "#151b23", "#1d2530"
TXT, SUB = "#e6edf3", "#8b949e"
AMBER, TEAL = "#f5b942", "#2dd4bf"


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


class Pad(object):
    """mode: xbox=完整 Xbox360；fvp=穿越机手感（只摇杆、expo）"""
    def __init__(self):
        self.pad = None; self.mode = "xbox"
        self.deadzone = 0.06; self.expo = 1.0
        self.ok = False; self.err = ""
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
        ok, out = True, ""
        try:
            r = subprocess.run(["adb", "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT],
                               capture_output=True, text=True, timeout=10)
            ok = r.returncode == 0
            out = (r.stdout or "") + (r.stderr or "")
        except Exception as e:
            ok, out = False, str(e)
        self.say("[adb] reverse " + ("成功" if ok else "失败：" + out.strip()[:70]))
        if not ok:
            self.say("      先 adb connect <遥控器IP>:5555，或插 USB 开调试")
        if self.pad and not self.pad.ok:
            self.say("[vigem] 虚拟手柄不可用：%s" % self.pad.err)
            self.say("        装 ViGEmBus 驱动后可获得虚拟手柄")
        self.link = Link(self.on_frame, self.on_state)
        self.link.start()
        self.running = True
        self.btn.configure(text="停 止")
        self.dot.configure(text="●  运行中", fg=TEAL)
        self.say("遥控器端开启「虚拟手柄数据流」：设置→诊断，或 adb shell am start -n com.rc2.etx/.MainActivity --es action pad --ei on 1")

    def stop(self):
        if self.link:
            self.link.stop(); self.link = None
        self.running = False
        self.btn.configure(text="启 动")
        self.dot.configure(text="●  未启动", fg=SUB)
        self.say("已停止")

    def on_frame(self, ax):
        if self.pad:
            self.pad.push(ax)

    def on_state(self, msg):
        self._pending = msg

    def draw_bar(self, cv, v):
        cv.delete("all")
        w = cv.winfo_width() or 200
        t = max(0.0, min(1.0, (float(v) - 364) / 1320.0))
        cv.create_rectangle(0, 0, w, 12, fill=CARD2, outline="")
        col = AMBER if abs(v - 1024) < 60 else TEAL
        cv.create_rectangle(0, 0, max(2, w * t), 12, fill=col, outline="")

    def _tick(self):
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
