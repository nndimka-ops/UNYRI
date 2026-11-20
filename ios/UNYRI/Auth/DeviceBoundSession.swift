import Foundation

#if os(iOS)
extension SessionStore {
    private func preparedDeviceBinding(newSession: Bool = false) async throws -> (id: String, key: DeviceSessionKey) {
        try Task.checkCancellation()
        do {
            return try await DeviceBoundKey.prepare(readID: { try self.deviceBindingID() },
                saveID: { try self.saveDeviceBindingID($0) },
                mayReplaceMissing: {
                    guard newSession else { return false }
                    return try self.checkedServerContinuityUserID() == nil
                })
        } catch DeviceBoundProof.Failure.missingKey {
            // A restored/copied token store cannot enroll another phone's key.
            throw APIError.server(code: "session_revoked", message: "Session ended")
        }
    }

    func loginWithDeviceKey(_ request: LoginRequest, api: AuthAPI) async throws -> AuthResponse {
        let binding = try await preparedDeviceBinding(newSession: true)
        var request = request
        request.deviceKey = binding.key
        for attempt in 0...1 {
            let proof = try await DeviceBoundKey.proof(id: binding.id, method: "POST", target: "/auth/login",
                session: request.installationId, token: "", timestamp: deviceBindingClock.timestamp())
            do {
                let response = try await api.login(request, deviceProof: proof)
                try saveDeviceBindingSessionID(DeviceBoundProof.accessSession(response.accessToken), keyID: binding.id)
                return response
            }
            catch let challenge as DeviceBoundClockChallenge {
                guard attempt == 0 else { throw APIError.http(401) }
                deviceBindingClock.correct(serverTime: challenge.serverTime)
            }
        }
        throw DeviceBoundProof.Failure.unavailable
    }

    func registerWithDeviceKey(_ request: RegisterRequest, api: AuthAPI) async throws -> AuthResponse {
        let binding = try await preparedDeviceBinding(newSession: true)
        var request = request
        request.deviceKey = binding.key
        for attempt in 0...1 {
            let proof = try await DeviceBoundKey.proof(id: binding.id, method: "POST", target: "/auth/register",
                session: request.installationId, token: "", timestamp: deviceBindingClock.timestamp())
            do {
                let response = try await api.register(request, deviceProof: proof)
                try saveDeviceBindingSessionID(DeviceBoundProof.accessSession(response.accessToken), keyID: binding.id)
                return response
            }
            catch let challenge as DeviceBoundClockChallenge {
                guard attempt == 0 else { throw APIError.http(401) }
                deviceBindingClock.correct(serverTime: challenge.serverTime)
            }
        }
        throw DeviceBoundProof.Failure.unavailable
    }

    /// The request_id survives retries; jti/signature are fresh on every attempt.
    func refreshWithDeviceKey(_ request: RefreshRequest, api: AuthAPI) async throws -> RefreshResponse {
        let binding = try await preparedDeviceBinding()
        var request = request
        request.deviceKey = binding.key
        let session = try DeviceBoundProof.refreshSession(request.refreshToken)
        for attempt in 0...1 {
            let proof = try await DeviceBoundKey.proof(id: binding.id, method: "POST", target: "/auth/refresh",
                session: session, token: request.refreshToken, timestamp: deviceBindingClock.timestamp())
            do {
                let response = try await api.refresh(request, deviceProof: proof)
                try saveDeviceBindingSessionID(DeviceBoundProof.accessSession(response.accessToken), keyID: binding.id)
                return response
            }
            catch let challenge as DeviceBoundClockChallenge {
                guard attempt == 0 else { throw APIError.http(401) }
                deviceBindingClock.correct(serverTime: challenge.serverTime)
            }
        }
        throw DeviceBoundProof.Failure.unavailable
    }

    func webSocketDeviceProof(token: String) async throws -> String {
        guard let id = try deviceBindingID() else {
            throw APIError.server(code: "session_revoked", message: "Session ended")
        }
        do {
            return try await DeviceBoundKey.proof(id: id, method: "GET", target: "ws",
                session: DeviceBoundProof.accessSession(token), token: token, timestamp: deviceBindingClock.timestamp())
        } catch DeviceBoundProof.Failure.missingKey {
            throw APIError.server(code: "session_revoked", message: "Session ended")
        }
    }
}
#endif
