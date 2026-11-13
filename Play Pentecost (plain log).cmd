@echo off
title Pentecost - plain log
cd /d "%~dp0"
echo Pentecost: starting Minecraft 1.20.1 with the class cache on. The console output goes to run\pentecost-console.log
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
pause
