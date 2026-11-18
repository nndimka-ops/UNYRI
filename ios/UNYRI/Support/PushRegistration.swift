import Foundation

enum UnyriPushProvider: String, Codable, CaseIterable, Hashable {
    case apns
    case voip = "apns_voip"
}

struct UnyriPushScope: Codable, Equatable {
    let origin: String
    let user: UUID
    let device: UUID

    var valid: Bool {
        guard let url = URLComponents(string: origin), ["http", "https"].contains(url.scheme ?? ""),
              url.host?.isEmpty == false, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { return false }
        return url.path.isEmpty || url.path == "/"
    }
}

struct UnyriPushContext: Codable, Equatable {
    let scope: UnyriPushScope
    let provider: UnyriPushProvider
    var key: String { scope.origin + "|" + scope.user.uuidString.lowercased() + "|" + scope.device.uuidString.lowercased() + "|" + provider.rawValue }
}

struct UnyriPushPreferences: Codable, Equatable {
    let messageAlertEnabled: Bool
    let messageSoundEnabled: Bool
    let permissionAllowed: Bool
}

struct UnyriPushPut: Encodable {
    let provider: String
    let token: String
    let environment: String
    let topic: String
    let registrationId: UUID
    let revision: Int64
    let preferences: UnyriPushPreferences?
}

struct UnyriPushReceipt: Decodable {
    let deviceId: UUID
    let provider: String
    let registrationId: UUID
    let revision: Int64
}

enum UnyriPushAPIError: Error {
    case invalid
    case http(Int)
    case stale(Int64)
    case retired
}

struct UnyriPushSession: Equatable {
    let scope: UnyriPushScope
    let generation: UInt64
}

/// A registration the server acknowledged with a PUT receipt.
struct UnyriPushConfirmation: Codable, Equatable {
    let id: UUID
    let revision: Int64
}

struct UnyriPushBinding: Codable, Equatable {
    let context: UnyriPushContext
    let id: UUID
    let revision: Int64
    var active: Bool
    var cleanupPending = false
    /// The registration the server last confirmed for this slot. A new revision is
    /// persisted before its PUT is sent; until the server confirms it, pushes still
    /// carry this one and must keep matching (absent in ledgers of older versions).
    var confirmed: UnyriPushConfirmation? = nil

    func carries(id other: UUID, revision value: Int64) -> Bool {
        (id == other && revision == value) || (confirmed?.id == other && confirmed?.revision == value)
    }
}

/// Installation-owned intent metadata only. APNs tokens and authentication
/// material must never be encoded here or restored from a backup.
final class UnyriPushIntentLedger {
    enum Failure: Error { case invalid, full, overflow }
    private struct Archive: Codable { let version: Int; let entries: [UnyriPushBinding] }
    private let file: URL
    private var entries: [String: UnyriPushBinding]
    private let write: (Data, URL) throws -> Void

    init(file: URL, write: ((Data, URL) throws -> Void)? = nil) throws {
        self.file = file
        self.write = write ?? { data, target in
            var directory = target.deletingLastPathComponent()
            if let attributes = try? FileManager.default.attributesOfItem(atPath: directory.path) {
                guard attributes[.type] as? FileAttributeType == .typeDirectory else { throw Failure.invalid }
            }
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            #if os(iOS)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try data.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: target, options: .atomic)
            #endif
        }
        var loaded: [String: UnyriPushBinding] = [:]
        if let attributes = try? FileManager.default.attributesOfItem(atPath: file.deletingLastPathComponent().path) {
            guard attributes[.type] as? FileAttributeType == .typeDirectory else { throw Failure.invalid }
        }
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  ((attributes[.size] as? NSNumber)?.intValue ?? Int.max) <= 128 * 1024 else { throw Failure.invalid }
            let archive = try JSONDecoder().decode(Archive.self, from: Data(contentsOf: file))
            guard archive.version == 1, archive.entries.count <= 64 else { throw Failure.invalid }
            for entry in archive.entries {
                guard entry.context.scope.valid, entry.revision > 0, loaded[entry.context.key] == nil else { throw Failure.invalid }
                loaded[entry.context.key] = entry
            }
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
            // A dangling link is not an absent owned ledger.
            if (try? FileManager.default.destinationOfSymbolicLink(atPath: file.path)) != nil { throw Failure.invalid }
        }
        entries = loaded
    }

    func binding(for context: UnyriPushContext) -> UnyriPushBinding? { entries[context.key] }
    func matches(_ payload: UnyriPushPayload, origin: String) -> UnyriPushBinding? {
        entries.values.first { $0.active && $0.context.scope.origin == origin &&
            $0.context.scope.user == payload.user && $0.context.scope.device == payload.device &&
            $0.context.provider == payload.provider && $0.carries(id: payload.registrationID, revision: payload.registrationRevision) }
    }
    /// Retired slots whose server DELETE has not succeeded yet.
    func pendingCleanups() -> [UnyriPushBinding] {
        entries.values.filter { !$0.active && $0.cleanupPending }.sorted { $0.context.key < $1.context.key }
    }

    /// Persist the new revision before any HTTP request can be dispatched.
    @discardableResult
    func advance(_ context: UnyriPushContext, after floor: Int64 = 0) throws -> UnyriPushBinding {
        guard context.scope.valid, floor >= 0 else { throw Failure.invalid }
        var candidate = entries
        guard candidate[context.key] != nil || candidate.count < 64 else { throw Failure.full }
        let old = candidate[context.key]
        let previous = max(old?.revision ?? 0, floor)
        guard previous < Int64.max else { throw Failure.overflow }
        var value = UnyriPushBinding(context: context, id: UUID(), revision: previous + 1, active: true)
        // Until the server confirms the new revision it still sends the old one.
        // A slot recorded before confirmations existed counts as confirmed.
        if let old, old.active { value.confirmed = old.confirmed ?? UnyriPushConfirmation(id: old.id, revision: old.revision) }
        candidate[context.key] = value
        try commit(candidate)
        return value
    }

    /// The server acknowledged `binding` (its PUT receipt). Kept while the slot is
    /// active and has not moved past it to a different registration.
    func confirm(_ binding: UnyriPushBinding) throws {
        guard var value = entries[binding.context.key], value.active, value.revision >= binding.revision,
              value.revision > binding.revision || value.id == binding.id,
              (value.confirmed?.revision ?? 0) < binding.revision else { return }
        value.confirmed = UnyriPushConfirmation(id: binding.id, revision: binding.revision)
        var candidate = entries; candidate[binding.context.key] = value
        try commit(candidate)
    }

    func retire(_ expected: UnyriPushBinding) throws {
        guard var value = entries[expected.context.key], value.id == expected.id,
              value.revision == expected.revision else { return }
        if !value.active && value.cleanupPending { return }
        var candidate = entries; value.active = false; value.cleanupPending = true
        candidate[expected.context.key] = value
        try commit(candidate)
    }

    func retire(scope: UnyriPushScope) throws {
        var candidate = entries
        for (key, var value) in candidate where value.context.scope == scope {
            value.active = false; value.cleanupPending = true; candidate[key] = value
        }
        try commit(candidate)
    }

    /// OS credential invalidation applies to this provider on the installation,
    /// including cold/restoring contexts not yet attached to an account.
    func retire(provider: UnyriPushProvider) throws {
        var candidate = entries
        var changed = false
        for (key, var value) in candidate where value.context.provider == provider {
            if value.active || !value.cleanupPending {
                value.active = false; value.cleanupPending = true; candidate[key] = value; changed = true
            }
        }
        if changed { try commit(candidate) }
    }

    func cleaned(_ expected: UnyriPushBinding) throws {
        guard let old = entries[expected.context.key], old.id == expected.id, old.revision == expected.revision, !old.active else { return }
        var candidate = entries; var value = old; value.cleanupPending = false
        candidate[expected.context.key] = value; try commit(candidate)
    }

    private func commit(_ candidate: [String: UnyriPushBinding]) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(Archive(version: 1, entries: candidate.values.sorted { $0.context.key < $1.context.key }))
        guard data.count <= 128 * 1024 else { throw Failure.full }
        try write(data, file)
        entries = candidate
    }
}

/// A push is an authenticated provider wake/routing hint, never message data
/// or authority to switch account, select a URL, answer a call, or start media.
struct UnyriPushPayload: Equatable {
    let event: String
    let user: UUID
    let device: UUID
    let pushID: UUID
    let provider: UnyriPushProvider
    let registrationID: UUID
    let registrationRevision: Int64
    let sentAt: Int64
    let expiresAt: Int64
    let callID: UUID?
    let mediaKind: String?
    let chatID: UUID?
    let messageID: UUID?

    static func integer(_ value: Any?) -> Int64? {
        // JSON integer representations only: explicitly exclude Boolean c/B
        // and floating-point f/d, without a lossy NSNumber-to-Int cast.
        guard let number = value as? NSNumber,
              ["s", "i", "l", "q", "S", "I", "L", "Q"].contains(String(cString: number.objCType)) else { return nil }
        return Int64(number.stringValue)
    }
    private static func uuid(_ value: Any?) -> UUID? {
        guard let text = value as? String, let id = UUID(uuidString: text), id.uuidString.lowercased() == text else { return nil }
        return id
    }
    static func parse(_ values: [AnyHashable: Any], provider: UnyriPushProvider, now: Int64) -> Self? {
        envelope(values, provider: provider, freshAt: now)
    }
    /// A genuine but stale UUID still has to reach CallKit immediately. This
    /// structure-only hint is never admission to auth/network/media recovery.
    static func reportableVoIP(_ values: [AnyHashable: Any]) -> Self? {
        envelope(values, provider: .voip, freshAt: nil)
    }
    /// A tap is the user's own action on an alert iOS already showed, so it may
    /// open its chat however old it is or whichever registration revision it
    /// carried. Structure only: never admission to presentation, wake or VoIP.
    static func tapRoute(_ values: [AnyHashable: Any]) -> Self? {
        guard let hint = envelope(values, provider: .apns, freshAt: nil), hint.event == "new_message" else { return nil }
        return hint
    }
    /// Sent to this account on this device: the only owner a tap may route to.
    func addressed(to scope: UnyriPushScope) -> Bool { user == scope.user && device == scope.device }
    private static func envelope(_ values: [AnyHashable: Any], provider: UnyriPushProvider, freshAt now: Int64?) -> Self? {
        guard values.count <= 32, let registration = values["registration"] as? [String: Any], registration.count == 3,
              registration["provider"] as? String == provider.rawValue,
              let id = uuid(registration["id"]), let revision = integer(registration["revision"]), revision > 0,
              let user = uuid(values["user_id"]), let device = uuid(values["device_id"]), let push = uuid(values["push_id"]),
              let sent = integer(values["sent_at_ms"]), let expires = integer(values["expires_at_ms"]),
              sent > 0, expires > sent, expires - sent <= (provider == .voip ? 60_000 : 86_400_000),
              let event = values["event"] as? String else { return nil }
        if let now {
            let future = now.addingReportingOverflow(300_000)
            guard !future.overflow, now > 0, sent <= future.partialValue, expires >= now else { return nil }
        }
        let call = uuid(values["call_id"]); let chat = uuid(values["chat_id"]); let message = uuid(values["message_id"])
        let media = values["media_kind"] as? String
        if provider == .voip {
            guard event == "incoming_call", call != nil, ["audio", "video"].contains(media ?? "") else { return nil }
        } else {
            guard ["new_message", "security_event", "admin_notice"].contains(event) else { return nil }
            if event == "new_message", chat == nil || message == nil { return nil }
        }
        return Self(event: event, user: user, device: device, pushID: push, provider: provider,
                    registrationID: id, registrationRevision: revision, sentAt: sent, expiresAt: expires,
                    callID: call, mediaKind: media, chatID: chat, messageID: message)
    }
}

/// One serialized lane per role, including completion and conditional cleanup.
/// Cancelling a local task is not evidence that a remote PUT did not commit.
/// No periodic worker: launch/auth/foreground/network and OS token callbacks
/// explicitly retry pending work through the existing application lifecycle.
@MainActor
final class UnyriPushRegistration {
    private struct Intent: Equatable {
        var binding: UnyriPushBinding
        let session: UnyriPushSession
        let token: String
        let preferences: UnyriPushPreferences?
        var uploaded = false
        var retired = false
        var rebased = false
    }
    private let ledger: UnyriPushIntentLedger
    private let current: () -> UnyriPushSession?
    private let access: () async throws -> String
    private let register: (UnyriPushPut, String, UnyriPushSession) async throws -> UnyriPushReceipt
    private let remove: (UnyriPushBinding, String, UnyriPushSession) async throws -> Void
    /// A bearer of a signed-in account that is not the current one (its own
    /// slot and server). Throws for any scope that is not such an account.
    private let inactiveAccess: (UnyriPushScope) async throws -> String
    private let environment: String
    private let topic: String
    private var tokens: [UnyriPushProvider: String] = [:]
    // Revocation is synchronous even when durable metadata cannot be written.
    // A fresh durable intent is the only way to clear the receive fence.
    private var receiveRevocations = Set<String>()
    private var invalidatedProviders = Set<UnyriPushProvider>()
    private var desired: [UnyriPushProvider: Intent] = [:]
    private var workers: [UnyriPushProvider: Task<Void, Never>] = [:]
    /// Accounts left by a switch: still signed in, their alert slots stay active.
    private var suspended = Set<String>()
    private var inactiveCleanup: Task<Void, Never>?
    /// Slots whose DELETE a lane is sending right now (the inactive retry skips them).
    private var cleaning = Set<String>()
    private static func cleaningKey(_ binding: UnyriPushBinding) -> String { binding.context.key + "#" + String(binding.revision) }
    /// Nil until notification settings are read for the current session. The alert
    /// slot is never uploaded with a guess: correcting it later takes a 409 rebase
    /// or a DELETE and a new revision, and pushes stop matching meanwhile.
    private var preferences: UnyriPushPreferences?
    private var session: UnyriPushSession?
    private(set) var issue: String?
    var changed: (() -> Void)?
    var isWorking: Bool { !workers.isEmpty || inactiveCleanup != nil }
    func isRegistered(_ provider: UnyriPushProvider) -> Bool { desired[provider]?.uploaded == true }

    init(ledger: UnyriPushIntentLedger, environment: String, topic: String,
         current: @escaping () -> UnyriPushSession?, access: @escaping () async throws -> String,
         register: @escaping (UnyriPushPut, String, UnyriPushSession) async throws -> UnyriPushReceipt,
         remove: @escaping (UnyriPushBinding, String, UnyriPushSession) async throws -> Void,
         inactiveAccess: @escaping (UnyriPushScope) async throws -> String = { _ in throw UnyriPushAPIError.invalid }) {
        self.ledger = ledger; self.environment = environment; self.topic = topic
        self.current = current; self.access = access; self.register = register; self.remove = remove
        self.inactiveAccess = inactiveAccess
    }

    private static func key(_ scope: UnyriPushScope) -> String {
        scope.origin + "|" + scope.user.uuidString.lowercased() + "|" + scope.device.uuidString.lowercased()
    }
    func isSuspended(_ scope: UnyriPushScope) -> Bool { suspended.contains(Self.key(scope)) }

    func updateSession() {
        let next = current()
        if let next { suspended.remove(Self.key(next.scope)) }
        guard next != session else { retry(); return }
        preferences = nil // Read again for this session (`updatePreferences`).
        if let old = session { retire(scope: old.scope) }
        session = next
        guard next != nil else { changed?(); return }
        for provider in UnyriPushProvider.allCases { replace(provider, freshOS: false) }
        retry() // Finish persisted invalidation cleanup only under its matching session.
    }

    func updatePreferences(_ value: UnyriPushPreferences) {
        guard preferences != value else { return }
        preferences = value
        replace(.apns, freshOS: false) // Message switches never disable VoIP.
    }

    func receivedToken(_ data: Data, provider: UnyriPushProvider) {
        guard !data.isEmpty, data.count <= 2048 else { issue = "invalid_token"; changed?(); return }
        tokens[provider] = data.map { String(format: "%02x", $0) }.joined()
        replace(provider, freshOS: true)
    }

    func invalidated(_ provider: UnyriPushProvider) {
        tokens[provider] = nil; invalidatedProviders.insert(provider)
        do { try ledger.retire(provider: provider) } catch { issue = "intent_storage" }
        var old = desired.removeValue(forKey: provider)
        // A cold process may receive invalidation before a token callback and
        // therefore have no desired intent despite a persisted active slot.
        if old == nil, let owner = session ?? current(),
           let saved = ledger.binding(for: UnyriPushContext(scope: owner.scope, provider: provider)) {
            old = Intent(binding: saved, session: owner, token: "", preferences: nil)
        }
        if let old {
            receiveRevocations.insert(old.binding.context.key)
            do { try ledger.retire(old.binding) } catch { issue = "intent_storage" }
            // The running lane retains its old snapshot through completion.
            if workers[provider] == nil { cleanupOnly(old, provider: provider) }
        }
        changed?()
    }

    func retire(scope: UnyriPushScope) {
        suspended.remove(Self.key(scope)) // Signed out (or removed): nothing is kept for it.
        for provider in UnyriPushProvider.allCases {
            receiveRevocations.insert(UnyriPushContext(scope: scope, provider: provider).key)
        }
        do { try ledger.retire(scope: scope) } catch { issue = "intent_storage" }
        for provider in UnyriPushProvider.allCases where desired[provider]?.session.scope == scope {
            if let old = desired.removeValue(forKey: provider), workers[provider] == nil { cleanupOnly(old, provider: provider) }
        }
        changed?()
    }

    /// Account switch: the leaving account stays signed in, so its server
    /// registration and active alert slot are kept (its alerts stay routable
    /// to it). This lane only stops serving it; an upload of that account still
    /// in flight completes or fails without retiring its alert slot.
    func suspend(scope: UnyriPushScope) {
        suspended.insert(Self.key(scope))
        for provider in UnyriPushProvider.allCases where desired[provider]?.session.scope == scope {
            desired.removeValue(forKey: provider)
        }
        if session?.scope == scope { session = nil }
        changed?()
    }

    /// Before an account switch, while `scope` is still current: withdraw its
    /// VoIP registration (an inactive account cannot answer calls in this
    /// version). The DELETE uses that account's own credentials even when the
    /// switch starts first; one that cannot be sent stays a durable pending
    /// cleanup (retired slot) that `retry` sends with the inactive account's
    /// session. Meanwhile its pushes are reported to CallKit and ended like any
    /// unmatched VoIP push.
    func retireVoIP(for scope: UnyriPushScope) async {
        let context = UnyriPushContext(scope: scope, provider: .voip)
        let original = ledger.binding(for: context)
        let leaving = current()
        receiveRevocations.insert(context.key)
        if desired[.voip]?.session.scope == scope { desired.removeValue(forKey: .voip) }
        changed?()
        // A running PUT finishes and cleans itself up conditionally.
        if let running = workers[.voip] { await running.value }
        // Only the slot seen at the start: a restoreVoIP meanwhile owns a newer one.
        guard let leaving, leaving.scope == scope, let saved = ledger.binding(for: context),
              saved.id == original?.id, saved.revision == original?.revision,
              saved.active || saved.cleanupPending,
              current() == leaving || isSuspended(scope) else { return }
        await conditionalCleanup(Intent(binding: saved, session: leaving, token: "", preferences: nil))
        changed?()
    }

    /// Lets an in-flight alert PUT of the leaving account finish while it is
    /// still current, so `suspend` does not find it mid-flight and retire it.
    func settleAlerts() async {
        if let running = workers[.apns] { await running.value }
    }

    /// An account switch that did not happen: the still-current account gets
    /// its VoIP registration back (a fresh revision; the old one was withdrawn).
    func restoreVoIP() {
        replace(.voip, freshOS: false)
    }

    func binding(for payload: UnyriPushPayload, origin: String) -> UnyriPushBinding? {
        guard !invalidatedProviders.contains(payload.provider), let saved = ledger.matches(payload, origin: origin),
              !receiveRevocations.contains(saved.context.key) else { return nil }
        return saved
    }

    func retry() {
        for provider in UnyriPushProvider.allCases {
            if workers[provider] == nil, desired[provider] == nil, let session = current(),
               let pending = ledger.binding(for: UnyriPushContext(scope: session.scope, provider: provider)),
               !pending.active, pending.cleanupPending {
                cleanupOnly(Intent(binding: pending, session: session, token: "", preferences: nil), provider: provider)
            } else { start(provider) }
        }
        retryInactiveCleanups()
    }

    /// Withdrawals left pending for a signed-in account that is not current (a
    /// switch started before its VoIP DELETE could be sent). Sent with that
    /// account's own session; any other scope (signed out, removed) is skipped,
    /// its server logout retires its registrations.
    private func retryInactiveCleanups() {
        guard inactiveCleanup == nil else { return }
        let active = current()?.scope
        let pending = ledger.pendingCleanups().filter { $0.context.scope != active }
        guard !pending.isEmpty else { return }
        inactiveCleanup = Task { [weak self] in
            guard let self else { return }
            for binding in pending {
                guard self.stillPending(binding), let bearer = try? await self.inactiveAccess(binding.context.scope),
                      self.stillPending(binding) else { continue }
                do {
                    try await self.remove(binding, bearer, UnyriPushSession(scope: binding.context.scope, generation: 0))
                    try self.ledger.cleaned(binding)
                } catch { self.issue = "registration_cleanup_pending" }
            }
            self.inactiveCleanup = nil
            self.changed?()
        }
    }

    /// Unchanged since it was listed, and its account is still not the current one.
    private func stillPending(_ binding: UnyriPushBinding) -> Bool {
        guard let saved = ledger.binding(for: binding.context) else { return false }
        return saved.id == binding.id && saved.revision == binding.revision && !saved.active && saved.cleanupPending &&
            current()?.scope != binding.context.scope && !cleaning.contains(Self.cleaningKey(binding))
    }

    private func replace(_ provider: UnyriPushProvider, freshOS: Bool) {
        guard let session = current(), session == self.session, session.scope.valid,
              let token = tokens[provider], ["production", "sandbox"].contains(environment), !topic.isEmpty else { return }
        // The alert slot waits for the settings read; `updatePreferences` resumes it.
        if provider == .apns && preferences == nil { return }
        let prefs = provider == .apns ? preferences : nil
        if let existing = desired[provider], existing.session == session, existing.token == token,
           existing.preferences == prefs, !(existing.retired && freshOS) {
            start(provider); return
        }
        let context = UnyriPushContext(scope: session.scope, provider: provider)
        do {
            // A cold process has no cached token (by design). Try its persisted
            // active binding with the fresh OS token first. An unchanged body
            // is idempotent; a rotated token/body takes the bounded 409 rebase.
            // Blindly rotating here would reject a genuine in-flight cold call.
            let binding: UnyriPushBinding
            if desired[provider] == nil, let saved = ledger.binding(for: context), saved.active,
               !receiveRevocations.contains(context.key), !invalidatedProviders.contains(provider) { binding = saved }
            else { binding = try ledger.advance(context) }
            receiveRevocations.remove(context.key); invalidatedProviders.remove(provider)
            desired[provider] = Intent(binding: binding, session: session, token: token, preferences: prefs)
            issue = nil; changed?(); start(provider)
        } catch { issue = "intent_storage"; changed?() }
    }

    private func current(_ intent: Intent, provider: UnyriPushProvider) -> Bool {
        current() == intent.session && desired[provider]?.binding.id == intent.binding.id &&
            desired[provider]?.binding.revision == intent.binding.revision
    }

    private func conditionalCleanup(_ intent: Intent) async {
        let scope = intent.session.scope
        // An account switch keeps the leaving account's alerts: an upload of it that
        // finishes or fails after the switch began never retires its alert slot
        // (its server revision is kept in the slot's confirmation).
        if intent.binding.context.provider == .apns && isSuspended(scope) { return }
        let marker = Self.cleaningKey(intent.binding)
        cleaning.insert(marker)
        defer { cleaning.remove(marker) }
        do { try ledger.retire(intent.binding) } catch { issue = "intent_storage" }
        // Server logout retires a signed-out session's slots atomically; only a
        // current or suspended (still signed-in) account withdraws its own.
        guard current() == intent.session || isSuspended(scope) else { return }
        do {
            let bearer: String
            if current() == intent.session {
                // Fetched while this account is current: the bearer is its own, so
                // the DELETE may still be sent after a switch begins.
                bearer = try await access()
                guard current() == intent.session || isSuspended(scope) else { return }
            } else {
                bearer = try await inactiveAccess(scope)
                guard isSuspended(scope) else { return } // Signed out, or current again (its own lane).
            }
            try await remove(intent.binding, bearer, intent.session)
            try ledger.cleaned(intent.binding)
        } catch { issue = "registration_cleanup_pending" }
    }

    private func cleanupOnly(_ intent: Intent, provider: UnyriPushProvider) {
        workers[provider] = Task { [weak self] in
            guard let self else { return }
            await self.conditionalCleanup(intent)
            self.workers[provider] = nil
            self.start(provider)
            self.changed?()
        }
    }

    private func start(_ provider: UnyriPushProvider) {
        guard workers[provider] == nil, let intent = desired[provider], !intent.uploaded, !intent.retired,
              current(intent, provider: provider) else { return }
        workers[provider] = Task { [weak self] in
            guard let self else { return }
            await self.run(provider)
            self.workers[provider] = nil
            self.changed?()
        }
    }

    private func run(_ provider: UnyriPushProvider) async {
        while let intent = desired[provider], !intent.uploaded, !intent.retired, current(intent, provider: provider) {
            do {
                let bearer = try await access()
                guard current(intent, provider: provider) else { await conditionalCleanup(intent); continue }
                let value = UnyriPushPut(provider: provider.rawValue, token: intent.token, environment: environment,
                    topic: topic + (provider == .voip ? ".voip" : ""), registrationId: intent.binding.id,
                    revision: intent.binding.revision, preferences: intent.preferences)
                let receipt = try await register(value, bearer, intent.session)
                guard receipt.deviceId == intent.session.scope.device, receipt.provider == provider.rawValue,
                      receipt.registrationId == intent.binding.id, receipt.revision == intent.binding.revision else { throw UnyriPushAPIError.invalid }
                // The server holds this revision now, whatever happens to the session next.
                do { try ledger.confirm(intent.binding) } catch { issue = "intent_storage" }
                guard current(intent, provider: provider) else { await conditionalCleanup(intent); continue }
                desired[provider]?.uploaded = true; issue = nil
            } catch UnyriPushAPIError.stale(let floor) {
                guard current(intent, provider: provider) else { await conditionalCleanup(intent); continue }
                guard !intent.rebased else {
                    receiveRevocations.insert(intent.binding.context.key)
                    desired[provider]?.retired = true; issue = "registration_conflict"; return
                }
                do {
                    let next = try ledger.advance(intent.binding.context, after: floor)
                    var replacement = intent; replacement.binding = next; replacement.rebased = true
                    desired[provider] = replacement
                } catch { issue = "intent_storage"; return }
            } catch UnyriPushAPIError.retired {
                guard current(intent, provider: provider) else { await conditionalCleanup(intent); continue }
                receiveRevocations.insert(intent.binding.context.key)
                desired[provider]?.retired = true; issue = "registration_retired"
                do { try ledger.retire(intent.binding); try ledger.cleaned(intent.binding) }
                catch { issue = "intent_storage" }
                return // Only a fresh OS registration intent can replace this tombstone.
            } catch {
                guard current(intent, provider: provider) else { await conditionalCleanup(intent); continue }
                issue = "registration_pending"
                return // Existing application foreground/recovery explicitly retries.
            }
        }
    }
}
