#!/usr/bin/env python3
"""Offline release checks: no real downloads, .env reads or container starts."""
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from build_selfhost_bundle import build, bundle_paths, checksums


ROOT = Path(__file__).resolve().parents[1]
VERSION = "9.8.7"
ARCHIVE = f"unyri-selfhost-{VERSION}.tar.gz"
# Intentionally independent of the builder's allowlist: additions must be reviewed.
ALLOWED = {
    "LICENSE", "docker-compose.selfhost.yml", "SELFHOST_VERSION", "SHA256SUMS",
    "docs/SELF_HOSTING.md", "docs/SELF_HOSTING_EN.md", "scripts/conference/README.md",
    *{"scripts/selfhost/" + name for name in (
        "Caddyfile", "THIRD_PARTY_NOTICES.md", "backup.sh", "backup.ps1",
        "check-ports.sh", "check-ports.ps1", "common.sh", "common.ps1",
        "preflight-ports.sh", "preflight-ports.ps1", "firewall.ps1",
        "desktop-firewall.ps1", "desktop-manage.ps1", "desktop-manage.sh",
        "desktop-upnp.ps1", "desktop-upnp.sh",
        "install.sh", "install.ps1", "get.sh", "health-check.sh", "init-storage.sh",
        "restore.sh", "restore.ps1", "restore-volume.sh", "start-turn.sh", "start.sh", "start.ps1",
        "storage-client-uploads-policy.template.json",
    )},
}


def native(path):
    value = str(path).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


def run(arguments, **kwargs):
    if "input" in kwargs and Path(arguments[0]).stem.lower() == "bash":
        # Windows Popen text mode rewrites LF stdin to CRLF; a real Bash tty
        # supplies LF. Keep prompts/validation fixtures faithful to the tty.
        payload = kwargs.pop("input").encode("utf-8")
        kwargs.pop("encoding", None)
        result = subprocess.run(arguments, input=payload, capture_output=True, **kwargs)
        result.stdout = result.stdout.decode("utf-8", errors="replace")
        result.stderr = result.stderr.decode("utf-8", errors="replace")
        return result
    # Decode as UTF-8 explicitly: the Windows locale codepage mangles Cyrillic names.
    kwargs.setdefault("encoding", "utf-8")
    kwargs.setdefault("errors", "replace")
    return subprocess.run(arguments, text=True, capture_output=True, **kwargs)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def rewrite_archive(path, files, extra=None):
    files = {**files, "SHA256SUMS": checksums(files)}
    with tarfile.open(path, "w:gz") as archive:
        for name, data in files.items():
            member = tarfile.TarInfo(name)
            member.mode = 0o755 if name.endswith(".sh") else 0o644
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
        if extra:
            archive.addfile(extra, io.BytesIO(b"bad"))


def check_downloader(work, files, bash):
    fake = work / "bin"
    fake.mkdir()
    # Bash mocks replace curl and the installer, so no network or Docker is used.
    (fake / "curl").write_text('''#!/usr/bin/env bash
set -eu
url= output=
while (( $# )); do
    case "$1" in
        https://*) url=$1; shift ;;
        -o) output=$2; shift 2 ;;
        *) shift ;;
    esac
done
case "$url" in
    https://api.github.com/*/releases?per_page=100) cp -- "$SH5_TEST_DIST/releases.json" "$output" ;;
    https://github.com/example/repo/releases/download/server-v9.8.7/*) cp -- "$SH5_TEST_DIST/${url##*/}" "$output" ;;
    *) echo "Unexpected mock URL: $url" >&2; exit 1 ;;
esac
''', newline="\n")
    (fake / "python3").write_text('#!/usr/bin/env bash\nexec "$SH5_PYTHON" "$@"\n', newline="\n")
    for path in fake.iterdir():
        path.chmod(0o755)
    dist = work / "download-fixture"
    dist.mkdir()
    (dist / "releases.json").write_text(json.dumps([
        {"tag_name": "ios-v99.0.0"},
        {"tag_name": "server-v10.0.0-rc1", "prerelease": True},
        {"tag_name": "server-v9.8.7"},
    ]))
    installer = b'#!/usr/bin/env bash\nprintf installed > "$SH5_TEST_MARKER"\nexit "${SH5_TEST_INSTALLER_EXIT:-0}"\n'
    files = {name: data for name, data in files.items() if name != "SHA256SUMS"}
    files["scripts/selfhost/install.sh"] = installer
    destination = work / "installation"
    destination.mkdir()
    sentinel = destination / "keep-private-settings.txt"
    sentinel.write_text("untouched")
    marker = work / "installer-started"
    env = {**os.environ, "PATH": str(fake) + os.pathsep + os.environ["PATH"],
           "SH5_PYTHON": sys.executable, "SH5_TEST_DIST": str(dist), "SH5_TEST_MARKER": str(marker)}
    # Git Bash's PATH import accepts Windows paths; use its native path for the
    # installation argument, which the downloader deliberately requires absolute.
    target = str(destination).replace("\\", "/")
    if os.name == "nt":
        target = "/" + target[0].lower() + target[2:]
    env["SH5_TEST_BIN"] = str(fake).replace("\\", "/")
    if os.name == "nt":
        env["SH5_TEST_BIN"] = "/" + env["SH5_TEST_BIN"][0].lower() + env["SH5_TEST_BIN"][2:]
    command = [bash, "--noprofile", "--norc", "-c",
               'export PATH="$SH5_TEST_BIN:/usr/bin:/bin"; [[ $(command -v curl) == "$SH5_TEST_BIN/curl" ]] || exit 99; exec bash "$@"',
               "offline-downloader", str(ROOT / "scripts/selfhost/get.sh"),
               "--repo", "example/repo", "--directory", target]

    def publish():
        (dist / "SHA256SUMS").write_bytes(checksums({ARCHIVE: (dist / ARCHIVE).read_bytes()}))

    def rejected(label):
        result = run(command + ["--version", "9.8.7"], env=env)
        require(result.returncode != 0, f"{label} was accepted")
        require(not marker.exists(), f"{label} executed installer")
        require(sentinel.read_text() == "untouched", f"{label} changed existing files")
        require(not (destination / "SELFHOST_VERSION").exists(), f"{label} copied bundle files")

    rewrite_archive(dist / ARCHIVE, files)
    publish()
    with (dist / ARCHIVE).open("ab") as stream:
        stream.write(b"corruption")
    rejected("Archive checksum mismatch")
    unsafe = tarfile.TarInfo("../outside.txt")
    unsafe.size = 3
    rewrite_archive(dist / ARCHIVE, files, unsafe)
    publish()
    rejected("Archive traversal")
    link = tarfile.TarInfo("scripts/selfhost/link.sh")
    link.type = tarfile.SYMTYPE
    link.linkname = "/etc/passwd"
    rewrite_archive(dist / ARCHIVE, files, link)
    publish()
    rejected("Archive symlink")
    for forbidden in ("docs/STATUS.md", "docs/DECISIONS.md", "scripts/selfhost/compose.source.yml", "scripts/selfhost/storage.Dockerfile", "scripts/selfhost/extra.sh"):
        extra = tarfile.TarInfo(forbidden)
        extra.size = 3
        rewrite_archive(dist / ARCHIVE, files, extra)
        publish()
        rejected("Unapproved runtime file " + forbidden)
    missing = {name: data for name, data in files.items() if name != "scripts/selfhost/firewall.ps1"}
    rewrite_archive(dist / ARCHIVE, missing)
    publish()
    rejected("Missing required operator file")
    rewrite_archive(dist / ARCHIVE, files)
    # Corrupt an internal manifest while keeping the outer archive hash valid.
    with tarfile.open(dist / ARCHIVE, "r:gz") as archive:
        entries = {entry.name: archive.extractfile(entry).read() for entry in archive}
    entries["scripts/selfhost/install.sh"] += b"# tampered\n"
    with tarfile.open(dist / ARCHIVE, "w:gz") as archive:
        for name, data in entries.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
    publish()
    rejected("Internal checksum mismatch")
    rewrite_archive(dist / ARCHIVE, files)
    publish()
    # No --version: ensure a mobile release and a server prerelease are skipped.
    result = run(command, env=env)
    require(result.returncode == 0, result.stdout + result.stderr)
    require(marker.read_text() == "installed", "Verified installer was not run")
    require(sentinel.read_text() == "untouched", "Existing settings were replaced")
    marker.unlink()
    result = run(command + ["--version", "server-v9.8.7"], env=env)
    require(result.returncode == 0 and marker.exists(), "Selected-version rerun failed: " + result.stderr)
    result = run(command + ["--version", "9.8.7"], env={**env, "SH5_TEST_INSTALLER_EXIT": "3"})
    require(result.returncode == 3, "Downloader did not propagate installed/unverified exit code 3")
    print("Downloader: latest/selected/rerun passed; hash mismatch, internal corruption, traversal and symlink refused")


def check_compose(work, files):
    docker = shutil.which("docker")
    if not docker:
        print("SKIP Compose rendering: docker unavailable")
        return
    runtime = work / "runtime"
    runtime.mkdir()
    for name, data in files.items():
        target = runtime / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    settings = work / "validation-settings.txt"
    required = set(re.findall(r"\$\{([A-Z0-9_]+):\?", files["docker-compose.selfhost.yml"].decode()))
    values = {key: "offline-test" for key in required}
    values.update(UNYRI_DOMAIN="example.invalid", TURN_EXTERNAL_IP="203.0.113.10")
    # Printable names must survive dotenv escaping and Compose interpolation.
    display_name = 'Family "space" $HOME #1 \\ Борис\'s'
    encoded_name = '"' + display_name.replace('\\', '\\\\').replace('"', '\\"').replace('$', '$$') + '"'
    settings.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())) + f"UNYRI_SERVER_NAME={encoded_name}\n", encoding="utf-8")
    config = work / "docker-config"
    config.mkdir()
    env = {**os.environ, "COMPOSE_DISABLE_ENV_FILE": "true"}
    for key in [*values, "UNYRI_SERVER_NAME", "UNYRI_SERVER_IMAGE", "UNYRI_MINIO_IMAGE", "UNYRI_MC_IMAGE", "COMPOSE_PROFILES"]:
        env.pop(key, None)
    command = [docker, "--config", str(config), "compose", "--project-name", "sh5-offline-check",
               "--env-file", str(settings), "-f", str(runtime / "docker-compose.selfhost.yml")]
    for profile in ([], ["--profile", "rooms"]):
        result = run(command + profile + ["config", "--format", "json"], env=env)
        require(result.returncode == 0, result.stderr)
        services = json.loads(result.stdout)["services"]
        require(services["backend"]["image"] == "ghcr.io/exampleowner/unyri-server:9.8.7", "Wrong default server image")
        require(all("build" not in service for service in services.values()), "Default install would compile")
        backend = services["backend"]
        # Compose re-escapes literal dollars in its config output for round trips;
        # the actual container environment receives a single dollar.
        require(backend["environment"]["UNYRI_SERVER_NAME"].replace('$$', '$') == display_name, "Server name escaping changed: " + repr(backend["environment"]["UNYRI_SERVER_NAME"]))
        require(backend["environment"]["COCO_DATA_DIR"] == "/app/data", "Private backend directory missing")
        require(any(volume.get("target") == "/app/data" and volume.get("source") == "backend_data" for volume in backend["volumes"]), "Private backend volume missing")
        require(all(backend["environment"][key] == value for key, value in {"TURN_EXTERNAL_IP": "203.0.113.10", "TURN_MIN_PORT": "49152", "TURN_MAX_PORT": "49200"}.items()), "TURN allocation environment missing")
        for service in services.values():
            for volume in service.get("volumes", []):
                if volume["type"] == "bind":
                    require(Path(volume["source"]).is_file(), "Missing bundle mount: " + volume["source"])
    result = run(command + ["config", "--format", "json"], env={**env, "UNYRI_SERVER_IMAGE": "local-review/server:123"})
    require(result.returncode == 0, result.stderr)
    require(json.loads(result.stdout)["services"]["backend"]["image"] == "local-review/server:123", "Image override lost")
    source = [docker, "--config", str(config), "compose", "--env-file", str(settings),
              "-f", str(ROOT / "docker-compose.selfhost.yml"), "-f", str(ROOT / "scripts/selfhost/compose.source.yml"), "config", "--format", "json"]
    result = run(source, env=env)
    require(result.returncode == 0, result.stderr)
    services = json.loads(result.stdout)["services"]
    require({name for name, service in services.items() if "build" in service} == {"backend", "minio", "storage-init"}, "Source overlay build set changed")
    print("Compose: both profiles, all bundle mounts, image override and source overlay passed (no daemon/start)")


def check_port_grouping(work, bash):
    suffix = '. Stop or reconfigure it, then rerun.'
    cases = [
        ({'3478/TCP': 'Docker container messenger-coturn', '3478/UDP': 'Docker container messenger-coturn', **{f'{port}/UDP': 'Docker container messenger-coturn' for port in range(49152, 49201)}},
         ['3478 TCP, 3478 UDP, 49152-49200 UDP: Docker container messenger-coturn' + suffix]),
        ({'49152/UDP': 'alpha', '49153/UDP': 'alpha', '49154/UDP': 'beta', '49155/UDP': 'alpha', '443/TCP': 'beta'},
         ['49152-49153 UDP, 49155 UDP: alpha' + suffix, '443 TCP, 49154 UDP: beta' + suffix]),
        ({'80/TCP': 'unknown program', '443/TCP': 'unknown program'}, ['80 TCP, 443 TCP: unknown program' + suffix]),
        ({'3478/UDP': 'coturn'}, ['3478 UDP: coturn' + suffix]),
        ({}, []),
    ]
    env = {**os.environ, 'SH5_PORT_SCRIPT': native(ROOT / 'scripts/selfhost/preflight-ports.sh')}
    for conflicts, expected in cases:
        rows = ''.join(f'{key.split("/")[0]}\t{key.split("/")[1].lower()}\t{owner}\n' for key, owner in conflicts.items())
        result = run([bash, '--noprofile', '--norc', '-c', 'source "$SH5_PORT_SCRIPT"; format_selfhost_port_conflicts'], env=env, input=rows)
        require(result.returncode == 0 and result.stdout.splitlines() == expected, 'Bash port ranges/owners grouped incorrectly: ' + result.stdout + result.stderr)
    powershell = shutil.which('powershell.exe') or shutil.which('pwsh')
    if powershell:
        inputs = work / 'grouping-cases.json'
        inputs.write_text(json.dumps([{'conflicts': conflicts, 'expected': expected} for conflicts, expected in cases]), encoding='utf-8')
        runner = work / 'grouping.ps1'
        runner.write_text('''
$ErrorActionPreference = 'Stop'
. $env:SH5_PS_PORT_SCRIPT
$cases = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'grouping-cases.json')) | ConvertFrom-Json
foreach ($case in $cases) {
    $conflicts = @{}
    foreach ($property in $case.conflicts.PSObject.Properties) { $conflicts[$property.Name] = $property.Value }
    $actual = @(Format-SelfhostPortConflicts $conflicts)
    if (($actual -join "`n") -cne ($case.expected -join "`n")) { throw 'PowerShell port ranges/owners grouped incorrectly.' }
}
''', encoding='utf-8-sig')
        result = run([powershell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(runner)], env={**os.environ, 'SH5_PS_PORT_SCRIPT': str(ROOT / 'scripts/selfhost/preflight-ports.ps1')})
        require(result.returncode == 0, result.stdout + result.stderr)
    print('Port grouping: contiguous ranges, protocol separation, mixed owners, gaps, single ports and empty input passed')


def check_installer(work, bash):
    root = work / "mock-install"
    scripts = root / "scripts/selfhost"
    scripts.mkdir(parents=True)
    (root / "server").mkdir()
    (root / "server/Dockerfile").touch()
    (root / "shared").mkdir()
    (root / "SELFHOST_VERSION").write_text(VERSION)
    settings = root / "private-settings.txt"
    settings.write_text('UNYRI_SELFHOST_FORMAT=1\nUNYRI_DOMAIN=example.invalid\nCOMPOSE_PROFILES=\nUNYRI_SERVER_NAME="UNYRI"\n')
    (scripts / "install.sh").write_bytes((ROOT / "scripts/selfhost/install.sh").read_bytes())
    (scripts / "preflight-ports.sh").write_bytes((ROOT / "scripts/selfhost/preflight-ports.sh").read_bytes())
    (scripts / "common.sh").write_text((ROOT / "scripts/selfhost/common.sh").read_text(encoding="utf-8") + '''
selfhost_env="$selfhost_root/private-settings.txt"
check_docker() { :; }
dc() {
    printf '%s\\n' "$*" >> "$selfhost_root/compose-calls.txt"
    case "$*" in
        'ps --all --quiet backend') echo fixture-backend ;;
        'exec -T backend sh -c '*owner-setup-code*) if [[ ${SH5_OWNER_PRESENT:-0} == 1 ]]; then printf '%s' "${SH5_OWNER_STATE:-available}"; fi ;;
        'up '*) [[ ${SH5_START_FAIL:-0} == 0 ]] ;;
    esac
}
''', newline="\n")
    fake = root / "bin"
    fake.mkdir()
    (fake / "curl").write_text('''#!/usr/bin/env bash
if [[ $* == *api.ipify.org* ]]; then
    echo ipify >> "$SH5_TEST_ROOT/ip-requests.txt"
    [[ ${SH5_IP_FAIL:-0} == 0 ]] || exit 22
    echo "${SH5_IP_VALUE:-203.0.113.10}"
else
    [[ ${SH5_HTTPS_FAIL:-0} == 0 ]] || exit 22
    echo '{"status":"ok"}'
fi
''', newline="\n")
    (fake / "docker").write_text('''#!/usr/bin/env bash
case "$1" in
    volume) [[ ${SH5_VOLUME_FAIL:-0} == 0 ]] || exit 1; printf '%s' "${SH5_VOLUMES:-}" ;;
    ps) printf '%s' "${SH5_CONTAINERS:-}" ;;
    inspect)
        if [[ $* == *NetworkSettings.Ports* ]]; then printf '%s' "${SH5_BINDINGS:-}"
        else echo 'Server image: test:9.8.7; image ID: sha256:test'; fi ;;
    *) echo 'Unexpected Docker call in offline fixture' >&2; exit 99 ;;
esac
''', newline="\n")
    (fake / "ss").write_text('''#!/usr/bin/env bash
if [[ $* == *-ltnp* ]]; then printf '%s' "${SH5_TCP:-}"
else printf '%s' "${SH5_UDP:-}"; fi
''', newline="\n")
    (fake / "sleep").write_text('#!/usr/bin/env bash\nexit 0\n', newline="\n")
    (fake / "openssl").write_text('#!/usr/bin/env bash\nprintf "%064d\\n" 0\n', newline="\n")
    (fake / "qrencode").write_text('#!/usr/bin/env bash\nexit 0\n', newline="\n")
    for path in fake.iterdir():
        path.chmod(0o755)
    native_bin = str(fake).replace("\\", "/")
    if os.name == "nt":
        native_bin = "/" + native_bin[0].lower() + native_bin[2:]
    env = {**os.environ, "SH5_TEST_BIN": native_bin, "SH5_TEST_ROOT": native(root), "SH5_OWNER_PRESENT": "1"}
    command = [bash, "--noprofile", "--norc", "-c",
               'export PATH="$SH5_TEST_BIN:/usr/bin:/bin"; [[ $(command -v curl) == "$SH5_TEST_BIN/curl" ]] || exit 99; exec bash "$@"',
               "offline-installer", str(scripts / "install.sh")]
    for source in (False, True, False):
        calls = root / "compose-calls.txt"
        calls.unlink(missing_ok=True)
        result = run(command + (["--from-source"] if source else []), env=env, input="n\n")
        require(result.returncode == 0, result.stdout + result.stderr)
        operations = calls.read_text().splitlines()
        require(("build --pull" in operations) == source, "Installer build mode mismatch")
        require(("pull --ignore-buildable" if source else "pull") in operations, "Installer pull mode mismatch")
        require("up -d --no-build --wait --wait-timeout 300" in operations, "Installer could implicitly build")
        require((root / ".selfhost-from-source").exists() == source, "Source marker mode mismatch")
        require(all(text in result.stdout for text in ["Before update:", "After update:", "Server: https://example.invalid", "Claim ownership", "coco-admin grant-owner USERNAME"]), "Installer output missing version/address/owner handoff")
        require('exec -T backend cat /app/data/owner-setup-code > /dev/tty' in result.stdout and 'Do not paste the code anywhere' in result.stdout, "Private owner-code redisplay instructions missing")
    original = settings.read_bytes()
    result = run(command, env={**env, "SH5_HTTPS_FAIL": "1"}, input="n\n")
    require(result.returncode == 3 and "NAT loopback" in result.stderr, "Outside-only failure must return 3")
    require(settings.read_bytes() == original, "Update changed settings")
    result = run(command, env={**env, "SH5_START_FAIL": "1"}, input="n\n")
    require(result.returncode == 1, "Startup failure must return 1")
    # Native and Docker-published collisions, all before any pull/start.
    calls = root / "compose-calls.txt"
    for extra, expected in [
        ({"SH5_UDP": 'UNCONN 0 0 0.0.0.0:3478 0.0.0.0:* users:(("coturn",pid=9,fd=1))'}, "3478 UDP: coturn"),
        ({"SH5_TCP": 'LISTEN 0 0 [::]:443 [::]:*'}, "443 TCP: unknown program"),
        ({"SH5_CONTAINERS": "other|another-project|other-turn", "SH5_BINDINGS": "3478/udp 3478"}, "3478 UDP: Docker container other-turn"),
        ({"SH5_UDP": 'UNCONN 0 0 *:49199 *:*'}, "49199 UDP: unknown program"),
    ]:
        calls.unlink(missing_ok=True)
        result = run(command, env={**env, **extra}, input="n\n")
        require(result.returncode == 1 and expected in result.stderr, "Port conflict was not explained: " + result.stderr)
        require(not calls.exists(), "Busy-port check ran after Compose")
    own = {"SH5_CONTAINERS": "ours|unyri-selfhost|selfhost-turn", "SH5_BINDINGS": "3478/udp 3478",
           "SH5_UDP": 'UNCONN 0 0 *:3478 *:* users:(("docker-proxy",pid=9,fd=1))'}
    result = run(command, env={**env, **own}, input="n\n")
    require(result.returncode == 0, "Own Docker publication blocked a rerun: " + result.stderr)
    # Rooms' extra ports are conditional, including unknown program ownership.
    rooms_conflict = {"SH5_TCP": 'LISTEN 0 0 *:17881 *:*', "SH5_UDP": 'UNCONN 0 0 *:17882 *:*'}
    result = run(command, env={**env, **rooms_conflict}, input="n\n")
    require(result.returncode == 0, "Disabled room ports blocked an install")
    settings.write_bytes(original.replace(b"COMPOSE_PROFILES=", b"COMPOSE_PROFILES=rooms"))
    result = run(command, env={**env, **rooms_conflict}, input="n\n")
    require(result.returncode == 1 and "17881 TCP, 17882 UDP: unknown program" in result.stderr, "Grouped room port conflicts missing")
    calls.unlink(missing_ok=True)
    bindings = '\n'.join(['3478/tcp 3478', '3478/udp 3478'] + [f'{port}/udp {port}' for port in range(49152, 49201)])
    result = run(command, env={**env, "SH5_CONTAINERS": "other|another-project|messenger-coturn", "SH5_BINDINGS": bindings, "SH5_TCP": 'LISTEN 0 0 *:3478 *:* users:(("docker-proxy",pid=9,fd=1))', "SH5_UDP": 'UNCONN 0 0 *:49152 *:* users:(("docker-proxy",pid=9,fd=1))'}, input="n\n")
    require(result.returncode == 1 and result.stderr.strip() == '3478 TCP, 3478 UDP, 49152-49200 UDP: Docker container messenger-coturn. Stop or reconfigure it, then rerun.', "TURN conflict was not grouped into one line: " + result.stderr)
    require(not calls.exists(), "Grouped conflict reached Compose")
    settings.write_bytes(original)
    result = run(command, env={**env, "SH5_OWNER_STATE": "shown"}, input="n\n")
    require(result.returncode == 0 and 'already shown once' in result.stdout and 'exec -T backend cat /app/data/owner-setup-code > /dev/tty' in result.stdout, "Shown owner code cannot be viewed privately again")
    result = run(command, env={**env, "SH5_OWNER_PRESENT": "0"}, input="n\n")
    require(result.returncode == 0 and 'exec -T backend cat /app/data/owner-setup-code' not in result.stdout, "Redisplay offered after code removal")
    # Never generate replacement credentials over any surviving project data.
    settings.unlink(); calls.unlink(missing_ok=True)
    inventory = '\n'.join(['unyri-selfhost_postgres_data|unyri-selfhost', 'unyri-selfhost_backend_data|', 'custom_project_data|unyri-selfhost', 'foreign_data|another-project'])
    result = run(command, env={**env, "SH5_VOLUMES": inventory}, input="")
    require(result.returncode == 1 and not settings.exists() and not calls.exists(), "Fresh install wrote settings/started over old data")
    require(all(text in result.stderr for text in ['Old server data was found', 'put the old .env back', 'xargs -r docker rm -f', 'docker volume rm -- unyri-selfhost_postgres_data', 'docker volume rm -- unyri-selfhost_backend_data', 'docker volume rm -- custom_project_data']), "Old-data recovery commands incomplete")
    require('foreign_data' not in result.stderr, "Removal instructions included another project's volume")
    result = run(command, env={**env, "SH5_VOLUME_FAIL": "1"}, input="")
    require(result.returncode == 1 and not settings.exists() and 'Cannot inspect old server data' in result.stderr, "Volume inspection failure did not fail closed")
    settings.write_bytes(original)
    result = run(command, env={**env, "SH5_VOLUMES": inventory}, input="n\n")
    require(result.returncode == 0 and settings.read_bytes() == original, "Existing configuration with its volumes no longer updates")
    # Fresh prompts: no consent => no discovery request; accepted/refused/failed
    # discovery is simulated. No file is ever named .env in these tests.
    ip_calls = root / "ip-requests.txt"
    name = 'Family "space" $HOME #1 \\ Борис\'s'
    for answers, extra, expected_ip, expected_requests in [
        (f"example.invalid\n{name}\nn\n203.0.113.8\nn\nn\n", {}, "203.0.113.8", False),
        (f"example.invalid\n{name}\ny\ny\nn\nn\n", {}, "203.0.113.10", True),
        ("example.invalid\n\ny\nn\n203.0.113.8\nn\nn\n", {}, "203.0.113.8", True),
        ("example.invalid\n\ny\n203.0.113.8\nn\nn\n", {"SH5_IP_FAIL": "1"}, "203.0.113.8", True),
        ("example.invalid\n\ny\n203.0.113.8\nn\nn\n", {"SH5_IP_VALUE": "not-an-ip"}, "203.0.113.8", True),
    ]:
        settings.unlink(missing_ok=True); ip_calls.unlink(missing_ok=True)
        result = run(command, env={**env, "SH5_VOLUMES": "foreign_data|another-project", **extra}, input=answers, encoding="utf-8")
        require(result.returncode == 0, result.stdout + result.stderr)
        saved = settings.read_text(encoding="utf-8")
        require(f"TURN_EXTERNAL_IP={expected_ip}\n" in saved, "Wrong chosen IP")
        require(ip_calls.exists() == expected_requests, "IP disclosure consent ignored")
        require('UNYRI_SERVER_NAME="' in saved, "Server name not persisted")
        require("0" * 64 not in result.stdout + result.stderr, "Generated fixture credential leaked")
    for bad in ("x" * 65, "bad\tname"):
        settings.unlink(missing_ok=True)
        result = run(command, env=env, input=f"example.invalid\n{bad}\n")
        require(result.returncode == 1 and not settings.exists(), "Invalid name accepted")
    # Execute the exact display/marker command with a public dummy fixture,
    # capturing it privately: first display only, no display on a rerun.
    display = re.search(r"sh -c '([^']*mkdir /app/data/\.owner-setup-code-shown[^']*)'", (ROOT / "scripts/selfhost/common.sh").read_text()).group(1)
    require(display in (ROOT / "scripts/selfhost/common.ps1").read_text(), "Owner display contract differs between installers")
    private = root / "private-backend-fixture"
    private.mkdir()
    dummy = "PUBLIC-TEST-FIXTURE"
    (private / "owner-setup-code").write_text(dummy)
    script = display.replace("/app/data", shlex.quote(native(private)))
    first = run([bash, "--noprofile", "--norc", "-c", script])
    again = run([bash, "--noprofile", "--norc", "-c", script])
    require(first.returncode == 0 and first.stdout == dummy and again.returncode == 0 and again.stdout == "", "Owner code display is not one-time")
    roundtrip = 'source "$SH5_TEST_ROOT/scripts/selfhost/common.sh"; valid_server_name "$SH5_TEST_NAME" && [[ $(unquote_server_name "$(quote_server_name "$SH5_TEST_NAME")") == "$SH5_TEST_NAME" ]]'
    result = run([bash, "--noprofile", "--norc", "-c", roundtrip], env={**env, "LC_ALL": "C", "SH5_TEST_NAME": "Б" * 64})
    require(result.returncode == 0, "Unicode name length/round trip depends on host locale")
    print("Bash installer: prompts/consent, names, port ownership, update modes, exit 1/3 and private owner handoff passed")


def check_windows(work):
    powershell = shutil.which("powershell.exe") or shutil.which("pwsh")
    if not powershell:
        print("SKIP mocked Windows execution: PowerShell unavailable (run parser checks on Windows)")
        return
    root = work / "mock-windows"
    scripts = root / "scripts/selfhost"
    scripts.mkdir(parents=True)
    for name in ("common.ps1", "install.ps1", "preflight-ports.ps1", "firewall.ps1"):
        (scripts / name).write_bytes((ROOT / "scripts/selfhost" / name).read_bytes())
    (root / "SELFHOST_VERSION").write_text(VERSION)
    settings = root / "private-settings.txt"
    original = 'UNYRI_SELFHOST_FORMAT=1\nUNYRI_DOMAIN=example.invalid\nCOMPOSE_PROFILES=\nUNYRI_SERVER_NAME="UNYRI"\n'
    settings.write_text(original)
    with (scripts / "common.ps1").open("a", encoding="utf-8") as output:
        output.write('''
$SelfhostEnv = Join-Path $SelfhostRoot 'private-settings.txt'
function Test-SelfhostDocker { }
function Protect-SelfhostPath([string]$Path) { }
function Invoke-SelfhostCompose {
    $operation = $args -join ' '
    [IO.File]::AppendAllText((Join-Path $SelfhostRoot 'compose-calls.txt'), $operation + "`n")
    if ($operation -eq 'ps --all --quiet backend') { return 'fixture-backend' }
    if ($operation -like 'exec -T backend sh -c *owner-setup-code*') { if ($env:SH5_OWNER_STATE) { return $env:SH5_OWNER_STATE }; return 'available' }
    if ($operation -like 'up *' -and $env:SH5_START_FAIL -eq '1') { throw 'Mock startup failure.' }
}
''')
    runner = root / "run.ps1"
    runner.write_text('''
$ErrorActionPreference = 'Stop'
$global:answers = New-Object 'System.Collections.Generic.Queue[string]'
foreach ($answer in ([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'answers.json')) | ConvertFrom-Json)) { $global:answers.Enqueue([string]$answer) }
function Read-Host([string]$Prompt) { if ($global:answers.Count -eq 0) { throw 'Unexpected prompt in offline test.' }; return $global:answers.Dequeue() }
function docker {
    $global:LASTEXITCODE = 0
    # Fixtures keep the "name|project" and "id|project|name" shapes; answer the
    # real calls: a label filter or names only, and ID|Names|Labels for ps.
    if ($args[0] -eq 'volume') {
        if ($env:SH5_VOLUME_FAIL -eq '1') { $global:LASTEXITCODE = 1; return }
        if (-not $env:SH5_VOLUMES) { return }
        $rows = @($env:SH5_VOLUMES -split "`n" | Where-Object { $_ })
        if ($args -contains '--filter') {
            $filter = $args[[array]::IndexOf($args, '--filter') + 1]
            if ($filter -ne 'label=com.docker.compose.project=unyri-selfhost') { throw "Unexpected volume filter: $filter" }
            return @($rows | ForEach-Object { $e = $_ -split '\\|', 2; if ($e.Count -ge 2 -and $e[1] -eq 'unyri-selfhost') { $e[0] } })
        }
        return @($rows | ForEach-Object { ($_ -split '\\|', 2)[0] })
    }
    if ($args[0] -eq 'ps') {
        if (($args -join ' ') -ne 'ps --format {{.ID}}|{{.Names}}|{{.Labels}}') { throw ('Unexpected ps call: ' + ($args -join ' ')) }
        if (-not $env:SH5_CONTAINERS) { return }
        return @($env:SH5_CONTAINERS -split "`n" | Where-Object { $_ } | ForEach-Object {
            $e = $_ -split '\\|', 3; $e[0] + '|' + $e[2] + '|com.docker.compose.version=2,com.docker.compose.project=' + $e[1] })
    }
    if (($args -join ' ') -like '*NetworkSettings.Ports*') { if ($env:SH5_PS_BINDINGS) { return $env:SH5_PS_BINDINGS }; return '{}' }
    return 'Server image: fixture:9.8.7; image ID: sha256:test'
}
function Get-NetTCPConnection { [CmdletBinding()]param(); if ($env:SH5_PS_TCP) { foreach ($socket in ($env:SH5_PS_TCP | ConvertFrom-Json)) { $socket } } }
function Get-NetUDPEndpoint { [CmdletBinding()]param(); if ($env:SH5_PS_UDP) { foreach ($socket in ($env:SH5_PS_UDP | ConvertFrom-Json)) { $socket } } }
function Get-Process { [CmdletBinding()]param($Id); if ($Id -eq 0) { return }; if ($Id -eq 55) { [pscustomobject]@{ ProcessName = 'coturn' } } else { [pscustomobject]@{ ProcessName = 'com.docker.backend' } } }
function Start-Process {
    [CmdletBinding()]param($FilePath,$ArgumentList,$Verb,[switch]$Wait,[switch]$PassThru,$WindowStyle)
    $correct = $FilePath -eq 'powershell.exe' -and $Verb -eq 'RunAs' -and $Wait -and $PassThru -and $WindowStyle -eq 'Hidden' -and ($ArgumentList -join ' ') -like '*firewall.ps1*'
    [IO.File]::AppendAllText((Join-Path $PSScriptRoot 'firewall-requests.txt'), "$correct`n")
    throw 'Mock UAC decline.'
}
function New-NetFirewallRule { throw 'Unelevated installer attempted to add a rule.' }
function Start-Sleep { param($Seconds) }
function Invoke-RestMethod {
    [CmdletBinding()]param($Uri,$TimeoutSec,$MaximumRedirection)
    if ($Uri -eq 'https://api.ipify.org') {
        [IO.File]::AppendAllText((Join-Path $PSScriptRoot 'ip-requests.txt'), 'ipify')
        if ($env:SH5_IP_VALUE) { return $env:SH5_IP_VALUE }; return '203.0.113.10'
    }
    if ($env:SH5_HTTPS_FAIL -eq '1') { throw 'Mock HTTPS failure.' }
    return [pscustomobject]@{ status = 'ok'; database = $true; redis = $true }
}
& (Join-Path $PSScriptRoot 'scripts/selfhost/install.ps1')
exit $LASTEXITCODE
''', encoding="utf-8-sig")
    command = [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(runner)]
    env = {**os.environ}

    def execute(answers, extra=None):
        (root / "answers.json").write_text(json.dumps(answers), encoding="utf-8")
        return run(command, env={**env, **(extra or {})})

    result = execute(["n"])
    require(result.returncode == 0 and "Firewall permission was declined" in result.stdout and "coco-admin grant-owner USERNAME" in result.stdout, "Unelevated/cancelled-firewall install failed: " + result.stdout + result.stderr)
    require((root / "firewall-requests.txt").read_text().splitlines() == ["True"] and result.stdout.count("New-NetFirewallRule -Name") == 8, "Firewall rules/elevation request differed from the displayed manual commands")
    require("TCP 80,443,3478; UDP 3478,49152-49200" in result.stdout, "Exact UPnP ports missing")
    result = execute(["n"], {"SH5_HTTPS_FAIL": "1"})
    require(result.returncode == 3 and "NAT loopback" in result.stdout, "Windows outside-only failure must return 3")
    result = execute(["n"], {"SH5_START_FAIL": "1"})
    require(result.returncode == 1, "Windows startup failure must return 1")
    calls = root / "compose-calls.txt"
    for extra, expected in [
        ({"SH5_PS_UDP": '[{"LocalPort":3478,"OwningProcess":55}]'}, "3478 UDP: coturn"),
        ({"SH5_PS_TCP": '[{"LocalPort":443,"OwningProcess":0,"State":"Listen"}]'}, "443 TCP: unknown program"),
        ({"SH5_CONTAINERS": "foreign|other-project|other-turn", "SH5_PS_BINDINGS": '{"3478/udp":[{"HostPort":"3478"}]}', "SH5_PS_UDP": '[{"LocalPort":3478,"OwningProcess":42}]'}, "Docker container other-turn"),
    ]:
        calls.unlink(missing_ok=True)
        result = execute([], extra)
        require(result.returncode == 1 and expected in result.stdout, "Windows busy port missing: " + result.stdout + result.stderr)
        require(not calls.exists(), "Windows collision reached Compose")
    result = execute(["n"], {"SH5_CONTAINERS": "own|unyri-selfhost|own-turn", "SH5_PS_BINDINGS": '{"3478/udp":[{"HostPort":"3478"}]}', "SH5_PS_UDP": '[{"LocalPort":3478,"OwningProcess":42}]'})
    require(result.returncode == 0, "Windows Docker Desktop own rerun blocked: " + result.stdout + result.stderr)
    require(settings.read_text() == original, "Windows update changed settings")
    result = execute(["n"], {"SH5_OWNER_STATE": "shown"})
    require(result.returncode == 0 and 'already shown once' in result.stdout and 'exec -T backend cat /app/data/owner-setup-code | ForEach-Object { [Console]::WriteLine($_) }' in result.stdout and 'if (-not [Console]::IsOutputRedirected)' in result.stdout and 'Do not paste the code anywhere' in result.stdout, "Windows private code redisplay command missing")
    result = execute(["n"], {"SH5_OWNER_STATE": "absent"})
    require(result.returncode == 0 and 'exec -T backend cat /app/data/owner-setup-code' not in result.stdout, "Windows redisplay offered after code removal")
    rooms = {"SH5_PS_TCP": '[{"LocalPort":17881,"OwningProcess":0,"State":"Listen"}]', "SH5_PS_UDP": '[{"LocalPort":17882,"OwningProcess":0}]'}
    result = execute(["n"], rooms)
    require(result.returncode == 0, "Windows disabled room ports blocked installation")
    settings.write_text(original.replace('COMPOSE_PROFILES=\n', 'COMPOSE_PROFILES=rooms\n'))
    result = execute([], rooms)
    require(result.returncode == 1 and "17881 TCP, 17882 UDP: unknown program" in result.stdout, "Windows grouped room port conflicts missing")
    calls.unlink(missing_ok=True)
    bindings = {f'{port}/{protocol}': [{"HostPort": str(port)}] for port, protocol in [(3478, 'tcp'), (3478, 'udp')] + [(port, 'udp') for port in range(49152, 49201)]}
    result = execute([], {"SH5_CONTAINERS": "foreign|other-project|messenger-coturn", "SH5_PS_BINDINGS": json.dumps(bindings), "SH5_PS_TCP": '[{"LocalPort":3478,"OwningProcess":42,"State":"Listen"}]', "SH5_PS_UDP": '[{"LocalPort":49152,"OwningProcess":42}]'})
    require(result.returncode == 1 and '3478 TCP, 3478 UDP, 49152-49200 UDP: Docker container messenger-coturn. Stop or reconfigure it, then rerun.' in result.stdout and not calls.exists(), "Windows TURN conflict was not grouped before Compose")
    settings.unlink(); calls.unlink(missing_ok=True)
    inventory = '\n'.join(['unyri-selfhost_postgres_data|unyri-selfhost', 'unyri-selfhost_backend_data|', 'custom_project_data|unyri-selfhost', 'foreign_data|another-project'])
    result = execute([], {"SH5_VOLUMES": inventory})
    require(result.returncode == 1 and not settings.exists() and not calls.exists(), "Windows fresh install wrote settings/started over old data")
    require(all(text in result.stdout for text in ['Old server data was found', 'put the old .env back', "docker volume rm -- 'unyri-selfhost_postgres_data'", "docker volume rm -- 'unyri-selfhost_backend_data'", "docker volume rm -- 'custom_project_data'", 'ForEach-Object { docker rm -f $_ }']), "Windows old-data commands incomplete")
    require('foreign_data' not in result.stdout, "Windows removal commands included foreign volume")
    result = execute([], {"SH5_VOLUME_FAIL": "1"})
    require(result.returncode == 1 and not settings.exists(), "Windows volume inspection failure did not fail closed")
    settings.write_text(original)
    result = execute(["n"], {"SH5_VOLUMES": inventory})
    require(result.returncode == 0 and settings.read_text() == original, "Windows existing config/volumes no longer update")
    for detect, expected, consent in [(["n", "203.0.113.8"], "203.0.113.8", False), (["y", "y"], "203.0.113.10", True), (["y", "n", "203.0.113.8"], "203.0.113.8", True)]:
        settings.unlink(missing_ok=True)
        requests = root / "ip-requests.txt"
        requests.unlink(missing_ok=True)
        result = execute(["example.invalid", 'Family "space" $HOME #1 \\ test', *detect, "n", "n"], {"SH5_VOLUMES": "foreign_data|another-project"})
        require(result.returncode == 0, result.stdout + result.stderr)
        require(f"TURN_EXTERNAL_IP={expected}" in settings.read_text(), "Windows IP choice changed")
        require(requests.exists() == consent, "Windows IP disclosure consent ignored")
    # Exercise the real encoders/validators with no configuration file read.
    validation = root / "names.ps1"
    validation.write_text('''
. (Join-Path $PSScriptRoot 'scripts/selfhost/common.ps1')
foreach ($name in @('UNYRI', ('x' * 64), 'Family "space" $HOME #1 \\ test', 'Борис')) {
    if (-not (Test-SelfhostServerName $name) -or (ConvertFrom-SelfhostServerName (ConvertTo-SelfhostServerName $name)) -ne $name) { throw 'Name round trip failed.' }
}
foreach ($name in @('', ('x' * 65), "bad`tname", "bad`nname")) { if (Test-SelfhostServerName $name) { throw 'Invalid name accepted.' } }
''', encoding="utf-8-sig")
    result = run(command[:-1] + [str(validation)], env=env)
    require(result.returncode == 0, result.stdout + result.stderr)
    print("Windows mocks: unelevated install/UAC decline, Docker Desktop port ownership, consent/name prompts and exit 1/3 passed")


def check_backup_contract(work, bash):
    root = work / "mock-backup"
    scripts = root / "scripts/selfhost"
    scripts.mkdir(parents=True)
    for name in ("backup.sh", "restore.sh"):
        (scripts / name).write_bytes((ROOT / "scripts/selfhost" / name).read_bytes())
    settings = root / "private-settings.txt"
    settings.write_text('UNYRI_SELFHOST_FORMAT=1\nCOMPOSE_PROFILES=\n')
    fixture = '''
selfhost_env="$selfhost_root/private-settings.txt"
check_docker() { :; }
dc() { printf 'compose %s\\n' "$*" >> "$selfhost_root/operations.txt"; }
# Neither mock opens a source or target .env file.
cp() { :; }
cmp() { return 0; }
docker() {
    printf 'docker %s\\n' "$*" >> "$selfhost_root/operations.txt"
    case "$*" in
        'image ls --quiet '*) echo helper-present ;;
        'volume inspect '*) : ;;
        'volume ls '*) : ;;
        *'tar -tzf '*) echo 'private-owner-file-name-must-not-be-listed' ;;
    esac
}
'''
    (scripts / "common.sh").write_text((ROOT / "scripts/selfhost/common.sh").read_text() + fixture, newline="\n")
    result = run([bash, "--noprofile", "--norc", str(scripts / "backup.sh")])
    require(result.returncode == 0 and "private-owner-file-name" not in result.stdout + result.stderr, "Backup failed or listed private files")
    operations = (root / "operations.txt").read_text()
    require("source=unyri-selfhost_backend_data,target=/data,readonly" in operations, "Backend state omitted from backup")
    require(operations.count("tar -czf") == 6 and operations.count("tar -tzf") == 6, "Backup did not validate all six archives")
    snapshot = root / "snapshot"
    snapshot.mkdir()
    (snapshot / "COMPLETE").write_text("six-volume fixture")
    for volume in ("postgres_data", "redis_data", "minio_data", "caddy_data", "caddy_config", "backend_data"):
        (snapshot / f"{volume}.tgz").touch()
    (root / "operations.txt").write_text("")
    result = run([bash, "--noprofile", "--norc", str(scripts / "restore.sh"), native(snapshot)])
    require(result.returncode == 0 and "private-owner-file-name" not in result.stdout + result.stderr, "Restore failed or listed private files")
    operations = (root / "operations.txt").read_text()
    require("source=unyri-selfhost_backend_data,target=/data" in operations and operations.count("sh /restore-volume.sh") == 6, "Restore omitted backend state")
    # All restore inputs must be checked before any destination is populated.
    (snapshot / "backend_data.tgz").unlink()
    (root / "operations.txt").write_text("")
    result = run([bash, "--noprofile", "--norc", str(scripts / "restore.sh"), native(snapshot)])
    require(result.returncode == 1 and "sh /restore-volume.sh" not in (root / "operations.txt").read_text(), "Incomplete six-volume restore was started")
    print("Backup/restore mocks: six-volume backend state, private listing suppression and incomplete-restore refusal passed")


def main():
    bash = shutil.which("bash")
    require(bash, "Bash required for offline downloader checks")
    workflow = (ROOT / ".github/workflows/server-release.yml").read_text()
    actions = re.findall(r"uses:\s*(\S+)", workflow)
    require(actions and all(re.fullmatch(r"[^@]+@[0-9a-f]{40}", action) for action in actions), "Action is not pinned to a full SHA")
    lines = workflow.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("        run: "):
            continue
        script = line[len("        run: "):]
        if script == "|":
            block = []
            for child in lines[index + 1:]:
                if child and not child.startswith("          "):
                    break
                block.append(child[10:])
            script = "\n".join(block)
        result = run([bash, "--noprofile", "--norc", "-n"], input=script)
        require(result.returncode == 0, result.stderr)
    for path in (ROOT / "scripts/selfhost").glob("*.sh"):
        result = run([bash, "--noprofile", "--norc", "-n", str(path)])
        require(result.returncode == 0, result.stderr)
    # Windows PowerShell 5.1 strips double quotes embedded in native-command
    # arguments: '{{.Label "x"}}' reached Docker as '{{.Label x}}' and broke every
    # fresh Windows install (18 Nov). The mocked run below cannot see this.
    for path in sorted((ROOT / "scripts/selfhost").glob("*.ps1")) + sorted(p for p in (ROOT / "admin-desktop").rglob("*.ps1") if "build" not in p.relative_to(ROOT).parts):
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            code = line.split("#", 1)[0]
            if re.search(r"&\s*(?:docker|\$\w+)\b.*'[^'\n]*\"[^'\n]*'", code):
                require(False, f"{path.relative_to(ROOT)}:{number}: native argument with an embedded double quote (stripped by Windows PowerShell 5.1)")
    require((ROOT / "server/storage-client-uploads-policy.template.json").read_bytes().replace(b"\r\n", b"\n") ==
            (ROOT / "scripts/selfhost/storage-client-uploads-policy.template.json").read_bytes().replace(b"\r\n", b"\n"), "Source storage policy copy drifted")
    with tempfile.TemporaryDirectory(prefix=".sh5-check-", dir=ROOT) as temporary:
        work = Path(temporary)
        dist = work / "dist"
        build(ROOT, "ExampleOwner", VERSION, dist)
        with tarfile.open(dist / ARCHIVE, "r:gz") as archive:
            files = {entry.name: archive.extractfile(entry).read() for entry in archive}
        with zipfile.ZipFile(dist / f"unyri-selfhost-{VERSION}.zip") as archive:
            require({name: archive.read(name) for name in archive.namelist()} == files, "ZIP and tar differ")
        require(set(files) <= ALLOWED, "Bundle contains files outside the explicit operator allowlist: " + str(set(files) - ALLOWED))
        expected = set(ALLOWED)
        guides = ("docs/SELF_HOSTING.md", "docs/SELF_HOSTING_EN.md")
        if not any(b"../scripts/conference/README.md" in files[name] for name in guides):
            expected.remove("scripts/conference/README.md")
        require(set(files) == expected, "Required operator files are missing")
        for name in guides:
            for link in re.findall(r'\]\(([^)]+)\)', files[name].decode()):
                if not re.match(r"https?://|#", link):
                    resolved = os.path.normpath(str(Path(name).parent / link)).replace("\\", "/")
                    require(resolved in files, "Operator guide links to omitted file: " + resolved)
        optional = work / "optional-guide"
        (optional / "docs").mkdir(parents=True)
        for name in ("SELF_HOSTING.md", "SELF_HOSTING_EN.md"):
            (optional / "docs" / name).write_text("No room guide link")
        require("scripts/conference/README.md" not in bundle_paths(optional), "Unlinked room guide was included")
        for name in ("backup.sh", "backup.ps1", "restore.sh", "restore.ps1", "restore-volume.sh"):
            require(b"backend_data" in files["scripts/selfhost/" + name], "Private backend volume missing from " + name)
        dockerfile = (ROOT / "server/Dockerfile").read_text()
        require("chown 10001:10001 /app/data" in dockerfile and "chmod 700 /app/data" in dockerfile and "COCO_DATA_DIR=/app/data" in dockerfile, "Backend volume initialization missing")
        require(files["SHA256SUMS"] == checksums({name: data for name, data in files.items() if name != "SHA256SUMS"}), "Internal manifest mismatch")
        archives = {path.name: path.read_bytes() for path in dist.iterdir() if path.name != "SHA256SUMS"}
        require((dist / "SHA256SUMS").read_bytes() == checksums(archives), "External manifest mismatch")
        require(not any(".env" == part or part == ".secrets" for name in files for part in Path(name).parts), "Forbidden bundle path")
        require(b"ghcr.io/OWNER/" not in files["docker-compose.selfhost.yml"], "Unstamped image placeholder")
        print(f"Bundle: {len(files)} operator files; ZIP/tar parity, internal/external SHA256, version stamping and path allowlist passed")
        check_compose(work, files)
        check_port_grouping(work, bash)
        check_downloader(work, files, bash)
        check_installer(work, bash)
        check_windows(work)
        check_backup_contract(work, bash)
    print("Offline self-host release checks passed")


if __name__ == "__main__":
    main()
