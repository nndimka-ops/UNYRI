import Foundation

/// Only static binary metadata is retained. No exception text, paths, URLs,
/// message content, registers, memory dumps or diagnostic userInfo are copied.
struct UnyriCrashRecord: Codable, Equatable, Identifiable, Sendable {
    struct Frame: Codable, Equatable, Sendable {
        let binary: String
        let uuid: UUID
        let offset: UInt64
        var valid: Bool {
            !binary.isEmpty && binary.utf8.count <= 96 && binary.utf8.allSatisfy {
                (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [43,45,46,95].contains($0)
            }
        }
    }
    let from: Date
    let until: Date
    let version: String
    let build: String
    let exception: Int?
    let signal: Int?
    let frames: [Frame]

    // This is only a local duplicate key, never an authentication capability.
    var id: String {
        let first = frames.first
        return "\(Int64(until.timeIntervalSince1970 * 1000))|\(version)|\(build)|\(exception ?? -1)|\(signal ?? -1)|\(first?.uuid.uuidString ?? "")|\(first?.offset ?? 0)"
    }
    var valid: Bool {
        func versionOK(_ text: String) -> Bool {
            !text.isEmpty && text.utf8.count <= 64 && text.utf8.allSatisfy {
                (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [43,45,46,95].contains($0)
            }
        }
        return from.timeIntervalSince1970.isFinite && until.timeIntervalSince1970.isFinite &&
            from.timeIntervalSince1970 >= 0 && until >= from && until.timeIntervalSince1970 < 100_000_000_000 &&
            versionOK(version) && versionOK(build) && !frames.isEmpty && frames.count <= 24 && frames.allSatisfy(\.valid) &&
            exception.map { (0...Int(Int32.max)).contains($0) } != false && signal.map { (0...255).contains($0) } != false
    }
    var reportDescription: String {
        let formatter = ISO8601DateFormatter()
        let interval = formatter.string(from: from) + " ... " + formatter.string(from: until)
        let rows = frames.map { "\($0.binary) \($0.uuid.uuidString) +0x\(String($0.offset, radix: 16))" }
        return (["[UNYRI system-confirmed crash v1]", "Reporting interval UTC: \(interval)", "Version: \(version) (\(build))",
                 "Exception: \(exception ?? -1); signal: \(signal ?? -1)"] + rows).joined(separator: "\n")
    }

    /// Iterative bounded traversal also tolerates unusually deep Swift stacks.
    static func frames(from data: Data) -> [Frame] {
        guard data.count <= 512 * 1024, let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let stacks = root["callStacks"] as? [[String: Any]] else { return [] }
        let attributed = stacks.filter { ($0["threadAttributed"] as? Bool) == true }
        let selected = attributed.isEmpty ? Array(stacks.prefix(1)) : Array(attributed.prefix(1))
        var work = selected.flatMap { $0["callStackRootFrames"] as? [[String: Any]] ?? [] }.prefix(1024).reversed().map { $0 }
        var app: [Frame] = [], system: [Frame] = [], visited = 0
        while let node = work.popLast(), visited < 1024 {
            visited += 1
            if let binary = node["binaryName"] as? String, let text = node["binaryUUID"] as? String, let uuid = UUID(uuidString: text),
               let number = node["offsetIntoBinaryTextSegment"] as? NSNumber,
               number.doubleValue.isFinite, number.doubleValue >= 0, number.doubleValue < Double(Int64.max),
               number.doubleValue.rounded(.towardZero) == number.doubleValue {
                let frame = Frame(binary: binary, uuid: uuid, offset: number.uint64Value)
                if frame.valid {
                    if ["UNYRI", "COCO"].contains(binary), app.count < 18 { app.append(frame) }
                    else if system.count < 6 { system.append(frame) }
                }
            }
            if let children = node["subFrames"] as? [[String: Any]] {
                work += children.prefix(max(0, 1024 - visited - work.count)).reversed()
            }
        }
        return app + system
    }
}

/// All disk operations occur on this actor, never during layout or on the main
/// actor. A session ticket fences late system callbacks and logout cleanup.
actor UnyriCrashArchive {
    struct Ticket: Equatable, Sendable { let owner: String; let epoch: UUID }
    private struct State: Codable {
        let owner: String
        let since: Date
        var records: [UnyriCrashRecord]
        var offered: [String]
    }
    private var ticket: Ticket?
    private var state: State?
    private let load: @Sendable () throws -> Data?
    private let save: @Sendable (Data?) throws -> Void
    init(load: @escaping @Sendable () throws -> Data?, save: @escaping @Sendable (Data?) throws -> Void) {
        self.load = load; self.save = save
    }
    func bind(owner: String?, now: Date = Date()) throws -> Ticket? {
        if let ticket, ticket.owner == owner { return ticket }
        ticket = nil; state = nil
        guard let owner, !owner.isEmpty, owner.utf8.count <= 2048 else { try save(nil); return nil }
        let data = try load()
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .millisecondsSince1970
        if let data, data.count <= 64 * 1024, var value = try? decoder.decode(State.self, from: data),
           value.owner == owner, value.since <= now, value.records.count <= 8, value.offered.count <= 32,
           value.records.allSatisfy(\.valid), value.offered.allSatisfy({ $0.utf8.count <= 256 }) {
            let since = value.since
            value.records.removeAll { now.timeIntervalSince($0.until) > 7 * 86_400 || $0.until > now.addingTimeInterval(60) || $0.from < since }
            state = value
        } else { state = State(owner: owner, since: now, records: [], offered: []) }
        try persist()
        let value = Ticket(owner: owner, epoch: UUID()); ticket = value; return value
    }
    func ingest(_ record: UnyriCrashRecord, ticket expected: Ticket, now: Date = Date()) throws {
        guard ticket == expected, var value = state, record.valid,
              // A mixed interval may include another login's diagnostics. Do
              // not attribute it to the new account merely because it arrived now.
              record.from >= value.since, record.until <= now.addingTimeInterval(60),
              now.timeIntervalSince(record.until) <= 7 * 86_400 else { return }
        guard !value.records.contains(where: { $0.id == record.id }) else { return }
        value.records.append(record); value.records.sort { $0.until > $1.until }
        value.records = Array(value.records.prefix(8)); state = value; try persist()
    }
    func pending(ticket expected: Ticket, onlyUnoffered: Bool) -> UnyriCrashRecord? {
        guard ticket == expected, let value = state else { return nil }
        return value.records.first { !onlyUnoffered || !value.offered.contains($0.id) }
    }
    func offered(_ id: String, ticket expected: Ticket) throws {
        guard ticket == expected, var value = state, value.records.contains(where: { $0.id == id }) else { return }
        if !value.offered.contains(id) { value.offered.append(id) }
        value.offered = Array(value.offered.suffix(32)); state = value; try persist()
    }
    func remove(_ id: String, ticket expected: Ticket) throws {
        guard ticket == expected, var value = state else { return }
        value.records.removeAll { $0.id == id }; state = value; try persist()
    }
    private func persist() throws {
        guard let state else { try save(nil); return }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .millisecondsSince1970
        let data = try encoder.encode(state)
        guard data.count <= 64 * 1024 else { throw CocoaError(.fileWriteOutOfSpace) }
        try save(data)
    }
}
