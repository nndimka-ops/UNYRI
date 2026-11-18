#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
source "$(dirname -- "${BASH_SOURCE[0]}")/preflight-ports.sh"
selfhost_from_source=false
if (( $# )); then
    [[ $# == 1 && $1 == --from-source ]] || { echo 'Usage: install.sh [--from-source]' >&2; exit 1; }
    selfhost_from_source=true
fi
if [[ $selfhost_from_source == true ]]; then
    [[ -f "$selfhost_root/server/Dockerfile" && -d "$selfhost_root/shared" ]] || { echo '--from-source needs a full source checkout, not the runtime bundle.' >&2; exit 1; }
    echo 'Target: local source build (Rust/Go compilation).'
else
    [[ -f "$selfhost_root/SELFHOST_VERSION" ]] || { echo 'Download a self-host release bundle for published images, or use --from-source in a full checkout.' >&2; exit 1; }
    printf 'Target bundle version: %s\n' "$(cat "$selfhost_root/SELFHOST_VERSION")"
fi
check_docker
command -v openssl >/dev/null || { echo 'Install openssl from your distribution package manager, then rerun.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'Install curl from your distribution package manager, then rerun.' >&2; exit 1; }
umask 077
if [[ ! -e "$selfhost_env" ]]; then
    check_selfhost_old_data || exit 1
    read -r -p 'Domain or DDNS name (family.duckdns.org; no https://): ' domain
    domain=${domain,,}
    [[ ${#domain} -le 253 && $domain =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || { echo 'Enter an ASCII DNS name (punycode for international names).' >&2; exit 1; }
    read -r -p 'Server name shown in the apps [UNYRI]: ' server_name
    server_name=${server_name:-UNYRI}
    valid_server_name "$server_name" || { echo 'Server name must be 1-64 printable characters, with no control characters.' >&2; exit 1; }
    echo 'A router WAN address in 100.64.0.0/10 means CGNAT. If router WAN differs from the public address, ask your provider about CGNAT/double NAT; forwarding alone may not work.'
    public_ip=
    read -r -p 'Detect automatically? It asks api.ipify.org over HTTPS, which learns your address [y/N] ' detect_ip
    if [[ $detect_ip =~ ^([yY]|[yY][eE][sS])$ ]]; then
        if public_ip=$(curl --fail --silent --show-error --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 15 https://api.ipify.org) && valid_public_ipv4 "$public_ip"; then
            printf 'Detected public IPv4: %s\n' "$public_ip"
            read -r -p 'Use this address? [y/N] ' confirm_ip
            [[ $confirm_ip =~ ^([yY]|[yY][eE][sS])$ ]] || public_ip=
        else public_ip=; echo 'Automatic detection failed; enter the address manually.'; fi
    fi
    if [[ -z $public_ip ]]; then read -r -p 'Public IPv4 from router WAN/VPS (not PC LAN address): ' public_ip; fi
    valid_public_ipv4 "$public_ip" || { echo 'Invalid IPv4; a dotted IPv4 address is required for TURN.' >&2; exit 1; }
    read -r -p 'Enable optional LiveKit rooms? [y/N] ' rooms
    lk_key="lk$(openssl rand -hex 8)"; lk_secret=$(openssl rand -hex 32)
    # Noclobber prevents replacing a concurrently created configuration.
    set -o noclobber
    {
        printf '%s\n' '# Generated locally. Keep private; back up before updating.' \
            'UNYRI_SELFHOST_FORMAT=1' "UNYRI_DOMAIN=$domain" "UNYRI_SERVER_NAME=$(quote_server_name "$server_name")" "TURN_EXTERNAL_IP=$public_ip" \
            'UNYRI_SUBNET=172.30.219.0/24' 'UNYRI_PROXY_IP=172.30.219.10' 'UNYRI_SFU_IP=172.30.219.20'
        for name in POSTGRES_PASSWORD REDIS_PASSWORD JWT_SECRET COCO_ADMIN_KEY MINIO_ROOT_PASSWORD S3_UPLOAD_SECRET_ACCESS_KEY TURN_SHARED_SECRET; do
            printf '%s=%s\n' "$name" "$(openssl rand -hex 32)"
        done
        printf '%s\n' "MINIO_ROOT_USER=root$(openssl rand -hex 8)" "S3_UPLOAD_ACCESS_KEY_ID=upld$(openssl rand -hex 8)" \
            "LIVEKIT_API_KEY=$lk_key" "LIVEKIT_API_SECRET=$lk_secret"
        if [[ $rooms =~ ^([yY]|[yY][eE][sS])$ ]]; then
            printf '%s\n' 'COMPOSE_PROFILES=rooms' "CONFERENCE_LIVEKIT_URL=wss://$domain" \
                'CONFERENCE_LIVEKIT_API_URL=http://172.30.219.20:7880' "CONFERENCE_LIVEKIT_API_KEY=$lk_key" "CONFERENCE_LIVEKIT_API_SECRET=$lk_secret"
        else
            printf '%s\n' 'COMPOSE_PROFILES=' 'CONFERENCE_LIVEKIT_URL=' 'CONFERENCE_LIVEKIT_API_URL=' 'CONFERENCE_LIVEKIT_API_KEY=' 'CONFERENCE_LIVEKIT_API_SECRET='
        fi
    } > "$selfhost_env"
    set +o noclobber
    unset lk_secret lk_key
    echo 'Generated private .env. Its secrets were not printed.'
else
    echo 'Update: keeping .env and all named volumes. Back up before continuing.'
fi
read_settings
chmod 600 "$selfhost_env"
domain=$(setting UNYRI_DOMAIN)
rooms=$(setting COMPOSE_PROFILES)
export COMPOSE_PROFILES="$rooms"
check_selfhost_ports "$rooms" || exit 1
echo 'Configure ufw/cloud firewall and router forwarding as in the guide. The installer does not change Linux system firewall policy.'
echo 'UPnP will request: TCP 80,443,3478; UDP 3478,49152-49200.'
if [[ $rooms == rooms ]]; then echo 'UPnP will also request rooms: TCP 17881; UDP 17882.'; fi
read -r -p 'Try UPnP on your local router to forward the stack ports? [y/N] ' upnp
if [[ $upnp =~ ^([yY]|[yY][eE][sS])$ ]] && command -v upnpc >/dev/null && command -v timeout >/dev/null && command -v ip >/dev/null; then
    lan=$(detect_selfhost_lan "$(setting UNYRI_SUBNET)")
    if [[ -n $lan ]]; then
        # Never replace an existing mapping; failed/conflicting mappings need manual review.
        for spec in '80 TCP' '443 TCP' '3478 TCP' '3478 UDP' $(seq 49152 49200 | sed 's/$/_UDP/'); do
            spec=${spec/_/ }
            read -r port proto <<< "$spec"
            if timeout 8s upnpc -a "$lan" "$port" "$port" "$proto" 0 >/dev/null 2>&1; then
                echo "UPnP $port/$proto: requested (verify in router; may be lost at reboot)."
            else echo "UPnP $port/$proto: unavailable/conflict; forward manually."; fi
        done
        if [[ $rooms == rooms ]]; then
            for spec in '17881 TCP' '17882 UDP'; do
                read -r port proto <<< "$spec"
                if timeout 8s upnpc -a "$lan" "$port" "$port" "$proto" 0 >/dev/null 2>&1; then
                    echo "UPnP $port/$proto: requested (verify in router; may be lost at reboot)."
                else echo "UPnP $port/$proto: unavailable/conflict; forward manually."; fi
            done
        fi
    fi
fi
dc config --quiet
echo 'Before update:'
show_server_version
echo 'Target images (UNYRI_SERVER_IMAGE overrides the bundle default if set):'
dc config --images
if [[ $selfhost_from_source == true ]]; then
    dc pull --ignore-buildable
    dc build --pull
else
    dc pull
fi
dc up -d --no-build --wait --wait-timeout 300
if [[ $selfhost_from_source == true ]]; then
    printf '%s\n' 'Local source build; pass --from-source again for the next source update.' > "$selfhost_root/.selfhost-from-source"
else
    rm -f -- "$selfhost_root/.selfhost-from-source"
fi
echo 'After update:'
show_server_version
dc exec -T backend curl --fail --silent http://127.0.0.1:8080/health
address="https://$domain"
printf '\nServer: %s\nAdministration: %s/administration\n' "$address" "$address"
show_owner_setup
if command -v qrencode >/dev/null; then qrencode -t ANSIUTF8 "$address" || true; fi
printf 'Address for copying or encoding as QR: %s\n' "$address"
echo 'The QR contains an address. Select this server manually in the app; it is not an account/room invitation.'
echo 'If the public IPv4 changes, update TURN_EXTERNAL_IP in .env and rerun; DDNS alone does not update TURN. Test from mobile data.'
public_ready=false
for ((attempt=0; attempt<36; attempt++)); do
    if curl --fail --silent --connect-timeout 3 --max-time 5 "$address/health" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"'; then public_ready=true; break; fi
    sleep 5
done
if [[ $public_ready != true ]]; then
    echo 'Installed, not verified from outside (exit 3). Local stack is healthy. Many home routers cannot reach their own public address (NAT loopback); the real test is a phone on mobile data with Wi-Fi off. Check DNS, certificates and forwarding if that test fails. Stack and data were kept.' >&2
    exit 3
fi
echo 'HTTPS health passed. Test two phones on different networks; health does not test media or push.'
