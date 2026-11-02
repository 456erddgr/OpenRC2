@echo off
setlocal
cd /d "%~dp0"
start "" /min "%~dp0runtime\pythonw.exe" "%~dp0padcore.py"
timeout /t 2 >nul
set EDGE="%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist %EDGE% set EDGE="%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
%EDGE% --app=http://127.0.0.1:9998/ --window-size=1200,760
