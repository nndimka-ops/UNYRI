"""Run the Foundation-only paste routing contract without an iOS SDK.

Usage: python3 ios/Tests/ChatComposerPasteContracts/run.py
Set SWIFTC if swiftc is not on PATH.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
swift = os.environ.get('SWIFTC') or shutil.which('swiftc')
if not swift and os.name == 'nt':
    candidate = Path(os.environ['LOCALAPPDATA']) / 'Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe'
    if candidate.is_file():
        swift = str(candidate)
if not swift:
    raise SystemExit('Swift compiler unavailable; set SWIFTC to your Swift compiler path.')
with tempfile.TemporaryDirectory(prefix='unyri-composer-paste-') as temporary:
    executable = Path(temporary) / ('contracts.exe' if os.name == 'nt' else 'contracts')
    subprocess.run([swift, '-parse-as-library', str(root / 'ios/UNYRI/Support/ChatComposerPastePolicy.swift'),
                    str(Path(__file__).with_name('main.swift')), '-o', str(executable)],
                   check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=30)
