# -*- coding: utf-8 -*-
"""OpenRC2 遥控器端一键安装：装 APK + 铺 SD 卡(V15) + 修属主 + 启动。"""
import os, subprocess, sys, threading, time, io

APP = "com.rc2.etx/.MainActivity"
SD = "/sdcard/Android/data/com.rc2.etx/files/sd"
TMP = "/data/local/tmp/openrc2_sd.zip"
BS = chr(92)


def _res(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def adb_exe():
    for c in (_res("adb.exe"), os.path.join(os.path.dirname(os.path.abspath(__file__)), "adb.exe")):
        if os.path.exists(c):
            return c
    return "adb"


def run(args, timeout=600):
    p = subprocess.run([adb_exe()] + args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return (p.returncode == 0), (p.stdout or "") + (p.stderr or "")


def devices():
    ok, out = run(["devices"], 20)
    if not ok:
        return []
    res = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            res.append(parts[0])
    return res


def install(log, dev):
    apk = _res("RC2ETX.apk")
    log("安装 APK：%s" % os.path.basename(apk))
    ok, out = run(["-s", dev, "install", "-r", "-d", apk], 900)
    log(("  成功" if ok and "Success" in out else "  失败：" + out.strip().splitlines()[-1][:160]))
    return ok and "Success" in out


def push_sd(log, dev):
    z = _res("sd_v15.zip")
    log("推送 SD 包：%.1f MB" % (os.path.getsize(z) / 1048576.0))
    ok, out = run(["-s", dev, "push", z, TMP], 1800)
    log("  推送" + ("成功" if ok else "失败：" + out[:160]))
    if not ok:
        return False
    log("设备上解压到 %s" % SD)
    cmd = "mkdir -p %s; cd %s; unzip -o %s >/dev/null 2>&1; echo RC=$?" % (SD, SD, TMP)
    ok, out = run(["-s", dev, "shell", cmd], 1800)
    log("  解压" + ("完成" if "RC=0" in out else "返回：" + out.strip()[:160]))
    log("修复属主 10047:1078")
    run(["-s", dev, "shell", "chown -R 10047:1078 %s" % SD], 600)
    run(["-s", dev, "shell", "rm -f %s" % TMP], 60)
    return "RC=0" in out


def launch(log, dev):
    run(["-s", dev, "shell", "am", "force-stop", "com.rc2.etx"], 60)
    ok, out = run(["-s", dev, "shell", "am", "start", "-n", APP], 120)
    log("启动 App" + ("成功" if ok else "失败：" + out[:120]))


def do_all(log, ip=""):
    log("查找 adb 设备…")
    devs = devices()
    if not devs and ip:
        log("尝试无线连接 %s" % ip)
        run(["connect", ip], 30)
        time.sleep(1.5)
        devs = devices()
    if not devs:
        log("没找到设备：请用 USB 连接遥控器，或先在遥控器上开无线调试")
        return False
    dev = devs[0]
    log("设备：%s" % dev)
    ok1 = install(log, dev)
    ok2 = push_sd(log, dev)
    launch(log, dev)
    log("—— 完成 ——" if (ok1 and ok2) else "—— 结束（有步骤失败，见上）——")
    return ok1 and ok2


def main_gui():
    import tkinter as tk
    from tkinter import ttk
    root = tk.Tk()
    root.title("OpenRC2 遥控器一键安装")
    root.geometry("560x400")
    root.configure(bg="#12171F")
    ttk.Label(root, text="OpenRC2 遥控器端一键安装",
              foreground="#F5B942", background="#12171F",
              font=("Microsoft YaHei UI", 14, "bold")).pack(anchor="w", padx=16, pady=(14, 4))
    ttk.Label(root, text="装 APK + 铺 V15 SD 卡 + 修属主 + 启动（USB 连接遥控器后点开始）",
              foreground="#9AA7B4", background="#12171F").pack(anchor="w", padx=16)
    box = tk.Text(root, height=15, bg="#0B0F14", fg="#C8D3DF", insertbackground="#C8D3DF",
                  relief="flat", font=("Consolas", 9))
    box.pack(fill="both", expand=True, padx=16, pady=10)
    bar = ttk.Progressbar(root, mode="indeterminate")
    bar.pack(fill="x", padx=16)

    def log(s):
        box.insert("end", s + "\n")
        box.see("end")
        root.update_idletasks()

    def start():
        bar.start(12)
        def work():
            try:
                do_all(log)
            except Exception as e:
                log("异常：%r" % (e,))
            finally:
                bar.stop()
        threading.Thread(target=work, daemon=True).start()

    btn = tk.Button(root, text="开始一键安装", command=start, bg="#F5B942", fg="#231703",
                    relief="flat", font=("Microsoft YaHei UI", 11, "bold"), height=2)
    btn.pack(fill="x", padx=16, pady=(0, 14))
    root.mainloop()


if __name__ == "__main__":
    if "--auto" in sys.argv:
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
        ip = ""
        for i, a in enumerate(sys.argv):
            if a == "--ip" and i + 1 < len(sys.argv):
                ip = sys.argv[i + 1]
        ok = do_all(lambda s: print(s, flush=True), ip)
        sys.exit(0 if ok else 1)
    main_gui()
