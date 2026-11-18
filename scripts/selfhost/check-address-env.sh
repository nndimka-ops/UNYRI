#!/usr/bin/env bash
# Fixture-only; no installation .env, Docker, secrets or network access.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/unyri-address-fixture-XXXXXX")
trap 'rm -f -- "$fixture_root/fixture.txt" "$fixture_root/fixture.txt.addresses.lock" "$fixture_root/expected.txt"; rmdir -- "$fixture_root"' EXIT
values=(192.168.50.10 443 2026-11-18T12:00:00Z home_pc direct '')
for newline in $'\n' $'\r\n'; do
    for bom in '' $'\xef\xbb\xbf'; do
        path="$fixture_root/fixture.txt"
        # Unicode, quoting, duplicates and a last line without a newline.
        unowned="# Synthetic fixture only${newline}JWT_SECRET=fixture-only-unchanged${newline}CUSTOM=\"a \$\$ b # Ю\"${newline}${newline}FINAL=no-newline"
        printf '%s' "${bom}UNYRI_LAN_ADDRESS=172.30.219.10${newline}UNYRI_LAN_ADDRESS=10.0.0.2${newline}UNYRI_INGRESS_MODE=old${newline}${unowned}" > "$path"
        chmod 600 "$path"
        permissions=$(stat -c '%a:%u:%g' "$path")
        {
            printf '%s' "$bom"
            printf '%s%s' 'UNYRI_LAN_ADDRESS=192.168.50.10' "$newline" 'UNYRI_LAN_PORT=443' "$newline" \
                'UNYRI_LAN_DETECTED_AT=2026-11-18T12:00:00Z' "$newline" 'UNYRI_HOSTING_KIND=home_pc' "$newline" \
                'UNYRI_INGRESS_MODE=direct' "$newline" 'UNYRI_TUNNEL_PROVIDER=' "$newline"
            printf '%s' "$unowned"
        } > "$fixture_root/expected.txt"
        write_selfhost_address_environment "$path" "${values[@]}"
        cmp --silent "$path" "$fixture_root/expected.txt"
        [[ $(stat -c '%a:%u:%g' "$path") == "$permissions" ]]
        write_selfhost_address_environment "$path" "${values[@]}"
        cmp --silent "$path" "$fixture_root/expected.txt"
        (
            exec 8> "$path.addresses.lock"; flock -n 8
            if write_selfhost_address_environment "$path" "${values[@]}" 2>/dev/null; then echo 'Concurrent writer was not refused' >&2; exit 1; fi
        )
        write_selfhost_address_environment "$path" '' '' '' home_pc direct ''
        # The first owned line may follow a UTF-8 BOM.
        grep -Eq $'UNYRI_LAN_ADDRESS=\r?$' "$path"
        [[ -z $(find "$fixture_root" -name '*.addresses-*' -print -quit) ]]
    done
done
printf '%s\n' 'PASS: address env fixtures (LF/CRLF, BOM, duplicates, no final newline, unchanged bytes/mode/owner, idempotence, lock, cleared LAN).'
