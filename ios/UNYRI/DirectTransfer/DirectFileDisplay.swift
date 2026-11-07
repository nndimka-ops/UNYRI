import Foundation

/// Direct-transfer presentation is shared with Android: binary magnitudes,
/// integer half-up tenths, and an ETA taken from the same receiver ACK sample.
enum DirectFileDisplay {
    static func bytes(_ bytes: UInt64, locale: Locale, units: [String]) -> String {
        guard units.count == 4 else { return "" }
        let value = min(bytes, UInt64(DirectFileOffer.maximumBytes))
        var divisor: UInt64 = 1
        var unit = 0
        while unit < 3 && value / divisor >= 1024 { divisor *= 1024; unit += 1 }
        if unit == 0 { return "\(value) " + units[0] }
        let tenths = (value * 10 + divisor / 2) / divisor
        return "\(tenths / 10)\(locale.decimalSeparator ?? ".")\(tenths % 10) " + units[unit]
    }

    static func eta(_ seconds: Int64?, overOneDay: String) -> String? {
        guard let seconds, seconds >= 0 else { return nil }
        if seconds > 86400 { return overOneDay }
        if seconds >= 3600 {
            return String(format: "%lld:%02lld:%02lld", seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
        return String(format: "%02lld:%02lld", seconds / 60, seconds % 60)
    }

    static func percent(offset: Int64, total: Int64) -> Int {
        guard total > 0, total <= DirectFileOffer.maximumBytes else { return 0 }
        return Int(min(total, max(0, offset)) * 100 / total)
    }
}
