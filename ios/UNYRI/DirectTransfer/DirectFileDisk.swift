import CryptoKit
import Foundation
import UniformTypeIdentifiers
import Darwin

/// A separate account-owned directory. None of these files is an upload job,
/// attachment cache object or chat render dependency. Every large read/hash,
/// copy, flush and rename runs on this actor's executor instead of the UI actor.
actor DirectFileDisk {
    enum Failure: Error { case invalid, noSpace, changed, missing, corrupt, tooMany, offset, admissionUnavailable }
    struct Prepared: Sendable { let bytes: Int64; let digest: String }
    struct Checkpoint: Sendable { let offset: Int64; let prefixSHA256: String }
    nonisolated let journal: DirectFileJournal
    private let directory: URL
    private let maximumBytes: Int64
    private var receivedOffsets: [String: Int64] = [:]
    private var lastCheckpointOffsets: [String: Int64] = [:]
    private var handles: [String: FileHandle] = [:]
    private var readerHandles: [String: FileHandle] = [:]
    private var retired = false
    private(set) var admissionBlocked = false
    private let bulk = DirectFileBulkIO()
    private var fileLeases: [String: UUID] = [:]
    private var rootReady = false
    private var ownedDirectories: [String: URL] = [:]
    private var createdDirectories = Set<String>()
    private var readerOffsets: [String: Int64] = [:]
    private let checkpointBytes: Int64 = 256 * 1024
    private struct StoredProgress: Codable {
        var attemptId: String?
        var offerClientMessageId: String
        var size: Int64
        var sha256: String
        var offset: Int64
        var chatId: String?
        var localDeviceId: String?
    }

    init(accountDirectory: URL, maximumBytes: Int64) {
        directory = accountDirectory.appendingPathComponent("direct-transfers", isDirectory: true)
        self.maximumBytes = maximumBytes
        journal = DirectFileJournal(accountDirectory: accountDirectory, maximumBytes: maximumBytes)
    }

    private func ensureRoot() throws {
        guard !retired else { throw CancellationError() }
        if rootReady { return }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var root = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try root.setResourceValues(values)
        rootReady = true
    }

    private func transferDirectory(_ id: String, create: Bool = false) throws -> URL {
        guard !retired else { throw CancellationError() }
        if let known = ownedDirectories[id], !create || createdDirectories.contains(id) { return known }
        guard UUID(uuidString: id) != nil else { throw Failure.invalid }
        try ensureRoot()
        let result = directory.appendingPathComponent(id.lowercased(), isDirectory: true)
        guard result.standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent() == directory.standardizedFileURL.resolvingSymlinksInPath() else {
            throw Failure.invalid
        }
        if create { try FileManager.default.createDirectory(at: result, withIntermediateDirectories: true) }
        ownedDirectories[id] = result
        if create { createdDirectories.insert(id) }
        return result
    }

    private func path(_ id: String, _ name: String, create: Bool = false) throws -> URL {
        try transferDirectory(id, create: create).appendingPathComponent(name)
    }

    func records() async throws -> [DirectFileRecord] {
        let records = try await journal.records()
        let recovered = records.map { record in
            var recovered = record
            if let offset = try? verifiedProgressOffset(record) {
                recovered.durableOffset = max(record.durableOffset, offset)
            }
            return recovered
        }
        let tickets: [DirectFileAdmissionTicket]
        do {
            tickets = try journal.allocation.reconcile(recovered)
            admissionBlocked = false
        } catch {
            try Task.checkCancellation()
            guard !retired else { throw CancellationError() }
            // Preserve the invalid ledger and its credits. Independently valid
            // private completed receipts remain usable; unfinished work cannot
            // allocate, claim or create another transport until revalidated.
            admissionBlocked = true
            return recovered
        }
        var result: [DirectFileRecord] = []
        for var record in recovered {
            if let ticket = tickets.first(where: { $0.id == record.id }) {
                var queue = record.queue ?? DirectFileQueueState(ordinal: ticket.ordinal, reservedFutureBytes: ticket.reservedFutureBytes)
                queue.reservedFutureBytes = ticket.reservedFutureBytes
                record.queue = queue
                try await journal.save(record)
            }
            result.append(record)
        }
        return result
    }

    func reserveAdmission(id: String, outgoing: Bool, bytes: Int64, displayFilename: String? = nil, declaredBytes: Int64? = nil, chatId: String? = nil) throws -> DirectFileAdmissionTicket {
        try Task.checkCancellation()
        guard !admissionBlocked else { throw Failure.admissionUnavailable }
        try ensureRoot()
        return try journal.allocation.reserve(id: id, outgoing: outgoing, bytes: bytes, displayFilename: displayFilename, declaredBytes: declaredBytes, chatId: chatId)
    }
    func admissionTickets() throws -> [DirectFileAdmissionTicket] { try journal.allocation.tickets() }
    func releaseAdmission(_ id: String) throws {
        // A blocked ledger is evidence, never rewritten even to release credit.
        guard !admissionBlocked else { return }
        try journal.allocation.release(id)
    }

    func save(_ record: DirectFileRecord) async throws { try await journal.save(record) }
    func prepareSource(id: String, source: URL) async throws -> Prepared {
        guard !admissionBlocked else { throw Failure.admissionUnavailable }
        try Task.checkCancellation()
        guard source.isFileURL else { throw Failure.invalid }
        let temporary = try path(id, "source.copying", create: true)
        let final = try path(id, "source.bin")
        guard !FileManager.default.fileExists(atPath: final.path) else { throw Failure.invalid }
        let lease = UUID(); fileLeases[id] = lease
        try requireSpace(0)
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        let available = (attributes[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        let copyLimit = min(maximumBytes, max(0, available - 64 * 1024 * 1024))
        guard copyLimit > 0 else { throw Failure.noSpace }
        var committed = false
        defer {
            if !committed {
                try? FileManager.default.removeItem(at: temporary)
                if let folder = try? transferDirectory(id),
                   (try? FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty) == true {
                    _ = Darwin.rmdir(folder.path)
                    ownedDirectories[id] = nil; createdDirectories.remove(id)
                }
            }
        }
        let allocation = journal.allocation
        let reader = AttachmentFileCopy.DocumentRead()
        let prepared = try await bulk.run(lane: .staging, id: id, onCancel: { reader.cancel() }) {
            // FileProvider access belongs to the awaiting caller until this
            // detached, coordinated reader and its hash have actually exited.
            let initial = try reader.size(of: source)
            if let initial {
                guard initial >= 0, initial <= copyLimit else { throw Failure.noSpace }
                try allocation.rebase(id, allocatedBytes: 0, futureBytes: initial)
            }
            try DirectFileTiming.measure(.sourceCopy, id: id, outgoing: true, bytes: initial ?? 0) {
                try reader.copy(from: source, to: temporary, maxBytes: copyLimit) { total, before in
                    if before { try allocation.beforeWrite(id, total: total) }
                    else { try allocation.didWrite(id, total: total) }
                }
            }
            guard let count = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  count > 0, initial == nil || initial == 0 || Int64(count) == initial else { throw Failure.changed }
            try allocation.rebase(id, allocatedBytes: Int64(count), futureBytes: 0)
            let hash = try DirectFileTiming.measure(.sourceHash, id: id, outgoing: true, bytes: Int64(count)) {
                try DirectFileBulkIO.digest(file: temporary, bytes: Int64(count))
            }
            let writer = try FileHandle(forWritingTo: temporary)
            defer { try? writer.close() }
            try DirectFileTiming.measure(.sourceFsync, id: id, outgoing: true, bytes: Int64(count)) { try writer.synchronize() }
            return Prepared(bytes: Int64(count), digest: hash)
        }
        try Task.checkCancellation()
        guard !retired, fileLeases[id] == lease else { throw CancellationError() }
        try FileManager.default.moveItem(at: temporary, to: final)
        try FileManager.default.setAttributes([.posixPermissions: 0o400,
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: final.path)
        committed = true
        return prepared
    }
    func sourceChunk(_ record: DirectFileRecord, offset: Int64, execution: DirectFileExecutionPermit? = nil) throws -> Data {
        guard execution?.allowed != false else { throw CancellationError() }
        try Task.checkCancellation()
        guard record.outgoing, offset >= 0, offset < record.offer.size else { throw Failure.offset }
        let file = try path(record.id, "source.bin")
        let handle: FileHandle
        if let existing = readerHandles[record.id] { handle = existing }
        else {
            guard try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == Int(record.offer.size) else { throw Failure.changed }
            handle = try FileHandle(forReadingFrom: file); readerHandles[record.id] = handle; readerOffsets[record.id] = 0
        }
        if readerOffsets[record.id] != offset { try handle.seek(toOffset: UInt64(offset)) }
        let size = Int(min(Int64(DirectFilePacket.chunkBytes), record.offer.size - offset))
        guard let bytes = try handle.read(upToCount: size), bytes.count == size else { throw Failure.changed }
        readerOffsets[record.id] = offset + Int64(bytes.count)
        return bytes
    }

    /// Resume starts at a durable checkpoint only. Any unacknowledged suffix
    /// from a terminated process is truncated instead of falsely claimed.
    func prepareReceiver(_ record: DirectFileRecord) async throws -> Checkpoint {
        try Task.checkCancellation()
        guard !admissionBlocked else { throw Failure.admissionUnavailable }
        guard !record.outgoing, !record.phase.terminal else { throw Failure.invalid }
        try requireSpace(record.offer.size - record.durableOffset)
        let timing = DirectFileTiming.begin(.receiverPreparation, id: record.id, outgoing: false, bytes: record.durableOffset)
        var timingSuccess = false
        defer { DirectFileTiming.end(timing, success: timingSuccess) }
        let file = try path(record.id, "received.part", create: true)
        if !FileManager.default.fileExists(atPath: file.path) {
            guard record.durableOffset == 0, FileManager.default.createFile(atPath: file.path, contents: nil,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw Failure.missing }
        }
        let lease = UUID(); fileLeases[record.id] = lease
        let handle = try FileHandle(forUpdating: file)
        do {
            let length = try handle.seekToEnd()
            guard length >= UInt64(record.durableOffset) else { throw Failure.corrupt }
            try handle.truncate(atOffset: UInt64(record.durableOffset))
            try handle.synchronize()
            try journal.allocation.rebase(record.id, allocatedBytes: record.durableOffset,
                futureBytes: record.offer.size - record.durableOffset)
            try handle.seek(toOffset: UInt64(record.durableOffset))
            let bytes = record.durableOffset
            let hash = try await bulk.run(lane: .inbound, id: record.id) { try DirectFileBulkIO.digest(file: file, bytes: bytes) }
            try Task.checkCancellation()
            guard !retired, fileLeases[record.id] == lease else { throw CancellationError() }
            try handles.removeValue(forKey: record.id)?.close()
            handles[record.id] = handle
            receivedOffsets[record.id] = record.durableOffset
            lastCheckpointOffsets[record.id] = record.durableOffset
            timingSuccess = true
            return Checkpoint(offset: record.durableOffset, prefixSHA256: hash)
        } catch { try? handle.close(); throw error }
    }

    /// An atomic rename and a journal update cannot form one filesystem
    /// transaction. A verified final original is therefore the authoritative
    /// recovery fact if termination occurred between those two operations.
    func recoverCompleted(_ record: DirectFileRecord, persistJournal: Bool = true) async throws -> DirectFileRecord? {
        guard !record.outgoing else { return nil }
        let final = try path(record.id, "received.bin")
        guard FileManager.default.fileExists(atPath: final.path) else {
            if record.phase == .completed { throw Failure.missing }
            return nil
        }
        guard try final.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]).isRegularFile == true,
              try final.resourceValues(forKeys: [.fileSizeKey]).fileSize == Int(record.offer.size) else { throw Failure.corrupt }
        // The durable completed journal is the same verification fact trusted
        // by manual opening. Rehash only the rename-before-journal crash gap;
        // do not reread 64GiB every time this account service is created.
        if record.phase != .completed {
            let bytes = record.offer.size
            let lease = fileLeases[record.id] ?? UUID(); fileLeases[record.id] = lease
            let hash = try await bulk.run(lane: .inbound, id: record.id) { try DirectFileBulkIO.digest(file: final, bytes: bytes) }
            guard !retired, fileLeases[record.id] == lease else { throw CancellationError() }
            guard hash == record.offer.sha256 else { throw Failure.corrupt }
        }
        try Task.checkCancellation()
        var completed = record; completed.phase = .completed; completed.durableOffset = record.offer.size
        completed.updatedAt = Int64(Date().timeIntervalSince1970)
        if persistJournal { try await save(completed) }
        return completed
    }

    func beginAttempt(_ record: DirectFileRecord) throws {
        try Task.checkCancellation()
        guard !admissionBlocked else { throw Failure.admissionUnavailable }
        try writeProgress(record, offset: record.durableOffset)
    }

    func senderAcknowledged(_ record: DirectFileRecord, offset: Int64, execution: DirectFileExecutionPermit? = nil) throws {
        guard execution?.allowed != false else { throw CancellationError() }
        guard record.outgoing else { throw Failure.invalid }
        try writeProgress(record, offset: offset)
    }

    func durableOffset(_ record: DirectFileRecord) throws -> Int64 {
        if let offset = try? verifiedProgressOffset(record) { return max(record.durableOffset, offset) }
        return record.durableOffset
    }

    private func writeProgress(_ record: DirectFileRecord, offset: Int64) throws {
        guard offset >= 0, offset <= record.offer.size else { throw Failure.offset }
        let checkpoint = StoredProgress(attemptId: record.attemptId,
            offerClientMessageId: record.offer.offerClientMessageId, size: record.offer.size,
            sha256: record.offer.sha256, offset: offset, chatId: record.chatId,
            localDeviceId: Self.canonicalUUID(record.localDeviceId) ? record.localDeviceId : nil)
        try JSONEncoder().encode(checkpoint).write(to: path(record.id, "progress.json", create: true),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func readProgress(_ record: DirectFileRecord) throws -> StoredProgress {
        let file = try path(record.id, "progress.json")
        guard let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 2048 else { throw Failure.invalid }
        let checkpoint = try JSONDecoder().decode(StoredProgress.self, from: Data(contentsOf: file))
        guard checkpoint.offerClientMessageId == record.offer.offerClientMessageId,
              checkpoint.sha256 == record.offer.sha256, checkpoint.size == record.offer.size,
              checkpoint.offset >= 0, checkpoint.offset <= checkpoint.size,
              checkpoint.attemptId == nil || Self.canonicalUUID(checkpoint.attemptId!),
              checkpoint.chatId == nil || (Self.canonicalUUID(checkpoint.chatId!) && checkpoint.chatId == record.chatId),
              checkpoint.localDeviceId == nil || (Self.canonicalUUID(checkpoint.localDeviceId!) && checkpoint.localDeviceId == record.localDeviceId)
              else { throw Failure.invalid }
        return checkpoint
    }

    private static func canonicalUUID(_ value: String) -> Bool {
        UUID(uuidString: value)?.uuidString.lowercased() == value
    }

    /// A retired-attempt checkpoint is a private fsynced byte fact, never a
    /// negotiation, consent or history authority. Older checkpoints cannot
    /// reduce the journal, and terminal receipts remain unchanged.
    private func verifiedProgressOffset(_ record: DirectFileRecord) throws -> Int64 {
        guard !record.phase.terminal else { return record.durableOffset }
        let progress = try readProgress(record)
        if progress.attemptId != record.attemptId {
            guard record.attemptId == nil, let retiredAttempt = progress.attemptId,
                  record.usedAttempts.contains(retiredAttempt),
                  progress.chatId == record.chatId, progress.localDeviceId == record.localDeviceId,
                  Self.canonicalUUID(record.chatId), Self.canonicalUUID(record.localDeviceId)
                  else { throw Failure.invalid }
        }
        let folder = try transferDirectory(record.id)
        let file = folder.appendingPathComponent(record.outgoing ? "source.bin" : "received.part")
        var folderInfo = stat(), fileInfo = stat()
        guard Darwin.lstat(folder.path, &folderInfo) == 0,
              folderInfo.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR),
              Darwin.lstat(file.path, &fileInfo) == 0,
              fileInfo.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              fileInfo.st_size >= progress.offset, fileInfo.st_size <= record.offer.size,
              !record.outgoing || fileInfo.st_size == record.offer.size else { throw Failure.invalid }
        return max(record.durableOffset, progress.offset)
    }

    func verifySourcePrefix(_ record: DirectFileRecord, offset: Int64, sha256: String) async throws -> Bool {
        guard record.outgoing, offset >= 0, offset <= record.offer.size else { return false }
        let file = try path(record.id, "source.bin")
        guard try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == Int(record.offer.size) else { return false }
        let lease = fileLeases[record.id] ?? UUID(); fileLeases[record.id] = lease
        let hash = try await bulk.run(lane: .outbound, id: record.id) {
            try DirectFileTiming.measure(.sourcePrefix, id: record.id, outgoing: true, bytes: offset) {
                try DirectFileBulkIO.digest(file: file, bytes: offset)
            }
        }
        try Task.checkCancellation()
        guard !retired, fileLeases[record.id] == lease else { throw CancellationError() }
        return hash == sha256
    }

    /// Returns a new durable offset only after file synchronization AND atomic
    /// journal publication. The UI/network actor may acknowledge only this value.
    func append(_ frame: DirectFilePacket.Frame, record: DirectFileRecord, execution: DirectFileExecutionPermit? = nil) throws -> Int64? {
        guard execution?.allowed != false else { throw CancellationError() }
        try Task.checkCancellation()
        guard !record.outgoing, frame.kind == .chunk, !record.phase.terminal,
              let handle = handles[record.id], let current = receivedOffsets[record.id],
              frame.offset == current, Int64(frame.body.count) <= record.offer.size - current else { throw Failure.offset }
        let next = current + Int64(frame.body.count)
        try journal.allocation.beforeWrite(record.id, total: next)
        try handle.write(contentsOf: frame.body)
        try journal.allocation.didWrite(record.id, total: next)
        receivedOffsets[record.id] = next
        let last = lastCheckpointOffsets[record.id] ?? record.durableOffset
        guard next - last >= checkpointBytes || next == record.offer.size else { return nil }
        return try checkpoint(record)
    }

    /// The coordinator also invokes this at most once per second and before
    /// pausing, so a slow link never has to fill 256KiB to obtain a durable ACK.
    func checkpoint(_ record: DirectFileRecord) throws -> Int64? {
        guard !record.outgoing, let handle = handles[record.id],
              let next = receivedOffsets[record.id], next > (lastCheckpointOffsets[record.id] ?? record.durableOffset) else { return nil }
        try handle.synchronize()
        // Bulk IO updates its own tiny checkpoint instead of overwriting the
        // independently serialized Signal/control journal or hopping to UI.
        try writeProgress(record, offset: next)
        lastCheckpointOffsets[record.id] = next
        if next == record.offer.size { try journal.allocation.didWrite(record.id, total: next, flush: true) }
        return next
    }

    func finishReceiver(_ record: DirectFileRecord, digest expected: Data, execution: DirectFileExecutionPermit? = nil) async throws -> URL {
        guard execution?.allowed != false else { throw CancellationError() }
        try Task.checkCancellation()
        guard !record.outgoing, expected.count == 32, Self.hex(expected) == record.offer.sha256 else { throw Failure.corrupt }
        if let _ = try await recoverCompleted(record, persistJournal: false) { return try path(record.id, "received.bin") }
        guard
              receivedOffsets[record.id] == record.offer.size,
              lastCheckpointOffsets[record.id] == record.offer.size else { throw Failure.offset }
        let partial = try path(record.id, "received.part")
        if let handle = handles.removeValue(forKey: record.id) { try handle.synchronize(); try handle.close() }
        let lease = fileLeases[record.id] ?? UUID(); fileLeases[record.id] = lease
        let bytes = record.offer.size
        let actual = try await bulk.run(lane: .inbound, id: record.id) {
            guard execution?.allowed != false else { throw CancellationError() }
            return try DirectFileTiming.measure(.finalReceiverHash, id: record.id, outgoing: false, bytes: bytes) {
                try DirectFileBulkIO.digest(file: partial, bytes: bytes)
            }
        }
        guard execution?.allowed != false else { throw CancellationError() }
        try Task.checkCancellation()
        guard !retired, fileLeases[record.id] == lease else { throw CancellationError() }
        guard actual == record.offer.sha256, Self.hex(expected) == actual else { throw Failure.corrupt }
        let final = try path(record.id, "received.bin")
        guard !FileManager.default.fileExists(atPath: final.path) else { throw Failure.invalid }
        try FileManager.default.moveItem(at: partial, to: final)
        receivedOffsets[record.id] = nil; lastCheckpointOffsets[record.id] = nil
        return final
    }

    /// A temporary local alias lets native container readers identify the
    /// already-owned immutable source. No original copy/upload is created.
    func offerPreview(id: String, filename: String, mimeType: String, bytes: Int64) async -> DirectFileMediaPreview? {
        guard !retired, bytes > 0, bytes <= maximumBytes,
              mimeType.hasPrefix("image/") || mimeType.hasPrefix("video/"),
              let lease = fileLeases[id], let source = try? path(id, "source.bin"),
              (try? source.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { return nil }
        var input = source
        var alias: URL?
        let rawExtension = (filename as NSString).pathExtension.lowercased()
        let ext = UTType(filenameExtension: rawExtension)?.preferredFilenameExtension ?? rawExtension
        if !ext.isEmpty, ext.count <= 16, ext.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }),
           let named = try? path(id, "poster-source." + ext), !FileManager.default.fileExists(atPath: named.path) {
            do { try FileManager.default.linkItem(at: source, to: named); input = named; alias = named }
            catch { return nil }
        }
        defer { if let alias { try? FileManager.default.removeItem(at: alias) } }
        let timing = DirectFileTiming.begin(.optionalPreview, id: id, outgoing: true, bytes: bytes)
        let preview = await DirectFileMediaPreviewGenerator.read(input, mimeType: mimeType, sourceBytes: bytes)
        DirectFileTiming.end(timing, success: !Task.isCancelled)
        guard !retired, !Task.isCancelled, fileLeases[id] == lease else { return nil }
        return preview
    }

    func completedURL(_ record: DirectFileRecord) throws -> URL? {
        guard record.phase == .completed else { return nil }
        let file = try path(record.id, record.outgoing ? "source.bin" : "received.bin")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let ext = UTType(mimeType: record.offer.mimeType)?.preferredFilenameExtension ?? "bin"
        guard ext.count <= 16, ext.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return file }
        let preview = try path(record.id, "preview." + ext)
        // A hard link avoids duplicating a multi-GiB original merely to give
        // the native viewer a vetted, internally generated file extension.
        if !FileManager.default.fileExists(atPath: preview.path) { try FileManager.default.linkItem(at: file, to: preview) }
        return preview
    }

    /// A single reusable, correctly named alias for Share. Open keeps its vetted
    /// preview extension. All paths remain inside this transfer's private folder;
    /// no original bytes are copied, renamed, overwritten, or rehashed here.
    func completedShareURL(_ record: DirectFileRecord) throws -> URL? {
        try Task.checkCancellation()
        guard !retired, record.phase == .completed, record.offer.hasValidShape,
              record.offer.size <= maximumBytes else { return nil }
        let folder = try transferDirectory(record.id)
        let expected = directory.resolvingSymlinksInPath().appendingPathComponent(record.id, isDirectory: true)
        guard folder.resolvingSymlinksInPath() == expected else { throw Failure.invalid }
        let folderFD = Darwin.open(folder.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard folderFD >= 0 else { throw Failure.invalid }
        defer { Darwin.close(folderFD) }
        var folderInfo = stat()
        guard Darwin.fstat(folderFD, &folderInfo) == 0 else { throw Failure.invalid }
        let canonical = record.outgoing ? "source.bin" : "received.bin"
        let sourceFD = Darwin.openat(folderFD, canonical, O_RDONLY | O_NOFOLLOW)
        if sourceFD < 0 {
            if errno == ENOENT { return nil }
            throw Failure.invalid
        }
        defer { Darwin.close(sourceFD) }
        var sourceInfo = stat()
        guard Darwin.fstat(sourceFD, &sourceInfo) == 0,
              sourceInfo.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              sourceInfo.st_size == record.offer.size else { throw Failure.changed }
        let exportName = "share-original"
        if Darwin.mkdirat(folderFD, exportName, 0o700) != 0, errno != EEXIST { throw Failure.invalid }
        let exportFD = Darwin.openat(folderFD, exportName, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard exportFD >= 0 else { throw Failure.invalid }
        defer { Darwin.close(exportFD) }
        var exportInfo = stat()
        guard Darwin.fstat(exportFD, &exportInfo) == 0,
              exportInfo.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR),
              exportInfo.st_dev == sourceInfo.st_dev else { throw Failure.invalid }
        // At most one original-name link per transfer, including after restart.
        // Stop at the first unexpected entry; never enumerate an unbounded list.
        let listingFD = Darwin.dup(exportFD)
        guard listingFD >= 0 else { throw Failure.invalid }
        guard let listing = Darwin.fdopendir(listingFD) else { Darwin.close(listingFD); throw Failure.invalid }
        defer { Darwin.closedir(listing) }
        var found = false
        while true {
            errno = 0
            guard let entry = Darwin.readdir(listing) else {
                guard errno == 0 else { throw Failure.invalid }; break
            }
            var rawName = entry.pointee.d_name
            let capacity = MemoryLayout.size(ofValue: rawName)
            let name = withUnsafePointer(to: &rawName) {
                $0.withMemoryRebound(to: CChar.self, capacity: capacity) { String(cString: $0) }
            }
            if name == "." || name == ".." { continue }
            guard !found, name == record.offer.filename else { throw Failure.changed }
            found = true
        }
        if Darwin.linkat(folderFD, canonical, exportFD, record.offer.filename, 0) != 0,
           errno != EEXIST { throw Failure.invalid }
        var aliasInfo = stat(), sourceNow = stat(), exportNow = stat(), folderNow = stat()
        guard Darwin.fstatat(exportFD, record.offer.filename, &aliasInfo, AT_SYMLINK_NOFOLLOW) == 0,
              aliasInfo.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              aliasInfo.st_ino == sourceInfo.st_ino, aliasInfo.st_dev == sourceInfo.st_dev,
              aliasInfo.st_size == sourceInfo.st_size,
              Darwin.fstatat(folderFD, canonical, &sourceNow, AT_SYMLINK_NOFOLLOW) == 0,
              sourceNow.st_ino == sourceInfo.st_ino, sourceNow.st_dev == sourceInfo.st_dev,
              sourceNow.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              Darwin.fstatat(folderFD, exportName, &exportNow, AT_SYMLINK_NOFOLLOW) == 0,
              exportNow.st_ino == exportInfo.st_ino, exportNow.st_dev == exportInfo.st_dev,
              exportNow.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR),
              Darwin.lstat(folder.path, &folderNow) == 0,
              folderNow.st_ino == folderInfo.st_ino, folderNow.st_dev == folderInfo.st_dev,
              folderNow.st_mode & mode_t(S_IFMT) == mode_t(S_IFDIR) else { throw Failure.changed }
        try Task.checkCancellation()
        let result = folder.appendingPathComponent(exportName, isDirectory: true)
            .appendingPathComponent(record.offer.filename, isDirectory: false)
        return result
    }

    func suspend(_ id: String) async {
        if let offset = receivedOffsets[id] { try? journal.allocation.didWrite(id, total: offset, flush: true) }
        fileLeases[id] = UUID()
        await bulk.cancelAndDrain(id)
        try? handles.removeValue(forKey: id)?.close()
        try? readerHandles.removeValue(forKey: id)?.close()
        receivedOffsets[id] = nil; lastCheckpointOffsets[id] = nil
        readerOffsets[id] = nil
    }

    func closeAll() async {
        for id in Set(handles.keys).union(readerHandles.keys).union(fileLeases.keys) { await suspend(id) }
    }

    func retire() async { await closeAll(); retired = true; await bulk.retire(); await journal.retire() }

    /// Only immutable source/partial bytes inside this transfer's owned folder
    /// are removable. A received complete original is retained for the user.
    func discardUnfinished(_ record: DirectFileRecord) async throws {
        await suspend(record.id)
        guard record.phase != .completed else { return }
        for name in ["source.copying", "source.bin", "received.part"] {
            let file = try path(record.id, name)
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        }
        try releaseAdmission(record.id)
    }

    func discardPreparedSource(id: String) async throws {
        fileLeases[id] = UUID()
        await bulk.cancelAndDrain(id)
        guard !FileManager.default.fileExists(atPath: try path(id, "record.json").path) else { return }
        try releaseAdmission(id)
        for name in ["source.copying", "source.bin"] {
            let file = try path(id, name)
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        }
        let folder = try transferDirectory(id)
        if try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty {
            _ = Darwin.rmdir(folder.path)
            ownedDirectories[id] = nil; createdDirectories.remove(id)
        }
    }

    private func requireSpace(_ bytes: Int64) throws {
        try ensureRoot()
        let values = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        guard let available = (values[.systemFreeSize] as? NSNumber)?.int64Value,
              bytes >= 0, bytes <= available - min(64 * 1024 * 1024, available) else { throw Failure.noSpace }
    }

    private static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
