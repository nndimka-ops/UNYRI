"""Execute actual ChatScreen/outbox Swift blocks with the production UI ledger."""
from pathlib import Path
import argparse, hashlib, json, platform, subprocess, tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--swiftc", default="swiftc")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--expect-red", action="store_true")
parser.add_argument("--baseline-chat", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
paths = {"chat": root / "ios/UNYRI/UI/Chats/ChatScreen.swift", "outbox": root / "ios/UNYRI/Messages/MessageOutbox.swift",
         "models": root / "ios/UNYRI/Models/MessageModels.swift", "ordering": root / "ios/UNYRI/Support/ChatViewportPolicy.swift",
         "helper": root / "ios/UNYRI/Support/UnyriPendingAlbumPresentation.swift", "store": root / "ios/UNYRI/Data/MessageStore.swift"}
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
before = {key: sha(path) for key, path in paths.items()}
texts = {key: path.read_text(encoding="utf-8-sig") for key, path in paths.items()}
if args.baseline_chat:
    texts["chat"] = args.baseline_chat.read_text(encoding="utf-8-sig")

def block(key, marker):
    text = texts[key]
    start = text.index(marker); end = text.index("{", start) + 1; depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}"); end += 1
    return text[start:end]

blocks = {
    "response": block("models", "struct SendMessageResponse:"), "status": block("models", "enum MessageStatus:"),
    "item": block("chat", "    private struct TimelineItem:"),
    "comparator": block("chat", "    private static func timelineMessagePrecedes("),
    "grouping": block("chat", "    private func groupAlbumRows("),
    "reconcile": block("outbox", "    private func reconcileSent("),
    "ordering": block("ordering", "enum ChatTimelineOrdering {")}
start = texts["models"].index("struct DecryptedChatMessage:")
end = texts["models"].index("    var displayText:", start)
blocks["metadata"] = texts["models"][start:end] + "}"
if "    private func stabilizePendingAlbums(" in texts["chat"]:
    blocks["integration"] = block("chat", "    private func stabilizePendingAlbums(")
if "    private func bookmarkAnchors(" in texts["chat"]:
    blocks["bookmarks"] = block("chat", "    private func bookmarkAnchors(")
else:
    # Before the fix the preference callback persisted the current row key.
    blocks["bookmarks"] = "private func bookmarkAnchors(for rows:[TimelineItem])->[String:String] { Dictionary(uniqueKeysWithValues: rows.map { ($0.id,$0.id) }) }"
anchor_source = (root / "ios/UNYRI/Support/AlbumMosaicLayout.swift").read_text(encoding="utf-8")
start = anchor_source.index("enum UnyriTimelineAnchor {")
blocks["anchor_resolution"] = anchor_source[start:]

adapter = r'''
struct MessageContent: Equatable { var albumId: String?; var revision = "initial" }
struct CallDTO { let id: UUID; var createdAt: Date }
struct FixtureUser { let id: String }
struct FixtureAuth {
    var currentUser: FixtureUser? = FixtureUser(id: "owner")
    var authenticatedServerURL = URL(string: "https://fixture.invalid")!
    var sessionGeneration: UInt64 = 1
}
struct FixtureChat { var chatId = "chat" }
'''
pipeline = adapter + "\n" + "\n".join(blocks[k] for k in ["response", "status", "metadata", "ordering", "anchor_resolution"]) + r'''
final class ActualPipeline {
    var auth = FixtureAuth()
    var chat = FixtureChat()
    var pendingAlbumPresentation = UnyriPendingAlbumPresentation()
    // ACTUAL_METHODS
    func snapshot(_ messages: [DecryptedChatMessage]) -> [(id:String, members:[DecryptedChatMessage])] {
        let ordered = ChatTimelineOrdering.sortedIfNeeded(messages, by: Self.timelineMessagePrecedes)
        return groupAlbumRows(ordered.map { TimelineItem(message: $0, call: nil) }).map { ($0.id, $0.members) }
    }
    func bookmark(_ messages:[DecryptedChatMessage]) -> String {
        let ordered = ChatTimelineOrdering.sortedIfNeeded(messages, by: Self.timelineMessagePrecedes)
        let rows = groupAlbumRows(ordered.map { TimelineItem(message:$0,call:nil) })
        return bookmarkAnchors(for:rows)[rows[0].id]!
    }
    func ack(_ message:DecryptedChatMessage, at:Double, sequence:Int64) -> DecryptedChatMessage {
        reconcileSent(message,response:SendMessageResponse(id:"server-"+message.id,chatId:message.chatId,
            clientMessageId:message.id,senderId:message.senderId,sequence:sequence,
            createdAt:Date(timeIntervalSince1970:at),status:"sent"))
    }
}
func fixtureMessage(_ id:String,_ time:Double,album:String?="album",sender:String="owner",chat:String="chat") -> DecryptedChatMessage {
    .init(clientMessageId:id,serverId:nil,chatId:chat,senderId:sender,sequence:nil,
          createdAt:Date(timeIntervalSince1970:time),kind:"content",targetMessageId:nil,status:.pending,
          content:MessageContent(albumId:album))
}
func actualIntegrationContracts() {
    let pipe = ActualPipeline()
    let a = fixtureMessage("A", 1000), b = fixtureMessage("B", 1001)
    let initial = pipe.snapshot([a, b])
    var sentA = pipe.ack(a, at: 1006, sequence: 10)
    sentA.content.revision = "latest-attachment"
    sentA.status = .delivered
    let afterA = pipe.snapshot([sentA, b])
    require(initial[0].id == afterA[0].id && afterA[0].members.map(\.id) == ["A", "B"],
            "ALBUM_ACK_STABILITY: actual integration must retain row and member order")
    require(afterA[0].members[0].content.revision == "latest-attachment" && afterA[0].members[0].status == .delivered,
            "Projection must resolve current live fields, never retained messages")
    let sentB = pipe.ack(b, at: 1008, sequence: 11)
    let text = fixtureMessage("text", 1003, album: nil)
    let final = pipe.snapshot([text, sentB, sentA])
    require(final.map { $0.members.map(\.id) } == [["A", "B"], ["text"]], "Actual display anchor must stay before interleaved text")
    require(final[0].id == initial[0].id, "Second ACK and vanished upload workspace must retain token")
    let savedBookmark = pipe.bookmark([sentA,sentB])
    let freshForBookmark = ActualPipeline().snapshot([sentA,sentB])
    require(savedBookmark == "A" && UnyriTimelineAnchor.rowID(target:savedBookmark,
        rows:freshForBookmark.map { (id:$0.id,members:$0.members.map(\.id)) }) == "A",
        "Persistent scroll bookmark must be a canonical client ID that resolves after a fresh view")
    require(sentA.createdAt == Date(timeIntervalSince1970: 1006) && sentB.createdAt == Date(timeIntervalSince1970: 1008),
            "Canonical receipt dates must remain unchanged")
    let hidden = pipe.snapshot([sentB])
    require(hidden[0].id == initial[0].id && hidden[0].members.map(\.id) == ["B"], "Missing live member never resurrects")
    let fresh = ActualPipeline().snapshot([text, sentB, sentA])
    require(fresh.map { $0.members.map(\.id) } == [["text"], ["A", "B"]], "Confirmed history after reopen remains canonical")
    let unloaded = ActualPipeline()
    _ = unloaded.snapshot([a,b])
    _ = unloaded.snapshot([])
    require(unloaded.snapshot([sentA,sentB])[0].id == "A", "Actual empty projection must prune a previously pending album")
    sentA.status = .failed
    let failed = pipe.snapshot([sentA,sentB])
    sentA.status = .pending
    let retry = pipe.snapshot([sentA,sentB])
    require(failed[0].id == initial[0].id && retry[0].id == initial[0].id,
        "Failure and retry cannot discard the live album token")
    pipe.auth.currentUser = nil
    _ = pipe.snapshot([sentA,sentB])
    pipe.auth.currentUser = FixtureUser(id:"owner")
    sentA.status = .sent
    require(pipe.snapshot([sentA,sentB])[0].id == "A", "Missing owner must retire old metadata")
    let large = (0..<21).map { fixtureMessage("m\($0)", Double($0)) }
    let largeRows = ActualPipeline().snapshot(large)
    require(largeRows.map { $0.members.count } == [10,10,1] && Set(largeRows.map(\.id)).count == 3,
            "Actual grouping retains unique bounded twenty-one-member fallback")
    let reversed = ActualPipeline()
    _ = reversed.snapshot([a,b])
    let bFirst = reversed.ack(b, at:1006, sequence:10)
    let aLast = reversed.ack(a, at:1008, sequence:11)
    require(reversed.snapshot([aLast,bFirst])[0].members.map(\.id) == ["A","B"], "Reverse ACK completion cannot reverse already displayed members")
    print("PASS: actual ChatScreen grouping plus real outbox reconciliation: stable row/tile/anchor, live values, canonical dates and twenty-one fallback")
}
'''
pipeline = pipeline.replace("// ACTUAL_METHODS", "\n".join(blocks[k] for k in ["item", "comparator", "grouping", "reconcile", "bookmarks"] + (["integration"] if "integration" in blocks else [])))
fixture = Path(__file__).with_name("main.swift").read_text(encoding="utf-8").replace("// ACTUAL_PRODUCTION_PIPELINE", pipeline)
ui_wiring = {}
if "integration" in blocks:
    cell = block("chat", "    @ViewBuilder private func albumCell(")
    ui_wiring = {
        "singleton_receives_timeline_row_identity": "albumCell(message, firstInGroup: firstInGroup, rowID: item.id)" in texts["chat"],
        "singleton_scroll_and_geometry_use_same_identity": ".id(presentationID)" in cell and "value: ChatItemFrameBatch(frames: inAlbum ? [:] : [presentationID: geometry.frame" in cell,
        "nested_cells_emit_no_row_frames_but_keep_read_frames": "value: ChatItemFrameBatch(frames: inAlbum ? [:]" in cell and ".preference(key: VisibleMessageFrames.self" in cell,
        "row_frame_batch_is_context_tagged_and_callback_fenced": ".transformPreference(ChatItemFrames.self) { batch in batch.context = bottomIdentity }" in texts["chat"] and "guard batch.context == bottomIdentity else { return }" in texts["chat"],
        "persistent_bookmark_uses_canonical_mapping": "anchor: bookmarkAnchor, followsLatest:" in texts["chat"],
        "legacy_comparison_and_lookup_use_canonical_mapping": "?.anchor == bookmarkAnchor" in texts["chat"] and "timelineRowID(bookmarkAnchor, items: timelineItems)" in texts["chat"],
        "unloaded_history_prunes_metadata": "guard messagesRepository.loadedHistoryChatId == chat.chatId else { return stabilizePendingAlbums([]) }" in texts["chat"],
    }
    assert all(ui_wiring.values()), "Inspect UI-token/canonical/geometry integration wiring"
args.output.mkdir(parents=True, exist_ok=True)
(args.output / "extracted-main.swift").write_text(fixture, encoding="utf-8")
cases = []
with tempfile.TemporaryDirectory(prefix="coco-album-presentation-") as tmp:
    exe = Path(tmp) / ("checks.exe" if platform.system() == "Windows" else "checks")
    compiled = subprocess.run([args.swiftc, "-parse-as-library", str(paths["helper"]), str(args.output / "extracted-main.swift"), "-o", str(exe)], capture_output=True, timeout=120)
    (args.output / "compile.log").write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, "Compilation failure is not a regression counterexample"
    binary = sha(exe)
    for case in ["boundaries", "integration"]:
        run = subprocess.run([str(exe), case], capture_output=True, timeout=30)
        log = run.stdout + run.stderr
        (args.output / (case + ".log")).write_bytes(log)
        expected_red = case == "integration" and args.expect_red
        valid = run.returncode != 0 and b"ALBUM_ACK_STABILITY" in log if expected_red else run.returncode == 0
        cases.append({"case": case, "exit": run.returncode, "expected_red": expected_red, "expected_outcome_observed": valid})
proof = {"mode": "red" if args.expect_red else "green", "actual_production_swift_blocks_executed": True,
         "chat_source_executed": str(args.baseline_chat or paths["chat"]),
         "chat_source_executed_sha256": sha(args.baseline_chat or paths["chat"]),
         "sources": {key: {"path": str(paths[key]), "sha256": value} for key, value in before.items()},
         "blocks": {key: hashlib.sha256(value.encode()).hexdigest() for key, value in blocks.items()},
         "test_main_sha256": sha(Path(__file__).with_name("main.swift")), "runner_sha256": sha(Path(__file__)),
         "compiled_binary_sha256": binary, "cases": cases,
         "static_ui_wiring_checks_not_native_UI_tests": ui_wiring,
         "production_source_unchanged_during_test": all(sha(paths[key]) == value for key, value in before.items()),
         "mac_sdk_actions_used": False, "all_expected_outcomes_observed": all(case["expected_outcome_observed"] for case in cases)}
(args.output / "result.json").write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
print(json.dumps(proof, indent=2))
assert proof["all_expected_outcomes_observed"] and proof["production_source_unchanged_during_test"]
