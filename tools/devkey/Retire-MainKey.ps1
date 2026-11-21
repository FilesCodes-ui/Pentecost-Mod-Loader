param([Parameter(Mandatory = $true)][string]$Pfx)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot '..\..\server' | Resolve-Path
$feedDir = Join-Path $server 'public\feed'
$base = 'https://api.pentecost-modloader.workers.dev'

$pw = Read-Host 'Backup key password' -AsSecureString
$cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2($Pfx, $pw, 'EphemeralKeySet')

$live = (Invoke-WebRequest "$base/feed/feed.json?t=$([DateTime]::UtcNow.Ticks)" -UseBasicParsing).Content | ConvertFrom-Json
$live | Add-Member -NotePropertyName retirePrimary -NotePropertyValue $true -Force
$live.seq = [long]$live.seq + 1
$live.issued = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$json = [Text.Encoding]::UTF8.GetBytes(($live | ConvertTo-Json -Depth 50 -Compress))
$tag = [Text.Encoding]::ASCII.GetBytes("pentecost-feed-v1`0")
$rsa = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($cert)
$sig = $rsa.SignData([byte[]]($tag + $json), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
[IO.File]::WriteAllBytes((Join-Path $feedDir 'feed.json'), $json)
[IO.File]::WriteAllText((Join-Path $feedDir 'feed.sig'), (@{ key = 'backup'; sig = [Convert]::ToBase64String($sig) } | ConvertTo-Json -Compress))

Push-Location $server
try { node node_modules\wrangler\bin\wrangler.js deploy } finally { Pop-Location }
Write-Host "Feed $($live.seq) signed with the backup key is live. The main key is retired as each Manager fetches it."
