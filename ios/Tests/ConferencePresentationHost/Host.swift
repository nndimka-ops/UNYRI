import AVFoundation
import Foundation
import LiveKit
import SwiftUI
import UIKit

// Presentation-only seams for the actual ConferenceViews.swift. These Rooms
// are never connected and never create capture tracks. This host has no auth,
// network fixture credentials, persistence, or access to the main app keychain.
@MainActor final class ConferenceController: ObservableObject {
    func allowNewRoomAction() -> Bool {
        guard !isBusy else { error = "You are already in a room. Leave it before starting another conversation."; return false }
        return true
    }
    static let invitationNotification = Notification.Name("FixtureInvitation")
    enum Phase: Equatable { case idle, connecting, connected, reconnecting, leaving }
    struct Tile: Identifiable { let id: String; let participant: Participant; let name: String; var isLocal: Bool { id == "fixture-0" } }
    struct RoomInfo: Identifiable {
        struct Member { let userId: UUID; let participantIdentity: String?; var audioPublishBlocked: Bool? = false }
        var title: String; let maxParticipants: Int; let id = UUID(); let admissionLocked: Bool? = false
        var description: String? = "A quiet test room"
        var listed: Bool? = true
        var accessMode: String? = "open"
        var settingsRevision: Int64? = 1
        var accessRevision: Int64? = 1
        let state = "active"
        let expiresAt = Date().addingTimeInterval(3600)
        let ownerUserId = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
        var participants: [Member] = []
    }
    struct API {
        struct Capabilities { let ownerRooms: Bool? }
        let server = AppConfig.apiBaseURL; let isCurrentSession = true
        func capabilities() async throws -> Capabilities { Capabilities(ownerRooms: !ProcessInfo.processInfo.arguments.contains("--legacy-server")) }
        func ownedRooms() async throws -> [RoomInfo] { (1...3).map { RoomInfo(title: "Unused room \($0)", maxParticipants: 30) } }
        func directory(query: String, cursor: String?) async throws -> ConferenceDirectoryPage {
            let values = ConferenceAccessMode.allCases.enumerated().map { index, mode in
                ConferenceDirectoryRoom(id: UUID(), title: "Room \(index + 1)", description: "A calm encrypted space", ownerUserId: UUID(), ownerDisplayName: "Room creator",
                    participantCount: index + 1, maxParticipants: 30, accessMode: mode.rawValue, admissionLocked: false, keyAvailable: index != 0,
                    createdAt: Date(), expiresAt: Date().addingTimeInterval(14400))
            }
            return ConferenceDirectoryPage(items: values.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }, nextCursor: nil)
        }
        func finish(_ id: UUID, end: Bool) async throws {}
        func requireCurrent() throws {}
    }
    let api = API()
    let keyHandoff = ConferenceKeyHandoff()
    @Published var requestedOwnedRooms = false
    @Published var directoryAvailable = true
    @Published var ownedRoomsAvailable = true
    @Published var accessSettingsAvailable = true
    let rooms = (0..<30).map { _ in Room() }
    @Published var phase = Phase.connected
    @Published var conference: RoomInfo? = RoomInfo(title: "Conference title with a long participant discussion", maxParticipants: 30)
    @Published var tiles: [Tile] = []
    @Published var microphoneEnabled = false
    @Published var cameraEnabled = false
    @Published var mediaChanging = false
    @Published var minimized = false
    @Published var showLobby = false
    @Published var offeredInvite: ConferenceInvite?
    @Published var pinnedID: String?
    @Published var error: String?
    @Published var remotePageAnchorID: String?
    @Published var selectedVisibleCount = 0
    @Published var fitEveryoneOnScreen = false
    @Published var hostControlsAvailable = true
    @Published var changingRoomSettings = false
    @Published var locallyMutedParticipantIDs = Set<String>()
    @Published var locallyHiddenParticipantIDs = Set<String>()
    @Published var participantModerationAvailable = true
    @Published var microphoneBlockedByOwner = false
    @Published var moderatingParticipantID: String?
    @Published var ownPreviewPosition = CGPoint(x: 0.96, y: 0.05)
    var isBusy: Bool { phase != .idle }
    var isOwner = true
    var activeInvite: URL? { invitationMetadata().flatMap { URL(string: $0.invite) } }
    var remoteTiles: [Tile] { Array(tiles.dropFirst()) }
    var ownTile: Tile? { tiles.first }
    var stageTiles: [Tile] { tiles }
    var totalParticipants: Int { tiles.count }
    var shouldKeepScreenOn: Bool { false }
    var presentationStatus: String { phase == .reconnecting ? "Reconnecting…" : "Encrypted conference" }
    init() {
        tiles = rooms.enumerated().map { Tile(id: "fixture-\($0.offset)", participant: $0.element.localParticipant, name: "Participant \($0.offset + 1)") }
        let owner = conference!.ownerUserId
        conference?.participants = tiles.map { .init(userId: $0.isLocal ? owner : UUID(), participantIdentity: $0.id) }
        if ProcessInfo.processInfo.arguments.contains("--reconnecting") { phase = .reconnecting }
        if ProcessInfo.processInfo.arguments.contains("--minimized") { minimized = true }
        if ProcessInfo.processInfo.arguments.contains("--preview") { cameraEnabled = true }
        if ProcessInfo.processInfo.arguments.contains("--last-page") { remotePageAnchorID = "fixture-25" }
        if ProcessInfo.processInfo.arguments.contains("--error") { error = "Could not change conference media. Check camera and microphone permissions." }
        if ProcessInfo.processInfo.arguments.contains("--empty") { tiles = Array(tiles.prefix(1)) }
        if ProcessInfo.processInfo.arguments.contains("--two") { tiles = Array(tiles.prefix(3)) }
        if ProcessInfo.processInfo.arguments.contains("--invite-card") { phase = .idle }
        if ProcessInfo.processInfo.arguments.contains("--lobby") || ProcessInfo.processInfo.arguments.contains("--rooms") { phase = .idle }
        if ProcessInfo.processInfo.arguments.contains("--host-blocked") { microphoneBlockedByOwner = true }
        if ProcessInfo.processInfo.arguments.contains("--legacy-server") { hostControlsAvailable = false; participantModerationAvailable = false }
    }
    func invitationMetadata() -> ConferenceInvitationMetadata? {
        let room = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
        let invitation = ConferenceInvite(server: AppConfig.apiBaseURL, roomID: room,
            secret: ConferenceInvite.encode(Data(1...32)), mediaKey: ConferenceInvite.encode(Data(33...64)))
        return ConferenceInvitationMetadata(id: "00112233-4455-4677-8899-aabbccddef00", title: "Обсудим планы на выходные — конференция друзей",
            server: AppConfig.publicDefaultOrigin, roomID: room.uuidString.lowercased(), invite: invitation.link.absoluteString,
            expiresAt: Int64(Date().timeIntervalSince1970) + 3600)
    }
    func refreshCapabilities() async { directoryAvailable = !ProcessInfo.processInfo.arguments.contains("--legacy-server"); accessSettingsAvailable = directoryAvailable; ownedRoomsAvailable = directoryAvailable }
    func create(configuration: ConferenceRoomConfiguration, limit: Int) {}
    func create(title: String, limit: Int, microphone: Bool, camera: Bool) {}
    func join(_ invite: ConferenceInvite, microphone: Bool, camera: Bool) {}
    func join(_ invite: ConferenceInvite, password: String?) {}
    func hasSavedAccess(to room: RoomInfo) -> Bool { ProcessInfo.processInfo.arguments.contains("--saved-owner-key") }
    func rejoinSavedDirectoryRoom(_ room: ConferenceDirectoryRoom) -> Bool { false }
    func rejoinOwnedRoom(_ room: RoomInfo) {
        if hasSavedAccess(to: room) { conference = room; phase = .connected; minimized = false }
        else { offeredInvite = nil; showLobby = true }
    }
    func forgetOwnedRoom(_ id: UUID) {}
    func receive(_ url: URL) {}
    func accept(_ metadata: ConferenceInvitationMetadata) {}
    func leave(endForEveryone: Bool = false) { phase = .idle }
    func toggleMicrophone() {
        if microphoneBlockedByOwner { error = "The room creator disabled your microphone. You can enable it yourself after the restriction is lifted."; return }
        microphoneEnabled.toggle()
    }
    func toggleCamera() { cameraEnabled.toggle() }
    func switchCamera() {}
    func showVideoParticipants(_ ids: Set<String>) {}
    func setForeground(_ active: Bool) {}
    func beginVideoPresentation(_ owner: UUID) {}
    func endVideoPresentation(_ owner: UUID) {}
    func presentVideo(_ ids: Set<String>, owner: UUID, active: Bool = true) {}
    func registerPreviewViewport(_ size: CGSize, owner: UUID) {}
    func moveOwnPreview(_ position: CGPoint, owner: UUID, viewport: CGSize) { ownPreviewPosition = position }
    func remotePageIndex(size: Int) -> Int { remotePageAnchorID.flatMap { id in stageTiles.firstIndex(where: { $0.id == id }) }.map { $0 / size } ?? 0 }
    func selectRemotePage(_ page: Int, size: Int) { if !stageTiles.isEmpty { remotePageAnchorID = stageTiles[min(max(0, page * size), stageTiles.count - 1)].id } }
    func focusParticipant(_ id: String) { guard id != ownTile?.id else { return }; pinnedID = id; remotePageAnchorID = id }
    func recordScrollAnchor(_ ids: Set<String>, owner: UUID) { if let first = stageTiles.first(where: { ids.contains($0.id) }) { remotePageAnchorID = first.id } }
    func toggleLocalAudio(_ id: String) {
        if locallyMutedParticipantIDs.contains(id) { locallyMutedParticipantIDs.remove(id) } else { locallyMutedParticipantIDs.insert(id) }
    }
    func isParticipantAudioBlocked(_ id: String) -> Bool { conference?.participants.first(where: { $0.participantIdentity == id })?.audioPublishBlocked == true }
    func toggleLocalVideo(_ id: String) {
        if locallyHiddenParticipantIDs.contains(id) { locallyHiddenParticipantIDs.remove(id) } else { locallyHiddenParticipantIDs.insert(id) }
    }
    func moderateParticipant(_ identity: String, audioBlocked: Bool?) async -> Bool {
        guard let index = conference?.participants.firstIndex(where: { $0.participantIdentity == identity }) else { return false }
        if let audioBlocked { conference?.participants[index].audioPublishBlocked = audioBlocked }
        else { conference?.participants.remove(at: index); tiles.removeAll(where: { $0.id == identity }) }
        return true
    }
    func updateRoomSettings(title: String, admissionLocked: Bool) async -> Bool { true }
    func updateRoomSettings(configuration: ConferenceRoomConfiguration, expectedRevision: Int64) async -> Bool { true }
}
@MainActor final class ConferenceKeyHandoff: ObservableObject {
    enum Phase { case idle, requesting, waiting, timedOut, failed, cancelled, ready }
    @Published var phase = Phase.idle
    @Published var error: String?
    @Published var roomTitle = ""
    var retryRoom: ConferenceDirectoryRoom?
    var isWaiting: Bool { phase == .requesting || phase == .waiting }
    func start(room: ConferenceDirectoryRoom, password: String?) { roomTitle = room.title; retryRoom = room; phase = .waiting }
    func cancel() { if isWaiting { phase = .cancelled } }
    func dismissStatus() { cancel(); phase = .idle; error = nil; retryRoom = nil }
}
struct UnyriMenuButton: View {
    var body: some View { Button {} label: { Image(systemName: "line.3.horizontal").frame(width: 44, height: 44) }.accessibilityLabel("Open menu") }
}
typealias ConferenceDTO = ConferenceController.RoomInfo

enum UnyriL10n { static func string(_ value: String, comment: String = "") -> String { NSLocalizedString(value, comment: comment) } }
@MainActor final class CallDisplayIdleTimerLeaseRegistry {
    static let shared = CallDisplayIdleTimerLeaseRegistry()
    func set(wanted: Bool, owner: UUID) {}
}
@MainActor final class HostWindowController: UIHostingController<AnyView> {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }
    override var shouldAutorotate: Bool { true }
}
@MainActor final class HostSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let controller = ConferenceController()
    private let auth = AuthRepository()
    private let messages = MessagesRepository()
    private let chats = ChatsRepository()
    private let users = UsersRepository()
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let root = HostContent().environmentObject(controller).environmentObject(auth).environmentObject(messages).environmentObject(chats).environmentObject(users)
            .dynamicTypeSize(ProcessInfo.processInfo.arguments.contains("--large") ? .accessibility3 : .large)
            .preferredColorScheme(.dark)
        let host = HostWindowController(rootView: AnyView(root))
        let value = UIWindow(windowScene: scene); value.rootViewController = host
        value.makeKeyAndVisible(); window = value
        if ProcessInfo.processInfo.arguments.contains("--landscape") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                host.setNeedsUpdateOfSupportedInterfaceOrientations()
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeLeft)) { _ in }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            let data: [String: String] = ["scene": "\(scene.interfaceOrientation.rawValue)",
                "sceneBounds": "\(scene.coordinateSpace.bounds)", "window": "\(value.bounds)",
                "controller": "\(host.view.bounds)", "screen": "\(scene.screen.bounds)",
                "device": "\(UIDevice.current.orientation.rawValue)"]
            let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("host-geometry.json")
            try? JSONSerialization.data(withJSONObject: data).write(to: file)
        }
    }
}
@main @MainActor final class HostAppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { .allButUpsideDown }
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = HostSceneDelegate.self
        return configuration
    }
}
