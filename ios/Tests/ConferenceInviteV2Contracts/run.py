"""Execute actual production invitation codec, sender composer and SQLite/history.

Windows uses the installed WinSQLite3 API; public-alias SHA256 cases are rerun on
the Mac with CryptoKit. No networking, credentials or user workspaces are touched.
"""
from pathlib import Path
import json
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sources = ["ios/UNYRI/Support/AppLanguage.swift", "ios/UNYRI/Support/HistoryArchive.swift",
           "ios/UNYRI/Signal/MessageContentCodec.swift", "ios/UNYRI/Models/MessageModels.swift",
           "ios/UNYRI/Models/SignalModels.swift", "ios/UNYRI/Models/AttachmentModels.swift",
           "ios/UNYRI/Data/MessageStore.swift", "ios/UNYRI/Messages/MessageSendComposer.swift",
           "ios/UNYRI/Conferences/ConferenceInvite.swift", "ios/Tests/ConferenceInviteV2Contracts/main.swift"]
with tempfile.TemporaryDirectory(prefix="coco-conference-v2-contracts-") as temporary:
    folder = Path(temporary)
    default = json.loads((ROOT / "shared/server-origin.json").read_text(encoding="utf-8"))["publicDefaultOrigin"]
    config = folder / "AppConfig.swift"
    config.write_text("enum AppConfig { static let publicDefaultOrigin = " + json.dumps(default) + " }\n", encoding="utf-8")
    flags = []
    if os.name == "nt":
        sdk = Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Windows Kits/10"
        candidates = []
        for include in (sdk / "Include").glob("*"):
            header = include / "um/winsqlite/winsqlite3.h"
            library = sdk / "Lib" / include.name / "um/x64/winsqlite3.lib"
            if header.is_file() and library.is_file(): candidates.append((tuple(map(int, include.name.split('.'))), header, library))
        assert candidates
        _, header, library = max(candidates)
        module = folder / "sqlite"; module.mkdir()
        (module / "module.modulemap").write_text('module SQLite3 [system] { header "' + header.as_posix() + '" link "winsqlite3" export * }\n', encoding="utf-8")
        flags = ["-I", str(module), "-L", str(library.parent)]
    executable = folder / ("conference-v2.exe" if os.name == "nt" else "conference-v2")
    subprocess.run(["swiftc", "-parse-as-library", *flags, str(config), *[str(ROOT / x) for x in sources], "-o", str(executable)], check=True, timeout=120)
    env = dict(os.environ)
    if os.name != "nt": env["UNYRI_REQUIRE_NATIVE_CRYPTO"] = "1"
    subprocess.run([str(executable), str(ROOT / "shared/conference-v2-vectors.json"), str(folder / "stores")], env=env, check=True, timeout=35)
