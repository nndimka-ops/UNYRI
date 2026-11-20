# -Check executes the installer preflight; dot-sourcing remains supported.
[CmdletBinding()]
param([switch]$Check, [switch]$Rooms, [switch]$LocalTrial)
function Get-SelfhostPorts([bool]$Rooms, [bool]$LocalTrial = $false) {
    if ($LocalTrial) { return @(@{ Port = 18080; Protocol = 'TCP' }, @{ Port = 18443; Protocol = 'TCP' }, @{ Port = 13478; Protocol = 'TCP' }, @{ Port = 13478; Protocol = 'UDP' }) + @(59152..59200 | ForEach-Object { @{ Port = $_; Protocol = 'UDP' } }) }
    $ports = @(
        @{ Port = 80; Protocol = 'TCP' }, @{ Port = 443; Protocol = 'TCP' },
        @{ Port = 3478; Protocol = 'TCP' }, @{ Port = 3478; Protocol = 'UDP' }
    )
    $ports += @(49152..49200 | ForEach-Object { @{ Port = $_; Protocol = 'UDP' } })
    if ($Rooms) { $ports += @(@{ Port = 17881; Protocol = 'TCP' }, @{ Port = 17882; Protocol = 'UDP' }) }
    return $ports
}

function Format-SelfhostPortConflicts([hashtable]$Conflicts, [switch]$Records) {
    foreach ($owner in @($Conflicts.Values | Sort-Object -Unique)) {
        $kind = if ($owner.StartsWith('Docker container ')) { 'container' } elseif ($owner -eq 'unknown program') { 'unknown' } else { 'process' }
        $holder = if ($kind -eq 'container') { $owner.Substring('Docker container '.Length) } elseif ($kind -eq 'unknown') { 'unknown' } else { $owner }
        # Only bounded identity fields cross into the desktop UI, never raw output.
        $holder = ($holder -replace '[\x00-\x1f\x7f|]', ' ').Trim()
        if ($holder.Length -gt 120) { $holder = $holder.Substring(0, 120) }
        $ranges = @()
        foreach ($protocol in @('TCP', 'UDP')) {
            $protocolRanges = @()
            $ports = @($Conflicts.Keys | Where-Object { $Conflicts[$_] -eq $owner -and $_ -match "/$protocol$" } | ForEach-Object { [int]($_ -split '/')[0] } | Sort-Object -Unique)
            $first = $null; $last = $null
            foreach ($port in $ports) {
                if ($null -ne $first -and $port -eq $last + 1) { $last = $port; continue }
                if ($null -ne $first) {
                    $span = if ($first -eq $last) { "$first" } else { "$first-$last" }
                    $protocolRanges += $span
                }
                $first = $last = $port
            }
            if ($null -ne $first) {
                $span = if ($first -eq $last) { "$first" } else { "$first-$last" }
                $protocolRanges += $span
            }
            $ranges += @($protocolRanges | ForEach-Object { "$_ $protocol" })
            if ($Records -and $protocolRanges.Count -gt 0) { "UNYRI_PORT_BUSY:$protocol|$($protocolRanges -join ',')|$kind|$holder" }
        }
        if (-not $Records) {
            $description = if ($kind -eq 'container') { "Docker container $holder" } elseif ($kind -eq 'unknown') { 'a program whose name could not be found' } else { "program $holder" }
            'Ports ' + ($ranges -join ', ') + " are already used by $description. The server cannot start and calls cannot work while these ports are busy. Stop that program, then check again. This installer cannot choose other ports."
        }
    }
}

function Test-SelfhostPorts([bool]$Rooms, [bool]$LocalTrial = $false, [bool]$Records = $false) {
    $wanted = @{}; $ours = @{}; $conflicts = @{}
    foreach ($entry in (Get-SelfhostPorts $Rooms $LocalTrial)) { $wanted["$($entry.Port)/$($entry.Protocol)"] = $true }
    # No embedded double quotes in native arguments (Windows PowerShell 5.1 strips
    # them): read all labels and pick the compose project here.
    $containers = @(& docker ps --format '{{.ID}}|{{.Names}}|{{.Labels}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker published ports. Nothing was started.' }
    foreach ($line in $containers) {
        if (-not $line) { continue }
        $parts = $line -split '\|', 3
        $project = ''
        if ($parts.Count -ge 3 -and $parts[2] -match '(?:^|,)com\.docker\.compose\.project=([^,]*)') { $project = $Matches[1] }
        $identity = @($parts[0], $project, $parts[1])
        $bindings = & docker inspect --format '{{json .NetworkSettings.Ports}}' $identity[0]
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker port bindings. Nothing was started.' }
        $published = $bindings | ConvertFrom-Json
        if ($null -eq $published) { continue }
        foreach ($property in $published.PSObject.Properties) {
            $protocol = ($property.Name -split '/')[-1].ToUpperInvariant()
            foreach ($binding in $property.Value) {
                $key = "$($binding.HostPort)/$protocol"
                if (-not $wanted.ContainsKey($key)) { continue }
                if ($identity[1] -eq 'unyri-selfhost') { $ours[$key] = $true }
                else { $conflicts[$key] = "Docker container $($identity[2])" }
            }
        }
    }
    # Docker Desktop's forwarding process name is not an ownership signal.
    # Only the project's published ports above exempt a socket on reruns.
    $tcp = @(Get-NetTCPConnection -ErrorAction Stop | Where-Object State -eq Listen)
    $udp = @(Get-NetUDPEndpoint -ErrorAction Stop)
    foreach ($protocol in @('TCP', 'UDP')) {
        $sockets = if ($protocol -eq 'TCP') { $tcp } else { $udp }
        foreach ($socket in $sockets) {
            $key = "$($socket.LocalPort)/$protocol"
            if (-not $wanted.ContainsKey($key) -or $ours.ContainsKey($key)) { continue }
            # Docker identifies the actual owner; Windows forwarding processes
            # shared by containers must not split its diagnostic into groups.
            if ($conflicts.ContainsKey($key) -and $conflicts[$key].StartsWith('Docker container ')) { continue }
            $program = 'unknown program'
            $process = Get-Process -Id $socket.OwningProcess -ErrorAction SilentlyContinue
            if ($process) { $program = $process.ProcessName }
            if ($conflicts.ContainsKey($key)) { $program = ((@($conflicts[$key] -split '; ') + $program) | Sort-Object -Unique) -join '; ' }
            $conflicts[$key] = $program
        }
    }
    # Put bounded identities first for the desktop's bounded output reader.
    # Terminal installers retain human-only output.
    if ($Records) { foreach ($record in (Format-SelfhostPortConflicts $conflicts -Records)) { Write-Host $record } }
    foreach ($message in (Format-SelfhostPortConflicts $conflicts)) { Write-Host $message }
    return ($conflicts.Count -eq 0)
}

function Get-SelfhostFirewallRules([bool]$Rooms) {
    $rules = @(
        @('UNYRI-Selfhost-Web', 'TCP', '80,443'),
        @('UNYRI-Selfhost-TURN-TCP', 'TCP', '3478'),
        @('UNYRI-Selfhost-TURN-UDP', 'UDP', '3478'),
        @('UNYRI-Selfhost-TURN-Relay', 'UDP', '49152-49200')
    )
    if ($Rooms) {
        $rules += ,@('UNYRI-Selfhost-Rooms-TCP', 'TCP', '17881')
        $rules += ,@('UNYRI-Selfhost-Rooms-UDP', 'UDP', '17882')
    }
    return $rules
}

function Format-SelfhostFirewallRule($Rule) {
    return "New-NetFirewallRule -Name '$($Rule[0])' -DisplayName '$($Rule[0])' -Direction Inbound -Action Allow -Protocol $($Rule[1]) -LocalPort $($Rule[2]) -Profile Any"
}

if ($Check) { if (Test-SelfhostPorts ([bool]$Rooms) ([bool]$LocalTrial) $true) { exit 0 } else { exit 1 } }
