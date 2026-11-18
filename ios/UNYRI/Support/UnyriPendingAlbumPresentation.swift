import Foundation

/// View-owned metadata only. Server dates and the live messages stay canonical.
/// Only an already displayed own pending album is pinned until this view ends.
final class UnyriPendingAlbumPresentation {
    struct Scope: Equatable {
        let origin: String
        let owner: String
        let generation: UInt64
        let chat: String
    }

    struct SortKey: Equatable, Comparable {
        let date: Date
        let isCall: Bool
        let sequence: Int64?
        let id: String

        static func < (lhs: Self, rhs: Self) -> Bool {
            if lhs.date != rhs.date { return lhs.date < rhs.date }
            if lhs.isCall != rhs.isCall { return lhs.isCall }
            if lhs.sequence != rhs.sequence { return (lhs.sequence ?? .max) < (rhs.sequence ?? .max) }
            return lhs.id < rhs.id
        }
    }

    struct Member {
        let id: String
        let chat: String
        let sender: String
        let album: String?
        let sort: SortKey
        let pending: Bool
        let eligible: Bool
    }

    struct Row {
        let id: String
        let sort: SortKey
        let members: [Member]
    }

    struct Projection {
        /// Resolves back to this revision's live row, never a retained message.
        let sourceID: String
        let id: String
        let members: [String]
    }

    private struct Key: Hashable {
        let chat: String
        let sender: String
        let album: String
    }

    private struct Entry {
        let token: String
        let anchor: SortKey
        var order: [String]
        var firstSeen: [String: SortKey]
    }

    private var scope: Scope?
    private var entries: [Key: Entry] = [:]
    private var tokenPrefix = UUID().uuidString
    private var nextToken: UInt64 = 0

    func retire() {
        entries.removeAll()
        scope = nil
    }

    func project(scope nextScope: Scope, rows: [Row]) -> [Projection] {
        if scope != nextScope {
            scope = nextScope
            entries.removeAll()
            tokenPrefix = UUID().uuidString
            nextToken = 0
        }
        func canonical(_ row: Row) -> Projection {
            Projection(sourceID: row.id, id: row.id, members: row.members.map(\.id))
        }
        // Production rows are unique. Do not invent a new identity/order for an
        // inconsistent snapshot; in particular never manufacture album tokens.
        let rowIDs = rows.map(\.id)
        let memberIDs = rows.flatMap { $0.members.map(\.id) }
        guard Set(rowIDs).count == rowIDs.count, Set(memberIDs).count == memberIDs.count else {
            entries.removeAll()
            return rows.map(canonical)
        }

        var occurrences: [Key: [Int]] = [:]
        for (index, row) in rows.enumerated() {
            let keys = Set(row.members.compactMap(Self.key))
            for key in keys { occurrences[key, default: []].append(index) }
        }
        var eligible: [Key: Int] = [:]
        for (key, indices) in occurrences {
            // >10, tombstones/decryption breaks or repeated chunks retain the
            // existing canonical grouping. A bare album ID would collide here.
            guard indices.count == 1, let index = indices.first,
                  key.chat == nextScope.chat, key.sender == nextScope.owner else { continue }
            let row = rows[index]
            guard (1...10).contains(row.members.count), row.sort.date.timeIntervalSince1970.isFinite,
                  row.members.allSatisfy({ $0.eligible && Self.key($0) == key &&
                      !$0.id.isEmpty && $0.sort.date.timeIntervalSince1970.isFinite }) else { continue }
            eligible[key] = index
        }
        // Missing members/rows never survive a window change, hide or deletion.
        entries = entries.filter { eligible[$0.key] != nil }

        var anchored: [(index: Int, sort: SortKey, projection: Projection)] = []
        var ordinary: [(sort: SortKey, projection: Projection)] = []
        let keyByIndex = Dictionary(uniqueKeysWithValues: eligible.map { ($0.value, $0.key) })
        var reservedIDs = Set(rowIDs).union(entries.values.map(\.token))
        for (index, row) in rows.enumerated() {
            guard let key = keyByIndex[index],
                  entries[key] != nil || row.members.contains(where: \.pending) else {
                ordinary.append((row.sort, canonical(row)))
                continue
            }
            var entry: Entry
            if let previous = entries[key] { entry = previous }
            else {
                var token: String
                repeat {
                    nextToken &+= 1
                    token = "pending-album:\(tokenPrefix):\(nextToken)"
                } while reservedIDs.contains(token)
                reservedIDs.insert(token)
                entry = Entry(token: token, anchor: row.sort, order: [], firstSeen: [:])
            }
            let currentIDs = Set(row.members.map(\.id))
            entry.order.removeAll { !currentIDs.contains($0) }
            entry.firstSeen = entry.firstSeen.filter { currentIDs.contains($0.key) }
            // Known relative order is immutable across ACK timestamps. A newly
            // materialized/paged member is inserted using its first canonical
            // key against the captured keys, not a later ACK's mutable date.
            for member in row.members where entry.firstSeen[member.id] == nil {
                let insertion = entry.order.firstIndex { id in
                    entry.firstSeen[id].map { member.sort < $0 } ?? false
                } ?? entry.order.endIndex
                entry.order.insert(member.id, at: insertion)
                entry.firstSeen[member.id] = member.sort
            }
            entries[key] = entry
            anchored.append((index, entry.anchor,
                Projection(sourceID: row.id, id: entry.token, members: entry.order)))
        }

        guard !anchored.isEmpty else { return ordinary.map(\.projection) }
        anchored.sort { $0.sort == $1.sort ? $0.index < $1.index : $0.sort < $1.sort }
        // Merge pinned display anchors without reordering ordinary canonical
        // rows. Confirmed server history is canonical again after view reopen.
        var result: [Projection] = []
        result.reserveCapacity(rows.count)
        var index = 0
        for row in ordinary {
            while index < anchored.count, anchored[index].sort < row.sort {
                result.append(anchored[index].projection)
                index += 1
            }
            result.append(row.projection)
        }
        while index < anchored.count {
            result.append(anchored[index].projection)
            index += 1
        }
        return result
    }

    private static func key(_ member: Member) -> Key? {
        guard let album = member.album, !album.isEmpty, album.utf8.count <= 128 else { return nil }
        return Key(chat: member.chat, sender: member.sender, album: album)
    }
}
