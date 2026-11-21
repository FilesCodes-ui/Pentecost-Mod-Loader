param(
    [string]$MinecraftDir = (Join-Path $env:APPDATA '.minecraft'),
    [switch]$Uninstall,
    [switch]$RemoveData,
    [switch]$NoPause
)
$ErrorActionPreference = 'Stop'
$ProfileKey = 'pentecost'
$Root = Split-Path -Parent $PSScriptRoot
$Payload = Join-Path $Root 'payload'
$PentecostHome = Join-Path $MinecraftDir 'pentecost'
$ProfilesFile = Join-Path $MinecraftDir 'launcher_profiles.json'
function Finish([int]$code) {
    if (-not $NoPause) { Write-Host ''; Read-Host 'Press Enter to close' | Out-Null }
    exit $code
}
function Fail([string]$message) {
    Write-Host ''
    Write-Host "Could not finish: $message" -ForegroundColor Red
    Finish 1
}
function Test-LauncherRunning {
    if ($MinecraftDir -ne (Join-Path $env:APPDATA '.minecraft')) { return $false }
    return [bool](Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^Minecraft(Launcher|\.Launcher)$' })
}

function New-Dict { [System.Collections.Generic.Dictionary[string,object]]::new() }
function Read-Profiles {
    Add-Type -AssemblyName System.Web.Extensions
    $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $ser.MaxJsonLength = [int]::MaxValue
    $ser.RecursionLimit = 200
    $data = $ser.DeserializeObject([IO.File]::ReadAllText($ProfilesFile))
    if (-not ($data -is [System.Collections.IDictionary])) { Fail 'launcher_profiles.json is not in the format I expected. It was not changed.' }
    if (-not $data.ContainsKey('profiles')) { $data['profiles'] = New-Dict }
    return @($ser, $data)
}
function Save-Profiles($ser, $data) {
    [IO.File]::WriteAllText($ProfilesFile, $ser.Serialize($data), (New-Object Text.UTF8Encoding($false)))
}
if (-not (Test-Path $MinecraftDir)) { Fail "I cannot find $MinecraftDir. Open the Minecraft Launcher once, then run this again." }
if (-not (Test-Path $ProfilesFile)) { Fail "I cannot find launcher_profiles.json in $MinecraftDir. Open the Minecraft Launcher once, sign in, close it, then run this again." }
if (Test-LauncherRunning) { Fail 'The Minecraft Launcher is open. Close it completely (also from the system tray), then run this again. It rewrites its profile file when it closes.' }
if ($Uninstall) {
    Write-Host 'Removing Pentecost from the Minecraft Launcher...'
    $backup = "$ProfilesFile.pentecost-backup"
    if (-not (Test-Path $backup)) { Copy-Item $ProfilesFile $backup }
    $pair = Read-Profiles
    $ser = $pair[0]; $data = $pair[1]
    if ($data['profiles'].ContainsKey($ProfileKey)) { [void]$data['profiles'].Remove($ProfileKey); Save-Profiles $ser $data; Write-Host '  profile removed' }
    Get-ChildItem (Join-Path $MinecraftDir 'versions') -Directory -Filter 'Pentecost-*' -ErrorAction SilentlyContinue |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force; Write-Host "  version $($_.Name) removed" }
    foreach ($item in 'lib', 'shaderpack', 'pentecost-wrapper.ps1', 'pentecost-wrapper.cmd', 'pentecost.ico', 'pentecost_still_256.png', 'version.txt') {
        $p = Join-Path $PentecostHome $item
        if (Test-Path $p) { Remove-Item $p -Recurse -Force }
    }
    if ($RemoveData) {
        Write-Host ''
        Write-Host "This deletes $PentecostHome with your Pentecost worlds, mods, settings and downloaded game files." -ForegroundColor Yellow
        if ((Read-Host 'Type DELETE to confirm') -ceq 'DELETE') { Remove-Item $PentecostHome -Recurse -Force; Write-Host '  game folder deleted' }
        else { Write-Host '  game folder kept' }
    } else {
        Write-Host "Your worlds, mods and settings are kept in $PentecostHome\run."
    }
    Write-Host 'Done.'
    Finish 0
}
$mainJar = Get-ChildItem (Join-Path $Payload 'lib') -Filter 'pentecost-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'gameglue|forgeglue' } | Select-Object -First 1
if (-not $mainJar) { Fail 'The installer files are incomplete (payload\lib is missing). Unzip the whole zip first, then run the installer from the unzipped folder.' }
$Version = $mainJar.BaseName -replace '^pentecost-', ''
$VersionId = "Pentecost-$Version"
Write-Host "Installing Pentecost $Version into $MinecraftDir"
New-Item -ItemType Directory -Force $PentecostHome | Out-Null
$lib = Join-Path $PentecostHome 'lib'
if (Test-Path $lib) { Remove-Item $lib -Recurse -Force }
Copy-Item (Join-Path $Payload 'lib') $lib -Recurse
$shader = Join-Path $PentecostHome 'shaderpack'
if (Test-Path $shader) { Remove-Item $shader -Recurse -Force }
if (Test-Path (Join-Path $Payload 'shaderpack')) { Copy-Item (Join-Path $Payload 'shaderpack') $shader -Recurse }
foreach ($f in 'pentecost-wrapper.ps1', 'pentecost-wrapper.cmd', 'pentecost.ico', 'pentecost_still_256.png') {
    if (Test-Path (Join-Path $Payload $f)) { Copy-Item (Join-Path $Payload $f) $PentecostHome -Force }
}
Set-Content -Path (Join-Path $PentecostHome 'version.txt') -Value $Version -Encoding ASCII
New-Item -ItemType Directory -Force (Join-Path $PentecostHome 'run\mods') | Out-Null
Write-Host '  program files copied'
$gameglue = Join-Path $lib "glue\pentecost-gameglue-$Version.jar"
$forgeglue = Join-Path $lib "glue\pentecost-forgeglue-$Version.jar"
$now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$jvm = @(
    "-Dpentecost.home=$PentecostHome",
    "-Dpentecost.gameglue=$gameglue",
    "-Dpentecost.forgeglue=$forgeglue",
    '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util.jar=ALL-UNNAMED',
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util=ALL-UNNAMED',
    '--add-exports', 'java.base/sun.security.util=ALL-UNNAMED',
    '-XX:-DontCompileHugeMethods',
    '-cp', (Join-Path $lib '*')
)
$version = [ordered]@{
    id          = $VersionId
    inheritsFrom = '1.20.1'
    type        = 'release'
    time        = $now
    releaseTime = $now
    mainClass   = 'khi.pentecost.boot.PentecostMain'
    javaVersion = [ordered]@{ component = 'java-runtime-delta'; majorVersion = 21 }
    arguments   = [ordered]@{ game = @('launch', '--forge'); jvm = $jvm }
}
$vdir = Join-Path $MinecraftDir "versions\$VersionId"
New-Item -ItemType Directory -Force $vdir | Out-Null
$json = $version | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText((Join-Path $vdir "$VersionId.json"), $json, (New-Object Text.UTF8Encoding($false)))
Write-Host "  launcher version $VersionId written"
$backup = "$ProfilesFile.pentecost-backup"
if (-not (Test-Path $backup)) { Copy-Item $ProfilesFile $backup; Write-Host '  launcher_profiles.json backed up' }
$pair = Read-Profiles
$ser = $pair[0]; $data = $pair[1]
$profiles = $data['profiles']
if ($profiles.ContainsKey($ProfileKey)) {
    $entry = $profiles[$ProfileKey]
    Write-Host '  existing Pentecost profile updated (your memory settings are kept)'
} else {
    $gb = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    if ($gb -ge 24) { $heap = 8; $young = 4 } elseif ($gb -ge 16) { $heap = 6; $young = 3 } elseif ($gb -ge 12) { $heap = 5; $young = 2 }
    elseif ($gb -ge 8) { $heap = 4; $young = 2 } else { $heap = 3; $young = 1 }
    $entry = New-Dict
    $entry['name'] = 'Pentecost'
    $entry['type'] = 'custom'
    $entry['created'] = [string]$now
    $entry['javaArgs'] = [string]("-Xms${heap}G -Xmx${heap}G -Xmn${young}G -XX:-DontCompileHugeMethods")
    $png = Join-Path $PentecostHome 'pentecost_still_256.png'
    if ((Test-Path $png) -and ((Get-Item $png).Length -lt 80000)) {
        $entry['icon'] = 'data:image/png;base64,' + [Convert]::ToBase64String([IO.File]::ReadAllBytes($png))
    } else {
        $entry['icon'] = 'Furnace'
    }
    Write-Host "  profile added (this PC has about $gb GB of memory; the game gets ${heap} GB, change it in the profile's JVM arguments)"
}
$entry['lastVersionId'] = [string]$VersionId
$entry['gameDir'] = [string]$PentecostHome
$entry['lastUsed'] = [string]$now
$profiles[$ProfileKey] = $entry
Save-Profiles $ser $data
Write-Host ''
Write-Host 'Pentecost is installed.' -ForegroundColor Green
Write-Host '  1. Open the Minecraft Launcher and pick "Pentecost" next to the Play button.'
Write-Host '  2. Press Play. The first start downloads Minecraft and Forge (several hundred MB) and takes a few minutes.'
Write-Host "  3. Mods go in: $PentecostHome\run\mods"
Finish 0