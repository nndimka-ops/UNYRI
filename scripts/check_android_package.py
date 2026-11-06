"""Inspect real APK/AAB native coverage; do not infer device/RAM size from ZIP size."""
import argparse
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("artifact", type=Path)
parser.add_argument("--abi", choices=["arm64-v8a", "armeabi-v7a", "x86_64", "x86"])
args = parser.parse_args()
with ZipFile(args.artifact) as archive:
    assert archive.testzip() is None, "Corrupt archive"
    names = archive.namelist()
    prefix = "base/lib/" if args.artifact.suffix == ".aab" else "lib/"
    libs = [n for n in names if n.startswith(prefix) and n.endswith(".so")]
    abis = {n[len(prefix):].split("/")[0] for n in libs}
    expected = {args.abi} if args.abi else {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
    assert abis == expected, (abis, expected)
    for abi in abis:
        local = [n for n in libs if n.startswith(prefix + abi + "/")]
        assert any("libsignal_jni.so" in n for n in local), (abi, "Signal missing")
        assert any(("webrtc" in n or "libjingle_peerconnection_so.so" in n) for n in local), (abi, "WebRTC missing")
    assert not any("libsignal_jni_testing" in n or n.endswith((".dll", ".dylib")) for n in names), "Unused desktop/test native library"
    print(f"PASS {args.artifact.name}: {args.artifact.stat().st_size / 1048576:.2f} MiB; ABIs={sorted(abis)}")
    for abi in sorted(abis):
        size = sum(archive.getinfo(n).compress_size for n in libs if n.startswith(prefix + abi + "/"))
        print(f"  {abi}: {size / 1048576:.2f} MiB native ZIP entries")
