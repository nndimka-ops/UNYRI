#!/bin/sh
set -eu
# The caller checks all destination names before creating any volumes.
# This second check refuses a concurrently populated volume as well.
test -z "$(ls -A /data)"
case "$1" in
    postgres_data|redis_data|minio_data|caddy_data|caddy_config|backend_data) ;;
    *) echo 'Unknown selfhost volume.' >&2; exit 1 ;;
esac
tar -xzf "/backup/$1.tgz" -C /data
if [ "$1" = backend_data ]; then
    chown 10001:10001 /data
    chmod 700 /data
fi
