import Foundation

// Pure foreground-banner policy contract (Runtime Correction D).
// Compiles MessageModels.swift (ForegroundBannerPolicy / ForegroundPreview)
// with no SwiftUI/Combine — runs on macOS CI.

var failures = 0

func check(_ condition: Bool, _ message: String) {
    if !condition {
        failures += 1
        print("FAIL: \(message)")
    }
}

func expectPreview(_ actual: ForegroundPreview?, _ expected: ForegroundPreview?, _ message: String) {
    if actual != expected {
        failures += 1
        print("FAIL: \(message) (got \(String(describing: actual)), want \(String(describing: expected)))")
    }
}

check(!ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: false, navigationChatId: "c"), "picker hiding read receipts must not re-notify the underlying chat")
check(ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "other", isOwnMessage: false, activeCall: false, navigationChatId: "c"), "other chat still notifies while picker hides read receipts")
check(ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: true, navigationChatId: "c"), "expanded call may show a message from the hidden underlying chat")

// show / suppress policy
check(ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: false), "foreground other-chat incoming -> banner")
check(!ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: "c", eventChatId: "c", isOwnMessage: false, activeCall: false), "same open chat -> no banner")
check(!ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "c", isOwnMessage: true, activeCall: false), "own message -> no banner")
check(ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: true), "incoming message during call -> banner")
check(ForegroundBannerPolicy.shouldShow(isForeground: true, activeChatId: "c", eventChatId: "c", isOwnMessage: false, activeCall: true), "chat behind expanded call -> banner")
check(!ForegroundBannerPolicy.shouldShow(isForeground: false, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: true), "background call -> no in-app banner")
check(!ForegroundBannerPolicy.shouldShow(isForeground: false, activeChatId: nil, eventChatId: "c", isOwnMessage: false, activeCall: false), "background -> no banner")

// preview mapping (reuse the chat-list formatter semantics)
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: "Hello", mediaType: nil, filename: nil), .text("Hello"), "text preview")
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: "[Voice message]", mediaType: "voice", filename: nil), .voice, "voice preview")
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: "[Photo]", mediaType: "image", filename: nil), .photo, "photo preview")
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: "[File] a.pdf", mediaType: "file", filename: "a.pdf"), .file("a.pdf"), "file preview")
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: true, text: "", mediaType: nil, filename: nil), .deleted, "deleted preview")
check(ForegroundBannerPolicy.preview(kind: "edit", status: "delivered", isDeleted: false, text: "x", mediaType: nil, filename: nil) == nil, "lifecycle event -> nil (skip banner)")
check(ForegroundBannerPolicy.preview(kind: "content", status: "decrypt_failed", isDeleted: false, text: "[Unable to decrypt message]", mediaType: nil, filename: nil) == nil, "decrypt_failed -> nil (skip banner)")
check(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: "{\"content\":\"...\"}", mediaType: nil, filename: nil) == nil, "raw JSON -> nil (skip banner)")

expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: nil, mediaType: "file", filename: "9534E325-FA88.mp4", mimeType: "video/mp4"), .video, "video MIME wins over generated filename")
expectPreview(ForegroundBannerPolicy.preview(kind: "content", status: "delivered", isDeleted: false, text: nil, mediaType: "file", filename: "report.pdf", mimeType: "application/pdf"), .file("report.pdf"), "ordinary file keeps its filename")

check(NotificationRecipientPolicy.matches(owner: "a", current: "a"), "current account reminder is allowed")
check(!NotificationRecipientPolicy.matches(owner: "a", current: "b"), "other account reminder is rejected")
check(!NotificationRecipientPolicy.matches(owner: nil, current: nil), "missing owner cannot match logged out")
check(!NotificationRecipientPolicy.matches(owner: "a", current: nil), "logout rejects a pending foreground reminder")
check(!NotificationRecipientPolicy.matches(owner: "", current: ""), "empty owners do not authorize a reminder")

if failures > 0 {
    print("\(failures) foreground banner contract check(s) failed")
    exit(1)
}
print("Foreground banner contract checks passed")
