import SwiftUI
import Combine

enum APIError: Error { case http(Int); case server(code: String, message: String) }
struct FixtureAccount { let id = "00000000-0000-0000-0000-000000000001" }
@MainActor final class AuthRepository: ObservableObject {
    @Published var currentUser: FixtureAccount? = FixtureAccount()
    @Published var sessionGeneration: UInt64 = 1
    let authenticatedServerURL = URL(string: "https://synthetic.invalid")!
    func currentAccessToken() async throws -> String { "synthetic-no-server-token" }
    func handleAuthenticatedRequestError(_ error: APIError, generation: UInt64, requestToken: String) async {}
}
@MainActor final class FakePrivacyServer: ObservableObject {
    static let shared = FakePrivacyServer()
    @Published var value = UserPrivacy()
    var conflict = ProcessInfo.processInfo.environment["SETTINGS_SCENARIO"] == "conflict"
    var autoDeleteConflict = ProcessInfo.processInfo.environment["SETTINGS_SCENARIO"] == "auto-delete-conflict"
    @Published var autoDelete = AccountAutoDeleteSettings(inactiveDays: nil, lastActiveAt: Date(), nextDeletionAt: nil, revision: 0)
}
@MainActor struct APIClient {
    init(baseURL: URL, preventRedirects: Bool = false, ephemeral: Bool = false, onAuthenticatedRequestError: ((APIError, String) async -> Void)? = nil) {}
    func get<Response: Decodable>(_ path: String, accessToken: String, as: Response.Type) async throws -> Response {
        await Task.yield()
        if path == "/users/me/auto-delete" { return FakePrivacyServer.shared.autoDelete as! Response }
        return FakePrivacyServer.shared.value as! Response
    }
    func put<Body: Encodable, Response: Decodable>(_ path: String, accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        if let request = body as? UpdateAccountAutoDelete {
            let server = FakePrivacyServer.shared
            if server.autoDeleteConflict {
                server.autoDeleteConflict = false
                server.autoDelete = AccountAutoDeleteSettings(inactiveDays: 7, lastActiveAt: Date(), nextDeletionAt: Date().addingTimeInterval(7 * 86400), revision: server.autoDelete.revision + 1)
                throw APIError.http(409)
            }
            guard request.expectedRevision == server.autoDelete.revision else { throw APIError.http(409) }
            server.autoDelete = AccountAutoDeleteSettings(inactiveDays: request.inactiveDays, lastActiveAt: server.autoDelete.lastActiveAt,
                nextDeletionAt: request.inactiveDays.map { Date().addingTimeInterval(Double($0) * 86400) }, revision: server.autoDelete.revision + 1)
            return server.autoDelete as! Response
        }
        let request = body as! UpdateUserPrivacy
        let server = FakePrivacyServer.shared
        await Task.yield()
        if server.conflict { server.conflict = false; server.value.revision += 1; server.value.onlineVisibility = .nobody; throw APIError.http(409) }
        guard request.expectedRevision == server.value.revision else { throw APIError.http(409) }
        server.value = UserPrivacy(onlineVisibility: request.onlineVisibility, lastSeenVisibility: request.lastSeenVisibility,
            alwaysAllow: request.alwaysAllow, neverAllow: request.neverAllow, sendReadReceipts: request.sendReadReceipts, revision: server.value.revision + 1)
        return server.value as! Response
    }
    func postNoContent(_ path: String, bearerToken: String) async throws {}
}
@MainActor final class UsersRepository: ObservableObject {
    func search(query: String) async throws -> SearchResponse {
        SearchResponse(items: [PublicUser(id: "00000000-0000-0000-0000-000000000002", username: "alex", displayName: "Alexandra With A Long Display Name", bio: nil, avatarUrl: nil, profileVersion: 1, avatarVersion: 0, hasAvatar: false)], nextOffset: nil)
    }
}
struct FixtureChat { let otherUser: PublicUser? = nil; let chatType = "saved" }
@MainActor final class ChatsRepository: ObservableObject { var preferenceCache: [String: FixtureChat] = ["fixture": FixtureChat()] }
struct ChatCacheUsage: Identifiable { let id: String; let bytes: Int64 }
struct CacheClearResult { let failedFiles: Int }
@MainActor final class StorageService: ObservableObject {
    @Published var present = true
    func usageByChat() async -> [ChatCacheUsage] { present ? [ChatCacheUsage(id: "fixture", bytes: 12 * 1024 * 1024)] : [] }
    func clear(chatID: String) async -> CacheClearResult { present = false; return CacheClearResult(failedFiles: 0) }
}
@MainActor final class UnyriMediaNetwork { static let shared = UnyriMediaNetwork(); var wifi = true; var connected = true }
enum CallUiState { case idle, outgoingRinging, incomingRinging, connecting, connected, reconnecting, ended, failed }
@MainActor final class CallController: ObservableObject {
    @Published var state = CallUiState.incomingRinging
    func acceptIncoming() async { state = .connected }
    func rejectIncoming() async { state = .idle }
    func hangup() async { state = .idle }
}
@MainActor final class ConferenceController: ObservableObject {
    enum Phase { case idle, connected, leaving }
    @Published var phase = Phase.idle
    func leave() { phase = .idle }
}
enum UnyriSavedChatStyle { static let background = Color.black; static let readTicks = Color.cyan }
enum FixtureAction { case action }
@MainActor final class PerformanceRecorder { static let shared = PerformanceRecorder(); func record(_ action: FixtureAction) {} }
@MainActor final class PerformanceMonitor { static let shared = PerformanceMonitor(); func activity() {} }
enum FileSizeFormatter { static func format(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .binary) } }
extension View {
    func unyriProminentAction() -> some View { self.buttonStyle(.borderedProminent).tint(UnyriColors.accent).foregroundStyle(UnyriColors.onAccent) }
    func unyriScreenBackground() -> some View { self.scrollContentBackground(.hidden).background(Color.black.ignoresSafeArea()) }
    func unyriBlocksRootDrawer() -> some View { self }
}
@main struct SettingsNativeHost: App {
    @StateObject private var auth: AuthRepository
    @StateObject private var privacy: UserPrivacyRepository
    @StateObject private var autoDelete: AccountAutoDeleteRepository
    @StateObject private var server = FakePrivacyServer.shared
    @StateObject private var users = UsersRepository()
    @StateObject private var chats = ChatsRepository()
    @StateObject private var storage = StorageService()
    @State private var showModal: Bool
    private let calls = CallController()
    private let rooms = ConferenceController()
    init() {
        let scenario = ProcessInfo.processInfo.environment["SETTINGS_SCENARIO"] ?? "normal"
        _showModal = State(initialValue: scenario == "lock")
        UserDefaults.standard.set(scenario == "lock", forKey: "appLock.enabled")
        UserDefaults.standard.set(ProcessInfo.processInfo.environment["SETTINGS_LANGUAGE"] ?? "en", forKey: UnyriL10n.key)
        UserDefaults.standard.removeObject(forKey: "automaticMedia.v1")
        UserDefaults.standard.removeObject(forKey: "mediaCachePolicy.v1")
        let account = AuthRepository(); _auth = StateObject(wrappedValue: account); _privacy = StateObject(wrappedValue: UserPrivacyRepository(auth: account))
        _autoDelete = StateObject(wrappedValue: AccountAutoDeleteRepository(auth: account))
    }
    var body: some Scene {
        WindowGroup {
            NavigationStack {
                UnyriSettingsPage("Settings") {
                    Text("PRIVATE CONVERSATION FIXTURE").accessibilityIdentifier("private-content")
                    NavigationLink("Online status & read receipts") { UnyriPrivacySettingsView() }.accessibilityIdentifier("open-privacy")
                    NavigationLink("Automatic downloads") { UnyriAutomaticDownloadsView() }.accessibilityIdentifier("open-downloads")
                    NavigationLink("Cache limits & chats") { UnyriCachePolicyView() }.accessibilityIdentifier("open-cache")
                    NavigationLink("App lock") { UnyriAppLockSettingsView() }.accessibilityIdentifier("open-lock")
                    NavigationLink("Account auto-delete") { UnyriAccountAutoDeleteSettingsView() }.accessibilityIdentifier("open-auto-delete")
                    Text("read:\(server.value.sendReadReceipts ? "true" : "false"):revision:\(server.value.revision)").accessibilityIdentifier("wire-state")
                }
            }
            .sheet(isPresented: $showModal) { Text("PRIVATE MODAL FIXTURE").accessibilityIdentifier("private-modal").presentationDetents([.large]) }
            .modifier(UnyriAppLockProtection())
            .environmentObject(auth).environmentObject(privacy).environmentObject(autoDelete).environmentObject(users).environmentObject(chats).environmentObject(storage)
            .environment(\.locale, Locale(identifier: UnyriL10n.language)).preferredColorScheme(.dark)
            .dynamicTypeSize(ProcessInfo.processInfo.environment["SETTINGS_LARGE"] == "1" ? .accessibility2 : .large)
            .task {
                let owner = auth.authenticatedServerURL.absoluteString + "|" + auth.currentUser!.id + "|" + String(auth.sessionGeneration)
                UnyriAppLock.shared.attach(calls: calls, rooms: rooms, owner: owner, auth: auth); UnyriAppLock.shared.phase(.active)
            }
        }
    }
}
