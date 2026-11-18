"""Compile production SH-3 rules in Swift 5 mode. Offline, no iOS SDK needed."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
here = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="unyri-self-hosting-") as temp:
    binary = Path(temp) / ("checks.exe" if os.name == "nt" else "checks")
    sources = [root / "ios/UNYRI/Support/PublicIdentityPolicy.swift",
               root / "ios/UNYRI/Support/SelfHostingPolicy.swift",
               root / "ios/Tests/Support/DeviceInfo.swift",
               root / "ios/UNYRI/Models/AuthModels.swift", here / "main.swift"]
    subprocess.run(["swiftc", "-swift-version", "5", *map(str, sources), "-o", str(binary)],
                   check=True, timeout=300)
    subprocess.run([str(binary)], check=True, timeout=30)
