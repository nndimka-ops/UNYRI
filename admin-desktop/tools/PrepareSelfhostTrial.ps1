# Prepare reviewed working-tree files only. No Docker, network, firewall or router calls.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ServerImage,
    [Parameter(Mandatory=$true)][string]$MinioImage,
    [Parameter(Mandatory=$true)][string]$McImage,
    [string]$Destination='')
$ErrorActionPreference='Stop'
foreach ($image in @($ServerImage,$MinioImage,$McImage)) {
    if ($image -notmatch '^[A-Za-z0-9][A-Za-z0-9_./:@-]{1,239}$') { throw 'Supply reviewed image references already available to Docker.' }
}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $Destination) { $Destination=Join-Path $repo ('admin-desktop/build/selfhost-trial-'+(Get-Date -Format 'yyyyMMdd-HHmmss')) }
$target=[IO.Path]::GetFullPath($Destination)
$allowed=[IO.Path]::GetFullPath((Join-Path $repo 'admin-desktop/build'))+[IO.Path]::DirectorySeparatorChar
if (-not $target.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Trial destination must stay below admin-desktop/build.' }
if (Test-Path -LiteralPath $target) { throw 'Destination exists; choose a new folder.' }
New-Item -ItemType Directory -Path (Join-Path $target 'scripts/selfhost') -Force | Out-Null
$utf8=New-Object Text.UTF8Encoding($false)
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repo 'scripts/selfhost') -File) {
    # Exact extension/name allowlist; no environment or private files are opened.
    if ($file.Name -notmatch '\.(ps1|sh|json|yml|md)$|^(Caddyfile|storage.Dockerfile|\.dockerignore)$') { continue }
    $text=[IO.File]::ReadAllText($file.FullName).Replace("`r`n","`n")
    [IO.File]::WriteAllText((Join-Path $target ('scripts/selfhost/'+$file.Name)),$text,$utf8)
}
$compose=[IO.File]::ReadAllText((Join-Path $repo 'docker-compose.selfhost.yml')).Replace("`r`n","`n")
$compose=$compose.Replace('ghcr.io/OWNER/unyri-server:VERSION',$ServerImage).Replace('ghcr.io/OWNER/unyri-minio:VERSION',$MinioImage).Replace('ghcr.io/OWNER/unyri-mc:VERSION',$McImage)
$compose=$compose.Replace('"80:80/tcp"','"127.0.0.1:18080:80/tcp"').Replace('"443:443/tcp"','"127.0.0.1:18443:443/tcp"')
$compose=$compose.Replace('"3478:3478/udp"','"127.0.0.1:13478:3478/udp"').Replace('"3478:3478/tcp"','"127.0.0.1:13478:3478/tcp"')
$compose=$compose.Replace('"49152-49200:49152-49200/udp"','"127.0.0.1:59152-59200:49152-49200/udp"')
$compose=$compose.Replace('"17881:17881/tcp"','"127.0.0.1:17881:17881/tcp"').Replace('"17882:17882/udp"','"127.0.0.1:17882:17882/udp"')
$compose=$compose.Replace('https://${UNYRI_DOMAIN:?Set the domain}','https://${UNYRI_DOMAIN:?Set the domain}:18443')
[IO.File]::WriteAllText((Join-Path $target 'docker-compose.selfhost.yml'),$compose,$utf8)
$caddyPath=Join-Path $target 'scripts/selfhost/Caddyfile'
$caddy=[IO.File]::ReadAllText($caddyPath)
$caddy=[regex]::Replace($caddy,'(?s)    tls \{\s*issuer acme \{.*?\n        \}\s*\n    \}','    tls internal')
if ($caddy -match 'issuer acme') { throw 'Trial failed to disable ACME.' }
[IO.File]::WriteAllText($caddyPath,$caddy,$utf8)
[IO.File]::WriteAllText((Join-Path $target 'SELFHOST_VERSION'),"local-trial`n",$utf8)
[IO.File]::WriteAllText((Join-Path $target '.selfhost-local-trial'),'Loopback only; no firewall/router; no public verification.',$utf8)
Write-Output "Prepared loopback trial: $target"
Write-Output 'Select it in the wizard. Keep public-IP detection, DuckDNS and UPnP off. Rooms/firewall are disabled.'
