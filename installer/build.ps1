<#
  Builds the standalone Pentecost installer: build\release\Pentecost-Installer-<version>.exe
  It also copies the menu world zips to build\release\ under the names the installer downloads.

    powershell -ExecutionPolicy Bypass -File installer\build.ps1 [-NoWorlds]

  -NoWorlds  makes an installer that does not offer the menu world download.
#>
param([switch]$NoWorlds)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$repo = 'FilesCodes-ui/Pentecost-Mod-Loader'

$version = ([regex]::Match((Get-Content build.gradle -Raw), "version\s*=\s*'([^']+)'")).Groups[1].Value
if (-not $version) { throw 'Cannot read the version from build.gradle.' }
Write-Host "Pentecost $version"

# 1. The program files, from the existing Gradle packaging.
& .\gradlew.bat installerZip --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'gradlew installerZip failed.' }
$zip = Join-Path $root "build\dist\Pentecost-$version-Installer.zip"
$stage = Join-Path $root 'build\installer-stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory $stage | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory($zip, $stage)
$inner = Join-Path $stage "Pentecost-$version-Installer"

$pack = Join-Path $root 'build\installer-payload'
if (Test-Path $pack) { Remove-Item $pack -Recurse -Force }
New-Item -ItemType Directory $pack | Out-Null
Copy-Item (Join-Path $inner 'payload') (Join-Path $pack 'payload') -Recurse
foreach ($f in 'LICENSE.txt', 'THIRD-PARTY-NOTICES.txt') { Copy-Item (Join-Path $inner $f) $pack }
$payloadZip = Join-Path $PSScriptRoot 'payload.zip'
if (Test-Path $payloadZip) { Remove-Item $payloadZip }
[IO.Compression.ZipFile]::CreateFromDirectory($pack, $payloadZip, [IO.Compression.CompressionLevel]::Optimal, $false)
Copy-Item (Join-Path $inner 'payload\pentecost.ico') (Join-Path $PSScriptRoot 'app.ico') -Force
Write-Host ('payload.zip ' + [math]::Round((Get-Item $payloadZip).Length / 1MB, 1) + ' MB')

# 2. The menu worlds: a manifest for the installer, and renamed copies to upload to the release page.
$release = Join-Path $root 'build\release'
New-Item -ItemType Directory -Force $release | Out-Null
$entries = @()
if (-not $NoWorlds) {
    foreach ($w in Get-ChildItem (Join-Path $root 'menu_world') -Filter '*.zip' | Sort-Object Name) {
        $seed = $w.BaseName
        $asset = "menu-world-$seed.zip"
        Write-Host "hashing $($w.Name)"
        $entries += [ordered]@{
            seed   = $seed
            asset  = $asset
            size   = $w.Length
            sha256 = (Get-FileHash $w.FullName -Algorithm SHA256).Hash
        }
        Copy-Item $w.FullName (Join-Path $release $asset) -Force
    }
}
$manifest = [ordered]@{ baseUrl = "https://github.com/$repo/releases/download/v$version/"; worlds = $entries }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'worlds.json'), ($manifest | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))

# 3. One self-contained exe. The friend needs neither .NET nor Java for the installer itself.
$out = Join-Path $root 'build\installer-out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
& dotnet publish (Join-Path $PSScriptRoot 'Pentecost.Installer.csproj') -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o $out --nologo -v quiet
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }
$exe = Join-Path $release "Pentecost-Installer-$version.exe"
Copy-Item (Join-Path $out 'Pentecost-Installer.exe') $exe -Force
Write-Host ('built ' + $exe + ' (' + [math]::Round((Get-Item $exe).Length / 1MB, 1) + ' MB)')
