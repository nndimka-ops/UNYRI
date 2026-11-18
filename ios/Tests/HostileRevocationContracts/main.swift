import Foundation

@main struct Main {
    @MainActor static func main() async throws {
        let senderDevice = Member(userId: UUID(), deviceId: UUID())
        let removedDevice = Member(userId: UUID(), deviceId: UUID())
        let snapshot = ConferenceDTO(id: UUID(), participants: [senderDevice, removedDevice])

        let normal = Sender(snapshot)
        let normalRoom = normal.room!
        normal.runMedia(normalRoom) { (_: Room) async throws -> Void in }
        await normal.mediaTask?.value
        precondition(normalRoom.localParticipant.publications == 2,
                     "The fixture must permit ordinary microphone and camera publication")

        // Legitimate terminal response still stops the actual monitor.
        let closed = Sender(snapshot)
        closed.api.value.state = "ended"
        closed.startMonitor(closed.attempt, immediately: true)
        for _ in 0..<100 {
            if closed.released == 1 { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        closed.monitor?.cancel()
        precondition(closed.released == 1 && closed.room == nil)

        // The owner stopped/retired its old room and moved to a fresh room.
        // The hostile server hides that decision from this other sender by
        // returning the exact previous active roster; removed still has K0.
        let sender = Sender(snapshot)
        let keyHeldByRemovedDevice = sender.retainedSharedKey
        sender.startMonitor(sender.attempt, immediately: true)
        for _ in 0..<100 {
            if sender.api.reads > 0 { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        sender.monitor?.cancel()
        guard let media = sender.room else {
            print("PASS: concealed removal fails closed before publication")
            return
        }
        sender.runMedia(media) { (_: Room) async throws -> Void in }
        await sender.mediaTask?.value
        if media.localParticipant.publications > 0 && sender.provider == keyHeldByRemovedDevice {
            FileHandle.standardError.write(Data("FAIL R6: concealed removal still permits audio/video with the removed device's old shared key. Authenticated epochs and a fail-closed sender barrier are not implemented.\n".utf8))
            exit(1)
        }
        print("PASS: old-room sender cannot publish using the revoked shared key")
    }
}
