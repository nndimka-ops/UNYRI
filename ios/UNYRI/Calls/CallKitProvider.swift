import Foundation

/// Report ownership only, not a call/media state machine. Tickets make late
/// platform completions incapable of mutating another account or call owner.
struct UnyriPushCallBook {
    struct Key: Equatable {
        let scope: UnyriPushScope
        let registration: UUID?
        let revision: Int64
        let call: UUID
    }
    struct Ticket: Equatable { let key: Key; let nonce: UUID }
    enum End: Equatable { case failed, remoteEnded, declined, unanswered, answeredElsewhere }
    private struct Entry {
        let ticket: Ticket
        var pending = true
        var reported = false
        var end: End?
        var endSent = false
        var rejected = false
        var generation: UInt64?
        var rejectTaken = false
    }
    private var entries: [UUID: Entry] = [:]
    private var terminals: [Key] = []
    var hasReports: Bool { !entries.isEmpty }

    func ticket(_ call: UUID) -> Ticket? { entries[call]?.ticket }
    func terminal(_ key: Key) -> Bool { terminals.contains(key) || entries[key.call]?.end != nil }
    mutating func begin(_ key: Key) -> Ticket? {
        guard !terminal(key), entries[key.call] == nil, entries.count < 8 else { return nil }
        let ticket = Ticket(key: key, nonce: UUID()); entries[key.call] = Entry(ticket: ticket)
        return ticket
    }
    mutating func reportFinished(_ ticket: Ticket, success: Bool) -> End? {
        guard var entry = entries[ticket.key.call], entry.ticket == ticket else { return nil }
        entry.pending = false; entry.reported = success
        if !success { entry.end = entry.end ?? .failed; entry.endSent = true; remember(ticket.key) }
        var result: End?
        if success, let end = entry.end, !entry.endSent { entry.endSent = true; result = end }
        entries[ticket.key.call] = entry
        return result
    }
    mutating func end(_ ticket: Ticket, reason: End, rejected: Bool = false) -> End? {
        guard var entry = entries[ticket.key.call], entry.ticket == ticket else { return nil }
        entry.end = entry.end ?? reason; entry.rejected = entry.rejected || rejected
        remember(ticket.key)
        var result: End?
        if !entry.pending, entry.reported, !entry.endSent { entry.endSent = true; result = entry.end }
        entries[ticket.key.call] = entry
        return result
    }
    func allowsIncoming(_ call: UUID, session: UnyriPushSession) -> Bool {
        if terminals.contains(where: { $0.call == call && $0.scope == session.scope }) { return false }
        guard let entry = entries[call] else { return true }
        return entry.ticket.key.scope == session.scope && entry.end == nil &&
            (entry.generation == nil || entry.generation == session.generation)
    }
    mutating func adopt(_ ticket: Ticket, session: UnyriPushSession) -> Bool {
        guard allowsIncoming(ticket.key.call, session: session), var entry = entries[ticket.key.call],
              entry.ticket == ticket, entry.end == nil else { return false }
        entry.generation = session.generation; entries[ticket.key.call] = entry
        return true
    }
    func admitted(_ ticket: Ticket, session: UnyriPushSession) -> Bool {
        guard let entry = entries[ticket.key.call], entry.ticket == ticket else { return false }
        return entry.end == nil && ticket.key.scope == session.scope &&
            (entry.generation == nil || entry.generation == session.generation)
    }
    mutating func locallyEnded(_ ticket: Ticket) {
        guard var entry = entries[ticket.key.call], entry.ticket == ticket else { return }
        // CXEndCallAction was fulfilled by the current account coordinator.
        // Keep the terminal fence, but never emit a second platform End.
        entry.end = entry.end ?? .declined; entry.endSent = true; entry.rejectTaken = true
        entries[ticket.key.call] = entry; remember(ticket.key); release(ticket)
    }

    mutating func takeReject(_ ticket: Ticket, session: UnyriPushSession) -> Bool {
        guard var entry = entries[ticket.key.call], entry.ticket == ticket, ticket.key.scope == session.scope,
              entry.rejected, !entry.rejectTaken else { return false }
        entry.rejectTaken = true; entries[ticket.key.call] = entry; return true
    }
    mutating func retire(_ scope: UnyriPushScope) -> [(Ticket, End)] {
        let tickets = entries.values.filter { $0.ticket.key.scope == scope }.map(\.ticket)
        return tickets.compactMap { ticket in end(ticket, reason: .failed).map { (ticket, $0) } }
    }
    mutating func release(_ ticket: Ticket) {
        guard let entry = entries[ticket.key.call], entry.ticket == ticket, !entry.pending, entry.end != nil,
              !entry.rejected || entry.rejectTaken else { return }
        entries[ticket.key.call] = nil
    }
    mutating func recoveryFinished(_ ticket: Ticket) {
        guard var entry = entries[ticket.key.call], entry.ticket == ticket else { return }
        entry.rejectTaken = true; entries[ticket.key.call] = entry; release(ticket)
    }
    mutating func reset() { entries.removeAll(); terminals.removeAll() }
    private mutating func remember(_ key: Key) {
        if !terminals.contains(key) { terminals.append(key) }
        if terminals.count > 128 { terminals.removeFirst(terminals.count - 128) }
    }
}

@MainActor
final class UnyriPushCompletion {
    private var handler: (() -> Void)?
    var finished: Bool { handler == nil }
    init(_ handler: @escaping () -> Void) { self.handler = handler }
    func finish() { let callback = handler; handler = nil; callback?() }
}

#if canImport(UIKit) && canImport(CallKit)
import UIKit
import CallKit
import AVFoundation

/// The only process-lifetime CXProvider. Account coordinators retain their
/// existing controller/actions; this broker owns the brief pre-auth report.
@MainActor
final class UnyriCallKitProvider: NSObject, CXProviderDelegate {
    static let shared = UnyriCallKitProvider()
    let provider: CXProvider
    let transactions = CXCallController()
    private weak var coordinator: CallKitCoordinator?
    private var session: UnyriPushSession?
    private var book = UnyriPushCallBook()
    private var answers: [UUID: (action: CXAnswerCallAction, timeout: Task<Void, Never>)] = [:]
    var hasReports: Bool { book.hasReports }

    private override init() {
        let config = CXProviderConfiguration()
        config.supportedHandleTypes = [.generic]; config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1; config.maximumCallGroups = 1; config.includesCallsInRecents = false
        provider = CXProvider(configuration: config)
        super.init(); provider.setDelegate(self, queue: .main)
    }

    func attach(_ coordinator: CallKitCoordinator, session: UnyriPushSession) {
        if let old = self.coordinator, old !== coordinator { old.shutdown() }
        self.coordinator = coordinator; self.session = session
    }

    func detach(_ coordinator: CallKitCoordinator, calls: Set<UUID>) {
        guard self.coordinator === coordinator else { return }
        for call in calls { reportEnd(call, reason: .failed) }
        // Unadopted push reports belong to the process receive operation, not
        // this coordinator. A delayed detach must not end a successor stub.
        self.coordinator = nil; session = nil
    }

    func retire(_ scope: UnyriPushScope) {
        for (ticket, reason) in book.retire(scope) {
            provider.reportCall(with: ticket.key.call, endedAt: Date(), reason: system(reason))
            cancelAnswer(ticket.key.call)
            book.release(ticket)
        }
        // Pending reports retain their terminal ticket until their completion.
        for (call, value) in answers where book.ticket(call)?.key.scope == scope {
            value.timeout.cancel()
            if !value.action.isComplete, value.action.timeoutDate > Date() { value.action.fail() }
            answers[call] = nil
        }
    }

    func allowsIncoming(_ raw: String, session: UnyriPushSession) -> Bool {
        guard let call = UUID(uuidString: raw) else { return false }
        return book.allowsIncoming(call, session: session)
    }

    /// Initiates Apple's report before returning to the PushKit delegate. No
    /// bootstrap, authentication, permission prompt or network await precedes it.
    func reportPush(_ payload: UnyriPushPayload, binding: UnyriPushBinding,
                    completion: @escaping () -> Void) -> UnyriPushCallBook.Ticket? {
        guard let call = payload.callID else { completion(); return nil }
        let key = UnyriPushCallBook.Key(scope: binding.context.scope, registration: binding.id, revision: binding.revision, call: call)
        let update = CXCallUpdate(); update.remoteHandle = CXHandle(type: .generic, value: UnyriL10n.string("Incoming call"))
        update.localizedCallerName = UnyriL10n.string("Incoming call"); update.hasVideo = payload.mediaKind == "video"
        update.supportsHolding = false; update.supportsGrouping = false; update.supportsUngrouping = false; update.supportsDTMF = false
        return report(key, update: update, completion: { _ in completion() })
    }

    func reportIncoming(_ call: CallInfo, coordinator: CallKitCoordinator, completion: @escaping (Error?) -> Void) {
        guard self.coordinator === coordinator, let session, let uuid = UUID(uuidString: call.callId) else {
            completion(NSError(domain: "UNYRI.CallKit", code: 1)); return
        }
        let key = UnyriPushCallBook.Key(scope: session.scope, registration: nil, revision: 0, call: uuid)
        let update = CXCallUpdate(); update.remoteHandle = CXHandle(type: .generic, value: call.remoteDisplayName)
        update.localizedCallerName = call.remoteDisplayName; update.hasVideo = call.mediaKind == .video
        update.supportsHolding = false; update.supportsGrouping = false; update.supportsUngrouping = false; update.supportsDTMF = false
        _ = report(key, update: update, completion: completion)
    }

    private func report(_ key: UnyriPushCallBook.Key, update: CXCallUpdate,
                        completion: @escaping (Error?) -> Void) -> UnyriPushCallBook.Ticket? {
        let call = key.call
        let existing = book.ticket(call)
        let ticket = book.begin(key)
        var outcome: Error?
        let completed = UnyriPushCompletion { completion(outcome) }
        let timeout = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            guard !completed.finished else { return }
            outcome = NSError(domain: "UNYRI.CallKit", code: 2)
            if let self, let ticket { self.end(ticket, reason: .failed) }
            completed.finish()
        }
        provider.reportNewIncomingCall(with: call, update: update) { [weak self] error in
            Task { @MainActor in
                outcome = error
                defer { timeout.cancel(); completed.finish() }
                guard let self else { return }
                if let ticket {
                    if let end = self.book.reportFinished(ticket, success: error == nil) {
                        self.provider.reportCall(with: call, endedAt: Date(), reason: self.system(end))
                    }
                    if let error {
                        self.cancelAnswer(call)
                        if self.session?.scope == ticket.key.scope { self.routed(call)?.incomingSystemReportFailed(call, error: error) }
                    }
                    self.book.release(ticket)
                } else if error == nil, existing == nil || (self.book.ticket(call) == existing && self.book.terminal(key)) {
                    // A previously terminal genuine UUID must never leave a
                    // re-reported stub onscreen. Never manufacture a UUID.
                    self.provider.reportCall(with: call, endedAt: Date(), reason: .failed)
                }
            }
        }
        return ticket
    }

    func admitted(_ ticket: UnyriPushCallBook.Ticket, session: UnyriPushSession) -> Bool { book.admitted(ticket, session: session) }
    func takeReject(_ ticket: UnyriPushCallBook.Ticket, session: UnyriPushSession) -> Bool { book.takeReject(ticket, session: session) }
    func recoveryFinished(_ ticket: UnyriPushCallBook.Ticket) { book.recoveryFinished(ticket) }
    func terminal(_ ticket: UnyriPushCallBook.Ticket, reason: CallEndReason) {
        let end: UnyriPushCallBook.End
        switch CallKitPolicy.endReasonKind(for: reason) {
        case .remoteEnded: end = .remoteEnded
        case .declinedElsewhere: end = .declined
        case .answeredElsewhere: end = .answeredElsewhere
        case .unanswered: end = .unanswered
        case .failed, .none: end = .failed
        }
        self.end(ticket, reason: end)
    }

    /// Returns true when the exact pre-auth report was adopted. The account
    /// controller must already hold the authoritatively recovered ringing call.
    func adopt(_ call: UUID, coordinator: CallKitCoordinator) -> Bool {
        guard self.coordinator === coordinator, let session, let ticket = book.ticket(call), book.adopt(ticket, session: session) else { return false }
        if let pending = answers.removeValue(forKey: call) {
            pending.timeout.cancel()
            if !pending.action.isComplete, pending.action.timeoutDate > Date() { coordinator.provider(provider, perform: pending.action) }
        }
        return true
    }

    func hasReport(_ call: UUID) -> Bool { book.ticket(call) != nil }
    func end(_ ticket: UnyriPushCallBook.Ticket, reason: UnyriPushCallBook.End) {
        if let end = book.end(ticket, reason: reason) { provider.reportCall(with: ticket.key.call, endedAt: Date(), reason: system(end)) }
        if reason == .failed, session?.scope == ticket.key.scope {
            routed(ticket.key.call)?.incomingSystemReportFailed(ticket.key.call, error: NSError(domain: "UNYRI.CallKit", code: 3))
        }
        cancelAnswer(ticket.key.call); book.release(ticket)
    }
    func locallyEnded(_ call: UUID, coordinator: CallKitCoordinator) {
        guard self.coordinator === coordinator, let session, let ticket = book.ticket(call),
              ticket.key.scope == session.scope else { return }
        book.locallyEnded(ticket); cancelAnswer(call)
    }
    func reportEnd(_ call: UUID, reason: CXCallEndedReason) {
        if let ticket = book.ticket(call) {
            let translated: UnyriPushCallBook.End
            switch reason {
            case .remoteEnded: translated = .remoteEnded
            case .unanswered: translated = .unanswered
            case .declinedElsewhere: translated = .declined
            case .answeredElsewhere: translated = .answeredElsewhere
            default: translated = .failed
            }
            end(ticket, reason: translated)
        } else { provider.reportCall(with: call, endedAt: Date(), reason: reason) }
    }
    private func system(_ end: UnyriPushCallBook.End) -> CXCallEndedReason {
        switch end { case .failed: return .failed; case .remoteEnded: return .remoteEnded; case .declined: return .declinedElsewhere; case .unanswered: return .unanswered; case .answeredElsewhere: return .answeredElsewhere }
    }
    private func cancelAnswer(_ call: UUID, timedOut: Bool = false) {
        guard let value = answers.removeValue(forKey: call) else { return }
        value.timeout.cancel()
        if !timedOut, !value.action.isComplete, value.action.timeoutDate > Date() { value.action.fail() }
    }
    private func routed(_ call: UUID) -> CallKitCoordinator? {
        guard let coordinator, coordinator.handlesSystemCall(call) else { return nil }
        return coordinator
    }

    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor in
            self.coordinator?.providerDidReset(provider)
            for call in Array(self.answers.keys) { self.cancelAnswer(call) }
            self.book.reset()
        }
    }
    nonisolated func providerDidBegin(_ provider: CXProvider) {}
    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        Task { @MainActor in if let owner = self.coordinator { owner.provider(provider, perform: action) } else { action.fail() } }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in
            guard !action.isComplete, action.timeoutDate > Date() else { return }
            if let owner = self.routed(action.callUUID) { owner.provider(provider, perform: action); return }
            guard let ticket = self.book.ticket(action.callUUID), !self.book.terminal(ticket.key),
                  self.answers[action.callUUID] == nil, action.timeoutDate > Date() else { action.fail(); return }
            let seconds = min(15, max(0, action.timeoutDate.timeIntervalSinceNow))
            let timeout = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) } catch { return }
                guard let self, self.answers[action.callUUID]?.action.uuid == action.uuid else { return }
                self.cancelAnswer(action.callUUID); self.end(ticket, reason: .failed)
            }
            self.answers[action.callUUID] = (action, timeout)
        }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor in
            if let owner = self.routed(action.callUUID) { owner.provider(provider, perform: action); return }
            if let ticket = self.book.ticket(action.callUUID) {
                if let end = self.book.end(ticket, reason: .declined, rejected: true) {
                    self.provider.reportCall(with: action.callUUID, endedAt: Date(), reason: self.system(end))
                }
                self.cancelAnswer(action.callUUID)
                // Preserve the rejected ticket for the in-flight authenticated
                // recovery, which may send one exact backend Reject later.
            }
            if !action.isComplete, action.timeoutDate > Date() { action.fulfill() }
        }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor in if let owner = self.routed(action.callUUID) { owner.provider(provider, perform: action) } else { action.fail() } }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) { action.fail() }
    nonisolated func provider(_ provider: CXProvider, perform action: CXSetGroupCallAction) { action.fail() }
    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) { action.fail() }
    nonisolated func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        Task { @MainActor in
            if let answer = action as? CXAnswerCallAction, self.answers[answer.callUUID]?.action.uuid == answer.uuid {
                self.cancelAnswer(answer.callUUID, timedOut: true)
                if let ticket = self.book.ticket(answer.callUUID) { self.end(ticket, reason: .failed) }
            } else { self.coordinator?.provider(provider, timedOutPerforming: action) }
        }
    }
    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        Task { @MainActor in self.coordinator?.provider(provider, didActivate: audioSession) }
    }
    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in self.coordinator?.provider(provider, didDeactivate: audioSession) }
    }
}
#endif
