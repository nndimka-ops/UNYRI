import Foundation

struct ShareJob: Codable, Equatable {
    enum Kind: String, Codable { case image, video, file }
    enum Route: String, Codable { case cloud }
    enum Preparation: String, Codable { case app }
    enum Quality: String, Codable { case standard, high, original }
    enum SendAs: String, Codable { case media, file }
    struct Target: Codable, Equatable {
        let chatId: String
        let type: String
        let peerUserId: String?
        let titleAtShare: String
    }
    struct Item: Codable, Equatable, Identifiable {
        let id: String
        var kind: Kind
        var route: Route = .cloud
        var prepare: Preparation = .app
        let name: String
        let bytes: Int64
        var clientAttachmentId: String = UUID().uuidString.lowercased()
        let albumItemIndex: Int
        var quality: Quality = .standard
        var sendAs: SendAs = .media
    }
    var version = 2
    let jobId: String
    let createdAt: TimeInterval
    let accountKey: String
    let target: Target
    let caption: String?
    let albumId: String?
    var launchedFromSuggestion = false
    let items: [Item]
    func caption(at index: Int) -> String? { index == 0 ? caption : nil }
    static func uuid(_ value: String) -> Bool { UUID(uuidString: value) != nil }
    func validate() throws {
        guard version == 2, Self.uuid(jobId), createdAt.isFinite,
              UnyriShareStore.validAccountKey(accountKey), Self.uuid(target.chatId),
              ["direct", "group", "saved"].contains(target.type),
              target.peerUserId.map(Self.uuid) ?? true,
              (target.type != "direct" || target.peerUserId != nil),
              (1...ShareItemClassification.maximumItems).contains(items.count), albumId.map(Self.uuid) ?? true,
              Set(items.map { $0.id.lowercased() }).count == items.count,
              Set(items.map { $0.clientAttachmentId.lowercased() }).count == items.count,
              items.enumerated().allSatisfy({ index, item in
                  Self.uuid(item.id) && Self.uuid(item.clientAttachmentId) && item.albumItemIndex == index &&
                  item.name == UnyriShareStore.safeName(item.name) && !item.name.contains("\0") &&
                  item.name.utf8.count <= 640 && item.bytes >= 0 &&
                  ShareRoutePolicy.issue(kind: item.kind, bytes: item.bytes, sendAs: item.sendAs) == nil
              }), target.titleAtShare.utf8.count <= 2048 else { throw CocoaError(.fileReadCorruptFile) }
        guard try MessageTextPolicy.caption(caption) == caption else { throw CocoaError(.fileReadCorruptFile) }
    }
    static func decode(_ data: Data) throws -> Self {
        guard data.count <= 64 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        do {
            let job = try JSONDecoder().decode(Self.self, from: data)
            try job.validate(); return job
        } catch { throw CocoaError(.fileReadCorruptFile) }
    }
    func encoded() throws -> Data {
        try validate()
        let data = try ShareContainer.encode(self)
        guard data.count <= 64 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        return data
    }
}

/// Queue-only facts. `imported` means a durable private source AND outbox row;
/// it never means delivered. `sent` is recorded only from the real message row.
struct ShareJobEvent: Codable, Equatable {
    enum Name: String, Codable { case imported, sent, failed, extensionFinished }
    let name: Name
    var code: String? = nil
    var permanent: Bool = false
}

enum ShareJobStore {
    final class Staging {
        let binding: UnyriShareStore.AccountBinding
        let id: String
        let directory: URL
        let cancellation = UnyriShareStore.CopyCancellation()
        private(set) var committed = false
        init(binding: UnyriShareStore.AccountBinding, root: URL) throws {
            guard binding.valid else { throw CocoaError(.fileReadCorruptFile) }
            self.binding = binding
            let requestID = UUID().uuidString.lowercased()
            id = requestID
            let parent = ShareContainer.accountJobs(binding, root: root)
            guard ShareContainer.isDirectory(parent) else { throw CocoaError(.fileNoSuchFile) }
            directory = parent.appendingPathComponent(requestID + ".staging", isDirectory: true)
            try ShareContainer.directory(directory, intermediates: false)
            try ShareContainer.directory(directory.appendingPathComponent("items"), intermediates: false)
            try ShareContainer.directory(directory.appendingPathComponent("events"), intermediates: false)
        }
        func itemDirectory(_ id: String) throws -> URL {
            try cancellation.check()
            guard ShareJob.uuid(id), !committed else { throw CocoaError(.fileReadCorruptFile) }
            let url = directory.appendingPathComponent("items").appendingPathComponent(id)
            try ShareContainer.directory(url, intermediates: false)
            return url
        }
        func commit(_ job: ShareJob) throws -> URL {
            try cancellation.check()
            guard !committed, job.jobId == id, job.accountKey == binding.accountKey else { throw CocoaError(.fileReadCorruptFile) }
            for item in job.items {
                guard try ShareContainer.regularSize(Self.source(item, in: directory)) == item.bytes else { throw CocoaError(.fileReadCorruptFile) }
            }
            try ShareContainer.write(job.encoded(), to: directory.appendingPathComponent("manifest.json"), backgroundReadable: true)
            try ShareJobStore.event(.init(name: .extensionFinished), itemID: "job", in: directory)
            try cancellation.check()
            let destination = directory.deletingPathExtension().appendingPathExtension("job")
            try FileManager.default.moveItem(at: directory, to: destination)
            committed = true
            return destination
        }
        private static func source(_ item: ShareJob.Item, in directory: URL) -> URL { ShareJobStore.source(item, in: directory) }
        func cancelAndRemove() throws {
            cancellation.cancel()
            if !committed { try UnyriShareStore.removeIfPresent(directory) }
        }
    }
    static func source(_ item: ShareJob.Item, in directory: URL) -> URL {
        directory.appendingPathComponent("items").appendingPathComponent(item.id).appendingPathComponent(item.name)
    }
    static func read(_ directory: URL) throws -> ShareJob {
        let job = try ShareJob.decode(ShareContainer.read(directory.appendingPathComponent("manifest.json"), maximum: 64 * 1024))
        guard directory.deletingPathExtension().lastPathComponent == job.jobId,
              directory.deletingLastPathComponent().lastPathComponent.hasPrefix(job.accountKey + ".") else { throw CocoaError(.fileReadCorruptFile) }
        return job
    }
    static func committed(root: URL) throws -> [URL] {
        try ShareContainer.children(ShareContainer.jobs(root)).filter { $0.pathExtension == "account" && ShareContainer.isDirectory($0) }
            .flatMap { try ShareContainer.children($0) }.filter { $0.pathExtension == "job" && ShareContainer.isDirectory($0) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
    static func event(_ event: ShareJobEvent, itemID: String, in directory: URL) throws {
        guard itemID == "job" || ShareJob.uuid(itemID) else { throw CocoaError(.fileReadCorruptFile) }
        // Never recreate a committed tree: a late fact after purge is ignored.
        try ShareContainer.write(ShareContainer.encode(event), to: directory.appendingPathComponent("events/\(itemID).\(event.name.rawValue).json"), backgroundReadable: true)
    }
    static func event(_ name: ShareJobEvent.Name, itemID: String, in directory: URL) -> ShareJobEvent? {
        guard itemID == "job" || ShareJob.uuid(itemID),
              let data = try? ShareContainer.read(directory.appendingPathComponent("events/\(itemID).\(name.rawValue).json"), maximum: 4096),
              let event = try? JSONDecoder().decode(ShareJobEvent.self, from: data), event.name == name else { return nil }
        return event
    }
    /// Only the app calls this, after each item has a durable outbox owner or a
    /// permanent failure. Pending source lifetimes now belong to the outbox.
    static func removeImportedSources(_ job: ShareJob, in directory: URL) throws {
        for item in job.items where event(.imported, itemID: item.id, in: directory) != nil {
            try UnyriShareStore.removeIfPresent(source(item, in: directory))
        }
    }
    /// App Group plaintext expires independently of which account is active.
    /// Private copies and durable outbox records are never touched here.
    /// Keep imported journals until their account reconciles the real send rows.
    static func expireSources(_ job: ShareJob, in directory: URL, now: Date) throws -> Bool {
        guard now.timeIntervalSince1970 - job.createdAt > 7 * 86400 else { return false }
        var whollyUnimported = true
        for item in job.items {
            let imported = event(.imported, itemID: item.id, in: directory) != nil
            if imported { whollyUnimported = false }
            // Free plaintext before writing a small fact, even on a full disk.
            // A crash here still cannot admit it: createdAt is immutable.
            try UnyriShareStore.removeIfPresent(source(item, in: directory))
            if !imported { try event(.init(name: .failed, code: "expired", permanent: true), itemID: item.id, in: directory) }
        }
        return whollyUnimported
    }
    static func sweepStaging(root: URL, olderThan age: TimeInterval, now: Date = Date()) throws {
        for parent in try ShareContainer.children(ShareContainer.jobs(root)) where parent.pathExtension == "account" && ShareContainer.isDirectory(parent) {
            for folder in try ShareContainer.children(parent) where folder.pathExtension == "staging" && ShareContainer.isDirectory(folder) {
                if let created = try folder.resourceValues(forKeys: [.creationDateKey]).creationDate, now.timeIntervalSince(created) > age {
                    try UnyriShareStore.removeIfPresent(folder)
                }
            }
        }
    }
}
