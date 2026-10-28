@echo off
cd /d "%~dp0"
title Enable wireless adb (one time, USB required)
set ADB=%~dp0platform-tools\adb.exe
echo ============================================================
echo   One-time setup: switch phone adb to Wi-Fi mode
echo ============================================================
echo.
echo   Connect the phone with a USB cable first, then press a key.
pause >nul
"%ADB%" devices
echo.
echo Enabling tcpip 5555 ...
"%ADB%" tcpip 5555
echo.
"%ADB%" shell ip route 2>nul | findstr /i "src"
echo.
echo Done. You can unplug the cable now.
echo Phone address will be  ^<phone-ip^>:5555
echo (phone IP is shown on the wireless debugging page)
pause