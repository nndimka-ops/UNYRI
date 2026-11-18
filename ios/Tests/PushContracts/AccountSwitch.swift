import Foundation

// swiftc -parse-as-library ios/UNYRI/Support/PushRegistration.swift ios/Tests/PushContracts/AccountSwitch.swift
// Two accounts on one device: a switch keeps the leaving account's alert slot
// routable, withdraws only its VoIP slot, and the returning account re-registers.
@MainActor private final class Transport {
    var puts: [UnyriPushPut] = []
    var deletes: [UnyriPushBinding] = []
    func register(_ put: UnyriPushPut, _ session: UnyriPushSession) async throws -> UnyriPushReceipt {
        puts.append(put)
        return UnyriPushReceipt(deviceId: session.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision)
    }
    func remove(_ binding: UnyriPushBinding) { deletes.append(binding) }
}

/// Holds one request until the check releases it (a slow server during a switch).
@MainActor private final class Gate {
    private var waiting: CheckedContinuation<Void, Error>?
    private(set) var entered = false
    func pass() async throws { entered = true; try await withCheckedThrowingContinuation { waiting = $0 } }
    func open(_ error: Error? = nil) {
        let continuation = waiting; waiting = nil; entered = false
        if let error { continuation?.resume(throwing: error) } else { continuation?.resume() }
    }
}

@main private struct PushAccountSwitchContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ name: String) { checks += 1; precondition(value, name); print("PASS " + name) }
        func wait(_ condition: @escaping @MainActor () -> Bool) async {
            for _ in 0..<10000 { if condition() { return }; await Task.yield() }
            preconditionFailure("Controlled continuation did not reach expected boundary")
        }
        let first = UnyriPushSession(scope: UnyriPushScope(origin: "https://one.example", user: UUID(), device: UUID()), generation: 1)
        let second = UnyriPushSession(scope: UnyriPushScope(origin: "https://two.example", user: UUID(), device: UUID()), generation: 2)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("UPA-" + UUID().uuidString.prefix(8))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ledger = try UnyriPushIntentLedger(file: root.appendingPathComponent("intent.json"))
        let transport = Transport()
        var current: UnyriPushSession? = first
        let runtime = UnyriPushRegistration(ledger: ledger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { current }, access: { "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL" },
            register: { put, _, session in try await transport.register(put, session) },
            remove: { binding, _, _ in transport.remove(binding) })
        // The coordinator reads notification settings after every session change;
        // the alert slot waits for them (VoIP does not).
        let settings = UnyriPushPreferences(messageAlertEnabled: true, messageSoundEnabled: true, permissionAllowed: true)
        runtime.receivedToken(Data([1]), provider: .apns)
        runtime.receivedToken(Data([2]), provider: .voip)
        runtime.updateSession(); runtime.updatePreferences(settings)
        await wait { runtime.isRegistered(.apns) && runtime.isRegistered(.voip) && !runtime.isWorking }
        let firstAlert = UnyriPushContext(scope: first.scope, provider: .apns)
        let firstCall = UnyriPushContext(scope: first.scope, provider: .voip)

        // Prepare the switch while the first account is still current.
        await runtime.retireVoIP(for: first.scope)
        check(transport.deletes.count == 1 && transport.deletes[0].context == firstCall, "only the leaving account's VoIP slot is deleted")
        check(ledger.binding(for: firstCall)?.active == false && ledger.binding(for: firstCall)?.cleanupPending == false, "deleted VoIP slot is retired and clean")
        check(ledger.binding(for: firstAlert)?.active == true, "alert slot stays active for routing")

        // The session change itself: suspend, never retire.
        runtime.suspend(scope: first.scope)
        current = nil; runtime.updateSession()
        check(transport.deletes.count == 1 && ledger.binding(for: firstAlert)?.active == true, "suspend sends no DELETE and keeps the alert slot")

        current = second; runtime.updateSession(); runtime.updatePreferences(settings)
        await wait { runtime.isRegistered(.apns) && runtime.isRegistered(.voip) && !runtime.isWorking }
        check(transport.puts.filter { $0.registrationId == ledger.binding(for: UnyriPushContext(scope: second.scope, provider: .apns))?.id }.count == 1,
              "second account registers its own alert slot")
        check(ledger.binding(for: firstAlert)?.active == true, "first account's alert slot survives the other account's session")

        // A push for the inactive account still matches its own origin and registration.
        let alert = ledger.binding(for: firstAlert)!
        let payload = UnyriPushPayload(event: "new_message", user: first.scope.user, device: first.scope.device, pushID: UUID(),
            provider: .apns, registrationID: alert.id, registrationRevision: alert.revision, sentAt: 1, expiresAt: 2,
            callID: nil, mediaKind: nil, chatID: UUID(), messageID: UUID())
        check(runtime.binding(for: payload, origin: first.scope.origin) != nil, "inactive account's alert is attributable to it")
        check(runtime.binding(for: payload, origin: second.scope.origin) == nil, "never attributed to the active account")

        // Switch back: alerts reuse the kept slot, VoIP takes a fresh revision.
        await runtime.retireVoIP(for: second.scope)
        runtime.suspend(scope: second.scope)
        let putsBefore = transport.puts.count
        current = UnyriPushSession(scope: first.scope, generation: 3); runtime.updateSession(); runtime.updatePreferences(settings)
        await wait { runtime.isRegistered(.apns) && runtime.isRegistered(.voip) && !runtime.isWorking }
        let returnedCall = ledger.binding(for: firstCall)!
        check(returnedCall.active && returnedCall.revision == 2, "returning account re-registers VoIP with a new revision")
        check(ledger.binding(for: firstAlert)?.id == alert.id && transport.puts.count == putsBefore + 2, "alert slot reused idempotently")

        // A switch that is abandoned after VoIP was withdrawn (e.g. a call arrived).
        await runtime.retireVoIP(for: first.scope)
        check(!runtime.isRegistered(.voip) && ledger.binding(for: firstCall)?.active == false, "prepared switch withdrew VoIP")
        runtime.restoreVoIP()
        await wait { runtime.isRegistered(.voip) && !runtime.isWorking }
        check(ledger.binding(for: firstCall)?.active == true && ledger.binding(for: firstCall)?.revision == 3,
              "aborted switch re-registers VoIP for the still-current account")
        await runtime.settleAlerts()
        check(ledger.binding(for: firstAlert)?.active == true, "settling alerts never retires them")

        // Work of the leaving account still in flight when a switch begins.
        let third = UnyriPushSession(scope: UnyriPushScope(origin: "https://three.example", user: UUID(), device: UUID()), generation: 10)
        let gatedLedger = try UnyriPushIntentLedger(file: root.appendingPathComponent("gated.json"))
        let putGate = Gate(), accessGate = Gate()
        var gatePuts = false, gateAccess = false, failAccess = false
        var gatedDeletes: [UnyriPushBinding] = []
        var inactiveBearers = 0
        var now: UnyriPushSession? = third
        let gated = UnyriPushRegistration(ledger: gatedLedger, environment: "production", topic: "com.messenger.unyri.X7G7M9PFJ3",
            current: { now }, access: {
                if failAccess { throw CancellationError() }
                if gateAccess { try await accessGate.pass() }
                return "PUBLIC CONTROLLED BEARER NOT A CREDENTIAL"
            }, register: { put, _, session in
                if gatePuts { try await putGate.pass() }
                return UnyriPushReceipt(deviceId: session.scope.device, provider: put.provider, registrationId: put.registrationId, revision: put.revision)
            }, remove: { binding, _, _ in gatedDeletes.append(binding) },
            inactiveAccess: { scope in
                guard scope == third.scope, now?.scope != scope else { throw UnyriPushAPIError.invalid }
                inactiveBearers += 1
                return "PUBLIC CONTROLLED INACTIVE BEARER NOT A CREDENTIAL"
            })
        gated.receivedToken(Data([7]), provider: .apns)
        gated.receivedToken(Data([8]), provider: .voip)
        gated.updateSession(); gated.updatePreferences(settings)
        await wait { gated.isRegistered(.apns) && gated.isRegistered(.voip) && !gated.isWorking }
        let thirdAlert = UnyriPushContext(scope: third.scope, provider: .apns)
        let thirdCall = UnyriPushContext(scope: third.scope, provider: .voip)
        let served = gatedLedger.binding(for: thirdAlert)!
        check(served.confirmed == UnyriPushConfirmation(id: served.id, revision: served.revision), "an uploaded alert slot records the server's receipt")

        // A preference change takes a new revision; its upload hangs while the switch starts.
        gatePuts = true
        let changed = UnyriPushPreferences(messageAlertEnabled: false, messageSoundEnabled: false, permissionAllowed: true)
        gated.updatePreferences(changed)
        await wait { putGate.entered }
        let unconfirmed = gatedLedger.binding(for: thirdAlert)!
        check(unconfirmed.revision == served.revision + 1 && unconfirmed.confirmed?.id == served.id,
              "a new revision keeps the confirmed one until the server answers")
        gated.suspend(scope: third.scope)
        now = nil; gated.updateSession()
        putGate.open(UnyriPushAPIError.http(503))
        await wait { !gated.isWorking }
        let kept = gatedLedger.binding(for: thirdAlert)!
        check(kept.active && kept.id == unconfirmed.id, "an upload failing after the switch never retires the leaving account's alert slot")
        let oldPush = UnyriPushPayload(event: "new_message", user: third.scope.user, device: third.scope.device, pushID: UUID(),
            provider: .apns, registrationID: served.id, registrationRevision: served.revision, sentAt: 1, expiresAt: 2,
            callID: nil, mediaKind: nil, chatID: UUID(), messageID: UUID())
        check(gated.binding(for: oldPush, origin: third.scope.origin) != nil, "inactive routing matches the revision the server confirmed")

        // Back on that account: the kept revision is uploaded and confirmed.
        gatePuts = false
        now = UnyriPushSession(scope: third.scope, generation: 11); gated.updateSession(); gated.updatePreferences(changed)
        await wait { gated.isRegistered(.apns) && gated.isRegistered(.voip) && !gated.isWorking }
        check(gatedLedger.binding(for: thirdAlert)?.confirmed?.id == unconfirmed.id, "the returning account's kept revision is confirmed once uploaded")
        check(gated.binding(for: oldPush, origin: third.scope.origin) == nil, "a superseded revision stops matching after confirmation")

        // The switch starts while the VoIP withdrawal is still fetching the bearer.
        gateAccess = true
        let callSlot = gatedLedger.binding(for: thirdCall)!
        let withdrawal = Task { await gated.retireVoIP(for: third.scope) }
        await wait { accessGate.entered }
        gated.suspend(scope: third.scope)
        now = nil; gated.updateSession()
        accessGate.open()
        await withdrawal.value
        await wait { !gated.isWorking }
        check(gatedDeletes.filter { $0.id == callSlot.id }.count == 1, "the VoIP DELETE still goes out once, with the bearer fetched before the switch")
        check(gatedLedger.binding(for: thirdCall)?.cleanupPending == false, "the withdrawn VoIP slot is clean")

        // No bearer at all: the withdrawal stays a durable pending cleanup that
        // `retry` sends with the inactive account's own session.
        gateAccess = false
        now = UnyriPushSession(scope: third.scope, generation: 12); gated.updateSession(); gated.updatePreferences(changed)
        await wait { gated.isRegistered(.voip) && !gated.isWorking }
        let secondCall = gatedLedger.binding(for: thirdCall)!
        failAccess = true
        await gated.retireVoIP(for: third.scope)
        check(gatedLedger.binding(for: thirdCall)?.cleanupPending == true, "a withdrawal without a bearer stays pending")
        gated.suspend(scope: third.scope)
        now = nil; gated.updateSession()
        await wait { !gated.isWorking }
        check(gatedDeletes.last?.id == secondCall.id && gatedLedger.binding(for: thirdCall)?.cleanupPending == false && inactiveBearers == 1,
              "retry sends the pending VoIP DELETE with the inactive account's session")
        check(gatedLedger.binding(for: thirdAlert)?.active == true, "the inactive account's alert slot is still active")

        // Signing that account out (removal) retires everything; nothing is kept.
        gated.retire(scope: third.scope)
        check(gatedLedger.binding(for: thirdAlert)?.active == false, "a removed account's alert slot is retired")
        print("PUSH_ACCOUNT_SWITCH \(checks) PASS")
    }
}
