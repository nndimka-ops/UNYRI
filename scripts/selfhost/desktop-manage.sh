#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_docker
[[ -f "$selfhost_root/desktop-install.marker" ]] || exit 1
case ${1:-} in
erase-backups)
    target="$selfhost_root/selfhost-backups"
    [[ ! -L $target ]] || exit 1
    [[ ! -d $target ]] || { [[ -z $(find "$target" -type l -print -quit) ]] && rm -rf -- "$target"; }
    ;;
retire)
    rm -f -- "$selfhost_env" "$selfhost_root/.selfhost-from-source" "$selfhost_root/desktop-install.marker" "$selfhost_root/desktop-local-ready"
    ;;
*) exit 1 ;;
esac
