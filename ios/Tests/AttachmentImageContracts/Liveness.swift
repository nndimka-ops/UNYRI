// Native boundaries for run_liveness.py. The actual source/observer implementations
// are appended to this file. File bytes and FileRevision are real Foundation I/O.
import Foundation

final class UIImage: NSObject, @unchecked Sendable {
    let name: String
    let size = (width: 768.0, height: 768.0)
    let scale = 1.0
    init(_ name: String) { self.name = name; super.init() }
}
struct AttachmentMetadata {
    let attachmentId: String; let mimeType: String; let thumbnail: String?; let isVideoNote: Bool
}
final class AnyCancellable {
    let cancel: () -> Void
    init(_ cancel: @escaping () -> Void) { self.cancel = cancel }
    deinit { cancel() }
}
@MainActor final class PassthroughSubject<Value, Failure: Error> {
    private var observers: [UUID: (Value) -> Void] = [:]
    private var initial: [Value]
    init(_ initial: [Value] = []) { self.initial = initial }
    func send(_ value: Value) {
        if !initial.isEmpty { initial = [value] }
        for receive in Array(observers.values) { receive(value) }
    }
    func sink(_ receive: @escaping (Value) -> Void) -> AnyCancellable {
        let id = UUID(); observers[id] = receive
        if let value = initial.first { receive(value) }
        return AnyCancellable { self.observers[id] = nil }
    }
    struct Dropped {
        let base: PassthroughSubject
        @MainActor func sink(_ receive: @escaping (Value) -> Void) -> AnyCancellable {
            var first = true
            return base.sink { value in
                if first { first = false } else { receive(value) }
            }
        }
    }
    func dropFirst() -> Dropped { Dropped(base: self) }
}
@MainActor @propertyWrapper final class Published<Value> {
    var wrappedValue: Value { didSet { projectedValue.send(wrappedValue) } }
    let projectedValue: PassthroughSubject<Value, Never>
    init(wrappedValue: Value) {
        self.wrappedValue = wrappedValue; projectedValue = .init([wrappedValue])
    }
}
@MainActor protocol ObservableObject {}
extension ObservableObject {
    var objectWillChange: ObjectChange { ObjectChange() }
}
struct ObjectChange { func send() {} }
@MainActor final class MessagesRepository {
    var paths: [String: String] = [:]
    var persistedIndex: URL?
    var pathLookups = 0
    @Published var attachmentCacheRevision = 0
    func cachedLocalPath(attachmentId: String) -> String? { paths[attachmentId] }
    func attachmentImageLocalPath(attachmentId: String) async -> String? {
        pathLookups += 1
        if let persistedIndex {
            return await Task.detached {
                guard let data = try? Data(contentsOf: persistedIndex),
                      let records = try? JSONDecoder().decode([String: String].self, from: data),
                      let path = records[attachmentId], FileManager.default.isReadableFile(atPath: path) else { return nil as String? }
                return path
            }.value
        }
        return paths[attachmentId]
    }
}
@MainActor final class UIApplication {
    enum State { case active, background }
    static let shared = UIApplication()
    var applicationState = State.active
}
@MainActor final class AttachmentImageLifecycle { let epoch: UInt64 = 0 }
final class MediaCacheLease { init(path: String) {} }
struct AVError: Error {
    enum Code {
        case fileFormatNotRecognized, fileFailedToParse, failedToParse, decoderNotFound,
             undecodableMediaData, formatUnsupported, contentIsProtected, operationInterrupted,
             mediaServicesWereReset, decoderTemporarilyUnavailable
    }
    let code: Code
}
@MainActor enum ConversationImages {
    static var generation: UInt64 = 0
    static var images: [String: UIImage] = [:]
    static var frames: [String: UIImage] = [:]
    static var decodes = 0
    static var holdNext = false
    static var held: CheckedContinuation<Void, Never>?
    static func cached(path: String?, thumbnail: String?, pixelEdge: Int = 768) -> UIImage? {
        if let path { return images["\(pixelEdge):" + path] }
        return thumbnail.flatMap { images["\(pixelEdge):inline:" + $0] }
    }
    static func cachedVideoFrame(path: String, pixelEdge: Int = 768) -> UIImage? { frames[path] }
    static func load(path: String?, thumbnail: String?, pixelEdge: Int = 768) async -> UIImage? {
        guard let path else {
            guard let thumbnail, let data = Data(base64Encoded: thumbnail), let name = String(data: data, encoding: .utf8) else { return nil }
            let image = UIImage(name); images["\(pixelEdge):inline:" + thumbnail] = image; return image
        }
        if let hit = cached(path: path, thumbnail: nil, pixelEdge: pixelEdge) { return hit }
        decodes += 1
        let name = await Task.detached { try? String(contentsOfFile: path, encoding: .utf8) }.value
        if holdNext {
            holdNext = false
            await withCheckedContinuation { held = $0 }
        }
        guard !Task.isCancelled, let name else { return nil }
        let image = UIImage(name); images["\(pixelEdge):" + path] = image
        UnyriAttachmentImages.changes.send(.path(path))
        return image
    }
    static func removeAttachmentImages(path: String) {
        images = images.filter { !$0.key.hasSuffix(":" + path) }; frames[path] = nil
    }
    static func cacheVideoFrame(_ image: UIImage, path: String, pixelEdge: Int, generation: UInt64) {
        frames[path] = image; UnyriAttachmentImages.changes.send(.path(path))
    }
}
@MainActor enum VideoNotePosters {
    static func cached(attachmentId: String?, clientMessageId: String?) -> UIImage? { nil }
    static func adopt(_ image: UIImage, attachmentId: String, clientMessageId: String?) {}
    static func store(_ image: UIImage, clientMessageId: String) {}
    static func remove(attachmentId: String) {}
}
actor AttachmentVideoDecodeQueue {
    static let shared = AttachmentVideoDecodeQueue()
    func load(_ url: URL, note: Bool) async -> Result<UIImage, Error> {
        await MainActor.run { ConversationImages.decodes += 1 }
        guard let name = try? String(contentsOf: url, encoding: .utf8), name != "bad" else {
            return .failure(AVError(code: .undecodableMediaData))
        }
        return .success(UIImage(name))
    }
}

// Photos snapshots are deliberately immutable. Refetch must find the edited object,
// and a delayed callback from the old snapshot can still arrive after cancellation.
typealias PHImageRequestID = Int
let PHImageResultIsDegradedKey = "degraded", PHImageCancelledKey = "cancelled"
final class PHAsset: NSObject, @unchecked Sendable {
    static var current: [String: PHAsset] = [:]
    let localIdentifier: String; let version: String
    init(_ id: String, _ version: String) { localIdentifier = id; self.version = version; super.init() }
    struct Result { let firstObject: PHAsset? }
    static func fetchAssets(withLocalIdentifiers ids: [String], options: Any?) -> Result {
        Result(firstObject: ids.first.flatMap { current[$0] })
    }
}
protocol PHPhotoLibraryChangeObserver { func photoLibraryDidChange(_ change: PHChange) }
final class PHChange {
    struct Details { let objectAfterChanges: PHAsset? }
    let after: PHAsset?
    init(_ after: PHAsset?) { self.after = after }
    func changeDetails(for asset: PHAsset) -> Details? { Details(objectAfterChanges: after) }
}
@MainActor final class PHPhotoLibrary {
    static let instance = PHPhotoLibrary()
    static func shared() -> PHPhotoLibrary { instance }
    var registrations = 0
    func register(_ observer: PHPhotoLibraryChangeObserver) { registrations += 1 }
    func unregisterChangeObserver(_ observer: PHPhotoLibraryChangeObserver) { registrations -= 1 }
}
final class PHImageRequestOptions {
    enum Delivery { case opportunistic }; enum Resize { case exact }
    var deliveryMode = Delivery.opportunistic; var resizeMode = Resize.exact
    var isNetworkAccessAllowed = true
}
@MainActor final class PHImageManager {
    static let instance = PHImageManager()
    static func `default`() -> PHImageManager { instance }
    enum ContentMode { case aspectFill }
    var calls = 0
    var callbacks: [Int: (PHAsset, (UIImage?, [String: Any]?) -> Void)] = [:]
    func requestImage(for asset: PHAsset, targetSize: CGSize, contentMode: ContentMode,
                      options: PHImageRequestOptions, resultHandler: @escaping (UIImage?, [String: Any]?) -> Void) -> Int {
        precondition(!options.isNetworkAccessAllowed)
        calls += 1; callbacks[calls] = (asset, resultHandler); return calls
    }
    func cancelImageRequest(_ request: Int) {} // intentionally keep late callbacks
    func complete(_ request: Int, degraded: Bool = false) {
        let (asset, callback) = callbacks[request]!
        callback(UIImage(asset.version), [PHImageResultIsDegradedKey: degraded])
    }
}

extension UnyriAttachmentImageSource {
    var busyForContract: Bool { worker != nil }
}
@main struct Liveness {
    @MainActor static func eventually(_ predicate: () -> Bool) async throws {
        for _ in 0..<5000 {
            if predicate() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        preconditionFailure("source did not settle")
    }
    @MainActor static func coldLaunch(_ directory: URL) async throws {
        // No warm UIImage/frame cache, no synchronous timeline path snapshot and
        // no viewer/transfer revision. Only the persisted attachment index survives.
        for role in ["sent", "received"] {
            ConversationImages.images.removeAll(); ConversationImages.frames.removeAll()
            UnyriAttachmentImages.reset()
            let repository = MessagesRepository()
            let index = directory.appendingPathComponent(role + "-attachments.json")
            var records: [String: String] = [:]
            var requests: [AttachmentImageRequest] = []
            for i in 0..<4 {
                let id = "\(role)-\(i)"
                let url = directory.appendingPathComponent(id + (i == 3 ? ".mp4" : ".jpg"))
                try Data(id.utf8).write(to: url)
                records[id] = url.path
                requests.append(AttachmentImageRequest(id: id, url: nil, mime: i == 3 ? "video/mp4" : "image/jpeg"))
            }
            try JSONEncoder().encode(records).write(to: index)
            repository.persistedIndex = index
            let sources = requests.map { _ in UnyriAttachmentImageSource() }
            let start = ConversationImages.decodes
            for (request, source) in zip(requests, sources) {
                var gate = AttachmentBindingGateFixture(request: request, phase: .inactive, visible: false)
                gate.mediaWork.viewerOpen = false
                source.configure(request, repository: repository, allowed: gate.allowed, displayed: gate.displayed)
                gate.phase = .active // activation alone does not decode an offscreen row
                source.configure(request, repository: repository, allowed: gate.allowed, displayed: gate.displayed)
                precondition(repository.pathLookups == 0 && ConversationImages.decodes == start)
            }
            // Every tile receives the timeline's visible signal. Decode admission
            // has no dependency on another modifier's still-false visibility flag.
            for (request, source) in zip(requests, sources) {
                var gate = AttachmentBindingGateFixture(request: request, phase: .active)
                gate.mediaWork.viewerOpen = false
                precondition(gate.allowed)
                source.configure(request, repository: repository, allowed: gate.allowed, displayed: gate.displayed)
            }
            try await eventually { zip(requests, sources).allSatisfy { request, source in
                source.value?.quality.sharp == true && source.best(for: request)?.name == request.id && !source.busyForContract
            } }
            precondition(repository.paths.isEmpty && repository.attachmentCacheRevision == 0)
            precondition(repository.pathLookups == 4 && ConversationImages.decodes == start + 4)
            sources.forEach { $0.stop() }

            for surface in ["reply", "pinned", "grid"] {
                ConversationImages.images.removeAll(); ConversationImages.frames.removeAll()
                UnyriAttachmentImages.reset()
                for request in [requests[0], requests[3]] {
                    let source = UnyriAttachmentImageSource()
                    // Reply/pinned rows have mount visibility; the grid gets real
                    // scroll visibility. All look up by ID outside the timeline.
                    var gate = AttachmentBindingGateFixture(request: request, phase: .active,
                        visible: surface == "grid", visibleWhenMounted: surface != "grid")
                    gate.mediaWork.viewerOpen = false
                    precondition(gate.allowed && gate.displayed)
                    source.configure(request, repository: repository, allowed: gate.allowed, displayed: gate.displayed)
                    try await eventually { source.best(for: request)?.name == request.id && !source.busyForContract }
                    source.stop()
                    if surface != "grid" {
                        gate.mounted = false
                        precondition(!gate.allowed && !gate.displayed, "Unmounted static thumbnails do not decode or pin pixels")
                    }
                }
            }
        }
        // A received attachment without a local original must still paint its
        // inline preview after a cold mount. Local lookup never starts a download.
        let repository = MessagesRepository(), source = UnyriAttachmentImageSource()
        let wire = Data("wire-preview".utf8).base64EncodedString()
        let remote = AttachmentImageRequest(id: "remote", url: nil, mime: "video/mp4", thumbnail: wire)
        var gate = AttachmentBindingGateFixture(request: remote, phase: .active)
        gate.mediaWork.viewerOpen = false
        source.configure(remote, repository: repository, allowed: gate.allowed, displayed: true)
        try await eventually { source.value?.quality.origin == .inline && source.best(for: remote)?.name == "wire-preview" && !source.busyForContract }
        source.stop()
        ConversationImages.images.removeAll(); ConversationImages.frames.removeAll(); UnyriAttachmentImages.reset()
        print("PASS: fresh process sent/received three-photo album + video, reply/pins/grid and inline-only fallback resolve on mount without viewer or cache-revision events")
    }
    @MainActor static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try await coldLaunch(directory)
        let url = directory.appendingPathComponent("same-path.jpg")
        try Data("original".utf8).write(to: url)
        let repository = MessagesRepository(); repository.paths["photo"] = url.path
        let request = AttachmentImageRequest(id: "photo", url: url, mime: "image/jpeg")
        let source = UnyriAttachmentImageSource(), sibling = UnyriAttachmentImageSource()
        source.configure(request, repository: repository, allowed: true, displayed: true)
        sibling.configure(request, repository: repository, allowed: true, displayed: true)
        try await eventually { source.value?.image.name == "original" && !source.busyForContract && !sibling.busyForContract }
        try Data("replacement-crop".utf8).write(to: url, options: .atomic)
        repository.attachmentCacheRevision += 1 // no configure or lifecycle transition
        try await eventually { source.value?.image.name == "replacement-crop" && sibling.value?.image.name == "replacement-crop" && !source.busyForContract && !sibling.busyForContract }
        let decodes = ConversationImages.decodes
        repository.attachmentCacheRevision += 1 // unrelated download
        try await eventually { !source.busyForContract && !sibling.busyForContract }
        precondition(ConversationImages.decodes == decodes, "Unchanged files must not decode again")
        source.stop(); sibling.stop()
        repository.attachmentCacheRevision += 1
        precondition(ConversationImages.decodes == decodes, "Stopped surfaces do no work")

        let videoURL = directory.appendingPathComponent("same-path.mp4")
        try Data("bad".utf8).write(to: videoURL)
        repository.paths["video"] = videoURL.path
        let video = AttachmentImageRequest(id: "video", url: videoURL, mime: "video/mp4")
        source.configure(video, repository: repository, allowed: true, displayed: true)
        try await eventually { !source.busyForContract }
        precondition(source.value == nil)
        try Data("valid-video-frame".utf8).write(to: videoURL, options: .atomic)
        repository.attachmentCacheRevision += 1
        try await eventually { source.value?.image.name == "valid-video-frame" && !source.busyForContract }
        source.stop()

        let delayed = directory.appendingPathComponent("delayed.jpg")
        try Data("old".utf8).write(to: delayed)
        repository.paths["delayed"] = delayed.path
        let delayedRequest = AttachmentImageRequest(id: "delayed", url: delayed, mime: "image/jpeg")
        let beforeDelayed = ConversationImages.decodes
        ConversationImages.holdNext = true
        source.configure(delayedRequest, repository: repository, allowed: true, displayed: true)
        try await eventually { ConversationImages.held != nil }
        try Data("new-after-inflight-edit".utf8).write(to: delayed, options: .atomic)
        for _ in 0..<10 { repository.attachmentCacheRevision += 1 }
        ConversationImages.held?.resume(); ConversationImages.held = nil
        try await eventually { source.value?.image.name == "new-after-inflight-edit" && !source.busyForContract }
        precondition(ConversationImages.decodes == beforeDelayed + 2, "A burst coalesces into one replacement decode")
        source.configure(delayedRequest, repository: repository, allowed: false, displayed: true)
        let paused = ConversationImages.decodes
        try Data("changed-while-paused".utf8).write(to: delayed, options: .atomic)
        repository.attachmentCacheRevision += 1
        precondition(!source.busyForContract && ConversationImages.decodes == paused)
        source.configure(delayedRequest, repository: repository, allowed: true, displayed: true)
        try await eventually { source.value?.image.name == "changed-while-paused" && !source.busyForContract }
        source.stop()

        // Another mounted consumer can discover replacement while the old native
        // decoder is suspended. Its late completion cannot restore the old pixels.
        let lateURL = directory.appendingPathComponent("late.jpg")
        try Data("old-pending-frame".utf8).write(to: lateURL)
        repository.paths["late"] = lateURL.path
        let late = AttachmentImageRequest(id: "late", url: lateURL, mime: "image/jpeg")
        ConversationImages.holdNext = true
        source.configure(late, repository: repository, allowed: true, displayed: true)
        try await eventually { ConversationImages.held != nil }
        try Data("new-frame-before-old-completes".utf8).write(to: lateURL, options: .atomic)
        sibling.configure(late, repository: repository, allowed: true, displayed: true)
        try await eventually { sibling.value?.image.name == "new-frame-before-old-completes" && !sibling.busyForContract }
        ConversationImages.held?.resume(); ConversationImages.held = nil
        try await eventually { !source.busyForContract }
        precondition(source.value?.image.name == "new-frame-before-old-completes")
        precondition(ConversationImages.cached(path: lateURL.path, thumbnail: nil)?.name == "new-frame-before-old-completes")
        source.stop(); sibling.stop()
        print("PASS: actual mounted source replaces same-path pixels/failures, coalesces in-flight events and stays idle while stopped/paused")

        // The independently hosted viewer stays open, with timeline work paused.
        // Its injected repository must discover replacement without another source
        // or a lifecycle/configure event. The full-image page owns decoding.
        source.configure(request, repository: repository, allowed: true, displayed: true)
        try await eventually { !source.busyForContract }
        let readonly = AttachmentImageRequest(id: "readonly", url: url, mime: "image/jpeg", photoGeneration: false)
        var activity = ViewerActivityFixture(viewerActive: true)
        let timelineGate = AttachmentBindingGateFixture(request: request, phase: activity.viewerScenePhase)
        var viewerGate = AttachmentBindingGateFixture(request: readonly, viewerSurface: true, phase: .inactive)
        precondition(!viewerGate.allowed, "The inactive inherited host phase reproduces the old gate failure")
        viewerGate.phase = activity.viewerScenePhase // the production environment bridge
        precondition(!timelineGate.allowed && viewerGate.allowed)
        source.configure(request, repository: repository, allowed: timelineGate.allowed, displayed: true)
        repository.paths["readonly"] = url.path
        sibling.configure(readonly, repository: repository, allowed: viewerGate.allowed, displayed: true)
        try await eventually { sibling.value != nil && !sibling.busyForContract }
        var viewerReplacements = 0
        let viewerChanges = UnyriAttachmentImages.changes.sink { change in
            if case .replaced(let path) = change, path == url.path { viewerReplacements += 1 }
        }
        let readonlyDecodes = ConversationImages.decodes
        try Data("viewer-file-replaced".utf8).write(to: url, options: .atomic)
        repository.attachmentCacheRevision += 1
        try await eventually { viewerReplacements == 1 && !sibling.busyForContract }
        precondition(sibling.value == nil && ConversationImages.decodes == readonlyDecodes)
        // Model the native full-image page publishing its replacement frame. The
        // same mounted viewer source must accept it without closing/reopening.
        let newFrame = UIImage(try String(contentsOf: url, encoding: .utf8))
        UnyriAttachmentImages.publish(newFrame, id: readonly.id, origin: .viewerFrame)
        precondition(sibling.value?.image.name == "viewer-file-replaced")
        activity.viewerActive = false
        viewerGate.phase = activity.viewerScenePhase
        precondition(!viewerGate.allowed, "Inactive/background viewer must stop work")
        sibling.configure(readonly, repository: repository, allowed: viewerGate.allowed, displayed: true)
        try Data("viewer-changed-while-inactive".utf8).write(to: url, options: .atomic)
        repository.attachmentCacheRevision += 1
        precondition(!sibling.busyForContract && viewerReplacements == 1)
        activity.viewerActive = true
        viewerGate.phase = activity.viewerScenePhase
        sibling.configure(readonly, repository: repository, allowed: viewerGate.allowed, displayed: true)
        try await eventually { viewerReplacements == 2 && !sibling.busyForContract }
        precondition(sibling.value == nil && ConversationImages.decodes == readonlyDecodes)
        withExtendedLifetime(viewerChanges) {}
        source.stop(); sibling.stop()
        print("PASS: actual viewer activity bridge/binding gate handles an inactive inherited phase, live replacement and resume while timeline work is paused")

        let asset = PHAsset("asset", "original-crop")
        PHAsset.current["asset"] = asset
        let photos = PhotosAttachmentThumbnailSource(), manager = PHImageManager.default()
        photos.follow(asset, active: true)
        try await eventually { manager.calls == 1 }
        manager.complete(1)
        try await eventually { UnyriAttachmentImages.best(id: "photos:asset")?.image.name == "original-crop" }
        photos.stop(); precondition(PHPhotoLibrary.shared().registrations == 0)
        PHAsset.current["asset"] = PHAsset("asset", "hidden-edit") // no PHChange is delivered
        photos.follow(asset, active: true) // incoming PHAsset is still the old snapshot
        try await eventually { manager.calls == 2 }
        precondition(UnyriAttachmentImages.best(id: "photos:asset") == nil, "Old sharp crop must be retired before equal-quality replacement")
        manager.complete(2)
        try await eventually { UnyriAttachmentImages.best(id: "photos:asset")?.image.name == "hidden-edit" }
        manager.complete(1) // old callback is delivered despite cancellation
        try await Task.sleep(nanoseconds: 1_000_000)
        precondition(UnyriAttachmentImages.best(id: "photos:asset")?.image.name == "hidden-edit")
        photos.follow(asset, active: true); precondition(manager.calls == 2, "Unchanged active configuration does not request again")
        photos.stop(); photos.follow(asset, active: false)
        precondition(manager.calls == 2 && PHPhotoLibrary.shared().registrations == 0)
        print("PASS: actual Photos observer refetches an asset edited while hidden, replaces equal-quality pixels and rejects old callbacks")
    }
}
