import Foundation

/// Display-only policy. It does not activate media, change audio routes or
/// bypass a user's explicit device lock. Only a foreground visual call holds
/// the automatic idle timer; normal audio calls retain the system default.
enum CallDisplayWakePolicy {
    static func keepsAwake(state: CallUiState, appActive: Bool, mediaState: ConnectedState? = nil) -> Bool {
        guard appActive else { return false }
        switch state {
        case .incomingRinging(let call), .outgoingRinging(let call), .connecting(let call):
            return call.mediaKind == .video
        case .connected(let current):
            return hasVisualMedia(current)
        case .reconnecting(let call):
            // The original CallInfo kind does not change on video upgrades.
            // Prefer current media controls, but never borrow another call's.
            if let mediaState, let id = UUID(uuidString: call.callId),
               UUID(uuidString: mediaState.call.callId) == id {
                return hasVisualMedia(mediaState)
            }
            return call.mediaKind == .video
        case .idle, .ended, .failed:
            return false
        }
    }

    private static func hasVisualMedia(_ state: ConnectedState) -> Bool {
        state.videoMode == .video || state.localVideoEnabled || state.remoteVideoEnabled ||
            state.localCameraState == .starting || state.localCameraState == .on ||
            state.remoteVideoState == .waiting || state.remoteVideoState == .on
    }
}
