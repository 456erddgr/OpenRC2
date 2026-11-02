# -*- coding: utf-8 -*-
"""
OpenRC2 虚拟手柄（PC 端）—— 思路同 RM510 那套：
  1) 本脚本在 PC 上监听 0.0.0.0:9999，并用 adb reverse 把遥控器的 127.0.0.1:9999 反查到 PC；
  2) 遥控器 App 里把“虚拟手柄数据流”打开（设置→诊断页可看状态，或 adb 命令开）；
  3) 本脚本把收到的摇杆数据喂给 ViGEmBus，系统里就出现一个 Xbox 360 手柄；
  4) 模拟器/游戏里直接选这个手柄即可。

依赖：
  pip install vgamepad
  ViGEmBus 驱动：https://github.com/nefarius/ViGEmBus/releases （装一次，重启后可长期使用）

用法：
  python OpenRC2-虚拟手柄.py            # 自动 adb reverse + 造手柄
  python OpenRC2-虚拟手柄.py --dry       # 不造手柄，只打印收到的数据（排查用）
  python OpenRC2-虚拟手柄.py --port 9999 --deadzone 0.08
"""
import argparse
import json
import socket
import subprocess
import sys
import threading
import time

PORT = 9999
CENTER = 1024.0
HALF = 660.0          # DJI 摇杆满行程 ±660，实测 364..1684


def run_adb(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=10)
        return r.returncode == 0, (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return False, str(e)


def setup_reverse(port):
    ok, out = run_adb(["adb", "reverse", f"tcp:{port}", f"tcp:{port}"])
    if ok:
        print(f"[adb] reverse tcp:{port} -> tcp:{port} 已建立")
    else:
        print("[adb] reverse 失败（设备没连或没开 adb）：", out.strip()[:200])
        print("      无线调试也可以：adb connect <遥控器IP>:5555 后再执行本脚本")


def norm(v, deadzone):
    x = (float(v) - CENTER) / HALF
    if x > 1.0:
        x = 1.0
    if x < -1.0:
        x = -1.0
    if abs(x) < deadzone:
        return 0.0
    # 死区外重新拉伸，保证推到底还是 1.0
    s = 1.0 if x > 0 else -1.0
    return s * (abs(x) - deadzone) / (1.0 - deadzone)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--deadzone", type=float, default=0.08)
    ap.add_argument("--curve", type=float, default=1.0, help="1.0=线性，>1 更柔和")
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()

    pad = None
    if not args.dry:
        try:
            import vgamepad as vg
            pad = vg.VX360Gamepad()
            print("[vigem] 虚拟 Xbox360 手柄已创建（joy.cpl 里能看到）")
        except Exception as e:
            print("[vigem] 创建虚拟手柄失败：", e)
            print("        请先 pip install vgamepad，并安装 ViGEmBus 驱动")
            print("        可先用 --dry 只验证数据能不能收到")
            pad = None

    setup_reverse(args.port)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(4)
    print(f"[net] 等待遥控器连接 0.0.0.0:{args.port} ……（App 里打开“虚拟手柄数据流”）")

    frames = 0
    t0 = time.time()
    while True:
        conn, addr = srv.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print("[net] 遥控器已连接：", addr)
        buf = b""
        try:
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if not line.strip():
                        continue
                    try:
                        d = json.loads(line.decode("utf-8", "replace"))
                    except Exception:
                        continue
                    ax = d.get("ax") or [1024] * 6
                    lx, ly, rx, ry = (ax[i] if i < len(ax) else 1024 for i in range(4))
                    frames += 1
                    if args.dry:
                        if frames % 25 == 0:
                            print(f"ax={ax}  {frames/(time.time()-t0):.1f} fps")
                        continue
                    if pad is not None:
                        lxv = norm(rx, args.deadzone)     # 右水平 = 副翼
                        lyv = -norm(ry, args.deadzone)    # 右竖直 = 俯仰
                        rxv = norm(lx, args.deadzone)     # 左水平 = 偏航
                        ryv = -norm(ly, args.deadzone)    # 左竖直 = 油门
                        if args.curve != 1.0:
                            lxv = (abs(lxv) ** args.curve) * (1 if lxv >= 0 else -1)
                            lyv = (abs(lyv) ** args.curve) * (1 if lyv >= 0 else -1)
                        pad.left_joystick_float(x_value_float=rxv, y_value_float=ryv)
                        pad.right_joystick_float(x_value_float=lxv, y_value_float=lyv)
                        pad.update()
        except Exception as e:
            print("[net] 连接中断：", e)
        finally:
            try:
                conn.close()
            except Exception:
                pass
            print("[net] 遥控器断开，等待重连 ……")


if __name__ == "__main__":
    main()
