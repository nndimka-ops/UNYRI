"""Run the production URLSession contract against a bounded loopback fixture."""
import pathlib
import socket
import subprocess
import sys
import time

fixture = pathlib.Path(__file__).with_name("fixture.py")
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
    subprocess.run([sys.argv[1], f"http://127.0.0.1:{port}"], check=True, timeout=90)
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
