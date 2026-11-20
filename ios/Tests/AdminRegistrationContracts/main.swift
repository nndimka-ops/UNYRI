import Foundation

@MainActor
private enum Checks { static var count = 0 }
@MainActor
private func check(_ condition: @autoclosure () -> Bool, _ name: String) {
    Checks.count += 1
    if !condition() { fatalError("Admin registration contract failed: " + name) }
}
private func decode<T: Decodable>(_ json: String, as: T.Type = T.self) throws -> T {
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(T.self, from: Data(json.utf8))
}
private enum FixtureFailure: Error { case rejected }
@MainActor
private final class Recorder {
    var calls: [(String, [String: Any]?)] = []
    var reply = "{}"
    var failure: FixtureFailure?
    func request(_ path: String, _ body: [String: Any]?) async throws -> SelfHostingAdminReply {
        calls.append((path, body))
        if let failure { throw failure }
        return try decode(reply)
    }
}

@main
private struct Contracts {
    @MainActor
    static func main() async throws {
        let id = "12345678-1234-1234-1234-123456789abc"
        let secondID = "12345678-1234-1234-1234-123456789abd"
        let expiry = "2026-11-26T12:00:00.123456Z"
        let row = "{\"id\":\"\(id)\",\"status\":\"active\",\"max_uses\":1,\"uses\":0,\"expires_at\":\"\(expiry)\"}"
        let creation = "{\"id\":\"\(id)\",\"code\":\"\(id).opaque_secret\",\"max_uses\":1,\"uses\":0,\"expires_at\":\"\(expiry)\"}"
        check(AdminInvitationPolicy.defaultUses == "1" && AdminInvitationPolicy.defaultDays == "7", "form defaults")
        check(AdminInvitationPolicy.limits(uses: "1", days: "7")?.seconds == 604800, "days map to seconds")
        check(AdminInvitationPolicy.limits(uses: "10000", days: "365")?.seconds == 31536000, "server upper bounds")
        for (uses, days) in [("0","7"),("10001","7"),("1","0"),("1","366"),("-1","7"),("+1","7"),("1 ","7"),("1","1.5"),("١","7"),(String(repeating:"9",count:100),"7")] {
            check(AdminInvitationPolicy.limits(uses: uses, days: days) == nil, "reject malformed or out-of-bounds limits")
        }
        check(AdminInvitationPolicy.expiry(expiry) != nil, "Rust fractional timestamp")
        check(AdminInvitationPolicy.expiry("2026-11-26T12:00:00Z") != nil, "whole-second timestamp")
        check(AdminInvitationPolicy.expiry("invalid") == nil, "invalid timestamp")
        for (status, key) in [("active","Active invitation"),("revoked","Revoked invitation"),("expired","Expired invitation"),("exhausted","Used invitation"),("future","Invitation")] {
            let item: SelfHostingInvite = try decode(row.replacingOccurrences(of:"active",with:status))
            check(item.statusKey == key && item.maxUses == 1 && item.uses == 0, "list status and snake-case DTO")
        }
        let page: SelfHostingAdminReply = try decode("{\"items\":[\(row)],\"next_before\":\"\(id)\",\"future_field\":true}")
        check(AdminInvitationPolicy.validPage(page), "next_before wire mapping")
        check(!AdminInvitationPolicy.validPage(page, after: id), "non-advancing cursor")
        let badPage: SelfHostingAdminReply = try decode("{\"items\":[\(row)],\"next_before\":\"\(secondID)\"}")
        check(!AdminInvitationPolicy.validPage(badPage), "cursor identifies last row")
        let duplicate: SelfHostingAdminReply = try decode("{\"items\":[\(row),\(row)],\"next_before\":null}")
        check(!AdminInvitationPolicy.validPage(duplicate), "duplicate IDs")
        let missing: SelfHostingAdminReply = try decode("{}")
        check(!AdminInvitationPolicy.validPage(missing), "omitted items never silently empty")
        let empty: SelfHostingAdminReply = try decode("{\"items\":[],\"next_before\":null}")
        check(AdminInvitationPolicy.validPage(empty), "empty page")
        let overused: SelfHostingAdminReply = try decode("{\"items\":[\(row.replacingOccurrences(of:"\"uses\":0",with:"\"uses\":2"))]}")
        check(!AdminInvitationPolicy.validPage(overused), "uses cannot exceed capacity")
        var issued = AdminIssuedInvitationCodes()
        for index in 0...100 { issued.store("code" + String(index), for: String(index)) }
        check(issued["0"] == nil && issued["1"] == "code1" && issued["100"] == "code100", "bounded foreground-only code retention")
        issued.remove("1")
        check(issued["1"] == nil, "revocation forgets locally issued code")
        issued.clear()
        check(issued["100"] == nil, "leaving the screen clears issued codes")

        // The exact production callback and methods are compiled, not a second client.
        for role in ["owner", "moderator"] {
            let recorder = Recorder()
            let client = AdminRegistrationClient(role: role, request: recorder.request)
            recorder.reply = creation
            let created = try await client.create()
            check(created.code == id + ".opaque_secret", "creation secret mapping")
            check(recorder.calls.last?.0 == "invites", "create route")
            check(recorder.calls.last?.1?["max_uses"] as? Int == 1, "default uses payload")
            check(recorder.calls.last?.1?["expires_in_seconds"] as? Int == 604800, "default expiry payload")
            let link = SelfHostingPolicy.inviteLink(origin: "https://example.test", code: created.code!)
            check(link != nil && SelfHostingPolicy.parseInvite(link!)?.code == created.code, "existing join-flow share round trip")
            check(SelfHostingPolicy.parseInvite(link!)?.origin == "https://example.test", "server-scoped share")
            recorder.reply = "{\"items\":[\(row)],\"next_before\":null}"
            _ = try await client.invitations()
            check(recorder.calls.last?.0 == "invites" && recorder.calls.last?.1 == nil, "list GET")
            _ = try await client.invitations(before: secondID)
            check(recorder.calls.last?.0 == "invites?before=" + secondID, "UUID before cursor, not admin next_cursor")
            recorder.reply = "{\"ok\":true}"
            try await client.revoke(id)
            check(recorder.calls.last?.0 == "invites/" + id + "/revoke" && recorder.calls.last?.1?.isEmpty == true, "revoke POST empty object")
            let before = recorder.calls.count
            do { _ = try await client.invitations(before: "x&secret=leak"); fatalError("accepted unsafe cursor") }
            catch AdminRegistrationError.invalidLimits { }
            do { _ = try await client.create(uses:"0"); fatalError("accepted invalid uses") }
            catch AdminRegistrationError.invalidLimits { }
            do { try await client.revoke("../registration"); fatalError("accepted unsafe ID") }
            catch AdminRegistrationError.invalidLimits { }
            check(recorder.calls.count == before, "invalid inputs never issue a request")
            recorder.failure = .rejected
            do { _ = try await client.invitations(); fatalError("lost transport failure") }
            catch FixtureFailure.rejected { }
            recorder.failure = nil
            if role == "owner" {
                recorder.reply = "{\"registration_mode\":\"invite_only\",\"registration_open\":false}"
                let policy = try await client.registration()
                check(policy.registrationMode == "invite_only" && policy.registrationOpen == false, "mode and separate operator closure")
                check(recorder.calls.last?.0 == "registration" && recorder.calls.last?.1 == nil, "owner mode GET")
                recorder.reply = "{\"ok\":true}"
                try await client.saveMode("open")
                check(recorder.calls.last?.1?["registration_mode"] as? String == "open", "owner mode POST")
                recorder.reply = "{\"ok\":false}"
                do { try await client.saveMode("invite_only"); fatalError("accepted rejected mode save") }
                catch AdminRegistrationError.invalidReply { }
            } else {
                let callCount = recorder.calls.count
                do { try await client.saveMode("open"); fatalError("moderator edited mode") }
                catch AdminRegistrationError.forbidden { }
                do { _ = try await client.registration(); fatalError("moderator called owner-only admin route") }
                catch AdminRegistrationError.forbidden { }
                check(recorder.calls.count == callCount, "moderator read-only boundary")
                let policy: RegistrationPolicy = try decode("{\"registration_mode\":\"invite_only\",\"registration_open\":true,\"invite_required\":true}")
                check(policy.registrationMode == "invite_only" && policy.registrationOpen == true && policy.requiresInvite, "real public server-info model for moderator")
            }
        }
        let refused = Recorder()
        let client = AdminRegistrationClient(role:"user",request:refused.request)
        do { _ = try await client.create(); fatalError("non-admin created invite") }
        catch AdminRegistrationError.forbidden { }
        check(refused.calls.isEmpty, "non-admin blocked before transport")
        let malformed = Recorder()
        malformed.reply = creation.replacingOccurrences(of:id + ".opaque_secret",with:secondID + ".opaque_secret")
        do { _ = try await AdminRegistrationClient(role:"owner",request:malformed.request).create(); fatalError("mismatched creation ID") }
        catch AdminRegistrationError.invalidReply { }
        check(AdminRequestFailurePolicy.messageKey(status:429) == "Too many attempts. Wait 5 minutes before trying again.", "existing throttle message")
        check(AdminRequestFailurePolicy.messageKey(status:429,invitationOperation:true) == SelfHostingPolicy.refusalKey("rate_limited"), "invitation throttle does not invent a five-minute retry")
        check(AdminRequestFailurePolicy.messageKey(status:503) == "The server could not complete the request. Contact the server owner.", "existing server error")
        check(AdminRequestFailurePolicy.messageKey(status:nil,networkFailure:true) != nil, "existing secure transport error")
        print("AdminRegistrationContracts: \(Checks.count) checks passed")
    }
}
