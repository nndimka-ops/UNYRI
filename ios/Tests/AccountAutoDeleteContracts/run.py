"""Execute production account-auto-delete repository with deterministic transport.

No real accounts, URLs, credentials, app lifecycle or deletion workers are used.
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / "ios/UNYRI/Support/AccountAutoDelete.swift").read_text(encoding="utf-8")
production = source[:source.index("/// Only this foreground view task")]
production = production.replace("import SwiftUI\n", "").replace("import UIKit\n", "")
support = r'''
@propertyWrapper struct Published<Value> { var wrappedValue: Value }
protocol ObservableObject: AnyObject {}
enum APIError: Error { case http(Int); case server(code: String, message: String) }
enum UnyriL10n { static func string(_ key: String) -> String { key } }
struct FixtureUser { let id: String }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var currentUser: FixtureUser? = FixtureUser(id: "owner")
    var authenticatedServerURL = URL(string: "https://synthetic.invalid")!
    var beforeToken: (() -> Void)?
    var authFailures = 0
    func currentAccessToken() async throws -> String { beforeToken?(); return "synthetic-token" }
    func handleAuthenticatedRequestError(_ error: APIError, generation: UInt64, requestToken: String) async {
        if generation == sessionGeneration && requestToken == "synthetic-token" { authFailures += 1 }
    }
}
@MainActor final class UnyriAppLock {
    static let shared = UnyriAppLock()
    var enabled = false
    var accepted = true
    var duringAuthentication: (() -> Void)?
    func authenticate() async -> Bool { duringAuthentication?(); return accepted }
}
@MainActor final class Transport {
    static let shared = Transport()
    var value = AccountAutoDeleteSettings(inactiveDays: nil, lastActiveAt: Date(timeIntervalSince1970: 1), nextDeletionAt: nil, revision: 0)
    var reads = 0
    var writes: [UpdateAccountAutoDelete] = []
    var getFailure: APIError?
    var putFailure: APIError?
    var duringGet: (() -> Void)?
    var duringPut: (() -> Void)?
    func reset() {
        value = AccountAutoDeleteSettings(inactiveDays: nil, lastActiveAt: Date(timeIntervalSince1970: 1), nextDeletionAt: nil, revision: 0)
        reads = 0; writes = []; getFailure = nil; putFailure = nil; duringGet = nil; duringPut = nil
        UnyriAppLock.shared.enabled = false; UnyriAppLock.shared.accepted = true; UnyriAppLock.shared.duringAuthentication = nil
    }
}
@MainActor struct APIClient {
    let onError: ((APIError, String) async -> Void)?
    init(baseURL: URL, preventRedirects: Bool, ephemeral: Bool, onAuthenticatedRequestError: ((APIError, String) async -> Void)? = nil) {
        precondition(baseURL.scheme == "https" && preventRedirects && ephemeral)
        onError = onAuthenticatedRequestError
    }
    func get<Response: Decodable>(_ path: String, accessToken: String, as: Response.Type) async throws -> Response {
        precondition(path == "/users/me/auto-delete")
        let server = Transport.shared; server.reads += 1
        await Task.yield(); server.duringGet?()
        if let failure = server.getFailure { await onError?(failure, accessToken); throw failure }
        return server.value as! Response
    }
    func put<Body: Encodable, Response: Decodable>(_ path: String, accessToken: String, body: Body, as: Response.Type) async throws -> Response {
        precondition(path == "/users/me/auto-delete")
        let request = body as! UpdateAccountAutoDelete
        let server = Transport.shared; server.writes.append(request)
        await Task.yield(); server.duringPut?()
        if let failure = server.putFailure { await onError?(failure, accessToken); throw failure }
        guard request.expectedRevision == server.value.revision else { throw APIError.http(409) }
        server.value = AccountAutoDeleteSettings(inactiveDays: request.inactiveDays, lastActiveAt: Date(timeIntervalSince1970: 2),
            nextDeletionAt: request.inactiveDays.map { Date(timeIntervalSince1970: 2 + Double($0) * 86400) }, revision: server.value.revision + 1)
        return server.value as! Response
    }
}
@main struct Run {
    @MainActor static var count = 0
    @MainActor static func check(_ condition: Bool, _ label: String) {
        count += 1; precondition(condition, "FAIL: " + label); print("PASS: " + label)
    }
    @MainActor static func main() async throws {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let disabled = try JSONSerialization.jsonObject(with: encoder.encode(UpdateAccountAutoDelete(inactiveDays: nil, expectedRevision: 4, password: nil))) as! [String: Any]
        check(disabled["inactive_days"] is NSNull && disabled["expected_revision"] as? Int == 4 && disabled["password"] == nil,
              "disable sends explicit null and expected revision, with no password")
        check(AccountAutoDeleteSettings.presets == [1, 3, 7, 365, 1095], "presets preserve 1 day through 3 years")
        let server = Transport.shared; server.reset()
        let auth = AuthRepository(); let repo = AccountAutoDeleteRepository(auth: auth)
        check(!repo.canEdit && repo.value == nil, "initial state cannot edit or write defaults")
        await repo.refresh()
        check(server.writes.isEmpty && repo.canEdit && repo.value?.inactiveDays == nil, "fetch preserves server default off without a write")
        for invalid in [0, -1, 1096] {
            let result = await repo.save(inactiveDays: invalid, expectedRevision: 0, password: "synthetic-password")
            check(!result && server.writes.isEmpty, "invalid day \(invalid) cannot reach transport")
        }
        let missingPassword = await repo.save(inactiveDays: 37, expectedRevision: 0, password: nil)
        check(!missingPassword && server.writes.isEmpty, "enable requires a fresh account password")
        let enabled = await repo.save(inactiveDays: 37, expectedRevision: 0, password: "synthetic-password")
        check(enabled && repo.value?.inactiveDays == 37 && server.writes.last?.inactiveDays == 37, "arbitrary integer 37 saves only after acknowledgement")
        let missingChangePassword = await repo.save(inactiveDays: 1095, expectedRevision: 1, password: "")
        check(!missingChangePassword && server.writes.count == 1, "changing an enabled interval also requires password")
        let staleConfirmation = await repo.save(inactiveDays: 1095, expectedRevision: 0, password: "synthetic-password")
        check(!staleConfirmation && server.writes.count == 1, "an old confirmation cannot use a silently newer revision")
        let upper = await repo.save(inactiveDays: 1095, expectedRevision: 1, password: "synthetic-password")
        check(upper && repo.value?.inactiveDays == 1095, "maximum 1095 day interval saves")
        let off = await repo.save(inactiveDays: nil, expectedRevision: 2, password: nil)
        check(off && repo.value?.inactiveDays == nil && server.writes.last?.password == nil, "active session can disable without password")
        let lower = await repo.save(inactiveDays: 1, expectedRevision: 3, password: "synthetic-password")
        check(lower && repo.value?.inactiveDays == 1, "minimum one day interval saves")
        server.putFailure = .http(409)
        server.value = AccountAutoDeleteSettings(inactiveDays: 7, lastActiveAt: Date(), nextDeletionAt: Date(), revision: 10)
        let conflict = await repo.save(inactiveDays: 3, expectedRevision: 4, password: "synthetic-password")
        check(!conflict && repo.value?.inactiveDays == 7 && repo.value?.revision == 10 && repo.canEdit, "409 refetches concurrent device setting without overwriting it")
        server.getFailure = .http(503)
        let unresolved = await repo.save(inactiveDays: nil, expectedRevision: 10, password: nil)
        let attempts = server.writes.count
        let retry = await repo.save(inactiveDays: nil, expectedRevision: 10, password: nil)
        check(!unresolved && !retry && !repo.canEdit && server.writes.count == attempts, "409 plus failed refetch blocks all retries using stale revision")
        server.reset()
        let offlineAuth = AuthRepository(); let offline = AccountAutoDeleteRepository(auth: offlineAuth)
        server.getFailure = .http(503); await offline.refresh()
        let offlineSave = await offline.save(inactiveDays: 1, expectedRevision: 0, password: "synthetic-password")
        check(!offlineSave && !offline.canEdit && server.writes.isEmpty, "failed initial load has no offline/default write")
        for boundary in ["generation", "owner", "server"] {
            server.reset(); let account = AuthRepository(); let subject = AccountAutoDeleteRepository(auth: account)
            await subject.refresh()
            account.beforeToken = {
                if boundary == "generation" { account.sessionGeneration += 1 }
                if boundary == "owner" { account.currentUser = FixtureUser(id: "successor") }
                if boundary == "server" { account.authenticatedServerURL = URL(string: "https://other.invalid")! }
            }
            let result = await subject.save(inactiveDays: 3, expectedRevision: 0, password: "synthetic-password")
            check(!result && server.writes.isEmpty, "\(boundary) switch before token admission sends no write")
        }
        server.reset(); let lateAuth = AuthRepository(); let late = AccountAutoDeleteRepository(auth: lateAuth)
        server.duringGet = { lateAuth.currentUser = FixtureUser(id: "new-owner") }
        server.getFailure = .server(code: "session_revoked", message: "Session ended")
        await late.refresh(); for _ in 0..<5 { await Task.yield() }
        check(late.value == nil && lateAuth.authFailures == 0, "old owner response cannot publish state or invalidate successor session")
        server.reset(); let rejectedAuth = AuthRepository(); let rejected = AccountAutoDeleteRepository(auth: rejectedAuth)
        server.getFailure = .server(code: "session_revoked", message: "Session ended")
        await rejected.refresh(); for _ in 0..<5 { await Task.yield() }
        check(rejectedAuth.authFailures == 1, "current rejected session reaches bearer-bound existing invalidation handler")
        server.reset(); let lockAuth = AuthRepository(); let locked = AccountAutoDeleteRepository(auth: lockAuth); await locked.refresh()
        UnyriAppLock.shared.enabled = true; UnyriAppLock.shared.accepted = false
        let refused = await locked.save(inactiveDays: 3, expectedRevision: 0, password: "synthetic-password")
        check(!refused && server.writes.isEmpty, "canceled app lock verification sends no security mutation")
        print("Account auto-delete production repository contracts: \(count) passed")
    }
}
'''

# The UI-specific lifecycle adapter cannot execute on Windows. Check that the
# production task is foreground keyed and uses an empty-body authenticated POST.
activity = source[source.index("struct UnyriAccountActivityReporter:"):]
assert 'phase == .active' in activity and 'UIApplication.shared.applicationState == .active' in activity
assert '60_000_000_000' in activity
assert 'postNoContent("/users/me/activity", bearerToken: token)' in activity
assert 'owner == account.currentUser?.id' in activity and 'server == account.authenticatedServerURL' in activity
assert 'requestToken: bearer' in activity
assert 'UserDefaults' not in source and 'print(' not in source

with tempfile.TemporaryDirectory(prefix="coco-account-auto-delete-") as temp:
    directory = Path(temp)
    swift = directory / "main.swift"
    swift.write_text(production + support, encoding="utf-8")
    executable = directory / ("contracts.exe" if os.name == "nt" else "contracts")
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(executable)], check=True, timeout=100)
    subprocess.run([str(executable)], check=True, timeout=25)
print("Foreground activity source contracts: passed (no device or network proof)")
