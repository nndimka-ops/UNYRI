import Foundation

// Foundation-only contracts for CALL_RINGING_SPEC §§1, 4 and 7.2.
// Server-only serialization/floor races are represented by the client-observable
// rows/events; this runner never contacts a backend or pretends to test SQL.
typealias RingPolicy = CallRingingIndicatorPolicy
typealias RingEvent = RingPolicy.Event
typealias RingPhase = CallRingIndicator.Phase
var checks = 0
var failures = 0
func check(_ value: @autoclosure () -> Bool, _ label: String) {
    checks += 1
    if !value() { failures += 1; print("FAIL: \(label)") }
}
func row(_ peer: Bool? = false, _ expected: Bool? = true, _ after: Bool = true,
         state: String = "ringing") -> RingEvent {
    .serverRow(state: state, peerRinging: peer, expected: expected, afterDeadline: after)
}
func reduce(_ value: CallRingIndicator?, _ event: RingEvent) -> CallRingIndicator? {
    RingPolicy.reduce(value, event).0
}
func trace(_ events: [RingEvent], from initial: CallRingIndicator? = nil) -> CallRingIndicator? {
    events.reduce(initial) { reduce($0, $1) }
}
let sending = CallRingIndicator()
let expected = CallRingIndicator(expect: true, created: true)
let legacy = CallRingIndicator(created: true)
let offlinePeer = CallRingIndicator(phase: .notOnline, expect: true, created: true)
let ringing = CallRingIndicator(phase: .ringing, expect: true, created: true, rang: true)
let events: [RingEvent] = [.started, .created(expect: true), .created(expect: false),
    .createFailed(busy: true), .createFailed(busy: false), .peerRinging,
    row(), row(nil, nil), row(false, false), row(true, false), row(true, true), row(false, true, false),
    row(false, nil, false), row(state: "accepted"), row(state: "ended"), .accepted,
    .ended(reason: "rejected"), .ended(reason: "hangup"), .ended(reason: "missed"),
    .ended(reason: "cancelled"), .ended(reason: "failed"), .ended(reason: "timeout"),
    .ended(reason: "device_revoked"), .ended(reason: "unknown"), .cancelledHere]

// Each ordered transition row, including ignored events and context updates.
check(reduce(nil, .started) == sending, "none + started -> sending")
check(reduce(nil, .created(expect: true)) == expected, "none + created binds expectation")
check(reduce(sending, .created(expect: true)) == expected, "sending + created binds expectation")
check(reduce(expected, .created(expect: false)) == expected, "created is applied only once")
check(reduce(legacy, .created(expect: true)) == legacy, "legacy never upgrades expectation")
check(reduce(sending, .createFailed(busy: true))?.phase == .busy, "busy create failure")
check(reduce(sending, .createFailed(busy: false))?.phase == .failed, "other create failure")
check(reduce(expected, .createFailed(busy: true)) == expected, "late create failure ignored")
for origin in [expected, offlinePeer] {
    let byEvent = RingPolicy.reduce(origin, .peerRinging)
    let byRow = RingPolicy.reduce(origin, row(true))
    check(byEvent.0?.phase == .ringing && byEvent.0?.rang == true, "event reaches ringing from \(origin.phase)")
    check(byRow.0?.phase == .ringing && byRow.1 == [.haptic, .announce("Ringing…")], "row reaches ringing with one output")
    check(byEvent.1 == [.haptic, .announce("Ringing…")], "one haptic and announcement")
}
check(RingPolicy.reduce(ringing, .peerRinging).1.isEmpty, "duplicate event has no outputs")
check(RingPolicy.reduce(ringing, row(true)).1.isEmpty, "duplicate positive row has no outputs")
check(reduce(offlinePeer, row(false, false))?.phase == .sending, "privacy revoke clears notOnline")
for origin in [expected, offlinePeer, ringing] {
    check(reduce(origin, row(false, false))?.expect == false, "false row revokes expectation")
}
check(reduce(ringing, row(false, false))?.phase == .ringing, "privacy revoke keeps ringing monotonic")
check(reduce(expected, row())?.phase == .notOnline, "late authoritative negative reaches notOnline")
check(reduce(expected, row(nil, nil))?.phase == .notOnline, "missing row fields follow ordered table")
check(reduce(sending, row())?.phase == .sending, "uncreated call never reaches notOnline")
check(reduce(expected, row(false, true, false)) == expected, "early issued GET never reaches notOnline")
check(reduce(legacy, row()) == legacy, "legacy never reaches notOnline")
check(reduce(offlinePeer, row()) == offlinePeer, "repeated negative row leaves notOnline")
check(reduce(ringing, row()) == ringing, "reordered negative row leaves ringing")
check(reduce(expected, row(true, false))?.phase == .ringing, "positive row wins ordered table")
for origin in [sending, expected, offlinePeer, ringing] {
    check(reduce(origin, row(state: "accepted")) == origin, "nonringing row waits for reconciliation")
    check(reduce(origin, row(state: "ended")) == origin, "ended row waits for reconciliation")
    check(reduce(origin, .accepted)?.phase == .answered, "accepted reaches answered")
    check(reduce(origin, .cancelledHere)?.phase == .cancelled, "local cancel reaches cancelled")
    let endMappings: [(String, RingPhase)] = [("rejected", .declined), ("hangup", .declined),
        ("missed", .noAnswer), ("cancelled", .cancelled), ("failed", .failed),
        ("timeout", .failed), ("device_revoked", .failed), ("unknown", .failed)]
    for (reason, phase) in endMappings {
        check(reduce(origin, .ended(reason: reason))?.phase == phase, "end \(reason) maps to \(phase)")
    }
}
for phase in RingPhase.allCases where phase.isTerminal {
    let terminal = CallRingIndicator(phase: phase, expect: true, created: true, rang: true)
    for event in events {
        let result = RingPolicy.reduce(terminal, event)
        check(result.0 == terminal && result.1.isEmpty, "terminal \(phase) absorbs \(event)")
    }
}
for phase in RingPhase.allCases {
    for created in [false, true] {
        for expect in [false, true] {
            let value = CallRingIndicator(phase: phase, expect: expect, created: created, rang: phase == .ringing)
            check(value.ringbackAllowed == (phase == .ringing || (phase == .sending && created && !expect)), "ringback derived for \(phase)")
            check(value.awaitsConfirmation == ((phase == .sending || phase == .notOnline) && created && expect), "polling derived for \(phase)")
        }
    }
}
for origin in [sending, expected, legacy, offlinePeer, ringing] {
    for event in events {
        guard let result = reduce(origin, event) else { continue }
        if origin.phase == .ringing { check(result.phase != .sending && result.phase != .notOnline, "ringing never regresses") }
        if origin.created && !origin.expect { check(!result.expect, "bound expectation never upgrades") }
        if result.phase == .notOnline && origin.phase != .notOnline {
            check(origin.created && origin.expect && event == row() ||
                  origin.created && origin.expect && event == row(nil, nil), "notOnline needs late row")
        }
        check(RingPolicy.reduce(result, event).1.isEmpty, "repeated event emits no duplicate output")
    }
}
check(RingPolicy.reduce(offlinePeer, .peerRinging).1 == [.haptic, .announce("Ringing…")], "late first confirmation ticks once")
check(RingPolicy.reduce(CallRingIndicator(phase: .notOnline, expect: true, created: true, rang: true), .peerRinging).1 == [.announce("Ringing…")], "a prior ring never repeats haptic")
check(RingPolicy.reduce(expected, row()).1 == [.announce("Not online yet. Waiting for them to come online")], "notOnline announcement")
let transitionOutputs: [(RingEvent, [RingPolicy.Output])] = [(.accepted, []), (.cancelledHere, []),
    (.ended(reason: "rejected"), [.announce("Declined")]), (.ended(reason: "missed"), [.announce("No answer")]),
    (.ended(reason: "failed"), [.announce("Failed")])]
for (event, outputs) in transitionOutputs {
    check(RingPolicy.reduce(expected, event).1 == outputs, "transition output \(event)")
}

let callID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
let owner = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
let peer = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!
let incoming = CallInfo(callId: callID.uuidString.lowercased(), remoteUserId: peer.uuidString.lowercased(),
                        remoteDisplayName: "Peer", mediaKind: .audio, direction: .incoming)
let outgoing = CallInfo(callId: incoming.callId, remoteUserId: incoming.remoteUserId,
                        remoteDisplayName: "Peer", mediaKind: .audio, direction: .outgoing)
func canConfirm(_ ui: CallUiState, _ report: CallRingConfirmPolicy.Report, sent: Bool = false,
                id: String = incoming.callId) -> Bool {
    CallRingConfirmPolicy.shouldSend(ui: ui, callId: id, report: report, alreadySent: sent)
}
check(!canConfirm(.incomingRinging(incoming), .pending), "admission before report waits")
check(canConfirm(.incomingRinging(incoming), .succeeded), "admission then successful report sends")
check(!canConfirm(.idle, .succeeded), "report before admission waits")
check(canConfirm(.incomingRinging(incoming), .succeeded), "successful report then admission sends")
check(!canConfirm(.incomingRinging(incoming), .failed), "DND/blocklist/error never sends")
check(!canConfirm(.incomingRinging(incoming), .succeeded, sent: true), "deduplicated call never sends twice")
check(!canConfirm(.incomingRinging(incoming), .succeeded, id: "foreign"), "different report id never sends")
for ui in [CallUiState.idle, .outgoingRinging(outgoing), .connecting(incoming),
           .connected(ConnectedState(call: incoming)), .ended(incoming, reason: .rejected), .failed(incoming, reason: .busy)] {
    check(!canConfirm(ui, .succeeded), "only incoming-ringing admits confirmation")
}
for failure in [CallRingConfirmPolicy.Failure.transport, .http(500), .http(503), .http(599)] {
    check(CallRingConfirmPolicy.retry(after: failure, attempt: 1, stillRinging: true), "retry eligible \(failure)")
    check(!CallRingConfirmPolicy.retry(after: failure, attempt: 2, stillRinging: true), "second failure final")
    check(!CallRingConfirmPolicy.retry(after: failure, attempt: 1, stillRinging: false), "ended/accepted cancels retry")
}
for code in 200...499 {
    check(!CallRingConfirmPolicy.retry(after: .http(code), attempt: 1, stillRinging: true), "HTTP \(code) final")
}
check(!CallRingConfirmPolicy.retry(after: .other, attempt: 1, stillRinging: true), "unknown/local cancellation final")
check(CallRingConfirmPolicy.retryDelay == 1 && CallRingConfirmPolicy.requestTimeout == 5, "bounded retry timing")
check(RingPolicy.confirmDeadline == 6 && RingPolicy.recheckInterval == 5, "caller timing")

func capsule(_ phase: CallStatusPhase?, ring: RingPhase?, offline: Bool = false) -> CallStatusCapsuleKind {
    CallStatusCapsulePolicy.kind(phase: phase, ring: ring, reconnectingShown: false,
        waitingForNetwork: false, offline: offline, endedDuration: nil)
}
for phase in [CallStatusPhase.starting, .calling] {
    check(capsule(phase, ring: .sending).titleKey == "Calling…", "sending title")
    check(capsule(phase, ring: .ringing).titleKey == "Ringing…", "ringing title")
    check(capsule(phase, ring: .notOnline).titleKey == "Not online yet", "notOnline title")
    check(capsule(phase, ring: .notOnline, offline: true) == .waitingForNetwork(inCall: false), "own offline beats peer status")
}
check(CallStatusCapsulePolicy.subtitleKey(kind: .peerNotOnline, routeIsRelay: nil, privateRelay: false,
    caption: nil, weakExpired: false) == "Waiting for them to come online", "notOnline subtitle")
for phase in RingPhase.allCases where phase.isTerminal && phase != .answered {
    check(capsule(.ended(.missed), ring: phase).titleKey == phase.titleKey, "outgoing terminal title \(phase)")
}
check(capsule(.ended(.missed), ring: nil).titleKey == "Missed", "incoming missed unchanged")
check(capsule(.connecting, ring: .answered).titleKey == "Connecting call…", "answered does not claim encryption")
check(capsule(.connected, ring: .answered).titleKey == "Encrypted", "media connected is encrypted")
for value in [sending, expected, legacy, offlinePeer, ringing, CallRingIndicator(phase: .answered)] {
    check(CallRingbackPolicy.shouldPlay(.outgoingRinging(outgoing), audioActive: true, ringbackAllowed: value.ringbackAllowed) == value.ringbackAllowed, "ringback follows indicator")
    check(!CallRingbackPolicy.shouldPlay(.outgoingRinging(outgoing), audioActive: false, ringbackAllowed: value.ringbackAllowed), "inactive CallKit audio is silent")
    check(!CallRingbackPolicy.shouldPlay(.connecting(outgoing), audioActive: true, ringbackAllowed: value.ringbackAllowed), "answered stops ringback")
}

// DTO compatibility and signaling wire shape.
var wire: [String: Any] = ["id": callID.uuidString, "caller_user_id": owner.uuidString,
    "callee_user_id": peer.uuidString, "media_kind": "audio", "state": "ringing", "created_at": 0]
let decoder = JSONDecoder()
decoder.keyDecodingStrategy = .convertFromSnakeCase
decoder.dateDecodingStrategy = .secondsSince1970
func decodeRow() -> CallDTO { try! decoder.decode(CallDTO.self, from: JSONSerialization.data(withJSONObject: wire)) }
let oldRow = decodeRow()
check(oldRow.peerRingingExpected == nil && oldRow.peerRinging == nil, "old server fields absent")
wire["peer_ringing_expected"] = true; wire["peer_ringing"] = false
let newRow = decodeRow()
check(newRow.peerRingingExpected == true && newRow.peerRinging == false, "new server fields decode")
wire["peer_ringing"] = true
check(decodeRow().peerRinging == true, "positive GET decodes")
check(CallSignalingEventParser.parse(Data("{\"type\":\"call.peer_ringing\",\"call_id\":\"\(incoming.callId)\"}".utf8)) == .peerRinging(callId: incoming.callId), "peer-ringing event parses")
check(CallSignalingEventParser.parse(Data("{\"type\":\"call.peer_ringing\"}".utf8)) == nil, "missing id ignored")
check(CallSignalingEventParser.parse(Data("{\"type\":\"call.future\",\"call_id\":\"x\"}".utf8)) == nil, "unknown event ignored")

// Caller reads of its ringing call (CallOutgoingReadPolicy, spec §3.2).
typealias Reads = CallOutgoingReadPolicy
func due(_ read: Reads.Read, _ at: TimeInterval) -> Reads.Due { Reads.Due(read: read, at: at) }
/// Runs the timer as the controller does: each due read is issued at its time.
func runTimer(_ reads: inout Reads, awaiting: Bool, until end: TimeInterval) -> [Reads.Due] {
    var issued: [Reads.Due] = []
    while let next = reads.next(awaitingConfirmation: awaiting), next.at <= end {
        reads.issued(next.read, at: next.at)
        issued.append(next)
    }
    return issued
}
check(Reads.pollBudget == 8 && Reads.backstopAfter == 46, "read budget and backstop timing")
var unconfirmed = Reads(createdAt: 100)
check(unconfirmed.deadline == 106, "deadline is created + 6 s")
check(unconfirmed.next(awaitingConfirmation: true) == due(.check, 106), "first timed read is the deadline check")
unconfirmed.issued(.created, at: 100.2)
let timed = runTimer(&unconfirmed, awaiting: true, until: 200)
check(timed.map(\.at) == [106, 111, 116, 121, 126, 131, 136, 146], "deadline, six re-checks, then the backstop")
check(timed.last?.read == .backstop && timed.dropLast().allSatisfy { $0.read == .check }, "only the last one is the backstop")
check(unconfirmed.polls == Reads.pollBudget, "post-create read and checks share the budget")
check(unconfirmed.next(awaitingConfirmation: true) == nil, "nothing is left after the backstop")
// Finding: a lost call.ended while this device was offline at 130-150 s.
// The checks spent the budget (failed reads count: polling stays bounded),
// yet the reconnect read still goes out and is never budgeted.
var offline = Reads(createdAt: 100)
offline.issued(.created, at: 100)
_ = runTimer(&offline, awaiting: true, until: 140)
check(offline.polls == Reads.pollBudget && offline.next(awaitingConfirmation: true) == due(.backstop, 146),
      "spent budget: the backstop is still due")
offline.issued(.backstop, at: 146)
offline.issued(.refresh, at: 150)
check(offline.polls == Reads.pollBudget && offline.lastReadAt == 150, "a reconnect read is issued outside the budget")
check(offline.next(awaitingConfirmation: true) == nil, "a reconnect read never re-arms polling")
check(reduce(offlinePeer, .ended(reason: "missed"))?.phase == .noAnswer, "its ended row reaches noAnswer")
// Finding: a reconnect read before the deadline is not suppressed, and it
// never moves the deadline check nor shows notOnline.
var early = Reads(createdAt: 100)
early.issued(.created, at: 100)
early.issued(.refresh, at: 102)
check(early.polls == 1, "a reconnect read before the deadline is outside the budget")
check(early.next(awaitingConfirmation: true) == due(.check, 106), "the deadline check stays at 6 s")
check(102 < early.deadline && reduce(expected, row(false, true, false)) == expected, "its row cannot show notOnline")
check(trace([.created(expect: true), .accepted]).map { $0.phase } == .answered, "its accepted row answers at once")
// A read after the deadline spaces the next check from it.
var spaced = Reads(createdAt: 100)
spaced.issued(.created, at: 100)
spaced.issued(.check, at: 106)
spaced.issued(.refresh, at: 108)
check(spaced.next(awaitingConfirmation: true) == due(.check, 113), "a re-check follows the last read by 5 s")
spaced.issued(.refresh, at: 143)
check(spaced.next(awaitingConfirmation: true) == due(.backstop, 146), "the backstop comes before a later check")
spaced.issued(.backstop, at: 146)
check(spaced.next(awaitingConfirmation: true) == due(.check, 151), "checks left in the budget follow it")
// Confirmed ringing, or a legacy call: no checks, the backstop only.
var confirmed = Reads(createdAt: 100)
confirmed.issued(.created, at: 100)
check(runTimer(&confirmed, awaiting: false, until: 200) == [due(.backstop, 146)], "confirmed or legacy: one backstop read")
var late = Reads(createdAt: 100)
late.issued(.created, at: 100)
check(runTimer(&late, awaiting: true, until: 110) == [due(.check, 106)], "awaiting until 110 s: the deadline check")
check(runTimer(&late, awaiting: false, until: 200) == [due(.backstop, 146)], "confirmed after it: the backstop alone")

// Spec race table: each numbered case checks its client-observable result.
check(trace([.created(expect: true), row(true, true, false)])?.phase == .ringing, "race 1: post-create GET recovers pre-bind event")
check(trace([.created(expect: true), .accepted, .peerRinging])?.phase == .answered, "race 2: confirmation after accept ignored")
let acceptanceOrders: [[RingEvent]] = [[.peerRinging, .accepted], [.accepted, .peerRinging]]
for tail in acceptanceOrders {
    check(trace([.created(expect: true)] + tail)?.phase == .answered, "race 3: both event/accept orders end answered")
}
for reason in ["rejected", "cancelled", "missed"] {
    let before = trace([.created(expect: true), .ended(reason: reason)])
    check(reduce(before, .peerRinging) == before, "race 4: confirmation after \(reason) ignored")
}
check(RingPolicy.reduce(ringing, .peerRinging).1.isEmpty, "race 5: duplicate confirmation has no second output")
check(trace([.created(expect: true), .peerRinging, .peerRinging, row(true)]) == ringing, "race 6: multiple devices collapse to one bit")
check(trace([.created(expect: true), .cancelledHere, .peerRinging])?.phase == .cancelled, "race 7: locally ended absorbs late event")
check(trace([.created(expect: true), row(), row(true)])?.phase == .ringing, "race 8: lost event recovered by later GET")
check(trace([.created(expect: true), .peerRinging, row()])?.phase == .ringing, "race 9: negative GET after event cannot regress")
check(expected.phase == .sending && capsule(.calling, ring: .sending, offline: true).titleKey == "Waiting for network…", "race 10: failed GET emits no event, own-network caption wins")
check(reduce(nil, row(true)) == nil, "race 11: no outgoing call restored after caller relaunch")
check(canConfirm(.incomingRinging(incoming), .succeeded), "race 12: fresh callee controller may confirm recovered call")
check(trace([.started, .createFailed(busy: true)])?.phase == .busy, "race 13: create busy never rings")
check(!canConfirm(.ended(incoming, reason: .rejected), .succeeded) && reduce(expected, .ended(reason: "rejected"))?.phase == .declined, "race 14: room rejection never confirms")
check(!canConfirm(.incomingRinging(incoming), .failed) && reduce(expected, row())?.phase == .notOnline, "race 15: DND/blocklist report does not confirm")
check(trace([.created(expect: true), row(), .peerRinging])?.phase == .ringing, "race 16: cold late confirmation recovers notOnline")
let missed = reduce(offlinePeer, .ended(reason: "missed"))!
check(missed.phase == .noAnswer && !missed.ringbackAllowed && !missed.awaitsConfirmation, "race 17: timeout stops sound and GETs")
check(reduce(expected, .ended(reason: "hangup"))?.phase == .declined, "race 18: block is indistinguishable from decline")
check(reduce(ringing, .ended(reason: "device_revoked"))?.phase == .failed && !CallRingConfirmPolicy.retry(after: .http(401), attempt: 1, stillRinging: true), "race 19: account revoke fails, revoked session never retries")
check(!CallRingConfirmPolicy.retry(after: .http(404), attempt: 1, stillRinging: true), "race 20: installation block final 404")
check(reduce(legacy, row()) == legacy && reduce(offlinePeer, row(false, false))?.phase == .sending, "race 21: hidden presence legacy before, revoke during")
check(reduce(offlinePeer, row()) == offlinePeer, "race 22: unreachable old build may ring with no acknowledgement")
check(legacy.ringbackAllowed && reduce(legacy, row())?.phase == .sending, "race 23: reachable old build stays legacy")
check(reduce(offlinePeer, row(false, false))?.ringbackAllowed == true, "race 24: kill switch resumes legacy")
check(trace([.started, .createFailed(busy: true)])?.phase == .busy, "race 25: losing simultaneous caller sees busy")

print("Call ringing contracts: \(checks) checks, \(failures) failures")
if failures != 0 { fatalError("Call ringing contracts failed") }
