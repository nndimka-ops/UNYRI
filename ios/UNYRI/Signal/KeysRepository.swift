import Combine
import Foundation

/// Repository around `KeysAPI`. Reuses `AuthRepository` for access-token
/// refresh — never duplicates refresh logic. No crypto generation here.
@MainActor
final class KeysRepository: ObservableObject {
    private let api: KeysAPI
    private let auth: AuthRepository
    private let generation: UInt64
    private var bundleRequests: [String: Task<UserBundlesResponse, Error>] = [:]
    private var bundleCooldown: [String: (until: TimeInterval, error: APIError)] = [:]
    private var deviceRequests: [String: Task<UserBundlesResponse, Error>] = [:]
    private var exactDeviceRequests: [String: Task<UserBundlesResponse, Error>] = [:]
    private var targetRequests: [String: Task<UserBundlesResponse, Error>] = [:]
    private var targetCooldown: [String: TimeInterval] = [:]

    /// Always fetch a fresh active-device list. No cryptographic bundle cache:
    /// revoked devices and changed identities must be observed on every send.
    func fetchDeviceBundles(userId: String) async throws -> UserBundlesResponse {
        _ = try verificationOwner()
        if let task = deviceRequests[userId] {
            #if os(iOS)
            PerformanceRecorder.shared.record(.networkRequest, ["operation": 7, "phase": 2])
            #endif
            let response = try await task.value
            _ = try verificationOwner()
            return response
        }
        let task = Task {
            do {
                return try await api.getDeviceBundles(accessToken: try await currentToken(), userId: userId)
            } catch APIError.http(404) {
                // Old servers have no discovery endpoint. Keep the former safe
                // bundle path; never fall back on authorization/server errors.
                return try await fetchBundles(userId: userId)
            }
        }
        deviceRequests[userId] = task
        defer { deviceRequests[userId] = nil }
        let response = try await task.value
        _ = try verificationOwner()
        return response
    }

    init(api: KeysAPI, auth: AuthRepository) {
        self.api = api
        self.auth = auth
        self.generation = auth.sessionGeneration
    }

    func uploadKeys(_ request: UploadKeysRequest) async throws -> UploadKeysResponse {
        try await api.uploadKeys(accessToken: try await currentToken(), request: request)
    }

    func fetchBundles(userId: String) async throws -> UserBundlesResponse {
        _ = try verificationOwner()
        if let cooldown = bundleCooldown[userId] {
            if ProcessInfo.processInfo.systemUptime < cooldown.until {
                #if os(iOS)
                PerformanceRecorder.shared.record(.networkRequest, ["operation": 7, "phase": 3, "status": 429,
                    "duration_ms": max(0, cooldown.until - ProcessInfo.processInfo.systemUptime) * 1000])
                #endif
                throw cooldown.error
            }
            bundleCooldown[userId] = nil
        }
        if let task = bundleRequests[userId] {
            #if os(iOS)
            PerformanceRecorder.shared.record(.networkRequest, ["operation": 7, "phase": 2])
            #endif
            let response = try await task.value
            _ = try verificationOwner()
            return response
        }
        let task = Task { try await api.getBundles(accessToken: try await currentToken(), userId: userId) }
        bundleRequests[userId] = task
        defer { bundleRequests[userId] = nil }
        do {
            let response = try await task.value
            _ = try verificationOwner()
            return response
        } catch {
            _ = try verificationOwner()
            if let apiError = error as? APIError, apiError.isRateLimited {
                bundleCooldown = bundleCooldown.filter { $0.value.until > ProcessInfo.processInfo.systemUptime }
                bundleCooldown[userId] = (ProcessInfo.processInfo.systemUptime + 60, apiError)
            }
            throw error
        }
    }

    func fetchOneTimePreKeyCount() async throws -> OneTimePreKeyCountResponse {
        try await api.oneTimePreKeyCount(accessToken: try await currentToken())
    }
    /// Mailbox discovery cannot inherit normal-chat compatibility fallback:
    /// a missing discovery endpoint must not consume prekeys for every device.
    func fetchExactDeviceBundles(userId: String, deviceId: String) async throws -> UserBundlesResponse {
        _ = try verificationOwner()
        guard UUID(uuidString: userId)?.uuidString.lowercased() == userId,
              UUID(uuidString: deviceId)?.uuidString.lowercased() == deviceId else { throw APIError.invalidURL }
        let response: UserBundlesResponse
        if let existing = exactDeviceRequests[userId] { response = try await existing.value }
        else {
            let task = Task { try await api.getDeviceBundles(accessToken: currentToken(), userId: userId) }
            exactDeviceRequests[userId] = task
            defer { exactDeviceRequests[userId] = nil }
            response = try await task.value
        }
        _ = try verificationOwner()
        let matches = response.devices.filter { $0.deviceId == deviceId }
        guard matches.count == 1 else { throw APIError.decoding("Invalid target device discovery") }
        return UserBundlesResponse(devices: matches)
    }
    /// No fallback to the all-device endpoint, even on a legacy-server 404.
    func fetchBundle(userId: String, deviceId: String) async throws -> UserBundlesResponse {
        _ = try verificationOwner()
        guard UUID(uuidString: userId)?.uuidString.lowercased() == userId,
              UUID(uuidString: deviceId)?.uuidString.lowercased() == deviceId else { throw APIError.invalidURL }
        let target = userId + ":" + deviceId
        if let until = targetCooldown[target], until > ProcessInfo.processInfo.systemUptime { throw APIError.http(429) }
        if let existing = targetRequests[target] {
            let result = try await existing.value; _ = try verificationOwner()
            guard result.devices.count == 1, result.devices[0].deviceId == deviceId else { throw APIError.decoding("Invalid target device bundle") }
            return result
        }
        let task = Task { try await api.getBundle(accessToken: currentToken(), userId: userId, deviceId: deviceId) }
        targetRequests[target] = task
        defer { targetRequests[target] = nil }
        do {
            let value = try await task.value; _ = try verificationOwner()
            guard value.devices.count == 1, value.devices[0].deviceId == deviceId else { throw APIError.decoding("Invalid target device bundle") }
            return value
        } catch {
            _ = try verificationOwner()
            if let apiError = error as? APIError, apiError.isRateLimited {
                targetCooldown = targetCooldown.filter { $0.value > ProcessInfo.processInfo.systemUptime }
                targetCooldown[target] = ProcessInfo.processInfo.systemUptime + 60
            }
            throw error
        }
    }

    func verificationOwner() throws -> String {
        guard generation == auth.sessionGeneration, let user = auth.currentUser?.id else { throw CancellationError() }
        return user
    }

    private func currentToken() async throws -> String {
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        let token = try await auth.currentAccessToken()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        return token
    }
}
