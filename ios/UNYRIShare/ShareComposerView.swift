import SwiftUI
import UIKit
import LocalAuthentication
import UniformTypeIdentifiers
import ImageIO
import AVFoundation
import Darwin

@MainActor
final class ShareComposerModel: ObservableObject {
    enum State: Equatable { case loading, signedOut, locked, legacy, picker, sending, done, error }
    struct Staged: Identifiable {
        var item: ShareJob.Item
        let thumbnail: UIImage?
        var id: String { item.id }
    }
    @Published var state: State = .loading
    @Published var index: ShareTargetSnapshot.Index?
    @Published var account: ShareTargetSnapshot.Account?
    @Published var snapshot: ShareTargetSnapshot.Snapshot?
    @Published var selected: String?
    @Published var search = ""
    @Published var caption = ""
    @Published var files: [Staged] = []
    @Published var error: String?
    @Published var stagedCount = 0
    @Published var copiedBytes: Int64 = 0
    @Published var expectedBytes: Int64 = 0
    @Published var stagingFinished = false
    @Published var loadingProvider = false
    @Published var providerFraction: Double?
    private let providers: [NSItemProvider]
    private let finish: (Bool) -> Void
    private var task: Task<Void, Never>?
    private var providerProgress: Progress?
    private var providerObservation: NSKeyValueObservation?
    private var providerLoadID: UUID?
    private var request: ShareJobStore.Staging?
    private var authentication: LAContext?
    private var closed = false
    private var ended = false
    private var activeItemIndex = 0
    private var committed = false
    private var sendRequested = false
    private var root: URL?
    private var authenticated = false
    private var initialLanguage: String {
        UnyriShareStore.language(saved: UserDefaults(suiteName: UnyriShareStore.group)?.string(forKey: "coco.language"), preferred: Locale.preferredLanguages.first ?? "en")
    }
    var language: String { account?.language ?? initialLanguage }
    var itemCount: Int { providers.count }
    var providerLoadingText: String { String(format: text("Loading item %1$d of %2$d… The original may need downloading from iCloud."), activeItemIndex + 1, itemCount) }
    func text(_ key: String) -> String { ShareStrings.text(key, language: language) }
    var targets: [ShareTargetSnapshot.Target] { ShareTargetSnapshot.ordered(snapshot?.targets ?? [], query: search) }
    var chosen: ShareTargetSnapshot.Target? { snapshot?.targets.first { $0.chatId == selected } }
    var captionValid: Bool { do { _ = try MessageTextPolicy.caption(caption); return true } catch { return false } }
    var routeError: String? { files.compactMap { ShareRoutePolicy.issue(kind: $0.item.kind, bytes: $0.item.bytes, sendAs: $0.item.sendAs) }.first }
    var canSend: Bool { chosen != nil && captionValid && routeError == nil && error == nil && state == .picker }
    var monochrome: Bool { account?.colorStyle != "colorful" }
    var stale: Bool { snapshot.map { Date().timeIntervalSince1970 - $0.writtenAt > 14 * 86400 } ?? false }
    var doneText: String {
        account?.state == "inactive"
            ? String(format: text("Queued. Open account “%@” in UNYRI to send."), account?.displayName ?? "UNYRI")
            : text("Queued. UNYRI will send when it runs. If it is closed, open it to send.")
    }
    var summary: String {
        let images = files.filter { $0.item.kind == .image }.count
        let videos = files.filter { $0.item.kind == .video }.count
        let count = String(format: text("%d photos · %d videos · %d files"), images, videos, files.count - images - videos)
        let bytes = files.reduce(Int64(0)) { $0 + $1.item.bytes }
        return files.isEmpty ? String(format: text("%d items"), providers.count) : count + " · " + ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
    init(providers: [NSItemProvider], finish: @escaping (Bool) -> Void) { self.providers = providers; self.finish = finish }
    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in await self?.load() }
    }
    private func load() async {
        guard (1...ShareItemClassification.maximumItems).contains(providers.count) else { state = .error; error = text("Choose between 1 and 10 items in total (photos, videos and files). Nothing was sent."); return }
        do {
            let root = try ShareContainer.root(); self.root = root
            // The atomic privacy fence closes the 2-second snapshot debounce
            // window without relying on UserDefaults propagation timing.
            let requiresLock = ShareContainer.requiresAuthentication(root: root)
            if requiresLock && !authenticated {
                guard await unlock() else { return }
            }
            let indexURL = ShareContainer.targets(root).appendingPathComponent("index.json")
            let loaded: ShareTargetSnapshot.Index? = try await ShareContainer.disk.perform {
                try ShareJobStore.sweepStaging(root: root, olderThan: 86400)
                if !FileManager.default.fileExists(atPath: indexURL.path) { return nil }
                return try ShareTargetSnapshot.readIndex(root: root)
            }
            guard !closed, !Task.isCancelled else { return }
            guard let loaded else {
                guard UnyriShareStore.activeBinding() != nil else { state = .signedOut; return }
                state = .legacy; return
            }
            // Do not publish the index (account display names) before LAContext.
            if loaded.accounts.contains(where: \.appLock) && !authenticated {
                guard await unlock() else { return }
            }
            guard !closed, !Task.isCancelled else { return }
            index = loaded
            guard let active = loaded.accounts.first(where: { $0.key == loaded.activeAccountKey && $0.state == "active" }) else { state = .signedOut; return }
            try await selectAccount(active)
        } catch {
            guard !closed else { return }
            // Protected files must never look like a missing snapshot.
            if (error as? CocoaError)?.code == .fileReadNoPermission { state = .locked }
            else { self.error = text("Could not load chats. Open UNYRI and share again."); state = .error }
        }
    }
    private func unlock() async -> Bool {
        state = .locked
        let context = LAContext(); authentication = context
        context.localizedCancelTitle = text("Close")
        var failure: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &failure) else { return false }
        do {
            let accepted = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: text("Unlock UNYRI with Face ID, Touch ID or your iPhone passcode."))
            guard accepted, !closed, !Task.isCancelled else { return false }
            authenticated = true; authentication = nil; return true
        } catch { authentication = nil; return false }
    }
    func select(_ account: ShareTargetSnapshot.Account) {
        guard state == .picker, !sendRequested, self.account?.key != account.key else { return }
        task?.cancel(); request?.cancellation.cancel(); stopProvider()
        do { try request?.cancelAndRemove(); request = nil } catch { self.error = text("Could not remove shared files. Tap Close to try again."); return }
        files = []; stagedCount = 0; stagingFinished = false; selected = nil; snapshot = nil; search = ""; error = nil
        task = Task { [weak self] in
            do { try await self?.selectAccount(account) }
            catch {
                guard let self, !self.closed, !Task.isCancelled else { return }
                self.account = account; self.snapshot = nil
                self.error = self.text("Open this account in UNYRI once to load its chats."); self.state = .picker
            }
        }
    }
    private func selectAccount(_ account: ShareTargetSnapshot.Account) async throws {
        guard let root, !closed else { throw CancellationError() }
        if account.appLock && !authenticated { guard await unlock() else { return } }
        let snapshot = try await ShareContainer.disk.perform { try ShareTargetSnapshot.read(account: account, root: root) }
        try Task.checkCancellation(); guard !closed else { throw CancellationError() }
        self.account = account; self.snapshot = snapshot; state = .picker
        // Own the tree before suspending, so Close can unlink it even if the
        // provider never calls back. No intermediate account parent is created.
        let request = try ShareJobStore.Staging(binding: account.binding, root: root)
        self.request = request
        do {
            for (index, provider) in providers.enumerated() {
                try Task.checkCancellation(); try request.cancellation.check()
                activeItemIndex = index; copiedBytes = 0; expectedBytes = 0
                let staged = try await stage(provider, index: index, request: request, choice: account.mediaChoice)
                try Task.checkCancellation(); guard !closed, self.request === request else { throw CancellationError() }
                files.append(staged); stagedCount += 1
            }
            stagingFinished = true
            if sendRequested { try commit() }
        } catch is CancellationError { try? request.cancelAndRemove() }
          catch {
            guard !closed, self.request === request else { return }
            self.error = message(error); state = .error
            try? request.cancelAndRemove()
        }
    }
    func setAsFile(_ id: String, _ value: Bool) {
        guard state == .picker, let index = files.firstIndex(where: { $0.id == id }) else { return }
        files[index].item.sendAs = value ? .file : .media
    }
    func send() {
        guard canSend else { return }
        sendRequested = true; state = .sending
        if stagingFinished {
            do { try commit() } catch { self.error = message(error); state = .error }
        }
    }
    private func commit() throws {
        guard !closed, let request, let account, let target = chosen else { throw CancellationError() }
        guard ShareContainer.isDirectory(request.directory.deletingLastPathComponent()) else {
            throw ShareFailure.issue("This account is no longer available. Open UNYRI and share again.")
        }
        if let issue = routeError { throw ShareFailure.issue(issue) }
        let job = ShareJob(jobId: request.id, createdAt: Date().timeIntervalSince1970, accountKey: account.key,
            target: .init(chatId: target.chatId, type: target.type, peerUserId: target.peerUserId, titleAtShare: target.title),
            caption: try MessageTextPolicy.caption(caption), albumId: files.count > 1 ? UUID().uuidString.lowercased() : nil,
            items: files.map(\.item))
        _ = try request.commit(job)
        committed = true; self.request = nil; state = .done
        ShareSignal.post()
        let delay: UInt64 = UIAccessibility.isVoiceOverRunning ? 5_000_000_000 : 800_000_000
        UIAccessibility.post(notification: .announcement, argument: doneText)
        Task { [weak self] in
            do { try await Task.sleep(nanoseconds: delay) } catch { return }
            self?.end(true)
        }
    }
    func close() {
        closed = true; authentication?.invalidate(); request?.cancellation.cancel(); stopProvider(); task?.cancel()
        do { try request?.cancelAndRemove(); request = nil; end(committed) }
        catch { self.error = text("Could not remove shared files. Tap Close to try again."); state = .error }
    }
    func disappeared() { if !committed { close() } }
    private func end(_ complete: Bool) { guard !ended else { return }; ended = true; finish(complete) }
    private enum ShareFailure: Error { case issue(String), noSpace(Int64, Int64), provider(Error), tooLarge(String, Int64, Bool) }
    private func message(_ error: Error) -> String {
        if let failure = error as? ShareFailure {
            switch failure {
            case .issue(let key): return text(key)
            case .provider(let error): return text(ShareItemClassification.providerFailure(error))
            case .tooLarge(let name, let bytes, let video):
                let actual = bytes == 0 ? 0 : (bytes - 1) / 1048576 + 1
                return String(format: text(video ? "Video “%1$@” is %2$lld MB. Video sources up to 500 MB can be queued for compression. Nothing was sent." : "File “%1$@” is %2$lld MB. Files and media sent as files must be at most 100 MB each. Nothing was sent."), name, actual)
            case .noSpace(let needed, let free):
                return String(format: text("Not enough space on this iPhone: needs %1$lld MB, %2$lld MB free."), (needed + 1048575) / 1048576, free / 1048576)
            }
        }
        if (error as NSError).code == CocoaError.fileWriteOutOfSpace.rawValue { return text("Not enough space on this iPhone. Free some storage and try again.") }
        return text("Could not read this file. Try again or share it from another app.")
    }
    private func stage(_ provider: NSItemProvider, index: Int, request: ShareJobStore.Staging, choice: String) async throws -> Staged {
        let identifiers = provider.registeredTypeIdentifiers
        let live = provider.hasItemConformingToTypeIdentifier(UTType.livePhoto.identifier) || identifiers.contains(where: ShareItemClassification.isLivePhotoIdentifier)
        var representations = identifiers.compactMap { identifier -> ShareItemClassification.Representation? in
            guard let type = UTType(identifier) else { return nil }
            let category: ShareItemClassification.Category
            if ShareItemClassification.isLivePhotoIdentifier(identifier) || type.conforms(to: .livePhoto) { category = .livePhoto }
            else if type == .fileURL { category = .fileURL }
            else if type.conforms(to: .movie) { category = .video }
            else if type.conforms(to: .image) && type != .svg { category = .image }
            else if type == .data { category = .genericData }
            else if type.conforms(to: .data) && type != .url { category = .file }
            else { return nil }
            return .init(identifier: identifier, category: category, filenameExtension: type.preferredFilenameExtension)
        }
        if live && !representations.contains(where: { $0.category == .image }) && provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            representations.append(.init(identifier: UTType.image.identifier, category: .image))
        }
        guard let selection = ShareItemClassification.select(representations, livePhoto: live) else {
            throw ShareFailure.issue(live ? "The Live Photo has no available still image. In Photos, choose a still photo and share again." : "This item cannot be shared to UNYRI. Share it as a file.")
        }
        stopProvider()
        let loadID = UUID(); providerLoadID = loadID; loadingProvider = true; providerFraction = nil
        defer { if providerLoadID == loadID { stopProvider(cancel: false) } }
        let id = UUID().uuidString.lowercased()
        let folder = try request.itemDirectory(id)
        let cancellation = request.cancellation
        // Own the coordinator before requesting either a materialized file or
        // a URL-only item. Close must interrupt an iCloud coordination wait too.
        let coordinator = NSFileCoordinator()
        let coordinationCancellation = cancellation.onCancel { coordinator.cancel() }
        var progressCancellation: UUID?
        defer {
            cancellation.removeHandler(coordinationCancellation)
            if let progressCancellation { cancellation.removeHandler(progressCancellation) }
        }
        return try await withTaskCancellationHandler(operation: {
          try await withCheckedThrowingContinuation { continuation in
            let complete: @Sendable (URL?, Error?) -> Void = { [weak self] url, error in
                do {
                    try cancellation.check()
                    if let error { throw ShareFailure.provider(error) }
                    guard let url else { throw ShareFailure.provider(error ?? CocoaError(.fileReadUnknown)) }
                    Task { @MainActor in
                        guard let self, self.providerLoadID == loadID, !self.closed else { return }
                        self.loadingProvider = false; self.providerObservation?.invalidate(); self.providerObservation = nil
                    }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let name = ShareItemClassification.name(provider.suggestedName, providerName: url.lastPathComponent, selection: selection)
                    var kind = ShareItemClassification.kind(selection, filename: name)
                    let bytes = try ShareContainer.regularSize(url)
                    if kind == .image, bytes > 25 * 1024 * 1024,
                       let image = PeerImagePolicy.source(url: url) {
                        let imageType = CGImageSourceGetType(image).map { $0 as String } ?? ""
                        if imageType == UTType.gif.identifier || imageType == UTType.webP.identifier || CGImageSourceGetCount(image) > 1 { kind = .file }
                    }
                    let sendAs = ShareItemClassification.initialSendAs(kind: kind)
                    if ShareRoutePolicy.issue(kind: kind, bytes: bytes, sendAs: sendAs) != nil { throw ShareFailure.tooLarge(name, bytes, kind == .video && sendAs == .media) }
                    let destination = folder.appendingPathComponent(name)
                    var coordinationError: NSError?
                    var result: Result<Int64, Error> = .failure(CocoaError(.fileReadUnknown))
                    try cancellation.check()
                    coordinator.coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
                        result = Result {
                            try cancellation.check()
                            let readableBytes = try ShareContainer.regularSize(readable)
                            if ShareRoutePolicy.issue(kind: kind, bytes: readableBytes, sendAs: sendAs) != nil {
                                throw ShareFailure.tooLarge(name, readableBytes, kind == .video && sendAs == .media)
                            }
                            // Clone only: FileManager.copyItem may silently fall
                            // back to an uninterruptible full copy. Our fallback
                            // always checks cancellation in each 256 KiB chunk.
                            if !Self.cloneProviderFile(readable, to: destination) {
                                try cancellation.check()
                                try UnyriShareStore.removeIfPresent(destination)
                                let free = try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage
                                if let free, readableBytes > free { throw ShareFailure.noSpace(readableBytes, free) }
                                let limit = kind == .image && sendAs == .media ? Int64(Int.max) : kind == .video && sendAs == .media ? ShareRoutePolicy.videoSourceBytes : ShareRoutePolicy.cloudBytes
                                _ = try UnyriShareStore.copy(readable, to: destination, limit: Int(limit), cancellation: cancellation, progress: { done in
                                    // Bound UI traffic to one update per MB.
                                    if done % 1048576 < 262144 {
                                        Task { @MainActor in
                                            guard let self, !self.closed, self.request === request, self.activeItemIndex == index else { return }
                                            self.copiedBytes = Int64(done); self.expectedBytes = readableBytes
                                        }
                                    }
                                })
                            }
                            try cancellation.check()
                            let actual = try ShareContainer.regularSize(destination)
                            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: destination.path)
                            return actual
                        }
                    }
                    if let coordinationError { throw coordinationError }
                    let actual: Int64
                    do { actual = try result.get() }
                    catch is UnyriShareStore.LimitExceeded {
                        throw ShareFailure.issue(kind == .video && sendAs == .media
                            ? "The video grew beyond 500 MB while loading. Nothing was sent."
                            : "The file grew beyond 100 MB while loading. Nothing was sent.")
                    }
                    if ShareRoutePolicy.issue(kind: kind, bytes: actual, sendAs: sendAs) != nil { throw ShareFailure.tooLarge(name, actual, kind == .video && sendAs == .media) }
                    let thumbnail = Self.preview(destination, kind: kind)
                    try cancellation.check()
                    continuation.resume(returning: Staged(item: .init(id: id, kind: kind, name: name, bytes: actual, albumItemIndex: index,
                        quality: choice == "image_high" ? .high : .standard, sendAs: sendAs), thumbnail: thumbnail))
                } catch { continuation.resume(throwing: error) }
            }
            if selection.usesFileURL {
                Self.loadURLItem(provider, cancellation: cancellation, completion: complete)
            }
            else {
                // Photos/iCloud materializes a disk file, never a Data/UIImage
                // containing the original. Clone/copy it inside its URL lifetime.
                let progress = provider.loadFileRepresentation(forTypeIdentifier: selection.identifier, completionHandler: complete)
                progressCancellation = cancellation.onCancel { progress.cancel() }
                providerProgress = progress
                providerObservation = Self.observeProvider(progress) { [weak self] value in
                    Task { @MainActor in
                        guard let self, !self.closed, self.providerLoadID == loadID, self.loadingProvider else { return }
                        if case .percent(let percent) = value { self.providerFraction = Double(percent) / 100 }
                        else { self.providerFraction = nil }
                    }
                }
            }
          }
        }, onCancel: { cancellation.cancel() })
    }
    /// Start URL-only loading off-main, but finish coordination and copying
    /// inline in its callback. A security grant does not keep a temp file alive.
    nonisolated private static func loadURLItem(_ provider: NSItemProvider, cancellation: UnyriShareStore.CopyCancellation,
                                               completion: @escaping @Sendable (URL?, Error?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do { try cancellation.check() } catch { completion(nil, error); return }
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { value, error in
                completion(value as? URL, error)
            }
        }
    }
    nonisolated private static func observeProvider(_ progress: Progress, update: @escaping @Sendable (ShareProviderProgressGate.Value) -> Void) -> NSKeyValueObservation {
        let gate = ShareProviderProgressGate()
        return progress.observe(\.fractionCompleted, options: [.initial, .new]) { progress, _ in
            guard let value = gate.take(fraction: progress.fractionCompleted, total: progress.totalUnitCount) else { return }
            update(value)
        }
    }
    nonisolated private static func cloneProviderFile(_ source: URL, to destination: URL) -> Bool {
        source.withUnsafeFileSystemRepresentation { sourcePath in
            destination.withUnsafeFileSystemRepresentation { destinationPath in
                guard let sourcePath, let destinationPath else { return false }
                return clonefile(sourcePath, destinationPath, UInt32(CLONE_NOFOLLOW)) == 0
            }
        }
    }
    private func stopProvider(cancel: Bool = true) {
        providerLoadID = nil; providerObservation?.invalidate(); providerObservation = nil
        if cancel { providerProgress?.cancel() }
        providerProgress = nil; loadingProvider = false; providerFraction = nil
    }
    nonisolated private static func preview(_ url: URL, kind: ShareJob.Kind) -> UIImage? {
        if kind == .image, let source = PeerImagePolicy.source(url: url),
           let pixels = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 160] as CFDictionary) { return UIImage(cgImage: pixels) }
        if kind == .video {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url)); generator.maximumSize = CGSize(width: 160, height: 160)
            generator.appliesPreferredTrackTransform = true
            if let pixels = try? generator.copyCGImage(at: .zero, actualTime: nil) { return UIImage(cgImage: pixels) }
        }
        return nil
    }
    func avatarURL(_ target: ShareTargetSnapshot.Target) -> URL? {
        guard let root, let account, let avatar = target.avatar else { return nil }
        return ShareContainer.targets(root).appendingPathComponent(account.key + "/avatars/" + avatar)
    }
}

@MainActor
struct ShareComposerView: View {
    @ObservedObject var model: ShareComposerModel
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .body) private var avatarSize: CGFloat = 60
    private var ink: Color { Color(white: 0.945) }
    private var accent: Color { model.monochrome ? Color(white: 0.87) : Color(red: 0.20, green: 0.79, blue: 0.91) }
    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Button(model.text("Close")) { model.close() }.frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("share-close")
                Spacer()
                Text(model.text("Send")).font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Color.clear.frame(width: 44, height: 44).accessibilityHidden(true)
            }.padding(.horizontal, 16)
            content
        }
        .foregroundStyle(ink).tint(accent)
        .environment(\.locale, Locale(identifier: model.language))
        .background(Color(white: 0.125).ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task { model.start() }
        .accessibilityIdentifier("share-composer")
    }
    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading: Spacer(); ProgressView().accessibilityLabel(model.text("Preparing share…")); Spacer()
        case .locked: status("lock.shield", "UNYRI is locked")
        case .signedOut: status("person.crop.circle.badge.exclamationmark", "Sign in to UNYRI to send files.")
        case .legacy: status("square.and.arrow.down", "Open UNYRI once after updating to choose a chat here.")
        case .done:
            status("checkmark.circle", model.doneText, translated: true)
        case .error:
            status("exclamationmark.circle", model.error ?? model.text("Could not read this file. Try again or share it from another app."), translated: true)
        case .picker, .sending: picker
        }
    }
    private func status(_ symbol: String, _ key: String, translated: Bool = false) -> some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: symbol).font(.largeTitle).accessibilityHidden(true)
            Text(translated ? key : model.text(key)).font(.body).multilineTextAlignment(.center)
            Spacer()
        }.padding(24).frame(maxWidth: .infinity)
    }
    private var picker: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 10) {
                    if let accounts = model.index?.accounts, accounts.count > 1 {
                        Menu {
                            ForEach(accounts.filter { $0.state != "signedOut" }) { account in
                                Button(account.displayName) { model.select(account) }
                            }
                        } label: { Label(model.account?.displayName ?? model.text("Account"), systemImage: "person.crop.circle").font(.subheadline).padding(10).frame(minHeight: 44) }
                        .accessibilityLabel(model.text("Account")).disabled(model.state == .sending)
                    }
                    Text(model.summary).font(.footnote).foregroundStyle(Color(white: 0.69)).padding(.horizontal, 16).accessibilityIdentifier("share-summary")
                    Text(model.text("Up to 10 items. Files up to 100 MB each; video originals up to 500 MB each. Photos are converted by UNYRI.")).font(.caption).foregroundStyle(Color(white: 0.69)).padding(.horizontal, 16)
                    previews
                    if model.stale { Text(model.text("The chat list may be out of date. Open UNYRI to refresh it.")).font(.footnote).padding(.horizontal, 16) }
                    if let error = model.routeError { Text(model.text(error)).font(.footnote).foregroundStyle(.red).padding(.horizontal, 16) }
                    if let error = model.error { Text(verbatim: error).font(.footnote).padding(.horizontal, 16) }
                    if model.state == .sending {
                        ProgressView(value: Double(model.stagedCount), total: Double(model.itemCount))
                            .padding(.horizontal, 20).accessibilityLabel(model.text("Preparing share…"))
                        Text(model.text("Preparing share…")).font(.body)
                        Button(model.text("Close — cancel sharing")) { model.close() }.frame(minHeight: 44)
                    } else {
                        HStack {
                            Image(systemName: "magnifyingglass").accessibilityHidden(true)
                            TextField(model.text("Search chats"), text: $model.search).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .accessibilityLabel(model.text("Search chats"))
                        }.padding(12).frame(minHeight: 44).modifier(SharePanelGlass(radius: 23)).padding(.horizontal, 16)
                        if model.targets.isEmpty {
                            Text(model.text(model.search.isEmpty ? "No chats yet. Open UNYRI to start a conversation." : "No results.")).padding(24)
                        }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 140 : 80), spacing: 8)], spacing: 18) {
                            ForEach(model.targets) { target in tile(target) }
                        }.padding(.horizontal, 12).padding(.vertical, 8)
                    }
                }.padding(.bottom, 12)
            }.scrollDismissesKeyboard(.interactively)
            if model.state == .picker && model.chosen != nil { composer }
        }
    }
    private var previews: some View {
        VStack(spacing: 5) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(model.files) { file in
                        VStack(spacing: 4) {
                            Group {
                                if let image = file.thumbnail { Image(uiImage: image).resizable().scaledToFill() }
                                else { Image(systemName: file.item.kind == .video ? "video" : file.item.kind == .image ? "photo" : "doc").font(.title2) }
                            }.frame(width: 68, height: 60).clipped().clipShape(RoundedRectangle(cornerRadius: 14)).accessibilityHidden(true)
                            Text(file.item.name).font(.caption).lineLimit(1).frame(width: 100)
                            if file.item.kind != .file {
                                Toggle(model.text("Send as file"), isOn: Binding(get: { model.files.first { $0.id == file.id }?.item.sendAs == .file }, set: { model.setAsFile(file.id, $0) }))
                                    .toggleStyle(.button).font(.caption).frame(minHeight: 44)
                                    .accessibilityLabel(model.text("Send as file") + ": " + file.item.name).disabled(model.state == .sending)
                            }
                        }.padding(8).modifier(SharePanelGlass(radius: 18))
                    }
                }.padding(.horizontal, 16)
            }
            if !model.stagingFinished {
                if model.loadingProvider {
                    Text(model.providerLoadingText).font(.caption).padding(.horizontal, 16).accessibilityIdentifier("share-loading-provider")
                    if let fraction = model.providerFraction {
                        ProgressView(value: fraction, total: 1).padding(.horizontal, 20)
                            .accessibilityLabel(model.providerLoadingText).accessibilityValue("\(Int((fraction * 100).rounded()))%")
                            .accessibilityIdentifier("share-provider-progress")
                    } else { ProgressView().accessibilityLabel(model.providerLoadingText) }
                } else {
                    HStack { ProgressView(); Text(model.text("Preparing files…")).font(.caption) }.accessibilityElement(children: .combine)
                }
                if model.expectedBytes > 0 { Text(String(format: model.text("%lld of %lld MB copied"), model.copiedBytes / 1048576, (model.expectedBytes + 1048575) / 1048576)).font(.caption) }
            }
            if model.files.contains(where: { $0.item.kind == .video && $0.item.sendAs == .media }) {
                Text(model.text("Videos are compressed by UNYRI. If a video still exceeds 100 MB, its message will show an error.")).font(.caption).foregroundStyle(Color(white: 0.69)).padding(.horizontal, 16)
            }
        }
    }
    private func tile(_ target: ShareTargetSnapshot.Target) -> some View {
        let selected = model.selected == target.chatId
        return Button { model.selected = selected ? nil : target.chatId } label: {
            VStack(spacing: 6) {
                ZStack(alignment: .bottomTrailing) {
                    ShareTargetAvatar(url: model.avatarURL(target), title: target.title, saved: target.type == "saved", size: min(avatarSize, 90), accent: accent)
                        .overlay(Circle().stroke(accent, lineWidth: selected ? 3 : 0).padding(-4))
                    if selected { Image(systemName: "checkmark.circle.fill").symbolRenderingMode(.palette).foregroundStyle(Color(white: 0.13), accent).background(Circle().fill(accent)).accessibilityHidden(true) }
                }.padding(5)
                Text(target.type == "saved" ? model.text("Saved Messages") : target.title).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                if target.archived { Text(model.text("Archive")).font(.caption2).foregroundStyle(Color(white: 0.69)) }
                else if target.pinned { Image(systemName: "pin.fill").font(.caption2).accessibilityLabel(model.text("Pinned")) }
            }.frame(maxWidth: .infinity, minHeight: 100).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityElement(children: .combine)
            .accessibilityIdentifier("share-target-" + target.chatId)
            .disabled(model.routeError != nil).opacity(model.routeError == nil ? 1 : 0.45)
            .accessibilityLabel(target.type == "saved" ? model.text("Saved Messages") : target.title)
            .accessibilityValue(target.archived ? model.text("Archive") : target.pinned ? model.text("Pinned") : "")
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityHint(model.routeError.map(model.text) ?? "")
    }
    private var composer: some View {
        VStack(spacing: 8) {
            Text(model.chosen.map { $0.type == "saved" ? model.text("Saved Messages") : $0.title } ?? "").font(.subheadline.weight(.semibold))
            HStack(alignment: .bottom, spacing: 10) {
                TextField(model.text("Add a comment…"), text: $model.caption, axis: .vertical).lineLimit(1...4)
                    .accessibilityIdentifier("share-caption")
                    .padding(12).modifier(SharePanelGlass(radius: 23)).accessibilityLabel(model.text("Add a comment…"))
                Button { model.send() } label: {
                    HStack(spacing: 8) {
                        Label(model.text("Send"), systemImage: "arrow.up").font(.headline)
                        Text("\(model.itemCount)").font(.caption.weight(.bold)).frame(minWidth: 24, minHeight: 24)
                            .background(Color.black.opacity(0.12), in: Circle())
                    }.padding(12).frame(minHeight: 44).foregroundStyle(Color(white: 0.13)).background(accent, in: Capsule())
                }
                    .disabled(!model.canSend)
                    .accessibilityIdentifier("share-confirm")
                    .accessibilityLabel(model.text("Send") + ": " + String(format: model.text("%d items"), model.itemCount))
            }
            if model.caption.unicodeScalars.count > 3500 { Text("\(model.caption.unicodeScalars.count) / 4096").font(.caption).monospacedDigit() }
            if !model.captionValid { Text(model.text("Caption is too long or contains unsupported characters.")).font(.caption).foregroundStyle(.red) }
        }.padding(.horizontal, 16).padding(.bottom, 12)
    }
}

/// The app's glass radii and monochrome palette (UnyriTheme/UnyriChatPicker),
/// with Dynamic Type text roles instead of fixed-size picker fonts.
private struct SharePanelGlass: ViewModifier {
    let radius: CGFloat
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if reduceTransparency { content.background(Color(white: 0.19), in: shape) }
        else if #available(iOS 26.0, *) { content.glassEffect(.regular, in: shape) }
        else { content.background(.ultraThinMaterial, in: shape) }
    }
}

private struct ShareTargetAvatar: View {
    let url: URL?
    let title: String
    let saved: Bool
    let size: CGFloat
    let accent: Color
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Circle().fill(saved ? accent : Color(white: 0.26))
            if saved { Image(systemName: "bookmark.fill").font(.title2).foregroundStyle(Color(white: 0.13)) }
            else if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Text(String(title.prefix(1))).font(.title2.weight(.medium)) }
        }.frame(width: size, height: size).clipShape(Circle()).accessibilityHidden(true)
            .task(id: url) {
                guard let url else { image = nil; return }
                // Snapshot JPEGs are <=120px; no original is ever decoded here.
                let loaded = await Task.detached(priority: .utility) { PeerImagePolicy.image(path: url.path) }.value
                if !Task.isCancelled { image = loaded }
            }
    }
}
