import SwiftUI

/// Stage 3A placeholder: no message history or composer yet.
struct ChatPlaceholderView: View {
    let chat: ChatSummary

    var body: some View {
        VStack(spacing: 8) {
            if let other = chat.otherUser {
                Text(other.displayName)
                    .font(.title2)
                    .fontWeight(.semibold)
                Text("@\(other.username)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            } else {
                Text(title)
                    .font(.title2)
                    .fontWeight(.semibold)
            }

            Text(chat.chatId)
                .font(.caption2)
                .foregroundColor(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .padding(.top, 8)

            Spacer().frame(height: 24)

            Text("Messages coming next")
                .font(.body)
                .foregroundColor(.secondary)
        }
        .padding(24)
        .unyriScreenBackground().navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var title: String {
        chat.otherUser?.displayName ?? (chat.chatType == "saved" ? "Saved Messages" : "Chat")
    }
}
