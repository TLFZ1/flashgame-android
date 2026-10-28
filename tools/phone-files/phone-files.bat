@echo off
chcp 936 >nul 2>&1
setlocal enabledelayedexpansion
if /i "%~1"=="pick" set PHONE_PICK=1
cd /d "%~dp0"
title Phone File Manager

where python >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Python not found. Install Python 3 and check "Add Python to PATH".
  pause
  exit /b 1
)

set TRIES=0

:run
python "%~dp0phone-files.py" 8100
if not errorlevel 1 goto done
if %TRIES% GEQ 1 goto done

:ask
echo.
echo ============================================================
echo   Could not find the phone automatically.
echo   Enter the address shown on the phone:
echo   Settings - Developer options - Wireless debugging
echo   It looks like:   192.168.1.23:5555
echo   (IP address, a colon, then the port - do NOT type the port alone)
echo ============================================================
set "PHONEDEV="
set /p PHONEDEV=  IP:port = 
echo !PHONEDEV! | findstr /r /c:"^[0-9][0-9.]*:[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo.
  echo   Wrong format: "!PHONEDEV!" is not IP:port
  echo   Example of a correct answer:  192.168.1.23:5555
  goto ask
)
echo !PHONEDEV!> phone.txt
echo   Saved to phone.txt
set TRIES=1
goto run

:done
echo.
echo Server stopped.
pause