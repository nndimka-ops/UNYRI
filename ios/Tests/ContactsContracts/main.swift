import Foundation
#if canImport(Combine)
import Combine
#else
// The only Combine pieces ContactsStore uses.
final class AnyCancellable {
    private let cancel: () -> Void
    init(_ cancel: @escaping () -> Void) { self.cancel = cancel }
    deinit { cancel() }
}
struct NotificationStandIn {
    let center: NotificationCenter
    let name: Notification.Name
    func sink(receiveValue: @escaping @Sendable (Notification) -> Void) -> AnyCancellable {
        let token = center.addObserver(forName: name, object: nil, queue: nil, using: receiveValue)
        let center = self.center
        return AnyCancellable { center.removeObserver(token) }
    }
}
extension NotificationCenter {
    func publisher(for name: Notification.Name) -> NotificationStandIn { NotificationStandIn(center: self, name: name) }
}
#endif

extension Notification.Name {
    static let unyriContactsChanged = Notification.Name("UNYRIContactsChanged")
}

@MainActor final class Gate {
    var entered = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}

enum FixtureError: Error { case failed }

func person(_ id: String, _ name: String, username: String? = nil) -> PublicUser {
    PublicUser(id: id, username: username ?? id, displayName: name, bio: nil, avatarUrl: nil, profileVersion: 1, avatarVersion: 1, hasAvatar: false)
}

@MainActor final class Service: ContactsService {
    var blockedAccountScope: String? = "owner-session"
    var blockedAccountOwnerID = "owner"
    var rows: [PublicUser] = []
    /// When set, pages are served in this order instead of `rows`.
    var script: [ContactsPage] = []
    var reads = 0, writes = 0, statusReads = 0
    var failReads = false, failWrites = false, failStatus = false
    var readGate: Gate?, writeGate: Gate?, statusGate: Gate?

    func contactsPage(cursor: String?) async throws -> ContactsPage {
        reads += 1
        let snapshot = rows
        if let gate = readGate { readGate = nil; await gate.wait() }
        if failReads { throw FixtureError.failed }
        if !script.isEmpty { return script.removeFirst() }
        return ContactsPage(items: snapshot.map { ContactEntry(user: $0, source: "chat", addedAt: "2026-11-11") }, nextCursor: nil)
    }

    func contactStatus(userID: String) async throws -> Bool {
        statusReads += 1
        let member = rows.contains { $0.id == userID }
        if let gate = statusGate { statusGate = nil; await gate.wait() }
        if failStatus { throw FixtureError.failed }
        return member
    }

    func setContact(userID: String, contact: Bool) async throws {
        writes += 1
        if let gate = writeGate { writeGate = nil; await gate.wait() }
        if failWrites { throw FixtureError.failed }
        if contact { if !rows.contains(where: { $0.id == userID }) { rows.append(person(userID, userID)) } }
        else { rows.removeAll { $0.id == userID } }
    }
}

@main struct Run {
    @MainActor static func main() async {
        var checks = 0
        func check(_ ok: Bool, _ why: String) { precondition(ok, why); checks += 1 }
        func waitFor(_ why: String, _ condition: () -> Bool) async {
            for _ in 0..<3000 { if condition() { return }; try? await Task.sleep(nanoseconds: 1_000_000) }
            preconditionFailure("timed out: \(why)")
        }
        func page(_ users: [PublicUser], next: String?) -> ContactsPage {
            ContactsPage(items: users.map { ContactEntry(user: $0, source: "manual", addedAt: "2026-11-11") }, nextCursor: next)
        }
        let anna = person("a1", "Anna"), boris = person("b2", "boris"), own = person("owner", "Me")
        let zed = person("z9", "Zed"), anna2 = person("a0", "Anna", username: "anna_k")

        do {
            let service = Service(); service.rows = [zed, anna, boris, anna, own]
            let store = ContactsStore(service: service)
            await store.ensureLoaded()
            check(store.phase == .loaded, "first load completes")
            check(store.entries.map(\.id) == ["a1", "b2", "z9"], "own id dropped, duplicates removed, sorted by name")
            check(store.ids == ["a1", "b2", "z9"], "ids follow entries")
            await store.ensureLoaded()
            check(service.reads == 1, "a fresh list is not fetched again")
        }
        do {
            let service = Service(); service.rows = [anna, anna2]
            let store = ContactsStore(service: service); await store.reload()
            check(store.entries.map(\.id) == ["a1", "a0"], "equal names order by username")
        }
        do {
            let service = Service()
            let cursor = UUID().uuidString.lowercased()
            service.script = [page([anna], next: cursor), page([boris], next: nil)]
            let store = ContactsStore(service: service); await store.reload()
            check(store.entries.map(\.id) == ["a1", "b2"] && service.reads == 2, "pages are followed by cursor")
        }
        do {
            let service = Service(); service.rows = [anna]
            let store = ContactsStore(service: service); await store.reload()
            service.script = [page([boris], next: "not-a-uuid")]
            await store.reload()
            check(store.phase == .failed && store.entries.map(\.id) == ["a1"], "a broken cursor fails and keeps the rows shown")
            let repeated = UUID().uuidString.lowercased()
            service.script = [page([boris], next: repeated), page([zed], next: repeated)]
            await store.reload()
            check(store.phase == .failed && service.script.isEmpty, "a repeated cursor never pages forever")
            service.failReads = true
            await store.reload()
            check(store.phase == .failed && store.entries.map(\.id) == ["a1"], "a failed read keeps state")
            service.failReads = false
            await store.ensureLoaded()
            check(store.phase == .loaded && store.entries.map(\.id) == ["a1"], "a failed list reloads on the next use")
        }
        do {
            let service = Service(); service.rows = [anna]
            let store = ContactsStore(service: service); await store.reload()
            check(await store.set(boris, contact: true), "add succeeds")
            check(store.entries.map(\.id) == ["a1", "b2"] && store.ids.contains("b2") && store.changing.isEmpty, "added in sorted place, busy released")
            check(!(await store.set(own, contact: true)), "own account is never added")
            service.failWrites = true
            check(!(await store.set(zed, contact: true)), "a failed write reports failure")
            check(!store.ids.contains("z9") && store.changing.isEmpty, "a failed write changes nothing")
            service.failWrites = false
            let gate = Gate(); service.writeGate = gate
            let first = Task { await store.set(anna, contact: false) }
            await waitFor("write entered") { gate.entered }
            check(store.changing.contains("a1"), "busy while the write runs")
            check(!(await store.set(anna, contact: false)), "one write per person at a time")
            gate.open()
            check(await first.value, "remove succeeds")
            check(store.entries.map(\.id) == ["b2"] && service.writes == 3, "removed; the refused duplicate sent nothing")
        }
        do {
            // A page fetched before a write must not bring back the removed row.
            let service = Service(); service.rows = [anna, boris]
            let store = ContactsStore(service: service); await store.reload()
            store.listAppeared()
            let pageGate = Gate(); service.readGate = pageGate
            let reload = Task { await store.reload() }
            await waitFor("page entered") { pageGate.entered }
            check(await store.set(anna, contact: false), "remove during a reload")
            check(store.entries.map(\.id) == ["b2"], "row gone at once")
            pageGate.open(); await reload.value
            check(!store.ids.contains("a1"), "the stale page is ignored")
            await waitFor("restarted reload") { service.reads >= 3 && store.phase == .loaded }
            check(store.entries.map(\.id) == ["b2"], "the cut-off load restarts with the write included")
        }
        do {
            // The list reloads on a change only while it is on screen.
            let service = Service(); service.rows = [anna]
            let store = ContactsStore(service: service)
            store.markStale()
            try? await Task.sleep(nanoseconds: 20_000_000)
            check(service.reads == 0, "an unused store never fetches")
            store.listAppeared()
            await store.ensureLoaded()
            check(service.reads == 1, "the open list loads once")
            store.listDisappeared()
            service.rows = [anna, boris]
            NotificationCenter.default.post(name: .unyriContactsChanged, object: nil)
            try? await Task.sleep(nanoseconds: 20_000_000)
            check(service.reads == 1, "a bare hint with the list closed fetches nothing")
            store.listAppeared()
            await store.ensureLoaded()
            check(service.reads == 2 && store.entries.map(\.id) == ["a1", "b2"], "the next visit picks the change up")
            service.rows = [anna, boris, zed]
            NotificationCenter.default.post(name: .unyriContactsChanged, object: nil)
            await waitFor("hint reload") { store.ids.contains("z9") }
            check(store.entries.map(\.id) == ["a1", "b2", "z9"], "a bare hint reloads the open list")
            store.listDisappeared()
            store.listDisappeared()
            store.listAppeared()
            store.markStale()
            await waitFor("reload after the clamp") { service.reads == 4 && store.phase == .loaded }
            check(store.entries.count == 3, "extra disappear calls never go below zero")
            store.hide("a1")
            check(!store.ids.contains("a1"), "a blocked contact hides at once")
            service.blockedAccountScope = "other-session"
            await store.ensureLoaded()
            check(store.entries.isEmpty && store.phase == .idle, "another session clears the store")
            check(!(await store.set(zed, contact: true)), "and refuses writes")
        }
        do {
            // A named hint patches one person and never pages the book.
            let full = Data(#"{"type":"contacts.changed","user_id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","is_contact":true}"#.utf8)
            check(ContactsChangeHint.parse(full) == ContactsChangeHint(userID: "6f9619ff-8b86-d011-b42d-00c04fc964ff", isContact: true),
                  "a current server's hint names the person, lowercased")
            check(ContactsChangeHint.parse(Data(#"{"type":"contacts.changed"}"#.utf8)) == nil, "an older server's bare hint names nobody")
            check(ContactsChangeHint.parse(Data(#"{"type":"profile.changed","user_id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","is_contact":true}"#.utf8)) == nil,
                  "another frame is not a contacts hint")

            let service = Service(); service.rows = [anna, boris]
            let store = ContactsStore(service: service)
            store.listAppeared(); await store.ensureLoaded(); store.listDisappeared()
            NotificationCenter.default.post(name: .unyriContactsChanged, object: ContactsChangeHint(userID: "a1", isContact: false))
            await waitFor("named removal") { !store.ids.contains("a1") }
            check(store.entries.map(\.id) == ["b2"] && service.reads == 1, "a named removal drops the row without a fetch")
            NotificationCenter.default.post(name: .unyriContactsChanged, object: ContactsChangeHint(userID: "z9", isContact: true))
            await waitFor("named add") { store.ids.contains("z9") }
            check(service.reads == 1, "a named add with the list closed fetches nothing")
            NotificationCenter.default.post(name: .unyriContactsChanged, object: ContactsChangeHint(userID: "owner", isContact: true))
            try? await Task.sleep(nanoseconds: 20_000_000)
            check(!store.ids.contains("owner"), "a hint about the owner is ignored")
            service.rows = [boris, zed]
            store.listAppeared(); await store.ensureLoaded()
            check(service.reads == 2 && store.entries.map(\.id) == ["b2", "z9"], "the added row arrives with the next visit")
        }
        do {
            // One person's membership: never the whole book.
            let service = Service(); service.rows = [anna]
            let store = ContactsStore(service: service)
            await store.check(anna)
            check(store.ids.contains("a1") && service.statusReads == 1 && service.reads == 0, "a profile row asks about one person")
            await store.check(boris)
            check(store.nonMembers.contains("b2") && !store.ids.contains("b2"), "a non-member is known as one")
            await store.check(own)
            check(service.statusReads == 2, "the owner is never asked about")
            service.failStatus = true
            await store.check(zed)
            check(!store.ids.contains("z9") && !store.nonMembers.contains("z9") && store.checking.isEmpty, "a failed answer leaves the person unknown")
            service.failStatus = false

            // A write that lands while a check runs wins over the older answer.
            let gate = Gate(); service.statusGate = gate
            let asking = Task { await store.check(zed) }
            await waitFor("status entered") { gate.entered }
            check(store.checking.contains("z9"), "busy while asking")
            check(await store.set(zed, contact: true), "add during a check")
            gate.open(); await asking.value
            check(store.ids.contains("z9"), "the older 'not a contact' answer is ignored")

            // A current list answers without a request.
            store.listAppeared(); await store.ensureLoaded()
            let before = service.statusReads
            await store.check(anna); await store.check(boris)
            check(service.statusReads == before && store.nonMembers.contains("b2"), "a fresh loaded list answers for everyone")
            store.markStale()
            await waitFor("stale reload") { service.reads == 2 && store.phase == .loaded }
            check(store.revision > 0, "a change that names nobody asks open rows again")
        }
        do {
            // Filtering uses keys folded once, off the main thread.
            let zoe = person("o5", "Zoë Ångström", username: "zoe_a")
            let service = Service(); service.rows = [anna, boris, zoe]
            let store = ContactsStore(service: service)
            store.listAppeared(); await store.ensureLoaded()
            check(await store.matching("").map(\.id) == ["a1", "b2", "o5"], "an empty query lists everyone")
            check(await store.matching("ZOE").map(\.id) == ["o5"], "case and diacritics are ignored")
            check(await store.matching("angst").map(\.id) == ["o5"], "a name fragment matches")
            check(await store.matching("zoe_").map(\.id) == ["o5"], "the username matches")
            check(await store.matching("nobody").isEmpty, "no match is empty")
            check(await store.set(person("c3", "Céline"), contact: true), "add to a loaded list")
            check(await store.matching("celine").map(\.id) == ["c3"], "an added row is searchable at once")
        }
        let hiddenContact = person("88888888-8888-4888-8888-888888888888", "Al\u{FFF0}ice", username: "nickname_six")
        let normalContact = person("99999999-9999-4999-8999-999999999999", "Zed")
        let contactEntries = page([normalContact, hiddenContact], next: nil).items
        let prepared = ContactsStore.prepared(contactEntries, excluding: "owner")
        check(prepared.entries.first?.id == hiddenContact.id, "sort uses sanitized names")
        check(prepared.keys[hiddenContact.id]?.contains("alice") == true, "search key uses sanitized name")
        check(prepared.entries.first == contactEntries.last, "presentation preserves the contact payload and source metadata")
        check(DirectoryCachePolicy.matching([hiddenContact], query: "Alice").first?.id == hiddenContact.id, "directory matches the displayed name")
        print("Contacts store contracts: \(checks) passed")
    }
}
