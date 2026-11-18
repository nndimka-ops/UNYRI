"""Compile actual repository read admission/flush and delivery acknowledgement methods with deterministic adapters."""
from pathlib import Path
import sys, hashlib, json

root = Path(__file__).resolve().parents[3]
# Each placeholder in Fixture.swift receives these production blocks, in order.
extractions = {
    "// PRODUCTION_READ_METHODS": (root / "ios/UNYRI/Messages/MessagesRepository.swift", [
        "    private var readPresentationAllowed: Bool {", "    private func readAllowed(", "    var activeChatId: String? {",
        "    @discardableResult\n    func setVisibleReadSequence(", "    func presentationVisibilityChanged()",
        "    func markReadLatest(", "    private func markVisibleRead(", "    func flushReadReceipts()",
        "    private func postRead(", "    private func saveServerRead("]),
    "// PRODUCTION_RECEIPT_STATE": (root / "ios/UNYRI/Messages/MessageIngestor.swift", ["@MainActor\nfinal class SessionReceipts {"]),
    "// PRODUCTION_DELIVERY_METHODS": (root / "ios/UNYRI/Messages/MessageIngestor.swift", [
        "    private func confirmDelivery(chatId: String, token: String)", "    private func saveServerDelivered("]),
}
fixture = (Path(__file__).parent / "Fixture.swift").read_text(encoding="utf-8")
manifest = []
for placeholder, (source, markers) in extractions.items():
    text = source.read_text(encoding="utf-8")
    blocks = []
    for marker in markers:
        start = text.index(marker); end = text.index("{", start) + 1; depth = 1
        while depth:
            depth += (text[end] == "{") - (text[end] == "}"); end += 1
        blocks.append(text[start:end])
    fixture = fixture.replace(placeholder, "\n".join(blocks))
    manifest.append({"source": str(source), "sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "methods": markers})
output = Path(sys.argv[1]); output.mkdir(parents=True, exist_ok=True)
(output / "main.swift").write_text(fixture, encoding="utf-8")
(output / "extraction-manifest.json").write_text(json.dumps({"sources": manifest, "scope": "Actual read-admission, coalescing flush, saved server read and delivered cursors and session receipt state; deterministic metadata, auth and HTTP adapters"}, indent=2), encoding="utf-8")
