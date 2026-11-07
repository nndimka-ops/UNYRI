"""Compile actual repository read admission/flush methods with deterministic adapters."""
from pathlib import Path
import sys, hashlib, json

root = Path(__file__).resolve().parents[3]
source = root / "ios/UNYRI/Messages/MessagesRepository.swift"
text = source.read_text(encoding="utf-8")
markers = ["    private var readPresentationAllowed: Bool {", "    var activeChatId: String? {", "    func setVisibleReadSequence(", "    func presentationVisibilityChanged()", "    func markReadLatest(", "    func flushReadReceipts()"]
blocks = []
for marker in markers:
    start = text.index(marker); end = text.index("{", start) + 1; depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}"); end += 1
    blocks.append(text[start:end])
fixture = (Path(__file__).parent / "Fixture.swift").read_text(encoding="utf-8")
output = Path(sys.argv[1]); output.mkdir(parents=True, exist_ok=True)
(output / "main.swift").write_text(fixture.replace("// PRODUCTION_READ_METHODS", "\n".join(blocks)), encoding="utf-8")
(output / "extraction-manifest.json").write_text(json.dumps({"source":str(source),"sha256":hashlib.sha256(source.read_bytes()).hexdigest(),"methods":markers,"scope":"Actual read-admission and durable flush methods; deterministic metadata, auth and HTTP adapters"}, indent=2), encoding="utf-8")
