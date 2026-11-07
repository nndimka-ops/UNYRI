import SwiftUI

struct BlockedAccountsView: View {
    @EnvironmentObject private var users: UsersRepository
    var body: some View { BlockedAccountsList(users: users) }
}

private struct BlockedAccountsList: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject var users: UsersRepository
    @StateObject private var model: BlockedAccountsModel
    @State private var confirmation: PublicUser?

    init(users: UsersRepository, mode: BlockedAccountsModel.Mode = .blocked, excludedIDs: Set<String> = []) {
        self.users = users
        _model = StateObject(wrappedValue: BlockedAccountsModel(service: users, mode: mode, excludedIDs: excludedIDs))
    }

    var body: some View {
        UnyriSettingsPage(model.mode == .blocked ? "Blocked accounts" : "Block account") {
            if model.mode == .blocked {
                UnyriSettingsGroup {
                    NavigationLink {
                        BlockedAccountsList(users: users, mode: .people, excludedIDs: Set(model.users.map(\.id)))
                    } label: {
                        UnyriSettingsRow(title: "Add account", icon: "person.badge.plus", tint: Color(hex: 0xAFA4F7))
                    }
                    .accessibilityIdentifier("blocked-accounts-add")
                }
                UnyriSettingsNote("Manage blocked accounts here, even after deleting a chat. Unblocking does not restore missed messages or calls.")
            } else {
                UnyriSettingsNote("Choose an account to block. Opening this list does not create a chat.")
            }
            UnyriSettingsGroup {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").foregroundStyle(UnyriColors.secondaryText)
                    TextField(LocalizedStringKey(model.mode == .blocked ? "Search blocked accounts" : "Search people"), text: $model.query)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("blocked-accounts-search")
                    if !model.query.isEmpty {
                        Button { model.query = "" } label: {
                            Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44)
                        }.accessibilityLabel("Clear search")
                    }
                }.padding(.leading, 14).padding(.trailing, model.query.isEmpty ? 14 : 0).frame(minHeight: 52)
            }
            if model.mutationFailed {
                UnyriNotice(message: UnyriL10n.string("The change was not confirmed. Check your connection and try again."))
                    .accessibilityIdentifier("blocked-accounts-mutation-error")
            }
            if !model.users.isEmpty {
                UnyriSettingsGroup {
                    ForEach(model.users) { user in
                        if user.id != model.users.first?.id { UnyriSettingsDivider() }
                        Button { confirmation = user } label: {
                            BlockedAccountRow(user: user, users: users, adding: model.mode == .people,
                                              changing: model.changing.contains(user.id))
                        }
                        .disabled(model.changing.contains(user.id))
                        .accessibilityIdentifier("blocked-account-\(user.id)")
                    }
                }
            }
            if model.loading {
                ProgressView().frame(maxWidth: .infinity).padding(20).accessibilityLabel("Loading")
            } else if model.loadFailed {
                UnyriSettingsGroup {
                    VStack(spacing: 14) {
                        Text("Could not load accounts. Check your connection.").font(.subheadline)
                            .foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
                        Button("Retry") { Task { await model.load() } }.buttonStyle(UnyriPrimaryButtonStyle())
                    }.padding(16)
                }
            } else if model.users.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: model.mode == .blocked ? "person.crop.circle.badge.checkmark" : "person.crop.circle.badge.questionmark")
                        .font(.system(size: 32)).foregroundStyle(Color(hex: 0xAFA4F7)).accessibilityHidden(true)
                    Text(LocalizedStringKey(model.mode == .blocked && model.query.isEmpty ? "No blocked accounts" : "No accounts found"))
                        .font(.headline).foregroundStyle(UnyriColors.primaryText)
                        .accessibilityIdentifier("blocked-accounts-empty")
                }.frame(maxWidth: .infinity).padding(.vertical, 30)
            }
            if model.hasMore && !model.loading && !model.loadFailed {
                Button("Load more") { Task { await model.load(reset: false) } }
                    .frame(maxWidth: .infinity, minHeight: 48).accessibilityIdentifier("blocked-accounts-more")
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .task(id: model.query) { await model.load(debounce: !model.query.isEmpty) }
        .onChange(of: users.blockedAccountsRevision) { _ in Task { await model.load() } }
        .onChange(of: scenePhase) { phase in if phase == .active { Task { await model.load() } } }
        .sheet(item: $confirmation) { user in
            BlockedAccountConfirmation(user: user, adding: model.mode == .people) {
                confirmation = nil
                Task { if await model.change(user) { UnyriHaptics.selection() } }
            }
            .dynamicTypeSize(dynamicTypeSize)
            .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }
}

private struct BlockedAccountRow: View {
    let user: PublicUser
    let users: UsersRepository
    let adding: Bool
    let changing: Bool
    @State private var avatar: URL?

    var body: some View {
        HStack(spacing: 12) {
            UserAvatar(url: avatar, name: user.displayName, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(user.displayName.isEmpty ? user.username : user.displayName)
                    .font(.body.weight(.medium)).foregroundStyle(UnyriColors.primaryText).lineLimit(2)
                if !user.username.isEmpty {
                    Text("@" + user.username).font(.subheadline).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if changing { ProgressView() }
            else {
                Image(systemName: adding ? "person.crop.circle.badge.xmark" : "lock.open")
                    .font(.system(size: 19)).foregroundStyle(Color(hex: adding ? 0xEC8997 : 0x83D9B7))
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12).contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(LocalizedStringKey(adding ? "Block account" : "Unblock"))
        .task(id: "\(user.id)|\(user.avatarVersion)|\(user.avatarUrl ?? "")") {
            avatar = nil
            let result = await users.avatarURL(id: user.id, avatarUrl: user.avatarUrl, avatarVersion: user.avatarVersion)
            guard !Task.isCancelled else { return }
            avatar = result
        }
    }
}

private struct BlockedAccountConfirmation: View {
    @Environment(\.dismiss) private var dismiss
    let user: PublicUser
    let adding: Bool
    let confirm: () -> Void
    var body: some View {
        UnyriSettingsPage(adding ? "Block account" : "Unblock") {
            VStack(spacing: 16) {
                Image(systemName: adding ? "person.crop.circle.badge.xmark" : "lock.open")
                    .font(.system(size: 32)).foregroundStyle(Color(hex: adding ? 0xEC8997 : 0x83D9B7))
                Text(String(format: UnyriL10n.string(adding ? "Block %@?" : "Unblock %@?"), user.displayName.isEmpty ? user.username : user.displayName))
                    .font(.title3.weight(.semibold)).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Text(LocalizedStringKey(adding
                     ? "New messages and calls in both directions will be blocked. Existing history stays."
                     : "Your block will be removed. Messages and calls that were not delivered will not be restored."))
                    .font(.subheadline).foregroundStyle(UnyriColors.secondaryText)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Button(action: confirm) {
                    Text(LocalizedStringKey(adding ? "Block account" : "Unblock"))
                }.buttonStyle(UnyriPrimaryButtonStyle()).accessibilityIdentifier("blocked-account-confirm")
                Button("Cancel") { dismiss() }.frame(maxWidth: .infinity, minHeight: 48)
            }.padding(.top, 12)
        }
    }
}
