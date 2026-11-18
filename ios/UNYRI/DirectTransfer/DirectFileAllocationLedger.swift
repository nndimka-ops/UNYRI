import Foundation

/// A data-container UUID may change during an iOS update. Keep every other
/// canonical component (including device/root, server and account) in scope.
private struct DirectFileAccountNamespace {
    let stable: String
    private let components: [String]
    private let containerIndex: Int

    private static func canonicalParts(_ url: URL) -> [String]? {
        let raw = url.pathComponents
        let canonical = url.resolvingSymlinksInPath().pathComponents
        if canonical == raw { return canonical }
        // This is the known iOS system alias, not arbitrary caller-supplied rebasing.
        if raw.count >= 2, raw[0] == "/", raw[1] == "var",
           canonical == ["/", "private"] + Array(raw.dropFirst()) { return canonical }
        return nil
    }

    init?(accountDirectory: URL) throws {
        guard accountDirectory.isFileURL else { throw DirectFileAllocationLedger.Failure.invalid }
        let raw = accountDirectory.standardizedFileURL
        let rawParts = raw.pathComponents
        let anchors = rawParts.indices.filter { index in
            index + 2 < rawParts.count && Array(rawParts[index...(index + 2)]) == ["Containers", "Data", "Application"]
        }
        guard !anchors.isEmpty else { return nil } // Non-container QA roots keep exact-path scope.
        guard anchors.count == 1 else { throw DirectFileAllocationLedger.Failure.invalid }
        let index = anchors[0] + 3
        guard rawParts.count == index + 6, UUID(uuidString: rawParts[index]) != nil,
              Array(rawParts[(index + 1)...(index + 3)]) == ["Library", "Application Support", "COCO"],
              UUID(uuidString: rawParts[index + 5])?.uuidString.lowercased() == rawParts[index + 5],
              let origin = Data(base64Encoded: rawParts[index + 4].replacingOccurrences(of: "_", with: "/")),
              !origin.isEmpty, String(data: origin, encoding: .utf8) != nil,
              origin.base64EncodedString().replacingOccurrences(of: "/", with: "_") == rawParts[index + 4] else {
            throw DirectFileAllocationLedger.Failure.invalid
        }
        // Do not reject a system /var -> /private/var alias above the container.
        // Every owned directory from the container down must itself be a directory.
        var owned = raw
        for _ in index..<rawParts.count {
            let values = try owned.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isDirectory == true, values.isSymbolicLink != true else { throw DirectFileAllocationLedger.Failure.invalid }
            owned.deleteLastPathComponent()
        }
        guard let canonical = Self.canonicalParts(raw) else { throw DirectFileAllocationLedger.Failure.invalid }
        let canonicalIndex = canonical.count - 6
        guard canonicalIndex >= 3,
              Array(canonical[(canonicalIndex - 3)..<canonicalIndex]) == ["Containers", "Data", "Application"],
              Array(canonical[canonicalIndex...]) == Array(rawParts[index...]) else { throw DirectFileAllocationLedger.Failure.invalid }
        components = canonical; containerIndex = canonicalIndex
        stable = "mcm-account-v2:" + canonical.enumerated().map { $0.offset == canonicalIndex ? "<data-container>" : $0.element }.joined(separator: "/")
    }

    func acceptsLegacy(_ stored: String) -> Bool {
        guard !stored.contains("\0"), !stored.contains("\\"), !stored.contains("//"),
              !stored.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else { return false }
        let oldURL = URL(fileURLWithPath: stored).standardizedFileURL
        guard oldURL.path == stored else { return false } // Exact absolute path in the current platform's URL syntax.
        guard let old = Self.canonicalParts(oldURL) else { return false }
        guard old.count == components.count,
              Array(old.prefix(containerIndex)) == Array(components.prefix(containerIndex)),
              UUID(uuidString: old[containerIndex]) != nil,
              Array(old.dropFirst(containerIndex + 1)) == Array(components.dropFirst(containerIndex + 1)) else { return false }
        // The currently owned private ledger and exact root/server/account
        // binding are the authority. Another container is outside this app's
        // sandbox; its presence/absence cannot authenticate a relocation.
        // As in the v2 header, the data-container UUID is deliberately not identity.
        return true
    }
}

/// One account-owned, volume-local reservation ledger shared by short IO and
/// the coordinated source worker. All callbacks are synchronous, take this
/// short lock only, and never await an actor/network/control queue.
final class DirectFileAllocationLedger: @unchecked Sendable {
    enum Failure: Error { case invalid, full, space, exhausted, retired }
    private let lock = NSLock()
    private let root: URL
    private var namespace: String
    private let availableBytes: @Sendable (URL) throws -> Int64
    private var ledger: DirectFileAdmissionLedger?
    private var allocated: [String: Int64] = [:]
    private var lastPersistedAllocation: [String: Int64] = [:]
    private var retired = false
    private var lastSpaceCheck: [String: Int64] = [:]
    private let margin: Int64 = 64 * 1024 * 1024
    private let window: Int64 = 1024 * 1024

    init(accountDirectory: URL, availableBytes: @escaping @Sendable (URL) throws -> Int64 = { directory in
        let attrs = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        guard let bytes = (attrs[.systemFreeSize] as? NSNumber)?.int64Value else { throw Failure.space }
        return bytes
    }) {
        self.availableBytes = availableBytes
        root = accountDirectory.appendingPathComponent("direct-transfers", isDirectory: true)
        namespace = accountDirectory.standardizedFileURL.path
    }
    private func locked<T>(_ operation: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try operation()
    }
    private var file: URL { root.appendingPathComponent("admission.json") }
    private func load() throws -> DirectFileAdmissionLedger {
        guard !retired else { throw Failure.retired }
        if let ledger { return ledger }
        // Validate the existing account before createDirectory can follow a
        // foreign parent link. AppStores creates this account directory first.
        let accountScope = try DirectFileAccountNamespace(accountDirectory: root.deletingLastPathComponent())
        namespace = accountScope?.stable ?? root.deletingLastPathComponent().standardizedFileURL.path
        guard (try? FileManager.default.destinationOfSymbolicLink(atPath: root.path)) == nil,
              (try? FileManager.default.destinationOfSymbolicLink(atPath: file.path)) == nil else { throw Failure.invalid }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let rootValues = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else { throw Failure.invalid }
        if FileManager.default.fileExists(atPath: file.path) {
            let values = try file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, let bytes = values.fileSize,
                  bytes <= 64 * 1024 else { throw Failure.invalid }
            let data = try Data(contentsOf: file)
            let decoded = try JSONDecoder().decode(DirectFileAdmissionLedger.self, from: data)
            guard decoded.valid else { throw Failure.invalid }
            if decoded.accountNamespace == namespace { ledger = decoded; return decoded }
            guard let accountScope, accountScope.acceptsLegacy(decoded.accountNamespace) else { throw Failure.invalid }
            var migrated = decoded; migrated.accountNamespace = namespace
            try persist(migrated) // Atomic header migration; every ticket and credit is preserved.
            return migrated
        }
        var created = DirectFileAdmissionLedger(); created.accountNamespace = namespace
        ledger = created; return created
    }
    private func persist(_ next: DirectFileAdmissionLedger) throws {
        guard next.valid, next.accountNamespace == namespace else { throw Failure.invalid }
        let bytes = try JSONEncoder().encode(next)
        guard bytes.count <= 64 * 1024 else { throw Failure.invalid }
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        ledger = next
    }
    private func space(for additional: Int64, in value: DirectFileAdmissionLedger) throws {
        guard additional >= 0 else { throw Failure.invalid }
        var future = additional
        for ticket in value.tickets {
            let sum = future.addingReportingOverflow(ticket.reservedFutureBytes)
            guard !sum.overflow else { throw Failure.invalid }; future = sum.partialValue
        }
        let available = try availableBytes(root)
        guard available >= margin, future <= available - margin else { throw Failure.space }
    }
    private func nextOrdinal(_ value: inout DirectFileAdmissionLedger, outgoing: Bool) throws -> Int64 {
        let next = outgoing ? value.nextOutboundOrdinal : value.nextInboundOrdinal
        guard next > 0, next < Int64.max else { throw Failure.exhausted }
        if outgoing { value.nextOutboundOrdinal = next + 1 } else { value.nextInboundOrdinal = next + 1 }
        return next
    }

    /// Rebuild only from trusted private journals/owned file lengths. Missing
    /// pre-offer source tickets stay visible as re-selection work, never offers.
    func reconcile(_ records: [DirectFileRecord]) throws -> [DirectFileAdmissionTicket] {
        try locked {
            var value = try load()
            // Reconcile counter rollback before allocating any legacy ordinal.
            for outgoing in [false, true] {
                let maximum = (value.tickets.filter { $0.outgoing == outgoing }.map(\.ordinal) +
                    records.filter { $0.outgoing == outgoing }.compactMap { $0.queue?.ordinal }).max() ?? 0
                guard maximum < Int64.max else { throw Failure.exhausted }
                if outgoing { value.nextOutboundOrdinal = max(value.nextOutboundOrdinal, maximum + 1) }
                else { value.nextInboundOrdinal = max(value.nextInboundOrdinal, maximum + 1) }
            }
            let ids = Set(records.map(\.id))
            for index in value.tickets.indices where !ids.contains(value.tickets[index].id) {
                value.tickets[index].needsReselection = true
                value.tickets[index].reservedFutureBytes = 0
            }
            for record in records {
                let retain = record.outgoing
                    ? !record.mailboxClosed && (!record.phase.terminal || record.phase == .completed)
                    : record.consentGiven && !record.phase.terminal && !record.mailboxClosed
                if !retain { value.tickets.removeAll { $0.id == record.id }; continue }
                let existing = value.tickets.first { $0.id == record.id }
                let ordinal: Int64
                if let known = record.queue?.ordinal ?? existing?.ordinal { ordinal = known }
                else { ordinal = try nextOrdinal(&value, outgoing: record.outgoing) }
                guard ordinal > 0 else { throw Failure.invalid }
                let folder = root.appendingPathComponent(record.id, isDirectory: true)
                guard folder.resolvingSymlinksInPath().deletingLastPathComponent() == root.resolvingSymlinksInPath() else { throw Failure.invalid }
                let source = folder.appendingPathComponent(record.outgoing ? "source.bin" : "received.part")
                let actual = min(record.offer.size, max(0, Int64((try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)))
                allocated[record.id] = actual
                value.tickets.removeAll { $0.id == record.id }
                value.tickets.append(DirectFileAdmissionTicket(id: record.id, outgoing: record.outgoing,
                    ordinal: ordinal, reservedFutureBytes: record.offer.size - actual,
                    displayFilename: record.offer.filename, declaredBytes: record.offer.size, chatId: record.chatId))
            }
            for outgoing in [false, true] {
                let maximum = value.tickets.filter { $0.outgoing == outgoing }.map(\.ordinal).max() ?? 0
                guard maximum < Int64.max else { throw Failure.exhausted }
                if outgoing { value.nextOutboundOrdinal = max(value.nextOutboundOrdinal, maximum + 1) }
                else { value.nextInboundOrdinal = max(value.nextInboundOrdinal, maximum + 1) }
            }
            try persist(value)
            return value.tickets
        }
    }

    func reserve(id: String, outgoing: Bool, bytes: Int64, displayFilename: String? = nil, declaredBytes: Int64? = nil, chatId: String? = nil) throws -> DirectFileAdmissionTicket {
        try locked {
            var value = try load()
            if let existing = value.tickets.first(where: { $0.id == id }) {
                guard existing.outgoing == outgoing else { throw Failure.invalid }; return existing
            }
            guard value.tickets.filter({ $0.outgoing == outgoing }).count < 8 else { throw Failure.full }
            guard bytes >= 0, bytes <= DirectFileOffer.maximumBytes else { throw Failure.invalid }
            // Unknown-size sources receive one growing bounded window.
            let allowance = outgoing && bytes == 0 ? window : bytes
            try space(for: allowance, in: value)
            let ticket = DirectFileAdmissionTicket(id: id, outgoing: outgoing,
                ordinal: try nextOrdinal(&value, outgoing: outgoing), reservedFutureBytes: allowance,
                displayFilename: displayFilename, declaredBytes: declaredBytes, chatId: chatId)
            guard ticket.valid else { throw Failure.invalid }
            value.tickets.append(ticket); try persist(value); allocated[id] = 0
            return ticket
        }
    }

    func beforeWrite(_ id: String, total: Int64) throws {
        try Task.checkCancellation()
        try locked {
            var value = try load()
            guard let index = value.tickets.firstIndex(where: { $0.id == id }),
                  total >= (allocated[id] ?? 0), total <= DirectFileOffer.maximumBytes else { throw Failure.invalid }
            let required = total - (allocated[id] ?? 0)
            if required > value.tickets[index].reservedFutureBytes {
                let growth = max(window, required - value.tickets[index].reservedFutureBytes)
                try space(for: growth, in: value)
                let sum = value.tickets[index].reservedFutureBytes.addingReportingOverflow(growth)
                guard !sum.overflow, sum.partialValue <= DirectFileOffer.maximumBytes else { throw Failure.invalid }
                value.tickets[index].reservedFutureBytes = sum.partialValue
                // Durable allowance precedes every write exceeding its window.
                try persist(value)
            } else if total - (lastSpaceCheck[id] ?? -window) >= window {
                try space(for: 0, in: value); lastSpaceCheck[id] = total
            }
        }
        try Task.checkCancellation()
    }
    func didWrite(_ id: String, total: Int64, flush: Bool = false) throws {
        try locked {
            var value = try load()
            guard let index = value.tickets.firstIndex(where: { $0.id == id }),
                  total >= (allocated[id] ?? 0) else { throw Failure.invalid }
            let delta = total - (allocated[id] ?? 0)
            guard delta <= value.tickets[index].reservedFutureBytes else { throw Failure.invalid }
            value.tickets[index].reservedFutureBytes -= delta; allocated[id] = total
            // Small bounded writes update RAM; at most each MiB journals the
            // conservative remaining reservation, plus final/pause checkpoints.
            ledger = value
            if flush || total - (lastPersistedAllocation[id] ?? 0) >= window {
                try persist(value); lastPersistedAllocation[id] = total
            }
        }
    }
    func rebase(_ id: String, allocatedBytes: Int64, futureBytes: Int64) throws {
        try locked {
            var value = try load()
            guard let index = value.tickets.firstIndex(where: { $0.id == id }),
                  allocatedBytes >= 0, futureBytes >= 0,
                  allocatedBytes <= DirectFileOffer.maximumBytes,
                  futureBytes <= DirectFileOffer.maximumBytes - allocatedBytes else { throw Failure.invalid }
            value.tickets[index].reservedFutureBytes = 0
            try space(for: futureBytes, in: value)
            value.tickets[index].reservedFutureBytes = futureBytes
            try persist(value); allocated[id] = allocatedBytes
        }
    }
    func release(_ id: String) throws {
        try locked {
            var value = try load(); value.tickets.removeAll { $0.id == id }
            try persist(value); allocated[id] = nil; lastPersistedAllocation[id] = nil; lastSpaceCheck[id] = nil
        }
    }
    func tickets() throws -> [DirectFileAdmissionTicket] { try locked { try load().tickets } }
    func retire() { locked { retired = true } }
}
