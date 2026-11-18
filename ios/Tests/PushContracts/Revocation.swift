import Foundation

@main private struct PushRevocationContracts {
    @MainActor static func main() async throws {
        var checks = 0, failures = 0
        func check(_ value: Bool, _ name: String) { checks += 1; if !value { failures += 1 }; FileHandle.standardOutput.write(Data(((value ? "PASS " : "RED ") + name + "\n").utf8)) }
        func settle(_ work: @escaping @MainActor () -> Bool) async {
            for _ in 0..<10000 { if !work() { return }; await Task.yield() }
            preconditionFailure("Worker did not settle")
        }
        func hint(_ binding: UnyriPushBinding) -> UnyriPushPayload {
            UnyriPushPayload(event: "incoming_call", user: binding.context.scope.user, device: binding.context.scope.device,
                pushID: UUID(), provider: binding.context.provider, registrationID: binding.id,
                registrationRevision: binding.revision, sentAt: 1, expiresAt: 2, callID: UUID(), mediaKind: "audio", chatID: nil, messageID: nil)
        }
        let scope = UnyriPushScope(origin: "https://server.example", user: UUID(), device: UUID())
        let session = UnyriPushSession(scope: scope, generation: 4)
        let context = UnyriPushContext(scope: scope, provider: .voip)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("UPR-" + UUID().uuidString.prefix(8))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let coldLedger = try UnyriPushIntentLedger(file: root.appendingPathComponent("cold.json"))
        let coldBinding = try coldLedger.advance(context)
        var coldDeletes: [UnyriPushBinding] = []
        let cold = UnyriPushRegistration(ledger: coldLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { session }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, value in UnyriPushReceipt(deviceId: value.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision) },
            remove: { value, _, _ in coldDeletes.append(value) })
        cold.updateSession() // No OS credential callback, and therefore no desired intent.
        cold.invalidated(.voip); await settle { cold.isWorking }
        check(coldLedger.binding(for: context)?.active == false && coldDeletes.map(\.id) == [coldBinding.id],
            "cold OS invalidation retires persisted binding even before desired intent exists")
        check(cold.binding(for: hint(coldBinding), origin: scope.origin) == nil,
            "cold invalidated payload is synchronously inadmissible")

        var failWrite = false
        let ledger = try UnyriPushIntentLedger(file: root.appendingPathComponent("failed.json")) { data, target in
            if failWrite { throw CocoaError(.fileWriteNoPermission) }; try data.write(to: target, options: .atomic)
        }
        let runtime = UnyriPushRegistration(ledger: ledger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { session }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, value in UnyriPushReceipt(deviceId: value.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision) },
            remove: { _, _, _ in })
        runtime.updateSession(); runtime.receivedToken(Data([1]), provider: .voip); await settle { runtime.isWorking }
        let binding = ledger.binding(for: context)!
        failWrite = true; runtime.retire(scope: scope)
        check(runtime.binding(for: hint(binding), origin: scope.origin) == nil,
            "logout revokes receive admission before failed durable retirement")
        await settle { runtime.isWorking }
        check(ledger.binding(for: context)?.id == binding.id && ledger.binding(for: context)?.active == true,
            "failed durable retirement retains old bytes and metadata rather than resetting revision")
        runtime.receivedToken(Data([2]), provider: .voip); await settle { runtime.isWorking }
        check(runtime.binding(for: hint(binding), origin: scope.origin) == nil && !runtime.isRegistered(.voip),
            "failed new-intent write cannot revive old receive binding")
        let unknownFile = root.appendingPathComponent("restoring.json")
        let unknownLedger = try UnyriPushIntentLedger(file: unknownFile)
        let unknownBinding = try unknownLedger.advance(context)
        let regularContext = UnyriPushContext(scope: scope, provider: .apns)
        let regularBinding = try unknownLedger.advance(regularContext)
        let foreignContext = UnyriPushContext(scope: UnyriPushScope(origin: "https://other.example", user: scope.user, device: scope.device), provider: .voip)
        let foreignBinding = try unknownLedger.advance(foreignContext)
        var restoredSession: UnyriPushSession?
        var restoredDeletes: [UnyriPushBinding] = []
        var restoredPuts = 0
        let unknown = UnyriPushRegistration(ledger: unknownLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { restoredSession }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, value in restoredPuts += 1; return UnyriPushReceipt(deviceId: value.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision) },
            remove: { value, _, _ in restoredDeletes.append(value) })
        unknown.invalidated(.voip) // Exact cold pre-auth state: current=nil, desired=nil.
        let reloaded = try UnyriPushIntentLedger(file: unknownFile)
        check(reloaded.binding(for: context)?.active == false && reloaded.binding(for: context)?.cleanupPending == true &&
              reloaded.binding(for: foreignContext)?.active == false && reloaded.binding(for: foreignContext)?.id == foreignBinding.id,
            "pre-auth OS invalidation durably retires provider across persisted origin contexts")
        check(reloaded.binding(for: regularContext) == regularBinding && restoredDeletes.isEmpty && restoredPuts == 0,
            "provider-wide retirement preserves other role and performs no unauthenticated HTTP")
        let relaunched = UnyriPushRegistration(ledger: reloaded, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { restoredSession }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, value in restoredPuts += 1; return UnyriPushReceipt(deviceId: value.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision) },
            remove: { value, _, _ in restoredDeletes.append(value) })
        check(relaunched.binding(for: hint(unknownBinding), origin: scope.origin) == nil,
            "new process reload cannot readmit payload after pre-auth invalidation")
        restoredSession = session; relaunched.updateSession(); await settle { relaunched.isWorking }
        check(restoredDeletes.map(\.id) == [unknownBinding.id] && reloaded.binding(for: context)?.cleanupPending == false &&
              reloaded.binding(for: foreignContext)?.cleanupPending == true,
            "auth restoration cleans only exact current-origin retired intent")
        relaunched.receivedToken(Data([3]), provider: .voip); await settle { relaunched.isWorking }
        check(restoredPuts == 1 && reloaded.binding(for: context)!.revision > unknownBinding.revision &&
              reloaded.binding(for: context)!.id != unknownBinding.id && relaunched.binding(for: hint(unknownBinding), origin: scope.origin) == nil,
            "fresh OS credential after cold invalidation creates new durable binding, never restores old one")
        var failRetiredWrite = false
        let serverLedger = try UnyriPushIntentLedger(file: root.appendingPathComponent("server-retired.json")) { data, target in
            if failRetiredWrite { throw CocoaError(.fileWriteNoPermission) }; try data.write(to: target, options: .atomic)
        }
        var held: CheckedContinuation<UnyriPushReceipt, Error>?
        var sentCount = 0
        let serverRetired = UnyriPushRegistration(ledger: serverLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { session }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { _, _, _ in sentCount += 1; return try await withCheckedThrowingContinuation { held = $0 } },
            remove: { _, _, _ in })
        serverRetired.updateSession(); serverRetired.receivedToken(Data([4]), provider: .voip)
        for _ in 0..<10000 { if held != nil { break }; await Task.yield() }
        guard let reply = held else { preconditionFailure("Held server response not reached") }
        let rejectedBinding = serverLedger.binding(for: context)!
        failRetiredWrite = true; held = nil; reply.resume(throwing: UnyriPushAPIError.retired)
        await settle { serverRetired.isWorking }
        check(serverRetired.binding(for: hint(rejectedBinding), origin: scope.origin) == nil &&
              serverLedger.binding(for: context)?.active == true,
            "known server retirement revokes receive before failed metadata write, preserving old ledger bytes")
        serverRetired.retry(); serverRetired.receivedToken(Data([4]), provider: .voip); await settle { serverRetired.isWorking }
        check(sentCount == 1 && held == nil && serverRetired.binding(for: hint(rejectedBinding), origin: scope.origin) == nil,
            "failed fresh intent cannot resurrect server-retired binding or create automatic HTTP retry")
        FileHandle.standardOutput.write(Data("PUSH_REVOCATION \(checks) CHECKS \(failures) RED\n".utf8))
        precondition(failures == 0, "Revocation contracts failed")
    }
}
