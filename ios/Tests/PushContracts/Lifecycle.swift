import Foundation

@MainActor private final class Transport {
    struct Pending { let put: UnyriPushPut; let session: UnyriPushSession; let reply: CheckedContinuation<UnyriPushReceipt, Error> }
    var pending: [Pending] = []
    var puts: [UnyriPushPut] = []
    var deletes: [UnyriPushBinding] = []
    var failCleanup = false
    func register(_ put: UnyriPushPut, _ session: UnyriPushSession) async throws -> UnyriPushReceipt {
        puts.append(put)
        return try await withCheckedThrowingContinuation { pending.append(Pending(put: put, session: session, reply: $0)) }
    }
    func finish(_ index: Int = 0, error: Error? = nil) {
        let value = pending.remove(at: index)
        if let error { value.reply.resume(throwing: error) }
        else { value.reply.resume(returning: UnyriPushReceipt(deviceId: value.session.scope.device, provider: value.put.provider,
            registrationId: value.put.registrationId, revision: value.put.revision)) }
    }
    func remove(_ binding: UnyriPushBinding) throws {
        deletes.append(binding)
        if failCleanup { throw CocoaError(.fileWriteNoPermission) }
    }
}

@main private struct PushLifecycleContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ name: String) { checks += 1; precondition(value, name); print("PASS " + name) }
        func wait(_ condition: @escaping @MainActor () -> Bool) async {
            for _ in 0..<10000 { if condition() { return }; await Task.yield() }
            preconditionFailure("Controlled continuation did not reach expected boundary")
        }
        let scope = UnyriPushScope(origin: "https://server.example", user: UUID(), device: UUID())
        let session = UnyriPushSession(scope: scope, generation: 1)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("UPL-" + UUID().uuidString.prefix(8))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var current: UnyriPushSession?
        let ledger = try UnyriPushIntentLedger(file: root.appendingPathComponent("intent.json"))
        let transport = Transport()
        let runtime = UnyriPushRegistration(ledger: ledger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { current }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, session in try await transport.register(put, session) },
            remove: { binding, _, _ in try transport.remove(binding) })
        runtime.receivedToken(Data([1, 2]), provider: .apns)
        await Task.yield(); check(transport.puts.isEmpty, "OS token before login has no server admission")
        current = session; runtime.updateSession()
        await wait { transport.pending.count == 1 }
        let first = transport.pending[0].put
        let context = UnyriPushContext(scope: scope, provider: .apns)
        check(ledger.binding(for: context)?.id == first.registrationId, "actual worker persists binding before HTTP")
        runtime.updatePreferences(UnyriPushPreferences(messageAlertEnabled: false, messageSoundEnabled: false, permissionAllowed: true))
        await Task.yield(); check(transport.puts.count == 1, "new preference waits for previous PUT completion")
        transport.finish(); await wait { transport.pending.count == 1 && transport.puts.count == 2 }
        check(transport.deletes.count == 1 && transport.deletes[0].id == first.registrationId, "late successful replaced intent conditionally cleaned first")
        check(transport.pending[0].put.preferences?.messageAlertEnabled == false, "message preference delivered on regular slot")
        transport.finish(); await wait { !runtime.isWorking }
        check(runtime.isRegistered(.apns), "latest preference alone marked uploaded")

        runtime.receivedToken(Data([3]), provider: .voip)
        await wait { transport.pending.count == 1 }; transport.finish(); await wait { !runtime.isWorking }
        check(runtime.isRegistered(.voip), "independent legitimate VoIP slot registers")
        runtime.receivedToken(Data([4]), provider: .apns)
        await wait { transport.pending.count == 1 }
        let cancelled = transport.pending[0].put
        runtime.invalidated(.apns); transport.failCleanup = true
        transport.finish(error: NSError(domain: "NSURLErrorDomain", code: -1005))
        await wait { !runtime.isWorking }
        check(transport.deletes.last?.id == cancelled.registrationId && ledger.binding(for: context)?.cleanupPending == true,
              "ambiguous failed PUT still retires exact intent and persists failed cleanup")
        check(runtime.isRegistered(.voip), "regular disable preserves calls")
        transport.failCleanup = false; let putCount = transport.puts.count; runtime.retry()
        await wait { !runtime.isWorking }
        check(transport.puts.count == putCount && ledger.binding(for: context)?.cleanupPending == false,
              "foreground retry finishes durable cleanup without resurrecting PUT")

        runtime.receivedToken(Data([5]), provider: .apns)
        await wait { transport.pending.count == 1 }
        let staleIntent = transport.pending[0].put
        transport.finish(error: UnyriPushAPIError.stale(100))
        await wait { transport.pending.count == 1 }
        check(transport.pending[0].put.revision == 101 && transport.pending[0].put.registrationId != staleIntent.registrationId,
              "one current-intent conflict rebase persists higher revision")
        transport.finish(error: UnyriPushAPIError.stale(200)); await wait { !runtime.isWorking }
        let blockedCount = transport.puts.count; runtime.retry(); await Task.yield()
        check(transport.puts.count == blockedCount, "second conflict cannot create a retry storm")
        runtime.receivedToken(Data([5]), provider: .apns); await wait { transport.pending.count == 1 }
        transport.finish(error: UnyriPushAPIError.retired); await wait { !runtime.isWorking }
        let retiredCount = transport.puts.count; runtime.retry(); await Task.yield()
        check(transport.puts.count == retiredCount && ledger.binding(for: context)?.active == false,
              "retired server intent stays retired until fresh OS intent")
        runtime.receivedToken(Data([5]), provider: .apns); await wait { transport.pending.count == 1 }
        transport.finish(); await wait { !runtime.isWorking }
        check(runtime.isRegistered(.apns), "fresh OS intent permits new revision after retirement")

        runtime.receivedToken(Data([6]), provider: .apns); await wait { transport.pending.count == 1 }
        let old = transport.pending[0].put
        let successor = UnyriPushSession(scope: UnyriPushScope(origin: "https://another.example", user: UUID(), device: UUID()), generation: 2)
        current = successor; runtime.updateSession()
        let deletesBefore = transport.deletes.count
        transport.finish()
        await wait { transport.pending.count == 2 }
        check(transport.deletes.count == deletesBefore && !runtime.isRegistered(.apns), "old account completion cannot mark successor or use its credential for cleanup")
        check(ledger.binding(for: context)?.active == false, "old origin binding terminalized before successor admission")
        while !transport.pending.isEmpty { transport.finish() }
        await wait { !runtime.isWorking }
        check(runtime.isRegistered(.apns) && runtime.isRegistered(.voip), "successor roles register under their own exact scope")
        check(transport.puts.last?.registrationId != old.registrationId, "old registration identity never reused across origin")

        let coldLedger = try UnyriPushIntentLedger(file: root.appendingPathComponent("cold.json"))
        let coldBinding = try coldLedger.advance(context)
        let coldTransport = Transport()
        let cold = UnyriPushRegistration(ledger: coldLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { session }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, s in try await coldTransport.register(put, s) }, remove: { b, _, _ in try coldTransport.remove(b) })
        cold.receivedToken(Data([7]), provider: .apns); cold.updateSession()
        await wait { coldTransport.pending.count == 1 }
        check(coldTransport.pending[0].put.registrationId == coldBinding.id && coldTransport.pending[0].put.revision == coldBinding.revision,
              "cold restore tries persisted active binding with fresh OS token before rotating")
        coldTransport.finish(); await wait { !cold.isWorking }
        check(cold.isRegistered(.apns), "cold idempotent registration completes without invalidating queued payload binding")

        let retiredLedger = try UnyriPushIntentLedger(file: root.appendingPathComponent("cold-retired.json"))
        let retiredBinding = try retiredLedger.advance(context)
        let retiredTransport = Transport()
        let restored = UnyriPushRegistration(ledger: retiredLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { session }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, s in try await retiredTransport.register(put, s) }, remove: { b, _, _ in try retiredTransport.remove(b) })
        restored.receivedToken(Data([8]), provider: .apns); restored.updateSession()
        await wait { retiredTransport.pending.count == 1 }; retiredTransport.finish(error: UnyriPushAPIError.retired)
        await wait { !restored.isWorking }
        restored.retry(); await Task.yield()
        check(retiredTransport.puts.count == 1 && retiredLedger.binding(for: context)?.active == false,
              "cold ACTIVE versus server-retired stays blocked; no held automatic fallback")
        restored.receivedToken(Data([8]), provider: .apns)
        await wait { retiredTransport.pending.count == 1 }
        check(retiredTransport.pending[0].put.registrationId != retiredBinding.id && retiredTransport.pending[0].put.revision > retiredBinding.revision,
              "new actual OS callback after retirement creates a separate durable intent")
        restored.invalidated(.apns); retiredTransport.finish(error: UnyriPushAPIError.retired)
        await wait { !restored.isWorking }
        check(!restored.isRegistered(.apns) && retiredTransport.pending.isEmpty,
              "role disable defeats late retired response and cannot revive registration")
        print("PUSH_LIFECYCLE \(checks) PASS")
    }
}
