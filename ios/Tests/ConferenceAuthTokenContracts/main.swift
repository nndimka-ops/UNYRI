import Foundation

@main struct AuthTokenContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); checks += 1 }
        let revoked = APIError.server(code: "session_revoked", message: "Synthetic revoked session")
        let auth = AuthRepository()
        let generation = auth.sessionGeneration
        auth.store.token = "S1"
        let transport = ScriptedTransport()
        var legacyCalls = 0, callbacks = 0
        let client = APIClient(session: transport, onAuthenticatedError: { _ in legacyCalls += 1 }, onAuthenticatedRequestError: { error, bearer in
            callbacks += 1
            await auth.handleAuthenticatedRequestError(error, generation: generation, requestToken: bearer)
        })
        do { _ = try await client.send("/conferences/key-request", method: "POST", query: [], body: nil, bearerToken: "S0"); preconditionFailure("typed operation error lost") }
        catch APIError.server(let code, _) { check(code == "session_revoked", "stale auth remains the original typed operation error") }
        check(auth.store.token == "S1" && auth.erasures == 0 && auth.sessionGeneration == generation, "S0 rejection cannot erase refreshed S1 in the same generation")
        check(callbacks == 1 && legacyCalls == 0 && transport.sentBearer == "Bearer S0", "forward actual request bearer once, without calling generation-only fallback")
        auth.store.token = nil
        await auth.handleAuthenticatedRequestError(revoked, generation: generation, requestToken: "S0")
        check(auth.erasures == 0 && auth.sessionGeneration == generation, "missing current token cannot erase a new or absent session")
        auth.currentUser = nil; auth.store.token = "S0"
        let absentReads = auth.store.reads
        await auth.handleAuthenticatedRequestError(revoked, generation: generation, requestToken: "S0")
        check(auth.erasures == 0 && auth.store.reads == absentReads, "no authenticated account is not a revocation target")
        auth.currentUser = "synthetic-account"; auth.store.token = "S1"
        await auth.handleAuthenticatedRequestError(revoked, generation: generation - 1, requestToken: "S1")
        check(auth.erasures == 0, "retired account generation cannot revoke its successor")
        let reads = auth.store.reads
        await auth.handleAuthenticatedRequestError(.http(401), generation: generation, requestToken: "S1")
        await auth.handleAuthenticatedRequestError(.network("synthetic"), generation: generation, requestToken: "S1")
        check(auth.erasures == 0 && auth.store.reads == reads, "ambiguous HTTP/network errors do not query storage or erase credentials")
        transport.status = 200; transport.body = Data("{}".utf8)
        _ = try await client.send("/conferences/directory", method: "GET", query: [], body: nil, bearerToken: "S1")
        check(callbacks == 1 && auth.store.reads == reads, "ordinary successful requests add no token-store reads")
        transport.status = 401; transport.body = Data(#"{"error":{"code":"session_revoked","message":"Synthetic"}}"#.utf8)
        do { _ = try await client.send("/conferences", method: "GET", query: [], body: nil, bearerToken: nil) } catch {}
        check(callbacks == 1, "unauthenticated requests never invoke the authenticated token callback")
        let legacy = APIClient(session: transport, onAuthenticatedError: { _ in legacyCalls += 1 }, onAuthenticatedRequestError: nil)
        do { _ = try await legacy.send("/legacy", method: "GET", query: [], body: nil, bearerToken: "S1") } catch {}
        check(legacyCalls == 1, "unmodified callers keep their legacy callback")
        do {
            let rotating = AuthRepository(), gate = Gate()
            rotating.refreshTask = Task { @MainActor in await gate.wait(); rotating.store.token = "S1"; return "S1" }
            for _ in 0..<100 where !gate.entered { await Task.yield() }
            let handled = Task { await rotating.handleAuthenticatedRequestError(revoked, generation: rotating.sessionGeneration, requestToken: "S0") }
            for _ in 0..<10 { await Task.yield() }
            check(rotating.erasures == 0 && rotating.store.reads == 0, "late S0 response waits for an already-running refresh")
            gate.open(); await handled.value
            check(rotating.store.token == "S1" && rotating.erasures == 0, "refresh commit wins before terminal token comparison")
        }
        await auth.handleAuthenticatedRequestError(revoked, generation: generation, requestToken: "S1")
        check(auth.erasures == 1 && auth.store.token == nil && auth.sessionGeneration == generation + 1, "genuine matching-bearer revocation still clears credentials")
        print("Conference request-token actual HTTP/auth methods: \(checks) passed; no network/Keychain/Apple SDK proof.")
    }
}
