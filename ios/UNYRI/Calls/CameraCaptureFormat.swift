import Foundation

// MARK: - Camera selection / capture format (pure, deterministic, testable)

/// Which physical camera a call is using for local video.
enum CameraPosition: String, Equatable {
    case front, back

    var toggled: CameraPosition { self == .front ? .back : .front }
}

/// Platform-agnostic description of a camera's capture capability — mirrors
/// the facts Android's `Camera2Enumerator`/`startCapture(1280, 720, 30)` uses
/// to pick a format. Kept free of `AVCaptureDevice.Format` so the selection
/// rule is deterministic and unit-testable on macOS CI.
struct CaptureFormatInfo: Equatable {
    let width: Int
    let height: Int
    let maxFps: Double
}

/// Deterministic capture-format selector. Android starts capture at
/// 1280x720@30fps; this mirrors the same selection principle:
///  1. Prefer formats supporting the target FPS.
///  2. Among those, choose the one whose area is closest to the target area.
///  3. Tie-break by exact width/height match, then by max frame rate.
/// Never selects the largest format blindly (a 4K sensor format would waste
/// bandwidth and CPU on a 720p call).
enum CaptureFormatSelector {
    static let targetWidth = 1280
    static let targetHeight = 720
    static let targetFps: Double = 30

    /// Returns the index into `formats` of the best match, or `nil` if there
    /// are no formats (no camera).
    static func bestIndex(in formats: [CaptureFormatInfo], width: Int = targetWidth, height: Int = targetHeight, fps: Double = targetFps) -> Int? {
        guard !formats.isEmpty else { return nil }

        let targetArea = width * height

        func score(_ f: CaptureFormatInfo) -> (fpsOk: Bool, areaDelta: Int, exact: Bool, fps: Double) {
            let area = f.width * f.height
            return (f.maxFps >= fps, abs(area - targetArea), f.width == width && f.height == height, f.maxFps)
        }

        let best = formats.enumerated().min { lhs, rhs in
            let a = score(lhs.element)
            let b = score(rhs.element)
            if a.fpsOk != b.fpsOk { return a.fpsOk }               // FPS-capable first
            if a.areaDelta != b.areaDelta { return a.areaDelta < b.areaDelta }
            if a.exact != b.exact { return a.exact }
            return a.fps > b.fps
        }
        return best?.offset
    }
}

// Limits are ceilings. Native congestion control may always reduce the stream.
enum CallQuality: String, CaseIterable {
    case auto, economy, high
    static let videoKey = "callVideoQuality"
    static let audioKey = "callAudioQuality"
    static func parse(_ value: String?) -> CallQuality { value.flatMap(Self.init(rawValue:)) ?? .auto }
    var video: CallVideoLimits {
        switch self {
        case .auto: return CallVideoLimits(width: 1280, height: 720, fps: 30, bitrate: 1_500_000)
        case .economy: return CallVideoLimits(width: 640, height: 360, fps: 15, bitrate: 300_000)
        case .high: return CallVideoLimits(width: 1920, height: 1080, fps: 30, bitrate: 2_500_000)
        }
    }
    var audioBitrate: Int {
        switch self { case .auto: return 32_000; case .economy: return 16_000; case .high: return 48_000 }
    }
}
struct CallVideoLimits { let width: Int; let height: Int; let fps: Int; let bitrate: Int }
