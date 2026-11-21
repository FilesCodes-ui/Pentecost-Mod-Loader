param([Parameter(Mandatory = $true)][string]$Out)
$ErrorActionPreference = 'Stop'
$subject = 'CN=Pentecost Backup Key, O=Kilo-Heat Industrialists'
$cer = Join-Path $PSScriptRoot 'dev-key-backup.cer'
if (Test-Path $cer) { throw "A backup key already exists ($cer). Delete it first only if you mean to replace it." }
if (Test-Path $Out) { throw "$Out already exists." }

$pw = Read-Host 'Choose a password for the backup key file' -AsSecureString
$pw2 = Read-Host 'Type it again' -AsSecureString
$a = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw))
$b = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw2))
if ($a -ne $b -or $a.Length -lt 12) { throw 'The passwords differ, or are shorter than 12 characters.' }

$cert = New-SelfSignedCertificate -Subject $subject -CertStoreLocation Cert:\CurrentUser\My `
    -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyUsage DigitalSignature `
    -KeyExportPolicy Exportable -Provider 'Microsoft Enhanced RSA and AES Cryptographic Provider' `
    -NotAfter (Get-Date).AddYears(25) -FriendlyName 'Pentecost Backup Key'
try {
    Export-PfxCertificate -Cert $cert -FilePath $Out -Password $pw | Out-Null
    [IO.File]::WriteAllBytes($cer, $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert))
} finally {
    Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -DeleteKey
}
Write-Host "Backup key written to $Out. Move it offline now."
Write-Host "Public half written to $cer. Rebuild the Manager so it carries it."
