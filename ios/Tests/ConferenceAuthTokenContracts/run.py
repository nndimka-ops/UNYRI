"""Production HTTP error forwarding + production auth token fence methods.
Explicit transport/storage/refresh seams; no credentials, network or Keychain.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]

def member(source, marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func", "func", 1)

api = (ROOT / "ios/UNYRI/Networking/APIClient.swift").read_text(encoding="utf-8")
auth = (ROOT / "ios/UNYRI/Auth/AuthRepository.swift").read_text(encoding="utf-8")
fixture = (ROOT / "ios/Tests/ConferenceAuthTokenContracts/Fixtures.swift").read_text(encoding="utf-8")
for key, source, marker in [
    ("__API_ERROR__", api, "enum APIError:"),
    ("__SEND__", api, "private func send("),
    ("__PARSE_ERROR__", api, "private func parseError("),
    ("__TOKEN_HANDLER__", auth, "func handleAuthenticatedRequestError("),
    ("__TERMINAL_POLICY__", auth, "static func isTerminalAuthFailure(")
]:
    fixture = fixture.replace(key, member(source, marker))
with tempfile.TemporaryDirectory(prefix="coco-room-auth-token-") as temp:
    folder = Path(temp); production = folder / "ExtractedProduction.swift"
    production.write_text(fixture, encoding="utf-8")
    executable = folder / ("contracts.exe" if os.name == "nt" else "contracts")
    subprocess.run(["swiftc", "-parse-as-library", str(production), str(ROOT / "ios/Tests/ConferenceAuthTokenContracts/main.swift"), "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=20)
