import Photos
import VisionKit
import UniformTypeIdentifiers
import Network
import SwiftUI
import AVKit
import Combine
import ImageIO
import CoreImage
import CoreVideo
import os

struct UnyriMediaItem: Identifiable, Equatable {
    let id: String
    let url: URL?
    let mime: String
    let name: String?
    let thumbnailBase64: String?
    /// Viewer header: who sent it and when ("Ruslan", today at 20:02).
    var sender: String? = nil
    var date: Date? = nil
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
private struct UnyriSendEditedPhotoKey: EnvironmentKey { static let defaultValue: ((UIImage) -> Void)? = nil }
extension EnvironmentValues {
    var unyriSendEditedPhoto: ((UIImage) -> Void)? { get { self[UnyriSendEditedPhotoKey.self] } set { self[UnyriSendEditedPhotoKey.self] = newValue } }
}
extension EnvironmentValues {
    var unyriMediaItems: [UnyriMediaItem] { get { self[UnyriMediaItemsKey.self] } set { self[UnyriMediaItemsKey.self] = newValue } }
}

/// Every poster observes the same attachment upgrades as its timeline/grid source.
@MainActor
private struct UnyriMediaPoster<Content: View>: View {
    let id: String
    let url: URL?
    let mime: String
    let thumbnail: String?
    let nearby: Bool
    let active: Bool
    let selected: Bool
    let initialPreview: UIImage?
    let content: (UIImage?) -> Content
    init(id: String, url: URL?, mime: String, thumbnail: String?, nearby: Bool, active: Bool, selected: Bool,
         initialPreview: UIImage?, @ViewBuilder content: @escaping (UIImage?) -> Content) {
        self.id = id; self.url = url; self.mime = mime; self.thumbnail = thumbnail
        self.nearby = nearby; self.active = active; self.selected = selected
        self.initialPreview = initialPreview; self.content = content
    }
    var body: some View {
        UnyriAttachmentImage(request: AttachmentImageRequest(id: id, url: url, mime: mime,
            thumbnail: UnyriMediaPosterPolicy.thumbnail(thumbnail), videoGeneration: !selected, photoGeneration: false),
            workAllowed: active && nearby, viewerSurface: true, rendersImage: nearby, initial: initialPreview) { image in
                content(nearby ? image : nil)
            }
    }
}

/// iOS 18 reports actual scroll clipping. Earlier systems use lazy-row lifetime.
struct UnyriMediaVisibility: ViewModifier {
    let changed: (Bool) -> Void
    /// Set by the UIKit chat timeline: its cells know when they are on screen, while
    /// SwiftUI's scroll-visibility callbacks need a SwiftUI scroll view around them.
    @Environment(\.chatHostedRowVisibility) private var hosted
    @ViewBuilder func body(content: Content) -> some View {
        if let hosted {
            content.onReceive(hosted.$displayed.removeDuplicates()) { changed($0) }
                // A full-screen cover (the call, the camera, the pinned list) takes the
                // timeline out of the window without UIKit ending the cell's display, so
                // `displayed` never changes again: on return, resume from the cell's truth.
                .onAppear { changed(hosted.displayed) }
                .onDisappear { changed(false) }
        } else if #available(iOS 18.0, *) {
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
    /// Sends an edited photo into the chat the viewer was opened from (composer tray).
    var onSendEdited: ((UIImage) -> Void)? = nil
    @State private var actions = false
    @State private var resolved: [String: URL] = [:]
    @State private var failed = Set<String>()
    @State private var retry = 0
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var viewerActive = UIApplication.shared.applicationState == .active
    // The standalone UIKit host has no live SwiftUI scene. Give its attachment
    // bindings the same activity state already used by pages and players.
    private var viewerScenePhase: ScenePhase { viewerActive ? .active : .inactive }
    @State private var selection: String
    @State private var previousPageIndex = -1
    @State private var controls = true
    @State private var headerHeight: CGFloat = 0
    @State private var exportFile = false
    @State private var actionError: String?
    /// A reference in `@State`: the root keeps one player for the whole viewer without
    /// observing it, so playback changes never re-evaluate the pages.
    @State private var video = UnyriVideoSession()
    @State private var workToken = UUID()
    @State private var dismissFraction: CGFloat = 0
    /// Average colour of the current photo: a soft glow behind it (UNYRI's touch).
    @State private var ambient: Color?
    /// Live Text on the current photo: select and copy text, numbers, links; look up objects.
    @State private var liveText = false
    @State private var editorImage: UnyriEditorImage?
    @State private var preparingEditor = false
    init(items: [UnyriMediaItem], initial: URL, download: @escaping (String) async -> URL? = { _ in nil },
         onForward: ((String) -> Void)? = nil, onClose: (() -> Void)? = nil,
         onSelection: ((String) -> Void)? = nil, initialPreview: UIImage? = nil, initialID: String? = nil,
         onSendEdited: ((UIImage) -> Void)? = nil) {
        self.onSendEdited = onSendEdited
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
            Color.black.opacity(max(0.12, 1.0 - Double(dismissFraction) * 0.88)).ignoresSafeArea()
            if let ambient {
                RadialGradient(colors: [ambient.opacity(0.38), ambient.opacity(0.10), .clear],
                               center: .center, startRadius: 40, endRadius: 520)
                    .opacity(1.0 - Double(dismissFraction))
                    .ignoresSafeArea().allowsHitTesting(false)
                    .transition(.opacity)
            }
            TabView(selection: $selection) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    // Keep a fixed, identified page root when its inner media
                    // changes between a poster and an active player.
                    let url = resolved[item.id] ?? item.url
                    UnyriMediaPoster(id: item.id, url: url, mime: item.mime, thumbnail: item.thumbnailBase64,
                        nearby: UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                        active: viewerActive, selected: selection == item.id,
                        initialPreview: url == initial ? initialPreview : nil) { poster in
                      ZStack {
                        if let url {
                            if item.mime.hasPrefix("video/"), selection == item.id {
                                UnyriVideoPage(id: item.id, url: url, video: video, active: viewerActive, onControls: { value in
                                    if selection == item.id { controls = value }
                                }, controlsPinned: actions, actionsButton: AnyView(mediaActions), onDismiss: closeViewer, onDismissDrag: { dismissFraction = $0 },
                                    initialPreview: poster)
                            }
                            else if item.mime.hasPrefix("video/") {
                                UnyriVideoThumbnail(url: url, compact: false, showsDuration: false,
                                    workAllowed: viewerActive && UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                                    showsPlayIcon: false, aspectFit: true, initialPreview: poster)
                            }
                            else { UnyriZoomImage(url: url, attachmentID: item.id,
                                active: viewerActive && UnyriMediaPagingPolicy.loadsPhoto(index: index, selected: selectedIndex),
                                selected: selection == item.id,
                                initialPreview: poster,
                                liveText: liveText && selection == item.id,
                                onTap: { if selection == item.id { controls.toggle() } }, onDismiss: closeViewer, onDismissDrag: { dismissFraction = $0 }) }
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
                        Spacer(minLength: 0)
                        if let current = items.first(where: { $0.id == selection }), current.sender != nil || current.date != nil {
                            VStack(spacing: 1) {
                                if let sender = current.sender { Text(sender).font(.system(size: 15, weight: .semibold)).lineLimit(1) }
                                if let date = current.date {
                                    Text(UnyriMediaViewer.dateLine(date)).font(.system(size: 12)).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                                }
                            }
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            .modifier(UnyriAttachGlass(shape: Capsule()))
                            .accessibilityElement(children: .combine)
                        }
                        Spacer(minLength: 0)
                        if items.count > 1 {
                            Text("\(max(0, selectedIndex) + 1) / \(items.count)").font(.system(size: 13, weight: .semibold).monospacedDigit())
                                .padding(.horizontal, 12).frame(height: 32)
                                .modifier(UnyriAttachGlass(shape: Capsule()))
                                .accessibilityIdentifier("media-page-counter").accessibilityValue(selection)
                        } else {
                            Color.clear.frame(width: 44, height: 44)
                        }
                    }
                    Spacer()
                    if items.first(where: { $0.id == selection })?.mime.hasPrefix("video/") != true {
                        photoToolbar
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
                            .modifier(UnyriAttachGlass(shape: RoundedRectangle(cornerRadius: 20, style: .continuous)))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.bottom, items.first(where: { $0.id == selection })?.mime.hasPrefix("video/") == true ? 124 : 76)
                    .buttonStyle(.plain).foregroundStyle(.white)
            }
        }.environment(\.scenePhase, viewerScenePhase)
            .background(UnyriMediaRotation(active: true)).onPreferenceChange(MediaHeaderHeightKey.self) { headerHeight = $0 }.statusBarHidden(true)
            .onAppear {
                viewerActive = UIApplication.shared.applicationState == .active
                previousPageIndex = items.firstIndex(where: { $0.id == selection }) ?? -1
                onSelection?(selection)
                UnyriMediaWorkState.shared.enter(workToken)
            }
            .onDisappear {
                UnyriMediaWorkState.shared.leave(workToken)
                video.close()
            }
            // A UIKit-presented hosting controller is outside the SwiftUI scene
            // graph. Its default scenePhase must not disable a foreground player.
            // Inactive (Control Center, alerts, Face ID) gates only decode and UI work;
            // the background gates playback.
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in viewerActive = true; video.setActive(true) }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in viewerActive = false; video.setActive(false) }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in video.setForeground(false) }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in video.setForeground(true) }
            .onChange(of: selection) { id in
                let next = items.firstIndex(where: { $0.id == id }) ?? -1
                PerformanceRecorder.shared.record(.mediaPage, ["direction": UnyriMediaPagingPolicy.direction(previous: previousPageIndex, next: next), "items": Double(items.count)])
                previousPageIndex = next
                PerformanceMonitor.shared.activity()
                controls = true; actions = false; onSelection?(id)
            }
            .performanceScreen(.media)
            .task(id: selection) {
                guard let item = items.first(where: { $0.id == selection }) else { return }
                let url = resolved[item.id] ?? item.url
                let thumbnail = item.thumbnailBase64
                let mime = item.mime
                let color = await Task.detached(priority: .utility) { () -> UIColor? in
                    UnyriAmbientColor.average(url: url, mime: mime, thumbnailBase64: thumbnail)
                }.value
                guard !Task.isCancelled else { return }
                withAnimation(UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.45)) {
                    ambient = color.map(Color.init(uiColor:))
                }
            }
            .task(id: selection + ":" + String(retry) + ":" + String(viewerActive)) {
                guard viewerActive, let item = items.first(where: { $0.id == selection }), item.url == nil, resolved[item.id] == nil else { return }
                let downloaded = await download(item.id)
                guard !Task.isCancelled else { return }
                if let downloaded { resolved[item.id] = downloaded }
                else { failed.insert(item.id) }
            }
            .fullScreenCover(item: $editorImage) { source in
                UnyriPhotoEditor(image: source.image, send: { edited in
                    editorImage = nil
                    if let onSendEdited {
                        closeViewer()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { onSendEdited(edited) }
                    } else if let url = UnyriEditorImage.write(edited) {
                        closeViewer()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { UnyriIncomingFile.shared.receive(url) }
                    }
                }, save: { edited in
                    if let url = UnyriEditorImage.write(edited) { Task { await saveToPhotos(url, mime: "image/jpeg") } }
                }, share: { edited in
                    if let url = UnyriEditorImage.write(edited) { UnyriShareSheet.present(url) }
                }, cancel: { editorImage = nil })
            }
            .onChange(of: selection) { _ in liveText = false }
            .sheet(isPresented: $exportFile) { if let item = items.first(where: { $0.id == selection }), let url = resolved[item.id] ?? item.url { UnyriFileExporter(url: url) } }
            .alert("Could not save", isPresented: Binding(get: { actionError != nil }, set: { if !$0 { actionError = nil } })) { Button("OK") { actionError = nil } } message: { Text(verbatim: actionError ?? "") }
    }
    private func closeViewer() { if let onClose { onClose() } else { dismiss() } }

    /// Bottom bar for photos: forward on the left, Markup and Live Text in the middle,
    /// More (share, save) on the right — Telegram's essentials plus iOS's tools.
    private var photoToolbar: some View {
        let current = items.first(where: { $0.id == selection })
        let url = current.flatMap { resolved[$0.id] ?? $0.url }
        return HStack(spacing: 12) {
            Button {
                UnyriHaptics.press()
                if let onForward, let current { onForward(current.id) } else if let url { UnyriShareSheet.present(url) }
            } label: {
                Image(systemName: "arrowshape.turn.up.right").font(.system(size: 19, weight: .medium)).frame(width: 48, height: 48)
            }
            .modifier(UnyriAttachGlass(shape: Circle()))
            .accessibilityLabel(onForward == nil ? "Share" : "Forward to chat")
            Spacer(minLength: 0)
            HStack(spacing: 4) {
                Button {
                    UnyriHaptics.press()
                    guard let url, !preparingEditor else { return }
                    preparingEditor = true
                    Task {
                        let image = await UnyriEditorImage.load(url)
                        preparingEditor = false
                        if let image { editorImage = UnyriEditorImage(image: image) }
                    }
                } label: {
                    Image(systemName: "pencil.tip.crop.circle").font(.system(size: 20, weight: .medium)).frame(width: 50, height: 48)
                }.accessibilityLabel("Markup")
                if UnyriLiveText.supported {
                    Button {
                        UnyriHaptics.selection()
                        liveText.toggle()
                        if liveText { controls = true }
                    } label: {
                        Image(systemName: "text.viewfinder").font(.system(size: 20, weight: .medium)).frame(width: 50, height: 48)
                            .foregroundStyle(liveText ? Color.black : Color.white)
                            .background(Circle().fill(liveText ? Color.white : Color.clear).frame(width: 40, height: 40))
                    }
                    .accessibilityLabel("Text in photo").accessibilityAddTraits(liveText ? .isSelected : [])
                }
            }
            .padding(.horizontal, 6)
            .modifier(UnyriAttachGlass(shape: Capsule()))
            Spacer(minLength: 0)
            Menu {
                if let url {
                    Button { UnyriShareSheet.present(url) } label: { Label("Share…", systemImage: "square.and.arrow.up") }
                    Button { Task { await saveToPhotos(url, mime: current?.mime ?? "image/jpeg") } } label: { Label("Save to Photos", systemImage: "photo.on.rectangle") }
                    Button { exportFile = true } label: { Label("Save to Files", systemImage: "folder") }
                    Button { UIPasteboard.general.image = PeerImagePolicy.image(path: url.path) } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 19, weight: .semibold)).frame(width: 48, height: 48)
            }
            .modifier(UnyriAttachGlass(shape: Circle()))
            .accessibilityLabel("More")
        }
        .disabled(url == nil)
        .opacity(url == nil ? 0.5 : 1)
    }

    static func dateLine(_ date: Date) -> String {
        let time = date.formatted(date: .omitted, time: .shortened)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return String(format: UnyriL10n.string("today at %@"), time) }
        if calendar.isDateInYesterday(date) { return String(format: UnyriL10n.string("yesterday at %@"), time) }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
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
        } catch { actionError = UnyriErrorText.message(error) ?? actionError }

    }
}

/// Weak, account-independent UI references only: no media bytes or persisted history.
@MainActor
private enum UnyriMediaSources {
    static let views = NSMapTable<NSString, UnyriMediaSourceView>(keyOptions: .strongMemory, valueOptions: .weakMemory)
    static weak var activeViewer: UIViewController?
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
        private let workToken = UUID()
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
            host.view.backgroundColor = .clear
            host.modalPresentationStyle = .custom
            host.modalPresentationCapturesStatusBarAppearance = true
            host.transitioningDelegate = self
            self.host = host
            UnyriMediaSources.activeViewer = host
            UnyriMediaWorkState.shared.enter(workToken)
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
                UnyriMediaWorkState.shared.leave(workToken)
                self.host = nil; closing = false; requested = false
                finish?()
            }
        }
        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            if UnyriMediaSources.activeViewer === host { UnyriMediaSources.activeViewer = nil }
            UnyriMediaWorkState.shared.leave(workToken)
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
        let image = sourceRect != nil && !UIAccessibility.isReduceMotionEnabled ? displayedPhoto?.image ?? UnyriAttachmentImages.best(id: id)?.image ?? source?.previewImage?() : nil
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

/// Simulator checks read these lines with `log stream --predicate 'subsystem == "com.unyri.media"'`.
/// Never log ids, names, captions or URLs here.
private let mediaLog = Logger(subsystem: "com.unyri.media", category: "player")

/// One AVPlayer for one file. The cache lease keeps the file on disk while it plays.
@MainActor
private final class MediaVideoPlayback {
    let url: URL
    let player: AVPlayer
    let item: AVPlayerItem
    private let cacheLease: MediaCacheLease
    init(url: URL) {
        self.url = url
        cacheLease = MediaCacheLease(path: url.path)
        item = AVPlayerItem(url: url)
        // Tuned for speech at other speeds, and cheaper than the spectral default.
        item.audioTimePitchAlgorithm = .timeDomain
        player = AVPlayer(playerItem: item)
        PerformanceRecorder.shared.record(.videoReady, ["phase": 2], screen: .media)
    }
    deinit { player.pause(); PerformanceRecorder.shared.record(.videoReady, ["phase": 3], screen: .media) }
}

/// Viewer-wide playback choices. Mute lasts for the app session and covers every page and
/// every viewer; the speed is remembered across launches.
enum UnyriVideoPreferences {
    static var muted = false
    private static let speedKey = "media.video.speed"
    static var speed: Float {
        get { UnyriVideoPolicy.storedSpeed(UserDefaults.standard.double(forKey: speedKey)) }
        set { UserDefaults.standard.set(Double(newValue), forKey: speedKey) }
    }
}

/// One prepared selection generator for the player. The scrubber and the hold prepare it
/// on touch-down, so the first tick does not wait for the Taptic Engine to wake up.
@MainActor
private enum UnyriPlayerHaptics {
    private static let generator = UISelectionFeedbackGenerator()
    static func prepare() { generator.prepare() }
    static func selection() { generator.selectionChanged() }
}

/// Times for the progress row, kept apart from the session so only that row redraws.
/// While playing it changes at most once per second; seeks and pauses write exact values.
@MainActor
final class UnyriVideoClock: ObservableObject {
    @Published fileprivate(set) var elapsed = 0.0
    @Published fileprivate(set) var duration = 0.0
    /// The whole second under the finger while the slider is dragged.
    @Published fileprivate(set) var scrubSecond: Int?
}

/// The viewer's one video player, owned by the root viewer for its whole lifetime. Pages
/// select and deselect it, so paging never leaves a second player or a claimed audio
/// session behind. `init` has no side effects: SwiftUI evaluates a `@State` initial value
/// on every parent update and drops the extra instances, so all work starts in `select`.
@MainActor
final class UnyriVideoSession: ObservableObject {
    enum Phase: Equatable { case loading, ready, ended, failed }
    enum AudioNotice: Equatable {
        case call, recording, unavailable
        var key: String {
            switch self {
            case .call: return "No sound during a call"
            case .recording: return "No sound while recording"
            case .unavailable: return "Sound is unavailable right now"
            }
        }
        var text: LocalizedStringKey { LocalizedStringKey(key) }
    }

    let clock = UnyriVideoClock()
    @Published private(set) var playbackID: String?
    @Published private(set) var phase = Phase.loading
    @Published private(set) var playing = false
    /// Loading or stalled, from KVO only.
    @Published private(set) var waiting = false
    /// Unknown until the tracks load (well under a second); playback stays muted until then.
    @Published private(set) var hasAudio: Bool?
    /// Display width / height of the video track.
    @Published private(set) var videoAspect: CGFloat?
    /// Shared by every page and viewer for the app session.
    @Published private(set) var userMuted = UnyriVideoPreferences.muted
    /// A call (UNYRI's or another app's) or a recording owns audio: the video stays muted.
    @Published private(set) var audioBlocked = false
    @Published private(set) var audioNotice: AudioNotice? {
        didSet {
            guard let audioNotice, audioNotice != oldValue else { return }
            UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(audioNotice.key))
        }
    }
    /// The chosen speed: the remembered one for videos of 30 s or more, else 1×.
    @Published private(set) var playRate: Float = 1
    /// A temporary rate while the surface is held; `playRate` comes back on release.
    @Published private(set) var heldRate: Float?

    var player: AVPlayer? { playback?.player }
    var seekable: Bool { phase == .ready || phase == .ended }
    /// Where the player is now; the clock may lag by up to a second.
    var currentTime: Double {
        let seconds = playback?.player.currentTime().seconds ?? .nan
        return seconds.isFinite ? seconds : clock.elapsed
    }
    var canPlayFastForward: Bool { playback?.item.canPlayFastForward == true }

    /// The surface's VoiceOver value: "0:12 of 4:10, paused, no sound".
    var accessibilityStatus: String {
        var text = String(format: UnyriL10n.string("%@ of %@"), UnyriVideoPolicy.time(currentTime), UnyriVideoPolicy.total(clock.duration))
        if !playing { text = String(format: UnyriL10n.string("%@, paused"), text) }
        if hasAudio == false { text = String(format: UnyriL10n.string("%@, no sound"), text) }
        return text
    }

    private var playback: MediaVideoPlayback?
    /// Bumped with every player, so late callbacks from an old one are dropped.
    private var generation = 0
    private var positions: [String: (position: Double, duration: Double)] = [:]
    private var autoplayConsumed = false
    /// The speed menu was used on this video: its length no longer decides the speed.
    private var rateChosen = false
    private var pauseReason: UnyriVideoPauseReason? {
        // A play or any other reason cancels a resume that waits for the app to be active.
        didSet { if pauseReason != .interruption { resumePending = false } }
    }
    /// An interruption ended with "should resume" while the app was inactive.
    private var resumePending = false
    /// When the player stopped with no reason recorded: AVPlayer pauses itself for an
    /// interruption or unplugged headphones, and that event may arrive just after.
    private var unexplainedStopAt: CFTimeInterval?
    private var foreground = true
    private var active = true
    private var chromeVisible = true
    private var releasePending = false
    private var seeks = 0
    /// Bumped by every seek; a completion from an older seek is ignored.
    private var seekToken = 0
    /// Live seeking: one seek in flight, the newest target waits for it.
    private var chaseInFlight = false
    private var chasePending: (seconds: Double, tolerance: CMTime)?
    private var scrubbing = false
    private var scrubSeeks = 0
    /// The scrub paused a playing video: play again after the release seek.
    private var scrubResumes = false
    /// The release seek is still running and playback continues after it.
    private var resumeAfterSeek = false
    private var recorderCall = false
    private var recording = false
    private var systemCall = false
    private weak var voicePlayer: VoicePlayerController?
    private weak var voiceRecorder: VoiceRecorder?
    private var subscriptions: [AnyCancellable] = []
    private var sessionObservers: [NSObjectProtocol] = []
    private var itemObservers: [NSObjectProtocol] = []
    private var keyValueObservers: [NSKeyValueObservation] = []
    private var timeObserver: Any?
    private var tracksTask: Task<Void, Never>?
    private var ownsAudio: Bool { UnyriPlaybackAudioSession.owner == .viewer }

    // MARK: Pages and lifecycle

    /// The page for `id` is on screen with a playable file.
    func select(id: String, url: URL, voicePlayer: VoicePlayerController, voiceRecorder: VoiceRecorder) {
        guard playbackID != id || playback == nil else { return }
        releasePending = false
        if playback != nil { savePosition(); teardownPlayback() }
        self.voicePlayer = voicePlayer
        observeCapture(voiceRecorder)
        observeAudioSession()
        let state = UIApplication.shared.applicationState
        foreground = state != .background
        active = state == .active
        let saved = positions[id]
        autoplayConsumed = false
        pauseReason = nil
        // 1× until the length is known; a speed picked meanwhile is kept.
        rateChosen = false
        if playRate != 1 { playRate = 1 }
        start(url: url, id: id, at: UnyriVideoPolicy.restorePosition(saved: saved?.position ?? 0, duration: saved?.duration ?? 0))
        // Video to video keeps the session (no churn) unless the new one plays silently.
        if userMuted || audioBlocked { releaseAudio("switch") }
    }

    /// The page for `id` left the screen. The session is released after this update
    /// unless another video page selects the player in the same update.
    func deselect(id: String) {
        guard playbackID == id else { return }
        savePosition()
        teardownPlayback()
        playbackID = nil
        releasePending = true
        DispatchQueue.main.async { [weak self] in
            guard let self, self.releasePending else { return }
            self.releasePending = false
            self.releaseAudio("page")
        }
    }

    /// The viewer closed: stop and give the audio session back now.
    func close() {
        releasePending = false
        teardownPlayback()
        playbackID = nil
        releaseAudio("close")
        for observer in sessionObservers { NotificationCenter.default.removeObserver(observer) }
        sessionObservers = []
        subscriptions = []
        voiceRecorder = nil
    }

    /// Leaving the foreground pauses and frees the session unless the video plays on an
    /// external screen. Coming back never resumes on its own, also after an interruption.
    func setForeground(_ value: Bool) {
        guard foreground != value else { return }
        foreground = value
        if !value && player?.isExternalPlaybackActive != true {
            systemPause(.background)
            releaseAudio("background")
        }
        updateClockObserver()
    }

    /// Inactive (Control Center, alerts, Face ID) keeps playing; only the clock stops.
    /// Becoming active runs a resume that an interruption end left waiting.
    func setActive(_ value: Bool) {
        guard active != value else { return }
        active = value
        if value {
            refreshClock()
            if resumePending { endInterruption(shouldResume: true) }
        }
        updateClockObserver()
    }

    func setChromeVisible(_ value: Bool) {
        guard chromeVisible != value else { return }
        chromeVisible = value
        if value { refreshClock() }
        updateClockObserver()
    }

    // MARK: Transport

    /// A user play at the chosen speed: clears the pause reason; an ended video starts over.
    func play() {
        guard let playback, phase != .failed else { return }
        pauseReason = nil
        mediaLog.debug("play reason=user")
        guard phase == .ended else { startPlaying(explicit: true); return }
        phase = .ready
        let generation = generation
        playback.player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            guard finished, let self else { return }
            DispatchQueue.main.async { if self.generation == generation { self.startPlaying(explicit: true) } }
        }
    }

    func pause() { pause(.user) }

    func togglePlay() { if playing { pause() } else { play() } }

    /// A choice from the speed menu: remembered for the next long videos and applied now,
    /// whatever this clip's length. `defaultRate` makes a plain or remote play keep it.
    /// Reads the player, not `playing`, which trails the rate by one main-queue hop.
    func setRate(_ rate: Float) {
        UnyriVideoPreferences.speed = rate
        rateChosen = true
        heldRate = nil
        playRate = rate
        playback?.player.defaultRate = rate
        if let player, player.rate != 0 { player.rate = rate }
        UIAccessibility.post(notification: .announcement,
            argument: String(format: UnyriL10n.string("Speed %@"), UnyriVideoPolicy.speedLabel(rate, locale: .current)))
    }

    /// An exact seek: the file is local, so it is cheap and never snaps to a keyframe.
    func seek(to seconds: Double) {
        let duration = clock.duration
        guard let playback, playback.item.status == .readyToPlay, duration > 0, seconds.isFinite else { seekLanded(); return }
        let target = min(duration, max(0, seconds))
        if clock.scrubSecond != nil { clock.scrubSecond = nil }
        // Leaving the end clears it, also when the real end comes before `item.duration`.
        if phase == .ended && target < duration { phase = .ready }
        setClock(elapsed: target)
        seeks += 1
        // AVPlayer cancels a chase seek still in flight; its completion must not go on.
        seekToken += 1
        chaseInFlight = false
        chasePending = nil
        let generation = generation, token = seekToken
        playback.player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            guard finished, let self else { return }
            DispatchQueue.main.async {
                guard self.generation == generation else { return }
                self.refreshClock()
                if self.seekToken == token { self.seekLanded() }
            }
        }
    }

    /// The newest seek landed: playback that a scrub paused goes on.
    private func seekLanded() {
        guard resumeAfterSeek, !scrubbing else { return }
        resumeAfterSeek = false
        play()
    }

    /// One seek in flight and the newest target waiting, so fast drags and taps never
    /// queue up stale seeks. The clock follows once the finger has let go.
    private func chase(to seconds: Double, tolerance: CMTime) {
        guard let playback, playback.item.status == .readyToPlay else { return }
        if chaseInFlight { chasePending = (seconds, tolerance); return }
        chaseInFlight = true
        seeks += 1
        if scrubbing { scrubSeeks += 1 }
        seekToken += 1
        let generation = generation, token = seekToken
        playback.player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async {
                guard self.generation == generation, self.seekToken == token else { return }
                self.chaseInFlight = false
                if let next = self.chasePending {
                    self.chasePending = nil
                    self.chase(to: next.seconds, tolerance: next.tolerance)
                } else if !self.scrubbing {
                    self.refreshClock()
                    self.seekLanded()
                }
            }
        }
    }

    func skip(_ offset: Double) {
        seek(to: currentTime + offset)
    }

    // MARK: Scrubbing, seek streaks and hold

    /// A drag on the scrubber started: playback pauses so the frame can follow the finger.
    /// A release seek still running counts as playing, so a quick second drag keeps playback.
    func beginScrub() {
        guard seekable, let player else { return }
        scrubbing = true
        scrubSeeks = 0
        scrubResumes = player.rate != 0 || resumeAfterSeek
        resumeAfterSeek = false
        if player.rate != 0 { pause(.user) }
    }

    /// Live frames: the second under the finger for the time label, and a seek chasing it.
    func scrub(to seconds: Double) {
        guard scrubbing, seconds.isFinite else { return }
        let duration = clock.duration
        let target = min(duration, max(0, seconds))
        let second = Int(min(target, 359_999))
        if clock.scrubSecond != second { clock.scrubSecond = second }
        // Keyframes only on long videos: an exact frame there can take long to decode.
        let tolerance = duration > 600 ? CMTime.positiveInfinity : CMTime(seconds: 0.1, preferredTimescale: 600)
        chase(to: target, tolerance: tolerance)
    }

    /// Release: one exact seek, then playback continues if it ran before and no system
    /// event stopped it meanwhile.
    func endScrub(at seconds: Double) {
        guard scrubbing else { return }
        scrubbing = false
        resumeAfterSeek = scrubResumes
        scrubResumes = false
        mediaLog.debug("scrub seeks=\(self.scrubSeeks, privacy: .public)")
        seek(to: seconds)
    }

    /// One step of a double-tap streak. Chased like a scrub, so quick taps never queue up.
    func seekStep(to seconds: Double) {
        let duration = clock.duration
        guard seekable, duration > 0, seconds.isFinite else { return }
        let target = min(duration, max(0, seconds))
        if phase == .ended && target < duration { phase = .ready }
        setClock(elapsed: target)
        chase(to: target, tolerance: .zero)
    }

    /// Hold to play faster: only while playing, and never the chosen speed itself.
    @discardableResult
    func hold(_ rate: Float) -> Bool {
        guard let player, player.rate != 0, rate > 0, rate != playRate, rate <= 2 || canPlayFastForward else { return false }
        heldRate = rate
        player.rate = rate
        mediaLog.debug("hold rate=\(Double(rate), privacy: .public)")
        return true
    }

    /// Release, page change, dismiss or leaving the foreground: back to the chosen speed.
    func endHold() {
        guard heldRate != nil else { return }
        heldRate = nil
        if let player, player.rate != 0 { player.rate = playRate }
        mediaLog.debug("hold end")
    }

    /// Muting gives the session back. After a refused session the same control retries.
    func toggleMute() {
        if !userMuted && audioNotice == .unavailable {
            audioNotice = nil
            if playing { acquireAudio(explicit: true) }
            return
        }
        userMuted.toggle()
        UnyriVideoPreferences.muted = userMuted
        if userMuted { releaseAudio("mute") } else if playing { acquireAudio(explicit: true) }
        applyMute()
    }

    // MARK: Player

    private func start(url: URL, id: String, at position: Double) {
        generation += 1
        seeks = 0
        unexplainedStopAt = nil
        let playback = MediaVideoPlayback(url: url)
        playback.player.isMuted = true
        if position > 0 {
            // Exact (a local file is cheap) and queued before readiness, so it lands before
            // autoplay and the first frame never flashes. A completion handler is not
            // allowed until the item is ready.
            playback.player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            seeks += 1
        }
        self.playback = playback
        playbackID = id
        phase = .loading
        hasAudio = nil
        videoAspect = nil
        clock.duration = 0
        clock.elapsed = position
        clock.scrubSecond = nil
        observe(playback)
        loadTracks(playback)
        updateWaiting()
    }

    private func teardownPlayback() {
        guard let playback else { return }
        removeClockObserver()
        keyValueObservers.forEach { $0.invalidate() }
        keyValueObservers = []
        for observer in itemObservers { NotificationCenter.default.removeObserver(observer) }
        itemObservers = []
        tracksTask?.cancel()
        tracksTask = nil
        playback.player.pause()
        playback.player.replaceCurrentItem(with: nil)
        self.playback = nil
        generation += 1
        chaseInFlight = false
        chasePending = nil
        scrubbing = false
        scrubResumes = false
        resumeAfterSeek = false
        if heldRate != nil { heldRate = nil }
        if playing { playing = false }
        if waiting { waiting = false }
        mediaLog.debug("close seeks=\(self.seeks, privacy: .public)")
    }

    /// Left while still loading, the earlier saved spot stays: the restore seek may not have landed.
    private func savePosition() {
        guard let playback, let id = playbackID, phase != .loading else { return }
        let duration = clock.duration
        let position = phase == .ended ? duration : playback.player.currentTime().seconds
        if position.isFinite { positions[id] = (position, duration) }
    }

    private func observe(_ playback: MediaVideoPlayback) {
        let generation = generation
        // KVO arrives on any thread: hop to main and drop callbacks from an older player.
        keyValueObservers = [
            playback.item.observe(\.status, options: [.initial, .new]) { @Sendable [weak self] _, _ in
                guard let self else { return }
                DispatchQueue.main.async { if self.generation == generation { self.statusChanged() } }
            },
            playback.player.observe(\.rate, options: [.new]) { @Sendable [weak self] _, _ in
                guard let self else { return }
                DispatchQueue.main.async { if self.generation == generation { self.rateChanged() } }
            },
            playback.player.observe(\.timeControlStatus, options: [.new]) { @Sendable [weak self] _, _ in
                guard let self else { return }
                DispatchQueue.main.async { if self.generation == generation { self.updateWaiting() } }
            },
            // New tracks start enabled: switch their audio off unless the player may be heard.
            playback.item.observe(\.tracks, options: [.new]) { @Sendable [weak self] _, _ in
                guard let self else { return }
                DispatchQueue.main.async { if self.generation == generation { self.applyMute() } }
            },
        ]
        let center = NotificationCenter.default
        // Raw names: the Swift spellings of these constants changed between SDKs.
        itemObservers = [
            center.addObserver(forName: Notification.Name("AVPlayerItemDidPlayToEndTimeNotification"), object: playback.item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.reachedEnd() }
            },
            center.addObserver(forName: Notification.Name("AVPlayerItemFailedToPlayToEndTimeNotification"), object: playback.item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.fail() }
            },
        ]
    }

    /// Whether the clip has sound decides the session and the loop; the aspect is for layout.
    private func loadTracks(_ playback: MediaVideoPlayback) {
        let asset = playback.item.asset
        let generation = generation
        tracksTask = Task { [weak self] in
            let audio = (try? await asset.loadTracks(withMediaType: .audio))?.isEmpty == false
            var aspect: CGFloat?
            if let track = try? await asset.loadTracks(withMediaType: .video).first,
               let size = try? await track.load(.naturalSize),
               let transform = try? await track.load(.preferredTransform) {
                let shown = CGRect(origin: .zero, size: size).applying(transform).size
                if shown.width != 0, shown.height != 0 { aspect = abs(shown.width / shown.height) }
            }
            guard let self, !Task.isCancelled, self.generation == generation else { return }
            self.tracksLoaded(hasAudio: audio, aspect: aspect)
        }
    }

    private func tracksLoaded(hasAudio: Bool, aspect: CGFloat?) {
        self.hasAudio = hasAudio
        videoAspect = aspect
        applyLoop()
        if !hasAudio { releaseAudio("silent") } else if playing { acquireAudio(explicit: false) }
        applyMute()
    }

    private func statusChanged() {
        guard let playback else { return }
        switch playback.item.status {
        case .readyToPlay where phase == .loading:
            phase = .ready
            refreshClock()
            applyStoredRate()
            applyLoop()
            autoplay()
        case .failed:
            fail()
        default:
            break
        }
        updateWaiting()
    }

    /// Once per player. Not in the background, and not while a call or a recording owns
    /// audio: the poster and Play stay, leaving the decoder to the call.
    private func autoplay() {
        guard !autoplayConsumed else { return }
        autoplayConsumed = true
        guard foreground, !audioBlocked, !playing, phase == .ready else { return }
        mediaLog.debug("play reason=autoplay")
        startPlaying(explicit: false)
    }

    private func startPlaying(explicit: Bool) {
        guard let playback else { return }
        acquireAudio(explicit: explicit)
        playback.player.playImmediately(atRate: playRate)
    }

    /// Once the length is known, before autoplay: long videos take the remembered speed.
    private func applyStoredRate() {
        if !rateChosen {
            let rate = UnyriVideoPolicy.speedApplies(duration: clock.duration) ? UnyriVideoPreferences.speed : 1
            if playRate != rate { playRate = rate }
        }
        playback?.player.defaultRate = playRate
    }

    private func pause(_ reason: UnyriVideoPauseReason) {
        guard let playback else { return }
        pauseReason = reason
        playback.player.pause()
        mediaLog.debug("pause reason=\(reason.rawValue, privacy: .public)")
    }

    /// A system event stopped playback or is about to. `playing` trails the player by one
    /// main-queue hop either way, so a just-made pause keeps its reason and a stop moments
    /// ago without one still counts as playback.
    private func systemPause(_ reason: UnyriVideoPauseReason) {
        // Whatever the recorded reason, a scrub in progress no longer resumes playback.
        scrubResumes = false
        resumeAfterSeek = false
        let ago = unexplainedStopAt.map { CACurrentMediaTime() - $0 }
        guard UnyriVideoPolicy.recordsSystemPause(reason, over: pauseReason, playing: playing, unexplainedStopAgo: ago) else { return }
        unexplainedStopAt = nil
        pause(reason)
    }

    private func rateChanged() {
        guard let player else { return }
        let nowPlaying = player.rate != 0
        let started = nowPlaying && !playing
        if nowPlaying { unexplainedStopAt = nil } else if playing && pauseReason == nil { unexplainedStopAt = CACurrentMediaTime() }
        // A pause ends a hold; the next play starts at the chosen speed.
        if !nowPlaying && heldRate != nil { heldRate = nil }
        if playing != nowPlaying {
            playing = nowPlaying
            mediaLog.debug("playing=\(nowPlaying ? 1 : 0, privacy: .public)")
        }
        if nowPlaying {
            // Playback belongs to the foreground or an external screen; native or remote
            // resumes claim the session too, a change between two speeds does not.
            guard foreground || player.isExternalPlaybackActive else { pause(.background); return }
            if started { acquireAudio(explicit: false) }
        }
        updateClockObserver()
        refreshClock()
    }

    private func reachedEnd() {
        guard let playback else { return }
        if UnyriVideoPolicy.loops(duration: clock.duration, hasAudio: hasAudio ?? true) {
            // `actionAtItemEnd` is `.none` for these clips, so the rate is kept.
            playback.player.seek(to: .zero)
            return
        }
        phase = .ended
        pauseReason = .ended
        releaseAudio("ended")
        refreshClock()
        UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string("Video ended"))
        mediaLog.debug("ended")
    }

    private func applyLoop() {
        guard let player, let hasAudio, clock.duration > 0 else { return }
        player.actionAtItemEnd = UnyriVideoPolicy.loops(duration: clock.duration, hasAudio: hasAudio) ? .none : .pause
    }

    /// A broken file or a failure mid-file: no transport and no session.
    private func fail() {
        guard phase != .failed else { return }
        phase = .failed
        pauseReason = .failed
        playback?.player.pause()
        releaseAudio("failed")
        updateWaiting()
        mediaLog.error("failed")
    }

    private func updateWaiting() {
        guard let playback else { return }
        let value = phase != .failed && (playback.item.status == .unknown || playback.player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
        if waiting != value { waiting = value }
    }

    // MARK: Clock

    /// One 2 Hz observer, present only while someone can see the time move.
    private func updateClockObserver() {
        let wanted = playing && chromeVisible && foreground && active
        if wanted, timeObserver == nil, let player {
            timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 2), queue: .main) { [weak self] time in
                MainActor.assumeIsolated { self?.tick(time.seconds) }
            }
        } else if !wanted {
            removeClockObserver()
        }
    }

    private func removeClockObserver() {
        guard let timeObserver else { return }
        player?.removeTimeObserver(timeObserver)
        self.timeObserver = nil
    }

    /// At most one SwiftUI update per second.
    private func tick(_ seconds: Double) {
        guard seconds.isFinite, Int(min(seconds, 359_999)) != Int(clock.elapsed) else { return }
        setClock(elapsed: seconds)
    }

    /// One-shot refresh: rate changes, seeks, readiness, the end and a visible chrome.
    private func refreshClock() {
        guard let playback else { return }
        let duration = playback.item.duration.seconds
        if duration.isFinite, duration > 0, clock.duration != duration { clock.duration = duration }
        setClock(elapsed: playback.player.currentTime().seconds)
    }

    private func setClock(elapsed: Double) {
        guard elapsed.isFinite else { return }
        let value = clock.duration > 0 ? min(clock.duration, max(0, elapsed)) : max(0, elapsed)
        if clock.elapsed != value { clock.elapsed = value }
    }

    // MARK: Audio session

    /// Claims the shared session only for audible playback. A refusal (another app's call,
    /// for example) keeps the video playing muted with a notice; only an explicit play or
    /// unmute tries again.
    private func acquireAudio(explicit: Bool) {
        defer { applyMute() }
        guard playback != nil, !ownsAudio, hasAudio == true, !userMuted, !audioBlocked, phase != .failed,
              explicit || audioNotice != .unavailable else { return }
        voicePlayer?.stop()
        // A round video message playing in the chat gives way before the session is configured.
        UnyriVideoNotePlayer.shared.stop()
        // Configured off the main thread: the video plays muted until the session is active.
        UnyriPlaybackAudioSession.acquire(.viewer, mode: .moviePlayback) { [weak self] grant in
            guard let self else { return }
            switch grant {
            case .active:
                if self.audioNotice == .unavailable { self.audioNotice = nil }
                self.applyMute()
            case .refused:
                self.audioNotice = .unavailable
                mediaLog.error("acquire refused audioNotice=unavailable")
                self.applyMute()
            case .superseded:
                break
            }
        }
    }

    /// The player goes silent before the session does: deactivating under running audio
    /// output fails as busy and can stop the player.
    private func releaseAudio(_ reason: String) {
        guard ownsAudio else { return }
        applyMute(audible: false)
        UnyriPlaybackAudioSession.release(.viewer)
        mediaLog.debug("release reason=\(reason, privacy: .public)")
    }

    /// The player is audible only while it owns the session and the session is active.
    private func applyMute() {
        applyMute(audible: UnyriPlaybackAudioSession.isActive(.viewer) && hasAudio == true && !userMuted && !audioBlocked)
    }

    /// A silent player also has its audio tracks off. Muting alone keeps AVPlayer's audio
    /// output running, which would re-activate the non-mixable session on play (pausing
    /// Music with nobody left to release it) and make a deactivation fail as busy.
    private func applyMute(audible: Bool) {
        guard let playback else { return }
        if playback.player.isMuted == audible { playback.player.isMuted = !audible }
        for track in playback.item.tracks where track.assetTrack?.mediaType == .audio && track.isEnabled != audible {
            track.isEnabled = audible
        }
    }

    private func observeCapture(_ recorder: VoiceRecorder) {
        guard voiceRecorder !== recorder || subscriptions.isEmpty else { return }
        voiceRecorder = recorder
        UnyriSystemCallMonitor.shared.start()
        // Values come from the publishers: @Published emits before the property changes.
        subscriptions = [
            recorder.$callInProgress.combineLatest(recorder.$hasRecordingSession).sink { [weak self] call, recording in
                self?.recorderCall = call
                self?.recording = recording
                self?.captureChanged()
            },
            UnyriSystemCallMonitor.shared.$active.sink { [weak self] active in
                self?.systemCall = active
                self?.captureChanged()
            },
        ]
    }

    /// A call or a recording mutes the video and disables autoplay. One that starts
    /// mid-video pauses it for good; a recording uses the call reason, which never resumes.
    private func captureChanged() {
        let call = recorderCall || systemCall
        let blocked = call || recording
        let started = blocked && !audioBlocked
        if audioBlocked != blocked { audioBlocked = blocked }
        let notice: AudioNotice? = call ? .call : recording ? .recording : (audioNotice == .unavailable ? .unavailable : nil)
        if audioNotice != notice {
            audioNotice = notice
            let label = notice.map { "\($0)" } ?? "none"
            mediaLog.debug("audioNotice=\(label, privacy: .public)")
        }
        if started {
            systemPause(.call)
            // Ownership only: the call has already switched the category.
            releaseAudio("call")
        } else if !blocked && playing {
            acquireAudio(explicit: false)
        }
        applyMute()
    }

    private func observeAudioSession() {
        guard sessionObservers.isEmpty else { return }
        let center = NotificationCenter.default
        sessionObservers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                MainActor.assumeIsolated { self?.interrupted(type, shouldResume: options.contains(.shouldResume)) }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
                // Headphones unplugged: pause like every audio app, no resume.
                guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
                MainActor.assumeIsolated { self?.systemPause(.route) }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.mediaServicesReset() }
            },
        ]
    }

    /// iOS 16+ no longer sends app-suspension interruptions, so every `.began` is real.
    private func interrupted(_ type: AVAudioSession.InterruptionType, shouldResume: Bool) {
        switch type {
        case .began:
            // A new interruption has not ended yet, whatever an earlier one allowed.
            resumePending = false
            systemPause(.interruption)
            // The system already deactivated the session: a resume must claim it again.
            UnyriPlaybackAudioSession.release(.viewer, deactivate: false)
            applyMute()
        case .ended:
            endInterruption(shouldResume: shouldResume)
        @unknown default:
            break
        }
    }

    /// Resumes now, or once the app is active again: Siri's card can outlast its audio.
    private func endInterruption(shouldResume: Bool) {
        resumePending = false
        switch UnyriVideoPolicy.interruptionEnd(shouldResume: shouldResume, reason: pauseReason,
            foreground: foreground, active: active, audioBlocked: audioBlocked) {
        case .resume:
            pauseReason = nil
            mediaLog.debug("play reason=interruption")
            startPlaying(explicit: false)
        case .resumeWhenActive:
            resumePending = true
        case .stay:
            break
        }
    }

    /// Every player and the session died with the media server: rebuild the player at the
    /// same spot, paused, so the poster shows and Play works again.
    private func mediaServicesReset() {
        UnyriPlaybackAudioSession.release(.viewer, deactivate: false)
        guard let playback, let id = playbackID else { return }
        let position = playback.player.currentTime().seconds
        let url = playback.url
        teardownPlayback()
        autoplayConsumed = true
        pauseReason = .failed
        start(url: url, id: id, at: position.isFinite ? max(0, position) : 0)
        mediaLog.error("media services reset")
    }
}

/// The selected video page: the root session's surface plus overlays. It selects the
/// player when it appears and hands it back when it leaves; it never owns playback.
private struct UnyriVideoPage: View {
    let id: String
    let url: URL
    @ObservedObject private var video: UnyriVideoSession
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @EnvironmentObject private var voiceRecorder: VoiceRecorder
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let active: Bool
    let onControls: (Bool) -> Void
    let controlsPinned: Bool
    let actionsButton: AnyView
    let onDismiss: () -> Void
    let onDismissDrag: (CGFloat) -> Void
    let initialPreview: UIImage?
    @State private var dismissOffset: CGFloat = 0
    @State private var firstFrameReady = false
    @State private var posterRecovery = false
    /// Fit or fill, picked by a pinch or the VoiceOver action; every page starts fitted.
    @State private var fill = false
    /// The mode just picked, shown for a second.
    @State private var gravityNotice: Bool?
    init(id: String, url: URL, video: UnyriVideoSession, active: Bool, onControls: @escaping (Bool) -> Void, controlsPinned: Bool,
         actionsButton: AnyView, onDismiss: @escaping () -> Void, onDismissDrag: @escaping (CGFloat) -> Void, initialPreview: UIImage?) {
        self.id = id
        self.url = url
        _video = ObservedObject(wrappedValue: video)
        self.active = active
        self.onControls = onControls
        self.controlsPinned = controlsPinned
        self.actionsButton = actionsButton
        self.onDismiss = onDismiss
        self.onDismissDrag = onDismissDrag
        self.initialPreview = initialPreview
    }
    /// Only this page's player: during a page change the session may still hold the last one.
    private var player: AVPlayer? { video.playbackID == id ? video.player : nil }
    var body: some View {
        ZStack {
            if let player {
                UnyriVideoSurface(player: player, fill: fill, ready: { firstFrameReady = $0 },
                                  status: { video.accessibilityStatus }, actions: surfaceActions)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay {
                if !firstFrameReady {
                    Group {
                    UnyriAttachmentImage(request: AttachmentImageRequest(id: id, url: url, mime: "video/mp4",
                        videoGeneration: video.phase == .failed || posterRecovery),
                        workAllowed: active, viewerSurface: true, initial: initialPreview) { image in
                            if let image { Image(uiImage: image).resizable().scaledToFit() }
                        }
                    }.allowsHitTesting(false).accessibilityElement(children: .ignore)
                        .accessibilityLabel("Video preview").accessibilityIdentifier("media-video-loading-poster")
                }
            }
            .overlay {
                if let player {
                    UnyriVideoControls(player: player, video: video, active: active, fill: fill, setFill: setFill, onControls: onControls, controlsPinned: controlsPinned, actionsButton: actionsButton, onDismiss: onDismiss, onDismissDrag: { value in
                        let fraction = min(1.0, abs(value) / 450.0)
                        onDismissDrag(fraction)
                        withAnimation(value == 0 && !UIAccessibility.isReduceMotionEnabled ? .easeOut(duration: 0.18) : nil) { dismissOffset = value }
                    })
                }
            }
            .overlay(alignment: .top) {
                if player != nil, let notice = video.audioNotice {
                    Text(notice.text).font(.caption).foregroundStyle(.white)
                        .padding(8).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 8)).padding(.top, 80)
                }
            }
            .overlay {
                if player != nil && video.phase == .failed {
                    Text("Preview unavailable. Save the file or open it in another app.").foregroundStyle(.white).padding(24).background(.black.opacity(0.8))
                } else if player != nil && video.waiting {
                    // Only after 0.4 s of continuous waiting: a local file usually starts sooner,
                    // and a removal during the delay never shows the spinner at all.
                    ProgressView().tint(.white).allowsHitTesting(false)
                        .transition(.asymmetric(insertion: .opacity.animation((reduceMotion ? Animation.linear(duration: 0) : .easeOut(duration: 0.15)).delay(0.4)),
                                                removal: .identity))
                }
            }
            .overlay {
                if let gravityNotice {
                    // VoiceOver gets an announcement: the pill is gone within a second.
                    Text(gravityNotice ? "Fill screen" : "Fit to screen")
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 14).frame(height: 32)
                        .modifier(UnyriAttachGlass(shape: Capsule()))
                        .allowsHitTesting(false).accessibilityIdentifier("media-gravity-notice")
                        .transition(.opacity.animation(reduceMotion ? nil : .easeOut(duration: 0.15)))
                }
            }
            .scaleEffect(max(0.72, 1.0 - min(1.0, abs(dismissOffset) / 450.0) * 0.28))
            .offset(y: dismissOffset)
            .onAppear { video.select(id: id, url: url, voicePlayer: voicePlayer, voiceRecorder: voiceRecorder) }
            .onDisappear { video.deselect(id: id) }
            .background(UnyriPlayerPosterCapture(id: id, url: url, player: player, ready: firstFrameReady, active: active))
            .task(id: "\(id)|\(active)|\(firstFrameReady)") {
                posterRecovery = false
                guard active, !firstFrameReady else { return }
                // Give playback exclusive first-frame ownership. A stalled/failed
                // player then gets a bounded shared-poster recovery attempt.
                do { try await Task.sleep(nanoseconds: 1_200_000_000) } catch { return }
                if !Task.isCancelled { posterRecovery = true }
            }
            .task(id: gravityNotice) {
                guard gravityNotice != nil else { return }
                do { try await Task.sleep(nanoseconds: 1_000_000_000); gravityNotice = nil } catch { }
            }
    }

    private func setFill(_ value: Bool) {
        guard fill != value else { return }
        fill = value
        gravityNotice = value
        UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(value ? "Fill screen" : "Fit to screen"))
    }

    /// Built when VoiceOver asks, so the names match the moment (Play or Pause, Mute or Unmute).
    private func surfaceActions(screenAspect: CGFloat) -> [UIAccessibilityCustomAction] {
        func action(_ key: String, _ perform: @escaping () -> Void) -> UIAccessibilityCustomAction {
            UIAccessibilityCustomAction(name: UnyriL10n.string(key)) { _ in perform(); return true }
        }
        var actions: [UIAccessibilityCustomAction] = []
        if video.phase != .failed {
            actions.append(action(video.playing ? "Pause" : video.phase == .ended ? "Replay" : "Play") { video.togglePlay() })
        }
        if video.seekable {
            actions.append(action("Back 10 seconds") { video.skip(-10) })
            actions.append(action("Forward 10 seconds") { video.skip(10) })
        }
        actions.append(action("Playback speed") { video.setRate(UnyriVideoPolicy.nextSpeed(after: video.playRate)) })
        if video.hasAudio != false && !video.audioBlocked {
            actions.append(action(video.userMuted || video.audioNotice == .unavailable ? "Unmute" : "Mute") { video.toggleMute() })
        }
        if UnyriVideoPolicy.offersFill(videoAspect: Double(video.videoAspect ?? 0), screenAspect: Double(screenAspect)) {
            let filled = fill
            actions.append(action(filled ? "Fit to screen" : "Fill screen") { setFill(!filled) })
        }
        return actions
    }
}
private struct UnyriVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let fill: Bool
    var ready: (Bool) -> Void = { _ in }
    let status: () -> String
    let actions: (CGFloat) -> [UIAccessibilityCustomAction]
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
        var ready = false
        var status: (() -> String)?
        var actions: ((CGFloat) -> [UIAccessibilityCustomAction])?
        // Both are read only when VoiceOver asks, so playback never updates them.
        override var accessibilityValue: String? {
            get { ready ? status?() : UnyriL10n.string("Loading…") }
            set { }
        }
        override var accessibilityCustomActions: [UIAccessibilityCustomAction]? {
            get { actions?(bounds.height > 0 ? bounds.width / bounds.height : 0) }
            set { }
        }
    }
    func makeUIView(context: Context) -> Surface {
        let view = Surface(); view.videoLayer.videoGravity = .resizeAspect; view.videoLayer.player = player
        // A filled video overflows the layer; it must stay inside its page.
        view.clipsToBounds = true
        view.isAccessibilityElement = true; view.accessibilityIdentifier = "media-video-surface"
        view.accessibilityLabel = UnyriL10n.string("Video")
        view.status = status; view.actions = actions
        context.coordinator.observation = view.videoLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak coordinator = context.coordinator, weak view] layer, _ in
            let value = layer.isReadyForDisplay
            DispatchQueue.main.async {
                if value, let span = coordinator?.performanceSpan {
                    PerformanceRecorder.shared.end(span); coordinator?.performanceSpan = nil
                }
                view?.ready = value; coordinator?.ready(value)
            }
        }
        return view
    }
    func updateUIView(_ view: Surface, context: Context) {
        context.coordinator.ready = ready
        view.videoLayer.player = player
        view.status = status; view.actions = actions
        let gravity: AVLayerVideoGravity = fill ? .resizeAspectFill : .resizeAspect
        guard view.videoLayer.videoGravity != gravity else { return }
        CATransaction.begin()
        if UIAccessibility.isReduceMotionEnabled { CATransaction.setDisableActions(true) } else { CATransaction.setAnimationDuration(0.25) }
        view.videoLayer.videoGravity = gravity
        CATransaction.commit()
    }
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

/// Times and scrubber: the only views that observe the clock, so playing redraws one row
/// at most once per second.
private struct UnyriVideoProgressRow: View {
    let player: AVPlayer
    @ObservedObject var clock: UnyriVideoClock
    /// Playing with the chrome up in an active app: only then the thumb follows playback.
    let running: Bool
    let touch: (Bool) -> Void
    let began: () -> Void
    let scrub: (Double) -> Void
    let ended: (Double) -> Void
    let jump: (Double) -> Void
    @AppStorage("media.video.remainingTime") private var showsRemaining = false
    var body: some View {
        let shown = clock.scrubSecond.map(Double.init) ?? clock.elapsed
        let right = showsRemaining ? UnyriVideoPolicy.remaining(elapsed: shown, duration: clock.duration) : UnyriVideoPolicy.total(clock.duration)
        // No spacing: the scrubber's own frame carries the 8 pt gaps as grab slop.
        HStack(spacing: 0) {
            // VoiceOver hears the same time from the scrubber.
            Text(verbatim: UnyriVideoPolicy.time(shown))
                .font(.system(size: 12, weight: .medium).monospacedDigit()).foregroundStyle(.white.opacity(0.92))
                .frame(minWidth: 36, alignment: .leading)
                .accessibilityHidden(true)
            UnyriVideoScrubber(player: player, duration: clock.duration, position: clock.elapsed, running: running,
                               touch: touch, began: began, scrub: scrub, ended: ended, jump: jump)
                .frame(height: 44)
            Button { showsRemaining.toggle() } label: {
                Text(verbatim: right)
                    .font(.system(size: 12, weight: .medium).monospacedDigit()).foregroundStyle(.white.opacity(0.58))
                    .frame(minWidth: 44, minHeight: 44, alignment: .trailing)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(showsRemaining ? "Show duration" : "Show remaining time")
            .accessibilityValue(Text(verbatim: right))
        }
    }
}

private struct UnyriVideoScrubber: UIViewRepresentable {
    let player: AVPlayer
    let duration: Double
    let position: Double
    let running: Bool
    let touch: (Bool) -> Void
    let began: () -> Void
    let scrub: (Double) -> Void
    let ended: (Double) -> Void
    let jump: (Double) -> Void
    func makeUIView(context: Context) -> UnyriScrubberControl { UnyriScrubberControl(frame: .zero) }
    func updateUIView(_ control: UnyriScrubberControl, context: Context) {
        control.player = player
        control.touched = touch; control.began = began; control.scrubbed = scrub; control.ended = ended; control.jumped = jump
        control.duration = duration
        control.show(position)
        control.running = running
    }
    static func dismantleUIView(_ control: UnyriScrubberControl, coordinator: ()) {
        control.running = false; control.player = nil
        // The row goes only with the chrome (or the page).
        control.chromeShown = false
    }
}

/// The video scrubber: track, played part and thumb as three layers. Drags are relative,
/// so touching the bar never jumps, and get finer as the finger moves away from it; the
/// frame follows through the session's chase seeks. It works in fractions of the length,
/// so a clip under a second reaches the end, and a pan that starts on it never reaches
/// the pager or the dismiss gesture.
private final class UnyriScrubberControl: UIControl {
    weak var player: AVPlayer?
    var duration = 0.0 {
        didSet {
            guard duration != oldValue else { return }
            isEnabled = duration > 0
            capFrameRate()
            layoutBar(animated: false)
        }
    }
    /// Playing, chrome up and the app active: only then a display link moves the thumb.
    var running = false { didSet { if running != oldValue { synchronize() } } }
    var touched: (Bool) -> Void = { _ in }
    var began: () -> Void = {}
    var scrubbed: (Double) -> Void = { _ in }
    var ended: (Double) -> Void = { _ in }
    /// A tap on the bar or a VoiceOver step: one exact seek, playback goes on.
    var jumped: (Double) -> Void = { _ in }

    /// Seconds under the thumb.
    private var value = 0.0
    private let track = CALayer(), played = CALayer(), thumb = CALayer()
    private let bubble: UIVisualEffectView
    private let bubbleTime = UILabel(), bubbleHint = UILabel()
    private var link: CADisplayLink?
    private var linkFPS: Float = 0
    private var touching = false
    private var touchPoint = CGPoint.zero
    private var touchTime: TimeInterval = 0
    private var moved = false
    private var startX: CGFloat = 0
    private var startValue = 0.0
    private var factor = 1.0
    private var atEdge = false
    /// The finger's height in the control, for the bubble.
    private var fingerY: CGFloat = 0
    /// Grab slop past each track end. It lies inside the control's own frame, because a
    /// touch outside a representable's frame never reaches it, and at either end half the
    /// thumb hangs past the track.
    private static let slop: CGFloat = 8

    override init(frame: CGRect) {
        let effect: UIVisualEffect
        if #available(iOS 26.0, *) { effect = UIGlassEffect() } else { effect = UIBlurEffect(style: .systemUltraThinMaterialDark) }
        bubble = UIVisualEffectView(effect: effect)
        super.init(frame: frame)
        track.backgroundColor = UIColor.white.withAlphaComponent(0.22).cgColor
        played.backgroundColor = UIColor.white.withAlphaComponent(0.95).cgColor
        thumb.backgroundColor = UIColor.white.cgColor
        thumb.borderColor = UIColor.black.withAlphaComponent(0.25).cgColor
        thumb.borderWidth = 0.5
        for bar in [track, played, thumb] { layer.addSublayer(bar) }
        bubbleTime.font = .monospacedDigitSystemFont(ofSize: 15, weight: .semibold)
        bubbleTime.textColor = .white
        bubbleTime.textAlignment = .center
        bubbleHint.font = .systemFont(ofSize: 12)
        bubbleHint.textColor = UIColor.white.withAlphaComponent(0.7)
        bubbleHint.textAlignment = .center
        bubbleHint.isHidden = true
        bubble.contentView.addSubview(bubbleTime)
        bubble.contentView.addSubview(bubbleHint)
        bubble.alpha = 0
        bubble.isUserInteractionEnabled = false
        bubble.layer.cornerCurve = .continuous
        // Glass draws its own shape; the blur before iOS 26 needs the clip.
        if #unavailable(iOS 26.0) { bubble.clipsToBounds = true }
        addSubview(bubble)
        isEnabled = false
        // Element-ness comes from `exposed` (see VoiceOver below).
        accessibilityTraits = .adjustable
        accessibilityLabel = UnyriL10n.string("Playback position")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // MARK: Position

    /// The clock's time while the finger is off the bar. A running link owns the thumb,
    /// so the clock only corrects it after a seek.
    func show(_ seconds: Double) {
        guard !touching, seconds.isFinite else { return }
        if link != nil && abs(seconds - value) <= 1 { return }
        let next = duration > 0 ? min(duration, max(0, seconds)) : max(0, seconds)
        guard next != value else { return }
        value = next
        layoutBar(animated: false)
    }

    private var fraction: CGFloat { duration > 0 ? CGFloat(min(1, max(0, value / duration))) : 0 }
    /// The track runs between the slops; all time maths uses it.
    private var trackWidth: CGFloat { max(0, bounds.width - 2 * Self.slop) }
    private var thumbX: CGFloat { Self.slop + trackWidth * fraction }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutBar(animated: false)
        capFrameRate()
    }

    /// Layer frames for the current value. Only the touch-down growth animates.
    private func layoutBar(animated: Bool) {
        CATransaction.begin()
        if animated && !UIAccessibility.isReduceMotionEnabled {
            CATransaction.setAnimationDuration(0.15)
            CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeOut))
        } else {
            CATransaction.setDisableActions(true)
        }
        let height: CGFloat = touching ? 8 : 4
        let size: CGFloat = touching ? 20 : 12
        let width = trackWidth, mid = bounds.midY
        track.frame = CGRect(x: Self.slop, y: mid - height / 2, width: width, height: height)
        track.cornerRadius = height / 2
        played.frame = CGRect(x: Self.slop, y: mid - height / 2, width: width * fraction, height: height)
        played.cornerRadius = height / 2
        thumb.frame = CGRect(x: thumbX - size / 2, y: mid - size / 2, width: size, height: size)
        thumb.cornerRadius = size / 2
        CATransaction.commit()
        if bubble.alpha > 0 { layoutBubble() }
    }

    // MARK: Display link

    override func didMoveToWindow() {
        super.didMoveToWindow()
        synchronize()
    }

    private func synchronize() {
        guard running, window != nil, !touching else {
            link?.invalidate()
            link = nil
            return
        }
        if link == nil {
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            self.link = link
            linkFPS = 0
        }
        capFrameRate()
    }

    /// About one pixel of thumb travel per frame instead of up to 120 Hz on ProMotion.
    private func capFrameRate() {
        guard let link else { return }
        let fps = Float(UnyriVideoPolicy.displayLinkFPS(trackWidth: Double(trackWidth),
            scale: Double(traitCollection.displayScale), duration: duration))
        guard fps != linkFPS else { return }
        linkFPS = fps
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 4, maximum: fps, preferred: fps)
    }

    @objc private func tick() {
        guard duration > 0, let seconds = player?.currentTime().seconds, seconds.isFinite else { return }
        // Sub-pixel moves are invisible; skipping them saves a layer update per frame.
        let pixels = abs(seconds - value) / duration * Double(trackWidth * traitCollection.displayScale)
        guard pixels >= 0.5 else { return }
        value = min(duration, max(0, seconds))
        layoutBar(animated: false)
    }

    // MARK: Touches

    /// A pan that starts on the bar is a scrub: the pager and the dismiss pan never get it.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer is UIPanGestureRecognizer && gestureRecognizer.view !== self { return false }
        return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard duration > 0 else { return false }
        touching = true
        touchPoint = touch.location(in: self)
        fingerY = touchPoint.y
        touchTime = touch.timestamp
        moved = false
        factor = 1
        UnyriPlayerHaptics.prepare()
        synchronize()
        layoutBar(animated: true)
        touched(true)
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        let point = touch.location(in: self)
        fingerY = point.y
        if !moved {
            guard hypot(point.x - touchPoint.x, point.y - touchPoint.y) >= 4 else { return true }
            // A drag pauses playback; a tap never does.
            moved = true
            startX = touchPoint.x
            startValue = value
            atEdge = value <= 0 || value >= duration
            began()
        }
        let nextFactor = UnyriVideoPolicy.scrubFactor(distance: Double(point.y - bounds.midY))
        if nextFactor != factor {
            // Re-based under the finger, so a precision change never moves the thumb.
            startValue = value
            startX = point.x
            factor = nextFactor
        }
        let width = max(1, trackWidth)
        let target = min(duration, max(0, startValue + Double((point.x - startX) / width) * duration * factor))
        let edge = target <= 0 || target >= duration
        if edge && !atEdge { UnyriPlayerHaptics.selection() }
        atEdge = edge
        if target != value {
            value = target
            layoutBar(animated: false)
            scrubbed(target)
        }
        updateBubble()
        showBubble(true)
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) {
        if moved {
            finishTracking()
            ended(value)
            return
        }
        // A short tap jumps there; a tap on the thumb (it was meant as a grab) or a long
        // touch seeks nothing.
        if let touch, touch.timestamp - touchTime < 0.25, trackWidth > 0 {
            let x = touch.location(in: self).x
            let tapped = min(duration, max(0, Double((x - Self.slop) / trackWidth) * duration))
            if UnyriVideoPolicy.tapSeeks(from: value, to: tapped, thumbDistance: Double(x - thumbX)) {
                value = tapped
                UnyriPlayerHaptics.selection()
                finishTracking()
                jumped(tapped)
                return
            }
        }
        finishTracking()
    }

    override func cancelTracking(with event: UIEvent?) {
        finishTracking()
        if moved { ended(value) }
    }

    private func finishTracking() {
        touching = false
        showBubble(false)
        layoutBar(animated: true)
        touched(false)
        synchronize()
    }

    // MARK: Bubble

    private func showBubble(_ visible: Bool) {
        let alpha: CGFloat = visible ? 1 : 0
        guard bubble.alpha != alpha else { return }
        if visible { layoutBubble() }
        if UIAccessibility.isReduceMotionEnabled {
            bubble.alpha = alpha
        } else {
            UIView.animate(withDuration: 0.12, delay: 0, options: [.beginFromCurrentState]) { self.bubble.alpha = alpha }
        }
    }

    private func updateBubble() {
        let time = UnyriVideoPolicy.time(value)
        if bubbleTime.text != time { bubbleTime.text = time }
        let hint = factor < 1 ? String(format: UnyriL10n.string("Fine scrubbing ×%@"), factor >= 0.5 ? "½" : factor >= 0.25 ? "¼" : "⅛") : nil
        if bubbleHint.text != hint {
            bubbleHint.text = hint
            bubbleHint.isHidden = hint == nil
        }
        layoutBubble()
    }

    /// Centred over the thumb, 12 pt above the bar, kept inside the screen's edges. A finger
    /// above the bar (fine scrubbing) would cover it there, so it then rides above the
    /// fingertip, as far from it as from a finger on the bar.
    private func layoutBubble() {
        let hinted = !bubbleHint.isHidden
        let width = max(bubbleTime.intrinsicContentSize.width, hinted ? bubbleHint.intrinsicContentSize.width : 0) + 20
        let height: CGFloat = hinted ? 46 : 30
        var x = thumbX - width / 2
        var bottom = min(-12, fingerY - bounds.midY - 12)
        if let window {
            let limits = convert(window.bounds, from: window).insetBy(dx: 12, dy: 0)
            x = min(max(x, limits.minX), limits.maxX - width)
            bottom = max(bottom, limits.minY + window.safeAreaInsets.top + 8 + height)
        }
        bubble.frame = CGRect(x: x, y: bottom - height, width: width, height: height)
        bubble.layer.cornerRadius = height / 2
        bubbleTime.frame = CGRect(x: 0, y: hinted ? 5 : 0, width: width, height: hinted ? 20 : height)
        bubbleHint.frame = CGRect(x: 0, y: 26, width: width, height: 15)
    }

    // MARK: VoiceOver

    /// Cleared when SwiftUI takes the row away with the chrome. The SwiftUI controls leave
    /// the accessibility tree with their views, but this hosted UIKit view was still handed
    /// out after its row was gone (the walkthrough found it), and its own flags would keep
    /// saying "element". VoiceOver must not focus a bar nobody can see.
    var chromeShown = true
    /// An element only while it is on screen in a shown chrome, like the back button.
    private var exposed: Bool { chromeShown && window != nil }
    override var isAccessibilityElement: Bool {
        get { exposed }
        set { }
    }
    /// Also hides the time bubble's labels, which a non-element would otherwise expose.
    override var accessibilityElementsHidden: Bool {
        get { !exposed }
        set { }
    }

    override var accessibilityValue: String? {
        get { String(format: UnyriL10n.string("%@ of %@"), UnyriVideoPolicy.time(value), UnyriVideoPolicy.total(duration)) }
        set { }
    }

    /// A VoiceOver double tap would otherwise become a tap at the bar's centre and seek to
    /// the middle. Like a slider, the bar only moves by swipes up and down.
    override func accessibilityActivate() -> Bool { true }

    override func accessibilityIncrement() { step(10) }
    override func accessibilityDecrement() { step(-10) }

    private func step(_ offset: Double) {
        guard duration > 0 else { return }
        value = min(duration, max(0, value + offset))
        layoutBar(animated: false)
        jumped(value)
    }
}

/// Video gestures by zone. The centre toggles the chrome at once; the sides wait for a
/// possible double tap, which seeks 10 s and starts a streak that takes every further tap
/// until it ends. Holding anywhere plays faster, and sliding sideways picks the rate; a
/// pinch switches fit and fill. The pan begins only vertically: horizontal pans belong to
/// the pager, and the controls above this view keep their own gestures.
private struct UnyriVideoGestures: UIViewRepresentable {
    let video: UnyriVideoSession
    let streak: UnyriSeekStreak
    let canFill: Bool
    let toggle: () -> Void
    /// A hold started (true) or ended (false).
    let held: (Bool) -> Void
    /// A pinch asked for fill (true) or fit (false).
    let pinch: (Bool) -> Void
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
        context.coordinator.install(on: view)
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.owner = self
        (view as? GestureSurface)?.reservedTop = reservedTop
        (view as? GestureSurface)?.reservedBottom = reservedBottom
        context.coordinator.update()
    }
    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        coordinator.endHold()
        coordinator.owner.streak.cancel()
    }
    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var owner: UnyriVideoGestures
        private let centreTap = UITapGestureRecognizer()
        private let sideTap = UITapGestureRecognizer()
        private let sideDouble = UITapGestureRecognizer()
        private let streakTap = UITapGestureRecognizer()
        private let hold = UILongPressGestureRecognizer()
        private let pan = UIPanGestureRecognizer()
        private let pinchGesture = UIPinchGestureRecognizer()
        private var holdStartX: CGFloat = 0
        private var holding = false
        private var pinchDecided = false
        private weak var linkedStreak: UnyriSeekStreak?
        init(_ owner: UnyriVideoGestures) { self.owner = owner }

        func install(on view: UIView) {
            let actions: [(UIGestureRecognizer, Selector)] = [
                (centreTap, #selector(tapped)), (sideTap, #selector(tapped)),
                (sideDouble, #selector(stepped(_:))), (streakTap, #selector(stepped(_:))),
                (hold, #selector(held(_:))), (pan, #selector(panned(_:))), (pinchGesture, #selector(pinched(_:))),
            ]
            for (gesture, action) in actions {
                gesture.addTarget(self, action: action)
                gesture.delegate = self
                view.addGestureRecognizer(gesture)
            }
            sideDouble.numberOfTapsRequired = 2
            // Only the sides wait for a second tap; the centre answers at once.
            sideTap.require(toFail: sideDouble)
            hold.minimumPressDuration = 0.35
            hold.allowableMovement = 12
            pan.maximumNumberOfTouches = 1
            streakTap.isEnabled = false
            update()
        }

        func update() {
            pinchGesture.isEnabled = owner.canFill
            guard linkedStreak !== owner.streak else { return }
            linkedStreak = owner.streak
            owner.streak.changed = { [weak self] active in self?.setStreaking(active) }
        }

        /// During a streak every tap is a step, and none toggles the chrome.
        private func setStreaking(_ active: Bool) {
            streakTap.isEnabled = active
            centreTap.isEnabled = !active
            sideTap.isEnabled = !active
            sideDouble.isEnabled = !active
        }

        private func zone(_ point: CGPoint, in view: UIView) -> UnyriVideoPolicy.Zone {
            UnyriVideoPolicy.zone(view.bounds.width > 0 ? Double(point.x / view.bounds.width) : 0.5)
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let view = gestureRecognizer.view else { return true }
            if gestureRecognizer === centreTap { return zone(touch.location(in: view), in: view) == .centre }
            if gestureRecognizer === sideTap || gestureRecognizer === sideDouble { return zone(touch.location(in: view), in: view) != .centre }
            if gestureRecognizer === hold { UnyriPlayerHaptics.prepare() }
            return true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let view = gestureRecognizer.view else { return false }
            if gestureRecognizer === pan {
                let v = pan.velocity(in: view)
                return UnyriMediaGesturePolicy.isVertical(x: v.x, y: v.y)
            }
            // Only a playing video speeds up; on a paused one the touch stays a tap.
            if gestureRecognizer === hold { return owner.video.playing }
            return true
        }

        @objc func tapped() { owner.toggle() }

        @objc func stepped(_ gesture: UITapGestureRecognizer) {
            guard let view = gesture.view else { return }
            let area = zone(gesture.location(in: view), in: view)
            guard area != .centre else { return }
            owner.streak.step(forward: area == .right, video: owner.video)
        }

        @objc func held(_ gesture: UILongPressGestureRecognizer) {
            guard let view = gesture.view else { return }
            switch gesture.state {
            case .began:
                holdStartX = gesture.location(in: view).x
                applyHold(dx: 0)
            case .changed:
                applyHold(dx: gesture.location(in: view).x - holdStartX)
            case .ended, .cancelled, .failed:
                endHold()
            default:
                break
            }
        }

        /// A tick when the hold starts and at every rate change.
        private func applyHold(dx: CGFloat) {
            let video = owner.video
            let rate = UnyriVideoPolicy.holdRate(base: video.playRate, dx: Double(dx), canFastForward: video.canPlayFastForward)
            guard rate != video.heldRate, video.hold(rate) else { return }
            UnyriPlayerHaptics.selection()
            if !holding {
                holding = true
                owner.held(true)
            }
        }

        func endHold() {
            guard holding else { return }
            holding = false
            owner.video.endHold()
            owner.held(false)
        }

        @objc func pinched(_ gesture: UIPinchGestureRecognizer) {
            switch gesture.state {
            case .began:
                pinchDecided = false
            case .changed:
                guard !pinchDecided, let fill = UnyriVideoPolicy.pinchFill(scale: Double(gesture.scale)) else { return }
                pinchDecided = true
                owner.pinch(fill)
            default:
                break
            }
        }

        @objc func panned(_ gesture: UIPanGestureRecognizer) {
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

/// A double-tap seek streak: the double tap seeks 10 s, and every tap within 0.7 s of the
/// last step adds 10 s or turns round on the other side. One cancellable work item, no
/// timer. Only the ripple observes it.
@MainActor
private final class UnyriSeekStreak: ObservableObject {
    struct Ripple: Equatable {
        let forward: Bool
        let seconds: Int
    }
    @Published private(set) var ripple: Ripple?
    /// Tells the gesture surface that every tap is a step (true) or a tap again (false).
    var changed: ((Bool) -> Void)?
    private var streak: UnyriVideoPolicy.SeekStreak?
    private var window: DispatchWorkItem?

    func step(forward: Bool, video: UnyriVideoSession) {
        let duration = video.clock.duration
        guard video.seekable, duration > 0 else { return }
        let previous = streak
        let next = UnyriVideoPolicy.nextStreak(previous, forward: forward, at: video.currentTime, duration: duration)
        streak = next
        // A tick for the first step and for a turn, not for every tap.
        if previous?.forward != forward { UnyriPlayerHaptics.selection() }
        video.seekStep(to: UnyriVideoPolicy.streakTarget(next, duration: duration))
        let shown = Ripple(forward: forward, seconds: UnyriVideoPolicy.streakSeconds(next, duration: duration))
        if ripple != shown { ripple = shown }
        if previous == nil { changed?(true) }
        window?.cancel()
        let item = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.finish() } }
        window = item
        DispatchQueue.main.asyncAfter(deadline: .now() + UnyriVideoPolicy.streakWindow, execute: item)
    }

    /// Page change or dismissal: ends without an announcement.
    func cancel() {
        window?.cancel()
        window = nil
        if streak != nil { end() }
    }

    /// The window closed: the ripple fades and VoiceOver hears the total.
    private func finish() {
        window = nil
        guard let streak else { return }
        let seconds = ripple?.seconds ?? 0
        end()
        guard seconds > 0 else { return }
        let format = UnyriL10n.string(streak.forward ? "Forward %lld seconds" : "Back %lld seconds")
        UIAccessibility.post(notification: .announcement, argument: String.localizedStringWithFormat(format, seconds))
    }

    private func end() {
        streak = nil
        ripple = nil
        changed?(false)
    }
}

/// The streak's ripple: a half ellipse from the screen edge with the seconds moved.
private struct UnyriSeekRipple: View {
    @ObservedObject var streak: UnyriSeekStreak
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let ripple = streak.ripple {
                    let width = geometry.size.width
                    let height = geometry.size.height
                    let depth = width * 0.38
                    ZStack {
                        // A dark base under the highlight, so the shape and the white
                        // seconds still read over snow, a sky or a whiteboard.
                        Ellipse().fill(Color.black.opacity(0.22))
                            .overlay(Ellipse().fill(Color.white.opacity(0.07)))
                            .frame(width: depth * 2, height: height * 1.2)
                            .position(x: ripple.forward ? width : 0, y: height / 2)
                        VStack(spacing: 6) {
                            Image(systemName: ripple.forward ? "forward.fill" : "backward.fill")
                                .font(.system(size: 15, weight: .semibold))
                            Text(verbatim: String(format: UnyriL10n.string("%lld s"), ripple.seconds))
                                .font(.system(size: 15, weight: .semibold).monospacedDigit())
                        }
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.35), radius: 3)
                        .position(x: ripple.forward ? width - depth / 2 : depth / 2, y: height / 2)
                    }
                    .frame(width: width, height: height)
                    .clipped()
                    // A turn draws a new ripple on the other side while the old one fades.
                    .id(ripple.forward)
                    .transition(.asymmetric(
                        insertion: (reduceMotion ? AnyTransition.opacity
                            : .opacity.combined(with: .scale(scale: 0.6, anchor: ripple.forward ? .trailing : .leading)))
                            .animation(.easeOut(duration: 0.12)),
                        removal: .opacity.animation(.easeOut(duration: 0.5))))
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct UnyriVideoControls: View {
    let player: AVPlayer
    @ObservedObject var video: UnyriVideoSession
    let active: Bool
    let fill: Bool
    let setFill: (Bool) -> Void
    let onControls: (Bool) -> Void
    let controlsPinned: Bool
    let actionsButton: AnyView
    var onDismiss: () -> Void = {}
    var onDismissDrag: (CGFloat) -> Void = { _ in }
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var visible = true
    @State private var dragging = false
    @State private var interaction = 0
    @State private var menuOpen = false
    /// A reference in `@State`: only the ripple observes it, so taps never redraw the chrome.
    @State private var streak = UnyriSeekStreak()
    private var timerKey: String { "\(visible):\(video.playing):\(dragging):\(active):\(voiceOver):\(switchControl):\(menuOpen):\(interaction):\(controlsPinned):\(video.heldRate != nil)" }
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                UnyriVideoGestures(video: video, streak: streak,
                    canFill: UnyriVideoPolicy.offersFill(videoAspect: Double(video.videoAspect ?? 0),
                        screenAspect: geometry.size.height > 0 ? Double(geometry.size.width / geometry.size.height) : 0),
                    toggle: toggleControls,
                    held: { holding in
                        if holding { visible = false }
                        interaction += 1
                    },
                    pinch: { wantsFill in if wantsFill != fill { setFill(wantsFill) } },
                    dismiss: {
                        guard !controlsPinned else { return }
                        video.endHold(); onDismiss()
                    }, drag: { value in if !controlsPinned { onDismissDrag(value) } },
                    reservedTop: visible ? geometry.safeAreaInsets.top + 70 : 0,
                    reservedBottom: visible ? geometry.safeAreaInsets.bottom + 136 : 0)
                    .accessibilityHidden(true)
                UnyriSeekRipple(streak: streak)
                if visible {
                    VStack {
                        Spacer()
                        VStack(spacing: 0) {
                            UnyriVideoProgressRow(player: player, clock: video.clock, running: active && video.playing,
                                touch: { down in dragging = down; interaction += 1 },
                                began: { video.beginScrub() },
                                scrub: { video.scrub(to: $0) },
                                ended: { video.endScrub(at: $0) },
                                jump: { video.seek(to: $0) })
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
                if let held = video.heldRate {
                    HStack(spacing: 4) {
                        Image(systemName: "forward.fill").font(.system(size: 11, weight: .semibold)).accessibilityHidden(true)
                        Text(verbatim: UnyriVideoPolicy.speedLabel(held, locale: .current))
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .accessibilityIdentifier("media-temporary-speed")
                    }
                    .padding(.horizontal, 12).frame(height: 32)
                    .modifier(UnyriAttachGlass(shape: Capsule()))
                    .frame(maxHeight: .infinity, alignment: .top).padding(.top, geometry.safeAreaInsets.top + 12)
                    .allowsHitTesting(false)
                }
            }.foregroundStyle(.white)
        }
        .onAppear { onControls(true); video.setChromeVisible(true) }
        .onChange(of: visible) { value in
            onControls(value); video.setChromeVisible(value)
            // The chrome's elements came or went. VoiceOver and Switch Control re-read the
            // page once this update commits (hence async), so neither keeps its focus or its
            // element list on a control that is gone.
            DispatchQueue.main.async { UIAccessibility.post(notification: .layoutChanged, argument: nil) }
        }
        .onChange(of: active) { value in if !value { video.endHold() } }
        .onDisappear { video.endHold(); streak.cancel() }
        // A pause or the end brings the chrome back (and the timer below keeps it).
        .onChange(of: video.playing) { playing in if !playing { visible = true } }
        // Never hides on its own for VoiceOver or Switch Control: their users reach the
        // controls one by one.
        .task(id: timerKey) {
            guard visible && video.playing && !dragging && active && !voiceOver && !switchControl && !menuOpen && !controlsPinned && video.heldRate == nil else { return }
            do { try await Task.sleep(nanoseconds: 3_500_000_000); visible = false } catch { }
        }
    }
    private func panelWidth(_ geometry: GeometryProxy) -> CGFloat {
        max(0, min(geometry.size.width - 32, geometry.size.width > geometry.size.height ? 440 : geometry.size.width))
    }
    private var playbackOptions: some View {
        HStack(spacing: 0) {
            UnyriSpeedChip(shown: video.heldRate ?? video.playRate, selected: video.playRate,
                select: { rate in
                    UnyriPlayerHaptics.selection()
                    video.setRate(rate)
                    interaction += 1
                },
                menuOpen: { open in menuOpen = open; interaction += 1 })
            // A refused session (another app's call) offers the same control as a retry.
            control(video.userMuted || video.audioBlocked || video.audioNotice != nil ? "speaker.slash" : "speaker.wave.2",
                    label: video.userMuted || video.audioNotice == .unavailable ? "Unmute" : "Mute", size: 44) {
                video.toggleMute(); interaction += 1
            }.disabled(video.audioBlocked)
        }.fixedSize()
    }
    private var transportControls: some View {
        let ended = video.phase == .ended
        return HStack(spacing: 0) {
            control("gobackward.10", label: "Back 10 seconds", size: 44) { video.skip(-10); interaction += 1 }.disabled(!video.seekable)
            control(ended ? "arrow.counterclockwise" : video.playing ? "pause.fill" : "play.fill",
                    label: ended ? "Replay" : video.playing ? "Pause" : "Play", size: 44, accent: true) {
                video.togglePlay(); interaction += 1
            }.disabled(video.phase == .failed)
            control("goforward.10", label: "Forward 10 seconds", size: 44) { video.skip(10); interaction += 1 }.disabled(!video.seekable)
        }.fixedSize()
    }
    private func control(_ icon: String, label: String, size: CGFloat, accent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.system(size: 21)).frame(width: 36, height: 36)
            .foregroundStyle(accent ? colorStyle.onColorfulButton : .white)
            .background(accent ? colorStyle.decorative(Color(red: 0.396, green: 0.345, blue: 1)) : Color.clear, in: Circle())
            .frame(width: size, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(label)))
    }
    private func toggleControls() { visible.toggle(); interaction += 1 }
}

/// A plain UIButton with a native pull-down menu: checkmarks instead of a dialog, and it
/// reports while the menu is open so the chrome does not hide under it.
private class UnyriViewerMenuButton: UIButton {
    var menuOpen: (Bool) -> Void = { _ in }
    /// Read when the menu opens, so it always shows the current state.
    var items: () -> [UIMenuElement] = { [] }

    override init(frame: CGRect) {
        super.init(frame: frame)
        showsMenuAsPrimaryAction = true
        menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] completion in completion(self?.items() ?? []) }])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willDisplayMenuFor configuration: UIContextMenuConfiguration,
                                         animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
        menuOpen(true)
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willEndFor configuration: UIContextMenuConfiguration,
                                         animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        menuOpen(false)
    }
}

/// The speed chip: a 26 pt capsule inside a 44 pt target. Outlined at 1×, filled white at
/// any other speed; during a hold it shows the held rate.
private struct UnyriSpeedChip: UIViewRepresentable {
    let shown: Float
    let selected: Float
    let select: (Float) -> Void
    let menuOpen: (Bool) -> Void
    func makeUIView(context: Context) -> Chip { Chip(frame: .zero) }
    func updateUIView(_ chip: Chip, context: Context) {
        chip.menuOpen = menuOpen
        chip.items = { [select] in
            UnyriVideoPolicy.speeds.map { rate in
                UIAction(title: UnyriVideoPolicy.speedLabel(rate, locale: .current), state: rate == selected ? .on : .off) { _ in select(rate) }
            }
        }
        chip.show(shown)
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: Chip, context: Context) -> CGSize? { uiView.intrinsicContentSize }
    /// The chip goes only with the chrome (or the page).
    static func dismantleUIView(_ chip: Chip, coordinator: ()) { chip.chromeShown = false }

    final class Chip: UnyriViewerMenuButton {
        private let capsule = UIView()
        private let label = UILabel()
        private var rate: Float = 0
        /// Cleared when the chrome hides, for the same reason as the scrubber's: a hosted
        /// UIKit view can outlive its SwiftUI removal in the accessibility tree.
        var chromeShown = true
        private var exposed: Bool { chromeShown && window != nil }
        override var isAccessibilityElement: Bool {
            get { exposed }
            set { }
        }
        override var accessibilityElementsHidden: Bool {
            get { !exposed }
            set { }
        }

        override init(frame: CGRect) {
            super.init(frame: frame)
            capsule.isUserInteractionEnabled = false
            capsule.layer.cornerRadius = 13
            capsule.layer.cornerCurve = .continuous
            label.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
            label.textAlignment = .center
            capsule.addSubview(label)
            addSubview(capsule)
            accessibilityLabel = UnyriL10n.string("Playback speed")
            show(1)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        func show(_ rate: Float) {
            guard rate != self.rate else { return }
            self.rate = rate
            let text = UnyriVideoPolicy.speedLabel(rate, locale: .current)
            label.text = text
            accessibilityValue = text
            let normal = rate == 1
            label.textColor = normal ? UIColor.white.withAlphaComponent(0.9) : UIColor(white: 0x11 / 255.0, alpha: 1)
            capsule.backgroundColor = normal ? .clear : UIColor.white.withAlphaComponent(0.92)
            capsule.layer.borderWidth = normal ? 1 : 0
            capsule.layer.borderColor = UIColor.white.withAlphaComponent(0.22).cgColor
            invalidateIntrinsicContentSize()
            setNeedsLayout()
        }

        private var chipWidth: CGFloat { max(36, ceil(label.intrinsicContentSize.width) + 16) }

        override var intrinsicContentSize: CGSize { CGSize(width: max(44, chipWidth + 8), height: 44) }

        override func layoutSubviews() {
            super.layoutSubviews()
            capsule.frame = CGRect(x: (bounds.width - chipWidth) / 2, y: (bounds.height - 26) / 2, width: chipWidth, height: 26)
            label.frame = capsule.bounds
        }
    }
}
private struct UnyriZoomImage: UIViewRepresentable {
    let url: URL
    var attachmentID: String? = nil
    let active: Bool
    var selected = true
    var initialPreview: UIImage? = nil
    var liveText = false
    let onTap: () -> Void
    let onDismiss: () -> Void
    var onDismissDrag: ((CGFloat) -> Void)? = nil
    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap, onDismiss: onDismiss, onDismissDrag: onDismissDrag) }
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UnyriPhotoScrollView(); scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 5; scroll.delegate = context.coordinator
        scroll.isSelectedMedia = selected
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.panGestureRecognizer.isEnabled = false
        scroll.backgroundColor = .clear; scroll.showsHorizontalScrollIndicator = false; scroll.showsVerticalScrollIndicator = false
        let image = UIImageView(image: initialPreview); image.contentMode = .scaleAspectFit; image.clipsToBounds = true; scroll.addSubview(image)
        image.accessibilityIdentifier = "media-viewer-photo"; image.accessibilityLabel = UnyriL10n.string("Photo")
        image.isAccessibilityElement = selected && initialPreview != nil
        scroll.picture = image
        context.coordinator.image = image; context.coordinator.scroll = scroll
        context.coordinator.attachmentID = attachmentID
        context.coordinator.watch(url, active: active)
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
        context.coordinator.watch(url, active: active)
        context.coordinator.onDismissDrag = onDismissDrag
        (scroll as? UnyriPhotoScrollView)?.isSelectedMedia = selected
        // The embedded poster may finish after this representable was created.
        // Never replace an already decoded full image with a smaller thumbnail.
        if !context.coordinator.hasDecodedImage, let initialPreview {
            context.coordinator.image?.image = initialPreview
        }
        context.coordinator.image?.isAccessibilityElement = selected && context.coordinator.image?.image != nil
        if #available(iOS 16.0, *) { context.coordinator.setLiveText(liveText && selected && active) }
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
    static func dismantleUIView(_ scroll: UIScrollView, coordinator: Coordinator) { coordinator.stop(); coordinator.imageUpdates?.cancel(); coordinator.imageUpdates = nil; coordinator.image?.stopAnimating(); coordinator.image?.image = nil }
    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        weak var image: UIImageView?
        var attachmentID: String?
        var imageUpdates: AnyCancellable?
        private var watchedURL: URL?
        private var active = false
        func watch(_ url: URL, active: Bool) {
            self.active = active
            guard watchedURL != url || imageUpdates == nil else { return }
            watchedURL = url
            imageUpdates = UnyriAttachmentImages.changes.sink { [weak self] change in
                guard let self else { return }
                switch change {
                case .reset: self.stop(); self.image?.image = nil; self.hasDecodedImage = false
                case .cacheCleared:
                    self.stop(); self.image?.image = nil; self.hasDecodedImage = false
                    if self.active { self.load(url, pixelEdge: max(768, self.loadedPixelEdge), animate: self.animatesImage) }
                case .retry(let path), .replaced(let path):
                    guard path == url.path, self.active else { return }
                    if case .replaced = change { self.hasDecodedImage = false; self.image?.image = nil }
                    guard !self.hasDecodedImage else { return }
                    self.load(url, pixelEdge: max(768, self.loadedPixelEdge), animate: self.animatesImage)
                default: break
                }
            }
        }
        private var liveTextBox: AnyObject?
        private var liveTextTask: Task<Void, Never>?
        private var liveTextOn = false
        /// VisionKit Live Text: select/copy text, call numbers, open links, look up subjects.
        @available(iOS 16.0, *)
        func setLiveText(_ on: Bool) {
            guard on != liveTextOn else { return }
            liveTextOn = on
            liveTextTask?.cancel(); liveTextTask = nil
            guard let view = image else { return }
            let interaction: ImageAnalysisInteraction
            if let existing = liveTextBox as? ImageAnalysisInteraction { interaction = existing }
            else {
                interaction = ImageAnalysisInteraction()
                view.addInteraction(interaction)
                liveTextBox = interaction
            }
            guard on, let picture = view.image else {
                interaction.preferredInteractionTypes = []
                interaction.analysis = nil
                view.isUserInteractionEnabled = false
                return
            }
            view.isUserInteractionEnabled = true
            liveTextTask = Task { @MainActor [weak interaction] in
                let analyzer = ImageAnalyzer()
                let configuration = ImageAnalyzer.Configuration([.text, .machineReadableCode, .visualLookUp])
                guard let analysis = try? await analyzer.analyze(picture, orientation: .up, configuration: configuration),
                      !Task.isCancelled, let interaction else { return }
                interaction.analysis = analysis
                interaction.preferredInteractionTypes = .automatic
                interaction.selectableItemsHighlighted = analysis.hasResults(for: [.text, .machineReadableCode])
            }
        }
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
        var onDismissDrag: ((CGFloat) -> Void)?
        init(onTap: @escaping () -> Void, onDismiss: @escaping () -> Void, onDismissDrag: ((CGFloat) -> Void)? = nil) {
            self.onTap = onTap; self.onDismiss = onDismiss; self.onDismissDrag = onDismissDrag
        }
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
                let distance = abs(translation.y)
                let progress = min(1.0, distance / max(1, scroll.bounds.height * 0.45))
                let scale = max(0.72, 1.0 - progress * 0.28)
                scroll.transform = CGAffineTransform(translationX: translation.x * 0.25, y: translation.y).scaledBy(x: scale, y: scale)
                onDismissDrag?(progress)
            case .ended:
                if scroll.zoomScale <= 1.01 && UnyriMediaGesturePolicy.dismisses(distance: translation.y, velocity: recognizer.velocity(in: scroll.superview).y, height: scroll.bounds.height) { onDismiss() }
                else { resetDismissDrag() }
            case .cancelled, .failed: resetDismissDrag()
            default: break
            }
        }
        private func resetDismissDrag() {
            guard let scroll else { return }
            onDismissDrag?(0)
            UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.25, delay: 0, usingSpringWithDamping: 0.8, initialSpringVelocity: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                scroll.transform = .identity
            }
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
            let cacheGeneration = ConversationImages.generation
            errorLabel?.isHidden = true
            task = Task.detached(priority: .userInitiated) { [weak self] in
                let revision = try? AttachmentImagePolicy.FileRevision.read(url)
                let epoch = await MainActor.run { UnyriAttachmentImages.attemptEpoch }
                guard await MainActor.run(body: { UnyriAttachmentImages.allowsFullImageDecode(revision) }) else {
                    await MainActor.run { [weak self] in if self?.loadID == id { self?.errorLabel?.isHidden = false } }
                    return
                }
                var performanceSpan = PerformanceRecorder.shared.begin(.previewDecode, ["width": Double(pixelEdge)])
                var performanceSuccess = false
                defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
                var failures = 0
                while !Task.isCancelled {
                    let source = PeerImagePolicy.source(url: url)
                    let count = source.map { CGImageSourceGetCount($0) } ?? 0
                    let persistent = source != nil && count == 0
                    await MainActor.run { [weak self] in if self?.loadID == id { self?.animatedSource = count > 1 } }
                    var index = 0
                    var first = true
                    if let source, count > 0 {
                        repeat {
                            guard !Task.isCancelled else { return }
                            let frame: (UIImage?, Double) = autoreleasepool {
                                let cg = CGImageSourceCreateThumbnailAtIndex(source, index, [
                                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                                    kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true,
                                    kCGImageSourceThumbnailMaxPixelSize: count > 1 ? min(1024, pixelEdge) : pixelEdge] as CFDictionary)
                                let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
                                let gif = (properties?[kCGImagePropertyGIFDictionary] as? [CFString: Any]) ??
                                    (properties?[kCGImagePropertyPNGDictionary] as? [CFString: Any]) ?? (properties?["{WebP}" as CFString] as? [CFString: Any])
                                let delay = gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double ?? gif?[kCGImagePropertyGIFDelayTime] as? Double ?? 0.1
                                return (cg.map { UIImage(cgImage: $0) }, delay.isFinite ? min(10, max(0.02, delay)) : 0.1)
                            }
                            guard let decoded = frame.0 else { break }
                            if first {
                                performanceSuccess = true
                                PerformanceRecorder.shared.end(performanceSpan, success: true)
                                performanceSpan = nil
                            }
                            let publish = first; first = false
                            await MainActor.run { [weak self] in
                                guard !Task.isCancelled, self?.loadID == id, cacheGeneration == ConversationImages.generation else { return }
                                self?.image?.image = decoded; self?.hasDecodedImage = true; self?.errorLabel?.isHidden = true
                                self?.image?.isAccessibilityElement = (self?.scroll as? UnyriPhotoScrollView)?.isSelectedMedia == true
                                self?.scroll?.setNeedsLayout()
                                if publish {
                                    ConversationImages.cacheAttachmentImage(decoded, path: url.path, pixelEdge: pixelEdge, generation: cacheGeneration)
                                    if let attachmentID = self?.attachmentID {
                                        UnyriAttachmentImages.remember(AttachmentImageRequest(id: attachmentID, url: url, mime: "image/*"))
                                    }
                                }
                            }
                            guard count > 1, animate else { return }
                            do { try await Task.sleep(nanoseconds: UInt64(frame.1 * 1_000_000_000)) } catch { return }
                            index = (index + 1) % count
                        } while !Task.isCancelled
                    }
                    guard !Task.isCancelled else { return }
                    failures += 1
                    guard let delay = AttachmentImagePolicy.retryDelay(failures: failures, persistent: persistent) else {
                        await MainActor.run { [weak self] in
                            guard self?.loadID == id, cacheGeneration == ConversationImages.generation else { return }
                            UnyriAttachmentImages.noteFullImageFailure(revision,
                                persistent: persistent && UnyriAttachmentImages.wasSteady(since: epoch))
                            self?.errorLabel?.isHidden = false
                        }
                        return
                    }
                    do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
                }
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
    /// iOS itself blocks this app's traffic (Settings → Mobile Data / WLAN → UNYRI off).
    /// Pushes and CallKit still arrive, so the app looked "online" for others while it
    /// could never reach the server (tester report, 9 Nov).
    enum Blocked: Equatable { case cellular, wifi }
    @Published private(set) var wifi = false
    @Published private(set) var cellular = false
    @Published private(set) var constrained = false
    @Published private(set) var connected = false
    @Published private(set) var blocked: Blocked?
    private let monitor = NWPathMonitor()
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.usesInterfaceType(.wifi); let connected = path.status == .satisfied
            let cellular = path.usesInterfaceType(.cellular), constrained = path.isConstrained
            var blocked: Blocked?
            if path.status != .satisfied {
                switch path.unsatisfiedReason {
                case .cellularDenied: blocked = .cellular
                case .wifiDenied: blocked = .wifi
                default: blocked = nil
                }
            }
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Publish only real changes: path updates arrive often and each
                // assignment re-rendered the chat list header.
                if self.wifi != wifi { self.wifi = wifi }
                if self.cellular != cellular { self.cellular = cellular }
                if self.constrained != constrained { self.constrained = constrained }
                if self.connected != connected { self.connected = connected }
                if self.blocked != blocked { self.blocked = blocked }
            }
        }
        monitor.start(queue: DispatchQueue(label: "unyri.media.network"))
    }
    static func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct UnyriCancelDownloadKey: EnvironmentKey { static let defaultValue: (String) -> Void = { _ in } }
extension EnvironmentValues {
    var unyriCancelDownload: (String) -> Void { get { self[UnyriCancelDownloadKey.self] } set { self[UnyriCancelDownloadKey.self] = newValue } }
}

@MainActor final class UnyriIncomingFile: ObservableObject {
    static let shared = UnyriIncomingFile()
    @Published var url: URL?
    @Published private(set) var hasLegacy = false
    @Published private(set) var legacyItemCount = 0
    var hasPendingImport: Bool { url != nil || hasLegacy }
    private(set) var files: [(id: String, url: URL)] = []
    private var batchDirectory: URL?
    private var legacyDirectories: [URL] = []
    private let io: UnyriShareStore.InboxIO
    private var scope: UnyriShareStore.InboxScope?
    private var sessionCurrent: (@MainActor () -> Bool)?
    private var revision: UInt64 = 0
    private var discovery: Task<Void, Never>?
    private var discoveryOffersLegacy = false
    private var discoveryNeedsRescan = false
    private var scoped = false
    init(io: UnyriShareStore.InboxIO = UnyriShareStore.inboxIO) { self.io = io }
    func receive(_ value: URL) {
        guard value.isFileURL else { return }
        close(); scoped = value.startAccessingSecurityScopedResource()
        files = [(UUID().uuidString.lowercased(), value)]; url = value
    }
    private func invalidateDiscovery() {
        revision &+= 1; discovery?.cancel(); discovery = nil
        discoveryNeedsRescan = false
    }
    private func valid(_ scope: UnyriShareStore.InboxScope, revision: UInt64, current: @MainActor () -> Bool) -> Bool {
        self.revision == revision && self.scope == scope && current() && UIApplication.shared.isProtectedDataAvailable
    }
    @discardableResult
    func checkSharedInbox(scope: UnyriShareStore.InboxScope?, offerLegacy: Bool = true,
                          current: @escaping @MainActor () -> Bool) -> Task<Void, Never>? {
        guard let scope, UnyriShareStore.validAccountKey(scope.accountKey), current(),
              UIApplication.shared.isProtectedDataAvailable else { return nil }
        if self.scope != scope {
            if self.scope == nil, url != nil {
                // A document opened directly in the app already owns its URL.
                self.scope = scope; sessionCurrent = current
                return nil
            }
            close(discardBatch: false)
        }
        sessionCurrent = current
        guard url == nil else { return nil }
        discoveryOffersLegacy = offerLegacy
        if let discovery {
            // Keep at most one pending pass. An event observed after the worker
            // read its snapshot must not be consumed by that obsolete result.
            discoveryNeedsRescan = true
            return discovery
        }
        self.scope = scope; sessionCurrent = current
        invalidateDiscovery()
        let ticket = revision, io = self.io
        let task = Task { @MainActor [weak self] in
            defer { if let self, self.revision == ticket { self.discovery = nil } }
            while let self, !Task.isCancelled {
                guard let beforeRead = self.sessionCurrent, self.valid(scope, revision: ticket, current: beforeRead), self.url == nil else { return }
                self.discoveryNeedsRescan = false
                let result: Result<UnyriShareStore.InboxSnapshot, Error>
                do {
                    let snapshot = try await io.discover(accountKey: scope.accountKey)
                    result = .success(snapshot)
                }
                catch { result = .failure(error) }
                guard !Task.isCancelled, let current = self.sessionCurrent,
                      self.valid(scope, revision: ticket, current: current), self.url == nil else { return }
                if self.discoveryNeedsRescan { continue }
                // Errors do not retry themselves. Only a new discovery event
                // requests another pass, including an unlock after a read failed.
                guard case .success(let snapshot) = result else { return }
                // Publish one completed snapshot only after revalidating the live
                // session/protection gates and draining the pending refresh event.
                self.batchDirectory = snapshot.directory
                self.files = snapshot.files.map { ($0.id, $0.url) }
                self.legacyDirectories = snapshot.legacyDirectories
                self.legacyItemCount = snapshot.legacyItemCount
                self.hasLegacy = self.discoveryOffersLegacy && !snapshot.legacyDirectories.isEmpty
                self.url = snapshot.files.first?.url
                return
            }
        }
        discovery = task
        return task
    }
    func importLegacy(binding: UnyriShareStore.AccountBinding) async throws {
        guard hasLegacy, let scope, scope.accountKey == binding.accountKey,
              let current = sessionCurrent, current() else { throw CancellationError() }
        invalidateDiscovery()
        let ticket = revision, directories = legacyDirectories
        var failure: Error?
        do { try await io.adoptLegacy(directories, binding: binding) }
        catch { failure = error }
        guard valid(scope, revision: ticket, current: current) else { throw CancellationError() }
        close(discardBatch: false)
        if let task = checkSharedInbox(scope: scope, current: current) { await task.value }
        if let failure { throw failure }
    }
    func discardLegacy() async throws {
        guard hasLegacy, let scope, let current = sessionCurrent, current() else { throw CancellationError() }
        invalidateDiscovery()
        let ticket = revision, directories = legacyDirectories
        try await io.discardLegacy(directories)
        guard valid(scope, revision: ticket, current: current) else { throw CancellationError() }
        close(discardBatch: false)
    }
    func acknowledge(_ id: String) async throws {
        guard let directory = batchDirectory else { return }
        guard let scope, let current = sessionCurrent, current() else { throw CancellationError() }
        let ticket = revision
        try await io.acknowledge(id, directory: directory)
        guard valid(scope, revision: ticket, current: current) else { throw CancellationError() }
    }
    func close(discardBatch: Bool = true) {
        invalidateDiscovery()
        if scoped { url?.stopAccessingSecurityScopedResource() }
        // Only extension-owned copies are removed; originals remain untouched.
        if discardBatch, let directory = batchDirectory { io.removeLater(directory) }
        scoped = false; batchDirectory = nil; files = []; url = nil
        hasLegacy = false; legacyItemCount = 0; legacyDirectories = []; scope = nil; sessionCurrent = nil
        discoveryOffersLegacy = false
    }
}

struct UnyriImportFileSheet: View {
    @ObservedObject var inbox: UnyriIncomingFile
    let messages: MessagesRepository
    let chats: ChatsRepository
    let users: UsersRepository
    let accountKey: String?
    @State private var staged: [String: URL] = [:]
    @State private var sent = Set<String>()
    @State private var recoveryError: String?
    @State private var recovering = false
    var body: some View {
        if inbox.hasLegacy { legacyRecovery } else { picker }
    }
    private var legacyRecovery: some View {
        VStack(spacing: 20) {
            Text(inbox.legacyItemCount > 0
                 ? String(format: UnyriL10n.string("Shared items from before the update: %d"), inbox.legacyItemCount)
                 : UnyriL10n.string("Shared items from before the update")).font(.headline)
            Text("Import these items into the current account, or discard them from this device. Nothing is sent until you choose a chat and confirm.")
            if let recoveryError { Text(verbatim: recoveryError).foregroundStyle(UnyriColors.danger) }
            if recovering { ProgressView() }
            Button("Import") {
                guard !recovering, let binding = UnyriShareStore.activeBinding(), binding.accountKey == accountKey else { return }
                recovering = true
                Task {
                    defer { recovering = false }
                    do { try await inbox.importLegacy(binding: binding) }
                    catch is CancellationError { }
                    catch { recoveryError = UnyriL10n.string("Could not recover all shared items. Remaining items have been kept. Try again or discard them.") }
                }
            }
            .disabled(recovering)
            Button("Discard shared items", role: .destructive) {
                guard !recovering, let accountKey, UnyriShareStore.activeBinding()?.accountKey == accountKey else { return }
                recovering = true
                Task {
                    defer { recovering = false }
                    do { try await inbox.discardLegacy() }
                    catch is CancellationError { }
                    catch { recoveryError = UnyriL10n.string("Could not remove shared items. Try again.") }
                }
            }
            .disabled(recovering)
            Button("Close") { inbox.close(discardBatch: false) }
        }
        .padding(24)
    }
    private var picker: some View {
        UnyriChatPickerSheet(
            title: UnyriL10n.string(inbox.files.count > 1 ? "Send files" : "Send file"),
            allowsMultiple: false, allowsComment: false,
            header: AnyView(VStack(alignment: .leading, spacing: 8) {
                ForEach(inbox.files, id: \.id) { file in
                    HStack(spacing: 12) {
                        Image(systemName: UnyriShareStore.isVideo(file.url.lastPathComponent) ? "video.fill" : UnyriShareStore.isImage(file.url.lastPathComponent) ? "photo.fill" : "doc.fill").font(.system(size: 17)).foregroundStyle(.white)
                            .frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.10)))
                            .accessibilityHidden(true)
                        Text(file.url.lastPathComponent).font(.subheadline).lineLimit(2).truncationMode(.middle)
                        Spacer(minLength: 0)
                    }.accessibilityIdentifier("import-file-" + file.id)
                }
                Text("Photos are sent as photos; animated images over 25 MB are sent as files. Videos are compressed before sending.").font(.footnote).foregroundStyle(UnyriColors.secondaryText)
            }.accessibilityIdentifier("import-file-sheet")),
            load: {
                await chats.loadCachedChatsAsync()
            },
            avatarURL: { chat in
                users.cachedAvatarURL(id: chat.otherUser?.id ?? "", avatarUrl: chat.otherUser?.avatarUrl, avatarVersion: chat.otherUser?.avatarVersion ?? 0)
            },
            send: { targets, _ in
                guard let source = inbox.url, let chat = targets.first else { return nil }
                do {
                    let files = inbox.files
                    for file in files where !sent.contains(file.id) {
                        guard inbox.url == source else { throw CancellationError() }
                        if messages.localMessage(id: file.id) == nil {
                            // A shared video goes the gallery way: the app compresses it, so
                            // its source may be up to the video ceiling, not the file limit.
                            let video = UnyriShareStore.isVideo(file.url.lastPathComponent)
                            let copy: URL
                            if let existing = staged[file.id] { copy = existing }
                            else { copy = try await messages.stageDraftFile(file.url, isVideo: video); staged[file.id] = copy }
                            guard inbox.url == source else { throw CancellationError() }
                            if video {
                                let bytes = (try? copy.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                                let saved = MediaGallerySendChoice.saved().intent().kind(isVideo: true)
                                // "As file" applies only while the original fits the file limit.
                                let kind = saved == "file" && bytes > UnyriShareStore.maxFileBytes ? "video" : saved
                                guard await messages.queueVisualMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                                                                      url: copy, kind: kind, caption: nil, albumId: nil) else { throw CocoaError(.fileWriteUnknown) }
                            } else if UnyriShareStore.isImage(file.url.lastPathComponent) {
                                let kind = try await Task.detached(priority: .userInitiated) { try ImageProcessor.shareSendKind(sourceURL: copy) }.value
                                guard inbox.url == source else { throw CancellationError() }
                                if kind == "image" {
                                    guard await messages.queueVisualMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                                                                          url: copy, kind: kind, caption: nil, albumId: nil) else { throw CocoaError(.fileWriteUnknown) }
                                } else {
                                    guard messages.queueMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                                                              url: copy, kind: "file", caption: nil) else { throw CocoaError(.fileWriteUnknown) }
                                }
                            } else {
                                guard messages.queueMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id, url: copy, kind: "file", caption: nil) else { throw CocoaError(.fileWriteUnknown) }
                            }
                        }
                        guard inbox.url == source else { throw CancellationError() }
                        try await inbox.acknowledge(file.id)
                        sent.insert(file.id)
                    }
                    if inbox.url == source { inbox.close() }
                } catch { return UnyriErrorText.message(error) ?? UnyriL10n.string("Something went wrong. Try again.") }
                return nil
            },
            cancel: { UnyriHaptics.press(); inbox.close() })
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
    /// nil: 10 pt, or square corners when fitted. A bubble that clips itself passes 0.
    var cornerRadius: CGFloat? = nil
    var onPreview: (UIImage) -> Void = { _ in }
    @Environment(\.scenePhase) private var scenePhase
    @State private var duration: Double = 0
    var body: some View {
        UnyriAttachmentImage(request: .file(url, video: true, pixelEdge: compact ? 312 : 768),
            workAllowed: workAllowed, viewerSurface: aspectFit, initial: initialPreview) { image in
            ZStack(alignment: .bottomTrailing) {
                if let image { Image(uiImage: image).resizable().aspectRatio(contentMode: aspectFit ? .fit : .fill) }
                else { Color.black.opacity(0.2) }
                if showsPlayIcon { Image(systemName: "play.circle.fill").font(.title).foregroundColor(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
                if showsDuration && duration.isFinite && duration > 0 { Text(String(format: "%d:%02d", Int(duration) / 60, Int(duration) % 60)).font(.caption.monospacedDigit()).padding(4).background(.black.opacity(0.6)).foregroundColor(.white) }
            }.frame(width: compact ? 104 : nil, height: compact ? 78 : nil).clipped()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius ?? (aspectFit ? 0 : 10)))
                .onChange(of: image) { value in if let value { onPreview(value) } }
        }
        .task(id: "\(url.path)|\(showsDuration)|\(workAllowed)|\(scenePhase)") {
            guard workAllowed, showsDuration, scenePhase == .active else { return }
            let seconds = try? await AVURLAsset(url: url).load(.duration).seconds
            if !Task.isCancelled { duration = seconds ?? 0 }
        }
    }
}

/// Copies one already-decoded player frame for posters/transitions. There is no
/// display link or continuing video output after capture, and no second decoder.
struct UnyriPlayerPosterCapture: View {
    let id: String
    let url: URL
    let player: AVPlayer?
    let ready: Bool
    let active: Bool
    @StateObject private var capture = PlayerPosterCapture()
    @State private var cacheRevision: UInt64 = 0
    private struct Request: Hashable {
        let id: String
        let item: ObjectIdentifier?
        let ready: Bool
        let active: Bool
        let cacheRevision: UInt64
    }
    var body: some View {
        Color.clear.allowsHitTesting(false)
            .task(id: Request(id: id, item: player.flatMap { $0.currentItem }.map { ObjectIdentifier($0) }, ready: ready, active: active, cacheRevision: cacheRevision)) {
                await capture.follow(id: id, url: url, player: player, ready: ready, active: active)
            }
            .onReceive(UnyriAttachmentImages.changes) { change in
                switch change {
                case .cacheCleared: capture.stop(); cacheRevision &+= 1
                case .reset: capture.stop()
                default: break
                }
            }
            .onDisappear { capture.stop() }
    }
}

@MainActor private final class PlayerPosterCapture: ObservableObject {
    private weak var item: AVPlayerItem?
    private var output: AVPlayerItemVideoOutput?
    private var finished = false
    func follow(id: String, url: URL, player: AVPlayer?, ready: Bool, active: Bool) async {
        guard active, let player, let current = player.currentItem else { stop(); return }
        if item !== current {
            stop(); item = current
            let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
            current.add(output); self.output = output
        }
        guard ready, !finished, let output else { return }
        let generation = ConversationImages.generation
        for delay: UInt64 in [0, 80_000_000, 240_000_000, 600_000_000] {
            if delay > 0 { do { try await Task.sleep(nanoseconds: delay) } catch { return } }
            guard !Task.isCancelled, item === current, generation == ConversationImages.generation else { return }
            guard let buffer = output.copyPixelBuffer(forItemTime: player.currentTime(), itemTimeForDisplay: nil) else { continue }
            let image = await Task.detached(priority: .utility) { () -> UIImage? in
                let track = try? await current.asset.loadTracks(withMediaType: .video).first
                let transform = (try? await track?.load(.preferredTransform)) ?? .identity
                var image = CIImage(cvPixelBuffer: buffer).transformed(by: transform)
                let extent = image.extent
                guard extent.width > 0, extent.height > 0 else { return nil }
                image = image.transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
                let scale = min(1, 768 / max(extent.width, extent.height))
                image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                let context = CIContext(options: [.cacheIntermediates: false])
                guard let frame = context.createCGImage(image, from: image.extent) else { return nil }
                return UIImage(cgImage: frame)
            }.value
            guard !Task.isCancelled, item === current, generation == ConversationImages.generation, let image else { return }
            UnyriAttachmentImages.remember(AttachmentImageRequest(id: id, url: url, mime: "video/mp4"))
            ConversationImages.cacheVideoFrame(image, path: url.path, pixelEdge: 768, generation: generation)
            UnyriAttachmentImages.publish(image, id: id, origin: .viewerFrame)
            current.remove(output); self.output = nil; finished = true
            return
        }
        // Unsupported outputs still stop after this finite capture window.
        current.remove(output); self.output = nil; finished = true
    }
    func stop() {
        if let item, let output { item.remove(output) }
        output = nil; item = nil; finished = false
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
        let changed: Bool
        if enabled { changed = viewers[key, default: []].insert(token).inserted }
        else {
            changed = viewers[key]?.remove(token) != nil
            if viewers[key]?.isEmpty == true { viewers.removeValue(forKey: key) }
        }
        // A newly presented cover can have cached portrait while another
        // viewer already owns the same mask. Refresh for every owner change,
        // even when the union of allowed orientations did not change.
        guard changed else { return }
        var controllers = scene.windows.compactMap(\.rootViewController)
        var visited = Set<ObjectIdentifier>()
        while let controller = controllers.popLast() {
            guard visited.insert(ObjectIdentifier(controller)).inserted else { continue }
            controller.setNeedsUpdateOfSupportedInterfaceOrientations()
            controllers.append(contentsOf: controller.children)
            if let presented = controller.presentedViewController { controllers.append(presented) }
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask(for: scene))) { _ in
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

/// A muted average colour of an image (8×8 thumbnail), for the viewer's ambient glow.
enum UnyriAmbientColor {
    static func average(url: URL?, mime: String, thumbnailBase64: String?) -> UIColor? {
        var source: CGImageSource?
        if let url, url.isFileURL, mime.hasPrefix("image/") {
            source = PeerImagePolicy.source(url: url)
        } else if let thumbnailBase64, let data = Data(base64Encoded: thumbnailBase64) {
            source = PeerImagePolicy.source(data: data, jpegOnly: true)
        }
        guard let source,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 8] as CFDictionary) else { return nil }
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
        UIColor(red: CGFloat(pixel[0]) / 255, green: CGFloat(pixel[1]) / 255, blue: CGFloat(pixel[2]) / 255, alpha: 1)
            .getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
        // Keep it a glow, never a wash: cap saturation and brightness.
        return UIColor(hue: hue, saturation: min(saturation, 0.75), brightness: min(max(brightness, 0.35), 0.8), alpha: 1)
    }
}


enum UnyriLiveText {
    static var supported: Bool {
        if #available(iOS 16.0, *) { return ImageAnalyzer.isSupported }
        return false
    }
}

/// The decoded photo handed to the editor (≤ 2560 px, orientation applied).
struct UnyriEditorImage: Identifiable {
    let id = UUID()
    let image: UIImage
    static func load(_ url: URL) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let source = PeerImagePolicy.source(url: url),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 2560] as CFDictionary) else { return nil }
            return UIImage(cgImage: image)
        }.value
    }
    static func write(_ image: UIImage) -> URL? {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("unyri-edited-" + UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent("Photo.jpg")
        guard let data = image.jpegData(compressionQuality: 0.92), (try? data.write(to: url, options: .atomic)) != nil else { return nil }
        return url
    }
}

/// System share sheet from anywhere in the viewer (it is presented over UIKit).
enum UnyriShareSheet {
    @MainActor static func present(_ url: URL) { present(items: [url]) }
    @MainActor static func present(items: [Any]) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var top = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let next = top.presentedViewController { top = next }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 80, width: 1, height: 1)
        top.present(sheet, animated: true)
    }
}
