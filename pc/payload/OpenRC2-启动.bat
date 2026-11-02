@echo off
chcp 65001 >nul
setlocal
set "DIR=%~dp0"
echo 正在解除文件锁定（Windows 会拦截从网上下载的 exe）...
powershell -NoProfile -Command "Get-ChildItem -Path '%DIR%' -Filter *.exe -Recurse | Unblock-File" >nul 2>&1
echo 启动 OpenRC2 虚拟手柄...
if exist "%DIR%OpenRC2-Pad.exe" (
  start "" "%DIR%OpenRC2-Pad.exe"
) else (
  echo 找不到 OpenRC2-Pad.exe —— 请把本 bat 和 exe 放在同一个文件夹。
)
echo.
echo 如果窗口没出现，请查看日志： %LOCALAPPDATA%\OpenRC2\log.txt
timeout /t 4 >nul
