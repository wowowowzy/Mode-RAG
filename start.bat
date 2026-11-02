@echo off
setlocal EnableExtensions EnableDelayedExpansion
title ModeTest Launcher

rem 用脚本自身所在目录定位其他文件，解压到任意位置都能用
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "JEV=%ROOT%\Jev-Style"
set "ROUTER=%ROOT%\ModeRouter"
set "TEST=%ROOT%\ModeTest"

set "HF_HOME=%JEV%\.hf"
set "HUGGINGFACE_HUB_CACHE=%JEV%\.hf\hub"
set "HF_ENDPOINT=https://hf-mirror.com"
set "TMP=%JEV%\.tmp"
set "TEMP=%JEV%\.tmp"
set "JEV_STYLE_DEVICE=cpu"
set "PYTHONIOENCODING=utf-8"

echo ============================================
echo   ModeTest 一键启动
echo ============================================
echo.

echo [1/4] 精判引擎 JEV (8765)
set "STARTED=0"
call :isup 8765
if not errorlevel 1 (
  echo       已在运行，跳过
) else (
  start "JEV" /min /d "%JEV%" "%JEV%\.venv\Scripts\python.exe" -c "import sys;from jev_style.cli import main;sys.exit(main())" serve --release 0.8b-v3 --backend torch --device cpu --host 127.0.0.1 --port 8765 --model-dir "%JEV%\model"
  call :wait 8765 90
  if errorlevel 1 (echo       启动超时，看 JEV 窗口) else (echo       已启动)
)

echo [2/4] 骨架服务 ModeRouter (18080)
call :isup 18080
if not errorlevel 1 (
  echo       已在运行，跳过
) else (
  start "ModeRouter" /min /d "%ROUTER%" java -Dfile.encoding=UTF-8 -jar "%ROUTER%\target\moderoute-1.0.0.jar" --server.port=18080
  call :wait 18080 90
  call :waitup 18080 60
  if errorlevel 1 (echo       启动超时，看 ModeRouter 窗口) else (echo       已启动)
)

echo [3/4] 评测后端 ModeTest (18090)
call :isup 18090
if not errorlevel 1 (
  echo       已在运行，跳过
) else (
  start "ModeTest API" /min /d "%TEST%" java -Dfile.encoding=UTF-8 -jar "%TEST%\target\modetest-1.0.0.jar" --server.port=18090
  call :wait 18090 90
  if errorlevel 1 (echo       启动超时，看 ModeTest API 窗口) else (echo       已启动)
)

echo [4/4] 前端 Vite (5173)
call :isup 5173
if not errorlevel 1 (
  echo       已在运行，跳过
) else (
  start "ModeTest Web" /min /d "%TEST%\web" cmd /c npm run dev
  call :wait 5173 60
  if errorlevel 1 (echo       启动超时，看 ModeTest Web 窗口) else (echo       已启动)
)

echo.
echo ============================================
echo   状态
echo ============================================
for %%P in (8765 18080 18090 5173) do (
  call :isup %%P
  if errorlevel 1 (echo   %%P  DOWN) else (echo   %%P  UP)
)
echo.
echo   页面: http://127.0.0.1:5173
echo   停止: stop.bat
echo ============================================
echo.
pause
exit /b 0

:isup
netstat -ano | findstr ":%~1 " | findstr "LISTENING" >nul 2>&1
exit /b %ERRORLEVEL%

:wait
set /a _n=0
:waitloop
netstat -ano | findstr ":%~1 " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 exit /b 0
set /a _n+=1
if %_n% geq %~2 exit /b 1
ping -n 3 127.0.0.1 >nul
goto waitloop

:waitup
set /a _u=0
:uploop
curl -s --max-time 2 http://127.0.0.1:%~1/healthz 2>nul | findstr "UP" >nul 2>&1
if not errorlevel 1 exit /b 0
set /a _u+=1
if %_u% geq %~2 (
  echo       注意: 向量仍在预热, 首次请求可能返回 DEGRADED
  exit /b 0
)
ping -n 3 127.0.0.1 >nul
goto uploop
