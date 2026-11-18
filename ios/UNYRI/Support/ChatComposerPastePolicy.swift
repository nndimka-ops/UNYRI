import Foundation

/// Paste routing without UIKit. The caller supplies the platform's UTI conformance
/// check; file URLs and text representations of media never become extra attachments.
enum ChatComposerPastePolicy {
    enum Kind: Equatable { case image, animatedImage, video }
    struct Media: Equatable {
        let typeIdentifier: String
        let kind: Kind
        var isVideo: Bool { kind == .video }
    }

    static let imageTypes = ["public.image", "public.heic", "public.heif", "public.png", "public.jpeg", "com.compuserve.gif"]
    static let videoTypes = ["public.movie", "public.video", "public.mpeg-4", "com.apple.quicktime-movie"]
    static let plainTextTypes = ["public.plain-text", "public.utf8-plain-text", "public.utf16-plain-text"]
    static let acceptableTypes = imageTypes + videoTypes + plainTextTypes

    static func media(in types: [String], conforms: (String, String) -> Bool) -> Media? {
        // A GIF can also advertise a still PNG/JPEG. Always request its animated bytes.
        if let gif = types.first(where: { conforms($0, "com.compuserve.gif") }) {
            return Media(typeIdentifier: gif, kind: .animatedImage)
        }
        for type in types {
            if conforms(type, "public.movie") || conforms(type, "public.video") {
                return Media(typeIdentifier: type, kind: .video)
            }
            if conforms(type, "public.image") { return Media(typeIdentifier: type, kind: .image) }
        }
        return nil
    }

    static func textType(in types: [String], conforms: (String, String) -> Bool) -> String? {
        // Prefer plain text. If a source offers only rich text, leave its handling
        // (including stripping formatting) to the text view's existing paste logic.
        types.first { conforms($0, "public.plain-text") } ?? types.first { conforms($0, "public.text") }
    }

    enum UIKitRoute: Equatable {
        case unchanged
        case representations([String])
    }
    static func uiKitRoute(in types: [String], conforms: (String, String) -> Bool) -> UIKitRoute {
        // UIKit owns non-media input, including URL-only and future supported types.
        guard media(in: types, conforms: conforms) != nil else { return .unchanged }
        var safeTypes = textType(in: types, conforms: conforms).map { [$0] } ?? []
        for type in types where conforms(type, "public.url") && !safeTypes.contains(type) {
            safeTypes.append(type)
        }
        return .representations(safeTypes)
    }

    enum FileOrigin: Equatable { case document, provider }
    struct FilePlan: Equatable {
        let owned: Bool
        let maximumBytes: Int64
    }
    static func filePlan(origin: FileOrigin, original: Bool, video: Bool, retainExternalDocument: Bool,
                         ordinaryLimit: Int64, videoLimit: Int64, originalLimit: Int64) -> FilePlan {
        // External picks can retain a grant for direct sending. Provider copies have
        // no durable external owner: even a direct original must enter draft storage.
        let owned = origin == .provider || !retainExternalDocument
        let limit = origin == .provider && original ? originalLimit : (video ? videoLimit : ordinaryLimit)
        return FilePlan(owned: owned, maximumBytes: limit)
    }

    /// Same clipboard item, including a second keyboard-suggestion tap, has the same
    /// identity while its provider bytes are loading and after they have been staged.
    static func identity(changeCount: Int, itemIndex: Int) -> String {
        "paste-\(changeCount)-\(itemIndex)"
    }
}

/// One tray owns every admitted import, including requests which turn out to be
/// duplicates. Completing one request can only release that request's handle.
@MainActor final class ChatComposerImportTasks {
    private var tasks: [UUID: Task<Void, Never>] = [:]
    var count: Int { tasks.count }
    var isEmpty: Bool { tasks.isEmpty }

    func start(_ operation: @escaping @MainActor () async -> Void) {
        let id = UUID()
        tasks[id] = Task { @MainActor [weak self] in
            defer { self?.tasks[id] = nil }
            guard !Task.isCancelled else { return }
            await operation()
        }
    }

    func cancelAll() {
        let cancelled = Array(tasks.values)
        tasks.removeAll()
        for task in cancelled { task.cancel() }
    }

    deinit { for task in tasks.values { task.cancel() } }
}

/// A cancellation-aware IO scope. Ownership is acquired on the main actor before
/// queueing work, and released there only after the worker has stopped reading.
enum ChatComposerRecentFileCopy {
    final class Cancellation: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        private var cancelReader: (@Sendable () -> Void)?
        func cancel() {
            lock.lock(); cancelled = true; let reader = cancelReader; lock.unlock()
            reader?()
        }
        func withReaderCancellation<T>(_ stop: @escaping @Sendable () -> Void, operation: () throws -> T) rethrows -> T {
            lock.lock(); cancelReader = stop; let stopped = cancelled; lock.unlock()
            if stopped { stop() }
            defer { lock.lock(); cancelReader = nil; lock.unlock() }
            return try operation()
        }
        func check() throws {
            lock.lock(); let stopped = cancelled; lock.unlock()
            if stopped { throw CancellationError() }
        }
    }

    @MainActor static func perform(on queue: DispatchQueue, acquire: () throws -> Void,
                                  release: () -> Void,
                                  copy: @escaping @Sendable (Cancellation) throws -> Void) async throws {
        try Task.checkCancellation()
        try acquire()
        defer { release() }
        let cancellation = Cancellation()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                queue.async {
                    do {
                        precondition(!Thread.isMainThread, "Recent-file IO must stay off the main thread")
                        try cancellation.check()
                        try copy(cancellation)
                        try cancellation.check()
                        continuation.resume()
                    } catch { continuation.resume(throwing: error) }
                }
            }
        }, onCancel: { cancellation.cancel() })
    }
}
