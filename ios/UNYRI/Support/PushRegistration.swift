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

struct UnyriPushBinding: Codable, Equatable {
    let context: UnyriPushContext
    let id: UUID
    let revision: Int64
    var active: Bool
    var cleanupPending = false
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
            $0.context.provider == payload.provider && $0.id == payload.registrationID && $0.revision == payload.registrationRevision }
    }

    /// Persist the new revision before any HTTP request can be dispatched.
    @discardableResult
    func advance(_ context: UnyriPushContext, after floor: Int64 = 0) throws -> UnyriPushBinding {
        guard context.scope.valid, floor >= 0 else { throw Failure.invalid }
        var candidate = entries
        guard candidate[context.key] != nil || candidate.count < 64 else { throw Failure.full }
        let previous = max(candidate[context.key]?.revision ?? 0, floor)
        guard previous < Int64.max else { throw Failure.overflow }
        let value = UnyriPushBinding(context: context, id: UUID(), revision: previous + 1, active: true)
        candidate[context.key] = value
        try commit(candidate)
        return value
    }

    func retire(_ expected: UnyriPushBinding) throws {
        guard entries[expected.context.key]?.id == expected.id,
              entries[expected.context.key]?.revision == expected.revision else { return }
        if entries[expected.context.key]?.active == false && entries[expected.context.key]?.cleanupPending == true { return }
        var candidate = entries; var value = expected; value.active = false; value.cleanupPending = true
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
    private let environment: String
    private let topic: String
    private var tokens: [UnyriPushProvider: String] = [:]
    // Revocation is synchronous even when durable metadata cannot be written.
    // A fresh durable intent is the only way to clear the receive fence.
    private var receiveRevocations = Set<String>()
    private var invalidatedProviders = Set<UnyriPushProvider>()
    private var desired: [UnyriPushProvider: Intent] = [:]
    private var workers: [UnyriPushProvider: Task<Void, Never>] = [:]
    private var preferences = UnyriPushPreferences(messageAlertEnabled: true, messageSoundEnabled: true, permissionAllowed: false)
    private var session: UnyriPushSession?
    private(set) var issue: String?
    var changed: (() -> Void)?
    var isWorking: Bool { !workers.isEmpty }
    func isRegistered(_ provider: UnyriPushProvider) -> Bool { desired[provider]?.uploaded == true }

    init(ledger: UnyriPushIntentLedger, environment: String, topic: String,
         current: @escaping () -> UnyriPushSession?, access: @escaping () async throws -> String,
         register: @escaping (UnyriPushPut, String, UnyriPushSession) async throws -> UnyriPushReceipt,
         remove: @escaping (UnyriPushBinding, String, UnyriPushSession) async throws -> Void) {
        self.ledger = ledger; self.environment = environment; self.topic = topic
        self.current = current; self.access = access; self.register = register; self.remove = remove
    }

    func updateSession() {
        let next = current()
        guard next != session else { retry(); return }
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
        for provider in UnyriPushProvider.allCases {
            receiveRevocations.insert(UnyriPushContext(scope: scope, provider: provider).key)
        }
        do { try ledger.retire(scope: scope) } catch { issue = "intent_storage" }
        for provider in UnyriPushProvider.allCases where desired[provider]?.session.scope == scope {
            if let old = desired.removeValue(forKey: provider), workers[provider] == nil { cleanupOnly(old, provider: provider) }
        }
        changed?()
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
    }

    private func replace(_ provider: UnyriPushProvider, freshOS: Bool) {
        guard let session = current(), session == self.session, session.scope.valid,
              let token = tokens[provider], ["production", "sandbox"].contains(environment), !topic.isEmpty else { return }
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
        do { try ledger.retire(intent.binding) } catch { issue = "intent_storage" }
        guard current() == intent.session else { return } // Server logout retires the old session's slots atomically.
        do {
            let bearer = try await access()
            guard current() == intent.session else { return }
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
