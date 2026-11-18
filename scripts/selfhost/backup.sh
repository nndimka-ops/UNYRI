#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_docker
read_settings
export COMPOSE_PROFILES
COMPOSE_PROFILES=$(setting COMPOSE_PROFILES)
umask 077
backup="$selfhost_root/selfhost-backups/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p -- "$selfhost_root/selfhost-backups"
mkdir -- "$backup"
helper_present=$(docker image ls --quiet alpine:3.24.3)
if [[ -z $helper_present ]]; then docker pull alpine:3.24.3; fi
dc stop
trap 'dc up -d --wait --wait-timeout 300' EXIT
cp -- "$selfhost_env" "$backup/.env"
for volume in postgres_data redis_data minio_data caddy_data caddy_config backend_data; do
    docker volume inspect "unyri-selfhost_$volume" --format '{{.Name}}' >/dev/null
    docker run --rm --network none --mount "type=volume,source=unyri-selfhost_$volume,target=/data,readonly" \
        --mount "type=bind,source=$backup,target=/backup" alpine:3.24.3 tar -czf "/backup/$volume.tgz" -C /data .
    docker run --rm --network none --mount "type=bind,source=$backup,target=/backup,readonly" \
        alpine:3.24.3 tar -tzf "/backup/$volume.tgz" >/dev/null
done
printf '%s\n' 'All six cold volume archives and .env were saved.' > "$backup/COMPLETE"
printf 'Backup complete: %s . Copy it and this exact source release to another disk. Test a restore.\n' "$backup"
