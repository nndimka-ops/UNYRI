import Foundation

/// The Android saved tokens use 8-bit alpha; match that material on iOS too.
enum UnyriAppearanceSurfaceMetrics {
    static func alpha(transparency: Double) -> Double {
        let value = transparency.isFinite ? min(100, max(0, transparency)) : 0
        return (255 * (1 - value / 100)).rounded() / 255
    }
}

/// Shared CSS border-radius normalization, independent from SwiftUI rendering.
enum UnyriAppearanceCornerMetrics {
    static func applyingOverride(_ source: [CGFloat], radius: Double?) -> [CGFloat] {
        source.map { $0 == 0 ? 0 : radius.map { CGFloat($0) } ?? $0 }
    }

    static func normalized(_ source: [CGFloat], width: CGFloat, height: CGFloat) -> [CGFloat] {
        guard source.count == 4 else { return [0, 0, 0, 0] }
        let c = source.map { $0.isFinite ? max(0, $0) : 0 }
        let w = width.isFinite ? max(0, width) : 0
        let h = height.isFinite ? max(0, height) : 0
        let pairs: [(CGFloat, CGFloat)] = [(w, c[0] + c[1]), (w, c[3] + c[2]),
            (h, c[0] + c[3]), (h, c[1] + c[2])]
        let scale = pairs.reduce(CGFloat(1)) { factor, pair in pair.1 > 0 ? min(factor, pair.0 / pair.1) : factor }
        return c.map { $0 * scale }
    }
}
