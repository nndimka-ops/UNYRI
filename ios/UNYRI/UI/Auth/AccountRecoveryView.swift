import SwiftUI

private final class RecoveryRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
private struct RecoveryReply: Decodable { var enabled: Bool?; var code: String? }

struct AccountRecoveryView: View {
    let signedIn: Bool
    let server: URL
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var scenePhase
    @State private var epoch: UInt64?
    @State private var generation = 0
    @State private var username = ""
    @State private var password = ""
    @State private var repeated = ""
    @State private var enteredCode = ""
    @State private var issuedCode = ""
    @State private var enabled: Bool?
    @State private var busy = false
    @State private var result: String?

    var body: some View {
        UnyriSettingsPage("Account recovery") {
            UnyriSettingsGroup("Current server") {
                VStack(alignment: .leading, spacing: 12) {
                    Label { Text(server.absoluteString).font(.caption).foregroundColor(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true) }
                        icon: { Image(systemName: "server.rack").unyriDecorativeForeground(Color(hex: 0x7BC9EC)).accessibilityHidden(true) }
                    Text("Optional recovery on this server. Save the code separately. It resets your password and signs out all phones. It does not restore encrypted history or replace your administrator key. HTTPS is required.")
                        .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            }
            if signedIn {
                UnyriSettingsGroup("Recovery code") {
                    VStack(alignment: .leading, spacing: 14) {
                        if let enabled {
                            Label(LocalizedStringKey(enabled ? "Recovery code is active" : "Recovery code is disabled"), systemImage: enabled ? "checkmark.shield" : "key")
                                .font(.subheadline.weight(.semibold)).foregroundColor(enabled ? UnyriColors.success : UnyriColors.secondaryText)
                        }
                        SecureField("Current password", text: $password).textContentType(.password)
                            .padding(.horizontal, 12).frame(minHeight: 52)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                        Button("Create a new recovery code") { perform {
                        let data = try await request("code", body: ["password":password])
                        guard let code = data.code, !code.isEmpty else { throw APIError.invalidURL }
                        issuedCode = code; enabled = true
                    } }.disabled(password.isEmpty || busy)
                            .unyriProminentAction().frame(maxWidth: .infinity)
                        if enabled == true {
                            Button("Disable recovery code", role: .destructive) { perform {
                            _ = try await request("disable", body: ["password":password])
                            enabled = false; issuedCode = ""; result = "Recovery code is disabled"
                        } }.disabled(password.isEmpty || busy)
                                .font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.danger)
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }.padding(14)
                }
                if !issuedCode.isEmpty {
                    UnyriSettingsGroup("Recovery code") {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("Shown only now. Save this code in a safe place. Creating a new code immediately replaces the previous one. If the response is lost, create another code using your password.")
                                .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                            Text(issuedCode).font(.system(.body, design: .monospaced)).privacySensitive()
                                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                            Button("Copy") {
                                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic:issuedCode]],
                                    options: [.localOnly:true, .expirationDate:Date().addingTimeInterval(60)])
                            }.unyriProminentAction().frame(maxWidth: .infinity)
                        }.padding(14)
                    }
                }
            } else {
                UnyriSettingsGroup("Account") {
                    VStack(alignment: .leading, spacing: 12) {
                        TextField("Your @handle", text: $username).textContentType(.username)
                            .frame(minHeight: 52).padding(.horizontal, 12).background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                        SecureField("Recovery code", text: $enteredCode).textContentType(.oneTimeCode)
                            .frame(minHeight: 52).padding(.horizontal, 12).background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                        SecureField("New password (8–256 characters)", text: $password).textContentType(.newPassword)
                            .frame(minHeight: 52).padding(.horizontal, 12).background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                        SecureField("Repeat new password", text: $repeated).textContentType(.newPassword)
                            .frame(minHeight: 52).padding(.horizontal, 12).background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                        Button("Reset password") { perform {
                        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
                        _ = try await request("reset", body: [
                            "username":name.hasPrefix("@") ? String(name.dropFirst()) : name,
                            "code":enteredCode, "new_password":password])
                        result = "Password changed. Sign in with your new password. This recovery code can no longer be used."
                    } }.disabled(busy || username.isEmpty || enteredCode.isEmpty || password.count < 8 ||
                            password.count > 256 || password != repeated)
                            .unyriProminentAction().frame(maxWidth: .infinity)
                    }.padding(14)
                }
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let result { UnyriSettingsGroup { Text(LocalizedStringKey(result)).font(.footnote).foregroundColor(UnyriColors.primaryText)
                .fixedSize(horizontal: false, vertical: true).padding(14).frame(maxWidth: .infinity, alignment: .leading) } }
        }
        .textInputAutocapitalization(.never).autocorrectionDisabled()
        .disabled(busy).privacySensitive()
        .scrollContentBackground(.hidden)
        .unyriDecorativeTint(Color(hex: 0x7BC9EC))
        .unyriScreenBackground().navigationTitle("Account recovery")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            epoch = auth.sessionGeneration
            if signedIn { perform { enabled = try await request("status").enabled } }
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { clearSecrets(); generation += 1 }
        }
        .onDisappear { clearSecrets(); generation += 1 }
    }
    private func clearSecrets() { password = ""; repeated = ""; enteredCode = ""; issuedCode = "" }
    @MainActor private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; result = nil
        let operation = generation
        Task { @MainActor in
            defer { busy = false; password = ""; repeated = ""; enteredCode = "" }
            do { try await action() }
            catch {
                guard operation == generation, epoch == auth.sessionGeneration else { return }
                result = "Could not complete recovery. Check your credentials, HTTPS connection and server version, or try again later. A lost response may mean the operation already completed."
            }
        }
    }
    @MainActor private func request(_ path: String, body: [String:String]? = nil) async throws -> RecoveryReply {
        let operation = generation
        guard server.scheme == "https", server == AppConfig.apiBaseURL, epoch == auth.sessionGeneration else { throw APIError.invalidURL }
        var request = URLRequest(url: server.appendingPathComponent("auth/recovery").appendingPathComponent(path))
        if signedIn {
            let token = try await auth.currentAccessToken()
            guard epoch == auth.sessionGeneration, operation == generation else { throw CancellationError() }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else if auth.currentUser != nil { throw CancellationError() }
        if let body {
            request.httpMethod = "POST"; request.httpBody = try JSONEncoder().encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: RecoveryRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data,response) = try await session.data(for:request)
        guard epoch == auth.sessionGeneration, operation == generation, server == AppConfig.apiBaseURL,
              !Task.isCancelled else { throw CancellationError() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.http((response as? HTTPURLResponse)?.statusCode ?? 500) }
        return data.isEmpty ? RecoveryReply() : try JSONDecoder().decode(RecoveryReply.self,from:data)
    }
}
