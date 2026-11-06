import Photos
import UniformTypeIdentifiers
import Network
import SwiftUI
import AVKit
import Combine
import ImageIO

struct UnyriMediaItem: Identifiable {
    let id: String
    let url: URL?
    let mime: String
    let name: String?
    let thumbnailBase64: String?
    init(url: URL, mime: String, thumbnailBase64: String? = nil) {
        self.id = url.path; self.url = url; self.mime = mime; self.name = url.lastPathComponent
        self.thumbnailBase64 = thumbnailBase64
    }
    init(id: String, url: URL?, mime: String, name: String?, thumbnailBase64: String? = nil) {
        self.id = id; self.url = url; self.mime = mime; self.name = name; self.thumbnailBase64 = thumbnailBase64
    }
}

enum UnyriMediaPagingPolicy {
    static func direction(previous: Int, next: Int) -> Double {
        guard previous >= 0, next >= 0, previous != next else { return 2 }
        return next > previous ? 1 : 0
    }
    static func initialID(ids: [String], urls: [URL?], requestedID: String?, initial: URL) -> String {
        if let requestedID, ids.contains(requestedID) { return requestedID }
        if let index = urls.firstIndex(where: { $0 == initial }), ids.indices.contains(index) { return ids[index] }
        return ids.first ?? ""
    }
    static func loadsPhoto(index: Int, selected: Int) -> Bool {
        index >= 0 && selected >= 0 && abs(index - selected) <= 1
    }
}
enum UnyriMediaGesturePolicy {
    static func acceptsTouch(y: Double, height: Double, top: Double, bottom: Double) -> Bool {
        y.isFinite && height.isFinite && top.isFinite && bottom.isFinite && height > 0 &&
            top >= 0 && bottom >= 0 && y >= top && y < height - bottom
    }
    static func isVertical(x: Double, y: Double) -> Bool {
        x.isFinite && y.isFinite && abs(y) > abs(x) * 1.5
    }
    static func dismisses(distance: Double, velocity: Double, height: Double) -> Bool {
        guard distance.isFinite, velocity.isFinite, height.isFinite, height > 0 else { return false }
        return abs(distance) > height * 0.15 || (abs(distance) > 24 && abs(velocity) > 900 && distance * velocity > 0)
    }
}
enum UnyriMediaPosterPolicy {
    static func thumbnail(_ value: String?) -> String? {
        guard let value, !value.isEmpty, value.utf8.count <= 128 * 1024 else { return nil }
        return value
    }
    static func cachedPath(url: URL?, mime: String) -> String? {
        guard let url, url.isFileURL else { return nil }
        if mime.hasPrefix("video/") { return "video-frame:768:" + url.path }
        return mime.hasPrefix("image/") ? url.path : nil
    }
}
private struct UnyriForwardMediaKey: EnvironmentKey { static let defaultValue: ((String) -> Void)? = nil }
extension EnvironmentValues {
    var unyriForwardMedia: ((String) -> Void)? { get { self[UnyriForwardMediaKey.self] } set { self[UnyriForwardMediaKey.self] = newValue } }
}
private struct UnyriMediaItemsKey: EnvironmentKey { static let defaultValue: [UnyriMediaItem] = [] }
extension EnvironmentValues {
    var unyriMediaItems: [UnyriMediaItem] { get { self[UnyriMediaItemsKey.self] } set { self[UnyriMediaItemsKey.self] = newValue } }
}

private struct UnyriMediaPosterRequest: Equatable {
    let url: URL?
    let mime: String
    let thumbnail: String?
    let nearby: Bool
    let active: Bool
    let selected: Bool
}

/// Reuse the existing decoded cache. Only embedded bytes are decoded here:
/// image pages and bounded video-frame generators already own local-file work.
@MainActor
private struct UnyriMediaPoster<Content: View>: View {
    let request: UnyriMediaPosterRequest
    let initialPreview: UIImage?
    let content: (UIImage?) -> Content
    @State private var image: UIImage?

    init(url: URL?, mime: String, thumbnail: String?, nearby: Bool, active: Bool, selected: Bool,
         initialPreview: UIImage?, @ViewBuilder content: @escaping (UIImage?) -> Content) {
        request = UnyriMediaPosterRequest(url: url, mime: mime,
            thumbnail: UnyriMediaPosterPolicy.thumbnail(thumbnail), nearby: nearby, active: active, selected: selected)
        self.initialPreview = initialPreview; self.content = content
    }

    private var cached: UIImage? {
        guard request.nearby else { return nil }
        return ConversationImages.cached(path: UnyriMediaPosterPolicy.cachedPath(url: request.url, mime: request.mime),
            thumbnail: request.thumbnail)
    }

    var body: some View {
        content(request.nearby ? (initialPreview ?? image ?? cached) : nil)
            .task(id: request) {
                guard request.nearby else { image = nil; return }
                if let initialPreview { image = initialPreview; return }
                if let cached { image = cached; return }
                guard request.active, let thumbnail = request.thumbnail else { return }
                let generation = ConversationImages.generation
                let decoded = await ConversationImages.load(path: nil, thumbnail: thumbnail)
                guard !Task.isCancelled, generation == ConversationImages.generation else { return }
                image = decoded
            }
            .onDisappear { image = nil }
    }
}

/// iOS 18 reports actual scroll clipping. Earlier systems use lazy-row lifetime.
struct UnyriMediaVisibility: ViewModifier {
    let changed: (Bool) -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollVisibilityChange(threshold: 0.01, changed)
                .onDisappear { changed(false) }
        } else {
            content.onAppear { changed(true) }.onDisappear { changed(false) }
        }
    }
}

struct UnyriMediaViewer: View {
    let items: [UnyriMediaItem]
    let initial: URL
    var download: (String) async -> URL? = { _ in nil }
    var onForward: ((String) -> Void)? = nil
    var onClose: (() -> Void)? = nil
    var onSelection: ((String) -> Void)? = nil
    var initialPreview: UIImage? = nil
    @State private var actions = false
    @State private var resolved: [String: URL] = [:]
    @State private var failed = Set<String>()
    @State private var retry = 0
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var viewerActive = UIApplication.shared.applicationState == .active
    @State private var selection: String
    @State private var previousPageIndex = -1
    @State private var controls = true
    @State private var headerHeight: CGFloat = 0
    @State private var exportFile = false
    @State private var actionError: String?
    @State private var videoPositions: [String: Double] = [:]
    init(items: [UnyriMediaItem], initial: URL, download: @escaping (String) async -> URL? = { _ in nil },
         onForward: ((String) -> Void)? = nil, onClose: (() -> Void)? = nil,
         onSelection: ((String) -> Void)? = nil, initialPreview: UIImage? = nil, initialID: String? = nil) {
        var seen = Set<String>()
        self.items = items.filter { seen.insert($0.id).inserted }
        self.initial = initial; self.download = download; self.onForward = onForward
        self.onClose = onClose; self.onSelection = onSelection; self.initialPreview = initialPreview
        let selected = UnyriMediaPagingPolicy.initialID(ids: self.items.map(\.id), urls: self.items.map(\.url), requestedID: initialID, initial: initial)
        _selection = State(initialValue: selected)
        // The clicked row's cache may be newer than the captured gallery list.
        _resolved = State(initialValue: selected.isEmpty ? [:] : [selected: initial])
    }
    var body: some View {
        let selectedIndex = items.firstIndex(where: { $0.id == selection }) ?? -1
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            TabView(selection: $selection) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    // Keep a fixed, identified page root when its inner media
                    // changes between a poster and an active player.
                    let url = resolved[item.id] ?? item.url
                    UnyriMediaPoster(url: url, mime: item.mime, thumbnail: item.thumbnailBase64,
                        nearby: UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                        active: viewerActive, selected: selection == item.id,
                        initialPreview: url == initial ? initialPreview : nil) { poster in
                      ZStack {
                        if let url {
                            if item.mime.hasPrefix("video/"), selection == item.id {
                                UnyriVideoPage(url: url, active: viewerActive, position: videoPositions[item.id] ?? 0, onControls: { value in
                                    if selection == item.id { controls = value }
                                }, controlsPinned: actions, actionsButton: AnyView(mediaActions), onDismiss: closeViewer,
                                    initialPreview: poster) { videoPositions[item.id] = $0 }
                            }
                            else if item.mime.hasPrefix("video/") {
                                UnyriVideoThumbnail(url: url, compact: false, showsDuration: false,
                                    workAllowed: viewerActive && UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                                    showsPlayIcon: false, aspectFit: true, initialPreview: poster)
                            }
                            else { UnyriZoomImage(url: url,
                                active: viewerActive && UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                                selected: selection == item.id,
                                initialPreview: poster,
                                onTap: { if selection == item.id { controls.toggle() } }, onDismiss: closeViewer) }
                        } else {
                            if let poster { Image(uiImage: poster).resizable().scaledToFit().allowsHitTesting(false) }
                            VStack(spacing: 18) {
                                Image(systemName: item.mime.hasPrefix("video/") ? "video" : "photo").font(.largeTitle)
                                if failed.contains(item.id) {
                                    Text("Download failed · Tap to retry")
                                    Button("Retry") { UnyriHaptics.press(); failed.remove(item.id); retry += 1 }
                                } else { ProgressView() }
                            }.foregroundColor(.white)
                        }
                      }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).id(item.id).tag(item.id)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never)).ignoresSafeArea()
            if controls || actions {
                VStack(alignment: .trailing, spacing: 10) {
                    HStack(spacing: 14) {
                        UnyriBackButton(action: { UnyriHaptics.press(); closeViewer() }, label: "Close")
                            .accessibilityIdentifier("media-return-to-chat")
                        Text("\(max(0, selectedIndex) + 1) / \(items.count)").font(.system(size: 13).monospacedDigit()).accessibilityIdentifier("media-page-counter").accessibilityValue(selection)
                        Spacer()
                    }
                    Spacer()
                    if items.first(where: { $0.id == selection })?.mime.hasPrefix("video/") != true {
                        HStack { mediaActions.background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 14)); Spacer() }
                    }

                }.buttonStyle(.plain).padding(16).foregroundColor(.white)
            }
            if actions {
                Color.black.opacity(0.001).ignoresSafeArea().onTapGesture { UnyriHaptics.press(); actions = false }
                VStack(alignment: .leading) {
                    Spacer()
                    if actions, let item = items.first(where: { $0.id == selection }), let url = resolved[item.id] ?? item.url {
                        VStack(alignment: .leading, spacing: 0) {
                            if let onForward {
                                Button { UnyriHaptics.press(); actions = false; onForward(item.id) } label: {
                                    Label("Forward to chat", systemImage: "arrowshape.turn.up.right").frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                                }
                            } else {
                                ShareLink(item: url) { Label("Share file", systemImage: "arrowshape.turn.up.right").frame(maxWidth: .infinity, minHeight: 52, alignment: .leading) }
                            }
                            Divider().overlay(.white.opacity(0.1))
                            Menu {
                                Button("Save to Files") { UnyriHaptics.press(); actions = false; exportFile = true }
                                Button("Save to Photos") { UnyriHaptics.press(); actions = false; Task { await saveToPhotos(url, mime: item.mime) } }
                            } label: { Label("Save to device", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity, minHeight: 52, alignment: .leading) }
                        }.padding(.horizontal, 18).padding(.vertical, 6).frame(maxWidth: 300)
                            .background(colorStyle.surface(Color(red: 0.125, green: 0.137, blue: 0.173)), in: RoundedRectangle(cornerRadius: 20))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.bottom, items.first(where: { $0.id == selection })?.mime.hasPrefix("video/") == true ? 124 : 76)
                    .buttonStyle(.plain).foregroundStyle(.white)
            }
        }.background(UnyriMediaRotation(active: true)).onPreferenceChange(MediaHeaderHeightKey.self) { headerHeight = $0 }.statusBarHidden(true)
            .onAppear {
                viewerActive = UIApplication.shared.applicationState == .active
                previousPageIndex = items.firstIndex(where: { $0.id == selection }) ?? -1
                onSelection?(selection)
            }
            // A UIKit-presented hosting controller is outside the SwiftUI scene
            // graph. Its default scenePhase must not disable a foreground player.
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in viewerActive = true }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in viewerActive = false }
            .onChange(of: selection) { id in
                let next = items.firstIndex(where: { $0.id == id }) ?? -1
                PerformanceRecorder.shared.record(.mediaPage, ["direction": UnyriMediaPagingPolicy.direction(previous: previousPageIndex, next: next), "items": Double(items.count)])
                previousPageIndex = next
                PerformanceMonitor.shared.activity()
                controls = true; actions = false; onSelection?(id)
            }
            .performanceScreen(.media)
            .task(id: selection + ":" + String(retry) + ":" + String(viewerActive)) {
                guard viewerActive, let item = items.first(where: { $0.id == selection }), item.url == nil, resolved[item.id] == nil else { return }
                let downloaded = await download(item.id)
                guard !Task.isCancelled else { return }
                if let downloaded { resolved[item.id] = downloaded }
                else { failed.insert(item.id) }
            }
            .sheet(isPresented: $exportFile) { if let item = items.first(where: { $0.id == selection }), let url = resolved[item.id] ?? item.url { UnyriFileExporter(url: url) } }
            .alert("Could not save", isPresented: Binding(get: { actionError != nil }, set: { if !$0 { actionError = nil } })) { Button("OK") { actionError = nil } } message: { Text(actionError ?? "") }
    }
    private func closeViewer() { if let onClose { onClose() } else { dismiss() } }
    private var mediaActions: some View {
        Button { UnyriHaptics.press(); actions.toggle() } label: {
            Image(systemName: "arrowshape.turn.up.right").font(.system(size: 21)).frame(width: 44, height: 44).contentShape(Rectangle())
        }.accessibilityLabel("Actions")
            .disabled(items.first(where: { $0.id == selection }).flatMap { resolved[$0.id] ?? $0.url } == nil)
    }
    private func saveToPhotos(_ url: URL, mime: String) async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { actionError = UnyriL10n.string("Allow Photos access in Settings to save media.", comment: ""); return }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                if mime.hasPrefix("video/") { PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) }
                else { PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url) }
            }
        } catch { actionError = error.localizedDescription }

    }
}

/// Weak, account-independent UI references only: no media bytes or persisted history.
@MainActor
private enum UnyriMediaSources {
    static let views = NSMapTable<NSString, UnyriMediaSourceView>(keyOptions: .strongMemory, valueOptions: .weakMemory)
    static weak var activeViewer: UIViewController?
}

@MainActor
final class UnyriMediaWorkState: ObservableObject {
    static let shared = UnyriMediaWorkState()
    @Published var viewerOpen = false
}

final class UnyriMediaSourceView: UIView {
    var mediaID = ""
    var previewImage: (() -> UIImage?)?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { UnyriMediaSources.views.setObject(self, forKey: mediaID as NSString) }
        else if UnyriMediaSources.views.object(forKey: mediaID as NSString) === self { UnyriMediaSources.views.removeObject(forKey: mediaID as NSString) }
    }
    func visibleRect(in container: UIView) -> CGRect? {
        guard window != nil, window === container.window else { return nil }
        let rect = convert(bounds, to: container)
        var visible = rect.intersection(container.bounds)
        var ancestor: UIView? = self
        while let view = ancestor {
            guard !view.isHidden, view.alpha > 0.01 else { return nil }
            if let scroll = view as? UIScrollView {
                visible = visible.intersection(scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: container))
            } else if view.clipsToBounds {
                visible = visible.intersection(view.convert(view.bounds, to: container))
            }
            ancestor = view.superview
        }
        // A clipped tile should fade instead of animating hidden pixels over the header.
        return UnyriMediaTransitionGeometry.sourceIsUsable(rect, visible: visible) ? rect : nil
    }
}

/// One presenter per visible attachment; presentation keeps the source chat in place.
struct UnyriMediaPresentation: UIViewRepresentable {
    @Binding var presented: Bool
    let id: String
    let image: () -> UIImage?
    let onDismiss: () -> Void
    let content: (@escaping () -> Void, @escaping (String) -> Void) -> AnyView
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UnyriMediaSourceView {
        let view = UnyriMediaSourceView(); view.isUserInteractionEnabled = false
        view.mediaID = id; view.backgroundColor = .clear
        return view
    }
    func updateUIView(_ view: UnyriMediaSourceView, context: Context) {
        if view.mediaID != id {
            if UnyriMediaSources.views.object(forKey: view.mediaID as NSString) === view { UnyriMediaSources.views.removeObject(forKey: view.mediaID as NSString) }
            view.mediaID = id
            if view.window != nil { UnyriMediaSources.views.setObject(view, forKey: id as NSString) }
        }
        view.previewImage = image
        let coordinator = context.coordinator
        coordinator.source = view
        // Dispatch out of the SwiftUI update transaction before presenting UIKit.
        coordinator.requested = presented
        coordinator.finish = { presented = false; onDismiss() }
        coordinator.content = content
        if presented { DispatchQueue.main.async { coordinator.presentIfNeeded() } }
        else { DispatchQueue.main.async { coordinator.close() } }
    }
    static func dismantleUIView(_ view: UnyriMediaSourceView, coordinator: Coordinator) {
        coordinator.requested = false
        coordinator.close()
        if UnyriMediaSources.views.object(forKey: view.mediaID as NSString) === view { UnyriMediaSources.views.removeObject(forKey: view.mediaID as NSString) }
    }
    final class Coordinator: NSObject, UIViewControllerTransitioningDelegate, UIAdaptivePresentationControllerDelegate {
        weak var source: UnyriMediaSourceView?
        var requested = false
        var finish: (() -> Void)?
        var content: ((@escaping () -> Void, @escaping (String) -> Void) -> AnyView)?
        private var host: UIHostingController<AnyView>?
        private var selectedID = ""
        private var closing = false
        func presentIfNeeded() {
            guard requested, host == nil, let source, let window = source.window, let content else { return }
            guard UnyriMediaSources.activeViewer == nil else { requested = false; finish?(); return }
            var owner = window.rootViewController
            while let next = owner?.presentedViewController { owner = next }
            guard let owner, !owner.isBeingDismissed, !owner.isBeingPresented else { return }
            selectedID = source.mediaID
            // This independently hosted tree subscribes to the existing theme
            // root. Environment updates keep page/player identity intact.
            let host = UIHostingController(rootView: AnyView(content({ [weak self] in self?.close() }, { [weak self] in self?.selectedID = $0 }).unyriAppColorThemeRoot()))
            host.view.backgroundColor = .black
            host.modalPresentationStyle = .custom
            host.modalPresentationCapturesStatusBarAppearance = true
            host.transitioningDelegate = self
            self.host = host
            UnyriMediaSources.activeViewer = host
            UnyriMediaWorkState.shared.viewerOpen = true
            owner.present(host, animated: true) { [self] in
                if !self.requested { self.close() }
            }
            host.presentationController?.delegate = self
        }
        func close() {
            guard let host, !closing, !host.isBeingPresented else { return }
            closing = true
            host.dismiss(animated: true) { [self] in
                if UnyriMediaSources.activeViewer === host { UnyriMediaSources.activeViewer = nil }
                UnyriMediaWorkState.shared.viewerOpen = UnyriMediaSources.activeViewer != nil
                self.host = nil; closing = false; requested = false
                finish?()
            }
        }
        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            if UnyriMediaSources.activeViewer === host { UnyriMediaSources.activeViewer = nil }
            UnyriMediaWorkState.shared.viewerOpen = UnyriMediaSources.activeViewer != nil
            host = nil; closing = false; requested = false; finish?()
        }
        func animationController(forPresented presented: UIViewController, presenting: UIViewController, source: UIViewController) -> UIViewControllerAnimatedTransitioning? {
            UnyriMediaTransition(opening: true, id: selectedID)
        }
        func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
            UnyriMediaTransition(opening: false, id: selectedID)
        }
        func presentationController(forPresented presented: UIViewController, presenting: UIViewController?, source: UIViewController) -> UIPresentationController? {
            UnyriMediaPresentationController(presentedViewController: presented, presenting: presenting)
        }
    }
}

private final class UnyriMediaPresentationController: UIPresentationController {
    override var shouldRemovePresentersView: Bool { false }
    override var shouldPresentInFullscreen: Bool { true }
    override var frameOfPresentedViewInContainerView: CGRect { containerView?.bounds ?? .zero }
    override func containerViewWillLayoutSubviews() {
        super.containerViewWillLayoutSubviews()
        presentedView?.frame = frameOfPresentedViewInContainerView
    }
}

enum UnyriMediaTransitionGeometry {
    static func sourceIsUsable(_ rect: CGRect, visible: CGRect) -> Bool {
        !rect.isNull && !visible.isNull && rect.width > 1 && rect.height > 1 &&
            visible.width >= rect.width - 1 && visible.height >= rect.height - 1
    }
    static func fittedRect(image: CGSize, viewport: CGRect) -> CGRect? {
        guard image.width.isFinite, image.height.isFinite, image.width > 0, image.height > 0,
              viewport.width.isFinite, viewport.height.isFinite, viewport.width > 0, viewport.height > 0 else { return nil }
        let ratio = min(viewport.width / image.width, viewport.height / image.height)
        let size = CGSize(width: image.width * ratio, height: image.height * ratio)
        return CGRect(x: viewport.midX - size.width / 2, y: viewport.midY - size.height / 2, width: size.width, height: size.height)
    }
}

private final class UnyriMediaTransition: NSObject, UIViewControllerAnimatedTransitioning {
    let opening: Bool
    let id: String
    init(opening: Bool, id: String) { self.opening = opening; self.id = id }
    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        UIAccessibility.isReduceMotionEnabled ? 0.12 : 0.22
    }
    func animateTransition(using context: UIViewControllerContextTransitioning) {
        guard let from = context.view(forKey: .from) ?? context.viewController(forKey: .from)?.view,
              let to = context.view(forKey: .to) ?? context.viewController(forKey: .to)?.view,
              let toController = context.viewController(forKey: .to) else { context.completeTransition(false); return }
        let container = context.containerView
        if opening {
            to.frame = context.finalFrame(for: toController)
            container.addSubview(to)
        }
        to.layoutIfNeeded()
        let animated = opening ? to : from
        let source = UnyriMediaSources.views.object(forKey: id as NSString)
        let sourceRect = source?.visibleRect(in: container)
        let displayedPhoto = opening ? nil : photo(in: from)
        let image = sourceRect != nil && !UIAccessibility.isReduceMotionEnabled ? displayedPhoto?.image ?? source?.previewImage?() : nil
        var tile: UIImageView?
        if let image, let sourceRect, let full = UnyriMediaTransitionGeometry.fittedRect(image: image.size, viewport: container.bounds) {
            let view = UIImageView(image: image); view.contentMode = .scaleAspectFill; view.clipsToBounds = true
            view.frame = opening ? sourceRect : (displayedPhoto?.convert(displayedPhoto?.bounds ?? .zero, to: container) ?? full)
            view.layer.cornerRadius = opening ? UnyriShapes.radiusBubble : 0
            container.addSubview(view); tile = view
            animated.alpha = opening ? 0 : 1
            UIView.animate(withDuration: transitionDuration(using: context), delay: 0, options: [.curveEaseInOut, .beginFromCurrentState]) {
                view.frame = self.opening ? full : sourceRect
                view.layer.cornerRadius = self.opening ? 0 : UnyriShapes.radiusBubble
                view.alpha = self.opening ? 0 : 1
                animated.alpha = self.opening ? 1 : 0
            } completion: { _ in
                tile?.removeFromSuperview(); animated.alpha = 1
                context.completeTransition(!context.transitionWasCancelled)
            }
        } else {
            animated.alpha = opening ? 0 : 1
            UIView.animate(withDuration: transitionDuration(using: context), animations: { animated.alpha = self.opening ? 1 : 0 }) { _ in
                animated.alpha = 1; context.completeTransition(!context.transitionWasCancelled)
            }
        }
    }
    private func photo(in view: UIView) -> UIImageView? {
        if let scroll = view as? UnyriPhotoScrollView, scroll.isSelectedMedia, let image = scroll.picture, image.image != nil { return image }
        for child in view.subviews { if let image = photo(in: child) { return image } }
        return nil
    }
}
private struct MediaHeaderHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

@MainActor
private final class MediaVideoPlayback: ObservableObject {
    let url: URL
    let player: AVPlayer
    let item: AVPlayerItem
    private let cacheLease: MediaCacheLease
    var hasStarted = false
    init(url: URL, position: Double) {
        self.url = url
        cacheLease = MediaCacheLease(path: url.path)
        item = AVPlayerItem(url: url); player = AVPlayer(playerItem: item)
        PerformanceRecorder.shared.record(.videoReady, ["phase": 2], screen: .media)
        if position.isFinite && position > 0 { player.seek(to: CMTime(seconds: position, preferredTimescale: 600)) }
    }
    deinit { player.pause(); PerformanceRecorder.shared.record(.videoReady, ["phase": 3], screen: .media) }
}

private struct UnyriVideoPage: View {
    @StateObject private var playback: MediaVideoPlayback
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @EnvironmentObject private var voiceRecorder: VoiceRecorder
    let active: Bool
    let onControls: (Bool) -> Void
    let controlsPinned: Bool
    let actionsButton: AnyView
    let onDismiss: () -> Void
    let initialPreview: UIImage?
    @State private var userMuted = false
    let onPosition: (Double) -> Void
    @State private var failed = false
    @State private var loading = true
    @State private var dismissOffset: CGFloat = 0
    @State private var firstFrameReady = false
    init(url: URL, active: Bool, position: Double = 0, onControls: @escaping (Bool) -> Void = { _ in }, controlsPinned: Bool = false, actionsButton: AnyView = AnyView(EmptyView()), onDismiss: @escaping () -> Void = {}, initialPreview: UIImage? = nil, onPosition: @escaping (Double) -> Void = { _ in }) {
        self.active = active
        self.onPosition = onPosition
        self.onControls = onControls
        self.controlsPinned = controlsPinned
        self.actionsButton = actionsButton
        self.onDismiss = onDismiss
        self.initialPreview = initialPreview
        _playback = StateObject(wrappedValue: MediaVideoPlayback(url: url, position: position))
    }
    var body: some View {
        UnyriVideoSurface(player: playback.player, ready: { firstFrameReady = $0 })
            .overlay {
                if !firstFrameReady {
                    Group {
                    if let initialPreview { Image(uiImage: initialPreview).resizable().scaledToFit() }
                    else {
                        // Only reuse the prepared poster here. The AVPlayer is
                        // already decoding: do not start a competing generator.
                        UnyriVideoThumbnail(url: playback.url, compact: false, showsDuration: false,
                            workAllowed: false, showsPlayIcon: false, aspectFit: true)
                    }
                    }.allowsHitTesting(false).accessibilityElement(children: .ignore)
                        .accessibilityLabel("Video preview").accessibilityIdentifier("media-video-loading-poster")
                }
            }
            .overlay { UnyriVideoControls(player: playback.player, active: active, onControls: onControls, controlsPinned: controlsPinned, actionsButton: actionsButton, audioAllowed: !voiceRecorder.callInProgress && !voiceRecorder.hasRecordingSession, muted: $userMuted, onDismiss: onDismiss, onDismissDrag: { value in
                withAnimation(value == 0 && !UIAccessibility.isReduceMotionEnabled ? .easeOut(duration: 0.18) : nil) { dismissOffset = value }
            }) }
            .overlay(alignment: .top) {
                if voiceRecorder.callInProgress || voiceRecorder.hasRecordingSession {
                    Text("Video plays without sound during a call or recording.").font(.caption).foregroundStyle(.white)
                        .padding(8).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 8)).padding(.top, 80)
                }
            }
            .overlay {
                if failed { Text("Preview unavailable. Save the file or open it in another app.").foregroundStyle(.white).padding(24).background(.black.opacity(0.8)) }
                else if loading { ProgressView().tint(.white).allowsHitTesting(false) }
            }
            .offset(y: dismissOffset)
            .onAppear { updatePlayback() }
            .onDisappear {
                playback.player.pause()
                let position = playback.player.currentTime().seconds
                if position.isFinite { onPosition(position) }
            }
            .onReceive(playback.item.publisher(for: \.status)) { status in
                failed = status == .failed; loading = status == .unknown
            }
            .onChange(of: userMuted) { _ in
                // Muted playback never claims the shared audio session. Configure
                // its route when sound is enabled, including after a silent start.
                playback.player.isMuted = userMuted || voiceRecorder.callInProgress || voiceRecorder.hasRecordingSession
                if !playback.player.isMuted && playback.player.rate > 0 { prepareAudio() }
            }
            .onChange(of: voiceRecorder.hasRecordingSession) { _ in playback.player.pause(); playback.player.isMuted = userMuted || voiceRecorder.callInProgress || voiceRecorder.hasRecordingSession }
            .onChange(of: active) { _ in updatePlayback() }
            .onChange(of: voiceRecorder.callInProgress) { _ in
                // Calls own the shared audio session. Never reset their route.
                playback.player.pause()
                playback.player.isMuted = voiceRecorder.callInProgress || voiceRecorder.hasRecordingSession || userMuted
            }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                if let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                   AVAudioSession.InterruptionType(rawValue: raw) == .began { playback.player.pause() }
            }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { notification in
                if let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable { playback.player.pause() }
            }
            .onReceive(playback.player.publisher(for: \.rate)) { rate in
                // Native controls can resume playback after a call or interruption.
                guard rate > 0 else { return }
                guard active else { playback.player.pause(); return }
                prepareAudio()
            }
    }
    @discardableResult
    private func prepareAudio() -> Bool {
        playback.player.isMuted = userMuted || voiceRecorder.callInProgress || voiceRecorder.hasRecordingSession
        guard !playback.player.isMuted else { return true }
        voicePlayer.stop()
        let session = AVAudioSession.sharedInstance()
        do {
            if session.category != .playback || session.mode != .moviePlayback {
                try session.setCategory(.playback, mode: .moviePlayback)
            }
            try session.setActive(true)
            return true
        } catch {
            // Do not play through a stale receiver route if configuration fails.
            playback.player.pause()
            return false
        }
    }
    private func updatePlayback() {
        guard active else { playback.player.pause(); return }
        guard prepareAudio() else { return }
        // Preserve a user's pause when coming back from another app.
        // Initial selection and a newly selected video start automatically.
        if !playback.hasStarted { playback.hasStarted = true; playback.player.play() }
    }
}
private struct UnyriVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    var ready: (Bool) -> Void = { _ in }
    final class Coordinator {
        var ready: (Bool) -> Void
        var observation: NSKeyValueObservation?
        var performanceSpan: PerformanceRecorder.Span? = PerformanceRecorder.shared.begin(.videoReady)
        init(_ ready: @escaping (Bool) -> Void) { self.ready = ready }
    }
    func makeCoordinator() -> Coordinator { Coordinator(ready) }
    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var videoLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> Surface {
        let view = Surface(); view.videoLayer.videoGravity = .resizeAspect; view.videoLayer.player = player
        view.isAccessibilityElement = true; view.accessibilityIdentifier = "media-video-surface"
        view.accessibilityLabel = UnyriL10n.string("Video")
        context.coordinator.observation = view.videoLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak coordinator = context.coordinator, weak view] layer, _ in
            let value = layer.isReadyForDisplay
            DispatchQueue.main.async {
                if value, let span = coordinator?.performanceSpan {
                    PerformanceRecorder.shared.end(span); coordinator?.performanceSpan = nil
                }
                view?.accessibilityValue = UnyriL10n.string(value ? "Ready" : "Loading…"); coordinator?.ready(value)
            }
        }
        return view
    }
    func updateUIView(_ view: Surface, context: Context) { context.coordinator.ready = ready; view.videoLayer.player = player }
    static func dismantleUIView(_ view: Surface, coordinator: Coordinator) {
        coordinator.observation?.invalidate(); coordinator.observation = nil
        if let span = coordinator.performanceSpan {
            // Closing or paging away before the first frame is not a decoder
            // failure. Complete the timing instead of leaving an orphan span.
            PerformanceRecorder.shared.end(span, success: false, metrics: ["status": 2])
            coordinator.performanceSpan = nil
        }
        view.videoLayer.player = nil
    }
}

/// Native slider samples only its own layer; the viewer does not re-render per frame.
private struct UnyriSmoothVideoSlider: UIViewRepresentable {
    let player: AVPlayer
    let active: Bool
    let duration: Double
    let tint: UIColor
    @Binding var position: Double
    let editing: (Bool) -> Void
    func makeUIView(context: Context) -> ProgressSlider {
        let slider = ProgressSlider()
        slider.minimumTrackTintColor = tint
        slider.accessibilityLabel = String(localized: "Playback position")
        slider.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .valueChanged)
        slider.addTarget(context.coordinator, action: #selector(Coordinator.began), for: .touchDown)
        slider.addTarget(context.coordinator, action: #selector(Coordinator.ended), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        return slider
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func updateUIView(_ slider: ProgressSlider, context: Context) {
        context.coordinator.owner = self
        slider.minimumTrackTintColor = tint
        slider.player = player
        slider.maximumValue = Float(max(1, duration)); slider.isEnabled = duration > 0
        if !slider.isTracking { slider.value = Float(position) }
        slider.running = active && player.rate > 0
    }
    static func dismantleUIView(_ slider: ProgressSlider, coordinator: Coordinator) { slider.running = false; slider.player = nil }
    final class Coordinator: NSObject {
        var owner: UnyriSmoothVideoSlider
        init(_ owner: UnyriSmoothVideoSlider) { self.owner = owner }
        @objc func began() { owner.editing(true) }
        @objc func changed(_ slider: UISlider) { owner.position = Double(slider.value); if !slider.isTracking { owner.editing(false) } }
        @objc func ended() { owner.editing(false) }
    }
    final class ProgressSlider: UISlider {
        weak var player: AVPlayer?
        private var link: CADisplayLink?
        var running = false { didSet { synchronize() } }
        override func didMoveToWindow() { super.didMoveToWindow(); synchronize() }
        private func synchronize() {
            if running && window != nil {
                if link == nil { let timer = CADisplayLink(target: self, selector: #selector(tick)); timer.add(to: .main, forMode: .common); link = timer }
            } else { link?.invalidate(); link = nil }
        }
        @objc private func tick() {
            guard !isTracking, let seconds = player?.currentTime().seconds, seconds.isFinite else { return }
            value = Float(seconds)
        }
    }
}

/// Video gestures recognize vertical intent before beginning. Horizontal pans
/// belong exclusively to the enclosing pager; controls above this view retain
/// their native tap/slider gestures.
private struct UnyriVideoGestures: UIViewRepresentable {
    let tap: (CGFloat, Bool) -> Void
    let hold: (Bool) -> Void
    let dismiss: () -> Void
    let drag: (CGFloat) -> Void
    let reservedTop: CGFloat
    let reservedBottom: CGFloat
    final class GestureSurface: UIView {
        var reservedTop: CGFloat = 0
        var reservedBottom: CGFloat = 0
        override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
            guard UnyriMediaGesturePolicy.acceptsTouch(y: point.y, height: bounds.height, top: reservedTop, bottom: reservedBottom) else { return false }
            return super.point(inside: point, with: event)
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UIView {
        let view = GestureSurface(); view.backgroundColor = .clear
        view.reservedTop = reservedTop; view.reservedBottom = reservedBottom
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.single(_:)))
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.double(_:))); double.numberOfTapsRequired = 2
        let held = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.held(_:)))
        held.minimumPressDuration = 0.35; held.allowableMovement = 12; held.delegate = context.coordinator
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pan(_:)))
        pan.maximumNumberOfTouches = 1; pan.delegate = context.coordinator
        single.require(toFail: double); single.require(toFail: held); double.require(toFail: held)
        let gestures: [UIGestureRecognizer] = [single, double, held, pan]
        for gesture in gestures { view.addGestureRecognizer(gesture) }
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.owner = self
        (view as? GestureSurface)?.reservedTop = reservedTop
        (view as? GestureSurface)?.reservedBottom = reservedBottom
    }
    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) { coordinator.owner.hold(false) }
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var owner: UnyriVideoGestures
        init(_ owner: UnyriVideoGestures) { self.owner = owner }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let view = gestureRecognizer.view else { return false }
            if let pan = gestureRecognizer as? UIPanGestureRecognizer {
                let v = pan.velocity(in: view)
                return UnyriMediaGesturePolicy.isVertical(x: v.x, y: v.y)
            }
            return gestureRecognizer.location(in: view).x > view.bounds.width * 0.6
        }
        @objc func single(_ gesture: UITapGestureRecognizer) { tapped(gesture, double: false) }
        @objc func double(_ gesture: UITapGestureRecognizer) { tapped(gesture, double: true) }
        private func tapped(_ gesture: UITapGestureRecognizer, double: Bool) {
            guard let view = gesture.view, view.bounds.width > 0 else { return }
            owner.tap(gesture.location(in: view).x / view.bounds.width, double)
        }
        @objc func held(_ gesture: UILongPressGestureRecognizer) {
            if gesture.state == .began { owner.hold(true) }
            else if gesture.state == .ended || gesture.state == .cancelled || gesture.state == .failed { owner.hold(false) }
        }
        @objc func pan(_ gesture: UIPanGestureRecognizer) {
            guard let view = gesture.view else { return }
            let distance = gesture.translation(in: view).y
            switch gesture.state {
            case .changed: owner.drag(distance)
            case .ended:
                if UnyriMediaGesturePolicy.dismisses(distance: distance, velocity: gesture.velocity(in: view).y, height: view.bounds.height) { owner.dismiss() }
                else { owner.drag(0) }
            case .cancelled, .failed: owner.drag(0)
            default: break
            }
        }
    }
}

private struct UnyriVideoControls: View {
    let player: AVPlayer
    let active: Bool
    let onControls: (Bool) -> Void
    let controlsPinned: Bool
    let actionsButton: AnyView
    let audioAllowed: Bool
    @Binding var muted: Bool
    var onDismiss: () -> Void = {}
    var onDismissDrag: (CGFloat) -> Void = { _ in }
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var visible = true
    @State private var playing = false
    @State private var dragging = false
    @State private var position = 0.0
    @State private var duration = 0.0
    @State private var interaction = 0
    @State private var feedback = ""
    @State private var speed: Float = 1
    @State private var speedMenu = false
    @State private var heldRate: Float?
    private var timerKey: String { "\(visible):\(playing):\(dragging):\(active):\(voiceOver):\(speedMenu):\(interaction):\(controlsPinned):\(heldRate != nil)" }
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                UnyriVideoGestures(tap: { fraction, double in
                    if double { skip(fraction < 0.5 ? -10 : 10) } else { toggleControls() }
                }, hold: { holding in
                    if holding, active, playing, heldRate == nil {
                        heldRate = player.rate; player.rate = max(2, speed)
                        UnyriHaptics.selection(); interaction += 1
                    } else if !holding { restoreHeldRate() }
                }, dismiss: {
                    guard !controlsPinned else { return }
                    restoreHeldRate(); onDismiss()
                }, drag: { value in if !controlsPinned { onDismissDrag(value) } },
                   reservedTop: visible ? (geometry.size.width > geometry.size.height ? 96 : 132) : 0,
                   reservedBottom: visible ? max(geometry.safeAreaInsets.bottom + 120, geometry.size.width > geometry.size.height ? 132 : 156) : 0)
                    .accessibilityHidden(true)
                if visible {
                    VStack {
                        Spacer()
                        VStack(spacing: 0) {
                            HStack(spacing: 8) {
                                Text(time(position)).font(.caption.monospacedDigit())
                                UnyriSmoothVideoSlider(player: player, active: active && visible, duration: duration,
                                    tint: UIColor(colorStyle.decorative(Color(red: 0.54, green: 0.50, blue: 1))), position: $position) { editing in
                                    dragging = editing; interaction += 1; if !editing { if duration > 0 { UnyriHaptics.selection() }; seek(position) }
                                }.frame(height: geometry.size.width > geometry.size.height ? 36 : 44)

                                Text(time(duration)).font(.caption.monospacedDigit())
                            }
                            if panelWidth(geometry) - 24 < 308 {
                                // Centered transport needs 132 + 2 * 88 points.
                                // Narrow viewports use disjoint layout slots.
                                HStack(spacing: 0) {
                                    actionsButton
                                    Spacer(minLength: 0)
                                    transportControls
                                    Spacer(minLength: 0)
                                    playbackOptions
                                }
                            } else {
                                ZStack {
                                    HStack(spacing: 0) {
                                        actionsButton
                                        Spacer(minLength: 0)
                                        playbackOptions
                                    }
                                    transportControls
                                }
                            }
                        }.padding(.horizontal, 12).padding(.vertical, 4)
                            .frame(width: panelWidth(geometry))
                            .background(colorStyle.surface(Color(red: 0.09, green: 0.106, blue: 0.145)).opacity(0.55), in: RoundedRectangle(cornerRadius: 20))
                            .padding(.bottom, max(16, geometry.safeAreaInsets.bottom + 16))
                    }
                }
                if !feedback.isEmpty {
                    Text(LocalizedStringKey(feedback)).font(.subheadline).padding(14).background(.black.opacity(0.7), in: Capsule()).offset(y: -90).allowsHitTesting(false)
                }
                if heldRate != nil {
                    Text("2×").font(.subheadline.monospacedDigit()).padding(10)
                        .background(.black.opacity(0.65), in: Capsule()).frame(maxHeight: .infinity, alignment: .top).padding(.top, 76)
                        .allowsHitTesting(false).accessibilityIdentifier("media-temporary-speed")
                }
            }.foregroundStyle(.white)
        }
        .onAppear { refresh(); onControls(true) }
        .onChange(of: visible) { onControls($0) }
        .onChange(of: active) { value in if !value { restoreHeldRate() } }
        .onDisappear { restoreHeldRate() }
        .onReceive(player.publisher(for: \.rate)) { rate in playing = rate > 0; if !playing { visible = true }; refresh() }
        .task(id: timerKey) {
            guard visible && playing && !dragging && active && !voiceOver && !speedMenu && !controlsPinned && heldRate == nil else { return }
            do { try await Task.sleep(nanoseconds: 3_500_000_000); visible = false } catch { }
        }
        .task(id: "\(visible):\(active):\(dragging)") {
            // Only the small overlay samples progress; no per-frame view updates.
            guard visible && active && !dragging else { return }
            while !Task.isCancelled {
                refresh()
                do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            }
        }
        .task(id: "\(feedback):\(interaction)") {
            guard !feedback.isEmpty else { return }
            do { try await Task.sleep(nanoseconds: 650_000_000); feedback = "" } catch { }
        }
        .confirmationDialog("Playback speed", isPresented: $speedMenu) {
            ForEach([Float(0.5), 1, 1.5, 2], id: \.self) { value in
                Button("\(String(format: "%g", value))×") { UnyriHaptics.selection(); speed = value; if playing { player.rate = value }; interaction += 1 }
            }
            Button("Cancel", role: .cancel) { interaction += 1 }
        }
    }
    private func panelWidth(_ geometry: GeometryProxy) -> CGFloat {
        max(0, min(geometry.size.width - 32, geometry.size.width > geometry.size.height ? 440 : geometry.size.width))
    }
    private var playbackOptions: some View {
        HStack(spacing: 0) {
            Button { UnyriHaptics.press(); speedMenu = true; interaction += 1 } label: {
                Text("\(String(format: "%g", speed))×").font(.subheadline).frame(width: 44, height: 44).contentShape(Rectangle())
            }.accessibilityLabel("Playback speed").accessibilityValue("\(String(format: "%g", heldRate == nil ? speed : max(2, speed)))×")
            control(muted || !audioAllowed ? "speaker.slash" : "speaker.wave.2", label: muted ? "Unmute" : "Mute", size: 44) { UnyriHaptics.press(); muted.toggle(); interaction += 1 }.disabled(!audioAllowed)
        }.fixedSize()
    }
    private var transportControls: some View {
        HStack(spacing: 0) {
            control("gobackward.10", label: "Back 10 seconds", size: 44) { skip(-10) }.disabled(duration <= 0)
            control(playing ? "pause.fill" : "play.fill", label: playing ? "Pause" : "Play", size: 44, accent: true) {
                UnyriHaptics.press()
                if playing { player.pause() }
                else { if duration > 0 && position >= duration - 0.1 { seek(0) }; player.playImmediately(atRate: speed) }
                interaction += 1
            }
            control("goforward.10", label: "Forward 10 seconds", size: 44) { skip(10) }.disabled(duration <= 0)
        }.fixedSize()
    }
    private func restoreHeldRate() {
        guard let rate = heldRate else { return }
        heldRate = nil
        if player.rate > 0 { player.rate = rate }
        interaction += 1
    }
    private func control(_ icon: String, label: String, size: CGFloat, accent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.system(size: 21)).frame(width: 36, height: 36)
            .foregroundStyle(accent ? colorStyle.onColorfulButton : .white)
            .background(accent ? colorStyle.decorative(Color(red: 0.396, green: 0.345, blue: 1)) : Color.clear, in: Circle())
            .frame(width: size, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(label)))
    }
    private func toggleControls() { UnyriHaptics.press(); visible.toggle(); interaction += 1 }
    private func refresh() {
        let length = player.currentItem?.duration.seconds ?? 0
        duration = length.isFinite ? max(0, length) : 0
        let current = player.currentTime().seconds
        if !dragging && current.isFinite { position = min(duration, max(0, current)) }
    }
    private func seek(_ value: Double) {
        guard duration > 0 else { return }
        position = min(duration, max(0, value))
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    private func skip(_ offset: Double) {
        guard duration > 0 else { return }
        UnyriHaptics.selection()
        let current = player.currentTime().seconds
        seek((current.isFinite ? current : position) + offset)
        feedback = offset < 0 ? "Back 10 seconds" : "Forward 10 seconds"; interaction += 1
    }
    private func time(_ value: Double) -> String {
        let seconds = Int(value.isFinite ? max(0, value) : 0)
        return seconds >= 3600 ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
private struct UnyriZoomImage: UIViewRepresentable {
    let url: URL
    let active: Bool
    var selected = true
    var initialPreview: UIImage? = nil
    let onTap: () -> Void
    let onDismiss: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap, onDismiss: onDismiss) }
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UnyriPhotoScrollView(); scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 5; scroll.delegate = context.coordinator
        scroll.isSelectedMedia = selected
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.panGestureRecognizer.isEnabled = false
        scroll.backgroundColor = .black; scroll.showsHorizontalScrollIndicator = false; scroll.showsVerticalScrollIndicator = false
        let image = UIImageView(image: initialPreview); image.contentMode = .scaleAspectFit; image.clipsToBounds = true; scroll.addSubview(image)
        image.accessibilityIdentifier = "media-viewer-photo"; image.accessibilityLabel = UnyriL10n.string("Photo")
        image.isAccessibilityElement = selected && initialPreview != nil
        scroll.picture = image
        context.coordinator.image = image; context.coordinator.scroll = scroll
        let error = UILabel(); error.text = UnyriL10n.string("Preview unavailable. Save the file or open it in another app.", comment: ""); error.textColor = .white; error.numberOfLines = 0; error.textAlignment = .center; error.isHidden = true
        error.translatesAutoresizingMaskIntoConstraints = false; scroll.addSubview(error)
        NSLayoutConstraint.activate([error.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor), error.centerYAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerYAnchor), error.widthAnchor.constraint(lessThanOrEqualTo: scroll.frameLayoutGuide.widthAnchor, multiplier: 0.8)])
        context.coordinator.errorLabel = error

        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:))); double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap)); single.require(toFail: double)
        scroll.addGestureRecognizer(single); scroll.addGestureRecognizer(double)
        let dismissPan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.dismissPan(_:)))
        dismissPan.maximumNumberOfTouches = 1; dismissPan.delegate = context.coordinator
        scroll.addGestureRecognizer(dismissPan)
        scroll.panGestureRecognizer.require(toFail: dismissPan)
        if active { context.coordinator.load(url, pixelEdge: selected ? 2048 : 768, animate: selected) }
        return scroll
    }
    func updateUIView(_ scroll: UIScrollView, context: Context) {
        (scroll as? UnyriPhotoScrollView)?.isSelectedMedia = selected
        // The embedded poster may finish after this representable was created.
        // Never replace an already decoded full image with a smaller thumbnail.
        if context.coordinator.image?.image == nil, let initialPreview {
            context.coordinator.image?.image = initialPreview
        }
        context.coordinator.image?.isAccessibilityElement = selected && context.coordinator.image?.image != nil
        if !selected { scroll.setZoomScale(1, animated: false) }
        scroll.panGestureRecognizer.isEnabled = selected && scroll.zoomScale > 1.01
        if !active {
            context.coordinator.stop()
            if !selected { context.coordinator.image?.image = nil; context.coordinator.hasDecodedImage = false }
            scroll.transform = .identity
        }
        else if context.coordinator.task == nil || context.coordinator.loadedURL != url ||
                    context.coordinator.loadedPixelEdge < (selected ? 2048 : 768) ||
                    (context.coordinator.animatedSource && selected != context.coordinator.animatesImage) {
            let coordinator = context.coordinator
            if !(coordinator.hasDecodedImage && !coordinator.animatedSource && coordinator.loadedURL == url && coordinator.loadedPixelEdge >= (selected ? 2048 : 768)) {
                coordinator.load(url, pixelEdge: selected ? 2048 : 768, animate: selected)
            }
        }
        scroll.setNeedsLayout()
    }
    static func dismantleUIView(_ scroll: UIScrollView, coordinator: Coordinator) { coordinator.stop(); coordinator.image?.stopAnimating(); coordinator.image?.image = nil }
    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        weak var image: UIImageView?
        weak var errorLabel: UILabel?
        weak var scroll: UIScrollView?
        let onTap: () -> Void
        var task: Task<Void, Never>?
        var loadedURL: URL?
        var loadedPixelEdge = 0
        var animatedSource = false
        var animatesImage = false
        var hasDecodedImage = false
        private var loadID = UUID()
        let onDismiss: () -> Void
        init(onTap: @escaping () -> Void, onDismiss: @escaping () -> Void) { self.onTap = onTap; self.onDismiss = onDismiss }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer, let scroll else { return true }
            let velocity = pan.velocity(in: scroll.superview)
            return scroll.zoomScale <= 1.01 && abs(velocity.y) > abs(velocity.x) * 1.5
        }
        @objc func dismissPan(_ recognizer: UIPanGestureRecognizer) {
            guard let scroll else { return }
            let translation = recognizer.translation(in: scroll.superview)
            switch recognizer.state {
            case .changed:
                scroll.transform = CGAffineTransform(translationX: 0, y: translation.y)
            case .ended:
                if scroll.zoomScale <= 1.01 && UnyriMediaGesturePolicy.dismisses(distance: translation.y, velocity: recognizer.velocity(in: scroll.superview).y, height: scroll.bounds.height) { onDismiss() }
                else { resetDismissDrag() }
            case .cancelled, .failed: resetDismissDrag()
            default: break
            }
        }
        private func resetDismissDrag() {
            guard let scroll else { return }
            UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) { scroll.transform = .identity }
        }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { image }
        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            let photo = scrollView as? UnyriPhotoScrollView
            scrollView.panGestureRecognizer.isEnabled = photo?.isSelectedMedia == true && scrollView.zoomScale > 1.01
            photo?.centerPicture()
        }
        @objc func singleTap() { onTap() }
        @objc func doubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let scroll, let image else { return }
            let animate = !UIAccessibility.isReduceMotionEnabled
            if scroll.zoomScale > 1 { scroll.setZoomScale(1, animated: animate); return }
            let point = recognizer.location(in: image)
            let size = CGSize(width: scroll.bounds.width / 2.5, height: scroll.bounds.height / 2.5)
            scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: animate)
        }
        func stop() { task?.cancel(); task = nil; loadID = UUID() }
        func load(_ url: URL, pixelEdge: Int, animate: Bool) {
            stop(); loadedURL = url; loadedPixelEdge = pixelEdge; animatesImage = animate; hasDecodedImage = false
            let id = loadID
            errorLabel?.isHidden = true
            task = Task.detached(priority: .userInitiated) { [weak self] in
                var performanceSpan = PerformanceRecorder.shared.begin(.previewDecode, ["width": Double(pixelEdge)])
                var performanceSuccess = false
                defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
                guard !Task.isCancelled else { return }
                guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { await MainActor.run { [weak self] in if self?.loadID == id { self?.errorLabel?.isHidden = false } }; return }
                let count = CGImageSourceGetCount(source)
                await MainActor.run { [weak self] in if self?.loadID == id { self?.animatedSource = count > 1 } }
                guard count > 0 else { await MainActor.run { [weak self] in if self?.loadID == id { self?.errorLabel?.isHidden = false } }; return }
                var index = 0
                repeat {
                    if Task.isCancelled { return }
                    let frame: (UIImage?, Double) = autoreleasepool {
                        let cg = CGImageSourceCreateThumbnailAtIndex(source,index,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceShouldCacheImmediately:true,kCGImageSourceThumbnailMaxPixelSize:count > 1 ? min(1024, pixelEdge) : pixelEdge] as CFDictionary)
                        let properties = CGImageSourceCopyPropertiesAtIndex(source,index,nil) as? [CFString:Any]
                        let gif = (properties?[kCGImagePropertyGIFDictionary] as? [CFString:Any]) ?? (properties?[kCGImagePropertyPNGDictionary] as? [CFString:Any]) ?? (properties?["{WebP}" as CFString] as? [CFString:Any])
                        let delay = gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double ?? gif?[kCGImagePropertyGIFDelayTime] as? Double ?? 0.1
                        let duration = delay.isFinite ? min(10, max(0.02, delay)) : 0.1
                        return (cg.map { UIImage(cgImage: $0) }, duration)
                    }
                    if index == 0 {
                        performanceSuccess = frame.0 != nil
                        PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess)
                        performanceSpan = nil
                    }
                    await MainActor.run { [weak self] in
                        guard !Task.isCancelled, self?.loadID == id else { return }
                        self?.errorLabel?.isHidden = frame.0 != nil
                        if let decoded = frame.0 { self?.image?.image = decoded }
                        self?.hasDecodedImage = frame.0 != nil
                        self?.image?.isAccessibilityElement = frame.0 != nil && (self?.scroll as? UnyriPhotoScrollView)?.isSelectedMedia == true
                        self?.scroll?.setNeedsLayout()
                    }
                    if count > 1 && animate { try? await Task.sleep(nanoseconds: UInt64(frame.1 * 1_000_000_000)) }
                    index = (index + 1) % count
                } while count > 1 && animate && !Task.isCancelled
            }
        }
    }
}

private final class UnyriPhotoScrollView: UIScrollView {
    var isSelectedMedia = true
    weak var picture: UIImageView?
    private var viewport = CGSize.zero
    func centerPicture() {
        let size = picture?.frame.size ?? .zero
        let vertical = max(0, (bounds.height - size.height) / 2)
        let horizontal = max(0, (bounds.width - size.width) / 2)
        let inset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        if contentInset != inset { contentInset = inset }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let picture, let image = picture.image, image.size.width > 0, image.size.height > 0 else { return }
        if viewport != bounds.size { viewport = bounds.size; setZoomScale(1, animated: false) }
        if zoomScale == 1 {
            let ratio = min(bounds.width / image.size.width, bounds.height / image.size.height)
            picture.frame = CGRect(origin: .zero, size: CGSize(width: image.size.width * ratio, height: image.size.height * ratio))
            contentSize = picture.frame.size
        }
        centerPicture()
    }
}

@MainActor final class UnyriMediaNetwork: ObservableObject {
    static let shared = UnyriMediaNetwork()
    @Published var wifi = false
    @Published var connected = false
    private let monitor = NWPathMonitor()
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.usesInterfaceType(.wifi); let connected = path.status == .satisfied
            Task { @MainActor [weak self] in self?.wifi = wifi; self?.connected = connected }
        }
        monitor.start(queue: DispatchQueue(label: "unyri.media.network"))
    }
}

private struct UnyriCancelDownloadKey: EnvironmentKey { static let defaultValue: (String) -> Void = { _ in } }
extension EnvironmentValues {
    var unyriCancelDownload: (String) -> Void { get { self[UnyriCancelDownloadKey.self] } set { self[UnyriCancelDownloadKey.self] = newValue } }
}

@MainActor final class UnyriIncomingFile: ObservableObject {
    static let shared = UnyriIncomingFile()
    @Published var url: URL?
    private(set) var files: [(id: String, url: URL)] = []
    private var batchDirectory: URL?
    private var scoped = false
    func receive(_ value: URL) {
        guard value.isFileURL else { return }
        close(); scoped = value.startAccessingSecurityScopedResource()
        files = [(UUID().uuidString.lowercased(), value)]; url = value
    }
    func checkSharedInbox() {
        guard url == nil, UIApplication.shared.isProtectedDataAvailable,
              let root = try? UnyriShareStore.root(),
              let directories = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey]) else { return }
        for directory in directories.filter({ $0.pathExtension == "ready" }).sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            guard let entries = try? UnyriShareStore.files(in: directory) else { continue }
            if entries.isEmpty { try? FileManager.default.removeItem(at: directory); continue }
            batchDirectory = directory; files = entries.map { ($0.0.id, $0.1) }; url = files.first?.url
            return
        }
    }
    func acknowledge(_ id: String) throws {
        if let directory = batchDirectory {
            try Data().write(to: directory.appendingPathComponent(id + ".done"), options: [.atomic, .completeFileProtection])
        }
    }
    func close() {
        if scoped { url?.stopAccessingSecurityScopedResource() }
        // Only extension-owned copies are removed; originals remain untouched.
        if let directory = batchDirectory { try? FileManager.default.removeItem(at: directory) }
        scoped = false; batchDirectory = nil; files = []; url = nil
    }
}

struct UnyriImportFileSheet: View {
    @ObservedObject var inbox: UnyriIncomingFile
    let messages: MessagesRepository
    let chats: ChatsRepository
    @State private var destinations: [ChatSummary] = []
    @State private var selected: ChatSummary?
    @State private var error: String?
    @State private var busy = false
    @State private var staged: [String: URL] = [:]
    @State private var sent = Set<String>()
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List {
                Section("Files") {
                    ForEach(inbox.files, id: \.id) { file in
                        HStack(spacing: 12) {
                            Image(systemName: "doc").font(.system(size: 18)).foregroundColor(Color(hex: 0x7BC9EC))
                                .frame(width: 32, height: 32).accessibilityHidden(true)
                            Text(file.url.lastPathComponent).font(.subheadline).foregroundColor(UnyriColors.primaryText)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }.frame(minHeight: 44).accessibilityIdentifier("import-file-" + file.id)
                    }
                    Text("Files are sent as originals after confirmation.").font(.footnote).foregroundColor(UnyriColors.secondaryText)
                }.listRowBackground(Color(hex: 0x1B1B1D))
                Section("Chats") {
                    ForEach(destinations.filter { search.isEmpty || ($0.otherUser?.displayName ?? "Saved Messages").localizedCaseInsensitiveContains(search) }) { chat in
                        Button { UnyriHaptics.selection(); selected = chat } label: {
                            HStack(spacing: 12) {
                                Image(systemName: chat.otherUser == nil ? "bookmark" : "bubble.left")
                                    .font(.system(size: 17)).foregroundColor(Color(hex: 0xAFA4F7))
                                    .frame(width: 32, height: 32).accessibilityHidden(true)
                                Text(chat.otherUser?.displayName ?? UnyriL10n.string("Saved Messages"))
                                    .font(.subheadline.weight(.medium)).foregroundColor(UnyriColors.primaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 4)
                                Image(systemName: selected?.chatId == chat.chatId ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(selected?.chatId == chat.chatId ? Color(hex: 0x83D9B7) : UnyriColors.veryMutedText)
                                    .font(.system(size: 21)).accessibilityHidden(true)
                            }.frame(minHeight: 48).contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(busy)
                            .accessibilityIdentifier("import-destination-" + chat.chatId)
                            .accessibilityAddTraits(selected?.chatId == chat.chatId ? [.isSelected] : [])
                            .accessibilityValue(Text(LocalizedStringKey(selected?.chatId == chat.chatId ? "Selected" : "Not selected")))
                            .listRowBackground(selected?.chatId == chat.chatId ? Color(hex: 0x25282E) : Color(hex: 0x1B1B1D))
                    }
                }
                if let error { Text(error).font(.footnote).foregroundColor(UnyriColors.danger).listRowBackground(Color(hex: 0x1B1B1D)) }
                if busy { ProgressView() }
            }.listStyle(.insetGrouped).unyriScreenBackground().navigationTitle("Share file").navigationBarTitleDisplayMode(.inline)
                .accessibilityIdentifier("import-file-sheet").searchable(text: $search).tint(Color(hex: 0x7BC9EC))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { UnyriHaptics.press(); inbox.close() }.frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("import-files-cancel").disabled(busy) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Send") {
                            guard let source = inbox.url, let chat = selected else { return }; UnyriHaptics.press(); busy = true
                            Task {
                                defer { busy = false }
                                do {
                                    let files = inbox.files
                                    for file in files where !sent.contains(file.id) {
                                        guard inbox.url == source else { throw CancellationError() }
                                        if messages.localMessage(id: file.id) == nil {
                                            let copy: URL
                                            if let existing = staged[file.id] { copy = existing }
                                            else { copy = try await messages.stageDraftFile(file.url); staged[file.id] = copy }
                                            guard inbox.url == source else { throw CancellationError() }
                                            guard messages.queueMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id, url: copy, kind: "file", caption: nil) else { throw CocoaError(.fileWriteUnknown) }
                                        }
                                        try inbox.acknowledge(file.id)
                                        sent.insert(file.id)
                                    }
                                    if inbox.url == source { inbox.close() }
                                } catch { self.error = error.localizedDescription }
                            }
                        }.frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("import-files-send").disabled(selected == nil || busy)
                    }
                }
                .task {
                    do {
                        var offset: Int64 = 0
                        while !Task.isCancelled {
                            let page = try await chats.listChats(limit: 100, offset: offset).items
                            destinations.append(contentsOf: page.filter { item in !destinations.contains(where: { $0.id == item.id }) })
                            if page.count < 100 { break }; offset += Int64(page.count)
                        }
                    } catch { self.error = error.localizedDescription }
                }
        }.interactiveDismissDisabled(busy)
    }
}

private struct UnyriFileExporter: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController { UIDocumentPickerViewController(forExporting: [url], asCopy: true) }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}

/// Generate only a bounded preview; never load the entire video into memory.
struct UnyriVideoThumbnail: View {
    let url: URL
    var compact = true
    var showsDuration = true
    var workAllowed = true
    var showsPlayIcon = true
    var aspectFit = false
    var initialPreview: UIImage? = nil
    var onPreview: (UIImage) -> Void = { _ in }
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var mediaWork = UnyriMediaWorkState.shared
    @State private var image: UIImage?
    @State private var duration: Double = 0
    @State private var imageSource: String?
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if let image = image ?? ConversationImages.cached(path: "video-frame:\(compact ? 312 : 768):" + url.path, thumbnail: nil) ?? initialPreview { Image(uiImage: image).resizable().aspectRatio(contentMode: aspectFit ? .fit : .fill) }
            else { Color.black.opacity(0.2) }
            if showsPlayIcon { Image(systemName: "play.circle.fill").font(.title).foregroundColor(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
            if showsDuration && duration.isFinite && duration > 0 { Text(String(format: "%d:%02d", Int(duration) / 60, Int(duration) % 60)).font(.caption.monospacedDigit()).padding(4).background(.black.opacity(0.6)).foregroundColor(.white) }
        }.frame(width: compact ? 104 : nil, height: compact ? 78 : nil).clipped().clipShape(RoundedRectangle(cornerRadius: aspectFit ? 0 : 10))
            .task(id: "\(url.path)|\(compact)|\(workAllowed)|\(scenePhase)|\(mediaWork.viewerOpen)") {
                let pixelEdge = compact ? 312 : 768
                let source = "\(pixelEdge):" + url.path
                if imageSource != source {
                    imageSource = source; image = nil; duration = 0
                }
                // Visibility/background changes pause work; they must not erase
                // an already displayed frame if the shared cache was evicted.
                if let cached = ConversationImages.cached(path: "video-frame:\(pixelEdge):" + url.path, thumbnail: nil) {
                    PerformanceRecorder.shared.record(.previewCache, ["cache_hit": 1]); image = cached
                }
                if let image { onPreview(image) }
                guard workAllowed, !isMenuPreview, (aspectFit || scenePhase == .active), (aspectFit || !mediaWork.viewerOpen) else { return }
                if image != nil && (!showsDuration || duration > 0) { return }
                let performanceSpan = PerformanceRecorder.shared.begin(.previewDecode)
                var performanceSuccess = false
                defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
                let generation = ConversationImages.generation
                let asset = AVURLAsset(url: url)
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: CGFloat(pixelEdge), height: CGFloat(pixelEdge))
                await withTaskCancellationHandler {
                    do {
                        let seconds = try await asset.load(.duration).seconds
                        guard !Task.isCancelled, generation == ConversationImages.generation else { return }
                        duration = seconds
                        if image == nil {
                            let frame = try await generator.image(at: .zero)
                            guard !Task.isCancelled, generation == ConversationImages.generation else { return }
                            let preview = UIImage(cgImage: frame.image)
                            ConversationImages.cacheVideoFrame(preview, path: url.path, pixelEdge: pixelEdge, generation: generation)
                            image = preview
                            onPreview(preview)
                        }
                        performanceSuccess = true
                    } catch { /* Keep the playable fallback for unsupported thumbnail codecs. */ }
                } onCancel: { generator.cancelAllCGImageGeneration() }
            }
    }
}

/// iPad supports every orientation; iPhone rotation belongs to the presenting media scene.
@MainActor
enum UnyriMediaOrientation {
    private static var viewers: [String: Set<UUID>] = [:]
    static func mask(for scene: UIWindowScene?) -> UIInterfaceOrientationMask {
        if UIDevice.current.userInterfaceIdiom == .pad { return .all }
        guard let scene, viewers[scene.session.persistentIdentifier]?.isEmpty == false else { return .portrait }
        return [.portrait, .landscapeLeft, .landscapeRight]
    }
    static func set(_ enabled: Bool, token: UUID, scene: UIWindowScene) {
        let key = scene.session.persistentIdentifier
        let before = mask(for: scene)
        if enabled { viewers[key, default: []].insert(token) }
        else {
            viewers[key]?.remove(token)
            if viewers[key]?.isEmpty == true { viewers.removeValue(forKey: key) }
        }
        let after = mask(for: scene)
        guard before != after else { return }
        for window in scene.windows {
            var controller = window.rootViewController
            while let current = controller {
                current.setNeedsUpdateOfSupportedInterfaceOrientations()
                controller = current.presentedViewController
            }
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: after)) { _ in
            // An inactive scene may decline the request; its mask remains correct on return.
        }
    }
}

struct UnyriMediaRotation: UIViewRepresentable {
    let active: Bool
    func makeUIView(context: Context) -> RotationView { RotationView() }
    func updateUIView(_ view: RotationView, context: Context) { view.active = active; view.synchronize() }
    static func dismantleUIView(_ view: RotationView, coordinator: ()) { view.active = false; view.synchronize() }
    final class RotationView: UIView {
        var active = false
        private let token = UUID()
        private weak var leasedScene: UIWindowScene?
        override func didMoveToWindow() { super.didMoveToWindow(); synchronize() }
        func synchronize() {
            let target = active ? window?.windowScene : nil
            guard target !== leasedScene else { return }
            if let old = leasedScene { UnyriMediaOrientation.set(false, token: token, scene: old) }
            leasedScene = target
            if let target { UnyriMediaOrientation.set(true, token: token, scene: target) }
        }
    }
}
