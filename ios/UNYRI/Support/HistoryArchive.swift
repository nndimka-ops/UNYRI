import Foundation

/// Portable final-state history. Never serialize credentials, pending sends or
/// Signal ratchets here. The native importer must fence its transaction by account.
struct HistoryArchive: Codable, Equatable {
    var format: Int = 1
    var server: String
    var owner: String
    var snapshot: String
    var messages: [HistoryArchiveMessage]
    var clearedThrough: Int64 = 0
    var chatClears: [String: Int64] = [:]
    var deletedChats: Set<String> = []
    var reactions: [HistoryArchiveReaction] = []
    var media: [HistoryMediaEntry]? = nil
    var chats: [HistoryArchiveChat]? = nil
    // Optional v2 fields: old message-only archives remain readable. Calls are
    // inside the same authenticated client-encrypted archive, never a restore
    // grant for plaintext server call history.
    var calls: [HistoryArchiveCall]? = nil
    var callDeletedIds: Set<String>? = nil
    var callClearedBeforeMs: Int64? = nil
    var callChatClears: [String: Int64]? = nil
    static let maxBytes = 8 * 1024 * 1024 - 28
    enum Failure: Error { case invalidArchive, conflict }

    func validate(server expectedServer: String, owner expectedOwner: String) throws {
        guard (1...2).contains(format), server == expectedServer, owner == expectedOwner,
              messages.count <= 50_000, chatClears.count <= 10_000, clearedThrough >= 0 else {
            throw Failure.invalidArchive
        }
        guard format == 1 ? (media == nil && chats == nil) : (media != nil && chats != nil) else { throw Failure.invalidArchive }
        guard format == 2 || (calls == nil && callDeletedIds == nil && callClearedBeforeMs == nil && callChatClears == nil) else { throw Failure.invalidArchive }
        guard (calls?.count ?? 0) <= 50_000, (callDeletedIds?.count ?? 0) <= 50_000,
              (callChatClears?.count ?? 0) <= 10_000, (callClearedBeforeMs ?? 0) >= 0 else { throw Failure.invalidArchive }
        for id in callDeletedIds ?? [] { try Self.validateId(id) }
        for (id, value) in callChatClears ?? [:] { try Self.validateId(id); guard value >= 0 else { throw Failure.invalidArchive } }
        var callIds = Set<String>()
        for call in calls ?? [] {
            try call.validate(owner: owner)
            guard callIds.insert(call.id).inserted, !(callDeletedIds ?? []).contains(call.id),
                  call.createdAtMs >= (callClearedBeforeMs ?? 0) else { throw Failure.invalidArchive }
            if let chat = call.chat {
                guard !call.chatVisible || (!deletedChats.contains(chat) && call.createdAtMs > ((callChatClears ?? [:])[chat] ?? -1)) else { throw Failure.invalidArchive }
            }
        }
        if let chats {
            guard chats.count <= 10_000 else { throw Failure.invalidArchive }
            var ids = Set<String>()
            for chat in chats { try chat.validate(); guard ids.insert(chat.id).inserted, !deletedChats.contains(chat.id) else { throw Failure.invalidArchive } }
        }
        for id in [server, owner, snapshot] { try Self.validateId(id) }
        for (chat, cutoff) in chatClears {
            try Self.validateId(chat)
            guard cutoff >= 0 else { throw Failure.invalidArchive }
        }
        guard deletedChats.count <= 10_000, reactions.count <= 50_000 else { throw Failure.invalidArchive }
        for chat in deletedChats { try Self.validateId(chat) }
        var clients = Set<String>(), ids = Set<String>(), sequences = Set<String>()
        var budget = 1024 + chatClears.count * 80 + deletedChats.count * 40 + reactions.count * 512
        budget += (media?.count ?? 0) * 384
        budget += (callDeletedIds?.count ?? 0) * 40 + (callChatClears?.count ?? 0) * 80
        for call in calls ?? [] { budget += 1024 + 6 * ((call.peer?.username.utf8.count ?? 0) + (call.peer?.displayName.utf8.count ?? 0)) }
        for chat in chats ?? [] { budget += 256 + 6 * ((chat.peer?.username.utf8.count ?? 0) + (chat.peer?.displayName.utf8.count ?? 0)) }
        guard budget <= Self.maxBytes else { throw Failure.invalidArchive }
        for message in messages {
            try message.validate()
            budget += 512 + 6 * (message.content?.utf8.count ?? 0)
            guard budget <= Self.maxBytes else { throw Failure.invalidArchive }
            guard clients.insert(message.client).inserted, ids.insert(message.id).inserted,
                  sequences.insert("\(message.chat):\(message.sequence)").inserted,
                  message.sequence > max(clearedThrough, chatClears[message.chat] ?? 0), !deletedChats.contains(message.chat) else {
                throw Failure.invalidArchive
            }
        }
        let targets = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        var reactionIds = Set<String>()
        for reaction in reactions {
            try reaction.validate()
            guard let target = targets[reaction.target], !target.hidden, target.deletedThrough == 0,
                  reaction.sequence > target.sequence, reactionIds.insert(reaction.target + ":" + reaction.user).inserted else {
                throw Failure.invalidArchive
            }
        }
        if let media {
            guard media.count <= 50_000 else { throw Failure.invalidArchive }
            let identities = try mediaIdentities()
            var ids = Set<String>(), blobs = Set<String>()
            for entry in media {
                try entry.validate()
                guard ids.insert(entry.attachment).inserted, blobs.insert(entry.blob).inserted,
                      identities[entry.attachment] == HistoryMediaIdentity(bytes: entry.bytes, sha256: entry.sha256) else { throw Failure.invalidArchive }
            }
        }
    }

    func withoutMedia() -> HistoryArchive {
        var value = self; value.format = 1; value.media = nil; value.chats = nil
        value.calls = nil; value.callDeletedIds = nil; value.callClearedBeforeMs = nil; value.callChatClears = nil; return value
    }
    /// Authenticated server tombstones cover deletes whose old Signal envelopes
    /// did not target the new device. No cursor or receipt is imported here.
    func applyingServerDeletions(_ deleted: [String: Int64]) throws -> HistoryArchive {
        try validate(server: server, owner: owner)
        let targets = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        for (id, sequence) in deleted {
            guard let target = targets[id], sequence > target.sequence else { throw Failure.invalidArchive }
        }
        var result = self
        result.messages = messages.map { row in
            guard let sequence = deleted[row.id] else { return row }
            var changed = row; changed.content = nil; changed.deletedThrough = max(row.deletedThrough, sequence); return changed
        }
        result.reactions = reactions.filter { deleted[$0.target] == nil }
        let required = try result.mediaIdentities()
        result.media = result.media?.filter { required[$0.attachment] != nil }
        try result.validate(server: server, owner: owner)
        if result.format == 2 { try result.validateCompleteMedia() }
        return result
    }
    func validateCompleteMedia() throws {
        guard format == 2, let media, let chats,
              Set(media.map { $0.attachment }) == Set(try mediaIdentities().keys),
              Set(messages.map { $0.chat }).isSubset(of: Set(chats.map { $0.id })) else { throw Failure.invalidArchive }
    }
    /// New exports must include the chat shell for visible call-only history.
    /// Older authenticated v2 archives are normalized at explicit restore time.
    func validateBackupChatDescriptors() throws {
        try validateCompleteMedia()
        guard Set((calls ?? []).filter(\.chatVisible).compactMap(\.chat))
            .isSubset(of: Set((chats ?? []).map(\.id))) else { throw Failure.invalidArchive }
    }
    func restoringCallChatDescriptors() throws -> HistoryArchive {
        try validate(server: server, owner: owner)
        guard format == 2 else { return self }
        var result = self
        var descriptors = Dictionary(uniqueKeysWithValues: (chats ?? []).map { ($0.id, $0) })
        let grouped = Dictionary(grouping: (calls ?? []).filter { $0.chatVisible && $0.chat != nil }, by: { $0.chat! })
        for (chat, rows) in grouped {
            if let existing = descriptors[chat], existing.type != "direct" { continue }
            let peers = Set(rows.map { $0.caller == owner ? $0.callee : $0.caller })
            guard peers.count == 1, let other = peers.first else { throw Failure.conflict }
            if let existing = descriptors[chat] {
                guard existing.peer?.id == other else { throw Failure.conflict }
            } else {
                let peer = rows.compactMap(\.peer).first ?? HistoryArchivePeer(id: other, username: "restored-contact", displayName: "Restored contact")
                descriptors[chat] = HistoryArchiveChat(id: chat, type: "direct", createdAtMs: rows.map(\.createdAtMs).min() ?? 0, peer: peer)
            }
        }
        result.chats = descriptors.values.sorted { $0.id < $1.id }
        try result.validate(server: server, owner: owner)
        try result.validateBackupChatDescriptors()
        return result
    }
    /// Message-only v1 archives have no descriptors. Reuse known server chat
    /// metadata; otherwise a generic group shell avoids inventing a recipient.
    func restoringLegacyTextHistory(chats known: [HistoryArchiveChat]) throws -> HistoryArchive {
        try validate(server: server, owner: owner)
        guard format == 1 else { return self }
        guard try mediaIdentities().isEmpty else { throw Failure.invalidArchive }
        let required = Set(messages.map(\.chat))
        guard required.count <= 10_000 else { throw Failure.invalidArchive }
        var created: [String: Int64] = [:]
        for message in messages { created[message.chat] = min(created[message.chat] ?? message.createdAtMs, message.createdAtMs) }
        var descriptors: [String: HistoryArchiveChat] = [:]
        for chat in known where required.contains(chat.id) && !deletedChats.contains(chat.id) {
            try chat.validate()
            guard descriptors[chat.id] == nil || descriptors[chat.id] == chat else { throw Failure.conflict }
            descriptors[chat.id] = chat
        }
        for chat in required where descriptors[chat] == nil {
            descriptors[chat] = HistoryArchiveChat(id: chat, type: "group", createdAtMs: created[chat] ?? 0, peer: nil)
        }
        var result = self; result.format = 2; result.media = []; result.chats = descriptors.values.sorted { $0.id < $1.id }
        try result.validate(server: server, owner: owner)
        try result.validateCompleteMedia()
        return result
    }
    func mediaIdentities() throws -> [String: HistoryMediaIdentity] {
        var identities: [String: HistoryMediaIdentity] = [:]
        for row in messages where !row.hidden && row.deletedThrough == 0 {
            guard let text = row.content, let root = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
                  let attachment = root["attachment"] as? [String: Any] else { continue }
            guard let id = attachment["attachment_id"] as? String,
                  let bytes = attachment["encrypted_size"] as? Int64,
                  let encryption = attachment["encryption"] as? [String: Any],
                  let digest = encryption["sha256"] as? String, let hash = Data(base64Encoded: digest), hash.count == 32,
                  (1...HistoryMediaEntry.maxFileBytes).contains(bytes) else { throw Failure.invalidArchive }
            try Self.validateId(id)
            let identity = HistoryMediaIdentity(bytes: bytes, sha256: hash.map { String(format: "%02x", $0) }.joined())
            if let existing = identities[id], existing != identity { throw Failure.conflict }
            identities[id] = identity
        }
        return identities
    }

    static func validateId(_ id: String) throws {
        guard id.count == 36, UUID(uuidString: id)?.uuidString.lowercased() == id else {
            throw Failure.invalidArchive
        }
    }

    static func decode(_ data: Data, server: String, owner: String) throws -> HistoryArchive {
        guard !data.isEmpty, data.count <= maxBytes else { throw Failure.invalidArchive }
        // Codable normally ignores unknown keys. Reject them, as Android does,
        // so a newer incompatible format cannot silently lose protected fields.
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = object["messages"] as? [[String: Any]], rows.count <= 50_000 else {
            throw Failure.invalidArchive
        }
        let fieldsForFormat: Set<String> = Set(["format", "server", "owner", "snapshot", "messages", "clearedThrough", "chatClears", "deletedChats", "reactions"])
            .union((object["format"] as? Int) == 2 ? ["media", "chats"] : [])
        let optionalCallFields: Set<String> = (object["format"] as? Int) == 2 ? ["calls", "callDeletedIds", "callClearedBeforeMs", "callChatClears"] : []
        guard fieldsForFormat.isSubset(of: Set(object.keys)), Set(object.keys).isSubset(of: fieldsForFormat.union(optionalCallFields)) else { throw Failure.invalidArchive }
        if let rows = object["calls"] {
            guard let rows = rows as? [[String: Any]], rows.count <= 50_000 else { throw Failure.invalidArchive }
            for row in rows {
                guard Set(row.keys).isSubset(of: ["id", "caller", "callee", "mediaKind", "createdAtMs", "acceptedAtMs", "connectedAtMs", "endedAtMs", "endReason", "chat", "chatVisible", "peer"]) else { throw Failure.invalidArchive }
                if let peer = row["peer"] as? [String: Any] { guard Set(peer.keys) == ["id", "username", "displayName"] else { throw Failure.invalidArchive } }
            }
        }
        if (object["format"] as? Int) == 2 {
            guard let media = object["media"] as? [[String: Any]], let chats = object["chats"] as? [[String: Any]],
                  media.count <= 50_000, chats.count <= 10_000 else { throw Failure.invalidArchive }
            for entry in media { guard Set(entry.keys) == ["attachment", "blob", "bytes", "sha256"] else { throw Failure.invalidArchive } }
            for chat in chats {
                guard Set(chat.keys).isSubset(of: ["id", "type", "createdAtMs", "peer"]) else { throw Failure.invalidArchive }
                if let peer = chat["peer"] as? [String: Any] {
                    guard Set(peer.keys) == ["id", "username", "displayName"] else { throw Failure.invalidArchive }
                }
            }
        }
        let fields: Set<String> = ["client", "id", "chat", "sender", "sequence", "createdAtMs", "content", "editedThrough", "deletedThrough", "hidden", "replyTo"]
        for row in rows {
            guard Set(row.keys).isSubset(of: fields) else { throw Failure.invalidArchive }
        }
        guard let reactionRows = object["reactions"] as? [[String: Any]] else { throw Failure.invalidArchive }
        for row in reactionRows {
            guard Set(row.keys).isSubset(of: ["target", "user", "sequence", "emoji"]) else { throw Failure.invalidArchive }
        }
        let archive = try JSONDecoder().decode(HistoryArchive.self, from: data)
        try archive.validate(server: server, owner: owner)
        if archive.format == 2 { try archive.validateCompleteMedia() }
        return archive
    }

    func encoded() throws -> Data {
        try validate(server: server, owner: owner)
        if format == 2 { try validateCompleteMedia() }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(self)
        guard data.count <= Self.maxBytes else { throw Failure.invalidArchive }
        return data
    }
}

struct HistoryArchiveMessage: Codable, Equatable {
    var client: String
    var id: String
    var chat: String
    var sender: String
    var sequence: Int64
    var createdAtMs: Int64
    /// Portable MessageContent JSON, not native enum serialization or a file path.
    var content: String?
    var editedThrough: Int64 = 0
    var deletedThrough: Int64 = 0
    var hidden: Bool = false
    var replyTo: String? = nil

    func validate() throws {
        for id in [client, self.id, chat, sender] { try HistoryArchive.validateId(id) }
        if let replyTo { try HistoryArchive.validateId(replyTo) }
        guard sequence > 0, (0...253402300799999).contains(createdAtMs),
              editedThrough == 0 || editedThrough > sequence,
              deletedThrough == 0 || deletedThrough > sequence else { throw HistoryArchive.Failure.invalidArchive }
        if hidden || deletedThrough > 0 {
            guard content == nil else { throw HistoryArchive.Failure.invalidArchive }
        } else {
            guard let content, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw HistoryArchive.Failure.invalidArchive
            }
        }
        guard (content?.utf8.count ?? 0) <= 1024 * 1024 else { throw HistoryArchive.Failure.invalidArchive }
    }
}

struct HistoryArchiveReaction: Codable, Equatable {
    var target: String
    var user: String
    var sequence: Int64
    var emoji: String?
    func validate() throws {
        try HistoryArchive.validateId(target); try HistoryArchive.validateId(user)
        guard sequence > 0 else { throw HistoryArchive.Failure.invalidArchive }
        if let emoji {
            guard !emoji.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, emoji.utf8.count <= 64 else { throw HistoryArchive.Failure.invalidArchive }
        }
    }
}

enum HistoryArchiveMerge {
    /// Pure plan only. Does not advance sync/read cursors or create send jobs.
    static func merge(local: HistoryArchive, restored: HistoryArchive) throws -> HistoryArchive {
        try local.validate(server: local.server, owner: local.owner)
        try restored.validate(server: local.server, owner: local.owner)
        let cutoff = max(local.clearedThrough, restored.clearedThrough)
        let deletedChats = local.deletedChats.union(restored.deletedChats)
        var clears = local.chatClears
        for (chat, value) in restored.chatClears { clears[chat] = max(clears[chat] ?? 0, value) }
        var rows = Dictionary(uniqueKeysWithValues: local.messages.map { ($0.client, $0) })
        for incoming in restored.messages {
            guard let current = rows[incoming.client] else { rows[incoming.client] = incoming; continue }
            guard current.id == incoming.id, current.chat == incoming.chat,
                  current.sender == incoming.sender, current.sequence == incoming.sequence,
                  abs(current.createdAtMs - incoming.createdAtMs) <= 1, current.replyTo == incoming.replyTo else { throw HistoryArchive.Failure.conflict }
            let deleted = max(current.deletedThrough, incoming.deletedThrough)
            let hidden = current.hidden || incoming.hidden
            if !hidden && deleted == 0 && current.editedThrough == incoming.editedThrough {
                guard sameContent(current.content, incoming.content) else { throw HistoryArchive.Failure.conflict }
            }
            var winner = incoming.editedThrough > current.editedThrough ? incoming : current
            winner.deletedThrough = deleted; winner.hidden = hidden
            if hidden || deleted > 0 { winner.content = nil }
            rows[incoming.client] = winner
        }
        var result = local
        result.clearedThrough = cutoff; result.chatClears = clears
        result.deletedChats = deletedChats
        result.messages = rows.values.filter { !deletedChats.contains($0.chat) && $0.sequence > max(cutoff, clears[$0.chat] ?? 0) }
            .sorted { $0.sequence == $1.sequence ? $0.client < $1.client : $0.sequence < $1.sequence }
        var reactions = Dictionary(uniqueKeysWithValues: local.reactions.map { ($0.target + ":" + $0.user, $0) })
        for incoming in restored.reactions {
            let key = incoming.target + ":" + incoming.user
            if let current = reactions[key] {
                if current.sequence == incoming.sequence && current.emoji != incoming.emoji { throw HistoryArchive.Failure.conflict }
                if incoming.sequence > current.sequence { reactions[key] = incoming }
            } else { reactions[key] = incoming }
        }
        let live = Set(result.messages.filter { !$0.hidden && $0.deletedThrough == 0 }.map { $0.id })
        result.reactions = reactions.values.filter { live.contains($0.target) }
            .sorted { $0.target == $1.target ? $0.user < $1.user : $0.target < $1.target }
        if local.format == 2 || restored.format == 2 {
            result.format = 2
            let deletedCalls = (local.callDeletedIds ?? []).union(restored.callDeletedIds ?? [])
            let callCutoff = max(local.callClearedBeforeMs ?? 0, restored.callClearedBeforeMs ?? 0)
            var callClears = local.callChatClears ?? [:]
            for (id, cutoff) in restored.callChatClears ?? [:] { callClears[id] = max(callClears[id] ?? 0, cutoff) }
            var calls = Dictionary(uniqueKeysWithValues: (local.calls ?? []).map { ($0.id, $0) })
            for incoming in restored.calls ?? [] {
                if let old = calls[incoming.id] {
                    guard old.caller == incoming.caller, old.callee == incoming.callee,
                          old.mediaKind == incoming.mediaKind, abs(old.createdAtMs - incoming.createdAtMs) <= 1,
                          old.acceptedAtMs == incoming.acceptedAtMs, old.connectedAtMs == incoming.connectedAtMs,
                          old.endedAtMs == incoming.endedAtMs, old.endReason == incoming.endReason else { throw HistoryArchive.Failure.conflict }
                    var merged = old; merged.chatVisible = old.chatVisible && incoming.chatVisible
                    if merged.peer == nil { merged.peer = incoming.peer }; calls[incoming.id] = merged
                } else { calls[incoming.id] = incoming }
            }
            var callRows: [HistoryArchiveCall] = []
            for value in calls.values where !deletedCalls.contains(value.id) && value.createdAtMs >= callCutoff {
                var row = value
                if let chat = row.chat, deletedChats.contains(chat) || row.createdAtMs <= (callClears[chat] ?? -1) { row.chatVisible = false }
                callRows.append(row)
            }
            callRows.sort { $0.createdAtMs == $1.createdAtMs ? $0.id < $1.id : $0.createdAtMs < $1.createdAtMs }
            result.calls = callRows.isEmpty ? nil : callRows
            result.callDeletedIds = deletedCalls.isEmpty ? nil : deletedCalls
            result.callClearedBeforeMs = callCutoff == 0 ? nil : callCutoff
            result.callChatClears = callClears.isEmpty ? nil : callClears
            let identities = try result.mediaIdentities()
            var manifest: [String: HistoryMediaEntry] = [:]
            for entry in (local.media ?? []) + (restored.media ?? []) where manifest[entry.attachment] == nil {
                if identities[entry.attachment] == HistoryMediaIdentity(bytes: entry.bytes, sha256: entry.sha256) { manifest[entry.attachment] = entry }
            }
            result.media = manifest.values.sorted { $0.attachment < $1.attachment }
            var descriptors: [String: HistoryArchiveChat] = [:]
            for chat in (local.chats ?? []) + (restored.chats ?? []) where descriptors[chat.id] == nil && !deletedChats.contains(chat.id) { descriptors[chat.id] = chat }
            result.chats = descriptors.values.sorted { $0.id < $1.id }
        }
        try result.validate(server: local.server, owner: local.owner)
        return result
    }
    private static func sameContent(_ a: String?, _ b: String?) -> Bool {
        if a == b { return true }
        guard let a, let b else { return false }
        func canonical(_ text: String) throws -> Data {
            let object = try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
            return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .fragmentsAllowed])
        }
        guard let first = try? canonical(a), let second = try? canonical(b) else { return false }
        return first == second
    }
}

struct HistoryArchiveCall: Codable, Equatable {
    let id: String; let caller: String; let callee: String; let mediaKind: String
    let createdAtMs: Int64; let acceptedAtMs: Int64?; let connectedAtMs: Int64?; let endedAtMs: Int64?
    let endReason: String?; let chat: String?; var chatVisible: Bool; var peer: HistoryArchivePeer?
    func validate(owner: String) throws {
        for id in [id, caller, callee] { try HistoryArchive.validateId(id) }
        if let chat { try HistoryArchive.validateId(chat) }; try peer?.validate()
        guard caller != callee, caller == owner || callee == owner, ["audio", "video"].contains(mediaKind),
              (0...253402300799999).contains(createdAtMs),
              endReason == nil || ["hangup", "rejected", "cancelled", "canceled", "missed", "timeout", "failed", "busy", "ended", "disconnect", "answered_elsewhere", "remote_ended"].contains(endReason!),
              peer == nil || peer?.id == (caller == owner ? callee : caller) else { throw HistoryArchive.Failure.invalidArchive }
        for date in [acceptedAtMs, connectedAtMs, endedAtMs].compactMap({ $0 }) {
            guard (createdAtMs...253402300799999).contains(date) else { throw HistoryArchive.Failure.invalidArchive }
        }
        if let acceptedAtMs, let connectedAtMs { guard acceptedAtMs <= connectedAtMs else { throw HistoryArchive.Failure.invalidArchive } }
        if let endedAtMs { guard (connectedAtMs ?? acceptedAtMs ?? createdAtMs) <= endedAtMs else { throw HistoryArchive.Failure.invalidArchive } }
    }
}

struct HistoryMediaIdentity: Equatable { let bytes: Int64; let sha256: String }
struct HistoryArchivePeer: Codable, Equatable {
    let id: String; let username: String; let displayName: String
    func validate() throws {
        try HistoryArchive.validateId(id)
        guard (1...512).contains(username.utf8.count), displayName.utf8.count <= 1024 else { throw HistoryArchive.Failure.invalidArchive }
    }
}
struct HistoryArchiveChat: Codable, Equatable {
    let id: String; let type: String; let createdAtMs: Int64; let peer: HistoryArchivePeer?
    func validate() throws {
        try HistoryArchive.validateId(id); try peer?.validate()
        guard ["direct", "group", "saved"].contains(type), (0...253402300799999).contains(createdAtMs),
              type != "direct" || peer != nil else { throw HistoryArchive.Failure.invalidArchive }
    }
}

/// Keep this metadata inside the authenticated archive, never trust a digest
/// supplied only by the server. No keys, local paths or plaintext in transport.
struct HistoryMediaEntry: Codable, Equatable {
    let attachment: String
    let blob: String
    let bytes: Int64
    let sha256: String
    static let maxFileBytes: Int64 = 100 * 1024 * 1024 + 4096
    func validate() throws {
        try HistoryArchive.validateId(attachment)
        try HistoryArchive.validateId(blob)
        guard (1...Self.maxFileBytes).contains(bytes), sha256.utf8.count == 64,
              sha256.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw HistoryArchive.Failure.invalidArchive
        }
    }
}
