"""Offline peer identity policy and UI privacy checks. Never contacts a server."""
from pathlib import Path
import argparse
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
here = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--source-only", action="store_true")
args = parser.parse_args()

# PublicUsername is explicitly public; the compatibility username is ambiguous.
raw_peer_prefix = re.compile(
    r'"@"\s*\+\s*(?:username\b|peerUsername\b|[^\r\n;]*?\.username\b)'
    r'|"@\\\((?:username\b|peerUsername\b|[^\r\n)]*?\.username\b)'
)
ui_files = list((root / "ios/UNYRI/UI").rglob("*.swift"))
ui_files += list((root / "ios/UNYRI/Conferences").glob("*View*.swift"))
ui_files += [root / "ios/UNYRI/UNYRIApp.swift"]
violations = []
for path in ui_files:
    for line, source in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if raw_peer_prefix.search(source):
            violations.append(f"{path.relative_to(root)}:{line}: {source.strip()}")
assert not violations, "UI prefixes @ to a raw peer username:\n" + "\n".join(violations)
print(f"Peer identity source check passed ({len(ui_files)} UI files)")

if not args.source_only:
    with tempfile.TemporaryDirectory(prefix="unyri-peer-identity-") as temp:
        binary = Path(temp) / ("checks.exe" if os.name == "nt" else "checks")
        sources = [root / "ios/UNYRI/Support/PublicIdentityPolicy.swift", here / "main.swift"]
        subprocess.run(["swiftc", "-swift-version", "5",
                        *map(str, sources), "-o", str(binary)], check=True, timeout=300)
        subprocess.run([str(binary)], check=True, timeout=30)
