import Foundation
import Security
import Combine
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif
#if canImport(UserNotifications)
import UserNotifications
#endif

// MARK: - Keychain per slot

extension KeychainStore {
    /// Every item name stored under `service`. Absence is an empty list; a
    /// locked or failing Keychain throws and must never look like "empty".
    static func itemNames(service: String) throws -> [String] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let rows = result as? [[String: Any]] else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return try rows.map {
            guard let name = $0[kSecAttrAccount as String] as? String else { throw CocoaError(.fileReadCorruptFile) }
            return name
        }
    }

    static func checkedRemove(_ key: String, service: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: key, kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    /// Removes one added account's whole slot service. Refuses any other service.
    static func removeAccountService(_ service: String) throws {
        guard service.hasPrefix(UnyriAccountSlot.secondaryServicePrefix),
              service.count > UnyriAccountSlot.secondaryServicePrefix.count else { throw CocoaError(.fileWriteInvalidFileName) }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

extension SessionStore {
    /// Installation-wide items stay in the base service for every slot: one
    /// installation id (server device rows are keyed by user + installation)
    /// and one queue of offline logout proofs (each entry carries its origin).
    static let deviceKeys: Set<String> = ["installation_id", "pending_logouts"]

    /// A store bound to one slot for its whole lifetime. A late write from a
    /// retired account's services lands in that account's slot, never in the
    /// account that is active after a switch.
    static func bound(to slot: UnyriAccountSlot) -> SessionStore {
        guard !slot.isPrimary else { return SessionStore() }
        let service = slot.keychainService
        let route: (String) -> String = { deviceKeys.contains($0) ? KeychainStore.defaultService : service }
        return SessionStore(read: { try KeychainStore.checkedValue(forKey: $0, service: route($0)) },
                            write: { KeychainStore.set($0, forKey: $1, service: route($1)) },
                            remove: { KeychainStore.remove($0, service: route($0)) })
    }

    static func active() -> SessionStore { bound(to: UnyriActiveAccountSlot.current) }
}

// MARK: - Registry

/// Non-secret description of a signed-in account. Tokens never appear here.
struct UnyriAccountRecord: Codable, Equatable, Identifiable {
    let slot: String
    var origin: String
    var userId: String
    var username: String
    var displayName: String
    var deviceId: String?
    var id: String { slot }

    var pushScope: UnyriPushScope? {
        guard let user = UUID(uuidString: userId), let device = deviceId.flatMap(UUID.init(uuidString:)) else { return nil }
        let scope = UnyriPushScope(origin: origin, user: user, device: device)
        return scope.valid ? scope : nil
    }
}

struct UnyriAccountRegistryState: Codable, Equatable {
    var version = 1
    var active: String
    var accounts: [UnyriAccountRecord]
    /// Slot reserved for an "Add account" sign-in that has not finished yet.
    var adding: String? = nil
    /// Account to return to when that sign-in is cancelled.
    var returnTo: String? = nil
    /// When that sign-in started (seconds since 1970). Absent in registries
    /// written before it existed, which decode as an old add.
    var addingSince: TimeInterval? = nil
}

/// Keychain-backed list of accounts on this device (base service, survives
/// sign-out of any single account). No registry means the historical
/// single-account mode: the primary slot is active and nothing else exists.
enum UnyriAccountRegistry {
    static let key = "account_registry_v1"
    static let limit = 2

    /// A locked or failing Keychain throws (never "no accounts"). Content this
    /// version cannot use is discarded instead of blocking every launch: the
    /// primary account stays usable and an added account must sign in again.
    static func load() throws -> UnyriAccountRegistryState? {
        guard let raw = try KeychainStore.checkedValue(forKey: key) else { return nil }
        if let state = valid(raw) { return state }
        try save(nil)
        return nil
    }

    private static func valid(_ raw: String) -> UnyriAccountRegistryState? {
        guard raw.utf8.count <= 16_384,
              let state = try? JSONDecoder().decode(UnyriAccountRegistryState.self, from: Data(raw.utf8)) else { return nil }
        let slots = state.accounts.map(\.slot)
        guard state.version == 1, state.accounts.count <= 8, Set(slots).count == slots.count,
              state.accounts.allSatisfy({ UnyriAccountSlot(id: $0.slot).isValid && UUID(uuidString: $0.userId) != nil }),
              UnyriAccountSlot(id: state.active).isValid,
              state.adding.map({ UnyriAccountSlot(id: $0).isValid && !slots.contains($0) }) ?? true else { return nil }
        return state
    }

    static func save(_ state: UnyriAccountRegistryState?) throws {
        guard let state else {
            KeychainStore.remove(key)
            guard try KeychainStore.checkedValue(forKey: key) == nil else { throw SessionStore.PersistenceError.failed }
            return
        }
        let raw = String(decoding: try JSONEncoder().encode(state), as: UTF8.self)
        guard KeychainStore.set(raw, forKey: key), try KeychainStore.checkedValue(forKey: key) == raw else {
            throw SessionStore.PersistenceError.failed
        }
    }
}

// MARK: - Per-account preferences

/// Account settings whose readers use fixed UserDefaults keys (some of them in
/// files other work owns, e.g. ChatScreen's @AppStorage). The unprefixed keys
/// always hold the active account's values; each account's copy is stashed
/// while it is inactive. Device settings (language, theme, app lock, media
/// quality, ...) are never touched. Interrupted swaps self-heal: the owner
/// marker is cleared before restoring, so a half-restored set is never stashed.
enum UnyriAccountPreferences {
    static let keys = ["coco.notifications.preferences.v1", "chatActivityEnabled", "liveDraftEnabled",
                       "shareDiagnostics", "showSavedMessages", "showCallsTab"]
    private static let ownerKey = "unyri.accounts.preferencesOwner"
    private static let unowned = "-"
    private static func stashKey(_ slot: String) -> String { "unyri.accounts.preferences." + slot }

    static func activate(_ slot: UnyriAccountSlot, defaults: UserDefaults = .standard) {
        let owner = defaults.string(forKey: ownerKey) ?? UnyriAccountSlot.primary.id
        guard owner != slot.id else { return }
        if owner != unowned {
            var stash: [String: Any] = [:]
            for key in keys { if let value = defaults.object(forKey: key) { stash[key] = value } }
            defaults.set(stash, forKey: stashKey(owner))
        }
        defaults.set(unowned, forKey: ownerKey)
        let saved = defaults.dictionary(forKey: stashKey(slot.id)) ?? [:]
        for key in keys {
            if let value = saved[key] { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
        }
        defaults.set(slot.id, forKey: ownerKey)
    }

    /// The notification settings of `slot`: live while it is active, its stashed
    /// copy otherwise. Missing or unreadable values are the defaults (all on).
    static func notificationPreferences(for slot: UnyriAccountSlot, defaults: UserDefaults = .standard) -> UnyriNotificationPreferences {
        let key = UnyriNotificationPreferencesStorage.key
        let owner = defaults.string(forKey: ownerKey) ?? UnyriAccountSlot.primary.id
        let data = owner == slot.id ? defaults.data(forKey: key) : defaults.dictionary(forKey: stashKey(slot.id))?[key] as? Data
        return data.flatMap { try? JSONDecoder().decode(UnyriNotificationPreferences.self, from: $0) } ?? UnyriNotificationPreferences()
    }

    /// A removed account's values are never stashed or restored again.
    static func forget(_ slot: UnyriAccountSlot, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: stashKey(slot.id))
        if (defaults.string(forKey: ownerKey) ?? UnyriAccountSlot.primary.id) == slot.id { defaults.set(unowned, forKey: ownerKey) }
    }
}

// MARK: - Erasing one account

/// Erases exactly one account's material while another account stays on the
/// device. LocalAccountCachePurger.erase() remains the path for the last one.
enum UnyriAccountPurger {
    static let deviceKeys: Set<String> = SessionStore.deviceKeys.union([UnyriAccountRegistry.key])

    /// `sweepTemporary` also removes staged temporary media; never while another
    /// account is running (removing an inactive account), whose uploads use it.
    static func erase(_ account: UnyriEndingAccount, remaining: [UnyriAccountRecord], sweepTemporary: Bool) async throws {
        #if os(iOS)
        if let user = account.user {
            let key = UnyriShareStore.accountKey(storageOrigin: account.storageOrigin, userID: user)
            try await ShareContainer.disk.perform { if let root = try ShareContainer.rootIfAvailable() { try ShareContainer.purge(accountKey: key, root: root) } }
            try await UnyriShareStore.inboxIO.purgeAccount(UnyriShareStore.accountKey(storageOrigin: account.storageOrigin, userID: user))
        }
        #endif
        try eraseKeychain(account, remaining: remaining)
        erasePreferences(account)
        URLCache.shared.removeAllCachedResponses()
        var roots = [UnyriRecentFiles.folder(for: account.slot)]
        guard let user = account.user, UUID(uuidString: user) != nil else {
            try await remove(roots)
            try await eraseAvatar(slot: account.slot.id)
            return
        }
        // Kept per account outside its folder: its voice transcripts and paused reminders.
        await VoiceTranscriber.erase(owner: account.storageOrigin + "|" + user)
        UnyriPausedReminderArchive.accountArchive(owner: user).clear(owner: user)
        let manager = FileManager.default
        let support = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        let caches = manager.urls(for: .cachesDirectory, in: .userDomainMask).first
        let server = Data(account.storageOrigin.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
        if let support {
            roots.append(support.appendingPathComponent("COCO", isDirectory: true)
                .appendingPathComponent(server, isDirectory: true).appendingPathComponent(user, isDirectory: true))
        }
        if let caches {
            roots.append(caches.appendingPathComponent("COCOAvatars", isDirectory: true)
                .appendingPathComponent(digest(account.storageOrigin + ":" + user), isDirectory: true))
            roots.append(caches.appendingPathComponent("COCODirectory", isDirectory: true)
                .appendingPathComponent(digest(account.storageOrigin + "|" + user), isDirectory: true))
        }
        if sweepTemporary {
            try await LocalAccountCachePurger.erase(accountRoots: roots, temporaryDirectory: manager.temporaryDirectory)
        } else {
            try await remove(roots)
        }
        if let support {
            let folder = support.appendingPathComponent("COCO", isDirectory: true).appendingPathComponent(server, isDirectory: true)
            if let entries = try? manager.contentsOfDirectory(atPath: folder.path), entries.isEmpty { try? manager.removeItem(at: folder) }
        }
        try await eraseAvatar(slot: account.slot.id)
    }

    private static func eraseAvatar(slot: String) async throws {
        #if os(iOS)
        // A cosmetic-file failure must not prevent erasing secrets and account roots.
        do {
            try await Task.detached(priority: .userInitiated) { try AccountAvatarStore.device.purge(slot: slot) }.value
        } catch {
            await AccountAvatarImage.didChange(slot: slot)
            throw error
        }
        await AccountAvatarImage.didChange(slot: slot)
        #endif
    }

    private static func remove(_ roots: [URL]) async throws {
        try await Task.detached(priority: .userInitiated) {
            for root in roots where FileManager.default.fileExists(atPath: root.path) {
                try FileManager.default.removeItem(at: root)
            }
        }.value
    }

    /// A reserved "Add account" slot whose sign-in never completed.
    static func eraseUnusedSlot(_ slot: UnyriAccountSlot) {
        guard !slot.isPrimary else { return }
        try? KeychainStore.removeAccountService(slot.keychainService)
        erasePreferences(UnyriEndingAccount(slot: slot, user: nil, storageOrigin: ""))
        #if os(iOS)
        AccountAvatarStore.device.invalidate(slot: slot.id)
        Task.detached(priority: .utility) {
            try? AccountAvatarStore.device.purge(slot: slot.id)
            await AccountAvatarImage.didChange(slot: slot.id)
        }
        #endif
    }

    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// "admin-public-pin:<server>|<user>" and its "|pending" twin.
    private static func adminPinOwner(_ name: String) -> String? {
        guard name.hasPrefix("admin-public-pin:") else { return nil }
        var parts = name.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        if parts.count > 2, parts.last == "pending" { parts.removeLast() }
        return parts.count >= 2 ? parts.last?.lowercased() : nil
    }

    static func eraseKeychain(_ account: UnyriEndingAccount, remaining: [UnyriAccountRecord]) throws {
        let base = UnyriAccountSlot.baseService
        let user = account.user?.lowercased()
        let kept = Set(remaining.map { $0.userId.lowercased() })
        if account.slot.isPrimary {
            // Same rule as the single-account purge (everything except device
            // items), but other accounts' admin pins in this service survive.
            for name in try KeychainStore.itemNames(service: base) where !deviceKeys.contains(name) {
                if let owner = adminPinOwner(name), owner != user, kept.contains(owner) { continue }
                try KeychainStore.checkedRemove(name, service: base)
            }
        } else {
            try KeychainStore.removeAccountService(account.slot.keychainService)
            if let user {
                for name in try KeychainStore.itemNames(service: base) where adminPinOwner(name) == user {
                    try KeychainStore.checkedRemove(name, service: base)
                }
            }
        }
        guard let owner = account.user, let user else { return }
        if let installation = try KeychainStore.checkedValue(forKey: "installation_id"), !installation.isEmpty {
            try eraseSignal(user: owner, storageOrigin: account.storageOrigin, installation: installation)
        }
        let history = base + ".history-recovery"
        for name in try KeychainStore.itemNames(service: history)
        where name.hasPrefix("history-v1:") && name.lowercased().hasSuffix(":" + user) {
            try KeychainStore.checkedRemove(name, service: history)
        }
    }

    /// Only this account's namespace (server + user + installation), resolved
    /// through its pin exactly as SignalNamespaceBinding.resolve selected it.
    private static func eraseSignal(user: String, storageOrigin: String, installation: String) throws {
        let keychain = SignalKeychainStore()
        let owner = SignalNamespace.make(userId: user, installationId: installation)
        let canonical = "server." + Data(storageOrigin.utf8).base64EncodedString() + "." + owner
        let pin = "namespace_binding:" + canonical
        var namespaces = [canonical]
        if let data = try keychain.data(forKey: pin), let saved = String(data: data, encoding: .utf8),
           saved == owner { namespaces.append(saved) }
        for key in try keychain.keys() where namespaces.contains(where: { key.hasPrefix($0 + ":") }) {
            try keychain.remove(forKey: key)
        }
        try keychain.remove(forKey: pin)
    }

    static func erasePreferences(_ account: UnyriEndingAccount, defaults: UserDefaults = .standard) {
        let snapshot = defaults.dictionaryRepresentation()
        if !account.slot.isPrimary {
            let prefix = account.slot.defaultsKey("")
            for key in snapshot.keys where key.hasPrefix(prefix) { defaults.removeObject(forKey: key) }
        }
        UnyriAccountPreferences.forget(account.slot, defaults: defaults)
        guard let user = account.user?.lowercased(), !user.isEmpty else { return }
        for (key, value) in snapshot {
            let lower = key.lowercased()
            let owned = (key.hasPrefix("history-auto-v1:") && lower.contains(":" + user + ":")) ||
                (key.hasPrefix("history-auto-server:") && lower.hasSuffix(":" + user)) ||
                (key.hasPrefix("chatPins|") && lower.hasSuffix("|" + user)) ||
                (key.hasPrefix(DirectSendPreference.prefix) && directSendOwner(key) == user) ||
                (key == "openHistoryBackupForAccount" && (value as? String)?.lowercased() == user) ||
                (key == "chatReminderRoute" && ((value as? [String: Any])?["owner"] as? String)?.lowercased() == user)
            if owned { defaults.removeObject(forKey: key) }
        }
    }

    private static func directSendOwner(_ key: String) -> String? {
        guard let data = Data(base64Encoded: String(key.dropFirst(DirectSendPreference.prefix.count))),
              let identity = String(data: data, encoding: .utf8) else { return nil }
        return identity.split(separator: "\n").last.map { String($0).lowercased() }
    }
}

// MARK: - Notifications of one account

enum UnyriAccountNotifications {
    /// Removes pending and delivered notifications that name this account
    /// (remote pushes carry `user_id`, local reminders carry `owner`).
    static func remove(owner: String) {
        #if canImport(UserNotifications)
        let user = owner.lowercased()
        func owns(_ info: [AnyHashable: Any]) -> Bool {
            (info["owner"] as? String)?.lowercased() == user || (info["user_id"] as? String)?.lowercased() == user
        }
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { requests in
            let ids = requests.filter { owns($0.content.userInfo) }.map(\.identifier)
            if !ids.isEmpty { center.removePendingNotificationRequests(withIdentifiers: ids) }
        }
        center.getDeliveredNotifications { notes in
            let ids = notes.filter { owns($0.request.content.userInfo) }.map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
        #endif
    }
}

// MARK: - Account manager

/// Up to two accounts on one device, one active at a time (plan stage 2).
/// The inactive account stays signed in: its Keychain slot, files and server
/// session are untouched, and its message alerts stay registered.
@MainActor
final class UnyriAccountManager: ObservableObject, UnyriAccountSessionHost {
    static let shared = UnyriAccountManager()

    @Published private(set) var records: [UnyriAccountRecord] = []
    @Published private(set) var activeSlot = UnyriAccountSlot.primary
    @Published private(set) var addingSlot: String?
    @Published private(set) var returnSlot: String?
    @Published private(set) var busy = false
    @Published var lastError: String?
    /// Last known unread total of each inactive account (counted when it was
    /// left, plus message pushes received for it since). Not a server value.
    @Published private(set) var unread: [String: Int] = [:]
    /// Inactive accounts whose server session ended (a refresh was definitively
    /// rejected). Nothing of theirs is erased until the person switches to them.
    @Published private(set) var needsSignIn: Set<String> = []

    private var registry: UnyriAccountRegistryState?
    private weak var auth: AuthRepository?
    private var observers = [AnyCancellable]()
    private var pendingRouteSlot: String?
    private var countedMessages: [String] = []
    private var inactiveRefreshes: [String: Task<String, Error>] = [:]
    private var leavingAddForRoute = false
    private var lastInactiveSessionCheck: Date?
    private static let unreadKey = "unyri.accounts.unread"
    private static let needsSignInKey = "unyri.accounts.needsSignIn"

    /// Set by the active account's services: its current unread total.
    var currentUnread: () -> Int = { 0 }

    private init() {
        unread = (UserDefaults.standard.dictionary(forKey: Self.unreadKey) as? [String: Int]) ?? [:]
        needsSignIn = Set(UserDefaults.standard.stringArray(forKey: Self.needsSignInKey) ?? [])
    }

    // MARK: Derived state

    var isMultiAccount: Bool { registry != nil }
    var signedInCount: Int { registry == nil ? 1 : records.count }
    var canAddAccount: Bool { signedInCount < UnyriAccountRegistry.limit && addingSlot == nil }
    var otherAccounts: [UnyriAccountRecord] { records.filter { $0.slot != activeSlot.id } }
    var returnRecord: UnyriAccountRecord? {
        guard let addingSlot, addingSlot == activeSlot.id, let returnSlot else { return nil }
        return records.first { $0.slot == returnSlot }
    }
    var hasOtherSignedInAccount: Bool { !otherAccounts.isEmpty }

    var activeSlotAwaitsSignIn: Bool { addingSlot != nil && addingSlot == activeSlot.id }
    var sharesInstallation: Bool { hasOtherSignedInAccount }

    // MARK: Launch

    func bind(auth: AuthRepository) {
        self.auth = auth
        auth.accounts = self
        observers = [
            auth.$state.receive(on: RunLoop.main).sink { [weak self] _ in self?.performPendingRoute() },
            // An "Add account" sign-in that finished or was left: a waiting route can go.
            $addingSlot.dropFirst().receive(on: RunLoop.main).sink { [weak self] _ in self?.performPendingRoute() },
            NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
                .sink { [weak self] _ in
                    self?.performPendingRoute()
                    self?.renewInactiveSessionsIfDue()
                },
        ]
    }

    /// Checked registry read before any session read. Keychain can outlive an
    /// uninstall while the installation marker cannot: a registry without the
    /// marker belongs to a deleted install, so its added slots are purged and
    /// the primary slot follows the existing fresh-install path in bootstrap.
    func resolveActiveSlot(installationMarker: () throws -> Bool, launch: Bool = false) throws {
        // Never move the active slot under a running session.
        guard auth?.currentUser == nil else { return }
        var state = try UnyriAccountRegistry.load()
        if let stale = state, (try? installationMarker()) == false {
            for slot in Set(stale.accounts.map(\.slot) + [stale.adding].compactMap { $0 }) where slot != UnyriAccountSlot.primary.id {
                try? KeychainStore.removeAccountService(UnyriAccountSlot(id: slot).keychainService)
            }
            try UnyriAccountRegistry.save(nil)
            state = nil
        }
        if var current = state, let adding = current.adding, adding != current.active {
            // An "Add account" whose slot was never activated.
            UnyriAccountPurger.eraseUnusedSlot(UnyriAccountSlot(id: adding))
            current.adding = nil; current.returnTo = nil; current.addingSince = nil
            try UnyriAccountRegistry.save(current)
            state = current
        }
        if launch, var current = state, let adding = current.adding, adding == current.active, let back = current.returnTo,
           current.accounts.contains(where: { $0.slot == back }), Self.addIsAbandoned(since: current.addingSince),
           Self.slotHoldsNoSession(UnyriAccountSlot(id: adding)) {
            // The app was closed on the "Add account" sign-in long ago: open the
            // account it came from, as Cancel would. A recent add stays on its
            // sign-in (iOS may have ended the app while the person fetched a
            // password in another app). A slot that already holds a session (or
            // cannot be read yet) is left alone.
            UnyriAccountPurger.eraseUnusedSlot(UnyriAccountSlot(id: adding))
            current.adding = nil; current.returnTo = nil; current.addingSince = nil; current.active = back
            state = normalized(current)
            try UnyriAccountRegistry.save(state)
        }
        if var current = state, current.adding != current.active, !current.accounts.contains(where: { $0.slot == current.active }) {
            guard let first = current.accounts.first else { throw CocoaError(.fileReadCorruptFile) }
            current.active = first.slot
            try UnyriAccountRegistry.save(current)
            state = current
        }
        apply(state)
    }

    /// How long an unfinished "Add account" sign-in survives the app being closed.
    static let addAccountResumeWindow: TimeInterval = 10 * 60

    /// Older than the window, from a registry without the start time, or dated in
    /// the future (the clock was changed): the person is not in the middle of it.
    static func addIsAbandoned(since: TimeInterval?, now: Date = Date()) -> Bool {
        guard let since else { return true }
        let age = now.timeIntervalSince1970 - since
        return !(0...addAccountResumeWindow).contains(age)
    }

    /// Checked read: true only when the slot's Keychain is readable and empty.
    private static func slotHoldsNoSession(_ slot: UnyriAccountSlot) -> Bool {
        do { return try SessionStore.bound(to: slot).checkedServerContinuityUserID() == nil } catch { return false }
    }

    private func apply(_ state: UnyriAccountRegistryState?) {
        registry = state
        if records != (state?.accounts ?? []) { records = state?.accounts ?? [] }
        if addingSlot != state?.adding { addingSlot = state?.adding }
        if returnSlot != state?.returnTo { returnSlot = state?.returnTo }
        let slot = state.map { UnyriAccountSlot(id: $0.active) } ?? .primary
        UnyriActiveAccountSlot.set(slot)
        if activeSlot != slot {
            activeSlot = slot
            // Shared HTTP cache: nothing fetched for one account is ever served to the other.
            URLCache.shared.removeAllCachedResponses()
        }
        UnyriAccountPreferences.activate(slot)
        UnyriNotificationPreferencesStore.shared.reload()
        let known = Set(records.map(\.slot))
        if unread.keys.contains(where: { !known.contains($0) }) { unread = unread.filter { known.contains($0.key) }; saveUnread() }
        if !needsSignIn.isSubset(of: known) { setNeedsSignIn(needsSignIn.intersection(known)) }
    }

    /// One registry holding a single primary account is the legacy mode.
    private func normalized(_ state: UnyriAccountRegistryState?) -> UnyriAccountRegistryState? {
        guard let state else { return nil }
        if state.adding == nil, state.accounts.count == 1, state.accounts[0].slot == UnyriAccountSlot.primary.id,
           state.active == UnyriAccountSlot.primary.id { return nil }
        return state
    }

    private func commit(_ state: UnyriAccountRegistryState?) throws {
        let value = normalized(state)
        try UnyriAccountRegistry.save(value)
        apply(value)
    }

    // MARK: Session host

    private func sameOrigin(_ raw: String, _ url: URL) -> Bool {
        guard let left = try? AppConfig.normalizedServerURL(raw), let right = try? AppConfig.normalizedServerURL(url.absoluteString) else {
            return raw == url.absoluteString
        }
        return left == right
    }

    func alreadySignedIn(username: String, origin: URL) -> Bool {
        let name = (username.hasPrefix("@") ? String(username.dropFirst()) : username).lowercased()
        return otherAccounts.contains { $0.username.lowercased() == name && sameOrigin($0.origin, origin) }
    }

    func admits(user: String, origin: URL) -> Bool {
        !otherAccounts.contains { $0.userId.lowercased() == user.lowercased() && sameOrigin($0.origin, origin) }
    }

    func endingAccount(user: String?) -> UnyriEndingAccount? {
        guard registry != nil else { return nil }
        let owner = user ?? records.first(where: { $0.slot == activeSlot.id })?.userId
        return UnyriEndingAccount(slot: activeSlot, user: owner, storageOrigin: AppConfig.storageOrigin)
    }

    func sessionStore(for slot: UnyriAccountSlot) -> SessionStore { SessionStore.bound(to: slot) }

    func bearerIssued(_ token: String, origin: URL) { UnyriBearerOrigins.record(token, origin: origin) }

    func finishSignOut(_ ending: UnyriEndingAccount) async throws -> UnyriAccountSlot? {
        guard var state = registry else {
            try await LocalAccountCachePurger.erase()
            return nil
        }
        let remaining = state.accounts.filter { $0.slot != ending.slot.id }
        if let user = ending.user { UnyriAccountNotifications.remove(owner: user) }
        if ending.slot.id == state.adding, let back = state.returnTo, remaining.contains(where: { $0.slot == back }) {
            // The new account signed out before it was finished: return to the previous one.
            try await UnyriAccountPurger.erase(ending, remaining: remaining, sweepTemporary: true)
            state.accounts = remaining; state.adding = nil; state.returnTo = nil; state.addingSince = nil; state.active = back
            try commit(state)
            return UnyriAccountSlot(id: back)
        }
        if let next = remaining.first {
            try await UnyriAccountPurger.erase(ending, remaining: remaining, sweepTemporary: true)
            state.accounts = remaining; state.adding = nil; state.returnTo = nil; state.addingSince = nil; state.active = next.slot
            unread[ending.slot.id] = nil; unread[next.slot] = nil; saveUnread()
            try commit(state)
            return UnyriAccountSlot(id: next.slot)
        }
        // The last account: the historical full erase, plus this slot's own service.
        #if canImport(UserNotifications)
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        #endif
        try await LocalAccountCachePurger.erase()
        if !ending.slot.isPrimary {
            try KeychainStore.removeAccountService(ending.slot.keychainService)
            UnyriAccountPurger.erasePreferences(UnyriEndingAccount(slot: ending.slot, user: nil, storageOrigin: ""))
        }
        for slot in [state.adding].compactMap({ $0 }) where slot != ending.slot.id {
            UnyriAccountPurger.eraseUnusedSlot(UnyriAccountSlot(id: slot))
        }
        unread = [:]; saveUnread()
        try commit(nil)
        return nil
    }

    // MARK: Activation

    /// Called when the active account's services are up (its workspace exists).
    /// Finishes an "Add account" sign-in and refreshes the stored display data.
    func activated(auth: AuthRepository, profile: UserProfile?) {
        if needsSignIn.contains(activeSlot.id) { setNeedsSignIn(needsSignIn.subtracting([activeSlot.id])) }
        guard var state = registry, let user = auth.currentUser else { return }
        let ownProfile = profile.flatMap { $0.id.lowercased() == user.id.lowercased() ? $0 : nil }
        let record = UnyriAccountRecord(slot: activeSlot.id, origin: auth.authenticatedServerURL.absoluteString, userId: user.id,
            username: ownProfile?.username ?? user.username, displayName: ownProfile?.displayName ?? user.displayName,
            deviceId: auth.currentDeviceId?.uuidString.lowercased())
        if state.adding == activeSlot.id {
            guard admits(user: user.id, origin: auth.authenticatedServerURL), state.accounts.count < UnyriAccountRegistry.limit else { return }
            if UserDefaults.standard.string(forKey: AppConfig.serverPreferenceKey) == nil {
                AppConfig.saveServer(auth.authenticatedServerURL)
            }
            state.accounts.append(record); state.adding = nil; state.returnTo = nil; state.addingSince = nil
        } else if let index = state.accounts.firstIndex(where: { $0.slot == activeSlot.id }) {
            // A slot is never rebound to a different person.
            guard state.accounts[index] != record, state.accounts[index].userId.lowercased() == record.userId.lowercased() else { return }
            state.accounts[index] = record
        } else { return }
        do { try commit(state) } catch { /* Display data only; retried on the next activation. */ }
    }

    // MARK: User actions

    private func blockedByMedia() -> Bool {
        guard auth?.currentUser != nil else { return false }
        return NonCallCaptureOwnership.callsBusy() || ConferenceMediaOwnership.isBusy
    }

    private func rememberLeavingUnread() {
        unread[activeSlot.id] = max(0, currentUnread())
        saveUnread()
    }

    private func saveUnread() { UserDefaults.standard.set(unread, forKey: Self.unreadKey) }

    func switchTo(_ record: UnyriAccountRecord) async {
        guard let auth, !busy, record.slot != activeSlot.id, addingSlot == nil,
              records.contains(where: { $0.slot == record.slot }), auth.currentUser != nil else { return }
        guard !blockedByMedia() else { lastError = UnyriL10n.string("Finish the call before switching accounts."); return }
        busy = true; lastError = nil
        defer { busy = false }
        rememberLeavingUnread()
        let generation = auth.sessionGeneration
        await UnyriPushNotificationCoordinator.shared.prepareAccountSwitch()
        // Nothing switched (e.g. a call arrived meanwhile): calls must keep ringing here.
        defer { if auth.sessionGeneration == generation { UnyriPushNotificationCoordinator.shared.restoreAfterAbortedSwitch() } }
        guard auth.currentUser != nil, !blockedByMedia() else { return }
        do {
            try await auth.switchAccount { [self] in
                guard var state = registry, state.accounts.contains(where: { $0.slot == record.slot }) else { throw CancellationError() }
                state.active = record.slot
                try commit(state)
            }
            unread[record.slot] = nil; saveUnread()
        } catch { lastError = UnyriL10n.string("Could not switch accounts. Try again.") }
    }

    /// Reserves a new slot and opens the ordinary sign-in/registration flow in
    /// it. The current account stays signed in and is restored by Cancel.
    func beginAddAccount() async {
        guard let auth, !busy, canAddAccount, let user = auth.currentUser else { return }
        guard !blockedByMedia() else { lastError = UnyriL10n.string("Finish the call before switching accounts."); return }
        busy = true; lastError = nil
        defer { busy = false }
        let slot = UnyriAccountSlot.makeSecondary()
        let current = UnyriAccountRecord(slot: activeSlot.id, origin: auth.authenticatedServerURL.absoluteString, userId: user.id,
            username: user.username, displayName: user.displayName, deviceId: auth.currentDeviceId?.uuidString.lowercased())
        var state = registry ?? UnyriAccountRegistryState(active: activeSlot.id, accounts: [current])
        if !state.accounts.contains(where: { $0.slot == activeSlot.id }) { state.accounts.append(current) }
        state.adding = slot.id; state.returnTo = activeSlot.id; state.addingSince = Date().timeIntervalSince1970
        do { try UnyriAccountRegistry.save(state); apply(state) } catch {
            lastError = UnyriL10n.string("Could not switch accounts. Try again.")
            return
        }
        rememberLeavingUnread()
        let generation = auth.sessionGeneration
        await UnyriPushNotificationCoordinator.shared.prepareAccountSwitch()
        defer { if auth.sessionGeneration == generation { UnyriPushNotificationCoordinator.shared.restoreAfterAbortedSwitch() } }
        do {
            try await auth.switchAccount { [self] in
                guard var next = registry, next.adding == slot.id else { throw CancellationError() }
                next.active = slot.id
                try commit(next)
            }
        } catch {
            lastError = UnyriL10n.string("Could not switch accounts. Try again.")
            if var undo = registry, undo.adding == slot.id, undo.active != slot.id {
                undo.adding = nil; undo.returnTo = nil; undo.addingSince = nil
                try? commit(undo)
                UnyriAccountPurger.eraseUnusedSlot(slot)
            }
        }
    }

    /// Leaves the unfinished sign-in and restores the previous account.
    func cancelAddAccount() async {
        guard let auth, !busy, let adding = addingSlot, adding == activeSlot.id, let back = returnSlot,
              records.contains(where: { $0.slot == back }) else { return }
        guard case .loggedOut = auth.state else { return }
        busy = true; lastError = nil
        defer { busy = false }
        do {
            try await auth.switchAccount { [self] in
                guard var state = registry, state.adding == adding else { throw CancellationError() }
                UnyriAccountPurger.eraseUnusedSlot(UnyriAccountSlot(id: adding))
                state.adding = nil; state.returnTo = nil; state.addingSince = nil; state.active = back
                try commit(state)
            }
        } catch { lastError = UnyriL10n.string("Could not switch accounts. Try again.") }
    }

    /// Signs the inactive account out of this device only: its server session
    /// is revoked through the offline logout queue and its local data erased.
    func removeInactive(_ record: UnyriAccountRecord) async {
        guard let auth, !busy, record.slot != activeSlot.id, var state = registry,
              state.accounts.contains(where: { $0.slot == record.slot }) else { return }
        busy = true; lastError = nil
        defer { busy = false }
        let slot = UnyriAccountSlot(id: record.slot)
        let store = SessionStore.bound(to: slot)
        do {
            // A locked Keychain must not look like "no session": then nothing is erased.
            try store.requireReadableSession()
            if let refresh = store.refreshToken(), let origin = URL(string: record.origin) {
                try store.enqueueLogout(origin: origin, refreshToken: refresh)
            }
        } catch {
            lastError = UnyriL10n.string("Could not remove the account from this iPhone. Try again.")
            return
        }
        if let scope = record.pushScope { UnyriPushNotificationCoordinator.shared.retireInactive(scope) }
        UnyriAccountNotifications.remove(owner: record.userId)
        let remaining = state.accounts.filter { $0.slot != record.slot }
        do {
            try await UnyriAccountPurger.erase(UnyriEndingAccount(slot: slot, user: record.userId,
                storageOrigin: AppConfig.storageOrigin(for: slot)), remaining: remaining, sweepTemporary: false)
            state.accounts = remaining
            unread[record.slot] = nil; saveUnread()
            try commit(state)
        } catch {
            lastError = UnyriL10n.string("Could not remove the account from this iPhone. Try again.")
        }
        Task { await auth.retryPendingLogouts() }
    }

    // MARK: Notifications of the inactive account

    func account(owner: String) -> UnyriAccountRecord? {
        records.first { $0.userId.lowercased() == owner.lowercased() }
    }

    func noteInactiveMessage(slot: String, message: String) {
        guard slot != activeSlot.id, records.contains(where: { $0.slot == slot }), !countedMessages.contains(message) else { return }
        countedMessages.append(message)
        if countedMessages.count > 256 { countedMessages.removeFirst(countedMessages.count - 256) }
        unread[slot, default: 0] += 1
        saveUnread()
    }

    /// Foreground only: generic in-app banner titled with the inactive account.
    func presentInactiveMessage(_ record: UnyriAccountRecord, chat: String, message: String) {
        noteInactiveMessage(slot: record.slot, message: message)
        // That account's own settings: with its message alerts off the message is
        // only counted (the switcher shows it), nothing is posted.
        let own = UnyriAccountPreferences.notificationPreferences(for: UnyriAccountSlot(id: record.slot))
        guard own.allowsMessages else { return }
        ForegroundMessageBannerController.presentInactive(owner: record.userId, chat: chat, message: message,
            name: record.displayName, preferences: own)
    }

    /// A tapped notification of a signed-in account opens that chat, switching
    /// accounts first when needed (the route carries the owner, so the other
    /// account's chat list ignores it).
    func openFromNotification(owner: String, chat: String, message: String) {
        guard let record = account(owner: owner), UUID(uuidString: chat) != nil else { return }
        UserDefaults.standard.set(["owner": record.userId, "chat": chat.lowercased(), "message": message.lowercased()], forKey: "chatReminderRoute")
        if record.slot == activeSlot.id {
            NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil)
            return
        }
        pendingRouteSlot = record.slot
        performPendingRoute()
    }

    private func performPendingRoute() {
        guard let slot = pendingRouteSlot, let auth, !busy, UIApplication.shared.applicationState == .active else { return }
        if activeSlotAwaitsSignIn {
            // The "Add account" sign-in is open (no account is signed in in this slot):
            // leave it first, as Back does, then route to the account it came from.
            guard !leavingAddForRoute, case .loggedOut = auth.state else { return }
            leavingAddForRoute = true
            Task { @MainActor [weak self] in
                guard let self else { return }
                await self.cancelAddAccount()
                self.leavingAddForRoute = false
                // Could not leave it: the route is dropped instead of retried forever.
                if self.activeSlotAwaitsSignIn { self.pendingRouteSlot = nil; return }
                self.performPendingRoute()
            }
            return
        }
        guard auth.currentUser != nil else { return }
        pendingRouteSlot = nil
        guard slot != activeSlot.id, let record = records.first(where: { $0.slot == slot }) else {
            // Already that account (it was reopened by leaving the add): its chat list takes the route.
            if slot == activeSlot.id { NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil) }
            return
        }
        Task { await switchTo(record) }
    }

    // MARK: Sessions of the inactive account

    /// An inactive account's refresh token is never renewed by its own session, and
    /// the server lets it expire (10 to 30 days, by configuration); opening the
    /// account after that erased it. The lifetime is not sent to the app: 3 days is
    /// about a third of the shortest one in use.
    static let inactiveRenewalAge: TimeInterval = 3 * 24 * 3600
    private static let inactiveCheckInterval: TimeInterval = 4 * 3600

    /// When the app becomes active, at most every few hours: renews the session of
    /// each inactive account whose tokens were received more than
    /// `inactiveRenewalAge` ago. A failure is retried at a later check.
    func renewInactiveSessionsIfDue(now: Date = Date()) {
        guard !busy else { return }
        if let last = lastInactiveSessionCheck, last <= now, now.timeIntervalSince(last) < Self.inactiveCheckInterval { return }
        let candidates = otherAccounts.filter { !needsSignIn.contains($0.slot) }
        guard !candidates.isEmpty else { return }
        lastInactiveSessionCheck = now
        for record in candidates {
            Task { @MainActor [weak self] in
                let store = SessionStore.bound(to: UnyriAccountSlot(id: record.slot))
                guard let self, let tokens = try? await store.readTokensAsync(), tokens.refresh != nil else { return }
                // Unknown age, or received "in the future" (clock changed): renew.
                if let received = SessionStore.receivedAt(tokens), received <= now.addingTimeInterval(300),
                   now.timeIntervalSince(received) < Self.inactiveRenewalAge { return }
                _ = try? await self.inactiveAccessToken(for: record, renew: true)
            }
        }
    }

    /// A bearer of the signed-in, inactive account that owns this push scope (its
    /// registration cleanup). Throws for any other scope.
    func inactiveAccessToken(for scope: UnyriPushScope) async throws -> String {
        guard let record = otherAccounts.first(where: { $0.pushScope == scope }), !needsSignIn.contains(record.slot) else {
            throw APIError.missingSession
        }
        return try await inactiveAccessToken(for: record)
    }

    /// A usable access token of an inactive account, refreshed when needed (or when
    /// `renew`) through the same endpoint, request id and storage as its own session
    /// would use, with its own slot and server. A definitive rejection only marks
    /// the account as needing sign-in: nothing of it is erased here.
    func inactiveAccessToken(for record: UnyriAccountRecord, renew: Bool = false) async throws -> String {
        if let running = inactiveRefreshes[record.slot] { return try await running.value }
        let task = Task { @MainActor [weak self] () throws -> String in
            guard let self else { throw CancellationError() }
            return try await self.refreshInactive(record, renew: renew)
        }
        inactiveRefreshes[record.slot] = task
        defer { if inactiveRefreshes[record.slot] == task { inactiveRefreshes[record.slot] = nil } }
        return try await task.value
    }

    private func refreshInactive(_ record: UnyriAccountRecord, renew: Bool) async throws -> String {
        let slot = UnyriAccountSlot(id: record.slot)
        guard slot != activeSlot, records.contains(where: { $0.slot == record.slot }),
              let origin = try? AppConfig.normalizedServerURL(record.origin) else { throw APIError.missingSession }
        let store = SessionStore.bound(to: slot)
        let tokens = try await store.readTokensAsync()
        if !renew, let access = tokens.access, let expires = tokens.expiresAt, Date() < expires.addingTimeInterval(-30) {
            UnyriBearerOrigins.record(access, origin: origin)
            return access
        }
        guard let refresh = tokens.refresh else { throw APIError.missingSession }
        let response: RefreshResponse
        do {
            response = try await AuthAPI(client: APIClient(baseURL: origin, preventRedirects: true))
                .refresh(RefreshRequest(refreshToken: refresh, requestId: try store.refreshRequestId()))
        } catch let error as APIError where AuthRepository.isTerminalAuthFailure(error) {
            if slot != activeSlot, records.contains(where: { $0.slot == record.slot }) {
                setNeedsSignIn(needsSignIn.union([record.slot]))
            }
            throw error
        }
        // Saved only while the account is still inactive and over the exact tokens it
        // renewed. Once it is active its own session owns the slot: that session may
        // already hold the old token, and it retries it with the same persisted
        // request id (saving here would clear that id, and a retry with a new one
        // looks like token reuse to the server, which revokes the session).
        guard slot != activeSlot, records.contains(where: { $0.slot == record.slot }),
              store.refreshToken() == refresh else { throw CancellationError() }
        try store.saveTokens(accessToken: response.accessToken, accessTokenExpiresAt: response.accessTokenExpiresAt,
                             refreshToken: response.refreshToken)
        UnyriBearerOrigins.record(response.accessToken, origin: origin)
        return response.accessToken
    }

    private func setNeedsSignIn(_ value: Set<String>) {
        guard needsSignIn != value else { return }
        needsSignIn = value
        UserDefaults.standard.set(value.sorted(), forKey: Self.needsSignInKey)
    }
}
