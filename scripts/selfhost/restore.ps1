# Restore only into empty, unused self-host volumes on a replacement/test machine.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$BackupPath)
. (Join-Path $PSScriptRoot 'common.ps1')
Test-SelfhostDocker
$null = Read-SelfhostEnvironment
$backup = (Resolve-Path -LiteralPath $BackupPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath (Join-Path $backup 'COMPLETE') -PathType Leaf)) { throw 'Incomplete backup: COMPLETE marker missing.' }
if ((Get-FileHash -LiteralPath $SelfhostEnv).Hash -ne (Get-FileHash -LiteralPath (Join-Path $backup '.env')).Hash) { throw 'Copy the backup .env to this fresh release directory first. Do not generate new secrets.' }
$volumes = @('postgres_data', 'redis_data', 'minio_data', 'caddy_data', 'caddy_config', 'backend_data')
$existing = @(& docker volume ls --format '{{.Name}}')
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect volumes.' }
foreach ($volume in $volumes) {
    if ("unyri-selfhost_$volume" -in $existing) { throw 'Destination volumes already exist. Restore requires a fresh Docker machine; no data was changed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $backup "$volume.tgz") -PathType Leaf)) { throw "Archive missing: $volume.tgz" }
}
$helperPresent = & docker image ls --quiet alpine:3.24.3
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the restore helper image.' }
if (-not $helperPresent) {
    & docker pull alpine:3.24.3
    if ($LASTEXITCODE -ne 0) { throw 'Cannot obtain restore helper.' }
}
foreach ($volume in $volumes) {
    & docker run --rm --network none --mount "type=bind,source=$backup,target=/backup,readonly" alpine:3.24.3 tar -tzf "/backup/$volume.tgz" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Invalid archive: $volume.tgz . No destination volumes were created." }
}
foreach ($volume in $volumes) {
    $helper = Join-Path $PSScriptRoot 'restore-volume.sh'
    & docker run --rm --network none --mount "type=volume,source=unyri-selfhost_$volume,target=/data" --mount "type=bind,source=$backup,target=/backup,readonly" --mount "type=bind,source=$helper,target=/restore-volume.sh,readonly" alpine:3.24.3 sh /restore-volume.sh $volume
    if ($LASTEXITCODE -ne 0) { throw "Restore failed for $volume. Do not start the stack; preserve these partial volumes for review." }
}
Protect-SelfhostPath $SelfhostEnv
Write-Host 'All six volumes restored. Use the same source release first, adjust the public IPv4 if moving, then run install.ps1. Do not run the old and replacement servers at the same time.'
