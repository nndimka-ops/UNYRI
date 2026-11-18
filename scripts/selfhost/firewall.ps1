# This is the only elevated part of Windows installation; no configuration is read.
[CmdletBinding()]
param([switch]$Rooms)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'preflight-ports.ps1')
foreach ($rule in (Get-SelfhostFirewallRules ([bool]$Rooms))) {
    if (-not (Get-NetFirewallRule -Name $rule[0] -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name $rule[0] -DisplayName $rule[0] -Direction Inbound -Action Allow -Protocol $rule[1] -LocalPort ($rule[2] -split ',') -Profile Any | Out-Null
    }
}
