@echo off
title Pentecost - class cache check
cd /d "%~dp0"
set PENTECOST_CACHE_CHECK=1
echo Pentecost: starting Minecraft with the class cache check on.
echo Every cached class is compared with a fresh run. This launch is slower than a normal one.
echo Console output: run\pentecost-console.log
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
pause
