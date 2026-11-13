@echo off
title Pentecost - ZGC
cd /d "%~dp0"
set PENTECOST_GC=zgc
echo Pentecost: starting with generational ZGC. The console output goes to run\pentecost-console.log
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
pause
