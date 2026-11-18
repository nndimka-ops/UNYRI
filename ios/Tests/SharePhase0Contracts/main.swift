import Foundation

@main struct SharePhase0Contracts {
    @MainActor static func main() async throws {
        var checks = 0
        func check(_ value: Bool, _ why: String) { precondition(value, why); checks += 1 }
        let fm = FileManager.default
        let root = contractRootURL
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        let a = String(repeating: "a", count: 32), b = String(repeating: "b", count: 32)
        let bindingA = try UnyriShareStore.prepareAccount(a, root: root)
        let bindingB = try UnyriShareStore.prepareAccount(b, root: root)
        func exists(_ url: URL) -> Bool { fm.fileExists(atPath: url.path) }
        func rejects(_ body: () throws -> Void, _ why: String) {
            do { try body(); preconditionFailure(why) } catch { checks += 1 }
        }
        func batch(_ name: String, owner: String?, count: Int = 1) throws -> (URL, [UnyriShareStore.Entry]) {
            let directory = root.appendingPathComponent(name + ".ready")
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let entries = try (0..<count).map { index -> UnyriShareStore.Entry in
                let id = UUID().uuidString.lowercased(), filename = "file\(index).pdf"
                let parent = directory.appendingPathComponent(id)
                try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                try Data([1, 2, 3]).write(to: parent.appendingPathComponent(filename))
                return .init(id: id, name: filename)
            }
            try JSONEncoder().encode(UnyriShareStore.Batch(entries: entries, accountKey: owner))
                .write(to: directory.appendingPathComponent("manifest.json"))
            return (directory, entries)
        }

        check(UnyriShareStore.validAccountKey(a), "opaque account key accepted")
        check(!UnyriShareStore.validAccountKey("A" + a.dropFirst()), "uppercase key rejected")
        check(!UnyriShareStore.validAccountKey("../"), "invalid key rejected")
        #if canImport(CryptoKit)
        let key = UnyriShareStore.accountKey(storageOrigin: "https://example.test", userID: "A")
        check(key == "d3a0c821a179984b3119a01c36b61f73", "stable domain-separated SHA-256 prefix")
        check(key != UnyriShareStore.accountKey(storageOrigin: "https://other.test", userID: "A"), "server isolation")
        check(key != UnyriShareStore.accountKey(storageOrigin: "https://example.test", userID: "B"), "user isolation")
        #endif
        for (saved, preferred, expected) in [(nil, "uk-UA", "uk"), ("system", "ru-RU", "ru"),
                                             ("obsolete", "uk", "uk"), ("en", "uk-UA", "en"),
                                             ("uk", "ru", "uk"), (nil, "fr", "en")] as [(String?, String, String)] {
            check(UnyriShareStore.language(saved: saved, preferred: preferred) == expected, "app/system language selection")
        }
        for image in ["photo.JPG", "photo.heic", "photo.png", "photo.gif", "photo.webp"] {
            check(UnyriShareStore.isImage(image), "photo classification: \(image)")
        }
        check(!UnyriShareStore.isImage("report.pdf"), "document stays a file")
        check(!UnyriShareStore.isImage("drawing.svg"), "vector drawing stays a file")

        let saved = ChatSummary(chatId: "saved", chatType: "saved")
        let blocked = ChatSummary(chatId: "blocked", chatType: "direct", isBlocked: true)
        let group = ChatSummary(chatId: "group", chatType: "group")
        let peer = ChatSummary(chatId: "peer", chatType: "direct", otherUser: .init(displayName: "Anna", username: "anna"))
        var picker = PickerContract(chats: [blocked, group, peer, saved], selected: ["blocked", "peer"])
        check(picker.visibleIDs == ["saved", "group", "peer"], "Saved first, blocked excluded, remaining order preserved")
        check(picker.title(blocked) == "Chat" && picker.title(group) == "Chat", "missing otherUser never means Saved")
        check(picker.title(saved) == "Saved Messages", "saved identified by type")
        let invisiblePeer = ChatSummary(chatId: "hidden", chatType: "direct", otherUser: .init(displayName: "\u{FFF0}", username: "alice"))
        check(picker.title(invisiblePeer) == "@alice", "share picker uses sanitized handle fallback")
        check(picker.selectedIDs == ["peer"], "blocked chat excluded from send targets")
        picker.search = "saved"
        check(picker.visibleIDs == ["saved"], "Saved search uses its title")
        picker.search = "ANNA"
        check(picker.visibleIDs == ["peer"], "case-insensitive peer search")

        let (dirB, entriesB) = try batch("00-other", owner: b, count: 2)
        let (dirA, entriesA) = try batch("10-current", owner: a)
        let skipped = try UnyriShareStore.files(in: dirB, accountKey: a)
        check(skipped == nil, "foreign batch skipped")
        check(exists(dirB), "foreign batch preserved")
        let roundTrip = try JSONDecoder().decode(UnyriShareStore.Batch.self, from: Data(contentsOf: dirA.appendingPathComponent("manifest.json")))
        check(roundTrip.accountKey == a && roundTrip.entries.first?.id == entriesA.first?.id, "bound manifest round trip")
        let io = UnyriShareStore.InboxIO(root: { contractRootURL })
        let inbox = UnyriIncomingFile(io: io)
        var currentScope: UnyriShareStore.InboxScope?
        @MainActor func scan(_ key: String?, offerLegacy: Bool = true) async {
            let requested = key.map { UnyriShareStore.InboxScope(accountKey: $0, generation: $0 == a ? 1 : 2) }
            currentScope = requested
            if let task = inbox.checkSharedInbox(scope: requested, offerLegacy: offerLegacy, current: { currentScope == requested }) {
                await task.value
            }
        }
        await scan(nil)
        check(inbox.url == nil && exists(dirA) && exists(dirB), "signed-out read touches no batch")
        UIApplication.shared.isProtectedDataAvailable = false
        await scan(a)
        check(inbox.url == nil && exists(dirA), "locked inbox untouched")
        UIApplication.shared.isProtectedDataAvailable = true
        await scan(a)
        check(inbox.files.first?.id == entriesA.first?.id, "account skips earlier foreign batch")
        try await inbox.acknowledge(entriesA[0].id)
        inbox.close(discardBatch: false)
        check(exists(dirA.appendingPathComponent(entriesA[0].id + ".done")), "switch preserves completion marker")
        check(inbox.url == nil && exists(dirA) && exists(dirB), "switch detaches without deleting either batch")
        await scan(a)
        check(!exists(dirA) && exists(dirB), "empty owned batch removed, foreign one kept")
        await scan(b)
        check(inbox.files.count == 2, "other account can resume its batch")
        try await inbox.acknowledge(entriesB[0].id)
        inbox.close(discardBatch: false)
        await scan(b)
        check(inbox.files.count == 1 && inbox.files[0].id == entriesB[1].id, "partial send resumes remaining item")
        inbox.close()
        try await io.perform { () }
        check(!exists(dirB), "explicit cancellation discards owned copies")
        let openedDocument = root.appendingPathComponent("opened-directly.pdf")
        try Data([4]).write(to: openedDocument)
        let directInbox = UnyriIncomingFile(io: io)
        directInbox.receive(openedDocument)
        directInbox.checkSharedInbox(scope: .init(accountKey: a, generation: 1), current: { true })
        check(directInbox.url == openedDocument, "asynchronous discovery preserves a directly opened document")
        directInbox.close()
        check(exists(openedDocument), "closing a direct document preserves its original")

        let (legacy, legacyEntries) = try batch("legacy", owner: nil)
        rejects({ _ = try UnyriShareStore.files(in: legacy, accountKey: a) }, "unbound batch cannot be adopted")
        await scan(a)
        let quarantined = try UnyriShareStore.quarantineLegacy(root: root)
        check(inbox.url == nil && inbox.hasLegacy && inbox.legacyItemCount == 1, "legacy recovery offered without assigning an owner")
        check(quarantined.count == 1 && !exists(legacy), "legacy batch moved intact into quarantine")
        let unbound = try UnyriShareStore.readBatch(in: quarantined[0])
        check(unbound.accountKey == nil && unbound.entries[0].id == legacyEntries[0].id, "quarantine never assigns ownership")
        inbox.close(discardBatch: false)
        await scan(a, offerLegacy: false)
        check(!inbox.hasPendingImport && exists(quarantined[0]), "Close preserves quarantine without immediately reopening it")
        await scan(a)
        try await inbox.importLegacy(binding: bindingA)
        check(inbox.files.first?.id == legacyEntries[0].id && !inbox.hasLegacy, "explicit Import binds to chosen account and opens confirmation picker")
        let recovered = try UnyriShareStore.readyDirectories(root: root)
        let recoveredManifest = try UnyriShareStore.readBatch(in: recovered[0])
        check(recoveredManifest.accountKey == a, "user-selected account persists in recovered manifest")
        inbox.close()
        _ = try batch("keep-legacy", owner: nil)
        await scan(a)
        let kept = try UnyriShareStore.quarantineLegacy(root: root)
        try UnyriShareStore.sweep(root: root, olderThan: 3600, now: Date().addingTimeInterval(365 * 86400))
        check(exists(kept[0]), "quarantine is never expired automatically")
        try await inbox.discardLegacy()
        check(!exists(kept[0]) && !inbox.hasPendingImport, "only explicit Discard removes unbound files")
        let (corrupt, _) = try batch("corrupt", owner: "bad")
        rejects({ _ = try UnyriShareStore.files(in: corrupt, accountKey: a) }, "bad account key rejected")
        try fm.removeItem(at: corrupt)

        let source = root.appendingPathComponent("source.bin"), output = root.appendingPathComponent("output.bin")
        let payload = Data(repeating: 37, count: 3 * 256 * 1024)
        try payload.write(to: source)
        let copied = try UnyriShareStore.copy(source, to: output, limit: payload.count, cancellation: .init())
        let copiedData = try Data(contentsOf: output)
        check(copied == payload.count && copiedData == payload, "bounded copy preserves bytes")
        try fm.removeItem(at: output)
        let preCancelled = UnyriShareStore.CopyCancellation(); preCancelled.cancel()
        rejects({ _ = try UnyriShareStore.copy(source, to: output, limit: payload.count, cancellation: preCancelled) }, "pre-cancelled copy rejected")
        check(!exists(output), "pre-cancel creates no partial file")
        let midCopy = UnyriShareStore.CopyCancellation()
        var chunks = 0
        shareCopyChunkHook = { chunks += 1; midCopy.cancel() }
        rejects({ _ = try UnyriShareStore.copy(source, to: output, limit: payload.count, cancellation: midCopy) }, "Close interrupts copy")
        shareCopyChunkHook = nil
        check(chunks == 1 && !exists(output), "Close stops at next chunk and removes partial copy")
        rejects({ _ = try UnyriShareStore.copy(source, to: output, limit: payload.count - 1, cancellation: .init()) }, "actual bytes enforce ceiling")
        check(!exists(output), "oversized copy removes partial file")
        try Data().write(to: source)
        let emptyBytes = try UnyriShareStore.copy(source, to: output, limit: 0, cancellation: .init())
        check(emptyBytes == 0, "empty file copy terminates")
        check(exists(source), "original file preserved")

        // Real store operations used by sign-out/removal purgers, including a
        // provider that resumes after revocation and after the same user logs in.
        func fill(_ request: UnyriShareStore.StagingRequest) throws -> UnyriShareStore.Entry {
            let entry = UnyriShareStore.Entry(id: UUID().uuidString.lowercased(), name: "shared.pdf")
            let folder = try request.itemFolder(id: entry.id)
            try Data([7]).write(to: folder.appendingPathComponent(entry.name))
            return entry
        }
        func readyCount(_ expected: Int, _ why: String) throws {
            let count = try UnyriShareStore.importableReadyBatchCount(root: root)
            check(count == expected, why)
        }
        let pendingA = try UnyriShareStore.StagingRequest(binding: bindingA, root: root)
        let pendingEntry = try fill(pendingA)
        let pendingB = try UnyriShareStore.StagingRequest(binding: bindingB, root: root)
        try pendingB.commit(entries: [fill(pendingB)])
        try readyCount(1, "unfinished staging does not consume a ready slot")
        await scan(b)
        inbox.close(discardBatch: false)
        check(exists(pendingB.readyDirectory) && exists(pendingA.directory), "account switch preserves pending data and writer authority")
        try UnyriShareStore.purgeAccount(a, root: root)
        check(!exists(pendingA.directory) && exists(pendingB.readyDirectory), "logout/removal purges only departing account")
        rejects({ try pendingA.commit(entries: [pendingEntry]) }, "late commit after logout must fail")
        rejects({ _ = try pendingA.itemFolder(id: UUID().uuidString) }, "late callback must not recreate revoked parents")
        let freshA = try UnyriShareStore.prepareAccount(a, root: root)
        check(freshA.id != bindingA.id, "re-login gets a fresh writable parent")
        rejects({ try pendingA.commit(entries: [pendingEntry]) }, "old writer remains revoked after re-login")
        let newA = try UnyriShareStore.StagingRequest(binding: freshA, root: root)
        try newA.commit(entries: [fill(newA)])
        try readyCount(2, "new login can share, inactive signed-in account remains importable")

        // Completion, malformed and legacy batches cannot falsely fill the cap.
        let (done, doneEntries) = try batch("done", owner: b)
        try Data().write(to: done.appendingPathComponent(doneEntries[0].id + ".done"))
        let (bad, _) = try batch("bad-size", owner: b)
        try Data("not json".utf8).write(to: bad.appendingPathComponent("manifest.json"))
        _ = try batch("retained-unbound", owner: nil)
        let retainedLegacy = try UnyriShareStore.quarantineLegacy(root: root).filter { (try? UnyriShareStore.readBatch(in: $0)) != nil }
        try readyCount(2, "done, corrupt and quarantine batches excluded from ready limit")
        let moreB = try UnyriShareStore.StagingRequest(binding: bindingB, root: root)
        try moreB.commit(entries: [fill(moreB)])
        let fourth = try UnyriShareStore.StagingRequest(binding: bindingB, root: root)
        try fourth.commit(entries: [fill(fourth)])
        try readyCount(UnyriShareStore.maxReadyBatches, "four valid pending batches reach the limit")
        try UnyriShareStore.purgeAccount(a, root: root)
        try readyCount(3, "removal releases ready slots immediately")

        // The .adopting move records user consent before the manifest changes.
        let adopting = UnyriShareStore.accountDirectory(bindingB, root: root).appendingPathComponent(UUID().uuidString + ".adopting")
        try fm.moveItem(at: retainedLegacy[0], to: adopting)
        try UnyriShareStore.recoverAdoptions(accountKey: b, root: root)
        check(!exists(adopting), "interrupted user-approved adoption recovers to ready")
        try readyCount(4, "recovered adoption belongs only to the approved account")
        let interrupted = try UnyriShareStore.prepareAccount(a, root: root)
        let interruptedRequest = try UnyriShareStore.StagingRequest(binding: interrupted, root: root)
        try interruptedRequest.commit(entries: [fill(interruptedRequest)])
        let interruptedParent = UnyriShareStore.accountDirectory(interrupted, root: root)
        let trash = interruptedParent.deletingPathExtension().appendingPathExtension("purging")
        try fm.moveItem(at: interruptedParent, to: trash)
        try readyCount(4, "interrupted revocation no longer counts as importable")
        try UnyriShareStore.purgeAccount(a, root: root)
        check(!exists(trash), "retry completes interrupted account erasure")

        // Request teardown unlinks all copied items before provider continuation.
        let closing = try UnyriShareStore.StagingRequest(binding: bindingB, root: root)
        let closeEntry = try fill(closing)
        let closeFolder = try closing.itemFolder(id: UUID().uuidString)
        try payload.write(to: source)
        var unlinkedBeforeContinuation = false
        #if os(Windows)
        // Windows cannot unlink open FileHandles like iOS/macOS. Exercise the
        // never-resumed-provider case here; Darwin also covers an open writer.
        try closing.cancelAndRemove()
        unlinkedBeforeContinuation = !exists(closing.directory)
        #else
        shareCopyChunkHook = {
            try! closing.cancelAndRemove()
            unlinkedBeforeContinuation = !exists(closing.directory)
        }
        #endif
        rejects({ _ = try UnyriShareStore.copy(source, to: closeFolder.appendingPathComponent("partial.bin"), limit: payload.count, cancellation: closing.cancellation) }, "Close interrupts active provider copy")
        shareCopyChunkHook = nil
        check(unlinkedBeforeContinuation, "staging removed synchronously before copy/continuation returns")
        rejects({ try closing.commit(entries: [closeEntry]) }, "closed request cannot publish ready files")
        let abandoned = try UnyriShareStore.StagingRequest(binding: bindingB, root: root)
        _ = try fill(abandoned)
        _ = try batch("last-legacy", owner: nil)
        let lastLegacy = try UnyriShareStore.quarantineLegacy(root: root)
        try UnyriShareStore.sweep(root: root, olderThan: 3600, now: Date().addingTimeInterval(3601))
        check(!exists(abandoned.directory) && exists(lastLegacy[0]), "app event recovers abandoned staging, keeps legacy shares")
        let recentLegacyStage = root.appendingPathComponent(UUID().uuidString + ".staging", isDirectory: true)
        let recentPayload = recentLegacyStage.appendingPathComponent("copied-before-manifest.bin")
        try fm.createDirectory(at: recentLegacyStage, withIntermediateDirectories: false)
        try Data([9, 8, 7]).write(to: recentPayload)
        check(exists(recentPayload) && !exists(recentLegacyStage.appendingPathComponent("manifest.json")), "recent legacy staging has plaintext and no manifest")
        try UnyriShareStore.purgeAllAccounts(root: root)
        check(!exists(recentLegacyStage) && !exists(recentPayload), "last-account erasure removes recent manifest-less legacy staging regardless of age")
        try readyCount(0, "last-account/terminal logout clears bound inbox")
        check(exists(lastLegacy[0]), "last logout cannot decide the owner of legacy data")

        for bytes in [UnyriShareStore.maxPreservedImageBytes - 1, UnyriShareStore.maxPreservedImageBytes] {
            check(UnyriShareStore.imageSendKind(bytes: bytes, preservesOriginal: true) == "image", "GIF/WebP at or below 25 MB remains image")
        }
        for bytes in [UnyriShareStore.maxPreservedImageBytes + 1, UnyriShareStore.maxFileBytes] {
            check(UnyriShareStore.imageSendKind(bytes: bytes, preservesOriginal: true) == "file", "oversized preserved bytes take sendable file route")
            check(UnyriShareStore.imageSendKind(bytes: bytes, preservesOriginal: false) == "image", "large still photo is downsampled")
        }

        // Hold real production I/O mid-discovery, while MainActor keeps serving
        // input/session changes. Snapshot publication must validate live auth.
        let raceRoot = root.appendingPathComponent("discovery-interleavings")
        try fm.createDirectory(at: raceRoot, withIntermediateDirectories: false)
        let raceA = try UnyriShareStore.prepareAccount(a, root: raceRoot)
        let raceB = try UnyriShareStore.prepareAccount(b, root: raceRoot)
        let raceRequestA = try UnyriShareStore.StagingRequest(binding: raceA, root: raceRoot)
        let raceEntryA = try fill(raceRequestA)
        try raceRequestA.commit(entries: [raceEntryA])
        let raceRequestB = try UnyriShareStore.StagingRequest(binding: raceB, root: raceRoot)
        let raceEntryB = try fill(raceRequestB)
        try raceRequestB.commit(entries: [raceEntryB])
        let gate = InboxRootGate(root: raceRoot)
        let raceIO = UnyriShareStore.InboxIO(root: { try gate.root() })
        let raceInbox = UnyriIncomingFile(io: raceIO)
        let accountA = UnyriShareStore.InboxScope(accountKey: a, generation: 10)
        let accountB = UnyriShareStore.InboxScope(accountKey: b, generation: 11)
        let reloggedA = UnyriShareStore.InboxScope(accountKey: a, generation: 12)
        var live: UnyriShareStore.InboxScope? = accountA
        func waitForGate() async {
            let deadline = Date().addingTimeInterval(5)
            while !gate.entered {
                precondition(Date() < deadline, "I/O gate not reached")
                await Task.yield()
            }
        }
        gate.arm()
        let oldAccount = raceInbox.checkSharedInbox(scope: accountA, current: { live == accountA })!
        await waitForGate()
        let heartbeat = Task { @MainActor in true }
        let responsive = await heartbeat.value
        check(responsive && gate.workerWasOffMain, "render/input actor remains responsive while I/O is blocked")
        live = accountB
        gate.open()
        await oldAccount.value
        check(!raceInbox.hasPendingImport, "account change rejects old discovery even without an explicit close")
        let newAccount = raceInbox.checkSharedInbox(scope: accountB, current: { live == accountB })!
        await newAccount.value
        check(raceInbox.files.first?.id == raceEntryB.id, "new account publishes only its own snapshot")

        raceInbox.close(discardBatch: false)
        live = accountA
        gate.arm()
        let oldSession = raceInbox.checkSharedInbox(scope: accountA, current: { live == accountA })!
        await waitForGate()
        live = reloggedA
        gate.open()
        await oldSession.value
        check(!raceInbox.hasPendingImport, "same-account session generation change rejects stale discovery")
        let newSession = raceInbox.checkSharedInbox(scope: reloggedA, current: { live == reloggedA })!
        await newSession.value
        check(raceInbox.files.first?.id == raceEntryA.id, "current generation can rediscover preserved files")

        raceInbox.close(discardBatch: false)
        gate.arm()
        let locked = raceInbox.checkSharedInbox(scope: reloggedA, current: { live == reloggedA })!
        await waitForGate()
        UIApplication.shared.isProtectedDataAvailable = false
        gate.open()
        await locked.value
        check(!raceInbox.hasPendingImport, "locking during discovery prevents plaintext UI publication")
        UIApplication.shared.isProtectedDataAvailable = true

        raceInbox.close(discardBatch: false)
        gate.arm()
        let dismissed = raceInbox.checkSharedInbox(scope: reloggedA, current: { live == reloggedA })!
        await waitForGate()
        raceInbox.close(discardBatch: false)
        gate.open()
        await dismissed.value
        check(!raceInbox.hasPendingImport && exists(raceRequestA.readyDirectory), "Close cancels late publication without deleting preserved data")

        gate.arm()
        let logoutScan = raceInbox.checkSharedInbox(scope: reloggedA, current: { live == reloggedA })!
        await waitForGate()
        live = nil
        raceInbox.close(discardBatch: false)
        let cancelledPreparation = Task { try await raceIO.prepareAccount(a) }
        cancelledPreparation.cancel()
        let logout = Task { try await raceIO.purgeAllAccounts() }
        gate.open()
        await logoutScan.value
        try await logout.value
        do { _ = try await cancelledPreparation.value; preconditionFailure("cancelled publication recreated account permission") }
        catch is CancellationError { checks += 1 }
        check(!raceInbox.hasPendingImport && !exists(raceRequestA.readyDirectory) && !exists(raceRequestB.readyDirectory), "serialized logout drains recovery without stale UI or surviving bound files")
        let accountParents = try fm.contentsOfDirectory(at: raceRoot, includingPropertiesForKeys: nil).filter { $0.pathExtension == "account" }
        check(accountParents.isEmpty, "cancelled account preparation cannot revive a logged-out account")

        // Reproduce the lost-event window: the worker has already read an empty
        // inbox, but MainActor has not published it when an extension commits.
        let refreshRoot = root.appendingPathComponent("refresh-events")
        try fm.createDirectory(at: refreshRoot, withIntermediateDirectories: false)
        let refreshBinding = try UnyriShareStore.prepareAccount(a, root: refreshRoot)
        let refreshIO = UnyriShareStore.InboxIO(root: { refreshRoot })
        let refreshInbox = UnyriIncomingFile(io: refreshIO)
        let refreshScope = UnyriShareStore.InboxScope(accountKey: a, generation: 20)
        let snapshotGate = InboxSnapshotGate()
        snapshotGate.arm()
        shareDiscoverySnapshotHook = { snapshot in await snapshotGate.observe(snapshot) }
        defer { shareDiscoverySnapshotHook = nil }
        let firstRead = refreshInbox.checkSharedInbox(scope: refreshScope, current: { true })!
        let snapshotDeadline = Date().addingTimeInterval(5)
        while !snapshotGate.entered {
            precondition(Date() < snapshotDeadline, "snapshot gate not reached")
            await Task.yield()
        }
        check(snapshotGate.firstWasEmpty && !refreshInbox.hasPendingImport, "initial empty snapshot is held before publication")
        let extensionCommit = try UnyriShareStore.StagingRequest(binding: refreshBinding, root: refreshRoot)
        let extensionEntry = try fill(extensionCommit)
        try extensionCommit.commit(entries: [extensionEntry])
        for _ in 0..<25 { refreshInbox.checkSharedInbox(scope: refreshScope, current: { true }) }
        snapshotGate.open()
        await firstRead.value
        shareDiscoverySnapshotHook = nil
        check(snapshotGate.calls == 2, "event burst retains exactly one rescan rather than dropping it or creating a backlog")
        check(refreshInbox.files.first?.id == extensionEntry.id, "refresh discards obsolete empty snapshot and publishes new extension batch")
        refreshInbox.close(discardBatch: false)

        @MainActor func waitUntilEntered(_ candidate: InboxRootGate) async {
            let deadline = Date().addingTimeInterval(5)
            while !candidate.entered {
                precondition(Date() < deadline, "worker gate not reached")
                await Task.yield()
            }
        }
        let unlockGate = InboxRootGate(root: refreshRoot)
        let unlockIO = UnyriShareStore.InboxIO(root: { try unlockGate.root() })
        let unlockInbox = UnyriIncomingFile(io: unlockIO)
        unlockGate.arm(fail: true)
        let protectedRead = unlockInbox.checkSharedInbox(scope: refreshScope, current: { true })!
        await waitUntilEntered(unlockGate)
        UIApplication.shared.isProtectedDataAvailable = false
        UIApplication.shared.isProtectedDataAvailable = true
        unlockInbox.checkSharedInbox(scope: refreshScope, current: { true })
        unlockGate.open()
        await protectedRead.value
        check(unlockGate.callCount == 2 && unlockInbox.files.first?.id == extensionEntry.id,
              "unlock refresh retries an in-flight protected read failure and publishes the fresh result")
        unlockInbox.close(discardBatch: false)

        let quietGate = InboxRootGate(root: refreshRoot)
        let quietInbox = UnyriIncomingFile(io: .init(root: { try quietGate.root() }))
        quietGate.arm(fail: true)
        let quietFailure = quietInbox.checkSharedInbox(scope: refreshScope, current: { true })!
        await waitUntilEntered(quietGate)
        quietGate.open()
        await quietFailure.value
        check(quietGate.callCount == 1 && !quietInbox.hasPendingImport, "a failure without a newer event does not create an autonomous retry loop")

        // Extension setup performs recovery/counting on its own worker. Close
        // can cancel it before or after the result returns, with no later staging.
        let extensionGate = InboxRootGate(root: refreshRoot)
        let extensionIO = UnyriShareStore.InboxIO(label: "contracts.share.extension.io", root: { try extensionGate.root() })
        let preparationCancellation = UnyriShareStore.CopyCancellation()
        let interruptedCleanup = refreshRoot.appendingPathComponent(a + "." + UUID().uuidString.lowercased() + ".purging")
        try fm.createDirectory(at: interruptedCleanup, withIntermediateDirectories: false)
        try Data([1]).write(to: interruptedCleanup.appendingPathComponent("interrupted.bin"))
        extensionGate.arm()
        let preparing = Task { try await extensionIO.prepareExtensionShare(cancellation: preparationCancellation) }
        await waitUntilEntered(extensionGate)
        let closeHeartbeat = Task { @MainActor in true }
        let closeResponsive = await closeHeartbeat.value
        check(closeResponsive && extensionGate.workerWasOffMain, "extension Close actor remains responsive while setup I/O is blocked")
        preparationCancellation.cancel(); preparing.cancel()
        extensionGate.open()
        do { _ = try await preparing.value; preconditionFailure("cancelled extension setup continued") }
        catch is CancellationError { checks += 1 }
        let refreshParent = UnyriShareStore.accountDirectory(refreshBinding, root: refreshRoot)
        let stagesAfterClose = try fm.contentsOfDirectory(at: refreshParent, includingPropertiesForKeys: nil).filter { $0.pathExtension == "staging" }
        check(stagesAfterClose.isEmpty && exists(interruptedCleanup), "Close before setup completes creates no request files and stops recovery before the scan")

        let returnedCancellation = UnyriShareStore.CopyCancellation()
        let returnedPreparation = try await extensionIO.prepareExtensionShare(cancellation: returnedCancellation)
        check(returnedPreparation.readyBatches == 1 && !exists(interruptedCleanup), "worker setup recovers interrupted erasure and counts importable ready batches")
        returnedCancellation.cancel()
        rejects({ _ = try returnedPreparation.makeRequest(binding: refreshBinding) }, "Close after setup returns prevents late staging creation")
        let stagesAfterReturn = try fm.contentsOfDirectory(at: refreshParent, includingPropertiesForKeys: nil).filter { $0.pathExtension == "staging" }
        check(stagesAfterReturn.isEmpty, "cancelled prepared result leaves no staging directory after request teardown")

        let successfulPreparation = try await extensionIO.prepareExtensionShare(cancellation: .init())
        let ownedRequest = try successfulPreparation.makeRequest(binding: refreshBinding)
        let ownedEntry = try fill(ownedRequest)
        let ownedPayload = ownedRequest.directory.appendingPathComponent(ownedEntry.id).appendingPathComponent(ownedEntry.name)
        check(exists(ownedPayload), "successful worker preparation hands staging ownership to the controller")
        try ownedRequest.cancelAndRemove()
        check(!exists(ownedRequest.directory) && !exists(ownedPayload), "owned staging cleanup completes synchronously before ending the extension request")
        print("Share phase 0 Foundation contracts passed: \(checks) checks")
    }
}
