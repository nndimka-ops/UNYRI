"""Run production moderation, permission and API methods with bounded seams.

No server, SDK transport, capture or physical-device result is simulated as
passed. The executable covers authorization, request encoding, stale responses
and the consent rule that lifting an owner restriction never enables a mic.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
controller = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")
api = (ROOT / "ios/UNYRI/Conferences/ConferencesAPI.swift").read_text(encoding="utf-8")


def member(source, marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func", "func", 1)


harness = r'''
import Foundation
__CAPABILITIES__
__DTO__
enum Failure: Error { case stale, offline }
enum Phase { case connected, reconnecting, leaving }
enum Track { enum Source: Int { case microphone = 1, camera = 2 } }
final class ParticipantPermissions: @unchecked Sendable {
    var canPublish = true; var canPublishSources = Set<Int>()
}
class Participant: @unchecked Sendable {}
final class LocalTrackPublication {
    var mutes = 0
    func mute() async throws { mutes += 1 }
}
final class LocalParticipant: Participant, @unchecked Sendable {
    var permissions = ParticipantPermissions()
    var audioTracks = [LocalTrackPublication()]
}
final class Room { let localParticipant = LocalParticipant() }
@MainActor final class Gate {
    var entered = false
    var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
let ownerID = UUID(uuidString: "00112233-4455-4677-8899-aabbccddeeff")!
let peerID = UUID(uuidString: "00112233-4455-4677-8899-aabbccddee01")!
let roomID = UUID(uuidString: "00112233-4455-4677-8899-aabbccddee02")!
func fixture(_ blocked: Bool = false, identity: String = "peer", id: UUID = roomID, state: String = "active") -> ConferenceDTO {
    let object: [String: Any] = [
        "id": id.uuidString, "owner_user_id": ownerID.uuidString, "title": "Fixture room",
        "max_participants": 30, "state": state, "created_at": "2026-10-29T00:00:00Z", "expires_at": "2026-10-30T00:00:00Z",
        "participants": [
            ["user_id": ownerID.uuidString, "device_id": ownerID.uuidString, "display_name": "Owner", "participant_identity": "self", "audio_publish_blocked": blocked],
            ["user_id": peerID.uuidString, "device_id": peerID.uuidString, "display_name": "Peer", "participant_identity": identity, "audio_publish_blocked": false]
        ]
    ]
    let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase; decoder.dateDecodingStrategy = .iso8601
    return try! decoder.decode(ConferenceDTO.self, from: JSONSerialization.data(withJSONObject: object))
}
@MainActor final class API {
    var isCurrentSession = true
    var requests: [(UUID, UUID, String, Bool?)] = []
    var response = fixture()
    var suspension: (() async throws -> ConferenceDTO)?
    func requireCurrent() throws {}
    func moderateAudio(_ id: UUID, userID: UUID, participantIdentity: String, blocked: Bool) async throws -> ConferenceDTO {
        requests.append((id, userID, participantIdentity, blocked))
        return try await suspension?() ?? response
    }
    func removeParticipant(_ id: UUID, userID: UUID, participantIdentity: String) async throws -> ConferenceDTO {
        requests.append((id, userID, participantIdentity, nil))
        return try await suspension?() ?? response
    }
}
@MainActor final class Owner {
    var participantModerationAvailable = true, isOwner = true, changingRoomSettings = false, retired = false
    var microphoneBlockedByOwner = false, microphoneEnabled = true
    var moderatingParticipantID: String?
    var phase = Phase.connected, attempt = UUID()
    var roomSettingsRevision: UInt64 = 0
    var microphoneConsentRevision: UInt64 = 0
    var conference: ConferenceDTO? = fixture()
    var expectedIdentity: String? = "self"
    var room: Room? = Room()
    var error: String?
    var reconciles = 0
    var permissionGate: Gate?
    var toggleTask: Task<Void, Never>?
    let api = API()
    func check(_ ticket: UUID) throws {
        try Task.checkCancellation()
        if ticket != attempt || retired { throw Failure.stale }
    }
    func reconcileMedia() { reconciles += 1 }
    func refresh(_ room: Room) { applyOwnAudioRestriction(room) }
    func requestPermissions(microphone: Bool, camera: Bool) async throws { if let permissionGate { await permissionGate.wait() } }
    func changeMedia(_ body: @escaping (Room) async throws -> Void) {
        guard let room else { return }
        toggleTask = Task { try? await body(room) }
    }
    __MODERATE__
    __RESTRICTION__
    __REVOKE__
    __PERMISSION_EVENT__
    __TOGGLE__
}
@MainActor final class Client {
    var paths: [String] = [], bodies: [[String: Any]] = []
    func post<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as: T.Type) async throws -> T {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        paths.append(path); bodies.append(try JSONSerialization.jsonObject(with: encoder.encode(body)) as! [String: Any])
        return fixture() as! T
    }
}
@MainActor final class Transport {
    let client = Client()
    var current = true
    func token() async throws -> String { "synthetic-not-a-live-token" }
    func requireCurrent() throws { if !current { throw Failure.stale } }
    __AUDIO_REQUEST__
    __REMOVE_REQUEST__
}
@main struct Contracts {
    @MainActor static func main() async throws {
        var count = 0
        func check(_ value: @autoclosure () -> Bool, _ label: String) { precondition(value(), label); count += 1 }
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let legacy = try decoder.decode(ConferenceCapabilities.self, from: Data(#"{"enabled":true,"max_participants":30,"e2ee_required":true}"#.utf8))
        check(legacy.participantModeration == nil, "old capability cannot expose new remote actions")
        let current = try decoder.decode(ConferenceCapabilities.self, from: Data(#"{"enabled":true,"max_participants":30,"e2ee_required":true,"participant_moderation":true}"#.utf8))
        check(current.participantModeration == true, "explicit moderation capability decodes")
        let legacyMember = try decoder.decode(ConferenceDTO.Member.self, from: Data(#"{"user_id":"00112233-4455-4677-8899-aabbccddeeff","device_id":"00112233-4455-4677-8899-aabbccddeeff","display_name":"Legacy"}"#.utf8))
        check(legacyMember.participantIdentity == nil && legacyMember.audioPublishBlocked == nil, "legacy members stay readable without assumed moderation authority")
        let transport = Transport()
        _ = try await transport.moderateAudio(roomID, userID: peerID, participantIdentity: "peer", blocked: true)
        _ = try await transport.removeParticipant(roomID, userID: peerID, participantIdentity: "peer")
        check(transport.client.paths[0] == "/conferences/\(roomID.uuidString.lowercased())/participants/\(peerID.uuidString.lowercased())/audio", "audio endpoint matches server contract")
        check(transport.client.paths[1].hasSuffix("/remove"), "remove endpoint is distinct")
        check(transport.client.bodies[0]["participant_identity"] as? String == "peer" && transport.client.bodies[0]["blocked"] as? Bool == true && transport.client.bodies[0].count == 2,
              "audio payload contains exact identity fence and chosen permission only")
        check(transport.client.bodies[1]["participant_identity"] as? String == "peer" && transport.client.bodies[1].count == 1,
              "remove payload never sends invitation or media key")
        transport.current = false
        do { _ = try await transport.removeParticipant(roomID, userID: peerID, participantIdentity: "peer"); preconditionFailure("late stale reply must fail") }
        catch Failure.stale { check(true, "transport rechecks generation after response") }
        for guardIndex in 0..<7 {
            let value = Owner()
            switch guardIndex {
            case 0: value.participantModerationAvailable = false
            case 1: value.isOwner = false
            case 2: value.phase = .reconnecting
            case 3: value.moderatingParticipantID = "other"
            case 4: value.changingRoomSettings = true
            case 5: value.conference = nil
            default: value.conference = fixture(identity: "successor-identity")
            }
            let result = await value.moderateParticipant("peer", audioBlocked: true)
            check(!result && value.api.requests.isEmpty, "unsupported, unauthorized, stale identity or busy actions never reach transport")
        }
        let selfTarget = Owner(), selfResult = await selfTarget.moderateParticipant("self", audioBlocked: true)
        check(!selfResult && selfTarget.api.requests.isEmpty, "owner cannot accidentally remotely remove/mute own member")
        let valid = Owner(), muted = await valid.moderateParticipant("peer", audioBlocked: true)
        check(muted && valid.api.requests.count == 1 && valid.api.requests[0].1 == peerID && valid.api.requests[0].2 == "peer" && valid.api.requests[0].3 == true,
              "SDK identity maps to authenticated server member instead of display name")
        check(valid.moderatingParticipantID == nil && valid.roomSettingsRevision == 2, "operation releases busy flag and fences older monitor replies")
        let removed = Owner(), removedResult = await removed.moderateParticipant("peer", audioBlocked: nil)
        check(removedResult && removed.api.requests.first?.3 == nil, "remove is explicit separate action")
        let offline = Owner(); offline.api.suspension = { throw Failure.offline }
        let failed = await offline.moderateParticipant("peer", audioBlocked: true)
        check(!failed && offline.error != nil && offline.moderatingParticipantID == nil, "network failure supplies retry and never reports false success")
        let stale = Owner(); stale.api.suspension = { stale.attempt = UUID(); return fixture(true) }
        let staleResult = await stale.moderateParticipant("peer", audioBlocked: true)
        check(!staleResult && stale.conference?.participants.first?.audioPublishBlocked == false && stale.error == nil, "stale account/room reply cannot overwrite successor")
        let wrong = Owner(); wrong.api.response = fixture(id: UUID())
        let wrongResult = await wrong.moderateParticipant("peer", audioBlocked: nil)
        check(!wrongResult && wrong.conference?.id == roomID, "wrong room response cannot publish")
        let ended = Owner(); ended.api.response = fixture(state: "ended")
        let endedResult = await ended.moderateParticipant("peer", audioBlocked: nil)
        check(!endedResult && ended.conference?.state == "active", "ended response cannot reopen room controls")
        let local = Owner(), sdk = local.room!
        local.conference = fixture(true); local.applyOwnAudioRestriction(sdk)
        check(local.microphoneBlockedByOwner && !local.microphoneEnabled && local.reconciles == 1, "owner restriction clears local capture intent and reconciles promptly")
        local.conference = fixture(false); local.applyOwnAudioRestriction(sdk)
        check(!local.microphoneBlockedByOwner && !local.microphoneEnabled, "lifting owner restriction never resumes microphone without consent")
        local.toggleMicrophone(); await local.toggleTask?.value
        check(local.microphoneEnabled, "only explicit local tap restores permission-cleared microphone")
        sdk.localParticipant.permissions.canPublishSources = [Track.Source.camera.rawValue]
        local.applyOwnAudioRestriction(sdk)
        check(local.microphoneBlockedByOwner && !local.microphoneEnabled, "SDK source restriction wins before metadata polling arrives")
        local.toggleTask = nil; local.toggleMicrophone()
        check(local.toggleTask == nil && !local.microphoneEnabled, "blocked tap cannot start permissions or capture")
        check(local.error == "The room creator disabled your microphone. You can enable it yourself after the restriction is lifted.", "first blocked tap supplies useful information without capture")
        sdk.localParticipant.permissions.canPublishSources = []
        local.applyOwnAudioRestriction(sdk)
        check(!local.microphoneBlockedByOwner && !local.microphoneEnabled, "SDK unblocking also never restores previous capture intent")
        let oldRoom = Room(); oldRoom.localParticipant.permissions.canPublish = false
        local.applyOwnAudioRestriction(oldRoom)
        check(!local.microphoneBlockedByOwner, "late old SDK permission callback cannot mutate successor room")
        let race = Owner(); race.microphoneEnabled = false
        let gate = Gate(); race.permissionGate = gate; race.toggleMicrophone()
        while !gate.entered { await Task.yield() }
        race.conference = fixture(true); race.applyOwnAudioRestriction(race.room!)
        gate.open(); await race.toggleTask?.value
        check(race.microphoneBlockedByOwner && !race.microphoneEnabled, "owner restriction arriving during local permission await prevents late capture intent")
        let cycle = Owner(); cycle.microphoneEnabled = false
        let cycleGate = Gate(); cycle.permissionGate = cycleGate; cycle.toggleMicrophone()
        while !cycleGate.entered { await Task.yield() }
        cycle.conference = fixture(true); cycle.applyOwnAudioRestriction(cycle.room!)
        cycle.conference = fixture(false); cycle.applyOwnAudioRestriction(cycle.room!)
        cycleGate.open(); await cycle.toggleTask?.value
        check(!cycle.microphoneBlockedByOwner && !cycle.microphoneEnabled,
              "block then allow during permission await still invalidates the old enable gesture")
        let snapshot = Owner(), snapshotRoom = Room(), deniedSnapshot = ParticipantPermissions()
        snapshot.room = snapshotRoom; deniedSnapshot.canPublish = false
        snapshot.room(snapshotRoom, participant: snapshotRoom.localParticipant, didUpdatePermissions: deniedSnapshot)
        for _ in 0..<16 { await Task.yield() }
        check(!snapshot.microphoneEnabled && snapshot.microphoneConsentRevision > 0 && !snapshot.microphoneBlockedByOwner,
              "queued SDK denial clears consent even when current SDK permissions already contain a later allow")
        check(snapshotRoom.localParticipant.audioTracks[0].mutes == 1,
              "permission event directly silences existing SDK publication without waiting behind camera publishing")
        let consentBefore = snapshot.microphoneConsentRevision
        snapshot.room(Room(), participant: snapshotRoom.localParticipant, didUpdatePermissions: deniedSnapshot)
        snapshot.room(snapshotRoom, participant: Participant(), didUpdatePermissions: deniedSnapshot)
        for _ in 0..<8 { await Task.yield() }
        check(snapshot.microphoneConsentRevision == consentBefore,
              "stale room and another participant's permissions never revoke own consent")
        print("PASS: \(count) actual-method participant moderation/consent/API contracts")
    }
}
'''

for placeholder, source, marker in [
    ("__CAPABILITIES__", api, "struct ConferenceCapabilities"),
    ("__DTO__", api, "struct ConferenceDTO"),
    ("__AUDIO_REQUEST__", api, "func moderateAudio("),
    ("__REMOVE_REQUEST__", api, "func removeParticipant("),
    ("__MODERATE__", controller, "func moderateParticipant("),
    ("__RESTRICTION__", controller, "private func applyOwnAudioRestriction("),
    ("__REVOKE__", controller, "private func revokeOwnMicrophoneIntent("),
    ("__PERMISSION_EVENT__", controller, "nonisolated func room(_ room: Room, participant: Participant, didUpdatePermissions"),
    ("__TOGGLE__", controller, "func toggleMicrophone("),
]:
    harness = harness.replace(placeholder, member(source, marker))

with tempfile.TemporaryDirectory(prefix="coco-conference-participant-controls-") as temporary:
    path = Path(temporary)
    main = path / "main.swift"
    main.write_text(harness, encoding="utf-8")
    output = path / ("checks.exe" if os.name == "nt" else "checks")
    subprocess.run(["swiftc", "-parse-as-library", str(main), "-o", str(output)], check=True)
    subprocess.run([str(output)], check=True, timeout=30)
