import SwiftUI

struct RegisterView: View {
    @EnvironmentObject private var auth: AuthRepository
    @Binding var serverAddress: String

    @State private var agreed = false
    @State private var username = ""
    @State private var displayName = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var isPasswordVisible = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    private enum Input: Hashable { case name, handle, password, confirmation }
    @FocusState private var focusedInput: Input?

    var body: some View {
        ScrollViewReader { scroll in
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 8) {
                    UnyriBrandHeader()
                    Text("Create account")
                        .font(.system(size: 28, weight: .bold))
                    Text("Join UNYRI")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.top, 16)
                .padding(.bottom, 8)

                AppLanguagePicker()
                    ServerAddressField(address: $serverAddress)
                    if let url = try? AppConfig.normalizedServerURL(serverAddress) { NavigationLink("Server card") { ServerCardView(server: url) } }

                VStack(alignment: .leading, spacing: 6) {
                    TextField("Your name", text: $displayName)
                        .textContentType(.name)
                        .focused($focusedInput, equals: .name)
                        .submitLabel(.next)
                        .onSubmit { focusedInput = .handle }
                        .unyriFieldStyle()
                    Text("How others see you. You can use a nickname and change it later.")
                        .font(.caption).foregroundColor(.secondary)
                }
                .id(Input.name)
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Choose an @handle", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                        .focused($focusedInput, equals: .handle)
                        .submitLabel(.next)
                        .onSubmit { focusedInput = .password }
                        .unyriFieldStyle()
                    Text("Unique on this server. Used for sign-in and search. 3–32 Latin letters, digits, underscores or dots.")
                        .font(.caption).foregroundColor(.secondary)
                }

                .id(Input.handle)

                passwordField
                    .id(Input.password)
                Text("8–256 characters. Use a long password that you do not use elsewhere.")
                    .font(.caption).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Group {
                    if isPasswordVisible {
                        TextField("Confirm password", text: $confirmPassword)
                    } else {
                        SecureField("Confirm password", text: $confirmPassword)
                    }
                }
                .textContentType(isPasswordVisible ? nil : .newPassword)
                .focused($focusedInput, equals: .confirmation)
                .submitLabel(.done)
                .onSubmit { focusedInput = nil }
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .unyriFieldStyle()
                .id(Input.confirmation)

                if let errorMessage {
                    UnyriNotice(message: errorMessage)
                }

                Toggle(UnyriLegalContent.agreement, isOn: $agreed)
                NavigationLink(UnyriLegalContent.title("terms")) { AlphaTermsView() }
                NavigationLink(UnyriLegalContent.title("privacy")) { UnyriLegalView(documentID: "privacy") }
                Text(verbatim: UnyriLegalContent.registrationNote).font(.caption).foregroundColor(UnyriColors.secondaryText)
                Text(verbatim: UnyriLegalContent.serverNote).font(.caption).foregroundColor(UnyriColors.secondaryText)
                Button(action: submit) {
                    if isLoading {
                        ProgressView().tint(UnyriColors.onAccent)
                    } else {
                        Text("Register")
                    }
                }
                .buttonStyle(UnyriPrimaryButtonStyle())
                .disabled(isLoading || !agreed)
            }
            .padding(24)
            .disabled(isLoading)
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focusedInput = nil }
            }
        }
        .unyriScreenBackground().navigationTitle("Create account")
        .navigationBarBackButtonHidden(isLoading)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: focusedInput) { input in
            revealInput(input, using: scroll)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
            // Re-anchor once after the keyboard appears, without fighting interactive dismissal.
            revealInput(focusedInput, using: scroll)
        }
        }
    }

    private func revealInput(_ input: Input?, using scroll: ScrollViewProxy) {
        guard let input else { return }
        withAnimation(.easeOut(duration: 0.18)) {
            scroll.scrollTo(input, anchor: .center)
        }
    }

    private var passwordField: some View {
        HStack(spacing: 12) {
            Group {
                if isPasswordVisible {
                    TextField("Password", text: $password)
                } else {
                    SecureField("Password", text: $password)
                }
            }
            .textContentType(isPasswordVisible ? nil : .newPassword)
            .focused($focusedInput, equals: .password)
            .submitLabel(.next)
            .onSubmit { focusedInput = .confirmation }
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)

            Button {
                isPasswordVisible.toggle()
            } label: {
                Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
                    .foregroundColor(.secondary)
            }
            .accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
        }
        .unyriFieldStyle()
    }

    private func submit() {
        guard agreed else { return }
        let input = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUsername = input.hasPrefix("@") ? String(input.dropFirst()) : input
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)

        guard isValidUsername(trimmedUsername) else {
            errorMessage = UnyriL10n.string("The handle must contain 3–32 Latin letters, digits, underscores or dots.")
            return
        }
        guard !trimmedDisplayName.isEmpty, trimmedDisplayName.count <= 64 else {
            errorMessage = UnyriL10n.string("Your name must contain 1–64 characters.")
            return
        }
        guard (8...256).contains(password.count) else {
            errorMessage = UnyriL10n.string("Password must be 8-256 characters.")
            return
        }
        guard password == confirmPassword else {
            errorMessage = UnyriL10n.string("Passwords do not match.")
            return
        }

        isLoading = true
        errorMessage = nil
        Task {
            do {
                try auth.selectServer(serverAddress)
                serverAddress = AppConfig.serverAddressForEditing
                try await auth.register(
                    username: trimmedUsername,
                    password: password,
                    displayName: trimmedDisplayName
                )
            } catch {
                errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            }
            isLoading = false
        }
    }

    private func isValidUsername(_ value: String) -> Bool {
        guard (3...32).contains(value.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
        return value.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}
