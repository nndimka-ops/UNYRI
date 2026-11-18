# Run from a reviewed release in a normal Windows PowerShell terminal.
[CmdletBinding()]
param([Alias('from-source')][switch]$FromSource)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'preflight-ports.ps1')
$SelfhostFromSource = [bool]$FromSource
if ($SelfhostFromSource) {
    if (-not (Test-Path -LiteralPath (Join-Path $SelfhostRoot 'server/Dockerfile')) -or -not (Test-Path -LiteralPath (Join-Path $SelfhostRoot 'shared') -PathType Container)) {
        throw '--from-source needs a full source checkout, not the runtime bundle.'
    }
    Write-Host 'Target: local source build (Rust/Go compilation).'
} else {
    $versionFile = Join-Path $SelfhostRoot 'SELFHOST_VERSION'
    if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf)) { throw 'Download a self-host release bundle for published images, or use -FromSource in a full checkout.' }
    Write-Host ('Target bundle version: ' + [IO.File]::ReadAllText($versionFile).Trim())
}
Test-SelfhostDocker

function New-SelfhostSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant() }
    finally { $rng.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}

if (-not (Test-Path -LiteralPath $SelfhostEnv)) {
    if (-not (Test-SelfhostOldData)) { exit 1 }
    $domain = (Read-Host 'Domain or DDNS name (for example family.duckdns.org; no https://)').Trim().ToLowerInvariant()
    if ($domain.Length -gt 253 -or $domain -notmatch '^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$') { throw 'Enter an ASCII DNS name; use punycode for international names.' }
    $serverName = Read-Host 'Server name shown in the apps [UNYRI]'
    if ($serverName -eq '') { $serverName = 'UNYRI' }
    if (-not (Test-SelfhostServerName $serverName)) { throw 'Server name must be 1-64 printable characters, with no control characters.' }
    Write-Host 'A router WAN address in 100.64.0.0/10 means CGNAT. If router WAN differs from the public address, ask your provider about CGNAT/double NAT; forwarding alone may not work.'
    $publicIP = ''
    if ((Read-Host 'Detect automatically? It asks api.ipify.org over HTTPS, which learns your address [y/N]') -match '^(y|yes)$') {
        try {
            # No redirect following: the only address-discovery recipient is ipify.
            $publicIP = (Invoke-RestMethod 'https://api.ipify.org' -TimeoutSec 15 -MaximumRedirection 0).ToString().Trim()
            $detectedIP = $null
            if (-not [Net.IPAddress]::TryParse($publicIP, [ref]$detectedIP) -or $detectedIP.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Detection did not return an IPv4 address.' }
            $publicIP = $detectedIP.ToString()
            Write-Host "Detected public IPv4: $publicIP"
            if ((Read-Host 'Use this address? [y/N]') -notmatch '^(y|yes)$') { $publicIP = '' }
        } catch { $publicIP = ''; Write-Host 'Automatic detection failed; enter the address manually.' }
    }
    if (-not $publicIP) { $publicIP = (Read-Host 'Public IPv4 from your router WAN/VPS (not the PC LAN address)').Trim() }
    $parsedIP = $null
    if (-not [Net.IPAddress]::TryParse($publicIP, [ref]$parsedIP) -or $parsedIP.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'An IPv4 address is required for TURN.' }
    $rooms = (Read-Host 'Enable optional LiveKit rooms? [y/N]') -match '^(y|yes)$'
    $settings = [ordered]@{
        UNYRI_SELFHOST_FORMAT = '1'; UNYRI_DOMAIN = $domain; TURN_EXTERNAL_IP = $parsedIP.ToString()
        UNYRI_SERVER_NAME = (ConvertTo-SelfhostServerName $serverName)
        UNYRI_SUBNET = '172.30.219.0/24'; UNYRI_PROXY_IP = '172.30.219.10'; UNYRI_SFU_IP = '172.30.219.20'
        POSTGRES_PASSWORD = (New-SelfhostSecret); REDIS_PASSWORD = (New-SelfhostSecret)
        JWT_SECRET = (New-SelfhostSecret); COCO_ADMIN_KEY = (New-SelfhostSecret)
        MINIO_ROOT_USER = ('root' + (New-SelfhostSecret).Substring(0, 16)); MINIO_ROOT_PASSWORD = (New-SelfhostSecret)
        S3_UPLOAD_ACCESS_KEY_ID = ('upld' + (New-SelfhostSecret).Substring(0, 16)); S3_UPLOAD_SECRET_ACCESS_KEY = (New-SelfhostSecret)
        TURN_SHARED_SECRET = (New-SelfhostSecret)
        LIVEKIT_API_KEY = ('lk' + (New-SelfhostSecret).Substring(0, 16)); LIVEKIT_API_SECRET = (New-SelfhostSecret)
        COMPOSE_PROFILES = ''; CONFERENCE_LIVEKIT_URL = ''; CONFERENCE_LIVEKIT_API_URL = ''
        CONFERENCE_LIVEKIT_API_KEY = ''; CONFERENCE_LIVEKIT_API_SECRET = ''
    }
    if ($rooms) {
        $settings.COMPOSE_PROFILES = 'rooms'
        $settings.CONFERENCE_LIVEKIT_URL = "wss://$domain"
        $settings.CONFERENCE_LIVEKIT_API_URL = "http://$($settings.UNYRI_SFU_IP):7880"
        $settings.CONFERENCE_LIVEKIT_API_KEY = $settings.LIVEKIT_API_KEY
        $settings.CONFERENCE_LIVEKIT_API_SECRET = $settings.LIVEKIT_API_SECRET
    }
    # Create an empty, restricted file before writing any generated secret.
    $stream = [IO.File]::Open($SelfhostEnv, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $stream.Dispose()
    Protect-SelfhostPath $SelfhostEnv
    $lines = @('# Generated locally. Keep private; back up before updating.') + @($settings.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
    [IO.File]::WriteAllLines($SelfhostEnv, $lines, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Generated private .env. Its secrets were not printed.'
} else {
    Write-Host 'Update: keeping .env and all named volumes. Back up before continuing.'
    $settings = Read-SelfhostEnvironment
    Protect-SelfhostPath $SelfhostEnv
}

$roomsEnabled = $settings.COMPOSE_PROFILES -eq 'rooms'
if (-not (Test-SelfhostPorts $roomsEnabled)) { exit 1 }
$rules = @(Get-SelfhostFirewallRules $roomsEnabled)
Write-Host 'Windows Firewall rules to request (only this step asks for elevation):'
foreach ($rule in $rules) { Write-Host (Format-SelfhostFirewallRule $rule) }
$firewallArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f (Join-Path $PSScriptRoot 'firewall.ps1')))
if ($roomsEnabled) { $firewallArguments += '-Rooms' }
try {
    $firewallProcess = Start-Process -FilePath powershell.exe -ArgumentList $firewallArguments -Verb RunAs -Wait -PassThru -WindowStyle Hidden
    if ($firewallProcess.ExitCode -ne 0) { throw 'Firewall helper did not complete.' }
} catch {
    Write-Host 'Firewall permission was declined or rules could not be applied. Installation continues. Run these lines manually in an Administrator PowerShell if needed:'
    foreach ($rule in $rules) { Write-Host (Format-SelfhostFirewallRule $rule) }
}

Write-Host 'UPnP will request: TCP 80,443,3478; UDP 3478,49152-49200.'
if ($roomsEnabled) { Write-Host 'UPnP will also request rooms: TCP 17881; UDP 17882.' }
if ((Read-Host 'Try UPnP on your local router to forward these ports? [y/N]') -match '^(y|yes)$') {
    try {
        $nat = New-Object -ComObject HNetCfg.NATUPnP
        $mappings = $nat.StaticPortMappingCollection
        if ($null -ne $mappings) {
            $lan = Get-SelfhostLanAddress $settings.UNYRI_SUBNET
            if (-not $lan) { throw 'No unambiguous physical LAN route.' }
            foreach ($rule in $rules) {
                $ports = if ($rule[2] -eq '49152-49200') { 49152..49200 } else { $rule[2] -split ',' | ForEach-Object { [int]$_ } }
                foreach ($port in $ports) {
                    try {
                        $existing = $mappings.Item($port, $rule[1])
                    } catch { $existing = $null }
                    if ($existing) {
                        if ($existing.InternalClient -ne $lan -or $existing.InternalPort -ne $port) {
                            Write-Warning "UPnP $port/$($rule[1]) is already mapped elsewhere; left unchanged."
                        } else { Write-Host "UPnP $port/$($rule[1]): already mapped to this PC." }
                    } else {
                        try {
                            $null = $mappings.Add($port, $rule[1], $port, $lan, $true, 'UNYRI selfhost')
                            Write-Host "UPnP $port/$($rule[1]): mapped."
                        } catch { Write-Warning "UPnP $port/$($rule[1]): failed; forward manually." }
                    }
                }
            }
        } else { Write-Host 'UPnP unavailable; use manual router forwarding in the guide.' }
    } catch { Write-Host 'UPnP unavailable; use manual router forwarding in the guide.' }
}

$env:COMPOSE_PROFILES = $settings.COMPOSE_PROFILES
Invoke-SelfhostCompose config --quiet
Write-Host 'Before update:'
Show-SelfhostServerVersion
Write-Host 'Target images (UNYRI_SERVER_IMAGE overrides the bundle default if set):'
Invoke-SelfhostCompose config --images
if ($SelfhostFromSource) {
    Invoke-SelfhostCompose pull --ignore-buildable
    Invoke-SelfhostCompose build --pull
} else {
    Invoke-SelfhostCompose pull
}
Invoke-SelfhostCompose up -d --no-build --wait --wait-timeout 300
$sourceMarker = Join-Path $SelfhostRoot '.selfhost-from-source'
if ($SelfhostFromSource) {
    [IO.File]::WriteAllText($sourceMarker, 'Local source build; pass -FromSource again for the next source update.')
} elseif (Test-Path -LiteralPath $sourceMarker) {
    Remove-Item -LiteralPath $sourceMarker
}
Write-Host 'After update:'
Show-SelfhostServerVersion
Invoke-SelfhostCompose exec -T backend curl --fail --silent http://127.0.0.1:8080/health
$address = 'https://' + $settings.UNYRI_DOMAIN
Write-Host "Server: $address"
Write-Host "Administration: $address/administration"
Show-SelfhostOwnerSetup
if (Get-Command qrencode -ErrorAction SilentlyContinue) { & qrencode -t ANSIUTF8 $address }
Write-Host "Address for copying or encoding as QR: $address"
Write-Host 'The QR contains an address. Select this server manually in the app; this is not an account/room invitation.'
Write-Host 'DDNS alone does not update TURN. If your public IPv4 changes, edit TURN_EXTERNAL_IP in .env and rerun. Test from mobile data.'
$publicReady = $false
for ($attempt = 0; $attempt -lt 36; $attempt++) {
    try {
        $health = Invoke-RestMethod "$address/health" -TimeoutSec 5
        if ($health.status -eq 'ok' -and $health.database -and $health.redis) { $publicReady = $true; break }
    } catch { }
    Start-Sleep -Seconds 5
}
if (-not $publicReady) {
    Write-Host 'Installed, not verified from outside (exit 3). Local stack is healthy. Many home routers cannot reach their own public address (NAT loopback); the real test is a phone on mobile data with Wi-Fi off. Check DNS, certificates and forwarding if that test fails. Stack and data were kept.'
    exit 3
}
Write-Host 'HTTPS health passed. Also test two phones on different networks; health does not test media or push.'
