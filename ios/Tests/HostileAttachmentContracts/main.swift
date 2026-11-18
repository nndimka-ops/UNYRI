import Foundation
#if canImport(Darwin)
import Darwin
#endif
private func check(_ value: @autoclosure () throws -> Bool, file: StaticString = #filePath, line: UInt = #line) {
    do {
        let result = try value()
        precondition(result, "Attachment contract failed", file: file, line: line)
    } catch {
        preconditionFailure("Expected safe attachment operation, got \(error)", file: file, line: line)
    }
}

private func accepts<T>(_ operation: () throws -> T, file: StaticString = #filePath, line: UInt = #line) -> T {
    do { return try operation() }
    catch { preconditionFailure("Expected safe attachment operation, got \(error)", file: file, line: line) }
}

private func rejects(_ operation: () throws -> Void, file: StaticString = #filePath, line: UInt = #line) {
    do { try operation(); preconditionFailure("Expected unsafe path rejection", file: file, line: line) }
    catch is AttachmentCacheBoundary.Failure {}
    catch { preconditionFailure("Unexpected rejection error: \(error)", file: file, line: line) }
}
@main struct Main {
    static func main() {
        do { try run() }
        catch { preconditionFailure("Attachment fixture setup/IO failed: \(error)") }
    }

    private static func physicalExistingPath(_ url: URL) throws -> String {
        #if canImport(Darwin)
        guard let result = realpath(url.path, nil) else { throw CocoaError(.fileReadUnknown) }
        defer { free(result) }
        return String(cString: result)
        #else
        return url.resolvingSymlinksInPath().path
        #endif
    }

    #if os(macOS)
    private static func aliasContracts(base: String, other: String, layout: String) throws {
        let fm = FileManager.default, bytes = Data("owned attachment".utf8)
        let canaryBytes = Data("sibling account must remain unchanged".utf8)
        let name = "unyri-hostile-" + UUID().uuidString
        let work = URL(fileURLWithPath: base + "/" + name)
        let otherWork = URL(fileURLWithPath: other + "/" + name)
        defer { try? fm.removeItem(at: work) }
        let suffix = layout + UUID().uuidString + "/Library/Application Support/COCO/server/"
        let account = work.appendingPathComponent(suffix + "account-a")
        let alternate = otherWork.appendingPathComponent(suffix + "account-a")
        let cache = account.appendingPathComponent("attachments")
        let alternateCache = alternate.appendingPathComponent("attachments")
        let drafts = account.appendingPathComponent("draft-media")
        let alternateDrafts = alternate.appendingPathComponent("draft-media")
        let canary = otherWork.appendingPathComponent(suffix + "account-b/canary.bin")
        try fm.createDirectory(at: cache, withIntermediateDirectories: true)
        try fm.createDirectory(at: canary.deletingLastPathComponent(), withIntermediateDirectories: true)
        try canaryBytes.write(to: canary)
        let boundary = AttachmentCacheBoundary(root: cache)
        let physicalCache = try physicalExistingPath(cache)
        // Assert the fixture really kept opposite spellings.
        check(cache.path.hasPrefix(base + "/") && alternateCache.path.hasPrefix(other + "/"))
        check(try physicalExistingPath(alternateCache) == physicalCache)

        let existing = cache.appendingPathComponent("existing.bin")
        let alternateExisting = alternateCache.appendingPathComponent("existing.bin")
        try bytes.write(to: existing)
        check(try boundary.checked(alternateExisting).path == physicalCache + "/existing.bin")
        check(try Data(contentsOf: boundary.readable(alternateExisting, draftRoot: alternateDrafts)) == bytes)
        // Both file(name:) and a caller's opposite-spelling URL must work BEFORE
        // a new download exists, and the result must use the physical namespace.
        let absent = accepts { try boundary.file(name: "new.bin") }
        let alternateAbsent = alternateCache.appendingPathComponent("new.bin")
        check(!fm.fileExists(atPath: absent.path))
        check(absent.path == physicalCache + "/new.bin")
        check(try boundary.checked(alternateAbsent) == absent)
        try bytes.write(to: accepts { try boundary.checked(alternateAbsent) })
        check(try Data(contentsOf: boundary.checked(alternateAbsent)) == bytes)
        try fm.removeItem(at: accepts { try boundary.checked(alternateAbsent) })
        check(!fm.fileExists(atPath: cache.appendingPathComponent("new.bin").path))
        // A root that has not been created yet also resolves its existing parent.
        let missingRoot = account.appendingPathComponent("new-attachments")
        let missingBoundary = AttachmentCacheBoundary(root: missingRoot)
        let newFile = accepts { try missingBoundary.file(name: "first.bin") }
        check(try missingBoundary.checked(alternate.appendingPathComponent("new-attachments/first.bin")) == newFile)
        try fm.createDirectory(at: newFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: newFile)
        check(try Data(contentsOf: missingBoundary.checked(newFile)) == bytes)

        let pendingFolder = UUID().uuidString
        let pending = alternateDrafts.appendingPathComponent(pendingFolder + "/voice_2.m4a")
        check(!fm.fileExists(atPath: drafts.path))
        check(try boundary.readable(pending, draftRoot: drafts).path == String(physicalCache.dropLast("attachments".count)) + "draft-media/" + pendingFolder + "/voice_2.m4a")

        for folder in [UUID().uuidString, "share-" + UUID().uuidString] {
            for filename in ["voice_1.m4a", "video-note-1.mp4", "photo 1.heic"] {
                let source = drafts.appendingPathComponent(folder + "/" + filename)
                let alternateSource = alternateDrafts.appendingPathComponent(folder + "/" + filename)
                try fm.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
                try bytes.write(to: source)
                for draftRoot in [drafts, alternateDrafts] {
                    for spelling in [source, alternateSource] {
                        check(try boundary.readable(spelling, draftRoot: draftRoot).path == physicalExistingPath(source))
                        check(try Data(contentsOf: boundary.readable(spelling, draftRoot: draftRoot)) == bytes)
                    }
                    rejects { _ = try boundary.readable(URL(fileURLWithPath: alternateSource.deletingLastPathComponent().path + "/../" + folder + "/" + filename), draftRoot: draftRoot) }
                    rejects { _ = try boundary.readable(canary, draftRoot: draftRoot) }
                }
                let draftLink = source.appendingPathExtension("link")
                try fm.createSymbolicLink(at: draftLink, withDestinationURL: source)
                rejects { _ = try boundary.readable(alternateSource.appendingPathExtension("link"), draftRoot: drafts) }
                let draftDangling = source.appendingPathExtension("dangling")
                try fm.createSymbolicLink(at: draftDangling, withDestinationURL: source.appendingPathExtension("absent"))
                rejects { _ = try boundary.readable(draftDangling, draftRoot: alternateDrafts) }
            }
        }
        let draftFolder = drafts.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: draftFolder, withIntermediateDirectories: true)
        let draftAncestor = drafts.appendingPathComponent(UUID().uuidString)
        try fm.createSymbolicLink(at: draftAncestor, withDestinationURL: draftFolder)
        rejects { _ = try boundary.readable(draftAncestor.appendingPathComponent("new.m4a"), draftRoot: alternateDrafts) }
        let danglingDraftFolder = drafts.appendingPathComponent(UUID().uuidString)
        try fm.createSymbolicLink(at: danglingDraftFolder, withDestinationURL: drafts.appendingPathComponent(UUID().uuidString))
        rejects { _ = try boundary.readable(danglingDraftFolder.appendingPathComponent("new.m4a"), draftRoot: alternateDrafts) }
        let linkedDraftRoot = account.appendingPathComponent("draft-link")
        try fm.createSymbolicLink(at: linkedDraftRoot, withDestinationURL: drafts)
        rejects { _ = try boundary.readable(linkedDraftRoot.appendingPathComponent(UUID().uuidString + "/voice.m4a"), draftRoot: linkedDraftRoot) }

        // Even links whose targets remain inside this account must be rejected,
        // together with escaping/dangling links, traversal and sibling accounts.
        for (name, target) in [("inside.bin", existing), ("outside.bin", canary),
                               ("dangling.bin", cache.appendingPathComponent("absent.bin"))] {
            try fm.createSymbolicLink(at: cache.appendingPathComponent(name), withDestinationURL: target)
            rejects { _ = try boundary.checked(alternateCache.appendingPathComponent(name)) }
            rejects { _ = try boundary.file(name: name) }
        }
        let link = cache.appendingPathComponent("linked-directory")
        try fm.createSymbolicLink(at: link, withDestinationURL: cache)
        rejects { _ = try boundary.checked(alternateCache.appendingPathComponent("linked-directory/existing.bin")) }
        rejects { _ = try boundary.checked(alternateCache.appendingPathComponent("linked-directory/new.bin")) }
        let danglingDirectory = cache.appendingPathComponent("dangling-directory")
        try fm.createSymbolicLink(at: danglingDirectory, withDestinationURL: cache.appendingPathComponent("missing-directory"))
        rejects { _ = try boundary.checked(alternateCache.appendingPathComponent("dangling-directory/new.bin")) }
        let rootLink = account.appendingPathComponent("cache-link")
        try fm.createSymbolicLink(at: rootLink, withDestinationURL: cache)
        rejects { _ = try AttachmentCacheBoundary(root: rootLink).checked(alternateExisting) }
        rejects { _ = try boundary.checked(URL(fileURLWithPath: alternateCache.path + "/nested/../existing.bin")) }
        rejects { _ = try boundary.checked(canary) }
        rejects { _ = try boundary.checked(alternate.appendingPathComponent("attachments-other/new.bin")) }
        // Exercise deletion through an old SQLite spelling too.
        try fm.removeItem(at: accepts { try boundary.checked(alternateExisting) })
        check(!fm.fileExists(atPath: existing.path))
        check(try Data(contentsOf: canary) == canaryBytes)
    }
    #endif

    static func run() throws {
        let fm = FileManager.default
        let work = fm.temporaryDirectory.appendingPathComponent("unyri-hostile-" + UUID().uuidString)
        defer { try? fm.removeItem(at: work) }
        let a = work.appendingPathComponent("account-a"), b = work.appendingPathComponent("account-b")
        try fm.createDirectory(at: a, withIntermediateDirectories: true)
        try fm.createDirectory(at: b, withIntermediateDirectories: true)
        let canary = b.appendingPathComponent("canary.bin"), bytes = Data("account B must survive".utf8)
        try bytes.write(to: canary)
        let boundary = AttachmentCacheBoundary(root: a)
        for id in ["../account-b/canary.bin", "/etc/passwd", canary.path, "..\\account-b\\canary.bin", "%2e%2e%2fcanary", "%252fetc%252fpasswd", "a/b", "C:\\Windows\\file"] {
            rejects { try AttachmentCacheBoundary.validateID(id) }
            let file = accepts { try boundary.file(name: UUID().uuidString + ".bin") }
            check(try file.path == physicalExistingPath(a) + "/" + file.lastPathComponent)
            try Data(id.utf8).write(to: file)
            check(try Data(contentsOf: boundary.checked(file)) == Data(id.utf8))
            try fm.removeItem(at: accepts { try boundary.checked(file) })
            check(try Data(contentsOf: canary) == bytes)
        }
        for id in ["", String(repeating: "x", count: 129), String(repeating: "💥", count: 1024)] {
            rejects { try AttachmentCacheBoundary.validateID(id) }
        }
        for id in ["a", UUID().uuidString, String(repeating: "x", count: 128), "opaque_id-1"] { accepts { try AttachmentCacheBoundary.validateID(id) } }
        let oldID = UUID().uuidString
        let legacy = accepts { try boundary.file(name: "upload-" + oldID + ".enc") }
        try bytes.write(to: legacy)
        let migrated = accepts { try boundary.uploadFile(id: oldID) }
        check(migrated.lastPathComponent.hasPrefix("upload-v2-"))
        check(!fm.fileExists(atPath: legacy.path))
        check(try Data(contentsOf: migrated) == bytes)
        rejects { _ = try boundary.uploadFile(id: "../account-b/canary.bin") }
        let cache = a.appendingPathComponent("attachments"), drafts = a.appendingPathComponent("draft-media")
        try fm.createDirectory(at: cache, withIntermediateDirectories: true)
        let owned = drafts.appendingPathComponent(UUID().uuidString).appendingPathComponent("source.jpg")
        try fm.createDirectory(at: owned.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: owned)
        let reader = AttachmentCacheBoundary(root: cache)
        check(try reader.readable(owned, draftRoot: drafts).path == physicalExistingPath(owned))
        check(try Data(contentsOf: reader.readable(owned, draftRoot: drafts)) == bytes)
        for folder in [UUID().uuidString, "share-" + UUID().uuidString] {
            for name in ["voice_1794659445000.m4a", "video-note-1794659445000.mp4", "video_note_1794659445000.mp4", "unyri-video-note-1794659445000.mp4", "photo 1.jpg", "report.pdf"] {
                let legacyDraft = drafts.appendingPathComponent(folder).appendingPathComponent(name)
                try fm.createDirectory(at: legacyDraft.deletingLastPathComponent(), withIntermediateDirectories: true)
                try bytes.write(to: legacyDraft)
                check(try Data(contentsOf: reader.readable(legacyDraft, draftRoot: drafts)) == bytes)
            }
        }
        for folder in ["not-a-uuid", "share-not-a-uuid", UUID().uuidString + "/nested"] {
            rejects { _ = try reader.readable(drafts.appendingPathComponent(folder).appendingPathComponent("voice_1.m4a"), draftRoot: drafts) }
        }
        rejects { _ = try reader.readable(canary, draftRoot: drafts) }
        // Normalization back inside the root does not excuse traversal.
        rejects { _ = try reader.readable(owned.deletingLastPathComponent().appendingPathComponent("../" + owned.deletingLastPathComponent().lastPathComponent + "/source.jpg"), draftRoot: drafts) }
        rejects { _ = try boundary.checked(a.appendingPathComponent("nested/../" + migrated.lastPathComponent)) }
        let unsafeDraft = drafts.appendingPathComponent(UUID().uuidString).appendingPathComponent("source.jpg")
        try fm.createDirectory(at: unsafeDraft.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.createSymbolicLink(at: unsafeDraft, withDestinationURL: canary)
        rejects { _ = try reader.readable(unsafeDraft, draftRoot: drafts) }
        let linkedFolder = drafts.appendingPathComponent(UUID().uuidString)
        try fm.createSymbolicLink(at: linkedFolder, withDestinationURL: owned.deletingLastPathComponent())
        rejects { _ = try reader.readable(linkedFolder.appendingPathComponent("source.jpg"), draftRoot: drafts) }
        for path in [canary, a.appendingPathComponent("../account-b/canary.bin"), work.appendingPathComponent("account-a-other/file")] {
            rejects { _ = try boundary.checked(path) }
        }
        let link = a.appendingPathComponent("link")
        try fm.createSymbolicLink(at: link, withDestinationURL: b)
        rejects { _ = try boundary.checked(link.appendingPathComponent("canary.bin")) }
        let leaf = a.appendingPathComponent("leaf.bin")
        try fm.createSymbolicLink(at: leaf, withDestinationURL: canary)
        rejects { _ = try boundary.file(name: "leaf.bin") }
        let dangling = a.appendingPathComponent("dangling.bin")
        try fm.createSymbolicLink(at: dangling, withDestinationURL: b.appendingPathComponent("missing"))
        rejects { _ = try boundary.checked(dangling) }
        let rootLink = work.appendingPathComponent("root-link")
        try fm.createSymbolicLink(at: rootLink, withDestinationURL: b)
        rejects { _ = try AttachmentCacheBoundary(root: rootLink).file(name: "canary.bin") }
        rejects { _ = try AttachmentCacheBoundary(root: rootLink).checked(canary) }
        rejects { _ = try AttachmentCacheBoundary(root: a.appendingPathComponent("nested/..")).checked(migrated) }
        rejects { _ = try AttachmentCacheBoundary(root: rootLink.appendingPathComponent("nested")).file(name: "canary.bin") }
        check(try Data(contentsOf: canary) == bytes)
        #if os(macOS)
        // Both directions, with private-app and share App Group container shapes.
        // These are REAL OS aliases, never symlinks fabricated by the fixture.
        for (base, other) in [("/var/tmp", "/private/var/tmp"), ("/private/var/tmp", "/var/tmp"),
                              ("/tmp", "/private/tmp"), ("/private/tmp", "/tmp")] {
            for layout in ["Containers/Data/Application/", "Containers/Shared/AppGroup/"] {
                try aliasContracts(base: base, other: other, layout: layout)
            }
        }
        for alias in ["/var", "/tmp"] {
            // A link at the root itself is not an allowed ancestor.
            rejects { _ = try AttachmentCacheBoundary(root: URL(fileURLWithPath: alias)).file(name: "new.bin") }
        }
        #endif
        print("Hostile F9 containment and account canary contracts passed")
    }
}
