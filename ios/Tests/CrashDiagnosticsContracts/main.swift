import Foundation

private final class MemoryFile: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Data?
    func read() -> Data? { lock.lock(); defer { lock.unlock() }; return bytes }
    func write(_ data: Data?) { lock.lock(); defer { lock.unlock() }; bytes = data }
}

@main struct CrashContracts {
    static func main() async throws {
        var checks = 0
        func check(_ yes: Bool, _ label: String) { precondition(yes, label); checks += 1 }
        let uuid = UUID(uuidString: "00112233-4455-6677-8899-AABBCCDDEEFF")!
        let since = Date(timeIntervalSince1970: 1_790_000_000)
        let end = since.addingTimeInterval(30)
        var node: [String: Any] = ["binaryName": "COCO", "binaryUUID": uuid.uuidString, "offsetIntoBinaryTextSegment": 6258772,
                                   "exceptionReason": "PRIVATE_CONTENT_MUST_NOT_BE_STORED", "path": "https://secret.invalid/token"]
        for index in 0..<70 {
            node = ["binaryName": "libswiftCore.dylib", "binaryUUID": uuid.uuidString, "offsetIntoBinaryTextSegment": index,
                    "subFrames": [node]]
        }
        let data = try JSONSerialization.data(withJSONObject: ["callStacks": [["threadAttributed": true, "callStackRootFrames": [node]]]])
        let frames = UnyriCrashRecord.frames(from: data)
        check(frames.first?.binary == "COCO" && frames.first?.offset == 6258772, "deep generic stack still retains the app frame before bounded system context")
        check(frames.count == 7, "system frames are bounded independently of app frames")
        check(UnyriCrashRecord.frames(from: Data(repeating: 32, count: 512*1024+1)).isEmpty, "oversized system payload is not parsed")
        let record = UnyriCrashRecord(from: since.addingTimeInterval(1), until: end, version: "0.3.0", build: "20261031", exception: 1, signal: 11, frames: frames)
        check(record.valid, "valid bounded static diagnostic is accepted")
        check(!record.reportDescription.contains("PRIVATE_CONTENT") && !record.reportDescription.contains("secret.invalid"), "exception text and paths never enter the report")
        check(record.reportDescription.contains(uuid.uuidString) && record.reportDescription.contains("+0x5f8054"), "binary UUID and app text offset remain available for symbolication")
        check(record.reportDescription.contains("Reporting interval UTC:") &&
              record.reportDescription.contains(ISO8601DateFormatter().string(from: record.from)) &&
              record.reportDescription.contains(ISO8601DateFormatter().string(from: record.until)),
              "a diagnostic delivery interval is never mislabeled as the precise crash time")
        check(!UnyriCrashRecord(from: end, until: since, version: "0.3", build: "1", exception: 1, signal: 11, frames: frames).valid, "backwards diagnostic time is rejected")
        check(!UnyriCrashRecord(from: since, until: end, version: "URL?token=secret", build: "1", exception: 1, signal: 11, frames: frames).valid, "version fields cannot smuggle arbitrary text")
        check(!UnyriCrashRecord.Frame(binary: "/private/path", uuid: uuid, offset: 1).valid, "binary paths are not permitted as module names")

        let disk = MemoryFile()
        let archive = UnyriCrashArchive(load: { disk.read() }, save: { disk.write($0) })
        let first = try await archive.bind(owner: "synthetic-origin|user-one", now: since)!
        try await archive.ingest(record, ticket: first, now: end)
        let pending = await archive.pending(ticket: first, onlyUnoffered: true)
        check(pending == record, "real crash is offered in the same login")
        try await archive.ingest(record, ticket: first, now: end)
        try await archive.offered(record.id, ticket: first)
        check(await archive.pending(ticket: first, onlyUnoffered: true) == nil, "duplicate delivery does not repeat an already-offered prompt")
        check(await archive.pending(ticket: first, onlyUnoffered: false) == record, "Later preserves the report for manual feedback")
        let restored = UnyriCrashArchive(load: { disk.read() }, save: { disk.write($0) })
        let restoredTicket = try await restored.bind(owner: "synthetic-origin|user-one", now: end)!
        check(await restored.pending(ticket: restoredTicket, onlyUnoffered: false) == record, "cold process reload retains only the same owner's diagnostics")
        check(await restored.pending(ticket: restoredTicket, onlyUnoffered: true) == nil, "cold launch does not repeat the prompt")
        let second = try await archive.bind(owner: "synthetic-origin|user-two", now: end.addingTimeInterval(1))!
        check(await archive.pending(ticket: second, onlyUnoffered: false) == nil, "account switch discards the preceding account's report")
        try await archive.ingest(record, ticket: first, now: end.addingTimeInterval(2))
        check(await archive.pending(ticket: second, onlyUnoffered: false) == nil, "late old-session callback cannot repopulate diagnostics")
        try await archive.ingest(record, ticket: second, now: end.addingTimeInterval(2))
        check(await archive.pending(ticket: second, onlyUnoffered: false) == nil, "a mixed interval from before login cannot be attributed to the next user")
        _ = try await archive.bind(owner: nil, now: end)
        check(disk.read() == nil, "logout deletes the diagnostic file")
        try await archive.ingest(record, ticket: second, now: end)
        check(disk.read() == nil, "late callback cannot recreate the file after logout")

        let ticket = try await archive.bind(owner: "synthetic-origin|user-one", now: since)!
        try await archive.ingest(record, ticket: ticket, now: end)
        try await archive.remove(record.id, ticket: ticket)
        check(await archive.pending(ticket: ticket, onlyUnoffered: false) == nil, "successful report submission removes the local pending diagnostic")
        check((disk.read()?.count ?? Int.max) <= 64*1024, "persistent envelope is bounded")
        print("PASS: \(checks) crash metadata/privacy/owner/duplicate/logout contracts; no OS callback or phone claim")
    }
}
