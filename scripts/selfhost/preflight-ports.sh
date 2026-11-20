#!/usr/bin/env bash
# Sourced by install.sh. Inspect only container identity and published sockets.
selfhost_port_list() {
    if [[ ${2:-} == true ]]; then
        printf '%s\n' '18080 tcp' '18443 tcp' '13478 tcp' '13478 udp'
        local trial_port
        for ((trial_port=59152; trial_port<=59200; trial_port++)); do printf '%s udp\n' "$trial_port"; done
        return
    fi
    printf '%s\n' '80 tcp' '443 tcp' '3478 tcp' '3478 udp'
    local port
    for ((port=49152; port<=49200; port++)); do printf '%s udp\n' "$port"; done
    if [[ ${1:-} == rooms ]]; then printf '%s\n' '17881 tcp' '17882 udp'; fi
}

format_selfhost_port_conflicts() {
    # Input: port<TAB>protocol<TAB>owner. Keep separate ranges across gaps,
    # protocols and owners, then put all ranges for one owner on one line.
    LC_ALL=C sort -t $'\t' -k3,3 -k2,2 -k1,1n | awk -F '\t' '
        function emit_range( range) {
            if (!first) return
            range = first == last ? first : first "-" last
            parts = parts (parts ? ", " : "") range " " toupper(proto)
        }
        function emit_owner() {
            if (owner != "") print parts ": " owner ". Stop or reconfigure it, then rerun."
        }
        {
            if ($3 != owner) {
                emit_range(); emit_owner(); parts = ""; first = 0; owner = $3
            }
            if (first && $2 == proto && $1 == last + 1) last = $1
            else { emit_range(); first = last = $1; proto = $2 }
        }
        END { emit_range(); emit_owner() }
    '
}

check_selfhost_ports() {
    local rooms=${1:-} local_trial=${2:-false} containers id project name mapping container_port port protocol line endpoint program key failed=false sockets
    local -A wanted=() ours=() conflicts=()
    while read -r port protocol; do wanted["$port/$protocol"]=1; done < <(selfhost_port_list "$rooms" "$local_trial")
    # Inspect every running container: NAT publications may have no ss listener.
    containers=$(docker ps --format '{{.ID}}|{{.Label "com.docker.compose.project"}}|{{.Names}}') || return 1
    while IFS='|' read -r id project name; do
        [[ -n $id ]] || continue
        mapping=$(docker inspect --format '{{range $port, $bindings := .NetworkSettings.Ports}}{{range $bindings}}{{printf "%s %s\n" $port .HostPort}}{{end}}{{end}}' "$id") || return 1
        while read -r container_port port; do
            [[ -n $port ]] || continue
            protocol=${container_port##*/}; key="$port/$protocol"
            [[ ${wanted[$key]:-} ]] || continue
            if [[ $project == unyri-selfhost ]]; then ours[$key]=1
            else conflicts[$key]="Docker container $name"; fi
        done <<< "$mapping"
    done <<< "$containers"
    if command -v ss >/dev/null; then
        for protocol in tcp udp; do
            if [[ $protocol == tcp ]]; then sockets=$(ss -H -ltnp) || return 1
            else sockets=$(ss -H -lunp) || return 1; fi
            while IFS= read -r line; do
                # The first address:port field is the local binding, with or
                # without an ss protocol column (IPv4, IPv6 and wildcard).
                endpoint=$(awk '{for(i=1;i<=NF;i++) if($i ~ /:[0-9]+$/) {print $i; exit}}' <<< "$line")
                port=${endpoint##*:}; key="$port/$protocol"
                [[ ${wanted[$key]:-} && ! ${ours[$key]:-} ]] || continue
                # A Docker publication identifies the actual owner; shared host
                # forwarders must not split that container into different groups.
                [[ ${conflicts[$key]:-} != 'Docker container '* ]] || continue
                program='unknown program'
                if [[ $line =~ \"([^\"]+)\" ]]; then program=${BASH_REMATCH[1]}; fi
                case "; ${conflicts[$key]:-}; " in
                    *"; $program; "*) ;;
                    *) conflicts[$key]="${conflicts[$key]:+${conflicts[$key]}; }$program" ;;
                esac
            done <<< "$sockets"
        done
    else
        # Minimal hosts/docker:cli may not have iproute2. /proc exposes bound
        # sockets but not their process names; do not pretend they are free.
        local table hex state
        for table in tcp tcp6 udp udp6; do
            [[ -r /proc/net/$table ]] || continue
            protocol=${table%6}
            while read -r hex state; do
                [[ $protocol != tcp || $state == 0A ]] || continue
                port=$((16#$hex)); key="$port/$protocol"
                [[ ${wanted[$key]:-} && ! ${ours[$key]:-} ]] || continue
                conflicts[$key]="${conflicts[$key]:-unknown program}"
            done < <(awk 'NR>1 {split($2,a,":"); print a[2],$4}' "/proc/net/$table")
        done
        echo 'ss unavailable: socket process names are unknown. Run on the Docker host for host socket checks.'
    fi
    local rows=
    while read -r port protocol; do
        key="$port/$protocol"
        if [[ ${conflicts[$key]:-} ]]; then
            rows+="$port"$'\t'"$protocol"$'\t'"${conflicts[$key]}"$'\n'
            failed=true
        fi
    done < <(selfhost_port_list "$rooms" "$local_trial")
    if [[ $failed == true ]]; then printf '%s' "$rows" | format_selfhost_port_conflicts >&2; fi
    [[ $failed == false ]]
}

# Direct desktop entry point; sourced callers are unchanged.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    set -euo pipefail
    check_selfhost_ports "${1:-}" "${2:-false}"
fi
