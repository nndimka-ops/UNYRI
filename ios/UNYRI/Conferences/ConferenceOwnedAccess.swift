import Foundation

/// Three short-lived creator capabilities, backed by the account's Keychain
/// record. This is not a server backup and never contains a room password.
/// Active SDK keys/tokens remain separate from this explicit re-entry record.
@MainActor
final class ConferenceOwnedAccess {
    enum Failure: Error { case invalid, persistence }
    private struct Entry: Codable {
        let roomID: UUID
        let expiresAt: Date
        let invitation: String
    }
    private struct Record: Codable {
        let version: Int
        let owner: String
        let entries: [Entry]
    }
    private let server: URL
    private let owner: String
    private let persist: (String) throws -> Void
    private var entries: [UUID: Entry] = [:]

    init(server: URL, owner: String, raw: String?, now: Date = Date(), persist: @escaping (String) throws -> Void) {
        self.server = server; self.owner = owner; self.persist = persist
        guard let raw, raw.utf8.count <= 12_288,
              let value = try? JSONDecoder().decode(Record.self, from: Data(raw.utf8)),
              value.version == 1, value.owner == owner, value.entries.count <= 3 else { return }
        for entry in value.entries {
            guard entry.expiresAt > now, entry.expiresAt.timeIntervalSince(now) <= 86_430,
                  let invite = try? ConferenceInvite.parse(entry.invitation, currentServer: server),
                  invite.roomID == entry.roomID, entries[entry.roomID] == nil else { continue }
            entries[entry.roomID] = entry
        }
        if value.entries.contains(where: { $0.expiresAt <= now }) { try? save() }
    }

    func invitation(for id: UUID, now: Date = Date()) -> ConferenceInvite? {
        guard let value = entries[id], value.expiresAt > now else {
            if entries.removeValue(forKey: id) != nil { try? save() }
            return nil
        }
        return try? ConferenceInvite.parse(value.invitation, currentServer: server)
    }

    func remember(_ invite: ConferenceInvite, expiresAt: Date, now: Date = Date()) throws {
        guard try ConferenceInvite.origin(invite.server) == ConferenceInvite.origin(server),
              expiresAt > now, expiresAt.timeIntervalSince(now) <= 86_430,
              try ConferenceInvite.parse(invite.wireLink.absoluteString, currentServer: server) == invite else { throw Failure.invalid }
        let before = entries
        entries = entries.filter { $0.value.expiresAt > now }
        entries[invite.roomID] = Entry(roomID: invite.roomID, expiresAt: expiresAt, invitation: invite.wireLink.absoluteString)
        if entries.count > 3, let oldest = entries.values.min(by: { $0.expiresAt < $1.expiresAt }) {
            entries.removeValue(forKey: oldest.roomID)
        }
        do { try save() } catch { entries = before; throw error }
    }

    func remove(_ id: UUID) {
        guard entries.removeValue(forKey: id) != nil else { return }
        try? save()
    }

    private func save() throws {
        let value = Record(version: 1, owner: owner, entries: entries.values.sorted { $0.roomID.uuidString < $1.roomID.uuidString })
        let data = try JSONEncoder().encode(value)
        guard data.count <= 12_288, let text = String(data: data, encoding: .utf8) else { throw Failure.invalid }
        try persist(text)
    }
}
