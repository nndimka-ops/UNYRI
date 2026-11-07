import Foundation

var checks = 0
func check(_ value: @autoclosure () -> Bool, _ name: String) {
    checks += 1
    precondition(value(), name)
    print("PASS " + name)
}
let user = UUID(), device = UUID(), registration = UUID(), chat = UUID(), message = UUID(), call = UUID()
let now: Int64 = 1_800_000_000_000
func values(_ provider: UnyriPushProvider = .apns) -> [AnyHashable: Any] {
    var result: [AnyHashable: Any] = [
        "event": provider == .apns ? "new_message" : "incoming_call",
        "user_id": user.uuidString.lowercased(), "device_id": device.uuidString.lowercased(),
        "push_id": UUID().uuidString.lowercased(), "sent_at_ms": NSNumber(value: now - 1000),
        "expires_at_ms": NSNumber(value: now + 1000),
        "registration": ["provider": provider.rawValue, "id": registration.uuidString.lowercased(), "revision": NSNumber(value: Int64(1))]]
    if provider == .apns { result["chat_id"] = chat.uuidString.lowercased(); result["message_id"] = message.uuidString.lowercased() }
    else { result["call_id"] = call.uuidString.lowercased(); result["media_kind"] = "audio" }
    return result
}
func parsed(_ data: [AnyHashable: Any], _ provider: UnyriPushProvider = .apns) -> UnyriPushPayload? {
    UnyriPushPayload.parse(data, provider: provider, now: now)
}
check(parsed(values())?.messageID == message, "valid regular hint")
check(parsed(values(.voip), .voip)?.callID == call, "valid genuine VoIP hint")
for invalid: Any in [true, "1", NSNumber(value: Double(1)), NSNumber(value: UInt64.max), NSNumber(value: Int64(-1)), NSNumber(value: Int64(0))] {
    var data = values(); var r = data["registration"] as! [String: Any]; r["revision"] = invalid; data["registration"] = r
    check(parsed(data) == nil, "invalid revision type or range")
}
check(UnyriPushPayload.integer(NSNumber(value: Int64.max)) == Int64.max, "exact Int64 maximum without Double conversion")
for text in ["true", "1.0", "1e0", "9223372036854775808"] {
    let raw = try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
    check(UnyriPushPayload.integer(raw) == nil, "wire JSON Boolean float exponent or overflow rejected")
}
let maximum = try JSONSerialization.jsonObject(with: Data("9223372036854775807".utf8), options: [.fragmentsAllowed])
check(UnyriPushPayload.integer(maximum) == Int64.max, "wire JSON Int64 maximum stays exact")
var malformed = values(); malformed["user_id"] = user.uuidString.uppercased()
check(parsed(malformed) == nil, "noncanonical UUID rejected")
malformed = values(); malformed["sent_at_ms"] = true
check(parsed(malformed) == nil, "Boolean timestamp rejected")
malformed = values(); malformed["expires_at_ms"] = NSNumber(value: now - 1)
check(parsed(malformed) == nil, "expired hint rejected")
malformed = values(); malformed["sent_at_ms"] = NSNumber(value: now + 300_001)
check(parsed(malformed) == nil, "future-clock hint rejected")
malformed = values(); malformed["expires_at_ms"] = NSNumber(value: now + 86_400_000)
check(parsed(malformed) == nil, "TTL above event bound rejected")
malformed = values(.voip); malformed["event"] = "call_ended"
check(parsed(malformed, .voip) == nil, "VoIP cannot carry follow-up end")
malformed = values(.voip); malformed["call_id"] = "invalid"
check(parsed(malformed, .voip) == nil, "malformed VoIP never invents a call UUID")
malformed = values(.voip); malformed["media_kind"] = "file"
check(parsed(malformed, .voip) == nil, "VoIP media kind bounded")
malformed = values(); malformed["event"] = "open_url"; malformed["url"] = "https://untrusted.invalid"
check(parsed(malformed) == nil, "unknown event cannot supply an action URL")

var oldCall = values(.voip); oldCall["sent_at_ms"] = NSNumber(value: now - 10_000); oldCall["expires_at_ms"] = NSNumber(value: now - 1)
check(parsed(oldCall, .voip) == nil && UnyriPushPayload.reportableVoIP(oldCall)?.callID == call,
      "expired genuine UUID is reportable but never recovery-admitted")
var futureCall = values(.voip); futureCall["sent_at_ms"] = NSNumber(value: now + 300_001); futureCall["expires_at_ms"] = NSNumber(value: now + 310_000)
check(parsed(futureCall, .voip) == nil && UnyriPushPayload.reportableVoIP(futureCall)?.callID == call,
      "future-clock genuine UUID is reportable but never recovery-admitted")
var badCall = oldCall; badCall["call_id"] = "malformed"
check(UnyriPushPayload.reportableVoIP(badCall) == nil, "malformed UUID is never manufactured for mandatory report")
check(UnyriPushPayload.reportableVoIP(values()) == nil, "regular message cannot become a CallKit report")

let root = FileManager.default.temporaryDirectory.appendingPathComponent("UP-" + UUID().uuidString.prefix(8))
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
let file = root.appendingPathComponent("intent.json")
let scope = UnyriPushScope(origin: "https://server.example", user: user, device: device)
let context = UnyriPushContext(scope: scope, provider: .apns)
let otherOrigin = UnyriPushContext(scope: UnyriPushScope(origin: "https://other.example", user: user, device: device), provider: .apns)
let voip = UnyriPushContext(scope: scope, provider: .voip)
var failWrite = false
let ledger = try UnyriPushIntentLedger(file: file) { data, target in
    if failWrite { throw CocoaError(.fileWriteNoPermission) }
    try data.write(to: target, options: .atomic)
}
let first = try ledger.advance(context)
check(first.revision == 1 && first.active, "persist first admission before HTTP")
let bytes = try Data(contentsOf: file)
let loaded = try UnyriPushIntentLedger(file: file)
check(loaded.binding(for: context) == first, "binding metadata survives cold load")
check(!String(decoding: bytes, as: UTF8.self).contains("token"), "ledger contains no push token")
failWrite = true
do { _ = try ledger.advance(context); preconditionFailure("write failure must throw") } catch {}
let bytesAfterFailure = try Data(contentsOf: file)
check(ledger.binding(for: context) == first && bytesAfterFailure == bytes, "atomic failure preserves memory and old bytes")
failWrite = false
let next = try ledger.advance(context)
check(next.revision == 2 && next.id != first.id, "new desired intent increments revision")
try ledger.retire(first)
check(ledger.binding(for: context) == next, "old cleanup cannot retire new intent")
let rebased = try ledger.advance(context, after: 900)
check(rebased.revision == 901 && rebased.id != next.id, "stale conflict rebase persists new intent")
let beforeOverflow = try Data(contentsOf: file)
do { _ = try ledger.advance(context, after: Int64.max); preconditionFailure("overflow must throw") } catch {}
let bytesAfterOverflow = try Data(contentsOf: file)
check(ledger.binding(for: context) == rebased && bytesAfterOverflow == beforeOverflow, "overflow cannot wrap or alter ledger")
let other = try ledger.advance(otherOrigin); let callBinding = try ledger.advance(voip)
check(other.revision == 1 && callBinding.revision == 1, "origin and role revision namespaces separate")
try ledger.retire(rebased)
check(ledger.binding(for: context)?.active == false && ledger.binding(for: voip)?.active == true, "regular retirement never disables calls")
try ledger.retire(scope: scope)
check(ledger.binding(for: voip)?.active == false && ledger.binding(for: otherOrigin)?.active == true, "logout only retires exact owner origin scope")
let valid = parsed(values())!
check(ledger.matches(valid, origin: scope.origin) == nil, "retired or foreign registration cannot be adopted")
try Data("corrupt ledger".utf8).write(to: root.appendingPathComponent("corrupt.json"))
do { _ = try UnyriPushIntentLedger(file: root.appendingPathComponent("corrupt.json")); preconditionFailure("corruption must fail closed") } catch {}
let corruptBytesAfterLoad = try Data(contentsOf: root.appendingPathComponent("corrupt.json"))
check(corruptBytesAfterLoad == Data("corrupt ledger".utf8), "corrupt bytes retained rather than resetting counters")
print("PUSH_CONTRACTS \(checks) PASS")
