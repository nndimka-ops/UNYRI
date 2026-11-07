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

    // DateFormatter construction is expensive (locale calendar lookup) and the
    // chat list evaluates a timestamp for every visible row on every body pass;
    // presence/typing publishes re-render all rows. The default (current
    // locale/calendar/timezone) path therefore reuses preconfigured formatters.
    // Formatting stays inside the lock: a configured DateFormatter is shared
    // across threads, so it is never mutated after construction.
    private static let formatterLock = NSLock()
    private static var formatterLocaleID = ""
    private static var formatterTimeZoneID = ""
    private static var currentTimeFormatter: DateFormatter?
    private static var currentDateFormatter: DateFormatter?

    static func date(fromRFC3339 string: String) -> Date? {
        fractional.date(from: string) ?? whole.date(from: string)
    }

    static func label(for date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let today = calendar.isDate(date, inSameDayAs: now)
        if calendar == .current, locale == .current, let cached = cachedLabel(for: date, today: today) {
            return cached
        }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateStyle = today ? .none : .short
        formatter.timeStyle = today ? .short : .none
        return formatter.string(from: date)
    }

    static func label(forRFC3339 string: String, now: Date = Date()) -> String? {
        guard let date = date(fromRFC3339: string) else { return nil }
        return label(for: date, now: now)
    }

    /// One formatter for today's clock time, one for older dates. Rebuilt when
    /// the device locale or time zone changes at runtime (region/time travel),
    /// so cached labels cannot stay on a stale zone.
    private static func cachedLabel(for date: Date, today: Bool) -> String? {
        let localeID = Locale.current.identifier
        let timeZoneID = TimeZone.current.identifier
        formatterLock.lock()
        defer { formatterLock.unlock() }
        if localeID != formatterLocaleID || timeZoneID != formatterTimeZoneID {
            formatterLocaleID = localeID
            formatterTimeZoneID = timeZoneID
            currentTimeFormatter = nil
            currentDateFormatter = nil
        }
        if today {
            if currentTimeFormatter == nil {
                let formatter = DateFormatter()
                formatter.locale = .current
                formatter.timeZone = .current
                formatter.dateStyle = .none
                formatter.timeStyle = .short
                currentTimeFormatter = formatter
            }
            return currentTimeFormatter?.string(from: date)
        }
        if currentDateFormatter == nil {
            let formatter = DateFormatter()
            formatter.locale = .current
            formatter.timeZone = .current
            formatter.dateStyle = .short
            formatter.timeStyle = .none
            currentDateFormatter = formatter
        }
        return currentDateFormatter?.string(from: date)
    }
}
