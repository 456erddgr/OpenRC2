# -*- coding: utf-8 -*-
r"""OpenRC2 网页版 一键安装：解包到 LOCALAPPDATA，建桌面/开始菜单快捷方式，装 ViGEmBus，写卸载项。"""
import ctypes, os, shutil, subprocess, sys

APP_NAME = "OpenRC2 虚拟手柄"
EXE_NAME = "OpenRC2-Web.exe"
DEST = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "OpenRC2-Web")
BS = chr(92)


def res(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def ask(text, title="OpenRC2 安装"):
    if os.environ.get("OPENRC2_SILENT") == "1":
        return True
    return ctypes.windll.user32.MessageBoxW(None, text, title, 0x44) == 6


def info(text, title="OpenRC2 安装"):
    if os.environ.get("OPENRC2_SILENT") == "1":
        print("[silent]", text)
        return
    ctypes.windll.user32.MessageBoxW(None, text, title, 0x40)


def make_shortcut(lnk_path, target):
    ps = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}');"
          "$s.TargetPath='{tgt}';$s.WorkingDirectory='{wd}';"
          "$s.IconLocation='{tgt},0';$s.Description='OpenRC2 虚拟手柄';$s.Save()"
          ).format(lnk=lnk_path, tgt=target, wd=os.path.dirname(target))
    subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def vigem_installed():
    root = os.environ.get("SystemRoot", "C:" + BS + "Windows")
    return os.path.exists(os.path.join(root, "System32", "drivers", "ViGEmBus.sys"))


def install_driver():
    if vigem_installed():
        return True
    setup = res(os.path.join("payload", "driver", "ViGEmBus_setup.exe"))
    if not os.path.exists(setup):
        return False
    try:
        subprocess.run([setup, "/S"], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=180)
    except Exception:
        pass
    return vigem_installed()


def copy_tree(src, dst):
    for root, _dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        target = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(target, exist_ok=True)
        for f in files:
            shutil.copy2(os.path.join(root, f), os.path.join(target, f))


def write_uninstaller():
    bat = os.path.join(DEST, "卸载 OpenRC2.bat")
    desktop = os.path.join(os.path.expanduser("~"), "Desktop", APP_NAME + ".lnk")
    startmenu = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows",
                             "Start Menu", "Programs", APP_NAME + ".lnk")
    reg = "HKCU" + BS + "Software" + BS + "Microsoft" + BS + "Windows" + BS + \
          "CurrentVersion" + BS + "Uninstall" + BS + "OpenRC2Web"
    L = [
        "@echo off",
        "taskkill /f /im OpenRC2-Web.exe >nul 2>&1",
        "taskkill /f /im padcore.exe >nul 2>&1",
        'del /q "%s" >nul 2>&1' % desktop,
        'del /q "%s" >nul 2>&1' % startmenu,
        'reg delete "%s" /f >nul 2>&1' % reg,
        'cd /d "%TEMP%" && rmdir /s /q "%s"' % DEST,
    ]
    with open(bat, "w", encoding="gbk", newline="\r\n") as f:
        for line in L:
            f.write(line + "\n")
    return bat


def main():
    payload = res("payload")
    if not os.path.isdir(payload):
        info("安装包损坏：缺少 payload。")
        return 1
    if not ask("即将把 OpenRC2 网页版安装到：\n" + DEST + "\n\n继续安装？"):
        return 0
    try:
        if os.path.isdir(DEST):
            shutil.rmtree(DEST, ignore_errors=True)
        os.makedirs(DEST, exist_ok=True)
        copy_tree(payload, DEST)
    except Exception as e:
        info("复制文件失败：\n" + str(e))
        return 1

    target = os.path.join(DEST, EXE_NAME)
    if not os.path.exists(target):
        info("安装包缺少 " + EXE_NAME)
        return 1

    make_shortcut(os.path.join(os.path.expanduser("~"), "Desktop", APP_NAME + ".lnk"), target)
    sm = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs")
    if os.path.isdir(sm):
        make_shortcut(os.path.join(sm, APP_NAME + ".lnk"), target)

    drv = install_driver()
    write_uninstaller()

    msg = "安装完成。\n\n· 桌面快捷方式：OpenRC2 虚拟手柄\n· 安装目录：" + DEST
    msg += "\n· ViGEmBus 驱动：" + ("已就绪" if drv else "未安装（可运行目录内 driver/ViGEmBus_setup.exe）")
    msg += "\n\n现在启动吗？"
    if ask(msg):
        subprocess.Popen([target], cwd=DEST, creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
