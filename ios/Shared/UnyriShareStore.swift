import Foundation

/// Immutable batches become visible only after the final directory rename.
/// No credentials, chat database, or Signal keys are shared with the extension.
enum UnyriShareStore {
    // Both app and extension receive this value from the same build setting.
    // Missing keys preserve older bundles; an invalid supplied value does not
    // silently select another App Group and its container.
    static let groupIdentifier: String = {
        guard let configured = Bundle.main.object(forInfoDictionaryKey: "UNYRI_APP_GROUP_IDENTIFIER") ?? Bundle.main.object(forInfoDictionaryKey: "COCO_APP_GROUP_IDENTIFIER") else {
            return "group.com.messenger.coco.share"
        }
        return configured as? String ?? ""
    }()
    // Preserve the existing share-extension caller API.
    static var group: String { groupIdentifier }
    static let maxFileBytes = 100 * 1024 * 1024
    static let maxBatchBytes = 250 * 1024 * 1024
    struct Entry: Codable, Identifiable {
        let id: String
        let name: String
    }
    struct Batch: Codable {
        let entries: [Entry]
    }
    static func root() throws -> URL {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let root = container.appendingPathComponent("ShareInbox", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        var excluded = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        return root
    }
    static func safeName(_ value: String) -> String {
        let name = String(value.replacingOccurrences(of: "\\", with: "_").replacingOccurrences(of: "/", with: "_").filter { !$0.isNewline && !($0.asciiValue.map { $0 < 32 } ?? false) }.prefix(160))
        return name.isEmpty || name == "." || name == ".." ? "File" : name
    }
    static func files(in directory: URL) throws -> [(Entry, URL)] {
        let data = try Data(contentsOf: directory.appendingPathComponent("manifest.json"))
        guard data.count < 65536 else { throw CocoaError(.fileReadCorruptFile) }
        let batch = try JSONDecoder().decode(Batch.self, from: data)
        guard (1...10).contains(batch.entries.count), Set(batch.entries.map(\.id)).count == batch.entries.count else { throw CocoaError(.fileReadCorruptFile) }
        return try batch.entries.filter { !FileManager.default.fileExists(atPath: directory.appendingPathComponent($0.id + ".done").path) }.map { entry in
            guard UUID(uuidString: entry.id) != nil, safeName(entry.name) == entry.name else { throw CocoaError(.fileReadCorruptFile) }
            let parent = directory.appendingPathComponent(entry.id, isDirectory: true)
            let parentInfo = try parent.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard parentInfo.isDirectory == true, parentInfo.isSymbolicLink != true else { throw CocoaError(.fileReadCorruptFile) }
            let file = parent.appendingPathComponent(entry.name)
            let info = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard info.isRegularFile == true, info.isSymbolicLink != true, (info.fileSize ?? Int.max) <= maxFileBytes else { throw CocoaError(.fileReadCorruptFile) }
            return (entry, file)
        }
    }
    /// Bounded streaming copy: a provider may lie about its declared file size.
    static func copy(_ source: URL, to destination: URL, limit: Int) throws -> Int {
        let info = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard source.isFileURL, info.isRegularFile == true, info.isSymbolicLink != true else { throw CocoaError(.fileReadUnsupportedScheme) }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete]) else { throw CocoaError(.fileWriteUnknown) }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var count = 0
        while let bytes = try input.read(upToCount: 256 * 1024), !bytes.isEmpty {
            count += bytes.count
            guard count <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
            try output.write(contentsOf: bytes)
        }
        try output.synchronize()
        return count
    }
}
