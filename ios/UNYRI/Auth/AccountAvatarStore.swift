import Foundation

/// Device-local own-account thumbnails only. Call disk methods off the UI thread.
/// Owner binding prevents a reused slot from ever displaying its former owner.
final class AccountAvatarStore: @unchecked Sendable {
    struct Ticket: Equatable, Sendable {
        let slot: String
        let owner: String
        let revision: String
        fileprivate let generation: UUID
    }
    struct Snapshot: Codable, Equatable, Sendable {
        let owner: String
        let revision: String
        let jpeg: Data
    }
    static let device = AccountAvatarStore(root: FileManager.default.urls(for: .applicationSupportDirectory,
        in: .userDomainMask)[0].appendingPathComponent("COCOAccountAvatars", isDirectory: true))
    let root: URL
    // Main-actor begin/invalidate never wait for disk reads, encoding or writes.
    private let ticketsLock = NSLock()
    private let diskLock = NSLock()
    private let beforeCommit: @Sendable () -> Void
    private var tickets: [String: Ticket] = [:]
    private static let maximumBytes = 128 * 1024

    init(root: URL, beforeCommit: @escaping @Sendable () -> Void = {}) {
        self.root = root
        self.beforeCommit = beforeCommit
    }

    static func owner(origin: String, user: String) -> String {
        origin.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "|" + user.lowercased()
    }

    private func file(_ slot: String) throws -> URL {
        let secondary = slot.count == 17 && slot.hasPrefix("s") && slot.dropFirst().allSatisfy { $0.isHexDigit && !$0.isUppercase }
        guard slot == "primary" || secondary else { throw CocoaError(.fileWriteInvalidFileName) }
        return root.appendingPathComponent(slot, isDirectory: true).appendingPathComponent("thumbnail.json")
    }

    /// No disk access. Reserving a new avatar invalidates every older encoder.
    func begin(slot: String, owner: String, revision: String) throws -> Ticket {
        _ = try file(slot)
        guard owner.contains("|"), let user = owner.split(separator: "|", omittingEmptySubsequences: false).last,
              !user.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CocoaError(.fileWriteInvalidFileName) }
        ticketsLock.lock(); defer { ticketsLock.unlock() }
        let ticket = Ticket(slot: slot, owner: owner, revision: revision, generation: UUID())
        tickets[slot] = ticket
        return ticket
    }

    func invalidate(slot: String) {
        ticketsLock.lock(); defer { ticketsLock.unlock() }
        tickets[slot] = nil
    }

    func read(slot: String, owner: String) throws -> Snapshot? {
        diskLock.lock(); defer { diskLock.unlock() }
        return try readFile(slot).flatMap { $0.owner == owner ? $0 : nil }
    }

    private func readFile(_ slot: String) throws -> Snapshot? {
        let url = try file(slot)
        guard FileManager.default.fileExists(atPath: url.path),
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= Self.maximumBytes * 2,
              let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(Snapshot.self, from: data),
              !value.jpeg.isEmpty, value.jpeg.count <= Self.maximumBytes else { return nil }
        return value
    }

    /// Atomic replacement, including owner/revision. A purged ticket cannot recreate files.
    @discardableResult
    func write(_ jpeg: Data?, ticket: Ticket) throws -> Bool {
        diskLock.lock(); defer { diskLock.unlock() }
        guard isCurrent(ticket) else { return false }
        let url = try file(ticket.slot)
        guard let jpeg else {
            ticketsLock.lock()
            guard tickets[ticket.slot] == ticket else { ticketsLock.unlock(); return false }
            tickets[ticket.slot] = nil
            ticketsLock.unlock()
            let folder = url.deletingLastPathComponent()
            guard FileManager.default.fileExists(atPath: folder.path) else { return false }
            try FileManager.default.removeItem(at: folder)
            return true
        }
        guard !jpeg.isEmpty, jpeg.count <= Self.maximumBytes else { throw CocoaError(.fileWriteInapplicableStringEncoding) }
        let snapshot = Snapshot(owner: ticket.owner, revision: ticket.revision, jpeg: jpeg)
        if try readFile(ticket.slot) == snapshot { return false }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        var folder = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
        let temporary = url.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".tmp")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let data = try JSONEncoder().encode(snapshot)
        #if os(iOS)
        try data.write(to: temporary, options: .completeFileProtectionUntilFirstUserAuthentication)
        #else
        try data.write(to: temporary)
        #endif
        beforeCommit()
        // Only the final ticket check and atomic replacement share the tiny lock.
        ticketsLock.lock(); defer { ticketsLock.unlock() }
        guard tickets[ticket.slot] == ticket else { return false }
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: url)
        }
        return true
    }

    private func isCurrent(_ ticket: Ticket) -> Bool {
        ticketsLock.lock(); defer { ticketsLock.unlock() }
        return tickets[ticket.slot] == ticket
    }

    func purge(slot: String) throws {
        diskLock.lock(); defer { diskLock.unlock() }
        let folder = try file(slot).deletingLastPathComponent()
        invalidate(slot: slot)
        if FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.removeItem(at: folder) }
    }

    func purgeAll() throws {
        diskLock.lock(); defer { diskLock.unlock() }
        ticketsLock.lock()
        tickets.removeAll()
        ticketsLock.unlock()
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
    }
}

/// Four decoded thumbnails. Peek is memory-only; load/decode is worker-only.
/// A generation prevents a decode racing purge/replacement from refilling it.
final class AccountAvatarMemory<Image>: @unchecked Sendable {
    private struct Key: Equatable {
        let slot: String
        let owner: String
        let revision: String
    }
    private let lock = NSLock()
    private let loadLock = NSLock()
    private var generation: UInt64 = 0
    private var entries: [(key: Key, image: Image)] = []

    func peek(slot: String, owner: String) -> Image? {
        lock.lock(); defer { lock.unlock() }
        return entries.last { $0.key.slot == slot && $0.key.owner == owner }?.image
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        generation &+= 1
        entries.removeAll()
    }

    func load(slot: String, owner: String, read: () throws -> AccountAvatarStore.Snapshot?,
              decode: (Data) -> Image?) rethrows -> Image? {
        if let image = peek(slot: slot, owner: owner) { return image }
        // Concurrent mounts share one decode, without blocking synchronous peek.
        loadLock.lock(); defer { loadLock.unlock() }
        if let image = peek(slot: slot, owner: owner) { return image }
        lock.lock(); let captured = generation; lock.unlock()
        guard let snapshot = try read(), snapshot.owner == owner, let image = decode(snapshot.jpeg) else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard captured == generation else { return nil }
        let key = Key(slot: slot, owner: owner, revision: snapshot.revision)
        entries.removeAll { $0.key.slot == slot && $0.key.owner == owner }
        entries.append((key, image))
        if entries.count > 4 { entries.removeFirst() }
        return image
    }
}
