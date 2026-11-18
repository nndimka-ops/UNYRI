import Foundation
import Dispatch

@main
struct ShareContracts {
    static var checks = 0
    static let account = String(repeating: "a", count: 32)
    static let other = String(repeating: "b", count: 32)
    static func check(_ value: Bool, _ message: String) {
        precondition(value, message); checks += 1
    }
    static func rejects(_ body: () throws -> Void, _ message: String) {
        do { try body(); preconditionFailure(message) } catch { checks += 1 }
    }
    static func makeJob(id: String = UUID().uuidString.lowercased(), accountKey: String = ShareContracts.account) -> ShareJob {
        ShareJob(jobId: id, createdAt: Date().timeIntervalSince1970, accountKey: accountKey,
            target: .init(chatId: "11111111-1111-4111-8111-111111111111", type: "direct", peerUserId: "22222222-2222-4222-8222-222222222222", titleAtShare: "Anna"),
            caption: "hello", albumId: UUID().uuidString.lowercased(),
            items: [.init(id: UUID().uuidString.lowercased(), kind: .image, name: "photo.jpg", bytes: 3, albumItemIndex: 0)])
    }
    static func manifest() throws {
        let job = makeJob()
        let data = try job.encoded()
        check(try ShareJob.decode(data) == job, "manifest v2 round-trip")
        check(job.caption(at: 0) == "hello" && job.caption(at: 1) == nil, "caption on item zero only")
        func changed(_ mutate: (inout [String: Any]) -> Void) throws -> Data {
            var value = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            mutate(&value); return try JSONSerialization.data(withJSONObject: value)
        }
        for field in ["version", "jobId", "accountKey"] {
            let corrupt = try changed { value in
                if field == "version" { value[field] = 99 } else { value[field] = "invalid" }
            }
            rejects({ _ = try ShareJob.decode(corrupt) }, "invalid \(field)")
        }
        let big = Data(repeating: 32, count: 65537)
        rejects({ _ = try ShareJob.decode(big) }, "manifest bounded before decode")
        for mutation in ["duplicate", "uuid", "name", "count", "bytes", "route", "prepare", "index", "caption"] {
            let corrupt = try changed { value in
                var items = value["items"] as! [[String: Any]]
                switch mutation {
                case "duplicate":
                    var duplicate = items[0]
                    duplicate["albumItemIndex"] = 1; duplicate["clientAttachmentId"] = UUID().uuidString
                    items.append(duplicate)
                case "uuid": items[0]["id"] = "../../outside"
                case "name": items[0]["name"] = "../outside"
                case "count":
                    let template = items[0]
                    items = (0..<11).map { index in
                        var item = template; item["id"] = UUID().uuidString
                        item["clientAttachmentId"] = UUID().uuidString; item["albumItemIndex"] = index
                        return item
                    }
                case "bytes": items[0]["bytes"] = -1
                case "route": items[0]["route"] = "direct"
                case "prepare": items[0]["prepare"] = "extension"
                case "index": items[0]["albumItemIndex"] = 1
                default: value["caption"] = String(repeating: "x", count: 4097)
                }
                value["items"] = items
            }
            rejects({ _ = try ShareJob.decode(corrupt) }, mutation)
        }
        #if canImport(CryptoKit)
        let key = UnyriShareStore.accountKey(storageOrigin: "https://example.test", userID: "user")
        check(key == "0a13bd46de369704bf4e7438760d87bf", "account key domain-separated known vector")
        check(key != UnyriShareStore.accountKey(storageOrigin: "https://other.test", userID: "user"), "origin binding")
        #endif
    }
    static func route() {
        for kind in [ShareJob.Kind.image, .file, .video] {
            for sendAs in [ShareJob.SendAs.file, .media] {
                let limit = kind == .video && sendAs == .media ? ShareRoutePolicy.videoSourceBytes : ShareRoutePolicy.cloudBytes
                for size in [Int64(0), limit - 1, limit] { check(ShareRoutePolicy.issue(kind: kind, bytes: size, sendAs: sendAs) == nil, "cloud boundary") }
                check((ShareRoutePolicy.issue(kind: kind, bytes: limit + 1, sendAs: sendAs) != nil) == !(kind == .image && sendAs == .media), "still source downsampled; other oversize refused")
                check(ShareRoutePolicy.issue(kind: kind, bytes: -1, sendAs: sendAs) != nil, "negative refused")
            }
        }
        var item = makeJob().items[0]
        check(ShareRoutePolicy.sendKind(item) == "image", "image not document")
        item.quality = .high
        check(ShareRoutePolicy.sendKind(item) == "image_high", "high quality image")
        item.kind = .video
        check(ShareRoutePolicy.sendKind(item) == "video_high", "video path")
        item.sendAs = .file
        check(ShareRoutePolicy.sendKind(item) == "file", "explicit document path")
    }
    static func classification() {
        typealias Policy = ShareItemClassification
        let heic = Policy.Representation(identifier: "public.heic", category: .image, filenameExtension: "heic")
        let png = Policy.Representation(identifier: "public.png", category: .image, filenameExtension: "png")
        let jpeg = Policy.Representation(identifier: "public.jpeg", category: .image, filenameExtension: "jpg")
        let movie = Policy.Representation(identifier: "com.apple.quicktime-movie", category: .video, filenameExtension: "mov")
        let mp4 = Policy.Representation(identifier: "public.mpeg-4", category: .video, filenameExtension: "mp4")
        let live = Policy.Representation(identifier: "com.apple.live-photo", category: .livePhoto)
        let data = Policy.Representation(identifier: "public.data", category: .genericData)
        let fileURL = Policy.Representation(identifier: "public.file-url", category: .fileURL)
        let pdf = Policy.Representation(identifier: "com.adobe.pdf", category: .file, filenameExtension: "pdf")
        for photo in [heic, png, jpeg] {
            for reps in [[photo], [data, fileURL, photo], [photo, data]] {
                let selected = Policy.select(reps)!
                check(selected.kind == .image && selected.identifier == photo.identifier, "typed photo wins over generic bytes/URL")
                check(Policy.kind(selected, filename: "wrong.mov") == .image, "HEIC/PNG/JPEG cannot become video by filename")
                check(Policy.initialSendAs(kind: .image) == .media, "photo is media despite remembered file mode")
            }
        }
        for reps in [[live, movie, heic], [movie, jpeg, live], [png, live, movie]] {
            let selected = Policy.select(reps)!
            check(selected.kind == .image && selected.livePhotoStill, "Live Photo contributes one still, independent of registration order")
        }
        check(Policy.select([movie, heic], livePhoto: true)?.kind == .image, "conforming Live Photo marker overrides paired movie")
        check(Policy.select([live, movie]) == nil, "missing still must not fall back to MOV/bundle")
        check(Policy.isLivePhotoIdentifier("com.apple.live-photo-bundle"), "Live Photo bundle marker")
        for video in [movie, mp4] {
            for reps in [[video], [data, video], [jpeg, video]] {
                let selected = Policy.select(reps)!
                check(selected.kind == .video && selected.identifier == video.identifier, "video wins over poster and generic data")
                check(Policy.kind(selected, filename: "wrong.jpg") == .video, "misleading suggestedName cannot turn video into file/photo")
                check(Policy.initialSendAs(kind: .video) == .media, "video defaults to video")
            }
        }
        let selected = Policy.select([movie])!
        check(Policy.name("IMG_1000.jpg", providerName: "temporary", selection: selected) == "IMG_1000.mov", "movie filename follows selected representation")
        check(Policy.name("IMG_1000", providerName: "temporary.bin", selection: Policy.select([heic])!) == "IMG_1000.heic", "HEIC filename retained for ImageIO")
        let safe = Policy.name(String(repeating: "a", count: 200), providerName: "temp", selection: selected)
        check(safe.count <= 160 && safe.hasSuffix(".mov") && safe == UnyriShareStore.safeName(safe), "bounded canonical filenames")
        check(Policy.select([data, pdf])?.kind == .file, "ordinary file preserves declared file type")
        check(Policy.kind(Policy.select([fileURL])!, filename: "clip.mp4") == .video, "URL-only movie fallback")
        check(Policy.kind(Policy.select([data])!, filename: "screen.png") == .image, "generic data photo fallback")
        check(Policy.select([]) == nil, "unsupported provider")
        let mixed = [[heic], [movie], [png], [mp4], [live, movie, jpeg]].compactMap { Policy.select($0) }
        let expected: [ShareJob.Kind?] = [.image, .video, .image, .video, .image]
        check(mixed.map(\.kind) == expected, "mixed selection preserves each provider's media kind and order")
        let ten = (0..<10).compactMap { Policy.select($0.isMultiple(of: 2) ? [heic] : [movie]) }
        check(ten.count == 10 && ten.filter { $0.kind == .image }.count == 5 && ten.filter { $0.kind == .video }.count == 5, "five photos and five videos remain ten ordered media items")
        check(Policy.maximumItems == 10, "one combined limit, not ten of each type")
        for count in [0, 11, 20] { check(!(1...Policy.maximumItems).contains(count), "invalid combined selection count") }
        for kind in [ShareJob.Kind.image, .video, .file] {
            var item = makeJob().items[0]; item.kind = kind; item.sendAs = Policy.initialSendAs(kind: kind)
            check(ShareRoutePolicy.sendKind(item) == kind.rawValue, "media/file dispatch follows item classification")
            item.sendAs = .file
            check(ShareRoutePolicy.sendKind(item) == "file", "per-share explicit file toggle wins")
        }
        let wrapped = NSError(domain: "NSItemProviderErrorDomain", code: -1000, userInfo: [NSUnderlyingErrorKey: NSError(domain: NSURLErrorDomain, code: URLError.cancelled.rawValue)])
        check(Policy.providerFailure(wrapped) == Policy.loadingCancelled, "nested provider cancellation is clear")
        check(Policy.providerFailure(URLError(.notConnectedToInternet)) == Policy.loadingFailure, "iCloud/offline failure is clear and promises no partial send")
        let full = NSError(domain: "NSItemProviderErrorDomain", code: -1000, userInfo: [NSUnderlyingErrorKey: CocoaError(.fileWriteOutOfSpace) as NSError])
        check(Policy.providerFailure(full) == "Not enough space on this iPhone. Free some storage and try again.", "cloud download storage errors do not suggest fixing the network")
        let gate = ShareProviderProgressGate()
        check(gate.take(fraction: 0, total: 100) == .percent(0), "provider progress starts")
        check(gate.take(fraction: 0.009, total: 100) == nil, "per-byte updates are coalesced")
        check(gate.take(fraction: 0.51, total: 100) == .percent(51), "real provider progress")
        check(gate.take(fraction: 1000, total: 100) == .percent(100), "clamp provider over-reporting")
        check(gate.take(fraction: .nan, total: 100) == .indeterminate, "invalid fraction is indeterminate")
        check(gate.take(fraction: 0, total: 0) == nil, "unknown total never invents a percentage")
    }
    static func binding(_ key: String = ShareContracts.account) -> UnyriShareStore.AccountBinding { .init(accountKey: key, id: UUID().uuidString.lowercased()) }
    static func publish(root: URL, binding: UnyriShareStore.AccountBinding) throws -> (ShareJob, URL) {
        try ShareContainer.prepare(binding, root: root)
        let staging = try ShareJobStore.Staging(binding: binding, root: root)
        let job = makeJob(id: staging.id, accountKey: binding.accountKey)
        let item = job.items[0]
        let folder = try staging.itemDirectory(item.id)
        try Data([1, 2, 3]).write(to: folder.appendingPathComponent(item.name))
        return (job, try staging.commit(job))
    }
    static func store(root: URL) throws {
        try cancellationContracts()
        let binding = binding()
        try ShareContainer.prepare(binding, root: root)
        let staging = try ShareJobStore.Staging(binding: binding, root: root)
        check(try ShareJobStore.committed(root: root).isEmpty, "staging invisible")
        let job = makeJob(id: staging.id)
        let folder = try staging.itemDirectory(job.items[0].id)
        try Data([1, 2, 3]).write(to: folder.appendingPathComponent(job.items[0].name))
        let directory = try staging.commit(job)
        check(try ShareJobStore.committed(root: root) == [directory], "rename commits")
        let before = try Data(contentsOf: directory.appendingPathComponent("manifest.json"))
        let imported = ShareJobEvent(name: .imported)
        DispatchQueue.concurrentPerform(iterations: 20) { _ in try! ShareJobStore.event(imported, itemID: job.items[0].id, in: directory) }
        check(ShareJobStore.event(.imported, itemID: job.items[0].id, in: directory) == imported, "concurrent idempotent event")
        check(ShareJobStore.event(.sent, itemID: job.items[0].id, in: directory) == nil, "missing event not yet")
        try Data("corrupt".utf8).write(to: directory.appendingPathComponent("events/\(job.items[0].id).sent.json"))
        check(ShareJobStore.event(.sent, itemID: job.items[0].id, in: directory) == nil, "corrupt event not yet")
        check(try Data(contentsOf: directory.appendingPathComponent("manifest.json")) == before, "events never mutate manifest")
        try staging.cancelAndRemove()
        check(FileManager.default.fileExists(atPath: directory.path), "extension cannot remove committed job")
        let closing = try ShareJobStore.Staging(binding: binding, root: root)
        try closing.cancelAndRemove()
        rejects({ _ = try closing.itemDirectory(UUID().uuidString) }, "close rejects late provider")
        let racing = try ShareJobStore.Staging(binding: binding, root: root)
        let raceJob = makeJob(id: racing.id)
        let raceItem = raceJob.items[0]
        let raceFolder = try racing.itemDirectory(raceItem.id)
        try Data([1, 2, 3]).write(to: raceFolder.appendingPathComponent(raceItem.name))
        try ShareContainer.purge(accountKey: account, root: root)
        rejects({ _ = try racing.commit(raceJob) }, "logout revokes in-flight commit")
        rejects({ _ = try ShareJobStore.Staging(binding: binding, root: root) }, "extension cannot recreate account lease")
        check(try ShareJobStore.committed(root: root).isEmpty, "purged jobs absent")
        let fresh = self.binding()
        try ShareContainer.prepare(fresh, root: root)
        let orphan = try ShareJobStore.Staging(binding: fresh, root: root)
        try ShareJobStore.sweepStaging(root: root, olderThan: 3600, now: Date().addingTimeInterval(3601))
        check(!FileManager.default.fileExists(atPath: orphan.directory.path), "abandoned staging expires on a real app pass")
    }
    final class CancelProbe: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func call() { lock.lock(); count += 1; lock.unlock() }
        var calls: Int { lock.lock(); defer { lock.unlock() }; return count }
    }
    static func cancellationContracts() throws {
        let token = UnyriShareStore.CopyCancellation(), coordinator = CancelProbe(), provider = CancelProbe()
        let retired = CancelProbe()
        let retiredID = token.onCancel { retired.call() }
        token.removeHandler(retiredID)
        token.onCancel { coordinator.call(); try! cancellationIsSet(token) }
        token.onCancel { provider.call() }
        DispatchQueue.concurrentPerform(iterations: 32) { _ in token.cancel() }
        check(coordinator.calls == 1 && provider.calls == 1 && retired.calls == 0, "coordination/provider cancel once, outside the token lock; retired registrations stay retired")
        let late = CancelProbe()
        token.onCancel { late.call() }
        check(late.calls == 1, "provider or coordinator registered after Close cancels immediately")
        for _ in 0..<32 {
            let racing = UnyriShareStore.CopyCancellation(), probe = CancelProbe()
            DispatchQueue.concurrentPerform(iterations: 2) { index in
                if index == 0 { racing.cancel() } else { racing.onCancel { probe.call() } }
            }
            check(probe.calls == 1, "registration vs Close has no missed cancellation")
        }
        let waiting = UnyriShareStore.CopyCancellation()
        let started = DispatchSemaphore(value: 0), released = DispatchSemaphore(value: 0), finished = DispatchSemaphore(value: 0)
        let accessors = CancelProbe()
        waiting.onCancel { released.signal() }
        DispatchQueue.global().async {
            started.signal()
            if released.wait(timeout: .now() + 2) == .success {
                if (try? waiting.check()) != nil { accessors.call() }
            }
            finished.signal()
        }
        check(started.wait(timeout: .now() + 2) == .success, "coordination wait starts")
        waiting.cancel()
        check(finished.wait(timeout: .now() + 2) == .success && accessors.calls == 0, "Close releases a blocked coordinator without entering its accessor")
    }
    static func cancellationIsSet(_ token: UnyriShareStore.CopyCancellation) throws {
        do { try token.check(); throw CocoaError(.fileReadUnknown) } catch is CancellationError { return }
    }
    @MainActor static func publicationContracts(root baseRoot: URL) async throws {
        let root = baseRoot.appendingPathComponent("publication")
        try ShareContainer.directory(root)
        let first = binding(), second = binding(other)
        let a = ShareTargetSnapshot.Account(key: account, binding: first, displayName: "Active")
        let b = ShareTargetSnapshot.Account(key: other, binding: second, displayName: "Removed", state: "inactive")
        let captured = try await ShareContainer.disk.perform { try ShareContainer.snapshotAuthority(root: root) }
        try await ShareContainer.disk.perform {
            try ShareContainer.publishSnapshot(authority: captured, root: root, accountKeys: [account, other]) {
                try ShareContainer.prepareTargets(root: root)
                try ShareContainer.prepare(second, root: root)
                try ShareContainer.write(ShareContainer.encode(ShareTargetSnapshot.Index(activeAccountKey: account, accounts: [a, b])), to: ShareContainer.targets(root).appendingPathComponent("index.json"))
            }
            try ShareContainer.purge(accountKey: other, root: root)
        }
        // A publication captured while registry.records still contains B resumes
        // after erasure on the same executor. Its body must never recreate B.
        var entered = false
        do {
            try await ShareContainer.disk.perform {
                try ShareContainer.publishSnapshot(authority: captured, root: root, accountKeys: [account, other]) {
                    entered = true
                    try ShareContainer.prepare(second, root: root)
                    try ShareContainer.write(ShareContainer.encode(ShareTargetSnapshot.Index(activeAccountKey: account, accounts: [a, b])), to: ShareContainer.targets(root).appendingPathComponent("index.json"))
                }
            }
            preconditionFailure("revoked queued publication was accepted")
        } catch is CancellationError { checks += 1 }
        check(!entered && !FileManager.default.fileExists(atPath: ShareContainer.accountJobs(second, root: root).path), "stale publication cannot recreate removed binding")
        check(try ShareTargetSnapshot.readIndex(root: root).accounts.map(\.key) == [account], "purged display metadata stays absent")
        let restarted = try await ShareContainer.disk.perform { try ShareContainer.snapshotAuthority(root: root) }
        check(restarted.revokedAccounts.contains(other), "revocation survives a writer/process restart before registry removal")
        check([a, b].filter { !restarted.revokedAccounts.contains($0.key) } == [a], "fresh publication excludes revoked stale registry inputs")
        rejects({ try ShareContainer.publishSnapshot(authority: restarted, root: root, accountKeys: [account, other]) {} }, "even a fresh permit cannot publish a revoked account")
        try await ShareContainer.disk.perform {
            try ShareContainer.publishSnapshot(authority: restarted, root: root, accountKeys: [account]) {
                try ShareContainer.write(ShareContainer.encode(ShareTargetSnapshot.Index(activeAccountKey: account, accounts: [a])), to: ShareContainer.targets(root).appendingPathComponent("index.json"))
            }
        }
        try await ShareContainer.disk.perform {
            check(try !ShareContainer.restoreSnapshotAccount(account, root: root), "active A cannot revive B")
            check(try ShareContainer.restoreSnapshotAccount(other, root: root), "a new authenticated B session may publish again")
        }
        let restored = try ShareContainer.snapshotAuthority(root: root)
        check(restored.revision != restarted.revision && !restored.revokedAccounts.contains(other), "restoration also invalidates older publication permits")
        try Data("broken".utf8).write(to: root.appendingPathComponent("SharePrivacy/snapshot-authority.json"))
        rejects({ try ShareContainer.publishSnapshot(authority: restored, root: root, accountKeys: [account]) {} }, "damaged authority never authorizes publication")
    }
    static func snapshot(root: URL) throws {
        for type in ["direct", "group", "saved"] {
            check(ShareTargetSnapshot.canInclude(type: type, blocked: false, deleted: false, hidden: false, hasPeer: true), "valid target")
            check(!ShareTargetSnapshot.canInclude(type: type, blocked: true, deleted: false, hidden: false, hasPeer: true), "blocked excluded")
            check(!ShareTargetSnapshot.canInclude(type: type, blocked: false, deleted: true, hidden: false, hasPeer: true), "deleted excluded")
            check(!ShareTargetSnapshot.canInclude(type: type, blocked: false, deleted: false, hidden: true, hasPeer: true), "hidden excluded")
            check(!ShareTargetSnapshot.canInclude(type: type, blocked: false, deleted: false, hidden: false, canPost: false, hasPeer: true), "read-only excluded")
        }
        check(!ShareTargetSnapshot.canInclude(type: "direct", blocked: false, deleted: false, hidden: false, hasPeer: false), "nil peer is not Saved")
        var saved = ShareTargetSnapshot.Target(chatId: UUID().uuidString, type: "saved", title: "Saved", peerUserId: nil)
        var pinned = saved; pinned = .init(chatId: UUID().uuidString, type: "direct", title: "Café", peerUserId: UUID().uuidString, pinned: true, lastActivity: 1)
        let recent = ShareTargetSnapshot.Target(chatId: UUID().uuidString, type: "group", title: "Recent", peerUserId: nil, lastActivity: 100)
        var archived = recent; archived = .init(chatId: UUID().uuidString, type: "group", title: "Archive", peerUserId: nil, archived: true)
        saved.lastActivity = 0
        check(ShareTargetSnapshot.ordered([recent, archived, pinned, saved]).map(\.chatId) == [saved, pinned, recent].map(\.chatId), "Saved, pinned, recent; archive hidden")
        check(ShareTargetSnapshot.ordered([pinned], query: "cafe").count == 1, "diacritic/case search")
        check(ShareTargetSnapshot.ordered([archived], query: "arch").count == 1, "archive searchable")
        var gate = ShareSnapshotGate()
        let first = gate.changed(at: 1)
        check(!gate.ready(first, at: 2.999), "two seconds debounce")
        let second = gate.changed(at: 2)
        check(!gate.ready(first, at: 10) && !gate.ready(second, at: 3.999) && gate.ready(second, at: 4), "burst coalesces by test clock")
        let hash = Data([1, 2])
        check(gate.differs(hash), "first snapshot writes")
        gate.didWrite(hash)
        check(!gate.differs(hash) && gate.differs(Data([3])), "content gate skips no-op")
        let binding = binding()
        let account = ShareTargetSnapshot.Account(key: Self.account, binding: binding, displayName: "Anna")
        let targetRoot = ShareContainer.targets(root)
        try ShareContainer.directory(targetRoot.appendingPathComponent(Self.account))
        let output = ShareTargetSnapshot.Snapshot(accountKey: Self.account, writtenAt: 1, targets: [saved])
        try ShareContainer.write(ShareContainer.encode(output), to: targetRoot.appendingPathComponent(Self.account + "/targets.json"))
        try ShareContainer.write(ShareContainer.encode(ShareTargetSnapshot.Index(activeAccountKey: Self.account, accounts: [account])), to: targetRoot.appendingPathComponent("index.json"))
        check(try ShareTargetSnapshot.read(account: account, root: root) == output, "snapshot round-trip")
        let text = String(decoding: try ShareContainer.encode(output), as: UTF8.self)
        for field in ["username", "preview", "token", "unread", "login"] { check(!text.contains(field), "minimal snapshot: \(field)") }
        try ShareContainer.purge(accountKey: Self.account, root: root)
        check(try ShareTargetSnapshot.readIndex(root: root).accounts.isEmpty, "account purge removes index entry")
        check(!FileManager.default.fileExists(atPath: targetRoot.appendingPathComponent(Self.account).path), "account purge removes snapshot")
        try ShareContainer.purge(accountKey: nil, root: root)
        check(try ShareTargetSnapshot.readIndex(root: root).accounts.isEmpty, "last-account signed-out sentinel")
        try ShareContainer.setRequiresAuthentication(true, root: root)
        check(ShareContainer.requiresAuthentication(root: root), "app lock fence is immediate")
        try Data("broken".utf8).write(to: root.appendingPathComponent("SharePrivacy/app-lock.json"))
        check(ShareContainer.requiresAuthentication(root: root), "damaged privacy fence fails closed")
        try ShareContainer.setRequiresAuthentication(false, root: root)
        check(!ShareContainer.requiresAuthentication(root: root), "authenticated disabling is mirrored")
        try ShareContainer.setRequiresAuthentication(true, root: root)
        try ShareContainer.purge(accountKey: nil, root: root)
        check(ShareContainer.requiresAuthentication(root: root), "device app lock survives account purge")
    }
    @MainActor static func processor(root: URL) async throws {
        // Each point reconstructs a fresh processor from durable records/events.
        for point in ["committed", "privateCopy", "rowBeforeImported", "imported", "corruptImported", "sentBeforeCleanup"] {
            let folder = root.appendingPathComponent(point); try ShareContainer.directory(folder)
            let (job, directory) = try publish(root: folder, binding: binding())
            let item = job.items[0]
            let privateURL = folder.appendingPathComponent("private.jpg")
            var record: ShareJobProcessor.Record = .missing
            var enqueueCount = 0, copies = 0
            var crashCopy = point == "privateCopy", crashInspect = false
            if ["imported", "corruptImported", "sentBeforeCleanup"].contains(point) {
                record = point == "sentBeforeCleanup" ? .sent : .pending
                enqueueCount = 1; try Data([1, 2, 3]).write(to: privateURL)
                if point == "corruptImported" { try Data([0]).write(to: directory.appendingPathComponent("events/\(item.id).imported.json")) }
                else { try ShareJobStore.event(.init(name: .imported), itemID: item.id, in: directory) }
            }
            let deps = ShareJobProcessor.Dependencies(current: { true }, accountKey: account, registeredKeys: { [account] },
                targets: { [.init(chatId: job.target.chatId, type: job.target.type, title: "", peerUserId: job.target.peerUserId)] }, inspect: { _, _ in
                    if crashInspect { crashInspect = false; throw CocoaError(.fileReadUnknown) }; return record
                }, adopt: { _, source in
                    if !FileManager.default.fileExists(atPath: privateURL.path) { try FileManager.default.copyItem(at: source, to: privateURL); copies += 1 }
                    if crashCopy { crashCopy = false; throw CancellationError() }; return privateURL
                }, enqueue: { _, _, _ in
                    enqueueCount += 1; record = .pending
                    if point == "rowBeforeImported" && enqueueCount == 1 { crashInspect = true }
                    return true
                }, notice: { _ in }, flush: {})
            let first = ShareJobProcessor(dependencies: deps, root: { folder })
            try? await first.process()
            let relaunched = ShareJobProcessor(dependencies: deps, root: { folder })
            try await relaunched.process()
            check(enqueueCount == 1, "exactly one durable admission after \(point)")
            if point != "sentBeforeCleanup" {
                check(FileManager.default.fileExists(atPath: privateURL.path), "private source owns pending send")
                check(!FileManager.default.fileExists(atPath: ShareJobStore.source(item, in: directory).path), "staged source cleaned only after durable row")
                record = .sent
                try await relaunched.process()
            }
            check(!FileManager.default.fileExists(atPath: directory.path), "sent job reconciles and deletes after \(point)")
            check(copies <= 1, "adoption resumes without duplicate copy")
        }
        for reason in ["inactive", "inactiveExpired", "removed", "target", "expired", "retired", "importedRowRemoved"] {
            let folder = root.appendingPathComponent(reason); try ShareContainer.directory(folder)
            let (job, directory) = try publish(root: folder, binding: binding())
            if reason == "importedRowRemoved" { try ShareJobStore.event(.init(name: .imported), itemID: job.items[0].id, in: directory) }
            var admissions = 0
            let deps = ShareJobProcessor.Dependencies(current: { reason != "retired" }, accountKey: ["inactive", "inactiveExpired", "removed"].contains(reason) ? other : account,
                registeredKeys: { reason == "removed" ? [other] : [account, other] }, targets: {
                    precondition(reason != "inactiveExpired", "expiry loaded inactive account chats")
                    return reason == "expired" ? [.init(chatId: job.target.chatId, type: job.target.type, title: "", peerUserId: job.target.peerUserId)] : []
                }, inspect: { _, _ in
                    precondition(reason != "inactiveExpired", "expiry opened the inactive account outbox")
                    return .missing
                },
                adopt: { _, source in source }, enqueue: { _, _, _ in admissions += 1; return true }, notice: { _ in }, flush: {})
            try await ShareJobProcessor(dependencies: deps, root: { folder }).process(now: Date(timeIntervalSince1970: job.createdAt + (["expired", "inactiveExpired"].contains(reason) ? 8 * 86400 : 0)))
            check(admissions == 0, "gate: \(reason)")
            check(FileManager.default.fileExists(atPath: directory.path) == ["inactive", "retired"].contains(reason), "gate retention: \(reason)")
        }
        let partialRoot = root.appendingPathComponent("partialInactiveExpiry")
        let lease = binding(); try ShareContainer.prepare(lease, root: partialRoot)
        let staging = try ShareJobStore.Staging(binding: lease, root: partialRoot)
        let template = makeJob(id: staging.id)
        let second = ShareJob.Item(id: UUID().uuidString.lowercased(), kind: .video, name: "clip.mov", bytes: 3, albumItemIndex: 1)
        let partial = ShareJob(jobId: template.jobId, createdAt: template.createdAt, accountKey: account,
            target: template.target, caption: template.caption, albumId: template.albumId, items: template.items + [second])
        for item in partial.items { try Data([1, 2, 3]).write(to: staging.itemDirectory(item.id).appendingPathComponent(item.name)) }
        let directory = try staging.commit(partial)
        let imported = partial.items[0]
        try ShareJobStore.event(.init(name: .imported), itemID: imported.id, in: directory)
        let privateURL = partialRoot.appendingPathComponent("private-source")
        try Data([1, 2, 3]).write(to: privateURL)
        let noAccountReads = ShareJobProcessor.Dependencies(current: { true }, accountKey: other, registeredKeys: { [account, other] },
            targets: { preconditionFailure("inactive expiry loaded chats") }, inspect: { _, _ in preconditionFailure("inactive expiry read an outbox") },
            adopt: { _, _ in preconditionFailure("inactive expiry adopted a source") }, enqueue: { _, _, _ in preconditionFailure("inactive expiry enqueued") }, notice: { _ in }, flush: {})
        let expiryProcessor = ShareJobProcessor(dependencies: noAccountReads, root: { partialRoot })
        try await expiryProcessor.process(now: Date(timeIntervalSince1970: partial.createdAt + 7 * 86400))
        check(FileManager.default.fileExists(atPath: ShareJobStore.source(second, in: directory).path), "seven-day boundary is retained")
        try await expiryProcessor.process(now: Date(timeIntervalSince1970: partial.createdAt + 7 * 86400 + 1))
        for item in partial.items { check(!FileManager.default.fileExists(atPath: ShareJobStore.source(item, in: directory).path), "inactive expired committed plaintext removed") }
        check(FileManager.default.fileExists(atPath: privateURL.path), "expiry cannot touch durable private copies")
        check(ShareJobStore.event(.imported, itemID: imported.id, in: directory) != nil && ShareJobStore.event(.sent, itemID: imported.id, in: directory) == nil,
            "expiry retains imported journal without inventing delivery")
        check(ShareJobStore.event(.failed, itemID: second.id, in: directory)?.code == "expired", "unimported tail is permanently expired")
        try await expiryProcessor.process(now: Date(timeIntervalSince1970: partial.createdAt + 9 * 86400))
        check(FileManager.default.fileExists(atPath: privateURL.path), "expiry pass is idempotent")
    }
    @MainActor static func main() async throws {
        let suite = CommandLine.arguments.last ?? "all"
        // macOS: /var is a symlink to /private/var and directory listings return the real path;
        // resolve the root once so constructed and listed URLs compare equal (as in the app,
        // whose App Group container URL is already the real path).
        var base = FileManager.default.temporaryDirectory
        #if canImport(Darwin)
        // resolvingSymlinksInPath() strips /private again on macOS; realpath keeps the real path.
        if let resolved = realpath(base.path, nil) {
            base = URL(fileURLWithPath: String(cString: resolved), isDirectory: true)
            free(resolved)
        }
        #endif
        let root = base.appendingPathComponent("share-contracts-" + UUID().uuidString)
        try ShareContainer.directory(root); defer { try? FileManager.default.removeItem(at: root) }
        switch suite {
        case "ShareJob": try manifest()
        case "ShareJobStore": try store(root: root)
        case "ShareRoutePolicy": route()
        case "ShareItemClassification": classification()
        case "ShareTargetSnapshot": try snapshot(root: root); try await publicationContracts(root: root)
        case "ShareJobProcessor": try await processor(root: root)
        default: preconditionFailure("unknown suite")
        }
        print("\(suite) Foundation contracts passed: \(checks) checks")
    }
}
