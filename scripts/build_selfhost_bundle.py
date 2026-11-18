#!/usr/bin/env python3
"""Package the reviewed runtime allowlist, never a workspace/source archive."""
import argparse
import hashlib
import io
from pathlib import Path
import re
import tarfile
import zipfile


# Exact runtime inputs. Source-build files and internal project documents must
# never enter a distributable bundle, including after new files are committed.
RUNTIME_FILES = {
    "LICENSE", "docker-compose.selfhost.yml",
    "docs/SELF_HOSTING.md", "docs/SELF_HOSTING_EN.md",
    *{f"scripts/selfhost/{name}" for name in (
        "Caddyfile", "THIRD_PARTY_NOTICES.md", "backup.sh", "backup.ps1",
        "check-ports.sh", "check-ports.ps1", "common.sh", "common.ps1",
        "preflight-ports.sh", "preflight-ports.ps1", "firewall.ps1",
        "install.sh", "install.ps1", "get.sh", "health-check.sh",
        "init-storage.sh", "restore.sh", "restore.ps1", "restore-volume.sh",
        "start-turn.sh", "start.sh", "start.ps1", "storage-client-uploads-policy.template.json",
    )},
}


def bundle_paths(root):
    paths = set(RUNTIME_FILES)
    if any("../scripts/conference/README.md" in (root / guide).read_text(encoding="utf-8")
           for guide in ("docs/SELF_HOSTING.md", "docs/SELF_HOSTING_EN.md")):
        paths.add("scripts/conference/README.md")
    return paths


def build(root, owner, version, output):
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9-]*", owner):
        raise ValueError("Invalid GitHub owner")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9]+(?:[.-][a-z0-9]+)*)?", version):
        raise ValueError("Use X.Y.Z with an optional lowercase prerelease suffix")
    files = {}
    for name in sorted(bundle_paths(root)):
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Not a regular bundle input: {name}")
        # Git checkouts on Windows can use CRLF; mounted shell scripts require LF.
        files[name] = path.read_bytes().replace(b"\r\n", b"\n")
    policy = root / "server/storage-client-uploads-policy.template.json"
    files["scripts/selfhost/storage-client-uploads-policy.template.json"] = policy.read_bytes().replace(b"\r\n", b"\n")
    compose = files["docker-compose.selfhost.yml"].decode()
    for image in ("server", "minio", "mc"):
        compose = compose.replace(f"ghcr.io/OWNER/unyri-{image}:VERSION", f"ghcr.io/{owner.lower()}/unyri-{image}:{version}")
    files["docker-compose.selfhost.yml"] = compose.encode()
    files["SELFHOST_VERSION"] = (version + "\n").encode()
    files["SHA256SUMS"] = checksums(files)
    output.mkdir(parents=True, exist_ok=True)
    stem = f"unyri-selfhost-{version}"
    archives = {}
    with zipfile.ZipFile(output / f"{stem}.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            archive.writestr(info, data)
    with tarfile.open(output / f"{stem}.tar.gz", "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for name, data in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o755 if name.endswith(".sh") else 0o644
            archive.addfile(info, io.BytesIO(data))
    for suffix in ("zip", "tar.gz"):
        name = f"{stem}.{suffix}"
        archives[name] = (output / name).read_bytes()
    (output / "SHA256SUMS").write_bytes(checksums(archives))
    print(f"Built {stem}.zip, {stem}.tar.gz and SHA256SUMS in {output}")


def checksums(files):
    return "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())).encode()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[1], args.owner, args.version, args.output.resolve())
