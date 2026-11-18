import Foundation

/// File consumers and deletion arbitrate with the same lock. A lease protects
/// visible previews, Quick Look and voice playback across asynchronous cleanup.
final class MediaCacheLease: @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var owners: [String: Int] = [:]
    nonisolated(unsafe) private static var accessed: [String: Date] = [:]
    private let path: String
    init(path: String) {
        self.path = URL(fileURLWithPath: path).standardizedFileURL.path
        Self.lock.lock(); Self.owners[self.path, default: 0] += 1; Self.accessed[self.path] = Date(); Self.prune(); Self.lock.unlock()
    }
    deinit {
        Self.lock.lock(); Self.owners[path, default: 1] -= 1
        if Self.owners[path] == 0 { Self.owners[path] = nil }
        Self.accessed[path] = Date(); Self.prune(); Self.lock.unlock()
    }
    private static func prune() {
        guard accessed.count > 4096 else { return }
        let excess = accessed.count - 4096
        for (path, _) in accessed.filter({ owners[$0.key] == nil }).sorted(by: { $0.value < $1.value }).prefix(excess) { accessed[path] = nil }
    }
    static func lastAccess(_ path: String) -> Date? { lock.lock(); defer { lock.unlock() }; return accessed[path] }
    static func removeIfUnleased(_ url: URL, grace: TimeInterval = 300) throws -> Bool {
        let path = url.standardizedFileURL.path
        lock.lock()
        guard owners[path] == nil, accessed[path].map({ Date().timeIntervalSince($0) >= grace }) ?? true else { lock.unlock(); return false }
        owners[path] = 1
        lock.unlock()
        defer {
            lock.lock()
            if let count = owners[path], count > 1 { owners[path] = count - 1 }
            else { owners[path] = nil; accessed[path] = nil }
            lock.unlock()
        }
        if FileManager.default.fileExists(atPath: path) { try FileManager.default.removeItem(at: url) }

        return true
    }
}

/// Retirement drains a removal already in progress and prevents any queued
/// filesystem job from deleting after its account services have shut down.
final class CacheDeletionFence: @unchecked Sendable {
    private let lock = NSLock()
    private var active = true
    func retire() { lock.lock(); active = false; lock.unlock() }
    func perform(_ operation: () throws -> Bool) rethrows -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return active ? try operation() : false
    }
}
