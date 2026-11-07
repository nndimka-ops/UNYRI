import SwiftUI

struct LoginView: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @EnvironmentObject private var auth: AuthRepository

    @State private var username = ""
    @State private var serverAddress = AppConfig.serverAddressForEditing
    @State private var password = ""
    @State private var isPasswordVisible = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var isShowingRegister = false
    @State private var isShowingRecovery = false
    @State private var isShowingServer = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 10) {
                        UnyriConversationBrandMark(size: 88, animated: true)
                        Text("UNYRI").font(.system(size: 36, weight: .bold, design: .rounded)).foregroundStyle(UnyriColors.primaryText)
                        Text("Back in touch").font(.subheadline).foregroundStyle(UnyriColors.secondaryText)
                    }.padding(.top, 28).padding(.bottom, 20)

                    Button { isShowingServer = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "network").foregroundStyle(colorStyle.decorative(ChatControlPalette.blue)).frame(width: 38, height: 38).background(colorStyle.surface(ChatControlPalette.blueBack), in: RoundedRectangle(cornerRadius: 12))
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Server").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                                Text(serverAddress).font(.subheadline.weight(.medium)).foregroundStyle(UnyriColors.primaryText).lineLimit(1).truncationMode(.middle)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(colorStyle.decorative(ChatControlPalette.blue))
                        }.padding(12).background(colorStyle.surface(ChatControlPalette.composer), in: RoundedRectangle(cornerRadius: 18))
                    }.buttonStyle(.plain)

                    VStack(spacing: 10) {
                        HStack(spacing: 12) {
                            Image(systemName: "person.fill").foregroundStyle(colorStyle.decorative(ChatControlPalette.blue)).frame(width: 36, height: 36).background(colorStyle.surface(ChatControlPalette.blueBack), in: RoundedRectangle(cornerRadius: 11))
                            TextField("Your @handle", text: $username)
                                .textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                        }.padding(12).background(colorStyle.surface(ChatControlPalette.composer), in: RoundedRectangle(cornerRadius: 18))
                        passwordField
                    }
                    Text("The handle you chose when registering on this server, with or without @.")
                        .font(.caption).foregroundStyle(UnyriColors.secondaryText).frame(maxWidth: .infinity, alignment: .leading)

                    if let errorMessage {
                        UnyriNotice(message: errorMessage)
                    }

                    Button(action: submit) {
                        Group {
                            if isLoading {
                                ProgressView().tint(colorStyle.onColorfulButton)
                            } else {
                                HStack { Text("Log in"); Image(systemName: "arrow.right") }
                            }
                        }
                        .font(.headline).foregroundStyle(colorStyle.onColorfulButton).frame(maxWidth: .infinity, minHeight: 52)
                        .background(LinearGradient(colors: colorStyle.isMonochrome ? [Color(hex: UnyriAppColorStyle.monochromeAccent), Color(hex: UnyriAppColorStyle.monochromeAccent)] : [Color(hex: 0x8574FF), Color(hex: 0x576CEA)], startPoint: .leading, endPoint: .trailing), in: RoundedRectangle(cornerRadius: 18))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(isLoading).opacity(isLoading ? 0.55 : 1)

                    Button("Account recovery") {
                        do {
                            try auth.selectServer(serverAddress)
                            serverAddress = AppConfig.serverAddressForEditing
                            isShowingRecovery = true
                        } catch { errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription }
                    }
                    Button {
                        isShowingRegister = true
                    } label: {
                        Text("Create an account")
                            .font(.subheadline)
                            .foregroundColor(colorStyle.decorative(ChatControlPalette.blue))
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .overlay(RoundedRectangle(cornerRadius: 18).stroke(ChatControlPalette.edge.opacity(0.45), lineWidth: 1))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    AppLanguagePicker()
                    NavigationLink("About UNYRI") { AlphaAboutView() }.font(.caption).foregroundStyle(UnyriColors.secondaryText)
                }
                .padding(24)
                .disabled(isLoading)
            }
            .scrollDismissesKeyboard(.interactively)
            .sheet(isPresented: $isShowingServer) {
                NavigationStack {
                    ScrollView {
                        VStack(spacing: 18) {
                            ServerAddressField(address: $serverAddress)
                            if let url = try? AppConfig.normalizedServerURL(serverAddress) { NavigationLink("Server card") { ServerCardView(server: url) } }
                            if let errorMessage { UnyriNotice(message: errorMessage) }
                        }.padding(24)
                    }.unyriScreenBackground().navigationTitle("Server").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") {
                        do { try auth.selectServer(serverAddress); serverAddress = AppConfig.serverAddressForEditing; errorMessage = nil; isShowingServer = false }
                        catch { errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription }
                    } } }
                }
            }
            .unyriScreenBackground().toolbar(.hidden, for: .navigationBar)
            .performanceScreen(.login)
            .navigationDestination(isPresented: $isShowingRecovery) {
                AccountRecoveryView(signedIn: false, server: AppConfig.apiBaseURL)
            }
            .navigationDestination(isPresented: $isShowingRegister) {
                RegisterView(serverAddress: $serverAddress)
            }
        }
    }

    private var passwordField: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.fill").foregroundStyle(colorStyle.decorative(ChatControlPalette.mint)).frame(width: 36, height: 36).background(colorStyle.surface(ChatControlPalette.mintBack), in: RoundedRectangle(cornerRadius: 11))
            Group {
                if isPasswordVisible {
                    TextField("Password", text: $password)
                } else {
                    SecureField("Password", text: $password)
                }
            }
            .textContentType(.password)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)

            Button {
                isPasswordVisible.toggle()
            } label: {
                Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
                    .foregroundColor(colorStyle.decorative(ChatControlPalette.mint)).frame(width: 44, height: 44)
            }.accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
        }
        .padding(12).background(colorStyle.surface(ChatControlPalette.composer), in: RoundedRectangle(cornerRadius: 18))
    }

    private func submit() {
        let input = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = input.hasPrefix("@") ? String(input.dropFirst()) : input
        guard !trimmed.isEmpty, !password.isEmpty else {
            errorMessage = UnyriL10n.string("Enter your @handle and password.")
            return
        }

        isLoading = true
        errorMessage = nil
        Task {
            do {
                try auth.selectServer(serverAddress)
                serverAddress = AppConfig.serverAddressForEditing
                try await auth.login(username: trimmed, password: password)
            } catch {
                errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            }
            isLoading = false
        }
    }
}

struct ServerAddressField: View {
    @Binding var address: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Server").font(.caption).foregroundColor(.secondary)
            TextField("https://server.example or http://192.168.1.10:8080", text: $address)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .unyriFieldStyle()
            #if DEBUG || UNYRI_ALPHA_LAN
            Text("Use http:// for a local server without HTTPS.").font(.caption2).foregroundColor(.secondary)
            #else
            Text("Enter the HTTPS address provided by your server operator.").font(.caption2).foregroundColor(.secondary)
            #endif
        }
    }
}

/// Two speech bubbles, shared by login and the static chat-list title.
struct UnyriConversationBrandMark: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    var size: CGFloat = 32
    var animated = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tilt = 0.0
    @State private var appeared = false
    @State private var animationTask: Task<Void, Never>?
    private var mark: some View {
        Image("UnyriBrandMark")
            .resizable().renderingMode(.original).scaledToFit()
            .frame(width: size, height: size)
            .rotation3DEffect(.degrees(tilt), axis: (x: 0, y: 1, z: 0))
    }
    var body: some View {
        Group {
            if animated { Button(action: animate) { mark }.buttonStyle(.plain).accessibilityLabel("UNYRI") }
            else { mark.accessibilityHidden(true) }
        }.onAppear { guard !appeared else { return }; appeared = true; animate() }.onDisappear { animationTask?.cancel(); tilt = 0 }
    }
    private func animate() {
        guard animated, !reduceMotion else { return }
        animationTask?.cancel()
        tilt = -10
        animationTask = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 16_000_000) } catch { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { tilt = 0 }
        }
    }
}
