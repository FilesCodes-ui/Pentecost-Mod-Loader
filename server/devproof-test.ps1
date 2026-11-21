$base = 'https://api.pentecost-modloader.workers.dev'
$owner = '808323d1022140ccb69bf8bcd0d16112'
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object Thumbprint -eq 'AC0AD520832AC163F9579F5545A97BE84F456DB7'
$c = Invoke-RestMethod "$base/dev/challenge"
function Int32BE([int]$n) { $b = [BitConverter]::GetBytes($n); [Array]::Reverse($b); $b }
$tag = [Text.Encoding]::ASCII.GetBytes("pentecost-dev-proof-v1`0")
$ch = [byte[]]::new(32); for ($i = 0; $i -lt 32; $i++) { $ch[$i] = [Convert]::ToByte($c.challenge.Substring($i * 2, 2), 16) }
$id = [byte[]]::new(16); for ($i = 0; $i -lt 16; $i++) { $id[$i] = [Convert]::ToByte($owner.Substring($i * 2, 2), 16) }
$p = [Text.Encoding]::UTF8.GetBytes('crash-report')
$msg = [byte[]]($tag + (Int32BE 32) + $ch + $id + (Int32BE $p.Length) + $p)
$sig = $cert.PrivateKey.SignData($msg, 'SHA256')
$body = @{ challenge = $c.challenge; ts = $c.ts; sig = $c.sig; proof = [Convert]::ToBase64String($sig) } | ConvertTo-Json -Compress
'first:  ' + (Invoke-WebRequest "$base/dev/crashes" -Method Post -Body $body -UseBasicParsing).Content
try { Invoke-WebRequest "$base/dev/crashes" -Method Post -Body $body -UseBasicParsing | Out-Null; 'replay: ACCEPTED (bad)' } catch { 'replay: refused (' + $_.Exception.Response.StatusCode.value__ + ')' }
