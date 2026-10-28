@echo off
setlocal
REM Uninstall the phone-files portable package.
REM Removes: desktop shortcut, adb PATH entries (both forms), and this folder.
REM Use:  uninstall.bat            (interactive)
REM       uninstall.bat /dryrun   (only print what would be removed)

set "PKG=%~dp0"
set "PKG=%PKG:~0,-1%"
set "SHORTCUT=%USERPROFILE%\Desktop\PhoneFiles.lnk"
set DRY=0
if /i "%~1"=="/dryrun" set DRY=1

echo ============================================================
echo   Uninstall phone-files portable package
echo ============================================================
echo.
echo   package    : %PKG%
echo   PATH forms : %PKG%
echo                %PKG%\platform-tools
echo   shortcut   : %SHORTCUT%
echo.
if "%DRY%"=="1" goto dryrun

echo Close the phone-files window first if it is running.
pause

if exist "%SHORTCUT%" (
  del /f /q "%SHORTCUT%" && echo   removed shortcut
) else (
  echo   shortcut not found, skip
)

echo   cleaning PATH entries ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=[Environment]::GetEnvironmentVariable('Path','User'); $a='%PKG%'.TrimEnd('\'); $b=($a + '\platform-tools'); $n=((($p -split ';') | Where-Object { $_ -and ($_.TrimEnd('\') -ne $a) -and ($_.TrimEnd('\') -ne $b) }) -join ';'); [Environment]::SetEnvironmentVariable('Path',$n,'User'); Write-Host ('   PATH entries now: ' + (($n -split ';').Count))"

cd /d "%TEMP%"
if exist "%PKG%" (
  rmdir /s /q "%PKG%" && echo   removed package folder
) else (
  echo   package folder not found, skip
)

echo.
echo Done. Open a NEW terminal for the PATH change to take effect.
pause
exit /b 0

:dryrun
echo [dry run] nothing will be changed.
if exist "%SHORTCUT%" (echo   would delete : %SHORTCUT%) else (echo   missing      : %SHORTCUT%)
powershell -NoProfile -Command "$p=[Environment]::GetEnvironmentVariable('Path','User'); $a='%PKG%'.TrimEnd('\'); $b=($a + '\platform-tools'); $hit=($p -split ';') | Where-Object { $_ -and (($_.TrimEnd('\') -eq $a) -or ($_.TrimEnd('\') -eq $b)) }; if ($hit) { $hit | ForEach-Object { Write-Host ('   would remove PATH: ' + $_) } } else { Write-Host '   no PATH entry found (ok)' }"
if exist "%PKG%" (echo   would delete : %PKG%) else (echo   missing      : %PKG%)
echo.
pause
exit /b 0