#!/usr/bin/env bash
set -euo pipefail
echo 'Local sockets: 80/443 TCP web; 3478 TCP+UDP TURN; 49152-49200 UDP relay; optional 17881 TCP/17882 UDP rooms.'
if command -v ss >/dev/null; then
    ss -lntu | awk 'NR==1 || $5 ~ /:(80|443|3478|17881|17882|4915[2-9]|491[6-9][0-9]|49200)$/'
else echo 'ss unavailable; use your system socket viewer (ss -lntu from iproute2).'; fi
echo 'Docker NAT may not appear as a userspace listener. UDP relay sockets may appear only during calls. Local sockets do not prove internet reachability.'
echo 'From a DIFFERENT network: curl --fail https://YOUR_DOMAIN/health'
echo 'On an outside Windows PC: Test-NetConnection YOUR_DOMAIN -Port 443 (repeat for 3478 and optional 17881).'
echo 'On a phone disable Wi-Fi, register, exchange a photo and call a second phone on a different network.'
echo 'UDP needs a real two-network call or an authenticated TURN allocation test; TCP checks cannot validate UDP relay/media.'
echo 'Never forward/publish 5432,6379,8080,7880,9000,9001. Check the cloud firewall as well as the router.'
