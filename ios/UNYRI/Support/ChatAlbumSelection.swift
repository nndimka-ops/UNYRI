import Foundation

/// Resolve actions only inside the materialized, privacy-filtered timeline.
/// It never fetches missing history or expands a captured delete confirmation.
enum UnyriChatAlbumSelection {
    struct Item: Equatable {
        let id: String
        let chat: String
        let sender: String
        let album: String?
    }

    static func members(of target: Item, rows: [[Item]], individual: Bool = false) -> [String] {
        guard !individual, let album = target.album, !album.isEmpty,
              let row = rows.first(where: { $0.contains(target) }),
              row.count > 1, row.count <= 10,
              Set(row.map(\.id)).count == row.count,
              row.allSatisfy({ !$0.id.isEmpty && $0.chat == target.chat && $0.sender == target.sender && $0.album == album })
        else { return [target.id] }
        return row.map(\.id)
    }

    /// Snapshot taken by the action, intersected with the exact displayed group.
    /// Repository/store eligibility is independently rechecked at mutation time.
    static func pendingMembers(of target: Item, rows: [[Item]], pendingIDs: Set<String>) -> [String] {
        members(of: target, rows: rows).filter { pendingIDs.contains($0) }
    }

    static func toggled(_ selected: Set<String>, members: [String]) -> Set<String> {
        let group = Set(members)
        return group.isSubset(of: selected) ? selected.subtracting(group) : selected.union(group)
    }

    static func forwardingAlbums(_ items: [Item], makeID: () -> String = { UUID().uuidString.lowercased() }) -> [String: String] {
        struct Key: Hashable { let chat: String; let sender: String; let album: String }
        var rows: [[Item]] = []
        var openGroups: [Key: Int] = [:]
        for item in items {
            guard let album = item.album, !album.isEmpty else { rows.append([item]); continue }
            let key = Key(chat: item.chat, sender: item.sender, album: album)
            if let index = openGroups[key], rows[index].count < 10 {
                rows[index].append(item)
            } else { openGroups[key] = rows.count; rows.append([item]) }
        }
        var result: [String: String] = [:]
        for row in rows where row.count > 1 && row.first?.album?.isEmpty == false {
            let fresh = makeID()
            for item in row { result[item.id] = fresh }
        }
        return result
    }
}

/// Explicit new-batch order survives deferred delivery/retry. Old/mixed/invalid rows retain legacy order.
enum UnyriAlbumItemOrder {
    static func sorted<Item>(_ items: [Item], index: (Item) -> Int?) -> [Item] {
        let values = items.compactMap(index)
        guard values.count == items.count, Set(values).count == values.count,
              values.allSatisfy({ (0..<10).contains($0) }) else { return items }
        return items.sorted { index($0)! < index($1)! }
    }
}
