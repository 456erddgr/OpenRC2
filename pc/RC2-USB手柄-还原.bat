@echo off
chcp 65001 >nul
setlocal
echo ==========================================
echo  RC2 EdgeTX  -  还原成 USB 文件传输 / 调试
echo ==========================================
echo.
where adb >nul 2>&1
if errorlevel 1 (
  echo [!] 找不到 adb，请把 platform-tools 加入 PATH。
  pause & exit /b 1
)
adb shell "/debug_ramdisk/su -c \"cd /data/local/tmp/usbjoy; sh /data/local/tmp/etx_restore.sh\"" 2>&1
adb shell "su -c \"cd /data/local/tmp/usbjoy; sh /data/local/tmp/etx_restore.sh\"" 2>&1
echo.
echo 已尝试还原；也可以直接在软件里选「文件存储 / USB 调试」。
pause & exit /b 0
