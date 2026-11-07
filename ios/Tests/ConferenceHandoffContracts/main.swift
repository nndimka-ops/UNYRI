import Foundation

@main struct HandoffContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); checks += 1 }
        func wait(_ condition: @escaping @MainActor () -> Bool) async throws {
            for _ in 0..<3000 {
                if condition() { return }
                try await Task.sleep(nanoseconds: 1_000_000)
            }
            preconditionFailure("bounded test condition not reached")
        }
        let room = ConferenceDirectoryRoom(id: UUID(), title: "Fixture", description: "", ownerUserId: UUID(), ownerDisplayName: "Provider",
            participantCount: 1, maxParticipants: 30, accessMode: "open", admissionLocked: false, keyAvailable: false,
            createdAt: Date(), expiresAt: Date().addingTimeInterval(3600))
        check(room.canRequestKey, "an absent provider heartbeat does not disable explicit waiting")
        func fixture() -> (AuthRepository, ConferencesAPI, SignalSessionManager, ConferenceKeyHandoff) {
            let auth = AuthRepository(), api: ConferencesAPI
            api = ConferencesAPI(auth: auth)
            let signal = SignalSessionManager(api: api)
            let model = ConferenceKeyHandoff(api: api, auth: auth, signal: signal, nonceFactory: { ConferenceInvite.encode(Data(0..<32)) })
            return (auth, api, signal, model)
        }
        do {
            let (_, api, signal, model) = fixture(); var ready: [ConferenceCatalogCredential] = []
            model.onReady = { ready.append($0) }; model.start(room: room, password: nil)
            try await wait { model.phase == .ready }
            check(ready.count == 1 && signal.decrypts == 1, "verified request starts exactly one admission callback")
            check(api.posts.count == 1 && api.reads.count == 1, "ready result is rechecked after Signal decrypt")
            check(ConferenceAdmissionCredential.catalog(ready[0]).invitation == nil, "public guests never gain an invitation secret")
        }
        do {
            let (_, api, signal, model) = fixture(); api.ackLostOnce = true; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: "  secret  ")
            try await wait { model.phase == .ready }
            check(api.posts.count == 1 && api.reads.count == 2, "lost POST acknowledgement recovers the same request through GET")
            check(ready == 1 && signal.decrypts == 1 && api.posts[0].3 == "  secret  ", "ack recovery does not duplicate decrypt or alter password")
        }
        do {
            let (_, api, signal, model) = fixture(); api.notCommittedOnce = true
            model.start(room: room, password: "  secret  "); try await wait { model.phase == .ready }
            check(api.posts.count == 2 && Set(api.posts.map { $0.1 }).count == 1 && Set(api.posts.map { $0.2 }).count == 1,
                "uncommitted POST retry preserves exact request ID/nonce/body")
            check(signal.decrypts == 1, "transport retry decrypts once")
        }
        do {
            let (_, api, signal, model) = fixture(); let gate = Gate(); api.postGate = gate; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: nil)
            try await wait { gate.entered }; let id = api.posts[0].1
            model.cancel(); gate.open(); try await wait { api.cancelled.contains(id) }
            try await Task.sleep(nanoseconds: 20_000_000)
            check(model.phase == .cancelled && ready == 0 && signal.decrypts == 0, "late committed POST cannot start media after Cancel")
            check(api.records[id] == nil, "cancel cleans the exact committed request")
        }
        do {
            let (auth, api, signal, model) = fixture(); let gate = Gate(); api.postGate = gate; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: nil); try await wait { gate.entered }
            auth.sessionGeneration += 1; model.cancel(); gate.open(); try await Task.sleep(nanoseconds: 30_000_000)
            check(ready == 0 && signal.decrypts == 0 && api.cancelled.isEmpty, "old-account result cannot use successor token, decrypt or start media")
        }
        do {
            let (_, api, signal, model) = fixture(); let gate = Gate(); api.postGate = gate; api.commitAfterGate = true; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: nil)
            try await wait { gate.entered }; let old = api.posts[0].1
            model.cancel(); try await wait { api.cancelled.contains(old) }
            model.start(room: room, password: nil)
            try await wait { model.phase == .ready }
            check(api.posts.count == 2 && ready == 1, "replacement request can complete after exact cancellation without retaining old transport")
            gate.open(); try await Task.sleep(nanoseconds: 20_000_000)
            check(api.records[old] == nil && api.cancelled.contains(old), "server tombstone rejects a POST that arrives after early DELETE")
            check(api.posts[1].1 != old && signal.decrypts == 1 && ready == 1, "late cancelled result cannot decrypt or admit over the replacement owner")
        }
        do {
            let (_, api, signal, model) = fixture(); let gate = Gate(); signal.gate = gate; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: nil); try await wait { gate.entered }
            model.cancel(); gate.open(); try await Task.sleep(nanoseconds: 30_000_000)
            check(ready == 0 && signal.decrypts == 1 && model.phase == .cancelled, "cancel during decrypt drops its late plaintext without second decrypt")
            check(!api.cancelled.isEmpty, "cancel during decrypt also invalidates mailbox")
        }
        do {
            let (_, api, signal, model) = fixture(); api.changedAssignment = true; var ready = 0
            model.onReady = { _ in ready += 1 }; model.start(room: room, password: nil); try await wait { model.phase == .failed }
            check(ready == 0 && signal.decrypts == 1, "reassignment after decrypt cannot start a room or re-decrypt old ciphertext")
            check(model.error == ConferenceDirectoryFailure.changed.localizedDescription, "reassignment supplies useful retry guidance")
        }
        do {
            let (_, api, signal, model) = fixture(); api.wrongRecipient = true
            model.start(room: room, password: nil); try await wait { model.phase == .failed }
            check(signal.decrypts == 0, "wrong recipient is rejected before Signal ratchet mutation")
        }
        do {
            let (_, api, signal, model) = fixture(); api.pending = true
            model.start(room: room, password: nil); try await wait { model.phase == .waiting }
            check(api.posts.count == 1 && signal.decrypts == 0, "no-provider state waits without decrypt/admission")
            model.cancel(); try await wait { !api.cancelled.isEmpty }
        }
        do {
            let (_, api, signal, model) = fixture(); api.expired = true
            model.start(room: room, password: nil); try await wait { model.phase == .timedOut }
            check(signal.decrypts == 0, "expired server deadline becomes timeout before decrypt")
        }
        do {
            let (_, api, signal, model) = fixture(); api.readFailures = 1
            model.start(room: room, password: nil); try await wait { model.phase == .ready }
            check(signal.decrypts == 1 && api.reads.count == 2, "transient confirmation failure retains verified plaintext and never re-decrypts")
        }
        do {
            let (_, api, signal, model) = fixture(); api.rateLimitedOnce = true
            model.start(room: room, password: nil); try await wait { api.reads.count == 1 }
            try await Task.sleep(nanoseconds: 25_000_000)
            check(api.posts.count == 1, "429 does not create a hot POST retry loop")
            model.cancel(); try await Task.sleep(nanoseconds: 20_000_000)
            check(model.phase == .cancelled && signal.decrypts == 0, "rate-limit backoff remains cancellable without media")
        }
        do {
            let (_, api, signal, model) = fixture(); api.approvalRequired = true
            let gate = Gate(); api.approvalGate = gate
            var ready = 0; model.onReady = { _ in ready += 1 }
            model.start(room: room, password: nil)
            try await wait { model.phase == .waitingApproval && gate.entered }
            check(signal.decrypts == 0 && ready == 0 && model.isWaiting, "creator wait never decrypts or admits")
            gate.open(); try await wait { model.phase == .ready }
            check(api.approvals == 1 && api.posts.count == 2 && api.posts[0].1 == api.posts[1].1 && ready == 1,
                  "approved catalog request resumes exact encrypted handshake once")
        }
        do {
            let (_, api, signal, model) = fixture(); api.approvalRequired = true
            let gate = Gate(); api.approvalGate = gate
            var ready = 0; model.onReady = { _ in ready += 1 }
            model.start(room: room, password: nil); try await wait { gate.entered }
            model.cancel(); gate.open(); try await Task.sleep(nanoseconds: 20_000_000)
            check(model.phase == .cancelled && signal.decrypts == 0 && ready == 0 && api.posts.count == 1,
                  "cancel during creator wait cannot request a late media key")
        }
        print("Conference handoff actual orchestration: \(checks) passed; in-memory API/Signal seams, NOT cryptography/native/network proof.")
    }
}
