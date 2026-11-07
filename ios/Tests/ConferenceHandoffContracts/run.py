"""Run the real requester state machine with explicit in-memory dependencies.
Only Combine observation wrappers are omitted on Windows. No crypto is replaced
in production, and this is not evidence of real Signal/native media behavior.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Conferences/ConferenceKeyHandoff.swift").read_text(encoding="utf-8")
source = source.replace("import Combine\n", "").replace(": ObservableObject {", " {").replace("@Published ", "")
sources = ["ios/UNYRI/Signal/MessageContentCodec.swift", "ios/UNYRI/Models/SignalModels.swift",
           "ios/UNYRI/Conferences/ConferenceInvite.swift", "ios/UNYRI/Conferences/ConferenceDirectoryModels.swift",
           "ios/Tests/ConferenceHandoffContracts/Fixtures.swift", "ios/Tests/ConferenceHandoffContracts/main.swift"]
with tempfile.TemporaryDirectory(prefix="coco-room-handoff-") as temp:
    folder = Path(temp)
    production = folder / "ConferenceKeyHandoff.swift"
    production.write_text(source, encoding="utf-8")
    executable = folder / ("contracts.exe" if os.name == "nt" else "contracts")
    subprocess.run(["swiftc", "-parse-as-library", str(production), *[str(ROOT / s) for s in sources], "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=20)
