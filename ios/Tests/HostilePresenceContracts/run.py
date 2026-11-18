"""Exercise the actual room monitor with a presence-only departure and re-entry.
No LiveKit, network, timers outside the existing monitor, or real account data.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root/"ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")
start = source.index("    private func startMonitor(")
end = source.index("{", start) + 1
depth = 1
while depth:
    depth += (source[end] == "{") - (source[end] == "}")
    end += 1
method = source[start:end].replace("private func", "func", 1)
harness = r"""
import Foundation
struct Member { let userId: UUID; let deviceId: UUID }
struct ConferenceDTO {
 let id: UUID; var participants: [Member]
 var state = "active"; var expiresAt = Date().addingTimeInterval(60)
}
enum Connection { case connected }
final class Room { let connectionState = Connection.connected }
enum ConferenceInvite { enum Failure: Error { case closed } }
@MainActor final class ConferencesAPI {
 var value: ConferenceDTO
 init(_ value: ConferenceDTO) { self.value = value }
 func room(_ id: UUID) async throws -> ConferenceDTO { value }
 static func endsConference(_ error: Error) -> Bool { false }
}
@MainActor final class Owner {
 var monitor: Task<Void, Never>?
 var conference: ConferenceDTO?
 let api: ConferencesAPI
 var room: Room? = Room()
 var roomSettingsRevision = 0
 var changingRoomSettings = false
 var moderatingParticipantID: String?
 var released = 0; var forgotten = 0
 var error: String?
 init(_ value: ConferenceDTO) { conference = value; api = ConferencesAPI(value) }
 func check(_ ticket: UUID) throws { try Task.checkCancellation() }
 func forgetOwnedRoom(_ id: UUID) { forgotten += 1 }
 func releaseMedia() async { released += 1 }
 func applyOwnAudioRestriction(_ room: Room) {}
 func refresh(_ room: Room) {}
 func reconcileMedia() {}
 static func roomError(_ error: Error?) -> String { "closed" }
METHOD
}
@main struct Check {
 @MainActor static func main() async throws {
  let a = Member(userId: UUID(), deviceId: UUID()), b = Member(userId: UUID(), deviceId: UUID())
  let room = ConferenceDTO(id: UUID(), participants: [a, b])
  let owner = Owner(room)
  owner.api.value.participants = [a] // Honest server removes presence on Leave.
  owner.startMonitor(UUID(), immediately: true)
  for _ in 0..<100 { if owner.conference?.participants.count == 1 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
  owner.monitor?.cancel()
  precondition(owner.conference?.participants.count == 1 && owner.released == 0 && owner.forgotten == 0 && owner.error == nil)
  owner.api.value = room // The same participant can enter the same room again.
  owner.startMonitor(UUID(), immediately: true)
  for _ in 0..<100 { if owner.conference?.participants.count == 2 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
  owner.monitor?.cancel()
  precondition(owner.conference?.participants.count == 2 && owner.released == 0 && owner.forgotten == 0)
  owner.api.value.state = "ended"
  owner.startMonitor(UUID(), immediately: true)
  for _ in 0..<100 { if owner.released == 1 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
  owner.monitor?.cancel()
  precondition(owner.released == 1 && owner.forgotten == 1)
  print("PASS: production monitor preserves ordinary Leave/re-entry and still ends a closed room")
 }
}
""".replace("METHOD", method)
with tempfile.TemporaryDirectory(prefix="unyri-presence-") as tmp:
    folder = Path(tmp)
    swift = folder/"Check.swift"; swift.write_text(harness, encoding="utf-8")
    exe = folder/"check"
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True, timeout=10)