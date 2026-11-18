# Fixture-only; never reads an installation .env or starts Docker.
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
$fixtureRoot = Join-Path $PSScriptRoot ('.address-fixture-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    $values = [ordered]@{
        UNYRI_LAN_ADDRESS = '192.168.50.10'; UNYRI_LAN_PORT = '443'; UNYRI_LAN_DETECTED_AT = '2026-11-18T12:00:00Z'
        UNYRI_HOSTING_KIND = 'home_pc'; UNYRI_INGRESS_MODE = 'direct'; UNYRI_TUNNEL_PROVIDER = ''
    }
    $utf8 = New-Object Text.UTF8Encoding($false)
    foreach ($newline in @("`n", "`r`n")) {
        foreach ($withBom in @($false, $true)) {
            $path = Join-Path $fixtureRoot 'fixture.txt'
            # Deliberately unusual spacing/Unicode, duplicates and no last newline.
            $unowned = @('# Synthetic fixture only', 'JWT_SECRET=fixture-only-unchanged', ('CUSTOM="a $$ b # ' + [char]0x42e + '"'), '', 'FINAL=no-newline') -join $newline
            $inputText = 'UNYRI_LAN_ADDRESS=172.30.219.10' + $newline +
                $unowned.Replace('CUSTOM=', ('UNYRI_LAN_ADDRESS=10.0.0.2' + $newline + 'UNYRI_INGRESS_MODE=old' + $newline + 'CUSTOM='))
            $bomBytes = if ($withBom) { [byte[]]@(239,187,191) } else { [byte[]]@() }
            [IO.File]::WriteAllBytes($path, [byte[]]($bomBytes + $utf8.GetBytes($inputText)))
            Protect-SelfhostPath $path
            $acl = (Get-Acl -LiteralPath $path).Sddl
            Write-SelfhostAddressEnvironment $path $values
            $expectedPrefix = 'UNYRI_LAN_ADDRESS=192.168.50.10' + $newline + 'UNYRI_LAN_PORT=443' + $newline +
                'UNYRI_LAN_DETECTED_AT=2026-11-18T12:00:00Z' + $newline + 'UNYRI_HOSTING_KIND=home_pc' + $newline +
                'UNYRI_INGRESS_MODE=direct' + $newline + 'UNYRI_TUNNEL_PROVIDER=' + $newline
            $expected = [byte[]]($bomBytes + $utf8.GetBytes($expectedPrefix + $unowned))
            $actual = [IO.File]::ReadAllBytes($path)
            if ([Convert]::ToBase64String($actual) -cne [Convert]::ToBase64String($expected)) { throw 'Fixture bytes changed outside the six owned keys.' }
            if ((Get-Acl -LiteralPath $path).Sddl -cne $acl) { throw 'Environment ACL changed.' }
            Write-SelfhostAddressEnvironment $path $values
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) -cne [Convert]::ToBase64String($expected)) { throw 'Writer is not idempotent.' }
            $held = [IO.File]::Open($path + '.addresses.lock', 'Open', 'ReadWrite', 'None')
            $refused = $false
            try { Write-SelfhostAddressEnvironment $path $values } catch { $refused = $true } finally { $held.Dispose() }
            if (-not $refused) { throw 'Concurrent writer was not refused.' }
            # A failed detection must clear the previous snapshot, without changing other keys.
            $empty = [ordered]@{}
            foreach ($key in $values.Keys) { $empty[$key] = $values[$key] }
            $empty.UNYRI_LAN_ADDRESS = ''; $empty.UNYRI_LAN_PORT = ''; $empty.UNYRI_LAN_DETECTED_AT = ''
            Write-SelfhostAddressEnvironment $path $empty
            if (-not [IO.File]::ReadAllText($path).Contains('UNYRI_LAN_ADDRESS=' + $newline)) { throw 'Old LAN address was retained.' }
            if (@(Get-ChildItem -LiteralPath $fixtureRoot -Filter '*.addresses-*').Count) { throw 'Temporary file leaked.' }
        }
    }
    # Mock the real NetTCPIP/NetAdapter API shapes; never inspect host networking.
    function Get-NetRoute { param($AddressFamily, $DestinationPrefix, $PolicyStore) $script:fixtureRoutes }
    function Get-NetAdapter { param($InterfaceIndex, $ErrorAction) $script:fixtureAdapters[$InterfaceIndex] }
    function Get-NetIPInterface { param($AddressFamily, $InterfaceIndex, $PolicyStore, $ErrorAction) [pscustomobject]@{ InterfaceMetric = $script:fixtureMetrics[$InterfaceIndex] } }
    function Get-NetIPAddress { param($AddressFamily, $InterfaceIndex, $ErrorAction) $script:fixtureIPs[$InterfaceIndex] }
    $script:fixtureRoutes = @([pscustomobject]@{ InterfaceIndex = 1; RouteMetric = 1 }, [pscustomobject]@{ InterfaceIndex = 2; RouteMetric = 10 }, [pscustomobject]@{ InterfaceIndex = 3; RouteMetric = 0 })
    $script:fixtureAdapters = @{
        1 = [pscustomobject]@{ HardwareInterface = $true; Status = 'Up'; Name = 'Ethernet'; InterfaceDescription = 'Physical adapter' }
        2 = [pscustomobject]@{ HardwareInterface = $true; Status = 'Up'; Name = 'Wi-Fi'; InterfaceDescription = 'Physical adapter' }
        3 = [pscustomobject]@{ HardwareInterface = $false; Status = 'Up'; Name = 'Tailscale'; InterfaceDescription = 'Virtual adapter' }
    }
    $script:fixtureMetrics = @{ 1 = 99; 2 = 10; 3 = 0 }
    $script:fixtureIPs = @{
        1 = @([pscustomobject]@{ AddressState = 'Preferred'; SkipAsSource = $false; IPAddress = '192.168.1.10' })
        2 = @([pscustomobject]@{ AddressState = 'Preferred'; SkipAsSource = $false; IPAddress = '192.168.2.10' })
    }
    if ((Get-SelfhostLanAddress) -ne '192.168.2.10') { throw 'Route/interface metrics or virtual-adapter exclusion failed.' }
    $script:fixtureMetrics[1] = 19
    if ((Get-SelfhostLanAddress) -ne '') { throw 'Equal-metric physical routes must be unknown.' }
    $script:fixtureMetrics[1] = 99
    $script:fixtureIPs[2] += [pscustomobject]@{ AddressState = 'Preferred'; SkipAsSource = $false; IPAddress = '192.168.2.11' }
    if ((Get-SelfhostLanAddress) -ne '') { throw 'Ambiguous addresses on the best interface must be unknown.' }
    $script:fixtureRoutes = @($script:fixtureRoutes[2])
    if ((Get-SelfhostLanAddress) -ne '') { throw 'A virtual-only route must be unknown.' }

    # Exercise the managed-up hook with a fake Docker command and fixture path.
    $SelfhostEnv = Join-Path $fixtureRoot 'managed.txt'
    [IO.File]::WriteAllText($SelfhostEnv, "UNYRI_SELFHOST_FORMAT=1`nUNYRI_SUBNET=172.30.219.0/24`nJWT_SECRET=fixture-only`nUNYRI_HOSTING_KIND=vps`nUNYRI_INGRESS_MODE=tunnel`nUNYRI_TUNNEL_PROVIDER=cloudflare`n", $utf8)
    Protect-SelfhostPath $SelfhostEnv
    $script:fixtureLan = '192.168.3.10'; $script:detections = 0; $script:dockerCalls = 0
    function Get-SelfhostLanAddress { param($Subnet) $script:detections++; $script:fixtureLan }
    function docker { $script:dockerCalls++; $global:LASTEXITCODE = 0 }
    Invoke-SelfhostCompose config --quiet
    Invoke-SelfhostCompose up -d --no-build
    $prepared = Read-SelfhostEnvironment
    if ($prepared.UNYRI_LAN_ADDRESS -ne '192.168.3.10' -or $prepared.UNYRI_LAN_PORT -ne '443' -or $prepared.UNYRI_HOSTING_KIND -ne 'vps' -or $prepared.UNYRI_TUNNEL_PROVIDER -ne 'cloudflare') { throw 'Managed start did not prepare/preserve owned metadata.' }
    $script:fixtureLan = ''
    Invoke-SelfhostCompose up -d --no-build
    $prepared = Read-SelfhostEnvironment
    if ($prepared.UNYRI_LAN_ADDRESS -or $prepared.UNYRI_LAN_PORT -or $prepared.UNYRI_LAN_DETECTED_AT) { throw 'Failed detection retained an old snapshot.' }
    if ($script:detections -ne 2 -or $script:dockerCalls -ne 3) { throw 'Only managed up should prepare addresses.' }
    Write-Host 'PASS: env bytes/ACL/lock/idempotence/clearing; route metrics/virtual NICs/ambiguity; managed-up preparation (all fixtures).'
} finally {
    # Remove only this verified, newly created fixture directory.
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $parent = [IO.Path]::GetFullPath($PSScriptRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($parent) -or -not ([IO.Path]::GetFileName($resolved)).StartsWith('.address-fixture-')) { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
