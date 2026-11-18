import Foundation
import Combine

@main
struct BannerControllerContracts {
    @MainActor static func main() async {
        let events = PassthroughSubject<RealtimeEvent, Never>()
        let changes = PassthroughSubject<Set<String>, Never>()
        var rows: [String: DecryptedChatMessage] = [:]
        var activeChat: String? = nil
        var calling = false
        let controller = ForegroundMessageBannerController(
            realtimeEvents: events, ingestorChanges: changes,
            loadMessage: { rows[$0] }, ownUserId: "me",
            activeChatId: { activeChat }, activeCall: { calling },
            resolveSenderName: { _ in "Pe\u{FFF0}er" })
        controller.isForeground = true

        func dto(_ id: String, _ sequence: Int64, sender: String = "peer", kind: String = "content") -> MessageDto {
            MessageDto(id: id, chatId: "chat", clientMessageId: id, senderId: sender,
                senderDeviceId: "device", sequence: sequence, createdAt: Date(), messageType: 1,
                ciphertext: "never display ciphertext", kind: kind, targetMessageId: nil)
        }
        func persist(_ dto: MessageDto) {
            rows[dto.clientMessageId] = DecryptedChatMessage(clientMessageId: dto.clientMessageId,
                serverId: dto.id, chatId: dto.chatId, senderId: dto.senderId, sequence: dto.sequence,
                createdAt: dto.createdAt, kind: dto.kind, targetMessageId: nil, status: .delivered,
                content: MessageContent(kind: MessageContent.kindText, text: dto.id))
        }

        let first = dto("event-first", 1)
        events.send(.messageNew(first))
        precondition(controller.banner == nil, "Unpersisted ciphertext must never become a banner")
        persist(first)
        changes.send(["chat"])
        precondition(controller.banner?.preview == .text(first.id))
        precondition(controller.banner?.senderName == "Peer", "Initial peer names are sanitized")
        let generation = controller.banner?.generation
        events.send(.messageNew(first))
        changes.send(["chat"])
        precondition(controller.banner?.generation == generation, "REST/WS duplicates must not re-notify")

        let second = dto("persist-first", 2)
        persist(second)
        changes.send(["chat"])
        events.send(.messageNew(second))
        precondition(controller.banner?.preview == .text(second.id), "Persistence before event must not lose the banner")

        controller.resolvePresentation = { _ in (name: "Re\u{202E}freshed", avatar: nil, muted: false) }
        let refreshed = dto("refreshed-name", 20)
        persist(refreshed); events.send(.messageNew(refreshed))
        for _ in 0..<100 { if controller.banner?.senderName == "Refreshed" { break }; await Task.yield() }
        precondition(controller.banner?.senderName == "Refreshed", "Async peer metadata cannot restore a raw name")
        controller.resolvePresentation = nil

        controller.dismiss()
        let incoming = dto("batch-content", 3)
        let own = dto("batch-own", 4, sender: "me")
        let edit = dto("batch-edit", 5, kind: "edit")
        for item in [incoming, own, edit] { events.send(.messageNew(item)); persist(item) }
        changes.send(["chat"])
        precondition(controller.banner?.preview == .text(incoming.id), "Later own/lifecycle rows must not mask incoming content")
        controller.dismiss()
        changes.send(["chat"])
        precondition(controller.banner == nil, "All resolved batch rows must be consumed")

        calling = true
        activeChat = "chat"
        let minimizedSameChat = dto("visible-chat", 6)
        persist(minimizedSameChat); events.send(.messageNew(minimizedSameChat))
        precondition(controller.banner == nil, "Minimized call must still suppress the visible chat")
        activeChat = nil
        let minimizedOtherChat = dto("minimized-call", 7)
        persist(minimizedOtherChat); events.send(.messageNew(minimizedOtherChat))
        precondition(controller.banner?.preview == .text(minimizedOtherChat.id))
        controller.isCallExpanded = true
        activeChat = "chat"
        let expanded = dto("expanded-call", 8)
        persist(expanded); events.send(.messageNew(expanded))
        precondition(controller.banner?.preview == .text(expanded.id), "Expanded call must show incoming messages")

        controller.isCallExpanded = false
        calling = false
        activeChat = nil
        controller.navigationChatId = "chat"
        precondition(controller.banner == nil, "Opening banner conversation immediately dismisses it")
        let pickerMessage = dto("picker-open", 10)
        persist(pickerMessage); events.send(.messageNew(pickerMessage))
        precondition(controller.banner == nil, "Picker must not re-notify its underlying chat")
        controller.navigationChatId = nil

        controller.isForeground = false
        precondition(controller.banner == nil, "Backgrounding must clear private previews")
        let background = dto("background", 9)
        events.send(.messageNew(background)); persist(background); changes.send(["chat"])
        controller.isForeground = true
        changes.send(["chat"])
        precondition(controller.banner == nil, "Background traffic must not be replayed as a fresh banner")

        var enabled = false
        var previews = false
        var presented = 0
        controller.notificationsEnabled = { enabled }
        controller.messagePreviewEnabled = { previews }
        controller.onPresented = { _, _ in presented += 1 }
        controller.preferencesChanged()
        let disabled = dto("alerts-disabled-still-persisted", 11)
        persist(disabled); events.send(.messageNew(disabled)); changes.send(["chat"])
        precondition(rows[disabled.id] != nil && controller.banner == nil && presented == 0,
            "Switching alerts off must not delete a persisted message or produce sound/banner")
        enabled = true
        controller.preferencesChanged(); changes.send(["chat"])
        events.send(.messageNew(disabled))
        precondition(controller.banner == nil, "Re-enabling must not notify old disabled-period traffic")
        let privatePreview = dto("private-plaintext-must-not-leak", 12)
        persist(privatePreview); events.send(.messageNew(privatePreview))
        precondition(controller.banner?.preview == .text(UnyriL10n.string("New message")))
        precondition(presented == 1, "An accepted message produces one presentation callback")
        events.send(.messageNew(privatePreview)); changes.send(["chat"])
        precondition(presented == 1, "Duplicate events must not produce a second sound callback")
        previews = true; controller.preferencesChanged()
        let normal = dto("previews-enabled", 13)
        persist(normal); events.send(.messageNew(normal))
        precondition(controller.banner?.preview == .text(normal.id))
        precondition(presented == 2)
        var alerts = 0
        controller.onPresented = { banner, _ in if banner.shouldAlert { alerts += 1 } }
        for index in 0..<10 {
            let item = dto("album-\(index)", Int64(100 + index))
            persist(item); rows[item.clientMessageId]?.content.albumId = "one-batch"
            events.send(.messageNew(item))
            precondition(controller.banner?.preview == .text(item.id), "One visible banner updates for every member")
        }
        precondition(alerts == 1, "Ten members must request only one sound")
        let independent = dto("independent-after-batch", 110)
        persist(independent); events.send(.messageNew(independent))
        precondition(alerts == 2, "An independent message after a batch is not silenced")
        let anotherBatch = dto("another-batch", 111)
        persist(anotherBatch); rows[anotherBatch.clientMessageId]?.content.albumId = "another-batch"
        events.send(.messageNew(anotherBatch))
        precondition(alerts == 3)
        for (index, state) in ["hidden", "deleted", "unreadable", "wrong-server-id"].enumerated() {
            let item = dto("excluded-\(state)", Int64(120 + index)); persist(item)
            switch state {
            case "hidden": rows[item.clientMessageId]?.hiddenLocally = true
            case "deleted": rows[item.clientMessageId]?.deleteSequence = 900
            case "unreadable": rows[item.clientMessageId]?.status = .decryptFailed
            default: rows[item.clientMessageId]?.serverId = "not-the-event"
            }
            events.send(.messageNew(item))
        }
        precondition(alerts == 3, "No hidden/deleted/unreadable/mismatched row supplies a presentation or batch key")

        // APNs-first, socket-first and persistence-first all converge on the
        // same in-app owner; sync without a foreground hint stays silent.
        let alertEvents = PassthroughSubject<RealtimeEvent, Never>()
        let alertChanges = PassthroughSubject<Set<String>, Never>()
        var store: [String: DecryptedChatMessage] = [:]
        var lookingAt: String? = nil
        var muted: Set<String> = []
        var alertsOn = true
        var sounds: [String] = []
        var flags = UnyriNotificationPreferences()
        let pushed = ForegroundMessageBannerController(
            realtimeEvents: alertEvents, ingestorChanges: alertChanges,
            loadMessage: { store[$0] }, ownUserId: "me",
            activeChatId: { lookingAt }, activeCall: { false },
            resolveSenderName: { _ in "Peer" }, notificationGeneration: 7,
            loadServerMessage: { id in store.values.first { $0.serverId == id } })
        pushed.isForeground = true
        pushed.isSilenced = { muted.contains($0) }
        pushed.notificationsEnabled = { alertsOn }
        pushed.inAppPreferences = { flags }
        pushed.onPresented = { _, clientId in sounds.append(clientId) }
        func wire(_ index: Int) -> MessageDto {
            MessageDto(id: "server-\(index)", chatId: "chat", clientMessageId: "client-\(index)", senderId: "peer",
                senderDeviceId: "device", sequence: Int64(200 + index), createdAt: Date(), messageType: 1,
                ciphertext: "never display ciphertext", kind: "content", targetMessageId: nil)
        }
        func ingest(_ dto: MessageDto) {
            store[dto.clientMessageId] = DecryptedChatMessage(clientMessageId: dto.clientMessageId,
                serverId: dto.id, chatId: dto.chatId, senderId: dto.senderId, sequence: dto.sequence,
                createdAt: dto.createdAt, kind: dto.kind, targetMessageId: nil, status: .delivered,
                content: MessageContent(kind: MessageContent.kindText, text: dto.id))
            alertChanges.send([dto.chatId])
        }
        func alert(_ index: Int, owner: String = "me", generation: UInt64 = 7) -> Bool {
            ForegroundMessageBannerController.takesAlert(UnyriForegroundPushHint(owner: owner,
                generation: generation, chatId: "chat", messageId: "server-\(index)"))
        }
        alertEvents.send(.messageNew(wire(1))); ingest(wire(1))
        precondition(sounds == ["client-1"] && alert(1) && sounds.count == 1)
        alertEvents.send(.messageNew(wire(2)))
        precondition(alert(2) && sounds.count == 1)
        ingest(wire(2))
        precondition(sounds.last == "client-2" && sounds.count == 2)

        precondition(alert(3) && sounds.count == 2, "Missing socket copy is owned by in-app sync, never iOS's foreground banner")
        ingest(wire(3))
        precondition(pushed.banner?.preview == .text("server-3") && sounds.count == 3)
        alertEvents.send(.messageNew(wire(3))); alertChanges.send(["chat"])
        precondition(alert(3) && sounds.count == 3, "Late socket copy and repeated APNs must not alert again")
        ingest(wire(4))
        precondition(sounds.count == 3, "Sync-only messages do not produce unsolicited banners")
        precondition(alert(4) && sounds.last == "client-4" && sounds.count == 4)
        pushed.dismiss()
        lookingAt = "chat"
        precondition(alert(5)); ingest(wire(5))
        precondition(pushed.banner == nil && sounds.count == 4)
        lookingAt = nil; muted = ["chat"]
        precondition(alert(6)); ingest(wire(6))
        precondition(pushed.banner == nil && sounds.count == 4)
        muted = []; alertsOn = false
        precondition(alert(7)); ingest(wire(7))
        precondition(pushed.banner == nil && sounds.count == 4)
        alertsOn = true; pushed.isForeground = false
        precondition(!alert(8))
        alertEvents.send(.messageNew(wire(8))); ingest(wire(8))
        pushed.isForeground = true
        alertEvents.send(.messageNew(wire(8))); alertChanges.send(["chat"])
        precondition(pushed.banner == nil && sounds.count == 4, "Background events are not replayed on resume")
        precondition(!alert(10, owner: "someone-else") && !alert(10, generation: 6))
        alertEvents.send(.messageNew(wire(10))); ingest(wire(10))
        precondition(sounds.last == "client-10" && sounds.count == 5)
        flags.inAppBanners = false
        ingest(wire(11)); alertEvents.send(.messageNew(wire(11)))
        precondition(pushed.banner == nil && sounds.last == "client-11", "Feedback still has an owner when banners are off")
        flags.inAppBanners = true
        pushed.messagePreviewEnabled = { false }
        ingest(wire(12)); alertEvents.send(.messageNew(wire(12)))
        precondition(pushed.banner?.preview == .text(UnyriL10n.string("New message")))
        pushed.privacyBlocked = { true }
        pushed.preferencesChanged()
        ForegroundMessageBannerController.presentInactive(owner: "other", chat: "chat", message: "inactive", name: "Other account", preferences: flags)
        precondition(pushed.banner == nil, "App lock blocks inactive-account previews too")
        pushed.privacyBlocked = { false }
        ForegroundMessageBannerController.presentInactive(owner: "other", chat: "chat", message: "inactive", name: "Other\u{FFF0} account", preferences: flags)
        precondition(pushed.banner?.ownerID == "other" && pushed.banner?.preview == .text(UnyriL10n.string("New message")))
        precondition(pushed.banner?.senderName == "Other\u{FFF0} account", "An inactive account title is the owner's own name, kept as typed")
        pushed.navigationChatId = "chat"
        precondition(pushed.banner?.ownerID == "other", "Same chat ID in another account must not suppress its banner")
        pushed.navigationChatId = nil

        // Replacement cancels the old one-shot deadline, and the latest expires.
        ingest(wire(13)); alertEvents.send(.messageNew(wire(13)))
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        ingest(wire(14)); alertEvents.send(.messageNew(wire(14)))
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        precondition(pushed.banner != nil, "Old expiry must not dismiss the replacement")
        // 4.5 s after the latest message: a 1 s margin over the 3.5 s expiry (0.2 s was flaky
        // on a loaded Mac, 18 Nov) while the check above keeps 1.5 s before it.
        try? await Task.sleep(nanoseconds: 2_500_000_000)
        precondition(pushed.banner == nil, "Latest banner expires after 3.5 seconds")
        print("Actual foreground banner controller contracts passed")
    }
}
