"""Blocking R6 regression: a concealed removal must stop an old-room sender.

Compiles the actual monitor, encryption gate and publication serializer against
explicit SDK/API seams. This is an authorization regression, not a native
LiveKit media test. It currently FAILS: R6 has no implemented epoch protocol.
Never invert that assertion or turn an exploit reproduction into a CI pass.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")


def method(marker):
    start = source.index(marker)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func", "func", 1)


methods = "\n".join(method(marker) for marker in [
    "    private func startMonitor(", "    private func verifyEncryption(",
    "    private func runMedia(",
])
fixture = Path(__file__).with_name("Fixture.swift").read_text(encoding="utf-8")
main = Path(__file__).with_name("main.swift")
with tempfile.TemporaryDirectory(prefix="unyri-revocation-") as tmp:
    folder = Path(tmp)
    swift = folder / "Controller.swift"
    swift.write_text(fixture.replace("// PRODUCTION_METHODS", methods), encoding="utf-8")
    executable = folder / "contracts"
    subprocess.run(["swiftc", "-parse-as-library", str(swift), str(main), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
