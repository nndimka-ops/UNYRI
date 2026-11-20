#!/usr/bin/env bash
# Download to files, verify, then run the installer. Never curl | sh here.
set -euo pipefail
repo= version=latest destination=/opt/unyri
while (( $# )); do
    case "$1" in
        --repo|--version|--directory)
            (( $# >= 2 )) || { echo "Missing value for $1" >&2; exit 1; }
            case "$1" in
                --repo) repo=$2 ;;
                --version) version=$2 ;;
                --directory) destination=$2 ;;
            esac
            shift 2 ;;
        *) echo 'Usage: get.sh --repo OWNER/REPO [--version server-vX.Y.Z|X.Y.Z|latest] [--directory /opt/unyri]' >&2; exit 1 ;;
    esac
done
[[ $repo =~ ^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$ ]] || { echo 'Supply --repo OWNER/REPO.' >&2; exit 1; }
for tool in curl python3 sha256sum mktemp; do
    command -v "$tool" >/dev/null || { echo "Install $tool, then rerun." >&2; exit 1; }
done
[[ $destination == /* && $destination != / && ! -L $destination ]] || { echo 'Use an absolute installation directory, not / or a symlink.' >&2; exit 1; }
umask 077
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
download() { curl --fail --show-error --silent --location --proto '=https' --proto-redir '=https' --tlsv1.2 --retry 3 --connect-timeout 15 --max-time 600 "$1" -o "$2"; }
if [[ $version == latest ]]; then
    # A mobile release may be GitHub's overall latest. Select a stable server
    # release instead; an older release can always be selected explicitly.
    download "https://api.github.com/repos/$repo/releases?per_page=100" "$work/releases.json"
    version=$(python3 - "$work/releases.json" <<'PY'
import json, re, sys
with open(sys.argv[1]) as stream:
    releases = json.load(stream)
for release in releases:
    tag = release.get("tag_name", "")
    if not release.get("draft") and not release.get("prerelease") and re.fullmatch(r"server-v[0-9]+\.[0-9]+\.[0-9]+", tag):
        print(tag)
        break
else:
    sys.exit("No stable server release in the most recent 100 releases; supply --version server-vX.Y.Z.")
PY
)
fi
version=${version#server-v}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+([.-][a-z0-9]+)*)?$ ]] || { echo 'Invalid server version.' >&2; exit 1; }
archive="unyri-selfhost-$version.tar.gz"
base="https://github.com/$repo/releases/download/server-v$version"
echo "Downloading UNYRI server $version to $destination"
download "$base/SHA256SUMS" "$work/SHA256SUMS"
download "$base/$archive" "$work/$archive"
# Do not let a remote checksum list select local filenames or extra paths.
expected=$(python3 - "$work/SHA256SUMS" "$archive" <<'PY'
import re, sys
with open(sys.argv[1]) as stream:
    hashes = [match[1] for line in stream if (match := re.fullmatch(r"([0-9a-f]{64})  " + re.escape(sys.argv[2]) + r"\n?", line))]
if len(hashes) != 1:
    sys.exit("Missing or duplicate archive checksum")
print(hashes[0])
PY
)
actual=$(sha256sum "$work/$archive")
[[ ${actual%% *} == "$expected" ]] || { echo 'SHA256 mismatch: refusing to unpack or run anything. Existing installation unchanged.' >&2; exit 1; }
mkdir "$work/bundle"
python3 - "$work/$archive" "$work/bundle" "$version" <<'PY'
import hashlib, pathlib, re, sys, tarfile
root = pathlib.Path(sys.argv[2])
allowed_files = {
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
with tarfile.open(sys.argv[1], "r:gz") as archive:
    seen = set()
    for member in archive.getmembers():
        name = member.name
        path = pathlib.PurePosixPath(name)
        allowed = name in allowed_files
        if not member.isfile() or not allowed or path.is_absolute() or ".." in path.parts or name in seen or str(path) != name:
            sys.exit("Unsafe or duplicate archive entry: " + name)
        seen.add(name)
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open("wb") as output:
            output.write(source.read())
        target.chmod(0o755 if name.endswith(".sh") else 0o644)
required = allowed_files - {"scripts/conference/README.md",
    "scripts/selfhost/desktop-firewall.ps1", "scripts/selfhost/desktop-manage.ps1",
    "scripts/selfhost/desktop-manage.sh", "scripts/selfhost/desktop-upnp.ps1",
    "scripts/selfhost/desktop-upnp.sh"}
if not required <= seen:
    sys.exit("Required operator files missing from bundle")
rooms_linked = any("../scripts/conference/README.md" in (root / guide).read_text(encoding="utf-8")
                   for guide in ("docs/SELF_HOSTING.md", "docs/SELF_HOSTING_EN.md"))
if ("scripts/conference/README.md" in seen) != rooms_linked:
    sys.exit("Room guide presence must match the operator guide links")
manifest = {}
for line in (root / "SHA256SUMS").read_text().splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
    if not match or match[2] in manifest:
        sys.exit("Invalid internal SHA256SUMS")
    manifest[match[2]] = match[1]
if set(manifest) != seen - {"SHA256SUMS"}:
    sys.exit("Internal checksum file list differs from archive")
for name, digest in manifest.items():
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        sys.exit("Internal SHA256 mismatch: " + name)
if (root / "SELFHOST_VERSION").read_text().strip() != sys.argv[3]:
    sys.exit("Bundle version differs from selected release")
if not (root / "scripts/selfhost/install.sh").is_file():
    sys.exit("Bundle installer missing")
PY
# Copy only verified bundle files. Never replace .env, backups or named volumes.
# Refuse symlinks in an existing installation before writing any file.
python3 - "$work/bundle" "$destination" <<'PY'
import pathlib, shutil, sys
source, target = map(pathlib.Path, sys.argv[1:])
files = [path for path in source.rglob("*") if path.is_file()]
for path in files:
    dest = target / path.relative_to(source)
    for entry in [dest, *dest.parents]:
        if entry.is_symlink():
            sys.exit("Refusing installation through symlink: " + str(entry))
    if dest.exists() and not dest.is_file():
        sys.exit("Destination is not a regular file: " + str(dest))
target.mkdir(parents=True, exist_ok=True)
for path in files:
    dest = target / path.relative_to(source)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, dest)
PY
echo "Verified release $version. Starting installer; existing secrets and data are kept."
bash "$destination/scripts/selfhost/install.sh"
