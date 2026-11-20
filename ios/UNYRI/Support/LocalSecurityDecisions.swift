import Foundation
import CryptoKit

/// User-owned decisions survive session erasure. Storage is one protected record
/// per canonical server origin and own user ID, never per login/device/slot.
final class LocalSecurityDecisions: @unchecked Sendable {
    struct RevokedDevice: Codable, Equatable {
        let userId: String
        let serverDeviceId: String
        var signalDeviceId: UInt32?
        var identityFingerprint: String?
    }
    struct IdentityPin: Codable, Equatable {
        let userId: String
        let signalDeviceId: UInt32
        var identityKey: Data
        var identityFingerprint: String
        var verified: Bool
        let firstSeenAtMs: Int64
    }
    private struct Record: Codable {
        let origin: String
        let ownUserId: String
        var blocks: [String: Bool] = [:]
        var revoked: [RevokedDevice] = []
        var observedDevices: [String: RevokedDevice] = [:]
        var pins: [IdentityPin] = []
        var warnedDevices: Set<String> = []
    }
    enum Failure: Error { case persistence, invalidRecord, capacity, revoked }
    static let pinLimit = 4096
    private let origin: String
    let ownUserId: String
    private let read: () throws -> Data?
    private let write: (Data) throws -> Void
    private let remove: () throws -> Void
    private let lock = NSRecursiveLock()
    private var cached: Record?
    private static let accountsLock = NSLock()
    private static var accounts: [String: LocalSecurityDecisions] = [:]

    static func canonicalOrigin(_ value: String) -> String {
        guard var parts = URLComponents(string: value) else { return value }
        let scheme = parts.scheme?.lowercased(), host = parts.host?.lowercased()
        parts.scheme = scheme; parts.host = host
        if scheme == "https" && parts.port == 443 || scheme == "http" && parts.port == 80 { parts.port = nil }
        if parts.path == "/" { parts.path = "" }
        return parts.string ?? value
    }
    static func storageKey(origin: String, userID: String) -> String {
        fingerprint(Data((canonicalOrigin(origin) + "|" + userID.lowercased()).utf8))
    }
    static func cachedAccount(key: String, create: () -> LocalSecurityDecisions) -> LocalSecurityDecisions {
        accountsLock.lock(); defer { accountsLock.unlock() }
        if let value = accounts[key] { return value }
        let value = create(); accounts[key] = value; return value
    }
    init(origin: String, userID: String, read: @escaping () throws -> Data?,
         write: @escaping (Data) throws -> Void, remove: @escaping () throws -> Void) {
        self.origin = Self.canonicalOrigin(origin); ownUserId = userID.lowercased()
        self.read = read; self.write = write; self.remove = remove
    }
    static func fingerprint(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
    private func load() throws -> Record {
        if let cached { return cached }
        let value: Record
        if let bytes = try read() {
            guard bytes.count <= 4 * 1024 * 1024 else { throw Failure.invalidRecord }
            value = try JSONDecoder().decode(Record.self, from: bytes)
            guard value.origin == origin, value.ownUserId == ownUserId,
                  value.pins.count <= Self.pinLimit, value.blocks.count <= 4096, value.revoked.count <= 1024, value.observedDevices.count <= 1024 else { throw Failure.invalidRecord }
        } else { value = Record(origin: origin, ownUserId: ownUserId) }
        cached = value; return value
    }
    private func save(_ value: Record) throws {
        let bytes = try JSONEncoder().encode(value)
        guard bytes.count <= 4 * 1024 * 1024 else { throw Failure.capacity }
        do {
            try write(bytes)
            guard try read() == bytes else { throw Failure.persistence }
            cached = value
        } catch { cached = nil; throw error }
    }
    func blockDecision(_ user: String) throws -> Bool? {
        lock.lock(); defer { lock.unlock() }; return try load().blocks[user.lowercased()]
    }
    func blockDecisions() throws -> [String: Bool] {
        lock.lock(); defer { lock.unlock() }; return try load().blocks
    }
    func blockedUsers() throws -> Set<String> {
        lock.lock(); defer { lock.unlock() }; return Set(try load().blocks.filter { $0.value }.keys)
    }
    func setBlocked(_ user: String, blocked: Bool) throws {
        lock.lock(); defer { lock.unlock() }
        var value = try load(); let id = user.lowercased()
        guard id != ownUserId, id.utf8.count <= 128 else { throw Failure.invalidRecord }
        if value.blocks[id] == blocked { return }
        guard value.blocks[id] != nil || value.blocks.count < 4096 else { throw Failure.capacity }
        value.blocks[id] = blocked; try save(value)
        DispatchQueue.main.async { NotificationCenter.default.post(name: Notification.Name("COCOLocalBlockChanged"), object: nil) }
    }
    func observe(user: String, device: String, signal: UInt32, identity: Data) throws {
        guard user.lowercased() == ownUserId else { return }
        lock.lock(); defer { lock.unlock() }
        var value = try load()
        let record = RevokedDevice(userId: ownUserId, serverDeviceId: device.lowercased(), signalDeviceId: signal, identityFingerprint: Self.fingerprint(identity))
        let revokedUUID = value.revoked.contains(where: { $0.serverDeviceId == record.serverDeviceId })
        if value.observedDevices[record.serverDeviceId] == record && (!revokedUUID || value.revoked.contains(record)) { return }
        guard value.observedDevices[record.serverDeviceId] != nil || value.observedDevices.count < 1024 else { throw Failure.capacity }
        value.observedDevices[record.serverDeviceId] = record
        if revokedUUID && !value.revoked.contains(record) {
            guard value.revoked.count < 1024 else { throw Failure.capacity }
            value.revoked.append(record)
        }
        // Observation and revocation aliases commit in one protected record.
        try save(value)
    }
    func revoke(_ input: RevokedDevice) throws {
        var device = input
        lock.lock(); defer { lock.unlock() }
        guard device.userId.lowercased() == ownUserId, device.serverDeviceId.utf8.count <= 128 else { throw Failure.invalidRecord }
        var value = try load()
        if let observed = value.observedDevices[device.serverDeviceId.lowercased()] {
            device.signalDeviceId = device.signalDeviceId ?? observed.signalDeviceId
            device.identityFingerprint = device.identityFingerprint ?? observed.identityFingerprint
        }
        if let previous = value.revoked.first(where: { $0.serverDeviceId == device.serverDeviceId }) {
            device.signalDeviceId = device.signalDeviceId ?? previous.signalDeviceId
            device.identityFingerprint = device.identityFingerprint ?? previous.identityFingerprint
        }
        if value.revoked.contains(device) { return }
        // Keep every observed address/key alias of a revoked UUID. Relisting
        // with replacement metadata cannot make an older alias usable again.
        guard value.revoked.count < 1024 else { throw Failure.capacity }
        value.revoked.append(device)
        try save(value)
    }
    func isRevoked(user: String, device: String? = nil, signal: UInt32? = nil, identity: Data? = nil) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        let fingerprint = identity.map(Self.fingerprint)
        return try revokedMatch(user: user, device: device, signal: signal, fingerprint: fingerprint)
    }
    private func revokedMatch(user: String, device: String?, signal: UInt32?, fingerprint: String?) throws -> Bool {
        let value = try load()
        if value.revoked.count >= 1024 && user.lowercased() == ownUserId { return true }
        return value.revoked.contains { record in
            (device != nil && record.serverDeviceId.lowercased() == device?.lowercased()) ||
            (signal != nil && record.userId == user.lowercased() && record.signalDeviceId == signal) ||
            (fingerprint != nil && record.identityFingerprint == fingerprint)
        }
    }
    func claimRelistingWarning(_ devices: [String]) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        var value = try load()
        let relisted = Set(devices.map { $0.lowercased() }).intersection(value.revoked.map { $0.serverDeviceId.lowercased() })
        let fresh = relisted.subtracting(value.warnedDevices)
        guard !fresh.isEmpty else { return false }
        value.warnedDevices.formUnion(fresh); try save(value); return true
    }
    func pins() throws -> [IdentityPin] {
        lock.lock(); defer { lock.unlock() }; return try load().pins
    }
    func pin(user: String, signal: UInt32, identity: Data, verified: Bool? = nil) throws {
        lock.lock(); defer { lock.unlock() }
        guard (1...127).contains(signal), identity.count <= 128, !identity.isEmpty, user.utf8.count <= 128 else { throw Failure.invalidRecord }
        var value = try load()
        let index = value.pins.firstIndex { $0.userId == user.lowercased() && $0.signalDeviceId == signal }
        let old = index.map { value.pins[$0] }
        let next = IdentityPin(userId: user.lowercased(), signalDeviceId: signal, identityKey: identity,
            identityFingerprint: Self.fingerprint(identity), verified: verified ?? (old?.identityKey == identity && old?.verified == true),
            firstSeenAtMs: old?.firstSeenAtMs ?? Int64(Date().timeIntervalSince1970 * 1000))
        if old == next { return }
        if let index { value.pins[index] = next } else {
            if value.pins.count >= Self.pinLimit {
                guard let victim = value.pins.indices.filter({ !value.pins[$0].verified }).min(by: { value.pins[$0].firstSeenAtMs < value.pins[$1].firstSeenAtMs }) else { throw Failure.capacity }
                value.pins.remove(at: victim)
            }
            value.pins.append(next)
        }
        try save(value)
    }
    /// Only account deletion or an explicit erase-account-data action calls this.
    func erase() throws {
        lock.lock(); defer { lock.unlock() }
        try remove(); guard try read() == nil else { throw Failure.persistence }; cached = nil
    }
}
