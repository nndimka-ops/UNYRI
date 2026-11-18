import AVFoundation
import Combine
import SwiftUI
import UIKit

/// A plain reference: attachment views observe cache revisions, not every
/// MessagesRepository publish. Shared by the timeline and attachment surfaces.
private struct ChatMessagesRepositoryKey: EnvironmentKey {
    static var defaultValue: MessagesRepository? { nil }
}
extension EnvironmentValues {
    var chatMessagesRepository: MessagesRepository? {
        get { self[ChatMessagesRepositoryKey.self] }
        set { self[ChatMessagesRepositoryKey.self] = newValue }
    }
}

struct AttachmentImageRequest: Equatable {
    let id: String
    var url: URL?
    let mime: String
    var thumbnail: String? = nil
    var clientMessageID: String? = nil
    var note = false
    var pixelEdge = 768
    var repositoryAttachment = true
    var videoGeneration = true
    var photoGeneration = true

    init(id: String, url: URL?, mime: String, thumbnail: String? = nil,
         clientMessageID: String? = nil, note: Bool = false, pixelEdge: Int = 768,
         repositoryAttachment: Bool = true, videoGeneration: Bool = true, photoGeneration: Bool = true) {
        self.id = id; self.url = url; self.mime = mime.lowercased(); self.thumbnail = thumbnail
        self.clientMessageID = clientMessageID; self.note = note; self.pixelEdge = pixelEdge
        self.repositoryAttachment = repositoryAttachment; self.videoGeneration = videoGeneration
        self.photoGeneration = photoGeneration
    }
    init(attachment: AttachmentMetadata, path: String?, clientMessageID: String? = nil, pixelEdge: Int = 768) {
        self.init(id: attachment.attachmentId, url: path.map { URL(fileURLWithPath: $0) }, mime: attachment.mimeType,
            thumbnail: attachment.thumbnail, clientMessageID: clientMessageID, note: attachment.isVideoNote, pixelEdge: pixelEdge)
    }
    static func file(_ url: URL, video: Bool = false, clientMessageID: String? = nil, pixelEdge: Int = 768) -> Self {
        Self(id: "file:" + url.path, url: url, mime: video ? "video/mp4" : "image/*",
            clientMessageID: clientMessageID, pixelEdge: pixelEdge, repositoryAttachment: false)
    }
}

struct AttachmentImageValue {
    let image: UIImage
    let quality: AttachmentImagePolicy.Quality
    init(_ image: UIImage, _ origin: AttachmentImagePolicy.Origin) {
        self.image = image
        quality = .init(origin: origin, pixels: Int(max(image.size.width, image.size.height) * image.scale))
    }
}

/// One cache selector, upgrade stream and decode owner for every attachment surface.
/// Pixels stay in the existing cost-bounded caches. Descriptors/memos are bounded;
/// observers retain pixels only while mounted and release them on disappearance.
@MainActor enum UnyriAttachmentImages {
    enum Change { case path(String), replaced(String), retry(String), attachment(String), invalidated(String), client(String), cacheCleared, reset }
    static let changes = PassthroughSubject<Change, Never>()
    private static var descriptors: [String: AttachmentImageRequest] = [:]
    private static let external = NSCache<NSString, External>()
    private final class External: NSObject {
        let value: AttachmentImageValue
        init(_ value: AttachmentImageValue) { self.value = value }
    }
    private struct WorkKey: Hashable {
        let file: AttachmentImagePolicy.FileRevision
        let video: Bool
        let edge: Int
    }
    private struct Flight {
        let id: UUID
        let task: Task<UIImage?, Never>
        var consumers: Set<UUID>
    }
    private static var pending: [WorkKey: Flight] = [:]
    private static var failures: [WorkKey: Bool] = [:] // persistent vs exhausted transient
    private static var revisions: [String: AttachmentImagePolicy.FileRevision] = [:]
    private static var fullImageFailures: [AttachmentImagePolicy.FileRevision: Bool] = [:]
    private static let lifecycle = AttachmentImageLifecycle()
    static var attemptEpoch: UInt64? {
        UIApplication.shared.applicationState == .active ? lifecycle.epoch : nil
    }
    static func wasSteady(since epoch: UInt64?) -> Bool {
        epoch == lifecycle.epoch && UIApplication.shared.applicationState == .active
    }

    static func remember(_ request: AttachmentImageRequest) {
        var descriptor = request; descriptor.thumbnail = nil // never retain base64 in metadata
        if descriptors.count >= 512, descriptors[request.id] == nil { descriptors.removeAll(keepingCapacity: true) }
        descriptors[request.id] = descriptor
    }
    static func best(id: String) -> AttachmentImageValue? {
        if let request = descriptors[id] { return best(request) }
        return external.object(forKey: id as NSString)?.value
    }
    static func best(_ request: AttachmentImageRequest, retained: AttachmentImageValue? = nil,
                     initial: UIImage? = nil) -> AttachmentImageValue? {
        var result = retained
        func offer(_ image: UIImage?, _ origin: AttachmentImagePolicy.Origin) {
            guard let image, image.size.width > 0, image.size.height > 0 else { return }
            let candidate = AttachmentImageValue(image, origin)
            if AttachmentImagePolicy.prefers(candidate.quality, over: result?.quality) { result = candidate }
        }
        if let value = external.object(forKey: request.id as NSString)?.value,
           AttachmentImagePolicy.prefers(value.quality, over: result?.quality) { result = value }
        offer(initial, .inline) // unlabelled retained posters can be wire thumbnails
        offer(VideoNotePosters.cached(attachmentId: request.id, clientMessageId: request.clientMessageID), .notePoster)
        if let path = request.url?.path {
            for edge in [2048, 768, 512, 480, 384, 312, 256, 240, 128] {
                if request.mime.hasPrefix("video/") { offer(ConversationImages.cachedVideoFrame(path: path, pixelEdge: edge), .videoFrame) }
                else { offer(ConversationImages.cached(path: path, thumbnail: nil, pixelEdge: edge), .localImage) }
            }
        }
        if let thumbnail = request.thumbnail {
            for edge in [768, 512, 384, 256, 128] { offer(ConversationImages.cached(path: nil, thumbnail: thumbnail, pixelEdge: edge), .inline) }
        }
        return result
    }
    static func publish(_ image: UIImage, id: String, origin: AttachmentImagePolicy.Origin) {
        guard image.size.width > 0, image.size.height > 0 else { return }
        let value = AttachmentImageValue(image, origin)
        if AttachmentImagePolicy.prefers(value.quality, over: external.object(forKey: id as NSString)?.value.quality) {
            external.totalCostLimit = 24 * 1024 * 1024; external.countLimit = 120
            external.setObject(External(value), forKey: id as NSString,
                cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            changes.send(.attachment(id))
        }
    }
    static func invalidate(id: String) {
        external.removeObject(forKey: id as NSString); changes.send(.invalidated(id))
    }
    static func reset(restartVisible: Bool = false) {
        pending.values.forEach { $0.task.cancel() }; pending.removeAll()
        failures.removeAll(); fullImageFailures.removeAll(); revisions.removeAll(); descriptors.removeAll(); external.removeAllObjects()
        changes.send(restartVisible ? .cacheCleared : .reset)
    }
    static func resumeTransientFailures() {
        let paths = Set(failures.filter { !$0.value }.keys.map { $0.file.path } + fullImageFailures.filter { !$0.value }.keys.map { $0.path })
        failures = failures.filter { $0.value }; fullImageFailures = fullImageFailures.filter { $0.value }
        for path in paths { changes.send(.retry(path)) }
    }
    static func allowsFullImageDecode(_ revision: AttachmentImagePolicy.FileRevision?) -> Bool {
        revision.map { fullImageFailures[$0] == nil } ?? true
    }
    static func noteFullImageFailure(_ revision: AttachmentImagePolicy.FileRevision?, persistent: Bool) {
        guard let revision else { return }
        if fullImageFailures.count >= 256 { fullImageFailures.removeAll(keepingCapacity: true) }
        fullImageFailures[revision] = persistent
    }

    static func load(_ request: AttachmentImageRequest) async -> AttachmentImageValue? {
        guard !Task.isCancelled else { return nil }
        _ = lifecycle
        let generation = ConversationImages.generation
        remember(request)
        if request.note, request.repositoryAttachment,
           let poster = VideoNotePosters.cached(attachmentId: request.id, clientMessageId: request.clientMessageID) {
            VideoNotePosters.adopt(poster, attachmentId: request.id, clientMessageId: request.clientMessageID)
        }
        if best(request)?.quality.sharp != true, let thumbnail = request.thumbnail {
            _ = await ConversationImages.load(path: nil, thumbnail: thumbnail, pixelEdge: 256)
            if !Task.isCancelled, generation == ConversationImages.generation { changes.send(.attachment(request.id)) }
        }
        guard !Task.isCancelled, generation == ConversationImages.generation else { return nil }
        guard let url = request.url, url.isFileURL,
              request.mime.hasPrefix("image/") || request.mime.hasPrefix("video/") else { return best(request) }
        let revision = try? await Task.detached(priority: .utility) { try AttachmentImagePolicy.FileRevision.read(url) }.value
        guard let revision, !Task.isCancelled, generation == ConversationImages.generation else { return best(request) }
        let old = revisions[url.path]
        if revisions.count >= 512, old == nil { revisions.removeAll(keepingCapacity: true) }
        revisions[url.path] = revision
        if let old, old != revision {
            // Retire in-flight readers as well as pixels. A decode of the old
            // bytes must never repopulate this path after its replacement.
            let obsolete = pending.keys.filter { $0.file.path == revision.path && $0.file != revision }
            for key in obsolete { pending.removeValue(forKey: key)?.task.cancel() }
            ConversationImages.removeAttachmentImages(path: url.path)
            for descriptor in descriptors.values where descriptor.url?.path == url.path {
                VideoNotePosters.remove(attachmentId: descriptor.id)
                external.removeObject(forKey: descriptor.id as NSString)
            }
            changes.send(.replaced(url.path))
        }
        // Viewer pages may own decoding, but still need file replacement events.
        guard !request.mime.hasPrefix("video/") || request.videoGeneration,
              !request.mime.hasPrefix("image/") || request.photoGeneration else { return best(request) }
        let edge = min(768, max(128, request.pixelEdge))
        let video = request.mime.hasPrefix("video/")
        if let hit = best(request), hit.quality.sharp {
            if video {
                if hit.quality.origin == .notePoster || hit.quality.origin == .viewerFrame ||
                    ConversationImages.cachedVideoFrame(path: url.path, pixelEdge: 768) != nil { return hit }
            } else if hit.quality.pixels >= edge || ConversationImages.cached(path: url.path, thumbnail: nil, pixelEdge: edge) != nil { return hit }
        }
        let key = WorkKey(file: revision, video: video, edge: video ? 768 : edge)
        guard failures[key] == nil else { return best(request) }
        let consumer = UUID()
        let task: Task<UIImage?, Never>
        let requestID: UUID
        if var flight = pending[key] {
            flight.consumers.insert(consumer); pending[key] = flight; task = flight.task; requestID = flight.id
        } else {
            requestID = UUID()
            task = Task { @MainActor in
                let lease = MediaCacheLease(path: url.path)
                defer { withExtendedLifetime(lease) {} }
                var count = 0
                while !Task.isCancelled, generation == ConversationImages.generation {
                    let image: UIImage?
                    var persistent = false
                    if video {
                        let epoch = attemptEpoch
                        let result = await AttachmentVideoDecodeQueue.shared.load(url, note: request.note)
                        switch result {
                        case .success(let value): image = value
                        case .failure(let error): image = nil; persistent = persistentVideoError(error, steady: wasSteady(since: epoch))
                        }
                    } else {
                        // No inline fallback here: fallback is never a successful local decode.
                        image = await ConversationImages.load(path: url.path, thumbnail: nil, pixelEdge: edge)
                    }
                    guard !Task.isCancelled, generation == ConversationImages.generation,
                          (revisions[url.path] == nil || revisions[url.path] == revision) else { return nil }
                    if let image {
                        if video {
                            ConversationImages.cacheVideoFrame(image, path: url.path, pixelEdge: 768, generation: generation)
                            if request.note, !request.repositoryAttachment, let client = request.clientMessageID {
                                VideoNotePosters.store(image, clientMessageId: client)
                            }
                        }
                        return image
                    }
                    count += 1
                    guard let delay = AttachmentImagePolicy.retryDelay(failures: count, persistent: persistent) else {
                        if failures.count >= 512 { failures.removeAll(keepingCapacity: true) }
                        failures[key] = persistent
                        return nil
                    }
                    do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return nil }
                }
                return nil
            }
            pending[key] = Flight(id: requestID, task: task, consumers: [consumer])
        }
        _ = await withTaskCancellationHandler(operation: { await task.value }, onCancel: {
            Task { @MainActor in leave(key, consumer: consumer, request: requestID, cancelled: true) }
        })
        leave(key, consumer: consumer, request: requestID, cancelled: false)
        guard !Task.isCancelled, generation == ConversationImages.generation else { return nil }
        return best(request)
    }
    private static func leave(_ key: WorkKey, consumer: UUID, request: UUID, cancelled: Bool) {
        guard var flight = pending[key], flight.id == request else { return }
        flight.consumers.remove(consumer)
        if flight.consumers.isEmpty {
            if cancelled { flight.task.cancel() }
            pending[key] = nil
        } else { pending[key] = flight }
    }
    private static func persistentVideoError(_ error: Error, steady: Bool) -> Bool {
        guard let error = error as? AVError else { return false }
        let failure: AttachmentImagePolicy.Failure
        switch error.code {
        case .fileFormatNotRecognized, .fileFailedToParse, .failedToParse, .decoderNotFound,
             .undecodableMediaData, .formatUnsupported: failure = .unsupported
        case .contentIsProtected: failure = .protectedContent
        case .operationInterrupted, .mediaServicesWereReset: failure = .interrupted
        case .decoderTemporarilyUnavailable: failure = .resourceUnavailable
        default: failure = .unknown
        }
        return AttachmentImagePolicy.persistent(failure, steady: steady)
    }
}

@MainActor private final class AttachmentImageLifecycle {
    private(set) var epoch: UInt64 = 0
    private var observers: [NSObjectProtocol] = []
    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.epoch &+= 1 }
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { UnyriAttachmentImages.resumeTransientFailures() }
        })
        // After an unlock, protected files can become readable a moment after activation:
        // reads that failed in that gap are retried then, not on the next activation.
        observers.append(center.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { UnyriAttachmentImages.resumeTransientFailures() }
        })
    }
}

/// AVAssetImageGenerator awaits are reentrant; explicitly serialize generators,
/// rather than assuming actor isolation alone bounds native decoder concurrency.
private actor AttachmentVideoDecodeQueue {
    static let shared = AttachmentVideoDecodeQueue()
    private var tail: Task<Void, Never>?
    private var last = UUID()
    func load(_ url: URL, note: Bool) async -> Result<UIImage, Error> {
        guard !Task.isCancelled else { return .failure(CancellationError()) }
        let previous = tail
        let id = UUID(); last = id
        let job = Task.detached(priority: .utility) { () -> Result<UIImage, Error> in
            await previous?.value
            guard !Task.isCancelled else { return .failure(CancellationError()) }
            let asset = AVURLAsset(url: url)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 768, height: 768)
            generator.requestedTimeToleranceBefore = .zero
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 30)
            let span = PerformanceRecorder.shared.begin(.previewDecode, ["width": 768])
            let result: Result<UIImage, Error> = await withTaskCancellationHandler(operation: {
                do {
                    let seconds = note ? ((try? await asset.load(.duration).seconds) ?? 0) : 0
                    try Task.checkCancellation()
                    let time = note && seconds.isFinite && seconds > 0 ? VideoNoteLimits.posterTime(durationSeconds: seconds) : .zero
                    let frame = try await generator.image(at: time)
                    try Task.checkCancellation()
                    return .success(UIImage(cgImage: frame.image))
                } catch { return .failure(error) }
            }, onCancel: { asset.cancelLoading(); generator.cancelAllCGImageGeneration() })
            PerformanceRecorder.shared.end(span, success: (try? result.get()) != nil)
            return result
        }
        tail = Task { _ = await job.value }
        let result = await withTaskCancellationHandler(operation: { await job.value }, onCancel: { job.cancel() })
        if last == id { tail = nil }
        return result
    }
}

@MainActor final class UnyriAttachmentImageSource: ObservableObject {
    @Published private(set) var value: AttachmentImageValue?
    private var request: AttachmentImageRequest?
    private weak var repository: MessagesRepository?
    private var allowed = false
    private var displayed = true
    private var configured = false
    private var worker: Task<Void, Never>?
    private var subscriptions: [AnyCancellable] = []
    private var ticket = UUID()
    private var lastURL: URL?
    private var resolvedURL: URL?
    private var needsRevalidation = false
    func best(for fallback: AttachmentImageRequest, initial: UIImage? = nil) -> UIImage? {
        guard !configured || displayed else { return nil }
        // Retained pixels belong to the attachment, not to one exact request value: a
        // render that arrives before configure() with a changed flag or path (typically
        // right after returning from the background, when the decoded caches have been
        // purged) must keep showing them instead of a placeholder.
        let matches = request == fallback
        let sameAttachment = request?.id == fallback.id
        return UnyriAttachmentImages.best(matches ? (effective ?? fallback) : fallback,
            retained: sameAttachment ? value : nil, initial: initial)?.image
    }
    func configure(_ next: AttachmentImageRequest, repository: MessagesRepository?, allowed: Bool, displayed: Bool) {
        var resolvedNext = next
        if next.repositoryAttachment, let repository {
            resolvedNext.url = repository.cachedLocalPath(attachmentId: next.id).map { URL(fileURLWithPath: $0) } ?? resolvedURL ?? next.url
        }
        // A repository notification can start the upgrade before SwiftUI passes
        // the same new URL down. Do not cancel/redecode that work a second time.
        if request != next, effective == resolvedNext, self.repository === repository,
           self.allowed == allowed, self.displayed == displayed {
            request = next; return
        }
        let changed = request != next || self.repository !== repository || self.allowed != allowed || self.displayed != displayed
        guard changed || subscriptions.isEmpty else { return }
        if self.displayed != displayed { objectWillChange.send() }
        configured = true; self.displayed = displayed
        if !displayed { value = nil }
        worker?.cancel(); worker = nil; needsRevalidation = false; ticket = UUID()
        // Another message: its pixels are not this one's. The same attachment at another
        // path (moved cache, relocated container, file briefly unreadable while the phone
        // is locked) keeps its pixels until a fresh decode replaces them.
        if request?.id != next.id { value = nil; resolvedURL = nil }
        if let url = next.url { resolvedURL = url }
        request = next; self.repository = repository; self.allowed = allowed
        subscriptions.removeAll()
        subscriptions.append(UnyriAttachmentImages.changes.sink { [weak self] change in self?.changed(change) })
        if next.repositoryAttachment, let repository {
            subscriptions.append(repository.$attachmentCacheRevision.dropFirst().sink { [weak self] _ in self?.availabilityChanged() })
        }
        refresh(start: true)
    }
    private var effective: AttachmentImageRequest? {
        guard var request else { return nil }
        if request.repositoryAttachment, let repository {
            request.url = repository.cachedLocalPath(attachmentId: request.id).map { URL(fileURLWithPath: $0) } ?? resolvedURL
        }
        return request
    }
    private func availabilityChanged() {
        guard displayed, allowed else { return }
        if effective?.url != lastURL {
            worker?.cancel(); worker = nil; needsRevalidation = false; ticket = UUID()
        } else if worker != nil {
            // Coalesce notifications while a reader/retry is running. Unrelated
            // downloads never cancel it; same-path replacements still get checked.
            needsRevalidation = true; return
        }
        // load() stats the file off-main before accepting cached pixels/failures.
        // An unchanged revision is a cache hit, not another decode.
        refresh(start: true)
    }
    private func changed(_ change: UnyriAttachmentImages.Change) {
        guard let request = effective else { return }
        switch change {
        case .reset: stop(); return
        case .cacheCleared:
            // A deletion in another chat or a storage clear: this row's pixels are still its
            // own attachment's. Revalidate the file without blanking what is on screen.
            worker?.cancel(); worker = nil; needsRevalidation = false; ticket = UUID()
            refresh(start: true); return
        case .path(let path): guard path == request.url?.path else { return }
        case .replaced(let path): guard path == request.url?.path else { return }; value = nil
        case .retry(let path): guard path == request.url?.path else { return }; refresh(start: true); return
        case .attachment(let id): guard id == request.id else { return }
        case .invalidated(let id): guard id == request.id else { return }; value = nil
        case .client(let id): guard id == request.clientMessageID else { return }
        }
        refresh(start: false)
    }
    private func refresh(start: Bool) {
        guard let request = effective else { return }
        // A path that changed or vanished for a moment (protected data while locked, a
        // cache path refresh after returning to the app) never blanks pixels already on
        // screen: only a newer decode or an explicit invalidation replaces them.
        lastURL = request.url
        UnyriAttachmentImages.remember(request)
        if displayed, let best = UnyriAttachmentImages.best(request, retained: value),
           best.image !== value?.image || best.quality != value?.quality { value = best }
        guard start, allowed, worker == nil else { return }
        let expected = ticket
        let pathRepository = repository
        worker = Task { @MainActor [weak self] in
            var resolved = request
            if resolved.repositoryAttachment, resolved.url == nil, let pathRepository {
                let path = await pathRepository.attachmentImageLocalPath(attachmentId: resolved.id)
                guard let self, !Task.isCancelled, self.ticket == expected else { return }
                resolved.url = path.map { URL(fileURLWithPath: $0) }
                self.resolvedURL = resolved.url; self.lastURL = resolved.url
            }
            let result = await UnyriAttachmentImages.load(resolved)
            guard let self, !Task.isCancelled, self.ticket == expected else { return }
            self.worker = nil
            // An attachment id's bytes never change (drafts key their requests by path), so a
            // new path for the same id holds the same pixels: only better quality replaces them.
            if self.displayed, let result, AttachmentImagePolicy.prefers(result.quality, over: self.value?.quality) { self.value = result }
            if self.needsRevalidation {
                self.needsRevalidation = false
                self.refresh(start: true)
            }
        }
    }
    func stop() {
        displayed = false; configured = true
        ticket = UUID(); worker?.cancel(); worker = nil; needsRevalidation = false
        subscriptions.removeAll(); value = nil; request = nil; lastURL = nil; resolvedURL = nil
    }
}

struct UnyriAttachmentImageBinding: ViewModifier {
    @ObservedObject var source: UnyriAttachmentImageSource
    let request: AttachmentImageRequest
    var repository: MessagesRepository? = nil
    var workAllowed = true
    var viewerSurface = false
    var rendersImage = true
    var visibleWhenMounted = false
    var onVisibility: ((Bool) -> Void)? = nil
    @Environment(\.chatMessagesRepository) private var environmentRepository
    @Environment(\.scenePhase) private var phase
    @Environment(\.unyriMessagePreview) private var menu
    @ObservedObject private var mediaWork = UnyriMediaWorkState.shared
    @State private var visible = false
    @State private var visibilityKnown = false
    @State private var mounted = false
    private var allowed: Bool {
        workAllowed && (request.mime.hasPrefix("image/") || request.mime.hasPrefix("video/")) &&
        AttachmentImagePolicy.allowsWork(visible: visibleWhenMounted ? mounted : visible, menu: menu, active: phase == .active,
            viewerOpen: mediaWork.viewerOpen, viewerSurface: viewerSurface)
    }
    private var displayed: Bool { rendersImage && (menu || (visibleWhenMounted ? mounted : (visible || !visibilityKnown))) }
    private func configure() { source.configure(request, repository: repository ?? environmentRepository, allowed: allowed, displayed: displayed) }
    func body(content: Content) -> some View {
        content.modifier(UnyriMediaVisibility {
            visible = $0; visibilityKnown = true; onVisibility?($0)
        })
            .onAppear { mounted = true; configure() }.onChange(of: request) { _ in configure() }
            .onChange(of: allowed) { _ in configure() }.onDisappear { mounted = false; source.stop() }
            .onChange(of: displayed) { _ in configure() }
    }
}

struct UnyriAttachmentImage<Content: View>: View {
    let request: AttachmentImageRequest
    var repository: MessagesRepository? = nil
    var workAllowed = true
    var viewerSurface = false
    var rendersImage = true
    var visibleWhenMounted = false
    var initial: UIImage? = nil
    let content: (UIImage?) -> Content
    @StateObject private var source = UnyriAttachmentImageSource()
    init(request: AttachmentImageRequest, repository: MessagesRepository? = nil, workAllowed: Bool = true,
         viewerSurface: Bool = false, rendersImage: Bool = true, visibleWhenMounted: Bool = false,
         initial: UIImage? = nil, @ViewBuilder content: @escaping (UIImage?) -> Content) {
        self.request = request; self.repository = repository; self.workAllowed = workAllowed
        self.viewerSurface = viewerSurface; self.rendersImage = rendersImage; self.initial = initial; self.content = content
        self.visibleWhenMounted = visibleWhenMounted
    }
    var body: some View {
        content(source.best(for: request, initial: initial))
            .modifier(UnyriAttachmentImageBinding(source: source, request: request, repository: repository,
                workAllowed: workAllowed, viewerSurface: viewerSurface, rendersImage: rendersImage,
                visibleWhenMounted: visibleWhenMounted))
    }
}
