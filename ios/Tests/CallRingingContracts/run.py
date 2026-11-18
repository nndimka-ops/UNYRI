"""Compile and run Foundation-only ringing contracts; no server or iOS SDK needed.

Usage: python3 ios/Tests/CallRingingContracts/run.py
Set SWIFTC to an explicit compiler path when it is not available on PATH.
"""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'runtime-logs/call-ringing-contracts'
OUT.mkdir(parents=True, exist_ok=True)
swift = os.environ.get('SWIFTC') or shutil.which('swiftc')
if not swift and os.name == 'nt':
    candidate = Path(os.environ['LOCALAPPDATA']) / 'Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe'
    if candidate.is_file():
        swift = str(candidate)
if not swift:
    raise SystemExit('Swift compiler unavailable; set SWIFTC to your Swift compiler path.')
files = [ROOT / name for name in [
    'ios/UNYRI/Support/HistoryArchive.swift',
    'ios/UNYRI/Calls/CallModels.swift',
    'ios/UNYRI/Calls/CallSignaling.swift',
]]
exe = OUT / ('contracts.exe' if os.name == 'nt' else 'contracts')
subprocess.run([swift, *map(str, files), str(Path(__file__).with_name('main.swift')),
                '-o', str(exe)], check=True, timeout=120, cwd=ROOT)
subprocess.run([str(exe)], check=True, timeout=30, cwd=ROOT)
