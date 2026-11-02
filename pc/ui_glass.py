# -*- coding: utf-8 -*-
"""OpenRC2 PC 端 · 液态玻璃 UI（苹果风格）。
静态背景（模糊壁纸 + 圆角玻璃卡片 + 文字）用 PIL 一次性合成成位图；
动态部分（数值条、指针、按钮高亮、动画）画在 Canvas 上，30fps，按需重绘。
"""
import os
import sys
import math
import tkinter as tk

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageTk, ImageFont
    HAS_PIL = True
except Exception:
    HAS_PIL = False

# ---------- 调色板（苹果深色模式近似） ----------
BG0 = "#0a0c10"
INK = "#0b0e13"
CARD = (22, 27, 34)
CARD_HI = (32, 39, 48)
STROKE = (255, 255, 255, 26)
TXT = "#f2f5f8"
SUB = "#98a2ad"
AMBER = (245, 185, 66)
TEAL = (45, 212, 191)
BLUE = (96, 165, 250)
PINK = (244, 114, 182)


def _font(size, bold=False):
    fams = ("SF Pro Display", "Segoe UI Variable Display", "Segoe UI", "Microsoft YaHei UI")
    return (fams[0] if bold else fams[-2], size, "bold" if bold else "normal")


_FONTS = {}


def _pfont(size, bold=False):
    """PIL 用的字体对象（缓存）。优先雅黑，其次 Segoe UI。"""
    key = (size, bold)
    if key in _FONTS:
        return _FONTS[key]
    cands = [r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
             r"C:\Windows\Fonts\seguiemj.ttf",
             r"C:\Windows\Fonts\segoeui.ttf",
             r"C:\Windows\Fontsrial.ttf"]
    f = None
    for c in cands:
        try:
            if os.path.exists(c):
                f = ImageFont.truetype(c, size); break
        except Exception:
            f = None
    if f is None:
        f = ImageFont.load_default()
    _FONTS[key] = f
    return f


def _rr(draw, box, r, fill=None, outline=None, width=1):
    """圆角矩形（PIL 没有原生，用 pieslice 拼）。"""
    x0, y0, x1, y1 = box
    r = max(0, min(r, (x1 - x0) // 2, (y1 - y0) // 2))
    draw.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=fill, outline=outline, width=width)


def _glass_panel(w, h, radius=26, tint=CARD, alpha=205, blur=26):
    """做一块“液态玻璃”：横向渐变 + 顶部高光 + 细描边。返回 RGBA 图。"""
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    _rr(d, (0, 0, w - 1, h - 1), radius, fill=(tint[0], tint[1], tint[2], alpha))
    # 顶部高光（1px 半透明白）
    _rr(d, (0, 0, w - 1, h - 1), radius, outline=(255, 255, 255, 30), width=1)
    # 柔和内阴影：底部压暗
    grad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(h):
        a = int(28 * (y / max(1, h - 1)) ** 2)
        if a:
            gd.line([(0, y), (w, y)], fill=(0, 0, 0, a))
    im = Image.alpha_composite(im, grad)
    return im


def _wallpaper(size, path=None):
    """壁纸：优先用户图，否则程序内 backgrounds；再模糊 + 压暗，做成玻璃底。"""
    w, h = size
    img = None
    cands = [path]
    try:
        bdir = _res("backgrounds")
        if os.path.isdir(bdir):
            cands += [os.path.join(bdir, f) for f in sorted(os.listdir(bdir))
                      if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
    except Exception:
        pass
    for p in filter(None, cands):
        try:
            if p and os.path.exists(p):
                img = Image.open(p).convert("RGB"); break
        except Exception:
            img = None
    if img is None:
        img = Image.new("RGB", (w, h), (10, 12, 16))
    sw, sh = img.size
    k = max(w / sw, h / sh)
    img = img.resize((max(w, int(sw * k)), max(h, int(sh * k))), Image.LANCZOS)
    x = (img.size[0] - w) // 2; y = (img.size[1] - h) // 2
    img = img.crop((x, y, x + w, y + h))
    img = img.filter(ImageFilter.GaussianBlur(10))
    dark = Image.new("RGB", (w, h), (6, 9, 14))
    return Image.blend(img, dark, 0.42)


def _res(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


class GlassUI(object):
    """苹果风格面板：玻璃卡片 + 圆角 + 30fps 插值动画。"""

    PAD = 18
    RAD = 22

    def __init__(self, root, cb):
        self.root = root
        self.cb = cb                      # dict: start/stop/selftest/model/mode/bg/joycpl
        self.w = 1180; self.h = 720
        self.bg_img = None
        self.bg_path = None
        self.anim = [512.0] * 6          # 0..1023 平滑值
        self.target = [512.0] * 6
        self.press = {}                  # name -> t (0..1) 按压动画
        self.hover = None
        self.fps = 0.0; self.frames = 0; self.connected = False
        self.mode = "xbox"; self.model = "0"; self.running = False
        self.buttons = {}                 # name -> (x0,y0,x1,y1)
        self.stat_text = "等待遥控器连接"
        self.bar_geom = []
        self._last_bg_key = None

        root.geometry("%dx%d" % (self.w, self.h))
        root.minsize(980, 640)
        root.configure(bg=BG0)
        self.cv = tk.Canvas(root, bg=BG0, highlightthickness=0, bd=0)
        self.cv.pack(fill="both", expand=True)
        self.cv.bind("<Configure>", self._on_resize)
        self.cv.bind("<Motion>", self._on_move)
        self.cv.bind("<Button-1>", self._on_down)
        self.cv.bind("<ButtonRelease-1>", self._on_up)
        self._layout(self.w, self.h)      # 先摆好按钮，第一帧就有几何
        self._tick()

    # ---------- 布局 ----------
    def _layout(self, w, h):
        p = self.PAD
        top = 78
        left_w = int(min(360, max(300, w * 0.30)))
        self.buttons = {}
        # 左侧卡片按钮（垂直排列）
        bx0, bx1 = p + 14, p + left_w - 14
        y = h - p - 150
        for name in ("start", "selftest", "bg", "joycpl"):
            self.buttons[name] = (bx0, y, bx1, y + 38)
            y += 46
        # 右侧顶部：三种模式/型号的小胶囊
        cx = p + left_w + 14
        self.buttons["mode_xbox"] = (cx + 16, top + 46, cx + 132, top + 78)
        self.buttons["mode_fvp"] = (cx + 140, top + 46, cx + 256, top + 78)
        mx = cx + 16
        self.buttons["model_auto"] = (mx, top + 118, mx + 92, top + 148)
        self.buttons["model_rc2"] = (mx + 100, top + 118, mx + 172, top + 148)
        self.buttons["model_rcpro"] = (mx + 180, top + 118, mx + 272, top + 148)
        return left_w

    # ---------- 静态背景合成 ----------
    def _compose(self, w, h):
        if not HAS_PIL:
            return None
        base = _wallpaper((w, h), self.bg_path).convert("RGBA")
        p = self.PAD
        left_w = int(min(360, max(300, w * 0.30)))
        # 顶部标题条
        head = _glass_panel(w - p * 2, 60, 20, (18, 22, 28), 170)
        base.alpha_composite(head, (p, 12))
        # 左卡片
        lh = h - 96 - p
        base.alpha_composite(_glass_panel(left_w, lh, 26, CARD, 176), (p, 84))
        # 右卡片
        rx = p + left_w + 12
        rw = w - rx - p
        base.alpha_composite(_glass_panel(rw, lh, 26, CARD, 176), (rx, 84))
        draw = ImageDraw.Draw(base)
        # 文案
        draw.text((p + 24, 26), "OpenRC2", font=_pfont(26, True), fill=TXT)
        draw.text((p + 150, 34), "虚拟手柄 · PC 端", font=_pfont(13), fill=SUB)
        draw.text((p + 26, 104), "手柄类型 / 型号", font=_pfont(15, True), fill=TXT)
        draw.text((rx + 26, 104), "摇杆实时数据", font=_pfont(15, True), fill=TXT)
        draw.text((p + 26, h - p - 196), "死区 / 曲线", font=_pfont(15, True), fill=TXT)
        # 数值条的底槽（动态条画在上面）
        self.bar_geom = []
        for i in range(6):
            y = 190 + i * 52
            x0 = rx + 150; x1 = rx + rw - 96
            draw.rounded_rectangle([x0, y, x1, y + 14], radius=7, fill=(255, 255, 255, 18))
            self.bar_geom.append((x0, y, x1, y + 14))
        for i, name in enumerate(("左水平 · 偏航", "左竖直 · 油门", "右水平 · 副翼", "右竖直 · 俯仰", "左波轮", "右波轮")):
            draw.text((rx + 26, 186 + i * 52 - 2), name, font=_pfont(13), fill=SUB)
        return base

    # ---------- 绘制 ----------
    def _on_resize(self, e):
        if (e.width, e.height) == (self.w, self.h) and self.bg_img is not None:
            return
        self.w, self.h = e.width, e.height
        self._rebuild_bg()

    def _rebuild_bg(self):
        self._layout(self.w, self.h)
        im = self._compose(self.w, self.h)
        if im is None:
            return
        self.bg_img = ImageTk.PhotoImage(im)
        self.cv.delete("static")
        self.cv.create_image(0, 0, image=self.bg_img, anchor="nw", tags="static")
        self.cv.tag_lower("static")

    def _rr_canvas(self, x0, y0, x1, y1, r, fill, outline="", width=1, tags=""):
        pts = [x0 + r, y0, x1 - r, y0, x1, y0, x1, y0 + r, x1, y1 - r,
               x1, y1, x1 - r, y1, x0 + r, y1, x0, y1, x0, y1 - r,
               x0, y0 + r, x0, y0]
        return self.cv.create_polygon(pts, smooth=True, splinesteps=18,
                                      fill=fill, outline=outline, width=width, tags=tags)

    def draw(self):
        if not self.buttons:
            return
        cv = self.cv
        cv.delete("dyn")
        p = self.PAD
        left_w = int(min(360, max(300, self.w * 0.30)))
        rx = p + left_w + 12
        rw = self.w - rx - p
        # 数值条 + 数字
        for i, (x0, y0, x1, y1) in enumerate(getattr(self, "bar_geom", [])):
            v = self.anim[i]
            t = max(0.0, min(1.0, (v - 364.0) / 1320.0))
            col = AMBER if abs(v - 1024) < 55 else TEAL
            if t > 0.005:
                self._rr_canvas(x0, y0, x0 + max(6, (x1 - x0) * t), y1, 7,
                                "#%02x%02x%02x" % col, tags="dyn")
            cv.create_text(x1 + 12, y0 + 7, text=str(int(v)), anchor="w",
                           fill=TXT, font=_font(13), tags="dyn")
        # 状态胶囊
        dotc = "#2dd4bf" if self.connected else "#f5b942"
        self._rr_canvas(self.w - p - 190, 24, self.w - p - 16, 58, 17, "", tags="dyn")
        cv.create_oval(self.w - p - 178, 36, self.w - p - 166, 48, fill=dotc, outline="", tags="dyn")
        cv.create_text(self.w - p - 158, 42, text=("已连接" if self.connected else "等待中") +
                       "  ·  %d fps" % int(self.fps), anchor="w", fill=TXT, font=_font(12), tags="dyn")
        # 胶囊按钮（模式 / 型号）
        def pill(name, label, on):
            x0, y0, x1, y1 = self.buttons[name]
            k = self.press.get(name, 0.0)
            fill = "#2dd4bf" if on else "#232b36"
            if k > 0:
                fill = "#5eead4" if on else "#2f3a47"
            self._rr_canvas(x0, y0 - int(1 * k), x1, y1 - int(1 * k), (y1 - y0) // 2, fill, tags="dyn")
            cv.create_text((x0 + x1) // 2, (y0 + y1) // 2 - int(1 * k), text=label,
                           fill="#08210f" if on else TXT, font=_font(12, on), tags="dyn")
        pill("mode_xbox", "Xbox 360", self.mode == "xbox")
        pill("mode_fvp", "FVP 手感", self.mode == "fvp")
        pill("model_auto", "自动", self.model == "0")
        pill("model_rc2", "RC2", self.model == "1")
        pill("model_rcpro", "RC Pro", self.model == "2")
        # 左侧底部按钮
        labels = {"start": ("停止" if self.running else "启动"),
                  "selftest": "自检摇杆", "bg": "换壁纸", "joycpl": "joy.cpl"}
        for name, lab in labels.items():
            x0, y0, x1, y1 = self.buttons[name]
            k = self.press.get(name, 0.0)
            fill = "#f5b942" if name == "start" else "#232b36"
            if k > 0:
                fill = "#ffd479" if name == "start" else "#2f3a47"
            self._rr_canvas(x0, y0, x1, y1 + int(1 * k), 14, fill, tags="dyn")
            cv.create_text((x0 + x1) // 2, (y0 + y1) // 2 + int(1 * k), text=lab,
                           fill="#241a04" if name == "start" else TXT,
                           font=_font(13, True), tags="dyn")
        cv.create_text(p + 26, self.h - p - 168, anchor="nw",
                       text="死区 6%    ·    曲线 Expo 1.00",
                       fill=SUB, font=_font(12), tags="dyn")
        # 底部日志行
        cv.create_text(p + 26, self.h - 34, anchor="w", text=self.stat_text[:96],
                       fill=SUB, font=_font(12), tags="dyn")

    # ---------- 交互 ----------
    def _hit(self, x, y):
        for name, (x0, y0, x1, y1) in self.buttons.items():
            if x0 <= x <= x1 and y0 - 2 <= y <= y1 + 2:
                return name
        return None

    def _on_move(self, e):
        self.hover = self._hit(e.x, e.y)
        self.cv.configure(cursor="hand2" if self.hover else "")

    def _on_down(self, e):
        n = self._hit(e.x, e.y)
        if n:
            self.press[n] = 1.0

    def _on_up(self, e):
        n = self._hit(e.x, e.y)
        for k in list(self.press.keys()):
            self.press[k] = 0.0
        if not n:
            return
        if n == "start":
            self._fire("stop" if self.running else "start")
        elif n in ("mode_xbox", "mode_fvp"):
            self.mode = "xbox" if n == "mode_xbox" else "fvp"
            self._fire("mode", self.mode)
        elif n.startswith("model_"):
            self.model = {"model_auto": "0", "model_rc2": "1", "model_rcpro": "2"}[n]
            self._fire("model", self.model)
        elif n == "selftest":
            self._fire("selftest")
        elif n == "bg":
            self._fire("bg")
        elif n == "joycpl":
            self._fire("joycpl")

    def _fire(self, name, *a):
        fn = self.cb.get(name)
        if fn:
            try:
                fn(*a)
            except Exception as ex:
                self.stat_text = "动作失败：%s" % ex

    # ---------- 数据 & 动画 ----------
    def set_frame(self, ax):
        for i in range(6):
            if i < len(ax):
                self.target[i] = float(ax[i])

    def set_status(self, connected=None, fps=None, frames=None, text=None):
        if connected is not None:
            self.connected = connected
        if fps is not None:
            self.fps = fps
        if frames is not None:
            self.frames = frames
        if text is not None:
            self.stat_text = text

    def set_running(self, on):
        self.running = bool(on)

    def set_mode(self, m):
        self.mode = m

    def set_model(self, v):
        self.model = str(v)

    def reload_wallpaper(self, path=None):
        self.bg_path = path
        self._rebuild_bg()

    def _tick(self):
        # 30fps：数值插值 + 按压回弹
        changed = False
        for i in range(6):
            d = self.target[i] - self.anim[i]
            if abs(d) > 0.35:
                self.anim[i] += d * 0.45
                changed = True
            else:
                self.anim[i] = self.target[i]
        for k in list(self.press.keys()):
            if self.press[k] > 0:
                self.press[k] = max(0.0, self.press[k] - 0.22)
                changed = True
        if changed or getattr(self, "_dirty", True):
            self.draw()
            self._dirty = False
        self.root.after(33, self._tick)
