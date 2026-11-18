"""Compile production link/number policy, identity DTOs and error mapping. No network."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
here = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="unyri-public-identity-") as temp:
    binary = Path(temp) / ("checks.exe" if os.name == "nt" else "checks")
    sources = [root / "ios/UNYRI/Support/PublicIdentityPolicy.swift",
               root / "ios/UNYRI/Support/DisplayNameSanitizer.swift",
               root / "ios/Tests/Support/DeviceInfo.swift",
               root / "ios/UNYRI/Models/AuthModels.swift",
               root / "ios/UNYRI/Models/UserModels.swift",
               root / "ios/UNYRI/Models/PublicIdentityModels.swift",
               here / "Fixture.swift", here / "main.swift"]
    subprocess.run(["swiftc", "-swift-version", "5", *map(str, sources), "-o", str(binary)], check=True, timeout=300)
    subprocess.run([str(binary)], check=True, timeout=30)
