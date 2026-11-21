@echo off
title Pentecost - clean room (only the mods in cache\try)
cd /d "%~dp0"
echo Pentecost clean room: Minecraft 1.20.1 with the Forge layer and ONLY the jars in:
echo   %~dp0cache\try
echo (Your normal mods folder is not used.)
echo.
call .tools\gradle-8.14.5\bin\gradle.bat runForge --console=plain -q
echo.
if errorlevel 1 (
  echo The game stopped with an error. The readable crash report is in:
  echo   %~dp0run\crash-reports
  echo   %~dp0run\pentecost-reports
)
echo Log: %~dp0run\logs\latest.log
pause
