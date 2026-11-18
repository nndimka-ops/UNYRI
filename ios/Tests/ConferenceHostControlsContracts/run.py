"""Native production owner-settings method with suspended API response seams.

Checks authorization, legacy capability fallback, validation, stale account/room
results and the actual additive DTO decoder. Does not contact live resources.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
controller = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")
api = (ROOT / "ios/UNYRI/Conferences/ConferencesAPI.swift").read_text(encoding="utf-8")

def declaration(source, marker):
    start = source.index(marker); opening = source.index("{", start)
    depth = 1; end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}"); end += 1
    return source[start:end]

harness = r'''
import Foundation
__DTOS__
__REQUEST__
enum APIError: Error { case server(String, String) }
struct ConferenceInvite {
    let roomID = UUID(), secret = "synthetic-not-live"
    let server = URL(string: "https://fixture.invalid")!
    static func origin(_ value: URL) throws -> URL { value }
    __INVITE_FAILURE__
}
@MainActor final class Client {
    var getPaths: [String] = []
    var failure = APIError.server("conflict", "Conference admission is locked.")
    func get<T: Decodable>(_ path: String, accessToken: String, as: T.Type) async throws -> T {
        getPaths.append(path); return [] as! T
    }
    func post<B: Encodable, T: Decodable>(_ path: String, accessToken: String, body: B, as: T.Type) async throws -> T { throw failure }
}
@MainActor final class JoinTransport {
    let server = URL(string: "https://fixture.invalid")!
    var client = Client(), current = true
    func token() async throws -> String { "synthetic-not-live" }
    func requireCurrent() throws { if !current { throw APIError.server("session_revoked", "fixture") } }
    __JOIN__
    __CREATE__
    __OWNED__
}
enum Phase { case connected, reconnecting, leaving }
enum Failure: Error { case stale, network }
struct Room { let id: UUID; let state: String; let title: String }
@MainActor final class API {
    var requests: [(UUID, String, Bool)] = []
    var suspension: (() async throws -> Room)?
    func updateSettings(_ id: UUID, title: String, admissionLocked: Bool) async throws -> Room {
        requests.append((id, title, admissionLocked))
        if let suspension { return try await suspension() }
        return Room(id: id, state: "active", title: title)
    }
}
@MainActor final class Owner {
    var hostControlsAvailable = true, isOwner = true, changingRoomSettings = false, retired = false
    var moderatingParticipantID: String?
    var phase = Phase.connected
    var attempt = UUID()
    var roomSettingsRevision: UInt64 = 0
    var conference: Room? = Room(id: UUID(), state: "active", title: "Before")
    var error: String?
    let api = API()
    func check(_ ticket: UUID) throws {
        try Task.checkCancellation()
        if ticket != attempt || retired { throw Failure.stale }
    }
    __METHOD__
}
var checks = 0
func check(_ value: Bool, _ label: String) { precondition(value, label); checks += 1 }
@main struct HostControls {
    @MainActor static func main() async throws {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let legacy = try decoder.decode(ConferenceCapabilities.self, from: Data(#"{"enabled":true,"max_participants":30,"e2ee_required":true}"#.utf8))
        check(legacy.hostControls == nil, "old server never advertises unsupported owner controls")
        check(legacy.ownerRooms == nil, "old server does not expose unsupported owned-room endpoint")
        let current = try decoder.decode(ConferenceCapabilities.self, from: Data(#"{"enabled":true,"max_participants":30,"e2ee_required":true,"host_controls":true}"#.utf8))
        check(current.hostControls == true, "new server enables owner tools explicitly")
        decoder.dateDecodingStrategy = .iso8601
        let oldRoom = Data(#"{"id":"00112233-4455-6677-8899-aabbccddeeff","title":"Legacy","owner_user_id":"00112233-4455-6677-8899-aabbccddeeff","max_participants":30,"state":"active","created_at":"2026-10-29T00:00:00Z","expires_at":"2026-10-29T04:00:00Z","participants":[]}"#.utf8)
        check(try decoder.decode(ConferenceDTO.self, from: oldRoom).admissionLocked == nil, "legacy room decoder keeps admission open by default")
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let payload = try encoder.encode(SettingsRequest(title: "Name", admissionLocked: true))
        let body = try JSONSerialization.jsonObject(with: payload) as! [String: Any]
        check(body["admission_locked"] as? Bool == true && body["title"] as? String == "Name" && body.count == 2,
              "production settings request uses exact snake_case contract")
        let transport = JoinTransport()
        do { _ = try await transport.join(ConferenceInvite()); preconditionFailure("locked join must fail") }
        catch ConferenceInvite.Failure.admissionLocked { check(true, "exact server admission policy maps to a useful retryable UI reason") }
        transport.client.failure = APIError.server("conflict", "Conference is full.")
        do { _ = try await transport.join(ConferenceInvite()); preconditionFailure("full room must fail") }
        catch APIError.server(let code, let message) { check(code == "conflict" && message == "Conference is full.", "different conflicts are not mislabeled as admission lock") }
        transport.client.failure = APIError.server("conflict", "End an existing conference before creating another.")
        do { _ = try await transport.create(title: "Name", limit: 30, hash: "synthetic"); preconditionFailure("owner quota must fail") }
        catch ConferenceInvite.Failure.ownerLimit { check(true, "exact owner quota gets actionable My rooms error") }
        transport.client.failure = APIError.server("conflict", "A different conflict.")
        do { _ = try await transport.create(title: "Name", limit: 30, hash: "synthetic"); preconditionFailure("other conflict must fail") }
        catch APIError.server(_, let message) { check(message == "A different conflict.", "other 409 is not mislabeled as quota") }
        let owned = try await transport.ownedRooms()
        check(owned.isEmpty && transport.client.getPaths == ["/conferences/owned"], "owned-room metadata comes from exact authenticated endpoint")
        transport.current = false
        do { _ = try await transport.ownedRooms(); preconditionFailure("stale owned rooms must fail") }
        catch APIError.server(let code, _) { check(code == "session_revoked", "owned-room response rechecks current account") }
        transport.current = true
        transport.client.failure = APIError.server("conflict", "Conference admission is locked."); transport.current = false
        do { _ = try await transport.join(ConferenceInvite()); preconditionFailure("stale session must fail") }
        catch APIError.server(let code, _) { check(code == "session_revoked", "late lock error cannot bypass current-session fence") }
        let owner = Owner()
        let saved = await owner.updateRoomSettings(title: "  New room  ", admissionLocked: true)
        check(saved && owner.conference?.title == "New room", "only server-confirmed settings replace local state")
        check(owner.api.requests.count == 1 && owner.api.requests[0].1 == "New room" && owner.api.requests[0].2, "canonical title and chosen lock are sent")
        check(!owner.changingRoomSettings && owner.roomSettingsRevision == 2, "successful update releases busy state and fences outstanding GET snapshots")
        for invalid in ["", " \n ", String(repeating: "a", count: 81), "bad\u{0}name", "bad\u{2028}name", "bad\u{2029}name"] {
            let fixture = Owner(), result = await fixture.updateRoomSettings(title: invalid, admissionLocked: true)
            check(!result && fixture.api.requests.isEmpty, "invalid title cannot send a request")
        }
        for gate in 0..<5 {
            let fixture = Owner()
            switch gate {
            case 0: fixture.hostControlsAvailable = false
            case 1: fixture.isOwner = false
            case 2: fixture.phase = .reconnecting
            case 3: fixture.changingRoomSettings = true
            default: fixture.conference = nil
            }
            let result = await fixture.updateRoomSettings(title: "Valid", admissionLocked: false)
            check(!result && fixture.api.requests.isEmpty, "capability, owner, connection, busy and room guards")
        }
        let failed = Owner(); failed.api.suspension = { throw Failure.network }
        let failure = await failed.updateRoomSettings(title: "Never applied", admissionLocked: true)
        check(!failure && failed.conference?.title == "Before" && !failed.changingRoomSettings && failed.error != nil,
              "network failure keeps confirmed settings and supplies retry error")
        let changed = Owner(); changed.api.suspension = {
            changed.attempt = UUID()
            return Room(id: changed.conference!.id, state: "active", title: "Stale")
        }
        let stale = await changed.updateRoomSettings(title: "Stale", admissionLocked: true)
        check(!stale && changed.conference?.title == "Before" && changed.error == nil, "late account/attempt result never publishes into successor")
        let wrong = Owner(); wrong.api.suspension = { Room(id: UUID(), state: "active", title: "Wrong room") }
        let wrongResult = await wrong.updateRoomSettings(title: "Wrong room", admissionLocked: true)
        check(!wrongResult && wrong.conference?.title == "Before", "response cannot replace a different room")
        let closed = Owner(); closed.api.suspension = { Room(id: closed.conference!.id, state: "ended", title: "Ended") }
        let closedResult = await closed.updateRoomSettings(title: "Ended", admissionLocked: true)
        check(!closedResult && closed.conference?.title == "Before", "ended room result cannot reopen owner controls")
        print("PASS: \(checks) production host controls authorization/validation/stale-response/DTO assertions")
    }
}
'''
harness = harness.replace("__DTOS__", declaration(api, "struct ConferenceCapabilities:") + "\n" + declaration(api, "struct ConferenceDTO:") + "\n" + declaration(api, "struct ConferenceJoinDTO:"))
request = declaration(declaration(api, "func updateSettings(_ id: UUID, title: String"), "struct Request:").replace("struct Request:", "struct SettingsRequest:")
harness = harness.replace("__REQUEST__", request)
harness = harness.replace("__INVITE_FAILURE__", declaration((ROOT / "ios/UNYRI/Conferences/ConferenceInvite.swift").read_text(encoding="utf-8"), "enum Failure:"))
harness = harness.replace("__JOIN__", declaration(api, "func join("))
harness = harness.replace("__CREATE__", declaration(api, "func create("))
harness = harness.replace("__OWNED__", declaration(api, "func ownedRooms("))
harness = harness.replace("__METHOD__", declaration(controller, "func updateRoomSettings"))
with tempfile.TemporaryDirectory(prefix="coco-conference-host-settings-") as directory:
    folder = Path(directory); entry = folder / "main.swift"
    executable = folder / ("checks.exe" if os.name == "nt" else "checks")
    entry.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(entry), "-o", str(executable)], check=True, timeout=60)
    subprocess.run([str(executable)], check=True, timeout=20)
