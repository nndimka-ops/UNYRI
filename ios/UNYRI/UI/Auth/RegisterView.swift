import SwiftUI

@MainActor
struct RegisterView: View {
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var scenePhase
    @Binding var serverAddress: String
    var onSignIn: (() -> Void)? = nil
    var initialInvite: SelfHostingPolicy.Invite? = nil
    @State private var registrationPolicy: RegistrationPolicy?
    @State private var policyOrigin: String?
    @State private var inviteCode = ""

    @State private var agreed = false
    @State private var username = ""
    @State private var displayName = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var isPasswordVisible = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var httpConsent: String?
    @State private var showingHTTPConsent = false
    private enum Input: Hashable { case name, handle, password, confirmation }
    @FocusState private var focusedInput: Input?

    private var sanitizedName: String { DisplayNamePolicy.sanitize(displayName) }

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

                credentialFields

                if SelfHostingPolicy.inviteVisible(registrationPolicy) {
                    TextField("Invitation code", text: $inviteCode)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().unyriFieldStyle()
                        .accessibilityIdentifier("register-invite-code")
                }
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
                .accessibilityIdentifier("register-submit")
                .disabled(isLoading || !agreed || registrationPolicy?.registrationOpen == false)
                if registrationPolicy?.registrationOpen == false { Text("Registration is temporarily closed by the server operator.").font(.footnote) }
                if let returnToSignIn = onSignIn {
                    Button("Log in", action: returnToSignIn).disabled(isLoading)
                }
            }
            .padding(24)
            .disabled(isLoading)
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar {
            if let returnToSignIn = onSignIn {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Back", action: returnToSignIn).disabled(isLoading)
                }
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focusedInput = nil }
            }
        }
        .unyriScreenBackground().navigationTitle("Create account")
        .navigationBarBackButtonHidden(isLoading)
        .task(id: serverAddress) { await loadRegistrationPolicy() }
        .onDisappear { inviteCode = "" }
        .navigationBarTitleDisplayMode(.inline)
        .alert("HTTP is unencrypted", isPresented: $showingHTTPConsent) {
            Button("Cancel", role: .cancel) { httpConsent = nil }
            Button("Use this HTTP server") {
                guard let origin = httpConsent else { return }
                var origins = UserDefaults.standard.stringArray(forKey: "login2.approved-origins") ?? []
                if !origins.contains(origin) { origins.append(origin) }
                UserDefaults.standard.set(origins, forKey: "login2.approved-origins")
                httpConsent = nil
                Task { @MainActor in await loadRegistrationPolicy(); submit() }
            }
        } message: {
            Text((httpConsent ?? "") + "\n" + UnyriL10n.string("HTTP is unencrypted. Your password and connection metadata can be intercepted. Installation protection requires HTTPS."))
        }
        .onChange(of: scenePhase) { next in
            if next == .background { password = ""; confirmPassword = ""; inviteCode = ""; isPasswordVisible = false }
        }
        .onChange(of: focusedInput) { input in
            revealInput(input, using: scroll)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
            // Re-anchor once after the keyboard appears, without fighting interactive dismissal.
            revealInput(focusedInput, using: scroll)
        }
        }
    }

    private var credentialFields: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                TextField("Your name", text: $displayName)
                    .textContentType(.name)
                    .accessibilityIdentifier("register-name")
                    .focused($focusedInput, equals: .name)
                    .submitLabel(.next)
                    .onSubmit { focusedInput = .handle }
                    .unyriFieldStyle()
                Text(verbatim: "\(sanitizedName.unicodeScalars.count)/\(DisplayNamePolicy.maximumScalars)")
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(sanitizedName.unicodeScalars.count > DisplayNamePolicy.maximumScalars ? UnyriColors.danger : UnyriColors.secondaryText)
                    .accessibilityIdentifier("register-name-count")
                Text("How others see you. You can use a nickname and change it later.")
                    .font(.caption).foregroundColor(.secondary)
            }
            .id(Input.name)
            VStack(alignment: .leading, spacing: 6) {
                TextField("Private login", text: $username)
                    .accessibilityIdentifier("register-login")
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textContentType(.username)
                    .focused($focusedInput, equals: .handle)
                    .submitLabel(.next)
                    .onSubmit { focusedInput = .password }
                    .unyriFieldStyle()
                Text("Only for signing in. Do not share it. Use 3–32 Latin letters, digits, underscores or dots. Your public number is created after registration.")
                    .font(.caption).foregroundColor(.secondary)
            }
            .id(Input.handle)
            passwordField.id(Input.password)
            // B-048: a password the server would refuse is named while it is typed.
            if !password.isEmpty, PublicIdentityPolicy.isCommonPassword(password) {
                Text(UnyriL10n.string(PublicIdentityPolicy.commonPasswordKey))
                    .font(.caption).foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("register-password-common")
            } else {
                Text(UnyriL10n.string(PublicIdentityPolicy.passwordStrengthKey(password)))
                    .font(.caption).foregroundStyle(UnyriColors.secondaryText)
                    .accessibilityIdentifier("register-password-strength")
            }
            Text("8–256 characters. Use a long password that you do not use elsewhere.")
                .font(.caption).foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Group {
                if isPasswordVisible { TextField("Confirm password", text: $confirmPassword) }
                else { SecureField("Confirm password", text: $confirmPassword) }
            }
            .textContentType(isPasswordVisible ? nil : .newPassword)
            .focused($focusedInput, equals: .confirmation)
            .submitLabel(.done)
            .onSubmit { focusedInput = nil }
            .autocorrectionDisabled().textInputAutocapitalization(.never).unyriFieldStyle()
            .id(Input.confirmation)
        }.padding(16).modifier(UnyriCardGlass())
            .accessibilityElement(children: .contain).accessibilityIdentifier("register-credentials")
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
        guard agreed, registrationPolicy?.registrationOpen != false else { return }
        let input = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUsername = input
        let trimmedDisplayName = sanitizedName

        guard isValidUsername(trimmedUsername) else {
            errorMessage = UnyriL10n.string("Private login must contain 3–32 Latin letters, digits, underscores or dots.")
            return
        }
        if let nameError = Validation.displayNameError(displayName) {
            errorMessage = UnyriL10n.string(nameError)
            focusedInput = .name
            return
        }
        guard (8...256).contains(password.unicodeScalars.count) else {
            errorMessage = UnyriL10n.string("Password must be 8-256 characters.")
            return
        }
        guard !PublicIdentityPolicy.isCommonPassword(password) else {
            errorMessage = UnyriL10n.string(PublicIdentityPolicy.commonPasswordKey)
            focusedInput = .password
            return
        }
        guard password == confirmPassword else {
            errorMessage = UnyriL10n.string("Passwords do not match.")
            return
        }

        guard registrationPolicy?.requiresInvite != true || !inviteCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = UnyriL10n.string("An invitation code is required."); return
        }

        if let origin = PublicIdentityPolicy.origin(serverAddress), origin.hasPrefix("http://"),
           let url = URL(string: origin), !AppConfig.hasIdentityHTTPConsent(url) {
            httpConsent = origin
            showingHTTPConsent = true
            return
        }

        isLoading = true
        errorMessage = nil
        Task { @MainActor in
            do {
                try auth.selectServer(serverAddress)
                serverAddress = AppConfig.serverAddressForEditing
                try await auth.register(
                    username: trimmedUsername,
                    password: password,
                    displayName: trimmedDisplayName,
                    inviteCode: registrationPolicy?.requiresInvite == true ? inviteCode.trimmingCharacters(in: .whitespacesAndNewlines) : nil
                )
                password = ""; confirmPassword = ""
            } catch {
                let failureCode: String?
                if case APIError.server(let code, _) = error { failureCode = code }
                else if case APIError.http(409) = error { failureCode = "conflict" }
                else { failureCode = nil }
                if let code = failureCode, SelfHostingPolicy.staleRegistration(code) {
                    await loadRegistrationPolicy(force: true)
                }
                if let code = failureCode, let key = SelfHostingPolicy.refusalKey(code) {
                    errorMessage = UnyriL10n.string(key)
                } else { errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription }
            }
            isLoading = false
        }
    }

    private func loadRegistrationPolicy(force: Bool = false) async {
        let selectedAddress = serverAddress
        guard let origin = PublicIdentityPolicy.origin(selectedAddress), let serverURL = URL(string: origin),
              serverURL.scheme == "https" || AppConfig.hasIdentityHTTPConsent(serverURL) else {
            registrationPolicy = nil; policyOrigin = nil; inviteCode = ""; return
        }
        if policyOrigin != origin {
            policyOrigin = origin; registrationPolicy = nil
            inviteCode = initialInvite?.origin == origin ? (initialInvite?.code ?? "") : ""
        }
        if !force {
            registrationPolicy = RegistrationPolicyCache.shared.cached(origin)
        } else { RegistrationPolicyCache.shared.invalidate(origin) }
        do {
            if !force, registrationPolicy != nil { return }
            let policy = try await AuthAPI(client: APIClient(baseURL: serverURL)).registrationPolicy()
            guard !Task.isCancelled, selectedAddress == serverAddress else { return }
            RegistrationPolicyCache.shared.save(policy, origin: origin)
            registrationPolicy = policy
        } catch { /* Registration still reports the server refusal; an outage is not an open policy. */ }
    }

    private func isValidUsername(_ value: String) -> Bool {
        guard (3...32).contains(value.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
        return value.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}
