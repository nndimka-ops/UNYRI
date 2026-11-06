import Foundation

/// Local clock time for today's chats, a compact date for older conversations.
enum RelativeTime {
    private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
    private static let whole: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func date(fromRFC3339 string: String) -> Date? {
        fractional.date(from: string) ?? whole.date(from: string)
    }

    static func label(for date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        let today = calendar.isDate(date, inSameDayAs: now)
        formatter.dateStyle = today ? .none : .short
        formatter.timeStyle = today ? .short : .none
        return formatter.string(from: date)
    }

    static func label(forRFC3339 string: String, now: Date = Date()) -> String? {
        guard let date = date(fromRFC3339: string) else { return nil }
        return label(for: date, now: now)
    }
}
