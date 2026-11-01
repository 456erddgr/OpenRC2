@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
echo ============================================================
echo  把 OpenRC2 推到你自己的 GitHub
echo ============================================================
echo.
echo 先做三件事（只做一次）：
echo   1. 在 GitHub 网页上新建一个空仓库（不要勾 Add README / .gitignore）
echo   2. 记下仓库地址，形如 https://github.com/你的用户名/OpenRC2.git
echo   3. 准备好 Personal Access Token（Settings - Developer settings - Tokens）
echo.
set /p REPO=粘贴仓库地址（https://github.com/xxx/OpenRC2.git）:
if "%REPO%"=="" (echo [!] 没填地址 & pause & exit /b 1)
set /p TOKEN=粘贴 Token（输入时不会显示也可以直接回车跳过，用钥匙串/浏览器认证）:
echo.
cd /d "%~dp0.."
git config user.name "软糖布丁" 2>nul
git config user.email "gummy@users.noreply.github.com" 2>nul
git branch -M main 2>nul
git remote remove origin 2>nul
if "%TOKEN%"=="" (
  git remote add origin "%REPO%"
) else (
  for /f "tokens=1,2 delims=/" %%a in ("%REPO%") do set _dummy=1
  set "R2=%REPO:https://=https://x-access-token:%TOKEN%@%"
  git remote add origin "!R2!"
)
echo [1/2] 推送...
git push -u origin main
if errorlevel 1 (
  echo.
  echo [!] 推送失败。常见原因：
  echo     - 仓库非空（新建时勾了 README）→ 先 git pull --rebase origin main
  echo     - Token 没勾 repo 权限
  echo     - 网络需要代理
  pause & exit /b 1
)
echo.
echo [2/2] 完成。打开 %REPO% 看 README 和截图。
pause & exit /b 0
