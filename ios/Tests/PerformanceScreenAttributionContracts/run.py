"""Execute the unchanged production Foundation recorder; no UI/SDK/live service."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--swiftc", default="swiftc")
parser.add_argument("--expect-before-fail", action="store_true")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
root = Path(__file__).resolve().parents[3]
production = root / "ios/UNYRI/Support/PerformanceDiagnostics.swift"
main = Path(__file__).with_name("main.swift")
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
before = sha(production)
toolchain = subprocess.run([args.swiftc, "--version"], capture_output=True, check=True).stdout.decode(errors="replace")
expected_failures = {
    "parent": "Closing the parent must identify that parent, not the active child",
    "unknown": "An unknown token must not fabricate a screen-close event",
    "repeated": "A repeated pop must not fabricate a second screen-close event",
}
results = []
with tempfile.TemporaryDirectory(prefix="coco-screen-attribution-") as temporary:
    executable = Path(temporary) / ("checks.exe" if platform.system() == "Windows" else "checks")
    compiled = subprocess.run([args.swiftc, "-parse-as-library", str(production), str(main), "-o", str(executable)], capture_output=True, timeout=90)
    (args.output / "compile.log").write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, "Inspect compile.log; compilation failure is not a regression counterexample"
    binary_sha = sha(executable)
    for name in ["current", "parent", "unknown", "repeated"]:
        process = subprocess.run([str(executable), name], capture_output=True, timeout=20)
        log = process.stdout + process.stderr
        (args.output / (name + ".log")).write_bytes(log)
        failure = name in expected_failures and expected_failures[name].encode() in log
        valid = process.returncode != 0 and failure if args.expect_before_fail and name in expected_failures else process.returncode == 0
        results.append({"case": name, "exit": process.returncode, "expected_runtime_assertion": failure, "expected_outcome_observed": valid})
unchanged = sha(production) == before
proof = {"expected_mode": "red-baseline" if args.expect_before_fail else "green", "all_expected_outcomes_observed": all(r["expected_outcome_observed"] for r in results),
         "production_source_sha256": before, "production_source_unchanged": unchanged, "test_main_sha256": sha(main), "runner_sha256": sha(Path(__file__)),
         "compiled_binary_sha256": binary_sha, "toolchain": toolchain.strip(), "cases": results, "actual_production_source_executed": True,
         "mac_or_ios_sdk_used": False, "production_source_edited": False, "message_or_account_contents_used": False}
(args.output / "result.json").write_text(json.dumps(proof, indent=2) + "\n")
print(json.dumps(proof, indent=2))
assert proof["all_expected_outcomes_observed"] and unchanged
