$ErrorActionPreference = 'Stop'
$SelfhostRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$SelfhostEnv = Join-Path $SelfhostRoot '.env'
$SelfhostCompose = Join-Path $SelfhostRoot 'docker-compose.selfhost.yml'
$SelfhostFromSource = Test-Path -LiteralPath (Join-Path $SelfhostRoot '.selfhost-from-source') -PathType Leaf

function Invoke-SelfhostCompose {
    # Every installer-owned stack start (including backup recovery) prepares host
    # metadata. Compose up recreates backend when its environment has changed.
    # Best effort: address metadata must never stop the server from starting.
    if ($args.Count -and $args[0] -eq 'up') {
        try { Update-SelfhostAddresses }
        catch { Write-Warning 'Could not refresh the home-network address; the server starts with the last saved value.' }
    }
    $files = @('-f', $SelfhostCompose)
    if ($SelfhostFromSource) { $files += @('-f', (Join-Path $SelfhostRoot 'scripts/selfhost/compose.source.yml')) }
    & docker compose --project-name unyri-selfhost --env-file $SelfhostEnv @files @args
    if ($LASTEXITCODE -ne 0) { throw 'Self-host Compose command failed. Review the error above; do not share secrets.' }
}

function Test-SelfhostLanAddress([string]$Address, [string]$Subnet = '172.30.219.0/24') {
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($Address, [ref]$ip) -or $ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { return $false }
    $b = $ip.GetAddressBytes()
    if (-not ($b[0] -eq 10 -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or ($b[0] -eq 192 -and $b[1] -eq 168))) { return $false }
    foreach ($network in @('172.17.0.0/16', $Subnet)) {
        if ($network -notmatch '^([^/]+)/([0-9]+)$') { continue }
        $net = $null; $bits = [int]$matches[2]
        if ($bits -lt 1 -or $bits -gt 32 -or -not [Net.IPAddress]::TryParse($matches[1], [ref]$net) -or $net.AddressFamily -ne $ip.AddressFamily) { continue }
        $n = $net.GetAddressBytes(); $same = $true
        for ($i = 0; $i -lt $bits; $i++) {
            $mask = 1 -shl (7 - ($i % 8)); $byte = [int][Math]::Floor($i / 8)
            if (($b[$byte] -band $mask) -ne ($n[$byte] -band $mask)) { $same = $false; break }
        }
        if ($same) { return $false }
    }
    return $true
}

function Get-SelfhostLanAddress([string]$Subnet = '172.30.219.0/24') {
    try {
        $candidates = @(foreach ($route in @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -PolicyStore ActiveStore)) {
            $adapter = Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction Stop
            # A virtual-only default (e.g. full-tunnel VPN) is not a home address.
            if (-not $adapter.HardwareInterface -or $adapter.Status -ne 'Up' -or
                ($adapter.Name + ' ' + $adapter.InterfaceDescription) -match 'vEthernet|WSL|Hyper-V|Tailscale|WireGuard|VPN|TAP|TUN|Docker|Virtual') { continue }
            $interface = Get-NetIPInterface -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -PolicyStore ActiveStore -ErrorAction Stop
            $ips = @(Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -ErrorAction Stop |
                Where-Object { $_.AddressState -eq 'Preferred' -and -not $_.SkipAsSource -and (Test-SelfhostLanAddress $_.IPAddress $Subnet) })
            # Do not invent an adapter/address choice when several are suitable.
            $address = if ($ips.Count -eq 1) { $ips[0].IPAddress } else { '' }
            [pscustomobject]@{ Address = $address; Metric = ([long]$route.RouteMetric + [long]$interface.InterfaceMetric) }
        })
        if (-not $candidates.Count) { return '' }
        $best = @($candidates | Sort-Object Metric)
        $equal = @($best | Where-Object { $_.Metric -eq $best[0].Metric } | Select-Object -ExpandProperty Address -Unique)
        if ($equal.Count -ne 1) { return '' }
        return $equal[0]
    } catch { return '' }
}

function Write-SelfhostAddressEnvironment([string]$Path, [System.Collections.IDictionary]$Values) {
    $keys = @('UNYRI_LAN_ADDRESS', 'UNYRI_LAN_PORT', 'UNYRI_LAN_DETECTED_AT', 'UNYRI_HOSTING_KIND', 'UNYRI_INGRESS_MODE', 'UNYRI_TUNNEL_PROVIDER')
    if (@($Values.Keys).Count -ne $keys.Count) { throw 'Expected exactly the six installer-owned address keys.' }
    foreach ($key in $keys) {
        if (-not $Values.Contains($key) -or [string]$Values[$key] -notmatch '^[A-Za-z0-9_.:/+-]*$') { throw 'Invalid installer address metadata.' }
    }
    $item = Get-Item -LiteralPath $Path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing an environment symlink.' }
    $lock = $null; $temporary = $Path + '.addresses-' + [guid]::NewGuid().ToString('N')
    try {
        # A stable lock file is deliberately retained; deleting it would let a
        # concurrent process lock a different inode/file. No secret is stored here.
        $lock = [IO.File]::Open($Path + '.addresses.lock', [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        Protect-SelfhostPath ($Path + '.addresses.lock')
        $encoding = [Text.Encoding]::GetEncoding(28591)
        $text = $encoding.GetString([IO.File]::ReadAllBytes($Path))
        $bom = ''
        if ($text.StartsWith($encoding.GetString([byte[]]@(239,187,191)))) { $bom = $text.Substring(0,3); $text = $text.Substring(3) }
        $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $pattern = '(?m)^(?:' + ($keys -join '|') + ')=[^\n]*(?:\n|$)'
        $unowned = [regex]::Replace($text, $pattern, '')
        # Prepending keeps even an unrelated final line without a newline intact.
        $prefix = ($keys | ForEach-Object { $_ + '=' + $Values[$_] + $newline }) -join ''
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Dispose()
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Path)
        [IO.File]::WriteAllBytes($temporary, $encoding.GetBytes($bom + $prefix + $unowned))
        # Windows PowerShell 5.1 marshals $null to an empty string for this
        # overload; NullString requests no backup path rather than an invalid one.
        [IO.File]::Replace($temporary, $Path, [NullString]::Value)
    } finally {
        if (Test-Path -LiteralPath $temporary) { [IO.File]::Delete($temporary) }
        if ($null -ne $lock) { $lock.Dispose() }
    }
}

function Update-SelfhostAddresses {
    $settings = Read-SelfhostEnvironment
    $subnet = if ($settings.UNYRI_SUBNET) { $settings.UNYRI_SUBNET } else { '172.30.219.0/24' }
    $lan = Get-SelfhostLanAddress $subnet
    $stamp = if ($lan) { [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'") } else { '' }
    Write-SelfhostAddressEnvironment $SelfhostEnv ([ordered]@{
        UNYRI_LAN_ADDRESS = $lan; UNYRI_LAN_PORT = $(if ($lan) { '443' } else { '' }); UNYRI_LAN_DETECTED_AT = $stamp
        UNYRI_HOSTING_KIND = $(if ($settings.ContainsKey('UNYRI_HOSTING_KIND')) { $settings.UNYRI_HOSTING_KIND } else { 'unknown' })
        UNYRI_INGRESS_MODE = $(if ($settings.ContainsKey('UNYRI_INGRESS_MODE')) { $settings.UNYRI_INGRESS_MODE } else { 'direct' })
        UNYRI_TUNNEL_PROVIDER = $(if ($settings.ContainsKey('UNYRI_TUNNEL_PROVIDER')) { $settings.UNYRI_TUNNEL_PROVIDER } else { '' })
    })
}

function Show-SelfhostServerVersion {
    $container = Invoke-SelfhostCompose ps --all --quiet backend
    if (-not $container) { Write-Host 'Server version: not installed' }
    else {
        & docker inspect --format 'Server image: {{.Config.Image}}; image ID: {{.Image}}' $container
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the installed server version.' }
    }
}

function Read-SelfhostEnvironment {
    if (-not (Test-Path -LiteralPath $SelfhostEnv -PathType Leaf)) { throw 'Run scripts/selfhost/install.ps1 first.' }
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines($SelfhostEnv)) {
        if ($line.StartsWith('UNYRI_SERVER_NAME=')) { $settings.UNYRI_SERVER_NAME = ConvertFrom-SelfhostServerName $line.Substring(18) }
        elseif ($line -match '^([A-Z][A-Z0-9_]*)=([A-Za-z0-9_./:@?=,+-]*)$') { $settings[$matches[1]] = $matches[2] }
        elseif ($line.Trim() -and -not $line.StartsWith('#')) { throw 'Unsupported .env syntax. Use installer-generated values and a quoted server name.' }
    }
    if ($settings['UNYRI_SELFHOST_FORMAT'] -ne '1') { throw 'Existing .env is not a self-host configuration. Use a separate release directory.' }
    return $settings
}

function Test-SelfhostServerName([string]$Name) {
    $length = [regex]::Matches($Name, '[\uD800-\uDBFF][\uDC00-\uDFFF]|[^\uD800-\uDFFF]').Count
    return ($length -ge 1 -and $length -le 64 -and $Name -notmatch '[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]')
}

function ConvertTo-SelfhostServerName([string]$Name) {
    return '"' + $Name.Replace('\', '\\').Replace('"', '\"').Replace('$', '$$') + '"'
}

function ConvertFrom-SelfhostServerName([string]$Value) {
    if ($Value.Length -lt 2 -or -not $Value.StartsWith('"') -or -not $Value.EndsWith('"')) { throw 'Invalid quoted server name.' }
    $result = ''
    for ($index = 1; $index -lt $Value.Length - 1; $index++) {
        $char = $Value[$index]
        if ($char -eq '\' -or $char -eq '$') {
            $index++
            if ($index -ge $Value.Length - 1) { throw 'Invalid server name escape.' }
            $next = $Value[$index]
            if (($char -eq '\' -and $next -ne '\' -and $next -ne '"') -or ($char -eq '$' -and $next -ne '$')) { throw 'Invalid server name escape.' }
            $char = $next
        } elseif ($char -eq '"') { throw 'Invalid quoted server name.' }
        $result += $char
    }
    if (-not (Test-SelfhostServerName $result)) { throw 'Server name must be 1-64 printable characters.' }
    return $result
}

function Test-SelfhostOldData {
    # Never pass embedded double quotes to a native command: Windows PowerShell 5.1
    # strips them, so '{{.Label "..."}}' reached Docker as '{{.Label ...}}' and every
    # fresh Windows install stopped here (18 Nov). A label filter needs no template.
    $labelled = @(& docker volume ls --filter 'label=com.docker.compose.project=unyri-selfhost' --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect old server data. No new .env was written; check Docker and rerun.' }
    $named = @(& docker volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect old server data. No new .env was written; check Docker and rerun.' }
    $volumes = @(@($labelled) + @($named | Where-Object { $_ -and $_.StartsWith('unyri-selfhost_') }) |
        Where-Object { $_ } | Sort-Object -Unique)
    if (-not $volumes.Count) { return $true }
    Write-Host 'Old server data was found. No new .env was written.'
    Write-Host 'To keep that data, put the old .env back into this folder from your backup, then rerun.'
    Write-Host 'To discard that data permanently, explicitly run the following commands, then rerun. Nothing is deleted automatically:'
    Write-Host 'docker ps -aq --filter label=com.docker.compose.project=unyri-selfhost | ForEach-Object { docker rm -f $_ }'
    foreach ($name in $volumes) { Write-Host ("docker volume rm -- '" + $name.Replace("'", "''") + "'") }
    return $false
}

function Show-SelfhostOwnerSetup {
    $available = Invoke-SelfhostCompose exec -T backend sh -c 'if test -s /app/data/owner-setup-code; then if test -d /app/data/.owner-setup-code-shown; then printf shown; else printf available; fi; fi'
    if ($available -eq 'shown') { Write-Host 'Owner code was already shown once; its private location is /app/data/owner-setup-code in backend_data.' }
    if ($available -eq 'available') {
        Write-Host 'Owner setup: open UNYRI on the phone -> sign in -> Profile -> Claim ownership.'
        if (-not [Console]::IsOutputRedirected) {
            # Direct console output avoids the PowerShell output/log pipeline.
            Invoke-SelfhostCompose exec -T backend sh -c 'if test -s /app/data/owner-setup-code && mkdir /app/data/.owner-setup-code-shown 2>/dev/null; then if ! cat /app/data/owner-setup-code; then rmdir /app/data/.owner-setup-code-shown; exit 1; fi; fi' | ForEach-Object { [Console]::WriteLine($_) }
        } else {
            Write-Host 'Private owner code: /app/data/owner-setup-code in backend_data. Run this installer in a private interactive terminal to display it once; redirected output never includes the code.'
        }
    } elseif ($available -ne 'shown') { Write-Host 'Owner setup code is absent (already claimed, or SH-2 is not in this image).' }
    if ($available -eq 'available' -or $available -eq 'shown') {
        Write-Host 'To see the owner code again privately while unclaimed, run in this release folder. Do not paste the code anywhere or record the terminal:'
        $files = '-f docker-compose.selfhost.yml'
        if ($SelfhostFromSource) { $files += ' -f scripts/selfhost/compose.source.yml' }
        $command = 'docker compose --project-name unyri-selfhost --env-file .env ' + $files + ' exec -T backend cat /app/data/owner-setup-code'
        Write-Host ('if (-not [Console]::IsOutputRedirected) { ' + $command + ' | ForEach-Object { [Console]::WriteLine($_) } } else { Write-Host ''Use a private interactive terminal.'' }')
    }
    Write-Host 'Fallback after registering in the app, from the release directory (replace USERNAME):'
    if ($SelfhostFromSource) {
        Write-Host 'docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml -f scripts/selfhost/compose.source.yml exec backend coco-admin grant-owner USERNAME'
    } else {
        Write-Host 'docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml exec backend coco-admin grant-owner USERNAME'
    }
}

function Protect-SelfhostPath([string]$Path) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $rights = if ((Get-Item -LiteralPath $Path).PSIsContainer) { '(OI)(CI)(F)' } else { '(F)' }
    & icacls $Path /inheritance:r /grant:r "${identity}:$rights" "*S-1-5-18:$rights" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict access. Use an NTFS directory owned by your account.' }
}

function Test-SelfhostDocker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Install Docker Desktop with WSL 2 yourself: https://docs.docker.com/desktop/setup/install/windows-install/ . Enable Linux containers and start Docker Desktop, then rerun.'
    }
    # Do not allow an accidental remote Docker context to change another machine.
    $endpoint = if ($env:DOCKER_CONTEXT) {
        (& docker context inspect $env:DOCKER_CONTEXT --format '{{.Endpoints.docker.Host}}' | Out-String).Trim()
    } else { $env:DOCKER_HOST }
    if (-not $endpoint) {
        $endpoint = (& docker context inspect --format '{{.Endpoints.docker.Host}}' | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the Docker context.' }
    }
    if ($endpoint -notmatch '^(npipe:|unix:)') { throw 'Select a local Docker Desktop context. Remote Docker endpoints are refused.' }
    $osType = (& docker info --format '{{.OSType}}' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $osType -ne 'linux') { throw 'Start Docker Desktop with its WSL 2/Linux container engine, then rerun.' }
    $versionText = (& docker compose version --short | Out-String).Trim().TrimStart('v')
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '^(\d+)\.(\d+)') { throw 'Docker Compose plugin 2.24 or newer is required.' }
    if ([int]$matches[1] -lt 2 -or ([int]$matches[1] -eq 2 -and [int]$matches[2] -lt 24)) { throw 'Update Docker Compose to 2.24 or newer.' }
}
