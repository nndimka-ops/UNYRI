import Foundation

/// Pure voice-message helpers (mirrors Android's voice UI formatting).
enum VoiceFormat {
    static func messageWidth(_ durationMs: Int64) -> CGFloat { 180 + 70 * sqrt(CGFloat(min(120_000, max(0, durationMs))) / 120_000) }

    /// Stable placeholder bars; Swift hashValue is randomized and adding to
    /// it can overflow in debug builds. Explicit wrapping arithmetic is safe.
    static func waveformHeight(id: String, bar: Int) -> Double {
        let seed = id.utf8.reduce(UInt64(14695981039346656037)) { ($0 ^ UInt64($1)) &* 1099511628211 }
        let value = seed &+ (UInt64(truncatingIfNeeded: bar) &* 31)
        return 6 + 22 * Double(value % 100) / 100
    }

    /// Bar count for a given available waveform width: 2pt bar + 2pt gap per
    /// slot, clamped to the 20–32 signature range (Phase 5 §4).
    static func barCount(availableWidth: CGFloat) -> Int {
        let n = Int((availableWidth + 2) / 4)
        return min(max(n, 20), 32)
    }

    /// Played fraction clamped to 0..1; duration <= 0 is treated as 1ms.
    static func playedFraction(positionMs: Int, durationMs: Int64) -> Double {
        let total = max(durationMs, 1)
        return min(max(Double(positionMs) / Double(total), 0), 1)
    }

    /// "m:ss" duration text — identical to Android's `"%d:%02d".format(m, s)`;
    /// switches to "h:mm:ss" once an hour is reached. Negative clamps to zero.
    static func durationText(_ durationMs: Int64) -> String {
        let totalSeconds = max(0, durationMs) / 1000
        let h = totalSeconds / 3600
        let m = (totalSeconds % 3600) / 60
        let s = totalSeconds % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%lld:%02lld", m, s)
    }
}

#if canImport(AVFoundation)
import AVFoundation

/// Serialized, bounded analysis of decoded local audio; no UI-thread decoding.
actor VoiceWaveformCache {
    static let shared = VoiceWaveformCache()
    private var values: [String: [Double]] = [:]
    private var order: [String] = []
    func cached(path: String) -> [Double] {
        guard let key = order.last(where: { $0.hasPrefix(path + ":") }) else { return [] }
        return values[key] ?? []
    }
    func load(path: String) -> [Double] {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: path)
            let key = "\(path):\(attributes[.size] ?? 0):\(attributes[.modificationDate] ?? "")"
            if let cached = values[key] {
                order.removeAll { $0 == key }; order.append(key)
                return cached
            }
            let file = try AVAudioFile(forReading: URL(fileURLWithPath: path), commonFormat: .pcmFormatFloat32, interleaved: false)
            guard file.length > 0, let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 8192) else { return [] }
            var peaks = Array(repeating: 0.0, count: 48)
            while file.framePosition < file.length {
                if Task.isCancelled { return [] }
                let start = file.framePosition
                try file.read(into: buffer)
                guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { break }
                for frame in stride(from: 0, to: Int(buffer.frameLength), by: 8) {
                    let bin = min(47, max(0, Int((start + Int64(frame)) * 48 / file.length)))
                    for channel in 0..<Int(buffer.format.channelCount) {
                        let value = Double(abs(channels[channel][frame]))
                        if value.isFinite { peaks[bin] = max(peaks[bin], value) }
                    }
                }
            }
            let peak = max(0.01, peaks.max() ?? 0)
            let result = peaks.map { min(1, sqrt($0 / peak)) }
            values[key] = result; order.append(key)
            if order.count > 64 { values.removeValue(forKey: order.removeFirst()) }
            return result
        } catch { return [] }
    }
}
#endif
