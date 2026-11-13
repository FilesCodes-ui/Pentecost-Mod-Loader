@echo off
title Pentecost - playtest with log
cd /d "%~dp0"
set PENTECOST_REPORT=1
echo Pentecost: starting Minecraft 1.20.1. The console output goes to run\pentecost-console.log
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
echo Mixin report: %~dp0run\logs\pentecost-mixins.log
echo Crash reports: %~dp0run\crash-reports
pause
