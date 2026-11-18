import Foundation

func person(_ id: String, _ name: String, _ username: String = "", online: Bool = false) -> ContactListPerson {
    ContactListPerson(id: id, name: name, username: username, online: online)
}
let people = [
    person("latin", "Alice", "alice"), person("cyr", "Борис", "boris"),
    person("a", "Анна", "anna"), person("online-z", "Zoe", "zoe", online: true),
    person("online-b", "Борис", "boris2", online: true), person("number", "123", "digit"),
    person("yo", "Ёлка", "tree"), person("e", "Елена", "elena"), person("emoji", "🦊", "fox"),
    person("latin", "duplicate", "ignored"), person("accent", "Émile", "emile")
]
let sections = ContactsPresentation.sections(people, query: "")
precondition(sections.map(\.id) == ["online", "А", "Б", "Е", "Ё", "A", "E", "#"])
precondition(sections.first?.people.map(\.id) == ["online-b", "online-z"])
precondition(sections.flatMap(\.people).filter { $0.id == "latin" }.count == 1)
precondition(ContactsPresentation.letter("  борис ") == "Б")
precondition(ContactsPresentation.letter("") == "#" && ContactsPresentation.letter("!") == "#")
precondition(ContactsPresentation.letter("👨‍👩‍👧‍👦") == "#")
precondition(ContactsPresentation.sections(people, query: " @BoRiS ").flatMap(\.people).map(\.id) == ["online-b", "cyr"])
precondition(ContactsPresentation.sections(people, query: "EMI").flatMap(\.people).map(\.id) == ["accent"])
precondition(ContactsPresentation.sections(people, query: "🦊").flatMap(\.people).map(\.id) == ["emoji"])
precondition(ContactsPresentation.sections(people, query: "absent").isEmpty)
let tied = [person("z", "Анна", "same"), person("a", "Анна", "same"), person("b", "Анна", "before")]
precondition(ContactsPresentation.sections(tied, query: "").first?.people.map(\.id) == ["b", "a", "z"])
let cyrillic = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
let alphabet = cyrillic.enumerated().map { person(String($0.offset), String($0.element)) }
precondition(ContactsPresentation.sections(Array(alphabet.reversed()), query: "").map(\.id).joined() == cyrillic)
precondition(ContactsPresentation.serverIDs(["contact", "a", "a", "b", "contact", "b", "c"], excluding: ["contact"]) == ["a", "b", "c"])
precondition(ContactsPresentation.serverIDs([], excluding: []).isEmpty)

for (width, normal, accessible) in [(320.0, 248.0, 288.0), (390, 318, 358), (430, 318, 398), (768, 318, 736)] {
    precondition(DrawerCPolicy.width(screenWidth: width, accessibility: false) == normal)
    precondition(DrawerCPolicy.width(screenWidth: width, accessibility: true) == accessible)
}
for invalid in [Double.nan, .infinity, -.infinity, -100, 0, 20] {
    precondition(DrawerCPolicy.width(screenWidth: invalid, accessibility: false) == 0)
    precondition(DrawerCPolicy.width(screenWidth: invalid, accessibility: true) == 0)
}

// Actual measured width, not a character-count heuristic.
let short = DrawerCPolicy.nameLayout(measuredWidth: 120, availableWidth: 186)
precondition(short.lines == 1 && short.pointSize == 20 && short.effectivePointSize == 20)
let shrunk = DrawerCPolicy.nameLayout(measuredWidth: 210, availableWidth: 186)
precondition(shrunk.lines == 1 && shrunk.effectivePointSize > 16 && shrunk.effectivePointSize < 20)
let floor = DrawerCPolicy.nameLayout(measuredWidth: 232.5, availableWidth: 186)
precondition(floor.lines == 1 && floor.effectivePointSize == 16)
let wrapped = DrawerCPolicy.nameLayout(measuredWidth: 233, availableWidth: 186)
precondition(wrapped.lines == 2 && wrapped.pointSize == 16)
precondition(DrawerCPolicy.nameLayout(measuredWidth: 2_000, availableWidth: 186).lines == 2)
precondition(DrawerCPolicy.nameLayout(measuredWidth: .infinity, availableWidth: 186).lines == 2)
precondition(DrawerCPolicy.nameLayout(measuredWidth: 200, availableWidth: 0).lines == 2)
precondition(abs(13 * DrawerCPolicy.usernameMinimumScale - 11) < 0.0001)
precondition(DrawerCPolicy.accountRow(signedInCount: 1, hasOther: false, canAdd: true) == .add)
precondition(DrawerCPolicy.accountRow(signedInCount: 1, hasOther: false, canAdd: false) == .none)
precondition(DrawerCPolicy.accountRow(signedInCount: 2, hasOther: true, canAdd: false) == .other)
precondition(DrawerCPolicy.accountRow(signedInCount: 2, hasOther: true, canAdd: true) == .other)
precondition(DrawerCPolicy.accountRow(signedInCount: 2, hasOther: false, canAdd: true) == .none)
precondition(DrawerCPolicy.badge(for: "chats") == .accent && DrawerCPolicy.badge(for: "calls") == .danger)
precondition(DrawerCPolicy.badge(for: "archive") == .neutral)
precondition(DrawerCPolicy.count(0) == nil && DrawerCPolicy.count(-1) == nil)
precondition(DrawerCPolicy.count(100) == "99+" && DrawerCPolicy.count(100, capped: false) == "100")

var classifications = ContactClassifications()
let book: Set<String> = ["anna", "boris"]
func classifiedSections(_ state: ContactClassifications) -> [ContactListSection] {
    let online = state.onlineIDs(in: book)
    return ContactsPresentation.sections([person("anna", "Анна", online: online.contains("anna")),
                                           person("boris", "Борис", online: online.contains("boris"))], query: "")
}
precondition(classifiedSections(classifications).map(\.id) == ["А", "Б"])
classifications.apply(id: "boris", online: true)
precondition(classifiedSections(classifications).first?.id == "online")
precondition(classifiedSections(classifications).first?.people.map(\.id) == ["boris"], "The first fresh snapshot promotes a cold-open contact")
classifications.apply(id: "directory-only", online: true)
precondition(classifications.onlineIDs(in: book) == ["boris"])
classifications.apply(id: "boris", online: nil)
precondition(classifications.onlineIDs(in: book) == ["boris"], "A subscription gap is not a new offline classification")
classifications.apply(id: "boris", online: false)
precondition(classifiedSections(classifications).map(\.id) == ["А", "Б"])
precondition(ContactsPresentation.personID("boris") == "person-boris", "Person identity is independent of the section")
precondition(ContactsPresentation.personID("online") != ContactsPresentation.sectionID("online"))
precondition(ContactsScrollPolicy.restoredOffset(rowMinY: 330, viewportOffset: 10, minimum: 0, maximum: 2_000) == 320)
precondition(ContactsScrollPolicy.restoredOffset(rowMinY: 30, viewportOffset: 50, minimum: 0, maximum: 2_000) == 0)
precondition(ContactsScrollPolicy.restoredOffset(rowMinY: 2_500, viewportOffset: 10, minimum: 0, maximum: 2_000) == 2_000)
precondition(ContactsRowPolicy.statusLines(accessibility: true) == 6)
precondition(ContactsRowPolicy.statusHeight(lineHeight: 18, accessibility: false) == 18)
precondition(ContactsRowPolicy.statusHeight(lineHeight: 54, accessibility: true) == 324)
precondition(!ContactsDirectoryPolicy.retryResets(failedOffset: 60))
precondition(ContactsDirectoryPolicy.retryResets(failedOffset: 0) && ContactsDirectoryPolicy.retryResets(failedOffset: nil))

var presence = ContactPresenceValue(serverLastSeen: "Last seen today at 10:00")
presence.apply(liveOnline: nil, uptime: 100)
precondition(!presence.online && presence.label == "Last seen today at 10:00")
presence.apply(liveOnline: false, label: "Last seen today at 11:00", lastSeen: "Last seen today at 11:00", expiresAtUptime: 105, uptime: 100)
presence.apply(liveOnline: nil, uptime: 101)
precondition(presence.label == "Last seen today at 11:00" && !presence.online)
presence.apply(liveOnline: true, label: "online", lastSeen: "Last seen today at 11:01", expiresAtUptime: 105, uptime: 100)
let confirmed = presence
for time in [100.1, 101, 104.999] { presence.apply(liveOnline: nil, uptime: time) }
precondition(presence == confirmed && presence.online && presence.isLive, "Quick scroll away/back retains the personally confirmed lease")
precondition(presence.nextOnlineExpiry == 105, "A subscription gap must never extend the deadline")
precondition(!presence.display(at: 105).online && presence.display(at: 105).label == "Last seen today at 11:01",
             "A resumed row cannot display expired online, even before a delayed expiry task runs")
presence.apply(liveOnline: nil, uptime: 105)
precondition(!presence.online && !presence.isLive && presence.label == "Last seen today at 11:01")
presence.apply(liveOnline: true, label: "online", lastSeen: "Last seen today at 11:02", expiresAtUptime: 110, uptime: 106)
precondition(presence.online)
presence.apply(liveOnline: true, label: "stale", lastSeen: "old", expiresAtUptime: 104, uptime: 107)
precondition(presence.label == "online" && presence.nextOnlineExpiry == 110, "Stale input cannot replace or extend current evidence")
presence.apply(liveOnline: false, label: "Status hidden", lastSeen: "Status hidden")
presence.invalidate()
presence.apply(liveOnline: nil, uptime: 108)
precondition(presence.label == "Status hidden" && !presence.online, "An authoritative hidden status clears the prior timestamp")
var unknown = ContactPresenceValue()
unknown.apply(liveOnline: true, label: "cached online", uptime: 100)
precondition(unknown.label.isEmpty && !unknown.online && !unknown.isLive, "A cached flag without a live lease never creates online")
print("PASS: Contacts live classification, stable IDs/anchor, filtering/dedup, lease handoff/expiry/privacy, Retry cursor and accessibility status height; drawer width/name/account/badge policies")
