# Creates a cold snapshot of this stack only, without binary shell redirection.
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
Test-SelfhostDocker
$settings = Read-SelfhostEnvironment
$env:COMPOSE_PROFILES = $settings.COMPOSE_PROFILES
$backup = Join-Path $SelfhostRoot ('selfhost-backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $backup -ErrorAction Stop | Out-Null
Protect-SelfhostPath $backup
# Obtain a missing helper before stopping anything; reuse it for offline backups.
$helperPresent = & docker image ls --quiet alpine:3.24.3
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the backup helper image.' }
if (-not $helperPresent) {
    & docker pull alpine:3.24.3
    if ($LASTEXITCODE -ne 0) { throw 'Backup helper pull failed. Stack was not stopped.' }
}
Invoke-SelfhostCompose stop
try {
    Copy-Item -LiteralPath $SelfhostEnv -Destination (Join-Path $backup '.env')
    foreach ($volume in @('postgres_data', 'redis_data', 'minio_data', 'caddy_data', 'caddy_config', 'backend_data')) {
        & docker volume inspect "unyri-selfhost_$volume" --format '{{.Name}}' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Missing volume $volume; refusing to create an empty backup." }
        & docker run --rm --network none --mount "type=volume,source=unyri-selfhost_$volume,target=/data,readonly" --mount "type=bind,source=$backup,target=/backup" alpine:3.24.3 tar -czf "/backup/$volume.tgz" -C /data .
        if ($LASTEXITCODE -ne 0) { throw "Backup failed for $volume. This snapshot is incomplete." }
        & docker run --rm --network none --mount "type=bind,source=$backup,target=/backup,readonly" alpine:3.24.3 tar -tzf "/backup/$volume.tgz" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Archive validation failed for $volume." }
    }
    [IO.File]::WriteAllText((Join-Path $backup 'COMPLETE'), 'All six cold volume archives and .env were saved.')
    Write-Host "Backup complete: $backup . Copy it and this exact source release to another disk. Test a restore."
} finally {
    Invoke-SelfhostCompose up -d --wait --wait-timeout 300
}
