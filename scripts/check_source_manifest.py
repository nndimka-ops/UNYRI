"""Verify selected public source bytes; no network, builds, Git writes or edits."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath


def verify(root, files):
    checked = 0
    for relative, expected in files.items():
        parts = PurePosixPath(relative)
        if parts.is_absolute() or ".." in parts.parts or "\\" in relative:
            raise ValueError("Manifest path must be a relative POSIX path")
        path = root.joinpath(*parts.parts)
        if path.is_symlink() or not path.is_file():
            raise ValueError("Missing or non-regular selected file: " + relative)
        path.resolve().relative_to(root.resolve())
        digest = hashlib.sha256()
        length = 0
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(262144), b""):
                digest.update(block)
                length += len(block)
        if length != expected["bytes"] or digest.hexdigest() != expected["sha256"]:
            raise ValueError("Selected source differs: " + relative)
        checked += 1
    return checked


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="JSON path-to-{bytes,sha256} map")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    count = verify(args.root, json.loads(args.manifest.read_text(encoding="utf-8")))
    print(json.dumps({"selectedFiles": count, "allBytesMatch": True}))
