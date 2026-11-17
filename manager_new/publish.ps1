# Builds the one-file Manager that players download: build\release\Pentecost-Manager-Setup-<version>.exe
# It needs neither .NET nor Java. Run it, it installs the Manager, and the Manager installs Pentecost.
#   powershell -ExecutionPolicy Bypass -File manager_new\publish.ps1
$ErrorActionPreference = 'Stop'

$project = Join-Path $PSScriptRoot 'Pentecost.Manager.csproj'
$root = Split-Path -Parent $PSScriptRoot
$version = ([regex]::Match((Get-Content $project -Raw), '<Version>([^<]+)</Version>')).Groups[1].Value
if (-not $version) { throw 'Cannot read the version from the project file.' }
Write-Host "Pentecost Manager $version"

# The program draws its own icon, so build once, ask it for the icon, then publish.
Write-Host 'Drawing the icon'
dotnet build $project -c Release --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'The build failed.' }
$built = Join-Path $PSScriptRoot 'bin\Release\net9.0-windows\Pentecost-Manager.exe'
Start-Process $built -ArgumentList '--icon', (Join-Path $PSScriptRoot 'app.ico') -Wait

$out = Join-Path $root 'build\manager-out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
dotnet publish $project -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o $out --nologo -v q
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }

$release = Join-Path $root 'build\release'
New-Item -ItemType Directory -Force $release | Out-Null
$exe = Join-Path $release "Pentecost-Manager-Setup-$version.exe"
Copy-Item (Join-Path $out 'Pentecost-Manager.exe') $exe -Force
Write-Host ('built ' + $exe + ' (' + [math]::Round((Get-Item $exe).Length / 1MB, 1) + ' MB)')
