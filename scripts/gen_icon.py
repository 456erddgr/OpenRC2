# -*- coding: utf-8 -*-
"""生成 OpenRC2 应用图标（暗底 + 琥珀色穿越机 + 青色信号弧）。"""
import os
from PIL import Image, ImageDraw

S = 512          # 设计尺寸
SS = 4           # 超采样倍数
BG1 = (22, 28, 38)      # #161C26
BG2 = (11, 14, 19)      # #0B0E13
AMBER = (245, 185, 66)  # #F5B942
AMBER_D = (198, 143, 40)
TEAL = (47, 211, 176)   # #2FD3B0

W = S * SS
img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
dr = ImageDraw.Draw(img)

# 圆角底板 + 竖向渐变
pad = 0
r = int(W * 0.22)
mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(mask).rounded_rectangle([pad, pad, W - 1 - pad, W - 1 - pad], radius=r, fill=255)
grad = Image.new("RGBA", (W, W))
gd = ImageDraw.Draw(grad)
for y in range(W):
    t = y / float(W - 1)
    c = tuple(int(BG1[i] + (BG2[i] - BG1[i]) * t) for i in range(3)) + (255,)
    gd.line([(0, y), (W, y)], fill=c)
img.paste(grad, (0, 0), mask)

def P(x, y):
    return (x * W, y * W)

def line(a, b, w, fill):
    dr.line([P(*a), P(*b)], fill=fill, width=int(w * W), joint="curve")

def ring(c, rad, w, fill):
    cx, cy = c
    dr.ellipse([P(cx - rad, cy - rad)[0], P(cx - rad, cy - rad)[1],
                P(cx + rad, cy + rad)[0], P(cx + rad, cy + rad)[1]],
               outline=fill, width=max(1, int(w * W)))

# 机架：X 形四臂
cx, cy = 0.5, 0.545
arm = 0.245
for sx in (-1, 1):
    for sy in (-1, 1):
        line((cx, cy), (cx + sx * arm * 0.78, cy + sy * arm * 0.78), 0.036, AMBER_D)

# 机身
bw, bh = 0.130, 0.100
dr.rounded_rectangle([P(cx - bw, cy - bh)[0], P(cx - bw, cy - bh)[1],
                      P(cx + bw, cy + bh)[0], P(cx + bw, cy + bh)[1]],
                     radius=int(W * 0.03), fill=AMBER)
# 机身中央镜头
dr.ellipse([P(cx - 0.035, cy - 0.035)[0], P(cx - 0.035, cy - 0.035)[1],
            P(cx + 0.035, cy + 0.035)[0], P(cx + 0.035, cy + 0.035)[1]],
           fill=(11, 14, 19, 255))

# 四个桨叶环
for sx in (-1, 1):
    for sy in (-1, 1):
        px, py = cx + sx * arm * 0.95, cy + sy * arm * 0.95
        ring((px, py), 0.098, 0.030, AMBER)
        # 桨叶盘（半透明）
        dr.ellipse([P(px - 0.062, py - 0.062)[0], P(px - 0.062, py - 0.062)[1],
                    P(px + 0.062, py + 0.062)[0], P(px + 0.062, py + 0.062)[1]],
                   outline=(245, 185, 66, 110), width=max(1, int(0.010 * W)))

# 左上信号弧（青色，表示遥控发射）
for i, rad in enumerate((0.085, 0.135, 0.185)):
    bbox = [P(0.155 - rad, 0.175 - rad)[0], P(0.155 - rad, 0.175 - rad)[1],
            P(0.155 + rad, 0.175 + rad)[0], P(0.155 + rad, 0.175 + rad)[1]]
    dr.arc(bbox, start=180, end=270, fill=TEAL, width=max(1, int(0.022 * W)))
dr.ellipse([P(0.118, 0.138)[0], P(0.118, 0.138)[1],
            P(0.192, 0.212)[0], P(0.192, 0.212)[1]], fill=TEAL)

master = img.resize((S, S), Image.LANCZOS)

out = os.path.join(os.path.dirname(__file__), "..", "app", "res")
sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for d, px in sizes.items():
    p = os.path.join(out, d)
    os.makedirs(p, exist_ok=True)
    master.resize((px, px), Image.LANCZOS).save(os.path.join(p, "ic_launcher.png"))
print("icon written:", ", ".join("%s=%d" % (k, v) for k, v in sizes.items()))
