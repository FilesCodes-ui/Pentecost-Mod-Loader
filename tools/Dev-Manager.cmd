@echo off
rem Pentecost Manager (dev): builds manager_new from source and runs it beside the installed Manager.
setlocal
set "SRC=C:\Users\files\source\Pentecost\manager_new"
set "OUT=%SRC%\bin\dev"

rem If this dev copy is already open, just bring it forward (--dev has its own single-instance).
powershell -NoProfile -Command "if (Get-Process Pentecost-Manager -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq '%OUT%\Pentecost-Manager.exe' }) { exit 0 } else { exit 1 }"
if not errorlevel 1 (
  start "" "%OUT%\Pentecost-Manager.exe" --dev
  exit /b 0
)

echo Building Pentecost Manager from source...
dotnet build "%SRC%\Pentecost.Manager.csproj" -c Debug -o "%OUT%" -v q -nologo
if errorlevel 1 (
  echo.
  echo Build failed. Fix the errors above, then run this again.
  pause
  exit /b 1
)
start "" "%OUT%\Pentecost-Manager.exe" --dev
