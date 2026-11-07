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
