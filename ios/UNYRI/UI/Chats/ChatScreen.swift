import SwiftUI
import QuickLook
import PhotosUI
import Photos
import UIKit
import UniformTypeIdentifiers
import AVFoundation
import AVKit
import Combine
import ImageIO
import UserNotifications
import CoreTransferable

/// Selection follows the filled message body. Receipts and reactions remain
/// outside this anchor and never pull its marker down or grow a short row.
private enum UnyriMessageBodyCenter: AlignmentID {
    static func defaultValue(in context: ViewDimensions) -> CGFloat { context[VerticalAlignment.center] }
}
private extension VerticalAlignment {
    static let unyriMessageBodyCenter = VerticalAlignment(UnyriMessageBodyCenter.self)
}

/// REST is a fallback for a visible foreground chat while the socket is down.
/// Reconnect already runs the account-wide cursor catch-up in AccountServices.
@MainActor
private enum ChatHistoryFallback {
    static func run(active: Bool, visible: Bool, connected: Bool,
                    wait: () async throws -> Void = { try await Task.sleep(nanoseconds: 3_000_000_000) },
                    refresh: () async -> Void) async {
        guard active, visible, !connected else { return }
        while !Task.isCancelled {
            do { try await wait() } catch { return }
            guard !Task.isCancelled else { return }
            await refresh()
        }
    }
}

/// Photos owns the received URL only during this callback. Copy it in bounded
/// chunks before returning; movie bytes never travel through a Data transfer.
private struct ImportedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let destination = FileManager.default.temporaryDirectory.appendingPathComponent("coco-video-" + UUID().uuidString + "." + ext)
            try AttachmentFileCopy.copy(from: received.file, to: destination, maxBytes: AttachmentFileCopy.maxVideoSourceBytes)
            return ImportedMovie(url: destination)
        }
    }
}

/// Keep oversized photos/GIFs out of a single in-memory Data allocation, too.
private struct ImportedPhoto: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            let ext = received.file.pathExtension
            let destination = FileManager.default.temporaryDirectory.appendingPathComponent(
                "coco-photo-" + UUID().uuidString + (ext.isEmpty ? "" : "." + ext))
            try AttachmentFileCopy.copy(from: received.file, to: destination, maxBytes: AttachmentFormat.maxImageBytes)
            return ImportedPhoto(url: destination)
        }
    }
}

/// Native initial anchoring avoids first displaying the oldest loaded rows.


private struct ChatInitialScrollPosition: ViewModifier {
    @State private var followsLatest: Bool
    let followsContainerChanges: Bool
    init(initialID: String?, followsLatest: Bool, followsContainerChanges: Bool) {
        _followsLatest = State(initialValue: followsLatest)
        self.followsContainerChanges = followsContainerChanges
    }
    @ViewBuilder func body(content: Content) -> some View {
        // ScrollViewReader owns explicit jumps. A second scrollPosition binding
        // used to continually re-anchor rows while their estimated heights changed.
        if #available(iOS 18.0, *) {
            content.scrollClipDisabled()
                .defaultScrollAnchor(followsLatest ? .bottom : .top, for: .initialOffset)
                .defaultScrollAnchor(.bottom, for: .alignment)
                .defaultScrollAnchor(followsContainerChanges ? .bottom : nil, for: .sizeChanges)
        } else if #available(iOS 17.0, *) {
            content.scrollClipDisabled().defaultScrollAnchor(followsLatest ? .bottom : .top)
        } else { content }
    }
}

private struct ChatReaderRestorationAnchor {
    let row: String
    let id: String
    let originY: Double
}
/// The one-point target is overlaid inside one bookmarked row. Its marker
/// takes no timeline space, accepts no touches and has no accessibility node.
private struct ChatReaderOffsetAnchor: ViewModifier {
    let row: String
    let target: ChatReaderRestorationAnchor?
    func body(content: Content) -> some View {
        content.overlay(alignment: .topLeading) {
            if let target, target.row == row {
                VStack(spacing: 0) {
                    Color.clear.frame(height: max(0, -CGFloat(target.originY)))
                    Color.clear.frame(width: 1, height: 1).id(target.id)
                }
                .frame(width: 1, alignment: .leading)
                .allowsHitTesting(false).accessibilityHidden(true)
            }
        }
    }
}

private struct ChatKeyboardScrollInteraction: ViewModifier {
    let began: () -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .tracking || phase == .interacting { began() }
            }
        } else { content }
    }
}

// Only pending upload controls observe progress; completed/text rows do not.
private struct PendingMediaLookupKey: EnvironmentKey {
    static let defaultValue: (String, String) -> QueuedMediaPreview? = { _, _ in nil }
}
private extension EnvironmentValues {
    var pendingMediaLookup: (String, String) -> QueuedMediaPreview? {
        get { self[PendingMediaLookupKey.self] }
        set { self[PendingMediaLookupKey.self] = newValue }
    }
}
private typealias PendingMediaPreview = QueuedMediaPreview

private struct QueuedVisualMediaView: View {
    let preview: PendingMediaPreview
    let messageID: String
    @EnvironmentObject private var attachments: AttachmentManager
    @Environment(\.chatMediaWidth) private var cellWidth
    @Environment(\.chatMediaHeight) private var cellHeight
    @Environment(\.scenePhase) private var phase
    @Environment(\.displayScale) private var displayScale
    @State private var mediaVisible = false
    @State private var image: UIImage?
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    private var previewPixelEdge: Int {
        let visual = preview.visual ?? QueuedMediaVisual(width: 1, height: 1, durationMs: 0)
        let size = unyriChatVisualSize(width: visual.width, height: visual.height, cellWidth: cellWidth, cellHeight: cellHeight)
        let pixels = max(size.width, size.height) * displayScale
        return pixels <= 384 ? 384 : pixels <= 512 ? 512 : 768
    }
    var body: some View {
        let visual = preview.visual ?? QueuedMediaVisual(width: 1, height: 1, durationMs: 0)
        let size = unyriChatVisualSize(width: visual.width, height: visual.height, cellWidth: cellWidth, cellHeight: cellHeight)
        ZStack {
            UnyriColors.deepPanel
            if preview.kind.hasPrefix("video") {
                QueuedVideoPreview(url: preview.url, visible: mediaVisible && !isMenuPreview && phase == .active,
                    meter: attachments.sendMeter(messageID))
                    .overlay(alignment: .topLeading) {
                        Text(VoiceFormat.durationText(preview.duration)).font(.caption.monospacedDigit())
                            .padding(4).background(.black.opacity(0.6)).foregroundColor(.white)
                    }
            } else if let image = image ?? ConversationImages.cached(path: preview.url.path, thumbnail: nil, pixelEdge: previewPixelEdge) {
                Image(uiImage: image).resizable().aspectRatio(contentMode: cellWidth == nil ? .fit : .fill)
            }
        }
        .frame(width: size.width, height: size.height).clipped()
        .modifier(UnyriMediaVisibility { mediaVisible = $0 })
        .task(id: "\(preview.url.path)|\(mediaVisible)|\(phase)|\(previewPixelEdge)") {
            guard mediaVisible, phase == .active, !isMenuPreview, !preview.kind.hasPrefix("video") else { return }
            let loaded = await ConversationImages.load(path: preview.url.path, thumbnail: nil, pixelEdge: previewPixelEdge)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}
private struct QueuedVideoPreview: View {
    let url: URL
    let visible: Bool
    @ObservedObject var meter: AttachmentSendMeter
    var body: some View {
        // Do not decode the source video again beside the active encoder.
        // The completed attachment supplies its compressed poster afterward.
        UnyriVideoThumbnail(url: url, compact: false, showsDuration: false,
            workAllowed: visible && (meter.stage == .uploading || meter.stage == .sent), showsPlayIcon: false)
    }
}
private struct VoicePhaseReveal: ViewModifier {
    let phase: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var opacity = 1.0
    func body(content: Content) -> some View {
        content.opacity(opacity).task(id: phase) {
            guard !reduceMotion else { opacity = 1; return }
            opacity = 0.9
            withAnimation(.easeOut(duration: 0.14)) { opacity = 1 }
        }
    }
}

private struct AudioEnvelopeView: View {
    let path: String?
    let fraction: Double
    let played: Color
    let unplayed: Color
    @State private var samples: [Double] = []
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    var body: some View {
        Canvas { context, size in
            let count = max(1, min(48, Int(size.width / 4)))
            let step = size.width / CGFloat(count)
            for i in 0..<count {
                let sample = samples.isEmpty ? 0 : samples[min(samples.count - 1, i * samples.count / count)]
                let height = 2 + sample * max(0, size.height - 2)
                let x = step * (CGFloat(i) + 0.5)
                var line = Path(); line.move(to: CGPoint(x: x, y: (size.height - height) / 2)); line.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(line, with: .color(Double(i) < fraction * Double(count) ? played : unplayed), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }.task(id: path) {
            guard let path else { samples = []; return }
            let result: [Double]
            if isMenuPreview { result = await VoiceWaveformCache.shared.cached(path: path) }
            else { result = await VoiceWaveformCache.shared.load(path: path) }
            guard !Task.isCancelled else { return }
            samples = result
        }
    }
}

private struct QueuedVoiceFileView: View {
    let preview: PendingMediaPreview
    let id: String
    let cancel: () -> Void
    @EnvironmentObject private var player: VoicePlayerController
    private var active: Bool { player.state.playingAttachmentId == id || player.state.pausedAttachmentId == id }
    var body: some View {
        if active { VoiceProgressView(progress: player.progress) { position in row(position: position) } }
        else { row(position: 0) }
    }
    private func row(position: Int) -> some View {
        UnyriVoiceMessageContent(duration: preview.duration, position: position, active: active,
            playing: player.state.playingAttachmentId == id, downloading: false, available: true, speed: player.state.speed,
            waveform: AnyView(AudioEnvelopeView(path: preview.url.path,
                fraction: VoiceFormat.playedFraction(positionMs: position, durationMs: preview.duration),
                played: UnyriSavedChatStyle.accent, unplayed: UnyriSavedChatStyle.waveQuiet)),
            action: { UnyriHaptics.press(); player.togglePlay(attachmentId: id, fileURL: preview.url) },
            cycleSpeed: { UnyriHaptics.selection(); player.cycleSpeed() }, transferControl: AnyView(UnyriVoiceTransferControl(id: id, cancel: cancel)))
    }
}

private struct QueuedFileCard: View {
    let preview: PendingMediaPreview
    let id: String
    let failed: Bool
    let cancel: () -> Void
    var metadata: AnyView? = nil
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var attachments: AttachmentManager
    var body: some View {
        QueuedFileCardStatus(preview: preview, id: id, failed: failed, cancel: cancel,
            connected: realtime.state == .connected, meter: attachments.sendMeter(id), metadata: metadata)
    }
}

private struct QueuedFileCardStatus: View {
    let preview: PendingMediaPreview
    let id: String
    let failed: Bool
    let cancel: () -> Void
    let connected: Bool
    @ObservedObject var meter: AttachmentSendMeter
    var metadata: AnyView? = nil
    @Environment(\.unyriChatAppearance) private var appearance
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var percent: Int? { meter.percent }
    private var waiting: Bool { !connected && !meter.stage.isLocalPreparation }
    private var statusLabel: String {
        if failed { return UnyriL10n.string("Failed to send") }
        if waiting { return UnyriL10n.string("Waiting for connection") }
        if let percent { return "\(percent)% · " + UnyriL10n.string("Sending…") }
        return UnyriL10n.string(meter.stage.label)
    }
    var body: some View {
        UnyriFileMessageContent(filename: preview.url.lastPathComponent, details: statusLabel, metadata: metadata,
            icon: AnyView(Button { UnyriHaptics.selection(); cancel() } label: {
                ZStack {
                    Circle().fill(UnyriSavedChatStyle.entry)
                    Circle().stroke(UnyriSavedChatStyle.muted.opacity(0.3), lineWidth: 2)
                    Circle().trim(from: 0, to: waiting || failed ? 0 : CGFloat(percent ?? 0) / 100)
                        .stroke(UnyriSavedChatStyle.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(reduceMotion ? nil : .linear(duration: 0.16), value: percent)
                    Image(systemName: "xmark").font(.system(size: 13, weight: .medium)).foregroundStyle(UnyriSavedChatStyle.accent)
                }.frame(width: 31, height: 31).frame(width: 44, height: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Cancel sending")))
    }
}

private struct MessageUploadProgress: View {
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var attachments: AttachmentManager
    let clientMessageId: String
    let compact: Bool
    let cancel: () -> Void

    var body: some View {
        MessageUploadStatus(meter: attachments.sendMeter(clientMessageId), connected: realtime.state == .connected,
            compact: compact, cancel: cancel, readSize: { attachments.pendingUploadSize(clientMessageId) })
    }
}

private struct MessageUploadStatus: View {
    @ObservedObject var meter: AttachmentSendMeter
    let connected: Bool
    let compact: Bool
    let cancel: () -> Void
    let readSize: () -> Int64?
    @State private var size: Int64?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var progressAccessibilityValue: String {
        if !connected && !meter.stage.isLocalPreparation { return UnyriL10n.string("Waiting for connection") }
        if let percent = meter.percent { return "\(percent)%" }
        return UnyriL10n.string(meter.stage.label)
    }
    var body: some View {
        Group {
            if compact {
                Button { UnyriHaptics.selection(); cancel() } label: {
                    ZStack {
                        Circle().fill(.black.opacity(0.48))
                        Circle().stroke(.white.opacity(0.24), lineWidth: 2.5)
                        if let percent = meter.percent {
                            Circle().trim(from: 0, to: CGFloat(percent) / 100)
                                .stroke(ChatControlPalette.blue, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                                .animation(reduceMotion ? nil : .linear(duration: 0.16), value: percent)
                        }
                        // The ring owns progress; keep the Cancel glyph clear
                        // during preparation instead of stacking a spinner under it.
                        Image(systemName: "xmark").font(.system(size: 16, weight: .medium)).foregroundStyle(.white)
                    }.frame(width: 40, height: 40).frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel("Cancel sending")
                    .accessibilityValue(Text(progressAccessibilityValue))
            } else if !connected && !meter.stage.isLocalPreparation {
                HStack(spacing: 6) {
                    Label("Waiting for connection", systemImage: "clock").font(.caption)
                    Button { UnyriHaptics.selection(); cancel() } label: { Image(systemName: "xmark").frame(width: 40, height: 40) }.accessibilityLabel("Cancel sending")
                }.foregroundColor(UnyriColors.secondaryText).padding(.horizontal, 10)
            } else if let percent = meter.percent {
                HStack(spacing: 4) {
                    ProgressView(value: Double(percent), total: 100).frame(width: compact ? 24 : 90)
                    Text("\(percent)%").font(.caption.monospacedDigit())
                    if !compact, let size { Text(AttachmentFormat.fileSize(size)).font(.caption2) }
                    Button { UnyriHaptics.selection(); cancel() } label: { Image(systemName: "xmark.circle") }
                        .accessibilityLabel(Text("Cancel"))
                }.padding(.horizontal, 10)
            } else {
                HStack(spacing: 6) {
                    if compact {
                        if meter.stage.isLocalPreparation { ProgressView().controlSize(.mini).accessibilityLabel(Text(LocalizedStringKey(meter.stage.label))) }
                        else { Image(systemName: meter.stage == .queued ? "clock" : "arrow.up.circle").accessibilityLabel(Text(LocalizedStringKey(meter.stage.label))) }
                    } else {
                        Image(systemName: meter.stage == .queued ? "clock" : "arrow.up.circle")
                        Text(LocalizedStringKey(meter.stage.label)).font(.caption)
                    }
                    Button { UnyriHaptics.selection(); cancel() } label: {
                        Image(systemName: "xmark").frame(width: 40, height: 40)
                    }.accessibilityLabel("Cancel sending")
                }.padding(.horizontal, 10)
            }
        }.task(id: meter.stage) {
            guard !compact else { return }
            size = readSize()
        }
    }
}

private struct VoiceRecordingMeter: View {
    let recorder: VoiceRecorder
    @State private var levels = Array(repeating: 0.0, count: 12)
    var body: some View {
        Canvas { context, size in
            for (index, level) in levels.enumerated() {
                let height = max(2, size.height * sqrt(max(0, min(1, level))))
                let x = (Double(index) + 0.5) * size.width / Double(levels.count)
                var line = Path(); line.move(to: CGPoint(x: x, y: (size.height - height) / 2)); line.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(line, with: .color(ChatControlPalette.blue), lineWidth: 2)
            }
        }.task {
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 100_000_000) } catch { break }
                levels = Array(levels.dropFirst()) + [recorder.level()]
            }
        }
    }
}

private struct VoiceProgressView<Content: View>: View {
    @ObservedObject var progress: VoicePlaybackProgress
    let content: (Int) -> Content
    var body: some View { content(progress.positionMs) }
}



private struct FileAttachmentDraft: Identifiable, Codable {
    let id: String
    let url: URL
    let size: Int64
}

private struct FileAttachmentPreview: View {
    let draft: FileAttachmentDraft
    let enqueue: () async -> Bool
    let onClose: () -> Void
    @State private var uploaded: AttachmentMetadata?
    @State private var sending = false
    @State private var error: String?
    @State private var previewURL: URL?
    var body: some View {
        NavigationStack {
            ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "doc.text.fill").font(.system(size: 36)).foregroundStyle(ChatControlPalette.amber).frame(width: 76, height: 76).background(ChatControlPalette.amberBack, in: RoundedRectangle(cornerRadius: 22))
                Text(draft.url.lastPathComponent).font(.headline).multilineTextAlignment(.center).lineLimit(3)
                Text(FileSizeFormatter.format(draft.size)).font(.subheadline).foregroundColor(.secondary)
                Button { UnyriHaptics.press(); previewURL = draft.url } label: { Label("Preview", systemImage: "eye").font(.subheadline.weight(.semibold)).foregroundStyle(ChatControlPalette.blue).frame(maxWidth: .infinity, minHeight: 44).background(ChatControlPalette.blueBack, in: Capsule()) }.buttonStyle(.plain).disabled(sending).opacity(sending ? 0.45 : 1)
                    .quickLookPreview($previewURL)
        .background(UnyriMediaRotation(active: previewURL != nil))
                if let error { Text(error).font(.footnote).foregroundColor(UnyriColors.danger).multilineTextAlignment(.center) }
                if sending { ProgressView("Sending…") }
            }.padding(24)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    UnyriHaptics.press()
                    sending = true; error = nil
                    Task {
                        defer { sending = false }
                        guard await enqueue() else { error = "Could not save the file message. Try again."; return }
                        onClose()
                    }
                } label: { Label(LocalizedStringKey(error == nil ? "Send file" : "Retry"), systemImage: error == nil ? "paperplane.fill" : "arrow.clockwise").font(.subheadline.weight(.semibold)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 48).background(ChatControlPalette.violet, in: Capsule()) }
                .buttonStyle(.plain).disabled(sending).opacity(sending ? 0.45 : 1)
                .padding(.horizontal, 24).padding(.vertical, 10)
                .background(UnyriColors.deepPanel)
            }
            .unyriScreenBackground().navigationTitle("File").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onClose).tint(ChatControlPalette.coral).disabled(sending).opacity(sending ? 0.45 : 1) } }
        }.interactiveDismissDisabled(sending)
    }
}

private enum CameraCaptureSource {
    case image(UIImage)
    case movie(URL)
}

private struct CameraPhotoPicker: UIViewControllerRepresentable {
    let captureClaim: NonCallCaptureOwnership.Claim?
    let onCapture: (CameraCaptureSource, String) async throws -> Void
    let onClose: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(claim: captureClaim, onCapture: onCapture, onClose: onClose) }
    func makeUIViewController(context: Context) -> UIViewController {
        let picker = UIImagePickerController()
        guard let lease = context.coordinator.lease,
              lease.attach(picker, interrupted: { context.coordinator.task?.cancel(); onClose() }) else {
            DispatchQueue.main.async { onClose() }; return UIViewController()
        }
        picker.sourceType = .camera
        let available = UIImagePickerController.availableMediaTypes(for: .camera) ?? [UTType.image.identifier]
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier].filter { available.contains($0) }
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.task?.cancel()
        coordinator.lease?.close()
    }
    @MainActor final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onCapture: (CameraCaptureSource, String) async throws -> Void
        let onClose: () -> Void
        var task: Task<Void, Never>?
        let lease: NativeCameraPickerLease?
        init(claim: NonCallCaptureOwnership.Claim?, onCapture: @escaping (CameraCaptureSource, String) async throws -> Void, onClose: @escaping () -> Void) {
            self.onCapture = onCapture; self.onClose = onClose
            lease = claim.map(NativeCameraPickerLease.init)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { task?.cancel(); onClose() }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard lease?.isCurrent == true else { return }
            let source: CameraCaptureSource
            if let url = info[.mediaURL] as? URL { source = .movie(url) }
            else if let image = info[.originalImage] as? UIImage { source = .image(image) }
            else { onClose(); return }
            stage(source, id: UUID().uuidString.lowercased(), picker: picker)
        }
        private func stage(_ source: CameraCaptureSource, id: String, picker: UIImagePickerController) {
            guard task == nil, lease?.isCurrent == true else { return }
            picker.view.isUserInteractionEnabled = false
            let spinner = UIActivityIndicatorView(style: .large)
            spinner.center = picker.view.center; spinner.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            spinner.startAnimating(); picker.view.addSubview(spinner)
            task = Task { @MainActor [weak self, weak picker] in
                guard let self, let picker else { return }
                defer { spinner.removeFromSuperview(); picker.view.isUserInteractionEnabled = true; self.task = nil }
                do {
                    // Keep the picker (and its temporary movie) alive until staging completes.
                    try await self.onCapture(source, id)
                    guard !Task.isCancelled else { return }
                    self.onClose()
                } catch is CancellationError { }
                catch {
                    guard !Task.isCancelled else { return }
                    let alert = UIAlertController(title: UnyriL10n.string("Could not prepare attachment"), message: error.localizedDescription, preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: UnyriL10n.string("Retry"), style: .default) { [weak self, weak picker] _ in
                        guard let self, let picker else { return }; self.stage(source, id: id, picker: picker)
                    })
                    alert.addAction(UIAlertAction(title: UnyriL10n.string("Cancel"), style: .cancel) { [weak self] _ in self?.onClose() })
                    picker.present(alert, animated: true)
                }
            }
        }
    }
}

/// Direct-chat screen: chronological message list + composer. REST-only for
/// this stage (no WebSocket, no receipts).
private struct ChatComposerAttachment: Identifiable {
    enum Source { case gallery(UnyriGallerySource), photo(PhotosPickerItem), file(StoredMediaDraft) }
    let id: String
    let source: Source
    let kind: String
    var ownedURL: URL? { if case .file(let file) = source { return file.url }; return nil }
    var directOriginal: DirectComposerOriginal.Source? {
        guard kind == "file" else { return nil }
        switch source {
        case .gallery(.asset(let asset)): return asset.mediaType == .video ? .asset(asset) : nil
        case .photo(let item): return item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) ? .picker(item) : nil
        case .file(let item): return item.isVideo ? .file(item.url) : nil
        default: return nil
        }
    }
}

private struct ChatComposerThumbnail: View {
    let item: ChatComposerAttachment
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @State private var preview: UIImage?
    var body: some View {
        ZStack {
            appColorStyle.surface(ChatControlPalette.blueBack)
            switch item.source {
            case .gallery(let source):
                switch source {
                case .asset(let asset): UnyriGalleryThumbnail(asset: asset)
                case .capture(let url): UnyriCaptureThumbnail(url: url)
                }
            case .file(let file):
                HStack(spacing: 8) {
                    Image(systemName: "doc.text").font(.system(size: 24)).foregroundStyle(appColorStyle.decorative(ChatControlPalette.blue))
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(file.url.lastPathComponent).font(.caption).lineLimit(2)
                            .foregroundStyle(UnyriSavedChatStyle.bottomInk)
                        Text(ByteCountFormatter.string(fromByteCount: Int64(file.bytes), countStyle: .file))
                            .font(.caption2).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.padding(.leading, 10).padding(.trailing, 40).padding(.vertical, 8)
            case .photo:
                if let preview { Image(uiImage: preview).resizable().scaledToFill() }
                else { Image(systemName: item.kind.hasPrefix("video") ? "video" : "photo").foregroundStyle(appColorStyle.decorative(ChatControlPalette.blue)) }
            }
        }.clipped().task(id: item.id) {
            guard case .photo(let photo) = item.source, let id = photo.itemIdentifier,
                  let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return }
            let options = PHImageRequestOptions(); options.deliveryMode = .fastFormat; options.resizeMode = .fast
            // Do not download a full iCloud original just to draw a 76-point tile.
            options.isNetworkAccessAllowed = false
            let image = await withCheckedContinuation { (continuation: CheckedContinuation<UIImage?, Never>) in
                PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 240, height: 240), contentMode: .aspectFill, options: options) { image, _ in
                    continuation.resume(returning: image)
                }
            }
            if !Task.isCancelled { preview = image }
        }
    }
}

// Nominal View boundaries keep the composer metadata shallow. The device crash
// in build 20261030 exhausted the stack while Swift decoded the inline field's
// deeply nested generic type, before the conversation could finish opening.
private struct ChatComposerTextEntry: View {
    @Binding var draft: String
    var focused: FocusState<Bool>.Binding
    let textScale: CGFloat
    let importing: Bool
    let attachmentDisabled: Bool
    let cameraDisabled: Bool
    let camera: () -> Void
    let photos: () -> Void
    let files: () -> Void
    let emoji: () -> Void
    @State private var showAttachmentPopover = false

    var body: some View {
        HStack(spacing: 0) {
            Button {
                UnyriHaptics.press()
                showAttachmentPopover = true
            } label: {
                Image(systemName: "plus").font(.system(size: 20, weight: .regular))
                    .foregroundStyle(UnyriSavedChatStyle.bottomInk).frame(width: 44, height: max(44, UnyriSavedChatStyle.entryHeight)).contentShape(Rectangle())
            }
            .disabled(attachmentDisabled).accessibilityLabel("Attach photo or file")
            .accessibilityIdentifier("chat-composer-attach")
            .background(UnyriAttachmentPopover(isPresented: $showAttachmentPopover,
                cameraDisabled: cameraDisabled, camera: camera, photos: photos, files: files)
                .allowsHitTesting(false))
            if importing { ProgressView().controlSize(.small) }
            TextField("Message", text: $draft, prompt: Text("Message").foregroundColor(UnyriColors.secondaryText), axis: .vertical)
                .font(.system(size: UnyriSavedChatStyle.entryFont * textScale))
                .focused(focused).lineLimit(1...4).textFieldStyle(.plain)
                .foregroundColor(UnyriSavedChatStyle.bottomInk).padding(.vertical, 8).accessibilityIdentifier("chat-composer-text")
            Button(action: emoji) {
                Image(systemName: "face.smiling").font(.system(size: 20, weight: .regular))
                    .foregroundStyle(UnyriSavedChatStyle.bottomInk).frame(width: 44, height: max(44, UnyriSavedChatStyle.entryHeight)).contentShape(Rectangle())
            }.accessibilityLabel("Emoji")
        }.padding(.horizontal, 2).frame(minHeight: max(44, UnyriSavedChatStyle.entryHeight))
    }
}

private struct ChatComposerSubmitControl: View {
    let showsRecorder: Bool
    let microphoneInUse: Bool
    let canSend: Bool
    let editing: Bool
    let attachmentCount: Int
    let record: () -> Void
    let send: () -> Void

    var body: some View {
        Group {
            if showsRecorder {
                Button(action: record) {
                    Image(systemName: "mic").font(.system(size: 20, weight: .regular))
                        .foregroundColor(UnyriSavedChatStyle.bottomInk)
                        .frame(width: max(44, UnyriSavedChatStyle.entryHeight), height: max(44, UnyriSavedChatStyle.entryHeight)).contentShape(Rectangle())
                }
                .opacity(microphoneInUse ? 0.4 : 1).disabled(microphoneInUse)
                .accessibilityLabel(microphoneInUse ? "Microphone is in use during the call" : "Record voice message")
            } else {
                HStack(alignment: .center, spacing: 4) {
                    if attachmentCount > 0 {
                        // Keep the intrinsic count beside the 44-point send
                        // target, without adding height or covering its glyph.
                        Text("\(attachmentCount)").font(.caption2.weight(.semibold)).monospacedDigit()
                            .foregroundStyle(UnyriSavedChatStyle.bottomInk)
                            .fixedSize(horizontal: true, vertical: true)
                            .allowsHitTesting(false)
                            .accessibilityIdentifier("chat-composer-attachment-count")
                    }
                    Button(action: send) {
                        Image(systemName: editing ? "checkmark" : "arrow.up")
                            .font(.system(size: UnyriSavedChatStyle.iconSize, weight: .semibold)).foregroundColor(UnyriSavedChatStyle.micInk)
                            .frame(width: max(36, UnyriSavedChatStyle.entryHeight - 4), height: max(36, UnyriSavedChatStyle.entryHeight - 4)).background(UnyriSavedChatStyle.micColor, in: Circle())
                            .opacity(canSend ? 1 : 0.4).frame(width: 44, height: 44).contentShape(Rectangle())
                    }.disabled(!canSend).accessibilityLabel(editing ? "Save edit" : "Send message").accessibilityIdentifier("chat-composer-send")
                }
            }
        }.frame(minHeight: UnyriSavedChatStyle.entryHeight)
    }
}

struct ChatScreen: View {
    @ObservedObject private var appLock = UnyriAppLock.shared
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @Environment(\.accessibilityReduceMotion) private var composerReduceMotion
    @Environment(\.unyriChatAppearance) private var appearance
    @ScaledMetric(relativeTo: .body) private var composerTextScale: CGFloat = 1
    @ScaledMetric(relativeTo: .caption) private var composerDocumentHeight: CGFloat = 72
    @AppStorage("quickReaction") private var quickReaction = "👍"
    @State private var quickReactionRequests = Set<String>()
    @State private var albumPreviewStates = UnyriAlbumPreviewStateCache()
    @Environment(\.callIsland) private var callIsland
    @Environment(\.dismiss) private var dismissChat
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @EnvironmentObject private var voiceRecorder: VoiceRecorder
    @EnvironmentObject private var callKit: CallKitCoordinator
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var callsHistory: CallsRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @AppStorage("chatActivityEnabled") private var activityEnabled = true
    // Saved Messages has no remote peer. Its draft/recording still works
    // locally, but a self-chat must not advertise peer activity to the server.
    private var allowsPeerActivity: Bool { chat.chatType != "saved" }
    @State private var activityNow = Date()
    @State private var presenceOwner = "chat:" + UUID().uuidString
    private var peerOnline: Bool {
        guard currentPreferenceChat.isBlocked != true, scenePhase == .active, let peer = chat.otherUser else { return false }
        return realtime.presenceObservation(for: peer.id)?.item.online == true
    }
    private var peerPresenceLabel: String {
        guard currentPreferenceChat.isBlocked != true, scenePhase == .active, let peer = chat.otherUser else { return chat.otherUser.map { "@" + $0.username } ?? "" }
        return realtime.presenceLabel(for: peer.id) ?? "@" + peer.username
    }
    @State private var composerEmojiOpen = false
    @State private var headerMenuOpen = false
    @State private var composerEmojiSelection: NSRange?
    @State private var headerAvatar: URL?
    @State private var showMessageTools = false
    private struct FullTextRequest: Identifiable {
        let id = UUID()
        let text: String
        let generation: UInt64
        let owner: String?
    }
    @State private var fullTextRequest: FullTextRequest?
    @State private var searchQuery = ""
    @State private var selectedMessages = Set<String>()
    @State private var showBulkDelete = false
    private struct DeleteSelection {
        var everyone: Bool
        let ids: [String]
        let generation: UInt64
        let owner: String
        let chatID: String
        let priorSelection: Set<String>
    }
    @State private var pendingBulkDelete: DeleteSelection?
    @State private var pinnedMessages = Set<String>()
    @State private var onlyPinned = false
    @State private var showKeyVerification = false
    @State private var retryAfterVerification: String?
    @State private var acceptedForRetry = false
    @Environment(\.signalVerificationManager) private var verificationManager
    @State private var identityNeedsReview = false
    @State private var showChatSafety = false
    @State private var didOpenInitialSafety = false
    @State private var chatSafetyAction: String?
    @State private var preferenceChat: ChatSummary?
    @State private var preferenceBusy = false
    @State private var preferenceStatus: String?
    @State private var confirmChatBlock = false
    @State private var reportReason = "spam"
    @State private var reportDescription = ""
    @State private var showReportForm = false
    private var currentPreferenceChat: ChatSummary { chatsRepository.preferenceCache[chat.chatId] ?? preferenceChat ?? chat }
    @State private var forwardSearch = ""
    @State private var showForwardPicker = false
    @State private var forwardQueue: [DecryptedChatMessage] = []
    @State private var forwardAlbumIDs: [String: String] = [:]
    @State private var destinations: [ChatSummary] = []
    @State private var forwarding = false
    @State private var forwardDestination: ChatSummary?
    @State private var forwardLoading = false
    @State private var forwardError: String?
    @State private var jumpRequest: String?
    @State private var returnAnchor: String?
    @State private var returningToAnchor = false
    @State private var arrivalTracker = ChatTailTracker()
    @State private var shortPaddingRoot = UUID()
    @State private var shortPadding = ChatPaddingContinuity<ShortHeightOwner, ShortHeightContext>()
    @State private var highlightedMessage: String?
    @State private var reminders: [String: Date] = [:]
    @State private var reminderFor: DecryptedChatMessage?
    @State private var reminderDate = Date().addingTimeInterval(3600)
    @State private var showReminders = false
    @State private var showSchedule = false
    @State private var scheduledDate = Date().addingTimeInterval(3600)
    @State private var reminderError: String?
    @State private var nextCallCursor: UUID?
    @State private var attemptedCallCursor: UUID?
    @State private var callHistoryLoaded = false
    @State private var loadingOlderCalls = false

    let chat: ChatSummary
    var initialSafetyAction: String? = nil
    var initialMessageId: String? = nil
    @State private var didOpenInitialMessage = false

    @State private var draft = ""
    @State private var ordinaryDraft = ""
    @State private var composerRestored = false
    @FocusState private var composerFocused: Bool
    @State private var didPositionInitialHistory = false
    @State private var pendingScrollTarget: String?
    @State private var pendingReaderOffset: ChatReaderBookmarkOffset?
    @State private var didCorrectInitialOffset = false
    @State private var initialPositionToken: UUID?
    @State private var initialRestorationAnchor: ChatReaderRestorationAnchor?
    @State private var isSending = false
    @State private var replyTarget: DecryptedChatMessage?
    @State private var editingMessage: DecryptedChatMessage?
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var photoDrafts: [PhotoDraft] = []
    @State private var mediaCaption = ""
    @State private var mediaAlbumId = UUID().uuidString.lowercased()
    @State private var persistedMediaURLs = Set<URL>()
    @State private var admittedMediaURLs = MediaDraftAdmissions()
    @State private var uploadedPhotos: [String: AttachmentMetadata] = [:]
    @State private var mediaRestoreToken = UUID()
    @State private var geometryUpdates = ChatGeometryUpdates()
    @State private var mediaPreviewTask: Task<Void, Never>?
    @State private var loadedMediaDraftRevision = 0
    private var mediaPreparationActive: Bool { messagesRepository.preparingMediaChats.contains(chat.chatId) }
    @State private var showPhotoPreview = false
    @State private var showLegacyMediaChoice = false
    @State private var didOfferLegacyMedia = false
    @State private var showMediaGallery = false
    @State private var showFileImporter = false
    @State private var showAttachmentMenu = false
    @State private var showPhotoPicker = false
    @State private var attachmentPhotoItems: [PhotosPickerItem] = []
    @State private var showCamera = false
    @State private var cameraClaim: NonCallCaptureOwnership.Claim?
    @State private var pendingVoiceClaim: NonCallCaptureOwnership.Claim?
    @State private var attachmentAssets: [UnyriGallerySource] = []
    @State private var gallerySendIntent = MediaSendIntent()
    @State private var composerAttachments: [ChatComposerAttachment] = []
    @State private var composerFileImports = 0
    @State private var composerFileImportTask: Task<Void, Never>?
    @State private var composerImportToken = UUID()
    @State private var gallerySelection: [String] = []
    @State private var galleryCaptures: [URL] = []
    @State private var addMoreMedia = false
    @State private var cameraSessionGeneration: UInt64 = 0
    @State private var cameraNeedsRetry = false
    @State private var attachmentChoice: String?
    @State private var fileDraft: FileAttachmentDraft?
    @State private var directFileSelection: DirectFileSendSelection?
    @State private var composerDirectTask: Task<Void, Never>?
    @State private var stagedFileDirectory: URL?
    @State private var voiceDraft: VoiceDraft?
    @State private var resumeVoiceBase: VoiceDraft?
    @State private var sendingVoice = false
    @State private var stagingVoice = false
    @State private var preparedVoice: AttachmentMetadata?
    @State private var isRecording = false
    @State private var recordingDrag = CGSize.zero
    @State private var recordingLocked = true
    @State private var startingVoice = false
    @State private var recordingHoldActive = false
    @State private var recordingHoldFinished = false
    @State private var lastVoiceHoldEnd = Date.distantPast
    @State private var composerSize: CGSize = .zero
    @State private var recordingSeconds: Int64 = 0
    // Runtime Correction D: near-bottom tracking + pending-new-messages count.
    @State private var isNearBottom = true
    @State private var measuredGeometry = ChatMeasuredGeometry()
    // One semantic publication when content/viewport identity changes, so the
    // jump button can refresh after the first coherent geometry for that view.
    @State private var bottomGeometryIdentity: String?
    @State private var bottomViewportSize = CGSize.zero
    @State private var keyboardResizing = false
    @State private var followKeyboardResize = false
    @State private var keyboardOpeningFollow = ChatKeyboardOpeningFollow()
    @State private var readerLifecycle = ChatReaderVisibility()
    @State private var readerCorrection = ChatReaderCorrectionController()
    @State private var timelineRootNonce = UUID()
    @State private var keyboardMountLease: UUID?
    @State private var timelineDeliveryEpoch: UInt64 = 0
    @State private var newMessagesCount = 0
    private let recordingTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var conversationVisible: Bool {
        scenePhase == .active && !appLock.requiresShield(owner: auth.currentUser.map {
            auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration)
        }) && !showAttachmentMenu && !showCamera && !showPhotoPicker &&
        !showPhotoPreview && !showFileImporter && !showMediaGallery && fileDraft == nil && directFileSelection == nil && fullTextRequest == nil
    }

    private var conversationContent: some View {
        VStack(spacing: 0) {
            if messagesRepository.loadedHistoryChatId == chat.chatId {
                messageList
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity).background(chatBackground)
            }
        }
        .environment(\.pendingMediaLookup, { chat, id in messagesRepository.queuedMediaPreview(chatId: chat, id: id) })
        .safeAreaInset(edge: .bottom, spacing: 0) { composer.background(selectedMessages.isEmpty && !showsGlassComposer ? UnyriSavedChatStyle.bottomColor : Color.clear) }
        .overlay { if composerEmojiOpen { composerEmojiPicker } }
        .background(chatBackground)
        .accessibilityHidden(showBulkDelete)
        .overlay {
            if showBulkDelete {
                GeometryReader { geometry in
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial).overlay(Color.black.opacity(0.25)).ignoresSafeArea()
                            .onTapGesture { cancelDeletion() }
                            .accessibilityHidden(true)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Delete selected items?").font(.headline)
                    HStack(spacing: 4) { Text("Selected"); Text("\(pendingBulkDelete?.ids.count ?? 0)") }
                        .foregroundStyle(UnyriColors.secondaryText).accessibilityElement(children: .combine)
                        .accessibilityIdentifier("chat-delete-selection-count")
                        .accessibilityValue(Text("\(pendingBulkDelete?.ids.count ?? 0)"))
                    let canDeleteEveryone = canDeleteForEveryone(pendingBulkDelete?.ids ?? [])
                    if canDeleteEveryone {
                        Toggle("Also delete for the other participant", isOn: Binding(get: { pendingBulkDelete?.everyone ?? false }, set: { pendingBulkDelete?.everyone = $0 }))
                            .tint(UnyriColors.accentSoft)
                    } else { Text("Only deleted for you").foregroundStyle(UnyriColors.secondaryText) }
                    HStack(spacing: 8) {
                        Button("Cancel") { cancelDeletion() }.frame(maxWidth: .infinity, minHeight: 44).accessibilityIdentifier("chat-delete-cancel")
                        Button("Delete", role: .destructive) {
                            guard let pending = pendingBulkDelete else { return }
                            guard pending.generation == auth.sessionGeneration, pending.owner == auth.currentUser?.id, pending.chatID == chat.chatId else { return }
                            guard !pending.everyone || canDeleteEveryone else {
                                showBulkDelete = false; pendingBulkDelete = nil
                                messagesRepository.reportError(UnyriL10n.string("The selected messages changed. Select them again and retry."))
                                return
                            }
                            showBulkDelete = false
                            pendingBulkDelete = nil
                    Task {
                        for id in pending.ids {
                            guard pending.generation == auth.sessionGeneration, pending.owner == auth.currentUser?.id, pending.chatID == chat.chatId else { break }
                            let deleted: Bool
                            if id.hasPrefix("call:"), let callId = UUID(uuidString: String(id.dropFirst(5))) {
                                do { try await callsHistory.deleteHistory(id: callId, everyone: pending.everyone); deleted = true }
                                catch { messagesRepository.reportError(UnyriL10n.string("Could not delete the call record. Check your connection and server version, then try again.")); deleted = false }
                            } else if pending.everyone { deleted = await messagesRepository.deleteForEveryone(clientMessageId: id) }
                            else { deleted = messagesRepository.deleteForMe(clientMessageId: id) }
                            if deleted { selectedMessages.remove(id) }
                        }
                    }

                    }.frame(maxWidth: .infinity, minHeight: 44).background(Color.red.opacity(0.10), in: RoundedRectangle(cornerRadius: 12)).accessibilityIdentifier("chat-delete-confirm")
                    }
                }.padding(24)
            }.foregroundStyle(UnyriColors.primaryText)
                .frame(maxWidth: 360).frame(height: min(300, max(160, geometry.size.height - 48)))
                .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 20))
                .padding(.horizontal, 24)
                .accessibilityAddTraits(.isModal)
                .accessibilityAction(.escape) { cancelDeletion() }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .onChange(of: messagesRepository.mediaDraftRevisions[chat.chatId, default: 0]) { _ in
            restoreMediaDrafts(restoreFile: fileDraft == nil && !messagesRepository.preparingMediaChats.contains(chat.chatId))
        }
        .onChange(of: photoDrafts.map(\.id)) { _ in persistMediaDrafts() }
        .onChange(of: mediaCaption) { _ in persistMediaDrafts() }
        .task(id: realtime.activity[chat.chatId]?.at) {
            activityNow = Date()
            guard let activity = realtime.activity[chat.chatId] else { return }
            let remaining = max(0, min(8, 8 - Date().timeIntervalSince(activity.at)))
            guard remaining > 0 else { return }
            do { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) } catch { return }
            activityNow = Date()
        }
        .onChange(of: realtime.state) { state in
            if state == .connected {
                if messagesRepository.errorIsConnectivity { messagesRepository.dismissError() }
                if conversationVisible, let peer = chat.otherUser?.id {
                    realtime.liveDrafts.open(chat: chat.chatId, peer: peer)
                }
            }
        }
        .sheet(isPresented: $showKeyVerification, onDismiss: {
            refreshIdentityWarning()
            let requested = retryAfterVerification
            let accepted = acceptedForRetry
            retryAfterVerification = nil; acceptedForRetry = false
            if accepted, let requested,
               let live = messagesRepository.messages.first(where: { $0.clientMessageId == requested && $0.status == .failed && !$0.hiddenLocally }) {
                retry(live, reviewKeys: false)
            }
        }) {
            if let peer = chat.otherUser?.id ?? auth.currentUser?.id {
                IdentityVerificationView(peer: peer, ownPeer: auth.currentUser?.id, onAccepted: { acceptedForRetry = true }, resumingMessage: retryAfterVerification != nil)
            }
        }
        .sheet(isPresented: $showChatSafety, onDismiss: { chatSafetyAction = nil; showReportForm = false }) { chatSafetySheet }
        .sheet(item: $fullTextRequest) { request in
            if request.generation == auth.sessionGeneration && request.owner == auth.currentUser?.id {
                UnyriFullMessageTextView(text: request.text)
            }
        }
        .onChange(of: auth.sessionGeneration) { _ in fullTextRequest = nil }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOChatReminder"))) { _ in openReminderTarget() }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOIdentityChanged")).receive(on: RunLoop.main)) { _ in refreshIdentityWarning() }
        .performanceScreen(.chat)
        .background(UnyriChatBackSwipe(enabled: conversationVisible && selectedMessages.isEmpty &&
            !showMessageTools && !showChatSafety && !composerEmojiOpen,
            onNavigationExit: { clearComposerAttachments() }).allowsHitTesting(false))
        .onAppear {
            PerformanceRecorder.shared.record(.chatOpen)
            refreshIdentityWarning()
            openReminderTarget()
            if !composerRestored {
                if let value = messagesRepository.chatValue(chatId: chat.chatId, name: "reminders"), let data = value.data(using: .utf8) { reminders = (try? JSONDecoder().decode([String: Date].self, from: data)) ?? [:] }
                if let value = messagesRepository.chatValue(chatId: chat.chatId, name: "pins"), let data = value.data(using: .utf8) {
                    pinnedMessages = (try? JSONDecoder().decode(Set<String>.self, from: data)) ?? []
                }
                restoreMediaDrafts()
                ordinaryDraft = messagesRepository.chatValue(chatId: chat.chatId, name: "draft") ?? ""
                draft = ordinaryDraft; composerRestored = true
                if let value = messagesRepository.chatValue(chatId: chat.chatId, name: "voiceDraft"), let data = value.data(using: .utf8) {
                    voiceDraft = try? JSONDecoder().decode(VoiceDraft.self, from: data)
                }
            } else {
                restoreMediaDrafts(restoreFile: fileDraft == nil)
            }
        }
        .environment(\.unyriCancelDownload, { messagesRepository.cancelDownloadAttachment($0) })
        .environment(\.unyriForwardMedia, { id in
            guard let message = messagesRepository.messages.first(where: { !$0.hiddenLocally && !$0.isDeletedForEveryone && $0.content.attachment?.attachmentId == id }) else { return }
            prepareForward([message])
        })
        // Reuse the exact visible chronological timeline, including album
        // members. Server sequence can differ after deferred video uploads.
        .environment(\.unyriMediaItems, conversationMediaItems)
        .navigationTitle(title)
        .toolbar(.hidden, for: .tabBar)
    }

    private var messagePresentation: some View {
        conversationContent
        .sheet(isPresented: $showSchedule) {
            NavigationStack {
                UnyriSettingsPage("Schedule message") {
                    UnyriSettingsGroup {
                        VStack(alignment: .leading, spacing: 14) {
                Text("Sent when this phone has a connection and UNYRI can run. The system may delay background sending.").font(.footnote)
                DatePicker("Date and time", selection: $scheduledDate, in: Date()...)
                    .accessibilityIdentifier("schedule-date")
                Button("Schedule message") {
                    if messagesRepository.scheduleText(chatId: chat.chatId, otherUserId: chat.otherUser?.id, text: draft, due: scheduledDate) {
                        draft = ""; ordinaryDraft = ""; messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: ""); showSchedule = false
                    }
                }.buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("schedule-confirm")
                Button("Cancel") { showSchedule = false }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("schedule-cancel")
                        }.padding(14)
                    }
                }.tint(Color(hex: 0x7BC9EC))
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showSchedule = false }.accessibilityIdentifier("schedule-close") } }
            }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(item: $reminderFor) { item in
            NavigationStack {
                UnyriSettingsPage("Remind me") {
                    UnyriSettingsGroup {
                        VStack(alignment: .leading, spacing: 14) {
                DatePicker("Date and time", selection: $reminderDate, in: Date()...)
                    .accessibilityIdentifier("reminder-date")
                if let reminderError { Text(reminderError).font(.footnote) }
                Button("Save reminder") { Task { await scheduleReminder(item) } }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-save")
                Button("Cancel") { reminderFor = nil }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-cancel")
                        }.padding(14)
                    }
                }.tint(Color(hex: 0x7BC9EC))
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { reminderFor = nil }.accessibilityIdentifier("reminder-close") } }
            }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showReminders) {
            NavigationStack {
                List(reminders.keys.sorted(), id: \.self) { id in
                    VStack(alignment: .leading, spacing: 10) {
                        Button(messagesRepository.localMessage(id: id)?.displayText ?? UnyriL10n.string("Original message is unavailable", comment: "")) { jumpRequest = id; showReminders = false }
                            .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-open-\(id)")
                        if let date = reminders[id] { Text(date.formatted()).font(.caption) }
                        Button("Done") {
                            UnyriLocalNotificationPreferences.cancelReminder(reminderIdentifier(id))
                            reminders[id] = nil; persistReminders()
                        }.buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-done-\(id)")
                    }.padding(12).listRowBackground(Color(hex: 0x1B1B1D))
                        .listRowSeparator(.hidden)
                }.listStyle(.plain).tint(Color(hex: 0x7BC9EC)).overlay {
                    if reminders.isEmpty { Text("Hold a message and choose Remind me to set a reminder.").foregroundColor(.secondary).padding(24) }
                }.unyriScreenBackground().navigationTitle("Reminders").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showReminders = false }.accessibilityIdentifier("reminders-close") } }
            }
        }
        .sheet(isPresented: Binding(get: { showMessageTools && !onlyPinned }, set: { if !$0 { showMessageTools = false } })) { messageTools }
        .fullScreenCover(isPresented: Binding(get: { showMessageTools && onlyPinned }, set: { if !$0 { showMessageTools = false } })) { messageTools }
        .sheet(isPresented: $showForwardPicker) { forwardPicker }

    }

    private var attachmentPresentation: some View {
        messagePresentation
        .sheet(isPresented: $showAttachmentMenu, onDismiss: openAttachmentChoice) {
            VStack(spacing: 0) {
                UnyriAttachmentGallery(cameraEnabled: !voiceRecorder.callInProgress,
                    camera: { attachmentChoice = "camera"; showAttachmentMenu = false },
                    library: { attachmentChoice = "photos"; showAttachmentMenu = false },
                    files: { attachmentChoice = "files"; showAttachmentMenu = false },
                    selected: { assets, intent in
                        attachmentAssets = assets; gallerySendIntent = intent
                        attachmentChoice = "assets"; showAttachmentMenu = false
                    }, selection: $gallerySelection, captures: galleryCaptures, sendingAllowed: composerAttachments.count < 10,
                    selectionLimit: max(1, 10 - composerAttachments.count),
                    caption: Binding(get: { draft }, set: updateComposerDraft),
                    close: { UnyriHaptics.press(); showAttachmentMenu = false })
            }
            .presentationDetents([.fraction(UnyriAttachmentPickerStyle.height / 100)]).presentationDragIndicator(.hidden)
            .modifier(AttachmentSheetGlass())
            .interactiveDismissDisabled(true)
        }
    }

    private var mediaPresentation: some View {
        attachmentPresentation
        .confirmationDialog("Unsent attachments from a previous version", isPresented: $showLegacyMediaChoice, titleVisibility: .visible) {
            Button("Review attachments") { showPhotoPreview = true }
            Button("Discard attachments", role: .destructive) { discardPhotos() }
            Button("Later", role: .cancel) {}
        } message: {
            Text("These attachments were saved before the new sending flow. They have not been sent.")
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: max(1, 10 - composerAttachments.count), selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), preferredItemEncoding: MediaGallerySendChoice.saved() == .original ? .current : .automatic)
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            addComposerPhotos(items)
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result {
                // SwiftUI clears isPresented before this callback. Acquire
                // provider grants now; the task owns them until copying ends.
                let documents = urls.map { AttachmentFileCopy.DocumentSource($0) }
                composerFileImportTask = Task { await addComposerFiles(documents) }
            }
            if case .failure(let error) = result { messagesRepository.reportError(error.localizedDescription) }
        }
        .sheet(item: $fileDraft, onDismiss: discardFileDraft) { file in
            FileAttachmentPreview(draft: file, enqueue: {
                messagesRepository.queueMedia(id: file.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id, url: file.url, kind: "file")
            }, onClose: { fileDraft = nil })
        }
        .sheet(item: $directFileSelection) { selection in
            if let transfers = messagesRepository.directFiles {
                DirectFileSendSheet(selection: selection, chat: currentPreferenceChat, transfers: transfers,
                    close: { directFileSelection = nil })
                    .presentationDetents([.medium, .large])
            }
        }
        .onChange(of: auth.sessionGeneration) { _ in
            clearComposerAttachments(); directFileSelection = nil
        }
        .fullScreenCover(isPresented: $showCamera, onDismiss: {
            if let claim = cameraClaim { NonCallCaptureOwnership.stop(claim); cameraClaim = nil }
            if cameraNeedsRetry { cameraNeedsRetry = false; showPhotoPreview = true }
        }) {
            CameraPhotoPicker(captureClaim: cameraClaim, onCapture: { source, id in
                try await prepareCameraCapture(source, id: id, generation: cameraSessionGeneration)
            }, onClose: { showCamera = false }).ignoresSafeArea()
        }
        .sheet(isPresented: $showPhotoPreview, onDismiss: {
            persistMediaDrafts()
            if addMoreMedia { addMoreMedia = false; showAttachmentMenu = true }
        }) {
            PhotoDraftPreview(drafts: $photoDrafts, caption: $mediaCaption, add: { addMoreMedia = true; showPhotoPreview = false },
                beginBatch: { await messagesRepository.flushDraftValues(); messagesRepository.beginMediaAdmissionBatch() },
                endBatch: { messagesRepository.endMediaAdmissionBatch() }) { draft, caption, progress in
                let kind = mediaDraftKind(isVideo: draft.isVideo)
                let queued = await messagesRepository.queueVisualMedia(id: draft.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                    url: draft.fileURL, kind: kind, caption: caption, albumId: mediaAlbumId)
                guard queued else { throw APIError.network("Could not save media to the outbox.") }
                admittedMediaURLs.urls.insert(draft.fileURL)
            }
        }
        .sheet(isPresented: $showMediaGallery) {
            ChatMediaGallery(chatId: chat.chatId)
                .environmentObject(messagesRepository)
                .environmentObject(voicePlayer)
        }
    }

    private var conversationHeader: some View {
        mediaPresentation
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if selectedMessages.isEmpty { chatHeader } else {
                    HStack {
                        UnyriBackButton(action: { selectedMessages.removeAll() }, label: "Cancel")
                            .accessibilityIdentifier("chat-selection-cancel")
                        HStack(spacing: 8) {
                            Text("Selected").font(.headline).lineLimit(1)
                            Text("\(selectedMessages.count)").font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.onAccent)
                                .padding(.horizontal, 6).frame(minWidth: 30, minHeight: 30)
                                .background(UnyriColors.accent.opacity(0.85), in: Capsule())
                        }.padding(.horizontal, 14).padding(.vertical, 6)
                            .background(UnyriColors.deepPanel.opacity(0.82), in: Capsule())
                            .overlay(Capsule().stroke(UnyriColors.primaryText.opacity(0.08), lineWidth: 0.5))
                            .frame(maxWidth: .infinity)
                        Menu {
                            Button("Select all loaded messages") {
                                selectedMessages = Set(messagesRepository.messages.filter { !$0.isDeletedForEveryone && !$0.hiddenLocally }.map(\.clientMessageId))
                                selectedMessages.formUnion(timelineItems.compactMap { item in item.call.flatMap { $0.isEnded ? "call:\($0.id.uuidString)" : nil } })
                            }
                        } label: {
                            Image(systemName: "ellipsis").frame(width: 44, height: 44)
                                .background(UnyriColors.deepPanel.opacity(0.82), in: Circle())
                                .overlay(Circle().stroke(UnyriColors.primaryText.opacity(0.08), lineWidth: 0.5))
                        }.accessibilityLabel("More options")
                    }
                    .padding(.horizontal, 12).frame(height: 56).foregroundStyle(UnyriColors.primaryText)
                }
                if !showDecryptionFailures && timelinePresentation.unavailable {
                    HStack(spacing: 8) {
                        Image(systemName: "lock.trianglebadge.exclamationmark")
                        Text(UnyriL10n.string("Some messages could not be decrypted.")).font(.footnote)
                        Spacer(minLength: 0)
                        Button(UnyriL10n.string("Details")) { showDecryptionFailures = true }.frame(minHeight: 44)
                    }.foregroundColor(UnyriColors.secondaryText).padding(.horizontal, 16)
                    .background(UnyriColors.background)
                }
                if let id = orderedPinnedIds.first {
                    HStack(spacing: 8) {
                        Button { jumpRequest = id } label: {
                            HStack {
                                Image(systemName: "pin.fill")
                                Text(pinnedPreviewText(messagesRepository.localMessage(id: id))).lineLimit(1)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Button { onlyPinned = true; showMessageTools = true } label: { Image(systemName: "ellipsis").frame(width: 36, height: 36) }.accessibilityLabel("Pinned messages")
                    }.font(.subheadline).padding(.horizontal, 12).background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 12)
                }
                if let action = messagesRepository.failedActions.first, action.chatId == chat.chatId {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(UnyriL10n.string(action.kind == "edit" ? "Could not save the message edit." : action.kind == "delete" ? "Could not delete the message for everyone." : "Could not save the reaction."))
                            .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                        HStack {
                            Spacer()
                            Button("Cancel") { _ = messagesRepository.deleteForMe(clientMessageId: action.clientMessageId) }
                            Button("Retry") { messagesRepository.retryFailedAction(action) }
                        }.font(.subheadline).buttonStyle(.borderless)
                    }.padding(12).background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 12)
                }
                if let error = messagesRepository.errorMessage, !messagesRepository.errorIsConnectivity {
                    HStack(spacing: 8) {
                        Text(error)
                            .font(.footnote)
                            .foregroundColor(UnyriColors.secondaryText)
                            .lineLimit(2)
                        Spacer()
                        Button { messagesRepository.dismissError() } label: {
                            Image(systemName: "xmark").frame(width: 44, height: 44)
                        }.accessibilityLabel("Dismiss")
                        .font(.footnote)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 12)
                }
            }
            .background(GeometryReader { geometry in
                Color.clear.preference(key: ChatNotificationHeaderHeight.self, value: geometry.size.height)
            })
        }
        .onPreferenceChange(ChatNotificationHeaderHeight.self) { height in
            if height > 0 { chatsRepository.notificationHeaderHeight = Double(height) }
        }
    }

    var body: some View {
        conversationHeader
        .accessibilityHidden(headerMenuOpen)
        .overlay { if headerMenuOpen { headerMenuOverlay } }
        .onDisappear { headerMenuOpen = false }
        .task(id: chat.otherUser?.avatarVersion) {
            guard let other = chat.otherUser else { return }
            headerAvatar = await usersRepository.avatarURL(id: other.id, avatarUrl: other.avatarUrl, avatarVersion: other.avatarVersion ?? 0)
        }
        .task(id: "presence:\(scenePhase):\(realtime.state):\(chat.chatId):\(currentPreferenceChat.isBlocked == true):\(conversationVisible)") {
            if currentPreferenceChat.isBlocked != true, scenePhase == .active, conversationVisible, let peer = chat.otherUser {
                realtime.subscribePresence(owner: presenceOwner, userIDs: [peer.id], priority: 100)
            } else { realtime.unsubscribePresence(owner: presenceOwner) }
        }
        .task(id: scenePhase) {
            messagesRepository.setChatVisible(chat.chatId, visible: conversationVisible)
            guard scenePhase == .active else { return }
            if messagesRepository.loadedHistoryChatId == chat.chatId {
                await messagesRepository.refreshLatest(chatId: chat.chatId)
            } else {
                await messagesRepository.loadHistory(chatId: chat.chatId)
            }
        }
        .task(id: "history-fallback:\(scenePhase):\(conversationVisible):\(realtime.state):\(chat.chatId)") {
            await ChatHistoryFallback.run(active: scenePhase == .active, visible: conversationVisible,
                                          connected: realtime.state == .connected) {
                await messagesRepository.refreshLatest(chatId: chat.chatId)
            }
        }
        .task(id: scenePhase) {
            if scenePhase == .active { await auth.reconcileHistoryContext() }
        }
        .onChange(of: messagesRepository.loadedHistoryChatId) { loaded in
            if loaded == chat.chatId, let initialMessageId, !didOpenInitialMessage {
                didOpenInitialMessage = true; jumpRequest = initialMessageId
            }
        }
        .onAppear {
            if messagesRepository.loadedHistoryChatId == chat.chatId, let initialMessageId, !didOpenInitialMessage {
                didOpenInitialMessage = true; jumpRequest = initialMessageId
            }
            if let initialSafetyAction, !didOpenInitialSafety {
                didOpenInitialSafety = true
                chatSafetyAction = initialSafetyAction
                showReportForm = initialSafetyAction == "report"
                showChatSafety = true
            }
            messagesRepository.setChatVisible(chat.chatId, visible: conversationVisible)
            if conversationVisible, let peer = chat.otherUser?.id { realtime.liveDrafts.open(chat: chat.chatId, peer: peer) }
        }
        .onDisappear {
            if !showCamera, let claim = cameraClaim { NonCallCaptureOwnership.stop(claim) }
            if let claim = pendingVoiceClaim { NonCallCaptureOwnership.stop(claim) }
            // Covering/dismissing a system picker is not a navigation exit.
            // The owned native route anchor handles actual completed pops.
            geometryUpdates.cancel()
            realtime.unsubscribePresence(owner: presenceOwner)
            mediaRestoreToken = UUID()
            mediaPreviewTask?.cancel(); mediaPreviewTask = nil
            Task { await messagesRepository.flushDraftValues() }
            realtime.liveDrafts.close(chat: chat.chatId)
            if isRecording { voiceRecorder.interrupt() }
            messagesRepository.setChatVisible(chat.chatId, visible: false)
        }
        .onChange(of: chatsRepository.deletedChatIDs) { ids in
            if ids.contains(chat.chatId) { clearComposerAttachments(); dismissChat() }
        }
        .onChange(of: conversationVisible) { visible in
            if !visible { geometryUpdates.cancel() }
            if !visible { Task { await messagesRepository.flushDraftValues() } }
            if visible, let peer = chat.otherUser?.id { realtime.liveDrafts.open(chat: chat.chatId, peer: peer) } else { realtime.liveDrafts.close(chat: chat.chatId) }
            messagesRepository.setChatVisible(chat.chatId, visible: visible)
        }
        .task(id: scenePhase) {
            guard scenePhase == .active, let peer = chat.otherUser.flatMap({ UUID(uuidString: $0.id) }) else { return }
            await callsHistory.loadCachedHistory()
            while !Task.isCancelled {
                do {
                    let cursor = try await callsHistory.refreshHistory(peer: peer)
                    if !callHistoryLoaded { nextCallCursor = cursor; callHistoryLoaded = true }
                } catch { if Task.isCancelled { return } }
                do { try await Task.sleep(nanoseconds: 10_000_000_000) } catch { return }
            }
        }
    }

    // MARK: - List

    /// Subtle STATIC atmospheric gradient (no animation, no bitmap): a faint
    /// violet-blue tint strongest near the top that fades into #0A0B10. Never
    /// competes with bubble contrast.
    private var chatBackground: some View {
        UnyriConversationBackground().ignoresSafeArea()
    }

    @ViewBuilder
    private var messageList: some View {
        // Create the scroll-position state only after the local snapshot is ready.
        // Otherwise its initial anchor is captured from an empty array.
        if messagesRepository.loadedHistoryChatId == chat.chatId {
            loadedMessageList
        } else {
            chatBackground.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var chatHeader: some View {
        HStack(spacing: 10) {
            UnyriBackButton(action: { clearComposerAttachments(); dismissChat() }).accessibilityIdentifier("chat-navigation-back")
            HStack(spacing: 8) {
                if let callIsland {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(title).font(.caption).foregroundColor(UnyriColors.primaryText).lineLimit(1)
                        CallIslandTitle(presentation: callIsland)
                    }
                } else {
                if chat.chatType == "saved" {
                    Image(systemName: "bookmark.fill").frame(width: UnyriSavedChatStyle.avatarSize, height: UnyriSavedChatStyle.avatarSize)
                } else {
                    UserAvatar(url: headerAvatar ?? usersRepository.cachedAvatarURL(id: chat.otherUser?.id ?? "", avatarUrl: chat.otherUser?.avatarUrl, avatarVersion: chat.otherUser?.avatarVersion ?? 0), name: title, size: UnyriSavedChatStyle.avatarSize, vividFallback: true).overlay(alignment: .bottomTrailing) {
                        if peerOnline && realtime.state == .connected { Circle().fill(Color(red: 0.26, green: 0.79, blue: 0.58)).frame(width: 8, height: 8).overlay(Circle().stroke(UnyriColors.chatPanel, lineWidth: 2)) }
                    }
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.headline).foregroundColor(UnyriColors.primaryText).lineLimit(1)
                    UnyriStatusText(text: realtime.state == .connected && !messagesRepository.errorIsConnectivity
                        ? (peerActivityText.isEmpty ? peerPresenceLabel : UnyriL10n.string(peerActivityText))
                        : UnyriL10n.string(realtime.state == .connecting ? "Connecting…" : "Waiting for network…"))
                        .foregroundColor(peerOnline && realtime.state == .connected && peerActivityText.isEmpty && !messagesRepository.errorIsConnectivity ? ChatControlPalette.mint : UnyriColors.secondaryText)
                }
                }
            }.frame(minHeight: 44, alignment: .leading).layoutPriority(1)
                .padding(.horizontal, 10)

            Spacer(minLength: 0)
            HStack(spacing: 2) {
            if let other = chat.otherUser {
                Button {
                    composerFocused = false
                    if currentPreferenceChat.isBlocked == true { showChatSafety = true }
                    else { callKit.startOutgoing(remoteUserId: other.id, displayName: other.displayName, isVideo: false) }
                } label: { Image(systemName: "phone").font(.system(size: UnyriSavedChatStyle.iconSize)).foregroundStyle(UnyriSavedChatStyle.topInk).frame(width: 34, height: 36).contentShape(Rectangle().inset(by: -4)) }
                .accessibilityLabel("Call")
            }
            Button { composerFocused = false; headerMenuOpen = true } label: {
                Image(systemName: "gearshape").font(.system(size: UnyriSavedChatStyle.iconSize)).foregroundStyle(UnyriSavedChatStyle.topInk).frame(width: 34, height: 36).contentShape(Rectangle().inset(by: -4))
            }.accessibilityLabel("Chat options")
            }.padding(.horizontal, 4).padding(.vertical, 1).overlay(Capsule().stroke(UnyriSavedChatStyle.border, lineWidth: 1))
        }
        .foregroundColor(UnyriColors.accentSoft)
        .padding(.horizontal, 12).frame(minHeight: UnyriSavedChatStyle.headerHeight)
        .background(UnyriSavedChatStyle.topColor)
    }
    private func headerMenuRow(_ title: LocalizedStringKey, symbol: String, tint: Color, fill: Color,
                               enabled: Bool = true, checked: Bool? = nil, action: @escaping () -> Void) -> some View {
        let ink = appColorStyle.decorative(tint)
        let plate = appColorStyle.isMonochrome ? ink.opacity(0.12) : fill
        return Button {
            headerMenuOpen = false
            action()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol).font(.system(size: 19)).foregroundColor(ink)
                    .frame(width: 34, height: 34).background(plate, in: RoundedRectangle(cornerRadius: 11))
                Text(title).font(.body).foregroundColor(UnyriColors.primaryText).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                if checked == true { Image(systemName: "checkmark").foregroundColor(appColorStyle.decorative(ChatControlPalette.mint)) }
            }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.4)
            .accessibilityValue(checked.map { UnyriL10n.string($0 ? "On" : "Off") } ?? "")
    }
    private var headerMenuDivider: some View {
        Rectangle().fill(UnyriColors.primaryText.opacity(0.07)).frame(height: 0.5).padding(.horizontal, 16).padding(.vertical, 5)
    }
    private var headerMenuOverlay: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topTrailing) {
                UnyriChatMenuBackdrop { headerMenuOpen = false }
                VStack(spacing: 0) {
                    HStack {
                        Text("Chat menu").font(.subheadline.weight(.semibold))
                        Spacer()
                        Button { headerMenuOpen = false } label: { Image(systemName: "xmark").font(.system(size: 17)).frame(width: 44, height: 44) }.accessibilityLabel("Close")
                    }.foregroundColor(UnyriColors.primaryText).padding(.leading, 16).padding(.trailing, 4)
                    ScrollView {
                        VStack(spacing: 0) {
                            headerMenuRow("Search messages", symbol: "magnifyingglass", tint: ChatControlPalette.blue, fill: ChatControlPalette.blueBack) { onlyPinned = false; showMessageTools = true }
                            headerMenuRow("Media", symbol: "photo.on.rectangle", tint: ChatControlPalette.mint, fill: ChatControlPalette.mintBack) { showMediaGallery = true }
                            headerMenuRow("Pinned messages", symbol: "pin", tint: ChatControlPalette.amber, fill: ChatControlPalette.amberBack) { onlyPinned = true; showMessageTools = true }
                            headerMenuDivider
                            if allowsPeerActivity {
                                headerMenuRow("Share typing and recording status", symbol: "eye", tint: ChatControlPalette.blue, fill: ChatControlPalette.blueBack, checked: activityEnabled) { activityEnabled.toggle() }
                            }
                            headerMenuRow("Schedule message", symbol: "clock", tint: ChatControlPalette.blue, fill: ChatControlPalette.blueBack, enabled: !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && editingMessage == nil && replyTarget == nil) { scheduledDate = Date().addingTimeInterval(3600); showSchedule = true }
                            headerMenuRow("Reminders", symbol: "bookmark", tint: ChatControlPalette.amber, fill: ChatControlPalette.amberBack) { showReminders = true }
                            if let recovered = messagesRepository.chatValue(chatId: chat.chatId, name: "recoveredDraft") {
                                headerMenuRow("Recovered draft", symbol: "arrow.uturn.backward", tint: ChatControlPalette.blue, fill: ChatControlPalette.blueBack) {
                                    draft = ordinaryDraft.isEmpty ? recovered : ordinaryDraft + "\n" + recovered
                                    ordinaryDraft = draft
                                    messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: draft)
                                    messagesRepository.saveChatValue(chatId: chat.chatId, name: "recoveredDraft", value: nil)
                                }
                            }
                            if chat.otherUser != nil {
                                headerMenuDivider
                                headerMenuRow(currentPreferenceChat.isMuted == true ? "Unmute notifications" : "Mute notifications", symbol: "bell", tint: ChatControlPalette.amber, fill: ChatControlPalette.amberBack, enabled: !preferenceBusy) { chatSafetyAction = "notifications"; showChatSafety = true }
                                headerMenuDivider
                                headerMenuRow(currentPreferenceChat.isBlocked == true ? "Unblock contact" : "Block contact", symbol: "hand.raised", tint: ChatControlPalette.coral, fill: ChatControlPalette.coralBack) { chatSafetyAction = "block"; showChatSafety = true }
                                headerMenuRow("Report contact", symbol: "exclamationmark.shield", tint: ChatControlPalette.coral, fill: ChatControlPalette.coralBack) { chatSafetyAction = "report"; showReportForm = true; preferenceStatus = nil; showChatSafety = true }
                            }
                        }.padding(.bottom, 8)
                    }
                }.frame(width: min(300, max(100, geometry.size.width - 24)))
                    .frame(maxHeight: min(620, max(100, geometry.size.height - 64)))
                    .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24).stroke(appColorStyle.decorative(ChatControlPalette.edge).opacity(0.25), lineWidth: 0.5))
                    .padding(.top, min(52, geometry.size.height / 8)).padding(.trailing, 12)
                    .accessibilityElement(children: .contain)
            }
        }.accessibilityAction(.escape) { headerMenuOpen = false }
    }

    @State private var showDecryptionFailures = false
    private var conversationEndID: String { "conversation-end:\(chat.chatId)" }

    @ViewBuilder private func albumCell(_ message: DecryptedChatMessage, firstInGroup: Bool, inAlbum: Bool = false, actionMembers: [DecryptedChatMessage] = [], rowID: String? = nil, viewportObservation: ChatViewportObservation? = nil, shortContext: ShortHeightContext? = nil) -> some View {
        let presentationID = rowID ?? message.id
                        let ownerID = auth.currentUser?.id
                        let unavailableHistory = isUnavailableHistory(message, cutoff: auth.historyBeforeSequence ?? 0)
                        let reportsVisibleRead = (message.senderId != ownerID || unavailableHistory) && message.sequence != nil
                        Group {
                        if unavailableHistory && !showDecryptionFailures {
                            EmptyView()
                        } else if message.status == .decryptFailed && !showDecryptionFailures {
                            EmptyView()
                        } else {
                        messageBubble(message, shareAlbumPreview: inAlbum, actionMembers: actionMembers).allowsHitTesting(selectedMessages.isEmpty)
                        }
                        }
                        .padding(.top, inAlbum ? 0 : firstInGroup ? appearance.gap : appearance.groupGap)
                        .id(presentationID)
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: ChatItemFrames.self,
                                value: ChatItemFrameBatch(frames: inAlbum ? [:] : chatViewportFrame(geometry, observation: viewportObservation, currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize).map { [presentationID: $0] } ?? [:]))
                            .preference(key: VisibleMessageFrames.self,
                                value: reportsVisibleRead
                                    ? chatViewportFrame(geometry, observation: viewportObservation, currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize).map { [message.sequence!: $0] } ?? [:] : [:])
                            .preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                value: inAlbum ? [] : shortContext.map { [ChatIntrinsicHeight(context: $0, row: presentationID, height: geometry.size.height)] } ?? [])
                            .preference(key: ChatBottomFramesKey.self, value: chatLegacyContentFrames(geometry,
                                observation: viewportObservation, controller: readerCorrection,
                                currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize))
                        })
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(highlightedMessage == message.clientMessageId ? UnyriColors.accentSoft : .clear, lineWidth: 2))
                        .modifier(UnyriMessageSelectionTap(action: selectedMessages.isEmpty || inAlbum ? nil : {
                            UnyriHaptics.selection()
                            if selectedMessages.contains(message.clientMessageId) { selectedMessages.remove(message.clientMessageId) }
                            else { selectedMessages.insert(message.clientMessageId) }
                        }))
                        .transition(.identity)
    }

    private func messageBubble(_ message: DecryptedChatMessage, content: AnyView? = nil, captionOnly: Bool = false, shareAlbumPreview: Bool = false, actionMembers: [DecryptedChatMessage] = []) -> MessageBubble {
        let available = actionMembers.filter { !$0.hiddenLocally && !$0.isDeletedForEveryone && $0.status != .decryptFailed && $0.status != .identityChanged }
        func selectionItem(_ value: DecryptedChatMessage) -> UnyriChatAlbumSelection.Item {
            .init(id: value.clientMessageId, chat: value.chatId, sender: value.senderId, album: value.content.albumId)
        }
        let ids = UnyriChatAlbumSelection.members(of: selectionItem(message), rows: [available.map(selectionItem)])
        let group = ids.count > 1 ? available.filter { ids.contains($0.clientMessageId) } : [message]
        let actionGeneration = auth.sessionGeneration
        let actionOwner = auth.currentUser?.id
        func actionIsCurrent() -> Bool {
            auth.sessionGeneration == actionGeneration && auth.currentUser?.id == actionOwner && message.chatId == chat.chatId
        }
        return MessageBubble(
                            message: message,
                            scheduledAt: messagesRepository.scheduledDate(chatId: chat.chatId, id: message.clientMessageId),
                            isPinned: pinnedMessages.contains(message.clientMessageId),
                            isMine: message.senderId == auth.currentUser?.id,
                            currentUserId: auth.currentUser?.id,
                            replyPeerName: chat.otherUser?.displayName ?? chat.otherUser?.username ?? UnyriL10n.string("Reply"),
                            reactions: messagesRepository.reactions(forMessageServerId: message.serverId),
                            onRetry: { retry(message) },
                            onReply: { replyTarget = message },
                            onJumpQuote: { if let target = message.targetMessageId { jumpRequest = target } },
                            onCopy: { UIPasteboard.general.string = message.content.text ?? message.displayText },
                            onForward: { guard actionIsCurrent() else { return }; prepareForward(group) },
                            onSave: { Task { await saveMessages(group) } },
                            onSelect: { guard actionIsCurrent() else { return }; UnyriHaptics.selection(); composerFocused = false; selectedMessages.formUnion(ids) },
                            onPin: { togglePin(message.clientMessageId) },
                            onRemind: { reminderDate = Date().addingTimeInterval(3600); reminderError = nil; reminderFor = message },
                            onEdit: { beginEdit(message) },
                            onDeleteForEveryone: { guard actionIsCurrent() else { return }; if ids.count > 1 { presentDeletion(ids, everyone: true) } else { deleteForEveryone(message) } },
                            onDeleteForMe: {
                                guard actionIsCurrent() else { return }
                                if message.senderId == actionOwner, message.serverId == nil,
                                   message.status == .pending || message.status == .failed {
                                    // Capture only this displayed group's current unsent IDs.
                                    // The repository rechecks ownership/status at the SQL mutation.
                                    let pending = Set(group.compactMap { member -> String? in
                                        guard let live = messagesRepository.localMessage(id: member.clientMessageId),
                                              live.senderId == actionOwner, live.chatId == chat.chatId,
                                              live.serverId == nil, !live.hiddenLocally, !live.isDeletedForEveryone,
                                              live.status == .pending || live.status == .failed else { return nil }
                                        return live.clientMessageId
                                    })
                                    let captured = UnyriChatAlbumSelection.pendingMembers(of: selectionItem(message),
                                        rows: [group.map(selectionItem)], pendingIDs: pending)
                                    messagesRepository.cancelUnsentMessages(chatId: chat.chatId, clientMessageIds: captured)
                                } else if ids.count > 1 { presentDeletion(ids) } else { deleteForMe(message) }
                            },
                            onReact: { react(message, $0) },
                            onQuickReaction: quickReactionAction(message),
                            onRemoveReaction: { removeReaction(message) },
                            onDownloadAttachment: { await messagesRepository.downloadAttachment(attachmentId: $0, origin: .messageTap) },
                            onCachedLocalPath: { messagesRepository.cachedLocalPath(attachmentId: $0) },
                            isVoicePlaying: { voicePlayer.state.playingAttachmentId == $0 },
                            voicePositionMs: { id in voicePlayer.state.playingAttachmentId == id || voicePlayer.state.pausedAttachmentId == id ? voicePlayer.state.positionMs : 0 },
                            onToggleVoice: { id, path in
                                UnyriHaptics.press()
                                voicePlayer.togglePlay(attachmentId: id, fileURL: URL(fileURLWithPath: path))
                            }
                        , selecting: !selectedMessages.isEmpty, selected: selectedMessages.contains(message.clientMessageId), appearancePreviewContent: content, albumCaptionMode: captionOnly,
                          sharedPreviewState: shareAlbumPreview ? albumPreviewStates.value(for: message.id) : nil,
                          actionMemberCount: ids.count,
                          onSelectIndividual: ids.count > 1 ? { guard actionIsCurrent() else { return }; UnyriHaptics.selection(); composerFocused = false; selectedMessages.insert(message.clientMessageId) } : nil,
                          onDeleteIndividual: ids.count > 1 ? { guard actionIsCurrent() else { return }; presentDeletion([message.clientMessageId]) } : nil,
                          onShowFullText: MessageTextPolicy.isPresentationBounded(message.content.text ?? "") ? {
                              guard actionIsCurrent(), let live = messagesRepository.localMessage(id: message.clientMessageId),
                                    live.chatId == chat.chatId, !live.hiddenLocally, !live.isDeletedForEveryone,
                                    live.status != .decryptFailed, live.status != .identityChanged,
                                    let raw = live.content.text else { return }
                              composerFocused = false
                              fullTextRequest = FullTextRequest(text: raw, generation: actionGeneration, owner: actionOwner)
                          } : nil)
    }

    private struct AlbumRun: Identifiable {
        let members: [DecryptedChatMessage]
        let visual: Bool
        var id: String { members[0].id }
    }

    private func albumVisual(_ message: DecryptedChatMessage) -> Bool {
        if let preview = messagesRepository.queuedMediaPreview(chatId: chat.chatId, id: message.clientMessageId) {
            return ["image", "image_high", "video", "video_high"].contains(preview.kind)
        }
        guard let attachment = message.content.attachment else { return false }
        return attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/")
    }

    private func albumRatio(_ message: DecryptedChatMessage) -> Double {
        if let visual = messagesRepository.queuedMediaPreview(chatId: chat.chatId, id: message.clientMessageId)?.visual {
            return Double(max(1, visual.width)) / Double(max(1, visual.height))
        }
        if let image = message.content.attachment?.image { return Double(max(1, image.width)) / Double(max(1, image.height)) }
        return 1
    }

    private func albumRuns(_ members: [DecryptedChatMessage]) -> [AlbumRun] {
        var runs: [AlbumRun] = []
        for member in members {
            let visual = albumVisual(member)
            if visual, let last = runs.last, last.visual {
                runs[runs.count - 1] = AlbumRun(members: last.members + [member], visual: true)
            } else { runs.append(AlbumRun(members: [member], visual: visual)) }
        }
        return runs
    }

    private func albumCaption(_ message: DecryptedChatMessage) -> String? {
        let text = messagesRepository.queuedMediaPreview(chatId: chat.chatId, id: message.clientMessageId)?.caption
            ?? (message.content.attachment == nil ? nil : message.content.text)
        return text.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
    }

    private func albumReceipt(_ members: [DecryptedChatMessage]) -> MessageStatus {
        for status in [MessageStatus.failed, .pending, .sent, .delivered] {
            if members.contains(where: { $0.status == status }) { return status }
        }
        return .read
    }

    private func albumGroup(_ item: TimelineItem, viewport: CGSize, firstInGroup: Bool, viewportObservation: ChatViewportObservation? = nil, shortContext: ShortHeightContext? = nil) -> some View {
        let members = item.members
        let outgoing = item.message?.senderId == auth.currentUser?.id
        let runs = albumRuns(members)
        let documents = runs.allSatisfy { !$0.visual }
        let width = min(max(80, (viewport.width - UnyriSavedChatStyle.side * 2 - (selectedMessages.isEmpty ? 0 : 44)) * UnyriSavedChatStyle.width),
                        documents ? UnyriSavedChatStyle.fileWidth : UnyriSavedChatStyle.mediaWidth)
        let caption = members.compactMap { member in albumCaption(member).map { (member, $0) } }.first
        return HStack(alignment: .unyriMessageBodyCenter, spacing: 0) {
            if outgoing { Spacer(minLength: 0) }
            if !selectedMessages.isEmpty && outgoing { albumSelectionMark(members) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 0) {
            VStack(spacing: 0) {
                VStack(spacing: UnyriSavedChatStyle.albumGap) {
                ForEach(runs) { run in
                    if run.visual {
                        UnyriAlbumVisualRun(ids: run.members.map(\.id), ratios: run.members.map(albumRatio), width: width) { index, tile in
                                let member = run.members[index]
                                albumCell(member, firstInGroup: firstInGroup, inAlbum: true, actionMembers: members, viewportObservation: viewportObservation, shortContext: shortContext)
                                    .environment(\.chatMediaWidth, CGFloat(tile.width))
                                    .environment(\.chatMediaHeight, CGFloat(tile.height))
                                    .environment(\.chatAlbumCell, true)
                                    .frame(width: tile.width, height: tile.height).clipped()
                        }
                    } else {
                        ForEach(run.members) { member in
                            albumCell(member, firstInGroup: firstInGroup, inAlbum: true, actionMembers: members, viewportObservation: viewportObservation, shortContext: shortContext)
                                .environment(\.chatAlbumCell, true)
                                .environment(\.chatAlbumWidth, width)
                        }
                    }
                }
                }
                if let caption {
                    messageBubble(caption.0, content: AnyView(
                        UnyriMessageRichText(source: caption.1).equatable()
                            .font(appearance.messageFont()).lineSpacing(appearance.extraLineSpacing)
                            .foregroundStyle(outgoing ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, appearance.padX).padding(.vertical, appearance.padY)
                    ), captionOnly: true, shareAlbumPreview: true, actionMembers: members)
                    .environment(\.chatAlbumCell, true)
                    .environment(\.chatAlbumWidth, width)
                    .frame(maxWidth: .infinity)
                }
            }
            .background(outgoing ? appearance.outgoing : appearance.incoming)
            .clipShape(documents ? AnyShape(appearance.documentAlbumShape) : AnyShape(UnyriSavedChatStyle.mediaShape))
            .alignmentGuide(.unyriMessageBodyCenter) { $0[VerticalAlignment.center] }
            if documents { albumMetadata(members, outgoing: outgoing) }
            if UnyriSavedChatStyle.showReactions && members.contains(where: { !messagesRepository.reactions(forMessageServerId: $0.serverId).isEmpty }) {
                UnyriReactionRows {
                    ForEach(members) { member in
                        ForEach(Array(messagesRepository.reactions(forMessageServerId: member.serverId).enumerated()), id: \.offset) { _, reaction in
                            ForEach(0..<max(0, reaction.count), id: \.self) { _ in
                                Button { if reaction.isMine { removeReaction(member) } else { react(member, reaction.emoji) } } label: {
                                    Text(reaction.emoji).font(.system(size: UnyriSavedChatStyle.emojiSize))
                                        .frame(minWidth: UnyriSavedChatStyle.reactionWidth, minHeight: UnyriSavedChatStyle.reactionHeight)
                                        .background(UnyriSavedChatStyle.reaction, in: Capsule())
                                }.buttonStyle(.plain).accessibilityIdentifier("message-reaction-\(reaction.emoji)")
                            }
                        }
                    }
                }.padding(.top, UnyriSavedChatStyle.reactionOffset)
            }
            if !documents { albumMetadata(members, outgoing: outgoing) }
            }.frame(width: width)
            if !selectedMessages.isEmpty && !outgoing { albumSelectionMark(members) }
            if !outgoing { Spacer(minLength: 0) }
        }
        .padding(.top, firstInGroup ? appearance.gap : appearance.groupGap)
        .frame(maxWidth: .infinity, alignment: outgoing ? .trailing : .leading)
        .id(item.id)
        .background(GeometryReader { geometry in
            Color.clear.preference(key: ChatItemFrames.self, value: ChatItemFrameBatch(frames: chatViewportFrame(geometry, observation: viewportObservation, currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize).map { [item.id: $0] } ?? [:]))
            .preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: item.id, height: geometry.size.height)] } ?? [])
            .preference(key: ChatBottomFramesKey.self, value: chatLegacyContentFrames(geometry,
                observation: viewportObservation, controller: readerCorrection,
                currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize))
        })
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-album")
        .modifier(UnyriMessageSelectionTap(action: selectedMessages.isEmpty ? nil : {
            UnyriHaptics.selection()
            selectedMessages = UnyriChatAlbumSelection.toggled(selectedMessages, members: members.map(\.clientMessageId))
        }))
    }

    private func albumSelectionMark(_ members: [DecryptedChatMessage]) -> some View {
        let ids = Set(members.map(\.clientMessageId))
        let all = ids.isSubset(of: selectedMessages)
        let partial = !ids.isDisjoint(with: selectedMessages)
        return Image(systemName: all ? "checkmark.circle.fill" : partial ? "minus.circle.fill" : "circle")
            .font(.system(size: 24, weight: .semibold)).foregroundStyle(UnyriColors.accentSoft)
            .frame(width: 44, height: 44).frame(height: 0).accessibilityHidden(true)
    }

    @ViewBuilder private func albumMetadata(_ members: [DecryptedChatMessage], outgoing: Bool) -> some View {
        if UnyriSavedChatStyle.showTimes, let last = members.last {
            UnyriCompactMessageMetadata(date: last.createdAt, status: albumReceipt(members), outgoing: outgoing,
                                       edited: members.contains(where: \.isEdited))
                .padding(.horizontal, 8).padding(.top, UnyriSavedChatStyle.timeOffset)
        }
    }

    private var loadedMessageList: some View {
        let presentation = timelinePresentation
        let items = presentation.items
        return ScrollViewReader { proxy in
            GeometryReader { viewport in
            let timelineOwner = ChatTimelineContainerOwner(generation: String(auth.sessionGeneration),
                account: auth.currentUser?.id, chat: chat.chatId, root: timelineRootNonce)
            let capturedKeyboardLease = keyboardMountLease
            let bottomIdentity = bottomGeometryContext(presentation.arrivalRevision, viewport: viewport.size)
            let viewportObservation: ChatViewportObservation? = ChatViewportObservation(viewport: viewport, context: bottomIdentity,
                legacyOwner: ChatLegacyMetricsAvailability.enabled ? timelineOwner : nil,
                legacyLeaseEpoch: ChatLegacyMetricsAvailability.enabled ? readerCorrection.legacyContainerEpoch : nil)
            let readerGeneration = auth.sessionGeneration
            let readerOwner = auth.currentUser?.id
            let readerIntent = initialRestorationAnchor?.id
            let hasMessages = presentation.hasMessages
            let readerReady = items.isEmpty || (didPositionInitialHistory && pendingScrollTarget == nil)
            let shortContext: ShortHeightContext? = shortHeightContext(viewport: viewport.size)
            let shortGap = shortContext.map { current in
                shortPadding.gap(owner: current.paddingOwner, context: current,
                    viewportHeight: viewport.size.height, now: ProcessInfo.processInfo.systemUptime)
            } ?? 0
            let shortPlaceholderReady = shortContext.map {
                shortPadding.currentExtent(owner: $0.paddingOwner, context: $0) != nil
            } ?? true
            let timelineScroll = ChatTimelineContainer(viewport: viewport.size, identity: bottomIdentity) {
                    Color.clear.frame(height: shortGap).id("short-gap:" + chat.chatId)
                        .modifier(ChatTimelineRow(readerReady: readerReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
                        .accessibilityHidden(true).allowsHitTesting(false)
                    if messagesRepository.hasMore {
                        Button("Load older") {
                            Task { await messagesRepository.loadOlder(chatId: chat.chatId) }
                        }
                        .font(.footnote)
                        .padding(.vertical, 4)
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: "special:load", height: geometry.size.height)] } ?? [])
                        })
                        .modifier(ChatTimelineRow(readerReady: readerReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
                    }

                    ForEach(presentation.indexed, id: \.element.id) { index, item in
                        Group {
                        if let message = item.message {
                        let firstInGroup = index == 0 || items[index - 1].message?.senderId != message.senderId
                        if item.members.count > 1 {
                            albumGroup(item, viewport: viewport.size, firstInGroup: firstInGroup, viewportObservation: viewportObservation, shortContext: shortContext)
                                .modifier(ChatReaderOffsetAnchor(row: item.id, target: initialRestorationAnchor))
                                .modifier(ChatReaderCorrectionRow(row: item.id, target: initialRestorationAnchor,
                                    controller: readerCorrection, observation: viewportObservation, currentFrame: {
                                        guard conversationVisible, auth.sessionGeneration == readerGeneration,
                                              auth.currentUser?.id == readerOwner,
                                              messagesRepository.loadedHistoryChatId == chat.chatId,
                                              currentBottomGeometryContext == bottomIdentity,
                                              measuredGeometry.itemsIdentity == bottomIdentity,
                                              pendingScrollTarget == item.id, didCorrectInitialOffset,
                                              initialRestorationAnchor?.id == readerIntent,
                                              let frame = measuredGeometry.items[item.id], let offset = pendingReaderOffset,
                                              offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)) == offset.originY else { return nil }
                                        return frame
                                    }))
                        } else {
                            albumCell(message, firstInGroup: firstInGroup, rowID: item.id, viewportObservation: viewportObservation, shortContext: shortContext)
                                .modifier(ChatReaderOffsetAnchor(row: item.id, target: initialRestorationAnchor))
                                .modifier(ChatReaderCorrectionRow(row: item.id, target: initialRestorationAnchor,
                                    controller: readerCorrection, observation: viewportObservation, currentFrame: {
                                        guard conversationVisible, auth.sessionGeneration == readerGeneration,
                                              auth.currentUser?.id == readerOwner,
                                              messagesRepository.loadedHistoryChatId == chat.chatId,
                                              currentBottomGeometryContext == bottomIdentity,
                                              measuredGeometry.itemsIdentity == bottomIdentity,
                                              pendingScrollTarget == item.id, didCorrectInitialOffset,
                                              initialRestorationAnchor?.id == readerIntent,
                                              let frame = measuredGeometry.items[item.id], let offset = pendingReaderOffset,
                                              offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)) == offset.originY else { return nil }
                                        return frame
                                    }))
                        }
                        } else if let call = item.call {
                            UnyriCallHistoryMenuRow(call: call, owner: auth.currentUser.flatMap { UUID(uuidString: $0.id) },
                                earlierHistory: call.endedAt.map { end in auth.historyContext.map { end < $0.beforeDate } ?? false } ?? false,
                                selecting: !selectedMessages.isEmpty, selected: selectedMessages.contains("call:\(call.id.uuidString)"),
                                onSelect: {
                                    UnyriHaptics.selection()
                                    composerFocused = false
                                    let key = "call:\(call.id.uuidString)"
                                    if selectedMessages.contains(key) { selectedMessages.remove(key) } else { selectedMessages.insert(key) }
                                }, onDelete: { everyone in
                                    Task {
                                        do { try await callsHistory.deleteHistory(id: call.id, everyone: everyone) }
                                        catch { messagesRepository.reportError(UnyriL10n.string("Could not delete the call record. Check your connection and server version, then try again.")) }
                                    }
                                })
                                .id(item.id)
                                .background(GeometryReader { geometry in
                                    Color.clear.preference(key: ChatItemFrames.self,
                                        value: ChatItemFrameBatch(frames: chatViewportFrame(geometry, observation: viewportObservation, currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize).map { [item.id: $0] } ?? [:]))
                                    .preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                        value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: item.id, height: geometry.size.height)] } ?? [])
                                    .preference(key: ChatBottomFramesKey.self, value: chatLegacyContentFrames(geometry,
                                        observation: viewportObservation, controller: readerCorrection,
                                        currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize))
                                })
                                .modifier(ChatReaderOffsetAnchor(row: item.id, target: initialRestorationAnchor))
                                .modifier(ChatReaderCorrectionRow(row: item.id, target: initialRestorationAnchor,
                                    controller: readerCorrection, observation: viewportObservation, currentFrame: {
                                        guard conversationVisible, auth.sessionGeneration == readerGeneration,
                                              auth.currentUser?.id == readerOwner,
                                              messagesRepository.loadedHistoryChatId == chat.chatId,
                                              currentBottomGeometryContext == bottomIdentity,
                                              measuredGeometry.itemsIdentity == bottomIdentity,
                                              pendingScrollTarget == item.id, didCorrectInitialOffset,
                                              initialRestorationAnchor?.id == readerIntent,
                                              let frame = measuredGeometry.items[item.id], let offset = pendingReaderOffset,
                                              offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)) == offset.originY else { return nil }
                                        return frame
                                    }))
                        }
                        }
                        .modifier(ChatTimelineRow(readerReady: readerReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
                    }

                    if items.isEmpty && messagesRepository.loadedHistoryChatId == chat.chatId && !messagesRepository.isLoading {
                        Text("No messages yet").font(.subheadline).foregroundColor(.secondary).padding()
                            .background(GeometryReader { geometry in
                                Color.clear.preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                    value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: "special:empty", height: geometry.size.height)] } ?? [])
                            })
                            .frame(maxWidth: .infinity, alignment: .center)
                            .modifier(ChatTimelineRow(readerReady: readerReady && shortPlaceholderReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
                    }
                    if items.isEmpty && messagesRepository.isLoading && realtime.state == .connected {
                        ProgressView().padding(.vertical, 8)
                            .background(GeometryReader { geometry in
                                Color.clear.preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                    value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: "special:loading", height: geometry.size.height)] } ?? [])
                            })
                            .frame(maxWidth: .infinity, alignment: .center)
                            .modifier(ChatTimelineRow(readerReady: readerReady && shortPlaceholderReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
                    }
                    // Scroll to the space after the bubble, never to a bubble
                    // hidden under the translucent composer.
                    Color.clear.frame(height: 12).id(conversationEndID)
                        .background {
                            ChatKeyboardTailAnchor(scope: "\(timelineOwner.generation)|\(timelineOwner.account ?? "")|\(timelineOwner.chat)|\(timelineOwner.root)|\(capturedKeyboardLease?.uuidString ?? "")",
                                enabled: nativeSizeChangesAnchoring && capturedKeyboardLease != nil && conversationVisible && didPositionInitialHistory && readerReady && !items.isEmpty,
                                shouldFollow: { self.followKeyboardResize || self.isNearBottom },
                                isCurrent: {
                                    capturedKeyboardLease != nil && self.keyboardMountLease == capturedKeyboardLease &&
                                    self.messagesRepository.activeChatId == timelineOwner.chat && self.conversationVisible &&
                                    String(self.auth.sessionGeneration) == timelineOwner.generation && self.auth.currentUser?.id == timelineOwner.account &&
                                    self.chat.chatId == timelineOwner.chat && self.timelineRootNonce == timelineOwner.root
                                })
                        }
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: ChatBottomFramesKey.self, value: chatMergeBottomFrames(chatViewportFrame(geometry, observation: viewportObservation, currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize).map { [bottomIdentity: ChatBottomFrames(content: nil, end: $0, viewport: viewport.size)] } ?? [:],
                                    chatLegacyContentFrames(geometry, observation: viewportObservation, controller: readerCorrection,
                                        currentContext: currentBottomGeometryContext, currentViewport: bottomViewportSize)))
                            .preference(key: ChatIntrinsicHeights<ShortHeightContext>.self,
                                value: shortContext.map { [ChatIntrinsicHeight(context: $0, row: "special:end", height: geometry.size.height)] } ?? [])
                        })
                        .modifier(ChatTimelineRow(bottom: 8, readerReady: readerReady, legacyController: readerCorrection, legacyOwner: timelineOwner, legacyObservation: viewportObservation))
            }
            timelineScroll.modifier(ChatInitialScrollPosition(
                initialID: initialScrollTarget(items: items).id,
                followsLatest: initialScrollTarget(items: items).followsLatest, followsContainerChanges: isNearBottom))
            .modifier(ChatKeyboardScrollInteraction { cancelInitialPositionForUserIntent() })
            .task(id: jumpRequest) {
                guard let request = jumpRequest else { return }
                cancelInitialPositionForUserIntent()
                if !returningToAnchor { returnAnchor = messagesRepository.scrollBookmark(chatId: chat.chatId)?.anchor }
                returningToAnchor = false
                let locatedID: String?
                if timelineRowID(request, items: items) != nil { locatedID = request }
                else { locatedID = await messagesRepository.locateMessage(chatId: chat.chatId, id: request) }
                guard !Task.isCancelled else { return }
                if let id = locatedID {
                    // The repository may have published an older history page.
                    await Task.yield()
                    if let row = timelineRowID(id, items: timelineItems) {
                        withAnimation { proxy.scrollTo(row, anchor: .center) }
                    }
                    highlightedMessage = id
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    if highlightedMessage == id { highlightedMessage = nil }
                } else if !Task.isCancelled {
                    messagesRepository.reportError(UnyriL10n.string("Original message is not available in loaded history.", comment: ""))
                }
                jumpRequest = nil
            }
            .coordinateSpace(name: "messageViewport")
            .environment(\.chatViewportWidth, max(1, viewport.size.width - UnyriSavedChatStyle.side * 2))
            .accessibilityIdentifier("chat-timeline")
            .accessibilityHidden(!readerReady)
            .scrollDismissesKeyboard(.interactively)
            // Tag this actual preference pass at the owning viewport. A new
            // identity must deliver even when every CGRect stayed unchanged.
            .transformPreference(ChatItemFrames.self) { batch in batch.context = bottomIdentity }
            .onAppear { synchronizeShortPadding(shortContext) }
            .onChange(of: shortContext) { synchronizeShortPadding($0) }
            .onPreferenceChange(ChatIntrinsicHeights<ShortHeightContext>.self) { measurements in
                geometryUpdates.submit(channel: 3) {
                    guard let context = shortContext,
                          context == shortHeightContext(viewport: bottomViewportSize) else { return }
                    let expected = timelinePresentation.intrinsicRowIDs
                    let measured = ChatIntrinsicExtent.extent(context: context, itemIDs: expected,
                        specialIDs: context.specialIDs, measurements: measurements)
                    let lowerBound = measured ?? ChatIntrinsicExtent.lowerBound(context: context,
                        itemIDs: expected, specialIDs: context.specialIDs, measurements: measurements)
                    var next = shortPadding
                    next.accept(owner: context.paddingOwner, context: context, completeExtent: measured,
                        lowerBound: lowerBound, viewportHeight: bottomViewportSize.height)
                    if shortPadding != next { shortPadding = next }

                }
            }
            .onPreferenceChange(ChatBottomFramesKey.self) { frames in
                geometryUpdates.submit(channel: -1) {
                    guard bottomIdentity == currentBottomGeometryContext else { return }
                    measuredGeometry.bottom = frames[bottomIdentity]
                    if bottomGeometryIdentity != bottomIdentity { bottomGeometryIdentity = bottomIdentity }
                    restoreScroll(proxy, items: items)
                    applyBottomPosition(identity: bottomIdentity, proxy: proxy)
                }
            }
            .onPreferenceChange(ChatItemFrames.self) { batch in
                guard batch.context == bottomIdentity else { return }
                let frames = batch.frames
                geometryUpdates.submit(channel: 0) {
                    guard bottomIdentity == currentBottomGeometryContext else { return }
                    measuredGeometry.items = frames; measuredGeometry.itemsIdentity = bottomIdentity
                    restoreScroll(proxy, items: items)
                    applyBottomPosition(identity: bottomIdentity, proxy: proxy)
                    guard didPositionInitialHistory, conversationVisible else { return }
                    if pendingScrollTarget != nil { return }
                    let bounds = CGRect(origin: .zero, size: viewport.size)
                    if scenePhase == .active {
                        let viewed = Set(frames.filter { $0.value.intersects(bounds) }.keys.compactMap { key -> UUID? in
                            guard key.hasPrefix("call:") else { return nil }
                            return UUID(uuidString: String(key.dropFirst(5)))
                        })
                        callsHistory.acknowledgeViewed(viewed)
                    }
                    var firstVisible: (key: String, value: CGRect)?
                    // Fractional slivers are not a reading position; keeping
                    // one would walk the bookmark back a row on every reopen.
                    for (key, frame) in frames where frame.intersects(bounds) &&
                        frame.intersection(bounds).height > 1 {
                        if frame.minY < (firstVisible?.value.minY ?? .infinity) {
                            firstVisible = (key, frame)
                        }
                    }
                    guard let first = firstVisible else { return }
                    let bookmarkAnchor = presentation.bookmarkAnchors[first.key] ?? first.key
                    if items.prefix(3).contains(where: { $0.id == first.key }),
                       let cursor = nextCallCursor, attemptedCallCursor != cursor, !loadingOlderCalls {
                        attemptedCallCursor = cursor
                        loadingOlderCalls = true
                        Task {
                            defer { loadingOlderCalls = false }
                            do {
                                nextCallCursor = try await callsHistory.refreshHistory(
                                    peer: chat.otherUser.flatMap { UUID(uuidString: $0.id) }, before: cursor)
                                if #unavailable(iOS 17.0), !isNearBottom,
                                   messagesRepository.scrollBookmark(chatId: chat.chatId)?.anchor == bookmarkAnchor {
                                    proxy.scrollTo(timelineRowID(bookmarkAnchor, items: timelineItems) ?? first.key, anchor: .top)
                                }
                            } catch { /* Retry after scrolling away and back, not every layout pass. */ }
                        }
                    } else if !items.prefix(3).contains(where: { $0.id == first.key }) {
                        attemptedCallCursor = nil
                    }
                    messagesRepository.rememberScroll(chatId: chat.chatId,
                        anchor: bookmarkAnchor, followsLatest: isNearBottom,
                        offset: ChatReaderBookmarkOffset(originY: Double(first.value.minY),
                            rowHeight: Double(first.value.height), viewportWidth: Double(viewport.size.width)))
                }
            }
            .onPreferenceChange(VisibleMessageFrames.self) { frames in
                geometryUpdates.submit(channel: 1) {
                    guard didPositionInitialHistory, conversationVisible, pendingScrollTarget == nil else { return }
                    let bounds = CGRect(origin: .zero, size: viewport.size)
                    if PerformanceRecorder.shared.isEnabled {
                        PerformanceRecorder.shared.recordScroll(visibleCount: frames.values.filter { $0.intersects(bounds) }.count)
                    }
                    var sequence: Int64 = 0
                    for (candidate, frame) in frames where frame.intersects(bounds) {
                        sequence = max(sequence, candidate)
                    }
                    messagesRepository.setVisibleReadSequence(chatId: chat.chatId, sequence: sequence)
                }
            }
            .background(chatBackground)
            .overlay(alignment: .bottomTrailing) {
                if ChatViewportPolicy.showsJump(hasMessages: hasMessages, positioned: didPositionInitialHistory,
                    nearBottom: measuredBottomPosition(identity: bottomIdentity), resizing: keyboardResizing,
                    pendingPosition: pendingScrollTarget != nil) {
                    NewMessagesPill(count: newMessagesCount) {
                        if !items.isEmpty {
                            withAnimation { proxy.scrollTo(conversationEndID, anchor: .bottom) }
                        }
                        newMessagesCount = 0
                    }
                    .padding(.trailing, 16)
                    .padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
            .onChange(of: presentation.arrivalRevision) { _ in
                // The callback can retain the previous rendered presentation.
                // Track and follow one coherent current snapshot.
                let presentation = timelinePresentation
                let items = presentation.items
                let hasMessages = presentation.hasMessages
                let arrived = arrivalTracker.update(tail: presentation.arrivalRevision.tail, ids: presentation.arrivalIDs)
                if items.isEmpty {
                    isNearBottom = true; newMessagesCount = 0; finishInitialPosition(removeAnchor: true)
                    didPositionInitialHistory = false; measuredGeometry.clear(); bottomGeometryIdentity = nil
                    return
                }
                if !didPositionInitialHistory && initialPositionToken == nil { boundInitialPosition(timeout: 1) }
                if let pending = pendingScrollTarget, pending != conversationEndID,
                   timelineRowID(pending, items: items) == nil {
                    finishInitialPosition(removeAnchor: true); didPositionInitialHistory = false
                    boundInitialPosition(timeout: 1)
                    restoreScroll(proxy, items: items)
                    return
                }
                if !hasMessages { newMessagesCount = 0 }
                guard conversationVisible, let last = items.last else { return }
                if didPositionInitialHistory {
                    guard arrived else { return }
                    if ChatViewportPolicy.followsNewItem(nearBottom: isNearBottom, isOwnMessage: last.message?.senderId == auth.currentUser?.id) {
                        if pendingScrollTarget != nil {
                            pendingScrollTarget = conversationEndID; pendingReaderOffset = nil
                            initialRestorationAnchor = nil; didCorrectInitialOffset = false
                            boundInitialPosition()
                        }
                        // Follow the bottom only when already near it — never
                        // force-scroll a user reading older messages.
                        proxy.scrollTo(conversationEndID, anchor: .bottom)
                    } else if hasMessages {
                        newMessagesCount += 1
                    }
                } else {
                    restoreScroll(proxy, items: items)
                }
            }
            .onAppear {
                keyboardMountLease = UUID()
                configureTimelineMetrics(timelineOwner)
                readerLifecycle.appeared()
                if !items.isEmpty && !didPositionInitialHistory { boundInitialPosition(timeout: 1) }
                bottomViewportSize = viewport.size
                _ = arrivalTracker.update(tail: presentation.arrivalRevision.tail, ids: presentation.arrivalIDs)
                restoreScroll(proxy, items: items)
                geometryUpdates.submit(channel: 2) { applyBottomPosition(identity: bottomIdentity, proxy: proxy) }
            }
            .onChange(of: timelineOwner) { owner in configureTimelineMetrics(owner) }
            .onChange(of: didPositionInitialHistory) { positioned in
                if positioned { geometryUpdates.submit(channel: 2) { applyBottomPosition(identity: bottomIdentity, proxy: proxy) } }
            }
            .onChange(of: conversationVisible) { visible in
                if !visible { readerCorrection.cancel() }
                let restoreReader = readerLifecycle.visibilityChanged(visible: visible)
                keyboardResizing = false; followKeyboardResize = false
                keyboardOpeningFollow.cancel()
                // A mounted native reader retains the fractional row offset.
                // Replaying a row-only bookmark on resume would snap it to top.
                guard visible, didPositionInitialHistory, restoreReader else { return }
                if isNearBottom { proxy.scrollTo(conversationEndID, anchor: .bottom) }
                else if let anchor = messagesRepository.scrollBookmark(chatId: chat.chatId)?.anchor {
                    proxy.scrollTo(timelineRowID(anchor, items: items) ?? anchor, anchor: .top)
                }
            }
            .onDisappear {
                keyboardMountLease = nil
                readerCorrection.disconnectLegacyContainer(timelineOwner)
                readerCorrection.cancel()
                keyboardOpeningFollow.cancel()
                readerLifecycle.disappeared()
            }
            .onChange(of: auth.sessionGeneration) { _ in readerCorrection.cancel() }
            .onChange(of: chat.chatId) { _ in readerCorrection.cancel() }
            .onChange(of: viewport.size.width) { _ in readerCorrection.invalidateObservation(); bottomViewportSize = viewport.size }
            .onChange(of: viewport.size.height) { _ in
                readerCorrection.invalidateObservation()
                bottomViewportSize = viewport.size
                geometryUpdates.submit(channel: 2) { applyBottomPosition(identity: bottomIdentity, proxy: proxy) }
                if ChatViewportPolicy.followsViewportResize(nativeSizeChanges: nativeSizeChangesAnchoring,
                    visible: conversationVisible, positioned: didPositionInitialHistory, nearBottom: isNearBottom,
                    legacyFollow: followKeyboardResize, hasItems: !items.isEmpty) {
                    proxy.scrollTo(conversationEndID, anchor: .bottom)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { _ in
                readerCorrection.cancel()
                guard conversationVisible else { return }
                if !keyboardResizing { followKeyboardResize = isNearBottom }
                keyboardResizing = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                guard nativeSizeChangesAnchoring else { return }
                keyboardOpeningFollow.willShow(context: keyboardOpeningContext, visible: conversationVisible,
                    positioned: didPositionInitialHistory, nearBottom: isNearBottom, hasItems: !items.isEmpty)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                keyboardOpeningFollow.cancel()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                guard nativeSizeChangesAnchoring,
                      keyboardOpeningFollow.didShow(context: keyboardOpeningContext, visible: conversationVisible,
                        positioned: didPositionInitialHistory, hasItems: !items.isEmpty) else { return }
                // The owned native viewport follows during UIKit layout; consume intent only.
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidChangeFrameNotification)) { _ in
                // Viewport-height changes already follow the keyboard. A second
                // scroll here can snap after the system animation has finished.
                keyboardResizing = false
                followKeyboardResize = false
                geometryUpdates.submit(channel: 2) { applyBottomPosition(identity: currentBottomGeometryContext, proxy: proxy) }
            }
            .onChange(of: messagesRepository.loadedHistoryChatId) { _ in restoreScroll(proxy, items: items) }
            }
        }
    }

    private func configureTimelineMetrics(_ owner: ChatTimelineContainerOwner) {
        guard ChatLegacyMetricsAvailability.enabled else { return }
        readerCorrection.configureLegacyContainer(owner, isCurrent: {
            self.conversationVisible && String(self.auth.sessionGeneration) == owner.generation &&
                self.auth.currentUser?.id == owner.account && self.chat.chatId == owner.chat &&
                self.timelineRootNonce == owner.root
        }, refresh: {
            guard self.readerCorrection.isLegacyOwner(owner),
                  String(self.auth.sessionGeneration) == owner.generation,
                  self.auth.currentUser?.id == owner.account, self.chat.chatId == owner.chat,
                  self.timelineRootNonce == owner.root else { return }
            self.timelineDeliveryEpoch &+= 1
        }, userPan: {
            guard self.conversationVisible, self.readerCorrection.isLegacyOwner(owner),
                  String(self.auth.sessionGeneration) == owner.generation,
                  self.auth.currentUser?.id == owner.account, self.chat.chatId == owner.chat,
                  self.timelineRootNonce == owner.root else { return }
            self.cancelInitialPositionForUserIntent()
        })
    }

    private var nativeSizeChangesAnchoring: Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    private var keyboardOpeningContext: String { "\(auth.sessionGeneration)|\(chat.chatId)" }

    private func bottomGeometryContext(_ revision: ArrivalRevision, viewport: CGSize) -> String {
        let original = "\(auth.sessionGeneration)|\(chat.chatId)|\(revision.count)|\(revision.tail ?? "")|\(viewport.width)|\(viewport.height)|\(conversationVisible)|\(keyboardResizing)"
        return ChatLegacyMetricsAvailability.enabled ? original + "|legacy:\(timelineDeliveryEpoch)" : original
    }

    private var currentBottomGeometryContext: String {
        bottomGeometryContext(timelinePresentation.arrivalRevision, viewport: bottomViewportSize)
    }

    private func measuredBottomPosition(identity: String) -> Bool? {
        guard identity == currentBottomGeometryContext, bottomGeometryIdentity == identity,
              let geometry = measuredGeometry.bottom, let content = geometry.content,
              geometry.viewport == bottomViewportSize else { return nil }
        return ChatViewportPolicy.measuredNearBottom(contentTop: Double(content.minY), contentHeight: Double(content.height),
            endBottom: geometry.end.map { Double($0.maxY) }, viewportHeight: Double(geometry.viewport.height))
    }

    private func applyBottomPosition(identity: String, proxy: ScrollViewProxy) {
        guard didPositionInitialHistory, conversationVisible, !keyboardResizing,
              let nearBottom = measuredBottomPosition(identity: identity) else { return }
        if let pending = pendingScrollTarget {
            if pending == conversationEndID {
                guard nearBottom else { return }
                guard ChatViewportPolicy.strictTailAcknowledged(
                    actualEndBottom: measuredGeometry.bottom?.end.map { Double($0.maxY) },
                    viewportHeight: Double(bottomViewportSize.height)) else { return }
            } else {
                guard measuredGeometry.itemsIdentity == identity,
                      let frame = measuredGeometry.items[pending],
                      frame.intersects(CGRect(origin: .zero, size: bottomViewportSize)) else { return }
                let tolerance = 1 / UIScreen.main.scale
                if let offset = pendingReaderOffset,
                   offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)) == nil {
                    pendingReaderOffset = nil; initialRestorationAnchor = nil
                    if didCorrectInitialOffset {
                        didCorrectInitialOffset = false
                        proxy.scrollTo(pending, anchor: .top)
                        return
                    }
                }
                if !didCorrectInitialOffset {
                    guard abs(frame.minY) <= tolerance || (nearBottom && frame.minY > 0) else { return }
                    if let offset = pendingReaderOffset,
                       let desired = offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)),
                       desired <= 0, let target = initialRestorationAnchor, target.row == pending {
                        readerCorrection.arm(target.id)
                        didCorrectInitialOffset = true
                        if abs(frame.minY - CGFloat(desired)) > tolerance && !nearBottom { return }
                    } else {
                        pendingReaderOffset = nil; initialRestorationAnchor = nil
                    }
                }
                if let offset = pendingReaderOffset,
                   let expected = offset.origin(rowHeight: Double(frame.height), viewportWidth: Double(bottomViewportSize.width)),
                   abs(frame.minY - CGFloat(expected)) > tolerance,
                   !(nearBottom && frame.minY > CGFloat(expected)) { return }
            }
            finishInitialPosition()
        }
        if isNearBottom != nearBottom { isNearBottom = nearBottom }
        if nearBottom && newMessagesCount != 0 { newMessagesCount = 0 }
    }

    private func cancelInitialPositionForUserIntent() {
        readerCorrection.cancel()
        keyboardOpeningFollow.cancel()
        guard !didPositionInitialHistory || pendingScrollTarget != nil else { return }
        finishInitialPosition(removeAnchor: true)
        // The user's current position replaces a not-yet-acknowledged intent.
        didPositionInitialHistory = true
    }

    private func finishInitialPosition(removeAnchor: Bool = false) {
        readerCorrection.cancel()
        pendingScrollTarget = nil; pendingReaderOffset = nil
        didCorrectInitialOffset = false; initialPositionToken = nil
        if removeAnchor { initialRestorationAnchor = nil }
    }

    private func boundInitialPosition(timeout: TimeInterval = 0.75) {
        guard initialPositionToken == nil else { return }
        let token = UUID(); initialPositionToken = token
        let correctionDeadline = DispatchTime.now() + timeout
        readerCorrection.beginWindow(token, deadline: correctionDeadline)
        // Missing/deleted targets must never leave hidden or inaccessible rows.
        // Expiry cancels readiness only; it issues no extra scroll command.
        DispatchQueue.main.asyncAfter(deadline: correctionDeadline) {
            guard initialPositionToken == token, (!didPositionInitialHistory || pendingScrollTarget != nil) else { return }
            finishInitialPosition(removeAnchor: true); didPositionInitialHistory = true
            if let nearBottom = measuredBottomPosition(identity: currentBottomGeometryContext) { isNearBottom = nearBottom }
        }
    }

    private func restoreScroll(_ proxy: ScrollViewProxy, items: [TimelineItem]) {
        let identity = currentBottomGeometryContext
        let coherent = measuredGeometry.itemsIdentity == identity && !measuredGeometry.items.isEmpty &&
            measuredBottomPosition(identity: identity) != nil
        guard !didPositionInitialHistory, messagesRepository.loadedHistoryChatId == chat.chatId,
              !items.isEmpty, conversationVisible, !keyboardResizing, coherent else { return }
        if let bookmark = messagesRepository.scrollBookmark(chatId: chat.chatId), !bookmark.followsLatest,
           let anchor = bookmark.anchor, let row = timelineRowID(anchor, items: items) {
            pendingScrollTarget = row
            pendingReaderOffset = bookmark.offset
            initialRestorationAnchor = bookmark.offset.map {
                ChatReaderRestorationAnchor(row: row, id: "reader-offset:" + UUID().uuidString, originY: $0.originY)
            }
            didCorrectInitialOffset = false
            isNearBottom = false
            proxy.scrollTo(row, anchor: .top)
        } else {
            pendingScrollTarget = conversationEndID
            proxy.scrollTo(conversationEndID, anchor: .bottom)
        }
        didPositionInitialHistory = true
        boundInitialPosition()
    }

    private func initialScrollTarget(items: [TimelineItem]) -> (id: String?, followsLatest: Bool) {
        if let bookmark = messagesRepository.scrollBookmark(chatId: chat.chatId), !bookmark.followsLatest,
           let anchor = bookmark.anchor, let row = timelineRowID(anchor, items: items) { return (row, false) }
        return (items.isEmpty ? nil : conversationEndID, true)
    }

    private struct TimelineItem: Identifiable {
        let message: DecryptedChatMessage?
        let call: CallDTO?
        var albumMembers: [DecryptedChatMessage] = []
        var presentationID: String? = nil
        var members: [DecryptedChatMessage] { albumMembers.isEmpty ? message.map { [$0] } ?? [] : albumMembers }
        var id: String { presentationID ?? message?.id ?? "call:\((call?.id.uuidString ?? "missing"))" }
        var bookmarkID: String { message?.clientMessageId ?? id }
    }

    private func timelineRowID(_ target: String, items: [TimelineItem]) -> String? {
        UnyriTimelineAnchor.rowID(target: target, rows: items.map { (id: $0.id, members: $0.members.map(\.id)) })
    }

    private struct ShortHeightContext: Equatable {
        let revision: TimelineRevision
        let auxiliaryLayoutRevision: UInt64
        let ownerIdentity: String
        let rootNonce: UUID
        let viewportWidth: CGFloat
        let textScale: CGFloat
        let appearance: UnyriChatAppearanceSettings
        let colorStyle: UnyriAppColorStyle
        let selecting: Bool
        let specialIDs: Set<String>
        var paddingOwner: ShortHeightOwner {
            ShortHeightOwner(identity: ownerIdentity, rootNonce: rootNonce, viewportWidth: viewportWidth,
                textScale: textScale, appearance: appearance, colorStyle: colorStyle, selecting: selecting)
        }
    }
    private struct ShortHeightOwner: Equatable {
        let identity: String
        let rootNonce: UUID
        let viewportWidth: CGFloat
        let textScale: CGFloat
        let appearance: UnyriChatAppearanceSettings
        let colorStyle: UnyriAppColorStyle
        let selecting: Bool
    }
    private func shortHeightContext(viewport: CGSize) -> ShortHeightContext {
        let current = timelinePresentation
        var special: Set<String> = ["special:end"]
        if messagesRepository.hasMore { special.insert("special:load") }
        if current.items.isEmpty && messagesRepository.loadedHistoryChatId == chat.chatId && !messagesRepository.isLoading { special.insert("special:empty") }
        if current.items.isEmpty && messagesRepository.isLoading && realtime.state == .connected { special.insert("special:loading") }
        return ShortHeightContext(revision: timelineRevision,
            auxiliaryLayoutRevision: messagesRepository.intrinsicAuxiliaryRevision,
            ownerIdentity: "\(auth.sessionGeneration)|\(auth.authenticatedServerURL.absoluteString)|\(auth.currentUser?.id ?? "")|\(chat.chatId)",
            rootNonce: shortPaddingRoot, viewportWidth: viewport.width,
            textScale: composerTextScale, appearance: appearance.settings, colorStyle: appColorStyle,
            selecting: !selectedMessages.isEmpty, specialIDs: special)
    }


    // Padding-only source timer. It never writes scroll geometry or acknowledges reading.
    private func synchronizeShortPadding(_ context: ShortHeightContext?) {
        guard let context else { return }
        let now = ProcessInfo.processInfo.systemUptime
        var next = shortPadding
        let previousToken = next.window?.token
        next.synchronize(owner: context.paddingOwner, context: context, now: now, token: UUID())
        if shortPadding != next { shortPadding = next }
        guard let window = next.window, window.token != previousToken else { return }
        let owner = context.paddingOwner
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, window.deadline - now)) {
            var latest = shortPadding
            guard latest.expire(owner: owner, token: window.token,
                now: ProcessInfo.processInfo.systemUptime) else { return }
            if shortPadding != latest { shortPadding = latest }
        }
    }
    private struct TimelineRevision: Equatable {
        let generation: UInt64
        let origin: String
        let owner: String?
        let chat: String
        let peer: String?
        let loaded: String?
        let messages: UInt64
        let calls: UInt64
        let cutoff: Int64
        let historyDate: Date?
        let failures: Bool
        let earlierCalls: Bool
    }
    private struct ArrivalRevision: Equatable {
        let count: Int
        let tail: String?
    }
    private struct TimelinePresentation {
        let arrivalRevision: ArrivalRevision
        let arrivalIDs: Set<String>
        let items: [TimelineItem]
        let indexed: [(offset: Int, element: TimelineItem)]
        let intrinsicRowIDs: Set<String>
        let bookmarkAnchors: [String: String]
        let hasMessages: Bool
        let unavailable: Bool
    }
    @State private var presentationCache = ChatPresentationCache<TimelineRevision, TimelinePresentation>()
    @State private var pendingAlbumPresentation = UnyriPendingAlbumPresentation()
    private var timelineRevision: TimelineRevision {
        TimelineRevision(generation: auth.sessionGeneration, origin: auth.authenticatedServerURL.absoluteString,
            owner: auth.currentUser?.id, chat: chat.chatId, peer: chat.otherUser?.id,
            loaded: messagesRepository.loadedHistoryChatId, messages: messagesRepository.messagesRevision,
            calls: callsHistory.historyRevision, cutoff: auth.historyBeforeSequence ?? 0,
            historyDate: auth.historyContext?.beforeDate, failures: showDecryptionFailures, earlierCalls: messagesRepository.hasMoreLocalHistory)
    }
    private struct MediaIndexRevision: Equatable {
        let timeline: TimelineRevision
        let paths: Int
    }
    @State private var mediaIndexCache = ChatPresentationCache<MediaIndexRevision, [UnyriMediaItem]>()
    private var conversationMediaItems: [UnyriMediaItem] {
        let key = MediaIndexRevision(timeline: timelineRevision, paths: messagesRepository.attachmentCacheRevision)
        return mediaIndexCache.value(for: key) {
            timelinePresentation.items.flatMap(\.members).compactMap { message in
                guard !message.isDeletedForEveryone, !message.hiddenLocally, let a = message.content.attachment,
                      a.mimeType.hasPrefix("image/") || a.mimeType.hasPrefix("video/") else { return nil }
                let url = messagesRepository.cachedLocalPath(attachmentId: a.attachmentId).map { URL(fileURLWithPath: $0) }
                return UnyriMediaItem(id: a.attachmentId, url: url, mime: a.mimeType, name: a.filename, thumbnailBase64: a.thumbnail)
            }
        }
    }
    private var timelinePresentation: TimelinePresentation {
        let key = timelineRevision
        return presentationCache.value(for: key) {
            let items = buildTimelineItems()
            let arrivalIDs = Set(items.flatMap { item in item.members.isEmpty ? [item.id] : item.members.map(\.id) })
            let tail = items.last.map { $0.members.last?.id ?? $0.id }
            return TimelinePresentation(arrivalRevision: ArrivalRevision(count: arrivalIDs.count, tail: tail), arrivalIDs: arrivalIDs,
                items: items, indexed: Array(items.enumerated()), intrinsicRowIDs: Set(items.map(\.id)),
                bookmarkAnchors: bookmarkAnchors(for: items),
                hasMessages: items.contains { $0.members.contains { !$0.hiddenLocally && !$0.isDeletedForEveryone } },
                unavailable: hasCurrentDecryptionFailures(
                    messagesRepository.messages, chatId: chat.chatId, loadedChatId: messagesRepository.loadedHistoryChatId, cutoff: key.cutoff))
        }
    }
    private var timelineItems: [TimelineItem] { timelinePresentation.items }
    private func buildTimelineItems() -> [TimelineItem] {
        guard messagesRepository.loadedHistoryChatId == chat.chatId else { return stabilizePendingAlbums([]) }
        let collapsed = collapseUnavailableHistory(messagesRepository.messages, cutoff: auth.historyBeforeSequence ?? 0,
                                                   collapseRecentFailures: !showDecryptionFailures)
        let visibleMessages = ChatTimelineOrdering.sortedIfNeeded(collapsed, by: Self.timelineMessagePrecedes)
        guard let peer = chat.otherUser.flatMap({ UUID(uuidString: $0.id) }) else {
            return groupAlbumRows(visibleMessages.map { TimelineItem(message: $0, call: nil) })
        }
        let calls = callsHistory.chatHistory.filter { call in
            CallActivityPolicy.matchesChat(call, chatId: chat.chatId) && (call.callerUserId == peer || call.calleeUserId == peer)
                && (!messagesRepository.hasMoreLocalHistory || call.createdAt >= (visibleMessages.first?.createdAt ?? .distantPast))
        }
            .sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString < $1.id.uuidString : $0.createdAt < $1.createdAt }
        var result: [TimelineItem] = []
        var index = 0
        for message in visibleMessages {
            while index < calls.count && calls[index].createdAt <= message.createdAt {
                result.append(TimelineItem(message: nil, call: calls[index])); index += 1
            }
            result.append(TimelineItem(message: message, call: nil))
        }
        while index < calls.count { result.append(TimelineItem(message: nil, call: calls[index])); index += 1 }
        return groupAlbumRows(result)
    }

    private static func timelineMessagePrecedes(_ lhs: DecryptedChatMessage, _ rhs: DecryptedChatMessage) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        if lhs.sequence != rhs.sequence { return (lhs.sequence ?? .max) < (rhs.sequence ?? .max) }
        return lhs.id < rhs.id
    }

    private func groupAlbumRows(_ source: [TimelineItem]) -> [TimelineItem] {
        struct AlbumKey: Hashable { let chat: String; let sender: String; let album: String }
        var rows: [TimelineItem] = []
        var openGroups: [AlbumKey: Int] = [:]
        for item in source {
            guard let current = item.message, let album = current.content.albumId, !album.isEmpty else {
                rows.append(item); continue
            }
            let key = AlbumKey(chat: current.chatId, sender: current.senderId, album: album)
            guard !current.isDeletedForEveryone, current.status != .decryptFailed, current.status != .identityChanged else {
                openGroups.removeValue(forKey: key)
                rows.append(item); continue
            }
            if let index = openGroups[key], rows[index].members.count < 10 {
                rows[index].albumMembers = rows[index].members + [current]
            } else {
                openGroups[key] = rows.count
                rows.append(item)
            }
        }
        return stabilizePendingAlbums(rows)
    }

    private func stabilizePendingAlbums(_ rows: [TimelineItem]) -> [TimelineItem] {
        guard let owner = auth.currentUser?.id else { pendingAlbumPresentation.retire(); return rows }
        let scope = UnyriPendingAlbumPresentation.Scope(origin: auth.authenticatedServerURL.absoluteString,
            owner: owner, generation: auth.sessionGeneration, chat: chat.chatId)
        let metadata = rows.map { row in
            UnyriPendingAlbumPresentation.Row(id: row.id,
                sort: .init(date: row.message?.createdAt ?? row.call?.createdAt ?? .distantPast,
                    isCall: row.call != nil, sequence: row.message?.sequence, id: row.id),
                members: row.members.map { message in
                    .init(id: message.id, chat: message.chatId, sender: message.senderId,
                        album: message.content.albumId,
                        sort: .init(date: message.createdAt, isCall: false, sequence: message.sequence, id: message.id),
                        pending: message.status == .pending,
                        eligible: message.kind == "content" && !message.hiddenLocally && !message.isDeletedForEveryone &&
                            message.status != .decryptFailed && message.status != .identityChanged)
                })
        }
        let projected = pendingAlbumPresentation.project(scope: scope, rows: metadata)
        // Only this revision's loaded values can enter the view. The ledger owns
        // IDs/ranks, not messages, attachments or an upload-job's lifetime.
        guard Set(rows.map(\.id)).count == rows.count else { return rows }
        let current = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0) })
        return projected.compactMap { projection in
            guard let row = current[projection.sourceID] else { return nil }
            if projection.id == row.id && projection.members == row.members.map(\.id) { return row }
            let liveMembers = Dictionary(row.members.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            let members = projection.members.compactMap { liveMembers[$0] }
            guard let first = members.first else { return row }
            return TimelineItem(message: first, call: row.call,
                albumMembers: members.count > 1 ? members : [], presentationID: projection.id)
        }
    }

    private func bookmarkAnchors(for rows: [TimelineItem]) -> [String: String] {
        Dictionary(rows.map { ($0.id, $0.bookmarkID) }, uniquingKeysWith: { first, _ in first })
    }

    private func openReminderTarget() {
        guard let route = UserDefaults.standard.dictionary(forKey: "chatReminderRoute"), route["owner"] as? String == auth.currentUser?.id,
              route["chat"] as? String == chat.chatId, let id = route["message"] as? String else { return }
        jumpRequest = id; UserDefaults.standard.removeObject(forKey: "chatReminderRoute")
    }
    private func reminderIdentifier(_ id: String) -> String { "reminder:\(auth.currentUser?.id ?? ""):\(chat.chatId):\(id)" }
    private func persistReminders() {
        if let data = try? JSONEncoder().encode(reminders) { messagesRepository.saveChatValue(chatId: chat.chatId, name: "reminders", value: String(decoding: data, as: UTF8.self)) }
    }
    private func scheduleReminder(_ item: DecryptedChatMessage) async {
        let generation = auth.sessionGeneration
        guard let owner = auth.currentUser?.id else { return }
        guard UnyriNotificationPreferencesStore.shared.value.allowsReminders else {
            reminderError = UnyriL10n.string("Enable reminders in UNYRI notification settings first."); return
        }
        let identifier = "reminder:\(owner):\(chat.chatId):\(item.clientMessageId)"
        do {
            let center = UNUserNotificationCenter.current()
            guard try await center.requestAuthorization(options: [.alert, .sound]) else {
                reminderError = UnyriL10n.string("Enable UNYRI notifications in phone settings to receive reminders.", comment: ""); return
            }
            guard generation == auth.sessionGeneration else { return }
            guard UnyriNotificationPreferencesStore.shared.value.allowsReminders else {
                reminderError = UnyriL10n.string("Enable reminders in UNYRI notification settings first."); return
            }
            let pending = await center.pendingNotificationRequests()
            guard pending.count < 64 || pending.contains(where: { $0.identifier == identifier }) else {
                reminderError = UnyriL10n.string("Too many reminders. Remove an existing reminder first."); return
            }
            guard generation == auth.sessionGeneration, UnyriNotificationPreferencesStore.shared.value.allowsReminders else { return }
            let content = UNMutableNotificationContent()
            content.title = UnyriL10n.string("Remind me", comment: "")
            content.body = UnyriL10n.string("Return to the message you marked", comment: "")
            content.sound = UnyriNotificationPreferencesStore.shared.value.allowsReminderSound ? .default : nil
            content.userInfo = ["kind": "chat_reminder", "owner": owner, "chat": chat.chatId, "message": item.clientMessageId,
                                "scheduled_at_ms": reminderDate.timeIntervalSince1970 * 1000]
            try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(1, reminderDate.timeIntervalSinceNow), repeats: false)))
            guard generation == auth.sessionGeneration,
                  UnyriNotificationPreferencesStore.shared.value.allowsReminders else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier]); return
            }
            UnyriLocalNotificationPreferences.reconcile()
            var updated = reminders; updated[item.clientMessageId] = reminderDate
            let data = try JSONEncoder().encode(updated)
            guard messagesRepository.saveChatValue(chatId: chat.chatId, name: "reminders", value: String(decoding: data, as: UTF8.self)) else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier]); reminderError = UnyriL10n.string("Could not save reminder."); return
            }
            reminders = updated; reminderFor = nil
        } catch { reminderError = error.localizedDescription }
    }
    private var peerActivityText: String {
        guard allowsPeerActivity, currentPreferenceChat.isBlocked != true, scenePhase == .active, let item = realtime.activity[chat.chatId], activityNow.timeIntervalSince(item.at) < 8 else { return "" }
        return item.kind == "typing" ? UnyriL10n.string("Typing…") : item.kind == "recording" ? UnyriL10n.string("Recording a voice message…") : item.kind == "uploading" ? UnyriL10n.string("Sending attachment…") : ""
    }
    private var selectedItems: [DecryptedChatMessage] { messagesRepository.messages.filter { selectedMessages.contains($0.clientMessageId) } }

    private func forwardingAlbums(_ items: [DecryptedChatMessage]) -> [String: String] {
        UnyriChatAlbumSelection.forwardingAlbums(items.map {
            .init(id: $0.clientMessageId, chat: $0.chatId, sender: $0.senderId, album: $0.content.albumId)
        })
    }
    private func prepareForward(_ items: [DecryptedChatMessage]) {
        forwardQueue = items
        forwardAlbumIDs = forwardingAlbums(items)
        showForwardPicker = true
    }

    private func canDeleteForEveryone(_ ids: [String]) -> Bool {
        let chosen = Set(ids)
        let targets = messagesRepository.messages.filter { chosen.contains($0.clientMessageId) }
        return !ids.isEmpty && targets.allSatisfy { $0.chatId == chat.chatId && $0.senderId == auth.currentUser?.id && !$0.isDeletedForEveryone } &&
            targets.count + ids.filter { $0.hasPrefix("call:") }.count == ids.count
    }

    private func presentDeletion(_ ids: [String], everyone: Bool = false) {
        guard let owner = auth.currentUser?.id else { return }
        var seen = Set<String>()
        let captured = ids.filter { seen.insert($0).inserted }
        guard !captured.isEmpty else { return }
        composerFocused = false
        let priorSelection = selectedMessages
        selectedMessages.formUnion(captured)
        pendingBulkDelete = DeleteSelection(everyone: everyone, ids: captured,
            generation: auth.sessionGeneration, owner: owner, chatID: chat.chatId, priorSelection: priorSelection)
        showBulkDelete = true
    }
    private func cancelDeletion() {
        if let pending = pendingBulkDelete, pending.generation == auth.sessionGeneration,
           pending.owner == auth.currentUser?.id, pending.chatID == chat.chatId {
            selectedMessages = pending.priorSelection
        }
        showBulkDelete = false; pendingBulkDelete = nil
    }
    private func togglePin(_ id: String) {
        if pinnedMessages.contains(id) { pinnedMessages.remove(id) } else { pinnedMessages.insert(id) }
        if let data = try? JSONEncoder().encode(pinnedMessages) { messagesRepository.saveChatValue(chatId: chat.chatId, name: "pins", value: String(decoding: data, as: UTF8.self)) }
    }
    private func saveMessages(_ items: [DecryptedChatMessage]) async {
        guard messagesRepository.validateForwardSelection(items) else { return }
        do {
            let saved = try await chatsRepository.getOrCreateSavedChat()
            let albums = forwardingAlbums(items)
            for item in items { if !(await messagesRepository.forward(item, to: saved, albumId: albums[item.clientMessageId])) { return } }
        } catch { messagesRepository.reportError(error.localizedDescription) }
    }
    private var orderedPinnedIds: [String] {
        let dates = Dictionary(uniqueKeysWithValues: pinnedMessages.compactMap { id -> (String, Date)? in
            guard let message = messagesRepository.localMessage(id: id),
                  !message.hiddenLocally, !message.isDeletedForEveryone else { return nil }
            return (id, message.createdAt)
        })
        return dates.keys.sorted {
            let left = dates[$0] ?? .distantPast
            let right = dates[$1] ?? .distantPast
            return left == right ? $0 < $1 : left > right
        }
    }
    private func pinnedPreviewText(_ message: DecryptedChatMessage?) -> String {
        guard let message else { return UnyriL10n.string("Original message is unavailable") }
        if message.isDeletedForEveryone { return UnyriL10n.string("This message was deleted") }
        if message.status == .decryptFailed || message.status == .identityChanged { return UnyriL10n.string("Unable to decrypt message") }
        if let text = message.content.text, !text.isEmpty { return text }
        if let media = message.content.attachment {
            if media.mimeType.hasPrefix("video/") { return UnyriL10n.string("Video") }
            if media.mediaType == AttachmentMetadata.mediaTypeImage { return UnyriL10n.string("Photo") }
            if media.mediaType == AttachmentMetadata.mediaTypeVoice { return UnyriL10n.string("Voice message") }
            return media.filename ?? UnyriL10n.string("File")
        }
        return message.displayText
    }
    private var messageTools: some View {
        NavigationStack {
            List {
                if onlyPinned && orderedPinnedIds.isEmpty { Text("No pinned messages") }
                if onlyPinned {
                    ForEach(orderedPinnedIds.filter { id in searchQuery.isEmpty || pinnedPreviewText(messagesRepository.localMessage(id: id)).localizedCaseInsensitiveContains(searchQuery) }, id: \.self) { id in
                        Button { showMessageTools = false; jumpRequest = id } label: {
                            PinnedMessagePreviewRow(message: messagesRepository.localMessage(id: id), text: pinnedPreviewText(messagesRepository.localMessage(id: id)))
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                        }.buttonStyle(UnyriQuietButtonStyle())
                            .listRowBackground(Color(hex: 0x1B1B1D)).listRowSeparator(.hidden)
                            .accessibilityIdentifier("pinned-message-\(id)")
                        .swipeActions { Button("Unpin", role: .destructive) { togglePin(id) } }
                        .contextMenu { Button("Unpin", role: .destructive) { togglePin(id) } }
                    }
                }
                ForEach(messagesRepository.messages.filter {
                    !onlyPinned &&
                    (searchQuery.isEmpty || $0.displayText.localizedCaseInsensitiveContains(searchQuery) || ($0.content.attachment?.filename ?? "").localizedCaseInsensitiveContains(searchQuery))
                }) { item in
                    Button { showMessageTools = false; jumpRequest = item.clientMessageId } label: {
                        VStack(alignment: .leading, spacing: 6) { Text(chatPresentationText(item.displayText, inline: true)).lineLimit(3); Text(item.createdAt.formatted()).font(.caption).foregroundColor(UnyriColors.secondaryText) }
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(UnyriColors.primaryText)
                        .listRowBackground(Color(hex: 0x1B1B1D)).listRowSeparator(.hidden)
                        .accessibilityIdentifier("chat-search-message-\(item.clientMessageId)")
                }
            }.listStyle(.plain).tint(Color(hex: 0x7BC9EC))
                .searchable(text: $searchQuery, prompt: "Search messages")
                .unyriScreenBackground().navigationTitle(onlyPinned ? "Pinned messages" : "Search messages")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showMessageTools = false }.accessibilityIdentifier("chat-message-tools-close") } }
        }
    }
    private var forwardPicker: some View {
        let filtered = destinations.filter { forwardSearch.isEmpty || ($0.otherUser?.displayName ?? UnyriL10n.string("Saved Messages", comment: "")).localizedCaseInsensitiveContains(forwardSearch) || ($0.otherUser?.username ?? "").localizedCaseInsensitiveContains(forwardSearch) }
        return NavigationStack {
            List {
                if forwardLoading && destinations.isEmpty { ProgressView() }
                if let error = forwardError { Text(error).font(.footnote).foregroundStyle(.secondary) }
                if !forwardLoading && filtered.isEmpty { Text("No results.").foregroundStyle(.secondary) }
                ForEach(filtered) { destination in
                    Button { forwardDestination = destination } label: {
                        HStack(spacing: 12) {
                            let title = destination.otherUser?.displayName ?? destination.otherUser?.username ?? UnyriL10n.string("Saved Messages", comment: "")
                            UserAvatar(url: usersRepository.cachedAvatarURL(id: destination.otherUser?.id ?? "", avatarUrl: destination.otherUser?.avatarUrl, avatarVersion: destination.otherUser?.avatarVersion ?? 0), name: title, size: 44)
                            Text(title).font(.body).foregroundStyle(UnyriColors.primaryText).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                            Spacer()
                            Image(systemName: forwardDestination?.chatId == destination.chatId ? "checkmark.circle.fill" : "circle").foregroundStyle(Color(hex: 0x7BC9EC)).accessibilityHidden(true)
                        }.padding(.vertical, 6).frame(minHeight: 56).contentShape(Rectangle())
                    }.buttonStyle(UnyriQuietButtonStyle()).disabled(forwarding)
                        .listRowBackground(Color(hex: 0x1B1B1D)).listRowSeparator(.hidden)
                        .accessibilityIdentifier("forward-target-\(destination.chatId)")
                        .accessibilityAddTraits(forwardDestination?.chatId == destination.chatId ? .isSelected : [])
                }
            }.listStyle(.plain).tint(Color(hex: 0x7BC9EC))
                .searchable(text: $forwardSearch, prompt: "Search chats")
                .scrollDismissesKeyboard(.interactively)
                .unyriScreenBackground().navigationTitle("Forward").navigationBarTitleDisplayMode(.inline)
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 6) {
                        if let destination = forwardDestination {
                            Text(destination.otherUser?.displayName ?? destination.otherUser?.username ?? UnyriL10n.string("Saved Messages", comment: "")).font(.subheadline).lineLimit(1)
                        }
                        Button {
                            guard !forwarding, let destination = forwardDestination else { return }
                            guard messagesRepository.validateForwardSelection(forwardQueue) else {
                                forwardError = messagesRepository.errorMessage
                                return
                            }
                            forwardError = nil
                            forwarding = true
                            Task {
                                defer { forwarding = false }
                                while let first = forwardQueue.first {
                                    guard await messagesRepository.forward(first, to: destination, albumId: forwardAlbumIDs[first.clientMessageId]) else { return }
                                    forwardQueue.removeFirst(); selectedMessages.remove(first.clientMessageId)
                                }
                                showForwardPicker = false
                            }
                        } label: {
                            HStack { if forwarding { ProgressView() }; Text("Forward") }.frame(maxWidth: .infinity).frame(minHeight: 44)
                        }.buttonStyle(UnyriPrimaryButtonStyle()).disabled(forwardDestination == nil || forwarding).accessibilityIdentifier("forward-confirm")
                    }.padding(.horizontal, 16).padding(.vertical, 8).background(UnyriColors.chatPanel)
                }
                .task {
                    forwardSearch = ""; forwardDestination = nil; forwardError = nil; forwardLoading = true
                    defer { forwardLoading = false }
                    do { destinations = try await chatsRepository.listChats(limit: 100).items }
                    catch { forwardError = error.localizedDescription }
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showForwardPicker = false }.disabled(forwarding).accessibilityIdentifier("forward-cancel") } }
        }.interactiveDismissDisabled(forwarding)
    }

    // MARK: - Composer

    private func refreshIdentityWarning() {
        guard let peer = chat.otherUser?.id ?? auth.currentUser?.id else { identityNeedsReview = false; return }
        if let changed = try? verificationManager?.hasPendingIdentity(peer: peer) {
            identityNeedsReview = changed || (auth.currentUser.flatMap { try? verificationManager?.hasPendingIdentity(peer: $0.id) } ?? false)
        }
    }

    @ViewBuilder private var identityReviewBanner: some View {
        if identityNeedsReview {
            VStack(alignment: .leading, spacing: 4) {
                Text("Security key changed. Compare keys with your contact, then retry unsent messages.").font(.footnote)
                Button("Verify security keys") { composerFocused = false; showKeyVerification = true }.frame(minHeight: 44)
            }.padding(.horizontal, 12).padding(.vertical, 6)
        }
    }

    private var composerEmojiPicker: some View {
                    UnyriEmojiPicker(initiallyExpanded: false, bottomInset: max(76, composerSize.height + 8), close: { composerEmojiOpen = false }) { emoji in
                        UnyriHaptics.selection()
                        let source = draft as NSString
                        let selection = composerEmojiSelection ?? NSRange(location: source.length, length: 0)
                        let start = min(max(0, selection.location), source.length)
                        let range = NSRange(location: start, length: min(selection.length, source.length - start))
                        updateComposerDraft(source.replacingCharacters(in: range, with: emoji))
                        composerEmojiSelection = NSRange(location: start + (emoji as NSString).length, length: 0)
                    }
    }

    private var composer: AnyView {
        AnyView(
        Group {
            if selectedMessages.isEmpty { ordinaryComposer }
            else { selectionActions }
        }
        .background(GeometryReader { geometry in Color.clear
            .onAppear { composerSize = geometry.size }
            .onChange(of: geometry.size) { composerSize = $0 } })
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.35).sequenced(before: DragGesture(minimumDistance: 0)).onChanged { phase in
            guard !recordingHoldFinished, case .second(true, let drag) = phase, let drag else { return }
            if !recordingHoldActive {
                guard !isRecording, voiceDraft == nil, !stagingVoice, selectedMessages.isEmpty,
                      draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, editingMessage == nil, composerAttachments.isEmpty, composerFileImports == 0,
                      drag.startLocation.x > composerSize.width - 56,
                      drag.startLocation.y > composerSize.height - 70 else { return }
                recordingHoldActive = true; recordingLocked = false
                toggleRecording(holding: true)
            }
            guard !recordingLocked else { return }
            recordingDrag = CGSize(width: max(-80, min(0, drag.translation.width)), height: max(-64, min(0, drag.translation.height)))
            if drag.translation.width < -80 { recordingHoldFinished = true; recordingHoldActive = false; cancelRecording() }
            else if drag.translation.height < -64 { UnyriHaptics.selection(); recordingLocked = true }
        }.onEnded { _ in
            let shouldFinish = recordingHoldActive && !recordingLocked
            if recordingHoldActive || recordingHoldFinished { lastVoiceHoldEnd = Date() }
            recordingHoldFinished = false
            recordingHoldActive = false
            recordingDrag = .zero
            if shouldFinish { if isRecording { UnyriHaptics.selection() }; finishRecording() }
        })
        .onReceive(recordingTimer) { _ in
            if allowsPeerActivity && conversationVisible { realtime.liveDrafts.update(editingMessage == nil && composerFocused ? draft : "") }
            if isRecording { recordingSeconds = Int64(voiceRecorder.recordedSeconds); if voiceRecorder.recordedSeconds >= VoiceRecorder.maxRecordingSeconds { finishRecording(); return }; if allowsPeerActivity && activityEnabled && voiceRecorder.isRecording { realtime.sendActivity(chatId: chat.chatId, kind: "recording") } }
        }
        .onChange(of: voiceRecorder.callInProgress) { active in
            if active { composerFocused = false; if isRecording { voiceRecorder.interrupt() } }
        }
        )
    }


    private var selectionActions: some View {
        HStack(spacing: 0) {
            Button {
                UnyriHaptics.selection(); prepareForward(selectedItems)
            } label: {
                Label("Forward", systemImage: "arrowshape.turn.up.right")
                    .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }.disabled(selectedMessages.contains { $0.hasPrefix("call:") } || selectedItems.count != selectedMessages.count || selectedItems.contains { $0.content.directFile != nil })
                .accessibilityIdentifier("chat-selection-forward")
            Divider().frame(height: 20).accessibilityHidden(true)
            Button(role: .destructive) { UnyriHaptics.selection(); presentDeletion(Array(selectedMessages)) } label: {
                Label("Delete", systemImage: "trash")
                    .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }.foregroundStyle(UnyriColors.danger).accessibilityIdentifier("chat-selection-delete")
        }.font(.subheadline.weight(.medium)).foregroundStyle(UnyriColors.primaryText)
            .buttonStyle(.plain).padding(.horizontal, 10).padding(.vertical, 4)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().stroke(UnyriColors.primaryText.opacity(0.10), lineWidth: 0.5))
            .padding(.horizontal, 12).padding(.top, 4).padding(.bottom, 6)
    }

    // Only the ordinary input row receives one material. Recording, voice
    // drafts, blocked/selection controls retain their existing presentations.
    private var showsGlassComposer: Bool {
        selectedMessages.isEmpty && !isRecording && voiceDraft == nil && !stagingVoice && currentPreferenceChat.isBlocked != true
    }

    private var ordinaryComposer: AnyView {
        AnyView(
        VStack(spacing: 0) {
            identityReviewBanner
            LiveDraftPreview(model: realtime.liveDrafts)
            if let returnAnchor {
                Button("Return to previous position") { returningToAnchor = true; jumpRequest = returnAnchor; self.returnAnchor = nil }.font(.caption)
            }
            mediaPreparationBanner
            voiceDraftPreview
            if stagingVoice && voiceDraft == nil && !isRecording {
                HStack(spacing: 12) { ProgressView().tint(ChatControlPalette.blue); Text("Preparing…").font(.subheadline).foregroundColor(UnyriColors.secondaryText); Spacer() }
                    .padding(.horizontal, 16).frame(height: 56).background(ChatControlPalette.composer, in: RoundedRectangle(cornerRadius: 27)).padding(.horizontal, 12).padding(.vertical, 6)
            }
            if isRecording { recordingBanner.modifier(VoicePhaseReveal(phase: "recording")) }
            if currentPreferenceChat.isBlocked == true {
                Button("Contact blocked. Open chat settings") { showChatSafety = true }.frame(maxWidth: .infinity, minHeight: 44)
            } else if !isRecording && voiceDraft == nil && !stagingVoice && selectedMessages.isEmpty {
                VStack(spacing: 0) {
                if !composerAttachments.isEmpty && editingMessage == nil { composerAttachmentTray.transition(.opacity) }
                UnyriComposerSurface {
                HStack(alignment: .bottom, spacing: 4) {
                    VStack(spacing: 0) {
                        if let target = editingMessage ?? replyTarget {
                            UnyriReplyComposerPreview(message: target,
                                author: target.senderId == auth.currentUser?.id ? UnyriL10n.string("You") : (chat.otherUser?.displayName ?? chat.otherUser?.username ?? UnyriL10n.string("Reply")),
                                editing: editingMessage != nil,
                                localPath: target.content.attachment.flatMap { messagesRepository.cachedLocalPath(attachmentId: $0.attachmentId) }) {
                                    if editingMessage != nil { editingMessage = nil; draft = ordinaryDraft } else { replyTarget = nil }
                                }
                            Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5).padding(.horizontal, 14)
                        }
                        ChatComposerTextEntry(draft: Binding(get: { draft }, set: updateComposerDraft), focused: $composerFocused,
                            textScale: composerTextScale, importing: composerFileImports > 0,
                            attachmentDisabled: composerAttachments.count >= 10 || composerFileImports > 0 || editingMessage != nil,
                            cameraDisabled: voiceRecorder.callInProgress,
                            camera: { openComposerAttachment("camera") },
                            photos: { openComposerAttachment("gallery") },
                            files: { openComposerAttachment("files") },
                            emoji: {
                                UnyriHaptics.press()
                                let capture = UnyriComposerSelection()
                                if composerFocused { UIApplication.shared.sendAction(#selector(UIResponder.unyriCaptureComposerSelection(_:)), to: nil, from: capture, for: nil) }
                                composerEmojiSelection = capture.range
                                composerFocused = false
                                composerEmojiOpen = true
                            })
                    }
                    .frame(maxWidth: .infinity)
                    ChatComposerSubmitControl(
                        showsRecorder: draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && editingMessage == nil && composerAttachments.isEmpty,
                        microphoneInUse: voiceRecorder.callInProgress, canSend: composerCanSend,
                        editing: editingMessage != nil, attachmentCount: composerAttachments.count,
                        record: { guard Date().timeIntervalSince(lastVoiceHoldEnd) > 0.5 else { return }; recordingLocked = true; toggleRecording() },
                        send: send)
                }
                .padding(.horizontal, 4)
                }
                }.buttonStyle(.plain)
                .background(Color.clear)
                .animation(composerReduceMotion ? nil : .easeInOut(duration: 0.16), value: composerAttachments.count)
                .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 8)
            }
        }
        )
    }


    private func updateComposerDraft(_ text: String) {
        draft = text
        if allowsPeerActivity && conversationVisible { realtime.liveDrafts.update(editingMessage == nil && composerFocused ? text : "") }
        if allowsPeerActivity && activityEnabled { realtime.sendActivity(chatId: chat.chatId, kind: text.isEmpty ? "idle" : "typing") }
        if editingMessage == nil {
            ordinaryDraft = text
            messagesRepository.saveDraftValues(chatId: chat.chatId, values: ["draft": text])
        }
    }

    private var recordingBanner: some View {
        HStack(spacing: 8) {
            if voiceRecorder.isRecording { Circle().fill(ChatControlPalette.coral).frame(width: 8, height: 8) }
            Text(VoiceFormat.durationText(recordingSeconds * 1000)).font(UnyriTypography.timestamp).monospacedDigit()
            VoiceRecordingMeter(recorder: voiceRecorder).frame(width: 40, height: 24)
            if !recordingLocked {
                Text("← Cancel").font(.caption).foregroundColor(UnyriColors.secondaryText)
                Spacer(minLength: 0)
                ChatControlIcon(name: "mic", tint: UnyriColors.onAccent, fill: UnyriColors.accent).offset(recordingDrag)
            } else {
                Spacer(minLength: 0)
                Button(action: cancelRecording) { ChatControlIcon(name: "trash", tint: ChatControlPalette.coral, fill: ChatControlPalette.coralBack) }.accessibilityLabel("Cancel recording")
                Button { UnyriHaptics.press(); voiceRecorder.togglePause() } label: { ChatControlIcon(name: voiceRecorder.isRecording ? "pause.fill" : "play.fill", tint: ChatControlPalette.blue, fill: ChatControlPalette.blueBack) }.accessibilityLabel("Pause or resume recording")
                Button { if isRecording { UnyriHaptics.selection() }; finishRecording() } label: { Text("Done").font(.subheadline.weight(.semibold)).foregroundColor(ChatControlPalette.mint).padding(.horizontal, 8).frame(minHeight: 44) }.accessibilityLabel("Review before sending")
            }
        }.padding(.horizontal, 8).frame(height: 56).background(ChatControlPalette.composer, in: RoundedRectangle(cornerRadius: 27))
            .overlay(RoundedRectangle(cornerRadius: 27).stroke(ChatControlPalette.edge.opacity(0.65), lineWidth: 0.5))
            .overlay(alignment: .topTrailing) {
                if !recordingLocked {
                    VStack(spacing: 12) { Image(systemName: "chevron.up"); Image(systemName: "lock.fill") }
                        .font(.system(size: 18)).foregroundColor(recordingDrag.height < -42 ? ChatControlPalette.mint : UnyriColors.accentSoft).frame(width: 38)
                        .padding(.vertical, 12).background(ChatControlPalette.composer, in: Capsule())
                        .offset(y: -84).allowsHitTesting(false)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
    }

    private func toggleRecording(holding: Bool = false, resume: Bool = false) {
        if isRecording {
            UnyriHaptics.selection()
            finishRecording()
        } else {
            guard !startingVoice, !sendingVoice else { return }
            guard (voiceDraft == nil || resume) && !stagingVoice else {
                messagesRepository.reportError(UnyriL10n.string("Send or discard the voice draft first.")); return
            }
            guard !voiceRecorder.callInProgress else {
                return
            }
            startingVoice = true
            let generation = auth.sessionGeneration
            Task { @MainActor in
              do {
                guard let claim = try await NonCallCaptureOwnership.prepare(.voice, generation: generation, stop: { claim in
                    if pendingVoiceClaim == claim { pendingVoiceClaim = nil; startingVoice = false }
                    NonCallCaptureOwnership.release(claim)
                }) else { startingVoice = false; return }
                pendingVoiceClaim = claim
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                Task { @MainActor in
                    var transferred = false
                    defer {
                        startingVoice = false
                        if pendingVoiceClaim == claim { pendingVoiceClaim = nil }
                        if !transferred { NonCallCaptureOwnership.release(claim) }
                    }
                    guard generation == auth.sessionGeneration, NonCallCaptureOwnership.isCurrent(claim),
                          !voiceRecorder.callInProgress else { return }
                    guard scenePhase == .active, conversationVisible, !isRecording else { return }
                    guard !holding || recordingHoldActive else { return }
                    guard granted else {
                        messagesRepository.reportError("Microphone access is required to record a voice message.")
                        return
                    }
                    do {
                        composerFocused = false
                        voiceRecorder.onInterruptedDraft = { url, duration in isRecording = false; stageRecordedVoice(fileURL: url, durationMs: duration) }
                        voicePlayer.stop()
                        _ = try voiceRecorder.start(captureClaim: claim)
                        transferred = true
                        UnyriHaptics.press()
                        if resume { voicePlayer.stop(); resumeVoiceBase = voiceDraft }
                        isRecording = true
                        recordingSeconds = 0
                    } catch {
                        voiceRecorder.onInterruptedDraft = nil
                        messagesRepository.reportError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
                    }
                }
            }
              } catch { startingVoice = false }
            }
        }
    }

    private struct VoiceDraft: Codable, Identifiable {
        var id: String
        var path: String
        var duration: Int64
    }
    private func finishRecording() {
        guard isRecording else { return }
        isRecording = false
        guard let result = voiceRecorder.stop() else { return }
        stageRecordedVoice(fileURL: result.fileURL, durationMs: result.durationMs)
    }
    private func stageRecordedVoice(fileURL: URL, durationMs: Int64) {
        stagingVoice = true
        Task {
            defer { stagingVoice = false }
        do {
            let base = resumeVoiceBase
            let source: URL
            if let base { source = try await VoiceRecorder.join(URL(fileURLWithPath: base.path), fileURL) } else { source = fileURL }
            let file = try await messagesRepository.stageDraftFile(source)
            let value = VoiceDraft(id: UUID().uuidString.lowercased(), path: file.path, duration: durationMs + (base?.duration ?? 0))
            let data = try JSONEncoder().encode(value)
            guard messagesRepository.saveChatValue(chatId: chat.chatId, name: "voiceDraft", value: String(decoding: data, as: UTF8.self)) else { return }
            voiceDraft = value; preparedVoice = nil; resumeVoiceBase = nil
            try? FileManager.default.removeItem(at: fileURL)
            if source != fileURL { try? FileManager.default.removeItem(at: source) }
            if let base { try? FileManager.default.removeItem(atPath: base.path) }
        } catch { messagesRepository.reportError(error.localizedDescription) }
        }
    }
    private var voiceDraftPreview: some View {
        Group {
            if let item = voiceDraft, !isRecording {
                VStack(spacing: 4) {
                HStack(spacing: 6) {
                    Button {
                        UnyriHaptics.selection()
                        voicePlayer.stop()
                        messagesRepository.saveChatValue(chatId: chat.chatId, name: "voiceDraft", value: nil)
                        try? FileManager.default.removeItem(atPath: item.path)
                        voiceDraft = nil; preparedVoice = nil
                    } label: { ChatControlIcon(name: "trash", tint: ChatControlPalette.coral, fill: ChatControlPalette.coralBack) }.accessibilityLabel("Delete recording").disabled(sendingVoice)

                    Button { UnyriHaptics.press(); voicePlayer.togglePlay(attachmentId: item.id, fileURL: URL(fileURLWithPath: item.path)) } label: { Image(systemName: voicePlayer.state.playingAttachmentId == item.id ? "pause.fill" : "play.fill").foregroundColor(ChatControlPalette.blue).frame(width: 40, height: 40).background(ChatControlPalette.blueBack, in: Circle()).frame(width: 44, height: 44) }
                    VoiceProgressView(progress: voicePlayer.progress) { position in
                        GeometryReader { geometry in
                            AudioEnvelopeView(path: item.path,
                                fraction: (voicePlayer.state.playingAttachmentId == item.id || voicePlayer.state.pausedAttachmentId == item.id) ? VoiceFormat.playedFraction(positionMs: position, durationMs: item.duration) : 0,
                                played: ChatControlPalette.blue, unplayed: UnyriColors.mutedText).contentShape(Rectangle()).gesture(DragGesture(minimumDistance: 0).onChanged { value in
                                guard voicePlayer.state.playingAttachmentId == item.id || voicePlayer.state.pausedAttachmentId == item.id else { return }
                                voicePlayer.seek(fraction: Double(value.location.x / max(1, geometry.size.width)))
                            }.onEnded { _ in
                                guard voicePlayer.state.playingAttachmentId == item.id || voicePlayer.state.pausedAttachmentId == item.id else { return }
                                UnyriHaptics.selection()
                            })
                        }.frame(minWidth: 16, maxWidth: .infinity).frame(height: 24)
                    }
                    let activeDraft = voicePlayer.state.playingAttachmentId == item.id || voicePlayer.state.pausedAttachmentId == item.id
                    Button { UnyriHaptics.selection(); voicePlayer.cycleSpeed() } label: {
                        VStack(spacing: 1) {
                            Text(VoiceFormat.durationText(item.duration)).font(UnyriTypography.timestamp).foregroundColor(UnyriColors.secondaryText)
                            if activeDraft { Text(String(format: "%.1f×", voicePlayer.state.speed)).font(.subheadline.weight(.semibold)).foregroundColor(ChatControlPalette.blue) }
                        }.frame(width: 48, height: 44).contentShape(Rectangle())
                    }.disabled(!activeDraft)

                    Button {
                        UnyriHaptics.press()
                        voicePlayer.stop()
                        sendingVoice = true
                        Task {
                            defer { sendingVoice = false }
                            do {
                                let queued = messagesRepository.queueMedia(id: item.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                                    url: URL(fileURLWithPath: item.path), kind: "voice", duration: item.duration)
                                if queued {
                                    messagesRepository.saveChatValue(chatId: chat.chatId, name: "voiceDraft", value: nil)
                                    voiceDraft = nil; preparedVoice = nil
                                }
                            } catch { messagesRepository.reportError(error.localizedDescription) }
                        }
                    } label: { Image(systemName: "arrow.up").foregroundColor(.white).frame(width: 40, height: 40).background(ChatControlPalette.violet, in: Circle()).frame(width: 44, height: 44) }.accessibilityLabel("Send").disabled(sendingVoice)
                }.disabled(sendingVoice || stagingVoice).modifier(VoicePhaseReveal(phase: item.id)).padding(.horizontal, 6).frame(height: 56).background(ChatControlPalette.composer, in: RoundedRectangle(cornerRadius: 27))
                    .overlay(RoundedRectangle(cornerRadius: 27).stroke(ChatControlPalette.edge.opacity(0.65), lineWidth: 0.5))
                }.padding(.horizontal, 12).padding(.vertical, 6)
            }
        }
    }

    private func cancelRecording() {
        if isRecording || startingVoice { UnyriHaptics.selection() }
        resumeVoiceBase = nil
        voiceRecorder.cancel()
        isRecording = false
        recordingSeconds = 0
    }

    private func changeChatPreference(_ action: String) {
        guard !preferenceBusy else { return }
        preferenceBusy = true; preferenceStatus = nil
        Task { @MainActor in
            defer { preferenceBusy = false }
            do {
                preferenceChat = try await chatsRepository.updatePreference(chatId: chat.chatId, action: action, body: .init(blockedUserId: action == "block" ? chat.otherUser?.id : nil))
                preferenceStatus = "Chat settings saved"
            } catch { preferenceStatus = "Could not complete the request. Check your connection and try again." }
        }
    }

    private var chatSafetySheet: some View {
        NavigationStack {
            UnyriSettingsPage("Chat settings") {
                VStack(alignment: .leading, spacing: 20) {
                if chatSafetyAction == nil || chatSafetyAction == "notifications" {
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 12) {
                    Button(currentPreferenceChat.isMuted == true ? "Enable message notifications" : "Mute message notifications") {
                        changeChatPreference(currentPreferenceChat.isMuted == true ? "unmute" : "mute")
                    }.disabled(preferenceBusy).buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("chat-safety-notifications")
                    Text("Messages still arrive. Calls are not muted.").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                    }.padding(14)
                }
                }
                if chatSafetyAction == nil || chatSafetyAction == "block" {
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 12) {
                    Button(currentPreferenceChat.isBlocked == true ? "Unblock contact" : "Block contact", role: .destructive) {
                        if currentPreferenceChat.isBlocked == true { changeChatPreference("unblock") }
                        else { confirmChatBlock = true }
                    }.disabled(preferenceBusy).buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("chat-safety-block")
                    }.padding(14)
                }
                }
                if chatSafetyAction == nil || (chatSafetyAction == "report" && showReportForm) {
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 12) {
                    if chatSafetyAction == nil { Button("Report contact") { showReportForm.toggle(); preferenceStatus = nil }.disabled(preferenceBusy).buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("chat-safety-show-report") }
                    if showReportForm {
                        Text("Your reason and description will be sent to this server administrator. Messages and files are not attached automatically.").font(.caption)
                        Picker("Reason", selection: $reportReason) {
                            Text("Spam").tag("spam")
                            Text("Harassment").tag("harassment")
                            Text("Scam").tag("scam")
                            Text("Other").tag("other")
                        }.pickerStyle(.menu).tint(Color(hex: 0x7BC9EC)).frame(minHeight: 44).accessibilityIdentifier("chat-report-reason")
                        Text("You may paste selected message text into the description. The description is sent to the moderator unencrypted; other messages are not shared.").font(.footnote)
                        TextField("Describe the problem (optional)", text: $reportDescription, axis: .vertical).lineLimit(3...6)
                            .unyriFieldStyle().accessibilityIdentifier("chat-report-description")
                            .onChange(of: reportDescription) { value in if value.count > 4000 { reportDescription = String(value.prefix(4000)) } }
                        Button("Send report") {
                            guard !preferenceBusy else { return }
                            preferenceBusy = true; preferenceStatus = nil
                            Task { @MainActor in
                                defer { preferenceBusy = false }
                                do {
                                    try await chatsRepository.reportChat(chatId: chat.chatId, reason: reportReason, peer: chat.otherUser?.id, description: reportDescription)
                                    reportDescription = ""; showReportForm = false; preferenceStatus = "Report sent to the server"
                                } catch { preferenceStatus = "Could not complete the request. Check your connection and try again." }
                            }
                        }.disabled(preferenceBusy).buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("chat-report-send")
                    }
                    }.padding(14)
                }
                }
                if preferenceBusy { ProgressView() }
                if let preferenceStatus { Text(LocalizedStringKey(preferenceStatus)).font(.footnote) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("chat-safety-content")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .tint(Color(hex: 0x7BC9EC))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { showChatSafety = false }.disabled(preferenceBusy).accessibilityIdentifier("chat-safety-close") } }
            .confirmationDialog("Block contact", isPresented: $confirmChatBlock, titleVisibility: .visible) {
                Button("Block contact", role: .destructive) { changeChatPreference("block") }
                Button("Cancel", role: .cancel) {}
            } message: { Text("New messages and calls in both directions will be blocked until you unblock this contact. Existing history stays.") }
            .interactiveDismissDisabled(preferenceBusy)
        }
    }

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSending
    }

    private var composerCanSend: Bool {
        if editingMessage != nil { return canSend }
        return composerAttachments.isEmpty ? canSend : (!isSending && !mediaPreparationActive && composerFileImports == 0)
    }

    private var composerAttachmentTray: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(composerAttachments.enumerated()), id: \.element.id) { index, item in
                    ZStack(alignment: .topTrailing) {
                        ChatComposerThumbnail(item: item)
                            .frame(width: item.ownedURL == nil ? 76 : 184,
                                   height: item.ownedURL == nil ? 72 : composerDocumentHeight)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(alignment: .topLeading) {
                                if item.ownedURL == nil {
                                    Text("\(index + 1)").font(.system(size: 11, weight: .semibold)).foregroundStyle(appColorStyle.onColorfulButton)
                                        .frame(width: 20, height: 20).background(appColorStyle.decorative(ChatControlPalette.blue).opacity(0.9), in: Circle()).padding(4)
                                }
                            }
                        Button { removeComposerAttachment(item.id) } label: {
                            Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 23, height: 23).background(.black.opacity(0.65), in: Circle())
                                .frame(width: 44, height: 44).contentShape(Rectangle())
                        }.accessibilityLabel("Remove attachment").accessibilityIdentifier("composer-remove-\(index)")
                    }
                }
            }.padding(.horizontal, 10).padding(.top, 10).padding(.bottom, 8)
        }.frame(height: composerAttachments.contains(where: { $0.ownedURL != nil }) ? max(90, composerDocumentHeight + 18) : 90)
            .background(UnyriSavedChatStyle.background)
            .accessibilityIdentifier("composer-attachments")
            .overlay(alignment: .bottom) { Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5).padding(.horizontal, 12) }
    }

    private func removeComposerAttachment(_ id: String) {
        guard let item = composerAttachments.first(where: { $0.id == id }) else { return }
        if composerDirectTask != nil { composerDirectTask?.cancel(); messagesRepository.directFiles?.cancelPreparing() }
        UnyriHaptics.selection()
        withAnimation(composerReduceMotion ? nil : .easeInOut(duration: 0.16)) {
            composerAttachments.removeAll { $0.id == id }
        }
        if let url = item.ownedURL { messagesRepository.discardUnownedDraftSource(url) }
    }

    private func clearComposerAttachments() {
        if composerDirectTask != nil { composerDirectTask?.cancel(); messagesRepository.directFiles?.cancelPreparing() }
        composerImportToken = UUID()
        composerFileImportTask?.cancel(); composerFileImportTask = nil
        let urls = composerAttachments.compactMap(\.ownedURL)
        composerAttachments = []
        for url in urls { messagesRepository.discardUnownedDraftSource(url) }
    }

    private func addComposerPhotos(_ items: [PhotosPickerItem]) {
        photoItems = []
        let quality = MediaGallerySendChoice.saved().intent()
        for item in items.prefix(max(0, 10 - composerAttachments.count)) {
            let id = item.itemIdentifier ?? UUID().uuidString
            if !composerAttachments.contains(where: { $0.id == id }) {
                composerAttachments.append(ChatComposerAttachment(id: id, source: .photo(item),
                    kind: quality.kind(isVideo: item.supportedContentTypes.contains { $0.conforms(to: .movie) })))
            }
        }
        composerFocused = true
    }

    private func addComposerFiles(_ documents: [AttachmentFileCopy.DocumentSource]) async {
        guard editingMessage == nil, composerFileImports == 0 else { return }
        let token = composerImportToken; let generation = auth.sessionGeneration
        let repository = messagesRepository
        composerFileImports += 1
        defer { composerFileImports -= 1; withExtendedLifetime(documents) {} }
        let directIssue = repository.directFiles?.destinationIssue(currentPreferenceChat) ??
            (repository.directFiles == nil ? DirectFileDestinationPolicy.unavailable : nil)
        var direct: [AttachmentFileCopy.DocumentSource] = []
        for document in documents.prefix(max(0, 10 - composerAttachments.count)) {
            let url = document.url
            do {
                let size = try? await repository.draftFileSize(url)
                guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else { return }
                let route = DirectFileDestinationPolicy.importRoute(size: size,
                    supportsDirect: directIssue == nil, ordinaryLimit: AttachmentManager.maxFileBytes)
                if route == .unsupportedLarge {
                    repository.reportError(UnyriL10n.string(directIssue ?? DirectFileDestinationPolicy.unavailable)); continue
                }
                if route == .direct {
                    direct.append(document); continue
                }
                let durable = try await repository.stageDraftFile(url, coordinated: true)
                guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else {
                    repository.discardUnownedDraftSource(durable); return
                }
                let bytes = try durable.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                let file = StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: bytes, isVideo: false)
                composerAttachments.append(ChatComposerAttachment(id: file.id, source: .file(file), kind: "file"))
            } catch {
                guard token == composerImportToken, generation == auth.sessionGeneration else { return }
                if directIssue != nil, (error as? AttachmentFileCopy.CopyError) == .tooLarge {
                    repository.reportError(UnyriL10n.string("This file exceeds 100 MB, and direct transfer is unavailable in this conversation."))
                    continue
                }
                repository.reportError(UnyriL10n.string(MediaPreparationFailure.messageKey(error))); break
            }
        }
        if !direct.isEmpty, token == composerImportToken, generation == auth.sessionGeneration {
            directFileSelection = DirectFileSendSelection(documents: direct)
        }
        // Staging may finish after the user hid the keyboard or opened another
        // presentation. Keep the staged tray; background completion owns no focus.
    }

    private func sendComposerAttachments() {
        guard composerCanSend else { return }
        guard draft.count <= 4096 else { messagesRepository.reportError(UnyriL10n.string("Caption can contain up to 4096 characters.")); return }
        let items = composerAttachments
        let capturedDraft = draft
        if items.contains(where: { $0.directOriginal != nil }) {
            isSending = true
            composerDirectTask = Task { await sendOriginalComposerAttachments(items, capturedDraft: capturedDraft) }
            return
        }
        enqueueComposerAttachments(items, capturedDraft: capturedDraft)
    }

    private func sendOriginalComposerAttachments(_ items: [ChatComposerAttachment], capturedDraft: String) async {
        let generation = auth.sessionGeneration
        var originals: [String: DirectComposerOriginal.Original] = [:]
        defer {
            isSending = false; composerDirectTask = nil
            for original in originals.values { Task { await original.cleanup() } }
        }
        do {
            for item in items {
                guard generation == auth.sessionGeneration, !Task.isCancelled else { throw CancellationError() }
                if let source = item.directOriginal { originals[item.id] = try await DirectComposerOriginal.load(source) }
            }
            let direct = items.filter { (originals[$0.id]?.size ?? 0) > AttachmentManager.maxFileBytes }
            let directIDs = Set(direct.map(\.id))
            let ordinary = items.filter { !directIDs.contains($0.id) }
            guard generation == auth.sessionGeneration, !Task.isCancelled else { throw CancellationError() }
            if !direct.isEmpty {
                guard let transfers = messagesRepository.directFiles else { throw DirectFileTransfers.Failure.unsupported }
                for (index, item) in direct.enumerated() {
                    guard let original = originals[item.id] else { continue }
                    let caption = ordinary.isEmpty && index == 0 ? capturedDraft : nil
                    guard await transfers.offer(source: original.url, chat: currentPreferenceChat,
                        filename: original.filename, caption: caption) else {
                        messagesRepository.reportError(transfers.errorMessage ?? "Could not prepare the original file.")
                        return
                    }
                    guard generation == auth.sessionGeneration else { return }
                    composerAttachments.removeAll { $0.id == item.id }
                    if let file = item.ownedURL { messagesRepository.discardUnownedDraftSource(file) }
                    if caption != nil && draft == capturedDraft { updateComposerDraft("") }
                }
            }
            guard generation == auth.sessionGeneration, !Task.isCancelled else { return }
            isSending = false
            if !ordinary.isEmpty { enqueueComposerAttachments(ordinary, capturedDraft: capturedDraft) }
        } catch is CancellationError { /* Selection/draft are retained for the current account. */ }
        catch {
            if generation == auth.sessionGeneration { messagesRepository.reportError(UnyriL10n.string("Could not read the original video. Save it to Files and select it there.")) }
        }
    }

    private func enqueueComposerAttachments(_ items: [ChatComposerAttachment], capturedDraft: String) {
        let repository = messagesRepository
        let intent = MediaSendIntent(caption: capturedDraft.trimmingCharacters(in: .whitespacesAndNewlines))
        let ownedURLs = items.compactMap(\.ownedURL)
        let accepted = repository.startMediaPreparation(chatId: chat.chatId, count: items.count,
            sendIntent: intent, otherUserId: chat.otherUser?.id, itemKinds: items.map(\.kind),
            finished: { for url in ownedURLs { repository.discardUnownedDraftSource(url) } }) { index in
                try await Self.prepareComposerAttachment(items[index], repository: repository)
            }
        guard accepted else {
            repository.reportError(repository.errorMessage ?? UnyriL10n.string("Could not save media to the outbox."))
            return
        }
        UnyriHaptics.press()
        // Source ownership belongs to the repository now, not to this view.
        let capturedIDs = Set(items.map(\.id))
        composerAttachments.removeAll { capturedIDs.contains($0.id) }
        if draft == capturedDraft { updateComposerDraft("") }
        composerFocused = true
    }

    private static func prepareComposerAttachment(_ item: ChatComposerAttachment, repository: MessagesRepository) async throws -> StoredMediaDraft {
        switch item.source {
        case .file(let stored): return stored
        case .photo(let photo):
            // A video sent as an original file still needs the movie importer.
            let video = photo.supportedContentTypes.contains { $0.conforms(to: .movie) }
            let temporary: URL
            if video {
                guard let imported = try await photo.loadTransferable(type: ImportedMovie.self) else { throw CocoaError(.fileReadUnknown) }
                temporary = imported.url
            } else {
                guard let imported = try await photo.loadTransferable(type: ImportedPhoto.self) else { throw CocoaError(.fileReadUnknown) }
                temporary = imported.url
            }
            defer { try? FileManager.default.removeItem(at: temporary) }
            try Task.checkCancellation()
            let durable = try await repository.stageDraftFile(temporary, isVideo: video)
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable,
                bytes: (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, isVideo: video)
        case .gallery(let source):
            switch source {
            case .capture(let url):
                let durable = try await repository.stageDraftFile(url)
                return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable,
                    bytes: (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, isVideo: false)
            case .asset(let asset):
                let resources = PHAssetResource.assetResources(for: asset)
                guard let resource = resources.first(where: { $0.type == (asset.mediaType == .video ? .video : .photo) }) ?? resources.first else { throw CocoaError(.fileReadUnknown) }
                let name = (resource.originalFilename as NSString).lastPathComponent
                guard !name.isEmpty, name != ".", name != ".." else { throw CocoaError(.fileReadInvalidFileName) }
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent("coco-composer-" + UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                defer { try? FileManager.default.removeItem(at: directory) }
                let temporary = directory.appendingPathComponent(name)
                let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = true
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    PHAssetResourceManager.default().writeData(for: resource, toFile: temporary, options: options) { error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                    }
                }
                try Task.checkCancellation()
                let video = asset.mediaType == .video
                let durable = try await repository.stageDraftFile(temporary, isVideo: video)
                return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable,
                    bytes: (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, isVideo: video)
            }
        }
    }

    private func send() {
        if !composerAttachments.isEmpty && editingMessage == nil { sendComposerAttachments(); return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        UnyriHaptics.press()
        isSending = true
        let replyTo = replyTarget
        let editing = editingMessage
        draft = editing == nil ? "" : ordinaryDraft
        if editing == nil {
            ordinaryDraft = ""
            messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: "")
        }
        replyTarget = nil
        editingMessage = nil
        Task {
            await messagesRepository.flushDraftValues()
            var queued = true
            if let editing {
                queued = await messagesRepository.editMessage(clientMessageId: editing.clientMessageId, newText: text)
            } else if let replyTo {
                queued = await messagesRepository.reply(
                    chatId: chat.chatId,
                    otherUserId: chat.otherUser?.id,
                    text: text,
                    replyToClientMessageId: replyTo.clientMessageId
                )
            } else {
                queued = await messagesRepository.sendText(
                    chatId: chat.chatId,
                    otherUserId: chat.otherUser?.id,
                    text: text
                )
            }
            if !queued {
                if draft.isEmpty {
                    draft = text; replyTarget = replyTo; editingMessage = editing
                    if editing == nil { ordinaryDraft = text; messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: text) }
                } else {
                    messagesRepository.saveChatValue(chatId: chat.chatId, name: "recoveredDraft", value: text)
                    messagesRepository.reportError(UnyriL10n.string("Previous text was saved. Open Recovered draft in the chat menu.", comment: ""))
                }
            }
            isSending = false
        }
    }

    private func beginEdit(_ message: DecryptedChatMessage) {
        editingMessage = message
        replyTarget = nil
        draft = message.content.text ?? ""
    }

    private var mediaPreparationBanner: some View {
        VStack(spacing: 4) {
            if messagesRepository.preparingMediaChats.contains(chat.chatId) {
                HStack {
                    ProgressView().controlSize(.small)
                    Text("Preparing media…").font(.subheadline)
                    Spacer()
                    Button("Cancel") { messagesRepository.cancelMediaPreparation(chatId: chat.chatId) }
                }.padding(10)
            }
            if let error = messagesRepository.mediaPreparationErrors[chat.chatId] {
                HStack {
                    Text(error).font(.caption)
                    Button("Close") { messagesRepository.acknowledgeMediaPreparationError(chatId: chat.chatId) }
                }.padding(10)
            }
        }.background(UnyriColors.chatPanel)
    }

    private func prepareGalleryAssets(_ assets: [UnyriGallerySource], intent: MediaSendIntent) -> Bool {
        guard !mediaPreparationActive else { return false }
        let repository = messagesRepository
        return repository.startMediaPreparation(chatId: chat.chatId, count: assets.count,
            sendIntent: intent, otherUserId: chat.otherUser?.id) { index in
            let asset: PHAsset
            switch assets[index] {
            case .asset(let value): asset = value
            case .capture(let url):
                let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                let durable = try await repository.stageDraftFile(url)
                return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: bytes, isVideo: false)
            }
            let resources = PHAssetResource.assetResources(for: asset)
            let resource = resources.first { $0.type == (asset.mediaType == .video ? .video : .photo) } ?? resources.first
            guard let resource else { throw CocoaError(.fileReadUnknown) }
            let originalName = (resource.originalFilename as NSString).lastPathComponent
            guard !originalName.isEmpty, originalName != ".", originalName != ".." else { throw CocoaError(.fileReadInvalidFileName) }
            let temporaryDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("coco-gallery-" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
            let temporary = temporaryDirectory.appendingPathComponent(originalName)
            defer { try? FileManager.default.removeItem(at: temporaryDirectory) }
            let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = true
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                PHAssetResourceManager.default().writeData(for: resource, toFile: temporary, options: options) { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                }
            }
            try Task.checkCancellation()
            let bytes = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            let durable = try await repository.stageDraftFile(temporary, isVideo: asset.mediaType == .video)
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: bytes, isVideo: asset.mediaType == .video)
        }
    }

    private func preparePhotos(_ items: [PhotosPickerItem]) async {
        photoItems = []
        guard !mediaPreparationActive else { return }
        let repository = messagesRepository
        repository.startMediaPreparation(chatId: chat.chatId, count: items.count,
            sendIntent: MediaGallerySendChoice.saved().intent(),
            otherUserId: chat.otherUser?.id) { index in
            let item = items[index]
            try Task.checkCancellation()
            if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
                guard let imported = try await item.loadTransferable(type: ImportedMovie.self) else { throw CocoaError(.fileReadUnknown) }
                defer { try? FileManager.default.removeItem(at: imported.url) }
                try Task.checkCancellation()
                let bytes = try imported.url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                let durable = try await repository.stageDraftFile(imported.url, isVideo: true)
                return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: bytes, isVideo: true)
            }
            guard let imported = try await item.loadTransferable(type: ImportedPhoto.self) else { throw CocoaError(.fileReadUnknown) }
            defer { try? FileManager.default.removeItem(at: imported.url) }
            try Task.checkCancellation()
            let bytes = try imported.url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            let durable = try await repository.stageDraftFile(imported.url)
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: bytes, isVideo: false)
        }
    }

    private func mediaDraftKind(isVideo: Bool) -> String {
        let quality = UserDefaults.standard.string(forKey: "mediaSendQuality") ?? "image"
        return isVideo && quality != "file" ? (quality == "image_high" ? "video_high" : "video") : quality
    }
    private func persistMediaDrafts() {
        guard !mediaPreparationActive, loadedMediaDraftRevision == messagesRepository.mediaDraftRevisions[chat.chatId, default: 0] else { return }
        if photoDrafts.isEmpty {
            mediaCaption = ""
            mediaAlbumId = UUID().uuidString.lowercased()
        }
        let currentURLs = Set(photoDrafts.map(\.fileURL))
        let removedURLs = persistedMediaURLs.subtracting(currentURLs)
        let discardedURLs = removedURLs.subtracting(admittedMediaURLs.urls)
        admittedMediaURLs.urls.subtract(removedURLs)
        persistedMediaURLs = currentURLs
        let stored = photoDrafts.map { StoredMediaDraft(id: $0.id, url: $0.fileURL, bytes: $0.bytes, isVideo: $0.isVideo) }
        if let data = try? JSONEncoder().encode(stored) {
            messagesRepository.saveDraftValues(chatId: chat.chatId, values: [
                "mediaDrafts": String(decoding: data, as: UTF8.self),
                "mediaCaption": mediaCaption, "mediaAlbum": mediaAlbumId
            ])
            if !discardedURLs.isEmpty {
                Task {
                    await messagesRepository.flushDraftValues()
                    for url in discardedURLs { messagesRepository.discardUnownedDraftSource(url) }
                }
            }
        }
    }
    private func restoreMediaDrafts(restoreFile: Bool = true) {
        messagesRepository.recoverInterruptedMediaPreparation(chatId: chat.chatId)
        let revision = messagesRepository.mediaDraftRevisions[chat.chatId, default: 0]
        let token = UUID(); mediaRestoreToken = token
        mediaAlbumId = messagesRepository.chatValue(chatId: chat.chatId, name: "mediaAlbum") ?? UUID().uuidString.lowercased()
        mediaCaption = messagesRepository.chatValue(chatId: chat.chatId, name: "mediaCaption") ?? ""
        let stored = messagesRepository.storedMediaDrafts(chatId: chat.chatId)
            .filter { FileManager.default.fileExists(atPath: $0.url.path) }
        // Publish usable drafts immediately. Preview decoding never locks the composer.
        persistedMediaURLs = Set(stored.map(\.url))
        photoDrafts = stored.map { PhotoDraft(id: $0.id, fileURL: $0.url, preview: UIImage(), bytes: $0.bytes, isVideo: $0.isVideo) }
        if !stored.isEmpty, !didOfferLegacyMedia {
            didOfferLegacyMedia = true; showLegacyMediaChoice = true
        }
        loadedMediaDraftRevision = revision
        let generation = auth.sessionGeneration
        mediaPreviewTask?.cancel()
        mediaPreviewTask = Task {
            for item in stored {
                guard !Task.isCancelled else { return }
                let preview: UIImage?
                if item.isVideo {
                    let generator = AVAssetImageGenerator(asset: AVURLAsset(url: item.url))
                    generator.appliesPreferredTrackTransform = true
                    generator.maximumSize = CGSize(width: 512, height: 512)
                    preview = await withTaskCancellationHandler {
                        guard let frame = try? await generator.image(at: .zero) else { return nil }
                        return UIImage(cgImage: frame.image)
                    } onCancel: { generator.cancelAllCGImageGeneration() }
                } else {
                    preview = await ConversationImages.load(path: item.url.path, thumbnail: nil)
                }
                guard generation == auth.sessionGeneration, mediaRestoreToken == token, !Task.isCancelled else { return }
                if let preview, let index = photoDrafts.firstIndex(where: { $0.id == item.id }) {
                    photoDrafts[index] = PhotoDraft(id: item.id, fileURL: item.url, preview: preview, bytes: item.bytes, isVideo: item.isVideo)
                }
            }
        }
        if restoreFile, let value = messagesRepository.chatValue(chatId: chat.chatId, name: "fileDraft"), let data = value.data(using: .utf8),
           let restored = try? JSONDecoder().decode(FileAttachmentDraft.self, from: data), FileManager.default.fileExists(atPath: restored.url.path) {
            fileDraft = restored; stagedFileDirectory = restored.url.deletingLastPathComponent()
        }
    }
    private func discardPhotos() {
        mediaPreviewTask?.cancel(); mediaPreviewTask = nil
        mediaRestoreToken = UUID()
        messagesRepository.cancelMediaPreparation(chatId: chat.chatId)
        loadedMediaDraftRevision = messagesRepository.mediaDraftRevisions[chat.chatId, default: 0]
        let discardedURLs = photoDrafts.map(\.fileURL)
        photoDrafts = []; mediaCaption = ""; mediaAlbumId = UUID().uuidString.lowercased()
        uploadedPhotos = [:]
        persistMediaDrafts()
        Task {
            await messagesRepository.flushDraftValues()
            for url in discardedURLs { messagesRepository.discardUnownedDraftSource(url) }
        }
    }

    private func attachmentOption(_ title: String, icon: String, action: String) -> some View {
        Button {
            UnyriHaptics.press()
            attachmentChoice = action
            showAttachmentMenu = false
        } label: {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 24)).frame(width: 56, height: 56)
                    .background(Circle().fill(UnyriColors.accent.opacity(0.14)))
                Text(LocalizedStringKey(title)).font(.caption).foregroundColor(UnyriColors.primaryText).lineLimit(2)
            }.foregroundColor(UnyriColors.accent).frame(width: 80).padding(.vertical, 4)
        }.buttonStyle(.plain)
    }

    // Menu actions reuse the existing gallery, importer and capture paths.
    // Recheck eligibility because the native menu can outlive its opening tap.
    private func openComposerAttachment(_ choice: String) {
        guard composerAttachments.count < 10, composerFileImports == 0, editingMessage == nil else { return }
        UnyriHaptics.press()
        composerFocused = false
        attachmentPhotoItems = []
        if choice == "gallery" {
            showAttachmentMenu = true
        } else {
            attachmentChoice = choice
            openAttachmentChoice()
        }
    }

    private func openAttachmentChoice() {
        let choice = attachmentChoice
        attachmentChoice = nil
        if choice != "assets" { gallerySelection = [] }
        switch choice {
        case "selectedPhotos":
            let selected = attachmentPhotoItems
            attachmentPhotoItems = []
            addComposerPhotos(selected)
        case "assets":
            let assets = attachmentAssets; attachmentAssets = []
            for source in assets {
                let id: String
                let video: Bool
                switch source {
                case .asset(let asset): id = asset.localIdentifier; video = asset.mediaType == .video
                case .capture(let url): id = url.absoluteString; video = false
                }
                guard composerAttachments.count < 10 else { break }
                if !composerAttachments.contains(where: { $0.id == id }) {
                    composerAttachments.append(ChatComposerAttachment(id: id, source: .gallery(source), kind: gallerySendIntent.kind(isVideo: video)))
                }
            }
            gallerySelection = []
            composerFocused = true
        case "photos": showPhotoPicker = true
        case "files": showFileImporter = true
        case "voice": toggleRecording()
        case "camera":
            guard voiceDraft == nil && !stagingVoice else {
                messagesRepository.reportError(UnyriL10n.string("Send or discard the voice draft first.")); return
            }
            guard !voiceRecorder.callInProgress else {
                messagesRepository.reportError("Finish the call before taking a photo. Photos and files can still be attached during a call.")
                return
            }
            let generation = auth.sessionGeneration
            Task { @MainActor in
                do {
                guard let claim = try await NonCallCaptureOwnership.prepare(.camera, generation: generation, stop: { claim in
                    if cameraClaim == claim { showCamera = false; cameraClaim = nil }
                    NonCallCaptureOwnership.release(claim)
                }) else { messagesRepository.reportError("Finish the call before taking a photo. Photos and files can still be attached during a call."); return }
                cameraClaim = claim
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard generation == auth.sessionGeneration, !Task.isCancelled, !voiceRecorder.callInProgress,
                      NonCallCaptureOwnership.isCurrent(claim) else { NonCallCaptureOwnership.release(claim); return }
                guard granted, UIImagePickerController.isSourceTypeAvailable(.camera) else {
                    NonCallCaptureOwnership.release(claim); cameraClaim = nil
                    messagesRepository.reportError("Camera access is required to take a photo."); return
                }
                cameraSessionGeneration = generation; cameraNeedsRetry = false
                showCamera = true
                } catch is CancellationError { }
                catch { if generation == auth.sessionGeneration { messagesRepository.reportError("Finish the call before taking a photo.") } }
            }
        default: gallerySelection = []
        }
    }

    private func prepareCameraCapture(_ source: CameraCaptureSource, id: String, generation: UInt64) async throws {
        func checkSession() throws {
            try Task.checkCancellation()
            guard generation == auth.sessionGeneration else { throw CancellationError() }
        }
        try checkSession()
        let repository = messagesRepository
        var temporary: URL?
        var durable: URL?
        var owned = false
        defer {
            if let temporary { try? FileManager.default.removeItem(at: temporary) }
            if !owned, let durable {
                if generation == auth.sessionGeneration { repository.discardUnownedDraftSource(durable) }
                else {
                    // This uniquely staged file was never admitted or registered as a draft.
                    // The retired repository cannot perform its normal session-fenced cleanup.
                    try? FileManager.default.removeItem(at: durable)
                }
            }
        }
        let sourceURL: URL
        let isVideo: Bool
        switch source {
        case .movie(let url): sourceURL = url; isVideo = true
        case .image(let image):
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("coco-camera-" + id + ".jpg")
            temporary = url
            try await Task.detached(priority: .userInitiated) {
                guard let data = image.jpegData(compressionQuality: 0.85) else { throw CocoaError(.fileReadCorruptFile) }
                try data.write(to: url, options: .atomic)
            }.value
            try checkSession()
            sourceURL = url; isVideo = false
        }
        // Finish prior composer writes before staging the capture into durable storage.
        await repository.flushDraftValues()
        try checkSession()
        let staged = try await repository.stageDraftFile(sourceURL, isVideo: isVideo)
        durable = staged
        try checkSession()
        // Persist the confirmed capture before optional visual probing/admission.
        // Interruption keeps this stable-ID draft available for an explicit retry.
        let bytes = (try? staged.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        var drafts = repository.storedMediaDrafts(chatId: chat.chatId)
        drafts.removeAll { $0.id == id }
        drafts.append(StoredMediaDraft(id: id, url: staged, bytes: bytes, isVideo: isVideo))
        let data = try JSONEncoder().encode(drafts)
        guard repository.saveChatValue(chatId: chat.chatId, name: "mediaDrafts", value: String(decoding: data, as: UTF8.self)) else {
            throw CocoaError(.fileWriteUnknown)
        }
        owned = true
        let queued = await repository.queueVisualMedia(id: id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
            url: staged, kind: mediaDraftKind(isVideo: isVideo), caption: "", albumId: nil)
        try checkSession()
        if queued {
            drafts.removeAll { $0.id == id }
            let remaining = try JSONEncoder().encode(drafts)
            _ = repository.saveChatValue(chatId: chat.chatId, name: "mediaDrafts", value: String(decoding: remaining, as: UTF8.self))
            return
        }
        // Keep the capture and the existing gallery choices/caption for explicit retry.
        restoreMediaDrafts(restoreFile: false)
        cameraNeedsRetry = true
        repository.reportError(UnyriL10n.string("Could not save media to the outbox."))
    }

    private func stageFile(_ url: URL) async {
        guard !mediaPreparationActive else { return }
        let repository = messagesRepository
        repository.startMediaPreparation(chatId: chat.chatId, count: 1,
            sendIntent: MediaSendIntent(mode: .file), otherUserId: chat.otherUser?.id) { _ in
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            try Task.checkCancellation()
            let durable = try await repository.stageDraftFile(url)
            let size = (try? durable.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable, bytes: size, isVideo: false)
        }
    }

    private func discardFileDraft() {
        guard messagesRepository.saveChatValue(chatId: chat.chatId, name: "fileDraft", value: nil) else { return }
        guard let directory = stagedFileDirectory else { return }
        stagedFileDirectory = nil
        for url in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
            messagesRepository.discardUnownedDraftSource(url)
        }
    }

    private func deleteForEveryone(_ message: DecryptedChatMessage) {
        Task { await messagesRepository.deleteForEveryone(clientMessageId: message.clientMessageId) }
    }

    private func deleteForMe(_ message: DecryptedChatMessage) {
        messagesRepository.deleteForMe(clientMessageId: message.clientMessageId)
    }

    private func quickReactionAction(_ message: DecryptedChatMessage) -> (() -> Void)? {
        let emoji = UnyriQuickReactionPolicy.normalize(quickReaction)
        guard !emoji.isEmpty, UnyriQuickReactionPolicy.eligible(status: message.status.rawValue,
            serverId: message.serverId, deleted: message.isDeletedForEveryone || message.hiddenLocally,
            selecting: !selectedMessages.isEmpty) else { return nil }
        return {
            guard !messagesRepository.reactions(forMessageServerId: message.serverId).contains(where: { $0.isMine && $0.emoji == emoji }),
                  quickReactionRequests.insert(message.clientMessageId).inserted else { return }
            UnyriHaptics.selection()
            Task {
                defer { quickReactionRequests.remove(message.clientMessageId) }
                await messagesRepository.setReaction(clientMessageId: message.clientMessageId, emoji: emoji)
            }
        }
    }

    private func react(_ message: DecryptedChatMessage, _ emoji: String) {
        UnyriHaptics.selection()
        Task { await messagesRepository.setReaction(clientMessageId: message.clientMessageId, emoji: emoji) }
    }

    private func removeReaction(_ message: DecryptedChatMessage) {
        UnyriHaptics.selection()
        Task { await messagesRepository.removeReaction(clientMessageId: message.clientMessageId) }
    }

    private func retry(_ message: DecryptedChatMessage, reviewKeys: Bool = true) {
        refreshIdentityWarning()
        if reviewKeys && identityNeedsReview {
            retryAfterVerification = message.clientMessageId; acceptedForRetry = false
            composerFocused = false; showKeyVerification = true
            return
        }
        Task {
            await messagesRepository.retrySend(
                clientMessageId: message.clientMessageId,
                chatId: chat.chatId,
                otherUserId: chat.otherUser?.id,
                content: message.content,
                targetMessageId: message.targetMessageId
            )
        }
    }

    private var title: String {
        chat.chatType == "saved" ? UnyriL10n.string("Saved Messages", comment: "") : (chat.otherUser?.displayName ?? "Chat")
    }
}

// MARK: - Chat media gallery

private enum ChatMediaTab: String, CaseIterable, Identifiable {
    case photos = "Photos"
    case files = "Files"
    case voice = "Voice"
    var id: String { rawValue }

    var mediaType: String {
        switch self {
        case .photos: return AttachmentMetadata.mediaTypeImage
        case .files: return AttachmentMetadata.mediaTypeFile
        case .voice: return AttachmentMetadata.mediaTypeVoice
        }
    }
}

/// Local, encrypted-chat media index. The source is the existing attachment
/// store populated by MessageIngestor, matching Android's media gallery and
/// avoiding any duplicate network or crypto path.
private struct ChatMediaGallery: View {
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @Environment(\.dismiss) private var dismiss

    let chatId: String
    @State private var records: [AttachmentRecord] = []
    @State private var selectedTab: ChatMediaTab = .photos
    @State private var downloading = Set<String>()
    @State private var previewURL: URL?
    @State private var mediaURL: URL?
    @State private var downloadError = false
    @State private var openTasks: [String: Task<Void, Never>] = [:]
    @State private var openLifecycle = OpenLifecycle()

    // A result may finish after dismissal, or after this gallery has reopened.
    // Only requests belonging to the current visible presentation may open media.
    struct OpenLifecycle {
        private(set) var token = UUID()
        private(set) var visible = false
        mutating func activate() { token = UUID(); visible = true }
        mutating func deactivate() { visible = false; token = UUID() }
        func accepts(_ request: UUID) -> Bool { visible && token == request }
    }

    private var visibleRecords: [AttachmentRecord] {
        records.filter { record in
            let visual = record.mimeType.hasPrefix("image/") || record.mimeType.hasPrefix("video/")
            if selectedTab == .photos { return visual }
            return record.mediaType == selectedTab.mediaType && !visual
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Media", selection: $selectedTab) {
                    ForEach(ChatMediaTab.allCases) { tab in
                        Text(LocalizedStringKey(tab.rawValue)).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .padding()

                if downloadError {
                    VStack(alignment: .leading, spacing: 0) {
                        UnyriNotice(message: UnyriL10n.string("Download failed · Tap to retry"))
                        Button("Done") { downloadError = false }.frame(minHeight: 44)
                    }.padding(.horizontal)
                }
                if visibleRecords.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 34)).foregroundColor(.secondary)
                        Text("No media yet")
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if selectedTab == .photos {
                    photos
                } else {
                    attachmentList
                }
            }
            .unyriScreenBackground().navigationTitle("Chat media")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .quickLookPreview($previewURL)
        .background(UnyriMediaRotation(active: previewURL != nil))
        .fullScreenCover(isPresented: Binding(get: { mediaURL != nil }, set: { if !$0 { mediaURL = nil } })) {
            if let url = mediaURL {
                UnyriMediaViewer(items: records.filter { $0.mimeType.hasPrefix("image/") || $0.mimeType.hasPrefix("video/") }.map {
                    UnyriMediaItem(id: $0.attachmentId, url: messagesRepository.cachedLocalPath(attachmentId: $0.attachmentId).map { URL(fileURLWithPath: $0) }, mime: $0.mimeType, name: $0.filename, thumbnailBase64: $0.thumbnailBase64)
                }, initial: url, download: { await messagesRepository.downloadAttachment(attachmentId: $0, origin: .mediaViewer) })
            }
        }
        .onAppear { openLifecycle.activate() }
        .onDisappear {
            openLifecycle.deactivate()
            openTasks.values.forEach { $0.cancel() }
            openTasks.removeAll()
            downloading.removeAll()
        }
        .task { records = messagesRepository.galleryAttachments(chatId: chatId) }
    }

    private var photos: some View {
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(visibleRecords, id: \.attachmentId) { record in
                    Button { open(record) } label: {
                        ZStack {
                            MediaGalleryThumbnail(record: record)
                            if downloading.contains(record.attachmentId) {
                                ProgressView().tint(.white)
                            } else if messagesRepository.cachedLocalPath(attachmentId: record.attachmentId) == nil {
                                Image(systemName: "arrow.down.circle.fill")
                                    .font(.title2).foregroundColor(.white)
                                    .shadow(radius: 3)
                            }
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusMedium))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(record.filename ?? UnyriL10n.string("Media"))
                    .disabled(downloading.contains(record.attachmentId))
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var attachmentList: some View {
        List(visibleRecords, id: \.attachmentId) { record in
            Button { open(record) } label: {
                HStack(spacing: 12) {
                    UnyriFileTypeBadge(mime: record.mimeType, filename: record.filename)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(chatPresentationText(record.filename ?? UnyriL10n.string(selectedTab == .voice ? "Voice message" : "File", comment: ""), inline: true))
                            .foregroundColor(.primary).lineLimit(1)
                        Text(metadata(for: record))
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    if downloading.contains(record.attachmentId) {
                        ProgressView()
                    } else {
                        Image(systemName: messagesRepository.cachedLocalPath(attachmentId: record.attachmentId) == nil
                            ? "arrow.down.circle" : (selectedTab == .voice ? "play.circle" : "eye"))
                            .foregroundColor(UnyriColors.accent)
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    private func metadata(for record: AttachmentRecord) -> String {
        if selectedTab == .voice, let duration = record.durationMs {
            return VoiceFormat.durationText(duration)
        }
        return ByteCountFormatter.string(fromByteCount: record.size, countStyle: .file)
    }

    private func open(_ record: AttachmentRecord) {
        guard openLifecycle.visible, !downloading.contains(record.attachmentId) else { return }
        let request = openLifecycle.token
        downloading.insert(record.attachmentId)
        openTasks[record.attachmentId] = Task { @MainActor in
            defer {
                if openLifecycle.accepts(request) {
                    downloading.remove(record.attachmentId)
                    openTasks[record.attachmentId] = nil
                }
            }
            guard !Task.isCancelled, openLifecycle.accepts(request) else { return }
            let url: URL?
            if let path = messagesRepository.cachedLocalPath(attachmentId: record.attachmentId) {
                url = URL(fileURLWithPath: path)
            } else {
                url = await messagesRepository.downloadAttachment(attachmentId: record.attachmentId, origin: .messageTap)
            }
            guard !Task.isCancelled, openLifecycle.accepts(request) else { return }
            guard let url else { downloadError = true; return }
            if record.mediaType == AttachmentMetadata.mediaTypeVoice {
                voicePlayer.togglePlay(attachmentId: record.attachmentId, fileURL: url)
            } else if record.mimeType.hasPrefix("image/") || record.mimeType.hasPrefix("video/") {
                records = messagesRepository.galleryAttachments(chatId: chatId)
                mediaURL = url
            } else {
                previewURL = url
            }
        }
    }
}

private struct MediaGalleryThumbnail: View {
    let record: AttachmentRecord
    @State private var image: UIImage?

    var body: some View {
        Group {
            if record.mimeType.hasPrefix("video/"), let path = record.localPath { UnyriVideoThumbnail(url: URL(fileURLWithPath: path), compact: false) }
            else if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Color(.systemGray5).overlay(Image(systemName: record.mimeType.hasPrefix("video/") ? "video" : "photo").foregroundColor(.secondary)) }
        }
        .clipped()
        .overlay(alignment: .bottomTrailing) {
            if record.mimeType.hasPrefix("video/"), record.localPath == nil {
                Label(FilePresentation.duration(record.durationMs), systemImage: "play.fill")
                    .font(.caption).foregroundStyle(.white).padding(5).background(.black.opacity(0.6))
            }
        }
        .task(id: "\(record.attachmentId):\(record.localPath ?? "")") {
            image = await ConversationImages.load(path: record.mimeType.hasPrefix("video/") ? nil : record.localPath, thumbnail: record.thumbnailBase64)
        }
    }
}

/// A single off-main decoder avoids a burst of ten simultaneous ImageIO decodes.
private actor UnyriImageDecodeQueue {
    static let shared = UnyriImageDecodeQueue()
    func decode(_ operation: @Sendable () -> UIImage?) -> UIImage? {
        guard !Task.isCancelled else { return nil }
        return autoreleasepool(invoking: operation)
    }
}

@MainActor
enum ConversationImages {
    private(set) static var generation: UInt64 = 0
    private struct PendingLoad {
        let id: UUID
        let task: Task<UIImage?, Never>
        var consumers: Set<UUID>
    }
    private static var pending: [String: PendingLoad] = [:]
    private static func finishConsumer(_ consumer: UUID, key: String, request: UUID, cancelled: Bool) {
        guard var entry = pending[key], entry.id == request else { return }
        entry.consumers.remove(consumer)
        if entry.consumers.isEmpty {
            if cancelled { entry.task.cancel() }
            pending[key] = nil
        } else { pending[key] = entry }
    }
    static func clearDecodedCache() {
        generation &+= 1
        pending.values.forEach { $0.task.cancel() }
        pending.removeAll()
        cache.removeAllObjects()
    }
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 24 * 1024 * 1024
        cache.countLimit = 80
        return cache
    }()

    static func cacheVideoFrame(_ image: UIImage, path: String, pixelEdge: Int, generation expected: UInt64) {
        guard expected == generation else { return }
        cache.setObject(image, forKey: ("video-frame:\(pixelEdge):" + path) as NSString,
                        cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
    }

    // Keep the default key shared with generated video frames and the media viewer.
    private static func previewKey(_ source: String, pixelEdge: Int) -> NSString {
        (pixelEdge == 768 ? source : "\(pixelEdge):\(source)") as NSString
    }

    static func cached(path: String?, thumbnail: String?, pixelEdge: Int = 768) -> UIImage? {
        if let path, let image = cache.object(forKey: previewKey(path, pixelEdge: pixelEdge)) { return image }
        if let thumbnail { return cache.object(forKey: previewKey(thumbnail, pixelEdge: pixelEdge)) }
        return nil
    }

    static func load(path: String?, thumbnail: String?, pixelEdge: Int = 768) async -> UIImage? {
        guard !Task.isCancelled else { return nil }
        guard let sourceKey = path ?? thumbnail, !sourceKey.isEmpty else { return nil }
        let key = previewKey(sourceKey, pixelEdge: pixelEdge)
        if let image = cache.object(forKey: key) {
            PerformanceRecorder.shared.recordMemoryCacheHit(); return image
        }
        let loadGeneration = generation
        // Never retain embedded thumbnail bytes in the diagnostic token map.
        let diagnosticOperation = PerformanceRecorder.shared.isEnabled
            ? PerformanceRecorder.shared.operation(path.map { "preview-file:" + $0 } ?? "preview-work:" + UUID().uuidString) : 0
        let requestKey = key as String
        let consumer = UUID()
        let work: Task<UIImage?, Never>
        let requestID: UUID
        if var existing = pending[requestKey] {
            existing.consumers.insert(consumer)
            pending[requestKey] = existing
            work = existing.task
            requestID = existing.id
        } else {
        requestID = UUID()
        work = Task.detached(priority: .utility) { () -> UIImage? in
            let span = PerformanceRecorder.shared.begin(.previewDecode, ["width": Double(pixelEdge), "operation": diagnosticOperation, "status": path == nil ? 2 : 1])
            let requestedAt = ProcessInfo.processInfo.systemUptime
            var success = false
            defer { PerformanceRecorder.shared.end(span, success: success) }
            let result = await UnyriImageDecodeQueue.shared.decode {
            PerformanceRecorder.shared.record(.previewCache, ["cache_hit": 0, "operation": diagnosticOperation, "gap_ms": max(0, ProcessInfo.processInfo.systemUptime - requestedAt) * 1000])
            guard !Task.isCancelled else { return nil }
            var source: CGImageSource?
            if let path { source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) }
            if path == nil, let thumbnail, thumbnail.utf8.count <= 128 * 1024, let data = Data(base64Encoded: thumbnail) {
                source = CGImageSourceCreateWithData(data as CFData, nil)
            }
            guard let source, let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: min(768, max(256, pixelEdge)),
                kCGImageSourceShouldCacheImmediately: true
            ] as CFDictionary) else { return nil }
            guard !Task.isCancelled else { return nil }
            return UIImage(cgImage: decoded)
            }
            success = result != nil
            return result
        }
        pending[requestKey] = PendingLoad(id: requestID, task: work, consumers: [consumer])
        }
        let image = await withTaskCancellationHandler(operation: {
            await work.value
        }, onCancel: {
            Task { @MainActor in
                finishConsumer(consumer, key: requestKey, request: requestID, cancelled: true)
            }
        })
        finishConsumer(consumer, key: requestKey, request: requestID, cancelled: false)
        guard loadGeneration == generation else { return nil }
        // Completed pixels remain reusable. A queued decode is cancelled only
        // after its last consumer leaves, never while another row needs it.
        if let image {
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        }
        guard !Task.isCancelled else { return nil }
        guard let image else {
            return path == nil ? nil : await load(path: nil, thumbnail: thumbnail, pixelEdge: pixelEdge)
        }
        return image
    }
}

private struct PhotoDraft: Identifiable {
    let id: String
    let fileURL: URL
    let preview: UIImage
    let bytes: Int
    let isVideo: Bool

    init(id: String = UUID().uuidString.lowercased(), fileURL: URL, preview: UIImage, bytes: Int, isVideo: Bool = false) {
        self.id = id
        self.fileURL = fileURL
        self.preview = preview
        self.bytes = bytes
        self.isVideo = isVideo
    }
}

/// Admission bookkeeping is not observable UI state: ten accepted files must
/// not invalidate the entire chat ten times just to transfer source ownership.
private final class MediaDraftAdmissions { var urls = Set<URL>() }

private struct PhotoDraftPreview: View {
    @AppStorage("mediaSendQuality") private var quality = "image"
    @Binding var drafts: [PhotoDraft]
    @Binding var caption: String
    let add: () -> Void
    let beginBatch: () async -> Void
    let endBatch: () -> Void
    let send: (PhotoDraft, String, (Int) -> Void) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected = 0
    @State private var isSending = false
    @State private var progress = 0
    @State private var error: String?
    @State private var preview: UnyriMediaItem?
    private func media(_ draft: PhotoDraft) -> UnyriMediaItem {
        UnyriMediaItem(url: draft.fileURL, mime: UTType(filenameExtension: draft.fileURL.pathExtension)?.preferredMIMEType ?? (draft.isVideo ? "video/mp4" : "image/jpeg"))
    }
    private func remove(_ draft: PhotoDraft) {
        guard !isSending else { return }
        UnyriHaptics.selection()
        drafts.removeAll { $0.id == draft.id }
        selected = min(selected, max(0, drafts.count - 1))
        if drafts.isEmpty { dismiss() }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("Tap to preview. Closing keeps these files and your caption in this chat.")
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    if drafts.indices.contains(selected) {
                        let draft = drafts[selected]
                        Button { UnyriHaptics.press(); preview = media(draft) } label: {
                            ZStack {
                                Image(uiImage: draft.preview).resizable().scaledToFit().frame(maxWidth: .infinity).frame(height: 230)
                                if draft.isVideo { Image(systemName: "play.circle.fill").font(.system(size: 48)).foregroundStyle(.white).shadow(radius: 8) }
                            }.background(.black, in: RoundedRectangle(cornerRadius: 16)).clipShape(RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain).accessibilityLabel("Open preview").disabled(isSending).opacity(isSending ? 0.45 : 1)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(Array(drafts.enumerated()), id: \.element.id) { index, draft in
                                ZStack(alignment: .topTrailing) {
                                    Button { UnyriHaptics.selection(); selected = index } label: {
                                        Image(uiImage: draft.preview.size.width > 0 ? draft.preview : (UIImage(systemName: draft.isVideo ? "video.fill" : "photo") ?? UIImage()))
                                            .resizable().scaledToFill().frame(width: 88, height: 88).clipped()
                                            .clipShape(RoundedRectangle(cornerRadius: 12))
                                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(index == selected ? UnyriColors.accent : .clear, lineWidth: 3))
                                            .overlay(alignment: .bottomLeading) {
                                                Text("\(index + 1)").font(.caption.bold()).foregroundStyle(.white).padding(5).background(.black.opacity(0.7), in: Circle()).padding(4)
                                            }
                                    }.buttonStyle(.plain).disabled(isSending).opacity(isSending ? 0.45 : 1)
                                    Button { remove(draft) } label: {
                                        Image(systemName: "xmark").font(.system(size: 13, weight: .semibold)).foregroundStyle(ChatControlPalette.coral)
                                            .frame(width: 28, height: 28).background(ChatControlPalette.coralBack, in: Circle())
                                            .frame(width: 44, height: 44).contentShape(Rectangle())
                                    }.buttonStyle(.plain).accessibilityLabel("Remove from selection").disabled(isSending).opacity(isSending ? 0.45 : 1)
                                }
                            }
                        }.padding(3)
                    }
                    Button { UnyriHaptics.press(); add() } label: { Label("Add photos", systemImage: "plus").font(.subheadline.weight(.semibold)).foregroundStyle(ChatControlPalette.mint).frame(maxWidth: .infinity, minHeight: 44).background(ChatControlPalette.mintBack, in: Capsule()) }.buttonStyle(.plain).disabled(isSending).opacity(isSending ? 0.45 : 1)
                    HStack {
                    Label("Media quality", systemImage: "sparkles").font(.subheadline).foregroundStyle(ChatControlPalette.amber)
                    Spacer()
                    Picker("Media quality", selection: $quality) {
                        Text("Standard").tag("image"); Text("High quality").tag("image_high"); Text("Original file").tag("file")
                    }.pickerStyle(.menu).tint(ChatControlPalette.amber).disabled(isSending).opacity(isSending ? 0.45 : 1)
                    }.padding(.horizontal, 12).frame(minHeight: 48).background(ChatControlPalette.amberBack, in: RoundedRectangle(cornerRadius: 16))
                    Text("Original files and animations retain their format and may contain location metadata.").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                    TextField("Caption (optional)", text: $caption, axis: .vertical)
                        .lineLimit(1...4).textFieldStyle(.plain).padding(14).background(ChatControlPalette.composer, in: RoundedRectangle(cornerRadius: 18)).overlay(RoundedRectangle(cornerRadius: 18).stroke(ChatControlPalette.edge.opacity(0.25), lineWidth: 1)).disabled(isSending).opacity(isSending ? 0.45 : 1)
                        .onChange(of: caption) { value in if value.count > 4096 { caption = String(value.prefix(4096)) } }
                    if let error { Text(error).foregroundStyle(ChatControlPalette.coral).font(.footnote) }
                }.padding(16)
            }.scrollDismissesKeyboard(.interactively)
             .background(UnyriColors.background)
             .unyriScreenBackground().navigationTitle("Selected: \(drafts.count)").navigationBarTitleDisplayMode(.inline)
             .safeAreaInset(edge: .bottom, spacing: 0) {
                Button { Task { await sendAll() } } label: {
                    HStack(spacing: 8) {
                        if isSending { ProgressView().tint(.white) }
                        Image(systemName: "paperplane.fill")
                        Text("Send")
                        Text("\(drafts.count)").font(.caption.bold()).padding(.horizontal, 7).padding(.vertical, 3).background(.white.opacity(0.18), in: Capsule())
                    }.font(.subheadline.weight(.semibold)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 48).background(ChatControlPalette.violet, in: Capsule())
                }.buttonStyle(.plain).disabled(isSending || drafts.isEmpty).opacity(isSending || drafts.isEmpty ? 0.45 : 1).padding(.horizontal, 16).padding(.vertical, 10).background(UnyriColors.deepPanel)
             }
             .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Keep draft") { UnyriHaptics.press(); dismiss() }.tint(ChatControlPalette.blue).disabled(isSending).opacity(isSending ? 0.45 : 1) }
             }
        }.interactiveDismissDisabled(isSending)
         .fullScreenCover(item: $preview) { item in
             if let url = item.url { UnyriMediaViewer(items: drafts.map(media), initial: url) }
         }
    }
    private func sendAll() async {
        guard !isSending else { return }
        UnyriHaptics.press()
        isSending = true; error = nil
        let selectedDrafts = drafts
        var accepted = Set<String>()
        await beginBatch()
        defer {
            // Publish the accepted subset once. A failed item and all later
            // items remain durable drafts; their original IDs never change.
            drafts.removeAll { accepted.contains($0.id) }
            selected = 0
            endBatch()
            isSending = false
        }
        do {
            for (index, draft) in selectedDrafts.enumerated() {
                try await send(draft, index == selectedDrafts.count - 1 ? caption : "", { progress = $0 })
                accepted.insert(draft.id)
            }
            dismiss()
        } catch is CancellationError { }
          catch let sendError { error = (sendError as? LocalizedError)?.errorDescription ?? sendError.localizedDescription }
    }
}

// MARK: - Bubble

private let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "＋"]

/// Compact "New messages" affordance (Runtime Correction D): shown only when
/// the user scrolled up and new incoming messages arrived since. Tap
/// smooth-scrolls to the newest. Glass Flow, reduced-motion-aware.
private struct ChatNotificationHeaderHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private enum UnyriReplyPreviewText {
    static func kind(_ attachment: AttachmentMetadata?) -> String {
        guard let attachment else { return "text" }
        if attachment.mimeType.hasPrefix("video/") { return "video" }
        return attachment.mediaType
    }
    static func label(_ kind: String) -> String {
        UnyriL10n.string(kind == "image" ? "Photo" : kind == "video" ? "Video" : kind == "voice" ? "Voice message" : "File")
    }
    static func detail(_ message: DecryptedChatMessage) -> String {
        guard let media = message.content.attachment else { return message.displayText }
        let kind = Self.kind(media)
        var result = kind == "file" ? (media.filename ?? label(kind)) : label(kind)
        if let duration = media.voice?.durationMs ?? media.video?.durationMs, duration > 0 {
            result += " · " + VoiceFormat.durationText(duration)
        }
        if let caption = message.content.text, !caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            result += " · " + caption
        }
        return result
    }
    static func snapshot(_ snapshot: ReplySnapshot) -> String {
        if snapshot.contentType != "text", ["", "Photo", "Image", "Voice message", "File", "Attachment"].contains(snapshot.preview) { return label(snapshot.contentType) }
        return snapshot.preview
    }
}

private struct UnyriReplyComposerPreview: View {
    let message: DecryptedChatMessage
    let author: String
    let editing: Bool
    let localPath: String?
    let onCancel: () -> Void
    @State private var image: UIImage?
    private var media: AttachmentMetadata? { message.content.attachment }
    private var kind: String { UnyriReplyPreviewText.kind(media) }
    var body: some View {
        HStack(spacing: 10) {
            if let media {
                ZStack {
                    UnyriColors.accentSoft.opacity(0.12)
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(systemName: kind == "image" ? "photo" : kind == "video" ? "video" : kind == "voice" ? "waveform" : "doc.text").font(.system(size: 20)).foregroundStyle(UnyriColors.accentSoft) }
                    if kind == "video", image != nil { Image(systemName: "play.fill").font(.system(size: 12)).foregroundStyle(.white).padding(4).background(.black.opacity(0.35), in: Circle()) }
                }.frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityHidden(true)
                    .task(id: media.attachmentId + (media.thumbnail ?? "") + (localPath ?? "")) {
                        image = nil
                        guard kind == "image" || kind == "video" else { return }
                        let loaded = await ConversationImages.load(path: kind == "image" ? localPath : nil, thumbnail: media.thumbnail, pixelEdge: 256)
                        guard !Task.isCancelled else { return }
                        image = loaded
                    }
            }
            RoundedRectangle(cornerRadius: 2).fill(UnyriColors.accentSoft).frame(width: 3, height: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(editing ? UnyriL10n.string("Editing message") : author).font(.caption.weight(.medium)).foregroundStyle(UnyriColors.accentSoft).lineLimit(1)
                Text(UnyriReplyPreviewText.detail(message).replacingOccurrences(of: "\n", with: " ")).font(.footnote).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onCancel) {
                Image(systemName: "xmark").font(.system(size: 15)).foregroundStyle(UnyriColors.secondaryText).frame(width: 44, height: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(editing ? "Cancel editing" : "Cancel reply")
        }.padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
    }
}

private struct PinnedMessagePreviewRow: View {
    let message: DecryptedChatMessage?
    let text: String
    @EnvironmentObject private var repository: MessagesRepository
    @State private var image: UIImage?
    var body: some View {
        HStack(spacing: 12) {
            if let media = message?.content.attachment, message?.isDeletedForEveryone != true {
                ZStack {
                    UnyriColors.glassFloating
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(systemName: media.mimeType.hasPrefix("video/") ? "video" : "doc").foregroundColor(UnyriColors.accent) }
                }.frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 10))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(text).lineLimit(4).foregroundColor(UnyriColors.primaryText)
                if let message { Text(message.createdAt.formatted()).font(.caption).foregroundColor(UnyriColors.secondaryText) }
            }
        }.padding(.vertical, 6)
        .task(id: message?.clientMessageId) {
            image = nil
            guard let media = message?.content.attachment, message?.isDeletedForEveryone != true,
                  media.mediaType == AttachmentMetadata.mediaTypeImage || media.mimeType.hasPrefix("video/") else { return }
            let loaded = await ConversationImages.load(path: media.mediaType == AttachmentMetadata.mediaTypeImage ? repository.cachedLocalPath(attachmentId: media.attachmentId) : nil, thumbnail: media.thumbnail)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

private struct NewMessagesPill: View {
    let count: Int
    let onTap: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var visible = false
    @State private var previousCount: Int?
    @State private var glyphOffset: CGFloat = 0
    @State private var pulseTask: Task<Void, Never>?
    @State private var pulseTicket: UUID?

    var body: some View {
        Button(action: onTap) {
            ZStack {
                Circle().fill(Color(hex: 0x142331))
                Image(systemName: "arrow.down")
                    .font(.system(size: 23, weight: .semibold))
                    .foregroundColor(Color(hex: 0x69C6ED)).offset(y: glyphOffset)
            }
                .frame(width: 42, height: 42)
                .overlay(alignment: .topTrailing) {
                    if count > 0 {
                        Text(count > 99 ? "99+" : String(count)).font(.caption2.weight(.semibold))
                            .foregroundColor(.white).padding(4).background(Capsule().fill(Color(red: 0.53, green: 0.36, blue: 0.96)))
                    }
                }
                .frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(UnyriQuietButtonStyle())
        .accessibilityLabel(UnyriL10n.string("Latest messages", comment: ""))
        .onAppear { visible = true; previousCount = count; glyphOffset = 0 }
        .onDisappear { visible = false; cancelPulse() }
        .onChange(of: reduceMotion) { reduced in if reduced { cancelPulse() } }
        .onChange(of: count) { next in
            let previous = previousCount
            previousCount = next
            guard visible, !reduceMotion, let previous, next > previous, pulseTask == nil else { return }
            let ticket = UUID(); pulseTicket = ticket
            pulseTask = Task { @MainActor in
                defer {
                    if pulseTicket == ticket { pulseTask = nil; pulseTicket = nil }
                }
                withAnimation(.easeOut(duration: 0.13)) { glyphOffset = 2 }
                do { try await Task.sleep(nanoseconds: 130_000_000) } catch { return }
                guard visible, !reduceMotion, pulseTicket == ticket else { return }
                withAnimation(.easeIn(duration: 0.13)) { glyphOffset = 0 }
                do { try await Task.sleep(nanoseconds: 130_000_000) } catch { return }
            }
        }
    }

    private func cancelPulse() {
        pulseTicket = nil; pulseTask?.cancel(); pulseTask = nil
        glyphOffset = 0
    }
}

@MainActor private final class UnyriAlbumPreviewStateCache {
    private final class Entry {
        weak var state: UnyriMessagePreviewState?
        init(_ state: UnyriMessagePreviewState) { self.state = state }
    }
    private var values: [String: Entry] = [:]
    private var insertions = 0
    func value(for id: String) -> UnyriMessagePreviewState {
        if let state = values[id]?.state { return state }
        insertions += 1
        if values.count >= 128 && insertions.isMultiple(of: 64) {
            values = values.filter { $0.value.state != nil }
        }
        let state = UnyriMessagePreviewState()
        values[id] = Entry(state)
        return state
    }
}

enum UnyriMessageContentPreviewPolicy {
    static func usesCustomContent(hasCustom: Bool, captionOnly: Bool, settingsPreview: Bool, inMenuPreview: Bool) -> Bool {
        hasCustom && !(inMenuPreview && captionOnly && !settingsPreview)
    }
}

/// Read the preview environment in the hosted subtree, after the menu presenter
/// supplies it. Capturing that value in MessageBubble would freeze the normal
/// caption branch before AnyView is moved into the separate hosting controller.
private struct UnyriMessageSurfaceContent: View {
    let custom: AnyView?
    let actual: AnyView
    let captionOnly: Bool
    let settingsPreview: Bool
    @Environment(\.unyriMessagePreview) private var inMenuPreview
    var body: some View {
        Group {
            if UnyriMessageContentPreviewPolicy.usesCustomContent(hasCustom: custom != nil, captionOnly: captionOnly,
                    settingsPreview: settingsPreview, inMenuPreview: inMenuPreview), let custom { custom }
            else { actual }
        }
    }
}

private struct MessageBubble: View {
    @Environment(\.unyriChatAppearance) private var appearance
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.chatAlbumCell) private var isAlbumCell
    @Environment(\.chatAlbumWidth) private var albumWidth
    @Environment(\.chatMediaHeight) private var mediaCellHeight
    @Environment(\.chatViewportWidth) private var viewportWidth
    @Environment(\.pendingMediaLookup) private var pendingMediaLookup
    private var pendingPreview: PendingMediaPreview? {
        guard (message.status == .pending || message.status == .failed), message.content.attachment == nil,
              let value = pendingMediaLookup(message.chatId, message.clientMessageId),
              value.isVisual || value.kind == "voice" || value.kind == "file" else { return nil }
        return value
    }
    @ScaledMetric(relativeTo: .body) private var bodyScale: CGFloat = 1
    private var messageFont: Font { appearance.messageFont(scale: bodyScale) }
    private var inlineMetadata: Bool {
        UnyriSavedChatStyle.metadataMode == "inline" && UnyriSavedChatStyle.showTimes && !isAlbumCell &&
        message.content.attachment == nil && message.content.conferenceInvite == nil && message.content.directFile == nil && pendingPreview == nil && !message.isDeletedForEveryone && message.status != .failed
    }
    private var displayedText: String {
        message.status == .decryptFailed ? UnyriL10n.string("Unable to decrypt message", comment: "")
            : message.status == .identityChanged ? UnyriL10n.string("Security key changed", comment: "") : message.displayText
    }
    private var lensText: AttributedString {
        var text = chatRichText(displayedText)
        if inlineMetadata {
            var metadata = AttributedString("\u{2002}\u{2002}" + Self.timestampFormatter.string(from: message.createdAt)
                + (message.isEdited ? "\u{00a0}" + UnyriL10n.string("edited") : ""))
            metadata.font = .system(size: UnyriSavedChatStyle.timeSize * bodyScale)
            metadata.foregroundColor = UnyriSavedChatStyle.muted
            text.append(metadata)
            if isMine {
                let symbol: String
                switch message.status {
                case .pending: symbol = "◷"
                case .sent: symbol = "✓"
                case .delivered, .read: symbol = "✓✓"
                default: symbol = ""
                }
                var receipt = AttributedString("\u{00a0}\u{00a0}" + symbol)
                receipt.font = .system(size: UnyriSavedChatStyle.timeSize * bodyScale)
                receipt.foregroundColor = message.status == .read ? UnyriSavedChatStyle.readTicks : UnyriSavedChatStyle.ticks
                text.append(receipt)
            }
        }
        return text
    }
    @StateObject private var messageMenu = UnyriMessageMenuPresenter()
    @State private var messagePreviewState = UnyriMessagePreviewState()
    @Environment(\.accessibilityReduceMotion) private var reduceReactionMotion
    @Environment(\.chatMediaWidth) private var mediaCellWidth
    let message: DecryptedChatMessage
    let scheduledAt: Date?
    let isPinned: Bool
    let isMine: Bool
    let currentUserId: String?
    var replyPeerName: String = UnyriL10n.string("Reply")
    let reactions: [ReactionSummary]
    let onRetry: () -> Void
    let onReply: () -> Void
    let onJumpQuote: () -> Void
    let onCopy: () -> Void
    let onForward: () -> Void
    let onSave: () -> Void
    let onSelect: () -> Void
    let onPin: () -> Void
    let onRemind: () -> Void
    let onEdit: () -> Void
    let onDeleteForEveryone: () -> Void
    let onDeleteForMe: () -> Void
    let onReact: (String) -> Void
    let onQuickReaction: (() -> Void)?
    let onRemoveReaction: () -> Void
    let onDownloadAttachment: (String) async -> URL?
    let onCachedLocalPath: (String) -> String?
    let isVoicePlaying: (String) -> Bool
    let voicePositionMs: (String) -> Int
    let onToggleVoice: (String, String) -> Void
    var selecting = false
    var selected = false
    var appearancePreviewContent: AnyView? = nil
    var appearancePreviewMode = false
    var albumCaptionMode = false
    var sharedPreviewState: UnyriMessagePreviewState? = nil
    var actionMemberCount = 1
    var onSelectIndividual: (() -> Void)? = nil
    var onDeleteIndividual: (() -> Void)? = nil
    var onShowFullText: (() -> Void)? = nil
    private var effectivePreviewState: UnyriMessagePreviewState { sharedPreviewState ?? messagePreviewState }
    private var captionMenuPreviewSize: CGSize? {
        guard albumCaptionMode, !appearancePreviewMode else { return nil }
        if let visual = pendingPreview?.visual, pendingPreview?.isVisual == true {
            return unyriChatVisualSize(width: visual.width, height: visual.height, cellWidth: albumWidth, cellHeight: nil)
        }
        guard let attachment = message.content.attachment,
              attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/") else { return nil }
        let video = attachment.mimeType.hasPrefix("video/")
        return unyriChatVisualSize(width: attachment.image?.width ?? (video ? 4 : 1),
            height: attachment.image?.height ?? (video ? 3 : 1), cellWidth: albumWidth, cellHeight: nil)
    }

    private var voiceCard: Bool { message.content.attachment?.mediaType == AttachmentMetadata.mediaTypeVoice || pendingPreview?.kind == "voice" }
    private var documentCard: Bool { !edgeToEdgeMedia && (message.content.attachment?.mediaType == AttachmentMetadata.mediaTypeFile || pendingPreview?.kind == "file") }
    private var fileFooter: Bool {
        let caption = pendingPreview != nil ? pendingPreview?.caption : message.content.text
        return UnyriSavedChatStyle.metadataMode == "inline" && UnyriSavedChatStyle.showTimes && documentCard && !isAlbumCell && (caption ?? "").isEmpty
    }
    private var maximumWidth: CGFloat {
        let limit = viewportWidth * UnyriSavedChatStyle.width
        if edgeToEdgeMedia { return min(limit, mediaCellWidth ?? UnyriSavedChatStyle.mediaWidth) }
        if voiceCard { return min(limit, UnyriSavedChatStyle.voiceWidth) }
        if documentCard { return min(limit, albumWidth ?? UnyriSavedChatStyle.fileWidth) }
        return limit
    }
    private var surfaceShape: AnyShape {
        if voiceCard { return AnyShape(appearance.voiceShape(isMine: isMine, layoutDirection: layoutDirection)) }
        if documentCard { return AnyShape(appearance.fileShape(isMine: isMine, layoutDirection: layoutDirection)) }
        return AnyShape(appearance.bodyShape(isMine: isMine, layoutDirection: layoutDirection))
    }
    private var surfaceColor: Color {
        if voiceCard { return isMine ? appearance.voiceOutgoing : appearance.voiceIncoming }
        if documentCard { return isMine ? appearance.fileOutgoing : appearance.fileIncoming }
        return isMine ? appearance.outgoing : appearance.incoming
    }

    private var edgeToEdgeMedia: Bool {
        if albumCaptionMode { return true }
        guard !message.isDeletedForEveryone else { return false }
        if pendingPreview?.isVisual == true { return true }
        guard let attachment = message.content.attachment else { return false }
        return attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/")
    }
    private var bareMedia: Bool { edgeToEdgeMedia && (pendingPreview?.isVisual == true ? pendingPreview?.caption ?? "" : message.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var visualContentWidth: CGFloat? {
        guard edgeToEdgeMedia else { return nil }
        if let preview = pendingPreview, preview.isVisual, let visual = preview.visual {
            return unyriChatVisualSize(width: visual.width, height: visual.height, cellWidth: mediaCellWidth, cellHeight: mediaCellHeight).width
        }
        guard let attachment = message.content.attachment else { return mediaCellWidth }
        let isVideo = attachment.mimeType.hasPrefix("video/")
        return unyriChatVisualSize(width: attachment.image?.width ?? (isVideo ? 4 : 1),
            height: attachment.image?.height ?? (isVideo ? 3 : 1), cellWidth: mediaCellWidth, cellHeight: mediaCellHeight).width
    }

    var body: some View {
        HStack(alignment: .unyriMessageBodyCenter, spacing: 0) {
            if isMine { Spacer(minLength: 0) }
            if selecting && isMine && !isAlbumCell { selectionMark }
            messageSurface
            .unyriAppearanceMaximumWidth(maximumWidth)
            .background(UnyriMessageMenuAnchor(presenter: messageMenu, open: showMessageMenu, enabled: !selecting && !appearancePreviewMode,
                preview: AnyView(messageSurface), previewSize: captionMenuPreviewSize))
            .accessibilityAction(named: Text("More options"), showMessageMenu)
            if selecting && !isMine && !isAlbumCell { selectionMark }
            if !isMine { Spacer(minLength: 0) }
        }
    }

    private var messageSurface: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: edgeToEdgeMedia ? 2 : 0) {
            VStack(alignment: isMine ? .trailing : .leading, spacing: 2) {
            if !isAlbumCell, let snapshot = message.content.replySnapshot {
                replyPreview(snapshot).contentShape(Rectangle()).onTapGesture(perform: onJumpQuote)
            }

            if let scheduledAt, message.status == .pending { Text("Schedule message").font(.caption); Text(scheduledAt.formatted()).font(.caption) }
            UnyriMessageSurfaceContent(custom: appearancePreviewContent, actual: AnyView(contentView),
                                      captionOnly: albumCaptionMode, settingsPreview: appearancePreviewMode)
                .overlay(alignment: .center) {
                    if edgeToEdgeMedia, !albumCaptionMode, isMine, message.status == .pending {
                        MessageUploadProgress(clientMessageId: message.clientMessageId, compact: true, cancel: onDeleteForMe)
                            .labelStyle(.iconOnly).foregroundColor(.white).padding(4)
                    } else if edgeToEdgeMedia, !albumCaptionMode, isMine, isAlbumCell, message.status == .failed {
                        Button(action: onRetry) {
                            Image(systemName: "arrow.clockwise").font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(.white).frame(width: 44, height: 44).background(.black.opacity(0.6), in: Circle())
                        }.buttonStyle(.plain).accessibilityLabel("Retry")
                    }
                }
            if isMine, message.status == .pending, !voiceCard, !documentCard, !edgeToEdgeMedia,
               pendingPreview != nil || message.content.attachment != nil {
                MessageUploadProgress(clientMessageId: message.clientMessageId,
                                      compact: mediaCellWidth != nil, cancel: onDeleteForMe)
            }

            if !isAlbumCell, !message.isDeletedForEveryone, message.status != .decryptFailed, message.status != .identityChanged,
               let address = chatLinkAddress(message.content.text ?? message.displayText) {
                Link(destination: address) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(address.host ?? "").font(messageFont.weight(.semibold)).lineLimit(1)
                        Text(address.path.isEmpty ? "/" : address.path).font(messageFont).lineLimit(2)
                    }.frame(maxWidth: 280, alignment: .leading).padding(10)
                        .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                        .background(surfaceColor)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }

            }
            .padding(.leading, edgeToEdgeMedia ? 0 : voiceCard ? 8 : appearance.padX)
            .padding(.trailing, edgeToEdgeMedia ? 0 : voiceCard ? 11 : appearance.padX)
            .padding(.vertical, edgeToEdgeMedia ? 0 : voiceCard ? appearance.voicePadY : appearance.padY)
            .frame(width: voiceCard || documentCard ? maximumWidth : nil)
            .background { if !edgeToEdgeMedia { surfaceShape.fill(surfaceColor) } }
            .alignmentGuide(.unyriMessageBodyCenter) { $0[VerticalAlignment.center] }
            // Reactions are sibling buttons, outside this double-tap recognizer.
            .modifier(UnyriMessageDoubleTap(action: guardedQuickReaction))
            if !isAlbumCell, !message.isDeletedForEveryone && !edgeToEdgeMedia && !inlineMetadata && !fileFooter && UnyriSavedChatStyle.showTimes {
                metaLine.padding(.horizontal, voiceCard ? 8 : appearance.padX).padding(.top, UnyriSavedChatStyle.timeOffset)
            }
            if !isAlbumCell, UnyriSavedChatStyle.showReactions, !reactions.isEmpty {
                reactionsBar
                    .padding(.horizontal, edgeToEdgeMedia ? 0 : 5)
                    .background(UnyriReactionTarget(presenter: messageMenu, emoji: "__reaction_bar"))
                    // The Lens reference keeps reactions outside the measured body.
                    .padding(.top, UnyriSavedChatStyle.reactionOffset)
                    .transition(.identity)
            }
            if !isAlbumCell && edgeToEdgeMedia && message.status == .failed { Button("Retry", action: onRetry).buttonStyle(.plain) }
        }
        // Only adding/removing reaction slots changes the bubble. Replacing an emoji does not animate the message.
        .animation(edgeToEdgeMedia || reduceReactionMotion ? nil : (reactions.isEmpty ? .timingCurve(0.4, 0, 1, 1, duration: 0.13) : .timingCurve(0.2, 0, 0.2, 1, duration: 0.16)), value: displayedReactions.count)
        .environment(\.unyriMessagePreviewState, effectivePreviewState)
    }

    private var mediaCaptionFill: Color { isMine ? appearance.outgoing : appearance.incoming }

    private var guardedQuickReaction: (() -> Void)? {
        guard let send = onQuickReaction else { return nil }
        return {
            guard !messageMenu.lastTouchWasReaction else { return }
            send()

        }
    }

    private var selectionMark: some View {
        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 24, weight: .semibold)).foregroundStyle(UnyriColors.accentSoft)
            .frame(width: 32, height: 44).frame(height: 0).accessibilityHidden(true)
    }

    @ViewBuilder
    private var contentView: some View {
        if message.isDeletedForEveryone {
            Text("This message was deleted")
                .font(messageFont)
                .italic()
                .foregroundColor(UnyriSavedChatStyle.muted)
        } else if let directFile = message.content.directFile {
            UnyriDirectFileCard(offer: directFile, chatId: message.chatId, outgoing: isMine, selecting: selecting, select: onSelect, text: message.content.text, historyOnly: message.content.authentication == nil)
        } else if let invitation = message.content.conferenceInvite {
            UnyriConferenceInvitationCard(metadata: invitation, message: message, outgoing: isMine,
                selecting: selecting, select: onSelect)
        } else if (message.status == .pending || message.status == .failed), message.content.attachment == nil, let preview = pendingPreview {
            if preview.isVisual {
                VStack(alignment: .leading, spacing: 0) {
                    QueuedVisualMediaView(preview: preview, messageID: message.clientMessageId)
                        .overlay(alignment: .bottomTrailing) {
                            if !isAlbumCell && UnyriSavedChatStyle.showTimes { metaLine.padding(.horizontal, 6).padding(.vertical, 3)
                                .background(.black.opacity(0.62), in: Capsule()).padding(6)
                            }
                        }
                    if !isAlbumCell, let caption = preview.caption, MessageTextPolicy.presentationResult(caption).kind != .empty {
                        UnyriMessageRichText(source: caption).equatable().font(messageFont).lineSpacing(appearance.extraLineSpacing * bodyScale)
                            .padding(.horizontal, max(0, appearance.padX - 2)).padding(.vertical, appearance.padY)
                            .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                            .background { mediaCaptionFill }
                    }
                }.frame(maxWidth: bareMedia ? mediaCellWidth : mediaCellWidth ?? 220, alignment: .leading)
                    .frame(width: visualContentWidth, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: isAlbumCell ? 0 : UnyriSavedChatStyle.mediaRadius))
            } else if preview.kind == "file" {
                VStack(alignment: .leading, spacing: 0) {
                    QueuedFileCard(preview: preview, id: message.clientMessageId, failed: message.status == .failed, cancel: onDeleteForMe,
                                   metadata: fileFooter ? AnyView(metaLine) : nil)
                    if !isAlbumCell, let caption = preview.caption, MessageTextPolicy.presentationResult(caption).kind != .empty {
                        UnyriMessageRichText(source: caption).equatable().font(messageFont).lineSpacing(appearance.extraLineSpacing * bodyScale)
                            .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                    }
                }
            } else { QueuedVoiceFileView(preview: preview, id: message.clientMessageId, cancel: onDeleteForMe) }
        } else if let attachment = message.content.attachment {
            VStack(alignment: .leading, spacing: 0) {
            AttachmentBubbleView(
                attachment: attachment,
                isMine: isMine,
                pendingSend: isMine && message.serverId == nil && (message.status == .pending || message.status == .failed),
                onDownload: onDownloadAttachment,
                onCachedLocalPath: onCachedLocalPath,
                isVoicePlaying: isVoicePlaying,
                voicePositionMs: voicePositionMs,
                onToggleVoice: onToggleVoice,
                fileMetadata: fileFooter ? AnyView(metaLine) : nil,
                voiceTransferControl: isMine && message.status == .pending ? AnyView(UnyriVoiceTransferControl(id: message.clientMessageId, cancel: onDeleteForMe)) : nil,
                fileTransferControl: isMine && message.status == .pending ? AnyView(UnyriVoiceTransferControl(id: message.clientMessageId, cancel: onDeleteForMe)) : nil
            )
            .overlay(alignment: .bottomTrailing) {
                if edgeToEdgeMedia && !isAlbumCell && UnyriSavedChatStyle.showTimes {
                    metaLine.padding(.horizontal, 6).padding(.vertical, 3)
                        .background(.black.opacity(0.62), in: Capsule()).padding(6)
                }
            }
            .environment(\.unyriMessageQuickReaction, guardedQuickReaction)

            if !isAlbumCell, let caption = message.content.text, MessageTextPolicy.presentationResult(caption).kind != .empty {
                UnyriMessageRichText(source: caption).equatable().font(messageFont).lineSpacing(appearance.extraLineSpacing * bodyScale)
                    .padding(.horizontal, edgeToEdgeMedia ? max(0, appearance.padX - 2) : 0).padding(.vertical, edgeToEdgeMedia ? appearance.padY : 0)
                    .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                    .background { if edgeToEdgeMedia { mediaCaptionFill } }
            }
            }
            .frame(maxWidth: edgeToEdgeMedia ? mediaCellWidth ?? UnyriSavedChatStyle.mediaWidth : nil, alignment: .leading)
            .frame(width: visualContentWidth, alignment: .leading)
            .background {
                if !edgeToEdgeMedia { Color.clear }
            }
            .clipShape(RoundedRectangle(cornerRadius: edgeToEdgeMedia ? isAlbumCell ? 0 : UnyriSavedChatStyle.mediaRadius : 0))
        } else {
            Group {
                if inlineMetadata { Text(lensText) }
                else { UnyriMessageRichText(source: displayedText).equatable() }
            }
                .lineSpacing(appearance.extraLineSpacing * bodyScale)
                .font(messageFont)
                .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                .fixedSize(horizontal: false, vertical: true)


        }
    }

    private func replyPreview(_ snapshot: ReplySnapshot) -> some View {
        HStack(spacing: 6) {
            Rectangle()
                .fill(isMine ? UnyriSavedChatStyle.outText.opacity(0.7) : UnyriColors.accentSoft)
                .frame(width: 3, height: 32)
                .clipShape(RoundedRectangle(cornerRadius: 2))
            VStack(alignment: .leading, spacing: 1) {
                Text(snapshot.senderId == currentUserId ? UnyriL10n.string("You") : replyPeerName)
                    .font(.caption)
                    .foregroundColor(UnyriColors.accentSoft)
                Text(chatPresentationText(UnyriReplyPreviewText.snapshot(snapshot), inline: true))
                    .font(appearance.messageFont(scale: bodyScale * 0.88))
                    .foregroundColor(isMine ? UnyriSavedChatStyle.outText.opacity(0.85) : UnyriColors.secondaryText)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background((isMine ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text).opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var displayedReactions: [ReactionSummary] {
        // Media keeps its existing compact presentation. Text, voice and files
        // deliberately show every active reaction as a separate emoji: there
        // are no counters and no silent two-reaction cap.
        let perEmojiLimit = edgeToEdgeMedia ? 2 : Int.max
        return reactions.flatMap { reaction in
            Array(repeating: reaction, count: max(0, min(perEmojiLimit, reaction.count)))
        }
    }

    private var reactionsBar: some View {
        let layout = edgeToEdgeMedia ? AnyLayout(HStackLayout(spacing: 6)) : AnyLayout(UnyriReactionRows())
        return layout {
            ForEach(Array(displayedReactions.enumerated()), id: \.offset) { _, reaction in
                Button {
                    if reaction.isMine { onRemoveReaction() } else { onReact(reaction.emoji) }
                } label: {
                    HStack(spacing: 4) {
                        Text(reaction.emoji).font(.system(size: UnyriSavedChatStyle.emojiSize * bodyScale))
                    }
                    .foregroundColor(reaction.isMine ? UnyriColors.accentSoft : UnyriColors.secondaryText)
                    .frame(minWidth: max(UnyriSavedChatStyle.reactionWidth, UnyriSavedChatStyle.emojiSize * bodyScale + 10),
                           minHeight: max(UnyriSavedChatStyle.reactionHeight, UnyriSavedChatStyle.emojiSize * bodyScale + 6))
                    .background(UnyriSavedChatStyle.reaction, in: Capsule())
                    .contentShape(Rectangle().inset(by: -4))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("message-reaction-\(reaction.emoji)")
                .accessibilityAddTraits(reaction.isMine ? .isSelected : [])
            }
        }
    }

    /// Timestamp (+ status/ticks for own messages) for every non-deleted
    /// message — Android parity. Subtle, never overlaps bubble content.
    @ViewBuilder
    private var metaLine: some View {
        HStack(spacing: 4) {
            if voiceCard && isMine && message.status == .pending { UnyriVoiceTransferCaption(id: message.clientMessageId) }
            UnyriCompactMessageMetadata(date: message.createdAt, status: message.status, outgoing: isMine, edited: message.isEdited,
                                       foreground: edgeToEdgeMedia ? .white : nil)
            if isMine && message.status == .failed && !edgeToEdgeMedia {
                Button("Retry", action: onRetry).buttonStyle(.plain).foregroundStyle(UnyriColors.danger)
            }
        }
        .font(.system(size: UnyriSavedChatStyle.timeSize * bodyScale))
        .foregroundStyle(edgeToEdgeMedia ? .white : UnyriSavedChatStyle.muted)
    }

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private func showMessageMenu() {
        guard !selecting else { return }
        let readable = message.status != .decryptFailed && message.status != .identityChanged
        let remoteActions = UnyriQuickReactionPolicy.eligible(status: message.status.rawValue,
            serverId: message.serverId, deleted: message.isDeletedForEveryone || message.hiddenLocally, selecting: selecting)
        var primary: [UnyriMessageMenuAction] = []
        var extra: [UnyriMessageMenuAction] = []
        var deletion: [UnyriMessageMenuAction] = []
        func item(_ id: String, _ title: String, _ symbol: String, _ action: @escaping () -> Void, destructive: Bool = false) -> UnyriMessageMenuAction {
            UnyriMessageMenuAction(id: id, title: title, symbol: symbol, destructive: destructive, perform: action)
        }
        if !message.isDeletedForEveryone && readable {
            if remoteActions { primary.append(item("reply", "Reply", "arrowshape.turn.up.left", onReply)) }
            if !(message.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                primary.append(item("copy", "Copy", "doc.on.doc", onCopy))
            }
            if remoteActions && message.content.directFile == nil { primary.append(item("forward", "Forward", "arrowshape.turn.up.right", onForward)) }
            // Pending/failed text can be edited too: the repository freezes
            // the in-flight payload and applies the latest intent after serverId.
            if isMine && message.kind == "content" && message.content.attachment == nil && message.content.conferenceInvite == nil && message.content.directFile == nil && pendingPreview == nil {
                primary.append(item("edit", "Edit", "pencil", onEdit))
            }
            if let onShowFullText { extra.append(item("full-text", "Show full text", "doc.text", onShowFullText)) }
            extra.append(item("pin", isPinned ? "Unpin" : "Pin for me", "pin", onPin))
            if remoteActions && message.content.directFile == nil { extra.append(item("save", "Save to Saved Messages", "bookmark", onSave)) }
            extra.append(item("remind", "Remind me", "clock", onRemind))
        }
        primary.append(item("select", "Select", "checkmark.circle", onSelect))
        if actionMemberCount > 1 {
            // This row opens the existing shared confirmation once. Its IDs
            // were captured from the visible album when this menu opened.
            primary.append(item("delete-batch", UnyriL10n.string("Delete batch") + " (\(actionMemberCount))", "trash", onDeleteForMe, destructive: true))
            if let onSelectIndividual { extra.append(item("select-individual", "Select this item only", "checkmark.circle", onSelectIndividual)) }
            if let onDeleteIndividual { extra.append(item("delete-individual", "Delete this item only", "trash", onDeleteIndividual, destructive: true)) }
        } else {
            if isMine && message.kind == "content" && remoteActions {
                deletion.append(item("all", "Delete for everyone", "trash", onDeleteForEveryone, destructive: true))
            }
            let cancel = isMine && message.serverId == nil && (message.status == .pending || message.status == .failed)
            deletion.append(item("me", cancel ? "Cancel sending" : "Delete for me", "trash", onDeleteForMe, destructive: true))
        }
        if reactions.contains(where: { $0.isMine }), !message.isDeletedForEveryone {
            extra.append(item("reaction", "Remove reaction", "hand.thumbsdown", onRemoveReaction))
        }
        messageMenu.show(primary: primary, extra: extra, deletion: deletion,
                         reactions: remoteActions ? quickReactions : [],
                         selectedReaction: reactions.first(where: { $0.isMine })?.emoji,
                         react: { emoji in
                             if reactions.contains(where: { $0.isMine && $0.emoji == emoji }) { onRemoveReaction() }
                             else { onReact(emoji) }
                         }, outgoing: isMine)
    }
}

// MARK: - Attachment bubble

/// Renders an attachment message: a low-res inline thumbnail for images (tap
/// to download+decrypt the full file), a play/pause + waveform for voice, or a
/// filename/file icon for generic files. Mirror of Android's attachment bubble
/// (Этап H п.34 tap-to-download).
private struct AttachmentSheetGlass: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 16.4, *) {
            content.presentationBackground(Color(hex: UnyriAttachmentPickerStyle.background).opacity(Double(UnyriAttachmentPickerStyle.opacity / 100)))
                .presentationCornerRadius(UnyriAttachmentPickerStyle.radius)
        } else {
            content.background(Color(hex: UnyriAttachmentPickerStyle.background).opacity(Double(UnyriAttachmentPickerStyle.opacity / 100)))
                .clipShape(RoundedRectangle(cornerRadius: UnyriAttachmentPickerStyle.radius))
        }
    }
}

private struct UnyriFileTypeBadge: View {
    let mime: String
    let filename: String?
    private var label: String { FilePresentation.label(mime: mime, filename: filename) }
    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: FilePresentation.symbol(label)).font(.system(size: 20))
        }.foregroundStyle(UnyriSavedChatStyle.accent).frame(width: 31, height: 35).background(Color(hex: 0x414141), in: Circle())
    }
}

private struct FileDownloadRing: View {
    @ObservedObject var meter: AttachmentDownloadMeter
    let cancel: () -> Void
    var body: some View {
        Button { UnyriHaptics.selection(); cancel() } label: {
            ZStack {
                Circle().stroke(UnyriColors.mutedText.opacity(0.2), lineWidth: 2)
                Circle().trim(from: 0, to: CGFloat(meter.percent) / 100).stroke(UnyriColors.accentSoft, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
                Image(systemName: "xmark").font(.caption)
            }.frame(width: 30, height: 30).frame(width: 44, height: 44)
        }.accessibilityLabel("Cancel download").accessibilityValue("\(meter.percent)%")
    }
}

private struct AttachmentBubbleView: View {
    @Environment(\.unyriChatAppearance) private var appearance
    @Environment(\.chatAlbumCell) private var isAlbumCell
    @Environment(\.chatAlbumWidth) private var albumWidth
    @Environment(\.chatMediaHeight) private var mediaCellHeight
    @EnvironmentObject private var attachments: AttachmentManager
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @EnvironmentObject private var voiceRecorder: VoiceRecorder
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    @Environment(\.scenePhase) private var mediaScenePhase
    @Environment(\.unyriMessagePreviewState) private var menuPreviewState
    @ObservedObject private var mediaWork = UnyriMediaWorkState.shared
    @Environment(\.unyriCancelDownload) private var cancelDownload
    @State private var downloadCancelled = false
    @ObservedObject private var mediaNetwork = UnyriMediaNetwork.shared
    @ObservedObject private var mediaSettings = UnyriDeviceSettings.shared
    @State private var cacheLease: MediaCacheLease?
    @Environment(\.unyriForwardMedia) private var forwardMedia
    @State private var pendingMediaForward: String?
    @Environment(\.unyriMediaItems) private var mediaItems
    @State private var showMediaViewer = false
    @Environment(\.chatMediaWidth) private var mediaCellWidth
    @Environment(\.displayScale) private var displayScale
    let attachment: AttachmentMetadata
    let isMine: Bool
    var pendingSend: Bool = false
    let onDownload: (String) async -> URL?
    let onCachedLocalPath: (String) -> String?
    let isVoicePlaying: (String) -> Bool
    let voicePositionMs: (String) -> Int
    let onToggleVoice: (String, String) -> Void
    var fileMetadata: AnyView? = nil
    var voiceTransferControl: AnyView? = nil
    var fileTransferControl: AnyView? = nil

    @EnvironmentObject private var mediaRepository: MessagesRepository
    @State private var localImage: UIImage?
    @State private var localPath: String?
    @State private var previewURL: URL?
    @State private var downloadRequests = Set<UUID>()
    private var isDownloading: Bool { !downloadRequests.isEmpty }
    @State private var downloadFailed = false
    // A newly created offscreen row must not begin thumbnail extraction before
    // the scroll visibility callback has reported its actual position.
    @State private var mediaVisible = false

    private var availablePath: String? { localPath ?? (isMenuPreview ? onCachedLocalPath(attachment.attachmentId) : nil) }
    private var previewWorkAllowed: Bool { !isMenuPreview && mediaVisible && mediaScenePhase == .active && !mediaWork.viewerOpen }
    private var displayDownloading: Bool { isMenuPreview ? menuPreviewState?.downloading ?? false : isDownloading }
    private var displayDownloadFailed: Bool { isMenuPreview ? menuPreviewState?.downloadFailed ?? false : downloadFailed }

    var body: some View {
        Group {
            if attachment.mediaType == AttachmentMetadata.mediaTypeImage {
                imageContent.modifier(UnyriMessageTap(single: {
                    if localPath != nil || !isDownloading { UnyriHaptics.press() }
                    if localPath != nil { showMediaViewer = true }
                    else { Task { await download() } }
                }))
            } else if attachment.mediaType == AttachmentMetadata.mediaTypeVoice {
                voiceContent
            } else {
                fileContent
            }
        }
        .frame(maxWidth: mediaCellWidth ?? ((attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/")) ? nil : .infinity))
        .clipShape(RoundedRectangle(cornerRadius: isAlbumCell ? 0 : UnyriSavedChatStyle.mediaRadius))

        .onAppear {
            if localPath == nil {
                localPath = onCachedLocalPath(attachment.attachmentId)
            }
            if !isMenuPreview, let image = transitionImage {
                menuPreviewState?.retainMediaImage(image, attachmentID: attachment.attachmentId)
            }
        }
        .onReceive(mediaRepository.$attachmentCacheRevision) { _ in
            let cached = onCachedLocalPath(attachment.attachmentId)
            if cached != localPath {
                localPath = cached
                if cached != nil { downloadFailed = false }
            }
        }
        .onChange(of: localPath) { path in cacheLease = path.map { MediaCacheLease(path: $0) } }
        .onAppear { cacheLease = localPath.map { MediaCacheLease(path: $0) } }
        .onDisappear { cacheLease = nil }
        .onChange(of: isDownloading) { value in if !isMenuPreview { menuPreviewState?.downloading = value } }
        .onChange(of: downloadFailed) { value in if !isMenuPreview { menuPreviewState?.downloadFailed = value } }
        .modifier(UnyriMediaVisibility { mediaVisible = $0 })
        .task(id: "\(localPath ?? ""):\(previewPixelEdge):\(previewWorkAllowed)") {
            guard previewWorkAllowed else { return }
            guard attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("image/") || attachment.mimeType.hasPrefix("video/") else { return }
            let loaded = await ConversationImages.load(path: attachment.mimeType.hasPrefix("video/") ? nil : localPath, thumbnail: attachment.thumbnail, pixelEdge: previewPixelEdge)
            guard !Task.isCancelled else { return }
            if let loaded {
                menuPreviewState?.retainMediaImage(loaded, attachmentID: attachment.attachmentId)
                // Cached photos and thumbnail upgrades must never fade on chat entry.
                var transaction = Transaction(animation: nil)
                transaction.disablesAnimations = true
                withTransaction(transaction) { localImage = loaded }
            }
        }
        .task(id: "\(attachment.attachmentId):\(pendingSend):\(previewWorkAllowed):\(mediaNetwork.connected):\(mediaNetwork.wifi):\(mediaSettings.downloads)") {
            if !pendingSend, previewWorkAllowed, localPath == nil, !downloadFailed,
                mediaSettings.allows(mediaType: attachment.mediaType, mime: attachment.mimeType, bytes: attachment.size) {
                await download(origin: .automatic)
            }
        }
        .overlay(alignment: .bottom) { if !pendingSend && displayDownloading && attachment.mediaType != AttachmentMetadata.mediaTypeFile { Button("Cancel") { UnyriHaptics.selection(); downloadCancelled = true; cancelDownload(attachment.attachmentId) }.buttonStyle(.borderedProminent).foregroundStyle(UnyriColors.onAccent).tint(UnyriColors.accent) } }
        .quickLookPreview($previewURL)
        .background(UnyriMediaRotation(active: previewURL != nil))
        .background {
            if !isMenuPreview, attachment.mimeType.hasPrefix("image/") || attachment.mimeType.hasPrefix("video/") {
                UnyriMediaPresentation(presented: $showMediaViewer, id: attachment.attachmentId,
                    image: { attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/") ? transitionImage : nil }, onDismiss: {
                        if let id = pendingMediaForward { pendingMediaForward = nil; forwardMedia?(id) }
                    }) { close, selection in
                        if let path = localPath {
                            let url = URL(fileURLWithPath: path)
                            return AnyView(UnyriMediaViewer(items: mediaItems.contains(where: { $0.id == attachment.attachmentId }) ? mediaItems : [UnyriMediaItem(id: attachment.attachmentId, url: url, mime: attachment.mimeType, name: attachment.filename, thumbnailBase64: attachment.thumbnail)],
                                initial: url, download: { await mediaRepository.downloadAttachment(attachmentId: $0, origin: .mediaViewer) }, onForward: forwardMedia == nil ? nil : { id in
                                    pendingMediaForward = id; close()
                                }, onClose: close, onSelection: selection, initialPreview: transitionImage, initialID: attachment.attachmentId)
                                .environmentObject(voicePlayer).environmentObject(voiceRecorder)
                                .environment(\.locale, Locale(identifier: UnyriL10n.language)))
                        }
                        return AnyView(Color.black)
                    }
            }
        }

    }

    private var transitionImage: UIImage? {
        if isMenuPreview, let retained = menuPreviewState?.mediaImage(attachmentID: attachment.attachmentId) { return retained }
        if let localImage { return localImage }
        if attachment.mimeType.hasPrefix("video/"), let path = availablePath,
           let frame = ConversationImages.cached(path: "video-frame:768:" + path, thumbnail: nil) { return frame }
        if isMenuPreview && !attachment.mimeType.hasPrefix("video/") {
            // A caption preview can be wider than its original mosaic tile.
            // Reuse its already-decoded smaller pixels without starting work.
            for edge in [768, 512, 384] {
                if let cached = ConversationImages.cached(path: availablePath, thumbnail: attachment.thumbnail, pixelEdge: edge) { return cached }
            }
        }
        return ConversationImages.cached(path: attachment.mimeType.hasPrefix("video/") ? nil : availablePath,
            thumbnail: attachment.thumbnail, pixelEdge: previewPixelEdge)
    }

    private var previewPixelEdge: Int {
        let pixels = max(imagePreviewSize.width, imagePreviewSize.height) * displayScale
        return pixels <= 384 ? 384 : pixels <= 512 ? 512 : 768
    }

    private var imagePreviewSize: CGSize {
        unyriChatVisualSize(width: attachment.image?.width ?? 1, height: attachment.image?.height ?? 1,
                          cellWidth: mediaCellWidth, cellHeight: mediaCellHeight)
    }

    private var imageContent: some View {
        ZStack(alignment: .center) {
            if let image = transitionImage {
                Image(uiImage: image).resizable()
                    .aspectRatio(contentMode: mediaCellWidth == nil ? .fit : .fill)
                    .frame(width: imagePreviewSize.width, height: imagePreviewSize.height).clipped()
                    .accessibilityIdentifier(isMenuPreview ? "message-menu-photo-preview" : "chat-photo-preview")
            }
            else { UnyriColors.deepPanel.overlay(Image(systemName: "photo").foregroundColor(.secondary)) }
            if !pendingSend && availablePath == nil {
                Group {
                    if displayDownloading { ProgressView().tint(.white) }
                    else { Image(systemName: displayDownloadFailed ? "arrow.clockwise" : "arrow.down") }
                }
                .font(.system(size: 20, weight: .semibold)).foregroundColor(.white)
                .frame(width: 44, height: 44).background(Circle().fill(.black.opacity(0.5))).padding(8)
                .accessibilityLabel(isDownloading ? "Downloading…" : downloadFailed ? "Retry" : "Download photo")
            }
        }
        .frame(width: imagePreviewSize.width, height: imagePreviewSize.height)
        .accessibilityIdentifier(isAlbumCell ? "chat-album-photo" : "chat-photo")
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("Photo")
    }

    @ViewBuilder
    private var voiceContent: some View {
        let active = isVoicePlaying(attachment.attachmentId) || voicePlayer.state.pausedAttachmentId == attachment.attachmentId
        if displayDownloadFailed {
            Button { UnyriHaptics.press(); Task { await download() } } label: {
                Label("Retry voice message", systemImage: "arrow.clockwise").font(.caption).foregroundStyle(UnyriColors.danger).frame(minHeight: 44)
            }.buttonStyle(.plain)
        } else if active {
            VoiceProgressView(progress: voicePlayer.progress) { position in voiceRow(position: position, active: true) }
        } else { voiceRow(position: 0, active: false) }
    }

    private func voiceRow(position: Int, active: Bool) -> some View {
        let duration = attachment.voice?.durationMs ?? 0
        return UnyriVoiceMessageContent(duration: duration, position: position, active: active,
            playing: isVoicePlaying(attachment.attachmentId), downloading: displayDownloading,
            available: availablePath != nil, speed: voicePlayer.state.speed,
            waveform: AnyView(voiceWaveform(fraction: VoiceFormat.playedFraction(positionMs: position, durationMs: duration))),
            action: toggleVoice, cycleSpeed: { UnyriHaptics.selection(); voicePlayer.cycleSpeed() }, transferControl: voiceTransferControl)
    }

    /// Real local audio envelope; progress only changes the played-color boundary.
    private func voiceWaveform(fraction: Double) -> some View {
        GeometryReader { geometry in
            AudioEnvelopeView(path: availablePath, fraction: fraction,
                played: UnyriSavedChatStyle.accent,
                unplayed: UnyriSavedChatStyle.waveQuiet)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                    guard voicePlayer.state.playingAttachmentId == attachment.attachmentId || voicePlayer.state.pausedAttachmentId == attachment.attachmentId else { return }
                    voicePlayer.seek(fraction: Double(value.location.x / max(1, geometry.size.width)))
                }.onEnded { _ in
                    guard voicePlayer.state.playingAttachmentId == attachment.attachmentId || voicePlayer.state.pausedAttachmentId == attachment.attachmentId else { return }
                    UnyriHaptics.selection()
                })
        }.frame(minWidth: 20, maxWidth: .infinity).frame(height: UnyriSavedChatStyle.waveHeight)
    }

    private var fileContent: some View {
        VStack(alignment: .leading, spacing: 6) {
            if attachment.mimeType.hasPrefix("video/") { videoCover }
            if attachment.mimeType.hasPrefix("video/") && displayDownloadFailed {
                Button("Download failed · Tap to retry") { UnyriHaptics.press(); Task { await download() } }.font(.caption).padding(6)
            }
            if !attachment.mimeType.hasPrefix("video/") { fileDetails }
        }
    }

    private var videoCover: some View {
        let size = unyriChatVisualSize(width: attachment.image?.width ?? 4, height: attachment.image?.height ?? 3,
            cellWidth: mediaCellWidth, cellHeight: mediaCellHeight)
        let width = CGFloat(size.width)
        let height = CGFloat(size.height)
        let preview = transitionImage
        return UnyriMessageActionButton {
            UnyriHaptics.press()
            if localPath != nil { showMediaViewer = true }
            else { Task { await download() } }
        } label: {
            ZStack {
                Color.black.opacity(0.35)
                if let image = preview {
                    Image(uiImage: image).resizable().scaledToFill()
                        .accessibilityIdentifier(isMenuPreview ? "message-menu-video-preview" : "chat-video-preview")
                }
                else if let path = availablePath {
                    UnyriVideoThumbnail(url: URL(fileURLWithPath: path), compact: false, showsDuration: false, workAllowed: previewWorkAllowed, onPreview: { image in
                        guard !isMenuPreview else { return }
                        menuPreviewState?.retainMediaImage(image, attachmentID: attachment.attachmentId)
                    })
                }
                if !pendingSend && displayDownloading { ProgressView().tint(.white) }
                else if !pendingSend && (preview != nil || availablePath == nil) {
                    Image(systemName: availablePath == nil ? "arrow.down" : "play.fill")
                        .font(.title2).foregroundStyle(.white).frame(width: 48, height: 48)
                        .background(.black.opacity(0.55), in: Circle())
                }
            }
            .frame(width: width, height: height).clipped()
            .overlay(alignment: .topLeading) {
                if attachment.video?.durationMs != nil {
                Text(FilePresentation.duration(attachment.video?.durationMs)).font(.caption.monospacedDigit())
                    .foregroundStyle(.white).padding(4).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 4)).padding(6)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: isAlbumCell ? 0 : UnyriSavedChatStyle.mediaRadius))
        }.buttonStyle(.plain).disabled(isDownloading)
          .accessibilityIdentifier("chat-video-" + attachment.attachmentId)
          .accessibilityIdentifier(isAlbumCell ? "chat-album-video" : "chat-video-" + attachment.attachmentId)
          .accessibilityValue(preview == nil ? "" : UnyriL10n.string("Video"))
          .accessibilityLabel(localPath == nil ? UnyriL10n.string("Download file", comment: "") : UnyriL10n.string("Open", comment: ""))
    }

    private var fileDetails: some View {
        UnyriFileMessageContent(filename: attachment.filename ?? UnyriL10n.string("File"),
            details: FilePresentation.label(mime: attachment.mimeType, filename: attachment.filename) + " · " + ByteCountFormatter.string(fromByteCount: attachment.size, countStyle: .file),
            metadata: fileMetadata,
            icon: AnyView(Group {
                if let fileTransferControl { fileTransferControl }
                else if displayDownloading {
                    FileDownloadRing(meter: attachments.downloadMeter(attachment.attachmentId)) {
                        downloadCancelled = true; cancelDownload(attachment.attachmentId)
                    }
                } else {
                    UnyriMessageActionButton(action: openFileAttachment) {
                        Image(systemName: availablePath == nil ? "arrow.down" : "doc")
                            .font(.system(size: 21)).foregroundStyle(UnyriSavedChatStyle.accent)
                            .frame(width: 31, height: 35).background(Color(hex: 0x414141), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel(availablePath == nil ? "Download file" : "Open file")
                }
            }), action: openFileAttachment)
    }

    private func openFileAttachment() {
        UnyriHaptics.press()
        if let path = localPath {
            if attachment.mimeType.hasPrefix("image/") { showMediaViewer = true }
            else { previewURL = URL(fileURLWithPath: path) }
        } else if !isDownloading { Task { await download() } }
    }

    private func placeholder(_ symbol: String) -> some View {
        HStack {
            Spacer()
            Image(systemName: symbol)
                .font(.system(size: 28))
                .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.secondaryText)
            Spacer()
        }
        .frame(width: 160, height: 120)
        .background(UnyriBubbleFill(outgoing: isMine))
    }

    private func toggleVoice() {
        if let path = availablePath {
            onToggleVoice(attachment.attachmentId, path)
        } else {
            UnyriHaptics.press()
            Task { await download() }
        }
    }

    private func download(origin: AttachmentDownloadOrigin = .messageTap) async {
        guard !pendingSend, !isMenuPreview, !Task.isCancelled, origin == .automatic || !isDownloading else { return }
        // A replacement automatic task may enter before the cancelled task's
        // defer runs. Independent request tokens avoid dropping that restart or
        // incorrectly clearing a still-active row indicator.
        let request = UUID()
        downloadRequests.insert(request)
        downloadCancelled = false
        downloadFailed = false
        defer { downloadRequests.remove(request) }
        let downloaded: URL?
        if origin == .automatic { downloaded = await mediaRepository.downloadAttachment(attachmentId: attachment.attachmentId, origin: origin) }
        else { downloaded = await onDownload(attachment.attachmentId) }
        guard !Task.isCancelled else { return }
        guard let url = downloaded else {
            downloadFailed = !downloadCancelled
            return
        }
        downloadFailed = false
        localPath = url.path
        // The localPath task decodes the downloaded image off the main actor.

    }
}

private struct ChatItemFrameBatch: Equatable {
    var context: String? = nil
    var frames: [String: CGRect] = [:]
}

private struct ChatItemFrames: PreferenceKey {
    static var defaultValue = ChatItemFrameBatch()
    static func reduce(value: inout ChatItemFrameBatch, nextValue: () -> ChatItemFrameBatch) {
        value.frames.merge(nextValue().frames, uniquingKeysWith: { _, new in new })
    }
}

// Raw pixel positions change on every scroll frame. They are measurements, not
// rendered state: mutating this cache must not invalidate the whole chat body.
// Only semantic changes such as isNearBottom/pendingScrollTarget publish state.
private final class ChatMeasuredGeometry {
    var bottom: ChatBottomFrames?
    var items: [String: CGRect] = [:]
    var itemsIdentity: String?
    func clear() { bottom = nil; items = [:]; itemsIdentity = nil }
}

private struct ChatBottomFrames: Equatable {
    var content: CGRect?
    var end: CGRect?
    let viewport: CGSize
}

private struct ChatBottomFramesKey: PreferenceKey {
    static var defaultValue: [String: ChatBottomFrames] = [:]
    static func reduce(value: inout [String: ChatBottomFrames], nextValue: () -> [String: ChatBottomFrames]) {
        for (identity, next) in nextValue() {
            if var existing = value[identity], existing.viewport == next.viewport {
                existing.content = next.content ?? existing.content
                existing.end = next.end ?? existing.end
                value[identity] = existing
            } else { value[identity] = next }
        }
    }
}

private struct VisibleMessageFrames: PreferenceKey {
    static var defaultValue: [Int64: CGRect] = [:]
    static func reduce(value: inout [Int64: CGRect], nextValue: () -> [Int64: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

/// The detector contains the matching rules, not cached message text. Reuse it
/// instead of constructing the same rules for every bubble body evaluation.
private enum ChatLinkDetection {
    static let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
}

private func chatLinkAddress(_ raw: String) -> URL? {
    let source = chatPresentationText(raw, inline: true)
    guard let detector = ChatLinkDetection.detector else { return nil }
    return detector.matches(in: source, range: NSRange(source.startIndex..., in: source)).compactMap(\.url).first {
        ["https", "http"].contains($0.scheme?.lowercased() ?? "") && $0.host != nil && $0.user == nil
    }
}

/// A receipt or reaction can update the bubble without changing its text.
/// Keep Markdown/link parsing behind a text-only equality boundary.
private struct UnyriMessageRichText: View, Equatable {
    let source: String
    var body: some View { Text(chatRichText(source)) }
}

private func chatPresentationText(_ raw: String, inline: Bool = false) -> String {
    UnyriMessageTextPresentation.display(raw, inline: inline)
}

private func chatRichText(_ raw: String) -> AttributedString {
    let source = chatPresentationText(raw)
    guard MessageTextPolicy.allowsRichParsing(source) else { return AttributedString(source) }
    var result = (try? AttributedString(markdown: source, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(source)
    for run in result.runs {
        if let url = run.link, !["https", "http"].contains(url.scheme?.lowercased() ?? "") {
            result[run.range].link = nil
        }
    }
    let plain = String(result.characters)
    if let detector = ChatLinkDetection.detector {
        for match in detector.matches(in: plain, range: NSRange(plain.startIndex..., in: plain)) {
            guard let url = match.url, ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
                  let range = Range(match.range, in: plain),
                  let lower = AttributedString.Index(range.lowerBound, within: result),
                  let upper = AttributedString.Index(range.upperBound, within: result) else { continue }
            result[lower..<upper].link = url
        }
    }
    return result
}

private struct ChatMediaWidthKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatMediaHeightKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatAlbumCellKey: EnvironmentKey { static let defaultValue = false }
private struct ChatAlbumWidthKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatViewportWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 360 }
private extension EnvironmentValues {
    var chatMediaWidth: CGFloat? {
        get { self[ChatMediaWidthKey.self] }
        set { self[ChatMediaWidthKey.self] = newValue }
    }
    var chatMediaHeight: CGFloat? {
        get { self[ChatMediaHeightKey.self] }
        set { self[ChatMediaHeightKey.self] = newValue }
    }
    var chatAlbumCell: Bool {
        get { self[ChatAlbumCellKey.self] }
        set { self[ChatAlbumCellKey.self] = newValue }
    }
    var chatAlbumWidth: CGFloat? {
        get { self[ChatAlbumWidthKey.self] }
        set { self[ChatAlbumWidthKey.self] = newValue }
    }
    var chatViewportWidth: CGFloat {
        get { self[ChatViewportWidthKey.self] }
        set { self[ChatViewportWidthKey.self] = newValue }
    }
}

private func unyriChatVisualSize(width: Int, height: Int, cellWidth: CGFloat?, cellHeight: CGFloat?) -> CGSize {
    if let cellWidth, let cellHeight { return CGSize(width: max(1, cellWidth), height: max(1, cellHeight)) }
    let available = min(UnyriSavedChatStyle.mediaWidth, cellWidth ?? UnyriSavedChatStyle.mediaWidth)
    let ratio = min(5, max(0.2, CGFloat(max(1, width)) / CGFloat(max(1, height))))
    let h = min(270, available / ratio)
    return CGSize(width: min(available, h * ratio), height: h)
}

private struct UnyriAlbumMosaicLayout: Layout {
    let mosaic: UnyriAlbumMosaic
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: mosaic.width, height: mosaic.height)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (index, view) in subviews.enumerated() where mosaic.tiles.indices.contains(index) {
            let tile = mosaic.tiles[index]
            view.place(at: CGPoint(x: bounds.minX + tile.x, y: bounds.minY + tile.y), anchor: .topLeading,
                       proposal: ProposedViewSize(width: tile.width, height: tile.height))
        }
    }
}

private struct UnyriAlbumVisualRun<Content: View>: View {
    private struct GeometryKey: Equatable {
        let ratios: [Double]
        let width: Double
    }
    let ids: [String]
    let ratios: [Double]
    let width: CGFloat
    @ViewBuilder let cell: (Int, UnyriAlbumTile) -> Content
    @State private var geometry = ChatPresentationCache<GeometryKey, UnyriAlbumMosaic>()
    var body: some View {
        let key = GeometryKey(ratios: ratios, width: Double(width))
        let mosaic = geometry.value(for: key) { UnyriAlbumMosaic.make(ratios: ratios, width: Double(width), gap: Double(UnyriSavedChatStyle.albumGap)) }
        UnyriAlbumMosaicLayout(mosaic: mosaic) {
            ForEach(Array(ids.enumerated()), id: \.element) { index, _ in cell(index, mosaic.tiles[index]) }
        }
    }
}

private struct UnyriCompactMessageMetadata: View {
    let date: Date
    let status: MessageStatus
    let outgoing: Bool
    var edited = false
    var foreground: Color? = nil
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    private static let formatter: DateFormatter = { let value = DateFormatter(); value.dateFormat = "HH:mm"; return value }()
    var body: some View {
        HStack(spacing: UnyriSavedChatStyle.metaGap) {
            Text(Self.formatter.string(from: date)).fixedSize()
            if outgoing { UnyriMessageReceipt(status: status) }
            if edited { Text("edited") }
        }
        .font(.system(size: UnyriSavedChatStyle.timeSize * scale))
        .foregroundStyle(foreground ?? UnyriSavedChatStyle.muted)
    }
}

private struct UnyriMessageReceipt: View {
    let status: MessageStatus
    var body: some View {
        Group {
            switch status {
            case .pending: Image(systemName: "clock").accessibilityLabel("Sending…")
            case .sent: Text("✓").accessibilityLabel("Sent")
            case .delivered: Text("✓✓").accessibilityLabel("Delivered")
            case .read: Text("✓✓").accessibilityLabel("Read")
            case .failed: Image(systemName: "exclamationmark.circle").accessibilityLabel("Failed to send")
            case .decryptFailed, .identityChanged: EmptyView()
            }
        }.foregroundStyle(status == .read ? UnyriSavedChatStyle.readTicks : status == .pending || status == .failed ? UnyriSavedChatStyle.muted : UnyriSavedChatStyle.ticks)
            .fixedSize()
    }
}

/// The same compact content is used by live voice rows and the appearance
/// preview. Playback positions are supplied by the isolated progress observer.
private struct UnyriVoiceTransferControl: View {
    let id: String
    let cancel: () -> Void
    @EnvironmentObject private var attachments: AttachmentManager
    var body: some View { UnyriVoiceTransferRing(meter: attachments.sendMeter(id), cancel: cancel) }
}

private struct UnyriVoiceTransferRing: View {
    @ObservedObject var meter: AttachmentSendMeter
    let cancel: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Button { if UnyriMessageMenuPresenter.canActivateMessage { UnyriHaptics.selection(); cancel() } } label: {
            ZStack {
                Circle().fill(UnyriSavedChatStyle.accent)
                Circle().stroke(UnyriSavedChatStyle.buttonInk.opacity(0.2), lineWidth: 2)
                Circle().trim(from: 0, to: CGFloat(meter.percent ?? 0) / 100)
                    .stroke(UnyriSavedChatStyle.buttonInk, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(-90)).animation(reduceMotion ? nil : .linear(duration: 0.16), value: meter.percent)
                Image(systemName: "xmark").font(.system(size: 13, weight: .medium)).foregroundStyle(UnyriSavedChatStyle.buttonInk)
            }.frame(width: UnyriSavedChatStyle.playSize, height: UnyriSavedChatStyle.playSize).contentShape(Rectangle().inset(by: -6))
        }.buttonStyle(.plain).accessibilityLabel("Cancel sending")
    }
}

private struct UnyriVoiceTransferCaption: View {
    let id: String
    @EnvironmentObject private var attachments: AttachmentManager
    @EnvironmentObject private var realtime: RealtimeClient
    var body: some View { UnyriVoiceTransferLabel(meter: attachments.sendMeter(id), connected: realtime.state == .connected) }
}

private struct UnyriVoiceTransferLabel: View {
    @ObservedObject var meter: AttachmentSendMeter
    let connected: Bool
    var body: some View {
        Group {
            if !connected && !meter.stage.isLocalPreparation { Text("Waiting for connection") }
            else if let percent = meter.percent { Text("\(percent)%") }
            else { Text(LocalizedStringKey(meter.stage.label)) }
        }.font(.system(size: UnyriSavedChatStyle.timeSize)).foregroundStyle(UnyriSavedChatStyle.muted).lineLimit(1).truncationMode(.tail)
            .frame(maxWidth: 100, alignment: .leading)
    }
}

private struct UnyriVoiceMessageContent: View {
    let duration: Int64
    let position: Int
    let active: Bool
    let playing: Bool
    let downloading: Bool
    let available: Bool
    let speed: Float
    let waveform: AnyView
    let action: () -> Void
    let cycleSpeed: () -> Void
    var transferControl: AnyView? = nil
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    var body: some View {
        HStack(spacing: 7) {
            if let transferControl { transferControl }
            else { Button { if UnyriMessageMenuPresenter.canActivateMessage { action() } } label: {
                Group {
                    if downloading { ProgressView().tint(UnyriSavedChatStyle.buttonInk) }
                    else { Image(systemName: available ? playing ? "pause.fill" : "play.fill" : "arrow.down").font(.system(size: 17)) }
                }.foregroundStyle(UnyriSavedChatStyle.buttonInk)
                    .frame(width: UnyriSavedChatStyle.playSize, height: UnyriSavedChatStyle.playSize)
                    .background(UnyriSavedChatStyle.accent, in: Circle())
                    .contentShape(Rectangle().inset(by: -6))
            }.buttonStyle(.plain).accessibilityLabel(playing ? "Pause voice message" : available ? "Play voice message" : "Download voice message")
            }
            VStack(alignment: .leading, spacing: 0) {
                waveform.frame(height: UnyriSavedChatStyle.waveHeight)
                HStack(spacing: 3) {
                    Text(VoiceFormat.durationText(active ? Int64(position) : duration)).monospacedDigit().lineLimit(1)
                    Spacer(minLength: 0)
                    Button(action: cycleSpeed) {
                        Text(active ? speed == 1 ? "1×" : speed == 1.5 ? "1,5×" : "2×" : "1×")
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(UnyriSavedChatStyle.entry, in: Capsule())
                            .contentShape(Rectangle().inset(by: -8))
                    }.buttonStyle(.plain).disabled(!active).accessibilityLabel("Playback speed")
                }.font(.system(size: UnyriSavedChatStyle.timeSize * scale)).foregroundStyle(UnyriSavedChatStyle.muted)
                    .frame(minHeight: 18 * scale)
            }
        }
    }
}

private struct UnyriFileMessageContent: View {
    let filename: String
    let details: String
    var metadata: AnyView? = nil
    let icon: AnyView
    var action: (() -> Void)? = nil
    @Environment(\.unyriChatAppearance) private var appearance
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    var body: some View {
        HStack(spacing: 10) {
            icon.frame(width: 31, height: 48)
            Group {
                if let action { UnyriMessageActionButton(action: action) { labels }.buttonStyle(.plain) }
                else { labels }
            }
        }.foregroundStyle(UnyriSavedChatStyle.text).frame(maxWidth: .infinity, minHeight: 48)
    }
    private var labels: some View {
            VStack(alignment: .leading, spacing: 2) {
                Text(chatPresentationText(filename, inline: true)).font(appearance.messageFont(scale: scale).weight(.medium))
                    .lineLimit(1).truncationMode(.middle)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(details).lineLimit(1).truncationMode(.tail)
                    if let metadata { Spacer(minLength: 0); metadata.fixedSize() }
                }.font(.system(size: UnyriSavedChatStyle.timeSize * scale)).foregroundStyle(UnyriSavedChatStyle.muted)
            }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Read-only fixtures use the actual message surface, text, voice and file
/// components. They have no account, repository, network or playback objects.
struct UnyriAppearancePreviewMessages: View {
    let attachments: Bool
    @Environment(\.unyriChatAppearance) private var appearance
    @State private var fixtureImage: UIImage?
    private static let stamp = Date(timeIntervalSince1970: 1_700_000_000)
    private func message(_ id: String, text: String? = nil, kind: String? = nil, outgoing: Bool = false) -> DecryptedChatMessage {
        let attachment = kind.map { kind in AttachmentMetadata(attachmentId: id, mediaType: kind, objectKey: "preview",
            size: 245_760, encryptedSize: 245_760, mimeType: kind == "voice" ? "audio/mp4" : "application/pdf",
            encryption: EncryptionInfo(sha256: ""), filename: "Маршрут.pdf", image: nil,
            voice: kind == "voice" ? VoiceInfo(durationMs: 12_000) : nil, thumbnail: nil) }
        return DecryptedChatMessage(clientMessageId: id, serverId: id, chatId: "appearance-preview", senderId: outgoing ? "self" : "peer",
            sequence: 1, createdAt: Self.stamp, kind: "content", status: outgoing ? .read : .delivered,
            content: MessageContent(kind: attachment == nil ? "text" : "attachment", text: text, attachment: attachment))
    }
    private func bubble(_ message: DecryptedChatMessage, preview: AnyView? = nil, reactions: [ReactionSummary] = []) -> some View {
        MessageBubble(message: message, scheduledAt: nil, isPinned: false, isMine: message.senderId == "self", currentUserId: "self",
            reactions: reactions, onRetry: {}, onReply: {}, onJumpQuote: {}, onCopy: {}, onForward: {}, onSave: {}, onSelect: {},
            onPin: {}, onRemind: {}, onEdit: {}, onDeleteForEveryone: {}, onDeleteForMe: {}, onReact: { _ in }, onQuickReaction: nil,
            onRemoveReaction: {}, onDownloadAttachment: { _ in nil }, onCachedLocalPath: { _ in nil }, isVoicePlaying: { _ in false },
            voicePositionMs: { _ in 0 }, onToggleVoice: { _, _ in }, appearancePreviewContent: preview, appearancePreviewMode: true)
    }
    var body: some View {
        VStack(spacing: appearance.gap) {
            if attachments {
                HStack {
                    if let fixtureImage { Image(uiImage: fixtureImage).resizable().scaledToFill().frame(width: 180, height: 85).clipped().clipShape(UnyriSavedChatStyle.mediaShape) }
                    else { UnyriSavedChatStyle.entry.frame(width: 180, height: 85).clipShape(UnyriSavedChatStyle.mediaShape) }
                    Spacer(minLength: 0)
                }
                bubble(message("voice", kind: "voice"), preview: AnyView(UnyriVoiceMessageContent(duration: 12_000, position: 0, active: false,
                    playing: false, downloading: false, available: true, speed: 1,
                    waveform: AnyView(UnyriPreviewVoiceEnvelope()), action: {}, cycleSpeed: {})))
                bubble(message("file", kind: "file", outgoing: true), preview: AnyView(UnyriFileMessageContent(filename: "Маршрут.pdf", details: "PDF · 240 КБ",
                    metadata: AnyView(UnyriCompactMessageMetadata(date: Self.stamp, status: .read, outgoing: true)),
                    icon: AnyView(Image(systemName: "doc").font(.system(size: 21)).frame(width: 31, height: 35).background(Color(hex: 0x414141), in: Circle())))))
            } else {
                bubble(message("incoming", text: UnyriL10n.string("This is how your messages will look.")))
                bubble(message("outgoing", text: "UNYRI 👍", outgoing: true), reactions: [ReactionSummary(emoji: "❤️", count: 1, isMine: false)])
            }
        }.padding(UnyriSavedChatStyle.side).allowsHitTesting(false)
            .task(id: attachments) {
                guard attachments, fixtureImage == nil, let url = Bundle.main.url(forResource: "appearance_preview", withExtension: "jpg") else { return }
                let loaded = await ConversationImages.load(path: url.path, thumbnail: nil, pixelEdge: 384)
                guard !Task.isCancelled else { return }; fixtureImage = loaded
            }
    }
}

private struct UnyriPreviewVoiceEnvelope: View {
    var body: some View {
        Canvas { context, size in
            let values: [CGFloat] = [0.16,0.32,0.64,0.48,0.22,0.86,0.64,0.36,0.72,0.96,0.42,0.24,0.56,0.38,0.78,0.44,0.26,0.64,0.38,0.12]
            for (index, value) in values.enumerated() {
                let x = size.width * (CGFloat(index) + 0.5) / CGFloat(values.count), height = max(2, size.height * value)
                var path = Path(); path.move(to: CGPoint(x: x, y: (size.height - height) / 2)); path.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(path, with: .color(UnyriSavedChatStyle.waveQuiet), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
    }
}

private struct LiveDraftPreview: View {
    @ObservedObject var model: LiveDrafts
    var body: some View {
        if !model.peerText.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Partner’s draft").font(.caption).foregroundColor(UnyriColors.accentSoft)
                Text(model.peerText).font(.subheadline).foregroundColor(UnyriColors.secondaryText).lineLimit(3)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 12)
        }
    }
}

// Styling is scoped to selection actions; it does not change the surrounding panel.
private struct ChatSelectionActionStyle: ButtonStyle {
    let destructive: Bool
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.medium))
            .foregroundStyle((destructive ? UnyriColors.danger : UnyriColors.accentSoft).opacity(enabled ? 1 : 0.35))
            .background(Color(hex: destructive ? 0x24171D : 0x191727).opacity(enabled ? 0.94 : 0.55), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke((destructive ? UnyriColors.danger : UnyriColors.accentSoft).opacity(0.15), lineWidth: 0.5))
            .overlay(RoundedRectangle(cornerRadius: 18).fill(Color.white.opacity(configuration.isPressed && enabled ? 0.06 : 0)))
            .contentShape(RoundedRectangle(cornerRadius: 18))
    }
}

// The approved control palette is scoped to chat chrome, not message/reaction materials.
enum ChatControlPalette {
    static let blue = Color(red: 0.447, green: 0.749, blue: 0.98)
    static let blueBack = Color(red: 0.082, green: 0.173, blue: 0.282)
    static let backArrow = Color(red: 0.514, green: 0.655, blue: 1)
    static let mint = Color(red: 0.467, green: 0.902, blue: 0.776)
    static let mintBack = Color(red: 0.09, green: 0.243, blue: 0.22)
    static let coral = Color(red: 1, green: 0.631, blue: 0.553)
    static let coralBack = Color(red: 0.22, green: 0.157, blue: 0.192)
    static let amber = Color(red: 1, green: 0.8, blue: 0.447)
    static let amberBack = Color(red: 0.227, green: 0.176, blue: 0.098)
    static let violet = LinearGradient(colors: [Color(red: 0.537, green: 0.478, blue: 1), Color(red: 0.396, green: 0.345, blue: 0.91)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let header = LinearGradient(colors: [Color(hex: 0x202735), Color(hex: 0x191F2A)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let composer = LinearGradient(colors: [Color(red: 0.082, green: 0.118, blue: 0.173), Color(red: 0.106, green: 0.114, blue: 0.208)], startPoint: .leading, endPoint: .trailing)
    static let compactComposer = LinearGradient(colors: [Color(hex: 0x151B29), Color(hex: 0x191C2A)], startPoint: .leading, endPoint: .trailing)
    static let edge = Color(red: 0.467, green: 0.486, blue: 0.882)
    static let emojis = ["😊", "😂", "❤️", "👍", "🥰", "😍", "😘", "😁", "😅", "😉", "😎", "🤔", "😢", "😭", "😮", "😡", "🙏", "👏", "🔥", "🎉", "✅", "👋", "💜", "🤗"]
}
private struct ChatControlIcon: View {
    let name: String
    let tint: Color
    let fill: Color
    var vertical = false
    var body: some View {
        Image(systemName: name).font(.system(size: 22, weight: .regular))
            .rotationEffect(.degrees(vertical ? 90 : 0)).foregroundColor(tint)
            .frame(width: 38, height: 38).background(fill, in: Circle())
            .frame(width: 44, height: 44).contentShape(Rectangle())
    }
}

// Ask only the active editor for its selection before opening the emoji sheet.
// This uses the public responder/UITextInput APIs, without walking SwiftUI's view hierarchy.
private final class UnyriComposerSelection: NSObject { var range: NSRange? }
private extension UIResponder {
    @objc func unyriCaptureComposerSelection(_ sender: UnyriComposerSelection) {
        guard let editor = self as? UITextInput, let selected = editor.selectedTextRange else { return }
        let start = editor.offset(from: editor.beginningOfDocument, to: selected.start)
        let end = editor.offset(from: editor.beginningOfDocument, to: selected.end)
        guard start >= 0, end >= start else { return }
        sender.range = NSRange(location: start, length: end - start)
    }
}


// A queued inspection is not the single permitted mutation.
private struct ChatReaderCorrectionState {
    struct Ticket: Equatable {
        let epoch: UInt64
        let window: UUID
        let token: String
        let registration: UUID
    }
    private(set) var epoch: UInt64 = 0
    private(set) var active: String?
    private(set) var registration: UUID?
    private(set) var wrote = false
    private var window: UUID?
    private var deadline: TimeInterval?
    private var queued: Ticket?
    private var freshEventWhileQueued = false

    mutating func beginWindow(_ token: UUID, deadline: TimeInterval) {
        guard window != token else { return }
        cancel()
        guard deadline.isFinite, deadline >= 0 else { return }
        window = token; self.deadline = deadline
    }
    private func withinDeadline(_ now: TimeInterval) -> Bool {
        guard let deadline else { return false }
        return now.isFinite && now >= 0 && now < deadline
    }
    mutating func arm(_ token: String, now: TimeInterval) -> Bool {
        guard window != nil, withinDeadline(now), !token.isEmpty else { return false }
        if active == token { return true }
        epoch &+= 1; active = token
        queued = nil; freshEventWhileQueued = false
        return true
    }
    mutating func register(_ value: UUID) {
        guard registration != value else { return }
        epoch &+= 1; registration = value
        queued = nil; freshEventWhileQueued = false
        // View replacement never resets the intent's mutation budget.
    }
    mutating func unregister(_ value: UUID) -> Bool {
        guard registration == value else { return false }
        // Physical detachment invalidates that view's work, not the readiness window.
        epoch &+= 1; registration = nil; queued = nil; freshEventWhileQueued = false
        return true
    }
    mutating func invalidateInspection() {
        // A fresh layout invalidates observations, never the original window/budget.
        epoch &+= 1; queued = nil; freshEventWhileQueued = false
    }
    mutating func cancel() {
        // Authoritative cancellation ends readiness; a still-attached physical lease
        // remains registered so a later NEW window does not require updateUIView.
        epoch &+= 1; active = nil; wrote = false
        window = nil; deadline = nil; queued = nil; freshEventWhileQueued = false
    }
    mutating func enqueue(token: String, registration: UUID, now: TimeInterval) -> Ticket? {
        guard active == token, self.registration == registration, !wrote,
              let window, withinDeadline(now) else { return nil }
        if queued != nil { freshEventWhileQueued = true; return nil }
        let ticket = Ticket(epoch: epoch, window: window, token: token, registration: registration)
        queued = ticket
        return ticket
    }
    func isCurrent(_ ticket: Ticket, now: TimeInterval) -> Bool {
        queued == ticket && ticket.epoch == epoch && ticket.window == window &&
            active == ticket.token && registration == ticket.registration &&
            !wrote && withinDeadline(now)
    }
    mutating func finishInspection(_ ticket: Ticket) -> Bool {
        guard queued == ticket else { return false }
        queued = nil
        let anotherFreshEvent = freshEventWhileQueued && !wrote
        freshEventWhileQueued = false
        return anotherFreshEvent
    }
    mutating func consumeMutation(_ ticket: Ticket, now: TimeInterval) -> Bool {
        guard isCurrent(ticket, now: now) else { return false }
        wrote = true
        return true
    }

}

@MainActor
private final class ChatReaderCorrectionController: NSObject {
    private var state = ChatReaderCorrectionState()
    private weak var currentView: ChatReaderCorrectionView?
    // Independent stable container lease; fine arm/cancel/write never changes it.
    private var legacyOwner: ChatTimelineContainerOwner?
    private weak var legacyScroll: UIScrollView?
    private weak var legacyWindow: UIWindow?
    private weak var legacyPan: UIPanGestureRecognizer?
    private var legacyProbes: [UUID: ChatTimelineWeakProbe] = [:]
    private(set) var legacyContainerEpoch: UInt64 = 0
    private var legacyRefreshQueued = false
    private var legacyOwnerIsCurrent: (() -> Bool)?
    private var legacyRefresh: (() -> Void)?
    private var legacyUserPan: (() -> Void)?
    private var now: TimeInterval { Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000_000 }

    func invalidateObservation() {
        state.invalidateInspection()
    }
    func beginWindow(_ token: UUID, deadline: DispatchTime) {
        state.beginWindow(token, deadline: Double(deadline.uptimeNanoseconds) / 1_000_000_000)
    }
    func arm(_ token: String) {
        guard state.arm(token, now: now) else { return }
        // Arm is a fresh owned-intent event; retain live guards at inspection.
        if let view = currentView, view.controller === self, view.window != nil,
           view.token == token, state.registration == view.registration {
            request(view)
        }
    }
    func cancel(token: String? = nil) {
        if let token, state.active != token { return }
        state.cancel()
    }
    func attach(_ view: ChatReaderCorrectionView) {
        guard view.controller === self else { return }
        currentView = view
        state.register(view.registration)
    }
    func remount(_ view: ChatReaderCorrectionView) {
        // An old recycled view cannot reclaim a newer physical registration.
        guard currentView === view, view.controller === self, view.window != nil else { return }
        state.register(view.registration)
        request(view)
    }
    func detach(_ view: ChatReaderCorrectionView) {
        guard currentView === view, state.registration == view.registration else { return }
        guard state.unregister(view.registration) else { return }
        // Keep the weak current view across harmless reparenting.
    }

    private struct Snapshot {
        let window: UIWindow
        let scroll: UIScrollView
        let context: String
        let observationUptime: TimeInterval
        let ownerRect: CGRect
        let ownerSize: CGSize
        let desiredY: CGFloat
        let sourceRow: CGRect
        let nativeRow: CGRect
        let viewport: CGRect
        let offset: CGPoint
        let contentSize: CGSize
        let bounds: CGRect
        let insets: UIEdgeInsets
        let requested: CGFloat
        let minimum: CGFloat
        let maximum: CGFloat
        let tolerance: CGFloat

        func sameOwnerFields(_ view: ChatReaderCorrectionView) -> Bool {
            guard let observation = view.observation else { return false }
            return view.window === window && view.desiredY == desiredY &&
                observation.context == context && observation.observedUptime == observationUptime &&
                observation.viewportGlobal == ownerRect && observation.viewportSize == ownerSize
        }
        func sameGeometry(as other: Snapshot) -> Bool {
            window === other.window && scroll === other.scroll && context == other.context &&
                observationUptime == other.observationUptime && ownerRect == other.ownerRect &&
                ownerSize == other.ownerSize && desiredY == other.desiredY && sourceRow == other.sourceRow &&
                nativeRow == other.nativeRow && viewport == other.viewport && offset == other.offset &&
                contentSize == other.contentSize && bounds == other.bounds && insets == other.insets &&
                requested == other.requested && minimum == other.minimum && maximum == other.maximum
        }
    }

    // The live source closure and UIKit conversions may run app/framework code.
    // Return only a fully owned snapshot; callers re-read it before mutation.
    private func snapshot(_ view: ChatReaderCorrectionView) -> Snapshot? {
        guard currentView === view, view.controller === self,
              UIApplication.shared.applicationState == .active,
              let window = view.window, let observation = view.observation,
              let measured = view.currentFrame?() else { return nil }
        let desired = view.desiredY
        var ancestor = view.superview
        var found: UIScrollView?
        while let next = ancestor {
            if let scroll = next as? UIScrollView { found = scroll; break }
            ancestor = next.superview
        }
        guard let scroll = found, scroll.window === window,
              !scroll.isTracking, !scroll.isDragging, !scroll.isDecelerating else { return nil }
        let tolerance = 1 / window.screen.scale
        let bounds = scroll.bounds
        let insets = scroll.adjustedContentInset
        let offset = scroll.contentOffset
        let contentSize = scroll.contentSize
        let viewport = scroll.convert(bounds.inset(by: insets), to: window)
        let nativeRow = view.convert(view.bounds, to: window)
            .offsetBy(dx: -observation.viewportGlobal.minX, dy: -observation.viewportGlobal.minY)
        let minimum = -insets.top
        let maximum = max(minimum, contentSize.height - bounds.height + insets.bottom)
        let requested = offset.y + nativeRow.minY - desired
        let values = [viewport.minX, viewport.minY, viewport.width, viewport.height,
                      nativeRow.minX, nativeRow.minY, nativeRow.width, nativeRow.height,
                      desired, offset.x, offset.y, contentSize.width, contentSize.height,
                      bounds.minX, bounds.minY, bounds.width, bounds.height,
                      insets.top, insets.left, insets.bottom, insets.right,
                      requested, minimum, maximum, tolerance]
        guard values.allSatisfy({ $0.isFinite }), desired <= 0, tolerance > 0,
              abs(viewport.minX - observation.viewportGlobal.minX) <= tolerance,
              abs(viewport.minY - observation.viewportGlobal.minY) <= tolerance,
              abs(viewport.width - observation.viewportSize.width) <= tolerance,
              abs(viewport.height - observation.viewportSize.height) <= tolerance,
              abs(nativeRow.minY - measured.minY) <= tolerance,
              abs(nativeRow.width - measured.width) <= tolerance,
              abs(nativeRow.height - measured.height) <= tolerance,
              abs(measured.minY) <= tolerance,
              requested >= minimum - tolerance, requested <= maximum + tolerance else { return nil }
        let value = Snapshot(window: window, scroll: scroll, context: observation.context,
            observationUptime: observation.observedUptime, ownerRect: observation.viewportGlobal,
            ownerSize: observation.viewportSize, desiredY: desired, sourceRow: measured,
            nativeRow: nativeRow, viewport: viewport, offset: offset, contentSize: contentSize,
            bounds: bounds, insets: insets, requested: requested, minimum: minimum,
            maximum: maximum, tolerance: tolerance)
        guard currentView === view, view.controller === self, value.sameOwnerFields(view),
              view.currentFrame?() == measured else { return nil }
        return value
    }

    func request(_ view: ChatReaderCorrectionView) {
        guard currentView === view, view.controller === self, view.window != nil,
              let token = view.token,
              let ticket = state.enqueue(token: token, registration: view.registration, now: now) else { return }
        let inspection = { [weak self, weak view] in
            guard let self else { return }
            defer {
                // Requeue only because a real newer view/layout event requested it.
                // A rejection alone never schedules a retry or extends the deadline.
                if self.state.finishInspection(ticket), let current = self.currentView { self.request(current) }
            }

            guard let view, self.state.isCurrent(ticket, now: self.now),
                  self.currentView === view, view.controller === self,
                  view.registration == ticket.registration, view.token == ticket.token,
                  let first = self.snapshot(view) else { return }
            // Repeat live source/native reads after callbacks. Nothing captured before
            // those callbacks may authorize a mutation if the owner or geometry changed.
            guard self.state.isCurrent(ticket, now: self.now),
                  view.token == ticket.token, view.registration == ticket.registration,
                  let final = self.snapshot(view), first.sameGeometry(as: final),
                  self.currentView === view, view.controller === self,
                  final.sameOwnerFields(view), view.token == ticket.token,
                  view.registration == ticket.registration else { return }
            guard self.state.consumeMutation(ticket, now: self.now) else { return }
            // Mutation is consumed immediately before the one setter. No ACK here.
            final.scroll.setContentOffset(CGPoint(x: final.offset.x, y: final.requested), animated: false)
        }
        DispatchQueue.main.async(execute: inspection)
    }
    func isLegacyOwner(_ owner: ChatTimelineContainerOwner) -> Bool { legacyOwner == owner }

    func configureLegacyContainer(_ owner: ChatTimelineContainerOwner,
                                  isCurrent: @escaping () -> Bool,
                                  refresh: @escaping () -> Void, userPan: @escaping () -> Void) {
        guard ChatLegacyMetricsAvailability.enabled, owner.isValid else { return }
        let changed = legacyOwner != owner
        if changed {
            removeLegacyPanTarget()
            legacyScroll = nil; legacyWindow = nil; legacyOwner = owner
            legacyContainerEpoch &+= 1
        }
        legacyOwnerIsCurrent = isCurrent; legacyRefresh = refresh; legacyUserPan = userPan
        pruneLegacyProbes()
        // Registry scans happen only at registration/lease events, never on a frame read.
        for probe in legacyProbes.values.compactMap({ $0.view }) where probe.owner == owner {
            if resolveLegacyContainer(from: probe) { break }
        }
        if changed { queueLegacyRefresh() }
    }

    func disconnectLegacyContainer(_ owner: ChatTimelineContainerOwner) {
        guard legacyOwner == owner else { return }
        removeLegacyPanTarget()
        legacyOwner = nil; legacyScroll = nil; legacyWindow = nil
        legacyProbes.removeAll()
        legacyOwnerIsCurrent = nil; legacyRefresh = nil; legacyUserPan = nil
        legacyContainerEpoch &+= 1
        // No fine-state cancellation or mutation here; parent owns that separately.
    }

    private func pruneLegacyProbes() {
        legacyProbes = legacyProbes.filter { _, entry in
            guard let view = entry.view else { return false }
            return view.controller === self && view.window != nil &&
                (legacyOwner == nil || view.owner == legacyOwner)
        }
    }

    func registerLegacyProbe(_ view: ChatTimelineMetricsView) {
        guard ChatLegacyMetricsAvailability.enabled, view.controller === self,
              view.window != nil, view.owner?.isValid == true,
              legacyOwner == nil || view.owner == legacyOwner else { return }
        pruneLegacyProbes()
        legacyProbes[view.registration] = ChatTimelineWeakProbe(view)
        _ = resolveLegacyContainer(from: view)
    }

    func unregisterLegacyProbe(_ view: ChatTimelineMetricsView) {
        if legacyProbes[view.registration]?.view === view { legacyProbes.removeValue(forKey: view.registration) }
        pruneLegacyProbes()
        // A single virtual row disappearing does not revoke an attached container.
        if let scroll = legacyScroll, scroll.window == nil {
            removeLegacyPanTarget(); legacyScroll = nil; legacyWindow = nil
            legacyContainerEpoch &+= 1; queueLegacyRefresh()
        }
    }

    private func resolveLegacyContainer(from view: ChatTimelineMetricsView) -> Bool {
        guard let owner = legacyOwner, owner == view.owner,
              let window = view.window, let observation = view.observation,
              observation.legacyOwner == owner else { return false }
        var ancestor = view.superview
        var candidate: UIScrollView?
        while let next = ancestor {
            if let scroll = next as? UIScrollView { candidate = scroll; break }
            ancestor = next.superview
        }
        guard let scroll = candidate, scroll.window === window else { return false }
        let rect = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window)
        let tolerance = 1 / window.screen.scale
        guard [rect.minX, rect.minY, rect.width, rect.height, tolerance].allSatisfy({ $0.isFinite }),
              tolerance > 0, rect.width > 0, rect.height > 0,
              abs(rect.minX - observation.viewportGlobal.minX) <= tolerance,
              abs(rect.minY - observation.viewportGlobal.minY) <= tolerance,
              abs(rect.width - observation.viewportSize.width) <= tolerance,
              abs(rect.height - observation.viewportSize.height) <= tolerance else { return false }
        if legacyScroll !== scroll || legacyWindow !== window {
            // A stale recycled view cannot re-install an older pointer after a
            // replacement lease has advanced. Initial discovery has no old pointer.
            if legacyScroll != nil {
                guard observation.legacyLeaseEpoch == legacyContainerEpoch else { return false }
            }
            removeLegacyPanTarget()
            legacyScroll = scroll; legacyWindow = window
            legacyContainerEpoch &+= 1
            let pan = scroll.panGestureRecognizer
            pan.addTarget(self, action: #selector(legacyPanChanged(_:)))
            legacyPan = pan
            queueLegacyRefresh()
        }
        return true
    }

    private func removeLegacyPanTarget() {
        legacyPan?.removeTarget(self, action: #selector(legacyPanChanged(_:)))
        legacyPan = nil
    }

    @objc private func legacyPanChanged(_ recognizer: UIPanGestureRecognizer) {
        guard ChatLegacyMetricsAvailability.enabled, recognizer === legacyPan,
              let scroll = legacyScroll, let window = legacyWindow,
              scroll.panGestureRecognizer === recognizer,
              scroll.window === window, legacyOwnerIsCurrent?() == true,
              recognizer.state == .began else { return }
        legacyUserPan?()
    }

    private func queueLegacyRefresh() {
        guard !legacyRefreshQueued else { return }
        legacyRefreshQueued = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.legacyRefreshQueued = false
            guard self.legacyOwner != nil else { return }
            // Callback itself rechecks its captured parent lease. Only pointer/lease
            // changes call this method, never offset changes or geometry samples.
            self.legacyRefresh?()
        }
    }

    func readLegacyMetrics(observation: ChatViewportObservation,
                           currentContext: String, currentViewport: CGSize) -> ChatTimelineOwnedGeometry? {
        guard ChatLegacyMetricsAvailability.enabled,
              let owner = legacyOwner, observation.legacyOwner == owner,
              observation.legacyLeaseEpoch == legacyContainerEpoch,
              observation.context == currentContext, observation.viewportSize == currentViewport,
              observation.viewportGlobal.size == currentViewport,
              legacyOwnerIsCurrent?() == true,
              UIApplication.shared.applicationState == .active,
              let scroll = legacyScroll, let window = legacyWindow, scroll.window === window else { return nil }
        let epoch = legacyContainerEpoch
        let bounds = scroll.bounds
        let insets = scroll.adjustedContentInset
        let offset = scroll.contentOffset
        let size = scroll.contentSize
        let rect = scroll.convert(bounds.inset(by: insets), to: window)
        let tolerance = 1 / window.screen.scale
        let values = [bounds.minX, bounds.minY, bounds.width, bounds.height,
                      insets.top, insets.left, insets.bottom, insets.right,
                      offset.x, offset.y, size.width, size.height,
                      rect.minX, rect.minY, rect.width, rect.height, tolerance]
        guard values.allSatisfy({ $0.isFinite }), tolerance > 0,
              currentViewport.width > 0, currentViewport.height > 0, size.width >= 0, size.height > 0,
              abs(rect.minX - observation.viewportGlobal.minX) <= tolerance,
              abs(rect.minY - observation.viewportGlobal.minY) <= tolerance,
              abs(rect.width - currentViewport.width) <= tolerance,
              abs(rect.height - currentViewport.height) <= tolerance else { return nil }
        let geometry = ChatTimelineGeometry(offset: offset, contentSize: size, container: bounds.size,
            top: insets.top, bottom: insets.bottom, leading: insets.left, trailing: insets.right)
        guard geometry.agrees(with: currentViewport),
              [geometry.contentRect.minX, geometry.contentRect.minY, geometry.contentRect.width,
               geometry.contentRect.height, geometry.contentRect.maxY].allSatisfy({ $0.isFinite }),
              legacyOwner == owner, legacyContainerEpoch == epoch,
              legacyScroll === scroll, legacyWindow === window, scroll.window === window,
              legacyOwnerIsCurrent?() == true else { return nil }
        let sample = ChatTimelineOwnedGeometry(identity: observation.context, viewport: currentViewport, geometry: geometry)
        return sample
    }
}

private final class ChatReaderCorrectionView: UIView {
    let registration = UUID()
    weak var controller: ChatReaderCorrectionController?
    var token: String?
    var desiredY: CGFloat = 0
    var observation: ChatViewportObservation?
    var currentFrame: (() -> CGRect?)?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { controller?.remount(self) }
        else { controller?.detach(self) }
    }
}
private struct ChatReaderCorrectionAnchor: UIViewRepresentable {
    let controller: ChatReaderCorrectionController
    let token: String
    let desiredY: CGFloat
    let observation: ChatViewportObservation
    let currentFrame: () -> CGRect?
    func makeUIView(context: Context) -> ChatReaderCorrectionView {
        let view = ChatReaderCorrectionView(); view.backgroundColor = .clear
        view.isUserInteractionEnabled = false; view.isAccessibilityElement = false
        return view
    }
    func updateUIView(_ view: ChatReaderCorrectionView, context: Context) {
        if let previous = view.controller, previous !== controller { previous.detach(view) }
        view.controller = controller; view.token = token; view.desiredY = desiredY
        view.observation = observation; view.currentFrame = currentFrame
        controller.attach(view)
        controller.request(view)
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ChatReaderCorrectionView, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height,
              width.isFinite, height.isFinite, width >= 0, height >= 0 else { return nil }
        return CGSize(width: width, height: height)
    }
    static func dismantleUIView(_ view: ChatReaderCorrectionView, coordinator: ()) {
        view.controller?.detach(view)
        view.currentFrame = nil; view.controller = nil
    }
}
private struct ChatReaderCorrectionRow: ViewModifier {
    let row: String
    let target: ChatReaderRestorationAnchor?
    let controller: ChatReaderCorrectionController
    let observation: ChatViewportObservation?
    let currentFrame: () -> CGRect?
    func body(content: Content) -> some View {
        content.background {
            if let target, target.row == row, let observation {
                ChatReaderCorrectionAnchor(controller: controller, token: target.id, desiredY: CGFloat(target.originY),
                    observation: observation, currentFrame: currentFrame)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }
        }
    }
}

// Older systems read current UIKit container metrics through passive row probes.
private enum ChatLegacyMetricsAvailability {
    static var enabled: Bool {
        if #available(iOS 18.0, *) { return false }
        return true
    }
}

private struct ChatTimelineContainerOwner: Equatable {
    let generation: String
    let account: String?
    let chat: String
    let root: UUID
    var isValid: Bool { !generation.isEmpty && account?.isEmpty == false && !chat.isEmpty }
}

private final class ChatTimelineWeakProbe {
    weak var view: ChatTimelineMetricsView?
    init(_ view: ChatTimelineMetricsView) { self.view = view }
}

private final class ChatTimelineMetricsView: UIView {
    let registration = UUID()
    weak var controller: ChatReaderCorrectionController?
    var owner: ChatTimelineContainerOwner?
    var observation: ChatViewportObservation?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { controller?.registerLegacyProbe(self) }
        else { controller?.unregisterLegacyProbe(self) }
    }
}

private struct ChatTimelineMetricsAnchor: UIViewRepresentable {
    let controller: ChatReaderCorrectionController
    let owner: ChatTimelineContainerOwner
    let observation: ChatViewportObservation
    func makeUIView(context: Context) -> ChatTimelineMetricsView {
        let view = ChatTimelineMetricsView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false; view.isAccessibilityElement = false
        return view
    }
    func updateUIView(_ view: ChatTimelineMetricsView, context: Context) {
        if let previous = view.controller, previous !== controller { previous.unregisterLegacyProbe(view) }
        view.controller = controller; view.owner = owner; view.observation = observation
        controller.registerLegacyProbe(view)
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ChatTimelineMetricsView, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height,
              width.isFinite, height.isFinite, width >= 0, height >= 0 else { return nil }
        return CGSize(width: width, height: height)
    }
    static func dismantleUIView(_ view: ChatTimelineMetricsView, coordinator: ()) {
        view.controller?.unregisterLegacyProbe(view)
        view.controller = nil; view.owner = nil; view.observation = nil
    }
}

private struct ChatTimelineListInsets: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.listRowSpacing(0).contentMargins(.all, 0, for: .scrollContent)
        } else { content }
    }
}

// Called only from the existing actual row/end GeometryReaders. No stored UIKit
// geometry is ever relabelled as a new revision's measurement.
@MainActor
private func chatLegacyContentFrames(_ geometry: GeometryProxy,
                                     observation: ChatViewportObservation?,
                                     controller: ChatReaderCorrectionController,
                                     currentContext: String, currentViewport: CGSize) -> [String: ChatBottomFrames] {
    guard ChatLegacyMetricsAvailability.enabled, let observation else { return [:] }
    let actualRow = geometry.frame(in: .global)
    guard [actualRow.minX, actualRow.minY, actualRow.width, actualRow.height,
           actualRow.maxX, actualRow.maxY].allSatisfy({ $0.isFinite }),
          actualRow.width >= 0, actualRow.height >= 0,
          let sample = controller.readLegacyMetrics(observation: observation,
              currentContext: currentContext, currentViewport: currentViewport) else { return [:] }
    var result: [String: ChatBottomFrames] = [:]
    _ = sample.merge(into: &result, identity: observation.context, viewport: currentViewport)
    return result
}

private func chatMergeBottomFrames(_ original: [String: ChatBottomFrames],
                                         _ additional: [String: ChatBottomFrames]) -> [String: ChatBottomFrames] {
    var result = original
    // The canonical reducer keeps the actual end sentinel independent of content.
    ChatBottomFramesKey.reduce(value: &result, nextValue: { additional })
    return result
}

private struct ChatTimelineRow: ViewModifier {
    var bottom: CGFloat = 0
    var readerReady = true
    var legacyController: ChatReaderCorrectionController? = nil
    var legacyOwner: ChatTimelineContainerOwner? = nil
    var legacyObservation: ChatViewportObservation? = nil
    @ViewBuilder func body(content: Content) -> some View {
        if ChatLegacyMetricsAvailability.enabled, let legacyController, let legacyOwner, let legacyObservation {
            content.padding(.horizontal, UnyriSavedChatStyle.side).padding(.bottom, bottom)
                .background(ChatTimelineMetricsAnchor(controller: legacyController, owner: legacyOwner, observation: legacyObservation)
                    .allowsHitTesting(false).accessibilityHidden(true))
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
                .opacity(readerReady ? 1 : 0).allowsHitTesting(readerReady)
                .accessibilityHidden(!readerReady)
        } else {
            content.padding(.horizontal, UnyriSavedChatStyle.side).padding(.bottom, bottom)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
                .opacity(readerReady ? 1 : 0).allowsHitTesting(readerReady)
                .accessibilityHidden(!readerReady)
        }
    }
}

private struct ChatTimelineContainer<Rows: View>: View {
    let viewport: CGSize
    let identity: String
    @ViewBuilder let rows: () -> Rows
    var body: some View {
        // Direct rows preserve List virtualization and explicit Reader targets.
        List { rows() }
            .listStyle(.plain)
            .environment(\.defaultMinListRowHeight, 0)
            .scrollContentBackground(.hidden)
            .modifier(ChatTimelineListInsets())
            .modifier(ChatTimelineGeometryAvailability(viewport: viewport, identity: identity))
    }
}
private struct ChatTimelineGeometryAvailability: ViewModifier {
    let viewport: CGSize
    let identity: String
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.modifier(ChatTimelinePublicGeometry(viewport: viewport, identity: identity))
        } else { content }
    }
}

private struct ChatTimelineGeometry: Equatable {
    let offset: CGPoint
    let contentSize: CGSize
    let container: CGSize
    let top: CGFloat
    let bottom: CGFloat
    let leading: CGFloat
    let trailing: CGFloat
    var effectiveViewport: CGSize {
        CGSize(width: container.width - leading - trailing, height: container.height - top - bottom)
    }
    var contentRect: CGRect {
        CGRect(x: -(offset.x + leading), y: -(offset.y + top), width: contentSize.width, height: contentSize.height)
    }
    func agrees(with viewport: CGSize) -> Bool {
        let value = effectiveViewport
        return contentSize.height > 0 && contentSize.height.isFinite &&
            abs(value.width - viewport.width) <= 1 && abs(value.height - viewport.height) <= 1
    }

}

private struct ChatTimelineOwnedGeometry: Equatable {
    let identity: String
    let viewport: CGSize
    let geometry: ChatTimelineGeometry
    func isCurrent(identity: String, viewport: CGSize) -> Bool {
        self.identity == identity && self.viewport == viewport && geometry.agrees(with: viewport)
    }
    @discardableResult
    func merge(into frames: inout [String: ChatBottomFrames], identity: String, viewport: CGSize) -> Bool {
        guard isCurrent(identity: identity, viewport: viewport) else { return false }
        if let existing = frames[identity], existing.viewport != viewport { return false }
        // Reuse the original reducer: nil end preserves the actual child sentinel.
        ChatBottomFramesKey.reduce(value: &frames, nextValue: {
            [identity: ChatBottomFrames(content: geometry.contentRect, end: nil, viewport: viewport)]
        })
        return true
    }
}

@available(iOS 18.0, *)
private struct ChatTimelinePublicGeometry: ViewModifier {
    let viewport: CGSize
    let identity: String
    @State private var sample: ChatTimelineOwnedGeometry?
    func body(content: Content) -> some View {
        content.onScrollGeometryChange(for: ChatTimelineOwnedGeometry.self) { value in
            ChatTimelineOwnedGeometry(identity: identity, viewport: viewport,
                geometry: ChatTimelineGeometry(offset: value.contentOffset, contentSize: value.contentSize,
                    container: value.containerSize, top: value.contentInsets.top, bottom: value.contentInsets.bottom,
                    leading: value.contentInsets.leading, trailing: value.contentInsets.trailing))
        } action: { _, next in
            sample = next

        }
        // Merge into child preferences; never manufacture an end or retag old state.
        .transformPreference(ChatBottomFramesKey.self) { frames in
            _ = sample?.merge(into: &frames, identity: identity, viewport: viewport)
        }
    }
}

private struct ChatViewportObservation {
    let context: String
    let viewportGlobal: CGRect
    let viewportSize: CGSize
    let observedUptime: TimeInterval
    let legacyOwner: ChatTimelineContainerOwner?
    let legacyLeaseEpoch: UInt64?
    init(viewport: GeometryProxy, context: String,
         legacyOwner: ChatTimelineContainerOwner? = nil, legacyLeaseEpoch: UInt64? = nil) {
        self.context = context
        self.legacyOwner = legacyOwner; self.legacyLeaseEpoch = legacyLeaseEpoch
        viewportGlobal = viewport.frame(in: .global)
        viewportSize = viewport.size
        // This is a source-ownership fence used by the repeated live snapshot.
        observedUptime = ProcessInfo.processInfo.systemUptime
    }
}
private func chatViewportFrame(_ geometry: GeometryProxy, observation: ChatViewportObservation?,
                               currentContext: String, currentViewport: CGSize) -> CGRect? {
    guard let observation else { return nil }
    return ChatViewportFrames.frame(global: geometry.frame(in: .global), observation: observation,
        currentContext: currentContext, currentViewport: currentViewport)
}
private enum ChatViewportFrames {
    static func frame(global: CGRect, observation: ChatViewportObservation,
                      currentContext: String, currentViewport: CGSize) -> CGRect? {
        guard !observation.context.isEmpty, currentContext == observation.context,
              currentViewport == observation.viewportSize,
              observation.viewportGlobal.size == observation.viewportSize,
              observation.viewportSize.width > 0, observation.viewportSize.height > 0 else { return nil }
        let values = [global.origin.x, global.origin.y, global.size.width, global.size.height,
                      observation.viewportGlobal.origin.x, observation.viewportGlobal.origin.y,
                      observation.viewportGlobal.size.width, observation.viewportGlobal.size.height]
        guard values.allSatisfy({ $0.isFinite }), global.size.width >= 0, global.size.height >= 0 else { return nil }
        let converted = global.offsetBy(dx: -observation.viewportGlobal.origin.x, dy: -observation.viewportGlobal.origin.y)
        guard [converted.origin.x, converted.origin.y, converted.size.width, converted.size.height].allSatisfy({ $0.isFinite }) else { return nil }
        return converted
    }
}

private struct ChatIntrinsicHeight<Context: Equatable>: Equatable {
    let context: Context
    let row: String
    let height: CGFloat
}
private struct ChatIntrinsicHeights<Context: Equatable>: PreferenceKey {
    static var defaultValue: [ChatIntrinsicHeight<Context>] { [] }
    static func reduce(value: inout [ChatIntrinsicHeight<Context>], nextValue: () -> [ChatIntrinsicHeight<Context>]) {
        value.append(contentsOf: nextValue())
    }
}
private struct ChatIntrinsicSnapshot<Context: Equatable>: Equatable {
    let context: Context
    let extent: CGFloat
}
private enum ChatIntrinsicExtent {
    static func lowerBound<Context: Equatable>(context: Context, itemIDs: Set<String>, specialIDs: Set<String>,
                                              measurements: [ChatIntrinsicHeight<Context>], bottomPadding: CGFloat = 8) -> CGFloat? {
        guard bottomPadding.isFinite, bottomPadding >= 0, itemIDs.isDisjoint(with: specialIDs),
              !itemIDs.isEmpty || !specialIDs.isEmpty else { return nil }
        var seen = Set<String>()
        var total = bottomPadding
        for value in measurements where value.context == context {
            guard itemIDs.contains(value.row) || specialIDs.contains(value.row) else { continue }
            guard value.height.isFinite, value.height >= 0, seen.insert(value.row).inserted else { return nil }
            total += value.height
            guard total.isFinite else { return nil }
        }
        return seen.isEmpty ? nil : total
    }
    static func gap(extent: CGFloat, viewportHeight: CGFloat) -> CGFloat? {
        guard extent.isFinite, extent >= 0, viewportHeight.isFinite, viewportHeight > 0 else { return nil }
        return max(0, viewportHeight - extent)
    }
    static func completed<Context: Equatable>(context: Context, extent: CGFloat?,
                                              previous: ChatIntrinsicSnapshot<Context>?) -> ChatIntrinsicSnapshot<Context>? {
        guard let extent, extent.isFinite, extent >= 0 else { return previous }
        return ChatIntrinsicSnapshot(context: context, extent: extent)
    }
    static func extent<Context: Equatable>(context: Context, itemIDs: Set<String>, specialIDs: Set<String>,
                                          measurements: [ChatIntrinsicHeight<Context>],
                                          bottomPadding: CGFloat = 8) -> CGFloat? {
        guard bottomPadding.isFinite, bottomPadding >= 0,
              itemIDs.isDisjoint(with: specialIDs) else { return nil }
        let expectedCount = itemIDs.count + specialIDs.count
        guard expectedCount > 0, measurements.count >= expectedCount else { return nil }
        var seen = Set<String>()
        var total = bottomPadding
        for value in measurements where value.context == context {
            guard itemIDs.contains(value.row) || specialIDs.contains(value.row) else { continue }
            guard value.height.isFinite, value.height >= 0, seen.insert(value.row).inserted else { return nil }
            total += value.height
            guard total.isFinite else { return nil }
        }
        guard seen.count == expectedCount else { return nil }
        return total
    }
}

private struct ChatPaddingContinuity<Owner: Equatable, Context: Equatable>: Equatable {
    struct Window: Equatable { let token: UUID; let deadline: TimeInterval }
    private(set) var owner: Owner?
    private(set) var complete: ChatIntrinsicSnapshot<Context>?
    private(set) var window: Window?
    private(set) var exhausted = false

    private mutating func reset(owner: Owner) {
        self.owner = owner; complete = nil; window = nil; exhausted = false
    }
    func currentExtent(owner: Owner, context: Context) -> CGFloat? {
        guard self.owner == owner, complete?.context == context else { return nil }
        return complete?.extent
    }
    func gap(owner: Owner, context: Context, viewportHeight: CGFloat, now: TimeInterval) -> CGFloat {
        guard self.owner == owner, let complete,
              now.isFinite, now >= 0 else { return 0 }
        if complete.context == context {
            return ChatIntrinsicExtent.gap(extent: complete.extent, viewportHeight: viewportHeight) ?? 0
        }
        guard !exhausted, window.map({ now < $0.deadline }) ?? true else { return 0 }
        // This first-body fallback precedes onChange; it is never a current-model extent.
        return ChatIntrinsicExtent.gap(extent: complete.extent, viewportHeight: viewportHeight) ?? 0
    }
    mutating func synchronize(owner: Owner, context: Context, now: TimeInterval, token: UUID) {
        if self.owner != owner { reset(owner: owner) }
        guard now.isFinite, now >= 0 else {
            complete = nil; window = nil; exhausted = true; return
        }
        if complete?.context == context { window = nil; exhausted = false; return }
        if let window {
            _ = expire(owner: owner, token: window.token, now: now)
            return // Model bursts cannot renew the first pending deadline.
        }
        guard complete != nil, !exhausted else { return }
        let deadline = now + 0.75
        guard deadline.isFinite, deadline > now else {
            complete = nil; exhausted = true; return
        }
        window = Window(token: token, deadline: deadline)
    }
    mutating func accept(owner: Owner, context: Context, completeExtent: CGFloat?,
                         lowerBound: CGFloat?, viewportHeight: CGFloat) {
        if self.owner != owner { reset(owner: owner) }
        if let extent = completeExtent, extent.isFinite, extent >= 0 {
            complete = ChatIntrinsicSnapshot(context: context, extent: extent)
            window = nil; exhausted = false
        } else if complete?.context != context,
                  let lowerBound, lowerBound.isFinite, lowerBound >= 0,
                  viewportHeight.isFinite, viewportHeight > 0, lowerBound >= viewportHeight {
            // A single CURRENT pass proves no positive gap. Retire old short padding.
            complete = nil; window = nil; exhausted = true
        }
    }
    @discardableResult
    mutating func expire(owner: Owner, token: UUID, now: TimeInterval) -> Bool {
        guard self.owner == owner, let window, window.token == token,
              now.isFinite, now >= window.deadline else { return false }
        complete = nil; self.window = nil; exhausted = true
        return true
    }
}
