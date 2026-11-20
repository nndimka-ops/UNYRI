"""Run the production URLSession contract against a bounded loopback fixture."""
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time

root = pathlib.Path(__file__).resolve().parents[3]
fixture = pathlib.Path(__file__).with_name("fixture.py")
temp_dir = None
if len(sys.argv) >= 2:
    binary = sys.argv[1]
else:
    temp_dir = tempfile.TemporaryDirectory(prefix="unyri-history-media-http-")
    binary = str(pathlib.Path(temp_dir.name) / ("contracts.exe" if os.name == "nt" else "contracts"))
    swiftc = shutil.which("swiftc") or "swiftc"
    sources = [
        root / "ios/UNYRI/Attachments/AttachmentCacheBoundary.swift",
        root / "ios/UNYRI/Support/HistoryArchive.swift",
        root / "ios/UNYRI/Support/HistoryMedia.swift",
        root / "ios/UNYRI/Support/HistoryBackupCoordinator.swift",
        root / "ios/UNYRI/Networking/HistoryBackupAPI.swift",
        pathlib.Path(__file__).with_name("main.swift"),
    ]
    compiled = subprocess.run([swiftc, "-parse-as-library", *[str(s) for s in sources], "-o", binary], capture_output=True, timeout=120)
    if compiled.returncode != 0:
        sys.stderr.buffer.write(compiled.stdout + compiled.stderr)
        temp_dir.cleanup()
        raise SystemExit(compiled.returncode)

try:
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    process = subprocess.Popen([sys.executable, str(fixture), str(port)])
    try:
        for _ in range(600):
            if process.poll() is not None:
                raise RuntimeError("HTTP fixture exited before startup")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.05)
        else:
            raise TimeoutError("HTTP fixture startup timeout")
        subprocess.run([binary, f"http://127.0.0.1:{port}"], check=True, timeout=90)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
finally:
    if temp_dir is not None:
        temp_dir.cleanup()
