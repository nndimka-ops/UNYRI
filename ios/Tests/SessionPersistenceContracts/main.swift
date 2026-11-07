import Foundation

func expect(_ condition: Bool, _ description: String) {
    guard condition else { fatalError(description) }
    print("PASS: \(description)")
}
@main
struct SessionPersistenceContracts {
@MainActor static func main() async throws {
var values = ["access_token": "old-access", "refresh_token": "old-refresh", "user_id": "owner",
              "username": "owner", "display_name": "Owner", "device_id": "device", "installation_id": "installation"]
var writable = true
func makeStore() -> SessionStore {
    SessionStore(read: { values[$0] }, write: { value, key in
        guard writable else { return false }; values[key] = value; return true
    }, remove: { values.removeValue(forKey: $0) })
}
let store = makeStore()
let lockedStore = SessionStore(read: { _ in throw CocoaError(.fileReadNoPermission) })
do {
    try lockedStore.requireReadableSession()
    fatalError("Inaccessible Keychain was treated as empty")
} catch { print("PASS: inaccessible Keychain remains distinguishable from missing credentials") }
try makeStore().requireReadableSession()
let attempt = try store.refreshRequestId()
expect(try makeStore().refreshRequestId() == attempt, "refresh attempt survives restart before response")
writable = false
do {
    try store.saveTokens(accessToken: "new-access", accessTokenExpiresAt: Date(), refreshToken: "new-refresh")
    fatalError("failed persistence was reported as success")
} catch { print("PASS: storage failure is surfaced") }
expect(store.refreshToken() == "old-refresh", "failed write retains original session")
expect(try store.refreshRequestId() == attempt, "failed write retains retry identity")
writable = true
try store.saveTokens(accessToken: "new-access", accessTokenExpiresAt: Date(), refreshToken: "new-refresh")
let restored = makeStore()
expect(restored.refreshToken() == "new-refresh" && restored.userId() == "owner" && restored.deviceId() == "device", "legacy session migrates as one snapshot")
expect(try restored.refreshRequestId() != attempt, "successful rotation starts a new retry identity")
restored.saveScreen("profile", owner: "server|owner|device", value: ["name": "Owner"])
let cached: [String: String]? = makeStore().cachedScreen("profile", owner: "server|owner|device", as: [String: String].self)
expect(cached?["name"] == "Owner", "screen snapshot survives process recreation")
let wrongOwner: [String: String]? = makeStore().cachedScreen("profile", owner: "server|other|device", as: [String: String].self)
expect(wrongOwner == nil, "cached screen cannot cross account boundary")
let wrongServer: [String: String]? = makeStore().cachedScreen("profile", owner: "other-server|owner|device", as: [String: String].self)
expect(wrongServer == nil, "cached screen cannot cross server boundary")
restored.saveScreen("admin", owner: "server|owner|device", value: ["secret": "forbidden"])
expect(values["offline_admin"] == nil, "only permitted screen snapshots are stored")
try restored.enqueueLogout(origin: URL(string: "https://old.example")!, refreshToken: "old-proof")
try restored.enqueueLogout(origin: URL(string: "https://second.example")!, refreshToken: "second-proof")
try restored.enqueueLogout(origin: URL(string: "https://old.example")!, refreshToken: "old-proof")
expect(try makeStore().pendingLogouts().count == 2, "logout journal deduplicates and survives restart")
writable = false
do { try restored.enqueueLogout(origin: URL(string: "https://third.example")!, refreshToken: "third-proof"); fatalError("lost logout") }
catch { print("PASS: logout journal persistence failure is surfaced before clearing credentials") }
writable = true
restored.clearSession()
expect(values["offline_profile"] == nil && values["offline_devices"] == nil, "logout deletes offline screen snapshots")
expect(makeStore().refreshToken() == nil && makeStore().currentUser() == nil, "logout clears snapshot and legacy credentials")
let queuedLogouts = try makeStore().pendingLogouts()
expect(queuedLogouts.count == 2 && queuedLogouts[0].origin.host == "old.example", "logout cleanup retains original endpoint and proof")
try restored.finishPendingLogout(queuedLogouts[0].id)
expect(try makeStore().pendingLogouts().map(\.refreshToken) == ["second-proof"], "acknowledgement removes only its own queued logout")
expect(values["installation_id"] == "installation", "logout preserves installation identity")
expect(try restored.durableInstallationId() == "installation", "device identity resolves after storage is readable")
let historySession = AuthResponse(historyBeforeSequence: 42, accessToken: "history-access", accessTokenExpiresAt: Date(),
    refreshToken: "history-refresh", deviceId: "history-device", user: User(id: "history-owner", username: "owner", displayName: "Owner"))
try restored.saveSession(historySession)
expect(makeStore().needsHistoryChoice() && makeStore().historyBeforeSequence() == 42, "history choice and boundary survive restart")
let historyRefreshAttempt = try restored.refreshRequestId()
let historyDate = Date(timeIntervalSince1970: 100)
try restored.saveHistoryContext(HistoryContext(beforeSequence: 99, beforeDate: historyDate))
expect(makeStore().historyBeforeSequence() == 42, "backfill cannot advance an existing login boundary")
expect(makeStore().historyContext()?.beforeDate == historyDate, "original login date survives restart")
expect(try restored.refreshRequestId() == historyRefreshAttempt, "metadata migration preserves in-flight refresh identity")
try restored.completeHistoryChoice()
expect(!makeStore().needsHistoryChoice(), "history choice persists")
expect(try restored.refreshRequestId() == historyRefreshAttempt, "history choice never resets in-flight refresh identity")
try restored.saveTokens(accessToken: "rotated", accessTokenExpiresAt: Date(), refreshToken: "rotated-refresh")
expect(!makeStore().needsHistoryChoice() && makeStore().historyBeforeSequence() == 42, "refresh preserves history choice and boundary")
expect(makeStore().historyContext()?.beforeDate == historyDate, "token rotation preserves backfilled login date")
restored.clearSession()
expect(!makeStore().needsHistoryChoice() && makeStore().historyBeforeSequence() == nil, "logout clears account history preferences")
expect(makeStore().historyContext() == nil, "logout clears backfilled context")
let oldVersionSession = AuthResponse(accessToken: "old", accessTokenExpiresAt: Date(), refreshToken: "old-refresh",
    deviceId: "old-device", user: User(id: "owner", username: "owner", displayName: "Owner"))
try restored.saveSession(oldVersionSession)
try restored.saveHistoryContext(HistoryContext(beforeSequence: 17, beforeDate: historyDate))
expect(makeStore().historyBeforeSequence() == 17 && !makeStore().needsHistoryChoice(), "existing session upgrades without signing out or interrupting use")
restored.clearSession()
let unwritableIdentity = SessionStore(read: { _ in nil }, write: { _, _ in false })
do {
    _ = try unwritableIdentity.durableInstallationId()
    fatalError("An unpersisted installation identity must not reach login")
} catch { print("PASS: login identity requires durable storage") }

let cacheTestRoot = FileManager.default.temporaryDirectory.appendingPathComponent("coco-erasure-contract-\(UUID())")
let accountCache = cacheTestRoot.appendingPathComponent("account")
let staging = cacheTestRoot.appendingPathComponent("tmp")
try FileManager.default.createDirectory(at: accountCache, withIntermediateDirectories: true)
try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: cacheTestRoot) }
try Data("private history".utf8).write(to: accountCache.appendingPathComponent("messages.sqlite3"))
let cameraDraft = staging.appendingPathComponent("coco-photo-test.jpg")
let videoDraft = staging.appendingPathComponent("coco-video-test.mov")
let legacyCameraDraft = staging.appendingPathComponent("\(UUID()).jpg")
let currentCameraDraft = staging.appendingPathComponent("coco-camera-test.jpg")
let galleryDraft = staging.appendingPathComponent("coco-gallery-test.mp4")
let joinedVoice = staging.appendingPathComponent("voice-join-test.m4a")
let unrelated = staging.appendingPathComponent("unrelated-marker")
for path in [cameraDraft, videoDraft, legacyCameraDraft, currentCameraDraft, galleryDraft, joinedVoice, unrelated] { try Data("test".utf8).write(to: path) }
try await LocalAccountCachePurger.erase(accountRoots: [accountCache], temporaryDirectory: staging)
expect(!FileManager.default.fileExists(atPath: accountCache.path), "session erasure removes persisted account history")
expect(!FileManager.default.fileExists(atPath: cameraDraft.path) && !FileManager.default.fileExists(atPath: legacyCameraDraft.path), "session erasure removes current and legacy camera drafts")
expect(!FileManager.default.fileExists(atPath: videoDraft.path), "session erasure removes imported video staging files")
expect([currentCameraDraft, galleryDraft, joinedVoice].allSatisfy { !FileManager.default.fileExists(atPath: $0.path) }, "session erasure removes camera, gallery and joined voice staging")
expect(FileManager.default.fileExists(atPath: unrelated.path), "session erasure stays within owned cache paths")
try await LocalAccountCachePurger.erase(accountRoots: [accountCache], temporaryDirectory: staging)
print("PASS: interrupted erasure can be retried")
let defaults = UserDefaults(suiteName: "coco-private-logout-test-" + UUID().uuidString)!
defaults.set("private-chat", forKey: "chatPins|server|owner")
defaults.set("private-owner", forKey: "history-auto-server:server:owner")
defaults.set(true, forKey: "history-auto-v1:server:owner:enabled")
defaults.set("private-owner", forKey: "openHistoryBackupForAccount")
defaults.set("ru", forKey: "appLanguage")
LocalAccountCachePurger.eraseAccountPreferences(defaults: defaults)
expect(defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix("chatPins|") && !$0.hasPrefix("history-auto-") && $0 != "openHistoryBackupForAccount" }, "account preferences must be removed on logout")
expect(defaults.string(forKey: "appLanguage") == "ru", "general app language remains")
}
}
