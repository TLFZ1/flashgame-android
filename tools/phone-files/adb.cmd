@echo off
REM adb entry point for this portable package.
REM Use: adb.cmd devices   (or add this folder to PATH and just type adb)
"%~dp0platform-tools\adb.exe" %*
exit /b %errorlevel%