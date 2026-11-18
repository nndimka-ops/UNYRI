#!/usr/bin/env bash
# Source only from the sibling scripts, never source the generated .env.
set -euo pipefail
selfhost_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
selfhost_env="$selfhost_root/.env"
selfhost_compose="$selfhost_root/docker-compose.selfhost.yml"
selfhost_from_source=false
[[ ! -f "$selfhost_root/.selfhost-from-source" ]] || selfhost_from_source=true
dc() {
    # Environment changes are applied by Compose up (not docker restart).
    # Best effort: address metadata must never stop the server from starting.
    if [[ ${1:-} == up ]]; then
        update_selfhost_addresses || echo 'Warning: could not refresh the home-network address; the server starts with the last saved value.' >&2
    fi
    local files=(-f "$selfhost_compose")
    if [[ $selfhost_from_source == true ]]; then files+=(-f "$selfhost_root/scripts/selfhost/compose.source.yml"); fi
    docker compose --project-name unyri-selfhost --env-file "$selfhost_env" "${files[@]}" "$@"
}
valid_lan_ipv4() {
    local address=$1 subnet=${2:-172.30.219.0/24} network bits ip_number net_number mask
    local -a a n
    valid_public_ipv4 "$address" || return 1
    IFS=. read -r -a a <<< "$address"
    (( a[0] == 10 || (a[0] == 172 && a[1] >= 16 && a[1] <= 31) || (a[0] == 192 && a[1] == 168) )) || return 1
    for network in 172.17.0.0/16 "$subnet"; do
        bits=${network#*/}; network=${network%/*}
        [[ $bits =~ ^[0-9]{1,2}$ ]] && (( 10#$bits >= 1 && 10#$bits <= 32 )) || continue
        valid_public_ipv4 "$network" || continue
        IFS=. read -r -a n <<< "$network"
        ip_number=$(( (a[0]<<24) | (a[1]<<16) | (a[2]<<8) | a[3] ))
        net_number=$(( (n[0]<<24) | (n[1]<<16) | (n[2]<<8) | n[3] ))
        mask=$(( (0xffffffff << (32 - 10#$bits)) & 0xffffffff ))
        (( (ip_number & mask) != (net_number & mask) )) || return 1
    done
}
detect_selfhost_lan() {
    local subnet=${1:-172.30.219.0/24} route dev address
    command -v ip >/dev/null || return 0
    # Kernel route lookup only: this sends no packet to the lookup destination.
    route=$(ip -4 route get 1.1.1.1 2>/dev/null) || return 0
    [[ $route =~ [[:space:]]dev[[:space:]]([^[:space:]]+) ]] || return 0
    dev=${BASH_REMATCH[1]}
    [[ $route =~ [[:space:]]src[[:space:]]([^[:space:]]+) ]] || return 0
    address=${BASH_REMATCH[1]}
    case "$dev" in lo|docker*|br-*|veth*|virbr*|vEthernet*|wsl*|tailscale*|wg*|tun*|tap*|vpn*) return 0;; esac
    # A physical device must back the interface. Virtual-only/VPN routes stay unknown.
    [[ -e /sys/class/net/$dev/device ]] || return 0
    valid_lan_ipv4 "$address" "$subnet" || return 0
    ip -4 -o address show dev "$dev" scope global 2>/dev/null |
        awk -v ip="$address" '$0 !~ /tentative|dadfailed|deprecated/ {split($4,a,"/"); if (a[1]==ip) found=1} END {exit !found}' || return 0
    printf '%s' "$address"
}
write_selfhost_address_environment() (
    # Subshell keeps the lock, umask and cleanup scoped to this finite write.
    set -euo pipefail
    umask 077
    local path=$1; shift
    local -a keys=(UNYRI_LAN_ADDRESS UNYRI_LAN_PORT UNYRI_LAN_DETECTED_AT UNYRI_HOSTING_KIND UNYRI_INGRESS_MODE UNYRI_TUNNEL_PROVIDER)
    local -a values=("$@")
    local temporary line ending key owned i first=true bom=
    [[ ${#values[@]} == ${#keys[@]} ]] || return 1
    for line in "${values[@]}"; do [[ $line =~ ^[A-Za-z0-9_.:/+-]*$ ]] || return 1; done
    [[ -f $path && ! -L $path ]] || return 1
    command -v flock >/dev/null || { echo 'Install util-linux (flock) before starting the managed stack.' >&2; return 1; }
    exec 9> "$path.addresses.lock"
    flock -n 9 || { echo 'Another address preparation is running; retry this start.' >&2; return 1; }
    temporary=$(mktemp "${path}.addresses-XXXXXX")
    trap 'rm -f -- "$temporary"' EXIT
    # GNU cp preserves ownership, mode, ACLs and xattrs before any secret bytes
    # are written. The rename remains on the same filesystem.
    cp --preserve=all -- "$path" "$temporary"
    ending=$'\n'
    if IFS= read -r line < "$path"; then [[ $line != *$'\r' ]] || ending=$'\r\n'; fi
    bom=$(head -c 3 -- "$path")
    [[ $bom == $'\xef\xbb\xbf' ]] || bom=
    {
        printf '%s' "$bom"
        for i in "${!keys[@]}"; do printf '%s=%s%s' "${keys[$i]}" "${values[$i]}" "$ending"; done
        while true; do
            if IFS= read -r line; then ending=$'\n'; else ending=; [[ -n $line ]] || break; fi
            if [[ $first == true ]]; then line=${line#"$bom"}; first=false; fi
            owned=false
            for key in "${keys[@]}"; do [[ $line != "$key="* ]] || owned=true; done
            if [[ $owned == false ]]; then printf '%s%s' "$line" "$ending"; fi
            [[ -n $ending ]] || break
        done < "$path"
    } > "$temporary"
    mv -fT -- "$temporary" "$path"
)
update_selfhost_addresses() {
    local lan stamp= hosting ingress provider
    read_settings
    lan=$(detect_selfhost_lan "$(setting UNYRI_SUBNET)")
    [[ -z $lan ]] || stamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    if grep -q '^UNYRI_HOSTING_KIND=' "$selfhost_env"; then hosting=$(setting UNYRI_HOSTING_KIND); else hosting=unknown; fi
    if grep -q '^UNYRI_INGRESS_MODE=' "$selfhost_env"; then ingress=$(setting UNYRI_INGRESS_MODE); else ingress=direct; fi
    provider=$(setting UNYRI_TUNNEL_PROVIDER)
    write_selfhost_address_environment "$selfhost_env" "$lan" "${lan:+443}" "$stamp" "$hosting" "$ingress" "$provider"
}
show_server_version() {
    local container
    container=$(dc ps --all --quiet backend)
    if [[ -z $container ]]; then echo 'Server version: not installed'
    else docker inspect --format 'Server image: {{.Config.Image}}; image ID: {{.Image}}' "$container"; fi
}
setting() {
    # No eval or shell expansion of configuration values.
    sed -n "s/^$1=//p" "$selfhost_env" | sed 's/\r$//'
}
valid_server_name() {
    local LC_ALL=C.UTF-8
    [[ ${#1} -ge 1 && ${#1} -le 64 && ! $1 =~ [^[:print:]] ]]
}
valid_public_ipv4() {
    local octet
    local -a octets
    [[ $1 =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS=. read -r -a octets <<< "$1"
    for octet in "${octets[@]}"; do
        [[ $octet == 0 || $octet != 0* ]] && (( 10#$octet <= 255 )) || return 1
    done
}
quote_server_name() {
    local value=$1
    value=${value//\\/\\\\}; value=${value//\"/\\\"}; value=${value//\$/\$\$}
    printf '"%s"' "$value"
}
unquote_server_name() {
    local value=$1 result= char next
    [[ $value == \"*\" ]] || return 1
    value=${value:1:${#value}-2}
    while [[ -n $value ]]; do
        char=${value:0:1}; value=${value:1}
        if [[ $char == '\' || $char == '$' ]]; then
            next=${value:0:1}; value=${value:1}
            if [[ $char == '\' ]]; then [[ $next == '\' || $next == '"' ]] || return 1
            else [[ $next == '$' ]] || return 1; fi
            char=$next
        else [[ $char != '"' ]] || return 1; fi
        result+=$char
    done
    valid_server_name "$result" || return 1
    printf '%s' "$result"
}
read_settings() {
    [[ -f "$selfhost_env" ]] || { echo 'Run scripts/selfhost/install.sh first.' >&2; exit 1; }
    local line
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        if [[ -z $line || $line == \#* ]]; then continue
        elif [[ $line == UNYRI_SERVER_NAME=* ]]; then
            unquote_server_name "${line#*=}" >/dev/null || { echo 'Invalid quoted server name.' >&2; exit 1; }
        elif [[ ! $line =~ ^[A-Z][A-Z0-9_]*=[A-Za-z0-9_./:@?=,+-]*$ ]]; then
            echo 'Unsupported .env syntax. Use installer-generated values and a quoted server name.' >&2; exit 1
        fi
    done < "$selfhost_env"
    [[ $(setting UNYRI_SELFHOST_FORMAT) == 1 ]] || { echo 'Existing .env belongs to another setup. Use a separate release directory.' >&2; exit 1; }
}

check_selfhost_old_data() {
    local inventory name project found=false
    local -a volumes=()
    inventory=$(docker volume ls --format '{{.Name}}|{{.Label "com.docker.compose.project"}}') || {
        echo 'Cannot inspect old server data. No new .env was written; check Docker and rerun.' >&2; return 1
    }
    while IFS='|' read -r name project; do
        [[ -n $name ]] || continue
        # Restore-created volumes may not carry Compose labels.
        if [[ $project == unyri-selfhost || $name == unyri-selfhost_* ]]; then
            volumes+=("$name"); found=true
        fi
    done <<< "$inventory"
    [[ $found == true ]] || return 0
    echo 'Old server data was found. No new .env was written.' >&2
    echo 'To keep that data, put the old .env back into this folder from your backup, then rerun.' >&2
    echo 'To discard that data permanently, explicitly run the following commands, then rerun. Nothing is deleted automatically:' >&2
    echo 'docker ps -aq --filter label=com.docker.compose.project=unyri-selfhost | xargs -r docker rm -f' >&2
    for name in "${volumes[@]}"; do printf 'docker volume rm -- %q\n' "$name" >&2; done
    return 1
}

show_owner_setup() {
    local available
    available=$(dc exec -T backend sh -c 'if test -s /app/data/owner-setup-code; then if test -d /app/data/.owner-setup-code-shown; then printf shown; else printf available; fi; fi')
    if [[ $available == shown ]]; then
        echo 'Owner code was already shown once; its private location is /app/data/owner-setup-code in backend_data.'
    fi
    if [[ $available == available ]]; then
        echo 'Owner setup: open UNYRI on the phone -> sign in -> Profile -> Claim ownership.'
        if [[ -t 1 && -w /dev/tty ]]; then
            # docker exec output goes directly to the terminal, never a log or
            # command substitution. The private marker survives updates/backups.
            dc exec -T backend sh -c 'if test -s /app/data/owner-setup-code && mkdir /app/data/.owner-setup-code-shown 2>/dev/null; then if ! cat /app/data/owner-setup-code; then rmdir /app/data/.owner-setup-code-shown; exit 1; fi; fi' > /dev/tty
            printf '\n' > /dev/tty
        else
            echo 'Private owner code: /app/data/owner-setup-code in backend_data. Run this installer in a private interactive terminal to display it once; redirected output never includes the code.'
        fi
    elif [[ $available != shown ]]; then
        echo 'Owner setup code is absent (already claimed, or SH-2 is not in this image).'
    fi
    if [[ $available == available || $available == shown ]]; then
        echo 'To see the owner code again privately while unclaimed, run in this release folder. Do not paste the code anywhere or record the terminal:'
        local files='-f docker-compose.selfhost.yml'
        if [[ $selfhost_from_source == true ]]; then files+=' -f scripts/selfhost/compose.source.yml'; fi
        printf 'docker compose --project-name unyri-selfhost --env-file .env %s exec -T backend cat /app/data/owner-setup-code > /dev/tty\n' "$files"
    fi
    echo 'Fallback after registering in the app, from the release directory (replace USERNAME):'
    if [[ $selfhost_from_source == true ]]; then
        echo 'docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml -f scripts/selfhost/compose.source.yml exec backend coco-admin grant-owner USERNAME'
    else
        echo 'docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml exec backend coco-admin grant-owner USERNAME'
    fi
}
check_docker() {
    command -v docker >/dev/null || {
        echo 'Install Docker Engine and Compose plugin yourself: https://docs.docker.com/engine/install/ubuntu/ (or the page for your distribution), then rerun.' >&2
        exit 1
    }
    local endpoint version major minor
    if [[ -n ${DOCKER_CONTEXT:-} ]]; then
        endpoint=$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')
    else
        endpoint=${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}
    fi
    [[ $endpoint == unix:* ]] || { echo 'Remote Docker endpoints are refused. Select a local Engine context.' >&2; exit 1; }
    [[ $(docker info --format '{{.OSType}}') == linux ]] || { echo 'Start the Linux Docker Engine and check this account has Docker access. Nothing was installed.' >&2; exit 1; }
    version=$(docker compose version --short)
    [[ $version =~ ^v?([0-9]+)\.([0-9]+) ]] || { echo 'Compose plugin 2.24+ is required.' >&2; exit 1; }
    major=${BASH_REMATCH[1]}; minor=${BASH_REMATCH[2]}
    (( major > 2 || (major == 2 && minor >= 24) )) || { echo 'Update Compose to 2.24+.' >&2; exit 1; }
}
