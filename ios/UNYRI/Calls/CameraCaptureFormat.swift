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
    /// Pixel binning (sensor pixels combined): softer picture, made for low light.
    var isBinned: Bool = false
    /// 4:2:0 bi-planar full range ('420f'), what the WebRTC capturer outputs.
    var isFullRange420: Bool = true
}

/// Deterministic capture-format selector. Android starts capture at
/// 1280x720@30fps; this mirrors the same selection principle:
///  1. Prefer formats supporting the target FPS.
///  2. Among those, choose the one whose area is closest to the target area.
///  3. Then an exact width/height match, an unbinned format, '420f', and the
///     lowest frame rate that still reaches the target (B10): a 120/240 fps
///     slow-motion format of the same size costs power and narrows exposure.
///     Without any format reaching the target, the fastest one is closest.
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

        func better(_ a: CaptureFormatInfo, than b: CaptureFormatInfo) -> Bool {
            let aFps = a.maxFps >= fps, bFps = b.maxFps >= fps
            if aFps != bFps { return aFps }                       // FPS-capable first
            let aDelta = abs(a.width * a.height - targetArea), bDelta = abs(b.width * b.height - targetArea)
            if aDelta != bDelta { return aDelta < bDelta }
            let aExact = a.width == width && a.height == height, bExact = b.width == width && b.height == height
            if aExact != bExact { return aExact }
            if a.isBinned != b.isBinned { return !a.isBinned }
            if a.isFullRange420 != b.isFullRange420 { return a.isFullRange420 }
            if a.maxFps != b.maxFps { return aFps ? a.maxFps < b.maxFps : a.maxFps > b.maxFps }
            return false                                          // keep the device's order
        }

        var best = 0
        for index in formats.indices.dropFirst() where better(formats[index], than: formats[best]) { best = index }
        return best
    }
}
