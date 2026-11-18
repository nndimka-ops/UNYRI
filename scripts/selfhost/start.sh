#!/usr/bin/env bash
# Managed start for an existing installation; suitable for an OS service after Docker/network readiness.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_docker
read_settings
export COMPOSE_PROFILES
COMPOSE_PROFILES=$(setting COMPOSE_PROFILES)
dc up -d --no-build --wait --wait-timeout 300
