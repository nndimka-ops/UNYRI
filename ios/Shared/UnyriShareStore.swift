import Foundation
import Dispatch
#if canImport(CryptoKit)
import CryptoKit
#endif
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

/// Immutable batches become visible only after the final directory rename.
/// No credentials, chat database, or Signal keys are shared with the extension.
enum UnyriShareStore {
    // Both app and extension receive this value from the same build setting.
    // Missing keys preserve older bundles; an invalid supplied value does not
    // silently select another App Group and its container.
    static let groupIdentifier: String = {
        guard let configured = Bundle.main.object(forInfoDictionaryKey: "UNYRI_APP_GROUP_IDENTIFIER") ?? Bundle.main.object(forInfoDictionaryKey: "COCO_APP_GROUP_IDENTIFIER") else {
            return "group.com.messenger.coco.share"
        }
        return configured as? String ?? ""
    }()
    // Preserve the existing share-extension caller API.
    static var group: String { groupIdentifier }
    static let activeAccountKeyPreference = "unyri.share.activeAccountKey.v1"
    private static let activeBindingPreference = "unyri.share.activeBinding.v1"
    struct AccountBinding: Codable, Equatable, Sendable {
        let accountKey: String
        let id: String
        var directoryName: String { accountKey + "." + id + ".account" }
        var valid: Bool { UnyriShareStore.validAccountKey(accountKey) && UUID(uuidString: id) != nil && id == id.lowercased() }
    }
    #if canImport(CryptoKit)
    static func accountKey(storageOrigin: String, userID: String) -> String {
        SHA256.hash(data: Data("unyri.share.v1|\(storageOrigin)|\(userID)".utf8))
            .prefix(16).map { String(format: "%02x", $0) }.joined()
    }
    #endif
    static func validAccountKey(_ key: String) -> Bool {
        key.utf8.count == 32 && key.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
    static func publishActiveBinding(_ binding: AccountBinding?) {
        let defaults = UserDefaults(suiteName: group)
        // One serialized value prevents a key from being paired with another lease.
        // Only the app creates account directories. The extension never recreates one.
        defaults?.removeObject(forKey: activeAccountKeyPreference)
        guard let binding, binding.valid,
              let data = try? JSONEncoder().encode(binding) else {
            defaults?.removeObject(forKey: activeBindingPreference)
            return
        }
        defaults?.set(data, forKey: activeBindingPreference)
    }
    static func activeBinding() -> AccountBinding? {
        guard let data = UserDefaults(suiteName: group)?.data(forKey: activeBindingPreference),
              let binding = try? JSONDecoder().decode(AccountBinding.self, from: data), binding.valid else { return nil }
        return binding
    }
    static func language(saved: String?, preferred: String) -> String {
        if let saved, ["en", "ru", "uk"].contains(saved) { return saved }
        return preferred.hasPrefix("uk") ? "uk" : preferred.hasPrefix("ru") ? "ru" : "en"
    }
    static let maxFileBytes = 100 * 1024 * 1024
    /// Videos are compressed by the app before sending, so their source may be larger
    /// (the same ceiling as a video picked from the gallery).
    static let maxVideoBytes = 500 * 1024 * 1024
    static let maxBatchBytes = 1024 * 1024 * 1024
    /// Unsent batches kept at once; the next share asks to open UNYRI first.
    static let maxReadyBatches = 4
    static let maxPreservedImageBytes = 25 * 1024 * 1024
    static func imageSendKind(bytes: Int, preservesOriginal: Bool) -> String {
        preservesOriginal && bytes > maxPreservedImageBytes ? "file" : "image"
    }
    static func isVideo(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        guard !ext.isEmpty else { return false }
        #if canImport(UniformTypeIdentifiers)
        return UTType(filenameExtension: ext)?.conforms(to: .movie) ?? false
        #else
        return ["mov", "mp4", "m4v", "3gp", "avi", "mkv", "webm"].contains(ext)
        #endif
    }
    static func limit(for name: String) -> Int { isVideo(name) ? maxVideoBytes : maxFileBytes }
    static func isImage(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        #if canImport(UniformTypeIdentifiers)
        // SVG is an image UTType, but ImageIO's photo pipeline cannot decode it.
        guard let type = UTType(filenameExtension: ext), !type.conforms(to: .svg) else { return false }
        return type.conforms(to: .image)
        #else
        return ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "tif", "tiff", "bmp", "avif"].contains(ext)
        #endif
    }
    struct Entry: Codable, Identifiable, Sendable {
        let id: String
        let name: String
    }
    struct Batch: Codable {
        let entries: [Entry]
        /// Nil for pre-0.4.16 manifests, preserved until the user chooses an account.
        let accountKey: String?
    }
    struct UnboundBatch: Error {}
    /// The inbox root, or nil when this build has no App Group container at all (the
    /// container lookup returned nothing). Then the extension can never have stored
    /// anything, so erasure has nothing to do: a missing container must never block
    /// sign-in after a logout or a fresh install (seen on iOS 27 simulators, where the
    /// lookup is "not entitled"). Every other failure still throws.
    static func rootIfAvailable(_ provider: () throws -> URL) throws -> URL? {
        do { return try provider() }
        catch let error as CocoaError where error.code == .fileNoSuchFile { return nil }
    }
    static func root() throws -> URL {
        #if os(iOS)
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let root = container.appendingPathComponent("ShareInbox", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        return root
        #else
        throw CocoaError(.featureUnsupported)
        #endif
    }
    static func safeName(_ value: String) -> String {
        let name = String(value.replacingOccurrences(of: "\\", with: "_").replacingOccurrences(of: "/", with: "_").filter { !$0.isNewline && !($0.asciiValue.map { $0 < 32 } ?? false) }.prefix(160))
        return name.isEmpty || name == "." || name == ".." ? "File" : name
    }
    private static var protection: [FileAttributeKey: Any] {
        #if os(iOS)
        return [.protectionKey: FileProtectionType.complete]
        #else
        return [:]
        #endif
    }
    private static func isDirectory(_ url: URL) -> Bool {
        guard let info = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]) else { return false }
        return info.isDirectory == true && info.isSymbolicLink != true
    }
    private static func children(_ directory: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey, .creationDateKey])
    }
    private static func binding(for directory: URL) -> AccountBinding? {
        guard directory.pathExtension == "account" else { return nil }
        let parts = directory.deletingPathExtension().lastPathComponent.split(separator: ".")
        guard parts.count == 2 else { return nil }
        let binding = AccountBinding(accountKey: String(parts[0]), id: String(parts[1]))
        return binding.valid ? binding : nil
    }
    private static func accountDirectories(root: URL) throws -> [URL] {
        try children(root).filter { directory in
            guard binding(for: directory) != nil else { return false }
            // An unreadable protected directory must fail erasure, not look absent.
            let info = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            return info.isDirectory == true && info.isSymbolicLink != true
        }
    }
    static func prepareAccount(_ key: String, root: URL) throws -> AccountBinding {
        guard validAccountKey(key) else { throw CocoaError(.fileReadCorruptFile) }
        if let existing = try accountDirectories(root: root).first(where: { binding(for: $0)?.accountKey == key }),
           let existingBinding = binding(for: existing) { return existingBinding }
        // Not named `binding`: Swift 6.2 (Xcode 26.5) resolves the calls to
        // `binding(for:)` above to a later local of that name and fails to compile.
        let created = AccountBinding(accountKey: key, id: UUID().uuidString.lowercased())
        try FileManager.default.createDirectory(at: accountDirectory(created, root: root), withIntermediateDirectories: false, attributes: protection)
        return created
    }
    static func accountDirectory(_ binding: AccountBinding, root: URL) -> URL {
        root.appendingPathComponent(binding.directoryName, isDirectory: true)
    }
    static func removeIfPresent(_ directory: URL) throws {
        do { try FileManager.default.removeItem(at: directory) }
        catch let error as CocoaError where error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile { }
    }
    static func readBatch(in directory: URL) throws -> Batch {
        let data = try Data(contentsOf: directory.appendingPathComponent("manifest.json"))
        guard data.count < 65536 else { throw CocoaError(.fileReadCorruptFile) }
        return try JSONDecoder().decode(Batch.self, from: data)
    }
    static func writeBatch(_ batch: Batch, in directory: URL) throws {
        #if os(iOS)
        let options: Data.WritingOptions = [.atomic, .completeFileProtection]
        #else
        let options: Data.WritingOptions = [.atomic]
        #endif
        try JSONEncoder().encode(batch).write(to: directory.appendingPathComponent("manifest.json"), options: options)
    }
    private static func legacyDirectory(root: URL) throws -> URL {
        let legacy = root.appendingPathComponent("Legacy", isDirectory: true)
        if !FileManager.default.fileExists(atPath: legacy.path) {
            try FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: false, attributes: protection)
        }
        guard isDirectory(legacy) else { throw CocoaError(.fileReadCorruptFile) }
        return legacy
    }
    /// Move without reading/copying payloads; no owner is assigned and no age expires it.
    static func quarantineLegacy(root: URL) throws -> [URL] {
        let legacy = try legacyDirectory(root: root)
        for directory in try children(root) where directory.pathExtension == "ready" && isDirectory(directory) {
            let batch = try? readBatch(in: directory)
            if let key = batch?.accountKey, validAccountKey(key) { continue }
            // Unknown/corrupt ownership also requires a user's decision; retain
            // its bytes and let Discard work even when Import cannot read it.
            try FileManager.default.moveItem(at: directory, to: legacy.appendingPathComponent(UUID().uuidString.lowercased() + ".ready"))
        }
        return try children(legacy).filter { $0.pathExtension == "ready" && isDirectory($0) }
    }
    static func readyDirectories(root: URL) throws -> [URL] {
        let parents = [root] + (try accountDirectories(root: root))
        return try parents.flatMap { try children($0).filter { $0.pathExtension == "ready" && isDirectory($0) } }
            .sorted { $0.path < $1.path }
    }
    static func importableReadyBatchCount(root: URL) throws -> Int {
        let owners = Set(try accountDirectories(root: root).compactMap { binding(for: $0)?.accountKey })
        return try readyDirectories(root: root).filter { directory in
            guard let batch = try? readBatch(in: directory), let key = batch.accountKey, owners.contains(key),
                  let pending = try? files(in: directory, accountKey: key) else { return false }
            return !pending.isEmpty
        }.count
    }
    /// The move is the durable record of the user's Import action. If interrupted,
    /// only this account's .adopting directories may finish binding on recovery.
    static func adoptLegacy(_ directories: [URL], binding: AccountBinding, root: URL) throws {
        let legacy = try legacyDirectory(root: root)
        let parent = accountDirectory(binding, root: root)
        guard binding.valid, isDirectory(parent) else { throw CancellationError() }
        var failure: Error?
        for directory in directories {
            do {
                guard directory.deletingLastPathComponent().standardizedFileURL == legacy.standardizedFileURL,
                      directory.pathExtension == "ready", isDirectory(directory) else { throw CocoaError(.fileReadCorruptFile) }
                let batch = try readBatch(in: directory)
                guard batch.accountKey == nil else { throw CocoaError(.fileReadCorruptFile) }
                _ = try validatedFiles(batch, in: directory)
                let adopting = parent.appendingPathComponent(UUID().uuidString.lowercased() + ".adopting")
                try FileManager.default.moveItem(at: directory, to: adopting)
            } catch { failure = error }
        }
        try recoverAdoptions(accountKey: binding.accountKey, root: root)
        if let failure { throw failure }
    }
    static func recoverAdoptions(accountKey: String, root: URL) throws {
        for parent in try accountDirectories(root: root) where binding(for: parent)?.accountKey == accountKey {
            for directory in try children(parent) where directory.pathExtension == "adopting" && isDirectory(directory) {
                let batch = try readBatch(in: directory)
                guard batch.accountKey == nil || batch.accountKey == accountKey else { throw CocoaError(.fileReadCorruptFile) }
                try writeBatch(Batch(entries: batch.entries, accountKey: accountKey), in: directory)
                try FileManager.default.moveItem(at: directory, to: directory.deletingPathExtension().appendingPathExtension("ready"))
            }
        }
    }
    static func discardLegacy(_ directories: [URL], root: URL) throws {
        let legacy = try legacyDirectory(root: root)
        for directory in directories {
            guard directory.deletingLastPathComponent().standardizedFileURL == legacy.standardizedFileURL,
                  directory.pathExtension == "ready" else { throw CocoaError(.fileReadCorruptFile) }
            try removeIfPresent(directory)
        }
    }
    /// Revocation is an atomic rename of the account parent, before unlinking its
    /// files. A late writer cannot commit or recreate that parent (all its mkdirs
    /// have withIntermediateDirectories: false). Switches never call this method.
    static func purgeAccount(_ key: String, root: URL) throws {
        guard validAccountKey(key) else { throw CocoaError(.fileReadCorruptFile) }
        for directory in try accountDirectories(root: root) where binding(for: directory)?.accountKey == key {
            let trash = directory.deletingPathExtension().appendingPathExtension("purging")
            try FileManager.default.moveItem(at: directory, to: trash)
            try removeIfPresent(trash)
        }
        // Compatibility with bound batches made before revocable account parents.
        for directory in try children(root) where ["ready", "staging"].contains(directory.pathExtension) && isDirectory(directory) {
            if (try? readBatch(in: directory).accountKey) == key { try removeIfPresent(directory) }
        }
        for directory in try children(root) where directory.pathExtension == "purging" && isDirectory(directory) {
            if binding(for: directory.deletingPathExtension().appendingPathExtension("account"))?.accountKey == key { try removeIfPresent(directory) }
        }
    }
    static func purgeAllAccounts(root: URL) throws {
        // The pre-update extension could be interrupted before writing a
        // manifest. Last-account erasure must remove that plaintext immediately.
        for directory in try children(root) where directory.pathExtension == "staging" {
            try removeIfPresent(directory)
        }
        try sweep(root: root, olderThan: 86400)
        var keys = Set(try accountDirectories(root: root).compactMap { binding(for: $0)?.accountKey })
        for directory in try children(root) {
            if let key = try? readBatch(in: directory).accountKey { keys.insert(key) }
        }
        for key in keys where validAccountKey(key) { try purgeAccount(key, root: root) }
    }
    /// Event-driven recovery: the app uses 1 h, the extension keeps its 24 h backstop.
    /// Legacy quarantine and user-approved .adopting data are never age-swept.
    static func sweep(root: URL, olderThan age: TimeInterval, now: Date = Date()) throws {
        for directory in try children(root) where directory.pathExtension == "purging" && isDirectory(directory) {
            if binding(for: directory.deletingPathExtension().appendingPathExtension("account")) != nil { try removeIfPresent(directory) }
        }
        for parent in [root] + (try accountDirectories(root: root)) {
            for directory in try children(parent) where directory.pathExtension == "staging" && isDirectory(directory) {
                if let created = try directory.resourceValues(forKeys: [.creationDateKey]).creationDate, now.timeIntervalSince(created) > age {
                    try removeIfPresent(directory)
                }
            }
        }
    }
    final class StagingRequest {
        let directory: URL
        let readyDirectory: URL
        let accountKey: String
        let cancellation = CopyCancellation()
        init(binding: AccountBinding, root: URL) throws {
            let parent = UnyriShareStore.accountDirectory(binding, root: root)
            guard binding.valid, UnyriShareStore.isDirectory(parent) else { throw CancellationError() }
            let id = UUID().uuidString.lowercased()
            directory = parent.appendingPathComponent(id + ".staging", isDirectory: true)
            readyDirectory = parent.appendingPathComponent(id + ".ready", isDirectory: true)
            accountKey = binding.accountKey
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: UnyriShareStore.protection)
        }
        func itemFolder(id: String) throws -> URL {
            try cancellation.check()
            guard UUID(uuidString: id) != nil else { throw CocoaError(.fileReadCorruptFile) }
            let folder = directory.appendingPathComponent(id, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false, attributes: UnyriShareStore.protection)
            return folder
        }
        func commit(entries: [Entry]) throws {
            try cancellation.check()
            try UnyriShareStore.writeBatch(Batch(entries: entries, accountKey: accountKey), in: directory)
            try cancellation.check()
            try FileManager.default.moveItem(at: directory, to: readyDirectory)
        }
        /// Safe before cancelRequest even when the provider never resumes: no
        /// callback owns cleanup and no callback may recreate these directories.
        func cancelAndRemove() throws {
            cancellation.cancel()
            try UnyriShareStore.removeIfPresent(directory)
        }
    }
    /// Nil means another account owns the batch: do not inspect or delete it.
    static func files(in directory: URL, accountKey: String) throws -> [(Entry, URL)]? {
        let batch = try readBatch(in: directory)
        guard let owner = batch.accountKey else { throw UnboundBatch() }
        guard validAccountKey(owner), validAccountKey(accountKey) else { throw CocoaError(.fileReadCorruptFile) }
        guard owner == accountKey else { return nil }
        return try validatedFiles(batch, in: directory)
    }
    private static func validatedFiles(_ batch: Batch, in directory: URL) throws -> [(Entry, URL)] {
        guard (1...10).contains(batch.entries.count), Set(batch.entries.map(\.id)).count == batch.entries.count else { throw CocoaError(.fileReadCorruptFile) }
        for entry in batch.entries {
            guard UUID(uuidString: entry.id) != nil, safeName(entry.name) == entry.name else { throw CocoaError(.fileReadCorruptFile) }
        }
        return try batch.entries.filter { !FileManager.default.fileExists(atPath: directory.appendingPathComponent($0.id + ".done").path) }.map { entry in
            guard UUID(uuidString: entry.id) != nil, safeName(entry.name) == entry.name else { throw CocoaError(.fileReadCorruptFile) }
            let parent = directory.appendingPathComponent(entry.id, isDirectory: true)
            let parentInfo = try parent.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard parentInfo.isDirectory == true, parentInfo.isSymbolicLink != true else { throw CocoaError(.fileReadCorruptFile) }
            let file = parent.appendingPathComponent(entry.name)
            let info = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard info.isRegularFile == true, info.isSymbolicLink != true, (info.fileSize ?? Int.max) <= limit(for: entry.name) else { throw CocoaError(.fileReadCorruptFile) }
            return (entry, file)
        }
    }
    struct InboxScope: Equatable, Sendable {
        let accountKey: String
        let generation: UInt64
    }
    struct InboxSnapshot: Sendable {
        struct File: Sendable { let id: String; let url: URL }
        var directory: URL? = nil
        var files: [File] = []
        var legacyDirectories: [URL] = []
        var legacyItemCount = 0
    }
    struct ExtensionPreparation: Sendable {
        let root: URL
        let readyBatches: Int
        private let cancellation: CopyCancellation
        fileprivate init(root: URL, readyBatches: Int, cancellation: CopyCancellation) {
            self.root = root; self.readyBatches = readyBatches; self.cancellation = cancellation
        }
        func makeRequest(binding: AccountBinding) throws -> StagingRequest {
            // Preparation creates no request-owned files. Close can end a request
            // during recovery, and this gate prevents staging from appearing later.
            try cancellation.check()
            return try StagingRequest(binding: binding, root: root)
        }
    }
    /// Synchronous store operations stay here; the app invokes them only on its
    /// serial I/O executor. No UIKit state or account credentials cross that queue.
    static func discoverInbox(accountKey: String, root: URL) throws -> InboxSnapshot {
        try sweep(root: root, olderThan: 3600)
        try recoverAdoptions(accountKey: accountKey, root: root)
        let legacy = try quarantineLegacy(root: root)
        for directory in try readyDirectories(root: root) {
            guard let entries = try? files(in: directory, accountKey: accountKey) else { continue }
            if entries.isEmpty { try removeIfPresent(directory); continue }
            return InboxSnapshot(directory: directory, files: entries.map { .init(id: $0.0.id, url: $0.1) })
        }
        let count = legacy.reduce(0) { count, directory in
            count + ((try? readBatch(in: directory).entries.filter {
                UUID(uuidString: $0.id) != nil && !FileManager.default.fileExists(atPath: directory.appendingPathComponent($0.id + ".done").path)
            }.count) ?? 0)
        }
        return InboxSnapshot(legacyDirectories: legacy, legacyItemCount: count)
    }
    static let inboxIO = InboxIO()
    /// One owned executor serializes app discovery, recovery, publication and
    /// erasure. Blocking Foundation file APIs never run on the main actor.
    final class InboxIO: @unchecked Sendable {
        private let queue: DispatchQueue
        private let rootProvider: @Sendable () throws -> URL
        init(label: String = "unyri.share.inbox.io", root: @escaping @Sendable () throws -> URL = { try UnyriShareStore.root() }) {
            queue = DispatchQueue(label: label, qos: .userInitiated); rootProvider = root
        }
        func perform<Value: Sendable>(_ operation: @escaping @Sendable () throws -> Value) async throws -> Value {
            let cancellation = CopyCancellation()
            return try await withTaskCancellationHandler(operation: {
                try Task.checkCancellation()
                return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Value, Error>) in
                    queue.async {
                        dispatchPrecondition(condition: .notOnQueue(.main))
                        continuation.resume(with: Result {
                            try cancellation.check()
                            return try operation()
                        })
                    }
                }
            }, onCancel: { cancellation.cancel() })
        }
        func discover(accountKey: String) async throws -> InboxSnapshot {
            try await perform {
                let snapshot = try UnyriShareStore.discoverInbox(accountKey: accountKey, root: self.rootProvider())
                return snapshot
            }
        }
        func prepareExtensionShare(cancellation: CopyCancellation) async throws -> ExtensionPreparation {
            try await perform {
                try cancellation.check()
                let root = try self.rootProvider()
                try cancellation.check()
                try UnyriShareStore.sweep(root: root, olderThan: 86400)
                try cancellation.check()
                let count = try UnyriShareStore.importableReadyBatchCount(root: root)
                try cancellation.check()
                return ExtensionPreparation(root: root, readyBatches: count, cancellation: cancellation)
            }
        }
        func prepareAccount(_ key: String) async throws -> AccountBinding {
            try await perform { try UnyriShareStore.prepareAccount(key, root: self.rootProvider()) }
        }
        func adoptLegacy(_ directories: [URL], binding: AccountBinding) async throws {
            try await perform { try UnyriShareStore.adoptLegacy(directories, binding: binding, root: self.rootProvider()) }
        }
        func discardLegacy(_ directories: [URL]) async throws {
            try await perform { try UnyriShareStore.discardLegacy(directories, root: self.rootProvider()) }
        }
        func acknowledge(_ id: String, directory: URL) async throws {
            try await perform {
                guard UUID(uuidString: id) != nil else { throw CocoaError(.fileReadCorruptFile) }
                #if os(iOS)
                let options: Data.WritingOptions = [.atomic, .completeFileProtection]
                #else
                let options: Data.WritingOptions = [.atomic]
                #endif
                try Data().write(to: directory.appendingPathComponent(id + ".done"), options: options)
            }
        }
        func removeLater(_ directory: URL) {
            // Submit before returning, so a following discovery cannot overtake
            // dismissal cleanup. No Task scheduling race and no synchronous wait.
            queue.async {
                dispatchPrecondition(condition: .notOnQueue(.main))
                do { try UnyriShareStore.removeIfPresent(directory) }
                catch { NSLog("UNYRI share inbox: dismissal cleanup failed") }
            }
        }
        func purgeAccount(_ key: String) async throws {
            try await perform {
                guard let root = try UnyriShareStore.rootIfAvailable(self.rootProvider) else { return }
                try UnyriShareStore.purgeAccount(key, root: root)
            }
        }
        func purgeAllAccounts() async throws {
            try await perform {
                guard let root = try UnyriShareStore.rootIfAvailable(self.rootProvider) else { return }
                try UnyriShareStore.purgeAllAccounts(root: root)
            }
        }
    }
    /// The source turned out larger than the copy's byte limit.
    struct LimitExceeded: Error {}
    /// Task cancellation does not propagate into NSItemProvider's callback thread.
    /// A per-save token connects Close to that callback, without polling at rest.
    final class CopyCancellation: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        private var handlers: [UUID: @Sendable () -> Void] = [:]
        /// Registration and cancellation are atomic, including registration
        /// after Close. Never invoke provider code while holding this lock.
        @discardableResult func onCancel(_ body: @escaping @Sendable () -> Void) -> UUID {
            let id = UUID()
            lock.lock()
            if cancelled { lock.unlock(); body() }
            else { handlers[id] = body; lock.unlock() }
            return id
        }
        func removeHandler(_ id: UUID) { lock.lock(); handlers[id] = nil; lock.unlock() }
        func cancel() {
            lock.lock()
            guard !cancelled else { lock.unlock(); return }
            cancelled = true
            let callbacks = Array(handlers.values); handlers.removeAll()
            lock.unlock()
            callbacks.forEach { $0() }
        }
        func check() throws {
            lock.lock(); let value = cancelled; lock.unlock()
            if value { throw CancellationError() }
        }
    }
    /// Bounded streaming copy: a provider may lie about its declared file size.
    static func copy(_ source: URL, to destination: URL, limit: Int, cancellation: CopyCancellation, progress: ((Int) -> Void)? = nil) throws -> Int {
        try cancellation.check()
        let info = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard source.isFileURL, info.isRegularFile == true, info.isSymbolicLink != true else { throw CocoaError(.fileReadUnsupportedScheme) }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        #if os(iOS)
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.complete]
        #else
        let attributes: [FileAttributeKey: Any] = [:]
        #endif
        guard FileManager.default.createFile(atPath: destination.path, contents: nil, attributes: attributes) else { throw CocoaError(.fileWriteUnknown) }
        var succeeded = false
        defer { if !succeeded { try? FileManager.default.removeItem(at: destination) } }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var count = 0
        while true {
            try cancellation.check()
            guard let bytes = try input.read(upToCount: 256 * 1024), !bytes.isEmpty else { break }
            count += bytes.count
            guard count <= limit else { throw LimitExceeded() }
            try cancellation.check()
            try output.write(contentsOf: bytes)
            progress?(count)
        }
        try cancellation.check()
        try output.synchronize()
        try cancellation.check()
        succeeded = true
        return count
    }
}
