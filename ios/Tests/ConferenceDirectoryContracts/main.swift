import Foundation

@main struct DirectoryContracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); checks += 1 }
        let source = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let vectors = try JSONSerialization.jsonObject(with: source) as! [String: Any]
        let context = vectors["context"] as! [String: Any]
        var requestObject: [String: Any] = context.filter { !["origin", "now_epoch_seconds", "request_expires_at_rfc3339"].contains($0.key) }
        requestObject["id"] = requestObject.removeValue(forKey: "request_id")
        requestObject["expires_at"] = context["request_expires_at_rfc3339"]
        requestObject["status"] = "ready"
        requestObject["envelope"] = ["recipient_device_id": context["requester_device_id"]!, "message_type": 1, "ciphertext": Data([1, 2, 3]).base64EncodedString()]
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase; decoder.dateDecodingStrategy = .iso8601
        let request = try decoder.decode(ConferenceKeyRequest.self, from: JSONSerialization.data(withJSONObject: requestObject))
        let origin = URL(string: context["origin"] as! String)!
        let user = context["requester_user_id"] as! String, device = context["requester_device_id"] as! String
        let now = Date(timeIntervalSince1970: context["now_epoch_seconds"] as! Double)
        for (field, replacement) in [
            ("nonce", ConferenceInvite.encode(Data(repeating: 7, count: 32))),
            ("requester_device_id", "ffffffff-ffff-4fff-8fff-ffffffffffff"),
            ("provider_identity", "changed-admission"),
            ("assignment_revision", 99), ("access_revision", 99),
            ("expires_at", "2027-01-15T09:00:59Z")
        ] as [(String, Any)] {
            var changed = requestObject; changed[field] = replacement
            let value = try decoder.decode(ConferenceKeyRequest.self, from: JSONSerialization.data(withJSONObject: changed))
            check(value.encryptedEnvelopeBinding != request.encryptedEnvelopeBinding, "provider ciphertext cache fences " + field)
        }
        var sameBinding = requestObject; sameBinding["status"] = "pending"; sameBinding.removeValue(forKey: "envelope")
        let pending = try decoder.decode(ConferenceKeyRequest.self, from: JSONSerialization.data(withJSONObject: sameBinding))
        check(pending.encryptedEnvelopeBinding == request.encryptedEnvelopeBinding, "status-only acknowledgement retries reuse the same ciphertext")
        var good: ConferenceKeyPayload?
        for row in vectors["cases"] as! [[String: Any]] {
            let bytes = Data((row["payload_json"] as! String).utf8)
            let overrides = row["context_overrides"] as? [String: Any] ?? [:]
            let caseNow = Date(timeIntervalSince1970: (overrides["now_epoch_seconds"] ?? context["now_epoch_seconds"]) as! Double)
            do {
                let value = try ConferenceKeyPayload.decode(bytes, request: request, server: origin, requester: user, device: device, now: caseNow)
                check(row["valid"] as! Bool, "unexpected acceptance: " + (row["name"] as! String)); good = value
            } catch { check(!(row["valid"] as! Bool), "unexpected rejection: " + (row["name"] as! String)) }
        }
        let payload = good!
        check(!String(describing: payload).contains(payload.mediaKey), "payload descriptions redact the key")
        let produced = try ConferenceKeyPayload.providing(request: request, server: origin, mediaKey: payload.mediaKey, now: now)
        let object = try JSONSerialization.jsonObject(with: produced) as! [String: Any]
        check(object.count == 14 && object["invite_secret"] == nil && object["media_key"] as? String == payload.mediaKey, "provider sends exact media-key-only payload")
        check(try ConferenceKeyPayload.decode(produced, request: request, server: origin, requester: user, device: device, now: now).mediaKey == payload.mediaKey, "production provider/receiver interoperate")
        for field in ["recipient_device_id", "message_type", "ciphertext"] {
            var invalid = requestObject
            var envelope = invalid["envelope"] as! [String: Any]
            envelope[field] = field == "message_type" ? 3 : "wrong"
            invalid["envelope"] = envelope
            let value = try decoder.decode(ConferenceKeyRequest.self, from: JSONSerialization.data(withJSONObject: invalid))
            do { try value.validate(now: now); preconditionFailure("malformed mailbox envelope accepted") } catch { checks += 1 }
        }
        var config = ConferenceRoomConfiguration(title: "  A room  ", accessMode: .password, passwordAction: .set, password: "  secret  ")
        try config.validate(creating: true); check(config.password == "  secret  ", "password whitespace is not trimmed")
        for mode in ConferenceAccessMode.allCases { check(ConferenceAccessMode(rawValue: mode.rawValue) == mode, "all four modes round trip") }
        check(ConferenceAccessMode(rawValue: "future_open") == nil, "unknown mode fails closed")
        for bad in ["short", String(repeating: "a", count: 129), "bad\npassword"] {
            config.password = bad
            do { try config.validate(creating: true); preconditionFailure("invalid password accepted") } catch { checks += 1 }
        }
        config.password = nil; config.passwordAction = .keep
        do { try config.validate(creating: true); preconditionFailure("new password mode without password accepted") } catch { checks += 1 }
        try config.validate(creating: false); checks += 1
        config.passwordAction = .remove
        do { try config.validate(creating: false); preconditionFailure("password removal in password mode accepted") } catch { checks += 1 }
        config.accessMode = .invite; try config.validate(creating: false); checks += 1
        check(!ConferenceRoomConfiguration.validText(String(repeating: "👨‍👩‍👧‍👦", count: 40), scalars: 0...240, bytes: 960), "description uses Unicode scalars, not graphemes")
        let auth = AuthRepository()
        let api = ConferencesAPI(auth: auth)
        let roomID = UUID(uuidString: request.roomId)!, requestID = UUID(uuidString: request.id)!
        let credential = ConferenceCatalogCredential(roomID: roomID, requestID: requestID, mediaKey: payload.mediaKey)
        check(!String(describing: credential).contains(payload.mediaKey), "catalog credentials are redacted")
        let conferenceJSON: [String: Any] = ["id": request.roomId, "title": "Fixture", "owner_user_id": request.providerUserId!, "max_participants": 30, "state": "active", "created_at": "2027-01-15T08:00:00Z", "expires_at": "2027-01-15T12:00:00Z", "participants": []]
        let joinJSON: [String: Any] = ["conference": conferenceJSON, "livekit_url": "wss://fixture.invalid/rtc", "participant_token": "synthetic-not-live", "participant_identity": request.providerIdentity!, "token_expires_at": "2027-01-15T09:00:00Z", "e2ee_required": true]
        ClientProbe.reply = try JSONSerialization.data(withJSONObject: joinJSON)
        _ = try await api.joinCredential(.catalog(credential), supportsKeyHandoff: true)
        let catalogBody = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(catalogBody["key_request_id"] != nil && catalogBody["invite_secret"] == nil && catalogBody["media_key"] == nil && catalogBody["password"] == nil, "catalog join has one credential and never exports keys/secrets/password")
        check(catalogBody["supports_key_handoff"] as? Bool == true && catalogBody.count == 2, "new successful members explicitly opt in to key service")
        let invite = ConferenceInvite(server: origin, roomID: roomID, secret: ConferenceInvite.encode(Data(0..<32)), mediaKey: payload.mediaKey)
        _ = try await api.joinCredential(.invitation(invite, password: "  secret  "), supportsKeyHandoff: true)
        let invitationBody = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(invitationBody["password"] as? String == "  secret  " && invitationBody["invite_secret"] != nil && invitationBody["key_request_id"] == nil, "invitation/password join has exact one credential")
        ClientProbe.reply = try JSONSerialization.data(withJSONObject: conferenceJSON)
        _ = try await api.create(title: "Legacy", limit: 30, hash: "synthetic")
        let oldBody = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(oldBody.count == 3 && oldBody["access_mode"] == nil, "legacy create sends no unsupported fields")
        config = ConferenceRoomConfiguration(title: "Fixture", description: "Info", listed: true, accessMode: .password, passwordAction: .set, password: "  secret  ")
        _ = try await api.updateSettings(roomID, configuration: config, expectedRevision: 4)
        let patch = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(patch["expected_revision"] as? Int == 4 && patch["password_action"] as? String == "set" && patch["password"] as? String == "  secret  ", "atomic settings revision/password action uses exact contract")
        check(patch["media_key"] == nil && patch["invite_secret"] == nil, "access settings never include E2EE material")
        config.passwordAction = .keep; config.password = nil
        _ = try await api.updateSettings(roomID, configuration: config, expectedRevision: 5)
        let keep = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(keep["password"] == nil && keep["password_action"] as? String == "keep", "keeping a password never sends an empty replacement")
        config.approvalRequired = true
        _ = try await api.updateSettings(roomID, configuration: config, expectedRevision: 6)
        let gated = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(gated["approval_required"] as? Bool == true, "creator approval is serialized with atomic settings revision")
        config.approvalRequired = false
        _ = try await api.updateSettings(roomID, configuration: config, expectedRevision: 7)
        let ungated = try JSONSerialization.jsonObject(with: ClientProbe.lastBody!) as! [String: Any]
        check(ungated["approval_required"] as? Bool == false, "turning approval off is an explicit false, never omission")
        let old = try decoder.decode(ConferenceDTO.self, from: JSONSerialization.data(withJSONObject: conferenceJSON))
        check(old.listed == nil && old.accessMode == nil && old.settingsRevision == nil, "legacy metadata remains decodable")
        check(old.approvalRequired == nil, "old server metadata never enables approval implicitly")
        ClientProbe.reply = Data(#"{"items":[],"next_cursor":null}"#.utf8)
        _ = try await api.directory(query: "two words &?", cursor: "opaque+cursor")
        check(ClientProbe.lastPath == "/conferences/directory" && ClientProbe.lastQuery.contains(URLQueryItem(name: "q", value: "two words &?")), "directory search is an encoded query, never path interpolation")
        check(ConferencesAPI.accessError(APIError.server(code: "forbidden", message: "Conference password is required.")) as? ConferenceDirectoryFailure == .passwordRequired, "password error is distinct from session authentication")
        check(ConferencesAPI.accessError(APIError.server(code: "gone", message: "You were removed from this conference.")) as? ConferenceDirectoryFailure == .removed, "exact removal is not mislabeled as ended")
        check(!(ConferencesAPI.accessError(APIError.server(code: "gone", message: "Conference ended.")) is ConferenceDirectoryFailure), "ended rooms remain distinct")
        ClientProbe.beforeReply = { auth.sessionGeneration += 1 }
        do { _ = try await api.directory(query: "", cursor: nil); preconditionFailure("late cross-account response accepted") } catch { checks += 1 }
        print("Conference directory Foundation contracts: \(checks) passed; Apple SDK, Signal transport and native rendering NOT tested.")
    }
}
