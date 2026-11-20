#!/usr/bin/env bash
set -euo pipefail
[[ ${1:-no} =~ ^(yes|no)$ ]] || exit 1
command -v upnpc >/dev/null && command -v timeout >/dev/null || exit 1
# Never overwrite an existing mapping; inspect the local IGD first.
listing=$(timeout 8s upnpc -l) || exit 1
lan=$(ip -4 route show default | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -n1)
[[ -n $lan ]] || lan=$(hostname -I | awk '{print $1}')
[[ $lan =~ ^[0-9.]+$ ]] || exit 1
failed=0
map_port() {
    local port=$1 proto=$2
    # Any existing mapping is left untouched, even if it belongs to this PC.
    if grep -Eq "${proto}[[:space:]]+${port}->" <<< "$listing"; then failed=1; return; fi
    timeout 8s upnpc -a "$lan" "$port" "$port" "$proto" 0 >/dev/null 2>&1 || failed=1
}
map_port 80 TCP; map_port 443 TCP; map_port 3478 TCP; map_port 3478 UDP
for port in $(seq 49152 49200); do map_port "$port" UDP; done
if [[ ${1:-no} == yes ]]; then map_port 17881 TCP; map_port 17882 UDP; fi
exit "$failed"
