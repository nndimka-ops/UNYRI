#!/bin/bash
# UNYRI helper; project license applies. Signal retains its own license/notices.
set -euo pipefail
umask 077

usage() {
  echo 'Usage: bash ios/scripts/restore-signal-ffi.sh --target arm64-simulator|x86_64-simulator|arm64-device [--archive /path/to/official.tar.gz]'
  echo 'Restores one pinned upstream static library; never replaces a different existing library.'
}
target=; supplied=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; target=$2; shift 2 ;;
    --archive) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; supplied=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
case "$target" in
  arm64-simulator|x86_64-simulator|arm64-device) ;;
  *) usage >&2; exit 2 ;;
esac
[[ $(uname -s) == Darwin ]] || { echo 'Run this helper on macOS with Xcode selected.' >&2; exit 2; }
command -v python3 >/dev/null
xcrun --find lipo >/dev/null
script_dir=$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tmp_parent=$(cd -P -- "${TMPDIR:-/tmp}" && pwd)
work=$(mktemp -d "$tmp_parent/unyri-signal-ffi.XXXXXX")
cleanup() {
  # Only these two files can be created here; never recursively delete a caller path.
  rm -f -- "$work/archive.tar.gz" "$work/library.a"
  rmdir -- "$work" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
url=https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.86.5.tar.gz
if [[ -n "$supplied" ]]; then
  archive=$supplied
else
  # 8.4+ enforces max-filesize even when Content-Length is absent.
  python3 - <<'PY'
import re, subprocess
text = subprocess.check_output(['curl', '--version'], text=True)
version = re.match(r'curl (\d+)\.(\d+)\.(\d+)', text)
if not version or tuple(map(int, version.groups())) < (8, 4, 0):
    raise SystemExit('curl 8.4+ is required for bounded downloads; alternatively supply --archive.')
PY
  archive=$work/archive.tar.gz
  curl --disable --fail --show-error --silent --location --proto '=https' \
    --proto-redir '=https' --max-redirs 3 --connect-timeout 15 --max-time 300 \
    --max-filesize 1073741824 --output "$archive" "$url"
fi
python3 - "$archive" "$target" "$script_dir/../libsignal-ffi" "$work/library.a" <<'PY'
from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys, tarfile, tempfile

archive, choice, directory, extracted = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
targets = {'arm64-simulator': ('aarch64-apple-ios-sim', 'iphonesimulator', 'arm64'),
           'x86_64-simulator': ('x86_64-apple-ios', 'iphonesimulator', 'x86_64'),
           'arm64-device': ('aarch64-apple-ios', 'iphoneos', 'arm64')}
rust, sdk, arch = targets[choice]
member = 'target/' + rust + '/release/libsignal_ffi.a'
expected = 'a3df979aa39b307ac4ef0f3fd59536daab5d6b3f8257c7aefe5f0470625f2582'
def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''): h.update(chunk)
    return h.hexdigest()
def regular(path):
    if path.is_symlink() or not path.is_file(): raise SystemExit('Expected a regular, non-symlink file: ' + str(path))
regular(archive)
if not 0 < archive.stat().st_size <= 1024**3: raise SystemExit('Archive exceeds the 1 GiB download/input limit.')
if digest(archive) != expected: raise SystemExit('Official archive SHA256 mismatch; no library installed.')
# Never extract archive paths. Read exactly one regular member into an owned file.
with tarfile.open(archive, 'r:gz') as package:
    selected = None; count = 0; declared = 0
    for item in package:
        count += 1; declared += max(0, item.size)
        if count > 512 or declared > 8 * 1024**3: raise SystemExit('Unexpected archive metadata bounds.')
        if item.name == member:
            if selected is not None or not item.isfile() or item.issparse() or not 0 < item.size <= 512 * 1024**2:
                raise SystemExit('Expected one bounded regular target member, without links or sparse data.')
            selected = item
    if selected is None: raise SystemExit('Requested target is absent from the pinned archive.')
    source = package.extractfile(selected)
    if source is None: raise SystemExit('Cannot read selected archive member.')
    with source, extracted.open('xb') as output:
        remaining = selected.size
        while remaining:
            chunk = source.read(min(1024 * 1024, remaining))
            if not chunk: raise SystemExit('Truncated library member.')
            output.write(chunk); remaining -= len(chunk)
        if source.read(1): raise SystemExit('Unexpected extra library bytes.')
        output.flush(); os.fsync(output.fileno())
with extracted.open('rb') as source:
    if source.read(8) != b'!<arch>\n': raise SystemExit('Selected member is not a normal static archive.')
architectures = subprocess.check_output(['xcrun', 'lipo', '-archs', str(extracted)], text=True).split()
if architectures != [arch]: raise SystemExit('Unexpected target architecture; no library installed.')
library_sha = digest(extracted)
provenance = {'version': '0.86.5', 'source_commit': 'b39e93f1a5e6531044dfcdf5876585cbcf08f884',
              'archive_url': 'https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.86.5.tar.gz',
              'archive_sha256': expected, 'member': member, 'rust_target': rust, 'sdk': sdk, 'architecture': arch,
              'library_sha256': library_sha, 'library_bytes': extracted.stat().st_size,
              'platform_provenance': 'Exact member of the SHA256-pinned upstream archive; not an independent source rebuild.'}
if directory.is_symlink() or (directory.exists() and not directory.is_dir()): raise SystemExit('Library directory must not be a symlink or non-directory.')
directory.mkdir(mode=0o755, exist_ok=True)
library = directory / 'libsignal_ffi.a'; receipt = directory / 'libsignal_ffi.provenance.json'
if receipt.exists() or receipt.is_symlink():
    regular(receipt)
    if not library.exists() or json.loads(receipt.read_bytes()) != provenance: raise SystemExit('Existing provenance differs; no files replaced. Use a separate source checkout for another target.')
if library.exists() or library.is_symlink():
    regular(library)
    if digest(library) != library_sha: raise SystemExit('Existing library differs; no overwrite. Use a separate source checkout for another target.')
def publish(name, write):
    fd, temporary = tempfile.mkstemp(prefix='.unyri-signal-', dir=directory)
    temporary = Path(temporary)
    try:
        with os.fdopen(fd, 'wb') as output: write(output); output.flush(); os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        try: os.link(temporary, name)  # Atomic, no overwrite, same directory/filesystem.
        except FileExistsError: pass  # Rechecked below; a different concurrent target fails closed.
    finally: temporary.unlink()
if not library.exists():
    def copy(output):
        with extracted.open('rb') as source: shutil.copyfileobj(source, output, 1024 * 1024)
    publish(library, copy)
regular(library)
if digest(library) != library_sha: raise SystemExit('Library changed concurrently; no overwrite performed.')
if not receipt.exists(): publish(receipt, lambda output: output.write((json.dumps(provenance, indent=2, sort_keys=True) + '\n').encode()))
regular(receipt)
if json.loads(receipt.read_bytes()) != provenance: raise SystemExit('Provenance changed concurrently; no overwrite performed.')
print('Verified Signal 0.86.5: ' + sdk + '/' + arch + ', SHA256 ' + library_sha)
print('Library: ' + str(library))
print('Provenance: ' + str(receipt))
PY
