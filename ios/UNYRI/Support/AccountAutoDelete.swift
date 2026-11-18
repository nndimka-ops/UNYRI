import Foundation
import SwiftUI
import UIKit

struct AccountAutoDeleteSettings: Codable, Equatable {
    let inactiveDays: Int?
    let lastActiveAt: Date
    let nextDeletionAt: Date?
    let revision: Int64

    static let allowedDays = 1...1095
    static let presets = [1, 3, 7, 365, 1095]
}

/// Disabled must be sent as JSON null; an omitted key is not a disable request.
struct UpdateAccountAutoDelete: Encodable {
    let inactiveDays: Int?
    let expectedRevision: Int64
    let password: String?
    enum CodingKeys: String, CodingKey { case inactiveDays, expectedRevision, password }
    func encode(to encoder: Encoder) throws {
        var body = encoder.container(keyedBy: CodingKeys.self)
        if let inactiveDays { try body.encode(inactiveDays, forKey: .inactiveDays) }
        else { try body.encodeNil(forKey: .inactiveDays) }
        try body.encode(expectedRevision, forKey: .expectedRevision)
        try body.encodeIfPresent(password, forKey: .password)
    }
}

@MainActor
final class AccountAutoDeleteRepository: ObservableObject {
    @Published private(set) var value: AccountAutoDeleteSettings?
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let auth: AuthRepository
    private let generation: UInt64
    private let owner: String
    private let server: URL
    private var ticket: UInt64 = 0
    private var refreshPending = false
    private var revisionIsCurrent = false

    init(auth: AuthRepository) {
        self.auth = auth; generation = auth.sessionGeneration
        owner = auth.currentUser?.id ?? ""; server = auth.authenticatedServerURL
    }
    private var current: Bool {
        !owner.isEmpty && generation == auth.sessionGeneration && owner == auth.currentUser?.id && server == auth.authenticatedServerURL
    }
    private var client: APIClient {
        let generation = generation; let owner = owner; let server = server
        return APIClient(baseURL: server, preventRedirects: true, ephemeral: true,
            onAuthenticatedRequestError: { [weak auth] failure, bearer in
                Task { @MainActor in
                    guard let auth, generation == auth.sessionGeneration, owner == auth.currentUser?.id,
                          server == auth.authenticatedServerURL else { return }
                    await auth.handleAuthenticatedRequestError(failure, generation: generation, requestToken: bearer)
                }
            })
    }
    var canEdit: Bool { current && value != nil && revisionIsCurrent && !busy }
    private func finish(_ request: UInt64) {
        guard request == ticket else { return }
        busy = false
        if refreshPending { refreshPending = false; Task { [weak self] in await self?.refresh() } }
    }
    func refresh() async {
        guard current, !Task.isCancelled else { return }
        guard !busy else { refreshPending = true; return }
        busy = true; ticket &+= 1; let request = ticket
        defer { finish(request) }
        do {
            let token = try await auth.currentAccessToken()
            guard current, !Task.isCancelled else { return }
            let next = try await client
                .get("/users/me/auto-delete", accessToken: token, as: AccountAutoDeleteSettings.self)
            guard current, request == ticket, !Task.isCancelled else { return }
            guard next.inactiveDays.map(AccountAutoDeleteSettings.allowedDays.contains) ?? true else {
                revisionIsCurrent = false; error = UnyriL10n.string("Could not load account auto-delete. Try again."); return
            }
            value = next; revisionIsCurrent = true; error = nil
        } catch {
            if current, request == ticket, !Task.isCancelled {
                revisionIsCurrent = false
                self.error = UnyriL10n.string("Could not load account auto-delete. Try again.")
            }
        }
    }
    /// The revision comes from the policy shown in the confirmation, never a newer silent refresh.
    func save(inactiveDays: Int?, expectedRevision: Int64, password: String?) async -> Bool {
        guard canEdit, !Task.isCancelled, let old = value, expectedRevision == old.revision else { return false }
        guard inactiveDays.map(AccountAutoDeleteSettings.allowedDays.contains) ?? true else {
            error = UnyriL10n.string("Enter a whole number of days from 1 to 1095."); return false
        }
        guard inactiveDays != old.inactiveDays else { return false }
        guard inactiveDays == nil || !(password ?? "").isEmpty else {
            error = UnyriL10n.string("Enter your current account password to enable or change auto-delete."); return false
        }
        busy = true; ticket &+= 1; let request = ticket
        defer { finish(request) }
        do {
            if UnyriAppLock.shared.enabled {
                guard await UnyriAppLock.shared.authenticate(), current, !Task.isCancelled else { return false }
            }
            let token = try await auth.currentAccessToken()
            guard current, !Task.isCancelled else { return false }
            let saved = try await client
                .put("/users/me/auto-delete", accessToken: token,
                     body: UpdateAccountAutoDelete(inactiveDays: inactiveDays, expectedRevision: expectedRevision,
                         password: inactiveDays == nil ? nil : password), as: AccountAutoDeleteSettings.self)
            guard current, request == ticket, !Task.isCancelled else { return false }
            value = saved; revisionIsCurrent = true; error = nil
            return true
        } catch {
            guard current, request == ticket, !Task.isCancelled else { return false }
            let stale: Bool
            switch error {
            case APIError.http(409): stale = true
            case APIError.server(let code, _): stale = code == "conflict" || code.contains("revision") || code == "stale_auto_delete"
            default: stale = false
            }
            if stale {
                // An unsuccessful refetch must still block retries with the old revision.
                revisionIsCurrent = false; busy = false
                await refresh()
                if current { self.error = UnyriL10n.string("Account auto-delete changed on another device. Review the latest setting and try again.") }
            } else {
                let passwordRejected: Bool
                switch error {
                case APIError.server(let code, let message): passwordRejected = code == "invalid_password" || code == "password_required"
                    || (code == "validation_error" && ["Current password is incorrect.", "Enter your current password."].contains(message))
                default: passwordRejected = false
                }
                self.error = UnyriL10n.string(passwordRejected
                    ? "The account password was not accepted. Enter it again."
                    : "Could not save account auto-delete. Check your connection and try again.")
            }
            return false
        }
    }
}

/// Only this foreground view task records inactivity activity. Push handling,
/// background history backup, and access-token refresh never call the endpoint.
struct UnyriAccountActivityReporter: ViewModifier {
    @ObservedObject var auth: AuthRepository
    @Environment(\.scenePhase) private var phase
    func body(content: Content) -> some View {
        content.task(id: "\(phase)|\(auth.sessionGeneration)|\(auth.currentUser?.id ?? "")|\(auth.authenticatedServerURL.absoluteString)") {
            guard phase == .active, let owner = auth.currentUser?.id else { return }
            let generation = auth.sessionGeneration; let server = auth.authenticatedServerURL
            let account = auth
            let client = APIClient(baseURL: server, preventRedirects: true, ephemeral: true,
                onAuthenticatedRequestError: { [weak account] failure, bearer in
                    Task { @MainActor in
                        guard let account, generation == account.sessionGeneration, owner == account.currentUser?.id,
                              server == account.authenticatedServerURL else { return }
                        await account.handleAuthenticatedRequestError(failure, generation: generation, requestToken: bearer)
                    }
                })
            @MainActor func current() -> Bool {
                !Task.isCancelled && phase == .active && UIApplication.shared.applicationState == .active
                    && generation == auth.sessionGeneration && owner == auth.currentUser?.id && server == auth.authenticatedServerURL
            }
            while current() {
                do {
                    let token = try await auth.currentAccessToken()
                    guard current() else { return }
                    try await client.postNoContent("/users/me/activity", bearerToken: token)
                } catch { /* A failed connection does not count as account activity. Retry only while foreground. */ }
                guard current() else { return }
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
            }
        }
    }
}
