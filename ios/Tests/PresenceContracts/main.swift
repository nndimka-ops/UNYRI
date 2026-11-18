import Foundation

@main struct PresenceContracts {
    static func main() throws {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let peer = "00000000-0000-0000-0000-000000000001"
        let other = "00000000-0000-0000-0000-000000000002"
        let frame = try decoder.decode(PresenceSnapshot.self, from: Data("""
        {"type":"presence.snapshot","items":[{"user_id":"\(peer)","online":true,"last_seen_at":null}],"observed_at":"2026-10-10T12:00:00.000Z","valid_for_ms":5000}
        """.utf8))
        let observed = PresencePolicy.timestamp(frame.observedAt)!
        let initial = PresencePolicy.snapshot(frame, requestedIDs: [peer, other], receivedAt: observed, uptime: 100, newerThan: nil)!
        precondition(initial.entries.count == 1 && initial.entries[other] == nil, "Missing/blocked peers must stay unknown")
        precondition(initial.entries[peer]!.item.online && initial.entries[peer]!.item.lastSeenAt == nil)
        precondition(PresencePolicy.fresh(initial.entries[peer]!, uptime: 104.999))
        precondition(!PresencePolicy.fresh(initial.entries[peer]!, uptime: 105), "Online must expire at the lease boundary")
        precondition(PresencePolicy.snapshot(frame, requestedIDs: [peer], receivedAt: observed, uptime: 200, newerThan: observed) == nil, "Duplicate observations must not renew presence")
        precondition(PresencePolicy.snapshot(frame, requestedIDs: [peer], receivedAt: observed.addingTimeInterval(6), uptime: 200, newerThan: nil) == nil, "Delayed observations must not become fresh online")
        precondition(PresencePolicy.snapshot(frame, requestedIDs: [peer], receivedAt: observed.addingTimeInterval(-6), uptime: 200, newerThan: nil) == nil, "Far-future observations must fail closed")
        precondition(PresencePolicy.snapshot(frame, requestedIDs: [peer], receivedAt: observed.addingTimeInterval(2), uptime: 200, newerThan: observed) == nil, "Foreground resume must retain the duplicate watermark")
        let delayed = PresencePolicy.snapshot(frame, requestedIDs: [peer], receivedAt: observed.addingTimeInterval(2), uptime: 200, newerThan: nil)!
        precondition(delayed.entries[peer]!.expiresAtUptime == 203)
        let missing = PresenceSnapshot(type: "presence.snapshot", items: [], observedAt: "2026-10-10T12:00:02Z", validForMs: 5000)
        precondition(PresencePolicy.snapshot(missing, requestedIDs: [peer], receivedAt: observed.addingTimeInterval(2), uptime: 202, newerThan: observed)!.entries.isEmpty, "Full snapshots clear omitted peers rather than retain online")
        var history = PresenceHistory()
        history.apply(initial.entries, requestedIDs: [peer])
        precondition(history.date(for: peer) == nil, "An authoritative null last-seen must not become an inferred historical fallback")
        let visibleOnline = PresenceObservation(item: PresenceItem(userId: peer, online: true, lastSeenAt: frame.observedAt), observedAt: observed, expiresAtUptime: 105)
        history.apply([peer: visibleOnline], requestedIDs: [peer])
        precondition(history.date(for: peer) == observed, "An online observation with visible last-seen can retain historical evidence")
        precondition(history.date(for: peer.uppercased()) == observed)
        let offlineDate = observed.addingTimeInterval(10)
        let offline = PresenceObservation(item: PresenceItem(userId: peer, online: false,
            lastSeenAt: "2026-10-10T12:00:10Z"), observedAt: offlineDate, expiresAtUptime: 215)
        history.apply([peer: offline], requestedIDs: [peer])
        precondition(history.date(for: peer) == offlineDate && !PresencePolicy.fresh(offline, uptime: 216),
            "Historical last-seen survives lease expiry without making it live online")
        history.apply([:], requestedIDs: [peer])
        precondition(history.date(for: peer) == nil, "Authoritative omission removes blocked/unknown historical fallback")
        history.apply([peer: offline], requestedIDs: [other])
        precondition(history.date(for: peer) == nil, "Unrequested input cannot populate historical identity evidence")
        history.apply([peer: offline], requestedIDs: [peer])
        history.clear()
        precondition(history.dates.isEmpty, "Account invalidation clears historical memory")
        let ids = (3...80).map { String(format: "00000000-0000-0000-0000-%012d", $0) }
        let combined = PresencePolicy.subscriptions([(0, "directory", ids + [peer, "invalid"]), (100, "chat", [peer.uppercased(), peer])])
        precondition(combined.count == 64 && combined.first == peer && Set(combined).count == 64)
        precondition(PresencePolicy.subscriptions([(0, "directory", [other])]) == [other], "Released chat ownership cannot overwrite directory ownership")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 2 * 3600)!
        let locale = Locale(identifier: "en_US")
        let now = PresencePolicy.timestamp("2026-10-10T22:30:00Z")!
        let today = PresencePolicy.timestamp("2026-10-10T22:10:00Z")!
        let yesterday = PresencePolicy.timestamp("2026-10-10T20:00:00Z")!
        precondition(PresencePolicy.lastSeenLabel(today, now: now, locale: locale, calendar: calendar).key == "Last seen today at %@")
        precondition(PresencePolicy.lastSeenLabel(yesterday, now: now, locale: locale, calendar: calendar).key == "Last seen yesterday at %@", "Use device timezone rather than UTC day")
        let priorYear = PresencePolicy.timestamp("2025-09-01T13:00:00Z")!
        precondition(PresencePolicy.lastSeenLabel(priorYear, now: now, locale: locale, calendar: calendar).value.contains("2025"))
        let legacy = try decoder.decode(PublicUser.self, from: Data("""
        {"id":"\(peer)","username":"synthetic","display_name":"Synthetic","bio":null,"avatar_url":null,"profile_version":1,"avatar_version":0,"has_avatar":false}
        """.utf8))
        precondition(legacy.online == nil && legacy.lastSeenAt == nil, "Legacy users must not decode as offline")
        let directory = (100...209).map { index in
            PublicUser(id: String(format: "00000000-0000-0000-0000-%012d", index), username: "alice_\(index)",
                       displayName: "Алиса", bio: nil, avatarUrl: nil, profileVersion: 1, avatarVersion: 0,
                       hasAvatar: false, online: true, lastSeenAt: frame.observedAt)
        }
        let identities = DirectoryCachePolicy.identities(directory + [directory[0]])
        precondition(identities.count == 100 && Set(identities.map(\.id)).count == 100)
        precondition(identities.allSatisfy { $0.online == nil && $0.lastSeenAt == nil }, "Disk identities must never persist fresh presence hints")
        precondition(DirectoryCachePolicy.matching(identities, query: "АЛИСА").count == 100)
        precondition(DirectoryCachePolicy.matching(identities, query: "@alice_100").map(\.id) == [directory[0].id])
        precondition(DirectoryCachePolicy.matching(identities, query: "unknown").isEmpty)
        let cache = DirectoryIdentityCache(scope: "synthetic-server|synthetic-owner", users: identities)
        let data = try JSONEncoder().encode(cache)
        let disk = try JSONDecoder().decode(DirectoryIdentityCache.self, from: data)
        precondition(disk.scope == cache.scope && disk.users == identities)
        precondition(DirectoryCachePolicy.restored(disk, scope: cache.scope, query: "").count == 100)
        precondition(DirectoryCachePolicy.restored(disk, scope: "other-server|synthetic-owner", query: "").isEmpty)
        precondition(DirectoryCachePolicy.restored(disk, scope: "synthetic-server|other-owner", query: "").isEmpty)
        let json = String(decoding: data, as: UTF8.self)
        precondition(!json.contains("\"online\"") && !json.contains("\"lastSeenAt\""))
        print("Presence contracts passed: leases, historical timestamps without stale online, blocked omission, account reset, delayed/duplicate snapshots, owner union, timezone/year labels, bounded hint-free directory cache")
    }
}
