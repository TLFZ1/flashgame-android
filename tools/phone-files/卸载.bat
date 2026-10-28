@echo off
setlocal
REM Uninstall FlashGame phone-files portable package.
REM Removes: desktop shortcut, adb forwarder, junction, and the package folder.
REM Use:  uninstall.bat            (interactive)
REM       uninstall.bat /dryrun   (only print what would be removed)

set "PKG=%~dp0"
set "PKG=%PKG:~0,-1%"
set "SHORTCUT=%USERPROFILE%\Desktop\PhoneFiles.lnk"
set "FWD=%USERPROFILE%\.local\bin\adb.cmd"
set "JUNC=%LOCALAPPDATA%\PhoneFiles"
set DRY=0
if /i "%~1"=="/dryrun" set DRY=1

echo ============================================================
echo   Uninstall phone-files portable package
echo ============================================================
echo.
echo   package   : %PKG%
echo   shortcut  : %SHORTCUT%
echo   adb shim  : %FWD%
echo   junction  : %JUNC%
echo.
if "%DRY%"=="1" goto dryrun

echo Close the phone-files window first if it is running.
pause

:step_shortcut
if exist "%SHORTCUT%" (
  del /f /q "%SHORTCUT%" && echo   removed shortcut
) else (
  echo   shortcut not found, skip
)

:step_fwd
if exist "%FWD%" (
  del /f /q "%FWD%" && echo   removed adb shim
) else (
  echo   adb shim not found, skip
)

:step_junction
if exist "%JUNC%\" (
  rmdir "%JUNC%" && echo   removed junction
) else (
  echo   junction not found, skip
)

:step_pkg
cd /d "%TEMP%"
if exist "%PKG%" (
  rmdir /s /q "%PKG%" && echo   removed package folder
) else (
  echo   package folder not found, skip
)

echo.
echo Done. The zip backup (if any) is untouched.
pause
exit /b 0

:dryrun
echo [dry run] nothing will be deleted.
if exist "%SHORTCUT%" (echo   would delete: %SHORTCUT%) else (echo   missing: %SHORTCUT%)
if exist "%FWD%" (echo   would delete: %FWD%) else (echo   missing: %FWD%)
if exist "%JUNC%\" (echo   would delete: %JUNC%) else (echo   missing: %JUNC%)
if exist "%PKG%" (echo   would delete: %PKG%) else (echo   missing: %PKG%)
echo.
pause
exit /b 0