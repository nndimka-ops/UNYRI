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
    @MainActor static func main() async throws {
        let fm=FileManager.default
        let fixture=fm.temporaryDirectory.appendingPathComponent("coco-cache-relocation-"+UUID().uuidString,isDirectory:true)
        try fm.createDirectory(at:fixture,withIntermediateDirectories:true)
        defer { try? fm.removeItem(at:fixture) }
        let application=fixture.appendingPathComponent("Containers/Data/Application",isDirectory:true)
        let old=application.appendingPathComponent("22222222-2222-4222-8222-222222222222",isDirectory:true)
        let new=application.appendingPathComponent("33333333-3333-4333-8333-333333333333",isDirectory:true)
        let oldAccount=account(old),newAccount=account(new)
        let oldCache=oldAccount.appendingPathComponent("attachments",isDirectory:true)
        let newCache=newAccount.appendingPathComponent("attachments",isDirectory:true)
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
            for id in ["missing","cross-owner","cross-server","cross-prefix","symlink","symlink-directory","traversal","existing-source","already-current"] {
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
        // A second start in the same container must not rescan or rewrite rows.
        try sql(newDB,"CREATE TRIGGER no_repeat_rebase BEFORE UPDATE OF local_path ON attachments BEGIN SELECT RAISE(ABORT,'repeat'); END;")
        do { let again=try AttachmentStore(databaseURL:newDB);check(try again.attachment(byId:"video")?.localPath==newCache.appendingPathComponent("video.bin").standardizedFileURL.resolvingSymlinksInPath().path,"Reopen is idempotent") }
        try sql(newDB,"DROP TRIGGER no_repeat_rebase;")
        // A failed write must roll back the whole pass, not half-migrate paths.
        let newer=application.appendingPathComponent("66666666-6666-4666-8666-666666666666",isDirectory:true)
        try fm.moveItem(at:new,to:newer)
        let newerDB=account(newer).appendingPathComponent("attachments.sqlite3")
        try sql(newerDB,"CREATE TRIGGER reject_rebase BEFORE UPDATE OF local_path ON attachments WHEN OLD.attachment_id='bulk-299' BEGIN SELECT RAISE(ABORT,'fixture failure'); END;")
        var rejected=false
        do { _=try AttachmentStore(databaseURL:newerDB) } catch { rejected=true }
        check(rejected,"Fixture must exercise a real SQLite transaction failure")
        do { let reader=try AttachmentStore(databaseURL:newerDB,readOnly:true);check(try reader.attachment(byId:"photo")?.localPath==newCache.appendingPathComponent("photo.bin").standardizedFileURL.path,"Failed migration cannot leave earlier rows partially updated") }
        try sql(newerDB,"DROP TRIGGER reject_rebase;")
        do { let retry=try AttachmentStore(databaseURL:newerDB);check(try retry.attachment(byId:"video")?.localPath==account(newer).appendingPathComponent("attachments/video.bin").standardizedFileURL.resolvingSymlinksInPath().path,"Retry after rollback remains possible") }
        print("PASS: \(checks) actual SQLite cache relocation checks: real container move, read-only, strict owner/server/root, symlinks/traversal/missing/existing, metadata unchanged, bounded reader, idempotence, rollback/retry")
    }
}
