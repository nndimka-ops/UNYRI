"""Compile the production Foundation share code in Swift 5 mode; no iOS mocks.

Run through any of the named *Contracts/run.py entrypoints. Native bindings stay
behind canImport(UIKit); Mac/device verification covers those real boundaries.
"""
from pathlib import Path
import argparse
import os
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def run(suite):
    parser = argparse.ArgumentParser()
    parser.add_argument("--swiftc")
    args = parser.parse_args()
    compiler = args.swiftc or shutil.which("swiftc")
    if not compiler and os.name == "nt":
        candidate = Path(os.environ["LOCALAPPDATA"]) / "Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe"
        if candidate.exists():
            compiler = str(candidate)
    if not compiler:
        raise SystemExit("swiftc not found")
    sources = [ROOT / "ios/Shared" / (name + ".swift") for name in (
        "UnyriShareStore", "ShareContainer", "ShareJob", "ShareRoutePolicy",
        "ShareTargetSnapshot", "ShareSignal", "MessageTextPolicy", "ShareItemClassification")]
    sources += [ROOT / "ios/UNYRI/Share/ShareJobProcessor.swift", Path(__file__).with_name("main.swift")]
    flags = ["-swift-version", "5", "-parse-as-library"]
    if platform.system() == "Darwin":
        flags += ["-target", platform.machine() + "-apple-macosx13.0"]
    with tempfile.TemporaryDirectory(prefix="unyri-share1-") as folder:
        binary = Path(folder) / ("contracts.exe" if os.name == "nt" else "contracts")
        subprocess.run([compiler, *flags, *map(str, sources), "-o", str(binary)], check=True, timeout=120)
        subprocess.run([str(binary), suite], check=True, timeout=30)
