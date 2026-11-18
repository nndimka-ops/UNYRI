"""Actual key repository and REST methods with an explicit transport seam.
No libsignal, real identities, keys, network or Apple SDK is used.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Signal/KeysRepository.swift").read_text(encoding="utf-8")
source = source.replace("import Combine\n", "").replace(": ObservableObject {", " {")
with tempfile.TemporaryDirectory(prefix="coco-room-exact-keys-") as temp:
    folder = Path(temp)
    production = folder / "KeysRepository.swift"
    production.write_text(source, encoding="utf-8")
    executable = folder / ("contracts.exe" if os.name == "nt" else "contracts")
    sources = ["ios/UNYRI/Models/KeysModels.swift", "ios/UNYRI/Networking/KeysAPI.swift",
               "ios/Tests/ConferenceExactKeysContracts/Fixtures.swift", "ios/Tests/ConferenceExactKeysContracts/main.swift"]
    subprocess.run(["swiftc", "-parse-as-library", str(production), *[str(ROOT / p) for p in sources], "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=20)
