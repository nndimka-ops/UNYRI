import SwiftUI

/// "What the server knows": an honest inventory of what the server stores, what only
/// passes through it and what it cannot read. Every line was checked against the
/// server schema and handlers (server/migrations, server/src; 10 Nov 2026). When the
/// server starts or stops keeping something, this screen changes in the same commit.
struct UnyriServerKnowledgeView: View {
    private struct Fact: Identifiable {
        let id: String      // the title key
        let icon: String
        let detail: String
    }

    private let unreadable = [
        Fact(id: "Message text", icon: "text.bubble",
             detail: "Text, reactions, edits and quotes are encrypted on your phone. Only the devices in the chat can open them."),
        Fact(id: "Photos, videos, voice messages and files", icon: "photo.on.rectangle",
             detail: "Encrypted before upload. The server does not know file names or types, only the size."),
        Fact(id: "Calls", icon: "phone.arrow.up.right",
             detail: "Audio and video travel between the phones encrypted. The server only helps the phones find each other."),
        Fact(id: "History backup", icon: "archivebox",
             detail: "Encrypted with your recovery key. The key never leaves your phone."),
        Fact(id: "Direct file transfer", icon: "arrow.left.arrow.right",
             detail: "Files go from phone to phone. Not a single byte is stored on the server."),
        Fact(id: "Live draft", icon: "text.cursor",
             detail: "Passed along encrypted and never saved."),
    ]

    private let absent = [
        Fact(id: "Phone number and email", icon: "phone",
             detail: "Registration needs only a username and a password."),
        Fact(id: "Your contacts", icon: "person.2",
             detail: "The address book is never uploaded."),
        Fact(id: "Location", icon: "location.slash",
             detail: "Not requested and not stored."),
        Fact(id: "IP address", icon: "network",
             detail: "Not written to the database. Used for up to one hour to limit sign-in attempts."),
    ]

    private let stored = [
        Fact(id: "Profile", icon: "person.crop.circle",
             detail: "Username, display name, bio and avatar. The people you talk to see them too."),
        Fact(id: "Password", icon: "key",
             detail: "Only a hash. The password itself is not kept."),
        Fact(id: "Devices", icon: "iphone",
             detail: "Name, platform, iOS and app versions, time of last activity and the notification token."),
        Fact(id: "Who you talk to and when", icon: "clock.arrow.circlepath",
             detail: "Chat members, the time and size of every message, delivery and read marks. The read time is recorded even if you hide read receipts."),
        Fact(id: "Actions on messages", icon: "hand.tap",
             detail: "That a message was edited, deleted, answered or got a reaction, and by whom. Never what it says."),
        Fact(id: "Last seen", icon: "eye",
             detail: "Recorded even when hidden. The setting only decides who may see it."),
        Fact(id: "Call history", icon: "clock",
             detail: "Who called whom, when and for how long. Removing a call from your history hides it but does not erase it on the server."),
        Fact(id: "Settings", icon: "slider.horizontal.3",
             detail: "Privacy choices, blocked accounts, muted, pinned and archived chats."),
    ]

    private let retention = [
        Fact(id: "Encrypted messages and files", icon: "tray.full",
             detail: "Until you clear the chat, delete a message for everyone or delete the account. Delete for me removes only your copy."),
        Fact(id: "Problem reports", icon: "ladybug",
             detail: "30 days, with the screenshots you attach."),
        Fact(id: "Diagnostics", icon: "waveform.path.ecg",
             detail: "24 hours, and only if you turn it on."),
        Fact(id: "Everything else", icon: "calendar",
             detail: "Until you delete the account."),
    ]

    private let others = [
        Fact(id: "Notifications through Apple", icon: "bell",
             detail: "A notification carries no text and no sender name, only technical identifiers."),
        Fact(id: "Server administrator", icon: "person.badge.shield.checkmark",
             detail: "Can see your profile and devices, suspend or delete the account and issue a recovery code. The admin panel cannot open messages or your list of chats."),
        Fact(id: "Server log", icon: "doc.text",
             detail: "Notes that a message was sent, by whom and to which chat. Never the content."),
        Fact(id: "Your own server", icon: "server.rack",
             detail: "UNYRI can run on a server that belongs to you. Then all of this stays with you."),
    ]

    var body: some View {
        UnyriSettingsPage("What the server knows") {
            UnyriSettingsNote("Messages, files and calls are encrypted on your phone before they leave it. This is the full list: what the server cannot read, what it does not have at all, and what it does keep.")
            section("The server cannot read", facts: unreadable, tint: Color(hex: 0x83D9B7))
            section("The server does not have", facts: absent, tint: Color(hex: 0x80D7FA))
            section("The server stores", facts: stored, tint: Color(hex: 0xE6BD73))
            section("How long it is kept", facts: retention, tint: Color(hex: 0xAFA4F7))
            section("Apple and the administrator", facts: others, tint: Color(hex: 0xEC8997))
            UnyriSettingsNote("This list is checked against the server code and is updated whenever the server changes what it keeps.")
        }
        .accessibilityIdentifier("server-knowledge")
    }

    private func section(_ title: String, facts: [Fact], tint: Color) -> some View {
        UnyriSettingsGroup(title) {
            ForEach(Array(facts.enumerated()), id: \.element.id) { index, fact in
                if index > 0 { UnyriSettingsDivider() }
                UnyriSettingsRow(title: fact.id, icon: fact.icon, tint: tint, subtitle: fact.detail, showsChevron: false)
                    .accessibilityElement(children: .combine)
            }
        }
    }
}
