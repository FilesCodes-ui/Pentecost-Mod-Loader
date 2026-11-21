$ErrorActionPreference = 'Stop'
$all = @($args)
$PentecostHome = $PSScriptRoot
$runDir = Join-Path $PentecostHome 'run'
New-Item -ItemType Directory -Force (Join-Path $runDir 'logs') | Out-Null
$log = Join-Path $runDir 'logs\wrapper.log'
$outLog = Join-Path $runDir 'logs\game-output.log'
function Log([string]$m) { Add-Content -Path $log -Value ("{0:s} {1}" -f (Get-Date), $m) }

Log ("Wrapper started with {0} arguments. First: {1}" -f $all.Count, $(if ($all.Count -gt 0) { $all[0] } else { '(none)' }))
function Get-Arg([string]$name) {
    for ($i = 0; $i -lt $all.Count - 1; $i++) { if ($all[$i] -eq $name) { return $all[$i + 1] } }
    return $null
}
function Get-JavaMajor([string]$exe) {
    try {
        $out = (& $exe -version 2>&1 | Out-String)
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

$user = Get-Arg '--username'
$uuid = Get-Arg '--uuid'
$token = Get-Arg '--accessToken'
$type = Get-Arg '--userType'
if (-not $user -or -not $uuid -or -not $token -or $token -eq '0') {
    Log 'No Minecraft session in the arguments. Start the instance from the Modrinth App after signing in there.'
    Write-Host 'Pentecost wrapper: no signed-in session was passed. Sign in to the Modrinth App and start the instance from there.'
    exit 1
}
Log "Session found for $user"
function Find-Java {
    $direct = New-Object System.Collections.ArrayList
    if ($env:PENTECOST_JAVA) { [void]$direct.Add($env:PENTECOST_JAVA) }
    $javaFile = Join-Path $PentecostHome 'java.txt'
    if (Test-Path $javaFile) { $first = Get-Content $javaFile -TotalCount 1; if ($first) { [void]$direct.Add($first.Trim()) } }
    if ($all.Count -gt 0 -and (Test-Path -LiteralPath $all[0] -PathType Leaf -ErrorAction SilentlyContinue)) { [void]$direct.Add($all[0]) }
    if ($env:JAVA_HOME) { [void]$direct.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    foreach ($c in $direct) {
        if ($c -and (Test-Path -LiteralPath $c -PathType Leaf) -and (Get-JavaMajor $c) -ge 21) { return $c }
    }
    $mc = Split-Path -Parent $PentecostHome
    $roots = @(
        (Join-Path $mc 'runtime\java-runtime-delta'),
        (Join-Path $env:APPDATA 'ModrinthApp\meta\java_versions'),
        (Join-Path $env:USERPROFILE '.gradle\jdks'),
        (Join-Path $env:USERPROFILE '.jdks'),
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Java',
        'C:\Program Files\Microsoft'
    )
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        foreach ($f in Get-ChildItem $r -Recurse -Filter java.exe -ErrorAction SilentlyContinue) {
            if ((Get-JavaMajor $f.FullName) -ge 21) { return $f.FullName }
        }
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and (Get-JavaMajor $onPath.Source) -ge 21) { return $onPath.Source }
    return $null
}
$java = Find-Java
if (-not $java) {
    Log 'No Java 21 found.'
    Write-Host 'Pentecost wrapper: Java 21 was not found. Install Java 21 (for example Temurin 21), or put the path of its java.exe on the first line of java.txt next to this script.'
    exit 1
}
Log "Java: $java"
$flagFile = Join-Path $PentecostHome 'jvm-args.txt'
if (Test-Path $flagFile) {
    $mem = @(Get-Content $flagFile | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
} else {
    $gb = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    if ($gb -ge 24) { $heap = 8; $young = 4; $reserve = 2 } elseif ($gb -ge 16) { $heap = 6; $young = 3; $reserve = 2 } elseif ($gb -ge 12) { $heap = 5; $young = 2; $reserve = 2 }
    elseif ($gb -ge 8) { $heap = 4; $young = 2; $reserve = 1 } else { $heap = 3; $young = 1; $reserve = 0 }
    $reserve = [math]::Max(0, [math]::Min($reserve, $gb - 2 - $heap))
    $ceiling = $heap + $reserve
    $mem = @("-Xms${heap}G", "-Xmx${ceiling}G", "-Xmn${young}G")
}
$lib = Join-Path $PentecostHome 'lib'
$jar = Get-ChildItem $lib -Filter 'pentecost-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'gameglue|forgeglue' } | Select-Object -First 1
if (-not $jar) { Log 'lib is missing.'; Write-Host 'Pentecost wrapper: Pentecost is not installed here. Run the Pentecost installer first.'; exit 1 }
$version = $jar.BaseName -replace '^pentecost-', ''
$cp = Join-Path $lib '*'
$secure = Get-ChildItem (Join-Path $PentecostHome 'cache\forge-install\libraries\cpw\mods\securejarhandler') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name | Select-Object -Last 1
if ($secure) { $cp = $cp + ';' + (Join-Path $secure.FullName '*') }
$jvm = @($mem) + @(
    "-Dpentecost.home=$PentecostHome",
    "-Dpentecost.gameglue=$(Join-Path $lib "glue\pentecost-gameglue-$version.jar")",
    "-Dpentecost.forgeglue=$(Join-Path $lib "glue\pentecost-forgeglue-$version.jar")",
    '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util.jar=ALL-UNNAMED',
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '--add-opens', 'java.base/java.util=ALL-UNNAMED',
    '--add-exports', 'java.base/sun.security.util=ALL-UNNAMED',
    '-XX:-DontCompileHugeMethods',
    '-cp', $cp,
    'khi.pentecost.boot.PentecostMain', 'launch', '--forge',
    '--username', $user, '--uuid', $uuid, '--accessToken', $token
)
if ($type) { $jvm += @('--userType', $type) }
Log "Starting Pentecost $version for $user"
Set-Location $runDir
Set-Content -Path $outLog -Value ("{0:s} Pentecost {1} starting" -f (Get-Date), $version)
$ErrorActionPreference = 'Continue'
& $java @jvm 2>&1 | ForEach-Object {
    $line = "$_"
    Add-Content -Path $outLog -Value $line
    $line
}
$code = $LASTEXITCODE
Log "Pentecost ended with code $code"
exit $code