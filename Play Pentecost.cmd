@echo off
title Pentecost - Forge x Fabric playtest
cd /d "%~dp0"
echo Pentecost: starting Minecraft 1.20.1 with every jar in the mods folder.
echo Forge and Fabric mods both go in: %~dp0mods
echo First launch after adding mods takes longer while Pentecost converts them.
echo.
call .tools\gradle-8.14.5\bin\gradle.bat runPlay --console=plain -q
echo.
if errorlevel 1 (
  echo The game stopped with an error. The readable crash report is in:
  echo   %~dp0run\crash-reports
  echo   %~dp0run\pentecost-reports
)
pause
