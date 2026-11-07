"""Run the actual invite-sheet async methods with controlled repository seams.

This is a Foundation concurrency contract, not a SwiftUI/image/network proof.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[3])
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--swiftc", default=shutil.which("swiftc") or "swiftc")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
source = args.project_root / "ios/UNYRI/Conferences/ConferenceMessageViews.swift"
text = source.read_text(encoding="utf-8")


def extract(marker):
    start = text.index(marker)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end].replace("@MainActor private func", "func")


request = extract("private struct ConferenceInvitationAvatarRequest:").replace("private struct", "struct", 1)
sheet_methods = "\n".join(extract(marker) for marker in [
    "private var normalizedSearch:", "private var visible:", "private var emptyMessage:", "private var canSend:",
    "@MainActor private func clearDestinations()", "@MainActor private func load()", "@MainActor private func sendSelected()"])
sheet_methods = sheet_methods.replace("private var", "var")
row_method = extract("@MainActor private func loadAvatar(for request:")
harness = r'''
import Foundation
enum Expected: Error { case offline }
struct Peer { let id: String; var displayName = "Display Name"; var username = "actual_login" }
struct ChatSummary: Equatable {
    let chatId: String; var chatType = "direct"; var isBlocked: Bool? = false; var otherUser: Peer?
    static func == (a: Self, b: Self) -> Bool { a.chatId == b.chatId }
}
struct Page { let items: [ChatSummary]; let nextOffset: Int64? }
@MainActor final class Auth { var currentUser: Peer? = Peer(id: "owner"); var sessionGeneration: UInt64 = 1 }
struct ConferenceInvitationMetadata {
    let id: String; let title: String; let server: String; let roomID: String; let invite: String; let expiresAt: Int64
}
@MainActor final class Messages {
    var accepted = false; var attempts: [String] = []
    func sendConferenceInvite(chat: ChatSummary, metadata: ConferenceInvitationMetadata) -> Bool {
        attempts.append(metadata.id); return accepted
    }
}
enum UnyriHaptics { static func selection() {} }
@MainActor final class Users {
    var blockedAccountScope: String? = "session1|origin1|owner"
    var cached: URL?; var calls = 0
    var resolve: () async -> URL? = { URL(fileURLWithPath: "/private/cached-photo.jpg") }
    func cachedAvatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) -> URL? { cached }
    func avatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) async -> URL? { calls += 1; return await resolve() }
}
@MainActor final class Chats {
    var fetch: () async throws -> Page = { Page(items: [], nextOffset: nil) }
    func listChats(limit: Int, offset: Int64) async throws -> Page { try await fetch() }
}
@MainActor final class Sheet {
    let users = Users(); let chats = Chats(); let auth = Auth()
    let messages = Messages()
    let metadata = ConferenceInvitationMetadata(id: "metadata", title: "Owned test room", server: "https://owned.invalid",
        roomID: "owned-room", invite: "owned-fixture-not-a-real-capability", expiresAt: 9999999999)
    var destinations: [ChatSummary] = []; var selected = Set<String>(); var sent = Set<String>()
    var logicalIDs: [String:String] = [:]; var loadID: UUID?; var loadedScope: String?
    var failed = false; var sendFailed = false
    var sending = false; var search = ""; var dismissals = 0
    var loading: Bool { loadID != nil }
    func dismiss() { dismissals += 1 }
    SHEET_METHODS
}
REQUEST
@MainActor final class Row {
    let users = Users()
    var id = "peer"; var raw: String? = "avatars/peer/v1.jpg"; var version: Int64 = 1; var retry: UInt64 = 0
    var avatarURL: URL?; var resolvedRequest: ConferenceInvitationAvatarRequest?
    var loadingRequest: ConferenceInvitationAvatarRequest?; var failedRequest: ConferenceInvitationAvatarRequest?
    var avatarRequest: ConferenceInvitationAvatarRequest {
        .init(scope: users.blockedAccountScope, userID: id, avatarURL: raw, avatarVersion: version, retry: retry)
    }
    ROW_METHOD
}
@MainActor final class Gate<T> {
    var continuation: CheckedContinuation<T, Never>?
    func wait() async -> T { await withCheckedContinuation { continuation = $0 } }
    func release(_ value: T) { let old = continuation; continuation = nil; old!.resume(returning: value) }
}
@MainActor func require(_ ok: @autoclosure () -> Bool, _ message: String) {
    if !ok() { fatalError("FAIL: " + message) }
}
@main struct Main {
    @MainActor static func main() async {
        var checks = 0
        let noPhoto = Row(); noPhoto.raw = nil
        await noPhoto.loadAvatar(for: noPhoto.avatarRequest)
        require(noPhoto.users.calls == 0 && noPhoto.failedRequest == nil && noPhoto.loadingRequest == nil,
            "avatarless uses initials without request or retry"); checks += 1

        let row = Row(); let original = row.avatarRequest
        await row.loadAvatar(for: original)
        require(row.avatarURL?.isFileURL == true && row.resolvedRequest == original && row.failedRequest == nil,
            "real resolver result is published"); checks += 1
        row.version = 2
        require(row.avatarRequest != original, "version changes task identity"); checks += 1
        row.raw = "avatars/peer/replaced.jpg"
        require(row.avatarRequest.avatarURL != original.avatarURL, "object replacement changes task identity"); checks += 1

        let failed = Row(); failed.users.resolve = { nil }
        await failed.loadAvatar(for: failed.avatarRequest)
        require(failed.failedRequest == failed.avatarRequest && failed.avatarURL == nil && failed.loadingRequest == nil,
            "failed photo keeps initials and enables separate retry"); checks += 1
        let failedKey = failed.avatarRequest; failed.retry += 1
        failed.users.resolve = { URL(fileURLWithPath: "/private/retried.jpg") }
        await failed.loadAvatar(for: failed.avatarRequest)
        require(failed.avatarRequest != failedKey && failed.avatarURL != nil && failed.failedRequest == nil,
            "explicit retry reuses resolver and clears failure"); checks += 1

        let offline = Row(); offline.users.cached = URL(fileURLWithPath: "/private/offline.jpg"); offline.users.resolve = { nil }
        await offline.loadAvatar(for: offline.avatarRequest)
        require(offline.avatarURL == offline.users.cached && offline.failedRequest == nil,
            "cached photo survives network failure"); checks += 1

        let late = Row(); let a = Gate<URL?>(); let b = Gate<URL?>()
        late.users.resolve = { await a.wait() }; let firstRequest = late.avatarRequest
        let first = Task { await late.loadAvatar(for: firstRequest) }
        while a.continuation == nil { await Task.yield() }
        late.users.blockedAccountScope = "session2|origin2|other-owner"
        late.users.resolve = { await b.wait() }; let secondRequest = late.avatarRequest
        let second = Task { await late.loadAvatar(for: secondRequest) }
        while b.continuation == nil { await Task.yield() }
        a.release(URL(fileURLWithPath: "/private/old-owner.jpg")); await first.value
        require(late.avatarURL == nil && late.loadingRequest == secondRequest,
            "old avatar completion and defer cannot publish or stop new scope loading"); checks += 1
        b.release(URL(fileURLWithPath: "/private/new-owner.jpg")); await second.value
        require(late.avatarURL?.lastPathComponent == "new-owner.jpg", "current scope avatar succeeds"); checks += 1

        let cancelled = Row(); let gate = Gate<URL?>(); cancelled.users.resolve = { await gate.wait() }
        let work = Task { await cancelled.loadAvatar(for: cancelled.avatarRequest) }
        while gate.continuation == nil { await Task.yield() }; work.cancel()
        gate.release(URL(fileURLWithPath: "/private/cancelled.jpg")); await work.value
        require(cancelled.avatarURL == nil && cancelled.failedRequest == nil && cancelled.loadingRequest == nil,
            "cancellation cannot publish or present a network failure"); checks += 1

        let sheet = Sheet()
        sheet.chats.fetch = { Page(items: [ChatSummary(chatId: "peer", otherUser: Peer(id: "peer")),
            ChatSummary(chatId: "self", otherUser: Peer(id: "owner")),
            ChatSummary(chatId: "blocked", isBlocked: true, otherUser: Peer(id: "blocked")),
            ChatSummary(chatId: "saved", chatType: "saved")], nextOffset: nil) }
        await sheet.load()
        require(sheet.destinations.map(\.chatId) == ["peer"], "only unblocked direct peers are offered"); checks += 1
        sheet.selected = ["peer"]; sheet.chats.fetch = { throw Expected.offline }
        await sheet.load()
        require(sheet.failed && sheet.destinations.map(\.chatId) == ["peer"] && sheet.selected == ["peer"],
            "same-scope refresh failure preserves existing rows and selection"); checks += 1
        require(sheet.canSend, "selected current-scope chat enables send"); checks += 1
        sheet.sending = true; require(!sheet.canSend, "ongoing send remains disabled"); checks += 1
        sheet.sending = false; let oldScope = sheet.loadedScope; sheet.loadedScope = nil
        require(!sheet.canSend, "missing loaded scope cannot send"); checks += 1
        sheet.loadedScope = "different-origin"; require(!sheet.canSend, "foreign scope cannot send"); checks += 1
        sheet.loadedScope = oldScope; sheet.selected = []; require(!sheet.canSend, "empty selection stays disabled"); checks += 1
        sheet.selected = ["peer"]
        require(sheet.emptyMessage == nil, "load failure never also claims there are no chats"); checks += 1
        sheet.search = "  @ACTUAL_LOGIN \n"
        require(sheet.visible.map(\.chatId) == ["peer"], "trimmed case-insensitive @username search works despite different display name"); checks += 1
        sheet.search = "  display  "
        require(sheet.visible.map(\.chatId) == ["peer"], "display name is independently searchable"); checks += 1
        sheet.failed = false; sheet.search = "not-in-this-list"
        require(sheet.visible.isEmpty && sheet.emptyMessage == "No results.", "empty search results use existing localized results copy"); checks += 1
        sheet.search = "  \n"; sheet.destinations = []
        require(sheet.emptyMessage == "No chats available for invitation", "empty recipient list is distinct from empty search"); checks += 1
        sheet.logicalIDs = ["peer":"stable-retry-id"]; sheet.selected = ["peer"]
        sheet.chats.fetch = { Page(items: [], nextOffset: nil) }; await sheet.load()
        require(sheet.selected.isEmpty && sheet.logicalIDs["peer"] == "stable-retry-id",
            "successful refresh removes unavailable selection without discarding durable retry ids"); checks += 1
        sheet.selected = ["removed-after-choice"]
        await sheet.sendSelected()
        require(sheet.dismissals == 0 && sheet.messages.attempts.isEmpty && !sheet.sending,
            "empty actual target set never dismisses or pretends to enqueue"); checks += 1
        sheet.destinations = [ChatSummary(chatId: "peer", otherUser: Peer(id: "peer"))]; sheet.selected = ["peer"]
        await sheet.sendSelected(); sheet.messages.accepted = true; await sheet.sendSelected()
        require(sheet.messages.attempts == ["stable-retry-id", "stable-retry-id"] && sheet.dismissals == 1 && sheet.selected.isEmpty,
            "failed enqueue retry reuses its logical id and dismisses only on accepted work"); checks += 1

        let listing = Sheet(); let oldList = Gate<Page>(); let newList = Gate<Page>()
        listing.chats.fetch = { await oldList.wait() }; let oldTask = Task { await listing.load() }
        while oldList.continuation == nil { await Task.yield() }
        listing.selected = ["old"]; listing.sent = ["old"]; listing.logicalIDs = ["old":"logical"]
        listing.users.blockedAccountScope = "session2|origin2|other-owner"
        listing.chats.fetch = { await newList.wait() }; let newTask = Task { await listing.load() }
        while newList.continuation == nil { await Task.yield() }
        let newTicket = listing.loadID
        require(listing.destinations.isEmpty && listing.selected.isEmpty && listing.sent.isEmpty && listing.logicalIDs.isEmpty,
            "scope change clears old recipients and selected delivery state"); checks += 1
        oldList.release(Page(items: [ChatSummary(chatId: "old", otherUser: Peer(id:"old"))], nextOffset:nil)); await oldTask.value
        require(listing.destinations.isEmpty && listing.loadID == newTicket && !listing.failed,
            "old list result/defer cannot overwrite current scope"); checks += 1
        newList.release(Page(items: [ChatSummary(chatId: "new", otherUser: Peer(id:"new"))], nextOffset:nil)); await newTask.value
        require(listing.destinations.map(\.chatId) == ["new"] && listing.loadID == nil,
            "current scope publishes normally"); checks += 1
        listing.users.blockedAccountScope = nil; await listing.load()
        require(listing.destinations.isEmpty && listing.loadedScope == nil, "logout clears private destinations"); checks += 1
        print("PASS \(checks) actual invite-avatar/loader contracts")
    }
}
'''.replace("REQUEST", request).replace("SHEET_METHODS", sheet_methods).replace("ROW_METHOD", row_method)
swift = args.output / "Contracts.swift"
swift.write_text(harness, encoding="utf-8")
binary = args.output / ("contracts.exe" if __import__("os").name == "nt" else "contracts")
compile_run = subprocess.run([args.swiftc, "-parse-as-library", str(swift), "-o", str(binary)], text=True, capture_output=True)
(args.output / "compile.log").write_text(compile_run.stdout + compile_run.stderr, encoding="utf-8")
result = {"scope": "actual extracted async methods with controlled repository seams; not native UI or real image delivery",
          "production_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "compile_exit": compile_run.returncode}
if compile_run.returncode == 0:
    run = subprocess.run([str(binary)], text=True, capture_output=True, timeout=30)
    (args.output / "run.log").write_text(run.stdout + run.stderr, encoding="utf-8")
    result.update(run_exit=run.returncode, output=run.stdout.strip(), passed=run.returncode == 0)
else:
    result["passed"] = False
(args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result))
raise SystemExit(0 if result["passed"] else 1)
