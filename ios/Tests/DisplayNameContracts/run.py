"""Compile the real Support/Validation.swift against the server's shared
sanitizing/reserved-name and version vectors, plus the iOS-only 32-scalar edit limit.
UnyriL10n (SwiftUI-backed in the app) is stubbed, so this also runs on Windows.
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
stub = 'enum UnyriL10n {\n static var language = "en"\n static func string(_ key: String) -> String { key }\n}\n'
with tempfile.TemporaryDirectory(prefix='unyri-display-name-') as temp:
    temp = Path(temp)
    (temp / 'L10nStub.swift').write_text(stub, encoding='utf-8')
    sources = [temp / 'L10nStub.swift', root / 'ios/UNYRI/Support/Validation.swift', Path(__file__).resolve().parent / 'main.swift']
    binary = temp / ('checks.exe' if os.name == 'nt' else 'checks')
    subprocess.run(['swiftc', *map(str, sources), '-o', str(binary)], check=True, timeout=300)
    subprocess.run([str(binary)], check=True, timeout=30)
