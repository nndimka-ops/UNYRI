import Foundation
import SwiftUI

// Boundary-only visual seams. Actual parser/content/store/controller logic is
// exercised by the separate native suites, not replaced by this UI fixture.
enum AppConfig {
    static let publicDefaultOrigin = "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud"
    static let apiBaseURL = URL(string: publicDefaultOrigin)!
}
struct HostUser {
    let id: String; let displayName: String; let username: String
    let avatarUrl: String? = nil; let avatarVersion: Int64 = 0
}
struct ChatSummary: Identifiable {
    let chatId: String; var id: String { chatId }
    let chatType = "direct"; let isBlocked: Bool? = false; let otherUser: HostUser?
}
struct ChatListResponse { let items: [ChatSummary]; let nextOffset: Int64? }
@MainActor final class AuthRepository: ObservableObject {
    @Published var sessionGeneration: UInt64 = 1
    let currentUser: HostUser? = HostUser(id: "self", displayName: "You", username: "fixture")
}
// Presentation boundary only: no signed URLs, credentials, or network avatars.
// The full app exercises the real UsersRepository in its focused native proof.
@MainActor final class UsersRepository: ObservableObject {
    var blockedAccountScope: String? { "presentation-fixture" }
    func cachedAvatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) -> URL? { nil }
    func avatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) async -> URL? { nil }
}
@MainActor final class ChatsRepository: ObservableObject {
    func listChats(limit: Int, offset: Int64) async throws -> ChatListResponse {
        ChatListResponse(items: (0..<30).map { ChatSummary(chatId: "chat-\($0)",
            otherUser: HostUser(id: "peer-\($0)", displayName: "Участник \($0 + 1)", username: "fixture-\($0)")) }, nextOffset: nil)
    }
}
@MainActor final class MessagesRepository: ObservableObject {
    var values: [String: String] = [:]
    func chatValue(chatId: String, name: String) -> String? { values[chatId + name] }
    func saveChatValue(chatId: String, name: String, value: String?) -> Bool { values[chatId + name] = value; return true }
    func sendConferenceInvite(chat: ChatSummary, metadata: ConferenceInvitationMetadata) -> Bool { true }
}
private struct PreviewFlag: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues { var unyriMessagePreview: Bool { get { self[PreviewFlag.self] } set { self[PreviewFlag.self] = newValue } } }
enum PerformanceRecorder {
    enum Event { case action }; static let shared = Value()
    struct Value { func record(_ event: Event) {} }
}
enum PerformanceMonitor { static let shared = Value(); struct Value { func activity() {} } }
enum UnyriSavedChatStyle { static let background = Color.black; static let readTicks = Color.blue }

struct HostContent: View {
    @EnvironmentObject private var controller: ConferenceController
    private var args: [String] { ProcessInfo.processInfo.arguments }
    var body: some View {
        if args.contains("--invite-card"), let metadata = controller.invitationMetadata() {
            ScrollView {
                VStack(spacing: 24) {
                    ForEach([false, true], id: \.self) { outgoing in
                        let message = DecryptedChatMessage(clientMessageId: outgoing ? "outgoing" : "incoming", serverId: "fixture",
                            chatId: "fixture", senderId: "peer", sequence: 1, createdAt: Date(), kind: "content", targetMessageId: nil,
                            status: .delivered, content: MessageContent(kind: "text", text: "Conference invitation", conferenceInvite: metadata))
                        UnyriConferenceInvitationCard(metadata: metadata, message: message, outgoing: outgoing, selecting: false, select: {})
                            .padding(14).background(Color(hex: 0x172331), in: RoundedRectangle(cornerRadius: 20))
                    }
                }.padding(14)
            }.background(Color.black)
        } else if args.contains("--invite-picker"), let metadata = controller.invitationMetadata() {
            ConferenceInvitationShareSheet(metadata: metadata)
        } else if args.contains("--root-banner") {
            // Reproduce the authenticated root boundary, not just a plain VStack.
            TabView {
                ConferenceDirectoryView().tabItem { Label("Rooms", systemImage: "person.3") }.tag(0)
                NavigationStack { Text("Chats").navigationTitle("Chats") }
                    .tabItem { Label("Chats", systemImage: "bubble.left") }.tag(1)
            }
            .toolbar(.hidden, for: .tabBar)
            .modifier(ConferencePresentation())
        } else if args.contains("--rooms") {
            ConferenceDirectoryView().sheet(isPresented: $controller.showLobby) { ConferenceLobby() }
        } else if args.contains("--lobby") {
            ConferenceLobby()
        } else if args.contains("--minimized") || args.contains("--presentation") {
            VStack {
                Text("Chats").font(.title2); Spacer()
                Text("Message composer").frame(maxWidth: .infinity, minHeight: 44).background(.gray.opacity(0.2))
            }.modifier(ConferencePresentation())
        } else if controller.phase == .idle {
            Text("Fixture: left conference").accessibilityIdentifier("fixture-left")
        } else {
            GeometryReader { viewport in
                ConferenceScreen()
                    .frame(width: args.contains("--small") ? min(568, viewport.size.width) : nil,
                           height: args.contains("--small") ? min(320, viewport.size.height) : nil)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
