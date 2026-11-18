import Foundation
import SQLite3

@main struct AttachmentCacheRelocationContracts {
    static var checks = 0
    static func check(_ value: Bool, _ why: String) {
        checks += 1
        precondition(value, why)
    }
    static func row(_ id: String, _ path: String) -> AttachmentRecord {
        AttachmentRecord(attachmentId:id,clientMessageId:id,chatId:"owned-chat",mediaType:"file",mimeType:"video/mp4",
            filename:nil,size:8,width:2,height:3,durationMs:12000,thumbnailBase64:nil,localPath:path,
            downloadState:"downloaded",uploadState:"uploaded",progress:100,objectKey:"owned-object-"+id,
            encryptedSize:24,encryptionVersion:1,keyBase64:nil,nonceBase64:nil,keysetBase64:nil,sha256Base64:"owned-checksum")
    }
    static func account(_ container: URL, origin: String = "same-server", owner: String = "11111111-1111-4111-8111-111111111111") -> URL {
        container.appendingPathComponent("Library/Application Support/COCO/"+origin+"/"+owner,isDirectory:true)
    }
    static func file(_ path: URL) throws {
        try FileManager.default.createDirectory(at:path.deletingLastPathComponent(),withIntermediateDirectories:true)
        try Data("owned-fixture".utf8).write(to:path)
    }
    static func sql(_ database: URL, _ command: String) throws {
        var handle: OpaquePointer?
        precondition(sqlite3_open(database.path,&handle)==SQLITE_OK)
        defer { sqlite3_close(handle) }
        guard sqlite3_exec(handle,command,nil,nil,nil)==SQLITE_OK else { throw NSError(domain:"OwnFixtureSQL",code:1) }
    }
    static func literal(_ value: String) -> String { "'" + value.replacingOccurrences(of: "'", with: "''") + "'" }
    static func marker(_ database: URL, _ key: String) throws -> String? {
        var handle: OpaquePointer?, statement: OpaquePointer?
        guard sqlite3_open_v2(database.path, &handle, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { throw AttachmentStore.StoreError.openFailed }
        defer { sqlite3_close(handle) }
        guard sqlite3_prepare_v2(handle, "SELECT value FROM attachment_store_metadata WHERE key=" + literal(key), -1, &statement, nil) == SQLITE_OK else { throw AttachmentStore.StoreError.stepFailed }
        defer { sqlite3_finalize(statement) }
        let step = sqlite3_step(statement)
        if step == SQLITE_ROW { return String(cString: sqlite3_column_text(statement, 0)) }
        guard step == SQLITE_DONE else { throw AttachmentStore.StoreError.stepFailed }
        return nil
    }
    final class OpeningProbe: @unchecked Sendable {
        let began = AsyncStream<Void>.makeStream(), committing = AsyncStream<Void>.makeStream(), registered = AsyncStream<Void>.makeStream()
        let allowScan = DispatchSemaphore(value: 0), allowCommit = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var visited: [String] = []
        func visit(_ path: String) { lock.lock(); visited.append(path); lock.unlock() }
        var paths: [String] { lock.lock(); defer { lock.unlock() }; return visited }
        func pause(_ gate: DispatchSemaphore) throws {
            precondition(!Thread.isMainThread, "Migration ran on the launch/UI thread")
            guard gate.wait(timeout: .now() + 5) == .success else { throw NSError(domain: "OpeningGateTimeout", code: 1) }
        }
    }
    @MainActor static func verifyNarrowDraftGate(root: URL) async throws {
        let fm = FileManager.default
        let application = root.appendingPathComponent("startup/Containers/Data/Application")
        let old = application.appendingPathComponent(UUID().uuidString), moved = application.appendingPathComponent(UUID().uuidString)
        let folder = UUID().uuidString
        let oldVoice = account(old).appendingPathComponent("draft-media/" + folder + "/voice.m4a")
        try file(oldVoice); try file(account(old).appendingPathComponent("attachments/photo.bin"))
        let oldVoicePath = oldVoice.resolvingSymlinksInPath().path
        do {
            let seed = try AttachmentStore(databaseURL: account(old).appendingPathComponent("attachments.sqlite3"))
            for index in 0..<600 { try seed.upsert(row("cached-\(index)", account(old).appendingPathComponent("attachments/photo.bin").path)) }
            try seed.upsert(row("voice", oldVoicePath))
        }
        try fm.moveItem(at: old, to: moved)
        let database = account(moved).appendingPathComponent("attachments.sqlite3")
        let cache = account(moved).appendingPathComponent("attachments").resolvingSymlinksInPath().path
        let voice = account(moved).appendingPathComponent("draft-media/" + folder + "/voice.m4a").resolvingSymlinksInPath().path
        try sql(database, "UPDATE attachments SET local_path=" + literal(cache + "/photo.bin") + " WHERE attachment_id LIKE 'cached-%'; DELETE FROM attachment_store_metadata WHERE key='local_roots_v2'; INSERT OR REPLACE INTO attachment_store_metadata(key,value) VALUES ('cache_root_v1'," + literal(cache) + ");")
        let probe = OpeningProbe()
        // Opening returns synchronously; even the scan's start hook cannot run
        // until a consumer requests normalized draft ownership.
        let store = try AttachmentStore(databaseURL: database, draftMigrationHooks: .init(beforeMigration: {
            probe.began.continuation.yield(()); try probe.pause(probe.allowScan)
        }, willInspectPath: { probe.visit($0) }, beforeCommit: {
            probe.committing.continuation.yield(()); try probe.pause(probe.allowCommit)
        }, didRegisterWaiter: { probe.registered.continuation.yield(()) }))
        check(probe.paths.isEmpty, "Synchronous launch does not start or await the draft scan")
        check(try marker(database, "local_roots_v2") == nil, "Opening does not publish a draft marker")
        check(try store.attachment(byId: "voice")?.localPath == oldVoicePath, "Ordinary readers are available before draft normalization")
        var observed: String?
        let reader = Task { @MainActor in
            try await store.waitForDraftReferences()
            observed = try store.attachment(byId: "voice")?.localPath
        }
        var registrations = probe.registered.stream.makeAsyncIterator()
        _ = await registrations.next()
        for await _ in probe.began.stream { break }
        check(try store.attachments(chatId: "owned-chat").count == 601, "Chat/gallery readers do not wait on the narrow gate")
        // A different account has a separate gate/connection and can finish.
        let otherDirectory = account(application.appendingPathComponent(UUID().uuidString))
        try fm.createDirectory(at: otherDirectory, withIntermediateDirectories: true)
        let other = try AttachmentStore(databaseURL: otherDirectory.appendingPathComponent("attachments.sqlite3"))
        try await other.waitForDraftReferences()
        check(try marker(otherDirectory.appendingPathComponent("attachments.sqlite3"), "local_roots_v2") != nil, "One account cannot hold another account's draft gate")
        probe.allowScan.signal()
        for await _ in probe.committing.stream { break }
        var snapshot: Set<URL>?
        let referenceReader = store.draftSourceReferences()
        let ownership = Task { @MainActor in snapshot = try await referenceReader() }
        _ = await registrations.next()
        let cancelled = Task { @MainActor in try await store.waitForDraftReferences() }
        _ = await registrations.next()
        cancelled.cancel()
        do { try await cancelled.value; preconditionFailure("Cancelled waiter completed") } catch is CancellationError {}
        check(observed == nil && snapshot == nil, "Only normalized draft readers and cleanup wait for COMMIT")
        do { _ = try store.referencesLocalPath(voice); preconditionFailure("Cleanup bypassed pending migration") }
        catch AttachmentStore.StoreError.draftReferencesNotReady {}
        check(fm.fileExists(atPath: voice), "Pending ownership cannot delete the attachment source")
        check(try store.attachment(byId: "cached-0")?.localPath == cache + "/photo.bin", "Ordinary reads remain usable during the draft transaction")
        check(probe.paths.count == 1 && probe.paths[0].contains("/draft-media/"), "A current v1 marker skips every unchanged cache path")
        check(try marker(database, "local_roots_v2") == nil, "Draft marker is not visible before atomic commit")
        probe.allowCommit.signal()
        try await reader.value; try await ownership.value
        check(observed == voice && snapshot == Set([URL(fileURLWithPath: voice)]), "Narrow readers see repaired references after commit")
        check(try store.referencesLocalPath(voice), "Completed gate enables indexed ownership checks")
        check(try marker(database, "cache_root_v1") == cache, "Cache v1 marker remains valid alongside v2")
        check(try marker(database, "local_roots_v2") == account(moved).resolvingSymlinksInPath().path, "v2 commits with repaired rows")
        let noScan = OpeningProbe()
        let reopened = try AttachmentStore(databaseURL: database, draftMigrationHooks: .init(willInspectPath: { noScan.visit($0) }))
        try await reopened.waitForDraftReferences()
        check(noScan.paths.isEmpty, "A normal launch performs no repeated path scan")

        // Account retirement cancels and DRAINS its worker. Individual consumer
        // cancellation above returned while this same worker remained held.
        try sql(database, "UPDATE attachments SET local_path=" + literal(oldVoicePath) + " WHERE attachment_id='voice'; DELETE FROM attachment_store_metadata WHERE key='local_roots_v2';")
        let cancelledProbe = OpeningProbe()
        let retiring = try AttachmentStore(databaseURL: database, draftMigrationHooks: .init(beforeCommit: {
            cancelledProbe.committing.continuation.yield(()); try cancelledProbe.pause(cancelledProbe.allowCommit)
        }))
        let waiting = Task { @MainActor in
            do { try await retiring.waitForDraftReferences(); preconditionFailure("Retired gate completed") } catch is CancellationError {}
        }
        for await _ in cancelledProbe.committing.stream { break }
        var drained = false
        let retirement = Task { @MainActor in await retiring.cancelAndDrainDraftReferences(); drained = true }
        try await waiting.value // retire has cancelled the worker and released waiters
        check(!drained, "Account erasure waits until its migration worker drains")
        cancelledProbe.allowCommit.signal()
        await retirement.value
        let uncommitted = try AttachmentStore(databaseURL: database, readOnly: true)
        check(try uncommitted.attachment(byId: "voice")?.localPath == oldVoicePath, "Cancelled scan rolls repaired paths back")
        check(try marker(database, "local_roots_v2") == nil, "Cancelled scan rolls its marker back")
        check(try marker(database, "cache_root_v1") == cache, "Draft cancellation never resets the v1 cache marker")
        do { try await retiring.waitForDraftReferences(); preconditionFailure("Retired account restarted migration") } catch is CancellationError {}
        let recovered = try AttachmentStore(databaseURL: database)
        try await recovered.waitForDraftReferences()
        check(try recovered.attachment(byId: "voice")?.localPath == voice, "A cancelled pass retries successfully")
        // Real SQL failure after row updates must roll back both references and
        // marker; another consumer may retry without restarting account services.
        try sql(database, "UPDATE attachments SET local_path=" + literal(oldVoicePath) + " WHERE attachment_id='voice'; DELETE FROM attachment_store_metadata WHERE key='local_roots_v2'; CREATE TRIGGER reject_draft_marker BEFORE INSERT ON attachment_store_metadata WHEN NEW.key='local_roots_v2' BEGIN SELECT RAISE(ABORT,'draft failure'); END;")
        let retryable = try AttachmentStore(databaseURL: database)
        do { try await retryable.waitForDraftReferences(); preconditionFailure("Draft SQL failure was ignored") }
        catch AttachmentStore.StoreError.stepFailed {}
        check(try retryable.attachment(byId: "voice")?.localPath == oldVoicePath, "Draft SQL failure rolls every repaired row back")
        check(try marker(database, "local_roots_v2") == nil, "Failed draft pass cannot publish its marker")
        try sql(database, "DROP TRIGGER reject_draft_marker;")
        try await retryable.waitForDraftReferences()
        check(try retryable.attachment(byId: "voice")?.localPath == voice, "A narrow consumer retries a failed pass without rebuilding services")
        try sql(database, "DELETE FROM attachments WHERE attachment_id='voice'; DELETE FROM attachment_store_metadata WHERE key='local_roots_v2';")
        let noDrafts = OpeningProbe()
        let cacheOnly = try AttachmentStore(databaseURL: database, draftMigrationHooks: .init(willInspectPath: { noDrafts.visit($0) }))
        try await cacheOnly.waitForDraftReferences()
        check(noDrafts.paths.isEmpty, "Cache-only history needs no filesystem rescan when v1 is current")
    }
    #if os(macOS)
    @MainActor static func aliasRelocationContracts(base: String, other: String) async throws {
        let fm = FileManager.default
        let fixtureName = "unyri-alias-relocation-" + UUID().uuidString
        let fixture = URL(fileURLWithPath: base + "/" + fixtureName)
        let alternate = URL(fileURLWithPath: other + "/" + fixtureName)
        defer { try? fm.removeItem(at: fixture) }
        let appPath = "Containers/Data/Application/"
        let oldID = UUID().uuidString, newID = UUID().uuidString
        let old = fixture.appendingPathComponent(appPath + oldID)
        let oldAlternate = alternate.appendingPathComponent(appPath + oldID)
        let moved = fixture.appendingPathComponent(appPath + newID)
        // Persist the base spelling, then reopen the moved DB via the OTHER alias.
        let current = account(alternate.appendingPathComponent(appPath + newID))
        let folder = UUID().uuidString, shareFolder = "share-" + UUID().uuidString
        let sources = [("photo", "attachments/photo.bin"), ("video", "attachments/video-note.mp4"),
                       ("voice", "draft-media/" + folder + "/voice_1.m4a"),
                       ("video-draft", "draft-media/" + folder + "/video-note-1.mp4"),
                       ("share", "draft-media/" + shareFolder + "/photo 1.heic")]
        let oldDatabase = account(old).appendingPathComponent("attachments.sqlite3")
        let oldCanary = account(old, owner: "55555555-5555-4555-8555-555555555555").appendingPathComponent("attachments/canary.bin")
        try file(oldCanary)
        let canaryBytes = Data("sibling relocation canary".utf8)
        try canaryBytes.write(to: oldCanary)
        do {
            for (_, relative) in sources { try file(account(old).appendingPathComponent(relative)) }
            let seed = try AttachmentStore(databaseURL: oldDatabase)
            try await seed.waitForDraftReferences()
            for (id, relative) in sources { try seed.upsert(row(id, account(old).appendingPathComponent(relative).path)) }
            try seed.upsert(row("sibling", account(old, owner: "55555555-5555-4555-8555-555555555555").appendingPathComponent("attachments/canary.bin").path))
            // Bypass today's write normalizer to reproduce absolute SQLite paths
            // from an older build, exactly in the opposite alias spelling.
            for (id, relative) in sources {
                try sql(oldDatabase, "UPDATE attachments SET local_path=" + literal(account(old).appendingPathComponent(relative).path) + " WHERE attachment_id=" + literal(id) + ";")
            }
        }
        try fm.moveItem(at: old, to: moved)
        check(!fm.fileExists(atPath: old.path), "Alias relocation must remove the old container")
        let store = try AttachmentStore(databaseURL: current.appendingPathComponent("attachments.sqlite3"))
        try await store.waitForDraftReferences()
        let boundary = AttachmentCacheBoundary(root: current.appendingPathComponent("attachments"))
        let drafts = current.appendingPathComponent("draft-media")
        for (id, relative) in sources {
            guard let path = try store.attachment(byId: id)?.localPath else { preconditionFailure("Relocated source lost its path: " + id) }
            let readable = try boundary.readable(URL(fileURLWithPath: path), draftRoot: drafts)
            check(try Data(contentsOf: readable) == Data("owned-fixture".utf8), "Boundary must open relocated alias source: " + id)
            // The live FileManager spelling can differ from the stored repaired
            // path; both must identify this same current-container file.
            check(try boundary.readable(account(moved).appendingPathComponent(relative), draftRoot: drafts) == readable,
                  "Current container's other spelling must remain readable: " + id)
            if relative.hasPrefix("draft-media/") {
                check(try store.referencesLocalPath(account(old).appendingPathComponent(relative).path), "Old draft owner survives UUID and alias changes")
                check(try store.referencesLocalPath(account(oldAlternate).appendingPathComponent(relative).path), "Old draft owner also accepts its alternate alias")
            }
        }
        // Exact OS aliases are legitimate identities even after an old
        // container (or the current leaf) disappears. Custom links never are.
        let ownVoice = current.appendingPathComponent("draft-media/" + folder + "/voice_1.m4a")
        try fm.removeItem(at: ownVoice)
        check(try store.referencesLocalPath(ownVoice.path), "A missing owned draft retains its physical identity")
        check(try store.referencesLocalPath(account(moved).appendingPathComponent("draft-media/" + folder + "/voice_1.m4a").path),
              "A missing owned draft accepts its exact OS alias")
        try file(ownVoice)
        let linkedFolder = current.appendingPathComponent("draft-media/" + UUID().uuidString)
        try fm.createSymbolicLink(at: linkedFolder, withDestinationURL: ownVoice.deletingLastPathComponent())
        check(try !store.referencesLocalPath(linkedFolder.appendingPathComponent("voice_1.m4a").path),
              "Custom directory links cannot impersonate an owned draft")
        let leafLink = ownVoice.deletingLastPathComponent().appendingPathComponent("linked.m4a")
        try fm.createSymbolicLink(at: leafLink, withDestinationURL: ownVoice)
        check(try !store.referencesLocalPath(leafLink.path), "Custom leaf links cannot impersonate an owned draft")
        let dangling = ownVoice.deletingLastPathComponent().appendingPathComponent("dangling.m4a")
        try fm.createSymbolicLink(at: dangling, withDestinationURL: ownVoice.appendingPathExtension("missing"))
        check(try !store.referencesLocalPath(dangling.path), "Dangling links are not missing owned drafts")
        let customPrefix = fixture.appendingPathComponent("custom-prefix")
        try fm.createSymbolicLink(at: customPrefix, withDestinationURL: fixture)
        let customVoice = account(customPrefix.appendingPathComponent(appPath + newID))
            .appendingPathComponent("draft-media/" + folder + "/voice_1.m4a")
        check(try !store.referencesLocalPath(customVoice.path), "Custom device-prefix links cannot impersonate the OS alias")
        let traversal = drafts.path + "/" + folder + "/../" + folder + "/voice_1.m4a"
        check(try !store.referencesLocalPath(traversal), "Traversal cannot impersonate an owned draft")
        let foreignVoice = account(oldAlternate, owner: "55555555-5555-4555-8555-555555555555")
            .appendingPathComponent("draft-media/" + folder + "/voice_1.m4a")
        check(try !store.referencesLocalPath(foreignVoice.path), "An OS alias cannot change the owning account")
        let sibling = try store.attachment(byId: "sibling")?.localPath
        check(sibling == account(old, owner: "55555555-5555-4555-8555-555555555555").appendingPathComponent("attachments/canary.bin").path,
              "Alias relocation must not adopt a sibling account")
        let canary = account(moved, owner: "55555555-5555-4555-8555-555555555555").appendingPathComponent("attachments/canary.bin")
        do {
            _ = try boundary.readable(canary, draftRoot: drafts)
            preconditionFailure("Alias relocation admitted a sibling account")
        } catch is AttachmentCacheBoundary.Failure {}
        check(try Data(contentsOf: canary) == canaryBytes, "Alias relocation must preserve the sibling canary")
    }
    #endif

    @MainActor static func main() async {
        do { try await run() }
        catch { preconditionFailure("Cache relocation fixture/contract failed: \(error)") }
    }

    @MainActor static func run() async throws {
        #if os(macOS)
        for (base, other) in [("/var/tmp", "/private/var/tmp"), ("/private/var/tmp", "/var/tmp"),
                              ("/tmp", "/private/tmp"), ("/private/tmp", "/tmp")] {
            try await aliasRelocationContracts(base: base, other: other)
        }
        #endif
        let fm=FileManager.default
        let fixture=fm.temporaryDirectory.appendingPathComponent("coco-cache-relocation-"+UUID().uuidString,isDirectory:true)
        try fm.createDirectory(at:fixture,withIntermediateDirectories:true)
        defer { try? fm.removeItem(at:fixture) }
        try await verifyNarrowDraftGate(root: fixture)
        let application=fixture.appendingPathComponent("Containers/Data/Application",isDirectory:true)
        let old=application.appendingPathComponent("22222222-2222-4222-8222-222222222222",isDirectory:true)
        let new=application.appendingPathComponent("33333333-3333-4333-8333-333333333333",isDirectory:true)
        let oldAccount=account(old),newAccount=account(new)
        let oldCache=oldAccount.appendingPathComponent("attachments",isDirectory:true)
        let newCache=newAccount.appendingPathComponent("attachments",isDirectory:true)
        let oldDraft=oldAccount.appendingPathComponent("draft-media",isDirectory:true)
        let newDraft=newAccount.appendingPathComponent("draft-media",isDirectory:true)
        let voiceFolder=UUID().uuidString,shareFolder="share-"+UUID().uuidString.lowercased()
        try fm.createDirectory(at:oldCache,withIntermediateDirectories:true)
        let oldDB=oldAccount.appendingPathComponent("attachments.sqlite3"),newDB=newAccount.appendingPathComponent("attachments.sqlite3")
        let live=application.appendingPathComponent("44444444-4444-4444-8444-444444444444",isDirectory:true)
        let liveFile=account(live).appendingPathComponent("attachments/live.bin")
        try file(liveFile)
        let external=fixture.appendingPathComponent("outside.bin");try file(external)
        var input:[String:AttachmentRecord]=[:]
        do {
            let store=try AttachmentStore(databaseURL:oldDB)
            for name in ["photo","video","original","nested/inside","foreign-owner","foreign-server","foreign-prefix","live","current"] {
                try file(oldCache.appendingPathComponent(name+".bin"))
            }
            try fm.createSymbolicLink(at:oldCache.appendingPathComponent("symlink.bin"),withDestinationURL:external)
            try fm.createSymbolicLink(at:oldCache.appendingPathComponent("linked-directory"),withDestinationURL:oldCache.appendingPathComponent("nested"))
            try file(oldDraft.appendingPathComponent(voiceFolder+"/voice.m4a"))
            try file(oldDraft.appendingPathComponent(shareFolder+"/photo.heic"))
            try fm.createSymbolicLink(at:oldDraft.appendingPathComponent(voiceFolder+"/linked.m4a"),withDestinationURL:external)
            var candidates:[(String,String)]=[
                ("photo",oldCache.appendingPathComponent("photo.bin").path),
                ("video",oldCache.appendingPathComponent("video.bin").path),
                ("original",oldCache.appendingPathComponent("original.bin").path),
                ("nested",oldCache.appendingPathComponent("nested/inside.bin").path),
                ("missing",oldCache.appendingPathComponent("missing.bin").path),
                ("cross-owner",account(old,owner:"55555555-5555-4555-8555-555555555555").appendingPathComponent("attachments/foreign-owner.bin").path),
                ("cross-server",account(old,origin:"other-server").appendingPathComponent("attachments/foreign-server.bin").path),
                ("cross-prefix",fixture.appendingPathComponent("other-device/Containers/Data/Application/22222222-2222-4222-8222-222222222222/Library/Application Support/COCO/same-server/11111111-1111-4111-8111-111111111111/attachments/foreign-prefix.bin").path),
                ("symlink",oldCache.appendingPathComponent("symlink.bin").path),
                ("symlink-directory",oldCache.appendingPathComponent("linked-directory/inside.bin").path),
                ("traversal",oldCache.path+"/nested/../photo.bin"),
                ("existing-source",liveFile.path),
                ("already-current",newCache.appendingPathComponent("current.bin").path)]
            candidates += [
                ("voice",oldDraft.appendingPathComponent(voiceFolder+"/voice.m4a").path),
                ("shared-draft",oldDraft.appendingPathComponent(shareFolder+"/photo.heic").path),
                ("draft-missing",oldDraft.appendingPathComponent(voiceFolder+"/missing.m4a").path),
                ("draft-cross-owner",account(old,owner:"55555555-5555-4555-8555-555555555555").appendingPathComponent("draft-media/"+voiceFolder+"/voice.m4a").path),
                ("draft-cross-server",account(old,origin:"other-server").appendingPathComponent("draft-media/"+voiceFolder+"/voice.m4a").path),
                ("draft-symlink",oldDraft.appendingPathComponent(voiceFolder+"/linked.m4a").path)]
            for index in 0..<300 {
                let path=oldCache.appendingPathComponent("bulk/item-\(index).bin")
                try file(path);candidates.append(("bulk-\(index)",path.path))
            }
            for (id,path) in candidates { let record=row(id,path);input[id]=record;try store.upsert(record) }
        }
        // The real installer moves the private container while SQLite still
        // contains old absolute URLs. Reproduce that exact filesystem change.
        try fm.moveItem(at:old,to:new)
        try fm.removeItem(at:newCache.appendingPathComponent("linked-directory"))
        try fm.createSymbolicLink(at:newCache.appendingPathComponent("linked-directory"),withDestinationURL:newCache.appendingPathComponent("nested"))
        check(!fm.fileExists(atPath:old.path),"Old container must really be absent")
        do {
            let reader=try AttachmentStore(databaseURL:newDB,readOnly:true)
            check(try reader.attachment(byId:"photo")?.localPath==input["photo"]?.localPath,"Read-only query must not migrate or write")
        }
        do {
            let store=try AttachmentStore(databaseURL:newDB)
            for (id,relative) in [("photo","photo.bin"),("video","video.bin"),("original","original.bin"),("nested","nested/inside.bin")] {
                let actual=try store.attachment(byId:id)
                check(actual?.localPath==newCache.appendingPathComponent(relative).standardizedFileURL.resolvingSymlinksInPath().path,"Existing owned cache must survive container relocation: "+id)
                check(fm.fileExists(atPath:actual!.localPath!),"Repaired reference must identify the real original file")
            }
            try await store.waitForDraftReferences()
            for (id,relative) in [("voice",voiceFolder+"/voice.m4a"),("shared-draft",shareFolder+"/photo.heic"),("draft-missing",voiceFolder+"/missing.m4a")] {
                let path=newDraft.appendingPathComponent(relative).resolvingSymlinksInPath().path
                check(try store.attachment(byId:id)?.localPath==path,"Draft plaintext attachment reference migrated: "+id)
                check(try store.referencesLocalPath(input[id]!.localPath!),"Old-container ownership lookup normalizes: "+id)
                check(try store.referencesLocalPath(path),"Current-container ownership lookup normalizes: "+id)
            }
            for id in ["missing","cross-owner","cross-server","cross-prefix","symlink","symlink-directory","traversal","existing-source","already-current","draft-cross-owner","draft-cross-server","draft-symlink"] {
                check(try store.attachment(byId:id)?.localPath==input[id]?.localPath,"Never adopt an invalid/foreign/missing path: "+id)
            }
            for (id,original) in input {
                var actual=try store.attachment(byId:id)!
                actual.localPath=original.localPath
                check(actual==original,"Migration must not change metadata, crypto, transfer status or message ownership")
            }
            let paths=try await store.cachedPathsAsync(chatId:"owned-chat",attachmentIDs:Set(["photo","video","original","nested"]))
            check(paths.count==4,"Actual background path reader must expose all four repaired owned files")
            for index in 0..<300 {
                check(try store.attachment(byId:"bulk-\(index)")?.localPath==newCache.appendingPathComponent("bulk/item-\(index).bin").standardizedFileURL.resolvingSymlinksInPath().path,"Migration must continue beyond a bounded SQL batch")
            }
        }
        // Upgrade an installation whose old cache-only pass already completed
        // in this same container. A new versioned marker must still fix drafts.
        let legacyVoice=input["voice"]!.localPath!.replacingOccurrences(of:"'",with:"''")
        let cacheMarker=newCache.resolvingSymlinksInPath().path.replacingOccurrences(of:"'",with:"''")
        try sql(newDB,"DELETE FROM attachment_store_metadata WHERE key='local_roots_v2'; INSERT OR REPLACE INTO attachment_store_metadata(key,value) VALUES ('cache_root_v1','\(cacheMarker)'); UPDATE attachments SET local_path='\(legacyVoice)' WHERE attachment_id='voice';")
        do {
            let upgraded=try AttachmentStore(databaseURL:newDB)
            try await upgraded.waitForDraftReferences()
            check(try upgraded.attachment(byId:"voice")?.localPath==newDraft.appendingPathComponent(voiceFolder+"/voice.m4a").resolvingSymlinksInPath().path,"Cache-only v1 marker cannot suppress draft migration")
        }
        // A second start in the same container must not rescan or rewrite rows.
        try sql(newDB,"CREATE TRIGGER no_repeat_rebase BEFORE UPDATE OF local_path ON attachments BEGIN SELECT RAISE(ABORT,'repeat'); END;")
        do { let again=try AttachmentStore(databaseURL:newDB);try await again.waitForDraftReferences();check(try again.attachment(byId:"video")?.localPath==newCache.appendingPathComponent("video.bin").standardizedFileURL.resolvingSymlinksInPath().path,"Reopen is idempotent") }
        try sql(newDB,"DROP TRIGGER no_repeat_rebase;")
        // A failed write must roll back the whole pass, not half-migrate paths.
        let newer=application.appendingPathComponent("66666666-6666-4666-8666-666666666666",isDirectory:true)
        try fm.moveItem(at:new,to:newer)
        let newerDB=account(newer).appendingPathComponent("attachments.sqlite3")
        try sql(newerDB,"CREATE TRIGGER reject_rebase BEFORE UPDATE OF local_path ON attachments WHEN OLD.attachment_id='bulk-299' BEGIN SELECT RAISE(ABORT,'fixture failure'); END;")
        var rejected=false
        do { _=try AttachmentStore(databaseURL:newerDB) } catch { rejected=true }
        check(rejected,"Fixture must exercise a real SQLite transaction failure")
        do {
            let reader=try AttachmentStore(databaseURL:newerDB,readOnly:true)
            check(try reader.attachment(byId:"photo")?.localPath==newCache.appendingPathComponent("photo.bin").standardizedFileURL.path,"Failed migration cannot leave earlier rows partially updated")
            check(try reader.attachment(byId:"voice")?.localPath==newDraft.appendingPathComponent(voiceFolder+"/voice.m4a").resolvingSymlinksInPath().path,"Failed v1 cache pass leaves draft references untouched")
        }
        try sql(newerDB,"DROP TRIGGER reject_rebase;")
        do { let retry=try AttachmentStore(databaseURL:newerDB);check(try retry.attachment(byId:"video")?.localPath==account(newer).appendingPathComponent("attachments/video.bin").standardizedFileURL.resolvingSymlinksInPath().path,"Retry after rollback remains possible") }
        let absentRoot=account(application.appendingPathComponent(UUID().uuidString))
        try fm.createDirectory(at:absentRoot,withIntermediateDirectories:true)
        let absentStore=try AttachmentStore(databaseURL:absentRoot.appendingPathComponent("attachments.sqlite3"))
        let expectedMissing=absentRoot.appendingPathComponent("draft-media/"+voiceFolder+"/missing.m4a").resolvingSymlinksInPath().path
        let oldMissing=oldDraft.appendingPathComponent(voiceFolder+"/missing.m4a").path
        try absentStore.upsert(row("absent-root",oldMissing))
        check(try absentStore.attachment(byId:"absent-root")?.localPath==expectedMissing,"Draft ownership is an identity even when the staging root is absent")
        try await absentStore.waitForDraftReferences()
        check(try absentStore.referencesLocalPath(oldMissing),"An absent file must not erase attachment ownership")
        print("PASS: \(checks) actual SQLite cache relocation checks: real container move, read-only, strict owner/server/root, symlinks/traversal/missing/existing, metadata unchanged, bounded reader, idempotence, rollback/retry")
    }
}
