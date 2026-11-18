import SwiftUI

/// The profile card is the current account. Show only the one other account,
/// or Add when the registry allows a second account.
struct UnyriDrawerAccountSwitcher: View {
    var compact = false
    @ObservedObject private var accounts = UnyriAccountManager.shared
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1
    @State private var removal: UnyriAccountRecord?
    @State private var avatarHasPhoto = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let other = accounts.otherAccounts.first
            let row = DrawerCPolicy.accountRow(signedInCount: accounts.signedInCount, hasOther: other != nil,
                                              canAdd: accounts.canAddAccount, limit: UnyriAccountRegistry.limit)
            if row == .other, let record = other {
                let unread = max(0, accounts.unread[record.slot] ?? 0)
                Button { UnyriHaptics.selection(); Task { await accounts.switchTo(record) } } label: {
                    if compact {
                        UnyriAccountAvatar(record: record, size: 28, onImageChange: { avatarHasPhoto = $0 })
                            .overlay(alignment: .topTrailing) {
                                if unread > 0 {
                                    Circle().fill(UnyriColors.accent).frame(width: 7, height: 7).accessibilityHidden(true)
                                }
                            }
                            .frame(width: 44, height: 44).contentShape(Circle())
                    } else {
                        HStack(spacing: 10) {
                            UnyriAccountAvatar(record: record, size: 36, onImageChange: { avatarHasPhoto = $0 }).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(record.displayName).font(.system(size: 15 * textScale, weight: .semibold))
                                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                                    .fixedSize(horizontal: false, vertical: true)
                                if unread > 0 {
                                    Text(String(format: UnyriL10n.string("%@ unread"), String(unread)))
                                        .font(.system(size: 12 * textScale)).foregroundStyle(UnyriColors.secondaryText)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            if accounts.busy { ProgressView().controlSize(.small).frame(width: 18, height: 18) }
                            else {
                                Image(systemName: "arrow.up.right.and.arrow.down.left").font(.system(size: 18))
                                    .foregroundStyle(UnyriColors.secondaryText).accessibilityHidden(true)
                            }
                        }.foregroundStyle(UnyriColors.primaryText).padding(.leading, 8).padding(.trailing, 14)
                            .padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 52)
                            .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 26))
                            .overlay(RoundedRectangle(cornerRadius: 26).stroke(.white.opacity(0.12), lineWidth: 0.5))
                            .contentShape(RoundedRectangle(cornerRadius: 26))
                    }
                }.buttonStyle(.plain).disabled(accounts.busy)
                    .accessibilityLabel(Text(String(format: UnyriL10n.string("Switch to %@"), record.displayName)))
                    .accessibilityValue(Text([
                        avatarHasPhoto ? UnyriL10n.string("Profile photo") : "",
                        unread > 0 ? String(format: UnyriL10n.string("%@ unread"), String(unread)) : ""
                    ].filter { !$0.isEmpty }.joined(separator: ", ")))
                    .accessibilityHint(Text(accounts.needsSignIn.contains(record.slot) ? "Sign in again" : "Switch account"))
                    .accessibilityIdentifier("drawer-account-1")
                    .contextMenu {
                        Button(role: .destructive) { removal = record } label: {
                            Label("Remove account", systemImage: "person.crop.circle.badge.minus")
                        }
                    }
                    .accessibilityAction(named: Text("Remove account")) { removal = record }
            } else if row == .add {
                Button { UnyriHaptics.selection(); Task { await accounts.beginAddAccount() } } label: {
                    UnyriDrawerAddAccountLabel(compact: compact)
                }.buttonStyle(.plain).disabled(accounts.busy).accessibilityLabel("Add account").accessibilityIdentifier("drawer-add-account")
            }
            if let error = accounts.lastError {
                Text(error).font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("drawer-account-error")
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier("drawer-accounts")
            .unyriAccountRemoval($removal)
    }
}

struct UnyriDrawerAddAccountLabel: View {
    var compact = false
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1
    var body: some View {
        if compact {
            Image(systemName: "plus").font(.system(size: 16)).foregroundStyle(UnyriColors.secondaryText)
                .frame(width: 32, height: 32)
                .overlay(Circle().stroke(.white.opacity(0.25), style: StrokeStyle(lineWidth: 0.5, dash: [4, 3])))
                .frame(width: 44, height: 44).contentShape(Circle())
        } else {
            HStack(spacing: 8) {
                Image(systemName: "plus").font(.system(size: 16)).accessibilityHidden(true)
                Text("Add account").font(.system(size: 15 * textScale)).fixedSize(horizontal: false, vertical: true)
            }.foregroundStyle(UnyriColors.secondaryText).padding(.horizontal, 12).padding(.vertical, 8)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 26))
                .overlay(RoundedRectangle(cornerRadius: 26).stroke(.white.opacity(0.16), style: StrokeStyle(lineWidth: 0.5, dash: [4, 3])))
                .contentShape(RoundedRectangle(cornerRadius: 26))
        }
    }
}

/// Settings > Account: every account on this iPhone, switch, add, remove.
struct UnyriAccountsSettingsGroup: View {
    @ObservedObject private var accounts = UnyriAccountManager.shared
    @EnvironmentObject private var auth: AuthRepository
    @State private var removal: UnyriAccountRecord?
    @State private var photoSlots = Set<String>()

    var body: some View {
        UnyriSettingsGroup("Accounts") {
            if let user = auth.currentUser {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 17, weight: .semibold))
                        .foregroundColor(UnyriColors.accent).frame(width: 32, height: 32).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(user.displayName).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                        Text(AppConfig.apiBaseURL.host ?? "").font(.caption).foregroundColor(UnyriColors.secondaryText)
                    }
                    Spacer(minLength: 0)
                }
                .padding(14).frame(minHeight: 56)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isSelected)
                .accessibilityIdentifier("settings-account-current")
            }
            ForEach(Array(accounts.otherAccounts.enumerated()), id: \.element.slot) { index, record in
                UnyriSettingsDivider()
                HStack(spacing: 8) {
                    Button { Task { await accounts.switchTo(record) } } label: {
                        HStack(spacing: 12) {
                            UnyriAccountAvatar(record: record, size: 32, neutralFallback: true, onImageChange: { present in
                                if present { photoSlots.insert(record.slot) } else { photoSlots.remove(record.slot) }
                            }).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(record.displayName).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                                if accounts.needsSignIn.contains(record.slot) {
                                    Text("Sign in again").font(.caption).foregroundColor(UnyriColors.danger)
                                } else {
                                    Text(record.origin).font(.caption).foregroundColor(UnyriColors.secondaryText)
                                }
                            }
                            Spacer(minLength: 0)
                            if accounts.busy { ProgressView().controlSize(.small) }
                        }.contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(accounts.busy)
                    .accessibilityHint(Text("Switch account"))
                    .accessibilityValue(Text(photoSlots.contains(record.slot) ? UnyriL10n.string("Profile photo") : ""))
                    .accessibilityIdentifier("settings-account-\(index + 1)")
                    Button(role: .destructive) { removal = record } label: {
                        Image(systemName: "person.crop.circle.badge.minus").font(.system(size: 17, weight: .semibold))
                            .foregroundColor(UnyriColors.danger).frame(width: 44, height: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(accounts.busy)
                    .accessibilityLabel(Text("Remove account"))
                    .accessibilityIdentifier("settings-remove-account-\(index + 1)")
                }
                .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6).frame(minHeight: 56)
            }
            if accounts.canAddAccount {
                UnyriSettingsDivider()
                Button { Task { await accounts.beginAddAccount() } } label: {
                    UnyriSettingsRow(title: "Add account", icon: "person.crop.circle.badge.plus", tint: Color(hex: 0x7BC9EC))
                }
                .buttonStyle(.plain)
                .disabled(accounts.busy)
                .accessibilityIdentifier("settings-add-account")
            }
            if let error = accounts.lastError {
                Text(error).font(.footnote).foregroundColor(UnyriColors.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14).padding(.bottom, 12)
            }
        }
        .onAppear { accounts.lastError = nil }
        .unyriAccountRemoval($removal)
    }
}

private struct UnyriAccountRemovalDialog: ViewModifier {
    @Binding var removal: UnyriAccountRecord?
    @ObservedObject private var accounts = UnyriAccountManager.shared
    func body(content: Content) -> some View {
        content.confirmationDialog("Remove account?", isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } }),
            titleVisibility: .visible, presenting: removal) { record in
            Button("Remove account", role: .destructive) { Task { await accounts.removeInactive(record) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("This account is signed out on this iPhone and its messages and keys here are deleted. The account itself is not deleted; you can add it again.")
        }
    }
}

extension View {
    fileprivate func unyriAccountRemoval(_ removal: Binding<UnyriAccountRecord?>) -> some View {
        modifier(UnyriAccountRemovalDialog(removal: removal))
    }
}

/// Sign-in for an added account: one way back to the account that stays signed in.
struct UnyriAddAccountBar: ViewModifier {
    @ObservedObject private var accounts = UnyriAccountManager.shared
    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .top, spacing: 0) {
            if let back = accounts.returnRecord {
                HStack {
                    Button { Task { await accounts.cancelAddAccount() } } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "chevron.left").font(.system(size: 15, weight: .semibold)).accessibilityHidden(true)
                            Text("Back to \(back.displayName)").font(.subheadline.weight(.semibold)).lineLimit(1)
                        }
                        .foregroundColor(UnyriColors.accent)
                        .frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(accounts.busy)
                    .accessibilityIdentifier("add-account-cancel")
                    Spacer(minLength: 0)
                    if accounts.busy { ProgressView().controlSize(.small) }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}
