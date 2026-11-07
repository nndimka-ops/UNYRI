import Foundation

@main struct ExactKeysContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); checks += 1 }
        let user = "22222222-2222-4222-8222-222222222222", first = "33333333-3333-4333-8333-333333333333", second = "44444444-4444-4444-8444-444444444444"
        func data(_ ids: [String]) throws -> Data {
            try JSONSerialization.data(withJSONObject: ["devices": ids.map { ["device_id": $0, "signal_device_id": 1, "registration_id": 1,
                "identity_key": "synthetic", "signed_prekey_id": 1, "signed_prekey_public": "synthetic", "signed_prekey_signature": "synthetic",
                "kyber_prekey_id": 1, "kyber_prekey_public": "synthetic", "kyber_prekey_signature": "synthetic"] as [String: Any] }])
        }
        let auth = AuthRepository()
        let actual = KeysRepository(api: KeysAPI(client: APIClient()), auth: auth)
        ExactKeysTransport.respond = { _ in try data([first, second]) }
        let selected = try await actual.fetchExactDeviceBundles(userId: user, deviceId: second)
        check(selected.devices.count == 1 && selected.devices[0].deviceId == second, "strict discovery selects exactly the addressed device")
        check(ExactKeysTransport.paths == ["/keys/devices/" + user], "discovery is non-consuming")
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { _ in throw APIError.http(404) }
        do { _ = try await actual.fetchExactDeviceBundles(userId: user, deviceId: first); preconditionFailure("strict discovery fell back") } catch { checks += 1 }
        check(ExactKeysTransport.paths == ["/keys/devices/" + user], "strict 404 never consumes all-device prekeys")
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { path in if path.hasPrefix("/keys/devices/") { throw APIError.http(404) }; return try data([first, second]) }
        _ = try await actual.fetchDeviceBundles(userId: user)
        check(ExactKeysTransport.paths == ["/keys/devices/" + user, "/keys/bundles/" + user], "ordinary chat retains its existing legacy fallback")
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { _ in try data([second]) }
        let target = try await actual.fetchBundle(userId: user, deviceId: second)
        check(target.devices.map(\.deviceId) == [second] && ExactKeysTransport.paths == ["/keys/bundles/\(user)/\(second)"], "session establishment consumes only the exact target")
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { _ in try data([first, second]) }
        do { _ = try await actual.fetchBundle(userId: user, deviceId: second); preconditionFailure("multi-device target response accepted") } catch { checks += 1 }
        ExactKeysTransport.respond = { _ in try data([second, second]) }
        do { _ = try await actual.fetchExactDeviceBundles(userId: user, deviceId: second); preconditionFailure("duplicate target discovery accepted") } catch { checks += 1 }
        ExactKeysTransport.respond = { _ in try data([first]) }
        do { _ = try await actual.fetchExactDeviceBundles(userId: user, deviceId: second); preconditionFailure("missing target accepted") } catch { checks += 1 }
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { _ in throw APIError.http(429) }
        for _ in 0..<2 { do { _ = try await actual.fetchBundle(userId: user, deviceId: second); preconditionFailure("rate limit bypassed") } catch { checks += 1 } }
        check(ExactKeysTransport.paths.count == 1, "target rate limit cooldown prevents a repeated consuming request")
        ExactKeysTransport.paths = []; ExactKeysTransport.respond = { _ in auth.sessionGeneration += 1; return try data([second]) }
        do { _ = try await actual.fetchExactDeviceBundles(userId: user, deviceId: second); preconditionFailure("late cross-account discovery accepted") } catch { checks += 1 }
        check(ExactKeysTransport.paths.count == 1, "account generation checked after response")
        print("Conference exact-device actual repository/API contracts: \(checks) passed; transport seam, NOT libsignal/Apple SDK proof.")
    }
}
