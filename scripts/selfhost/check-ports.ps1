[CmdletBinding()]
param([string]$Domain)
$tcp = @(80, 443, 3478, 17881)
$udp = @(3478, 17882) + @(49152..49200)
Write-Host 'Local TCP listeners (17881/17882 are needed only with rooms):'
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $tcp | Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table
Write-Host 'Local UDP bindings (relay ports may appear only during calls):'
Get-NetUDPEndpoint -ErrorAction SilentlyContinue | Where-Object LocalPort -in $udp | Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table
Write-Host 'Docker Desktop may forward UDP without a visible Windows socket. A local listener does not prove internet reachability.'
if ($Domain) { Write-Host "From a DIFFERENT network: curl.exe --fail https://$Domain/health" }
Write-Host 'On a phone disable Wi-Fi, open https://YOUR_DOMAIN/health, register, send a photo and call a second phone on another network.'
Write-Host 'On an outside Windows PC: Test-NetConnection YOUR_DOMAIN -Port 443 (repeat for 3478 and optional 17881).'
Write-Host 'For UDP/TURN allocations use a real two-network call or an authenticated TURN tester. TCP checks and web port-check sites cannot validate UDP relay/media.'
Write-Host 'Forward TCP 80,443,3478; UDP 3478,49152-49200; optional rooms TCP 17881 and UDP 17882. Never forward 5432,6379,8080,7880,9000,9001.'
