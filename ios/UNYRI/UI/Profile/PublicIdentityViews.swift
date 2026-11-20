import SwiftUI

@MainActor
struct PublicIdentityCard: View {
    let identity: OwnerIdentity
    @State private var showingQR = false
    var body: some View {
        UnyriSettingsGroup("Your public number") {
            VStack(alignment: .leading, spacing: 12) {
                Text(PublicIdentityPolicy.formattedNumber(identity.unyriNumber)).font(.title2.monospacedDigit().bold())
                    .accessibilityIdentifier("profile-public-number")
                let alias = PeerIdentityPolicy.label(publicUsername: identity.publicUsername, unyriNumber: nil)
                if !alias.isEmpty { Text(alias).foregroundStyle(UnyriColors.secondaryText) }
                HStack {
                    Button("Copy number") { UIPasteboard.general.string = identity.unyriNumber }
                    if let link = identity.link { ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") } }
                    Button { showingQR = true } label: { Image(systemName: "qrcode") }.accessibilityLabel("My QR code")
                }
                Text("Your number never changes. Your login stays private. You can choose an optional @username later.")
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
            }.padding(16)
        }.sheet(isPresented: $showingQR) { UnyriProfileQRSheet(identity: identity) }
    }
}

/// Do not infer visibility from an existing chat or fall back to its login.
@MainActor
struct PeerPublicIdentityView: View {
    let userID: String
    @EnvironmentObject private var users: UsersRepository
    @Environment(\.scenePhase) private var scenePhase
    @State private var profile: DiscoveredProfile?
    @State private var origin: String?
    @State private var message: String?
    var body: some View {
        VStack(spacing: 10) {
            if let profile {
                Text(PublicIdentityPolicy.formattedNumber(profile.unyriNumber)).font(.subheadline.monospacedDigit())
                    .accessibilityIdentifier("peer-public-number")
                let alias = PeerIdentityPolicy.label(publicUsername: profile.publicUsername, unyriNumber: nil)
                if !alias.isEmpty { Text(alias).font(.subheadline) }
                HStack {
                    Button("Copy number") { UIPasteboard.general.string = profile.unyriNumber }
                    if let origin, let link = PublicIdentityPolicy.link(number: profile.unyriNumber, origin: origin) {
                        ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") }
                    }
                }.font(.footnote)
            }
            if let message { Text(message).font(.footnote) }
        }
        .foregroundStyle(UnyriColors.secondaryText)
        .task(id: scenePhase) {
            profile = nil; message = nil
            guard scenePhase == .active else { return }
            do {
                let result = try await users.publicProfile(userID)
                let own = try? await users.getIdentity()
                guard !Task.isCancelled else { return }
                profile = result; origin = own?.publicOrigin
            } catch is CancellationError { }
            catch { message = IdentityErrorText.message(error) }
        }
    }
}

/// Explicit Apply avoids a partial multi-endpoint Save with profile/photo edits.
/// Validation remains server-authoritative; typing never starts a request.
@MainActor
struct PublicIdentityEditor: View {
    @EnvironmentObject private var users: UsersRepository
    @Binding var dirty: Bool
    @Binding var applying: Bool
    @State private var identity: OwnerIdentity?
    @State private var username = ""
    @State private var policy = "everyone"
    @State private var message: String?
    private var changed: Bool { username != (identity?.publicUsername ?? "") || policy != identity?.discoverability }
    var body: some View {
        UnyriSettingsGroup("How people find you") {
            VStack(alignment: .leading, spacing: 12) {
                if let identity {
                    PublicIdentityCard(identity: identity)
                    HStack {
                        Text("@").accessibilityHidden(true)
                        TextField("Optional username", text: $username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().unyriFieldStyle()
                        .accessibilityIdentifier("edit-profile-username")
                        .onChange(of: username) { value in
                            if value.utf8.count > 128 { username = String(value.prefix(64)) }
                            message = nil; dirty = changed
                        }
                    }
                    Text("Use 5–32 ASCII letters, digits or _, start with a letter, and avoid __.")
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    Text("Leave empty to remove. After a change, wait 7 days to change or remove again. The old username is held for 30 days. Username links can later belong to someone else; share your permanent number instead.")
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    Picker("Who can find me", selection: $policy) {
                        Text("Everyone").tag("everyone")
                        Text("My contacts").tag("contacts")
                        Text("Nobody").tag("nobody")
                    }.onChange(of: policy) { _ in dirty = changed }
                    Text("My contacts means people in your own contact list. Hidden, blocked and missing profiles all appear unavailable.")
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    Button("Apply identity changes") { Task { await apply() } }
                        .disabled(applying || !changed).accessibilityIdentifier("login2-identity-apply")
                } else {
                    Button("Load public identity") { Task { await reload() } }
                }
                if applying { ProgressView() }
                if let message { Text(message).font(.footnote).foregroundStyle(UnyriColors.secondaryText).accessibilityIdentifier("login2-identity-error") }
            }.padding(16)
        }.task { await reload() }
    }
    private func reload() async {
        do {
            let result = try await users.getIdentity()
            guard !Task.isCancelled else { return }
            identity = result; username = result.publicUsername ?? ""; policy = result.discoverability; dirty = false
        } catch is CancellationError { }
        catch { message = IdentityErrorText.message(error) }
    }
    private func apply() async {
        guard let identity, !applying else { return }
        applying = true; message = nil; defer { applying = false }
        let patch: Patch<String> = username == (identity.publicUsername ?? "") ? .absent : (username.isEmpty ? .clear : .set(username))
        do {
            let result = try await users.updateIdentity(.init(expectedRevision: identity.revision, publicUsername: patch,
                discoverability: policy == identity.discoverability ? nil : policy))
            self.identity = result; username = result.publicUsername ?? ""; policy = result.discoverability; dirty = false
            message = UnyriL10n.string("Identity saved")
        } catch is CancellationError { }
        catch {
            if let status = error as? APIStatusError, status.status == 409, status.code == "conflict" {
                await reload()
            }
            message = IdentityErrorText.message(error)
        }
    }
}
