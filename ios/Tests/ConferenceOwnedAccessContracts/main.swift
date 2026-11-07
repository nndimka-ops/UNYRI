import Foundation

@main struct OwnedAccessContracts {
    @MainActor static func main() throws {
        var count = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); count += 1; print("PASS: \(label)") }
        func reject(_ label: String, _ body: () throws -> Void) {
            do { try body(); preconditionFailure(label) } catch { count += 1; print("PASS: \(label)") }
        }
        var memory: [String: String] = [:]
        var writable = true, readable = true
        func store() -> SessionStore {
            SessionStore(read: { key in if !readable { throw CocoaError(.fileReadNoPermission) }; return memory[key] },
                write: { value, key in if !writable { return false }; memory[key] = value; return true },
                remove: { memory.removeValue(forKey: $0) })
        }
        let user = User(id: "11111111-1111-4111-8111-111111111111", username: "owner", displayName: "Owner")
        let device = "22222222-2222-4222-8222-222222222222"
        let server = URL(string: "https://owned-test.invalid")!
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func response() -> AuthResponse {
            AuthResponse(accessToken: "synthetic-access", accessTokenExpiresAt: now.addingTimeInterval(3600),
                refreshToken: "synthetic-refresh", deviceId: device, user: user)
        }
        try store().saveSession(response())
        let scope = try store().ownedRoomAccessSnapshot(server: server)!
        check(scope.raw == nil, "new login has no inherited room key")
        let a = ConferenceInvite(server: server, roomID: UUID(), secret: ConferenceInvite.encode(Data(1...32)), mediaKey: ConferenceInvite.encode(Data(33...64)))
        let vault = ConferenceOwnedAccess(server: server, owner: scope.binding, raw: scope.raw, now: now) { raw in
            try store().saveOwnedRoomAccess(raw, server: server, binding: scope.binding)
        }
        try vault.remember(a, expiresAt: now.addingTimeInterval(3600), now: now)
        check(vault.invitation(for: a.roomID, now: now) == a, "leave may discard SDK keys while creator re-entry remains")
        let saved = try store().ownedRoomAccessSnapshot(server: server)!
        check(saved.raw != nil && saved.binding == scope.binding, "record is durably bound to this login")
        let restored = ConferenceOwnedAccess(server: server, owner: saved.binding, raw: saved.raw, now: now) { _ in }
        check(restored.invitation(for: a.roomID, now: now) == a, "re-entry survives process object recreation")
        check(saved.raw?.contains("password") == false, "saved schema contains no password field")
        try store().saveTokens(accessToken: "rotated-access", accessTokenExpiresAt: now.addingTimeInterval(2000), refreshToken: "rotated-refresh")
        let refreshed = try store().ownedRoomAccessSnapshot(server: server)!
        check(refreshed.binding == scope.binding && refreshed.raw == saved.raw, "token refresh preserves re-entry owner nonce and record")
        writable = false
        reject("failed new-login snapshot does not clear existing re-entry") { try store().saveSession(response()) }
        writable = true
        let unchanged = try store().ownedRoomAccessSnapshot(server: server)!
        check(unchanged.binding == scope.binding && unchanged.raw == saved.raw, "failed login write preserves the existing binding and record atomically")
        let proxy = AuthProxy(store: store(), user: user)
        check(try proxy.ownedRoomAccessSnapshot(server: server, generation: 1)?.binding == scope.binding, "actual auth proxy reads only its current generation")
        proxy.sessionGeneration = 2
        check(try proxy.ownedRoomAccessSnapshot(server: server, generation: 1) == nil, "old auth generation cannot read saved room access")
        reject("old auth generation cannot persist room access") { try proxy.saveOwnedRoomAccess(saved.raw!, server: server, binding: scope.binding, generation: 1) }
        proxy.state = .loggedOut
        reject("logged-out auth proxy rejects room storage writes") { try proxy.saveOwnedRoomAccess(saved.raw!, server: server, binding: scope.binding, generation: 2) }
        check(try store().ownedRoomAccessSnapshot(server: URL(string: "https://other-test.invalid")!)?.binding != scope.binding, "server origin is part of the binding")
        let wrongBinding = ConferenceOwnedAccess(server: server, owner: scope.binding + "-other", raw: saved.raw, now: now) { _ in }
        check(wrongBinding.invitation(for: a.roomID, now: now) == nil, "retained foreign-owner record is never admitted")
        let foreign = ConferenceOwnedAccess(server: URL(string: "https://foreign.invalid")!, owner: scope.binding, raw: saved.raw, now: now) { _ in }
        check(foreign.invitation(for: a.roomID, now: now) == nil, "record cannot import a different-server invitation")
        var prunedRaw: String?
        let expired = ConferenceOwnedAccess(server: server, owner: scope.binding, raw: saved.raw, now: now.addingTimeInterval(3601)) { prunedRaw = $0 }
        check(expired.invitation(for: a.roomID, now: now.addingTimeInterval(3601)) == nil, "expired key is unavailable after restart")
        check(prunedRaw != nil && prunedRaw?.contains(a.roomID.uuidString) == false, "expired entries are removed from the protected record on load")
        var dayRaw: String?
        let dayVault = ConferenceOwnedAccess(server: server, owner: scope.binding, raw: nil, now: now) { dayRaw = $0 }
        try dayVault.remember(a, expiresAt: now.addingTimeInterval(86_400), now: now)
        check(dayVault.invitation(for: a.roomID, now: now) == a, "full-day room key is accepted")
        let dayRestored = ConferenceOwnedAccess(server: server, owner: scope.binding, raw: dayRaw, now: now.addingTimeInterval(18_000)) { _ in }
        check(dayRestored.invitation(for: a.roomID, now: now.addingTimeInterval(18_000)) == a, "24-hour key survives restart after the old four-hour deadline")
        check(dayRestored.invitation(for: a.roomID, now: now.addingTimeInterval(86_400)) == nil, "full-day key expires at the actual deadline")
        reject("refuse excessive room lifetime") { try vault.remember(a, expiresAt: now.addingTimeInterval(86_431), now: now) }
        reject("refuse already-expired room") { try vault.remember(a, expiresAt: now, now: now) }
        let wrongOrigin = ConferenceInvite(server: URL(string: "https://other.invalid")!, roomID: a.roomID, secret: a.secret, mediaKey: a.mediaKey)
        reject("refuse wrong-origin save") { try vault.remember(wrongOrigin, expiresAt: now.addingTimeInterval(3600), now: now) }
        let malformed = ConferenceOwnedAccess(server: server, owner: scope.binding, raw: String(repeating: "x", count: 12_289), now: now) { _ in }
        check(malformed.invitation(for: a.roomID, now: now) == nil, "oversized or malformed records fail closed")
        writable = false
        let b = ConferenceInvite(server: server, roomID: UUID(), secret: a.secret, mediaKey: a.mediaKey)
        reject("storage failure is surfaced") { try vault.remember(b, expiresAt: now.addingTimeInterval(4000), now: now) }
        check(vault.invitation(for: b.roomID, now: now) == nil, "failed write does not advertise a recoverable key")
        writable = true; readable = false
        reject("locked Keychain is not treated as an empty login") { _ = try store().ownedRoomAccessSnapshot(server: server) }
        readable = true
        check(memory["conference_owned_access_v1"] == saved.raw, "locked read never erases existing data")
        let row = ConferenceDTO(ownerUserId: UUID(uuidString: user.id)!, id: a.roomID)
        let selector = Selector(); selector.auth?.currentUser = user
        let currentScope = try store().ownedRoomAccessSnapshot(server: server)!
        let liveVault = ConferenceOwnedAccess(server: server, owner: currentScope.binding, raw: nil) { _ in }
        try liveVault.remember(a, expiresAt: Date().addingTimeInterval(3600))
        selector.vault = liveVault
        selector.rejoinOwnedRoom(row)
        check(selector.joined == a && !selector.requestedMic && !selector.requestedCamera, "actual controller rejoin uses original key with capture off")
        func directory(owner: UUID = row.ownerUserId, access: String = "open", expires: Date = Date().addingTimeInterval(3600)) -> ConferenceDirectoryRoom {
            ConferenceDirectoryRoom(id: a.roomID, title: "Test", description: "", ownerUserId: owner, ownerDisplayName: "Owner",
                participantCount: 0, maxParticipants: 30, accessMode: access, admissionLocked: false, keyAvailable: false,
                createdAt: Date(), expiresAt: expires)
        }
        let catalog = Selector(); catalog.auth?.currentUser = user; catalog.vault = liveVault
        check(catalog.rejoinSavedDirectoryRoom(directory()), "server directory consumes an existing owner key even when no provider is online")
        check(catalog.joined == a && !catalog.showLobby && !catalog.requestedMic && !catalog.requestedCamera,
              "server-tab entry uses exactly the saved secret with capture off and no handoff")
        let protected = Selector(); protected.auth?.currentUser = user; protected.vault = liveVault
        check(protected.rejoinSavedDirectoryRoom(directory(access: "password")) && protected.showLobby && protected.offeredInvite == a && protected.joined == nil,
              "saved key never bypasses room password entry")
        let other = Selector(); other.auth?.currentUser = user; other.vault = liveVault
        check(!other.rejoinSavedDirectoryRoom(directory(owner: UUID())) && other.joined == nil,
              "directory cannot use the owner key for a different account")
        check(!other.rejoinSavedDirectoryRoom(directory(expires: Date().addingTimeInterval(-1))), "expired directory entry cannot reuse a key")
        let catalogMissing = Selector(); catalogMissing.auth?.currentUser = user
        check(!catalogMissing.rejoinSavedDirectoryRoom(directory()) && catalogMissing.joined == nil, "missing installation key falls back to the normal entry sheet")
        catalog.joined = nil; catalog.isBusy = true
        check(catalog.rejoinSavedDirectoryRoom(directory()) && catalog.joined == nil && catalog.error != nil,
              "active conversation blocks directory re-entry without requesting another key")
        catalog.isBusy = false; catalog.api.isCurrentSession = false
        check(!catalog.rejoinSavedDirectoryRoom(directory()) && catalog.joined == nil, "retired session cannot reuse the room key")
        let pw = Selector(); pw.auth?.currentUser = user; pw.vault = liveVault
        var passwordRow = row; passwordRow.accessMode = "invite_password"
        pw.rejoinOwnedRoom(passwordRow)
        check(pw.showLobby && pw.offeredInvite == a && pw.joined == nil, "password-protected room asks again without storing password")
        let missing = Selector(); missing.auth?.currentUser = user
        missing.rejoinOwnedRoom(row)
        check(missing.showLobby && missing.offeredInvite == nil && missing.joined == nil, "old room without key explicitly falls back to invitation")
        let denied = Selector(); denied.auth?.currentUser = user; denied.vault = liveVault; denied.api.isCurrentSession = false
        denied.rejoinOwnedRoom(row)
        check(denied.joined == nil && !denied.showLobby, "stale session does not rejoin")
        let busy = Selector(); busy.auth?.currentUser = user; busy.vault = liveVault; busy.canUseMedia = { false }
        busy.rejoinOwnedRoom(row)
        check(busy.joined == nil && busy.error != nil, "current media owner is not bypassed")
        let returning = Selector(); returning.auth?.currentUser = user; returning.vault = liveVault; returning.isBusy = true; returning.conference = row
        returning.rejoinOwnedRoom(row)
        check(!returning.minimized && returning.joined == nil, "active same-room action restores instead of duplicate join")
        var otherOwner = row; otherOwner.ownerUserId = UUID()
        check(!selector.hasSavedAccess(to: otherOwner), "foreign owner DTO cannot use saved creator capability")
        selector.forgetOwnedRoom(a.roomID)
        check(!selector.hasSavedAccess(to: row), "end removes saved creator capability")
        let beforeNewLogin = memory["conference_owned_access_v1"]!
        try store().saveSession(response())
        let second = try store().ownedRoomAccessSnapshot(server: server)!
        check(second.binding != scope.binding && second.raw == nil, "same-account same-device relogin creates a fresh owner nonce")
        memory["conference_owned_access_v1"] = beforeNewLogin
        let retained = ConferenceOwnedAccess(server: server, owner: second.binding, raw: beforeNewLogin, now: now) { _ in }
        check(retained.invitation(for: a.roomID, now: now) == nil, "imperfect deletion cannot inherit old workspace keys")
        reject("late pre-login writer is rejected") { try vault.remember(b, expiresAt: now.addingTimeInterval(4000), now: now) }
        check(memory["conference_owned_access_v1"] == beforeNewLogin, "rejected old writer leaves current record intact")
        store().clearSession()
        check(memory["conference_owned_access_v1"] == nil, "logout deletes the dedicated Keychain record")
        reject("post-logout write cannot repopulate keys") { try store().saveOwnedRoomAccess(beforeNewLogin, server: server, binding: second.binding) }
        var boundedRaw = ""
        let bounded = ConferenceOwnedAccess(server: server, owner: "bound", raw: nil, now: now) { boundedRaw = $0 }
        var ids = [UUID]()
        for index in 0..<4 {
            let value = ConferenceInvite(server: server, roomID: UUID(), secret: a.secret, mediaKey: a.mediaKey); ids.append(value.roomID)
            try bounded.remember(value, expiresAt: now.addingTimeInterval(Double(1000 + index)), now: now)
        }
        check(ids.filter { bounded.invitation(for: $0, now: now) != nil }.count == 3, "bounded storage keeps at most three current keys")
        check(bounded.invitation(for: ids[0], now: now) == nil, "oldest entry is evicted at the room quota")
        check(!boundedRaw.contains("synthetic-refresh") && !boundedRaw.contains("synthetic-access"), "room record contains no auth tokens")
        check(ConferenceRoomMetrics.controlSize >= 44 && ConferenceRoomMetrics.controlIcon < ConferenceRoomMetrics.controlSize, "visible room controls retain accessible targets")
        check(ConferenceRoomMetrics.showsSinglePlaceholder(total: 1, hasOwnTile: true, cameraEnabled: false), "single listener uses the compact idle stage")
        check(!ConferenceRoomMetrics.showsSinglePlaceholder(total: 1, hasOwnTile: true, cameraEnabled: true), "own camera enters the grid when enabled")
        check(!ConferenceRoomMetrics.showsSinglePlaceholder(total: 2, hasOwnTile: true, cameraEnabled: false), "a peer joining restores the shared grid")
        check(!ConferenceRoomMetrics.showsSinglePlaceholder(total: 1, hasOwnTile: false, cameraEnabled: false), "missing own tile is not misclassified as a live single room")
        var editor = EditorFixture()
        check(!editor.validDraft, "actual editor disables empty-title submission")
        editor.configuration.title = "My room"
        check(editor.validDraft && editor.dirty, "valid private room is immediately creatable")
        editor.limit = 31
        check(!editor.validDraft, "actual editor enforces the 30-person limit")
        editor.limit = 30; editor.configuration.accessMode = .invitePassword
        check(!editor.validDraft, "password access cannot submit without a new password")
        editor.password = "123"
        check(!editor.validDraft, "short password is rejected before a request")
        editor.password = "123456"
        check(editor.validDraft, "valid password enables submission")
        editor.configuration.description = "line\nbreak"
        check(!editor.validDraft, "invalid description disables submission")
        editor.configuration.description = ""; editor.existing = true; editor.initialMode = .invitePassword
        editor.password = ""; editor.initialConfiguration = editor.configuration
        check(editor.validDraft && !editor.dirty && editor.draft.passwordAction == .keep, "unchanged existing password is kept without an unnecessary save")
        editor.password = "new-password"
        check(editor.validDraft && editor.dirty && editor.draft.passwordAction == .set, "an explicit password replacement becomes a valid dirty draft")
        editor.password = ""; editor.configuration.title = "Changed room"
        check(editor.validDraft && editor.dirty, "room edits become savable without retyping the unchanged password")
        print("PASS: \(count) production owner-vault/session/rejoin/room-metric assertions; no native Keychain or media claim")
    }
}
