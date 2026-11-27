# Query the running game's live probe. Usage:
#   .\tools\probe.ps1 status
#   .\tools\probe.ps1 sample "ms=5000&thread=Render"
#   .\tools\probe.ps1 game/frame
#   .\tools\probe.ps1 log "file=run/logs/latest.log&grep=Lamb&lines=50"
param([string]$Endpoint = "", [string]$Query = "")
$info = Get-Content (Join-Path $PSScriptRoot "..\run\pentecost-probe.json") -Raw | ConvertFrom-Json
$url = $info.url.TrimEnd('/') + "/" + $Endpoint.TrimStart('/') + "?token=" + $info.token
if ($Query) { $url += "&" + $Query }
(Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 60).Content
