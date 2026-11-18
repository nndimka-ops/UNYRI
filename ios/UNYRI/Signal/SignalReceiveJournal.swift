import Foundation

/// One atomic protected record precedes every receive-ratchet mutation. Recovery
/// runs before ANY subsequent Signal access, so a partial commit cannot roll a
/// later send back. Plaintext remains protected until SQLite confirms storage.
final class SignalReceiveJournal {
    private struct Mutation: Codable { let key: String; let value: Data? }
    private struct Commit: Codable { let id: String; let plaintext: Data; let metadata: Data; let mutations: [Mutation] }
    private struct Plaintext: Codable { let bytes: Data; let metadata: Data }
    private let read: (String) throws -> Data?
    private let write: (String, Data) throws -> Void
    private let remove: (String) throws -> Void
    private let keys: () throws -> [String]
    private var retainedKeys: Set<String>?
    static let maximumRetainedMessages = 128
    static let maximumPlaintextBytes = 1_048_576
    static func chatPrefix(_ chatID: String) -> String { "chat." + Data(chatID.utf8).base64EncodedString() + "." }
    private var staged: [String: Mutation]?
    private var recoveryChecked = false
    private let pendingKey = "receive_commit.v1"
    private func plaintextKey(_ id: String) -> String { "receive_plaintext.v1." + id }
    init(read: @escaping (String) throws -> Data?, write: @escaping (String, Data) throws -> Void,
         remove: @escaping (String) throws -> Void, keys: @escaping () throws -> [String]) {
        self.read = read; self.write = write; self.remove = remove; self.keys = keys
    }
    func recover() throws {
        guard staged == nil, !recoveryChecked else { return }
        guard let bytes = try read(pendingKey) else { recoveryChecked = true; return }
        let commit = try JSONDecoder().decode(Commit.self, from: bytes)
        for mutation in commit.mutations {
            if let value = mutation.value { try write(mutation.key, value) }
            else { try remove(mutation.key) }
        }
        try write(plaintextKey(commit.id), JSONEncoder().encode(Plaintext(bytes: commit.plaintext, metadata: commit.metadata)))
        retainedKeys?.insert(plaintextKey(commit.id))
        try remove(pendingKey)
        recoveryChecked = true
    }
    func data(_ key: String) throws -> Data? {
        try recover()
        if let mutation = staged?[key] { return mutation.value }
        return try read(key)
    }
    func set(_ key: String, value: Data?) throws {
        try recover()
        // A rejected identity must remain visible even when libsignal throws.
        let trustNotice = key.hasPrefix("pending_identity.") || key.hasPrefix("verified_identity.")
        if staged != nil && !trustNotice { staged?[key] = Mutation(key: key, value: value); return }
        if let value { try write(key, value) } else { try remove(key) }
    }
    func recovered(id: String) throws -> Data? {
        try recover()
        try loadRetainedKeys()
        let key = plaintextKey(id)
        guard retainedKeys?.contains(key) == true else { return nil }
        guard let data = try read(key) else { return nil }
        return try JSONDecoder().decode(Plaintext.self, from: data).bytes
    }
    func pendingMetadata() throws -> [Data] {
        try recover()
        if retainedKeys == nil { retainedKeys = Set(try keys().filter { $0.hasPrefix("receive_plaintext.v1.") }) }
        return try (retainedKeys ?? []).sorted().compactMap { key in
            guard let bytes = try read(key) else { return nil }
            return try JSONDecoder().decode(Plaintext.self, from: bytes).metadata
        }
    }
    func receive(id: String, metadata: Data = Data(), decrypt: () throws -> Data) throws -> Data {
        try recover()
        if let plaintext = try recovered(id: id) { return plaintext }
        guard staged == nil else { throw Failure.nestedReceive }
        if retainedKeys == nil { retainedKeys = Set(try keys().filter { $0.hasPrefix("receive_plaintext.v1.") }) }
        guard (retainedKeys?.count ?? 0) < Self.maximumRetainedMessages else { throw Failure.retentionLimit }
        staged = [:]
        defer { staged = nil }
        let plaintext = try decrypt()
        guard plaintext.count <= Self.maximumPlaintextBytes else { throw Failure.retentionLimit }
        let commit = Commit(id: id, plaintext: plaintext, metadata: metadata, mutations: (staged ?? [:]).values.sorted { $0.key < $1.key })
        // A single Keychain update is the crash-safety boundary. Failure leaves
        // the old ratchet/prekeys untouched. No SQLite/network await occurs here.
        recoveryChecked = false
        try write(pendingKey, JSONEncoder().encode(commit))
        staged = nil
        try recover()
        return plaintext
    }
    private func loadRetainedKeys() throws {
        if retainedKeys == nil { retainedKeys = Set(try keys().filter { $0.hasPrefix("receive_plaintext.v1.") }) }
    }
    func finish(id: String) throws {
        try recover()
        try loadRetainedKeys()
        let key = plaintextKey(id)
        // History replays are overwhelmingly already persisted. No Keychain
        // delete (or per-message absence probe) is needed for those rows.
        guard retainedKeys?.contains(key) == true else { return }
        try remove(key)
        retainedKeys?.remove(key)
    }
    func discard(chatID: String, through: Int64? = nil) throws {
        try recover()
        let prefix = plaintextKey(Self.chatPrefix(chatID))
        try loadRetainedKeys()
        for key in (retainedKeys ?? []).sorted() where key.hasPrefix(prefix) {
            if let through {
                let tail = key.dropFirst(prefix.count)
                guard let separator = tail.firstIndex(of: "."), let sequence = Int64(tail[..<separator]), sequence <= through else { continue }
            }
            try remove(key); retainedKeys?.remove(key)
        }
    }
    enum Failure: Error { case nestedReceive, retentionLimit }
}
