import Foundation
import Combine

@main
struct BannerControllerContracts {
    @MainActor static func main() {
        let events = PassthroughSubject<RealtimeEvent, Never>()
        let changes = PassthroughSubject<Set<String>, Never>()
        var rows: [String: DecryptedChatMessage] = [:]
        var activeChat: String? = nil
        var calling = false
        let controller = ForegroundMessageBannerController(
            realtimeEvents: events, ingestorChanges: changes,
            loadMessage: { rows[$0] }, ownUserId: "me",
            activeChatId: { activeChat }, activeCall: { calling },
            resolveSenderName: { _ in "Peer" })
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
        let generation = controller.banner?.generation
        events.send(.messageNew(first))
        changes.send(["chat"])
        precondition(controller.banner?.generation == generation, "REST/WS duplicates must not re-notify")

        let second = dto("persist-first", 2)
        persist(second)
        changes.send(["chat"])
        events.send(.messageNew(second))
        precondition(controller.banner?.preview == .text(second.id), "Persistence before event must not lose the banner")

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
        print("Actual foreground banner controller contracts passed")
    }
}
