"""B-038: execute Contacts' real exact-search methods with deterministic peers."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "scripts"))
from ios_contacts_test_support import SOURCE, run

assert "ContactsAddByIdentity" not in SOURCE
assert "contacts-add-identity" not in SOURCE
assert 'Button { UnyriHaptics.press(); changeContact(user, adding: true) }' in SOURCE
assert '.sheet(item: $openedLink) { IdentityLinkView(target: $0) }' in SOURCE
assert 'person(user, contact: contactIDs.contains(user.id))' in SOURCE
assert 'candidate = try await users.publicProfile(user.id).contact' in SOURCE
assert '.onSubmit { Task { @MainActor in await submitSearch() } }' in SOURCE
assert 'if serverErrorRetryable {' in SOURCE

run("swiftc", r'''
@main struct Run {
    @MainActor static func main() async {
        for (raw, target) in [(" 123 456 789 ", PublicIdentityPolicy.Target.number("123456789")),
                              ("@Alice_1", .username("alice_1"))] {
            let m = Model(); m.query = raw
            await m.searchServer(); await m.searchServer()
            await m.submitSearch()
            precondition(m.users.lookups == [target], "A complete exact identity runs once and Return reuses the result")
            precondition(m.users.queries.isEmpty && m.serverResults.map(\.id) == ["exact-person"] && m.nextOffset == nil,
                         "The exact result uses a normal person row, with no directory fallback or paging")
        }
        let typing = Model()
        for digits in ["1", "12", "123", "1234", "12345", "123456", "1234567", "12345678"] {
            typing.query = digits; await typing.searchServer()
            precondition(typing.users.lookups.isEmpty, "A typing pause at a partial number must not spend the exact lookup budget")
        }
        await typing.submitSearch()
        precondition(typing.users.lookups == [.number("12345678")], "An explicit submit still supports a short team alias")
        let invalidation = Model(); invalidation.query = "123456789"
        await invalidation.searchServer(); invalidation.retry += 1; await invalidation.searchServer()
        precondition(invalidation.users.lookups.count == 1, "A retry/privacy revision is not another exact lookup")
        let pending = Model(); pending.query = "123456789"
        let automatic = Task { await pending.searchServer() }
        while pending.lastRemoteKey == nil { await Task.yield() }
        await pending.submitSearch(); await automatic.value
        precondition(pending.users.lookups.count == 1, "Submit consumes an in-flight debounce without a later duplicate")
        let inFlight = Model(); inFlight.query = "123456789"; inFlight.users.wait = true
        let request = Task { await inFlight.searchServer() }
        while inFlight.users.lookups.isEmpty { await Task.yield() }
        await inFlight.submitSearch(); inFlight.users.wait = false; await request.value
        precondition(inFlight.users.lookups.count == 1, "Return reuses a running complete-value lookup")
        let failed = Model(); failed.query = "@Alice_1"; failed.users.failure = .http(404)
        await failed.searchServer()
        precondition(failed.serverResults.isEmpty && failed.users.queries.isEmpty && failed.serverError == "HTTP 404")
        failed.users.failure = nil; await failed.retryServer()
        precondition(failed.users.lookups.count == 2 && failed.serverResults.count == 1, "Retry repeats only the exact lookup")
        let hidden = Model(); hidden.query = "123456789"; hidden.users.failure = .server(code: "forbidden", message: "unavailable")
        await hidden.searchServer()
        precondition(hidden.serverResults.isEmpty && hidden.users.queries.isEmpty && hidden.serverError == "unavailable")
        let blocked = Model(); blocked.query = "123456789"
        blocked.users.lookupFailure = UsersRepository.IdentityLookupError.profileUnavailable
        await blocked.searchServer(); await blocked.retryServer()
        precondition(blocked.serverError == "Profile not available." && !blocked.serverErrorRetryable,
                     "A locally blocked exact match is unavailable without Retry")
        precondition(blocked.users.lookups.count == 1 && blocked.users.queries.isEmpty)
        let own = Model(); own.query = "123456789"; own.users.lookupResponse = DiscoveredProfile(contact: PublicUser(id: "owner"))
        await own.searchServer()
        precondition(own.serverResults.isEmpty, "The owner is never offered as an addable result")
        let stale = Model(); stale.query = "@Alice_1"
        stale.users.action = { stale.query = "@Boris_1"; stale.serverResults = [PublicUser(id: "successor")]; stale.loading = true }
        await stale.loadServer(reset: true)
        precondition(stale.serverResults.map(\.id) == ["successor"] && stale.loading, "An old exact result cannot replace a new query")
        let cancelled = Model(); cancelled.query = "123456789"; cancelled.users.wait = true
        let task = Task { await cancelled.loadServer(reset: true) }
        while cancelled.users.lookups.isEmpty { await Task.yield() }
        cancelled.epoch = UUID(); cancelled.serverResults = [PublicUser(id: "current")]; cancelled.loading = false
        task.cancel(); await task.value
        precondition(cancelled.serverResults.map(\.id) == ["current"] && cancelled.serverError == nil)
        let offline = Model(); offline.query = "@Alice_1"; offline.connected = false
        await offline.searchServer()
        precondition(offline.users.lookups.isEmpty && offline.users.queries.isEmpty)
        for raw in ["unyri://u/n/123456789?server=other.example:8443", "https://other.example:8443/u/@Alice_1"] {
            let m = Model(); m.query = raw; m.connected = false
            await m.searchServer()
            precondition(m.profileLink?.origin == "https://other.example:8443" && m.openedLink == nil,
                         "Pasting a link prepares the explicit action and never opens a sheet")
            m.retry += 1; await m.searchServer()
            precondition(m.openedLink == nil, "A retry/privacy revision never opens a sheet")
            await m.submitSearch()
            precondition(m.openedLink?.origin == "https://other.example:8443", "Return explicitly opens the profile presenter")
            precondition(m.users.queries.isEmpty && m.users.lookups.isEmpty, "A server-bearing link never uses the current account's lookup")
            m.openedLink = nil; m.query = "https://user:pass@evil.example/u/n/123456789"
            await m.submitSearch()
            precondition(m.openedLink == nil && m.profileLink == nil, "An invalid submitted link cannot reopen the previous profile")
        }
        for raw in ["@bad__name", "000000000", "https://user:pass@evil.example/u/n/123456789"] {
            let m = Model(); m.query = raw
            await m.searchServer()
            await m.submitSearch()
            precondition(m.serverError != nil && m.users.queries.isEmpty && m.users.lookups.isEmpty && m.openedLink == nil)
        }
        print("PASS: B-038 explicit links, complete-value debounce, submit deduplication, blocked/no-Retry, stale replies and owner exclusion")
    }
}
''')
