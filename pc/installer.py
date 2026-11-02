# -*- coding: utf-8 -*-
"""OpenRC2 虚拟手柄 安装程序（把 exe 装到 本用户目录 并建桌面快捷方式）"""
import os, shutil, subprocess, sys, tkinter as tk

APP = "OpenRC2-Pad.exe"
DST = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "OpenRC2")
HERE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
SRC = os.path.join(getattr(sys, "_MEIPASS", HERE), "payload", APP)


def shortcut(target, name):
    desk = os.path.join(os.path.expanduser("~"), "Desktop", name + ".lnk")
    ps = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%s');"
          "$s.TargetPath='%s';$s.WorkingDirectory='%s';$s.Save()" % (desk, target, DST))
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, timeout=20)
        return True
    except Exception:
        return False


def install(win, bar, msg):
    msg.configure(text="正在释放文件 …")
    os.makedirs(DST, exist_ok=True)
    shutil.copy2(SRC, os.path.join(DST, APP))
    bar["value"] = 55; win.update()
    bg_src = os.path.join(getattr(sys, "_MEIPASS", HERE), "payload", "backgrounds")
    if os.path.isdir(bg_src):
        bdst = os.path.join(DST, "backgrounds")
        os.makedirs(bdst, exist_ok=True)
        for fn in os.listdir(bg_src):
            shutil.copy2(os.path.join(bg_src, fn), os.path.join(bdst, fn))
    bar["value"] = 80; win.update()
    msg.configure(text="正在创建桌面快捷方式 …")
    shortcut(os.path.join(DST, APP), "OpenRC2 虚拟手柄")
    bar["value"] = 100
    msg.configure(text="安装完成，正在启动 …")
    win.update()
    subprocess.Popen([os.path.join(DST, APP)])
    win.after(600, win.destroy)


def main():
    win = tk.Tk(); win.title("OpenRC2 虚拟手柄 · 安装")
    win.geometry("520x300"); win.configure(bg="#0b0f14"); win.resizable(False, False)
    tk.Label(win, text="OpenRC2 虚拟手柄", bg="#0b0f14", fg="#e6edf3",
             font=("Microsoft YaHei UI", 18, "bold")).pack(pady=(26, 4))
    tk.Label(win, text="安装到本用户目录，并创建桌面快捷方式", bg="#0b0f14", fg="#8b949e",
             font=("Microsoft YaHei UI", 10)).pack()
    msg = tk.Label(win, text="点击安装开始", bg="#0b0f14", fg="#f5b942",
                   font=("Microsoft YaHei UI", 10))
    msg.pack(pady=18)
    bar = tk.DoubleVar(value=0)
    c = tk.Canvas(win, width=420, height=10, bg="#1d2530", highlightthickness=0)
    r = c.create_rectangle(0, 0, 0, 10, fill="#f5b942", outline="")
    c.pack()
    def sync(*_):
        c.coords(r, 0, 0, 420 * bar.get() / 100.0, 10)
    bar.trace_add("write", sync)
    tk.Button(win, text="安  装", command=lambda: install(win, bar, msg), bg="#f5b942",
              fg="#1a1206", bd=0, font=("Microsoft YaHei UI", 12, "bold"),
              padx=40, pady=8).pack(pady=26)
    tk.Label(win, text="作者：软糖布丁 · GPLv2", bg="#0b0f14", fg="#4b5563",
             font=("Microsoft YaHei UI", 9)).pack(side="bottom", pady=8)
    win.mainloop()


if __name__ == "__main__":
    main()
