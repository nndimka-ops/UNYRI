import SwiftUI

/// One chat picker for the whole app (forwarding messages and media, sending shared
/// files, sending an edited photo elsewhere). A glass sheet that sizes itself to the
/// number of chats, shows what is being sent, a searchable avatar grid with the
/// UNYRI gradient selection ring, an optional comment, "other apps", and Send.
struct UnyriChatPickerSheet: View {
    let title: String
    var subtitle: String? = nil
    var allowsMultiple = true
    var allowsComment = true
    var header: AnyView? = nil
    /// What is being sent (a small bubble-like card at the top).
    var preview: AnyView? = nil
    /// Hands the content to the iOS share sheet (other apps).
    var shareOutside: (() -> Void)? = nil
    let load: () async throws -> [ChatSummary]
    let avatarURL: (ChatSummary) -> URL?
    /// Returns nil on success or a message to show.
    let send: (_ targets: [ChatSummary], _ comment: String) async -> String?
    let cancel: () -> Void

    @State private var chats: [ChatSummary] = []
    @State private var loading = true
    @State private var error: String?
    @State private var search = ""
    @State private var selected: [String] = []
    @State private var comment = ""
    @State private var sending = false
    @State private var detent: PresentationDetent = .fraction(0.5)
    @FocusState private var commentFocused: Bool

    static let brand = LinearGradient(colors: [Color(hex: 0x32C9E9), Color(hex: 0xAA85ED)], startPoint: .topLeading, endPoint: .bottomTrailing)
    private static let maximumTargets = 10
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6, alignment: .top), count: 4)

    private func name(_ chat: ChatSummary) -> String {
        if chat.chatType == "saved" { return UnyriL10n.string("Saved Messages") }
        return chat.otherUser?.visibleName ?? UnyriL10n.string("Chat")
    }
    private var filtered: [ChatSummary] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let ordered = chats.filter { $0.isBlocked != true }.sorted { ($0.chatType == "saved" ? 0 : 1) < ($1.chatType == "saved" ? 0 : 1) }
        guard !query.isEmpty else { return ordered }
        return ordered.filter { name($0).localizedCaseInsensitiveContains(query) || ($0.otherUser?.publicIdentityLabel ?? "").localizedCaseInsensitiveContains(query) }
    }
    private var selectedChats: [ChatSummary] { selected.compactMap { id in chats.first { $0.chatId == id && $0.isBlocked != true } } }

    /// Height that fits the content: header, preview, the grid rows, and the send bar.
    private var fittedDetent: PresentationDetent {
        let rows = max(1, Int(ceil(Double(max(chats.count, 1)) / 4)))
        let height = 150 + (preview == nil ? 0 : 76) + (header == nil ? 0 : 90) + CGFloat(min(rows, 4)) * 104 + 130
        return .height(min(height, 640))
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color.white.opacity(0.28)).frame(width: 38, height: 5).padding(.top, 7)
            topBar
            if let preview {
                preview
                    .padding(.leading, 16).padding(.trailing, 12).padding(.vertical, 9)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.07)))
                    .overlay(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 2).fill(Self.brand).frame(width: 3).padding(.vertical, 10).padding(.leading, 7)
                    }
                    .padding(.horizontal, 14).padding(.top, 10)
            }
            searchField
            ScrollView {
                VStack(spacing: 12) {
                    if let header { header.padding(.horizontal, 14) }
                    if loading && chats.isEmpty { ProgressView().padding(.top, 30) }
                    if let error { Text(verbatim: error).font(.footnote).foregroundStyle(UnyriColors.danger).multilineTextAlignment(.center).padding(.horizontal, 20) }
                    if !loading && filtered.isEmpty {
                        Text("No results.").foregroundStyle(UnyriColors.secondaryText).padding(.top, 24)
                    }
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(filtered) { chat in tile(chat) }
                    }
                    .padding(.horizontal, 8)
                }
                .padding(.top, 8).padding(.bottom, 16)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .foregroundStyle(UnyriColors.primaryText)
        .safeAreaInset(edge: .bottom, spacing: 0) { if !selected.isEmpty { bottomBar.transition(.move(edge: .bottom).combined(with: .opacity)) } }
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: selected)
        .modifier(UnyriChatPickerPresentation(detents: [fittedDetent, .large], selection: $detent))
        .onChange(of: chats.count) { _ in detent = fittedDetent }
        .onAppear { detent = fittedDetent }
        .task {
            loading = true
            defer { loading = false }
            do { chats = try await load() } catch { self.error = UnyriErrorText.message(error) ?? self.error }
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button { UnyriHaptics.press(); cancel() } label: {
                Image(systemName: "xmark").font(.system(size: 15, weight: .semibold)).frame(width: 38, height: 38)
                    .background(Circle().fill(Color.white.opacity(0.10)))
            }
            // Closing is always allowed: items already queued keep sending.
            .buttonStyle(.plain)
            .accessibilityLabel("Cancel").accessibilityIdentifier("forward-cancel")
            Spacer(minLength: 6)
            VStack(spacing: 1) {
                Text(title).font(.system(size: 17, weight: .semibold))
                Text(selected.isEmpty ? (subtitle ?? UnyriL10n.string("Choose a chat"))
                     : String(format: UnyriL10n.string("Selected: %d"), selected.count))
                    .font(.system(size: 12)).foregroundStyle(UnyriColors.secondaryText)
            }
            Spacer(minLength: 6)
            if let shareOutside {
                Button { UnyriHaptics.press(); shareOutside() } label: {
                    Image(systemName: "square.and.arrow.up").font(.system(size: 15, weight: .semibold)).frame(width: 38, height: 38)
                        .background(Circle().fill(Color.white.opacity(0.10)))
                }
                .buttonStyle(.plain).disabled(sending)
                .accessibilityLabel("Other apps")
            } else {
                Color.clear.frame(width: 38, height: 38)
            }
        }
        .padding(.horizontal, 14).padding(.top, 4)
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 14, weight: .medium)).foregroundStyle(UnyriColors.secondaryText)
            TextField(UnyriL10n.string("Search chats"), text: $search)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("forward-search")
            if !search.isEmpty {
                Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(UnyriColors.secondaryText) }
                    .buttonStyle(.plain).accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 13).frame(height: 38)
        .background(Capsule().fill(Color.white.opacity(0.08)))
        .padding(.horizontal, 14).padding(.top, 10)
    }

    private func tile(_ chat: ChatSummary) -> some View {
        let isSelected = selected.contains(chat.chatId)
        return Button {
            UnyriHaptics.selection()
            if isSelected { selected.removeAll { $0 == chat.chatId } }
            else if allowsMultiple { if selected.count < Self.maximumTargets { selected.append(chat.chatId) } }
            else { selected = [chat.chatId] }
        } label: {
            VStack(spacing: 6) {
                ZStack(alignment: .bottomTrailing) {
                    Group {
                        if chat.chatType == "saved" {
                            Image(systemName: "bookmark.fill").font(.system(size: 22, weight: .semibold)).foregroundStyle(.white)
                                .frame(width: 60, height: 60).background(Circle().fill(Self.brand))
                        } else {
                            UserAvatar(url: avatarURL(chat), name: name(chat), size: 60)
                        }
                    }
                    .scaleEffect(isSelected ? 0.84 : 1)
                    .frame(width: 66, height: 66)
                    .overlay(Circle().strokeBorder(Self.brand, lineWidth: isSelected ? 3 : 0))
                    if isSelected {
                        Image(systemName: "checkmark").font(.system(size: 11, weight: .heavy)).foregroundStyle(.white)
                            .frame(width: 22, height: 22).background(Circle().fill(Self.brand))
                            .overlay(Circle().stroke(Color(white: 0.10), lineWidth: 2.5))
                            .transition(.scale(scale: 0.3).combined(with: .opacity))
                    }
                }
                Text(name(chat)).font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                    .foregroundStyle(UnyriColors.primaryText.opacity(isSelected ? 1 : 0.85))
                    .lineLimit(2).multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(sending)
        .accessibilityIdentifier("forward-target-\(chat.chatId)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .animation(.spring(response: 0.28, dampingFraction: 0.7), value: isSelected)
    }

    private var bottomBar: some View {
        VStack(spacing: 9) {
            if allowsMultiple && selected.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(selectedChats) { chat in
                            Button { UnyriHaptics.selection(); selected.removeAll { $0 == chat.chatId } } label: {
                                HStack(spacing: 6) {
                                    if chat.chatType == "saved" {
                                        Image(systemName: "bookmark.fill").font(.system(size: 9)).foregroundStyle(.white)
                                            .frame(width: 20, height: 20).background(Circle().fill(Self.brand))
                                    } else {
                                        UserAvatar(url: avatarURL(chat), name: name(chat), size: 20)
                                    }
                                    Text(name(chat)).font(.system(size: 13, weight: .medium)).lineLimit(1)
                                    Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).opacity(0.6)
                                }
                                .padding(.leading, 4).padding(.trailing, 10).frame(height: 28)
                                .background(Capsule().fill(Color.white.opacity(0.10)))
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
            HStack(alignment: .bottom, spacing: 10) {
                if allowsComment {
                    TextField(UnyriL10n.string("Add a comment…"), text: $comment, axis: .vertical)
                        .lineLimit(1...4).focused($commentFocused)
                        .padding(.horizontal, 16).padding(.vertical, 11)
                        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.white.opacity(0.08)))
                        .accessibilityIdentifier("forward-comment")
                } else {
                    Text(selectedChats.map(name).joined(separator: ", ")).font(.system(size: 14, weight: .medium))
                        .lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                }
                Button {
                    guard !sending else { return }
                    UnyriHaptics.press()
                    sending = true
                    let targets = selectedChats
                    let text = comment.trimmingCharacters(in: .whitespacesAndNewlines)
                    Task {
                        let failure = await send(targets, text)
                        sending = false
                        if let failure { error = failure } else { UINotificationFeedbackGenerator().notificationOccurred(.success) }
                    }
                } label: {
                    ZStack(alignment: .topTrailing) {
                        Group {
                            if sending { ProgressView().tint(.white) }
                            else { Image(systemName: "paperplane.fill").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white).offset(x: -1, y: 1) }
                        }
                        .frame(width: 46, height: 46).background(Circle().fill(Self.brand))
                        .shadow(color: Color(hex: 0x32C9E9).opacity(0.35), radius: 8, y: 2)
                        if selected.count > 1 {
                            Text("\(selected.count)").font(.system(size: 11, weight: .bold)).foregroundStyle(.black)
                                .frame(minWidth: 18, minHeight: 18).background(Capsule().fill(Color.white))
                                .offset(x: 4, y: -4)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
                .accessibilityIdentifier("forward-confirm")
            }
        }
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 10)
        .background(Color(white: 0.11).opacity(0.96).ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Color.white.opacity(0.06)).frame(height: 0.5) }
    }
}

/// Glass sheet sized to its content (Telegram-like), pulling up to full height.
struct UnyriChatPickerPresentation: ViewModifier {
    var detents: Set<PresentationDetent> = [.fraction(0.55), .large]
    var selection: Binding<PresentationDetent>? = nil
    func body(content: Content) -> some View {
        let sized: AnyView = selection.map { AnyView(content.presentationDetents(detents, selection: $0)) }
            ?? AnyView(content.presentationDetents(detents))
        if #available(iOS 16.4, *) {
            sized
                .presentationDragIndicator(.hidden)
                .presentationCornerRadius(30)
                .presentationBackground(.ultraThinMaterial)
                .presentationContentInteraction(.scrolls)
                .background(Color(white: 0.07).opacity(0.72).ignoresSafeArea())
        } else {
            sized.presentationDragIndicator(.hidden).background(Color(white: 0.08).ignoresSafeArea())
        }
    }
}
