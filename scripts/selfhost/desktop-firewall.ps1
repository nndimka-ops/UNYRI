# Only launches the canonical elevation-only helper. No rule definitions here.
param([switch]$Rooms)
$ErrorActionPreference = 'Stop'
$arguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f (Join-Path $PSScriptRoot 'firewall.ps1')))
if ($Rooms) { $arguments += '-Rooms' }
try {
    $child = Start-Process powershell.exe -ArgumentList $arguments -Verb RunAs -Wait -PassThru -WindowStyle Hidden
    exit $child.ExitCode
} catch { exit 1 }
