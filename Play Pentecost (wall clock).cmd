@echo off
title Pentecost - wall clock profile
cd /d "%~dp0"
set PENTECOST_WALL=1
echo Pentecost: starting Minecraft with the wall clock sampler on.
echo Console output: run\pentecost-console.log
echo Samples: run\wallclock.txt (written every 10 seconds)
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q > run\pentecost-console.log 2>&1
echo.
echo The game stopped. Log: %~dp0run\pentecost-console.log
echo Samples: %~dp0run\wallclock.txt
pause
