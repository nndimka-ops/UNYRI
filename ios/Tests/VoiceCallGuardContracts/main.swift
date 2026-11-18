import Foundation
import Combine

@main
struct VoiceCallGuardContracts {
    @MainActor static func main() {
        let recorder = VoiceRecorder()
        var availability: [Bool] = []
        let observation = recorder.$callInProgress.sink { availability.append($0) }
        recorder.callInProgress = true
        let player = VoicePlayerController()
        player.playbackAllowed = { !recorder.callInProgress && !recorder.hasRecordingSession }
        player.togglePlay(attachmentId: "blocked-call", fileURL: URL(fileURLWithPath: "/missing-voice.m4a"))
        precondition(player.state.playingAttachmentId == nil && player.state.pausedAttachmentId == nil)
        do {
            _ = try recorder.start()
            preconditionFailure("Voice recording took the microphone during a call")
        } catch VoiceRecordError.callInProgress {
            precondition(!recorder.isRecording)
            precondition(recorder.recordedSeconds == 0)
        } catch {
            preconditionFailure("Call guard touched the audio session before rejecting: \(error)")
        }
        recorder.cancel()
        recorder.callInProgress = false
        precondition(availability == [false, true, false], "Chat must observe microphone availability immediately")
        withExtendedLifetime(observation) {}
        print("Voice/call microphone guard contracts passed")
    }
}
