import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Cache paths contain only locally generated names. Remote IDs and display
/// filenames never become path components. Only the OS aliases above the root
/// may be symlinks; the root itself and every component inside it must not be.
struct AttachmentCacheBoundary: Sendable {
    enum Failure: Error { case unsafePath, invalidID }
    let root: URL
    // Keep the supplied spelling until checked() has inspected its components.
    init(root: URL) { self.root = root }

    static func validateID(_ id: String) throws {
        guard !id.isEmpty, id.utf8.count <= 128,
              id.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95 }) else { throw Failure.invalidID }
    }
    func uploadFile(id: String) throws -> URL {
        try Self.validateID(id)
        let encoded = Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "=", with: "")
        let target = try file(name: "upload-v2-" + encoded + ".enc")
        // Preserve safe, resumable caches from earlier app versions.
        let legacy = try file(name: "upload-" + id + ".enc")
        if !FileManager.default.fileExists(atPath: target.path), FileManager.default.fileExists(atPath: legacy.path) {
            try FileManager.default.moveItem(at: legacy, to: target)
        }
        return target
    }
    func file(name: String) throws -> URL {
        guard !name.isEmpty, name.utf8.count <= 200,
              name.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 46 || $0 == 95 }),
              name != ".", name != ".." else { throw Failure.unsafePath }
        return try checked(root.appendingPathComponent(name))
    }
    /// Existing outgoing drafts have a separate account-owned root. Only the
    /// producer's UUID/<filename> and share-UUID/<filename> layouts are
    /// admitted there. Voice/video drafts preserve their original basename.
    func readable(_ file: URL, draftRoot: URL) throws -> URL {
        if let cached = try? checked(file) { return cached }
        let draft = try AttachmentCacheBoundary(root: draftRoot).checked(file)
        let physicalDraftRoot = try Self.physicalPath(draftRoot.path)
        let relative = String(draft.path.dropFirst(physicalDraftRoot.count + 1)).split(separator: "/")
        guard relative.count == 2 else { throw Failure.unsafePath }
        let folder = String(relative[0])
        let id = folder.hasPrefix("share-") ? String(folder.dropFirst(6)) : folder
        // draftFileDestination preserves arbitrary selected-file names. The
        // boundary is the account root plus locally allocated UUID, never a
        // display-name prefix. checked() rejects every ancestor/leaf symlink.
        guard UUID(uuidString: id) != nil, !relative[1].isEmpty,
              relative[1] != ".", relative[1] != "..", relative[1].utf8.count <= 2048,
              !relative[1].contains("\\"), !relative[1].contains("\0") else { throw Failure.unsafePath }
        return draft
    }
    func checked(_ file: URL) throws -> URL {
        // Inspect lexical components before resolving anything. Normalization
        // must never erase traversal or a custom symlink (even inside the root).
        for url in [file, root] {
            guard url.isFileURL, (url.path as NSString).isAbsolutePath, !url.path.contains("\0"),
                  !url.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else { throw Failure.unsafePath }
        }
        // Walk literal path strings: URL standardization on Darwin can strip
        // /private, hiding which component was actually a link.
        try Self.inspect(root.path, allowedAliasAncestorOf: root.path)
        let physicalRoot = try Self.physicalPath(root.path)
        try Self.inspect(file.path, allowedAliasAncestorOf: physicalRoot)
        let candidate = try Self.physicalPath(file.path)
        guard candidate != physicalRoot,
              candidate.hasPrefix(physicalRoot == "/" ? "/" : physicalRoot + "/") else { throw Failure.unsafePath }
        return URL(fileURLWithPath: candidate)
    }

    private static func inspect(_ path: String, allowedAliasAncestorOf root: String) throws {
        var part = ""
        for component in path.split(separator: "/") {
            part += "/" + String(component)
            #if canImport(Darwin) || canImport(Glibc)
            var info = stat()
            guard lstat(part, &info) == 0 else {
                guard errno == ENOENT else { throw Failure.unsafePath }
                continue
            }
            guard info.st_mode & mode_t(S_IFMT) == mode_t(S_IFLNK) else { continue }
            #else
            // Portable contract hosts; Apple uses lstat, including dangling links.
            guard (try? FileManager.default.destinationOfSymbolicLink(atPath: part)) != nil else { continue }
            #endif
            guard let destination = try? FileManager.default.destinationOfSymbolicLink(atPath: part) else { throw Failure.unsafePath }
            let alias = "/private" + part
            // These exact OS links must be STRICT ancestors of the root, in
            // either spelling. An alias at/below the boundary is not admitted.
            guard ["/var", "/tmp"].contains(part),
                  [alias, "private" + part].contains(destination),
                  root.hasPrefix(part + "/") || root.hasPrefix(alias + "/") else { throw Failure.unsafePath }
        }
    }

    /// realpath preserves Darwin's physical /private prefix, unlike Foundation
    /// normalization. New download leaves (and missing draft directories) use
    /// the nearest existing ancestor, followed by the inspected missing suffix.
    private static func physicalPath(_ path: String) throws -> String {
        var ancestor = path
        var missing = [String]()
        while true {
            #if canImport(Darwin) || canImport(Glibc)
            if let resolved = realpath(ancestor, nil) {
                defer { free(resolved) }
                let base = String(cString: resolved)
                return ([base == "/" && !missing.isEmpty ? "" : base] + Array(missing.reversed())).joined(separator: "/")
            }
            guard errno == ENOENT else { throw Failure.unsafePath }
            #else
            if FileManager.default.fileExists(atPath: ancestor) {
                return ([URL(fileURLWithPath: ancestor).resolvingSymlinksInPath().path] + Array(missing.reversed())).joined(separator: "/")
            }
            #endif
            guard let slash = ancestor.lastIndex(of: "/"), ancestor != "/" else { throw Failure.unsafePath }
            missing.append(String(ancestor[ancestor.index(after: slash)...]))
            ancestor = slash == ancestor.startIndex ? "/" : String(ancestor[..<slash])
        }
    }
}
