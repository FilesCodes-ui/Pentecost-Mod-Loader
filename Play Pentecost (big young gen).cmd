@echo off
title Pentecost - 6 GB heap, 3 GB young
cd /d "%~dp0"
set PENTECOST_JVM=-Xms6G -Xmx6G -Xmn3G
echo Pentecost: starting with a 6 GB heap and a 3 GB young area. Log: run\pentecost-console.log
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
pause
