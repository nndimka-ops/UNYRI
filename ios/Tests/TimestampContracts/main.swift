import Foundation

func expect(_ condition: Bool, _ description: String) {
    guard condition else { fatalError(description) }
    print("PASS: \(description)")
}
let utc = RelativeTime.date(fromRFC3339: "2026-09-10T22:30:00Z")!
let offset = RelativeTime.date(fromRFC3339: "2026-09-11T01:30:00+03:00")!
expect(utc == offset, "RFC3339 timezone is respected")
expect(RelativeTime.date(fromRFC3339: "2026-09-10T22:30:00.123456789Z") != nil, "server fractional precision parses")
expect(RelativeTime.date(fromRFC3339: "not a date") == nil, "malformed timestamp does not invent a date")
var calendar = Calendar(identifier: .gregorian)
calendar.timeZone = TimeZone(identifier: "Europe/Bucharest")!
let now = RelativeTime.date(fromRFC3339: "2026-09-10T23:00:00Z")!
expect(RelativeTime.label(for: utc, now: now, calendar: calendar, locale: Locale(identifier: "en_GB")) == "01:30", "today uses local time across UTC midnight")
