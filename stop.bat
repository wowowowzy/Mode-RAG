@echo off
setlocal EnableExtensions EnableDelayedExpansion
title ModeTest Stopper

echo ============================================
echo   ModeTest Ò»¼üÍ£Ö¹
echo ============================================
echo.

echo Í£Ö¹Ç°¶Ë Vite (5173)
call :killport 5173
taskkill /F /FI "WINDOWTITLE eq ModeTest Web*" >nul 2>&1

echo Í£Ö¹ÆÀ²âºó¶Ë ModeTest (18090)
call :killport 18090
taskkill /F /FI "WINDOWTITLE eq ModeTest API*" >nul 2>&1

echo Í£Ö¹¹Ç¼Ü·þÎñ ModeRouter (18080)
call :killport 18080
taskkill /F /FI "WINDOWTITLE eq ModeRouter*" >nul 2>&1

echo Í£Ö¹¾«ÅÐÒýÇæ JEV (8765)
call :killport 8765
taskkill /F /FI "WINDOWTITLE eq JEV*" >nul 2>&1

echo.
echo ============================================
echo   ×´Ì¬
echo ============================================
for %%P in (8765 18080 18090 5173) do (
  call :isup %%P
  if errorlevel 1 (echo   %%P  stopped) else (echo   %%P  STILL UP)
)
echo ============================================
echo.
pause
exit /b 0

:killport
for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":%~1 " ^| findstr "LISTENING"') do (
  taskkill /F /PID %%A >nul 2>&1
)
exit /b 0

:isup
netstat -ano | findstr ":%~1 " | findstr "LISTENING" >nul 2>&1
exit /b %ERRORLEVEL%