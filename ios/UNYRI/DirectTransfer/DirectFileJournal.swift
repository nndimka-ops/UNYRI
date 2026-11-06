import Foundation

/// Control commits must not wait behind hashing or copying a multi-GiB file.
/// This small, independently serialized journal owns only generated UUID paths.
actor DirectFileJournal {
    enum Failure: Error { case invalid }
    private let root: URL
    private let maximumBytes: Int64
    private var retired = false
    nonisolated let allocation: DirectFileAllocationLedger
    init(accountDirectory: URL, maximumBytes: Int64) {
        root = accountDirectory.appendingPathComponent("direct-transfers", isDirectory: true)
        self.maximumBytes = maximumBytes
        allocation = DirectFileAllocationLedger(accountDirectory: accountDirectory)
    }
    private func directory(_ id: String) throws -> URL {
        guard !retired else { throw CancellationError() }
        guard UUID(uuidString: id)?.uuidString.lowercased() == id else { throw Failure.invalid }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let directory = root.appendingPathComponent(id, isDirectory: true)
        guard directory.standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent() == root.standardizedFileURL.resolvingSymlinksInPath() else { throw Failure.invalid }
        return directory
    }
    func save(_ record: DirectFileRecord) throws {
        guard record.validLocalExtensions, record.offer.hasValidShape, record.offer.size <= maximumBytes,
              record.durableOffset >= 0, record.durableOffset <= record.offer.size,
              record.seenSignals.count <= 256, record.incomingControls.count <= 32,
              record.outgoingSignals.count <= 8, record.completionRecoveryTriggers.count <= 64,
              (0...6).contains(record.completionRecoveryCount) else { throw Failure.invalid }
        let bytes = try JSONEncoder().encode(record)
        guard bytes.count <= 2 * 1024 * 1024 else { throw Failure.invalid }
        let directory = try directory(record.id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try bytes.write(to: directory.appendingPathComponent("record.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func records() throws -> [DirectFileRecord] {
        guard !retired else { throw CancellationError() }
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        var result: [DirectFileRecord] = []
        for entry in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
            guard UUID(uuidString: entry.lastPathComponent)?.uuidString.lowercased() == entry.lastPathComponent,
                  entry.standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent() == root.standardizedFileURL.resolvingSymlinksInPath() else { continue }
            let file = entry.appendingPathComponent("record.json")
            guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 2 * 1024 * 1024,
                  let bytes = try? Data(contentsOf: file),
                  let record = try? JSONDecoder().decode(DirectFileRecord.self, from: bytes),
                  record.validLocalExtensions, record.offer.hasValidShape, record.id == entry.lastPathComponent,
                  record.offer.size <= maximumBytes, record.durableOffset >= 0,
                  record.durableOffset <= record.offer.size,
                  record.seenSignals.count <= 256, record.incomingControls.count <= 32,
                  record.outgoingSignals.count <= 8, record.completionRecoveryTriggers.count <= 64,
                  (0...6).contains(record.completionRecoveryCount) else { continue }
            result.append(record)
        }
        return result.sorted { $0.updatedAt < $1.updatedAt }
    }
    func record(_ id: String) throws -> DirectFileRecord? {
        let file = try directory(id).appendingPathComponent("record.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        guard let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 2 * 1024 * 1024 else { throw Failure.invalid }
        let record = try JSONDecoder().decode(DirectFileRecord.self, from: Data(contentsOf: file))
        guard record.id == id, record.offer.hasValidShape, record.validLocalExtensions else { throw Failure.invalid }
        return record
    }
    func retire() { retired = true; allocation.retire() }
}
