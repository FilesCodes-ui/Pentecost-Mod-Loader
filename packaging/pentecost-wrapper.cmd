@echo off
rem Modrinth App wrapper for Pentecost. Set this file as the instance's Wrapper launch hook.
rem Modrinth passes the game's command line as arguments; they go unchanged to the PowerShell script.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0pentecost-wrapper.ps1" %*
exit /b %ERRORLEVEL%
