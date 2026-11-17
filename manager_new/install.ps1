# Builds the Manager into a folder of its own and puts a shortcut on the desktop and in the Start menu.
# Run it again after changing the code to update the installed copy.
$ErrorActionPreference = 'Stop'

$project = Join-Path $PSScriptRoot 'Pentecost.Manager.csproj'
$target = Join-Path $env:LOCALAPPDATA 'Pentecost Manager'
$exe = Join-Path $target 'Pentecost-Manager.exe'

Get-Process Pentecost-Manager -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 400

# The program draws its own icon, so build once, ask it for the icon, then build for real.
Write-Host 'Drawing the icon'
dotnet build $project -c Release --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'The build failed.' }
$built = Join-Path $PSScriptRoot 'bin\Release\net9.0-windows\Pentecost-Manager.exe'
Start-Process $built -ArgumentList '--icon', (Join-Path $PSScriptRoot 'app.ico') -Wait

Write-Host "Building into $target"
dotnet publish $project -c Release -o $target --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'The build failed.' }
if (-not (Test-Path $exe)) { throw "$exe was not produced." }

Remove-Item (Join-Path $target 'shots') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $target '*.pdb') -Force -ErrorAction SilentlyContinue

$shell = New-Object -ComObject WScript.Shell
function Set-Shortcut($path) {
    $lnk = $shell.CreateShortcut($path)
    $lnk.TargetPath = $exe
    $lnk.WorkingDirectory = $target
    $lnk.IconLocation = "$exe,0"
    $lnk.Description = 'Pentecost: Forge, Fabric and Quilt mods in one game'
    $lnk.Save()
    Write-Host "Shortcut: $path"
}

Set-Shortcut (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Pentecost Manager.lnk')
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
New-Item -ItemType Directory -Force -Path $startMenu | Out-Null
Set-Shortcut (Join-Path $startMenu 'Pentecost Manager.lnk')

Write-Host "Done. The program lives in $target"
