@echo off
chcp 65001 >nul
setlocal
echo ==========================================
echo  RC2 EdgeTX  -  USB 游戏手柄 开启
echo ==========================================
echo.
echo 前提：遥控器已用 USB 连电脑，adb devices 能看到设备。
echo.
where adb >nul 2>&1
if errorlevel 1 (
  echo [!] 找不到 adb，请把 platform-tools 目录加入 PATH。
  pause & exit /b 1
)
echo [1/3] 检查设备...
adb devices
echo.
echo [2/3] 写入脚本到设备...
adb push "%~dp0..\usbjoy\setup.sh" /data/local/tmp/etx_setup.sh || goto :fail
adb push "%~dp0..\usbjoy\restore.sh" /data/local/tmp/etx_restore.sh || goto :fail
adb push "%~dp0..\usbjoy\desc.b64" /data/local/tmp/desc.b64 || goto :fail
echo.
echo [3/3] 执行（需要 root；本 ROM 的 su 在 /debug_ramdisk/su）...
adb shell "mkdir -p /data/local/tmp/usbjoy; cp /data/local/tmp/desc.b64 /data/local/tmp/etx_setup.sh /data/local/tmp/etx_restore.sh /data/local/tmp/usbjoy/ 2>/dev/null"
adb shell "/debug_ramdisk/su -c \"cd /data/local/tmp/usbjoy; sh /data/local/tmp/etx_setup.sh\"" > "%TEMP%\etx_hid.txt" 2>&1
findstr /C:"state=configured" "%TEMP%\etx_hid.txt" >nul 2>&1
if errorlevel 1 (
  echo    /debug_ramdisk/su 未成功，改用 PATH 里的 su 再试...
  adb shell "su -c \"cd /data/local/tmp/usbjoy; sh /data/local/tmp/etx_setup.sh\"" >> "%TEMP%\etx_hid.txt" 2>&1
)
type "%TEMP%\etx_hid.txt"
echo.
findstr /C:"state=configured" "%TEMP%\etx_hid.txt" >nul 2>&1
if errorlevel 1 goto :failroot
echo 成功标志：state=configured，Windows「设备和打印机」出现 HID-compliant game controller。
echo 之后插线在软件里选「游戏手柄」即可，不必再 root。
pause & exit /b 0
:failroot
echo [!] 没拿到 state=configured。可能：①su 没授权 ②线只能充电 ③DJI App 占着 USB
pause & exit /b 1
:fail
echo [!] 推送失败，请检查 adb 连接。
pause & exit /b 1
