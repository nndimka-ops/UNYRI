# Managed start for an existing installation; also suitable for a local OS task.
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
Test-SelfhostDocker
$settings = Read-SelfhostEnvironment
$env:COMPOSE_PROFILES = $settings.COMPOSE_PROFILES
Invoke-SelfhostCompose up -d --no-build --wait --wait-timeout 300
