import Foundation

struct Member { let userId: UUID; let deviceId: UUID }
struct ConferenceDTO {
    let id: UUID
    var participants: [Member]
    var state = "active"
    var expiresAt = Date().addingTimeInterval(60)
}
enum Connection { case connected }
enum E2EEType { case gcm }
final class E2EEManager { let frameEncryptionType = E2EEType.gcm }
enum ConferenceInvite {
    enum Failure: Error, LocalizedError {
        case closed, encryption
        var errorDescription: String? { "Closed or invalid encryption" }
    }
}
struct LiveKitError: Error {
    enum Kind { case network, cancelled, insufficientPermissions }
    let type: Kind
    init(_ type: Kind) { self.type = type }
}
@MainActor final class LocalParticipant {
    struct Identity { let stringValue: String }
    let identity: Identity? = Identity(stringValue: "old-room-sender")
    var microphone = false
    var camera = false
    var publications = 0
    func setMicrophone(enabled: Bool) async throws {
        microphone = enabled
        if enabled { publications += 1 }
    }
    func setCamera(enabled: Bool) async throws {
        camera = enabled
        if enabled { publications += 1 }
    }
    func isMicrophoneEnabled() -> Bool { microphone }
    func isCameraEnabled() -> Bool { camera }
}
@MainActor final class Room {
    let connectionState = Connection.connected
    let e2eeManager: E2EEManager? = E2EEManager()
    let localParticipant = LocalParticipant()
}
@MainActor final class ConferencesAPI {
    var value: ConferenceDTO
    var reads = 0
    init(_ value: ConferenceDTO) { self.value = value }
    func room(_ id: UUID) async throws -> ConferenceDTO { reads += 1; return value }
    static func endsConference(_ error: Error) -> Bool { false }
}
@MainActor final class Sender {
    enum Phase { case connected, leaving }
    var phase = Phase.connected
    var monitor: Task<Void, Never>?
    var conference: ConferenceDTO?
    let api: ConferencesAPI
    var room: Room? = Room()
    // These model the shared index-0 key already held by both devices. The
    // production GCM gate does not inspect its epoch or revocation authority.
    let retainedSharedKey = Data(repeating: 7, count: 32)
    var provider: Data? = Data(repeating: 7, count: 32)
    var expectedIdentity: String? = "old-room-sender"
    var roomSettingsRevision = 0
    var changingRoomSettings = false
    var moderatingParticipantID: String?
    var released = 0
    var error: String?
    var mediaTask: Task<Void, Never>?
    var mediaChanging = false
    var mediaFailure: Error?
    var mediaRevision = 0
    var microphoneConsentRevision = 0
    var microphoneEnabled = true
    var cameraEnabled = true
    var microphoneBlockedByOwner = false
    var foreground = true
    let attempt = UUID()
    init(_ value: ConferenceDTO) { conference = value; api = ConferencesAPI(value) }
    func check(_ ticket: UUID) throws {
        try Task.checkCancellation()
        guard ticket == attempt, phase == .connected else { throw CancellationError() }
    }
    func forgetOwnedRoom(_ id: UUID) {}
    func releaseMedia() async {
        released += 1
        phase = .leaving
        room?.localParticipant.microphone = false
        room?.localParticipant.camera = false
        room = nil
        provider = nil
    }
    func applyOwnAudioRestriction(_ room: Room) {}
    func refresh(_ room: Room) {}
    func reconcileMedia() {}
    func attachCameraMirror(_ room: Room) {}
    static func roomError(_ error: Error?) -> String { "closed" }
    static func canRecover(_ error: Error) -> Bool { false }
// PRODUCTION_METHODS
}
