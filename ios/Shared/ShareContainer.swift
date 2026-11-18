import Foundation

/// The only extension-readable data is the minimal target snapshot and queued
/// plaintext. No credentials, chat database, or Signal keys are shared.
enum ShareContainer {
    actor Disk {
        func perform<T>(_ body: () throws -> T) throws -> T {
            try Task.checkCancellation()
            return try body()
        }
    }
    /// App writes and purges use one executor. No lock is held across suspension.
    static let disk = Disk()
    /// The container, or nil when this build has no App Group container (the lookup
    /// returned nothing): then nothing was ever stored and erasure has nothing to do.
    /// Sign-in and sign-out must never be blocked on that. Every other failure throws.
    static func rootIfAvailable() throws -> URL? {
        do { return try root() }
        catch let error as CocoaError where error.code == .fileNoSuchFile { return nil }
    }
    static func root() throws -> URL {
        #if os(iOS)
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: UnyriShareStore.group) else {
            throw CocoaError(.fileNoSuchFile)
        }
        return url
        #else
        throw CocoaError(.featureUnsupported)
        #endif
    }
    static func directory(_ url: URL, intermediates: Bool = true) throws {
        #if os(iOS)
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.complete]
        #else
        let attributes: [FileAttributeKey: Any] = [:]
        #endif
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: intermediates, attributes: attributes)
        #if os(iOS) || os(macOS)
        var excluded = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        #endif
    }
    static func write(_ data: Data, to url: URL, backgroundReadable: Bool = false) throws {
        #if os(iOS)
        let options: Data.WritingOptions = backgroundReadable ? [.atomic, .completeFileProtectionUntilFirstUserAuthentication] : [.atomic, .completeFileProtection]
        #else
        let options: Data.WritingOptions = [.atomic]
        #endif
        try data.write(to: url, options: options)
    }
    static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(value)
    }
    static func read(_ url: URL, maximum: Int) throws -> Data {
        guard url.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
        let info = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard info.isRegularFile == true, info.isSymbolicLink != true,
              let size = info.fileSize, size <= maximum else { throw CocoaError(.fileReadCorruptFile) }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        let data = try handle.read(upToCount: maximum + 1) ?? Data()
        guard data.count <= maximum else { throw CocoaError(.fileReadCorruptFile) }
        return data
    }
    static func regularSize(_ url: URL) throws -> Int64 {
        guard url.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
        let info = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard info.isRegularFile == true, info.isSymbolicLink != true, let size = info.fileSize, size >= 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return Int64(size)
    }
    static func children(_ url: URL) throws -> [URL] {
        do { return try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: [.isSymbolicLinkKey, .isDirectoryKey, .creationDateKey]) }
        catch let error as CocoaError where error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile { return [] }
    }
    static func isDirectory(_ url: URL) -> Bool {
        guard let info = try? url.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey]) else { return false }
        return info.isDirectory == true && info.isSymbolicLink != true
    }
    static func targets(_ root: URL) -> URL { root.appendingPathComponent("ShareTargets/v1", isDirectory: true) }
    static func prepareTargets(root: URL) throws {
        try directory(root.appendingPathComponent("ShareTargets", isDirectory: true))
        try directory(targets(root))
    }
    static func jobs(_ root: URL) -> URL { root.appendingPathComponent("ShareJobs", isDirectory: true) }

    /// A separate single-writer privacy fence: snapshot publication is debounced
    /// and UserDefaults propagation across processes is asynchronous. Enabling
    /// app lock must take effect before the settings action returns.
    static func setRequiresAuthentication(_ enabled: Bool, root: URL) throws {
        let folder = root.appendingPathComponent("SharePrivacy", isDirectory: true)
        let file = folder.appendingPathComponent("app-lock.json")
        let data = try encode(enabled)
        if (try? read(file, maximum: 16)) == data { return }
        try directory(folder)
        try write(data, to: file)
    }
    static func requiresAuthentication(root: URL) -> Bool {
        let file = root.appendingPathComponent("SharePrivacy/app-lock.json")
        if FileManager.default.fileExists(atPath: file.path) {
            guard let data = try? read(file, maximum: 16), let enabled = try? JSONDecoder().decode(Bool.self, from: data) else { return true }
            return enabled
        }
        // Upgrade fallback only; new builds persist the atomic fence above.
        return UserDefaults(suiteName: UnyriShareStore.group)?.bool(forKey: "unyri.share.requiresAuthentication") == true
    }

    // A revocable account parent, as in phase 0. Only the app creates it. Rename
    // on purge closes the commit-vs-logout race without any cross-process lock.
    static func accountJobs(_ binding: UnyriShareStore.AccountBinding, root: URL) -> URL {
        jobs(root).appendingPathComponent(binding.directoryName, isDirectory: true)
    }
    struct SnapshotAuthority: Codable, Equatable {
        var revision = "initial"
        var revokedAccounts: [String] = []
    }
    static let snapshotAuthorityChanged = Notification.Name("UnyriShareSnapshotAuthorityChanged")
    private static func authorityURL(_ root: URL) -> URL { root.appendingPathComponent("SharePrivacy/snapshot-authority.json") }
    /// These operations run on `disk`, including publication's entire body.
    /// Revocations survive termination between shared erasure and registry save.
    static func snapshotAuthority(root: URL) throws -> SnapshotAuthority {
        let file = authorityURL(root)
        guard FileManager.default.fileExists(atPath: file.path) else { return .init() }
        let value = try JSONDecoder().decode(SnapshotAuthority.self, from: read(file, maximum: 256 * 1024))
        guard value.revision == "initial" || UUID(uuidString: value.revision) != nil,
              value.revokedAccounts.count <= 4096,
              Set(value.revokedAccounts).count == value.revokedAccounts.count,
              value.revokedAccounts.allSatisfy(UnyriShareStore.validAccountKey) else { throw CocoaError(.fileReadCorruptFile) }
        return value
    }
    private static func saveAuthority(_ value: SnapshotAuthority, root: URL) throws {
        guard value.revokedAccounts.count <= 4096 else { throw CocoaError(.fileWriteOutOfSpace) }
        try directory(authorityURL(root).deletingLastPathComponent())
        try write(encode(value), to: authorityURL(root))
    }
    private static func revokeSnapshotAccounts(_ keys: [String], root: URL) throws {
        var value = try snapshotAuthority(root: root)
        value.revision = UUID().uuidString
        value.revokedAccounts = Set(value.revokedAccounts).union(keys).sorted()
        try saveAuthority(value, root: root)
    }
    /// Only an authenticated active session may restore its own publication right.
    @discardableResult static func restoreSnapshotAccount(_ key: String, root: URL) throws -> Bool {
        guard UnyriShareStore.validAccountKey(key) else { throw CocoaError(.fileReadCorruptFile) }
        var value = try snapshotAuthority(root: root)
        guard value.revokedAccounts.contains(key) else { return false }
        value.revision = UUID().uuidString
        value.revokedAccounts.removeAll { $0 == key }
        try saveAuthority(value, root: root)
        return true
    }
    static func publishSnapshot<T>(authority: SnapshotAuthority, root: URL, accountKeys: [String], _ body: () throws -> T) throws -> T {
        guard try snapshotAuthority(root: root) == authority,
              accountKeys.allSatisfy({ UnyriShareStore.validAccountKey($0) && !authority.revokedAccounts.contains($0) }) else { throw CancellationError() }
        return try body()
    }
    static func prepare(_ binding: UnyriShareStore.AccountBinding, root: URL) throws {
        guard binding.valid else { throw CocoaError(.fileReadCorruptFile) }
        try directory(jobs(root)); try directory(accountJobs(binding, root: root))
    }
    static func purge(accountKey: String?, root: URL) throws {
        if let accountKey, !UnyriShareStore.validAccountKey(accountKey) { throw CocoaError(.fileReadCorruptFile) }
        let targetRoot = targets(root)
        let indexURL = targetRoot.appendingPathComponent("index.json")
        let revoked = accountKey.map { [$0] } ?? ((try? ShareTargetSnapshot.readIndex(root: root))?.accounts.map(\.key) ?? [])
        // Fence captured publications and exclude these keys from all subsequent
        // snapshot inputs BEFORE deleting metadata, files or account credentials.
        try revokeSnapshotAccounts(revoked, root: root)
        if let accountKey {
            try UnyriShareStore.removeIfPresent(targetRoot.appendingPathComponent(accountKey))
            if FileManager.default.fileExists(atPath: indexURL.path) {
                // Erasure must still succeed if an index was interrupted or
                // damaged. Losing the picker until the next snapshot is safer
                // than retaining a removed account's private display metadata.
                var index = (try? ShareTargetSnapshot.readIndex(root: root)) ?? .init(activeAccountKey: nil, accounts: [])
                index.accounts.removeAll { $0.key == accountKey }
                if index.activeAccountKey == accountKey { index.activeAccountKey = nil }
                try write(encode(index), to: indexURL)
            }
            for parent in try children(jobs(root)) where parent.lastPathComponent.hasPrefix(accountKey + ".") {
                guard isDirectory(parent) else { continue }
                let trash = parent.pathExtension == "purging" ? parent : parent.deletingPathExtension().appendingPathExtension("purging")
                if parent != trash { try FileManager.default.moveItem(at: parent, to: trash) }
                try UnyriShareStore.removeIfPresent(trash)
            }
        } else {
            try UnyriShareStore.removeIfPresent(targetRoot)
            // Keep an empty, non-private index: signed out must not look like
            // "first run after update" and accidentally enable legacy import.
            try prepareTargets(root: root)
            try write(encode(ShareTargetSnapshot.Index(activeAccountKey: nil, accounts: [])), to: indexURL)
            for parent in try children(jobs(root)) {
                let trash = parent.deletingPathExtension().appendingPathExtension("purging")
                if parent != trash { try FileManager.default.moveItem(at: parent, to: trash) }
                try UnyriShareStore.removeIfPresent(trash)
            }
        }
    }
}
