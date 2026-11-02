# -*- coding: utf-8 -*-
"""生成 OpenRC2 开机动画 bootanimation.zip（1920x1080@24fps 缩放友好，帧 960x540）。"""
import math, os, zipfile
from PIL import Image, ImageDraw, ImageFont

W, H = 960, 540
FPS = 24
FRAMES = 60
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bootanim", "openrc2")
ZIP = os.path.join(os.path.dirname(OUT), "openrc2-bootanimation.zip")
BG = (14, 19, 25)
AMBER = (245, 185, 66)
TEAL = (45, 212, 191)
GREY = (140, 152, 170)


def font(size, bold=False, cjk=False):
    wins = "C:/Windows/Fonts/"
    cands = []
    if cjk:
        cands += [wins + "msyhbd.ttc", wins + "msyh.ttc"]
    if bold:
        cands += [wins + "seguisb.ttf", wins + "arialbd.ttf"]
    else:
        cands += [wins + "segoeui.ttf", wins + "arial.ttf"]
    for path in cands:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default()


def main():
    os.makedirs(os.path.join(OUT, "part0"), exist_ok=True)
    f_title = font(74, True)
    f_sub = font(24, cjk=True)
    for i in range(FRAMES):
        t = i / float(FRAMES)
        im = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(im)

        # 背景柔光
        for r in range(4):
            a = 30 - r * 7
            d.ellipse([W / 2 - 190 - r * 26, H / 2 - 190 - r * 26,
                       W / 2 + 190 + r * 26, H / 2 + 190 + r * 26],
                      outline=(BG[0] + a, BG[1] + a, BG[2] + a), width=2)

        # 旋转弧
        ang0 = t * 360.0
        box = [W / 2 - 172, H / 2 - 172, W / 2 + 172, H / 2 + 172]
        d.arc(box, start=ang0, end=ang0 + 110, fill=AMBER, width=8)
        d.arc(box, start=ang0 + 180, end=ang0 + 250, fill=TEAL, width=4)

        # 标题淡入
        alpha = min(1.0, t * 4.0)
        col = tuple(int(c * (0.45 + 0.55 * alpha)) for c in (235, 238, 242))
        d.text((W / 2, H / 2 - 6), "OpenRC2", font=f_title, fill=col, anchor="mm")

        acol = tuple(int(c * (0.3 + 0.7 * alpha)) for c in AMBER)
        d.text((W / 2, H / 2 + 62), "开源遥控 · EdgeTX 2.12.2", font=f_sub, fill=acol, anchor="mm")

        # 进度条
        bw, bh = 320, 6
        x0, y0 = (W - bw) / 2, H - 96
        d.rounded_rectangle([x0, y0, x0 + bw, y0 + bh], bh / 2, fill=(27, 36, 48))
        w = bw * min(1.0, t * 1.25)
        d.rounded_rectangle([x0, y0, x0 + w, y0 + bh], bh / 2, fill=AMBER)

        im.save(os.path.join(OUT, "part0", "%04d.png" % (i + 1)), optimize=True)

    desc = "%d %d %d\nc 1 0 part0\n" % (W, H, FPS)
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", desc)
        for fn in sorted(os.listdir(os.path.join(OUT, "part0"))):
            z.write(os.path.join(OUT, "part0", fn), "part0/" + fn)
    print("built", ZIP, os.path.getsize(ZIP), "bytes")


if __name__ == "__main__":
    main()
