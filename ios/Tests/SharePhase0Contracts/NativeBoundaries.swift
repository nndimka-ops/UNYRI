import Foundation
import Dispatch

// The real methods under test are inserted from the UIKit/SwiftUI source.
// These fixtures supply only the native/framework/model boundaries they need.
struct ChatSummary {
    struct Peer {
        var displayName: String?; var username: String?
        var publicUsername: String? = nil; var unyriNumber: String? = nil
        var visibleName: String { DisplayNameSanitizer.peer(displayName ?? "", username: username) }
        // B-033: pickers search the public @username or number, never the private login.
        var publicIdentityLabel: String { publicUsername.map { "@" + $0 } ?? unyriNumber ?? "" }
    }
    var chatId: String
    var chatType: String
    var otherUser: Peer? = nil
    var isBlocked: Bool? = nil
}
enum UnyriL10n { static func string(_ key: String) -> String { key } }
struct PickerContract {
    var chats: [ChatSummary]
    var search = ""
    var selected: [String] = []
    // PICKER_METHODS
    var visibleIDs: [String] { filtered.map(\.chatId) }
    var selectedIDs: [String] { selectedChats.map(\.chatId) }
    func title(_ chat: ChatSummary) -> String { name(chat) }
}
@MainActor final class UIApplication {
    static let shared = UIApplication()
    var isProtectedDataAvailable = true
}
#if os(Windows) || os(Linux)
extension URL {
    func startAccessingSecurityScopedResource() -> Bool { false }
    func stopAccessingSecurityScopedResource() {}
}
#endif
let contractRootURL = FileManager.default.temporaryDirectory.appendingPathComponent("share-phase0-" + UUID().uuidString)
var shareCopyChunkHook: (() -> Void)?
@MainActor var shareDiscoverySnapshotHook: ((UnyriShareStore.InboxSnapshot) async -> Void)?

/// Suspend after the real worker read, before MainActor publishes its result.
@MainActor final class InboxSnapshotGate {
    private var armed = false
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var entered = false
    private(set) var calls = 0
    private(set) var firstWasEmpty = false
    func arm() { armed = true; entered = false }
    func observe(_ snapshot: UnyriShareStore.InboxSnapshot) async {
        calls += 1
        if calls == 1 { firstWasEmpty = snapshot.files.isEmpty && snapshot.legacyDirectories.isEmpty }
        guard armed else { return }
        armed = false; entered = true
        await withCheckedContinuation { continuation = $0 }
    }
    func open() { continuation?.resume(); continuation = nil }
}

final class InboxRootGate: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private let release = DispatchSemaphore(value: 0)
    private var armed = false
    private var didEnter = false
    private var offMain = false
    private var failNext = false
    private var calls = 0
    init(root: URL) { url = root }
    func arm(fail: Bool = false) { lock.lock(); armed = true; didEnter = false; offMain = false; failNext = fail; lock.unlock() }
    var entered: Bool { lock.lock(); defer { lock.unlock() }; return didEnter }
    var workerWasOffMain: Bool { lock.lock(); defer { lock.unlock() }; return offMain }
    var callCount: Int { lock.lock(); defer { lock.unlock() }; return calls }
    func root() throws -> URL {
        dispatchPrecondition(condition: .notOnQueue(.main))
        lock.lock()
        let wait = armed
        let fail = wait && failNext
        calls += 1; failNext = false
        if wait { armed = false; didEnter = true; offMain = !Thread.isMainThread }
        lock.unlock()
        if wait { precondition(release.wait(timeout: .now() + 10) == .success, "test gate timed out") }
        if fail { throw CocoaError(.fileReadNoPermission) }
        return url
    }
    func open() { release.signal() }
}

// INBOX_MODEL
