import Foundation
import SQLite3

@main struct ConferenceInviteV2Contracts {
    static var checks = 0
    static func check(_ condition: Bool, _ label: String) {
        precondition(condition, label); checks += 1
    }
    static func verify(_ value: ConferenceInvite, expected: [String: Any], id: String) {
        check(value.server.absoluteString == expected["server"] as? String, id + ": origin")
        check(value.roomID.uuidString.lowercased() == expected["roomId"] as? String, id + ": room UUID bytes")
        check(value.secret == expected["secret"] as? String, id + ": full capability")
        check(value.mediaKey == expected["mediaKey"] as? String, id + ": full media key")
        check(!String(reflecting: value).contains(value.secret), id + ": redacted capability description")
    }
    static func json(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]) }
    @MainActor static func main() throws {
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
        let publicOrigin = fixture["publicOrigin"] as! String
        #if canImport(CryptoKit)
        let nativeFingerprint = true
        #else
        let nativeFingerprint = false
        #endif
        var skippedPublic = 0
        for row in fixture["valid"] as! [[String: Any]] {
            let id = row["id"] as! String
            let current = row["currentServer"] as! String
            if !nativeFingerprint && (try? ConferenceInvite.origin(URL(string: current)!))?.absoluteString == publicOrigin { skippedPublic += 1; continue }
            let parsed: ConferenceInvite
            do { parsed = try ConferenceInvite.parse(row["link"] as! String, currentServer: URL(string: current)!) }
            catch { preconditionFailure("valid v2 vector rejected: " + id) }
            verify(parsed, expected: row["expected"] as! [String: Any], id: id)
            check(parsed.wireLink.absoluteString == row["canonicalLink"] as? String, id + ": canonical production export")
            check(try ConferenceInvite.parse(parsed.link.absoluteString, currentServer: parsed.server) == parsed, id + ": v2 roundtrip")
        }
        for row in fixture["legacyValid"] as! [[String: Any]] {
            let parsed: ConferenceInvite
            do { parsed = try ConferenceInvite.parse(row["link"] as! String, currentServer: URL(string: row["currentServer"] as! String)!) }
            catch { preconditionFailure("valid legacy vector rejected: " + (row["id"] as! String)) }
            verify(parsed, expected: row["expected"] as! [String: Any], id: row["id"] as! String)
            check(try ConferenceInvite.parse(parsed.legacyLink.absoluteString, currentServer: parsed.server) == parsed, "legacy explicit export")
        }
        for row in fixture["invalid"] as! [[String: Any]] {
            do {
                _ = try ConferenceInvite.parse(row["link"] as! String, currentServer: URL(string: row["currentServer"] as! String)!)
                preconditionFailure("accepted malformed invitation: " + (row["id"] as! String))
            } catch { checks += 1 }
        }
        let fallback = ConferenceInvitationMetadata.fallbackText
        for row in fixture["metadataValid"] as! [[String: Any]] {
            let metadata: ConferenceInvitationMetadata
            do { metadata = try JSONDecoder().decode(ConferenceInvitationMetadata.self, from: json(row["metadata"]!)) }
            catch { preconditionFailure("valid metadata shape rejected: " + (row["id"] as! String)) }
            let expected = row["expected"] as! [String: Any]
            if nativeFingerprint || metadata.server != publicOrigin {
                let parsed = try metadata.validated(currentServer: URL(string: metadata.server)!)
                verify(parsed, expected: expected["invite"] as! [String: Any], id: row["id"] as! String)
            } else { skippedPublic += 1 }
            check(((try? ConferenceInvite.origin(URL(string: row["currentServer"] as! String)!))?.absoluteString == metadata.server) == expected["serverMatches"] as? Bool,
                "different selected server remains a valid inert metadata card")
            check((metadata.expiresAt <= (row["nowUnix"] as! NSNumber).int64Value) == expected["expiredHint"] as? Bool, "expiry is only a presentation hint")
            let body = MessageContent(kind: "text", text: metadata.invite, conferenceInvite: metadata)
            check(body.text == fallback, "constructor never indexes a capability as message text")
            var message = DecryptedChatMessage(clientMessageId: "00000000-0000-4000-8000-000000000101", serverId: nil,
                chatId: "00000000-0000-4000-8000-000000000102", senderId: "00000000-0000-4000-8000-000000000103",
                sequence: nil, createdAt: Date(timeIntervalSince1970: 1), kind: "content", targetMessageId: nil, status: .pending, content: body)
            let payload = MessageSendComposer.payload(for: message)
            check(MessageContentCodec.decode(payload.plaintext).conferenceInvite == metadata, "actual sender composer retains invitation")
            check(MessageSendComposer.payload(for: message).plaintext == payload.plaintext, "same logical payload on transport retry")
            let authenticated = try MessageContentCodec.authenticate(payload.plaintext, chatId: message.chatId, clientMessageId: message.clientMessageId,
                operation: "content", targetMessageId: nil)
            let decoded = MessageContentCodec.decode(authenticated)
            check(decoded.conferenceInvite == metadata && decoded.text == fallback, "Signal-authenticated content preserves typed metadata and harmless fallback")
            check(MessageContentCodec.authenticates(decoded, chatId: message.chatId, clientMessageId: message.clientMessageId, operation: "content", targetMessageId: nil), "existing authenticated text contract")
            message.content.forwarded = true; message.content.replySnapshot = nil
            check(MessageContentCodec.decode(MessageSendComposer.payload(for: message).plaintext).conferenceInvite == metadata, "forward copying does not discard capability")
            check(try JSONDecoder().decode(MessageContent.self, from: JSONEncoder().encode(message.content)).conferenceInvite == metadata, "direct persisted/history content roundtrip")
            check(!String(reflecting: metadata).contains(metadata.invite), "metadata description redacted")
        }
        for row in fixture["metadataInvalid"] as! [[String: Any]] {
            let data = try json(["kind": "text", "text": "coco://c#SENSITIVE_CAPABILITY", "conference_invite": row["metadata"]!])
            let content = MessageContentCodec.decode(data)
            check(content.text == fallback, "invalid metadata never displays its wire text: " + (row["id"] as! String))
            if let metadata = content.conferenceInvite {
                do { _ = try metadata.validated(currentServer: URL(string: metadata.server)!); preconditionFailure("actionable invalid metadata " + (row["id"] as! String)) }
                catch { checks += 1 }
            } else { checks += 1 }
            check(try JSONDecoder().decode(MessageContent.self, from: data).text == fallback, "store/history direct decoder also masks invalid metadata")
        }
        let plain = MessageContentCodec.decode(Data("coco://conference?server=legacy#secret".utf8))
        check(plain.text == "coco://conference?server=legacy#secret", "standalone legacy text is not rewritten")
        let malformed = try json(["kind": "unknown", "text": "secret", "conference_invite": ["version": 99]])
        check(MessageContentCodec.decode(malformed).text == fallback, "unknown-kind optional metadata never becomes raw JSON")
        let badAuth = try json(["kind": "unknown", "authentication": true, "conference_invite": ["version": 99]])
        check(MessageContentCodec.decode(badAuth).kind == "invalid_authenticated_content", "optional extension never weakens authentication rejection")
        try verifyStore(fixture)
        for count in 0...30 {
            let ids = Array(0..<count)
            for landscape in [false, true] { for accessible in [false, true] {
                let pages = ConferenceGridPolicy.pages(ids, landscape: landscape, accessibility: accessible)
                check(pages.flatMap { $0 } == ids, "every participant reachable once")
                check(pages.allSatisfy { $0.count <= ConferenceGridPolicy.pageSize(landscape: landscape, accessibility: accessible) }, "bounded decoder page")
            } }
            for selected in 1...30 {
                let size = ConferenceGridPolicy.visibleCount(selected, total: count, fitEveryone: false, landscape: false, accessibility: false)
                let sections = ConferenceGridPolicy.chunks(ids, count: size)
                check(sections.flatMap { $0 } == ids, "custom scrolling never omits or duplicates participants")
                check(sections.allSatisfy { $0.count <= min(selected, max(1, count)) }, "custom viewport follows selected number")
            }
            check(ConferenceGridPolicy.visibleCount(5, total: count, fitEveryone: true, landscape: false, accessibility: true) == max(1, count), "all-fit retains everyone regardless of selected count")
        }
        let viewport = CGRect(x: 0, y: 0, width: 300, height: 500)
        var frames = Dictionary(uniqueKeysWithValues: (0..<10).map { ("peer-\($0)", CGRect(x: 0, y: $0 * 100 - 100, width: 100, height: 95)) })
        let visible = ConferenceGridPolicy.visibleIDs(frames, viewport: viewport, limit: 5)
        check(visible.count == 5 && !visible.contains("peer-0") && !visible.contains("peer-6"), "scrolling subscribes only clipped viewport, not preloaded lower rows")
        frames["offscreen"] = CGRect(x: 1000, y: 0, width: 100, height: 100)
        check(!ConferenceGridPolicy.visibleIDs(frames, viewport: viewport, limit: 30).contains("offscreen"), "horizontal offscreen tile never decodes")
        check(ConferenceGridPolicy.visibleIDs(frames, viewport: .zero, limit: 30).isEmpty, "zero viewport releases video")
        print("PASS: \(checks) production conference v2 parser/metadata/composer/store/history assertions; public-fingerprint cases skipped here=\(skippedPublic)")
        if ProcessInfo.processInfo.environment["UNYRI_REQUIRE_NATIVE_CRYPTO"] == "1" { check(skippedPublic == 0, "native CryptoKit is required for full public fingerprint coverage") }
    }
    @MainActor static func verifyStore(_ fixture: [String: Any]) throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[2])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let row = (fixture["metadataValid"] as! [[String: Any]])[0]
        let metadata = try JSONDecoder().decode(ConferenceInvitationMetadata.self, from: json(row["metadata"]!))
        let db = directory.appendingPathComponent("account-a.sqlite3")
        let store = try MessageStore(databaseURL: db)
        let client = "00000000-0000-4000-8000-000000000201", chat = "00000000-0000-4000-8000-000000000202", owner = "00000000-0000-4000-8000-000000000203"
        var message = DecryptedChatMessage(clientMessageId: client, serverId: nil, chatId: chat, senderId: owner,
            sequence: nil, createdAt: Date(timeIntervalSince1970: 1), kind: "content", targetMessageId: nil, status: .pending,
            content: MessageContent(kind: "text", text: metadata.invite, conferenceInvite: metadata))
        try store.upsert(message)
        let first = try store.message(byClientMessageId: client)!
        check(first.content.conferenceInvite == metadata, "actual SQLite insert retains optional metadata")
        let payload = MessageSendComposer.payload(for: first).plaintext
        try store.beginSend(clientMessageId: client, fallbackText: first.content.text)
        let restarted = try MessageStore(databaseURL: db)
        let retry = try restarted.message(byClientMessageId: client)!
        check(retry.sendAttempted && MessageSendComposer.payload(for: retry).plaintext == payload, "real persisted/reopened retry keeps exact typed payload")
        try restarted.saveChatValue(chatId: chat, name: "conference_declined:" + client + ":" + metadata.id, value: "1")
        let reopened = try MessageStore(databaseURL: db)
        check(try reopened.chatValue(chatId: chat, name: "conference_declined:" + client + ":" + metadata.id) == "1", "decline survives account-store reopen")
        let otherOwner = try MessageStore(databaseURL: directory.appendingPathComponent("account-b.sqlite3"))
        check(try otherOwner.chatValue(chatId: chat, name: "conference_declined:" + client + ":" + metadata.id) == nil, "decline is not shared with a different account workspace")
        message.serverId = "00000000-0000-4000-8000-000000000204"; message.sequence = 1; message.status = .sent
        try reopened.upsertSentWithPendingEdit(message, editRow: nil)
        check(try reopened.latestPreviewByChat()[chat] == .conferenceInvitation, "generic SQLite preview is a capability-free typed label")
        check(try reopened.searchVisibleMessages(query: metadata.invite).isEmpty, "search never indexes the invitation capability")
        let historyServer = "00000000-0000-4000-8000-000000000206" // Existing archive binds the server UUID, not the API URL.
        let archive = try reopened.exportHistoryArchive(server: historyServer, owner: owner,
            snapshot: "00000000-0000-4000-8000-000000000205", assertCurrentAndQuiescent: {})
        check(archive.messages.count == 1, "confirmed invitation included in account history")
        let restored = try MessageStore(databaseURL: directory.appendingPathComponent("restored.sqlite3"))
        _ = try restored.importHistoryArchive(archive, server: historyServer, owner: owner, assertCurrentAndQuiescent: {})
        check(try restored.message(byClientMessageId: client)?.content.conferenceInvite == metadata, "actual history export/import retains actionable encrypted-envelope metadata")
        // A pre-existing malformed content_json must also remain harmless when
        // decoded directly from SQLite, not only through MessageContentCodec.
        var raw: OpaquePointer?
        check(sqlite3_open(db.path, &raw) == SQLITE_OK, "open test-only raw JSON seam")
        defer { sqlite3_close(raw) }
        var statement: OpaquePointer?
        check(sqlite3_prepare_v2(raw, "UPDATE messages SET content_json=? WHERE client_message_id=?", -1, &statement, nil) == SQLITE_OK, "prepare legacy JSON seam")
        defer { sqlite3_finalize(statement) }
        let malicious = String(decoding: try json(["kind": "text", "text": metadata.invite, "conference_invite": ["version": 99]]), as: UTF8.self)
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(statement, 1, malicious, -1, transient); sqlite3_bind_text(statement, 2, client, -1, transient)
        check(sqlite3_step(statement) == SQLITE_DONE, "legacy content JSON written")
        check(try reopened.message(byClientMessageId: client)?.content.text == ConferenceInvitationMetadata.fallbackText, "raw SQLite invalid metadata cannot expose secret text")
    }
}
