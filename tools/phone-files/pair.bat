@echo off
chcp 437 >nul 2>&1
cd /d "%~dp0"
title Pair phone (wireless debugging)
set ADB=%~dp0platform-tools\adb.exe

if not exist "%ADB%" (
  echo [ERROR] platform-tools\adb.exe not found next to this script.
  pause
  exit /b 1
)

echo ============================================================
echo   Pair the phone WITHOUT a USB cable
echo ============================================================
echo.
echo   On the phone: Developer options - Wireless debugging
echo     1) tap  "Pair device with pairing code"
echo     2) it shows  IP:PORT  and a 6-digit code
echo.
set /p PAIRADDR=  Enter IP:pair-port  = 
set /p CODE=      Enter 6-digit code = 
echo.
"%ADB%" pair %PAIRADDR% %CODE%
echo.
echo   If it says "Successfully paired", go BACK to the wireless
echo   debugging main page - it shows  "IP address and port"  there.
echo   That is the address this tool needs.
echo.
set /p CONNADDR=  Enter IP:port shown there = 
"%ADB%" connect %CONNADDR%
echo.
if not "%CONNADDR%"=="" (
  echo %CONNADDR%> phone.txt
  echo Saved to phone.txt
)
echo.
echo Now double-click phone-files.bat
pause