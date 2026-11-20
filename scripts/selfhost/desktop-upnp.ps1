param([ValidateSet('yes','no')][string]$RoomsChoice='no')
$ErrorActionPreference='Stop'
try {
    $nat=New-Object -ComObject HNetCfg.NATUPnP
    $m=$nat.StaticPortMappingCollection
    if ($null -eq $m) { exit 1 }
    $route=Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1
    $lan=(Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex | Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress
    $rules=@(@(80,'TCP'),@(443,'TCP'),@(3478,'TCP'),@(3478,'UDP'))
    foreach($port in 49152..49200) { $rules+=,@($port,'UDP') }
    if($RoomsChoice -eq 'yes') { $rules+=,@(17881,'TCP'); $rules+=,@(17882,'UDP') }
    $failed=$false
    foreach($r in $rules) {
        try { $old=$m.Item($r[0],$r[1]) } catch { $old=$null }
        if($old) { if($old.InternalClient -ne $lan -or $old.InternalPort -ne $r[0]) { $failed=$true }; continue }
        try { $null=$m.Add($r[0],$r[1],$r[0],$lan,$true,'UNYRI selfhost') } catch { $failed=$true }
    }
    if($failed) { exit 1 }
} catch { exit 1 }
