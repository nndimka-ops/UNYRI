#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_docker
read_settings
[[ $# == 1 ]] || { echo 'Usage: bash scripts/selfhost/restore.sh /absolute/path/to/snapshot' >&2; exit 1; }
backup=$(cd -- "$1" && pwd)
[[ -f "$backup/COMPLETE" ]] || { echo 'Incomplete backup: COMPLETE marker missing.' >&2; exit 1; }
cmp -s -- "$selfhost_env" "$backup/.env" || { echo 'Copy backup .env into the fresh release directory first. Do not generate new secrets.' >&2; exit 1; }
volumes=(postgres_data redis_data minio_data caddy_data caddy_config backend_data)
existing=$(docker volume ls --format '{{.Name}}')
for volume in "${volumes[@]}"; do
    if grep -Fxq "unyri-selfhost_$volume" <<< "$existing"; then
        echo 'Destination volumes already exist. Restore requires a fresh Docker machine; no data was changed.' >&2; exit 1
    fi
    [[ -f "$backup/$volume.tgz" ]] || { echo "Missing archive: $volume.tgz" >&2; exit 1; }
done
helper_present=$(docker image ls --quiet alpine:3.24.3)
if [[ -z $helper_present ]]; then docker pull alpine:3.24.3; fi
for volume in "${volumes[@]}"; do
    docker run --rm --network none --mount "type=bind,source=$backup,target=/backup,readonly" \
        alpine:3.24.3 tar -tzf "/backup/$volume.tgz" >/dev/null
done
for volume in "${volumes[@]}"; do
    docker run --rm --network none --mount "type=volume,source=unyri-selfhost_$volume,target=/data" \
        --mount "type=bind,source=$backup,target=/backup,readonly" \
        --mount "type=bind,source=$selfhost_root/scripts/selfhost/restore-volume.sh,target=/restore-volume.sh,readonly" \
        alpine:3.24.3 sh /restore-volume.sh "$volume"
done
chmod 600 "$selfhost_env"
echo 'All six volumes restored. Use the same source release first, adjust the public IPv4 if moving, then run install.sh. Do not run old and replacement servers at the same time.'
