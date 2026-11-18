#!/bin/sh
set -eu
# /health returns HTTP 200 even when dependencies are degraded. Check its JSON.
if command -v curl >/dev/null 2>&1; then
    response=$(curl --fail --silent --max-time 4 "$1") || exit 1
else
    response=$(wget -q -T 4 -O - "$1") || exit 1
fi
printf '%s\n' "$response" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"'
