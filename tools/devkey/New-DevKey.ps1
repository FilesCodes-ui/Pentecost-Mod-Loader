$ErrorActionPreference = 'Stop'
$subject = 'CN=Pentecost Dev Key, O=Kilo-Heat Industrialists'
$out = Join-Path $PSScriptRoot 'dev-key.cer'

$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $subject } | Select-Object -First 1
if ($cert) {
    Write-Host "The key already exists ($($cert.Thumbprint)). Nothing was created."
} else {
    $cert = New-SelfSignedCertificate -Subject $subject -CertStoreLocation Cert:\CurrentUser\My `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyUsage DigitalSignature `
        -KeyExportPolicy NonExportable -Provider 'Microsoft Enhanced RSA and AES Cryptographic Provider' `
        -NotAfter (Get-Date).AddYears(25) -FriendlyName 'Pentecost Dev Key'
    Write-Host "Created the key ($($cert.Thumbprint))."
}
[IO.File]::WriteAllBytes($out, $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert))
Write-Host "Public half written to $out"
