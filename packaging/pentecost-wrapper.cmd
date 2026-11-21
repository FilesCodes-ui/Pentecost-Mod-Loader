@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0pentecost-wrapper.ps1" %*
exit /b %ERRORLEVEL%
