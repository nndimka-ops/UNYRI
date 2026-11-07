import Foundation

// The last plaintext gate before Signal encryption must preserve the persisted
// attachment envelope. A codec-only round trip cannot detect fields discarded
// by MessageSendComposer (the six-photo cross-platform regression).
struct AttachmentWireFixture: Codable {
    let id: String
    let album: String?
    let caption: String?
    let media: String
    let mime: String
    let forwarded: Bool
    let reply: Bool
    let plaintext: String
}

var failures = 0
var assertions = 0
var fixtures: [AttachmentWireFixture] = []
func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    assertions += 1
    if !condition() {
        failures += 1
        FileHandle.standardError.write(Data("FAIL: \(description)\n".utf8))
    }
}

let quote = ReplySnapshot(senderId: "peer", contentType: "image", preview: "Photo", sequenceHint: 9)
func verify(_ name: String, album: String?, caption: String?, media: String,
            version: Int = 1, forwarded: Bool = false, reply: Bool = false) throws {
    // Production video is file media + video MIME/metadata on both platforms.
    let attachment = AttachmentMetadata(attachmentId: name, mediaType: media == "video" ? "file" : media,
        objectKey: "attachments/\(name)", size: 123, encryptedSize: 139,
        mimeType: media == "video" ? "video/mp4" : media == "voice" ? "audio/mp4" : media == "file" ? "application/pdf" : "image/jpeg",
        encryption: EncryptionInfo(version: version, key: version == 1 ? "Zml4dHVyZQ==" : nil,
            nonce: version == 1 ? "bm9uY2U=" : nil, keyset: version == 2 ? "a2V5c2V0" : nil, sha256: "aGFzaA=="),
        filename: media == "file" ? "Original.pdf" : nil,
        image: media == "image" || media == "video" ? ImageInfo(width: 720, height: 1280) : nil,
        voice: media == "voice" ? VoiceInfo(durationMs: 1234) : nil,
        thumbnail: media == "image" || media == "video" ? "dGh1bWI=" : nil,
        video: media == "video" ? VideoInfo(durationMs: 1234) : nil)
    let content = MessageContent(kind: MessageContent.kindAttachment, text: caption,
        attachment: attachment, replySnapshot: reply ? quote : nil, forwarded: forwarded, albumId: album)
    let message = DecryptedChatMessage(clientMessageId: name, serverId: nil, chatId: "wire-chat", senderId: "sender",
        sequence: nil, createdAt: Date(timeIntervalSince1970: 1), kind: "content", targetMessageId: reply ? "target" : nil,
        status: .pending, content: content)
    let payload = MessageSendComposer.payload(for: message)
    check(payload.kind == "content" && payload.attachmentIds == [name], "\(name): attachment routing")
    check(payload.targetMessageId == message.targetMessageId, "\(name): reply routing")
    check(payload.plaintext == MessageSendComposer.payload(for: message).plaintext, "\(name): stable retry plaintext")
    let decoded = MessageContentCodec.decode(payload.plaintext)
    check(decoded.albumId == album, "\(name): album survives actual sender composer")
    check(decoded.text == caption, "\(name): caption survives actual sender composer")
    check(decoded.attachment == attachment, "\(name): crypto/preview/voice/video metadata preserved")
    check(decoded.replySnapshot == content.replySnapshot && decoded.forwarded == forwarded, "\(name): quote and forward hints preserved")
    let authenticated = try MessageContentCodec.authenticate(payload.plaintext, chatId: message.chatId,
        clientMessageId: name, operation: "content", targetMessageId: message.targetMessageId, targetClientMessageId: nil)
    let protected = MessageContentCodec.decode(authenticated)
    check(protected.albumId == album && protected.text == caption && protected.attachment == attachment,
          "\(name): authentication does not discard attachment fields")
    check(MessageContentCodec.authenticates(protected, chatId: message.chatId, clientMessageId: name,
        operation: "content", targetMessageId: message.targetMessageId), "\(name): authenticated envelope accepted")
    let json = try JSONSerialization.jsonObject(with: authenticated) as! [String: Any]
    check(json["albumId"] == nil && (json["album_id"] as? String) == album, "\(name): Android snake_case album key")
    check((json["text"] as? String) == caption, "\(name): Android caption key")
    fixtures.append(AttachmentWireFixture(id: name, album: album, caption: caption, media: attachment.mediaType, mime: attachment.mimeType,
        forwarded: forwarded, reply: reply, plaintext: String(decoding: authenticated, as: UTF8.self)))
}

for version in [1, 2] {
    for count in [1, 2, 6, 10] {
        for index in 0..<count {
            try verify("v\(version)-batch\(count)-\(index)", album: "v\(version)-album\(count)",
                caption: index == 0 ? "Подпись **literal** 🦋\nsecond line" : nil,
                media: index % 3 == 2 ? "video" : "image", version: version)
        }
    }
    try verify("v\(version)-single", album: nil, caption: "", media: "image", version: version)
    try verify("v\(version)-voice", album: nil, caption: nil, media: "voice", version: version)
    try verify("v\(version)-file", album: nil, caption: "document caption", media: "file", version: version)
    // Forwarding intentionally clears albumId upstream: never invent a batch.
    try verify("v\(version)-forward", album: nil, caption: "forwarded caption", media: "image", version: version, forwarded: true)
    try verify("v\(version)-reply", album: "v\(version)-reply-album", caption: "reply caption", media: "image", version: version, reply: true)
}
let legacy = AttachmentMetadata(attachmentId: "legacy", mediaType: "file", objectKey: "legacy", size: 1,
    encryptedSize: 17, mimeType: "application/pdf", encryption: EncryptionInfo(key: "key", nonce: "nonce", sha256: "hash"))
let legacyDecoded = MessageContentCodec.decode(MessageContentCodec.encodeAttachment(legacy))
check(legacyDecoded.text == nil && legacyDecoded.albumId == nil && legacyDecoded.attachment == legacy,
      "Default arguments preserve existing single-attachment callers")
if let path = ProcessInfo.processInfo.environment["UNYRI_MESSAGE_WIRE_FIXTURES"] {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
    try encoder.encode(fixtures).write(to: URL(fileURLWithPath: path), options: .atomic)
}
if let path = ProcessInfo.processInfo.environment["UNYRI_ANDROID_RETURN_FIXTURES"] {
    let returned = try JSONDecoder().decode([AttachmentWireFixture].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
    check(returned.count == fixtures.count, "Android returns every composed attachment")
    for (expected, actual) in zip(fixtures, returned) {
        let content = MessageContentCodec.decode(Data(actual.plaintext.utf8))
        check(expected.id == actual.id && content.attachment?.attachmentId == expected.id, "Android-to-iOS member identity/order")
        check(content.albumId == expected.album && content.text == expected.caption, "Android-to-iOS album and literal caption")
        check(content.forwarded == expected.forwarded && (content.replySnapshot != nil) == expected.reply, "Android-to-iOS forward/reply")
    }
}
guard failures == 0 else { fatalError("\(failures) attachment sender assertions failed") }
print("PASS: actual iOS sender/composer/authenticated wire: \(fixtures.count) envelopes, \(assertions) assertions; 1/2/6/10 photo-video albums, captions, v1/v2, reply/forward, voice/file, deterministic retries")
