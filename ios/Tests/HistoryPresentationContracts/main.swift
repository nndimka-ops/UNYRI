import Foundation

func row(_ id: String, _ sequence: Int64?, _ status: MessageStatus) -> DecryptedChatMessage {
    DecryptedChatMessage(clientMessageId: id, serverId: id, chatId: "chat", senderId: "peer",
        sequence: sequence, createdAt: Date(timeIntervalSince1970: 0), kind: "content",
        targetMessageId: nil, status: status, content: MessageContent(kind: MessageContent.kindText, text: "content"))
}
let rows = [row("old1", 1, .decryptFailed), row("readable", 2, .delivered),
            row("old2", 3, .decryptFailed), row("identity", 4, .identityChanged),
            row("new", 6, .decryptFailed), row("pending", nil, .pending)]
precondition(collapseUnavailableHistory(rows, cutoff: 5).map(\.id) == ["readable", "identity", "new", "pending"])
precondition(collapseUnavailableHistory(rows, cutoff: 0) == rows)
precondition(rows.count == 6)
precondition(!isUnavailableHistory(row("identity", 1, .identityChanged), cutoff: 5))
let recovered = [row("old1", 1, .delivered), row("old2", 2, .decryptFailed)]
precondition(collapseUnavailableHistory(recovered, cutoff: 2) == [recovered[0]])
precondition(collapseUnavailableHistory([row("newest", 10, .decryptFailed), row("older", 2, .decryptFailed)], cutoff: 10).map(\.id) == [])
print("PASS: history grouping, recovery, pagination, old-server compatibility and security warnings")
let failures = [row("old", 1, .decryptFailed), row("latest", 12, .decryptFailed), row("readable", 9, .read), row("warning", 10, .identityChanged), row("recent", 8, .decryptFailed)]
precondition(collapseUnavailableHistory(failures, cutoff: 5, collapseRecentFailures: true).map(\.id) == ["latest", "readable", "warning"])
precondition(collapseUnavailableHistory(failures, cutoff: 5, collapseRecentFailures: false) == Array(failures.dropFirst()))
precondition(collapseUnavailableHistory(failures, cutoff: 0, collapseRecentFailures: true).map(\.id) == ["latest", "readable", "warning"])
print("PASS: separate expandable recent failures preserve readable messages and identity warnings")
var deletedFailure = row("deleted", 20, .decryptFailed)
deletedFailure.deleteSequence = 21
var hiddenFailure = row("hidden", 22, .decryptFailed)
hiddenFailure.hiddenLocally = true
let bannerRows = [deletedFailure, hiddenFailure, row("old", 1, .decryptFailed)]
precondition(!hasCurrentDecryptionFailures(bannerRows, chatId: "chat", loadedChatId: "chat", cutoff: 5))
precondition(!hasCurrentDecryptionFailures(failures, chatId: "chat", loadedChatId: "previous", cutoff: 5))
precondition(!hasCurrentDecryptionFailures(failures, chatId: "other", loadedChatId: "other", cutoff: 5))
precondition(hasCurrentDecryptionFailures(failures, chatId: "chat", loadedChatId: "chat", cutoff: 5))
print("PASS: decryption banner excludes deleted, hidden, old and other-chat records; real failures remain visible")


// B-023 follow-up 2: one file projection across search, banners and quotes.
let directOffer = DirectFileOffer(
    transferId: "10000000-0000-4000-8000-000000000001",
    offerClientMessageId: "10000000-0000-4000-8000-000000000002",
    senderUserId: "10000000-0000-4000-8000-000000000003",
    senderDeviceId: "10000000-0000-4000-8000-000000000004",
    recipientUserId: "10000000-0000-4000-8000-000000000005",
    offerNonce: "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    filename: "b023-report.pdf", mimeType: "application/pdf", size: 1024,
    sha256: String(repeating: "a", count: 64), expiresAt: 999999)
precondition(directOffer.hasValidShape)
for label in ["Файл", "File", "Файл"] {
    for fallback in [DirectFileOffer.fallbackText, "Передача файла напрямую"] {
        for caption in ["", "Подпись Caption Підпис 👋"] {
            let suffix: String = caption.isEmpty ? "" : "\n\n" + caption
            let raw: String = fallback + suffix
            let expected: String = label + ": b023-report.pdf" + suffix
            let projected: String? = DirectFilePreviewText.projected(text: raw, offer: directOffer)
            precondition(DirectFilePreviewText.display(text: raw, offer: directOffer, label: label) == expected)
            precondition(DirectFilePreviewText.display(text: projected, label: label) == expected)
            precondition(DirectFilePreviewText.display(text: raw, label: label) == label)
        }
    }
}
var directMessage: DecryptedChatMessage = row("direct", 30, .delivered)
directMessage.content = MessageContent(kind: "text", text: DirectFileOffer.fallbackText, directFile: directOffer)
precondition(directMessage.displayText == "File: b023-report.pdf")
let malformedOffer: Data = Data(#"{"version":1,"kind":"text","text":"Direct file transfer","direct_file":{}}"#.utf8)
let malformedContent: MessageContent = MessageContentCodec.decode(malformedOffer)
precondition(malformedContent.directFile == nil)
precondition(DirectFilePreviewText.display(text: malformedContent.text, label: "Файл") == "Файл")
print("PASS: localized direct-file offers, captions, legacy fallbacks and malformed metadata")
