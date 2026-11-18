import BackgroundTasks
import Foundation
import UIKit

/// Optional iOS 26 execution layer. Denial changes no foreground transfer state.
/// This uses CPU/network only; calls and audio never keep a file transfer alive.
@MainActor
final class DirectFileContinuedProcessing {
    private let owner = UUID()
    @discardableResult func request(_ member: DirectFileBackgroundMember, permit: DirectFileExecutionPermit,
                 total: Int64, offset: Int64,
                 authorize: @escaping @MainActor (String) async -> Bool,
                 interrupted: @escaping @MainActor () async -> Void) -> Bool {
        guard #available(iOS 26.0, *), UIApplication.shared.applicationState == .active else { return false }
        return DirectFileContinuedRegistry.request(owner: owner, member: member, permit: permit,
            total: total, offset: offset, authorize: authorize, interrupted: interrupted)
    }
    func progress(_ member: DirectFileBackgroundMember, offset: Int64) {
        if #available(iOS 26.0, *) { DirectFileContinuedRegistry.progress(owner: owner, member: member, offset: offset) }
    }
    func finish(_ member: DirectFileBackgroundMember, success: Bool) {
        if #available(iOS 26.0, *) { DirectFileContinuedRegistry.finish(owner: owner, member: member, success: success) }
    }
    func finishAll() {
        if #available(iOS 26.0, *) { DirectFileContinuedRegistry.finishAll(owner: owner) }
    }
}

@available(iOS 26.0, *)
@MainActor
private enum DirectFileContinuedRegistry {
    private final class Entry {
        let owner: UUID
        let member: DirectFileBackgroundMember
        let permit: DirectFileExecutionPermit
        let arbitration = DirectFileTaskArbitration()
        let total: Int64
        var offset: Int64
        let authorize: @MainActor (String) async -> Bool
        let interrupted: @MainActor () async -> Void
        var task: BGContinuedProcessingTask?
        var expired = false
        init(owner: UUID, member: DirectFileBackgroundMember, permit: DirectFileExecutionPermit,
             total: Int64, offset: Int64, authorize: @escaping @MainActor (String) async -> Bool,
             interrupted: @escaping @MainActor () async -> Void) {
            self.owner = owner; self.member = member; self.permit = permit
            self.total = total; self.offset = offset; self.authorize = authorize; self.interrupted = interrupted
        }
    }
    private static var entries: [String: Entry] = [:]
    private static var registrationCount = 0
    private static let maximumRegistrations = 64
    private static var prefix: String? { Bundle.main.bundleIdentifier.map { $0 + ".direct-file." } }
    private static func register(_ identifier: String) -> Bool {
        // Info.plist permits a wildcard, but the registered and submitted
        // identifiers are the SAME fully composed per-attempt identifier.
        guard registrationCount < maximumRegistrations else { return false }
        let accepted = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            MainActor.assumeIsolated { receive(task) }
        }
        if accepted { registrationCount += 1 }
        return accepted
    }
    static func request(owner: UUID, member: DirectFileBackgroundMember, permit: DirectFileExecutionPermit,
                        total: Int64, offset: Int64, authorize: @escaping @MainActor (String) async -> Bool,
                        interrupted: @escaping @MainActor () async -> Void) -> Bool {
        guard let prefix, total > 0,
              !entries.values.contains(where: { $0.owner == owner && $0.member == member }) else { return false }
        let identifier = prefix + UUID().uuidString.lowercased()
        guard register(identifier) else { return false }
        let entry = Entry(owner: owner, member: member, permit: permit, total: total,
            offset: offset, authorize: authorize, interrupted: interrupted)
        entries[identifier] = entry
        let request = BGContinuedProcessingTaskRequest(identifier: identifier,
            title: NSLocalizedString("File transfer", comment: "Continued processing task"),
            subtitle: NSLocalizedString(member.outgoing ? "Sending original file" : "Receiving original file", comment: "Continued processing task"))
        request.strategy = .fail // no queued grant can inherit a later attempt
        do { try BGTaskScheduler.shared.submit(request); return true }
        catch { entries[identifier] = nil; return false } // normal foreground remains usable
    }
    private static func receive(_ raw: BGTask) {
        guard let task = raw as? BGContinuedProcessingTask,
              let entry = entries[raw.identifier], entry.task == nil else {
            raw.setTaskCompleted(success: false); return
        }
        entry.task = task
        let permit = entry.permit
        task.expirationHandler = {
            guard entry.arbitration.interrupt() else { return }
            permit.revoke(interrupted: true) // synchronous before MainActor/drain/journal awaits
            Task { @MainActor in
                guard entries[task.identifier] === entry, !entry.expired else { return }
                entry.expired = true
                await entry.interrupted()
                complete(task.identifier, entry: entry, success: false, interruptionHandled: true)
            }
        }
        task.progress.totalUnitCount = entry.total
        task.progress.completedUnitCount = min(entry.total, max(0, entry.offset))
        Task { @MainActor in
            let authorized = await entry.authorize(task.identifier)
            guard entries[task.identifier] === entry else { return }
            guard authorized, !entry.expired, entry.permit.grant() else {
                // The controller resolves any ambiguous durable marker before
                // returning false. A clean pre-grant denial preserves foreground.
                complete(task.identifier, entry: entry, success: false); return
            }
        }
    }
    static func progress(owner: UUID, member: DirectFileBackgroundMember, offset: Int64) {
        for entry in entries.values where entry.owner == owner && entry.member == member {
            entry.offset = max(entry.offset, min(entry.total, max(0, offset)))
            entry.task?.progress.completedUnitCount = entry.offset
        }
    }
    static func finish(owner: UUID, member: DirectFileBackgroundMember, success: Bool) {
        for (id, entry) in entries where entry.owner == owner && entry.member == member {
            complete(id, entry: entry, success: success)
        }
    }
    static func finishAll(owner: UUID) {
        for (id, entry) in entries where entry.owner == owner { complete(id, entry: entry, success: false) }
    }
    private static func complete(_ id: String, entry: Entry, success: Bool, interruptionHandled: Bool = false) {
        guard entries[id] === entry else { return }
        // A queued native failure/natural finish or authorization result must not
        // remove the entry before its already-revoked expiration handler saves
        // the durable interruption. Only that handler may complete this case.
        guard entry.arbitration.finish(interruptionHandled: interruptionHandled) else { return }
        if entry.permit.hasGrant { entry.permit.revoke() }
        entries[id] = nil
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
        entry.task?.expirationHandler = nil
        entry.task?.setTaskCompleted(success: success)
    }
}
