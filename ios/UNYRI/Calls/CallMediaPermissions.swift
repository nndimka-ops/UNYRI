import AVFoundation
import Foundation

/// Invoke from an explicit call/video action, never at application launch.
/// Does not start capture or activate the audio session.
enum CallMediaPermissions {
    enum PermissionError: Error, LocalizedError {
        case microphoneDenied, cameraDenied

        var errorDescription: String? {
            switch self {
            case .microphoneDenied: return "Allow microphone access in Settings to make calls."
            case .cameraDenied: return "Allow camera access in Settings to enable video."
            }
        }
    }

    static func request(for kind: CallMediaKind) async throws {
        try Task.checkCancellation()
        guard await authorized(for: .audio) else { throw PermissionError.microphoneDenied }
        try Task.checkCancellation()
        if kind == .video {
            guard await authorized(for: .video) else { throw PermissionError.cameraDenied }
        }
        try Task.checkCancellation()
    }

    private static func authorized(for mediaType: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized: return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: mediaType)
        case .denied, .restricted: return false
        @unknown default: return false
        }
    }
}
