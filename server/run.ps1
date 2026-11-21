param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Args2)
Set-Location $PSScriptRoot
$p = Start-Process node -ArgumentList (@('node_modules\wrangler\bin\wrangler.js') + $Args2) -RedirectStandardOutput wrangler-out.log -RedirectStandardError wrangler-err.log -NoNewWindow -Wait -PassThru
"exit=$($p.ExitCode)"
Get-Content wrangler-out.log, wrangler-err.log | Where-Object { $_ -notmatch '^\s*$' }
