@echo off
title Pentecost - startup profile
cd /d "%~dp0"
set PENTECOST_JFR=1
echo Pentecost: starting Minecraft with the Java Flight Recorder on.
echo Console output: run\pentecost-console.log
echo Recording: run\startup.jfr (written when the game closes)
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
echo Recording: %~dp0run\startup.jfr
pause
