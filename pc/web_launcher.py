# -*- coding: utf-8 -*-
"""OpenRC2 Web 版启动器：拉起 padcore.exe（无窗口），稍等再开 Edge 应用窗口。"""
import os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, "frozen", False) else __file__))
EDGE = [
    os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
    os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Microsoft", "Edge", "Application", "msedge.exe"),
    os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Google", "Chrome", "Application", "chrome.exe"),
    os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"), "Google", "Chrome", "Application", "chrome.exe"),
]
URL = "http://127.0.0.1:9998/"


def core_running(port=9998):
    import socket
    s = socket.socket(); s.settimeout(0.25)
    try:
        s.connect(("127.0.0.1", port)); return True
    except Exception:
        return False
    finally:
        try: s.close()
        except Exception: pass


def find_core():
    for name in ("padcore.exe", "padcore.pyw", "padcore.py"):
        p = os.path.join(HERE, name)
        if os.path.exists(p): return p
    return None


def main():
    core = find_core()
    if core is None:
        _msg("找不到 padcore，安装可能不完整。"); return 1
    if not core_running():
        flags = (getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NO_WINDOW", 0)) if os.name == "nt" else 0
        cmd = [core] if core.lower().endswith(".exe") else [sys.executable, core]
        subprocess.Popen(cmd, cwd=HERE, creationflags=flags,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(40):
            if core_running(): break
            time.sleep(0.15)
    edge = next((p for p in EDGE if os.path.exists(p)), None)
    if edge is None:
        import webbrowser; webbrowser.open(URL); return 0
    subprocess.Popen([edge, "--app=" + URL, "--window-size=1200,800"], cwd=HERE,
                     creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))
    return 0


def _msg(text):
    try:
        import ctypes; ctypes.windll.user32.MessageBoxW(None, text, "OpenRC2", 0x40)
    except Exception:
        print(text)


if __name__ == "__main__":
    sys.exit(main())
