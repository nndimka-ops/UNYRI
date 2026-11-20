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

@MainActor private enum UnyriMediaCopyRequest {
    static var latest = UUID()
}

private extension UnyriMessageMediaCopyPolicy {
    static func kind(message: DecryptedChatMessage, localPath: String?) -> Kind? {
        kind(messageKind: message.kind, status: message.status.rawValue, serverID: message.serverId,
             hidden: message.hiddenLocally, deleted: message.isDeletedForEveryone,
             directFile: message.content.directFile != nil,
             mediaType: message.content.attachment?.mediaType, mime: message.content.attachment?.mimeType,
             localAvailable: localPath.map { FileManager.default.isReadableFile(atPath: $0) } ?? false)
    }

    static func pasteboardType(kind: Kind, mime: String, filename: String?) -> UTType? {
        if kind == .gif { return .gif }
        let family: UTType = kind == .movie ? .movie : .image
        if let type = UTType(mimeType: mime.lowercased()), type.conforms(to: family) { return type }
        if let filename, let type = UTType(filenameExtension: (filename as NSString).pathExtension),
           type.conforms(to: family) { return type }
        return kind == .movie ? .movie : nil
    }
}


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
    @StateObject private var imageSource = UnyriAttachmentImageSource()
    private var imageRequest: AttachmentImageRequest { .file(preview.url, pixelEdge: previewPixelEdge) }
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
                QueuedVideoPreview(url: preview.url, visible: (mediaVisible || isMenuPreview) && phase == .active,
                    meter: attachments.sendMeter(messageID))
                    .overlay(alignment: .topLeading) {
                        Text(VoiceFormat.durationText(preview.duration)).font(.caption.monospacedDigit())
                            .padding(4).background(.black.opacity(0.6)).foregroundColor(.white)
                    }
            } else if let image = imageSource.best(for: imageRequest) {
                Image(uiImage: image).resizable().aspectRatio(contentMode: cellWidth == nil ? .fit : .fill)
            }
        }
        .frame(width: size.width, height: size.height).clipped()
        .modifier(UnyriAttachmentImageBinding(source: imageSource, request: imageRequest,
            workAllowed: !preview.kind.hasPrefix("video"), onVisibility: { mediaVisible = $0 }))
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
    /// Envelope from the message itself (sender-computed); drawn at once and never decoded.
    var embedded: [Double]? = nil
    var decodeOnPlay = false
    @State private var samples: [Double]
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    init(path: String?, fraction: Double, played: Color, unplayed: Color, embedded: [Double]? = nil, decodeOnPlay: Bool = false) {
        self.path = path; self.fraction = fraction; self.played = played; self.unplayed = unplayed; self.embedded = embedded; self.decodeOnPlay = decodeOnPlay
        _samples = State(initialValue: embedded ?? path.flatMap { VoiceWaveformMemory.shared.get($0) } ?? [])
    }
    var body: some View {
        Canvas { context, size in
            let count = max(1, min(64, Int(size.width / 4)))
            let step = size.width / CGFloat(count)
            for i in 0..<count {
                let sample = samples.isEmpty ? 0 : samples[min(samples.count - 1, i * samples.count / count)]
                let height = 3 + sample * max(0, size.height - 3)
                let x = step * (CGFloat(i) + 0.5)
                var line = Path(); line.move(to: CGPoint(x: x, y: (size.height - height) / 2)); line.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(line, with: .color(Double(i) < fraction * Double(count) ? played : unplayed), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
            }
        }.task(id: "\(path ?? "")|\(decodeOnPlay)") {
            if let embedded, !embedded.isEmpty { if samples != embedded { samples = embedded }; return }
            guard let path else { samples = []; return }
            guard decodeOnPlay else { samples = []; return }
            if let ready = VoiceWaveformMemory.shared.get(path) { if samples != ready { samples = ready }; return }
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
    var metadata: AnyView? = nil
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
            cycleSpeed: { UnyriHaptics.selection(); player.cycleSpeed() }, transferControl: AnyView(UnyriVoiceTransferControl(id: id, cancel: cancel)),
            metadata: metadata)
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
    @Environment(\.unyriAppColorStyle) private var appColorStyle
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
                                .stroke(appColorStyle.decorative(ChatControlPalette.blue), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
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

private struct VoiceRecordingMeter<Recorder: ChatRecordingSource>: View {
    let recorder: Recorder
    var count = 12
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @State private var levels: [Double] = []
    var body: some View {
        Canvas { context, size in
            for (index, level) in levels.enumerated() {
                let height = max(2, size.height * sqrt(max(0, min(1, level))))
                let x = (Double(index) + 0.5) * size.width / Double(levels.count)
                var line = Path(); line.move(to: CGPoint(x: x, y: (size.height - height) / 2)); line.addLine(to: CGPoint(x: x, y: (size.height + height) / 2))
                context.stroke(line, with: .color(appColorStyle.decorative(ChatControlPalette.blue)), lineWidth: 2)
            }
        }.task {
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 100_000_000) } catch { break }
                if levels.count != count { levels = Array(repeating: 0, count: count) }
                levels = Array(levels.dropFirst()) + [recorder.level()]
            }
        }
    }
}

final class ChatVoiceHoldDragBox: ObservableObject {
    @Published var offset: CGSize = .zero {
        didSet { if offset != .zero { trail = offset } }
    }
    /// The last non-zero offset. Not published: the hold code resets `offset` in the same
    /// update that locks, and the bar still needs to know where the mic was, to merge it
    /// into the lock from there (and whether the lock was reached by sliding at all).
    var trail: CGSize = .zero
}

/// The composer while a voice message records. It draws no background of its own: the
/// composer glass stays where it is and only its content changes, so nothing flashes
/// when recording starts (`standalone` adds a plain capsule where no composer is mounted).
/// Holding: red dot + timer, a live wave of the voice, "< Slide to cancel", a large mic
/// that follows the finger and breathes with the voice (halo + a ring the voice pushes
/// out), and a glass lock that comes down to meet the finger while its shackle closes.
/// Reaching the lock: the mic shrinks into it, the lock clicks (pulse + a crisp haptic),
/// then the lock travels into the stop control while send grows where the mic was.
/// Locked: trash, timer, live meter, pause, stop-to-review and send.
/// Every effect is a transform or an opacity change; levels refresh at 10 Hz, the lock
/// sequence is one-shot transitions (none of it runs with Reduce Motion).
/// A video note uses the same bar (`video`): the mic becomes a camera, and the locked
/// controls are trash, flip camera and send (no pause, no review); the lock turns into flip.
private struct ChatRecordingBar<Recorder: ChatRecordingSource>: View {
    @ObservedObject var recorder: Recorder
    @ObservedObject var drag: ChatVoiceHoldDragBox
    let locked: Bool
    let cancelDistance: CGFloat
    let lockDistance: CGFloat
    let reduceMotion: Bool
    var standalone = false
    var video = false
    var flipCamera: () -> Void = {}
    let cancel: () -> Void
    let togglePause: () -> Void
    let review: () -> Void
    let send: () -> Void
    @State private var nudge = false
    @State private var entered = false
    private var height: CGFloat { max(44, UnyriSavedChatStyle.entryHeight) }
    private var cancelProgress: CGFloat { min(1, max(0, -drag.offset.width / cancelDistance)) }
    private var lockProgress: CGFloat { min(1, max(0, -drag.offset.height / lockDistance)) }
    private var spring: Animation? { reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.82) }

    var body: some View {
        HStack(spacing: 8) {
            if locked {
                Button(action: cancel) { glyph("trash") }.accessibilityLabel("Cancel recording")
                    .accessibilityIdentifier(video ? "video-note-cancel" : "voice-recording-cancel")
                    .transition(lockedControl(delay: 0.1))
            }
            ChatRecordingClock(recorder: recorder)
                .offset(x: locked ? 0 : (entered ? drag.offset.width * 0.25 : -18))
                .opacity(locked ? 1 : 1 - cancelProgress * 0.7)
            if locked {
                VoiceRecordingMeter(recorder: recorder, count: 26).frame(maxWidth: .infinity).frame(height: 22)
                    .transition(lockedFade)
                if video {
                    Button(action: flipCamera) { glyph("arrow.triangle.2.circlepath.camera") }
                        .accessibilityLabel("Switch camera").accessibilityIdentifier("video-note-flip")
                        .transition(fromLock)
                } else {
                Button(action: togglePause) { glyph(recorder.isRecording ? "pause.fill" : "mic.fill") }
                    .accessibilityLabel("Pause or resume recording")
                    .transition(lockedControl(delay: 0.28))
                Button(action: review) { glyph("stop.fill") }.accessibilityLabel("Review before sending")
                    .transition(fromLock)
                }
                Button(action: send) {
                    Image(systemName: "arrow.up").font(.system(size: UnyriSavedChatStyle.iconSize, weight: .semibold))
                        .foregroundColor(UnyriSavedChatStyle.micInk)
                        .frame(width: height - 6, height: height - 6).background(UnyriSavedChatStyle.micColor, in: Circle())
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.accessibilityLabel(Text(UnyriL10n.string(video ? "Send video message" : "Send voice message")))
                    .accessibilityIdentifier(video ? "video-note-send" : "voice-recording-send")
                    .transition(sendAppears)
            } else {
                // The voice is visible while the finger is still down, not only a blinking dot.
                VoiceRecordingMeter(recorder: recorder, count: 14).frame(width: 58, height: 20)
                    .opacity(entered ? 1 - cancelProgress : 0)
                    .offset(x: drag.offset.width * 0.25)
                    .transition(.opacity)
                Spacer(minLength: 0)
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left").font(.system(size: 13, weight: .semibold))
                        .offset(x: nudge ? -4 : 0)
                    Text("Slide to cancel").font(.subheadline).lineLimit(1)
                }
                .foregroundStyle(UnyriColors.secondaryText)
                .opacity(entered ? 1 - cancelProgress * 0.9 : 0)
                .offset(x: entered ? drag.offset.width * 0.6 : 18)
                .accessibilityHidden(true)
                .transition(.opacity)
                Spacer(minLength: 0)
                Color.clear.frame(width: 56, height: 1)   // room for the floating mic
            }
        }
        .padding(.leading, locked ? 0 : 14).padding(.trailing, locked ? 0 : 4)
        .frame(height: height)
        .background { if standalone { Capsule().fill(Color(white: 0.13)).overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5)) } }
        // The mic and the lock live in containers that stay where the lock engaged, so
        // when they leave, their exits (merge, click, travel) play from exactly there.
        .overlay(alignment: .trailing) {
            ZStack { if !locked { holdingMic.transition(micExit) } }
                .offset(x: 12 + micPlace.width, y: micPlace.height)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .overlay(alignment: .topTrailing) {
            ZStack { if !locked { lockPill.transition(lockExit) } }
                .offset(x: -Self.pillInset, y: pillTop(lockTravel))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .animation(spring, value: locked)
        .onAppear {
            guard !reduceMotion else { entered = true; return }
            withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) { entered = true }
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { nudge = true }
        }
        .onChange(of: locked) { isLocked in
            // The click: a crisp tick as the shackle snaps shut, just after the mic went in
            // (reaching the lock already gave the light tap of the hold gesture).
            guard isLocked, reachedBySlide else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.8)
            }
        }
        .onDisappear { drag.trail = .zero }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(video ? (locked ? "video-note-recording-locked" : "video-note-recording-holding")
                                 : (locked ? "voice-recording-locked" : "voice-recording-holding"))
    }

    private func glyph(_ name: String) -> some View {
        Image(systemName: name).font(.system(size: 19, weight: .regular)).foregroundStyle(UnyriColors.primaryText)
            .frame(width: 44, height: 44).contentShape(Rectangle())
    }

    // MARK: Lock geometry, in the bar's space: y from its top edge, x from its trailing edge.

    private static var pillInset: CGFloat { 2 }
    private static var pillWidth: CGFloat { 44 }
    private static var pillCenterX: CGFloat { -(pillInset + pillWidth / 2) }
    /// The mic's 76 pt frame sits 12 pt past the trailing edge.
    private static var micCenterX: CGFloat { -(76 / 2 - 12) }
    /// The stop control (voice) or flip camera (video): the slot left of the send button.
    private static var controlCenterX: CGFloat { -(44 + 8 + 22) }
    private func pillHeight(_ p: CGFloat) -> CGFloat { 46 + 16 * (1 - p) }
    /// The lock comes down 30 pt as the finger rises; at the top it sits right on the mic.
    private func pillTop(_ p: CGFloat) -> CGFloat { -122 + 30 * p }
    private func pillCenterY(_ p: CGFloat) -> CGFloat { pillTop(p) + pillHeight(p) / 2 }
    /// Where the mic is while holding; once locked, where it was when the lock engaged.
    private var micPlace: CGSize { locked ? drag.trail : drag.offset }
    /// How far the lock has come down; frozen at its last place once locked.
    private var lockTravel: CGFloat { locked ? min(1, max(0, -drag.trail.height / lockDistance)) : lockProgress }
    /// The lock was reached by sliding up; a hold the system cancelled locks without it.
    private var reachedBySlide: Bool { -drag.trail.height >= lockDistance * 0.6 }
    private var lockedBySlide: Bool { locked && reachedBySlide && !reduceMotion }

    // MARK: Lock transitions. Removals are read from the last holding frame, insertions
    // from the first locked one; each carries its own one-shot animation.

    /// The mic shrinks into the lock (it is drawn under the glass, so it goes "in").
    private var micExit: AnyTransition {
        if reduceMotion { return .opacity }
        guard lockProgress > 0.6 else { return .scale(scale: 0.5).combined(with: .opacity) }
        let into = CGSize(width: Self.pillCenterX - (Self.micCenterX + drag.offset.width),
                          height: pillCenterY(lockProgress) - (height / 2 + drag.offset.height))
        return .asymmetric(insertion: .opacity, removal: AnyTransition.modifier(
            active: ChatRecordingMorph(offset: into, scale: 0.2, opacity: 0),
            identity: ChatRecordingMorph(offset: .zero, scale: 1, opacity: 1))
            .animation(.easeIn(duration: 0.17)))
    }

    /// The lock clicks, then travels into the control it becomes, shrinking as it fades.
    private var lockExit: AnyTransition {
        if reduceMotion { return .opacity }
        guard lockProgress > 0.6 else { return .scale(scale: 0.6, anchor: .bottom).combined(with: .opacity) }
        let travel = CGSize(width: Self.controlCenterX - Self.pillCenterX, height: height / 2 - pillCenterY(lockProgress))
        return .asymmetric(insertion: .opacity, removal: AnyTransition.modifier(
            active: ChatLockClick(progress: 1, travel: travel),
            identity: ChatLockClick(progress: 0, travel: travel))
            .animation(.linear(duration: 0.52)))
    }

    /// The control the lock turns into comes out of the lock's place, along its path.
    private var fromLock: AnyTransition {
        guard lockedBySlide else { return lockedControl(delay: 0) }
        let back = CGSize(width: Self.pillCenterX - Self.controlCenterX, height: pillCenterY(lockTravel) - height / 2)
        return .asymmetric(insertion: AnyTransition.modifier(
            active: ChatRecordingMorph(offset: back, scale: 0.7, opacity: 0),
            identity: ChatRecordingMorph(offset: .zero, scale: 1, opacity: 1))
            .animation(.spring(response: 0.32, dampingFraction: 0.8).delay(0.26)),
            removal: .opacity)
    }

    private func lockedControl(delay: Double) -> AnyTransition {
        if reduceMotion { return .opacity }
        let pop = AnyTransition.scale(scale: 0.6).combined(with: .opacity)
        return lockedBySlide ? pop.animation(.spring(response: 0.3, dampingFraction: 0.8).delay(delay)) : pop
    }

    private var lockedFade: AnyTransition {
        lockedBySlide ? AnyTransition.opacity.animation(.easeOut(duration: 0.2).delay(0.16)) : .opacity
    }

    /// Send grows where the mic was, with a little overshoot.
    private var sendAppears: AnyTransition {
        if reduceMotion { return .opacity }
        guard lockedBySlide else { return .scale(scale: 0.4).combined(with: .opacity) }
        return AnyTransition.scale(scale: 0.3).combined(with: .opacity)
            .animation(.spring(response: 0.36, dampingFraction: 0.62).delay(0.12))
    }

    // MARK: Holding views

    /// The mic follows the finger. Sliding up, it stretches toward the lock half-way and
    /// settles a little smaller as it docks under it; the halo and the ring move with the
    /// voice level, sampled by the same 10 Hz timeline.
    private var holdingMic: some View {
        let p = lockProgress
        let stretch: CGFloat = reduceMotion ? 0 : 0.56 * p * (1 - p)
        return TimelineView(.periodic(from: .now, by: 0.1)) { _ in
            let level = CGFloat(recorder.level())
            // Typed locals: Xcode 26.5 (Swift 6.2) cannot type-check the inline
            // mixed CGFloat/Double ternaries in reasonable time. Same values.
            let ringScale: CGFloat = reduceMotion ? 1 : 1.16 + level * 0.62
            let ringOpacity: Double = reduceMotion ? 0 : Double(min(1, 0.1 + level * 0.9) * (1 - p))
            let haloScale: CGFloat = reduceMotion ? 1 : 1.12 + level * 0.5
            let innerHaloScale: CGFloat = reduceMotion ? 1 : 1 + level * 0.28
            ZStack {
                Circle().stroke(Color.white.opacity(0.35), lineWidth: 1.2).frame(width: 76, height: 76)
                    .scaleEffect(ringScale)
                    .opacity(ringOpacity)
                Circle().fill(Color.white.opacity(0.10)).frame(width: 76, height: 76)
                    .scaleEffect(haloScale)
                Circle().fill(Color.white.opacity(0.16)).frame(width: 76, height: 76)
                    .scaleEffect(innerHaloScale)
                Circle().fill(Color.white).frame(width: 68, height: 68)
                Image(systemName: cancelProgress > 0.98 ? "trash" : video ? "video.fill" : "mic.fill")
                    .font(.system(size: 26, weight: .medium)).foregroundStyle(Color.black)
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: level)
        }
        .scaleEffect(x: 1 - stretch * 0.5, y: 1 + stretch, anchor: .bottom)
        .scaleEffect((entered ? 1 : 0.5) - cancelProgress * 0.2 - p * 0.16)
    }

    /// The glass lock comes down to meet the finger; its shackle closes on the way and is
    /// shut just before the lock engages.
    private var lockPill: some View {
        let p = lockProgress
        return VStack(spacing: 0) {
            ChatRecordingLockGlyph(closed: p / 0.92)
                .scaleEffect(1 + p * 0.12)
            Image(systemName: "chevron.up").font(.system(size: 12, weight: .bold))
                .offset(y: nudge ? -3 : 1)
                .frame(height: 16 * (1 - p))
                .opacity(Double(1 - p))
        }
        .padding(.top, 12)
        .frame(width: Self.pillWidth, height: pillHeight(p), alignment: .top)
        .foregroundStyle(UnyriColors.primaryText.opacity(0.9))
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(Color.white.opacity(0.14 + 0.12 * p), lineWidth: 0.5))
        .scaleEffect(entered ? 1 : 0.6, anchor: .bottom)
        .opacity(entered ? 1 - cancelProgress : 0)
    }
}

/// Offset, scale and opacity in one, for the recording bar's morph transitions.
private struct ChatRecordingMorph: ViewModifier {
    let offset: CGSize
    let scale: CGFloat
    let opacity: Double
    func body(content: Content) -> some View {
        content.scaleEffect(scale).offset(offset).opacity(opacity)
    }
}

/// The lock once the mic is inside it: a short click (a pulse), then it travels to the
/// control it becomes, shrinking and fading. One linear progress (0...1), shaped here.
private struct ChatLockClick: ViewModifier, Animatable {
    var progress: CGFloat
    let travel: CGSize
    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }
    func body(content: Content) -> some View {
        let click = min(1, max(0, (progress - 0.19) / 0.31))
        let move = min(1, max(0, (progress - 0.5) / 0.5))
        let eased = move * move * (3 - 2 * move)
        return content
            .scaleEffect(1 + 0.16 * sin(click * .pi) - 0.3 * eased)
            .offset(x: travel.width * eased, y: travel.height * eased)
            .opacity(Double(1 - max(0, (move - 0.4) / 0.6)))
    }
}

/// A padlock drawn from two shapes, so the shackle closes progressively with transforms
/// only (0 open, 1 shut): open, it is lifted and swung out on its left leg.
private struct ChatRecordingLockGlyph: View {
    var closed: CGFloat
    var body: some View {
        let open = 1 - min(1, max(0, closed))
        ZStack(alignment: .bottom) {
            ChatLockShackle()
                .stroke(style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                .frame(width: 9, height: 11)
                .rotationEffect(.degrees(Double(-24 * open)), anchor: .bottomLeading)
                .offset(y: -8 - 3.5 * open)
            RoundedRectangle(cornerRadius: 3.2, style: .continuous)
                .frame(width: 15, height: 11.5)
        }
        .frame(width: 22, height: 22, alignment: .bottom)
    }
}

private struct ChatLockShackle: Shape {
    func path(in rect: CGRect) -> Path {
        let radius = rect.width / 2
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addArc(tangent1End: CGPoint(x: rect.minX, y: rect.minY), tangent2End: CGPoint(x: rect.midX, y: rect.minY), radius: radius)
        path.addArc(tangent1End: CGPoint(x: rect.maxX, y: rect.minY), tangent2End: CGPoint(x: rect.maxX, y: rect.minY + radius), radius: radius)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        return path
    }
}

/// m:ss,d at 10 Hz in its own view, so the tick only redraws the digits.
private struct ChatRecordingClock<Recorder: ChatRecordingSource>: View {
    @ObservedObject var recorder: Recorder
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.1)) { context in
            let phase = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 7) {
                Circle().fill(Color(red: 1, green: 0.27, blue: 0.23)).frame(width: 9, height: 9)
                    .opacity(recorder.isRecording ? 0.6 + 0.4 * cos(phase * 4.5) : 0.35)
                Text(Self.format(recorder.recordedSeconds)).font(.system(size: 15, weight: .regular)).monospacedDigit()
                    .foregroundStyle(UnyriColors.primaryText)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(VoiceFormat.durationText(Int64(recorder.recordedSeconds * 1000))))
    }
    static func format(_ seconds: TimeInterval) -> String {
        let tenths = Int(seconds * 10)
        let separator = Locale.current.decimalSeparator ?? "."
        return String(format: "%d:%02d%@%d", tenths / 600, (tenths / 10) % 60, separator, tenths % 10)
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
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    let draft: FileAttachmentDraft
    let enqueue: () async -> Bool
    let onClose: () -> Void
    @State private var sending = false
    @State private var error: String?
    @State private var previewURL: URL?
    var body: some View {
        NavigationStack {
            ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "doc.text.fill").font(.system(size: 36)).foregroundStyle(appColorStyle.decorative(ChatControlPalette.amber)).frame(width: 76, height: 76).background(appColorStyle.surface(ChatControlPalette.amberBack), in: RoundedRectangle(cornerRadius: 22))
                Text(draft.url.lastPathComponent).font(.headline).multilineTextAlignment(.center).lineLimit(3)
                Text(FileSizeFormatter.format(draft.size)).font(.subheadline).foregroundColor(.secondary)
                Button { UnyriHaptics.press(); previewURL = draft.url } label: { Label("Preview", systemImage: "eye").font(.subheadline.weight(.semibold)).foregroundStyle(appColorStyle.decorative(ChatControlPalette.blue)).frame(maxWidth: .infinity, minHeight: 44).background(appColorStyle.surface(ChatControlPalette.blueBack), in: Capsule()) }.buttonStyle(.plain).disabled(sending).opacity(sending ? 0.45 : 1)
                    .quickLookPreview($previewURL)
        .background(UnyriMediaRotation(active: previewURL != nil))
                if let error { Text(verbatim: error).font(.footnote).foregroundColor(UnyriColors.danger).multilineTextAlignment(.center) }
                if sending { ProgressView("Sending…") }
            }.padding(24)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    UnyriHaptics.press()
                    sending = true; error = nil
                    Task {
                        defer { sending = false }
                        guard await enqueue() else { error = UnyriL10n.string("Could not save the file message. Try again."); return }
                        onClose()
                    }
                } label: { Label(LocalizedStringKey(error == nil ? "Send file" : "Retry"), systemImage: error == nil ? "paperplane.fill" : "arrow.clockwise").font(.subheadline.weight(.semibold)).foregroundStyle(appColorStyle.isMonochrome ? appColorStyle.onColorfulButton : .white).frame(maxWidth: .infinity, minHeight: 48).background(appColorStyle.decorative(ChatControlPalette.violet), in: Capsule()) }
                .buttonStyle(.plain).disabled(sending).opacity(sending ? 0.45 : 1)
                .padding(.horizontal, 24).padding(.vertical, 10)
                .background(UnyriColors.deepPanel)
            }
            .unyriScreenBackground().navigationTitle("File").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onClose).tint(appColorStyle.controlTint(ChatControlPalette.coral)).disabled(sending).opacity(sending ? 0.45 : 1) } }
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
        // The default .typeMedium recorded ~480p video that looked blurry on any screen.
        picker.videoQuality = .typeHigh
        picker.videoMaximumDuration = 180          // stays under the source-size limit
        picker.cameraFlashMode = .auto
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
    enum Source { case gallery(UnyriGallerySource), photo(PhotosPickerItem), file(StoredMediaDraft), document(AttachmentFileCopy.DocumentSource, Int64?), unavailable(MediaComposerBatch.Member), importing(String, Bool) }
    let id: String
    let source: Source
    let kind: String
    /// How it enters the tray: picked items fly in (ChatComposerTrayEntrance).
    var entrance = ChatComposerEntrance.settled
    /// Keep the provider identity after its bytes move to an app-owned draft URL.
    var documentURL: URL? = nil
    var isImporting = false
    var ownedURL: URL? { if case .file(let file) = source { return file.url }; return nil }
    var isDocument: Bool {
        switch source {
        case .file: return kind == "file"
        case .importing: return kind == "file"
        case .document, .unavailable: return true
        default: return false
        }
    }
    var documentName: String {
        switch source {
        case .file(let file): return file.url.lastPathComponent
        case .document(let value, _): return value.url.lastPathComponent
        case .unavailable(let value): return value.source.name
        case .importing(let name, _): return name
        default: return ""
        }
    }
    var documentBytes: Int64? {
        switch source {
        case .file(let file): return Int64(file.bytes)
        case .document(_, let bytes): return bytes
        case .unavailable(let value): return value.source.bytes
        default: return nil
        }
    }
    var originalSource: DirectComposerOriginal.Source? {
        switch source {
        case .gallery(.asset(let asset)): return .asset(asset)
        case .gallery(.capture(let url)): return .file(url)
        case .photo(let item): return .picker(item)
        case .file(let item): return .file(item.url)
        case .document(let item, _): return .file(item.url)
        case .unavailable, .importing: return nil
        }
    }
    var directOriginal: DirectComposerOriginal.Source? { kind == "file" ? originalSource : nil }
    var isVideo: Bool {
        switch source {
        case .gallery(.asset(let asset)): return asset.mediaType == .video
        case .gallery(.capture): return false
        case .photo(let item): return item.supportedContentTypes.contains { $0.conforms(to: .movie) }
        case .file(let file): return file.isVideo
        case .document: return false
        case .unavailable(let value): return value.source.isVideo
        case .importing(_, let video): return video
        }
    }
}

/// How an item arrives in the composer tray. Since the owner's decision of 12 Nov 2026
/// the attach sheet fills the tray instead of sending (116ca60a had made it send because
/// a silent fill looked like nothing happened), so picked photos, videos and files fly
/// in, staggered in pick order. Items restored on chat entry, camera captures and Send
/// now picks appear as before.
private struct ChatComposerEntrance {
    var flies = false
    var order = 0
    /// One arrival: a photo removed and picked again is a new arrival and flies again.
    let arrival = UUID()
    static let settled = ChatComposerEntrance(flies: false)
}

/// What one file import left in the tray.
private enum ChatComposerImport {
    /// Nothing new: duplicates only, over the limit, or the import was retired.
    case nothing
    /// Some picks landed; a copy failed or the user removed one meanwhile.
    case partial
    /// Every pick landed.
    case complete
}

/// Files picks and paste providers enter one importer. Provider loading is only a
/// bridge to a document URL; capacity, placeholders, staging and errors are shared.
private struct ChatComposerFileSelection {
    enum Source {
        case document(AttachmentFileCopy.DocumentSource)
        case provider(NSItemProvider, ChatComposerPastePolicy.Media)
    }
    let source: Source
    let identityURL: URL
    var kind = "file"
    var isVideo = false
    func filePlan(retainExternalDocument: Bool) -> ChatComposerPastePolicy.FilePlan {
        let origin: ChatComposerPastePolicy.FileOrigin
        switch source {
        case .document: origin = .document
        case .provider: origin = .provider
        }
        return ChatComposerPastePolicy.filePlan(origin: origin, original: kind == "file", video: isVideo,
            retainExternalDocument: retainExternalDocument, ordinaryLimit: AttachmentFormat.maxFileBytes,
            videoLimit: AttachmentFileCopy.maxVideoSourceBytes, originalLimit: DirectFileOffer.maximumBytes)
    }
    var placeholder: ChatComposerAttachment.Source {
        switch source {
        case .document(let document): return .document(document, nil)
        case .provider(let provider, _):
            return .importing(provider.suggestedName ?? UnyriL10n.string(isVideo ? "Video" : "Photo"), isVideo)
        }
    }
    func document() async throws -> AttachmentFileCopy.DocumentSource {
        switch source {
        case .document(let value): return value
        case .provider(let provider, let media):
            let limit = filePlan(retainExternalDocument: false).maximumBytes
            return try await ChatComposerProviderCopy.load(provider, typeIdentifier: media.typeIdentifier, maxBytes: limit)
        }
    }
}

/// loadFileRepresentation's URL expires when its callback returns. Copy its bytes
/// there, using the same bounded file reader as gallery/files, never UIImage (GIFs).
private final class ChatComposerProviderCopy: @unchecked Sendable {
    private let lock = NSLock()
    private let reader = AttachmentFileCopy.DocumentRead()
    private var progress: Progress?
    private var cancelled = false
    private func track(_ progress: Progress) {
        lock.lock(); self.progress = progress; let stopped = cancelled; lock.unlock()
        if stopped { progress.cancel() }
    }
    private func cancel() {
        lock.lock(); cancelled = true; let progress = progress; lock.unlock()
        reader.cancel(); progress?.cancel()
    }
    private func checkCancellation() throws {
        lock.lock(); let stopped = cancelled; lock.unlock()
        if stopped { throw CancellationError() }
    }
    static func load(_ provider: NSItemProvider, typeIdentifier: String, maxBytes: Int64) async throws -> AttachmentFileCopy.DocumentSource {
        let operation = ChatComposerProviderCopy()
        return try await withTaskCancellationHandler {
            let document: AttachmentFileCopy.DocumentSource = try await withCheckedThrowingContinuation { continuation in
                let progress = provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("unyri-paste-" + UUID().uuidString, isDirectory: true)
                    do {
                        if let error { throw error }
                        guard let url else { throw CocoaError(.fileReadUnknown) }
                        var name = DirectFileOffer.safeFilename(provider.suggestedName ?? url.lastPathComponent)
                        if let type = UTType(typeIdentifier), let ext = type.preferredFilenameExtension,
                           UTType(filenameExtension: (name as NSString).pathExtension)?.conforms(to: type) != true {
                            name = (name as NSString).deletingPathExtension + "." + ext
                        }
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        let destination = directory.appendingPathComponent(name)
                        try operation.reader.copy(from: url, to: destination, maxBytes: maxBytes, allocation: { _, _ in
                            try operation.checkCancellation()
                        })
                        continuation.resume(returning: AttachmentFileCopy.DocumentSource(destination, cleanup: {
                            try? FileManager.default.removeItem(at: directory)
                        }))
                    } catch {
                        try? FileManager.default.removeItem(at: directory)
                        if maxBytes == DirectFileOffer.maximumBytes, (error as? AttachmentFileCopy.CopyError) == .videoSourceTooLarge {
                            continuation.resume(throwing: DirectComposerOriginal.PreparationFailure.tooLarge)
                        } else { continuation.resume(throwing: error) }
                    }
                }
                operation.track(progress)
            }
            try Task.checkCancellation()
            return document
        } onCancel: { operation.cancel() }
    }
}

/// A picked item's one-shot flight into the tray: it rises from below, where the sheet
/// was, with one spring; Reduce Motion gets a short fade. It runs once per arrival, also
/// across a re-mounted composer, and nothing animates after it has landed.
private struct ChatComposerTrayEntrance: ViewModifier {
    let reduceMotion: Bool
    let order: Int
    let landed: () -> Void
    @State private var away: Bool

    init(flies: Bool, reduceMotion: Bool, order: Int, landed: @escaping () -> Void) {
        self.reduceMotion = reduceMotion
        self.order = order
        self.landed = landed
        _away = State(initialValue: flies)
    }

    func body(content: Content) -> some View {
        content
            .scaleEffect(away && !reduceMotion ? 0.6 : 1)
            .offset(y: away && !reduceMotion ? 44 : 0)
            .opacity(away ? 0 : 1)
            .onAppear {
                guard away else { return }
                landed()
                let delay = Double(min(order, 9)) * (reduceMotion ? 0.02 : 0.045)
                let motion = reduceMotion ? Animation.easeOut(duration: 0.2) : Animation.spring(response: 0.42, dampingFraction: 0.72)
                withAnimation(motion.delay(delay)) { away = false }
            }
    }
}

/// Arrivals that already flew in. A reference the chat does not observe, so recording
/// a landing re-renders nothing.
private final class ChatComposerLandings {
    var arrivals = Set<UUID>()
}

/// Session-owned drafts survive a completed navigation pop. The cache owns the
/// provider grants and staged files until an item is sent, removed or the session ends.
/// Observed only through the slots of the screens showing this chat, and it publishes
/// only real changes, so another chat's picks, imports and sends re-run nothing here.
@MainActor private final class ChatComposerTrayState: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    let model = ChatComposerModel()
    let landings = ChatComposerLandings()
    /// Written only by picks, imports and sends.
    var attachments: [ChatComposerAttachment] = [] {
        willSet { objectWillChange.send() }
        didSet { journalOwnedFiles() }
    }
    var batch: MediaComposerBatch? { willSet { objectWillChange.send() } }
    var restored = false { willSet { publish(restored, newValue) } }
    var restoring = false { willSet { publish(restoring, newValue) } }
    var restoreFailed = false { willSet { publish(restoreFailed, newValue) } }
    var sending = false { willSet { publish(sending, newValue) } }
    var editing: DecryptedChatMessage? { willSet { publish(editing, newValue) } }
    var reply: DecryptedChatMessage? { willSet { publish(reply, newValue) } }
    let imports = ChatComposerImportTasks()
    var sendTask: Task<Void, Never>?
    /// Completion ownership is separate from the content token invalidated by removal.
    var sendOperationID: UUID?
    /// Tray items the running Send task captured. Removing one of them stops that send;
    /// removing an item added while it runs does not.
    var sendingIDs = Set<String>()
    var importToken = UUID()
    var sendToken = UUID()
    var scrollTarget: String?
    var scrollRequest = UUID() { willSet { publish(scrollRequest, newValue) } }
    var feedback: String? { willSet { publish(feedback, newValue) } }
    var feedbackToken = UUID()
    /// Writes the staged files the tray holds to the chat's journal (set by the memory).
    var journal: (([URL]) -> Void)?
    private var journaled: [URL] = []

    /// Holds nothing the repository does not keep itself: no picks, send, reply, edit,
    /// message or text. A running import always has a placeholder in `attachments`.
    var isIdle: Bool {
        attachments.isEmpty && batch == nil && imports.isEmpty && sendTask == nil && !sending && !restoring &&
            editing == nil && reply == nil && feedback == nil && model.text.isEmpty
    }

    private func publish<Value: Equatable>(_ current: Value, _ next: Value) {
        if current != next { objectWillChange.send() }
    }

    /// Picked files and camera captures are staged copies that only this in-memory tray
    /// references. The store's launch sweep retires them after process termination.
    private func journalOwnedFiles() {
        guard let journal else { return }
        let owned = attachments.compactMap(\.ownedURL)
        guard owned != journaled else { return }
        journaled = owned
        journal(owned)
    }

    func clear(repository: MessagesRepository, chatID: String) {
        importToken = UUID(); sendToken = UUID()
        imports.cancelAll()
        if sendTask != nil { sendTask?.cancel(); repository.directFiles?.cancelPreparing() }
        sendTask = nil; sendOperationID = nil; sending = false; sendingIDs = []
        if repository.preparingMediaChats.contains(chatID) { repository.cancelMediaPreparation(chatId: chatID) }
        // The repository preserves every source still referenced by the journal/outbox.
        let urls = attachments.compactMap(\.ownedURL)
        model.flushActivity(); model.blur()
        attachments = []; batch = nil; restored = false; restoring = false; restoreFailed = false
        feedback = nil; feedbackToken = UUID(); editing = nil; reply = nil; landings.arrivals.removeAll()
        for url in urls { repository.discardUnownedDraftSource(url) }
    }

    func finishSend(_ id: UUID) {
        guard sendOperationID == id else { return }
        sendOperationID = nil; sendTask = nil; sending = false; sendingIDs = []
    }
}

/// The tray one chat screen shows. The screen observes only this slot, which forwards
/// the changes of its own tray; the memory sees the slot as the tray's holder.
@MainActor private final class ChatComposerTraySlot: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    private(set) var tray = ChatComposerTrayState()
    private var forwarding: AnyCancellable?

    init() { forward() }

    func show(_ next: ChatComposerTrayState) {
        guard next !== tray else { return }
        objectWillChange.send()
        tray = next
        forward()
    }

    private func forward() {
        forwarding = tray.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }
}

@MainActor private final class ChatComposerTrayMemory {
    static let shared = ChatComposerTrayMemory()
    private struct Key: Hashable {
        let auth: ObjectIdentifier
        let server: URL
        let owner: String?
        let generation: UInt64
        let chat: String
    }
    private struct Holder { weak var slot: ChatComposerTraySlot? }
    private struct Entry {
        let tray: ChatComposerTrayState
        weak var chats: ChatsRepository?
        let clear: () -> Void
        /// The screens showing this tray (two tabs can show one chat).
        var holders: [Holder] = []
        var held: Bool { holders.contains { $0.slot != nil } }
    }
    private struct Watch {
        weak var chats: ChatsRepository?
        let deletions: AnyCancellable
    }
    private var entries: [Key: Entry] = [:]
    private var watches: [Watch] = []
    private var hooked = Set<ObjectIdentifier>()

    func tray(auth: AuthRepository, repository: MessagesRepository, chats: ChatsRepository, chatID: String,
              holder: ChatComposerTraySlot) -> ChatComposerTrayState {
        let identity = ObjectIdentifier(auth)
        if hooked.insert(identity).inserted {
            let previous = auth.willChangeSession
            // Run while the old repository still owns its session, including when no chat is open.
            auth.willChangeSession = { [weak self] in
                self?.clear(auth: identity)
                previous?()
            }
        }
        watch(chats)
        let key = Key(auth: identity, server: auth.authenticatedServerURL, owner: auth.currentUser?.id,
            generation: auth.sessionGeneration, chat: chatID)
        if var entry = entries[key] {
            entry.holders.removeAll { $0.slot == nil || $0.slot === holder }
            entry.holders.append(Holder(slot: holder))
            entries[key] = entry
            evictIdle()
            return entry.tray
        }
        evictIdle()
        let tray = ChatComposerTrayState()
        tray.journal = { [weak repository] urls in
            repository?.journalComposerTrayFiles(chatId: chatID, urls: urls)
        }
        entries[key] = Entry(tray: tray, chats: chats, clear: { [weak repository] in
            if let repository { tray.clear(repository: repository, chatID: chatID) }
        }, holders: [Holder(slot: holder)])
        return tray
    }

    /// Deleting a conversation from the list must also retire its hidden draft. One
    /// subscription per chats repository (one per session), not one per opened chat.
    private func watch(_ chats: ChatsRepository) {
        watches.removeAll { $0.chats == nil }
        guard !watches.contains(where: { $0.chats === chats }) else { return }
        var known = chats.deletedChatIDs
        let deletions = chats.$deletedChatIDs.dropFirst().sink { [weak self, weak chats] ids in
            let deleted = ids.subtracting(known)
            known = ids
            guard let self, let chats, !deleted.isEmpty else { return }
            for (key, entry) in self.entries where entry.chats === chats && deleted.contains(key.chat) { self.remove(key) }
        }
        watches.append(Watch(chats: chats, deletions: deletions))
    }

    /// A tray no screen shows that holds nothing of its own is dropped, so browsing many
    /// chats leaves no trays behind. Busy or non-empty trays stay for the session.
    private func evictIdle() {
        for (key, entry) in entries where !entry.held && entry.tray.isIdle { entries[key] = nil }
    }

    private func remove(_ key: Key) {
        let entry = entries.removeValue(forKey: key)
        entry?.clear()
    }

    private func clear(auth: ObjectIdentifier) {
        let keys = entries.keys.filter { $0.auth == auth }
        for key in keys { remove(key) }
    }
}

/// First frame of a staged video for the composer tray, with a small video badge.
private struct ChatComposerVideoFrame: View {
    let url: URL
    var clientMessageID: String? = nil
    var body: some View {
        UnyriAttachmentImage(request: .file(url, video: true, clientMessageID: clientMessageID, pixelEdge: 256)) { image in
            Color.secondary.opacity(0.15)
                .overlay { if let image { Image(uiImage: image).resizable().scaledToFill() } }
                .overlay(alignment: .bottomLeading) {
                    Image(systemName: "video.fill").font(.system(size: 10, weight: .semibold)).foregroundStyle(.white)
                        .padding(4).background(.black.opacity(0.55), in: Capsule()).padding(4)
                }.clipped()
        }
    }
}

enum ChatComposerDirectStatus: Equatable { case preparing, queued }

private struct ChatComposerThumbnail: View {
    let item: ChatComposerAttachment
    var directStatus: ChatComposerDirectStatus? = nil
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @State private var preview: UIImage?
    var body: some View {
        ZStack {
            if item.isDocument { Color.white.opacity(0.07) } else { appColorStyle.surface(ChatControlPalette.blueBack) }
            switch item.source {
            case .gallery(let source):
                switch source {
                case .asset(let asset): UnyriGalleryThumbnail(asset: asset)
                case .capture(let url): UnyriCaptureThumbnail(url: url)
                }
            case .file(let file) where !item.isDocument:
                if file.isVideo { ChatComposerVideoFrame(url: file.url, clientMessageID: item.id) } else { UnyriCaptureThumbnail(url: file.url) }
            case .file, .document, .unavailable:
                HStack(spacing: 10) {
                    Image(systemName: "doc.fill").font(.system(size: 18, weight: .medium)).foregroundStyle(UnyriSavedChatStyle.bottomInk.opacity(0.9))
                        .frame(width: 40, height: 40)
                        .background(Color.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.documentName).font(.footnote.weight(.medium)).lineLimit(1).truncationMode(.middle)
                            .foregroundStyle(UnyriSavedChatStyle.bottomInk)
                        if let directStatus {
                            HStack(spacing: 5) {
                                if directStatus == .preparing { ProgressView().controlSize(.mini).tint(UnyriColors.secondaryText) }
                                Text(directStatus == .preparing ? "Preparing…" : "Queued")
                                    .font(.caption2).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                            }
                        } else {
                            Text(item.documentBytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? UnyriL10n.string("Original unavailable"))
                                .font(.caption2).monospacedDigit().foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.padding(.leading, 10).padding(.trailing, 26).padding(.vertical, 8)
            case .photo(let photo):
                UnyriPickerAttachmentThumbnail(item: photo, video: item.isVideo)
            case .importing:
                if let preview { Image(uiImage: preview).resizable().scaledToFill() }
                else { Image(systemName: item.kind.hasPrefix("video") ? "video" : "photo").foregroundStyle(appColorStyle.decorative(ChatControlPalette.blue)) }
            }
        }.clipped()
    }
}

/// The composer text, kept out of ChatScreen state. ChatScreen holds it as a plain
/// @State reference and never observes it. A keystroke publishes nothing to SwiftUI: the
/// UIKit input owns the text while typing, and only small publishers reach views, each
/// when its value really changes: blank state (send vs mic, the Schedule row), the
/// input's height (a wrap to another line) and, for the attachment sheet's caption only,
/// every change. Actions (send, edit, reply, voice hold, the live-draft tick, Schedule)
/// read the text at the moment they run.
@MainActor final class ChatComposerModel {
    private(set) var text = ""
    /// Whitespace-only text counts as empty (the mic shows instead of Send).
    var isBlank: Bool { blankState.isBlank }
    let blankState = ChatComposerBlankState()
    let changes = ChatComposerChanges()
    let height = ChatComposerHeight()
    /// The non-edit draft: kept while a message is being edited and restored after it.
    var ordinaryDraft = ""
    /// Bumped by user edits; an asynchronous caption restore skips text changed meanwhile.
    var revision: UInt64 = 0
    private var restored = false
    /// Every input showing this model: the Chats and Calls tabs can each hold a screen of
    /// the same chat, and they share its tray. Programmatic text and focus go to the one
    /// on screen, else to the one that claimed the model last.
    private var inputs: [ChatComposerInputWeak] = []
    private weak var claimed: ChatComposerInputView?
    fileprivate var input: ChatComposerInputView? {
        if let claimed, claimed.window != nil { return claimed }
        return inputs.lazy.compactMap(\.input).first { $0.window != nil } ?? claimed
    }
    /// A focus request made while the input was not on screen, honoured when it appears.
    fileprivate var focusRequestedAt: TimeInterval?

    /// Typed text (from the input, not written back) and programmatic text (shown in it).
    func setText(_ value: String) {
        guard value != text else { return }
        changes.objectWillChange.send()
        text = value
        blankState.set(Self.blank(value))
        input?.show(value)
    }

    /// Applies the saved draft once, before the composer first renders (nothing observes
    /// the model yet, so this does not publish).
    func restoreOnce(_ load: () -> String) {
        guard !restored else { return }
        restored = true
        ordinaryDraft = load()
        text = ordinaryDraft
        blankState.set(Self.blank(text), publish: false)
    }

    var isFocused: Bool { input?.isFirstResponder ?? false }

    /// An input attached to this model or came on screen: it receives programmatic text
    /// and focus from now on.
    fileprivate func claim(_ input: ChatComposerInputView) {
        inputs.removeAll { $0.input == nil || $0.input === input }
        inputs.append(ChatComposerInputWeak(input: input))
        claimed = input
    }

    /// Like setting a FocusState: honoured for up to a second while the input is not on
    /// screen yet or UIKit refuses (a menu or presentation still closing).
    func focus() {
        focusRequestedAt = ProcessInfo.processInfo.systemUptime
        focusIfRequested()
    }

    func blur() {
        focusRequestedAt = nil
        if let input, input.isFirstResponder { input.resignFirstResponder() }
    }

    fileprivate func focusIfRequested() {
        guard let requested = focusRequestedAt else { return }
        guard ProcessInfo.processInfo.systemUptime - requested < 1 else { focusRequestedAt = nil; return }
        // Not on screen yet: the input asks again when it reaches a window.
        guard let input, input.window != nil else { return }
        if input.isFirstResponder || input.becomeFirstResponder() { focusRequestedAt = nil; return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in self?.focusIfRequested() }
    }

    /// Stops at the first visible character, instead of trimming the whole text per keystroke.
    static func blank(_ value: String) -> Bool { !value.contains { !$0.isWhitespace } }

    // MARK: Typing side effects

    /// What the chat allowed at the last change; the text itself is read when the pass runs.
    struct ActivityGate {
        let editing: Bool
        /// Live draft allowed: a peer chat on screen.
        let liveDraft: Bool
        /// "Typing…" allowed: a peer chat with the setting on.
        let typing: Bool
    }
    private var pendingActivity: ActivityGate?
    private var chatID = ""
    private weak var repository: MessagesRepository?
    private weak var realtime: RealtimeClient?

    func bindActivity(chatID: String, repository: MessagesRepository, realtime: RealtimeClient) {
        self.chatID = chatID
        if self.repository !== repository { self.repository = repository }
        if self.realtime !== realtime { self.realtime = realtime }
    }

    /// Draft save, live draft and typing activity in one trailing pass, at most about four
    /// times a second, so a keystroke does only the text update itself. Holds no screen
    /// copy: it outlives the chat for the last change only.
    func scheduleActivity(_ gate: ActivityGate) {
        let alreadyDue = pendingActivity != nil
        pendingActivity = gate
        guard !alreadyDue else { return }
        // Strong reference: text typed just before leaving the chat is still saved.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.flushActivity() }
    }

    func flushActivity() {
        guard let gate = pendingActivity else { return }
        pendingActivity = nil
        let text = self.text
        if gate.liveDraft { realtime?.liveDrafts.update(!gate.editing && isFocused ? text : "") }
        if gate.typing { realtime?.sendActivity(chatId: chatID, kind: text.isEmpty ? "idle" : "typing") }
        if !gate.editing { repository?.saveDraftValues(chatId: chatID, values: ["draft": text]) }
    }
}

/// Published only when the composer turns blank or not.
@MainActor final class ChatComposerBlankState: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    private(set) var isBlank = true
    fileprivate func set(_ value: Bool, publish: Bool = true) {
        guard value != isBlank else { return }
        if publish { objectWillChange.send() }
        isBlank = value
    }
}

/// Published on every text change: observed only by the attachment sheet's caption.
@MainActor final class ChatComposerChanges: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
}

/// Published only when the input's height changes (a wrap to another line).
@MainActor final class ChatComposerHeight: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    /// Bumped on each change, so SwiftUI asks the input for its size again.
    private(set) var revision: UInt64 = 0
    fileprivate func changed() {
        objectWillChange.send()
        revision &+= 1
    }
}

/// The composer's size for the voice-hold gesture math: a reference the chat reads when a
/// gesture runs, so a change of size never re-renders the chat.
private final class ChatComposerGeometry {
    var size: CGSize = .zero
}

/// The composer's text input: a UITextView that grows from one to four lines. It owns the
/// text while typing; SwiftUI hears only a change of height or of blank state, so a
/// keystroke re-renders and re-lays out nothing in SwiftUI.
final class ChatComposerInputView: UITextView {
    fileprivate weak var model: ChatComposerModel?
    /// Set while a typed change goes out, so it is not written back into the view.
    fileprivate var reportingEdit = false
    private let placeholderLabel = UILabel()
    private var fitted: (width: CGFloat, height: CGFloat) = (0, 0)
    private static let maxLines: CGFloat = 4
    fileprivate var onPasteMedia: (([NSItemProvider], Int) -> Void)?

    fileprivate static func pastedMedia(_ provider: NSItemProvider) -> ChatComposerPastePolicy.Media? {
        ChatComposerPastePolicy.media(in: provider.registeredTypeIdentifiers) { type, parent in
            guard let type = UTType(type), let parent = UTType(parent) else { return false }
            return type.conforms(to: parent)
        }
    }

    init() {
        super.init(frame: .zero, textContainer: nil)
        backgroundColor = .clear
        let existingPasteTypes = pasteConfiguration?.acceptableTypeIdentifiers ?? []
        pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: existingPasteTypes +
            ChatComposerPastePolicy.acceptableTypes.filter { !existingPasteTypes.contains($0) })
        // The SwiftUI field had 8 pt above and below and no side inset.
        textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        textContainer.lineFragmentPadding = 0
        // Its own measured height is the whole story: no automatic safe-area insets.
        contentInsetAdjustmentBehavior = .never
        // The status-bar tap belongs to the timeline (oldest message).
        scrollsToTop = false
        showsHorizontalScrollIndicator = false
        placeholderLabel.isAccessibilityElement = false
        placeholderLabel.numberOfLines = 1
        addSubview(placeholderLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func canPaste(_ itemProviders: [NSItemProvider]) -> Bool {
        if isEditable, itemProviders.contains(where: { Self.pastedMedia($0) != nil }) { return true }
        return super.canPaste(itemProviders)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        // Inspect only type metadata while constructing the menu, without reading bytes.
        if action == #selector(paste(_:)), isEditable,
           ChatComposerPastePolicy.media(in: UIPasteboard.general.types, conforms: { type, parent in
               guard let type = UTType(type), let parent = UTType(parent) else { return false }
               return type.conforms(to: parent)
           }) != nil { return true }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        let providers = UIPasteboard.general.itemProviders
        if providers.contains(where: { Self.pastedMedia($0) != nil }) {
            paste(itemProviders: providers)
        } else {
            super.paste(sender)
        }
    }

    override func paste(itemProviders: [NSItemProvider]) {
        guard itemProviders.contains(where: { Self.pastedMedia($0) != nil }) else {
            super.paste(itemProviders: itemProviders)
            return
        }
        // Non-media providers pass through intact. Media providers expose only their
        // text/URL alternatives to UIKit; image/video bytes go to the tray importer.
        let textProviders = itemProviders.compactMap { provider -> NSItemProvider? in
            let route = ChatComposerPastePolicy.uiKitRoute(in: provider.registeredTypeIdentifiers, conforms: { type, parent in
                guard let type = UTType(type), let parent = UTType(parent) else { return false }
                return type.conforms(to: parent)
            })
            switch route {
            case .unchanged: return provider
            case .representations(let types):
                guard !types.isEmpty else { return nil }
                let text = NSItemProvider()
                for type in types {
                    text.registerDataRepresentation(forTypeIdentifier: type, visibility: .all) { completion in
                        provider.loadDataRepresentation(forTypeIdentifier: type, completionHandler: completion)
                    }
                }
                return text
            }
        }
        if !textProviders.isEmpty { super.paste(itemProviders: textProviders) }
        onPasteMedia?(itemProviders, UIPasteboard.general.changeCount)
    }

    private var lineHeight: CGFloat { ceil(font?.lineHeight ?? 18) }
    private var verticalInsets: CGFloat { textContainerInset.top + textContainerInset.bottom }
    private var minimumHeight: CGFloat { lineHeight + verticalInsets }
    private var maximumHeight: CGFloat { lineHeight * Self.maxLines + verticalInsets }

    fileprivate func attach(_ composer: ChatComposerModel) {
        model = composer
        composer.claim(self)
        if text != composer.text { text = composer.text }
        placeholderLabel.isHidden = !composer.text.isEmpty
        fitted = (0, 0)
    }

    fileprivate func apply(fontSize: CGFloat, ink: UIColor, tint: UIColor, placeholder: String, placeholderInk: UIColor) {
        // Caret and selection keep the SwiftUI tint the field had (UIKit's default is blue).
        if tintColor != tint { tintColor = tint }
        if font?.pointSize != fontSize {
            let font = UIFont.systemFont(ofSize: fontSize)
            self.font = font
            placeholderLabel.font = font
            fitted = (0, 0)
            setNeedsLayout()
        }
        if textColor != ink { textColor = ink }
        if placeholderLabel.text != placeholder { placeholderLabel.text = placeholder }
        if placeholderLabel.textColor != placeholderInk { placeholderLabel.textColor = placeholderInk }
    }

    /// Programmatic text from the model (send, edit, restore, the caption field).
    fileprivate func show(_ value: String) {
        guard !reportingEdit else { return }
        if text != value { text = value }
        placeholderLabel.isHidden = !value.isEmpty
        refit()
    }

    /// After a typed change.
    fileprivate func typed() {
        placeholderLabel.isHidden = !text.isEmpty
        refit()
    }

    /// Measures again after every change and reports a new height (outside SwiftUI updates).
    /// No change is skipped, also at the line limit: a longer text can still take fewer
    /// lines (an edit loaded over a multi-line draft, a paste over a selection, a joined line).
    private func refit() {
        let width = bounds.width
        // Not laid out yet: the next size query or layout pass measures this text.
        guard width > 0 else { fitted = (0, 0); return }
        let previous = fitted.height
        fitted = (width, measure(width: width))
        if abs(fitted.height - previous) > 0.5 { model?.height.changed() }
    }

    /// The height for a width, measured once per width and text (SwiftUI's size query).
    fileprivate func height(for width: CGFloat) -> CGFloat {
        guard width > 0 else { return minimumHeight }
        if abs(fitted.width - width) > 0.5 || fitted.height <= 0 {
            fitted = (width, measure(width: width))
        }
        return fitted.height
    }

    private func measure(width: CGFloat) -> CGFloat {
        let fitting = sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        guard fitting.isFinite else { return minimumHeight }
        return min(maximumHeight, max(minimumHeight, ceil(fitting)))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let inset = textContainerInset
        placeholderLabel.frame = CGRect(x: inset.left, y: inset.top,
                                        width: max(0, bounds.width - inset.left - inset.right), height: lineHeight)
        if bounds.width > 0, abs(bounds.width - fitted.width) > 0.5 {
            // A width SwiftUI did not ask about: report the height after this layout pass.
            let previous = fitted.height
            if abs(height(for: bounds.width) - previous) > 0.5 {
                DispatchQueue.main.async { [weak self] in self?.model?.height.changed() }
            }
        }
        // Below the line limit everything fits: never leave the text scrolled.
        if fitted.height < maximumHeight - 0.5, contentOffset.y != 0 { contentOffset = .zero }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil, let model else { return }
        // Back on screen (a tab switch or a pop): take the shared model back from the other
        // screen of this chat and show what was typed there. The height is reported after
        // layout (layoutSubviews), never from inside this call.
        model.claim(self)
        if text != model.text {
            text = model.text
            placeholderLabel.isHidden = !model.text.isEmpty
            fitted = (0, 0)
            setNeedsLayout()
        }
        guard model.focusRequestedAt != nil else { return }
        DispatchQueue.main.async { [weak self] in self?.model?.focusIfRequested() }
    }
}

/// A weak reference to an input, for the model's list of inputs.
private struct ChatComposerInputWeak {
    weak var input: ChatComposerInputView?
}

/// The input as a SwiftUI view. Sized from the input's own measurement; re-rendered only
/// when its height changes (`heightRevision` is part of the value for that reason).
private struct ChatComposerTextInput: UIViewRepresentable {
    let composer: ChatComposerModel
    let heightRevision: UInt64
    let fontSize: CGFloat
    let tint: Color
    let onTextChange: (String) -> Void
    let onFocusChange: (Bool) -> Void
    let onPasteMedia: ([NSItemProvider], Int) -> Void

    @MainActor final class Coordinator: NSObject, UITextViewDelegate {
        var onTextChange: (String) -> Void = { _ in }
        var onFocusChange: (Bool) -> Void = { _ in }

        func textViewDidChange(_ textView: UITextView) {
            guard let input = textView as? ChatComposerInputView else { return }
            input.reportingEdit = true
            onTextChange(input.text ?? "")
            input.reportingEdit = false
            input.typed()
        }
        // Reported on the next turn: focus can change inside a SwiftUI update (the input
        // leaving the screen) and the chat writes state in response.
        func textViewDidBeginEditing(_ textView: UITextView) {
            let report = onFocusChange
            DispatchQueue.main.async { report(true) }
        }
        func textViewDidEndEditing(_ textView: UITextView) {
            let report = onFocusChange
            DispatchQueue.main.async { report(false) }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ChatComposerInputView {
        let input = ChatComposerInputView()
        input.delegate = context.coordinator
        input.accessibilityIdentifier = "chat-composer-text"
        configure(input, context: context)
        input.attach(composer)
        return input
    }

    func updateUIView(_ input: ChatComposerInputView, context: Context) {
        configure(input, context: context)
        if input.model !== composer { input.attach(composer) }
    }

    private func configure(_ input: ChatComposerInputView, context: Context) {
        context.coordinator.onTextChange = onTextChange
        context.coordinator.onFocusChange = onFocusChange
        input.onPasteMedia = onPasteMedia
        let placeholder = UnyriL10n.string("Message")
        input.accessibilityLabel = placeholder
        input.apply(fontSize: fontSize, ink: UIColor(UnyriSavedChatStyle.bottomInk), tint: UIColor(tint),
                    placeholder: placeholder, placeholderInk: UIColor(UnyriColors.secondaryText))
    }

    static func dismantleUIView(_ input: ChatComposerInputView, coordinator: Coordinator) {
        // Leaving with work pending (a draft typed just now): do it now.
        input.model?.flushActivity()
        input.onPasteMedia = nil
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ChatComposerInputView, context: Context) -> CGSize? {
        let width = proposal.width.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? max(120, uiView.bounds.width)
        return CGSize(width: width, height: uiView.height(for: width))
    }
}

/// The only composer view that re-renders on a height change.
private struct ChatComposerField: View {
    let composer: ChatComposerModel
    @ObservedObject var height: ChatComposerHeight
    let fontSize: CGFloat
    let tint: Color
    let onTextChange: (String) -> Void
    let onFocusChange: (Bool) -> Void
    let onPasteMedia: ([NSItemProvider], Int) -> Void
    var body: some View {
        ChatComposerTextInput(composer: composer, heightRevision: height.revision, fontSize: fontSize, tint: tint,
                              onTextChange: onTextChange, onFocusChange: onFocusChange, onPasteMedia: onPasteMedia)
            .frame(maxWidth: .infinity)
    }
}

/// What Send needs apart from the text, so the chat can hand it to the composer
/// without reading the text itself.
private enum ChatComposerSendGate: Equatable {
    /// `restoring`: draft attachments are still being restored on chat entry (~100 ms).
    /// Send waits, but the button keeps its look instead of blinking grey.
    case blocked, needsText, ready, restoring
    func allowsSend(blank: Bool) -> Bool {
        switch self {
        case .blocked, .restoring: return false
        case .needsText: return !blank
        case .ready: return true
        }
    }
    func looksEnabled(blank: Bool) -> Bool { self == .restoring ? !blank : allowsSend(blank: blank) }
}

/// Hands its content a binding to the composer text and re-renders it on each change
/// (the attachment sheet's caption); the chat itself does not observe the composer.
private struct ChatComposerTextBinding<Content: View>: View {
    let composer: ChatComposerModel
    @ObservedObject var changes: ChatComposerChanges
    let set: (String) -> Void
    let content: (Binding<String>) -> Content
    var body: some View { content(Binding(get: { composer.text }, set: set)) }
}

/// Shows its content only while the composer holds text (the Schedule menu row).
private struct ChatComposerHasText<Content: View>: View {
    @ObservedObject var blank: ChatComposerBlankState
    @ViewBuilder let content: () -> Content
    @ViewBuilder var body: some View {
        if !blank.isBlank { content() }
    }
}

// Nominal View boundaries keep the composer metadata shallow. The device crash
// in build 20261030 exhausted the stack while Swift decoded the inline field's
// deeply nested generic type, before the conversation could finish opening.
private struct ChatComposerTextEntry: View {
    /// Not observed here: a keystroke re-renders nothing in this row (the attach and emoji
    /// buttons and the attachment popover); only the field follows its own height.
    let composer: ChatComposerModel
    let onTextChange: (String) -> Void
    let onFocusChange: (Bool) -> Void
    let onPasteMedia: ([NSItemProvider], Int) -> Void
    let textScale: CGFloat
    /// Caret and selection color.
    let tint: Color
    let importing: Bool
    let attachmentDisabled: Bool
    let cameraDisabled: Bool
    @Binding var directSelected: Bool
    let directScope: String?
    let directIssue: String?
    let directInfo: () -> Void
    let camera: () -> Void
    let photos: () -> Void
    let files: () -> Void
    var emojiActive = false
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
                cameraDisabled: cameraDisabled, directSelected: $directSelected, directScope: directScope, directIssue: directIssue, directInfo: directInfo, camera: camera, photos: photos, files: files)
                .allowsHitTesting(false))
            if importing { ProgressView().controlSize(.small) }
            ChatComposerField(composer: composer, height: composer.height, fontSize: UnyriSavedChatStyle.entryFont * textScale,
                              tint: tint, onTextChange: onTextChange, onFocusChange: onFocusChange, onPasteMedia: onPasteMedia)
            Button(action: emoji) {
                Image(systemName: emojiActive ? "keyboard" : "face.smiling").font(.system(size: 20, weight: .regular))
                    .foregroundStyle(UnyriSavedChatStyle.bottomInk).frame(width: 44, height: max(44, UnyriSavedChatStyle.entryHeight)).contentShape(Rectangle())
                    .contentTransition(.opacity)
            }.accessibilityLabel(emojiActive ? "Keyboard" : "Emoji").accessibilityIdentifier("chat-composer-emoji")
        }.padding(.horizontal, 2).frame(minHeight: max(44, UnyriSavedChatStyle.entryHeight))
    }
}

private struct ChatComposerSubmitControl: View {
    /// Observed here (not by ChatScreen): mic vs Send follows blank state without a chat
    /// pass, and only when it flips (not on every keystroke).
    @ObservedObject var blank: ChatComposerBlankState
    /// No edit and no attachments: an empty composer shows the recorder.
    let recorderEligible: Bool
    let microphoneInUse: Bool
    let sendGate: ChatComposerSendGate
    private var showsRecorder: Bool { recorderEligible && blank.isBlank }
    private var canSend: Bool { sendGate.allowsSend(blank: blank.isBlank) }
    let editing: Bool
    let attachmentCount: Int
    let record: () -> Void
    let send: () -> Void
    var switchMode: () -> Void = {}
    /// Voice or video note; observed here only, so switching does not re-render the chat.
    @AppStorage(ChatVideoNoteMode.key) private var videoMode = false

    var body: some View {
        Group {
            if showsRecorder {
                Button(action: record) {
                    ChatRecordModeGlyph(video: videoMode)
                        .foregroundColor(UnyriSavedChatStyle.bottomInk)
                        .frame(width: max(44, UnyriSavedChatStyle.entryHeight), height: max(44, UnyriSavedChatStyle.entryHeight)).contentShape(Rectangle())
                }
                .opacity(microphoneInUse ? 0.4 : 1).disabled(microphoneInUse)
                .accessibilityLabel(Text(UnyriL10n.string(microphoneInUse ? "Microphone is in use during the call" : videoMode ? "Record video message" : "Record voice message")))
                .accessibilityValue(Text(verbatim: videoMode ? "video" : "voice"))
                .accessibilityAction(named: Text(UnyriL10n.string(videoMode ? "Switch to voice message" : "Switch to video message")), switchMode)
                .accessibilityIdentifier("chat-composer-record")
            } else {
                HStack(alignment: .center, spacing: 4) {
                    // The picked items are visible in the composer itself; the badge counts what
                    // Send will take (a tray fill must be seen), VoiceOver hears it as the value.
                    Button(action: send) {
                        Image(systemName: editing ? "checkmark" : "arrow.up")
                            .font(.system(size: UnyriSavedChatStyle.iconSize, weight: .semibold)).foregroundColor(UnyriSavedChatStyle.micInk)
                            .frame(width: max(36, UnyriSavedChatStyle.entryHeight - 4), height: max(36, UnyriSavedChatStyle.entryHeight - 4)).background(UnyriSavedChatStyle.micColor, in: Circle())
                            .overlay(alignment: .topTrailing) {
                                if attachmentCount > 0 && !editing {
                                    Text(verbatim: "\(attachmentCount)").font(.system(size: 11, weight: .bold)).monospacedDigit()
                                        .foregroundColor(UnyriSavedChatStyle.micColor)
                                        .padding(.horizontal, 4).frame(minWidth: 17, minHeight: 17)
                                        .background(UnyriSavedChatStyle.micInk, in: Capsule())
                                        .overlay(Capsule().strokeBorder(UnyriSavedChatStyle.micColor, lineWidth: 1))
                                        .contentTransition(.numericText())
                                        .offset(x: 5, y: -5)
                                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                                        .accessibilityHidden(true)
                                }
                            }
                            .opacity(sendGate.looksEnabled(blank: blank.isBlank) ? 1 : 0.4).frame(width: 44, height: 44).contentShape(Rectangle())
                    }.disabled(!canSend).accessibilityLabel(editing ? "Save edit" : "Send message")
                        .accessibilityValue(attachmentCount > 0 ? Text(verbatim: "\(attachmentCount)") : Text(verbatim: ""))
                        .accessibilityIdentifier("chat-composer-send")
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
    @Environment(\.dismiss) private var dismissChat
    @Environment(\.scenePhase) private var scenePhase
    // Not @EnvironmentObject: ChatsRepository publishes unread counts, list cache
    // revisions and refresh timestamps for every chat, so each incoming message in
    // any other chat re-evaluated this screen. Only this chat's preference entry and
    // deletions are observed (see the onReceive handlers in the body).
    @Environment(\.chatChatsRepository) private var chatsRepositoryReference
    private var chatsRepository: ChatsRepository {
        guard let chatsRepositoryReference else { preconditionFailure("ChatScreen requires the chatChatsRepository environment value") }
        return chatsRepositoryReference
    }
    @State private var preferenceSeen = ChatPreferenceSeen()
    @State private var preferenceRevision = 0
    @State private var deletedChatsSeen = false
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    @EnvironmentObject private var voiceRecorder: VoiceRecorder
    @EnvironmentObject private var callKit: CallKitCoordinator
    // Not @EnvironmentObject: RealtimeClient publishes presence for every user and
    // typing activity for every chat. Observing it re-evaluated this whole screen
    // (and its timeline presentation) on unrelated events, even mid-scroll.
    // Only the connection state is observed here; presence/typing live in ChatHeaderBar.
    @Environment(\.chatRealtimeClient) private var realtimeReference
    private var realtime: RealtimeClient {
        guard let realtimeReference else { preconditionFailure("ChatScreen requires the chatRealtimeClient environment value") }
        return realtimeReference
    }
    @State private var realtimeStateSeen = ChatRealtimeStateSeen()
    @State private var realtimeStateChange: RealtimeClient.State?
    /// The current connection state; a change re-renders through `realtimeStateChange`.
    private var realtimeState: RealtimeClient.State { realtimeStateChange ?? realtime.state }
    @EnvironmentObject private var callsHistory: CallsRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @AppStorage("chatActivityEnabled") private var activityEnabled = false
    // Saved Messages has no remote peer. Its draft/recording still works
    // locally, but a self-chat must not advertise peer activity to the server.
    private var allowsPeerActivity: Bool { chat.chatType != "saved" }
    @State private var presenceOwner = "chat:" + UUID().uuidString
    @State private var composerEmojiKeyboard = false
    @State private var headerMenuOpen = false
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
    @State private var expandedDirectAlbums = Set<String>()
    @State private var showBulkDelete = false
    private struct DeleteSelection {
        var everyone: Bool
        let ids: [String]
        let generation: UInt64
        let owner: String
        let chatID: String
        let priorSelection: Set<String>
        let queuedAlbumIDs: [String]
    }
    @State private var pendingBulkDelete: DeleteSelection?
    private var pinnedMessages: Set<String> { messagesRepository.pinnedPreviews(chatId: chat.chatId).ids }
    @State private var onlyPinned = false
    @State private var showKeyVerification = false
    @State private var retryAfterVerification: String?
    @State private var acceptedForRetry = false
    @Environment(\.signalVerificationManager) private var verificationManager
    @State private var identityNeedsReview = false
    @State private var ownDeviceNeedsReview = false
    @State private var peerDeviceNeedsReview = false
    @State private var chatBindingFrozen = false
    @State private var identityRefreshPending = false
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
    private var currentPreferenceChat: ChatSummary {
        _ = preferenceRevision // re-render dependency: bumped when this chat's cached entry changes
        return chatsRepository.preferenceCache[chat.chatId] ?? preferenceChat ?? chat
    }
    // Display only: the route/recipient/call identity remains the opened chat.
    private var currentHeaderPeer: PublicUser? {
        guard let routed = chat.otherUser else { return nil }
        let latest = currentPreferenceChat
        guard latest.chatId == chat.chatId, latest.chatType == chat.chatType,
              let peer = latest.otherUser, peer.id == routed.id else { return routed }
        return peer
    }
    /// The header avatar task's identity. A value, not a string interpolated (with the
    /// server URL's absoluteString) on every body pass.
    private struct HeaderAvatarKey: Equatable {
        let generation: UInt64
        let server: URL
        let owner: String?
        let peer: String?
        let version: Int64?
        let avatar: String?
    }
    private var headerAvatarKey: HeaderAvatarKey {
        let peer = currentHeaderPeer
        return HeaderAvatarKey(generation: auth.sessionGeneration, server: auth.authenticatedServerURL,
            owner: auth.currentUser?.id, peer: peer?.id, version: peer?.avatarVersion, avatar: peer?.avatarUrl)
    }
    /// Task identities compared on every body pass: values instead of strings that
    /// described the scene phase and connection state through reflection each pass.
    private struct PresenceTaskKey: Equatable {
        let phase: ScenePhase
        let state: RealtimeClient.State
        let chat: String
        let blocked: Bool
        let visible: Bool
    }
    private var presenceTaskKey: PresenceTaskKey {
        PresenceTaskKey(phase: scenePhase, state: realtimeState, chat: chat.chatId,
            blocked: currentPreferenceChat.isBlocked == true, visible: conversationVisible)
    }
    private struct HistoryFallbackTaskKey: Equatable {
        let phase: ScenePhase
        let visible: Bool
        let state: RealtimeClient.State
        let chat: String
    }
    private var historyFallbackTaskKey: HistoryFallbackTaskKey {
        HistoryFallbackTaskKey(phase: scenePhase, visible: conversationVisible, state: realtimeState, chat: chat.chatId)
    }
    @State private var forwardSearch = ""
    @State private var showForwardPicker = false
    @State private var forwardQueue: [DecryptedChatMessage] = []
    /// What one Forward attempt already delivered, so a retry after a partial failure
    /// does not send the same messages (and the comment) to the same chats again.
    @State private var forwardProgress = ChatForwardProgress()
    /// One id per Forward sheet: each (message, destination) gets a stable client id,
    /// so the server deduplicates a resend even if the sheet state is lost.
    @State private var forwardSession = UUID()
    @State private var forwardAlbumIDs: [String: String] = [:]
    @State private var destinations: [ChatSummary] = []
    @State private var forwarding = false
    @State private var forwardDestination: ChatSummary?
    @State private var forwardLoading = false
    @State private var forwardError: String?
    @State private var jumpRequest: String?
    // Telegram-style in-chat search: the header becomes a search field and the
    // timeline scrolls to each hit in place (no separate results screen).
    @State private var inChatSearch = false
    @State private var showPeerProfile = false
    @State private var bubbleActions = ChatBubbleActionCache()
    @State private var inChatQuery = ""
    @State private var searchHits: [String] = []
    @State private var searchHitIndex = 0
    @State private var searchBusy = false
    @FocusState private var searchFocused: Bool
    @State private var returnAnchor: String?
    @State private var returningToAnchor = false
    @State private var arrivalTracker = ChatTailTracker()
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

    /// The composer text and focus: a reference ChatScreen does not observe (see
    /// ChatComposerModel). Focus lives in the UIKit input, so showing or hiding the
    /// keyboard does not re-run this screen. The tray is observed through this screen's
    /// own slot: another chat's tray changes never re-run it.
    @StateObject private var composerTraySlot = ChatComposerTraySlot()
    private var composerTray: ChatComposerTrayState { composerTraySlot.tray }
    private var composerModel: ChatComposerModel { composerTray.model }
    @State private var composerRestored = false
    /// Uptime at the moment this chat was entered and not yet shown positioned.
    /// Used only to report the entry-to-visible latency users actually feel.
    @State private var openClock = ChatOpenClock()
    private var isSending: Bool {
        get { composerTray.sending }
        nonmutating set { composerTray.sending = newValue }
    }
    private var replyTarget: DecryptedChatMessage? {
        get { composerTray.reply }
        nonmutating set { composerTray.reply = newValue }
    }
    private var editingMessage: DecryptedChatMessage? {
        get { composerTray.editing }
        nonmutating set { composerTray.editing = newValue }
    }
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var photoDrafts: [PhotoDraft] = []
    @State private var mediaCaption = ""
    @State private var mediaAlbumId = UUID().uuidString.lowercased()
    @State private var persistedMediaURLs = Set<URL>()
    @State private var admittedMediaURLs = MediaDraftAdmissions()
    @State private var uploadedPhotos: [String: AttachmentMetadata] = [:]
    @State private var mediaRestoreToken = UUID()
    @State private var mediaPreviewTask: Task<Void, Never>?
    @State private var loadedMediaDraftRevision = 0
    private var mediaPreparationActive: Bool { messagesRepository.preparingMediaChats.contains(chat.chatId) }
    @State private var showPhotoPreview = false
    @State private var showLegacyMediaChoice = false
    @State private var didOfferLegacyMedia = false
    @State private var showMediaGallery = false
    @State private var showFileImporter = false
    @State private var showAttachmentMenu = false
    @State private var attachmentDocuments: [AttachmentFileCopy.DocumentSource] = []
    @State private var cameraFocusesComposer = false
    @State private var attachmentStartsOnFiles = false
    @State private var showPhotoPicker = false
    @State private var attachmentPhotoItems: [PhotosPickerItem] = []
    @State private var showCamera = false
    @State private var cameraClaim: NonCallCaptureOwnership.Claim?
    @State private var pendingVoiceClaim: NonCallCaptureOwnership.Claim?
    @State private var attachmentAssets: [UnyriGallerySource] = []
    /// The sheet closed with Send now (a long press on Add) instead of Add.
    @State private var attachmentSendsNow = false
    private var composerLandings: ChatComposerLandings { composerTray.landings }
    @State private var gallerySendIntent = MediaSendIntent()
    private var composerAttachments: [ChatComposerAttachment] {
        get { composerTray.attachments }
        nonmutating set { composerTray.attachments = newValue }
    }
    private var composerBatch: MediaComposerBatch? {
        get { composerTray.batch }
        nonmutating set { composerTray.batch = newValue }
    }
    private var composerBatchRestored: Bool {
        get { composerTray.restored }
        nonmutating set { composerTray.restored = newValue }
    }
    private var composerBatchRestoring: Bool {
        get { composerTray.restoring }
        nonmutating set { composerTray.restoring = newValue }
    }
    private var composerBatchRestoreFailed: Bool {
        get { composerTray.restoreFailed }
        nonmutating set { composerTray.restoreFailed = newValue }
    }
    private var composerFileImports: Int { composerAttachments.filter(\.isImporting).count }
    private var composerImportToken: UUID {
        get { composerTray.importToken }
        nonmutating set { composerTray.importToken = newValue }
    }
    private var composerSendToken: UUID {
        get { composerTray.sendToken }
        nonmutating set { composerTray.sendToken = newValue }
    }
    @State private var gallerySelection: [String] = []
    @State private var galleryCaptures: [URL] = []
    @State private var addMoreMedia = false
    @State private var cameraSessionGeneration: UInt64 = 0
    @State private var cameraNeedsRetry = false
    @State private var attachmentChoice: String?
    @State private var fileDraft: FileAttachmentDraft?
    @State private var directFileSelection: DirectFileSendSelection?
    @State private var directPreferenceRevision: UInt64 = 0
    @State private var directSelectionContext: DirectSendSelectionContext?
    @State private var showDirectTransferInfo = false
    @State private var showDirectOffer = false
    private var composerDirectTask: Task<Void, Never>? {
        get { composerTray.sendTask }
        nonmutating set { composerTray.sendTask = newValue }
    }
    @State private var stagedFileDirectory: URL?
    @State private var voiceDraft: VoiceDraft?
    @State private var resumeVoiceBase: VoiceDraft?
    @State private var sendingVoice = false
    @State private var stagingVoice = false
    @State private var preparedVoice: AttachmentMetadata?
    @State private var isRecording = false
    // Hold drag lives in a box only the recording bar observes: writing it per touch
    // move no longer re-runs the whole chat body.
    @State private var recordingDragBox = ChatVoiceHoldDragBox()
    private var recordingDrag: CGSize {
        get { recordingDragBox.offset }
        nonmutating set { if recordingDragBox.offset != newValue { recordingDragBox.offset = newValue } }
    }
    @State private var recordingLocked = true
    /// Round video messages. A reference this screen does not observe: its phases reach
    /// only the recording stage over the timeline and the recording bar.
    @State private var videoNoteRecorder = VideoNoteRecorder()
    /// The recording in progress is a video note, not a voice message.
    @State private var recordingVideoNote = false
    @State private var startingVoice = false
    @State private var recordingHoldActive = false
    @State private var recordingHoldFinished = false
    @State private var voiceHoldAxis: VoiceHoldAxis?
    @State private var voiceHoldHint: String?
    @State private var stagingSendsAfter = false
    @State private var lastVoiceHoldEnd = Date.distantPast
    /// A reference, not a @State value: the composer's size goes to the timeline controller
    /// directly and the gesture math reads it when it runs, so a wrap does not re-run this screen.
    @State private var composerGeometry = ChatComposerGeometry()
    /// The inverted UIKit timeline (docs/IOS_CHAT_TIMELINE_INVERTED_20261110.md).
    @State private var hostedTimeline = ChatTimelineController()
    @State private var hostedRows = HostedRowsCache()
    @State private var recordingSeconds: Int64 = 0
    // Runtime Correction D: near-bottom tracking + pending-new-messages count.
    /// "At the bottom" and the new-messages count live outside SwiftUI state: flipping
    /// them (reaching the end of a fling, the jump button appearing) re-evaluated the
    /// whole chat screen and every row — a ~130 ms freeze then a visible jump at the
    /// bottom (screen recording, 9 Nov). Only the jump button observes them now.
    @State private var bottomState = ChatBottomState()
    private var isNearBottom: Bool {
        get { bottomState.nearBottom }
        nonmutating set { bottomState.nearBottom = newValue }
    }
    @State private var environmentActions = ChatEnvironmentActions()
    @State private var timelineRootNonce = UUID()
    private var newMessagesCount: Int {
        get { bottomState.newCount }
        nonmutating set { bottomState.newCount = newValue }
    }
    private final class ChatReadStateTracker {
        /// Newest sequence the repository accepted as read on screen.
        var sequence: Int64 = 0
        /// Newest sequence seen on screen, waiting out the debounce.
        var pending: Int64 = 0
        var task: Task<Void, Never>?
    }
    @State private var readTracker = ChatReadStateTracker()
    // One shared 1 Hz publisher. A stored publisher is re-created whenever SwiftUI
    // builds a new ChatScreen value (every parent update, including the opens
    // triggered by list state), which restarts the `onReceive` subscription and
    // its recording/live-draft ticks. Autoconnect still stops the timer when the
    // last subscriber goes away.
    private static let recordingTicker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private var recordingTimer: Publishers.Autoconnect<Timer.TimerPublisher> { Self.recordingTicker }

    private var conversationVisible: Bool {
        // requiresShield is false whenever the lock is off; skip building its owner string
        // (absoluteString + concatenation) on each of the many reads per body pass.
        scenePhase == .active && !(appLock.enabled && appLock.requiresShield(owner: auth.currentUser.map {
            auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration)
        })) && !showAttachmentMenu && !showCamera && !showPhotoPicker &&
        !showPhotoPreview && !showFileImporter && !showMediaGallery && fileDraft == nil && directFileSelection == nil && fullTextRequest == nil &&
        !callScreenCovering
    }
    /// Sheets, covers and menus over the timeline. Presence, drafts and the repository
    /// still treat the chat as open, but what arrives behind them is not read. The
    /// in-chat search is part of the chat and stays readable.
    private var coveredBySheet: Bool {
        showPeerProfile || showKeyVerification || showChatSafety || showSchedule || reminderFor != nil ||
        showReminders || showMessageTools || showForwardPicker || showLegacyMediaChoice || headerMenuOpen || showBulkDelete
    }
    /// The timeline is on screen and uncovered: what it shows counts as read.
    private var readable: Bool { conversationVisible && !coveredBySheet }
    /// The call screen covers the chat: nothing is read and the timeline holds still.
    @State private var callScreenCovering = false

    /// The saved text draft goes into the composer model before the composer's first
    /// render. It used to be written to @State in onAppear, which re-ran the whole chat
    /// once more on every open; the model is not observed here, so this costs no pass.
    private func restoreComposerDraft() {
        composerModel.restoreOnce { messagesRepository.chatValue(chatId: chat.chatId, name: "draft") ?? "" }
        composerModel.bindActivity(chatID: chat.chatId, repository: messagesRepository, realtime: realtime)
    }

    private var conversationContent: some View {
        restoreComposerDraft()
        return VStack(spacing: 0) {
            if messagesRepository.loadedHistoryChatId == chat.chatId {
                messageList
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity).background(chatBackground)
            }
        }
        .modifier(ChatVideoNoteStage(recorder: videoNoteRecorder))
        // Stable environment values: a fresh closure per body pass counted as a change
        // and re-ran every visible bubble on each keystroke/publish (timeline audit P0-1).
        .environment(\.pendingMediaLookup, environmentActions.bind(messagesRepository).pendingLookup)
        .environment(\.chatMessagesRepository, messagesRepository)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            // In-chat search owns the keyboard (Telegram-like): no composer floating over
            // the results, and the timeline ends right above the keyboard (report 0.3.25).
            if !inChatSearch {
                composer.background(selectedMessages.isEmpty && !showsGlassComposer ? UnyriSavedChatStyle.bottomColor : Color.clear)
                    .overlay(alignment: .topTrailing) {
                        if let hint = voiceHoldHint {
                            Text(hint).font(.footnote.weight(.medium)).foregroundStyle(UnyriColors.primaryText)
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(Color(white: 0.16), in: Capsule())
                                .offset(x: -10, y: -44)
                                .transition(.opacity.combined(with: .move(edge: .bottom)))
                                .allowsHitTesting(false)
                                .accessibilityIdentifier("voice-hold-hint")
                        }
                    }
            }
        }
        .background(chatBackground)
        .accessibilityHidden(showBulkDelete)
        .overlay {
            if showBulkDelete {
                GeometryReader { geometry in
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial).overlay(Color.black.opacity(0.25)).ignoresSafeArea()
                            .onTapGesture { cancelDeletion() }
                            .accessibilityHidden(true)
            // Compact card sized to its content (it was a fixed 300 pt box with an empty
            // half), a clear checkbox row naming the other person, solid red Delete.
            let count = pendingBulkDelete?.ids.count ?? 0
            let canDeleteEveryone = canDeleteForEveryone(pendingBulkDelete?.ids ?? [])
            let everyone = pendingBulkDelete?.everyone ?? false
            VStack(spacing: 16) {
                Image(systemName: "trash.fill").font(.system(size: 20, weight: .semibold)).foregroundStyle(Color(hex: 0xFF453A))
                    .frame(width: 48, height: 48).background(Circle().fill(Color(hex: 0xFF453A).opacity(0.14)))
                    .accessibilityHidden(true)
                VStack(spacing: 6) {
                    Text(UnyriDeletionText.title(count: count)).font(.system(size: 18, weight: .semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("chat-delete-selection-count")
                        .accessibilityValue(Text("\(count)"))
                    if !canDeleteEveryone {
                        Text(UnyriL10n.string("They will be deleted only for you.")).font(.system(size: 14))
                            .foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
                    }
                }
                if canDeleteEveryone {
                    Button {
                        UnyriHaptics.selection()
                        pendingBulkDelete?.everyone.toggle()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: everyone ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 22)).foregroundStyle(everyone ? Color(hex: 0xFF453A) : UnyriColors.secondaryText)
                            Text(String(format: UnyriL10n.string("Also delete for %@"),
                                        chat.otherUser?.visibleName ?? UnyriL10n.string("the other participant")))
                                .font(.system(size: 15)).foregroundStyle(UnyriColors.primaryText)
                                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 14).frame(minHeight: 48)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.06)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(everyone ? .isSelected : [])
                    .accessibilityIdentifier("chat-delete-everyone")
                }
                HStack(spacing: 10) {
                    Button { cancelDeletion() } label: {
                        Text("Cancel").font(.system(size: 16, weight: .medium)).foregroundStyle(UnyriColors.primaryText)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(Capsule().fill(Color.white.opacity(0.10)))
                    }.buttonStyle(.plain).accessibilityIdentifier("chat-delete-cancel")
                    Button {
                            guard let pending = pendingBulkDelete else { return }
                            guard pending.generation == auth.sessionGeneration, pending.owner == auth.currentUser?.id, pending.chatID == chat.chatId else { return }
                            guard !pending.everyone || canDeleteEveryone else {
                                showBulkDelete = false; pendingBulkDelete = nil
                                messagesRepository.reportError(UnyriL10n.string("The selected messages changed. Select them again and retry."))
                                return
                            }
                            guard messagesRepository.stopComposerRemainderForDeletion(chatId: pending.chatID,
                                albumIDs: pending.queuedAlbumIDs, clientMessageIds: pending.ids) else { return }
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

                    } label: {
                        Text("Delete").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(Capsule().fill(Color(hex: 0xFF453A)))
                    }.buttonStyle(.plain).accessibilityIdentifier("chat-delete-confirm")
                }
            }
            .padding(22)
            .frame(maxWidth: 340)
            .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Color(white: 0.12)))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Color.white.opacity(0.07), lineWidth: 0.5))
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
        .onAppear { UnyriAppBadge.clearDelivered(chatId: chat.chatId) }
        // Typing work still pending (the last keystrokes) runs as the chat goes away.
        .onDisappear { composerModel.flushActivity() }
        .task(id: inChatSearch ? inChatQuery : "\u{0}") {
            guard inChatSearch else { return }
            let term = inChatQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !term.isEmpty else { searchHits = []; searchHitIndex = 0; searchBusy = false; return }
            searchBusy = true
            do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            let found = (try? await messagesRepository.searchChatMessages(chatId: chat.chatId, query: term)) ?? []
            guard !Task.isCancelled, inChatSearch else { return }
            searchBusy = false
            searchHits = found.map(\.clientMessageId)
            searchHitIndex = 0
            if let first = searchHits.first { jumpRequest = first }
        }
        .onReceive(messagesRepository.directFiles?.actionErrors.receive(on: RunLoop.main).eraseToAnyPublisher() ?? Empty<String, Never>().eraseToAnyPublisher()) { message in
            messagesRepository.reportError(UnyriL10n.string(message))
        }
        .onChange(of: inChatSearch) { searching in
            // The search owns the keyboard and hides the composer: its height leaves the list.
            hostedTimeline.setBottomChrome(searching ? 0 : composerGeometry.size.height)
        }
        .onReceive(realtime.$state.removeDuplicates()) { state in
            // The first emission is the value the body already read: no re-render.
            let previous = realtimeStateSeen.value
            realtimeStateSeen.value = state
            guard let previous, previous != state else { return }
            realtimeStateChange = state
            // A reconnect syncs missed reactions: they appear without landing effects.
            UnyriReactionBursts.shared.quiet(1.5)
            if state == .connected {
                if messagesRepository.errorIsConnectivity { messagesRepository.dismissError() }
                if conversationVisible, let peer = chat.otherUser?.id {
                    realtime.liveDrafts.open(chat: chat.chatId, peer: peer)
                    prepareLiveDraft()
                }
            }
        }
        .sheet(isPresented: $showPeerProfile) { peerProfileSheet }
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
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOChatBindingChanged"))) { note in
            if note.object as? String == chat.chatId { refreshIdentityWarning() }
        }
        .performanceScreen(.chat)
        .background(UnyriChatBackSwipe(enabled: conversationVisible && selectedMessages.isEmpty &&
            !showMessageTools && !showChatSafety,
            // Leaving the chat stops its voice message (it kept playing with no control).
            onNavigationExit: { voicePlayer.stop(owner: chat.chatId) }).allowsHitTesting(false))
        .onAppear {
            PerformanceRecorder.shared.record(.chatOpen)
            if openClock.startedAt == nil { openClock.startedAt = ProcessInfo.processInfo.systemUptime }
            // The security banner is not required for the first frame: this lookup
            // enumerates Signal Keychain state through `hasPendingIdentity`
            // (`SecItemCopyMatching` over every stored item, synchronous securityd
            // XPC on the main thread). It is moved out of the chat-open update path
            // onto the next main-run-loop turn; see `scheduleIdentityWarningRefresh`.
            scheduleIdentityWarningRefresh()
            openReminderTarget()
            // Restore the same live reference, including imports still finishing offscreen.
            composerTraySlot.show(ChatComposerTrayMemory.shared.tray(auth: auth, repository: messagesRepository,
                chats: chatsRepository, chatID: chat.chatId, holder: composerTraySlot))
            restoreComposerDraft()
            // Direct offers can finish while this route is absent. Reconcile the
            // authoritative journal before presenting its cached pending members.
            if composerBatchRestored, composerBatch?.directSend == true { refreshComposerDirectBatch() }
            else { restoreComposerBatchIfNeeded() }
            if !composerRestored {
                if let value = messagesRepository.chatValue(chatId: chat.chatId, name: "reminders"), let data = value.data(using: .utf8) { reminders = (try? JSONDecoder().decode([String: Date].self, from: data)) ?? [:] }
                restoreMediaDrafts()
                // The text draft itself is restored before the first render (restoreComposerDraft).
                composerRestored = true
                if let value = messagesRepository.chatValue(chatId: chat.chatId, name: "voiceDraft"), let data = value.data(using: .utf8) {
                    voiceDraft = try? JSONDecoder().decode(VoiceDraft.self, from: data)
                }
            } else {
                restoreMediaDrafts(restoreFile: fileDraft == nil)
            }
        }
        .environment(\.unyriCancelDownload, environmentActions.cancelDownload)
        // A stable closure (target swapped each pass): an inline closure is a new
        // environment value every pass, which re-ran every visible media bubble.
        .environment(\.unyriSendEditedPhoto, environmentActions.sendEditedPhoto(perform: { image in
            // An edited photo joins this chat's composer tray (caption, review, Send),
            // exactly like a fresh camera capture.
            let id = UUID().uuidString.lowercased()
            let generation = auth.sessionGeneration
            Task { try? await prepareCameraCapture(.image(image), id: id, generation: generation) }
        }))
        .environment(\.unyriForwardMedia, environmentActions.forwardMedia(perform: { id in
            guard let message = messagesRepository.messages.first(where: { !$0.hiddenLocally && !$0.isDeletedForEveryone && $0.content.attachment?.attachmentId == id }) else { return }
            prepareForward([message])
        }))
        // Reuse the exact visible chronological timeline, including album
        // members. Server sequence can differ after deferred video uploads.
        // Built only when a viewer opens (stable closure, target swapped each pass).
        .modifier(ChatMediaEnvironment(player: voicePlayer, recorder: voiceRecorder,
            mediaItems: environmentActions.mediaItems(build: { conversationMediaItems })))
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
                    if messagesRepository.scheduleText(chatId: chat.chatId, otherUserId: chat.otherUser?.id, text: composerModel.text, due: scheduledDate) {
                        composerModel.setText(""); composerModel.ordinaryDraft = ""; messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: ""); showSchedule = false
                    }
                }.buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("schedule-confirm")
                Button("Cancel") { showSchedule = false }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("schedule-cancel")
                        }.padding(14)
                    }
                }.tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC)))
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
                if let reminderError { Text(verbatim: reminderError).font(.footnote) }
                Button("Save reminder") { Task { await scheduleReminder(item) } }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-save")
                Button("Cancel") { reminderFor = nil }
                    .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("reminder-cancel")
                        }.padding(14)
                    }
                }.tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC)))
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
                }.listStyle(.plain).tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC))).overlay {
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
                ChatComposerTextBinding(composer: composerModel, changes: composerModel.changes, set: updateComposerDraft) { caption in
                UnyriAttachmentGallery(cameraEnabled: !voiceRecorder.callInProgress,
                    camera: { attachmentChoice = "camera"; showAttachmentMenu = false },
                    library: { attachmentChoice = "photos"; showAttachmentMenu = false },
                    files: { attachmentChoice = "files"; showAttachmentMenu = false },
                    selected: { assets, intent, commit in
                        attachmentAssets = assets; gallerySendIntent = intent
                        attachmentSendsNow = commit == .sendNow
                        attachmentChoice = "assets"; showAttachmentMenu = false
                    }, selection: $gallerySelection, captures: galleryCaptures, sendingAllowed: composerSelectionRemaining > 0,
                    selectionLimit: max(1, composerSelectionRemaining),
                    caption: caption,
                    close: { UnyriHaptics.press(); showAttachmentMenu = false },
                    documents: { sources, commit in
                        attachmentDocuments = sources
                        attachmentSendsNow = commit == .sendNow
                        attachmentChoice = "documents"; showAttachmentMenu = false
                    },
                    startOnFiles: attachmentStartsOnFiles)
                }
            }
            .presentationDetents([.fraction(UnyriAttachmentPickerStyle.height / 100)]).presentationDragIndicator(.hidden)
            .modifier(AttachmentSheetGlass())
            .interactiveDismissDisabled(true)
        }
        .alert("Direct transfer", isPresented: $showDirectTransferInfo) { Button("OK", role: .cancel) {} }
            message: { Text(LocalizedStringKey(DirectSendPreference.explanation)) }
        .alert("Send directly?", isPresented: $showDirectOffer) {
            Button("Send directly") { directPreferenceBinding.wrappedValue = true; sendComposerAttachments() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This file is over 100 MB. It can go straight from this phone to the other phone, up to 64 GB, without cloud storage. The recipient has to accept it.") }
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
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: max(1, composerSelectionRemaining), selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), preferredItemEncoding: MediaGallerySendChoice.saved() == .original ? .current : .automatic,
            // With the library, itemIdentifier is the asset id: a photo picked twice, or already
            // added from the in-app gallery, is found again, and the tray can draw its thumbnail.
            photoLibrary: .shared())
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            addComposerPhotos(items)
        }
        .sheet(isPresented: $showFileImporter) {
            UnyriFolderAttachmentPicker(capacity: composerSelectionRemaining, add: { documents in
                // The bounded folder selection retains root grants inside each existing DocumentSource.
                let context = directSelectionContext ?? currentDirectSelection
                showFileImporter = false
                composerTray.imports.start { _ = await addComposerFiles(documents, context: context, flies: true) }
            }, close: { showFileImporter = false })
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
        .onChange(of: messagesRepository.composerDirectRevisions[chat.chatId, default: 0]) { _ in refreshComposerDirectBatch() }
        .onChange(of: auth.sessionGeneration) { _ in
            showFileImporter = false
            clearComposerAttachments(); directFileSelection = nil
        }
        .fullScreenCover(isPresented: $showCamera, onDismiss: {
            if let claim = cameraClaim { NonCallCaptureOwnership.stop(claim); cameraClaim = nil }
            if cameraNeedsRetry { cameraNeedsRetry = false; showPhotoPreview = true }
            if cameraFocusesComposer { cameraFocusesComposer = false; composerModel.focus() }
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
        .chatTopBar {
            VStack(spacing: 0) {
                if selectedMessages.isEmpty { if inChatSearch { chatSearchBar } else { chatHeader } } else {
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
                                Text(pinnedPreviewText(messagesRepository.pinnedPreviews(chatId: chat.chatId).messages[id])).lineLimit(1)
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
                            Button("Retry") { messagesRepository.retryFailedAction(action, otherUserId: chat.otherUser?.id) }
                        }.font(.subheadline).buttonStyle(.borderless)
                    }.padding(12).background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 12)
                }
                if let error = messagesRepository.errorMessage, !messagesRepository.errorIsConnectivity {
                    HStack(spacing: 8) {
                        Text(verbatim: error)
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
            guard height > 0 else { return }
            // Straight to the timeline's insets: no state, no chat pass.
            hostedTimeline.setTopChrome(height)
            // Read by the root's message banner when it shows (not observed, not published).
            if chatsRepository.notificationHeaderHeight != Double(height) { chatsRepository.notificationHeaderHeight = Double(height) }
        }
    }

    var body: some View {
        conversationHeader
        .accessibilityHidden(headerMenuOpen)
        .overlay { if headerMenuOpen { headerMenuOverlay.transition(.opacity) } }
        .onDisappear { headerMenuOpen = false }
        .task(id: headerAvatarKey) {
            let key = headerAvatarKey
            guard let other = currentHeaderPeer else { headerAvatar = nil; return }
            let resolved = await usersRepository.avatarURL(id: other.id, avatarUrl: other.avatarUrl, avatarVersion: other.avatarVersion)
            guard !Task.isCancelled, key == headerAvatarKey else { return }
            headerAvatar = resolved
        }
        .task(id: presenceTaskKey) {
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
        .task(id: historyFallbackTaskKey) {
            await ChatHistoryFallback.run(active: scenePhase == .active, visible: conversationVisible,
                                          connected: realtimeState == .connected) {
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
            if messagesRepository.loadedHistoryChatId != chat.chatId {
                messagesRepository.preloadChat(chat.chatId)
            }
            if conversationVisible, let peer = chat.otherUser?.id { realtime.liveDrafts.open(chat: chat.chatId, peer: peer) }
            UnyriReactionBursts.shared.attach(messagesRepository)
        }
        .onDisappear {
            if !showCamera, let claim = cameraClaim { NonCallCaptureOwnership.stop(claim) }
            if let claim = pendingVoiceClaim { NonCallCaptureOwnership.stop(claim) }
            // Covering/dismissing a system picker is not a navigation exit.
            // The owned native route anchor handles actual completed pops.
            // The read goes in while the chat is still visible: setChatVisible(false)
            // below makes the repository refuse it.
            readTracker.task?.cancel()
            readTracker.task = nil
            submitVisibleRead()
            realtime.unsubscribePresence(owner: presenceOwner)
            mediaRestoreToken = UUID()
            mediaPreviewTask?.cancel(); mediaPreviewTask = nil
            Task { await messagesRepository.flushDraftValues() }
            realtime.liveDrafts.close(chat: chat.chatId)
            if recordingVideoNote { cancelRecording() }
            if isRecording { voiceRecorder.interrupt() }
            UnyriReactionBursts.shared.cancelAll()
            messagesRepository.setChatVisible(chat.chatId, visible: false)
        }
        .onReceive(chatsRepository.$deletedChatIDs.removeDuplicates()) { ids in
            // Like onChange: the first emission is the state the screen opened with.
            guard deletedChatsSeen else { deletedChatsSeen = true; return }
            if ids.contains(chat.chatId) { clearComposerAttachments(); dismissChat() }
        }
        .onReceive(chatsRepository.$preferenceCache.map { [chatId = chat.chatId] in $0[chatId] }.removeDuplicates()) { entry in
            guard preferenceSeen.update(entry) else { return }
            preferenceRevision &+= 1
        }
        .onChange(of: conversationVisible) { visible in
            if visible { UnyriAppBadge.clearDelivered(chatId: chat.chatId) }
            if !visible { Task { await messagesRepository.flushDraftValues() } }
            if visible, let peer = chat.otherUser?.id { realtime.liveDrafts.open(chat: chat.chatId, peer: peer) } else { realtime.liveDrafts.close(chat: chat.chatId) }
            messagesRepository.setChatVisible(chat.chatId, visible: visible)
        }
        .task(id: scenePhase) {
            guard scenePhase == .active, let peer = chat.otherUser.flatMap({ UUID(uuidString: $0.id) }) else { return }
            await callsHistory.loadCachedHistory()
            while !Task.isCancelled {
                if conversationVisible {
                    do {
                        let cursor = try await callsHistory.refreshHistory(peer: peer)
                        if !callHistoryLoaded { nextCallCursor = cursor; callHistoryLoaded = true }
                    } catch { if Task.isCancelled { return } }
                }
                // Socket call-history events already reconcile CallsRepository. While
                // connected this is only a safety net; a 10 s loop issued several REST
                // requests plus main-thread SQLite work every cycle for an open chat.
                let pollInterval: UInt64 = realtime.state == .connected ? 60_000_000_000 : 10_000_000_000
                do { try await Task.sleep(nanoseconds: pollInterval) } catch { return }
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
        // Build the timeline only after the local snapshot is ready, so its initial
        // position is not taken from an empty array.
        if messagesRepository.loadedHistoryChatId == chat.chatId {
            hostedMessageList
        } else {
            chatBackground.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var chatHeader: some View {
        let peer = currentHeaderPeer
        let isSaved = chat.chatType == "saved"
        return ChatHeaderBar(
            chatId: chat.chatId,
            title: title,
            isSaved: isSaved,
            presencePeer: chat.otherUser,
            avatarURL: isSaved ? nil : (headerAvatar ?? usersRepository.cachedAvatarURL(id: peer?.id ?? "", avatarUrl: peer?.avatarUrl, avatarVersion: peer?.avatarVersion ?? 0)),
            blocked: currentPreferenceChat.isBlocked == true,
            allowsPeerActivity: allowsPeerActivity,
            connectivityError: messagesRepository.errorIsConnectivity,
            onBack: { dismissChat() },
            onCall: chat.otherUser.map { other in {
                composerModel.blur()
                if currentPreferenceChat.isBlocked == true { showChatSafety = true }
                else { callKit.startOutgoing(remoteUserId: other.id, displayName: other.visibleName, isVideo: false) }
            } },
            onMenu: { composerModel.blur(); withAnimation(.easeOut(duration: 0.16)) { headerMenuOpen = true } },
            onProfile: chat.otherUser == nil ? nil : { composerModel.blur(); showPeerProfile = true },
            legacyIdentityServer: usersRepository.legacyIdentityServer)
    }
    /// Closes the profile sheet first; another sheet can only present after it is gone.
    private func fromProfile(_ action: @escaping () -> Void) {
        showPeerProfile = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: action)
    }
    private func profileAction(_ symbol: String, _ title: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 20, weight: .medium)).frame(height: 24)
                Text(UnyriL10n.string(title)).font(.caption).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(UnyriColors.primaryText)
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.4)
    }
    private func profileRow(_ symbol: String, _ title: String, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.system(size: 18)).frame(width: 26)
                Text(UnyriL10n.string(title)).font(.body)
                Spacer(minLength: 0)
            }
            .foregroundStyle(destructive ? UnyriColors.danger : UnyriColors.primaryText)
            .padding(.horizontal, 16).frame(minHeight: 50).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    private var peerProfileSheet: some View {
        let peer = currentHeaderPeer
        let muted = currentPreferenceChat.isMuted == true
        let blocked = currentPreferenceChat.isBlocked == true
        return NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    UserAvatar(url: headerAvatar ?? usersRepository.cachedAvatarURL(id: peer?.id ?? "", avatarUrl: peer?.avatarUrl, avatarVersion: peer?.avatarVersion ?? 0),
                               name: title, size: 96, vividFallback: !appColorStyle.isMonochrome)
                        .padding(.top, 8).accessibilityHidden(true)
                    VStack(spacing: 4) {
                        Text(title).font(.title2.weight(.semibold)).foregroundStyle(UnyriColors.primaryText).multilineTextAlignment(.center)
                        if let peer { PeerPublicIdentityView(userID: peer.id) }
                    }
                    HStack(spacing: 10) {
                        if let other = chat.otherUser {
                            profileAction("phone", "Call", enabled: !blocked) {
                                fromProfile { callKit.startOutgoing(remoteUserId: other.id, displayName: other.visibleName, isVideo: false) }
                            }
                        }
                        profileAction("magnifyingglass", "Search") { fromProfile { startInChatSearch() } }
                        profileAction(muted ? "bell" : "bell.slash", muted ? "Unmute" : "Mute", enabled: !preferenceBusy) {
                            fromProfile { chatSafetyAction = "notifications"; showChatSafety = true }
                        }
                    }
                    if let peer, chat.chatType == "direct" {
                        UnyriContactToggleRow(store: usersRepository.contacts, user: peer)
                    }
                    VStack(spacing: 0) {
                        profileRow("photo.on.rectangle", "Media") { fromProfile { showMediaGallery = true } }
                        Divider().padding(.leading, 56)
                        profileRow("lock.shield", "Verify security keys") { fromProfile { showKeyVerification = true } }
                    }
                    .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14))
                    VStack(spacing: 0) {
                        profileRow("hand.raised", blocked ? "Unblock contact" : "Block contact", destructive: !blocked) {
                            fromProfile { chatSafetyAction = "block"; showChatSafety = true }
                        }
                        Divider().padding(.leading, 56)
                        profileRow("exclamationmark.shield", "Report contact", destructive: true) {
                            fromProfile { chatSafetyAction = "report"; showReportForm = true; preferenceStatus = nil; showChatSafety = true }
                        }
                    }
                    .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14))
                }
                .padding(.horizontal, 20).padding(.bottom, 24)
            }
            .unyriScreenBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UnyriL10n.string("Close")) { showPeerProfile = false }.accessibilityIdentifier("peer-profile-close") } }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func startInChatSearch() {
        composerModel.blur()
        inChatQuery = ""; searchHits = []; searchHitIndex = 0
        inChatSearch = true
        DispatchQueue.main.async { searchFocused = true }
    }
    private func endInChatSearch() {
        searchFocused = false
        inChatSearch = false; inChatQuery = ""; searchHits = []; searchHitIndex = 0; searchBusy = false
    }
    /// older: towards the start of the chat (hits are newest first).
    private func stepSearch(older: Bool) {
        guard !searchHits.isEmpty else { return }
        let next = searchHitIndex + (older ? 1 : -1)
        guard searchHits.indices.contains(next) else { return }
        searchFocused = false
        searchHitIndex = next
        returningToAnchor = true // keep "return" pointing to where the reader was before searching
        jumpRequest = searchHits[next]
    }
    private var chatSearchBar: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(UnyriColors.secondaryText).accessibilityHidden(true)
                    TextField(UnyriL10n.string("Search messages"), text: $inChatQuery)
                        .focused($searchFocused).submitLabel(.search).autocorrectionDisabled()
                        .foregroundStyle(UnyriColors.primaryText)
                        .onSubmit { searchFocused = false }
                        .accessibilityIdentifier("chat-inline-search-field")
                    if searchBusy { ProgressView().controlSize(.small) }
                    else if !inChatQuery.isEmpty {
                        Button { inChatQuery = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(UnyriColors.secondaryText).frame(width: 30, height: 44)
                        }.buttonStyle(.plain).accessibilityLabel("Clear")
                    }
                }
                .padding(.leading, 14).padding(.trailing, 6).frame(minHeight: 44)
                .modifier(ChatHeaderGlass(solid: false))
                Button(UnyriL10n.string("Cancel")) { endInChatSearch() }
                    .font(.body).foregroundStyle(UnyriColors.primaryText).frame(minHeight: 44)
                    .accessibilityIdentifier("chat-inline-search-cancel")
            }
            if !inChatQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !searchBusy {
                HStack(spacing: 4) {
                    Text(searchHits.isEmpty ? UnyriL10n.string("No results")
                         : String(format: UnyriL10n.string("%d of %d"), searchHitIndex + 1, searchHits.count))
                        .font(.footnote.monospacedDigit()).foregroundStyle(UnyriColors.secondaryText)
                        .padding(.leading, 14)
                    Spacer(minLength: 0)
                    Button { stepSearch(older: true) } label: {
                        Image(systemName: "chevron.up").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 36)
                    }.disabled(searchHitIndex + 1 >= searchHits.count).accessibilityLabel("Older result")
                    Button { stepSearch(older: false) } label: {
                        Image(systemName: "chevron.down").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 36)
                    }.disabled(searchHitIndex == 0).accessibilityLabel("Newer result")
                }
                .buttonStyle(.plain).foregroundStyle(UnyriColors.primaryText)
                .frame(minHeight: 36).modifier(ChatHeaderGlass(solid: false))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .frame(minHeight: UnyriSavedChatStyle.headerHeight)
        .background {
            LinearGradient(colors: [Color.black.opacity(0.82), Color.black.opacity(0)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top).allowsHitTesting(false)
        }
    }
    /// One quiet row: a plain glyph, the title, nothing decorative (Telegram-like
    /// context menu); destructive actions are red.
    private func headerMenuRow(_ title: LocalizedStringKey, symbol: String, destructive: Bool = false,
                               enabled: Bool = true, action: @escaping () -> Void) -> some View {
        let ink = destructive ? UnyriColors.danger : UnyriColors.primaryText
        return Button {
            headerMenuOpen = false
            action()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.system(size: 18)).foregroundColor(ink).frame(width: 24)
                Text(title).font(.system(size: 16)).foregroundColor(ink).lineLimit(1).minimumScaleFactor(0.85)
                Spacer(minLength: 0)
            }.padding(.horizontal, 16).frame(minHeight: 46).contentShape(Rectangle())
        }.buttonStyle(UnyriMenuRowStyle()).disabled(!enabled).opacity(enabled ? 1 : 0.4)
    }
    private var headerMenuDivider: some View {
        Rectangle().fill(UnyriColors.primaryText.opacity(0.08)).frame(height: 0.5).padding(.vertical, 4)
    }
    private var headerMenuOverlay: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topTrailing) {
                UnyriChatMenuBackdrop { headerMenuOpen = false }
                ViewThatFits(in: .vertical) {
                    headerMenuItems
                    ScrollView { headerMenuItems }
                }
                .frame(width: min(300, max(100, geometry.size.width - 24)))
                .frame(maxHeight: min(620, max(100, geometry.size.height - 64)), alignment: .top)
                .fixedSize(horizontal: false, vertical: true)
                .modifier(UnyriAttachGlass(shape: RoundedRectangle(cornerRadius: 22, style: .continuous)))
                .shadow(color: .black.opacity(0.35), radius: 24, y: 8)
                .padding(.top, min(56, geometry.size.height / 8)).padding(.trailing, 12)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("chat-header-menu")
            }
        }.accessibilityAction(.escape) { headerMenuOpen = false }
    }

    private var headerMenuItems: some View {
                    VStack(spacing: 0) {
                        headerMenuRow("Search messages", symbol: "magnifyingglass") { startInChatSearch() }
                        headerMenuRow("Media", symbol: "photo.on.rectangle") { showMediaGallery = true }
                            .accessibilityIdentifier("chat-menu-media")
                        headerMenuRow("Pinned messages", symbol: "pin") { onlyPinned = true; showMessageTools = true }
                        headerMenuRow("Reminders", symbol: "bookmark") { showReminders = true }
                        if let other = chat.otherUser, currentPreferenceChat.isBlocked != true {
                            headerMenuRow("Video call", symbol: "video") {
                                composerModel.blur()
                                callKit.startOutgoing(remoteUserId: other.id, displayName: other.visibleName, isVideo: true)
                            }
                        }
                        if editingMessage == nil && replyTarget == nil {
                            ChatComposerHasText(blank: composerModel.blankState) {
                                headerMenuRow("Schedule message", symbol: "clock") { scheduledDate = Date().addingTimeInterval(3600); showSchedule = true }
                            }
                        }
                        if let recovered = messagesRepository.chatValue(chatId: chat.chatId, name: "recoveredDraft") {
                            headerMenuRow("Recovered draft", symbol: "arrow.uturn.backward") {
                                let merged = composerModel.ordinaryDraft.isEmpty ? recovered : composerModel.ordinaryDraft + "\n" + recovered
                                composerModel.setText(merged)
                                composerModel.ordinaryDraft = merged
                                messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: merged)
                                messagesRepository.saveChatValue(chatId: chat.chatId, name: "recoveredDraft", value: nil)
                            }
                        }
                        if allowsPeerActivity {
                            headerMenuDivider
                            // A switch, not a checkmark: it changes a setting and keeps the menu open.
                            Toggle(isOn: $activityEnabled) {
                                HStack(spacing: 14) {
                                    Image(systemName: "keyboard").font(.system(size: 17)).frame(width: 24)
                                    Text("Show that I'm typing").font(.system(size: 16)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .toggleStyle(.switch).tint(Color(white: 0.55))
                            .foregroundColor(UnyriColors.primaryText)
                            .padding(.horizontal, 16).frame(minHeight: 46)
                            .accessibilityHint(Text("Share typing and recording status"))
                        }
                        if chat.otherUser != nil {
                            headerMenuDivider
                            headerMenuRow(currentPreferenceChat.isMuted == true ? "Unmute notifications" : "Mute notifications",
                                          symbol: currentPreferenceChat.isMuted == true ? "bell" : "bell.slash", enabled: !preferenceBusy) {
                                chatSafetyAction = "notifications"; showChatSafety = true
                            }
                            headerMenuRow(currentPreferenceChat.isBlocked == true ? "Unblock" : "Block", symbol: "hand.raised", destructive: currentPreferenceChat.isBlocked != true) {
                                chatSafetyAction = "block"; showChatSafety = true
                            }
                            headerMenuRow("Report", symbol: "exclamationmark.bubble", destructive: true) {
                                chatSafetyAction = "report"; showReportForm = true; preferenceStatus = nil; showChatSafety = true
                            }
                        }
                    }.padding(.vertical, 6)
    }

    @State private var showDecryptionFailures = false

    @ViewBuilder private func albumCell(_ message: DecryptedChatMessage, firstInGroup: Bool, inAlbum: Bool = false, actionMembers: [DecryptedChatMessage] = [], rowID: String? = nil) -> some View {
        let presentationID = rowID ?? message.id
                        let unavailableHistory = isUnavailableHistory(message, cutoff: auth.historyBeforeSequence ?? 0)
                        Group {
                        if unavailableHistory && !showDecryptionFailures {
                            EmptyView()
                        } else if message.status == .decryptFailed && !showDecryptionFailures {
                            EmptyView()
                        } else {
                        messageBubble(message, shareAlbumPreview: inAlbum, actionMembers: actionMembers).equatable().allowsHitTesting(selectedMessages.isEmpty)
                        }
                        }
                        .padding(.top, inAlbum ? 0 : firstInGroup ? appearance.groupGap : appearance.gap)
                        .id(presentationID)
                        // Found/jumped-to message: a soft glow blooms behind the bubble on its
                        // side and the bubble gives one gentle bounce (no full-width strip).
                        .modifier(UnyriFoundMessageGlow(active: highlightedMessage == message.clientMessageId,
                                                        trailing: message.senderId == auth.currentUser?.id))
                        .modifier(UnyriMessageSelectionTap(action: selectedMessages.isEmpty || inAlbum ? nil : { [actions = bubbleActions] () -> Void in
                            guard let screen = actions.screen else { return }
                            screen.toggleMessageSelection(message.clientMessageId)
                        }))
                        .transition(.identity)
    }

    /// One immutable value snapshot per row construction; actions share this reference.
    @MainActor private final class ChatBubbleActionSnapshot {
        let screen: ChatScreen
        init(_ screen: ChatScreen) { self.screen = screen }
    }
    /// The snapshot shared by every row built from one timeline body pass. Cleared on
    /// disappear: snapshot -> screen copy -> @State storage -> this cache is a cycle.
    @MainActor private final class ChatBubbleActionCache {
        var snapshot: ChatBubbleActionSnapshot?
        /// Read by row lookups that run inside bubble bodies (cached path, voice state),
        /// so those calls do not copy the screen.
        private(set) weak var repository: MessagesRepository?
        private(set) weak var player: VoicePlayerController?
        func refresh(_ screen: ChatScreen) -> Bool {
            snapshot = ChatBubbleActionSnapshot(screen)
            repository = screen.messagesRepository
            player = screen.voicePlayer
            return true
        }
        /// The latest screen, resolved when an action runs: an equatable row built in an
        /// earlier pass keeps this cache, not a screen copy, and acts on the current screen.
        var screen: ChatScreen? { snapshot?.screen }
    }

    /// Values every message row of one timeline body pass shares, computed once per pass
    /// instead of once per row.
    private struct TimelineRowPassContext {
        let ownerID: String?
        let generation: UInt64
        let chatID: String
        let cutoff: Int64
        let showsFailures: Bool
        let selection: Set<String>
        let highlighted: String?
        let pinned: Set<String>
        let replyPeerName: String
        /// The normalized quick reaction ("" when none is configured).
        let quickReaction: String
    }

    private var timelineRowPassContext: TimelineRowPassContext {
        TimelineRowPassContext(ownerID: auth.currentUser?.id, generation: auth.sessionGeneration, chatID: chat.chatId,
            cutoff: auth.historyBeforeSequence ?? 0, showsFailures: showDecryptionFailures,
            selection: selectedMessages, highlighted: highlightedMessage, pinned: pinnedMessages,
            replyPeerName: chat.otherUser?.visibleName ?? UnyriL10n.string("Reply"),
            quickReaction: UnyriQuickReactionPolicy.normalize(quickReaction))
    }

    /// Everything a message row outside an album shows, as plain values. An equal model
    /// means the row skips its body: no MessageBubble (with its ~25 closures) is built.
    private struct MessageRowModel: Equatable {
        let message: DecryptedChatMessage
        let isMine: Bool
        let currentUserId: String?
        let scheduledAt: Date?
        let isPinned: Bool
        let reactions: [ReactionSummary]
        let replyPeerName: String
        let selecting: Bool
        let selected: Bool
        /// The text is shortened in the bubble, so the menu offers "Show full text".
        let fullTextBounded: Bool
        /// The double-tap reaction when it is available for this message.
        let quickReaction: String?
        let generation: UInt64
        let chatID: String
    }

    /// `MessageTextPolicy.isPresentationBounded` scans the whole text; cached per message
    /// id and text so an unchanged row never rescans it.
    private final class ChatRowTextBounds {
        private var entries: [String: (text: String, bounded: Bool)] = [:]
        func bounded(id: String, text: String) -> Bool {
            if let entry = entries[id], entry.text == text { return entry.bounded }
            let bounded = MessageTextPolicy.isPresentationBounded(text)
            entries[id] = (text, bounded)
            return bounded
        }
    }

    /// A message row outside an album. Used with `.equatable()`: while its model is
    /// unchanged (most rows on most passes: keystrokes, unrelated repository publishes,
    /// geometry passes) SwiftUI skips this body. Its actions resolve the current screen
    /// through the shared cache when they run.
    private struct MessageRow: View, Equatable {
        let model: MessageRowModel
        let actions: ChatBubbleActionCache
        static func == (lhs: MessageRow, rhs: MessageRow) -> Bool {
            lhs.model == rhs.model && lhs.actions === rhs.actions
        }
        var body: some View {
            ChatScreen.rowBubble(model, actions: actions).equatable().allowsHitTesting(!model.selecting)
        }
    }

    /// The bubble `messageBubble(_:)` builds for a message outside an album (same inputs
    /// and actions), from a row model and without capturing the screen.
    private static func rowBubble(_ model: MessageRowModel, actions: ChatBubbleActionCache) -> MessageBubble {
        let message = model.message
        let actionGeneration = model.generation
        let actionOwner = model.currentUserId
        let actionChatID = model.chatID
        let ids = [message.clientMessageId]
        let group = [message]
        func selectionItem(_ value: DecryptedChatMessage) -> UnyriChatAlbumSelection.Item {
            .init(id: value.clientMessageId, chat: value.chatId, sender: value.senderId, album: value.content.albumId)
        }
        /// The screen, when the session and chat that built this row are still current.
        func current() -> ChatScreen? {
            guard let screen = actions.screen, screen.auth.sessionGeneration == actionGeneration,
                  screen.auth.currentUser?.id == actionOwner, message.chatId == actionChatID else { return nil }
            return screen
        }
        return MessageBubble(
            message: message,
            scheduledAt: model.scheduledAt,
            isPinned: model.isPinned,
            isMine: model.isMine,
            currentUserId: model.currentUserId,
            replyPeerName: model.replyPeerName,
            reactions: model.reactions,
            onRetry: {
                guard let screen = current() else { return }
                let failed = group.filter { $0.senderId == actionOwner && $0.status == .failed }
                screen.refreshIdentityWarning()
                if screen.identityNeedsReview, let first = failed.first { screen.retry(first); return }
                for member in failed { screen.retry(member) }
            },
            onReply: { actions.screen?.beginReply(message) },
            onJumpQuote: {
                guard let target = message.targetMessageId, let screen = actions.screen else { return }
                screen.jumpRequest = target
            },
            onCopy: { UIPasteboard.general.string = DirectFilePreviewText.display(text: message.content.text, offer: message.content.directFile) ?? message.content.text ?? message.displayText },
            onCopyMedia: { current()?.copyMediaMessage(message.clientMessageId) },
            onForward: { current()?.prepareForward(group) },
            onSave: {
                guard let screen = actions.screen else { return }
                Task { await screen.saveMessages(group) }
            },
            onSelect: {
                guard let screen = current() else { return }
                UnyriHaptics.selection(); screen.composerModel.blur(); screen.selectedMessages.formUnion(ids)
            },
            onPin: { actions.screen?.togglePin(message.clientMessageId) },
            onRemind: {
                guard let screen = actions.screen else { return }
                screen.reminderDate = Date().addingTimeInterval(3600); screen.reminderError = nil; screen.reminderFor = message
            },
            onEdit: { actions.screen?.beginEdit(message) },
            onDeleteForEveryone: { current()?.deleteForEveryone(message) },
            onDeleteForMe: {
                guard let screen = current() else { return }
                if message.senderId == actionOwner, message.serverId == nil,
                   message.status == .pending || message.status == .failed {
                    let repository = screen.messagesRepository
                    // Capture only this displayed group's current unsent IDs.
                    // The repository rechecks ownership/status at the SQL mutation.
                    let pending = Set(group.compactMap { member -> String? in
                        guard let live = repository.localMessage(id: member.clientMessageId),
                              live.senderId == actionOwner, live.chatId == actionChatID,
                              live.serverId == nil, !live.hiddenLocally, !live.isDeletedForEveryone,
                              live.status == .pending || live.status == .failed else { return nil }
                        return live.clientMessageId
                    })
                    let captured = UnyriChatAlbumSelection.pendingMembers(of: selectionItem(message),
                        rows: [group.map(selectionItem)], pendingIDs: pending)
                    guard repository.stopComposerRemainderForDeletion(chatId: actionChatID,
                        albumIDs: [], clientMessageIds: ids) else { return }
                    repository.cancelUnsentMessages(chatId: actionChatID, clientMessageIds: captured)
                } else { screen.deleteForMe(message) }
            },
            onReact: { emoji in actions.screen?.react(message, emoji) },
            onQuickReaction: model.quickReaction.map { emoji in { () -> Void in
                guard let screen = actions.screen else { return }
                screen.sendQuickReaction(message, emoji: emoji)
            } },
            onRemoveReaction: { actions.screen?.removeReaction(message) },
            onDownloadAttachment: { id in await actions.repository?.downloadAttachment(attachmentId: id, origin: .messageTap) },
            onCachedLocalPath: { id in actions.repository?.cachedLocalPath(attachmentId: id) },
            isVoicePlaying: { id in actions.player?.state.playingAttachmentId == id },
            voicePositionMs: { id in
                guard let player = actions.player else { return 0 }
                return player.state.playingAttachmentId == id || player.state.pausedAttachmentId == id ? player.state.positionMs : 0
            },
            onToggleVoice: { id, path in
                UnyriHaptics.press()
                actions.player?.togglePlay(attachmentId: id, fileURL: URL(fileURLWithPath: path), owner: actionChatID)
            },
            selecting: model.selecting, selected: model.selected,
            onShowFullText: model.fullTextBounded ? {
                guard let screen = current(), let live = screen.messagesRepository.localMessage(id: message.clientMessageId),
                      live.chatId == actionChatID, !live.hiddenLocally, !live.isDeletedForEveryone,
                      live.status != .decryptFailed, live.status != .identityChanged,
                      let raw = DirectFilePreviewText.display(text: live.content.text, offer: live.content.directFile) ?? live.content.text else { return }
                screen.composerModel.blur()
                screen.fullTextRequest = FullTextRequest(text: raw, generation: actionGeneration, owner: actionOwner)
            } : nil)
    }

    @State private var rowTextBounds = ChatRowTextBounds()

    /// The value model of a message row and whether it hides.
    private func messageRowModel(_ message: DecryptedChatMessage, context: TimelineRowPassContext)
        -> (model: MessageRowModel, hidden: Bool) {
        let unavailableHistory = isUnavailableHistory(message, cutoff: context.cutoff)
        let isMine = message.senderId == context.ownerID
        let selecting = !context.selection.isEmpty
        let quickEligible = !context.quickReaction.isEmpty && UnyriQuickReactionPolicy.eligible(status: message.status.rawValue,
            serverId: message.serverId, deleted: message.isDeletedForEveryone || message.hiddenLocally, selecting: selecting)
        // Unavailable history and decryption failures stay out while failures are hidden
        // (the same empty Group the row had before).
        let hidden = !context.showsFailures && (unavailableHistory || message.status == .decryptFailed)
        let model = MessageRowModel(message: message,
            isMine: isMine, currentUserId: context.ownerID,
            scheduledAt: messagesRepository.scheduledDate(chatId: context.chatID, id: message.clientMessageId),
            isPinned: context.pinned.contains(message.clientMessageId),
            reactions: messagesRepository.reactions(forMessageServerId: message.serverId),
            replyPeerName: context.replyPeerName,
            selecting: selecting, selected: context.selection.contains(message.clientMessageId),
            fullTextBounded: rowTextBounds.bounded(id: message.clientMessageId, text: message.content.text ?? ""),
            quickReaction: quickEligible ? context.quickReaction : nil,
            generation: context.generation, chatID: context.chatID)
        return (model, hidden)
    }

    /// A message outside an album: an equatable row from a value model and the shared action cache.
    private func messageRow(_ message: DecryptedChatMessage, rowID: String, firstInGroup: Bool, context: TimelineRowPassContext) -> some View {
        let (model, hidden) = messageRowModel(message, context: context)
        let isMine = message.senderId == context.ownerID
        let selecting = !context.selection.isEmpty
        return Group {
            if hidden {
                EmptyView()
            } else {
                MessageRow(model: model, actions: bubbleActions).equatable()
            }
        }
            .padding(.top, firstInGroup ? appearance.groupGap : appearance.gap)
            .id(rowID)
            // Found/jumped-to message: a soft glow blooms behind the bubble on its
            // side and the bubble gives one gentle bounce (no full-width strip).
            .modifier(UnyriFoundMessageGlow(active: context.highlighted == message.clientMessageId, trailing: isMine))
            .modifier(UnyriMessageSelectionTap(action: selecting ? { [actions = bubbleActions] () -> Void in
                guard let screen = actions.screen else { return }
                screen.toggleMessageSelection(message.clientMessageId)
            } : nil))
            .transition(.identity)
    }

    private func toggleMessageSelection(_ id: String) {
        UnyriHaptics.selection()
        if selectedMessages.contains(id) { selectedMessages.remove(id) } else { selectedMessages.insert(id) }
    }

    private func toggleCallSelection(_ call: CallDTO) {
        UnyriHaptics.selection()
        composerModel.blur()
        let key = "call:\(call.id.uuidString)"
        if selectedMessages.contains(key) { selectedMessages.remove(key) } else { selectedMessages.insert(key) }
    }

    private func deleteCallRecord(_ call: CallDTO, everyone: Bool) {
        Task {
            do { try await callsHistory.deleteHistory(id: call.id, everyone: everyone) }
            catch { messagesRepository.reportError(UnyriL10n.string("Could not delete the call record. Check your connection and server version, then try again.")) }
        }
    }

    private func callBack(_ other: PublicUser, call: CallDTO) {
        composerModel.blur()
        if currentPreferenceChat.isBlocked == true { showChatSafety = true }
        else { callKit.startOutgoing(remoteUserId: other.id, displayName: other.visibleName, isVideo: call.mediaKind == .video) }
    }

    private func messageBubble(_ message: DecryptedChatMessage, content: AnyView? = nil, captionOnly: Bool = false, shareAlbumPreview: Bool = false, actionMembers: [DecryptedChatMessage] = []) -> MessageBubble {
        let available = actionMembers.filter { !$0.hiddenLocally && !$0.isDeletedForEveryone && $0.status != .decryptFailed && $0.status != .identityChanged }
        func selectionItem(_ value: DecryptedChatMessage) -> UnyriChatAlbumSelection.Item {
            .init(id: value.clientMessageId, chat: value.chatId, sender: value.senderId, album: value.content.albumId)
        }
        let ids = UnyriChatAlbumSelection.members(of: selectionItem(message), rows: [available.map(selectionItem)])
        let group = ids.count > 1 ? available.filter { ids.contains($0.clientMessageId) } : [message]
        let queuedAlbumID = ids.count > 1 && message.content.directFile != nil ? message.content.albumId : nil
        let actions = bubbleActions.snapshot ?? ChatBubbleActionSnapshot(self)
        let repository = messagesRepository
        let player = voicePlayer
        let actionAuth = auth
        let actionChatID = chat.chatId
        let actionGeneration = actionAuth.sessionGeneration
        let actionOwner = actionAuth.currentUser?.id
        func actionIsCurrent() -> Bool {
            actionAuth.sessionGeneration == actionGeneration && actionAuth.currentUser?.id == actionOwner && message.chatId == actionChatID
        }
        return MessageBubble(
                            message: message,
                            scheduledAt: messagesRepository.scheduledDate(chatId: chat.chatId, id: message.clientMessageId),
                            isPinned: pinnedMessages.contains(message.clientMessageId),
                            isMine: message.senderId == auth.currentUser?.id,
                            currentUserId: auth.currentUser?.id,
                            replyPeerName: chat.otherUser?.visibleName ?? UnyriL10n.string("Reply"),
                            reactions: messagesRepository.reactions(forMessageServerId: message.serverId),
                            onRetry: {
                                guard actionIsCurrent() else { return }
                                let failed = group.filter { $0.senderId == actionOwner && $0.status == .failed }
                                actions.screen.refreshIdentityWarning()
                                if actions.screen.identityNeedsReview, let first = failed.first { actions.screen.retry(first); return }
                                for member in failed { actions.screen.retry(member) }
                            },
                            onReply: { actions.screen.beginReply(message) },
                            onJumpQuote: { if let target = message.targetMessageId { actions.screen.jumpRequest = target } },
                            onCopy: { UIPasteboard.general.string = DirectFilePreviewText.display(text: message.content.text, offer: message.content.directFile) ?? message.content.text ?? message.displayText },
                            onCopyMedia: { guard actionIsCurrent() else { return }; actions.screen.copyMediaMessage(message.clientMessageId) },
                            onForward: { guard actionIsCurrent() else { return }; actions.screen.prepareForward(group) },
                            onSave: { Task { await actions.screen.saveMessages(group) } },
                            onSelect: { guard actionIsCurrent() else { return }; UnyriHaptics.selection(); actions.screen.composerModel.blur(); actions.screen.selectedMessages.formUnion(ids) },
                            onPin: { actions.screen.togglePin(message.clientMessageId) },
                            onRemind: { actions.screen.reminderDate = Date().addingTimeInterval(3600); actions.screen.reminderError = nil; actions.screen.reminderFor = message },
                            onEdit: { actions.screen.beginEdit(message) },
                            onDeleteForEveryone: { guard actionIsCurrent() else { return }; if ids.count > 1 { actions.screen.presentDeletion(ids, everyone: true, queuedAlbumID: queuedAlbumID) } else { actions.screen.deleteForEveryone(message) } },
                            onDeleteForMe: {
                                guard actionIsCurrent() else { return }
                                if message.senderId == actionOwner, message.serverId == nil,
                                   message.status == .pending || message.status == .failed {
                                    // Capture only this displayed group's current unsent IDs.
                                    // The repository rechecks ownership/status at the SQL mutation.
                                    let pending = Set(group.compactMap { member -> String? in
                                        guard let live = repository.localMessage(id: member.clientMessageId),
                                              live.senderId == actionOwner, live.chatId == actionChatID,
                                              live.serverId == nil, !live.hiddenLocally, !live.isDeletedForEveryone,
                                              live.status == .pending || live.status == .failed else { return nil }
                                        return live.clientMessageId
                                    })
                                    let captured = UnyriChatAlbumSelection.pendingMembers(of: selectionItem(message),
                                        rows: [group.map(selectionItem)], pendingIDs: pending)
                                    guard repository.stopComposerRemainderForDeletion(chatId: actionChatID,
                                        albumIDs: queuedAlbumID.map { [$0] } ?? [], clientMessageIds: ids) else { return }
                                    repository.cancelUnsentMessages(chatId: actionChatID, clientMessageIds: captured)
                                } else if ids.count > 1 { actions.screen.presentDeletion(ids, queuedAlbumID: queuedAlbumID) } else { actions.screen.deleteForMe(message) }
                            },
                            onReact: { actions.screen.react(message, $0) },
                            onQuickReaction: quickReactionAction(message),
                            onRemoveReaction: { actions.screen.removeReaction(message) },
                            onDownloadAttachment: { await repository.downloadAttachment(attachmentId: $0, origin: .messageTap) },
                            onCachedLocalPath: { repository.cachedLocalPath(attachmentId: $0) },
                            isVoicePlaying: { player.state.playingAttachmentId == $0 },
                            voicePositionMs: { id in player.state.playingAttachmentId == id || player.state.pausedAttachmentId == id ? player.state.positionMs : 0 },
                            onToggleVoice: { id, path in
                                UnyriHaptics.press()
                                player.togglePlay(attachmentId: id, fileURL: URL(fileURLWithPath: path), owner: actionChatID)
                            }
                        , selecting: !selectedMessages.isEmpty, selected: selectedMessages.contains(message.clientMessageId), appearancePreviewContent: content, albumCaptionMode: captionOnly,
                          sharedPreviewState: shareAlbumPreview ? albumPreviewStates.value(for: message.id) : nil,
                          actionMemberCount: ids.count,
                          // The caption of an album shows the album's receipt, so it must
                          // re-render when any member's status/edit flag changes.
                          membersStateKey: captionOnly ? actionMembers.map { "\($0.status)\($0.isEdited)" }.joined(separator: ",") : "",
                          onSelectIndividual: ids.count > 1 ? { guard actionIsCurrent() else { return }; UnyriHaptics.selection(); actions.screen.composerModel.blur(); actions.screen.selectedMessages.insert(message.clientMessageId) } : nil,
                          onDeleteIndividual: ids.count > 1 ? { guard actionIsCurrent() else { return }; actions.screen.presentDeletion([message.clientMessageId]) } : nil,
                          onShowFullText: MessageTextPolicy.isPresentationBounded(message.content.text ?? "") ? {
                              guard actionIsCurrent(), let live = repository.localMessage(id: message.clientMessageId),
                                    live.chatId == actionChatID, !live.hiddenLocally, !live.isDeletedForEveryone,
                                    live.status != .decryptFailed, live.status != .identityChanged,
                                    let raw = DirectFilePreviewText.display(text: live.content.text, offer: live.content.directFile) ?? live.content.text else { return }
                              actions.screen.composerModel.blur()
                              actions.screen.fullTextRequest = FullTextRequest(text: raw, generation: actionGeneration, owner: actionOwner)
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
        if message.content.directFile != nil { return DirectFileOffer.caption(in: message.content.text) }
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

    private func albumReplyEligible(_ members: [DecryptedChatMessage]) -> Bool {
        guard selectedMessages.isEmpty, let first = members.first else { return false }
        return !first.isDeletedForEveryone && !first.hiddenLocally &&
            first.status != .decryptFailed && first.status != .identityChanged &&
            UnyriQuickReactionPolicy.eligible(status: first.status.rawValue, serverId: first.serverId, deleted: first.isDeletedForEveryone || first.hiddenLocally, selecting: false)
    }

    private func albumGroup(_ item: TimelineItem, viewport: CGSize, firstInGroup: Bool) -> some View {
        let members = item.members
        let outgoing = item.message?.senderId == auth.currentUser?.id
        let runs = albumRuns(members)
        let documents = runs.allSatisfy { !$0.visual }
        let directOffers = members.compactMap(\.content.directFile)
        let isDirectAlbum = directOffers.count > 1
        let width: CGFloat
        if isDirectAlbum {
            let maxChatWidth = viewport.width - UnyriSavedChatStyle.side * 2 - (selectedMessages.isEmpty ? 0 : 44)
            width = min(270, max(200, maxChatWidth * 0.88))
        } else {
            width = min(max(80, (viewport.width - UnyriSavedChatStyle.side * 2 - (selectedMessages.isEmpty ? 0 : 44)) * UnyriSavedChatStyle.width),
                        documents ? UnyriSavedChatStyle.fileWidth : UnyriSavedChatStyle.mediaWidth)
        }
        let caption = members.compactMap { member in albumCaption(member).map { (member, $0) } }.first
        return HStack(alignment: .unyriMessageBodyCenter, spacing: 0) {
            if outgoing { Spacer(minLength: 0) }
            if !selectedMessages.isEmpty && outgoing { albumSelectionMark(members) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 0) {
            VStack(spacing: 0) {
                if directOffers.count > 1, selectedMessages.isEmpty {
                    UnyriDirectFileAlbumHeader(offers: directOffers, outgoing: outgoing, chatId: chat.chatId, albumId: item.message?.content.albumId, isExpanded: Binding(get: { expandedDirectAlbums.contains(item.id) }, set: { if $0 { expandedDirectAlbums.insert(item.id) } else { expandedDirectAlbums.remove(item.id) } }))
                }
                if directOffers.count <= 1 || directOffers.count != members.count || !selectedMessages.isEmpty || expandedDirectAlbums.contains(item.id) {
                VStack(spacing: UnyriSavedChatStyle.albumGap) {
                ForEach(runs) { run in
                    if run.visual {
                        UnyriAlbumVisualRun(ids: run.members.map(\.id), ratios: run.members.map(albumRatio), width: width) { index, tile in
                                let member = run.members[index]
                                albumCell(member, firstInGroup: firstInGroup, inAlbum: true, actionMembers: members)
                                    .environment(\.chatMediaWidth, CGFloat(tile.width))
                                    .environment(\.chatMediaHeight, CGFloat(tile.height))
                                    .environment(\.chatAlbumCell, true)
                                    .frame(width: tile.width, height: tile.height).clipped()
                        }
                    } else {
                        ForEach(run.members) { member in
                            albumCell(member, firstInGroup: firstInGroup, inAlbum: true, actionMembers: members)
                                .environment(\.chatAlbumCell, true)
                                .environment(\.chatAlbumWidth, width)
                        }
                    }
                }
                }
                // Uncaptioned file albums keep their time inside the bubble, bottom-trailing.
                if documents && caption == nil {
                    albumMetadataLine(members, outgoing: outgoing, onMedia: false)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.horizontal, appearance.padX).padding(.bottom, 6).padding(.top, 2)
                }
                }
                if let caption {
                    messageBubble(caption.0, content: AnyView(
                        VStack(alignment: .trailing, spacing: 2) {
                            UnyriMessageRichText(source: caption.1).equatable()
                                .font(appearance.messageFont()).lineSpacing(appearance.extraLineSpacing)
                                .foregroundStyle(outgoing ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            // Telegram-like: a captioned album keeps its time inside the caption.
                            albumMetadataLine(members, outgoing: outgoing, onMedia: false)
                        }
                        .padding(.horizontal, appearance.padX).padding(.vertical, appearance.padY)
                    ), captionOnly: true, shareAlbumPreview: true, actionMembers: members).equatable()
                    .environment(\.chatAlbumCell, true)
                    .environment(\.chatAlbumWidth, width)
                    .frame(maxWidth: .infinity)
                }
            }
            .background(outgoing ? appearance.outgoing : appearance.incoming)
            .clipShape(documents ? AnyShape(appearance.documentAlbumShape) : AnyShape(UnyriSavedChatStyle.mediaShape))
            .overlay(alignment: .bottomTrailing) {
                // Uncaptioned photo albums show the time on the media, like single photos.
                if !documents && caption == nil { albumMetadataLine(members, outgoing: outgoing, onMedia: true) }
            }
            .alignmentGuide(.unyriMessageBodyCenter) { $0[VerticalAlignment.center] }
            if members.contains(where: { !visibleReactions($0).isEmpty }) {
                // One chip with a count per member and emoji, so a tap acts on that member.
                // Sized with the estimate's text scale, so the estimate matches the row.
                let chip = ChatReactionMetrics.chip(lineHeight: appearance.messageLineHeight * ChatRowEstimateMetrics.typeScale)
                UnyriReactionRows(spacing: ChatReactionMetrics.spacing, rowGap: ChatReactionMetrics.rowGap) {
                    ForEach(members) { member in
                        ForEach(visibleReactions(member), id: \.emoji) { reaction in
                            ChatReactionChip(reaction: reaction, metrics: chip, bare: true, outgoing: outgoing) {
                                if reaction.isMine { removeReaction(member) } else { react(member, reaction.emoji) }
                            }
                        }
                    }
                }.padding(.top, UnyriSavedChatStyle.reactionOffset)
            }
            }.frame(width: width)
            .unyriSwipeToReply(enabled: albumReplyEligible(members), onReply: {
                if let target = members.first { beginReply(target) }
            })
            if !selectedMessages.isEmpty && !outgoing { albumSelectionMark(members) }
            if !outgoing { Spacer(minLength: 0) }
        }
        .padding(.top, firstInGroup ? appearance.groupGap : appearance.gap)
        .frame(maxWidth: .infinity, alignment: outgoing ? .trailing : .leading)
        .id(item.id)
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

    @ViewBuilder private func albumMetadataLine(_ members: [DecryptedChatMessage], outgoing: Bool, onMedia: Bool) -> some View {
        if UnyriSavedChatStyle.showTimes, let last = members.last {
            let line = UnyriCompactMessageMetadata(date: last.createdAt, status: albumReceipt(members), outgoing: outgoing,
                                                   edited: members.contains(where: \.isEdited), foreground: onMedia ? .white : nil,
                                                   liveID: members.first?.clientMessageId)
                .font(.system(size: UnyriSavedChatStyle.timeSize))
            if onMedia {
                line.padding(.horizontal, 6).padding(.vertical, 3)
                    .background(.black.opacity(0.62), in: Capsule()).padding(6)
            } else {
                line.foregroundStyle(UnyriSavedChatStyle.muted)
            }
        }
    }

    /// Older pages are requested as the reader nears the top of the loaded history.
    /// `loadingOlder` keeps one request in flight and a moment after it, so the new rows
    /// reach the timeline before another page can be asked for.
    @State private var loadingOlder = false
    @State private var olderAttemptKey = ""
    private func loadOlderAnchored() {
        // One attempt per loaded size and server cursor: a load that adds nothing
        // (offline, end of history, initial load still running) must not retry on every
        // scroll frame, but a newly adopted cursor unlocks it at once (a chat opened from
        // the list showed a dead "Load older" for up to 10 s).
        let loaded = messagesRepository.messages.count
        let attempt = "\(loaded)|\(messagesRepository.nextBeforeSequence ?? -1)|\(messagesRepository.hasMore)"
        guard !loadingOlder, olderAttemptKey != attempt else { return }
        olderAttemptKey = attempt
        loadingOlder = true
        Task {
            await messagesRepository.loadOlder(chatId: chat.chatId)
            // Let the timeline apply the new rows before another page may start.
            try? await Task.sleep(nanoseconds: 350_000_000)
            loadingOlder = false
            // A failed attempt can be retried later by a new scroll after 10 s.
            if messagesRepository.messages.count == loaded {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                if olderAttemptKey == attempt { olderAttemptKey = "" }
            }
        }
    }

    /// Entry-to-visible latency: from the chat appearing until the rows are shown
    /// already positioned. phase=1 marks the completed measure.
    private func recordOpenVisible(items: Int) {
        guard items > 0, let started = openClock.startedAt else { return }
        openClock.startedAt = nil
        PerformanceRecorder.shared.record(.chatOpen, [
            "phase": 1, "items": Double(items),
            "duration_ms": max(0, (ProcessInfo.processInfo.systemUptime - started) * 1000)])
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

    /// Canonical row mapping identical to `UnyriTimelineAnchor.rowID` (same order,
    /// same `id`-or-member match), but resolved lazily per row instead of first
    /// materialising one `(id, members: [String])` tuple and a member-id array for
    /// every loaded row. This runs from the timeline body and every jump, so the
    /// old allocation burst repeated for a long cached history; a row-id hit (the
    /// common bookmark case) now short-circuits before any member lookup.
    private func timelineRowID(_ target: String, items: [TimelineItem]) -> String? {
        items.first { item in
            item.id == target || item.members.contains { $0.id == target }
        }?.id
    }

    private struct TimelineRevision: Equatable {
        let generation: UInt64
        let origin: URL
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
        /// The presentation's date separators depend on the calendar day boundaries.
        let zone: TimeZone
    }
    private struct ArrivalRevision: Equatable {
        let count: Int
        let tail: String?
    }
    /// Per-row layout facts that depend only on the item order. Computed once per
    /// timeline revision; the row builder used to recompute them (two calendar
    /// comparisons and neighbour lookups per row) on every body pass.
    private struct TimelineRowLayout: Equatable {
        /// The day of the separator drawn above this row, when it starts a new day.
        let dateSeparator: Date?
        let firstInGroup: Bool
        let lastInRun: Bool
    }
    private struct TimelinePresentation {
        let arrivalRevision: ArrivalRevision
        let arrivalIDs: Set<String>
        let items: [TimelineItem]
        /// Aligned with `items`.
        let layouts: [TimelineRowLayout]
        let bookmarkAnchors: [String: String]
        let hasMessages: Bool
        let unavailable: Bool
    }
    @State private var presentationCache = ChatPresentationCache<TimelineRevision, TimelinePresentation>()
    @State private var pendingAlbumPresentation = UnyriPendingAlbumPresentation()
    private var timelineRevision: TimelineRevision {
        TimelineRevision(generation: auth.sessionGeneration, origin: auth.authenticatedServerURL,
            owner: auth.currentUser?.id, chat: chat.chatId, peer: chat.otherUser?.id,
            loaded: messagesRepository.loadedHistoryChatId, messages: messagesRepository.messagesRevision,
            calls: callsHistory.historyRevision, cutoff: auth.historyBeforeSequence ?? 0,
            historyDate: auth.historyContext?.beforeDate, failures: showDecryptionFailures, earlierCalls: messagesRepository.hasMoreLocalHistory,
            zone: TimeZone.current)
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
                var item = UnyriMediaItem(id: a.attachmentId, url: url, mime: a.mimeType, name: a.filename, thumbnailBase64: a.thumbnail)
                item.sender = message.senderId == auth.currentUser?.id ? UnyriL10n.string("You")
                    : chat.otherUser?.visibleName
                item.date = message.createdAt
                return item
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
                items: items, layouts: Self.rowLayouts(items),
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

    private static func timelineDate(_ item: TimelineItem) -> Date? { item.message?.createdAt ?? item.call?.createdAt }

    /// Same rules the row builder applied per row: a separator when the day differs from
    /// the previous row's (or there is none), a group starts and a run ends where the
    /// sender changes.
    private static func rowLayouts(_ items: [TimelineItem]) -> [TimelineRowLayout] {
        let calendar = Calendar.current
        return items.indices.map { index in
            let item = items[index]
            let day = timelineDate(item)
            let startsDay: Bool = {
                guard let day else { return false }
                guard index > 0, let previous = timelineDate(items[index - 1]) else { return true }
                return !calendar.isDate(previous, inSameDayAs: day)
            }()
            let sender = item.message?.senderId
            return TimelineRowLayout(dateSeparator: startsDay ? day : nil,
                firstInGroup: index == 0 || items[index - 1].message?.senderId != sender,
                lastInRun: index + 1 >= items.count || items[index + 1].message?.senderId != sender)
        }
    }

    private static func timelineMessagePrecedes(_ lhs: DecryptedChatMessage, _ rhs: DecryptedChatMessage) -> Bool {
        // A message still on its way to the server carries this phone's clock, which can
        // be ahead of or behind the server's: it stays below every confirmed message, so it
        // never shows above a newer reply and then jumps when the server answers.
        let lhsPending = lhs.status == .pending && lhs.sequence == nil
        let rhsPending = rhs.status == .pending && rhs.sequence == nil
        if lhsPending != rhsPending { return rhsPending }
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
        // An album row is identified by its album, not by its first photo: deleting that
        // one photo (or a member arriving late) must not recreate the whole row.
        var albumOrdinals: [AlbumKey: Int] = [:]
        for index in rows.indices where rows[index].members.count > 1 {
            rows[index].albumMembers = UnyriAlbumItemOrder.sorted(rows[index].members, index: { $0.content.albumItemIndex })
            if rows[index].presentationID == nil, let first = rows[index].members.first, let album = first.content.albumId {
                let key = AlbumKey(chat: first.chatId, sender: first.senderId, album: album)
                let ordinal = albumOrdinals[key, default: 0]
                albumOrdinals[key] = ordinal + 1
                rows[index].presentationID = "album:\(first.senderId):\(album)" + (ordinal > 0 ? ":\(ordinal)" : "")
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
        guard let route = UserDefaults.standard.dictionary(forKey: "chatReminderRoute"),
              NotificationRecipientPolicy.matches(owner: route["owner"] as? String, current: auth.currentUser?.id),
              (route["chat"] as? String)?.lowercased() == chat.chatId.lowercased() else { return }
        // Consumed once this chat is open, with or without a message to jump to; a route
        // left behind reopened the chat every time the list appeared.
        UserDefaults.standard.removeObject(forKey: "chatReminderRoute")
        if let id = route["message"] as? String, !id.isEmpty { jumpRequest = id }
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
        } catch { reminderError = UnyriErrorText.message(error) ?? reminderError }
    }
    private var selectedItems: [DecryptedChatMessage] { messagesRepository.messages.filter { selectedMessages.contains($0.clientMessageId) } }

    private func forwardingAlbums(_ items: [DecryptedChatMessage]) -> [String: String] {
        UnyriChatAlbumSelection.forwardingAlbums(items.map {
            .init(id: $0.clientMessageId, chat: $0.chatId, sender: $0.senderId, album: $0.content.albumId)
        })
    }
    /// The menu never downloads to satisfy Copy. Read the full decrypted file only
    /// after the explicit action, then recheck session/message/file before publishing.
    private func copyMediaMessage(_ id: String) {
        let generation = auth.sessionGeneration
        let owner = auth.currentUser?.id
        let server = auth.authenticatedServerURL
        let chatID = chat.chatId
        let repository = messagesRepository
        let request = UUID()
        UnyriMediaCopyRequest.latest = request
        let pasteboardChangeCount = UIPasteboard.general.changeCount
        Task { @MainActor in
            guard let live = repository.localMessage(id: id), live.chatId == chatID,
                  let attachment = live.content.attachment,
                  let path = repository.cachedLocalPath(attachmentId: attachment.attachmentId),
                  let kind = UnyriMessageMediaCopyPolicy.kind(message: live, localPath: path),
                  let type = UnyriMessageMediaCopyPolicy.pasteboardType(kind: kind, mime: attachment.mimeType, filename: attachment.filename) else { return }
            // Keep asynchronous cache cleanup from removing the source during the read.
            let lease = MediaCacheLease(path: path)
            do {
                let data = try await Task.detached(priority: .userInitiated) {
                    defer { withExtendedLifetime(lease) {} }
                    // Original image/GIF/movie bytes, never the decoded/downsampled preview.
                    return try Data(contentsOf: URL(fileURLWithPath: path), options: .mappedIfSafe)
                }.value
                guard !Task.isCancelled, !data.isEmpty, UnyriMediaCopyRequest.latest == request,
                      UIPasteboard.general.changeCount == pasteboardChangeCount,
                      generation == auth.sessionGeneration, owner == auth.currentUser?.id,
                      server == auth.authenticatedServerURL,
                      let current = repository.localMessage(id: id), current.chatId == chatID,
                      current.content.attachment == attachment,
                      repository.cachedLocalPath(attachmentId: attachment.attachmentId) == path,
                      UnyriMessageMediaCopyPolicy.kind(message: current, localPath: path) == kind else { return }
                // Same privacy lifetime as conference links and safety numbers. Data
                // under the concrete image/movie UTI also creates an item-provider
                // representation for receiving apps; GIF keeps every original frame.
                UIPasteboard.general.setItems([[type.identifier: data]],
                    options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
            } catch {
                guard generation == auth.sessionGeneration, owner == auth.currentUser?.id,
                      server == auth.authenticatedServerURL else { return }
                repository.reportError(UnyriL10n.string("Could not open this file. Check that it is available on this device, then try again."))
            }
        }
    }

    private func prepareForward(_ items: [DecryptedChatMessage]) {
        forwardQueue = items
        forwardProgress = ChatForwardProgress()
        forwardSession = UUID()
        forwardAlbumIDs = forwardingAlbums(items)
        showForwardPicker = true
    }

    private func canDeleteForEveryone(_ ids: [String]) -> Bool {
        let chosen = Set(ids)
        let targets = messagesRepository.messages.filter { chosen.contains($0.clientMessageId) }
        return !ids.isEmpty && targets.allSatisfy { $0.chatId == chat.chatId && $0.senderId == auth.currentUser?.id && !$0.isDeletedForEveryone } &&
            targets.count + ids.filter { $0.hasPrefix("call:") }.count == ids.count
    }

    private func queuedAlbumsForDeletion(_ ids: [String]) -> [String] {
        guard let owner = auth.currentUser?.id else { return [] }
        let selected = Set(ids)
        return Array(Set(timelineItems.compactMap { row -> String? in
            let members = row.members
            guard members.count > 1, members.count <= 10, let first = members.first,
                  let album = first.content.albumId, !album.isEmpty,
                  members.contains(where: { $0.content.directFile != nil }),
                  Set(members.map(\.clientMessageId)).count == members.count,
                  members.allSatisfy({ selected.contains($0.clientMessageId) && $0.chatId == chat.chatId &&
                      $0.senderId == owner && $0.content.albumId == album }) else { return nil }
            return album
        }))
    }

    private func presentDeletion(_ ids: [String], everyone: Bool = false, queuedAlbumID: String? = nil) {
        guard let owner = auth.currentUser?.id else { return }
        var seen = Set<String>()
        let captured = ids.filter { seen.insert($0).inserted }
        guard !captured.isEmpty else { return }
        composerModel.blur()
        let priorSelection = selectedMessages
        selectedMessages.formUnion(captured)
        pendingBulkDelete = DeleteSelection(everyone: everyone, ids: captured,
            generation: auth.sessionGeneration, owner: owner, chatID: chat.chatId, priorSelection: priorSelection,
            queuedAlbumIDs: queuedAlbumID.map { [$0] } ?? queuedAlbumsForDeletion(captured))
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
        var next = pinnedMessages
        if next.contains(id) { next.remove(id) } else { next.insert(id) }
        if let data = try? JSONEncoder().encode(next) { messagesRepository.saveChatValue(chatId: chat.chatId, name: "pins", value: String(decoding: data, as: UTF8.self)) }
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
        messagesRepository.pinnedPreviews(chatId: chat.chatId).orderedIDs
    }
    private func pinnedPreviewText(_ message: DecryptedChatMessage?) -> String {
        guard let message else { return UnyriL10n.string("Original message is unavailable") }
        if message.isDeletedForEveryone { return UnyriL10n.string("This message was deleted") }
        if message.status == .decryptFailed || message.status == .identityChanged { return UnyriL10n.string("Unable to decrypt message") }
        if let fileText = DirectFilePreviewText.display(text: message.content.text, offer: message.content.directFile) { return fileText }
        if let text = message.content.text, !text.isEmpty { return text }
        if let media = message.content.attachment {
            if media.mimeType.hasPrefix("video/") { return UnyriL10n.string("Video") }
            if media.mediaType == AttachmentMetadata.mediaTypeImage { return UnyriL10n.string("Photo") }
            if media.mediaType == AttachmentMetadata.mediaTypeVoice { return UnyriL10n.string("Voice message") }
            return media.filename ?? UnyriL10n.string("File")
        }
        return message.displayText
    }
    private func messageSearchPreview(_ message: DecryptedChatMessage) -> AttributedString {
        let source: String = chatPresentationText(message.displayText, inline: true)
        var result: AttributedString = AttributedString(source)
        if !searchQuery.isEmpty, let match = result.range(of: searchQuery, options: [.caseInsensitive, .diacriticInsensitive]) {
            result[match].inlinePresentationIntent = .stronglyEmphasized
        }
        return result
    }
    private var messageTools: some View {
        NavigationStack {
            List {
                if onlyPinned && orderedPinnedIds.isEmpty { Text("No pinned messages") }
                if onlyPinned {
                    ForEach(orderedPinnedIds.filter { id in searchQuery.isEmpty || pinnedPreviewText(messagesRepository.pinnedPreviews(chatId: chat.chatId).messages[id]).localizedCaseInsensitiveContains(searchQuery) }, id: \.self) { id in
                        let message = messagesRepository.pinnedPreviews(chatId: chat.chatId).messages[id]
                        Button { showMessageTools = false; jumpRequest = id } label: {
                            PinnedMessagePreviewRow(message: message, text: pinnedPreviewText(message))
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
                        VStack(alignment: .leading, spacing: 6) { Text(messageSearchPreview(item)).lineLimit(3); Text(item.createdAt.formatted()).font(.caption).foregroundColor(UnyriColors.secondaryText) }
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(UnyriColors.primaryText)
                        .listRowBackground(Color(hex: 0x1B1B1D)).listRowSeparator(.hidden)
                        .accessibilityIdentifier("chat-search-message-\(item.clientMessageId)")
                }
            }.listStyle(.plain).tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC)))
                .searchable(text: $searchQuery, prompt: "Search messages")
                .unyriScreenBackground().navigationTitle(onlyPinned ? "Pinned messages" : "Search messages")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showMessageTools = false }.accessibilityIdentifier("chat-message-tools-close") } }
        }
    }
    private var forwardPicker: some View {
        let count = forwardQueue.count
        let first = forwardQueue.first
        let shareItems: [Any] = forwardQueue.compactMap { message in
            if let attachment = message.content.attachment,
               let path = messagesRepository.cachedLocalPath(attachmentId: attachment.attachmentId) { return URL(fileURLWithPath: path) }
            let text = (message.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
        }
        return UnyriChatPickerSheet(
            title: UnyriL10n.string("Forward"),
            subtitle: count > 1 ? String(format: UnyriL10n.string("%d messages"), count) : nil,
            preview: first.map { message in
                AnyView(HStack(spacing: 10) {
                    let media = message.content.attachment
                    Image(systemName: media == nil ? "text.bubble.fill" : media!.mimeType.hasPrefix("video/") ? "video.fill"
                          : media!.mediaType == AttachmentMetadata.mediaTypeImage ? "photo.fill"
                          : media!.mediaType == AttachmentMetadata.mediaTypeVoice ? "waveform" : "doc.fill")
                        .font(.system(size: 15, weight: .semibold)).foregroundStyle(.white.opacity(0.85)).frame(width: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(count > 1 ? String(format: UnyriL10n.string("%d messages"), count) : UnyriL10n.string("Forwarding"))
                            .font(.system(size: 13, weight: .semibold)).foregroundStyle(Color(hex: 0x7BD5EF))
                        Text(pinnedPreviewText(message)).font(.system(size: 14)).lineLimit(2).foregroundStyle(UnyriColors.primaryText)
                    }
                })
            },
            shareOutside: shareItems.isEmpty ? nil : { UnyriShareSheet.present(items: shareItems) },
            load: {
                var result: [ChatSummary] = []
                var offset: Int64 = 0
                repeat {
                    let page = try await chatsRepository.listChats(limit: 100, offset: offset)
                    result.append(contentsOf: page.items)
                    guard let next = page.nextOffset, next > offset else { break }
                    offset = next
                } while result.count < 1000
                return result
            },
            avatarURL: { destination in
                usersRepository.cachedAvatarURL(id: destination.otherUser?.id ?? "", avatarUrl: destination.otherUser?.avatarUrl,
                                                avatarVersion: destination.otherUser?.avatarVersion ?? 0)
            },
            send: { targets, comment in
                let queue = forwardQueue
                guard messagesRepository.validateForwardSelection(queue) else {
                    return messagesRepository.errorMessage ?? UnyriL10n.string("Something went wrong. Try again.")
                }
                let progress = forwardProgress
                let session = forwardSession
                for target in targets {
                    if !comment.isEmpty, !progress.commented.contains(target.chatId) {
                        if await messagesRepository.sendText(chatId: target.chatId, otherUserId: target.otherUser?.id, text: comment,
                            clientMessageId: MessagesRepository.stableForwardId(session: session, item: "comment", destinationChatId: target.chatId)) {
                            progress.commented.insert(target.chatId)
                        }
                    }
                    for item in queue {
                        let key = target.chatId + "|" + item.clientMessageId
                        if progress.delivered.contains(key) { continue }
                        guard await messagesRepository.forward(item, to: target, albumId: forwardAlbumIDs[item.clientMessageId],
                            clientMessageId: MessagesRepository.stableForwardId(session: session, item: item.clientMessageId, destinationChatId: target.chatId)) else {
                            return messagesRepository.errorMessage ?? UnyriL10n.string("Something went wrong. Try again.")
                        }
                        progress.delivered.insert(key)
                    }
                }
                for item in queue { selectedMessages.remove(item.clientMessageId) }
                forwardQueue = []
                showForwardPicker = false
                return nil
            },
            cancel: { showForwardPicker = false })
    }

    // MARK: - Composer

    private func refreshIdentityWarning() {
        chatBindingFrozen = chatsRepository.bindingIsFrozen(chat.chatId)
        let peers = Set([chat.otherUser?.id, auth.currentUser?.id].compactMap { $0 })
        guard !peers.isEmpty else { identityNeedsReview = false; ownDeviceNeedsReview = false; peerDeviceNeedsReview = false; return }
        if let pending = try? verificationManager?.pendingIdentityPeers(among: peers) {
            identityNeedsReview = !pending.isEmpty
            ownDeviceNeedsReview = auth.currentUser.map { pending.contains($0.id) } ?? false
            peerDeviceNeedsReview = chat.otherUser.map { pending.contains($0.id) } ?? false
        }

    }

    /// Deferred out of the chat-open update transaction. `refreshIdentityWarning`
    /// enumerates Signal Keychain state twice (`hasPendingIdentity(peer:)` and
    /// `hasPendingIdentity(currentUser.id)` -> `stateStore.keys(withPrefix:)` ->
    /// `SecItemCopyMatching` over the stored items) synchronously on the main
    /// actor, which must not sit in the appear/commit path.
    ///
    /// A next-main-run-loop hop runs after the current SwiftUI update/commit
    /// returns; it is not a rendered-frame guarantee, and the enumeration itself
    /// still uses the main thread once it runs. Repeated requests during open are
    /// coalesced into one refresh.
    private func scheduleIdentityWarningRefresh() {
        guard !identityRefreshPending else { return }
        identityRefreshPending = true
        DispatchQueue.main.async {
            identityRefreshPending = false
            guard auth.currentUser != nil else { identityNeedsReview = false; return }
            refreshIdentityWarning()
        }
    }

    @ViewBuilder private var identityReviewBanner: some View {
        if chatBindingFrozen {
            Label("Chat identity changed. Sending is blocked. Contact this person through a trusted channel.", systemImage: "exclamationmark.shield.fill")
                .font(.footnote.weight(.semibold)).foregroundStyle(UnyriColors.danger)
                .padding(12).accessibilityIdentifier("chat-binding-frozen")
        }
        if identityNeedsReview {
            VStack(alignment: .leading, spacing: 4) {
                if ownDeviceNeedsReview {
                    Label("A new device on your account is blocked. Confirm it from this device only after comparing its security code.", systemImage: "exclamationmark.shield.fill")
                        .font(.footnote.weight(.semibold)).foregroundStyle(UnyriColors.danger)
                }
                if peerDeviceNeedsReview {
                    Text(String(format: UnyriL10n.string("%@ has a new or changed device — compare the security code."), chat.otherUser?.displayName ?? ""))
                        .font(.footnote).accessibilityIdentifier("chat-device-system-line")
                }
                Text("Messages continue to approved devices. New devices receive nothing until you approve them.").font(.footnote)
                Button("Verify security keys") { composerModel.blur(); showKeyVerification = true }.frame(minHeight: 44)
            }.padding(.horizontal, 12).padding(.vertical, 6)
        }
    }

    private var composer: AnyView {
        AnyView(
        Group {
            if selectedMessages.isEmpty { ordinaryComposer }
            else { selectionActions }
        }
        .background(GeometryReader { geometry in Color.clear
            .onAppear { composerSizeChanged(geometry.size) }
            .onChange(of: geometry.size) { composerSizeChanged($0) } })
        .modifier(ChatVoiceHoldHost(
            began: { voiceHoldBegan(at: $0) }, changed: { voiceHoldChanged($0) },
            ended: { voiceHoldEnded() }, cancelled: { voiceHoldCancelled() },
            legacy: AnyGesture(LongPressGesture(minimumDuration: 0.35).sequenced(before: DragGesture(minimumDistance: 0)).onChanged { phase in
            guard !recordingHoldFinished, case .second(true, let drag) = phase, let drag else { return }
            if !recordingHoldActive {
                let composerSize = composerGeometry.size
                guard !isRecording, voiceDraft == nil, !stagingVoice, selectedMessages.isEmpty,
                      composerModel.isBlank, editingMessage == nil, composerAttachments.isEmpty, composerFileImports == 0,
                      drag.startLocation.x > composerSize.width - 56,
                      drag.startLocation.y > composerSize.height - 70 else { return }
                recordingHoldActive = true; recordingLocked = false
                beginHoldRecording()
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
        }.map { _ in () })))
        .onReceive(recordingTimer) { _ in
            if allowsPeerActivity && conversationVisible { realtime.liveDrafts.update(editingMessage == nil && composerModel.isFocused ? composerModel.text : "") }
            if isRecording { recordingSeconds = Int64(voiceRecorder.recordedSeconds); if voiceRecorder.recordedSeconds >= VoiceRecorder.maxRecordingSeconds { finishRecording(); return }; if allowsPeerActivity && activityEnabled && voiceRecorder.isRecording { realtime.sendActivity(chatId: chat.chatId, kind: "recording") } }
        }
        .onChange(of: voiceRecorder.callInProgress) { active in
            if active { composerModel.blur(); if isRecording { voiceRecorder.interrupt() } }
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
        selectedMessages.isEmpty && !isRecording && voiceDraft == nil && (!stagingVoice || stagingSendsAfter) && currentPreferenceChat.isBlocked != true
    }

    private var ordinaryComposer: AnyView {
        AnyView(
        VStack(spacing: 0) {
            identityReviewBanner
            LiveDraftPreview(model: realtime.liveDrafts)
            mediaPreparationBanner
            voiceDraftPreview
            if stagingVoice && !stagingSendsAfter && voiceDraft == nil && !isRecording {
                HStack(spacing: 12) { ProgressView().tint(UnyriColors.secondaryText); Text("Preparing…").font(.subheadline).foregroundColor(UnyriColors.secondaryText); Spacer() }
                    .padding(.horizontal, 16).frame(height: max(44, UnyriSavedChatStyle.entryHeight))
                    .background(Color(white: 0.13), in: Capsule()).overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5))
                    .padding(.horizontal, 12).padding(.vertical, 8)
            }
            // While recording, the input row stays mounted (hidden) under the recording bar:
            // removing the text field dropped the keyboard mid-hold, the composer fell
            // ~300 pt and the mic was no longer under the finger (Telegram keeps it up).
            let inputRowMounted = currentPreferenceChat.isBlocked != true && selectedMessages.isEmpty
                && (isRecording || (voiceDraft == nil && (!stagingVoice || stagingSendsAfter)))
            if isRecording && !inputRowMounted { recordingBanner(standalone: true).modifier(VoicePhaseReveal(phase: "recording")) }
            if currentPreferenceChat.isBlocked == true {
                Button("Contact blocked. Open chat settings") { showChatSafety = true }.frame(maxWidth: .infinity, minHeight: 44)
            } else if inputRowMounted {
                VStack(spacing: 0) {
                if composerBatchRestoreFailed {
                     HStack(spacing: 10) {
                         Text("Could not restore the attachment draft. Your saved selection is kept.")
                             .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                         Spacer(minLength: 0)
                         Button("Retry") { restoreComposerBatchIfNeeded() }
                             .frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("composer-retry-restoration")
                     }.padding(.horizontal, 12).accessibilityIdentifier("composer-restoration-error")
                 }
                UnyriComposerSurface(orbit: true) {
                VStack(spacing: 0) {
                if let feedback = composerTray.feedback, editingMessage == nil {
                    Text(feedback).font(.caption).foregroundStyle(UnyriColors.secondaryText)
                        .padding(.horizontal, 12).padding(.top, 6)
                        .accessibilityIdentifier("composer-attachment-feedback")
                }
                if !composerAttachments.isEmpty && editingMessage == nil {
                    composerAttachmentStrip
                    Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5).padding(.horizontal, 14)
                }
                HStack(alignment: .bottom, spacing: 4) {
                    VStack(spacing: 0) {
                        if let target = editingMessage ?? replyTarget {
                            UnyriReplyComposerPreview(message: target,
                                author: target.senderId == auth.currentUser?.id ? UnyriL10n.string("You") : (chat.otherUser?.visibleName ?? UnyriL10n.string("Reply")),
                                editing: editingMessage != nil,
                                localPath: target.content.attachment.flatMap { messagesRepository.cachedLocalPath(attachmentId: $0.attachmentId) },
                                onOpen: { jumpRequest = target.clientMessageId }) {
                                    if editingMessage != nil { editingMessage = nil; composerModel.setText(composerModel.ordinaryDraft) } else { replyTarget = nil }
                                }
                            Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5).padding(.horizontal, 14)
                        }
                        ChatComposerTextEntry(composer: composerModel, onTextChange: updateComposerDraft,
                            onFocusChange: composerFocusChanged,
                            onPasteMedia: pasteComposerMedia,
                            textScale: composerTextScale, tint: appColorStyle.controlTint(UnyriColors.accent),
                            importing: composerFileImports > 0,
                            attachmentDisabled: composerSelectionRemaining == 0 || composerBatch?.autoSend == true || composerBatchRestoring || composerBatchRestoreFailed || composerFileImports > 0 || editingMessage != nil,
                            cameraDisabled: voiceRecorder.callInProgress,
                            directSelected: directPreferenceBinding,
                            directScope: DirectSendPreference.scope(server: auth.authenticatedServerURL, owner: auth.currentUser?.id),
                            directIssue: composerDirectIssue, directInfo: { showDirectTransferInfo = true },
                            camera: { openComposerAttachment("camera") },
                            photos: { openComposerAttachment("gallery") },
                            files: { openComposerAttachment("files") },
                            emojiActive: composerEmojiKeyboard,
                            emoji: toggleComposerEmojiKeyboard)
                    }
                    .frame(maxWidth: .infinity)
                    ChatComposerSubmitControl(blank: composerModel.blankState,
                        recorderEligible: editingMessage == nil && composerAttachments.isEmpty && composerFileImports == 0,
                        microphoneInUse: voiceRecorder.callInProgress, sendGate: composerSendGate,
                        editing: editingMessage != nil, attachmentCount: composerAttachments.count,
                        record: { recordButtonTapped() },
                        send: send,
                        switchMode: { switchRecordingMode() })
                }
                .padding(.horizontal, 4)
                // Recording keeps this glass in place and swaps only what is inside it
                // (the whole composer used to vanish and a separate opaque bar appeared).
                .opacity(isRecording ? 0 : 1)
                .animation(composerReduceMotion ? nil : .easeOut(duration: 0.16), value: isRecording)
                }
                }
                }.buttonStyle(.plain)
                .background(Color.clear)
                .animation(composerReduceMotion ? nil : .easeInOut(duration: 0.16), value: composerAttachments.count)
                .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 8)
                .allowsHitTesting(!isRecording)
                .accessibilityHidden(isRecording)
                .overlay(alignment: .bottom) {
                    if isRecording { recordingBanner().transition(.opacity) }
                }
                .animation(composerReduceMotion ? nil : .easeOut(duration: 0.18), value: isRecording)
            }
        }
        )
    }


    /// 🙂 swaps the keyboard for the emoji panel (same height) and ⌨️ swaps it back;
    /// the composer keeps focus, so nothing in the chat moves (Telegram-like).
    private func toggleComposerEmojiKeyboard() {
        UnyriHaptics.press()
        UnyriKeyboardMetrics.startObserving()
        if composerEmojiKeyboard {
            composerEmojiKeyboard = false
            UnyriEmojiInput.setEmoji(false)
            return
        }
        composerEmojiKeyboard = true
        if composerModel.isFocused, UnyriEmojiInput.setEmoji(true) { return }
        composerModel.focus()
        // Swap on the next turn, once the field is first responder.
        DispatchQueue.main.async {
            guard composerEmojiKeyboard else { return }
            if !UnyriEmojiInput.setEmoji(true) {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    if composerEmojiKeyboard, !UnyriEmojiInput.setEmoji(true) { composerEmojiKeyboard = false }
                }
            }
        }
    }

    /// Every keystroke (from the input) and programmatic change (caption restore, the
    /// attachment sheet's caption). Writes no SwiftUI state; the draft save, live draft and
    /// typing activity follow in one coalesced pass (ChatComposerModel.scheduleActivity).
    private func updateComposerDraft(_ text: String) {
        if composerModel.text != text {
            composerModel.revision &+= 1
            composerModel.setText(text)
        }
        let editing = editingMessage != nil
        if !editing { composerModel.ordinaryDraft = text }
        composerModel.scheduleActivity(.init(editing: editing, liveDraft: allowsPeerActivity && conversationVisible,
                                             typing: allowsPeerActivity && activityEnabled))
    }

    /// The composer's size goes straight to the timeline (insets) and to a box the gesture
    /// math reads: a wrap to another line does not re-run this screen.
    private func composerSizeChanged(_ size: CGSize) {
        composerGeometry.size = size
        hostedTimeline.setBottomChrome(inChatSearch ? 0 : size.height)
    }

    /// Focus moved into or out of the composer (reported by the input on the next turn).
    private func composerFocusChanged(_ focused: Bool) {
        hostedTimeline.setTapEndsEditing(focused)
        if focused {
            // Diagnostics: phase 8 marks the composer gaining focus (see keyboard phase 7).
            PerformanceRecorder.shared.record(.action, ["phase": 8])
            prepareLiveDraft()
        } else if composerEmojiKeyboard {
            // Leaving the field (tap on the chat, drag down) ends the emoji panel; the
            // next focus opens the normal keyboard.
            composerEmojiKeyboard = false
            UnyriEmojiInput.resetAfterBlur()
        }
    }

    /// The live-draft stream key is set up when typing is about to start, not on the
    /// first keystroke (it involves Signal session work).
    private func prepareLiveDraft() {
        guard allowsPeerActivity, editingMessage == nil, composerModel.isFocused, conversationVisible else { return }
        realtime.liveDrafts.prepare()
    }

    @ViewBuilder private func recordingBanner(standalone: Bool = false) -> some View {
        if recordingVideoNote {
            ChatRecordingBar(recorder: videoNoteRecorder, drag: recordingDragBox, locked: recordingLocked,
                             cancelDistance: min(120, max(80, composerGeometry.size.width * 0.3)), lockDistance: 90,
                             reduceMotion: composerReduceMotion, standalone: standalone, video: true,
                             flipCamera: { UnyriHaptics.press(); videoNoteRecorder.flipCamera() },
                             cancel: { UnyriHaptics.longPress(); cancelRecording() },
                             togglePause: {}, review: {},
                             send: { finishRecording(send: true) })
                .padding(.horizontal, 12).padding(.vertical, 8)
        } else {
        ChatRecordingBar(recorder: voiceRecorder, drag: recordingDragBox, locked: recordingLocked,
                         cancelDistance: min(120, max(80, composerGeometry.size.width * 0.3)), lockDistance: 90,
                         reduceMotion: composerReduceMotion, standalone: standalone,
                         cancel: { UnyriHaptics.longPress(); cancelRecording() },
                         togglePause: { UnyriHaptics.press(); voiceRecorder.togglePause() },
                         review: { if isRecording { UnyriHaptics.selection() }; finishRecording() },
                         send: { finishRecording(send: true) })
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
    }

    private func toggleRecording(holding: Bool = false, resume: Bool = false) {
        if isRecording {
            UnyriHaptics.selection()
            finishRecording()
        } else {
            guard !startingVoice, !sendingVoice, composerAttachments.isEmpty, composerFileImports == 0 else { return }
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
            let requestPermission: (@Sendable @escaping (Bool) -> Void) -> Void = { handler in
                if #available(iOS 17.0, *) {
                    AVAudioApplication.requestRecordPermission(completionHandler: handler)
                } else {
                    AVAudioSession.sharedInstance().requestRecordPermission(handler)
                }
            }
            requestPermission { granted in
                Task { @MainActor in
                    var transferred = false
                    defer {
                        startingVoice = false
                        if pendingVoiceClaim == claim { pendingVoiceClaim = nil }
                        if !transferred { NonCallCaptureOwnership.release(claim) }
                    }
                    guard generation == auth.sessionGeneration, NonCallCaptureOwnership.isCurrent(claim),
                          !voiceRecorder.callInProgress else { return }
                    guard scenePhase == .active, conversationVisible, !isRecording, composerAttachments.isEmpty, composerFileImports == 0 else { return }
                    guard !holding || recordingHoldActive else { return }
                    guard granted else {
                        messagesRepository.reportError(UnyriL10n.string("Microphone access is required to record a voice message."))
                        return
                    }
                    do {
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
    private func finishRecording(send: Bool = false) {
        guard isRecording else { return }
        // A video note has no review step: finishing it sends it.
        if recordingVideoNote { finishVideoNote(); return }
        isRecording = false
        guard let result = voiceRecorder.stop() else { return }
        // An accidental tap-and-release is not a message.
        if send && result.durationMs < 1000 && resumeVoiceBase == nil {
            try? FileManager.default.removeItem(at: result.fileURL)
            showVoiceHoldHint()
            return
        }
        stageRecordedVoice(fileURL: result.fileURL, durationMs: result.durationMs, sendAfter: send)
    }

    // MARK: Voice hold (Telegram-like): hold to record, release to send, slide left to
    // cancel, slide up to lock. A system-cancelled hold keeps the recording, locked.
    fileprivate enum VoiceHoldAxis { case horizontal, vertical }
    private func voiceHoldBegan(at location: CGPoint) {
        let composerSize = composerGeometry.size
        guard !recordingHoldActive, !isRecording, voiceDraft == nil, !stagingVoice, selectedMessages.isEmpty,
              composerModel.isBlank, editingMessage == nil,
              composerAttachments.isEmpty, composerFileImports == 0,
              location.x > composerSize.width - 56, location.y > composerSize.height - 70 else { return }
        guard !voiceRecorder.callInProgress else { showVoiceHoldHint(UnyriL10n.string("Finish the call before recording.")); return }
        recordingHoldActive = true; recordingLocked = false; voiceHoldAxis = nil
        UnyriHaptics.press()
        beginHoldRecording()
    }
    private func voiceHoldChanged(_ translation: CGSize) {
        guard recordingHoldActive, !recordingLocked else { return }
        let cancelAt = -min(120, max(80, composerGeometry.size.width * 0.3))
        let lockAt: CGFloat = -90
        // The axis only steers the visual (the mic follows one direction at a time);
        // it is re-evaluated until a clear direction emerges, and the thresholds below
        // do not depend on it, so a slightly diagonal slide still cancels or locks.
        let dx = abs(translation.width), dy = abs(translation.height)
        if hypot(dx, dy) > 12, dx > dy * 1.3 { voiceHoldAxis = .horizontal }
        else if hypot(dx, dy) > 12, dy > dx * 1.3 { voiceHoldAxis = .vertical }
        if translation.width < cancelAt {
            recordingHoldActive = false; recordingDrag = .zero; voiceHoldAxis = nil
            UnyriHaptics.longPress()
            cancelRecording()
            return
        }
        if translation.height < lockAt {
            UnyriHaptics.press(); recordingLocked = true; recordingDrag = .zero; voiceHoldAxis = nil
            return
        }
        switch voiceHoldAxis {
        case .horizontal: recordingDrag = CGSize(width: max(cancelAt, min(0, translation.width)), height: 0)
        case .vertical: recordingDrag = CGSize(width: 0, height: max(lockAt, min(0, translation.height)))
        case nil: recordingDrag = .zero
        }
    }
    private func voiceHoldEnded() {
        lastVoiceHoldEnd = Date()
        let send = recordingHoldActive && !recordingLocked
        recordingHoldActive = false; recordingDrag = .zero; voiceHoldAxis = nil
        if send { finishRecording(send: true) }
    }
    private func voiceHoldCancelled() {
        lastVoiceHoldEnd = Date()
        // The system took the touch (Control Center, an alert, a second finger):
        // keep recording with the locked controls instead of leaving no way to stop it.
        if recordingHoldActive && !recordingLocked && isRecording { recordingLocked = true }
        recordingHoldActive = false; recordingDrag = .zero; voiceHoldAxis = nil
    }
    private func showVoiceHoldHint(_ text: String = UnyriL10n.string("Hold to record, release to send")) {
        UnyriHaptics.selection()
        withAnimation(.easeOut(duration: 0.15)) { voiceHoldHint = text }
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            withAnimation(.easeOut(duration: 0.2)) { if voiceHoldHint == text { voiceHoldHint = nil } }
        }
    }
    private func sendVoiceDraft(_ item: VoiceDraft) {
        voicePlayer.stop()
        // Voice and media do not carry a quote yet; the reply must not silently attach
        // to the next text instead (audit 10 Nov).
        replyTarget = nil
        sendingVoice = true
        Task {
            defer { sendingVoice = false }
            let queued = messagesRepository.queueMedia(id: item.id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                url: URL(fileURLWithPath: item.path), kind: "voice", duration: item.duration)
            if queued {
                messagesRepository.saveChatValue(chatId: chat.chatId, name: "voiceDraft", value: nil)
                voiceDraft = nil; preparedVoice = nil
            } else {
                // Keep the recording reviewable (a hold-release send never showed it).
                voiceDraft = item
                messagesRepository.reportError(UnyriL10n.string("Could not send the voice message. Try again."))
            }
        }
    }

    private func stageRecordedVoice(fileURL: URL, durationMs: Int64, sendAfter: Bool = false) {
        stagingVoice = true; stagingSendsAfter = sendAfter
        Task {
            defer { stagingVoice = false; stagingSendsAfter = false }
        do {
            let base = resumeVoiceBase
            let source: URL
            if let base { source = try await VoiceRecorder.join(URL(fileURLWithPath: base.path), fileURL) } else { source = fileURL }
            let file = try await messagesRepository.stageDraftFile(source)
            let value = VoiceDraft(id: UUID().uuidString.lowercased(), path: file.path, duration: durationMs + (base?.duration ?? 0))
            let data = try JSONEncoder().encode(value)
            guard messagesRepository.saveChatValue(chatId: chat.chatId, name: "voiceDraft", value: String(decoding: data, as: UTF8.self)) else { return }
            if !sendAfter { voiceDraft = value }
            preparedVoice = nil; resumeVoiceBase = nil
            try? FileManager.default.removeItem(at: fileURL)
            if source != fileURL { try? FileManager.default.removeItem(at: source) }
            if let base { try? FileManager.default.removeItem(atPath: base.path) }
            if sendAfter { UnyriHaptics.selection(); sendVoiceDraft(value) }
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
                    } label: { Image(systemName: "trash").font(.system(size: 19)).foregroundStyle(UnyriColors.primaryText).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Delete recording").disabled(sendingVoice)

                    Button { UnyriHaptics.press(); voicePlayer.togglePlay(attachmentId: item.id, fileURL: URL(fileURLWithPath: item.path)) } label: { Image(systemName: voicePlayer.state.playingAttachmentId == item.id ? "pause.fill" : "play.fill").font(.system(size: 17)).foregroundStyle(UnyriColors.primaryText).frame(width: 40, height: 44).contentShape(Rectangle()) }
                        .accessibilityLabel(voicePlayer.state.playingAttachmentId == item.id ? "Pause" : "Play")
                    VoiceProgressView(progress: voicePlayer.progress) { position in
                        GeometryReader { geometry in
                            AudioEnvelopeView(path: item.path,
                                fraction: (voicePlayer.state.playingAttachmentId == item.id || voicePlayer.state.pausedAttachmentId == item.id) ? VoiceFormat.playedFraction(positionMs: position, durationMs: item.duration) : 0,
                                played: UnyriColors.primaryText, unplayed: UnyriColors.mutedText).contentShape(Rectangle()).gesture(DragGesture(minimumDistance: 0).onChanged { value in
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
                            if activeDraft { Text(String(format: "%.1f×", voicePlayer.state.speed)).font(.caption.weight(.semibold)).foregroundColor(UnyriColors.primaryText) }
                        }.frame(width: 48, height: 44).contentShape(Rectangle())
                    }.disabled(!activeDraft)

                    Button {
                        UnyriHaptics.press()
                        sendVoiceDraft(item)
                    } label: {
                        Image(systemName: "arrow.up").font(.system(size: UnyriSavedChatStyle.iconSize, weight: .semibold)).foregroundColor(UnyriSavedChatStyle.micInk)
                            .frame(width: max(36, UnyriSavedChatStyle.entryHeight - 6), height: max(36, UnyriSavedChatStyle.entryHeight - 6))
                            .background(UnyriSavedChatStyle.micColor, in: Circle()).opacity(sendingVoice ? 0.4 : 1)
                            .frame(width: 44, height: 44).contentShape(Rectangle())
                    }.accessibilityLabel("Send voice message").accessibilityIdentifier("voice-draft-send").disabled(sendingVoice)
                }.disabled(sendingVoice || stagingVoice).modifier(VoicePhaseReveal(phase: item.id))
                    .frame(height: max(44, UnyriSavedChatStyle.entryHeight))
                    .background(Color(white: 0.13), in: Capsule())
                    .overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5))
                }.padding(.horizontal, 12).padding(.vertical, 8)
            }
        }
    }

    private func cancelRecording() {
        if isRecording || startingVoice { UnyriHaptics.selection() }
        if recordingVideoNote { recordingVideoNote = false; videoNoteRecorder.cancel() }
        resumeVoiceBase = nil
        voiceRecorder.cancel()
        isRecording = false
        recordingSeconds = 0
    }

    // MARK: Round video message (video note): the same hold gestures as a voice message.
    // A tap on the record button switches what the hold records.
    private func beginHoldRecording() {
        if ChatVideoNoteMode.enabled { startVideoNote(holding: true) } else { toggleRecording(holding: true) }
    }

    private func recordButtonTapped() {
        guard Date().timeIntervalSince(lastVoiceHoldEnd) > 0.5, !isRecording, !startingVoice, composerAttachments.isEmpty, composerFileImports == 0 else { return }
        // Telegram-like: a tap switches between voice and video message and explains the
        // hold. VoiceOver cannot hold, so it gets the locked recorder (stop/pause/send
        // controls) directly and switches the mode with the button's accessibility action.
        if UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning {
            recordingLocked = true
            if ChatVideoNoteMode.enabled { startVideoNote(holding: false) } else { toggleRecording() }
        } else {
            switchRecordingMode()
        }
    }

    private func switchRecordingMode() {
        guard !isRecording, !startingVoice else { return }
        let video = !ChatVideoNoteMode.enabled
        ChatVideoNoteMode.enabled = video
        showVoiceHoldHint(ChatVideoNoteMode.hint(video: video))
    }

    private func startVideoNote(holding: Bool) {
        guard !isRecording, !startingVoice, !sendingVoice, composerAttachments.isEmpty, composerFileImports == 0 else { return }
        guard voiceDraft == nil && !stagingVoice else {
            messagesRepository.reportError(UnyriL10n.string("Send or discard the voice draft first.")); return
        }
        guard !voiceRecorder.callInProgress else { return }
        startingVoice = true
        let generation = auth.sessionGeneration
        let recorder = videoNoteRecorder
        Task { @MainActor in
            var claimed: NonCallCaptureOwnership.Claim?
            var transferred = false
            defer {
                startingVoice = false
                if let claimed {
                    if pendingVoiceClaim == claimed { pendingVoiceClaim = nil }
                    if !transferred { NonCallCaptureOwnership.release(claimed) }
                }
            }
            do {
                // A quick cancel-and-hold-again: the previous take may still be closing the camera.
                await recorder.settle()
                guard let claim = try await NonCallCaptureOwnership.prepare(.camera, generation: generation, stop: { claim in
                    if pendingVoiceClaim == claim { pendingVoiceClaim = nil; startingVoice = false }
                    NonCallCaptureOwnership.release(claim)
                }) else { return }
                claimed = claim
                pendingVoiceClaim = claim
                let access = await VideoNoteRecorder.requestAccess()
                guard generation == auth.sessionGeneration, NonCallCaptureOwnership.isCurrent(claim),
                      !voiceRecorder.callInProgress else { return }
                guard scenePhase == .active, conversationVisible, !isRecording, composerAttachments.isEmpty, composerFileImports == 0 else { return }
                // Denied: the stage offers to open Settings (iOS asks only once).
                guard access == .granted else { recorder.accessProblem = access; return }
                // Released while iOS was asking, or before the camera could be claimed.
                guard !holding || recordingHoldActive else { return }
                voicePlayer.stop()
                UnyriVideoNotePlayer.shared.stop()
                try recorder.start(captureClaim: claim)
                transferred = true
                recorder.onInterrupted = {
                    // A call, Siri or leaving the app stopped the camera. The take is kept:
                    // show the locked controls (send or discard), as a cancelled hold does.
                    recordingLocked = true; recordingHoldActive = false; recordingDrag = .zero; voiceHoldAxis = nil
                }
                recorder.onLimitReached = { finishRecording(send: true) }
                recorder.onFailed = {
                    recordingVideoNote = false; isRecording = false
                    recordingHoldActive = false; recordingDrag = .zero; voiceHoldAxis = nil
                    messagesRepository.reportError(UnyriL10n.string("Could not start recording."))
                }
                UnyriHaptics.press()
                recordingVideoNote = true
                isRecording = true
                recordingSeconds = 0
            } catch is CancellationError {
            } catch {
                messagesRepository.reportError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        }
    }

    private func finishVideoNote() {
        guard isRecording, recordingVideoNote else { return }
        isRecording = false
        recordingVideoNote = false
        recordingSeconds = 0
        let recorder = videoNoteRecorder
        let generation = auth.sessionGeneration
        Task { @MainActor in
            guard let recording = await recorder.finish() else {
                showVoiceHoldHint(ChatVideoNoteMode.hint(video: true)); return
            }
            guard generation == auth.sessionGeneration else {
                try? FileManager.default.removeItem(at: recording.fileURL); return
            }
            // An accidental tap-and-release is not a message.
            guard Double(recording.durationMs) >= VideoNoteLimits.minSeconds * 1000 else {
                try? FileManager.default.removeItem(at: recording.fileURL)
                showVoiceHoldHint(ChatVideoNoteMode.hint(video: true))
                return
            }
            UnyriHaptics.selection()
            await sendVideoNote(recording)
        }
    }

    /// Through the ordinary media outbox: an older client or Android shows a video.
    private func sendVideoNote(_ recording: VideoNoteRecording) async {
        // Voice and media do not carry a quote yet; the reply must not silently attach
        // to the next text instead.
        replyTarget = nil
        let repository = messagesRepository
        let id = UUID().uuidString.lowercased()
        // The queued row's first render already has the sharp frame.
        if let poster = recording.poster { VideoNotePosters.store(poster, clientMessageId: id) }
        var queued = false
        // The poster is held until a row adopts or cancels it; without a row, nothing would.
        defer { if !queued { VideoNotePosters.forgetSend(clientMessageId: id) } }
        defer { try? FileManager.default.removeItem(at: recording.fileURL) }
        do {
            let staged = try await repository.stageDraftFile(recording.fileURL, isVideo: true)
            // The recorder knows the size and length: no asset probe before the row appears.
            queued = await repository.queueVisualMedia(id: id, chatId: chat.chatId, otherUserId: chat.otherUser?.id,
                url: staged, kind: "video_note", caption: nil, albumId: nil,
                visual: QueuedMediaVisual(width: VideoNoteLimits.side, height: VideoNoteLimits.side, durationMs: recording.durationMs))
            if !queued { repository.discardUnownedDraftSource(staged) }
        } catch is CancellationError {
        } catch { repository.reportError(error.localizedDescription) }
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
                        }.pickerStyle(.menu).tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC))).frame(minHeight: 44).accessibilityIdentifier("chat-report-reason")
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
            .tint(appColorStyle.controlTint(Color(hex: 0x7BC9EC)))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { showChatSafety = false }.disabled(preferenceBusy).accessibilityIdentifier("chat-safety-close") } }
            .confirmationDialog("Block contact", isPresented: $confirmChatBlock, titleVisibility: .visible) {
                Button("Block contact", role: .destructive) { changeChatPreference("block") }
                Button("Cancel", role: .cancel) {}
            } message: { Text("New messages and calls in both directions will be blocked until you unblock this contact. Existing history stays.") }
            .interactiveDismissDisabled(preferenceBusy)
        }
    }

    /// Everything Send depends on except the text; the composer combines it with the text.
    private var composerSendGate: ChatComposerSendGate {
        guard !composerBatchRestoreFailed, composerBatch?.autoSend != true else { return .blocked }
        guard composerBatchRestored, !composerBatchRestoring else { return .restoring }
        if editingMessage == nil && composerFileImports > 0 { return .blocked }
        if editingMessage != nil || composerAttachments.isEmpty { return isSending ? .blocked : .needsText }
        return !isSending && !mediaPreparationActive && composerFileImports == 0 ? .ready : .blocked
    }

    /// Read by actions at the moment they run (never in body).
    private var composerCanSend: Bool { composerSendGate.allowsSend(blank: composerModel.isBlank) }

    /// Picked photos, videos and files sit inside the composer's glass, above the text
    /// (as in iMessage). They used to be a separate chat-coloured strip above the
    /// composer that almost vanished on a dark chat.
    /// Picked items fly in (ChatComposerTrayEntrance) and the strip scrolls to the newest.
    private var composerAttachmentStrip: some View {
        let hasDocument = composerAttachments.contains(where: { $0.isDocument })
        let showsOrder = composerAttachments.filter { !$0.isDocument }.count > 1
        let landings = composerLandings
        let plain: AnyTransition = composerReduceMotion ? .opacity : .scale(scale: 0.85).combined(with: .opacity)
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(composerAttachments.enumerated()), id: \.element.id) { index, item in
                        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
                        let status = composerDirectStatus(item)
                        let arrival = item.entrance.arrival
                        ChatComposerThumbnail(item: item, directStatus: status)
                            .frame(width: item.isDocument ? 200 : 64, height: item.isDocument ? composerDocumentHeight : 64)
                            .clipShape(shape)
                            .overlay { shape.strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5) }
                            .overlay(alignment: .bottomLeading) {
                                if showsOrder && !item.isDocument {
                                    Text("\(index + 1)").font(.system(size: 11, weight: .semibold)).monospacedDigit().foregroundStyle(.white)
                                        .frame(width: 18, height: 18).background(.black.opacity(0.55), in: Circle()).padding(4)
                                        .accessibilityHidden(true)
                                }
                            }
                            .overlay {
                                if let status, !item.isDocument {
                                    ZStack {
                                        Color.black.opacity(0.45)
                                        if status == .preparing { ProgressView().controlSize(.small).tint(.white) }
                                        else { Image(systemName: "clock").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white.opacity(0.9)) }
                                    }.allowsHitTesting(false)
                                }
                            }
                            .overlay(alignment: .topTrailing) {
                                Button { removeComposerAttachment(item.id) } label: {
                                    Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(.white)
                                        .frame(width: 20, height: 20).background(.black.opacity(0.6), in: Circle())
                                        .padding(4)
                                        .frame(width: 44, height: 44, alignment: .topTrailing).contentShape(Rectangle())
                                }.accessibilityLabel("Remove attachment").accessibilityIdentifier("composer-remove-\(index)")
                            }
                            .modifier(ChatComposerTrayEntrance(flies: item.entrance.flies && !landings.arrivals.contains(arrival),
                                reduceMotion: composerReduceMotion, order: item.entrance.order,
                                landed: { _ = landings.arrivals.insert(arrival) }))
                            // A flying item brings its own entrance; the rest keep the plain one.
                            .transition(.asymmetric(insertion: item.entrance.flies ? .identity : plain, removal: plain))
                            .id(item.id)
                    }
                }.padding(.horizontal, 8).padding(.top, 8).padding(.bottom, 6)
            }
            .frame(height: (hasDocument ? max(64, composerDocumentHeight) : 64) + 14)
            .accessibilityIdentifier("composer-attachments")
            // New items join at the end: bring the newest into view once it is laid out.
            .onChange(of: composerTray.scrollRequest) { _ in
                guard let target = composerTray.scrollTarget else { return }
                withAnimation(composerReduceMotion ? nil : .easeOut(duration: 0.25)) { proxy.scrollTo(target, anchor: .center) }
            }
            .onChange(of: composerAttachments.last?.id) { last in
                guard let last else { return }
                let reduceMotion = composerReduceMotion
                DispatchQueue.main.async {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) { proxy.scrollTo(last, anchor: .trailing) }
                }
            }
        }
    }

    /// A picked file that is being sent directly: prepared and offered now, or waiting
    /// for the one before it (one at a time). Nothing for an ordinary draft.
    private func composerDirectStatus(_ item: ChatComposerAttachment) -> ChatComposerDirectStatus? {
        if item.isImporting { return .preparing }
        guard let batch = composerBatch, batch.autoSend == true,
              let member = batch.pending.first(where: { $0.sourceID == item.id }) else { return nil }
        _ = messagesRepository.composerDirectActivity[chat.chatId]
        return messagesRepository.directFiles?.composerActiveMemberID(chatId: chat.chatId) == member.id ? .preparing : .queued
    }

    private func removeComposerAttachment(_ id: String) {
        guard let item = composerAttachments.first(where: { $0.id == id }) else { return }
        // Only an item of the running Send (captured by its task, or a pending member of
        // its batch) stops that send. An item added while it prepares just leaves the tray.
        let member = composerBatch?.pending.contains { $0.sourceID == id } == true
        let captured = composerDirectTask != nil && composerTray.sendingIDs.contains(id)
        // Journal reads and preparation callbacks for the old selection become stale.
        if member || captured { composerSendToken = UUID() }
        if captured { composerDirectTask?.cancel(); messagesRepository.directFiles?.cancelPreparing() }
        if member && mediaPreparationActive { messagesRepository.cancelMediaPreparation(chatId: chat.chatId) }
        if member, var batch = composerBatch {
            let accepted = Set(batch.acceptedIDs)
            batch.members.removeAll { $0.sourceID == id && !accepted.contains($0.id) }
            let retained: MediaComposerBatch? = batch.pending.isEmpty ? nil : batch
            guard messagesRepository.saveComposerBatch(retained, chatId: chat.chatId) else { return }
            composerBatch = retained
            messagesRepository.directFiles?.composerDraftChanged(chatId: chat.chatId)
        }
        UnyriHaptics.selection()
        withAnimation(composerReduceMotion ? nil : .easeInOut(duration: 0.16)) {
            composerAttachments.removeAll { $0.id == id }
        }
        if let url = item.ownedURL { messagesRepository.discardUnownedDraftSource(url) }
    }

    private func clearComposerAttachments() {
        composerTray.clear(repository: messagesRepository, chatID: chat.chatId)
    }
    // Visible placeholders reserve capacity once: a Photos result may return while
    // a previously admitted Files item is awaiting its provider copy.
    private var currentDirectSelection: DirectSendSelectionContext {
        DirectSendSelectionContext(server: auth.authenticatedServerURL, owner: auth.currentUser?.id, generation: auth.sessionGeneration)
    }
    private var composerDirectIssue: String? {
        messagesRepository.directFiles?.destinationIssue(currentPreferenceChat) ??
            (messagesRepository.directFiles == nil ? DirectFileDestinationPolicy.unavailable : nil)
    }
    private var directPreferenceBinding: Binding<Bool> {
        _ = directPreferenceRevision
        let server = auth.authenticatedServerURL, owner = auth.currentUser?.id
        let scope = DirectSendPreference.scope(server: server, owner: owner)
        return Binding(get: { DirectSendPreference.enabled(server: server, owner: owner) }, set: { enabled in
            guard scope != nil, scope == DirectSendPreference.scope(server: auth.authenticatedServerURL, owner: auth.currentUser?.id),
                  !enabled || composerDirectIssue == nil else { return }
            DirectSendPreference.set(enabled, server: server, owner: owner)
            directPreferenceRevision &+= 1
        })
    }
    private var composerSelectionRemaining: Int { max(0, 10 - (composerBatch?.acceptedIDs.count ?? 0) - composerAttachments.count) }
    private func admitComposerSelectionCount(_ count: Int) -> Bool {
        guard count > 0 else { return false }
        let remaining = composerSelectionRemaining
        guard count <= remaining else {
            messagesRepository.reportError(String(format: UnyriL10n.string("Selected %d items. You can add %d more (maximum %d attachments). Nothing was added. Choose fewer items and try again."), count, remaining, 10))
            return false
        }
        return true
    }

    private func addComposerPhotos(_ items: [PhotosPickerItem]) {
        photoItems = []
        var identifiers = Set(composerAttachments.map(\.id))
        let candidates = items.compactMap { item -> (String, PhotosPickerItem)? in
            // The asset id (the picker has the photo library), as the in-app gallery uses;
            // a random id only for an item outside the library, which has no id to match.
            let id = item.itemIdentifier ?? UUID().uuidString
            return identifiers.insert(id).inserted ? (id, item) : nil
        }
        guard !candidates.isEmpty else {
            if !items.isEmpty { announceComposerFill(0, existingID: items.compactMap(\.itemIdentifier).first) }
            return
        }
        guard admitComposerSelectionCount(candidates.count) else { return }
        let quality = MediaGallerySendChoice.saved().intent()
        for (order, candidate) in candidates.enumerated() {
            let (id, item) = candidate
            composerAttachments.append(ChatComposerAttachment(id: id, source: .photo(item),
                kind: quality.kind(isVideo: item.supportedContentTypes.contains { $0.conforms(to: .movie) }),
                entrance: ChatComposerEntrance(flies: true, order: order)))
        }
        composerModel.focus()
        announceComposerFill(candidates.count)
    }

    /// Keep these new messages localized while their catalog entries are integrated.
    /// An existing catalog translation always wins; this does not alter global localization.
    private func localizedComposerFeedback(_ key: String, ru: String, uk: String) -> String {
        let translated = UnyriL10n.string(key)
        guard translated == key else { return translated }
        switch UnyriL10n.language {
        case "ru": return ru
        case "uk": return uk
        default: return translated
        }
    }

    private func showComposerFeedback(_ text: String) {
        let tray = composerTray
        let token = UUID(); tray.feedbackToken = token; tray.feedback = text
        UIAccessibility.post(notification: .announcement,
            argument: NSAttributedString(string: text, attributes: [.accessibilitySpeechQueueAnnouncement: true]))
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard tray.feedbackToken == token else { return }
            tray.feedback = nil
        }
    }

    /// A duplicate selection is visible feedback too, and reveals the existing item.
    private func announceComposerFill(_ added: Int, existingID: String? = nil) {
        if added == 0 {
            composerTray.scrollTarget = existingID ?? composerAttachments.first?.id
            composerTray.scrollRequest = UUID()
            UnyriHaptics.selection()
            showComposerFeedback(localizedComposerFeedback("Already added", ru: "Уже добавлено", uk: "Вже додано"))
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + (composerReduceMotion ? 0.05 : 0.25)) { UnyriHaptics.press() }
        showComposerFeedback(String(format: UnyriL10n.string("%d added"), added))
    }

    /// Send now falls back to an explicit, reviewable Add when another Send owns
    /// preparation. It never silently queues an automatic send or drops a selection.
    private var canSendPickedAttachmentsNow: Bool {
        editingMessage == nil && composerBatchRestored && !composerBatchRestoring && !composerBatchRestoreFailed &&
        composerBatch?.autoSend != true && !isSending && !mediaPreparationActive && composerFileImports == 0 &&
        composerModel.text.count <= 4096
    }

    private func announceDeferredComposerSend() {
        if composerModel.text.count > 4096 {
            showComposerFeedback(UnyriL10n.string("Caption can contain up to 4096 characters."))
        } else {
            showComposerFeedback(localizedComposerFeedback("Added to tray. Tap Send after the current send finishes.",
                ru: "Добавлено в лоток. Нажмите «Отправить», когда текущая отправка завершится.",
                uk: "Додано до лотка. Натисніть «Надіслати», коли поточне надсилання завершиться."))
        }
    }

    /// Eligibility is checked at invocation, including keyboard suggestions delivered
    /// to the still-mounted input under the recording bar. Text paste remains allowed.
    private var composerPasteIssue: String? {
        if isRecording || startingVoice || voiceDraft != nil || stagingVoice {
            return "Send or discard the voice draft first."
        }
        if currentPreferenceChat.isBlocked == true { return "Contact blocked. Open chat settings" }
        if editingMessage != nil { return "Finish editing before adding attachments." }
        if currentDirectSelection.requested, let issue = composerDirectIssue { return issue }
        if composerBatchRestoreFailed { return "Could not verify the saved attachment draft. Your caption and selected items have been kept." }
        if !composerBatchRestored || composerBatchRestoring || composerBatch?.autoSend == true || !selectedMessages.isEmpty {
            return "Attachments cannot be added right now. Finish the current action and try again."
        }
        return nil
    }

    private func pasteComposerMedia(_ providers: [NSItemProvider], changeCount: Int) {
        let intent = MediaGallerySendChoice.saved().intent()
        // Text items do not renumber media: the keyboard can supply just the media
        // while the edit menu supplies the mixed clipboard, with the same identities.
        let mediaProviders = providers.compactMap { provider -> (NSItemProvider, ChatComposerPastePolicy.Media)? in
            ChatComposerInputView.pastedMedia(provider).map { (provider, $0) }
        }
        let selections = mediaProviders.enumerated().map { index, value in
            let (provider, media) = value
            let identity = ChatComposerPastePolicy.identity(changeCount: changeCount, itemIndex: index)
            return ChatComposerFileSelection(source: .provider(provider, media),
                identityURL: FileManager.default.temporaryDirectory.appendingPathComponent(identity),
                kind: media.kind == .animatedImage ? "file" : intent.kind(isVideo: media.isVideo), isVideo: media.isVideo)
        }
        guard !selections.isEmpty else { return }
        let context = currentDirectSelection; let token = composerImportToken
        composerTray.imports.start {
            guard token == composerImportToken,
                  context.isCurrent(server: auth.authenticatedServerURL, owner: auth.currentUser?.id, generation: auth.sessionGeneration) else { return }
            if let issue = composerPasteIssue {
                let text = UnyriL10n.string(issue)
                messagesRepository.reportError(text)
                showComposerFeedback(text)
                return
            }
            await addComposerFiles(selections, context: context, flies: true)
        }
    }

    @discardableResult
    private func addComposerFiles(_ documents: [AttachmentFileCopy.DocumentSource], context: DirectSendSelectionContext, flies: Bool = false, deferredSend: Bool = false) async -> ChatComposerImport {
        await addComposerFiles(documents.map { ChatComposerFileSelection(source: .document($0), identityURL: $0.url.standardizedFileURL) },
            context: context, flies: flies, deferredSend: deferredSend)
    }

    @discardableResult
    private func addComposerFiles(_ selections: [ChatComposerFileSelection], context: DirectSendSelectionContext, flies: Bool = false, deferredSend: Bool = false) async -> ChatComposerImport {
        guard context.isCurrent(server: auth.authenticatedServerURL, owner: auth.currentUser?.id, generation: auth.sessionGeneration),
              editingMessage == nil else { return .nothing }
        let token = composerImportToken; let generation = auth.sessionGeneration
        let repository = messagesRepository
        // Compare the provider URL, never the newly staged URL or a fresh UUID.
        var identities = Set(composerAttachments.compactMap { $0.documentURL?.standardizedFileURL })
        let candidates = selections.filter { identities.insert($0.identityURL.standardizedFileURL).inserted }
        guard !candidates.isEmpty else {
            if let first = selections.first {
                let existing = composerAttachments.first { $0.documentURL?.standardizedFileURL == first.identityURL.standardizedFileURL }
                announceComposerFill(0, existingID: existing?.id)
            }
            return .nothing
        }
        guard admitComposerSelectionCount(candidates.count) else { return .nothing }
        // Reserve every visible slot before any provider metadata or copy can suspend.
        let slots = candidates.enumerated().map { order, selection in
            ChatComposerAttachment(id: UUID().uuidString.lowercased(), source: selection.placeholder, kind: selection.kind,
                entrance: flies ? ChatComposerEntrance(flies: true, order: order) : .settled,
                documentURL: selection.identityURL, isImporting: true)
        }
        composerAttachments.append(contentsOf: slots)
        if flies {
            announceComposerFill(slots.count)
            if deferredSend { announceDeferredComposerSend() }
        }
        // Tasks retain their DocumentSources (and grants) across all awaits. A removed
        // slot is skipped individually; clear/session changes retire this whole import.
        defer {
            let pending = Set(slots.map(\.id))
            // Every write publishes the tray: write only when a placeholder is left over.
            if token == composerImportToken, composerAttachments.contains(where: { pending.contains($0.id) && $0.isImporting }) {
                composerAttachments.removeAll { pending.contains($0.id) && $0.isImporting }
            }
            withExtendedLifetime(selections) {}
        }
        let directIssue = repository.directFiles?.destinationIssue(currentPreferenceChat) ??
            (repository.directFiles == nil ? DirectFileDestinationPolicy.unavailable : nil)
        var completed = true
        for (slot, selection) in zip(slots, candidates) {
            guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else { return .nothing }
            guard composerAttachments.contains(where: { $0.id == slot.id }) else { continue }
            do {
                let document = try await selection.document()
                guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else { return .nothing }
                guard composerAttachments.contains(where: { $0.id == slot.id }) else { continue }
                defer { withExtendedLifetime(document) {} }
                let size = try? await repository.draftFileSize(document.url)
                guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else { return .nothing }
                guard composerAttachments.contains(where: { $0.id == slot.id }) else { continue }
                let route = DirectFileDestinationPolicy.importRoute(size: size,
                    supportsDirect: directIssue == nil, ordinaryLimit: AttachmentManager.maxFileBytes, prefersDirect: context.requested)
                let source: ChatComposerAttachment.Source
                let plan = selection.filePlan(retainExternalDocument: selection.kind == "file" && (route == .unsupportedLarge || route == .direct))
                if !plan.owned {
                    source = .document(document, size)
                } else {
                    let durable = try await repository.stageDraftFile(document.url, isVideo: selection.isVideo,
                        coordinated: true, maximumBytes: plan.maximumBytes)
                    guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else {
                        repository.discardUnownedDraftSource(durable); return .nothing
                    }
                    guard composerAttachments.contains(where: { $0.id == slot.id }) else {
                        repository.discardUnownedDraftSource(durable); continue
                    }
                    let bytes = (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    source = .file(StoredMediaDraft(id: slot.id, url: durable, bytes: bytes, isVideo: selection.isVideo))
                }
                if let index = composerAttachments.firstIndex(where: { $0.id == slot.id }) {
                    composerAttachments[index] = ChatComposerAttachment(id: slot.id, source: source, kind: slot.kind,
                        entrance: slot.entrance, documentURL: slot.documentURL)
                }
            } catch {
                guard token == composerImportToken, generation == auth.sessionGeneration, !Task.isCancelled else { return .nothing }
                completed = false
                composerAttachments.removeAll { $0.id == slot.id }
                if directIssue != nil, (error as? AttachmentFileCopy.CopyError) == .tooLarge {
                    repository.reportError(UnyriL10n.string("This file exceeds 100 MB, and direct transfer is unavailable in this conversation."))
                } else {
                    let key = (error as? DirectComposerOriginal.PreparationFailure)?.message ?? MediaPreparationFailure.messageKey(error)
                    repository.reportError(UnyriL10n.string(key))
                }
                // A failed provider must not discard the rest of the user's selection.
            }
        }
        let landed = Set(composerAttachments.map(\.id)).intersection(slots.map(\.id))
        if completed && landed.count == slots.count { return .complete }
        return landed.isEmpty ? .nothing : .partial
    }

    private func captureComposerBatch(_ items: [ChatComposerAttachment], capturedDraft: String) async -> MediaComposerBatch? {
        guard composerBatchRestored, !composerBatchRestoring, !composerBatchRestoreFailed else { return nil }
        let generation = auth.sessionGeneration; let token = composerSendToken
        var batch = composerBatch ?? MediaComposerBatch(intent: MediaSendIntent(caption: capturedDraft.trimmingCharacters(in: .whitespacesAndNewlines)),
            directSend: currentDirectSelection.requested,
            directChat: currentDirectSelection.requested ? (try? JSONEncoder().encode(currentPreferenceChat)) : nil)
        let references: [MediaComposerSource] = items.compactMap { item in
            switch item.source {
            case .file(let value): return MediaComposerSource(assetIdentifier: nil, url: value.url, bookmark: nil, owned: true,
                name: value.url.lastPathComponent, isVideo: value.isVideo, bytes: Int64(value.bytes))
            case .document(let value, let bytes): return MediaComposerSource(assetIdentifier: nil, url: value.url, bookmark: nil, owned: false,
                name: value.url.lastPathComponent, isVideo: false, bytes: bytes)
            case .gallery(.asset(let value)): return MediaComposerSource(assetIdentifier: value.localIdentifier, url: nil, bookmark: nil, owned: false,
                name: UnyriL10n.string(item.isVideo ? "Video" : "Photo"), isVideo: item.isVideo, bytes: nil)
            case .gallery(.capture(let url)): return MediaComposerSource(assetIdentifier: nil, url: url, bookmark: nil, owned: true,
                name: url.lastPathComponent, isVideo: item.isVideo, bytes: nil)
            case .photo(let value): return MediaComposerSource(assetIdentifier: value.itemIdentifier, url: nil, bookmark: nil, owned: false,
                name: UnyriL10n.string(item.isVideo ? "Video" : "Photo"), isVideo: item.isVideo, bytes: nil)
            case .unavailable(let value): return value.source
            case .importing: return nil
            }
        }
        guard references.count == items.count else { return nil }
        // Provider bookmark work is outside MainActor, retaining the picked scopes until complete.
        let sources = await Task.detached(priority: .utility) {
            defer { withExtendedLifetime(items) {} }
            return references.map { source -> MediaComposerSource in
                var source = source
                if !source.owned, source.bookmark == nil, let url = source.url {
                    source.bookmark = try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
                }
                return source
            }
        }.value
        var captionCommitted = batch.members.contains { $0.index == 0 && batch.acceptedIDs.contains($0.id) }
        if composerBatch != nil, !captionCommitted, let first = batch.members.first(where: { $0.index == 0 }) {
            let authoritative: MediaComposerBatch?
            do { authoritative = try await messagesRepository.loadComposerBatch(chatId: chat.chatId) }
            catch { return nil }
            guard generation == auth.sessionGeneration, token == composerSendToken, !Task.isCancelled else { return nil }
            guard let authoritative, authoritative.intent.albumId == batch.intent.albumId,
                  authoritative.members.contains(where: { $0.id == first.id }) else {
                messagesRepository.reportError(UnyriL10n.string("Could not verify the saved attachment draft. Your caption and selected items have been kept."))
                return nil
            }
            captionCommitted = authoritative.acceptedIDs.contains(first.id)
        }
        guard generation == auth.sessionGeneration, token == composerSendToken, !Task.isCancelled,
              composerBatchRestored, !composerBatchRestoreFailed,
              Set(items.map(\.id)).isSubset(of: Set(composerAttachments.map(\.id))) else { return nil }
        if !captionCommitted {
            batch.intent = MediaSendIntent(mode: batch.intent.mode, highQuality: batch.intent.highQuality,
                caption: capturedDraft.trimmingCharacters(in: .whitespacesAndNewlines), albumId: batch.intent.albumId)
        }
        let accepted = batch.members.filter { batch.acceptedIDs.contains($0.id) }
        let prior = batch.pending
        guard accepted.count + items.count <= 10 else { return nil }
        let next = (accepted.map(\.index).max() ?? -1) + 1
        batch.members = accepted + items.enumerated().map { index, item in
            let existing = prior.first { $0.sourceID == item.id }
            return MediaComposerBatch.Member(sourceID: item.id, id: existing?.id ?? UUID().uuidString.lowercased(),
                index: next + index, kind: item.kind, source: sources[index], prepared: existing?.prepared)
        }
        guard batch.isValid else {
            messagesRepository.reportError(UnyriL10n.string("Could not save the selected attachments. Your selection has been kept.")); return nil
        }
        if batch.directSend == true { batch.autoSend = true }
        guard messagesRepository.saveComposerBatch(batch, chatId: chat.chatId) else { return nil }
        composerBatch = batch
        return batch
    }
    // Automatic admission/retirement is synchronous and must not inherit tray count animation.
    private func updateAdmittedComposerState(_ updates: () -> Void) {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction, updates)
    }

    private func consumeComposerMember(_ item: ChatComposerAttachment, member: MediaComposerBatch.Member,
                                       prepared: StoredMediaDraft?, capturedDraft: String) {
        guard var batch = composerBatch, batch.members.contains(where: { $0.id == member.id }),
              !batch.acceptedIDs.contains(member.id) else { return }
        if let prepared { batch.prepared(prepared, for: member.id) }
        batch.accepted(member.id)
        let remaining: MediaComposerBatch? = batch.pending.isEmpty ? nil : batch
        // If this write is interrupted, loadComposerBatch reconciles the committed outbox row.
        _ = messagesRepository.saveComposerBatch(remaining, chatId: chat.chatId)
        updateAdmittedComposerState {
            composerBatch = remaining
            composerAttachments.removeAll { $0.id == item.id }
            if let file = item.ownedURL { messagesRepository.discardUnownedDraftSource(file) }
            if member.index == 0, composerModel.text == capturedDraft,
               capturedDraft.trimmingCharacters(in: .whitespacesAndNewlines) == batch.intent.caption { updateComposerDraft("") }
        }
    }

    private func refreshComposerDirectBatch() {
        let generation = auth.sessionGeneration; let token = composerSendToken
        let revision = messagesRepository.composerDirectRevisions[chat.chatId, default: 0]
        let oldPending = Set(composerBatch?.pending.map(\.sourceID) ?? [])
        Task {
            do {
                let fresh = try await messagesRepository.loadComposerBatch(chatId: chat.chatId)
                guard generation == auth.sessionGeneration, token == composerSendToken,
                      revision == messagesRepository.composerDirectRevisions[chat.chatId, default: 0] else { return }
                let pending = Set(fresh?.pending.map(\.sourceID) ?? [])
                let retired = oldPending.subtracting(pending)
                let urls = composerAttachments.filter { retired.contains($0.id) }.compactMap(\.ownedURL)
                updateAdmittedComposerState {
                    composerAttachments.removeAll { retired.contains($0.id) }
                    composerBatch = fresh?.pending.isEmpty == false ? fresh : nil
                    for url in urls { messagesRepository.discardUnownedDraftSource(url) }
                    if let fresh, let captionMember = fresh.members.first(where: { $0.index == 0 }),
                       oldPending.contains(captionMember.sourceID), !pending.contains(captionMember.sourceID),
                       fresh.acceptedIDs.contains(captionMember.id),
                       composerModel.text.trimmingCharacters(in: .whitespacesAndNewlines) == fresh.intent.caption { updateComposerDraft("") }
                }
            } catch {
                guard generation == auth.sessionGeneration, token == composerSendToken else { return }
                composerBatchRestoreFailed = true; composerBatchRestored = false
            }
        }
    }

    private func restoreComposerBatchIfNeeded() {
        guard !composerBatchRestored, !composerBatchRestoring else { return }
        composerBatchRestoring = true; composerBatchRestoreFailed = false
        let generation = auth.sessionGeneration; let token = composerSendToken; let repository = messagesRepository
        let draftRevision = composerModel.revision
        let directRevision = repository.composerDirectRevisions[chat.chatId, default: 0]
        Task {
            let batch: MediaComposerBatch?
            do { batch = try await repository.loadComposerBatch(chatId: chat.chatId) }
            catch {
                guard generation == auth.sessionGeneration, token == composerSendToken else { return }
                guard directRevision == repository.composerDirectRevisions[chat.chatId, default: 0] else {
                    composerBatchRestoring = false
                    restoreComposerBatchIfNeeded()
                    return
                }
                composerBatchRestoring = false; composerBatchRestoreFailed = true; composerBatchRestored = false
                return
            }
            guard generation == auth.sessionGeneration, token == composerSendToken else { return }
            guard directRevision == repository.composerDirectRevisions[chat.chatId, default: 0] else {
                composerBatchRestoring = false
                restoreComposerBatchIfNeeded()
                return
            }
            guard let batch, !batch.pending.isEmpty else {
                composerBatchRestoring = false; composerBatchRestored = true; return
            }
            let restored = await Task.detached(priority: .utility) {
                let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                return batch.pending.map { member -> ChatComposerAttachment in
                    let source: ChatComposerAttachment.Source
                    if let prepared = member.prepared, FileManager.default.fileExists(atPath: prepared.url.path) {
                        source = .file(prepared)
                    } else if let identifier = member.source.assetIdentifier,
                              access == .authorized || access == .limited,
                              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject {
                        source = .gallery(.asset(asset))
                    } else if let url = member.source.url, member.source.owned, FileManager.default.fileExists(atPath: url.path) {
                        source = .file(StoredMediaDraft(id: member.id, url: url, bytes: Int(member.source.bytes ?? 0), isVideo: member.source.isVideo))
                    } else if let bookmark = member.source.bookmark {
                        var stale = false
                        if let url = try? URL(resolvingBookmarkData: bookmark, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale) {
                            source = .document(AttachmentFileCopy.DocumentSource(url), member.source.bytes)
                        } else { source = .unavailable(member) }
                    } else { source = .unavailable(member) }
                    var item = ChatComposerAttachment(id: member.sourceID, source: source, kind: member.kind, entrance: .settled)
                    if case .document(let document, _) = source { item.documentURL = document.url }
                    return item
                }
            }.value
            guard generation == auth.sessionGeneration, token == composerSendToken else { return }
            guard directRevision == repository.composerDirectRevisions[chat.chatId, default: 0] else {
                composerBatchRestoring = false
                restoreComposerBatchIfNeeded()
                return
            }
            composerBatch = batch
            if composerAttachments.isEmpty { composerAttachments = restored }
            if composerModel.revision == draftRevision, editingMessage == nil, composerModel.text.isEmpty,
               batch.pending.contains(where: { $0.index == 0 }) { updateComposerDraft(batch.intent.caption) }
            composerBatchRestoring = false; composerBatchRestored = true
        }
    }

    /// An item over the 100 MB server limit: offer the direct route in one tap instead of
    /// an error telling the user to find a checkbox. The selected items are kept.
    private func offerDirectForLargeOriginal() {
        if composerDirectIssue == nil { showDirectOffer = true }
        else { messagesRepository.reportError(UnyriL10n.string("This file exceeds 100 MB, and direct transfer is unavailable in this conversation.")) }
    }

    private func sendComposerAttachments() {
        guard composerCanSend else { return }
        guard composerModel.text.count <= 4096 else { messagesRepository.reportError(UnyriL10n.string("Caption can contain up to 4096 characters.")); return }
        let items = composerAttachments
        replyTarget = nil
        let capturedDraft = composerModel.text
        let context = currentDirectSelection
        let tray = composerTray; let operationID = UUID()
        tray.sendOperationID = operationID; tray.sendingIDs = Set(items.map(\.id))
        if context.requested || composerBatch?.directSend == true || items.contains(where: { $0.directOriginal != nil }) {
            isSending = true
            composerDirectTask = Task {
                defer { tray.finishSend(operationID) }
                await sendOriginalComposerAttachments(items, capturedDraft: capturedDraft, context: context)
            }
            return
        }
        isSending = true
        composerDirectTask = Task {
            defer { tray.finishSend(operationID) }
            await enqueueComposerAttachments(items, capturedDraft: capturedDraft)
        }
    }

    /// Documents feed the attach sheet's "Recently sent files" (local copies, ≤100 MB).
    private func rememberRecentFiles(_ items: [ChatComposerAttachment]) async throws {
        let files = items.filter { $0.kind == "file" }
        let sources = files.compactMap { item -> UnyriRecentFiles.Source? in
            switch item.source {
            case .file(let file): return UnyriRecentFiles.Source(url: file.url)
            case .document(let source, let bytes) where (bytes ?? 0) <= UnyriRecentFiles.maxBytes:
                return UnyriRecentFiles.Source(url: source.url, keepAlive: source)
            default: return nil
            }
        }
        guard !sources.isEmpty else { return }
        // Capture the destination account before suspending. The repository holds
        // additional source references even if the tray/pending batch is cancelled.
        let destination = UnyriRecentFiles.folder(for: UnyriActiveAccountSlot.current)
        try await messagesRepository.copyRecentFiles(sources: files.compactMap(\.ownedURL), on: UnyriRecentFiles.copyQueue) { cancellation in
            try UnyriRecentFiles.record(sources, into: destination, cancellation: cancellation)
        }
    }

    private func sendOriginalComposerAttachments(_ items: [ChatComposerAttachment], capturedDraft: String, context: DirectSendSelectionContext) async {
        let generation = auth.sessionGeneration
        let tray = composerTray; let token = tray.sendToken
        var originals: [String: DirectComposerOriginal.Original] = [:]
        defer {
            for original in originals.values { Task { await original.cleanup() } }
        }
        guard let batch = await captureComposerBatch(items, capturedDraft: capturedDraft) else { return }
        let intent = batch.intent
        func checkContext() throws {
            try Task.checkCancellation()
            guard tray.sendToken == token,
                  context.isCurrent(server: auth.authenticatedServerURL, owner: auth.currentUser?.id, generation: auth.sessionGeneration) else { throw CancellationError() }
        }
        do {
            try checkContext()
            try await rememberRecentFiles(items)
            try checkContext()
            if batch.directSend == true {
                guard let transfers = messagesRepository.directFiles else { throw DirectFileTransfers.Failure.unsupported }
                if let issue = transfers.destinationIssue(currentPreferenceChat) { messagesRepository.reportError(UnyriL10n.string(issue)); return }
                let accepted = transfers.enqueueComposerBatch(batch, chat: currentPreferenceChat) { member in
                    guard let item = items.first(where: { $0.id == member.sourceID }), let source = item.originalSource else { throw CocoaError(.fileReadNoSuchFile) }
                    let original = try await DirectComposerOriginal.prepared(source, kind: item.kind)
                    // Captured items own provider grants for the entire asynchronous source/offer admission.
                    withExtendedLifetime(items) {}
                    return DirectFileTransfers.ComposerPrepared(original: original, document: nil)
                }
                if !accepted { messagesRepository.reportError(UnyriL10n.string(transfers.errorMessage ?? "Could not queue the selected files.")) }
                return
            }
            // Normal transport never silently becomes direct because Original was selected.
            for item in items {
                try checkContext()
                if case .document(let document, _) = item.source {
                    let size = try await messagesRepository.draftFileSize(document.url)
                    if let size, size > AttachmentManager.maxFileBytes { offerDirectForLargeOriginal(); return }
                    continue
                }
                if item.kind == "file", case .file(let file) = item.source, Int64(file.bytes) > AttachmentManager.maxFileBytes {
                    // Pasted originals now own staged files; retain the same direct
                    // offer that external document originals received above.
                    offerDirectForLargeOriginal(); return
                }
                if let source = item.directOriginal {
                    let original = try await DirectComposerOriginal.loadForCloud(source)
                    originals[item.id] = original
                    if original.size > AttachmentManager.maxFileBytes { offerDirectForLargeOriginal(); return }
                }
            }
            try checkContext()
            if await enqueueComposerAttachments(items, capturedDraft: capturedDraft, preloadedOriginals: originals, capturedBatch: batch) { originals = [:] }
        } catch is CancellationError { /* Account/cancel retires this exact batch; no transport fallback. */ }
        catch {
            if generation == auth.sessionGeneration {
                let key = (error as? DirectComposerOriginal.PreparationFailure)?.message ?? MediaPreparationFailure.messageKey(error)
                messagesRepository.reportError(UnyriL10n.string(key))
            }
        }
    }
    @discardableResult
    private func enqueueComposerAttachments(_ items: [ChatComposerAttachment], capturedDraft: String,
        preloadedOriginals: [String: DirectComposerOriginal.Original] = [:], capturedBatch: MediaComposerBatch? = nil) async -> Bool {
        let repository = messagesRepository
        let generation = auth.sessionGeneration; let token = composerSendToken
        let capturedIDs = Set(items.map(\.id))
        let recovery: MediaComposerBatch?
        if let capturedBatch { recovery = capturedBatch }
        else { recovery = await captureComposerBatch(items, capturedDraft: capturedDraft) }
        guard let batch = recovery else { return false }
        let intent = batch.intent
        do { _ = try MessageTextPolicy.caption(intent.caption) }
        catch {
            repository.reportError(UnyriL10n.string("Caption is too long or contains unsupported characters. Your caption and selected items have been kept."))
            return false
        }
        // Preserve the tray until the durable preparation markers are accepted.
        // The existing draft writer must finish without blocking MainActor first.
        await repository.flushDraftValues()
        guard generation == auth.sessionGeneration, token == composerSendToken,
              !Task.isCancelled, editingMessage == nil,
              capturedIDs.isSubset(of: Set(composerAttachments.map(\.id))) else { return false }
        let accepted = repository.startMediaPreparation(chatId: chat.chatId, count: items.count,
            sendIntent: intent, otherUserId: chat.otherUser?.id, itemKinds: items.map(\.kind), composerBatch: batch,
            prepared: { index, prepared in
                guard generation == auth.sessionGeneration, token == composerSendToken,
                      let member = batch.pending.first(where: { $0.sourceID == items[index].id }),
                      var current = composerBatch, current.members.contains(where: { $0.id == member.id }) else { return }
                current.prepared(prepared, for: member.id); composerBatch = current
                if let slot = composerAttachments.firstIndex(where: { $0.id == items[index].id }) {
                    composerAttachments[slot] = ChatComposerAttachment(id: items[index].id, source: .file(prepared), kind: items[index].kind,
                        entrance: composerAttachments[slot].entrance, documentURL: composerAttachments[slot].documentURL)
                }
            },
            admitted: { index, prepared in
                guard generation == auth.sessionGeneration, token == composerSendToken,
                      let member = batch.pending.first(where: { $0.sourceID == items[index].id }) else { return }
                consumeComposerMember(items[index], member: member, prepared: prepared, capturedDraft: capturedDraft)
            },
            finished: {
                for original in preloadedOriginals.values { Task { await original.cleanup() } }
            }) { index in
                try await Self.prepareComposerAttachment(items[index], repository: repository, preloadedOriginal: preloadedOriginals[items[index].id])
            }
        guard accepted else {
            repository.reportError(repository.errorMessage ?? UnyriL10n.string("Could not save media to the outbox."))
            return false
        }
        UnyriHaptics.press()
        // Each source leaves the tray only after its own durable outbox admission.
        return true
    }

    private static func prepareComposerAttachment(_ item: ChatComposerAttachment, repository: MessagesRepository,
        preloadedOriginal: DirectComposerOriginal.Original? = nil) async throws -> StoredMediaDraft {
        if let preloadedOriginal {
            if case .file(let stored) = item.source { return stored }
            let durable = try await repository.stageDraftFile(preloadedOriginal.url, isVideo: item.isVideo)
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable,
                bytes: (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, isVideo: item.isVideo)
        }
        switch item.source {
        case .file(let stored): return stored
        case .document(let document, _):
            let durable = try await repository.stageDraftFile(document.url, coordinated: true)
            withExtendedLifetime(document) {}
            return StoredMediaDraft(id: UUID().uuidString.lowercased(), url: durable,
                bytes: (try? durable.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, isVideo: false)
        case .unavailable, .importing: throw CocoaError(.fileReadNoSuchFile)
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
        guard editingMessage != nil || composerFileImports == 0 else { return }
        if !composerAttachments.isEmpty && editingMessage == nil { sendComposerAttachments(); return }
        let text = composerModel.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // An edit that changes nothing just leaves edit mode; it must not mark the
        // message "edited" for both sides.
        if let editing = editingMessage,
           text == (editing.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines) {
            editingMessage = nil
            composerModel.revision &+= 1
            composerModel.setText(composerModel.ordinaryDraft)
            return
        }
        UnyriHaptics.press()
        isSending = true
        // The quoted message may have been deleted for everyone since Reply was chosen;
        // its text must not travel again inside the quote.
        var replyTo = replyTarget
        if let target = replyTo,
           let current = messagesRepository.messages.first(where: { $0.clientMessageId == target.clientMessageId }),
           current.isDeletedForEveryone || current.hiddenLocally { replyTo = nil }
        let editing = editingMessage
        composerModel.setText(editing == nil ? "" : composerModel.ordinaryDraft)
        if editing == nil {
            composerModel.ordinaryDraft = ""
            messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: "")
        }
        replyTarget = nil
        editingMessage = nil
        // The composer is cleared directly (not through updateComposerDraft), so the
        // peer would otherwise keep seeing "typing…"/the live draft for up to 8 s.
        if allowsPeerActivity && activityEnabled { realtime.sendActivity(chatId: chat.chatId, kind: "idle") }
        if allowsPeerActivity && conversationVisible { realtime.liveDrafts.update("") }
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
                if composerModel.text.isEmpty {
                    composerModel.setText(text)
                    // Do not bring back a reply/edit panel for a message deleted meanwhile.
                    if let replyTo, messagesRepository.isComposerTargetAvailable(clientMessageId: replyTo.clientMessageId) { replyTarget = replyTo }
                    if let editing, messagesRepository.isComposerTargetAvailable(clientMessageId: editing.clientMessageId) { editingMessage = editing }
                    if editing == nil { composerModel.ordinaryDraft = text; messagesRepository.saveChatValue(chatId: chat.chatId, name: "draft", value: text) }
                } else {
                    messagesRepository.saveChatValue(chatId: chat.chatId, name: "recoveredDraft", value: text)
                    messagesRepository.reportError(UnyriL10n.string("Previous text was saved. Open Recovered draft in the chat menu.", comment: ""))
                }
            }
            isSending = false
        }
    }

    /// Every Reply entry point (menu, bubble swipe, album swipe). Replying while editing
    /// leaves edit mode first: Send used to overwrite the edited message with the reply
    /// text. A message deleted for everyone (or unreadable) cannot be quoted: its text
    /// would be sent again inside the quote.
    private func beginReply(_ target: DecryptedChatMessage) {
        guard !target.isDeletedForEveryone, !target.hiddenLocally,
              target.status != .decryptFailed, target.status != .identityChanged else { return }
        if editingMessage != nil {
            editingMessage = nil
            composerModel.revision &+= 1
            composerModel.setText(composerModel.ordinaryDraft)
        }
        replyTarget = target
        composerModel.focus()
    }

    private func beginEdit(_ message: DecryptedChatMessage) {
        editingMessage = message
        replyTarget = nil
        composerModel.setText(message.content.text ?? "")
        // Match Reply: choosing Edit from the menu opens the keyboard on the text.
        if composerEmojiKeyboard { composerEmojiKeyboard = false; UnyriEmojiInput.setEmoji(false) }
        composerModel.focus()
    }

    /// Preparation progress lives on the message itself (its ring and stage label, with
    /// cancel), like Telegram; the extra "Preparing media…" strip covered the last
    /// message and lingered after sending (report 0.4.1). Only a failure is shown here.
    @ViewBuilder
    private var mediaPreparationBanner: some View {
        if let error = messagesRepository.mediaPreparationErrors[chat.chatId] {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Color(hex: 0xE5AC5E)).accessibilityHidden(true)
                Text(verbatim: error).font(.footnote).foregroundStyle(UnyriColors.primaryText).lineLimit(3)
                Spacer(minLength: 4)
                Button { messagesRepository.acknowledgeMediaPreparationError(chatId: chat.chatId) } label: {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .semibold)).frame(width: 30, height: 30)
                }.buttonStyle(.plain).foregroundStyle(UnyriColors.secondaryText).accessibilityLabel("Close")
            }
            .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6)
            .modifier(ChatHeaderGlass(solid: false))
            .padding(.horizontal, 10).padding(.bottom, 6)
        }
    }

    /// The quality chosen in the gallery (MediaGallerySendChoice) also applies to the
    /// camera; the camera used to read a legacy key and always sent Standard.
    private func mediaDraftKind(isVideo: Bool) -> String {
        MediaGallerySendChoice.saved().intent().kind(isVideo: isVideo)
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
        mediaRestoreToken = UUID()
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
        // Previews are now loaded by the visible review tiles, not eagerly at chat entry.
        mediaPreviewTask?.cancel(); mediaPreviewTask = nil
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

    // Menu actions reuse the existing gallery, importer and capture paths.
    // Recheck eligibility because the native menu can outlive its opening tap.
    private func openComposerAttachment(_ choice: String) {
        guard composerSelectionRemaining > 0, composerBatchRestored, !composerBatchRestoring, !composerBatchRestoreFailed, composerFileImports == 0, editingMessage == nil else { return }
        let context = currentDirectSelection
        if context.requested, let issue = composerDirectIssue { messagesRepository.reportError(UnyriL10n.string(issue)); return }
        directSelectionContext = context
        UnyriHaptics.press()
        composerModel.blur()
        attachmentPhotoItems = []
        if choice == "gallery" || choice == "files" {
            // "Files" is the attach sheet's Files tab (pick several, scan, recent files).
            attachmentStartsOnFiles = choice == "files"
            showAttachmentMenu = true
        } else {
            attachmentChoice = choice
            openAttachmentChoice()
        }
    }

    private func openAttachmentChoice() {
        let choice = attachmentChoice
        attachmentChoice = nil
        let sendNow = attachmentSendsNow
        attachmentSendsNow = false
        if choice != "assets" { gallerySelection = [] }
        switch choice {
        case "selectedPhotos":
            let selected = attachmentPhotoItems
            attachmentPhotoItems = []
            addComposerPhotos(selected)
        case "assets":
            let assets = attachmentAssets; attachmentAssets = []
            let sendImmediately = sendNow && canSendPickedAttachmentsNow
            var seen = Set(composerAttachments.map(\.id))
            let candidates = assets.compactMap { source -> (String, UnyriGallerySource, Bool)? in
                let id: String
                let video: Bool
                switch source {
                case .asset(let asset): id = asset.localIdentifier; video = asset.mediaType == .video
                case .capture(let url): id = url.absoluteString; video = false
                }
                return seen.insert(id).inserted ? (id, source, video) : nil
            }
            gallerySelection = []
            guard !candidates.isEmpty else {
                if let first = assets.first {
                    let id: String
                    switch first {
                    case .asset(let asset): id = asset.localIdentifier
                    case .capture(let url): id = url.absoluteString
                    }
                    announceComposerFill(0, existingID: id)
                }
                break
            }
            guard admitComposerSelectionCount(candidates.count) else { break }
            for (order, candidate) in candidates.enumerated() {
                let (id, source, video) = candidate
                composerAttachments.append(ChatComposerAttachment(id: id, source: .gallery(source), kind: gallerySendIntent.kind(isVideo: video),
                    entrance: sendImmediately ? .settled : ChatComposerEntrance(flies: true, order: order)))
            }
            if sendImmediately { sendComposerAttachments() }
            else {
                announceComposerFill(candidates.count)
                if sendNow { announceDeferredComposerSend() }
            }
        case "documents":
            let documents = attachmentDocuments; attachmentDocuments = []
            guard !documents.isEmpty else { break }
            let context = directSelectionContext ?? currentDirectSelection
            let sendImmediately = sendNow && canSendPickedAttachmentsNow
            let sendToken = composerSendToken
            let generation = auth.sessionGeneration
            composerTray.imports.start {
                let imported = await addComposerFiles(documents, context: context, flies: !sendImmediately, deferredSend: sendNow && !sendImmediately)
                guard !Task.isCancelled, generation == auth.sessionGeneration, sendImmediately else { return }
                if imported == .complete && sendToken == composerSendToken {
                    if canSendPickedAttachmentsNow { sendComposerAttachments() }
                    else { announceDeferredComposerSend() }
                } else if imported != .nothing {
                    // Send now never sends a selection that changed meanwhile (a failed copy,
                    // a removed file): say so instead of leaving the rest in the tray silently.
                    UnyriHaptics.selection()
                    showComposerFeedback(UnyriL10n.string("Not sent. Check the tray and tap Send."))
                }
            }
        case "photos": showPhotoPicker = true
        case "files": showFileImporter = true
        case "voice": toggleRecording()
        case "camera":
            guard voiceDraft == nil && !stagingVoice else {
                messagesRepository.reportError(UnyriL10n.string("Send or discard the voice draft first.")); return
            }
            guard !voiceRecorder.callInProgress else {
                messagesRepository.reportError(UnyriL10n.string("Finish the call before taking a photo. Photos and files can still be attached during a call."))
                return
            }
            let generation = auth.sessionGeneration
            Task { @MainActor in
                do {
                guard let claim = try await NonCallCaptureOwnership.prepare(.camera, generation: generation, stop: { claim in
                    if cameraClaim == claim { showCamera = false; cameraClaim = nil }
                    NonCallCaptureOwnership.release(claim)
                }) else { messagesRepository.reportError(UnyriL10n.string("Finish the call before taking a photo. Photos and files can still be attached during a call.")); return }
                cameraClaim = claim
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard generation == auth.sessionGeneration, !Task.isCancelled, !voiceRecorder.callInProgress,
                      NonCallCaptureOwnership.isCurrent(claim) else { NonCallCaptureOwnership.release(claim); return }
                guard granted, UIImagePickerController.isSourceTypeAvailable(.camera) else {
                    NonCallCaptureOwnership.release(claim); cameraClaim = nil
                    messagesRepository.reportError(UnyriL10n.string("Camera access is required to take a photo.")); return
                }
                cameraSessionGeneration = generation; cameraNeedsRetry = false
                showCamera = true
                } catch is CancellationError { }
                catch { if generation == auth.sessionGeneration { messagesRepository.reportError(UnyriL10n.string("Finish the call before taking a photo.")) } }
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
                // Near-lossless first save: the upload step does the one real compression
                // (two 0.85 JPEG passes made camera photos visibly worse).
                guard let data = image.jpegData(compressionQuality: 0.95) else { throw CocoaError(.fileReadCorruptFile) }
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
        // Telegram-like: the capture joins the composer tray, where it can get a caption,
        // be reviewed or removed, and goes out with Send. The tray owns the file now.
        if composerAttachments.count < 10, composerBatch == nil, editingMessage == nil {
            drafts.removeAll { $0.id == id }
            let remaining = try JSONEncoder().encode(drafts)
            // The tray journals the file as it joins (ChatComposerTrayState), before it leaves
            // the durable draft list, so a termination never finds it unreferenced.
            withAnimation(composerReduceMotion ? nil : .easeInOut(duration: 0.16)) {
                composerAttachments.append(ChatComposerAttachment(id: id,
                    source: .file(StoredMediaDraft(id: id, url: staged, bytes: bytes, isVideo: isVideo)),
                    kind: mediaDraftKind(isVideo: isVideo), entrance: .settled))
            }
            _ = repository.saveChatValue(chatId: chat.chatId, name: "mediaDrafts", value: String(decoding: remaining, as: UTF8.self))
            cameraFocusesComposer = true
            return
        }
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
        return { sendQuickReaction(message, emoji: emoji) }
    }

    private func sendQuickReaction(_ message: DecryptedChatMessage, emoji: String) {
        guard !messagesRepository.reactions(forMessageServerId: message.serverId).contains(where: { $0.isMine && $0.emoji == emoji }),
              quickReactionRequests.insert(message.clientMessageId).inserted else { return }
        UnyriHaptics.selection()
        noteReactionIntent(message, emoji)
        Task {
            defer { quickReactionRequests.remove(message.clientMessageId) }
            await messagesRepository.setReaction(clientMessageId: message.clientMessageId, emoji: emoji)
        }
    }

    private func react(_ message: DecryptedChatMessage, _ emoji: String) {
        UnyriHaptics.selection()
        noteReactionIntent(message, emoji)
        Task { await messagesRepository.setReaction(clientMessageId: message.clientMessageId, emoji: emoji) }
    }

    /// Tells the landing effect which chip to expect; the tick above stays the only haptic.
    private func noteReactionIntent(_ message: DecryptedChatMessage, _ emoji: String) {
        guard let serverId = message.serverId else { return }
        if let owner = auth.currentUser?.id {
            UnyriReactionStore(account: AppConfig.storageOrigin + "|" + owner.lowercased()).recordSend(emoji)
        }
        UnyriReactionBursts.shared.expectAdd(chatId: message.chatId, serverId: serverId, emoji: emoji, messageID: message.clientMessageId)
    }

    private func removeReaction(_ message: DecryptedChatMessage) {
        UnyriHaptics.reactionRemoved()
        Task { await messagesRepository.removeReaction(clientMessageId: message.clientMessageId) }
    }

    private func retry(_ message: DecryptedChatMessage, reviewKeys: Bool = true) {
        refreshIdentityWarning()
        if reviewKeys && identityNeedsReview {
            retryAfterVerification = message.clientMessageId; acceptedForRetry = false
            composerModel.blur(); showKeyVerification = true
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
        chat.chatType == "saved" ? UnyriL10n.string("Saved Messages", comment: "") : (currentHeaderPeer?.visibleName ?? "Chat")
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
    @State private var recordsLoaded = false
    @State private var selectedTab: ChatMediaTab = .photos
    @State private var downloading = Set<String>()
    @State private var previewURL: URL?
    @State private var previewFilename: String?
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
                if !recordsLoaded {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if visibleRecords.isEmpty {
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
        .unyriQuickLookPreview($previewURL, title: previewFilename)
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
        // Off the main thread; deleted media is filtered out and newest comes first.
        .task { records = await messagesRepository.galleryAttachmentsAsync(chatId: chatId); recordsLoaded = true }
    }

    private var photos: some View {
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(visibleRecords, id: \.attachmentId) { record in
                    Button { open(record) } label: {
                        // B-044: the square comes from the column width alone. The thumbnail and the
                        // download state are overlays, so a wide or tall picture can never resize its
                        // cell, cover a neighbour or run past the screen edge.
                        Color.clear
                            .aspectRatio(1, contentMode: .fit)
                            .overlay { MediaGalleryThumbnail(record: record, repository: messagesRepository) }
                            .overlay {
                                if downloading.contains(record.attachmentId) {
                                    ProgressView().tint(.white)
                                } else if messagesRepository.cachedLocalPath(attachmentId: record.attachmentId) == nil {
                                    Image(systemName: "arrow.down.circle.fill")
                                        .font(.title2).foregroundColor(.white)
                                        .shadow(radius: 3)
                                }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusMedium))
                            .contentShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusMedium))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(record.filename ?? UnyriL10n.string("Media"))
                    .accessibilityIdentifier("chat-media-tile")
                    .disabled(downloading.contains(record.attachmentId))
                }
            }
            .padding(.horizontal, 16)
        }
        .accessibilityIdentifier("chat-media-grid")
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
                records = await messagesRepository.galleryAttachmentsAsync(chatId: chatId)
                mediaURL = url
            } else {
                previewFilename = record.filename
                previewURL = url
            }
        }
    }
}

private struct MediaGalleryThumbnail: View {
    let record: AttachmentRecord
    let repository: MessagesRepository
    var body: some View {
        UnyriAttachmentImage(request: AttachmentImageRequest(id: record.attachmentId,
            url: repository.cachedLocalPath(attachmentId: record.attachmentId).map { URL(fileURLWithPath: $0) },
            mime: record.mimeType, thumbnail: record.thumbnailBase64, pixelEdge: 384), repository: repository) { image in
            ZStack(alignment: .bottomTrailing) {
                // The base takes exactly the proposed square; the picture fills it as an overlay and is
                // clipped there, so the duration label stays on the visible corner (B-044).
                Color(.systemGray5)
                    .overlay {
                        if let image { Image(uiImage: image).resizable().scaledToFill() }
                        else { Image(systemName: record.mimeType.hasPrefix("video/") ? "video" : "photo").foregroundColor(.secondary) }
                    }
                    .clipped()
                if record.mimeType.hasPrefix("video/") {
                    Label(FilePresentation.duration(record.durationMs), systemImage: "play.fill")
                        .font(.caption).foregroundStyle(.white).padding(5).background(.black.opacity(0.6))
                }
            }.clipped()
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
    /// A chat deletion passes false: notes still uploading in other chats keep their posters.
    static func clearDecodedCache(includingPendingSends: Bool = true) {
        generation &+= 1
        pending.values.forEach { $0.task.cancel() }
        pending.removeAll()
        cache.removeAllObjects()
        // Inline previews and note posters are pictures of the same messages.
        VideoNotePosters.clear(includingSends: includingPendingSends)
        UnyriAttachmentImages.reset(restartVisible: !includingPendingSends)
    }
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        // ~14 decoded previews fit in 24 MB: scrolling back re-decoded and flashed placeholders.
        cache.totalCostLimit = 80 * 1024 * 1024
        cache.countLimit = 300
        return cache
    }()

    static func cacheVideoFrame(_ image: UIImage, path: String, pixelEdge: Int, generation expected: UInt64) {
        guard expected == generation else { return }
        cache.setObject(image, forKey: videoFrameKey(path: path, pixelEdge: pixelEdge),
                        cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        UnyriAttachmentImages.changes.send(.path(path))
    }

    static func cacheAttachmentImage(_ image: UIImage, path: String, pixelEdge: Int, generation expected: UInt64) {
        guard expected == generation else { return }
        cache.setObject(image, forKey: previewKey(path, pixelEdge: pixelEdge),
            cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        UnyriAttachmentImages.changes.send(.path(path))
    }

    static func removeAttachmentImages(path: String) {
        for edge in [2048, 768, 512, 480, 384, 312, 256, 240, 128] {
            let key = previewKey(path, pixelEdge: edge)
            pending.removeValue(forKey: key as String)?.task.cancel()
            cache.removeObject(forKey: key)
            cache.removeObject(forKey: videoFrameKey(path: path, pixelEdge: edge))
        }
    }

    /// A generated video frame, under the key its writer used.
    static func cachedVideoFrame(path: String, pixelEdge: Int = 768) -> UIImage? {
        cache.object(forKey: videoFrameKey(path: path, pixelEdge: pixelEdge))
    }

    /// The one key for generated video frames, for the writer and every reader. The writer
    /// used to store the raw string while readers hashed it: on a device, attachment paths
    /// are long enough to be hashed, so no generated frame was ever found again.
    /// `UnyriMediaPosterPolicy.cachedPath` reaches the same key through `cached(path:)`.
    private static func videoFrameKey(path: String, pixelEdge: Int) -> NSString {
        previewKey("video-frame:\(pixelEdge):" + path, pixelEdge: 768)
    }

    // Keep the default key shared with generated video frames and the media viewer.
    private static func previewKey(_ source: String, pixelEdge: Int) -> NSString {
        let normalized: String
        // utf8.count is O(1) for native strings; Character count walked the whole base64.
        if source.utf8.count > 128 {
            var hasher = Hasher()
            hasher.combine(source)
            normalized = "b64:\(source.utf8.count):\(hasher.finalize())"
        } else {
            normalized = source
        }
        return (pixelEdge == 768 ? normalized : "\(pixelEdge):\(normalized)") as NSString
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
            if let path { source = PeerImagePolicy.source(url: URL(fileURLWithPath: path)) }
            if path == nil, let thumbnail, thumbnail.utf8.count <= 128 * 1024, let data = Data(base64Encoded: thumbnail) {
                source = PeerImagePolicy.source(data: data, jpegOnly: true)
            }
            guard let source, let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: pixelEdge == 128 ? 128 : min(768, max(256, pixelEdge)),
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
        let current = pending[requestKey]?.id == requestID
        finishConsumer(consumer, key: requestKey, request: requestID, cancelled: false)
        guard loadGeneration == generation else { return nil }
        guard current else { return nil } // this path may have been replaced while decoding
        // Completed pixels remain reusable. A queued decode is cancelled only
        // after its last consumer leaves, never while another row needs it.
        if let image {
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            if let path { UnyriAttachmentImages.changes.send(.path(path)) }
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
    @Environment(\.unyriAppColorStyle) private var appColorStyle
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
                                UnyriAttachmentImage(request: .file(draft.fileURL, video: draft.isVideo, clientMessageID: draft.id)) { image in
                                    if let image { Image(uiImage: image).resizable().scaledToFit() }
                                    else { Color.secondary.opacity(0.15) }
                                }.frame(maxWidth: .infinity).frame(height: 230)
                                if draft.isVideo { Image(systemName: "play.circle.fill").font(.system(size: 48)).foregroundStyle(.white).shadow(radius: 8) }
                            }.background(.black, in: RoundedRectangle(cornerRadius: 16)).clipShape(RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain).accessibilityLabel("Open preview").disabled(isSending).opacity(isSending ? 0.45 : 1)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 10) {
                            ForEach(Array(drafts.enumerated()), id: \.element.id) { index, draft in
                                ZStack(alignment: .topTrailing) {
                                    Button { UnyriHaptics.selection(); selected = index } label: {
                                        UnyriAttachmentImage(request: .file(draft.fileURL, video: draft.isVideo, clientMessageID: draft.id, pixelEdge: 256)) { image in
                                            if let image { Image(uiImage: image).resizable().scaledToFill() }
                                            else { Image(systemName: draft.isVideo ? "video.fill" : "photo") }
                                        }.frame(width: 88, height: 88).clipped()
                                            .clipShape(RoundedRectangle(cornerRadius: 12))
                                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(index == selected ? UnyriColors.accent : .clear, lineWidth: 3))
                                            .overlay(alignment: .bottomLeading) {
                                                Text("\(index + 1)").font(.caption.bold()).foregroundStyle(.white).padding(5).background(.black.opacity(0.7), in: Circle()).padding(4)
                                            }
                                    }.buttonStyle(.plain).disabled(isSending).opacity(isSending ? 0.45 : 1)
                                    Button { remove(draft) } label: {
                                        Image(systemName: "xmark").font(.system(size: 13, weight: .semibold)).foregroundStyle(appColorStyle.decorative(ChatControlPalette.coral))
                                            .frame(width: 28, height: 28).background(appColorStyle.surface(ChatControlPalette.coralBack), in: Circle())
                                            .frame(width: 44, height: 44).contentShape(Rectangle())
                                    }.buttonStyle(.plain).accessibilityLabel("Remove from selection").disabled(isSending).opacity(isSending ? 0.45 : 1)
                                }
                            }
                        }.padding(3)
                    }
                    Button { UnyriHaptics.press(); add() } label: { Label("Add photos", systemImage: "plus").font(.subheadline.weight(.semibold)).foregroundStyle(appColorStyle.decorative(ChatControlPalette.mint)).frame(maxWidth: .infinity, minHeight: 44).background(ChatControlPalette.mintBack, in: Capsule()) }.buttonStyle(.plain).disabled(isSending).opacity(isSending ? 0.45 : 1)
                    HStack {
                    Label("Media quality", systemImage: "sparkles").font(.subheadline).foregroundStyle(appColorStyle.decorative(ChatControlPalette.amber))
                    Spacer()
                    Picker("Media quality", selection: $quality) {
                        Text("Standard").tag("image"); Text("High quality").tag("image_high"); Text("Original file").tag("file")
                    }.pickerStyle(.menu).tint(appColorStyle.controlTint(ChatControlPalette.amber)).disabled(isSending).opacity(isSending ? 0.45 : 1)
                    }.padding(.horizontal, 12).frame(minHeight: 48).background(appColorStyle.surface(ChatControlPalette.amberBack), in: RoundedRectangle(cornerRadius: 16))
                    Text("Original files and animations retain their format and may contain location metadata.").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                    TextField("Caption (optional)", text: $caption, axis: .vertical)
                        .lineLimit(1...4).textFieldStyle(.plain).padding(14).background(appColorStyle.surface(ChatControlPalette.composer), in: RoundedRectangle(cornerRadius: 18)).overlay(RoundedRectangle(cornerRadius: 18).stroke(appColorStyle.decorative(ChatControlPalette.edge).opacity(0.25), lineWidth: 1)).disabled(isSending).opacity(isSending ? 0.45 : 1)
                        .onChange(of: caption) { value in if value.count > 4096 { caption = String(value.prefix(4096)) } }
                    if let error { Text(verbatim: error).foregroundStyle(UnyriColors.danger).font(.footnote) }
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
                    }.font(.subheadline.weight(.semibold)).foregroundStyle(appColorStyle.isMonochrome ? appColorStyle.onColorfulButton : .white).frame(maxWidth: .infinity, minHeight: 48).background(appColorStyle.decorative(ChatControlPalette.violet), in: Capsule())
                }.buttonStyle(.plain).disabled(isSending || drafts.isEmpty).opacity(isSending || drafts.isEmpty ? 0.45 : 1).padding(.horizontal, 16).padding(.vertical, 10).background(UnyriColors.deepPanel)
             }
             .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Keep draft") { UnyriHaptics.press(); dismiss() }.tint(appColorStyle.controlTint(ChatControlPalette.blue)).disabled(isSending).opacity(isSending ? 0.45 : 1) }
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
        return DirectFilePreviewText.display(text: snapshot.preview) ?? snapshot.preview
    }
}

private struct UnyriReplyComposerPreview: View {
    @EnvironmentObject private var repository: MessagesRepository
    let message: DecryptedChatMessage
    let author: String
    let editing: Bool
    let localPath: String?
    var onOpen: (() -> Void)? = nil
    let onCancel: () -> Void
    private var media: AttachmentMetadata? { message.content.attachment }
    private var kind: String { UnyriReplyPreviewText.kind(media) }
    var body: some View {
        HStack(spacing: 10) {
            if let media {
                UnyriAttachmentImage(request: AttachmentImageRequest(attachment: media, path: localPath,
                    clientMessageID: message.clientMessageId, pixelEdge: 256), repository: repository,
                    workAllowed: kind == "image" || kind == "video", visibleWhenMounted: true) { image in
                ZStack {
                    UnyriColors.accentSoft.opacity(0.12)
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(systemName: kind == "image" ? "photo" : kind == "video" ? "video" : kind == "voice" ? "waveform" : "doc.text").font(.system(size: 20)).foregroundStyle(UnyriColors.accentSoft) }
                    if kind == "video", image != nil { Image(systemName: "play.fill").font(.system(size: 12)).foregroundStyle(.white).padding(4).background(.black.opacity(0.35), in: Circle()) }
                }.frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityHidden(true)
                }
            }
            Image(systemName: editing ? "pencil" : "arrowshape.turn.up.left.fill").font(.system(size: 15, weight: .semibold))
                .foregroundStyle(UnyriColors.accentSoft).frame(width: 18).accessibilityHidden(true)
            RoundedRectangle(cornerRadius: 2).fill(UnyriColors.accentSoft).frame(width: 3, height: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(editing ? UnyriL10n.string("Editing message") : author).font(.caption.weight(.medium)).foregroundStyle(UnyriColors.accentSoft).lineLimit(1)
                Text(UnyriReplyPreviewText.detail(message).replacingOccurrences(of: "\n", with: " ")).font(.footnote).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
                // Like Telegram: tapping the quoted message scrolls to the original.
                .contentShape(Rectangle())
                .onTapGesture { onOpen?() }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(onOpen == nil ? [] : .isButton)
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
    var body: some View {
        HStack(spacing: 12) {
            if let media = message?.content.attachment, message?.isDeletedForEveryone != true {
                UnyriAttachmentImage(request: AttachmentImageRequest(attachment: media,
                    path: repository.cachedLocalPath(attachmentId: media.attachmentId),
                    clientMessageID: message?.clientMessageId, pixelEdge: 256), repository: repository,
                    visibleWhenMounted: true) { image in
                ZStack {
                    UnyriColors.glassFloating
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(systemName: media.mimeType.hasPrefix("video/") ? "video" : "doc").foregroundColor(UnyriColors.accent) }
                }.frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(text).lineLimit(4).foregroundColor(UnyriColors.primaryText)
                if let message { Text(message.createdAt.formatted()).font(.caption).foregroundColor(UnyriColors.secondaryText) }
            }
        }.padding(.vertical, 6)

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
    @Environment(\.unyriAppColorStyle) private var appColorStyle

    var body: some View {
        let mono = appColorStyle.isMonochrome
        // Same glass as the header islands (one material language across the chat);
        // the unread count sits on top like a badge, not in a corner chip.
        Button(action: onTap) {
            Image(systemName: "chevron.down")
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(UnyriColors.primaryText)
                .offset(y: glyphOffset + 1)
                .frame(width: 44, height: 44)
                .modifier(ChatJumpGlass())
                .overlay(alignment: .top) {
                    if count > 0 {
                        Text(count > 99 ? "99+" : String(count))
                            .font(.system(size: 12, weight: .semibold)).monospacedDigit()
                            .foregroundStyle(mono ? Color.black : Color.white)
                            .padding(.horizontal, 6).frame(minWidth: 22, minHeight: 22)
                            .background(Capsule().fill(mono ? Color.white : appColorStyle.decorative(ChatControlPalette.blue)))
                            .offset(y: -11)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                .frame(width: 52, height: 56, alignment: .bottom).contentShape(Rectangle())
                .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.8), value: count > 0)
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

struct UnyriSwipeToReplyModifier: ViewModifier {
    let isEnabled: Bool
    let onReply: () -> Void

    @State private var swipeOffset: CGFloat = 0
    @State private var didHapticOnSwipe = false
    @State private var isSwiping = false
    // DragGesture.onEnded is never called when the gesture is cancelled (the List
    // takes over a mostly-vertical drag, a system edge gesture, an alert). Without
    // a reset path the bubble stayed shifted with the reply arrow visible.
    @GestureState private var dragTracking = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var layoutDirection

    func body(content: Content) -> some View {
        let isRTL = layoutDirection == .rightToLeft
        content
            .offset(x: swipeOffset)
            .overlay(alignment: isRTL ? .leading : .trailing) {
                let magnitude = abs(swipeOffset)
                if magnitude > 6 {
                    ZStack {
                        Circle()
                            .fill(UnyriColors.incomingBubble.opacity(0.92))
                            .frame(width: 32, height: 32)
                        Image(systemName: "arrowshape.turn.up.left.fill")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(magnitude >= 42 ? UnyriColors.accentSoft : UnyriColors.secondaryText)
                            .scaleEffect(min(1.15, max(0.65, magnitude / 42.0)))
                            .flipsForRightToLeftLayoutDirection(true)
                    }
                    // Centred in the space the bubble moves out of. Pushed past the trailing
                    // edge it was off-screen for own (right-aligned) messages.
                    .offset(x: isRTL ? max(0, magnitude / 2 - 16) : min(0, 16 - magnitude / 2))
                    .opacity(min(1.0, max(0.0, (magnitude - 6) / 26.0)))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            .modifier(UnyriReplyGestureHost(isEnabled: isEnabled, isRTL: isRTL,
                changed: { applySwipe(translation: $0, isRTL: isRTL) },
                ended: { cancelled in finishSwipe(cancelled: cancelled) }))
            .simultaneousGesture(
                !isEnabled || UnyriReplyGestureHost.usesUIKit ? nil :
                DragGesture(minimumDistance: 16, coordinateSpace: .local)
                    .updating($dragTracking) { _, tracking, _ in tracking = true }
                    .onChanged { value in
                        let rawH = value.translation.width
                        let h = isRTL ? rawH : -rawH
                        let v = abs(value.translation.height)
                        if !isSwiping {
                            guard h > 0, h > v * 1.35 else { return }
                            isSwiping = true
                        }
                        guard h > 0 else {
                            swipeOffset = 0
                            return
                        }
                        let resisted = h > 50 ? 50 + pow(h - 50, 0.7) : h
                        let clamped = min(72, resisted)
                        swipeOffset = isRTL ? clamped : -clamped
                        if clamped >= 42 && !didHapticOnSwipe {
                            didHapticOnSwipe = true
                            UnyriHaptics.selection()
                        } else if clamped < 35 && didHapticOnSwipe {
                            didHapticOnSwipe = false
                        }
                    }
                    .onEnded { _ in
                        let shouldReply = abs(swipeOffset) >= 42
                        withAnimation(reduceMotion ? .linear(duration: 0.1) : .spring(response: 0.32, dampingFraction: 0.72)) {
                            swipeOffset = 0
                        }
                        didHapticOnSwipe = false
                        isSwiping = false
                        if shouldReply {
                            onReply()
                        }
                    }
            )
            .onChange(of: dragTracking) { tracking in
                guard !tracking else { return }
                // Defer one turn so a normal onEnded (which may trigger the reply)
                // always runs first; only a cancelled drag is still marked here.
                DispatchQueue.main.async {
                    guard isSwiping || swipeOffset != 0 else { return }
                    withAnimation(reduceMotion ? .linear(duration: 0.1) : .spring(response: 0.32, dampingFraction: 0.72)) {
                        swipeOffset = 0
                    }
                    didHapticOnSwipe = false
                    isSwiping = false
                }
            }
            .onChange(of: isEnabled) { enabled in
                if !enabled && swipeOffset != 0 {
                    swipeOffset = 0
                    didHapticOnSwipe = false
                    isSwiping = false
                }
            }
            .accessibilityAction(named: Text("Reply")) {
                if isEnabled {
                    onReply()
                }
            }
    }

    private func applySwipe(translation rawH: CGFloat, isRTL: Bool) {
        let h = isRTL ? rawH : -rawH
        isSwiping = true
        guard h > 0 else { swipeOffset = 0; return }
        let resisted = h > 50 ? 50 + pow(h - 50, 0.7) : h
        let clamped = min(72, resisted)
        swipeOffset = isRTL ? clamped : -clamped
        if clamped >= 42 && !didHapticOnSwipe {
            didHapticOnSwipe = true
            UnyriHaptics.selection()
        } else if clamped < 35 && didHapticOnSwipe {
            didHapticOnSwipe = false
        }
    }

    private func finishSwipe(cancelled: Bool) {
        let shouldReply = !cancelled && abs(swipeOffset) >= 42
        withAnimation(reduceMotion ? .linear(duration: 0.1) : .spring(response: 0.32, dampingFraction: 0.72)) {
            swipeOffset = 0
        }
        didHapticOnSwipe = false
        isSwiping = false
        if shouldReply { onReply() }
    }
}

/// iOS 18+: swipe-to-reply is a UIKit pan that only begins for a leftward, mostly
/// horizontal movement and never competes with the list's vertical pan. The SwiftUI
/// DragGesture it replaces could leave the timeline unscrollable after a few
/// vertical drags that started on a file card (device report 0.3.25).
private struct UnyriReplyGestureHost: ViewModifier {
    static var usesUIKit: Bool { if #available(iOS 18.0, *) { return true } else { return false } }
    let isEnabled: Bool
    let isRTL: Bool
    let changed: (CGFloat) -> Void
    let ended: (Bool) -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *), isEnabled {
            content.gesture(UnyriReplyPan(isRTL: isRTL, changed: changed, ended: ended))
        } else { content }
    }
}

@available(iOS 18.0, *)
private struct UnyriReplyPan: UIGestureRecognizerRepresentable {
    let isRTL: Bool
    let changed: (CGFloat) -> Void
    let ended: (Bool) -> Void
    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator(isRTL: isRTL) }
    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        pan.cancelsTouchesInView = false
        pan.delaysTouchesBegan = false
        pan.maximumNumberOfTouches = 1
        return pan
    }
    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        context.coordinator.isRTL = isRTL
    }
    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        switch recognizer.state {
        case .began, .changed: changed(recognizer.translation(in: recognizer.view).x)
        case .ended: ended(false)
        case .cancelled, .failed: ended(true)
        default: break
        }
    }
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var isRTL: Bool
        init(isRTL: Bool) { self.isRTL = isRTL }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
            let velocity = pan.velocity(in: pan.view)
            let horizontal = isRTL ? velocity.x : -velocity.x
            return horizontal > 0 && horizontal > abs(velocity.y) * 1.35
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}

extension View {
    func unyriSwipeToReply(enabled: Bool = true, onReply: @escaping () -> Void) -> some View {
        modifier(UnyriSwipeToReplyModifier(isEnabled: enabled, onReply: onReply))
    }
}

private struct MessageBubble: View {
    @Environment(\.unyriChatAppearance) private var appearance
    @Environment(\.chatBubbleTailed) private var tailed
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
    private var linkAddress: URL? {
        guard !isAlbumCell, !message.isDeletedForEveryone, message.status != .decryptFailed, message.status != .identityChanged else { return nil }
        return chatLinkAddress(message.content.text ?? message.displayText)
    }
    private var inlineMetadata: Bool {
        // With a link card the time goes after the card (bottom-right), not between text and card.
        linkAddress == nil &&
        UnyriSavedChatStyle.metadataMode == "inline" && UnyriSavedChatStyle.showTimes && !isAlbumCell &&
        message.content.attachment == nil && message.content.conferenceInvite == nil && message.content.directFile == nil && pendingPreview == nil && !message.isDeletedForEveryone && message.status != .failed
    }
    private var displayedText: String {
        message.status == .decryptFailed ? UnyriL10n.string("Unable to decrypt message", comment: "")
            : message.status == .identityChanged ? UnyriL10n.string("Security key changed", comment: "") : message.displayText
    }
    /// 1...3 emoji and nothing else: drawn large on the wallpaper, without the bubble.
    /// A quote, a forwarded header, a schedule note, album and settings previews keep it.
    /// Memoized per message id + text, and long text is rejected before hashing.
    private var bigEmoji: ChatEmojiOnlyCache.Entry? {
        guard !isAlbumCell, !albumCaptionMode, appearancePreviewContent == nil,
              scheduledAt == nil || message.status != .pending,
              message.content.kind == MessageContent.kindText, !message.content.forwarded,
              message.content.replySnapshot == nil, message.content.attachment == nil,
              message.content.conferenceInvite == nil, message.content.directFile == nil,
              !message.isDeletedForEveryone, message.status != .decryptFailed, message.status != .identityChanged,
              let text = message.content.text, pendingPreview == nil else { return nil }
        return ChatEmojiOnlyCache.shared.entry(id: message.clientMessageId, text: text)
    }
    private var inlineAccessibilityStatus: String {
        var parts = [isMine ? UnyriL10n.string("You") : "", message.createdAt.formatted(date: .omitted, time: .shortened)]
        if message.isEdited { parts.append(UnyriL10n.string("edited")) }
        if isMine {
            switch message.status {
            case .pending: parts.append(UnyriL10n.string("Sending…"))
            case .sent: parts.append(UnyriL10n.string("Sent"))
            case .delivered: parts.append(UnyriL10n.string("Delivered"))
            case .read: parts.append(UnyriL10n.string("Read"))
            case .failed: parts.append(UnyriL10n.string("Failed to send"))
            case .decryptFailed, .identityChanged: break
            }
        }
        return parts.filter { !$0.isEmpty }.joined(separator: ", ")
    }
    /// The inline time is drawn over the text's last line at its trailing end, from a clear
    /// copy kept in the text for room. That end is the bubble's trailing edge only when the
    /// last paragraph runs the same way as the interface; otherwise (Hebrew text in a
    /// Russian interface) the time stays a visible glyph run inside the text.
    private var inlineOverlay: Bool {
        inlineMetadata && ChatReactionMetrics.endsRightToLeft(displayedText) == (layoutDirection == .rightToLeft)
    }
    private func lensText(overlay: Bool, reserve: CGFloat) -> AttributedString {
        // Rows are rebuilt while scrolling; the composed text (rich text + time + ticks)
        // is memoized instead of re-assembling attributed runs every time.
        // The text itself is part of the key: a length-only key kept the old text on
        // screen after an edit of the same length ("5pm" -> "6pm"), including offline
        // edits that stay pending with sequence 0 (audit 10 Nov).
        let holdPoint = reserve > 0 ? max(appearance.fontSize * bodyScale, footerChip.height / 1.193) : 0
        let key = ChatLensTextKey(id: message.clientMessageId, text: displayedText,
            flags: (inlineMetadata ? 1 : 0) | (isMine ? 2 : 0) | (message.isEdited ? 4 : 0) | (overlay ? 8 : 0)
                | (layoutDirection == .rightToLeft ? 32 : 0),
            status: message.status.rawValue, minute: Int(message.createdAt.timeIntervalSince1970 / 60),
            scale: bodyScale, language: UnyriL10n.language, zone: TimeZone.current.identifier,
            reserve: Int((reserve * 2).rounded()), holdPoint: holdPoint)
        if let cached = ChatLensTextCache.shared.value(key) { return cached }
        let built = buildLensText(overlay: overlay, reserve: reserve, holdPoint: holdPoint)
        ChatLensTextCache.shared.store(built, key)
        return built
    }
    /// The visible copy of the inline time for the overlay. It does not depend on the
    /// text, so one entry serves every message with the same minute and status.
    private var metadataText: AttributedString {
        // Own receipts are drawn by UnyriMessageReceipt over a clear run of the same width.
        let separateReceipt = receiptOverlay
        let key = ChatLensTextKey(id: "", text: "", flags: 16 | (isMine ? 2 : 0) | (message.isEdited ? 4 : 0) | (separateReceipt ? 64 : 0),
            status: message.status.rawValue, minute: Int(message.createdAt.timeIntervalSince1970 / 60),
            scale: bodyScale, language: UnyriL10n.language, zone: TimeZone.current.identifier)
        if let cached = ChatLensTextCache.shared.value(key) { return cached }
        let built = metadataRun(receiptClear: separateReceipt)
        ChatLensTextCache.shared.store(built, key)
        return built
    }
    private var receiptOverlay: Bool { isMine && UnyriReceiptInkPolicy.drawsSeparately(message.status) }
    private static var receiptPaddingCache: [String: CGFloat] = [:]
    /// Extra advance after a status glyph so every status is as wide as "✓✓".
    private static func receiptPadding(_ symbol: String, size: CGFloat) -> CGFloat {
        let key = "\(symbol)|\(size)"
        if let cached = receiptPaddingCache[key] { return cached }
        let font = UIFont.systemFont(ofSize: size)
        func width(_ value: String) -> CGFloat { (value as NSString).size(withAttributes: [.font: font]).width }
        let padding = max(0, (width("✓✓") - width(symbol)).rounded(.up))
        receiptPaddingCache[key] = padding
        return padding
    }
    private func buildLensText(overlay: Bool, reserve: CGFloat, holdPoint: CGFloat) -> AttributedString {
        var text = chatRichText(displayedText)
        guard inlineMetadata else { return text }
        // Under the overlay, a mark of the interface's direction keeps the time's digits at the
        // line's end, where the overlay draws them, even after a word in the other script
        // ("hello שלום 12:34" would otherwise put them before that word).
        let mark = overlay ? (layoutDirection == .rightToLeft ? "\u{200F}" : "\u{200E}") : ""
        var gap = AttributedString(mark + "\u{2002}\u{2002}")
        gap.font = .system(size: UnyriSavedChatStyle.timeSize * bodyScale).monospacedDigit()
        text.append(gap)
        guard overlay else {
            text.append(metadataRun())
            return text
        }
        if reserve > 0 {
            // Holds the chips' room. A glyph, not a space: trailing spaces may hang past the
            // line end and would never wrap. Glued to the time digits, it can only move to a
            // new line with the time, and its size makes that line at least a chip tall.
            var hold = AttributedString("0")
            hold.font = .system(size: holdPoint).monospacedDigit()
            hold.foregroundColor = Color.clear
            let advance = ("0" as NSString).size(withAttributes: [.font: UIFont.monospacedDigitSystemFont(ofSize: holdPoint, weight: .regular)]).width
            hold.kern = max(0, reserve - advance)
            text.append(hold)
        }
        // A clear copy keeps the time's room in the line; inlineFooter draws the visible one.
        var held = metadataRun()
        held.foregroundColor = Color.clear
        text.append(held)
        return text
    }
    /// The inline time ("12:34", "edited") and, on own messages, the receipt in its fixed
    /// "✓✓"-wide slot, without the gap before them. `receiptClear` keeps the receipt's
    /// width but leaves its drawing to the overlay receipt (inlineFooter).
    private func metadataRun(receiptClear: Bool = false) -> AttributedString {
        var metadata = AttributedString(Self.timestampFormatter.string(from: message.createdAt)
            + (message.isEdited ? "\u{00a0}" + UnyriL10n.string("edited") : ""))
        // Fixed-width digits: a time that changes on delivery must not re-wrap the bubble.
        metadata.font = .system(size: UnyriSavedChatStyle.timeSize * bodyScale).monospacedDigit()
        metadata.foregroundColor = UnyriSavedChatStyle.muted
        if isMine {
            let symbol: String
            switch message.status {
            case .pending: symbol = "◷"
            case .sent: symbol = "✓"
            case .delivered, .read: symbol = "✓✓"
            default: symbol = ""
            }
            let size = UnyriSavedChatStyle.timeSize * bodyScale
            var receipt = AttributedString("\u{00a0}\u{00a0}" + symbol)
            receipt.font = .system(size: size)
            receipt.foregroundColor = receiptClear ? Color.clear
                : message.status == .read ? UnyriSavedChatStyle.readTicks : UnyriSavedChatStyle.ticks
            // The status slot is always as wide as "✓✓": sending → sent → delivered
            // never changes the line's width, so the bubble never re-wraps or grows.
            if !symbol.isEmpty, let last = receipt.characters.indices.last {
                receipt[last..<receipt.endIndex].kern = Self.receiptPadding(symbol, size: size)
            }
            metadata.append(receipt)
        }
        return metadata
    }
    /// The visible time over its clear copy at the end of the last line and, when they
    /// have room there, the reaction chips just before it. Sits on the text's last
    /// baseline: a time that wrapped to a line of its own lands bottom-right.
    private func inlineFooter(reserve: CGFloat) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 0) {
            if reserve > 0 {
                let chip = footerChip
                HStack(spacing: ChatReactionMetrics.spacing) {
                    ForEach(displayedReactions, id: \.emoji) { reactionChip($0, chip, bare: false) }
                }
                .background(UnyriReactionTarget(presenter: messageMenu, emoji: "__reaction_bar"))
                .padding(.trailing, ChatReactionMetrics.timeGap)
                // Centred on the line's capitals, which puts the chip right on the line box.
                .alignmentGuide(.lastTextBaseline) { $0[VerticalAlignment.bottom] - (chip.height - appearance.messageCapHeight * bodyScale) / 2 }
                // Like the row under the text: a fading cluster would hang over the time
                // while the bubble shrinks. Single chips keep their own transitions.
                .transition(.identity)
            }
            Text(metadataText)
                // The receipt's slot is a clear run at the text's trailing end: the ink receipt sits on it.
                .overlay(alignment: Alignment(horizontal: .trailing, vertical: .lastTextBaseline)) {
                    if receiptOverlay {
                        UnyriMessageReceipt(status: message.status, size: UnyriSavedChatStyle.timeSize * bodyScale,
                                            liveID: appearancePreviewMode ? nil : message.clientMessageId)
                    }
                }
                .accessibilityHidden(true)
        }
        .fixedSize()
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
    var onCopyMedia: (() -> Void)? = nil
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
    var membersStateKey = ""
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
    private var metaInsideCard: Bool {
        !isAlbumCell && !message.isDeletedForEveryone && UnyriSavedChatStyle.showTimes && message.status != .failed &&
            (message.content.directFile != nil || voiceCard || message.content.conferenceInvite != nil)
    }
    /// Voice keeps the generic trailing time row (its bubble has a fixed width).
    private var metaRowInsideCard: Bool { metaInsideCard && message.content.directFile == nil && !voiceCard }
    private var fileFooter: Bool {
        let caption = pendingPreview != nil ? pendingPreview?.caption : message.content.text
        return UnyriSavedChatStyle.metadataMode == "inline" && UnyriSavedChatStyle.showTimes && documentCard && !isAlbumCell && (caption ?? "").isEmpty
    }
    private var maximumWidth: CGFloat {
        let limit = viewportWidth * UnyriSavedChatStyle.width
        if edgeToEdgeMedia { return min(limit, mediaCellWidth ?? UnyriSavedChatStyle.mediaWidth) }
        if voiceCard { return VoiceFormat.bubbleWidth(message.content.attachment?.voice?.durationMs ?? pendingPreview?.duration ?? 0, limit: limit) }
        if documentCard { return min(limit, albumWidth ?? UnyriSavedChatStyle.fileWidth) }
        return limit
    }
    private var surfaceShape: AnyShape {
        if voiceCard { return AnyShape(appearance.voiceShape(isMine: isMine, layoutDirection: layoutDirection)) }
        if documentCard { return AnyShape(appearance.fileShape(isMine: isMine, layoutDirection: layoutDirection)) }
        return AnyShape(appearance.bodyShape(isMine: isMine, tailed: tailed, layoutDirection: layoutDirection))
    }
    private var surfaceColor: Color {
        if voiceCard { return isMine ? appearance.voiceOutgoing : appearance.voiceIncoming }
        if documentCard { return isMine ? appearance.fileOutgoing : appearance.fileIncoming }
        return isMine ? appearance.outgoing : appearance.incoming
    }

    /// The time sits on the picture (white on a dark pill) only without a caption;
    /// under a caption it is ordinary muted metadata (it was white there too).
    private var metaOnMedia: Bool { edgeToEdgeMedia && !hasMediaCaption }
    private var hasMediaCaption: Bool {
        guard !isAlbumCell, let caption = message.content.text else { return false }
        return MessageTextPolicy.presentationResult(caption).kind != .empty
    }
    private var edgeToEdgeMedia: Bool {
        if albumCaptionMode { return true }
        guard !message.isDeletedForEveryone else { return false }
        if pendingPreview?.isVisual == true { return true }
        guard let attachment = message.content.attachment else { return false }
        return attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/")
    }
    private var bareMedia: Bool { edgeToEdgeMedia && (pendingPreview?.isVisual == true ? pendingPreview?.caption ?? "" : message.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    /// A round video message without a caption: a bare circle, no bubble and no frame.
    /// With a caption (another client) or inside an album it stays an ordinary video.
    private var videoNoteBubble: Bool {
        guard !isAlbumCell, !albumCaptionMode, !message.isDeletedForEveryone else { return false }
        guard let attachment = message.content.attachment else {
            return pendingPreview?.isVideoNote == true && (pendingPreview?.caption ?? "").isEmpty
        }
        return attachment.isVideoNote && !hasMediaCaption
    }
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

    private var isReplyEligible: Bool {
        !selecting && !appearancePreviewMode && !isAlbumCell &&
        !message.isDeletedForEveryone && !message.hiddenLocally &&
        message.status != .decryptFailed && message.status != .identityChanged &&
        UnyriQuickReactionPolicy.eligible(status: message.status.rawValue, serverId: message.serverId, deleted: message.isDeletedForEveryone || message.hiddenLocally, selecting: selecting)
    }

    var body: some View {
        // Alignment through the frame, not Spacers: an HStack with Spacers probed the
        // width-limited bubble at several widths, measuring the text ~3x per pass.
        HStack(alignment: .unyriMessageBodyCenter, spacing: 0) {
            if selecting && isMine && !isAlbumCell { selectionMark }
            messageSurface
            .unyriSwipeToReply(enabled: isReplyEligible, onReply: onReply)
            .unyriAppearanceMaximumWidth(maximumWidth)
            .background(UnyriMessageMenuAnchor(presenter: messageMenu, open: showMessageMenu, enabled: !selecting && !appearancePreviewMode,
                previewBuilder: { AnyView(messageSurface) }, previewSize: captionMenuPreviewSize))
            .accessibilityAction(named: Text("More options"), showMessageMenu)
            if selecting && !isMine && !isAlbumCell { selectionMark }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
    }

    private var messageSurface: some View {
        // Emoji-only text has no bubble: no fill or padding, its time on its own pill,
        // and the reactions under it as under bare media.
        let bare = bigEmoji != nil
        return VStack(alignment: isMine ? .trailing : .leading, spacing: edgeToEdgeMedia || bare ? 2 : 0) {
            VStack(alignment: isMine ? .trailing : .leading, spacing: 2) {
            if !isAlbumCell, message.content.forwarded {
                Label { Text(UnyriL10n.string("Forwarded")) } icon: { Image(systemName: "arrowshape.turn.up.right.fill") }
                    .labelStyle(.titleAndIcon).font(.caption)
                    .foregroundColor(isMine ? UnyriSavedChatStyle.outText.opacity(0.75) : UnyriColors.secondaryText)
                    // Inside a text bubble the content padding already applies; the extra
                    // inset put the label out of line with the text below it.
                    .padding(.horizontal, edgeToEdgeMedia ? 10 : 0).padding(.top, edgeToEdgeMedia ? 6 : 0).padding(.bottom, 2)
                    .accessibilityElement(children: .combine)
            }
            if !isAlbumCell, let snapshot = message.content.replySnapshot {
                replyPreview(snapshot).contentShape(Rectangle()).onTapGesture(perform: onJumpQuote)
            }

            if let scheduledAt, message.status == .pending { Text("Schedule message").font(.caption); Text(scheduledAt.formatted()).font(.caption) }
            UnyriMessageSurfaceContent(custom: appearancePreviewContent, actual: AnyView(contentView),
                                      captionOnly: albumCaptionMode, settingsPreview: appearancePreviewMode)
                .overlay(alignment: .center) {
                    if videoNoteBubble, isMine, message.status == .pending {
                        // Progress runs around the circle; Cancel stays in the middle.
                        UnyriVideoNoteUploadRing(clientMessageId: message.clientMessageId, cancel: onDeleteForMe)
                    } else if edgeToEdgeMedia, !albumCaptionMode, isMine, message.status == .pending {
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

            if let address = linkAddress {
                VStack(alignment: .trailing, spacing: 4) {
                // Same quiet style as a reply quote: accent bar, small type, light inner plate.
                Link(destination: address) {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 1.5).fill(isMine ? UnyriSavedChatStyle.outText.opacity(0.7) : UnyriColors.primaryText.opacity(0.7))
                            .frame(width: 3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(address.host ?? "").font(.system(size: 14 * bodyScale, weight: .semibold)).lineLimit(1)
                                .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                            if !address.path.isEmpty && address.path != "/" {
                                Text(address.path).font(.system(size: 14 * bodyScale)).lineLimit(2)
                                    .foregroundStyle(UnyriSavedChatStyle.muted)
                            }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 280, alignment: .leading)
                    .padding(.horizontal, 8).padding(.vertical, 6)
                    .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                }
                if linkTimeRow { reactionTimeRow(chips: true) }
                }
            }
            // Reactions sit inside the bubble (Telegram-like), not on the wallpaper below it.
            // Text, links, captions and invites carry them on the time's line; voice and file
            // cards keep their time in the card and get a compact row of chips under it, as
            // does text whose last line has no room for them.
            if !edgeToEdgeMedia, !bare, !isAlbumCell, hasReactionChips, !reactionsInTimeRow,
               inlineReactionReserve(overlay: inlineOverlay) == 0 {
                reactionRows(bare: false)
                    .padding(.top, 2) // with the stack's spacing, ChatReactionMetrics.rowTop
                    .transition(.identity)
            }
            if metaRowInsideCard { reactionTimeRow(chips: true).frame(maxWidth: .infinity, alignment: .trailing) }

            }
            .padding(.leading, edgeToEdgeMedia || bare ? 0 : voiceCard ? 8 : appearance.padX)
            .padding(.trailing, edgeToEdgeMedia || bare ? 0 : voiceCard ? 11 : appearance.padX)
            .padding(.vertical, edgeToEdgeMedia || bare ? 0 : voiceCard ? appearance.voicePadY : (documentCard && isAlbumCell) ? 3 : appearance.padY)
            .frame(width: voiceCard || documentCard ? maximumWidth : nil)
            .background {
                if !edgeToEdgeMedia && !bare {
                    surfaceShape.fill(surfaceColor)
                    reactionSurface(surfaceShape, fill: surfaceColor)
                }
            }
            .alignmentGuide(.unyriMessageBodyCenter) { $0[VerticalAlignment.center] }
            // In-bubble chips are inside this double-tap recognizer; their "__reaction_bar"
            // target keeps double-tap and long-press off them (lastTouchWasReaction).
            .modifier(UnyriMessageDoubleTap(action: guardedQuickReaction))
            if !isAlbumCell, !bare, !message.isDeletedForEveryone && !edgeToEdgeMedia && !inlineMetadata && !fileFooter && !metaInsideCard && UnyriSavedChatStyle.showTimes
                && !(linkAddress != nil && UnyriSavedChatStyle.metadataMode == "inline" && message.status != .failed) {
                metaLine.padding(.horizontal, voiceCard ? 8 : appearance.padX).padding(.top, UnyriSavedChatStyle.timeOffset)
            }
            if (edgeToEdgeMedia && !captionTimeRow) || bare, !isAlbumCell, hasReactionChips {
                // On the wallpaper, wrapping within the picture's width.
                reactionRows(bare: true, maxWidth: visualContentWidth ?? maximumWidth)
                    // The Lens reference keeps reactions outside the measured body.
                    .padding(.top, UnyriSavedChatStyle.reactionOffset)
                    .transition(.identity)
            }
            if !isAlbumCell && edgeToEdgeMedia && message.status == .failed { Button("Retry", action: onRetry).buttonStyle(.plain) }
        }
        // The bubble follows the chips' width: a slot added or removed, or a count that appears
        // (1 -> 2), leaves or gains a digit. Swapping my lone emoji keeps it, so nothing animates.
        .animation(edgeToEdgeMedia || reduceReactionMotion ? nil : (reactions.isEmpty ? .timingCurve(0.4, 0, 1, 1, duration: 0.13) : .timingCurve(0.2, 0, 0.2, 1, duration: 0.16)),
                   value: ChatReactionMetrics.chipsWidth(displayedReactions, footerChip))
        .environment(\.unyriMessagePreviewState, effectivePreviewState)
    }

    private var mediaCaptionFill: Color { isMine ? appearance.outgoing : appearance.incoming }

    private var guardedQuickReaction: (() -> Void)? {
        guard let send = onQuickReaction else { return nil }
        return {
            guard !messageMenu.lastTouchWasReaction else { return }
            // The reaction's flyer pops out of the fingertip, where a chip will take it.
            if canLandReaction, let anchor = messageMenu.anchor as? UnyriMessageMenuAnchorView,
               let point = anchor.lastTouchPoint, let window = anchor.window {
                UnyriReactionBursts.shared.arm(messageID: message.clientMessageId, origin: UnyriReactionBursts.Origin(
                    window: window, point: anchor.convert(point, to: nil), glyph: 0, source: .doubleTap))
            }
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
            // A fixed-width file row with its time under it on the right; a flexible time
            // row used to stretch the bubble and push the card to one side (report 0.3.26).
            // Time and ticks sit at the end of the size/status line, like a document row
            // (a separate time row made the card a third taller; report 0.4.0).
            UnyriDirectFileCard(offer: directFile, chatId: message.chatId, outgoing: isMine, selecting: selecting, select: onSelect,
                text: isAlbumCell ? nil : message.content.text, historyOnly: message.content.authentication == nil, inAlbum: isAlbumCell,
                metadata: metaInsideCard ? AnyView(metaLine) : nil)
            .frame(width: isAlbumCell ? nil : min(264, max(160, maximumWidth - 2 * appearance.padX)))
        } else if let invitation = message.content.conferenceInvite {
            UnyriConferenceInvitationCard(metadata: invitation, message: message, outgoing: isMine,
                selecting: selecting, select: onSelect)
        } else if (message.status == .pending || message.status == .failed), message.content.attachment == nil, let preview = pendingPreview {
            if videoNoteBubble, let visual = preview.visual {
                UnyriQueuedVideoNoteBubble(id: message.clientMessageId, preview: preview,
                    size: unyriChatVisualSize(width: visual.width, height: visual.height, cellWidth: mediaCellWidth, cellHeight: mediaCellHeight),
                    metadata: UnyriSavedChatStyle.showTimes ? AnyView(metaLine.padding(.horizontal, 6).padding(.vertical, 3)
                        .background(.black.opacity(0.62), in: Capsule()).padding(6)) : nil)
            } else if preview.isVisual {
                // Same layout as the uploaded bubble below (time on the media without a
                // caption, under the caption with one): the row keeps its height when the
                // upload completes instead of growing by a line.
                let pendingCaption = isAlbumCell ? nil : preview.caption.flatMap { MessageTextPolicy.presentationResult($0).kind != .empty ? $0 : nil }
                VStack(alignment: .leading, spacing: 0) {
                    QueuedVisualMediaView(preview: preview, messageID: message.clientMessageId)
                        .overlay(alignment: .bottomTrailing) {
                            if !isAlbumCell && UnyriSavedChatStyle.showTimes && pendingCaption == nil {
                                metaLine.padding(.horizontal, 6).padding(.vertical, 3)
                                    .background(.black.opacity(0.62), in: Capsule()).padding(6)
                            }
                        }
                    if let caption = pendingCaption {
                        VStack(alignment: .trailing, spacing: 2) {
                            UnyriMessageRichText(source: caption).equatable().font(messageFont).lineSpacing(appearance.extraLineSpacing * bodyScale)
                                .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if UnyriSavedChatStyle.showTimes { reactionTimeRow(chips: captionTimeRow).foregroundStyle(UnyriSavedChatStyle.muted) }
                        }
                        .padding(.horizontal, max(0, appearance.padX - 2)).padding(.vertical, appearance.padY)
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
            } else { QueuedVoiceFileView(preview: preview, id: message.clientMessageId, cancel: onDeleteForMe,
                                         metadata: metaInsideCard ? AnyView(metaLine) : nil) }
        } else if let attachment = message.content.attachment {
            VStack(alignment: .leading, spacing: 0) {
            AttachmentBubbleView(
                attachment: attachment,
                isMine: isMine,
                pendingSend: isMine && message.serverId == nil && (message.status == .pending || message.status == .failed),
                roundVideo: videoNoteBubble,
                clientMessageId: videoNoteBubble && isMine ? message.clientMessageId : nil,
                onDownload: onDownloadAttachment,
                onCachedLocalPath: onCachedLocalPath,
                isVoicePlaying: isVoicePlaying,
                voicePositionMs: voicePositionMs,
                onToggleVoice: onToggleVoice,
                fileMetadata: fileFooter || (voiceCard && metaInsideCard) ? AnyView(metaLine) : nil,
                voiceTransferControl: isMine && message.status == .pending ? AnyView(UnyriVoiceTransferControl(id: message.clientMessageId, cancel: onDeleteForMe)) : nil,
                fileTransferControl: isMine && message.status == .pending ? AnyView(UnyriVoiceTransferControl(id: message.clientMessageId, cancel: onDeleteForMe)) : nil
            )
            .overlay(alignment: .bottomTrailing) {
                // Without a caption the time sits on the media; with one it moves to the
                // caption's bottom-right (Telegram-like) instead of floating on the media.
                if edgeToEdgeMedia && !isAlbumCell && UnyriSavedChatStyle.showTimes && !hasMediaCaption {
                    metaLine.padding(.horizontal, 6).padding(.vertical, 3)
                        .background(.black.opacity(0.62), in: Capsule()).padding(6)
                }
            }
            .environment(\.unyriMessageQuickReaction, guardedQuickReaction)

            if !isAlbumCell, let caption = message.content.text, MessageTextPolicy.presentationResult(caption).kind != .empty {
                VStack(alignment: .trailing, spacing: 2) {
                    UnyriMessageRichText(source: caption).equatable().font(messageFont).lineSpacing(appearance.extraLineSpacing * bodyScale)
                        .foregroundColor(isMine ? UnyriSavedChatStyle.outText : UnyriColors.onIncomingBubble)
                        // The caption block spans the full media width, not just its text.
                        .frame(maxWidth: edgeToEdgeMedia ? .infinity : nil, alignment: .leading)
                    if edgeToEdgeMedia && UnyriSavedChatStyle.showTimes { reactionTimeRow(chips: captionTimeRow).foregroundStyle(UnyriSavedChatStyle.muted) }
                }
                .padding(.horizontal, edgeToEdgeMedia ? max(0, appearance.padX - 2) : 0).padding(.vertical, edgeToEdgeMedia ? appearance.padY : 0)
                .background {
                    if edgeToEdgeMedia {
                        mediaCaptionFill
                        // The media bubble's clip rounds its corners.
                        reactionSurface(AnyShape(Rectangle()), fill: mediaCaptionFill)
                    }
                }
            }
            }
            .frame(maxWidth: edgeToEdgeMedia ? mediaCellWidth ?? UnyriSavedChatStyle.mediaWidth : nil, alignment: .leading)
            .frame(width: visualContentWidth, alignment: .leading)
            .background {
                if !edgeToEdgeMedia { Color.clear }
            }
            .clipShape(RoundedRectangle(cornerRadius: edgeToEdgeMedia ? isAlbumCell ? 0 : UnyriSavedChatStyle.mediaRadius : 0))
            // Hairline frame for the whole media bubble (media + caption): dark footage
            // otherwise melts into the dark chat background.
            .overlay {
                if edgeToEdgeMedia && !isAlbumCell && !videoNoteBubble {
                    RoundedRectangle(cornerRadius: UnyriSavedChatStyle.mediaRadius).strokeBorder(.white.opacity(0.08), lineWidth: 0.5)
                }
            }
        } else if let emoji = bigEmoji {
            // Size follows the chat text size and Dynamic Type, within limits.
            ChatBigEmojiMessage(id: message.clientMessageId, glyphs: emoji.glyphs, count: emoji.count, isMine: isMine,
                scale: min(1.4, max(0.85, appearance.fontSize / 17 * bodyScale)),
                tappable: !selecting && !appearancePreviewMode,
                pops: !reduceReactionMotion && !appearancePreviewMode
                    && ChatBigEmojiArrivals.shared.isNew(message.clientMessageId, createdAt: message.createdAt),
                metadata: UnyriSavedChatStyle.showTimes ? AnyView(metaLine) : nil)
                .environment(\.unyriMessageQuickReaction, guardedQuickReaction)
        } else {
            Group {
                if inlineMetadata {
                    // The inline time and ticks keep their room as glyph runs inside the text
                    // and are drawn over it (inlineFooter); VoiceOver reads the message and a
                    // spoken status instead of "check mark".
                    let timeOverlay = inlineOverlay
                    let reserve = inlineReactionReserve(overlay: timeOverlay)
                    Text(lensText(overlay: timeOverlay, reserve: reserve))
                        .accessibilityLabel(Text(displayedText))
                        .accessibilityValue(Text(inlineAccessibilityStatus))
                        .overlay(alignment: Alignment(horizontal: .trailing, vertical: .lastTextBaseline)) {
                            if timeOverlay { inlineFooter(reserve: reserve) }
                        }
                }
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

    /// Reactions on a "This message was deleted" placeholder are noise.
    private var displayedReactions: [ReactionSummary] { message.isDeletedForEveryone ? [] : reactions.filter { $0.count > 0 } }
    private var hasReactionChips: Bool { UnyriSavedChatStyle.showReactions && !displayedReactions.isEmpty }
    /// Chips one text line tall at this bubble's text size.
    private var footerChip: ChatReactionMetrics.Chip { ChatReactionMetrics.chip(lineHeight: appearance.messageLineHeight * bodyScale) }

    /// Room the chips take on the text's last line just before the time (chips plus the gap
    /// to it), or 0 when they go in a row under the text instead: no time drawn over the
    /// text, custom preview content, or chips and time wider than a whole line.
    private func inlineReactionReserve(overlay: Bool) -> CGFloat {
        guard overlay, appearancePreviewContent == nil, hasReactionChips else { return 0 }
        let chips = ChatReactionMetrics.chipsWidth(displayedReactions, footerChip) + ChatReactionMetrics.timeGap
        let time = ChatReactionMetrics.timeWidth(scale: bodyScale, mine: isMine, edited: message.isEdited, editedLabel: UnyriL10n.string("edited"))
        return chips + time <= maximumWidth - 2 * appearance.padX ? chips : 0
    }

    /// A link card's time line under the card.
    private var linkTimeRow: Bool {
        linkAddress != nil && UnyriSavedChatStyle.metadataMode == "inline" && UnyriSavedChatStyle.showTimes && message.status != .failed
    }
    /// A photo or video caption's own time line.
    private var captionTimeRow: Bool { edgeToEdgeMedia && hasMediaCaption && UnyriSavedChatStyle.showTimes }
    /// Links, captions and invites carry the chips on their time line, not in a row of their own.
    private var reactionsInTimeRow: Bool { linkTimeRow || metaRowInsideCard || captionTimeRow }

    private func reactionChip(_ reaction: ReactionSummary, _ chip: ChatReactionMetrics.Chip, bare: Bool) -> some View {
        ChatReactionChip(reaction: reaction, metrics: chip, bare: bare, outgoing: isMine,
                         burstKey: reactionBurstServerId.map { UnyriReactionBurstPolicy.key(chat: message.chatId, server: $0, emoji: reaction.emoji) }) {
            if reaction.isMine { onRemoveReaction() } else { onReact(reaction.emoji) }
        }
    }

    /// The server id reaction landings are keyed by; none in previews and album rows.
    private var reactionBurstServerId: String? { appearancePreviewMode || isAlbumCell ? nil : message.serverId }
    /// Album chips take no landing, so a flyer armed there would hover and dissolve.
    private var canLandReaction: Bool { reactionBurstServerId != nil }

    /// The effect surface above a fill, while a landing is coming or playing on this bubble.
    /// A plain read of the coordinator: other rows mount nothing.
    @ViewBuilder private func reactionSurface(_ shape: AnyShape, fill: Color) -> some View {
        if hasReactionChips, let serverId = reactionBurstServerId {
            let key = UnyriReactionBurstPolicy.messageKey(chat: message.chatId, server: serverId)
            if UnyriReactionBursts.shared.wantsSurface(key) {
                UnyriReactionSurface(messageKey: key, shape: shape, light: Self.isLight(fill))
            }
        }
    }

    private static func isLight(_ fill: Color) -> Bool {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        _ = UIColor(fill).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return UnyriReactionBurstPolicy.isLightSurface(red: red, green: green, blue: blue)
    }

    /// One chip per emoji with a count ("❤️ 2"), wrapped into rows; your own reaction is the
    /// brighter chip.
    private func reactionRows(bare: Bool, maxWidth: CGFloat = .infinity) -> some View {
        let chip = footerChip
        return UnyriReactionRows(spacing: ChatReactionMetrics.spacing, rowGap: ChatReactionMetrics.rowGap, maxWidth: maxWidth) {
            ForEach(displayedReactions, id: \.emoji) { reactionChip($0, chip, bare: bare) }
        }
        .background(UnyriReactionTarget(presenter: messageMenu, emoji: "__reaction_bar"))
    }

    /// A card's or caption's time line, with the chips just before the time when `chips`.
    /// They wrap within the room the time leaves, and the time stays centred on their last
    /// row: one row costs the line only the chip's extra height over the time's.
    private func reactionTimeRow(chips: Bool) -> some View {
        HStack(alignment: .center, spacing: 0) {
            if chips && hasReactionChips {
                let chip = footerChip
                reactionRows(bare: false)
                    .padding(.trailing, ChatReactionMetrics.timeGap)
                    .alignmentGuide(VerticalAlignment.center) { $0[VerticalAlignment.bottom] - chip.height / 2 }
                    .transition(.identity)
            }
            metaLine.layoutPriority(1)
        }
    }

    /// Timestamp (+ status/ticks for own messages) for every non-deleted
    /// message — Android parity. Subtle, never overlaps bubble content.
    @ViewBuilder
    private var metaLine: some View {
        HStack(spacing: 4) {
            // Inline voice rows already show progress on the ring; the caption overflowed them.
            if voiceCard && isMine && message.status == .pending && !metaInsideCard { UnyriVoiceTransferCaption(id: message.clientMessageId) }
            UnyriCompactMessageMetadata(date: message.createdAt, status: message.status, outgoing: isMine, edited: message.isEdited,
                                       foreground: metaOnMedia ? .white : nil,
                                       liveID: appearancePreviewMode ? nil : message.clientMessageId)
            if isMine && message.status == .failed && !edgeToEdgeMedia {
                Button("Retry", action: onRetry).buttonStyle(.plain).foregroundStyle(UnyriColors.danger)
            }
        }
        .font(.system(size: UnyriSavedChatStyle.timeSize * bodyScale))
        .foregroundStyle(metaOnMedia ? .white : UnyriSavedChatStyle.muted)
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
            if let attachment = message.content.attachment, let onCopyMedia,
               let kind = UnyriMessageMediaCopyPolicy.kind(message: message, localPath: onCachedLocalPath(attachment.attachmentId)),
               UnyriMessageMediaCopyPolicy.pasteboardType(kind: kind, mime: attachment.mimeType, filename: attachment.filename) != nil {
                primary.append(item("copy-media", "Copy", "doc.on.doc", onCopyMedia))
            }
            if !(message.content.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                primary.append(item("copy", message.content.attachment == nil ? "Copy" : "Copy text", "doc.on.doc", onCopy))
            }
            if voiceCard, let voiceId = message.content.attachment?.attachmentId, let transcript = VoiceTranscriber.shared.visibleText(for: voiceId) {
                primary.append(item("copy-transcript", "Copy text", "doc.on.doc", { UIPasteboard.general.string = transcript }))
            }
            if remoteActions && message.content.directFile == nil { primary.append(item("forward", "Forward", "arrowshape.turn.up.right", onForward)) }
            // Pending/failed text can be edited too: the repository freezes
            // the in-flight payload and applies the latest intent after serverId.
            if isMine && message.kind == "content" && !message.content.forwarded && message.content.attachment == nil && message.content.conferenceInvite == nil && message.content.directFile == nil && pendingPreview == nil {
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
                             else {
                                 if let origin = messageMenu.takeReactionOrigin(), canLandReaction {
                                     UnyriReactionBursts.shared.arm(messageID: message.clientMessageId, origin: origin)
                                 }
                                 onReact(emoji)
                             }
                         }, outgoing: isMine,
                         reactionAccount: currentUserId.map { AppConfig.storageOrigin + "|" + $0.lowercased() })
    }
}

extension MessageBubble: Equatable {
    static func == (lhs: MessageBubble, rhs: MessageBubble) -> Bool {
        lhs.message == rhs.message &&
        lhs.scheduledAt == rhs.scheduledAt &&
        lhs.isPinned == rhs.isPinned &&
        lhs.isMine == rhs.isMine &&
        lhs.currentUserId == rhs.currentUserId &&
        lhs.replyPeerName == rhs.replyPeerName &&
        lhs.reactions == rhs.reactions &&
        lhs.selecting == rhs.selecting &&
        lhs.selected == rhs.selected &&
        lhs.albumCaptionMode == rhs.albumCaptionMode &&
        lhs.appearancePreviewMode == rhs.appearancePreviewMode &&
        lhs.actionMemberCount == rhs.actionMemberCount &&
        lhs.membersStateKey == rhs.membersStateKey
    }
}

// MARK: - Emoji-only messages

/// 1...3 emoji and nothing else (whitespace ignored) make a big-emoji message. A flag, a
/// skin tone, a ZWJ sequence or a keycap is one emoji; plain digits, # and * (Emoji=Yes
/// in Unicode) and text-style symbols such as (c) or TM without VS16 are not.
private enum ChatEmojiOnlyText {
    /// Three of the longest sequences (a subdivision flag is 28 UTF-8 bytes) and spacing.
    static let maximumBytes = 128

    /// The emoji with the whitespace taken out, or nil for an ordinary message.
    static func glyphs(_ text: String) -> (count: Int, glyphs: String)? {
        guard !text.isEmpty, text.utf8.count <= maximumBytes else { return nil }
        var count = 0
        var glyphs = ""
        for character in text {
            if character.isWhitespace { continue }
            guard isEmoji(character) else { return nil }
            count += 1
            guard count <= 3 else { return nil }
            glyphs.append(character)
        }
        return count > 0 ? (count, glyphs) : nil
    }

    static func isEmoji(_ character: Character) -> Bool {
        let scalars = character.unicodeScalars
        guard let first = scalars.first else { return false }
        // 0-9, # and * are pictures only as keycaps (1, VS16, U+20E3).
        if first.isASCII { return scalars.contains { $0.value == 0x20E3 } }
        if first.properties.isEmojiPresentation { return true }
        guard first.properties.isEmoji else { return false }
        // A bare U+2764 is drawn as the red heart on Apple platforms; other text-style
        // symbols become pictures with VS16, a skin tone, a ZWJ sequence or tag characters.
        if scalars.count == 1 { return first.value == 0x2764 }
        return scalars.dropFirst().contains { scalar in
            scalar.value == 0xFE0F || scalar.value == 0x200D || scalar.value == 0x20E3
                || scalar.properties.isEmojiModifier || (0xE0020...0xE007F).contains(scalar.value)
        }
    }
}

/// Dynamic Type scale for row estimates, read once per text size change (estimates run
/// for every unmeasured row on each update, so no trait lookup per row).
@MainActor
private enum ChatRowEstimateMetrics {
    private static var cached: (category: UIContentSizeCategory, scale: CGFloat)?
    static var typeScale: CGFloat {
        let category = UIApplication.shared.preferredContentSizeCategory
        if let cached, cached.category == category { return cached.scale }
        let scale = UIFontMetrics(forTextStyle: .body).scaledValue(for: 100, compatibleWith: UITraitCollection(preferredContentSizeCategory: category)) / 100
        cached = (category, scale)
        return scale
    }
    static var rightToLeft: Bool { UIApplication.shared.userInterfaceLayoutDirection == .rightToLeft }
}

/// Reaction chip geometry, shared by the bubble layout and the row estimate so the two
/// cannot drift apart. Every size follows the message font's line height (text size
/// setting and Dynamic Type). Main-thread caches only.
@MainActor
private enum ChatReactionMetrics {
    /// Between chips, between wrapped rows, from the last chip to the time, and above a
    /// row of chips under a bubble's content (stack spacing 2 + padding 2).
    static let spacing: CGFloat = 4, rowGap: CGFloat = 4, timeGap: CGFloat = 6, rowTop: CGFloat = 4
    /// From a chip's leading edge to its emoji slot (the reaction landing aims at the slot).
    static let emojiLead: CGFloat = 5

    struct Chip: Equatable {
        let height: CGFloat
        let emojiPoint: CGFloat
        /// A fixed emoji slot, so a chip's width never depends on the glyph.
        let emojiBox: CGFloat
        let countPoint: CGFloat
        /// Advance of one tabular digit of the count, rounded up.
        let digit: CGFloat
    }
    private static var chips: [CGFloat: Chip] = [:]

    /// One text line tall (20 pt at 17 pt text), within 18...30.
    static func chip(lineHeight: CGFloat) -> Chip {
        let height = min(30, max(18, lineHeight.rounded(.down)))
        if let cached = chips[height] { return cached }
        let emoji = (height * 0.65).rounded(), count = (height * 0.55).rounded()
        let digit = ("0" as NSString).size(withAttributes: [.font: UIFont.monospacedDigitSystemFont(ofSize: count, weight: .semibold)]).width.rounded(.up)
        let chip = Chip(height: height, emojiPoint: emoji, emojiBox: (emoji * 1.3).rounded(.up), countPoint: count, digit: digit)
        chips[height] = chip
        return chip
    }

    /// 27 pt for an emoji alone at 17 pt text; with a count, a little more room after the
    /// digits than before the emoji, which balances the pill optically.
    static func width(_ reaction: ReactionSummary, _ chip: Chip) -> CGFloat {
        guard reaction.count > 1 else { return emojiLead + chip.emojiBox + 5 }
        return emojiLead + chip.emojiBox + 2 + CGFloat(String(reaction.count).count) * chip.digit + 6
    }

    static func chipsWidth(_ reactions: [ReactionSummary], _ chip: Chip) -> CGFloat {
        reactions.reduce(0) { $0 + width($1, chip) } + spacing * CGFloat(max(0, reactions.count - 1))
    }

    /// The chips packed the way UnyriReactionRows packs them within `limit`.
    static func rowsHeight(_ reactions: [ReactionSummary], _ chip: Chip, limit: CGFloat) -> CGFloat {
        guard !reactions.isEmpty else { return 0 }
        var rows: CGFloat = 1
        var x: CGFloat = 0
        for reaction in reactions {
            let chipWidth = width(reaction, chip)
            if x > 0 && x + chipWidth > limit { rows += 1; x = 0 }
            x += chipWidth + spacing
        }
        return rows * chip.height + (rows - 1) * rowGap
    }

    private struct TimeKey: Hashable {
        let scale: CGFloat
        let mine: Bool
        let edited: Bool
        let editedLabel: String
    }
    private static var times: [TimeKey: CGFloat] = [:]

    /// The inline time run with the gap before it: two en spaces, tabular "00:00",
    /// " edited", and on own messages the receipt slot (always as wide as "✓✓").
    static func timeWidth(scale: CGFloat, mine: Bool, edited: Bool, editedLabel: String) -> CGFloat {
        let key = TimeKey(scale: scale, mine: mine, edited: edited, editedLabel: editedLabel)
        if let cached = times[key] { return cached }
        let size = UnyriSavedChatStyle.timeSize * scale
        func width(_ text: String, _ font: UIFont) -> CGFloat { (text as NSString).size(withAttributes: [.font: font]).width }
        var value = width("\u{2002}\u{2002}", .systemFont(ofSize: size)) + width("00:00", .monospacedDigitSystemFont(ofSize: size, weight: .regular))
        if edited { value += width("\u{00a0}" + editedLabel, .systemFont(ofSize: size)) }
        // The receipt slot pads a narrower status up to whole points: one point of slack.
        if mine { value += width("\u{00a0}\u{00a0}✓✓", .systemFont(ofSize: size)) + 1 }
        value = value.rounded(.up)
        times[key] = value
        return value
    }

    /// Whether the last paragraph runs right to left (its first letter is Hebrew, Arabic,
    /// Syriac, Thaana or N'Ko). The inline time sits at that paragraph's end, which is
    /// the bubble's trailing edge only when it runs the same way as the interface.
    static func endsRightToLeft(_ text: String) -> Bool {
        let scalars = text.unicodeScalars
        var end = scalars.endIndex
        while end > scalars.startIndex, scalars[scalars.index(before: end)] == "\n" { end = scalars.index(before: end) }
        var start = end
        while start > scalars.startIndex, scalars[scalars.index(before: start)] != "\n" { start = scalars.index(before: start) }
        for scalar in scalars[start..<end] where scalar.properties.isAlphabetic {
            switch scalar.value {
            case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF, 0x10800...0x10FFF, 0x1E800...0x1EFFF: return true
            default: return false
            }
        }
        return false
    }
}

/// One reaction pill: the emoji in a fixed slot and, from two, a tabular count. Its size
/// comes from ChatReactionMetrics, so the text reservation and the row estimate know it
/// without measuring. Mine is the brighter pill with a glass edge; no blur or material,
/// which would cost on every scrolled frame.
private struct ChatReactionChip: View {
    let reaction: ReactionSummary
    let metrics: ChatReactionMetrics.Chip
    /// On the wallpaper (bare media, big emoji, albums) rather than inside a bubble.
    let bare: Bool
    /// The pill sits on (or under) my bubble: its count takes that bubble's text colour.
    let outgoing: Bool
    /// Its reaction landing's key; nil where none plays (album rows, previews).
    var burstKey: String? = nil
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            HStack(spacing: 2) {
                Text(reaction.emoji).font(.system(size: metrics.emojiPoint)).fixedSize()
                    .frame(width: metrics.emojiBox)
                if reaction.count > 1 {
                    Text(verbatim: String(reaction.count))
                        .font(.system(size: metrics.countPoint, weight: .semibold)).monospacedDigit()
                        .foregroundStyle((outgoing ? UnyriSavedChatStyle.outText : UnyriColors.primaryText).opacity(reaction.isMine ? 1 : 0.85))
                        .fixedSize()
                        // The digits roll to the new count.
                        .contentTransition(.numericText())
                        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.8), value: reaction.count)
                }
            }
            .padding(.leading, ChatReactionMetrics.emojiLead)
            .frame(width: ChatReactionMetrics.width(reaction, metrics), height: metrics.height, alignment: .leading)
            .background { pill }
            .contentShape(Rectangle().inset(by: -4))
        }
        .buttonStyle(UnyriReactionChipPressStyle())
        .accessibilityIdentifier("message-reaction-\(reaction.emoji)")
        .accessibilityAddTraits(reaction.isMine ? .isSelected : [])
        .accessibilityHint(Text(UnyriL10n.string(reaction.isMine ? "Remove reaction" : "Add the same reaction")))
        .modifier(UnyriReactionLanding(reaction: reaction, burstKey: burstKey, bare: bare,
                                       glyph: metrics.emojiPoint, slot: metrics.emojiBox, lead: ChatReactionMetrics.emojiLead))
    }

    @ViewBuilder private var pill: some View {
        if bare {
            Capsule().fill(Color(white: reaction.isMine ? 0.28 : 0.16))
                .overlay { Capsule().strokeBorder(Color.white.opacity(reaction.isMine ? 0.14 : 0.06), lineWidth: 0.5) }
        } else {
            Capsule().fill(Color.white.opacity(reaction.isMine ? 0.20 : 0.08))
                .overlay { if reaction.isMine { Capsule().strokeBorder(Color.white.opacity(0.16), lineWidth: 0.5) } }
        }
    }
}

/// Bounded memo of the emoji-only check, per message id and text (an edit re-checks).
private final class ChatEmojiOnlyCache: @unchecked Sendable {
    struct Entry {
        let count: Int
        let glyphs: String
    }
    private struct Key: Hashable {
        let id: String
        let text: String
    }
    static let shared = ChatEmojiOnlyCache()
    private let lock = NSLock()
    private var values: [Key: Entry] = [:]

    func entry(id: String, text: String) -> Entry? {
        // Long text is never emoji-only, and text that starts with a letter (any script)
        // cannot be: neither is hashed nor stored, so ordinary messages cost O(1) here.
        guard let first = text.unicodeScalars.first, text.utf8.count <= ChatEmojiOnlyText.maximumBytes,
              first.properties.isEmoji || first.properties.isWhitespace else { return nil }
        let key = Key(id: id, text: text)
        lock.lock()
        let cached = values[key]
        lock.unlock()
        if let cached { return cached.count > 0 ? cached : nil }
        let found = ChatEmojiOnlyText.glyphs(text)
        let entry = Entry(count: found?.count ?? 0, glyphs: found?.glyphs ?? "")
        lock.lock()
        if values.count >= 1000 { values.removeAll(keepingCapacity: true) }
        values[key] = entry
        lock.unlock()
        return entry.count > 0 ? entry : nil
    }
}

/// Big-emoji messages whose arrival pop already played in this process: scrolling back
/// to one, reopening the chat or its long-press preview never pops it again.
private final class ChatBigEmojiArrivals: @unchecked Sendable {
    static let shared = ChatBigEmojiArrivals()
    private let lock = NSLock()
    private var played = Set<String>()
    private var order: [String] = []

    /// New: created moments ago (a little server clock skew tolerated) and not popped yet.
    /// History, synced backlogs and older pages are never new.
    func isNew(_ id: String, createdAt: Date) -> Bool {
        let age = Date().timeIntervalSince(createdAt)
        guard age > -120, age < 20 else { return false }
        lock.lock(); defer { lock.unlock() }
        return !played.contains(id)
    }

    /// Takes the one pop of this message; false when it already played.
    func claim(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard played.insert(id).inserted else { return false }
        order.append(id)
        if order.count > 500 { played.remove(order.removeFirst()) }
        return true
    }
}

/// An emoji-only message drawn large on the wallpaper, without the bubble, with its time
/// and receipt on a small translucent pill on the message's side. A new arrival pops once
/// (0.6 -> 1.08 -> 1); a tap replays a small bounce with a light haptic. At rest it is a
/// plain Text under an identity transform: no animation, no timer, no per-frame update.
private struct ChatBigEmojiMessage: View {
    let id: String
    let glyphs: String
    let count: Int
    let isMine: Bool
    let scale: CGFloat
    let tappable: Bool
    let metadata: AnyView?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.unyriMessagePreview) private var inMenuPreview
    @State private var pop: CGFloat
    @State private var taps: CGFloat = 0

    init(id: String, glyphs: String, count: Int, isMine: Bool, scale: CGFloat, tappable: Bool, pops: Bool, metadata: AnyView?) {
        self.id = id
        self.glyphs = glyphs
        self.count = count
        self.isMine = isMine
        self.scale = scale
        self.tappable = tappable
        self.metadata = metadata
        // Starts small only for a new arrival, so the first frame is already the pop's.
        _pop = State(initialValue: pops ? 0 : 1)
    }

    /// 1 emoji 68 pt, 2 emoji 52 pt, 3 emoji 44 pt at the default text size.
    static func pointSize(_ count: Int) -> CGFloat { count <= 1 ? 68 : count == 2 ? 52 : 44 }

    var body: some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 0) {
            Text(glyphs)
                .font(.system(size: Self.pointSize(count) * scale))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .modifier(ChatBigEmojiMotion(pop: pop, taps: taps))
                .contentShape(Rectangle())
                .modifier(ChatBigEmojiTap(enabled: tappable && !inMenuPreview, action: bounce))
                .accessibilityIdentifier("message-big-emoji")
            if let metadata {
                metadata
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(Color(white: 0.16).opacity(0.82), in: Capsule())
                    .overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5))
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("message-big-emoji-meta")
            }
        }
        .onAppear {
            guard pop < 1 else { return }
            if !reduceMotion, ChatBigEmojiArrivals.shared.claim(id) {
                withAnimation(.linear(duration: 0.46)) { pop = 1 }
            } else {
                pop = 1
            }
        }
    }

    private func bounce() {
        UnyriHaptics.press()
        guard !reduceMotion else { return }
        withAnimation(.linear(duration: 0.5)) { taps += 1 }
    }
}

/// The single tap waits for the double-tap decision (quick reaction), like media.
private struct ChatBigEmojiTap: ViewModifier {
    let enabled: Bool
    let action: () -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if enabled { content.modifier(UnyriMessageTap(single: action)) } else { content }
    }
}

/// The arrival pop and the tap bounce as one scale transform around the centre. Both
/// inputs are shaped here from linear progress, so a single one-shot animation drives
/// each: `pop` 0 -> 1 once, `taps` +1 per tap. At rest the transform is the identity.
private struct ChatBigEmojiMotion: GeometryEffect {
    var pop: CGFloat
    var taps: CGFloat
    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(pop, taps) }
        set { pop = newValue.first; taps = newValue.second }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        let scale = Self.popScale(pop) * Self.tapScale(taps - taps.rounded(.down))
        guard scale != 1 else { return ProjectionTransform(CGAffineTransform.identity) }
        return ProjectionTransform(CGAffineTransform(translationX: size.width / 2 * (1 - scale), y: size.height / 2 * (1 - scale))
            .scaledBy(x: scale, y: scale))
    }

    /// 0.6 -> 1.08 (fast, easing out) -> 1 (settling).
    static func popScale(_ progress: CGFloat) -> CGFloat {
        if progress >= 1 { return 1 }
        if progress <= 0 { return 0.6 }
        if progress < 0.55 {
            let t = progress / 0.55
            return 0.6 + 0.48 * (1 - (1 - t) * (1 - t))
        }
        let t = (progress - 0.55) / 0.45
        return 1.08 - 0.08 * t * t * (3 - 2 * t)
    }

    /// One damped bounce: up about 11 %, a little under, then rest.
    static func tapScale(_ fraction: CGFloat) -> CGFloat {
        guard fraction > 0 else { return 1 }
        return 1 + 0.15 * sin(fraction * 2 * .pi) * (1 - fraction)
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
    // Not @EnvironmentObject: observing the player and recorder re-rendered every photo,
    // video and file bubble on each play, pause, speed change and recording. Only the
    // voice row observes the player (AttachmentVoiceContent); these plain references
    // serve actions and the full-screen viewer.
    @Environment(\.chatVoicePlayer) private var voicePlayerReference
    @Environment(\.chatVoiceRecorder) private var voiceRecorderReference
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
    @Environment(\.unyriSendEditedPhoto) private var sendEditedPhoto
    @State private var pendingMediaForward: String?
    /// Builds the chat's viewer items when a viewer opens. A stable closure: the array as
    /// an environment value changed with every message and download and re-rendered
    /// every media bubble.
    @Environment(\.chatMediaItems) private var mediaItemsProvider
    @State private var showMediaViewer = false
    @State private var voicePlayedHere = false
    @Environment(\.chatMediaWidth) private var mediaCellWidth
    @Environment(\.displayScale) private var displayScale
    let attachment: AttachmentMetadata
    let isMine: Bool
    var pendingSend: Bool = false
    /// A round video message: drawn as a circle that plays in place.
    var roundVideo: Bool = false
    /// The sender's own note: its recorder poster is cached under this id until adopted.
    var clientMessageId: String? = nil
    let onDownload: (String) async -> URL?
    let onCachedLocalPath: (String) -> String?
    let isVoicePlaying: (String) -> Bool
    let voicePositionMs: (String) -> Int
    let onToggleVoice: (String, String) -> Void
    var fileMetadata: AnyView? = nil
    var voiceTransferControl: AnyView? = nil
    var fileTransferControl: AnyView? = nil

    // Not @EnvironmentObject: observing the whole repository re-rendered every
    // visible photo/voice/file bubble on each unrelated publish (a new message,
    // a reaction, isLoading, errors), bypassing MessageBubble's Equatable boundary.
    // Only the attachment cache revision is observed, via onReceive below.
    @Environment(\.chatMessagesRepository) private var mediaRepository
    private var attachmentCacheRevisions: AnyPublisher<Int, Never> {
        mediaRepository?.$attachmentCacheRevision.eraseToAnyPublisher() ?? Empty<Int, Never>().eraseToAnyPublisher()
    }
    @StateObject private var attachmentImage = UnyriAttachmentImageSource()
    @State private var localPath: String?
    @State private var previewURL: URL?
    @State private var downloadRequests = Set<UUID>()
    private var isDownloading: Bool { !downloadRequests.isEmpty }
    @State private var downloadFailed = false
    // A newly created offscreen row must not begin thumbnail extraction before
    // the scroll visibility callback has reported its actual position.
    @State private var mediaVisible = false

    /// The local file if it is already downloaded. Read from the cache synchronously on
    /// the very first render: `localPath` is only set in onAppear, after the first frame,
    /// and voice messages flashed a download spinner and a cancel mark on chat entry.
    private var availablePath: String? { localPath ?? onCachedLocalPath(attachment.attachmentId) }
    private var previewDecodeAllowed: Bool {
        AttachmentImagePolicy.allowsWork(visible: mediaVisible, menu: isMenuPreview,
            active: mediaScenePhase == .active, viewerOpen: mediaWork.viewerOpen, viewerSurface: false)
    }
    private var previewWorkAllowed: Bool { !isMenuPreview && previewDecodeAllowed }
    private var displayDownloading: Bool { isMenuPreview ? menuPreviewState?.downloading ?? false : isDownloading }
    private var displayDownloadFailed: Bool { isMenuPreview ? menuPreviewState?.downloadFailed ?? false : downloadFailed }

    var body: some View {
        Group {
            if attachment.mediaType == AttachmentMetadata.mediaTypeImage {
                imageContent.modifier(UnyriMessageTap(single: {
                    if availablePath != nil || !isDownloading { UnyriHaptics.press() }
                    if availablePath != nil { showMediaViewer = true }
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
        .onReceive(attachmentCacheRevisions) { _ in
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
        // The shared binding owns decode visibility/activity. This bubble's
        // mediaVisible gate owns automatic downloads and note playback only.
        .modifier(UnyriAttachmentImageBinding(source: attachmentImage, request: attachmentImageRequest,
            repository: mediaRepository, onVisibility: { mediaVisible = $0 }))
        .onChange(of: attachmentImage.value?.image) { image in
            if !isMenuPreview, let image { menuPreviewState?.retainMediaImage(image, attachmentID: attachment.attachmentId) }
        }
        // A small Hashable id: interpolating the settings struct printed it through
        // reflection on every body pass of every media bubble.
        .task(id: AttachmentAutoDownloadKey(id: attachment.attachmentId, pending: pendingSend, allowed: previewWorkAllowed,
            connected: mediaNetwork.connected, wifi: mediaNetwork.wifi,
            automatic: mediaSettings.allows(mediaType: attachment.mediaType, mime: attachment.mimeType, bytes: attachment.size))) {
            if !pendingSend, previewWorkAllowed, availablePath == nil, !downloadFailed,
                mediaSettings.allows(mediaType: attachment.mediaType, mime: attachment.mimeType, bytes: attachment.size) {
                await download(origin: .automatic)
            }
        }
        // A small cancel mark in the corner instead of a large prominent "Cancel" button
        // that stuck out over voice and photo bubbles while downloading.
        .overlay(alignment: .topTrailing) {
            if !pendingSend && displayDownloading && attachment.mediaType != AttachmentMetadata.mediaTypeFile {
                Button { UnyriHaptics.selection(); downloadCancelled = true; cancelDownload(attachment.attachmentId) } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 24, height: 24).background(Color.black.opacity(0.5), in: Circle())
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel(Text("Cancel download"))
            }
        }
        .unyriQuickLookPreview($previewURL, title: attachment.filename)
        .background(UnyriMediaRotation(active: previewURL != nil))
        .background {
            if !isMenuPreview, attachment.mimeType.hasPrefix("image/") || attachment.mimeType.hasPrefix("video/") {
                UnyriMediaPresentation(presented: $showMediaViewer, id: attachment.attachmentId,
                    image: { attachment.mediaType == AttachmentMetadata.mediaTypeImage || attachment.mimeType.hasPrefix("video/") ? transitionImage : nil }, onDismiss: {
                        if let id = pendingMediaForward { pendingMediaForward = nil; forwardMedia?(id) }
                    }) { close, selection in
                        if let path = localPath {
                            let url = URL(fileURLWithPath: path)
                            let mediaItems = mediaItemsProvider?() ?? []
                            return AnyView(UnyriMediaViewer(items: mediaItems.contains(where: { $0.id == attachment.attachmentId }) ? mediaItems : [UnyriMediaItem(id: attachment.attachmentId, url: url, mime: attachment.mimeType, name: attachment.filename, thumbnailBase64: attachment.thumbnail)],
                                initial: url, download: { await mediaRepository?.downloadAttachment(attachmentId: $0, origin: .mediaViewer) }, onForward: forwardMedia == nil ? nil : { id in
                                    pendingMediaForward = id; close()
                                }, onClose: close, onSelection: selection, initialPreview: transitionImage, initialID: attachment.attachmentId,
                                onSendEdited: sendEditedPhoto)
                                // The presentation creates a separate UIHostingController.
                                .environment(\.chatMessagesRepository, mediaRepository)
                                .modifier(ChatVoiceObjects(player: voicePlayerReference, recorder: voiceRecorderReference))
                                .unyriLocale())
                        }
                        return AnyView(Color.black)
                    }
            }
        }

    }

    private var attachmentImageRequest: AttachmentImageRequest {
        AttachmentImageRequest(attachment: attachment, path: availablePath,
            clientMessageID: clientMessageId, pixelEdge: previewPixelEdge)
    }
    private var transitionImage: UIImage? {
        attachmentImage.best(for: attachmentImageRequest,
            initial: isMenuPreview ? menuPreviewState?.mediaImage(attachmentID: attachment.attachmentId) : nil)
    }

    private var previewPixelEdge: Int {
        if attachment.mediaType == AttachmentMetadata.mediaTypeFile && attachment.mimeType.hasPrefix("image/") { return 128 }
        let pixels = max(imagePreviewSize.width, imagePreviewSize.height) * displayScale
        return pixels <= 384 ? 384 : pixels <= 512 ? 512 : 768
    }

    private var imagePreviewSize: CGSize {
        unyriChatVisualSize(width: attachment.image?.width ?? 1, height: attachment.image?.height ?? 1,
                          cellWidth: mediaCellWidth, cellHeight: mediaCellHeight)
    }

    private var imageContent: some View {
        let preview = transitionImage
        return ZStack(alignment: .center) {
            if let image = preview {
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
        .accessibilityValue(preview == nil ? "" : UnyriL10n.string("Photo"))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("Photo")
    }

    private var voiceContent: some View {
        AttachmentVoiceContent(attachment: attachment, isMine: isMine, availablePath: availablePath,
            downloading: displayDownloading, downloadFailed: displayDownloadFailed, playedHere: voicePlayedHere,
            isVoicePlaying: isVoicePlaying, transferControl: voiceTransferControl, metadata: fileMetadata,
            retry: { Task { await download() } },
            play: {
                if availablePath != nil { voicePlayedHere = true; UnyriVoicePlayedStore.markPlayed(attachment.attachmentId) }
                toggleVoice()
            })
    }

    private var fileContent: some View {
        VStack(alignment: .leading, spacing: 6) {
            if roundVideo { videoNoteCover }
            else if attachment.mimeType.hasPrefix("video/") { videoCover }
            if attachment.mimeType.hasPrefix("video/") && displayDownloadFailed && !roundVideo {
                Button("Download failed · Tap to retry") { UnyriHaptics.press(); Task { await download() } }.font(.caption).padding(6)
            }
            if !attachment.mimeType.hasPrefix("video/") { fileDetails }
        }
    }

    /// The frame matches an ordinary video of the same dimensions, so row geometry and
    /// the time chip stay where the bubble expects them (see VideoNoteViews.swift).
    private var videoNoteCover: some View {
        UnyriVideoNoteBubble(id: attachment.attachmentId, clientMessageId: clientMessageId,
            size: unyriChatVisualSize(width: attachment.image?.width ?? 4, height: attachment.image?.height ?? 3,
                                      cellWidth: mediaCellWidth, cellHeight: mediaCellHeight),
            thumbnail: attachment.thumbnail,
            menuPoster: transitionImage,
            path: availablePath, durationMs: attachment.video?.durationMs, pendingSend: pendingSend,
            downloading: displayDownloading, failed: displayDownloadFailed, menuPreview: isMenuPreview,
            visible: previewWorkAllowed,
            downloadMeter: attachments.downloadMeter(attachment.attachmentId),
            // The long-press menu then shows the sharp poster too.
            onPoster: { menuPreviewState?.retainMediaImage($0, attachmentID: attachment.attachmentId) },
            download: {
                Task {
                    await download()
                    // One tap: download, then play (it took a second tap after the download).
                    guard previewWorkAllowed, let path = localPath, UnyriVideoNotePlayer.shared.activeID == nil,
                          voicePlayerReference?.state.playingAttachmentId == nil, voiceRecorderReference?.callInProgress != true,
                          voiceRecorderReference?.hasRecordingSession != true, !NonCallCaptureOwnership.isBusy else { return }
                    UnyriVideoNotePlayer.shared.toggle(id: attachment.attachmentId, url: URL(fileURLWithPath: path))
                }
            })
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
                Color(white: 0.12)
                if let preview {
                    Image(uiImage: preview).resizable().scaledToFill()
                        .accessibilityIdentifier(isMenuPreview ? "message-menu-video-preview" : "chat-video-preview")
                }
                if !pendingSend && displayDownloading { ProgressView().tint(.white) }
                else if !pendingSend {
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
            icon: AnyView(ZStack {
                if attachment.mimeType.hasPrefix("image/"), let image = transitionImage {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 40, height: 40).clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 9)).accessibilityHidden(true)
                }
                if let fileTransferControl { fileTransferControl }
                else if displayDownloading {
                    FileDownloadRing(meter: attachments.downloadMeter(attachment.attachmentId)) {
                        downloadCancelled = true; cancelDownload(attachment.attachmentId)
                    }
                } else {
                    UnyriMessageActionButton(action: openFileAttachment) {
                        let thumbnail = attachment.mimeType.hasPrefix("image/") && transitionImage != nil
                        Group {
                            if availablePath == nil {
                                Image(systemName: "arrow.down")
                                    .font(.system(size: 16, weight: .semibold))
                            } else if thumbnail {
                                // The picture itself is the icon: nothing on top of it.
                                Color.clear
                            } else {
                                Text(fileTypeTag).font(.system(size: 11, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
                                    .padding(.horizontal, 3)
                            }
                        }
                        .foregroundStyle(UnyriSavedChatStyle.accent)
                        .frame(width: 40, height: 40)
                        .background(Color.white.opacity(thumbnail ? 0 : 0.10), in: RoundedRectangle(cornerRadius: 9))
                        .background(thumbnail && availablePath == nil ? Color.black.opacity(0.35) : .clear, in: RoundedRectangle(cornerRadius: 9))
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityLabel(availablePath == nil ? "Download file" : "Open file")
                }
            }), action: openFileAttachment)
    }

    /// "PDF", "TXT", "KEY"… for the file plate (falls back to the generic label).
    private var fileTypeTag: String {
        let ext = ((attachment.filename ?? "") as NSString).pathExtension.uppercased()
        if !ext.isEmpty, ext.count <= 4 { return ext }
        return String(FilePresentation.label(mime: attachment.mimeType, filename: attachment.filename).uppercased().prefix(4))
    }

    private func openFileAttachment() {
        UnyriHaptics.press()
        if let path = localPath {
            if attachment.mimeType.hasPrefix("image/") { showMediaViewer = true }
            else { previewURL = URL(fileURLWithPath: path) }
        } else if !isDownloading { Task { await download() } }
    }

    private func toggleVoice() {
        if let path = availablePath {
            onToggleVoice(attachment.attachmentId, path)
        } else {
            // One tap: download, then play (it took a second tap after the download).
            UnyriHaptics.press()
            Task {
                await download()
                guard mediaVisible, voicePlayerReference?.state.playingAttachmentId == nil, let path = availablePath else { return }
                voicePlayedHere = true; UnyriVoicePlayedStore.markPlayed(attachment.attachmentId)
                onToggleVoice(attachment.attachmentId, path)
            }
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
        if origin == .automatic { downloaded = await mediaRepository?.downloadAttachment(attachmentId: attachment.attachmentId, origin: origin) }
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

/// The voice row of an attachment bubble (moved out of AttachmentBubbleView unchanged).
/// Only voice bubbles observe the player now; photo and file bubbles no longer
/// re-render on every play, pause, speed change or recording.
private struct AttachmentVoiceContent: View {
    @EnvironmentObject private var voicePlayer: VoicePlayerController
    let attachment: AttachmentMetadata
    let isMine: Bool
    let availablePath: String?
    let downloading: Bool
    let downloadFailed: Bool
    let playedHere: Bool
    let isVoicePlaying: (String) -> Bool
    let transferControl: AnyView?
    let metadata: AnyView?
    let retry: () -> Void
    let play: () -> Void

    @ViewBuilder var body: some View {
        let active = isVoicePlaying(attachment.attachmentId) || voicePlayer.state.pausedAttachmentId == attachment.attachmentId
        if downloadFailed {
            Button { UnyriHaptics.press(); retry() } label: {
                Label("Retry voice message", systemImage: "arrow.clockwise").font(.caption).foregroundStyle(UnyriColors.danger).frame(minHeight: 44)
            }.buttonStyle(.plain)
        } else if active {
            VoiceProgressView(progress: voicePlayer.progress) { position in voiceRow(position: position, active: true) }
        } else { voiceRow(position: 0, active: false) }
    }

    private func voiceRow(position: Int, active: Bool) -> some View {
        let duration = attachment.voice?.durationMs ?? 0
        return UnyriVoiceMessageContent(duration: duration, position: position, active: active,
            playing: isVoicePlaying(attachment.attachmentId), downloading: downloading,
            available: availablePath != nil, speed: voicePlayer.state.speed,
            waveform: AnyView(voiceWaveform(fraction: VoiceFormat.playedFraction(positionMs: position, durationMs: duration))),
            action: play,
            cycleSpeed: { UnyriHaptics.selection(); voicePlayer.cycleSpeed() }, transferControl: transferControl,
            metadata: metadata, unplayed: !isMine && !playedHere && !UnyriVoicePlayedStore.isPlayed(attachment.attachmentId),
            transcript: UnyriVoiceTranscriptSource(entry: VoiceTranscriber.shared.entry(for: attachment.attachmentId),
                                                   path: availablePath, outgoing: isMine))
    }

    /// Real local audio envelope; progress only changes the played-color boundary.
    private func voiceWaveform(fraction: Double) -> some View {
        let active = voicePlayer.state.playingAttachmentId == attachment.attachmentId || voicePlayer.state.pausedAttachmentId == attachment.attachmentId
        return GeometryReader { geometry in
            AudioEnvelopeView(path: availablePath, fraction: fraction,
                played: UnyriSavedChatStyle.accent,
                unplayed: UnyriSavedChatStyle.waveQuiet,
                embedded: VoiceWaveformCodec.decode(attachment.voice?.waveform), decodeOnPlay: active)
                .contentShape(Rectangle())
                // Seeking only exists on the active message, and a drag needs 8 pt first:
                // a zero-distance drag on every waveform swallowed scrolls, swipe-to-reply
                // and the long-press menu that started on a voice bubble.
                .gesture(SpatialTapGesture().onEnded { value in
                    voicePlayer.seek(fraction: Double(value.location.x / max(1, geometry.size.width)))
                }.exclusively(before: DragGesture(minimumDistance: 8).onChanged { value in
                    guard abs(value.translation.width) > abs(value.translation.height) else { return }
                    voicePlayer.seek(fraction: Double(value.location.x / max(1, geometry.size.width)))
                }.onEnded { _ in UnyriHaptics.selection() }), including: active ? .all : .subviews)
        }.frame(minWidth: 20, maxWidth: .infinity).frame(height: UnyriSavedChatStyle.waveHeight)
    }
}

/// Hands the separately hosted media viewer the voice objects it expects in its
/// environment (they exist wherever a chat sets them).
private struct ChatVoiceObjects: ViewModifier {
    let player: VoicePlayerController?
    let recorder: VoiceRecorder?
    @ViewBuilder func body(content: Content) -> some View {
        if let player, let recorder { content.environmentObject(player).environmentObject(recorder) } else { content }
    }
}

/// The voice player/recorder as plain references and the lazy viewer items, set once on
/// the conversation. Reference values that stay the same do not invalidate bubbles.
private struct ChatMediaEnvironment: ViewModifier {
    let player: VoicePlayerController
    let recorder: VoiceRecorder
    let mediaItems: (() -> [UnyriMediaItem])?
    func body(content: Content) -> some View {
        content.environment(\.chatVoicePlayer, player)
            .environment(\.chatVoiceRecorder, recorder)
            .environment(\.chatMediaItems, mediaItems)
    }
}

/// The detector contains the matching rules, not cached message text. Reuse it
/// instead of constructing the same rules for every bubble body evaluation.
private enum ChatLinkDetection {
    static let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
}

/// NSDataDetector runs on every text bubble body. Rich text was already cached;
/// the link-card lookup re-scanned the same text on each row construction,
/// receipt and reaction update. Results (including "no link") are cached per text.
private final class ChatLinkAddressCache: @unchecked Sendable {
    static let shared = ChatLinkAddressCache()
    private final class Box {
        let value: URL?
        init(_ value: URL?) { self.value = value }
    }
    private let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        c.countLimit = 1000
        return c
    }()
    func address(for raw: String, compute: (String) -> URL?) -> URL? {
        let key = raw as NSString
        if let cached = cache.object(forKey: key) { return cached.value }
        let value = compute(raw)
        cache.setObject(Box(value), forKey: key)
        return value
    }
}

private func chatLinkAddress(_ raw: String) -> URL? {
    ChatLinkAddressCache.shared.address(for: raw) { raw in
        let source = chatPresentationText(raw, inline: true)
        guard let detector = ChatLinkDetection.detector else { return nil }
        return detector.matches(in: source, range: NSRange(source.startIndex..., in: source)).compactMap(\.url).first {
            ["https", "http"].contains($0.scheme?.lowercased() ?? "") && $0.host != nil && $0.user == nil
        }
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

private final class ChatRichTextCache: @unchecked Sendable {
    static let shared = ChatRichTextCache()
    private final class Box {
        let value: AttributedString
        init(_ value: AttributedString) { self.value = value }
    }
    private let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        c.countLimit = 1000
        return c
    }()
    func richText(for raw: String) -> AttributedString {
        let key = raw as NSString
        if let cached = cache.object(forKey: key) {
            return cached.value
        }
        let parsed = parse(raw)
        cache.setObject(Box(parsed), forKey: key)
        return parsed
    }
    private func parse(_ raw: String) -> AttributedString {
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
}

private struct ChatLensTextKey: Hashable {
    let id: String
    let text: String
    let flags: Int
    let status: String
    let minute: Int
    let scale: CGFloat
    let language: String
    let zone: String
    /// The reaction chips' room on the last line (half points) and its glyph's size.
    var reserve = 0
    var holdPoint: CGFloat = 0
}

/// Bounded memo of composed bubble text (see MessageBubble.lensText).
private final class ChatLensTextCache: @unchecked Sendable {
    static let shared = ChatLensTextCache()
    private let lock = NSLock()
    private var values: [ChatLensTextKey: AttributedString] = [:]
    func value(_ key: ChatLensTextKey) -> AttributedString? { lock.lock(); defer { lock.unlock() }; return values[key] }
    func store(_ value: AttributedString, _ key: ChatLensTextKey) {
        lock.lock(); defer { lock.unlock() }
        if values.count >= 800 { values.removeAll(keepingCapacity: true) }
        values[key] = value
    }
}

private func chatRichText(_ raw: String) -> AttributedString {
    ChatRichTextCache.shared.richText(for: raw)
}

private struct ChatMediaWidthKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatMediaHeightKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatAlbumCellKey: EnvironmentKey { static let defaultValue = false }
private struct ChatBubbleTailedKey: EnvironmentKey { static let defaultValue = true }
private struct ChatAlbumWidthKey: EnvironmentKey { static let defaultValue: CGFloat? = nil }
private struct ChatViewportWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 360 }
/// Non-observing RealtimeClient reference for ChatScreen (injected next to the
/// environment object). ChatScreen must not re-render on presence/typing events.
private struct ChatRealtimeClientKey: EnvironmentKey { static var defaultValue: RealtimeClient? { nil } }
extension EnvironmentValues {
    var chatRealtimeClient: RealtimeClient? {
        get { self[ChatRealtimeClientKey.self] }
        set { self[ChatRealtimeClientKey.self] = newValue }
    }
}

/// True when the chat was opened by the row zoom: the header avatar then lands from
/// the row's scale. Set by the chat list for that one push only.
private struct UnyriChatArrivalKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var unyriChatArrival: Bool {
        get { self[UnyriChatArrivalKey.self] }
        set { self[UnyriChatArrivalKey.self] = newValue }
    }
}

private struct ChatChatsRepositoryKey: EnvironmentKey { static var defaultValue: ChatsRepository? { nil } }
extension EnvironmentValues {
    /// Non-observing ChatsRepository reference for ChatScreen; see its declaration.
    var chatChatsRepository: ChatsRepository? {
        get { self[ChatChatsRepositoryKey.self] }
        set { self[ChatChatsRepositoryKey.self] = newValue }
    }
}

/// This chat's last seen preference-cache entry. Returns true only for a real
/// change after the first emission (which the body already read).
private final class ChatPreferenceSeen {
    private var started = false
    private var value: ChatSummary?
    func update(_ next: ChatSummary?) -> Bool {
        defer { started = true; value = next }
        return started && value != next
    }
}

/// Last connection state ChatScreen has seen; a box so recording it never re-renders.
private final class ChatRealtimeStateSeen {
    var value: RealtimeClient.State?
}

/// The chat header. It owns everything that changes on realtime events (presence,
/// typing, connection, call island), so those events re-render only this bar.
/// Messages scroll beneath it: three small glass islands (back, identity, actions)
/// over a soft gradient instead of one full-width blur, which keeps the per-frame
/// blur area small while the list scrolls.
private struct ChatHeaderBar: View {
    @EnvironmentObject private var realtime: RealtimeClient
    @Environment(\.callIsland) private var callIsland
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    // Same initial-letter avatar as the chat list: grey in the monochrome theme.
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriChatArrival) private var arrival
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    @State private var activityNow = Date()
    /// The zoom-open avatar has settled into the header (one-shot).
    @State private var landed = false

    let chatId: String
    let title: String
    let isSaved: Bool
    /// The routed peer (presence identity); display data comes from `title`/`avatarURL`.
    let presencePeer: PublicUser?
    let avatarURL: URL?
    let blocked: Bool
    let allowsPeerActivity: Bool
    let connectivityError: Bool
    let onBack: () -> Void
    let onCall: (() -> Void)?
    let onMenu: () -> Void
    /// Like Telegram: tapping the name/avatar opens the peer's profile.
    var onProfile: (() -> Void)? = nil
    var legacyIdentityServer = false

    private var solidChrome: Bool { reduceTransparency || lowPower || contrast == .increased }
    private var connected: Bool { realtime.state == .connected }
    private var peerOnline: Bool {
        guard !blocked, scenePhase == .active, let peer = presencePeer else { return false }
        return realtime.presenceObservation(for: peer.id)?.item.online == true
    }
    /// Unknown while offline, blocked or in the background, so a reconnect never ripples.
    private var presenceState: UnyriPresenceState {
        guard connected, !blocked, scenePhase == .active, let peer = presencePeer else { return .unknown }
        return UnyriPresenceState(realtime.presenceObservation(for: peer.id))
    }
    private var peerPresenceLabel: String {
        guard let peer = presencePeer else { return "" }
        let identity = peer.peerIdentityLabel(legacyServer: legacyIdentityServer)
        guard !blocked, scenePhase == .active else { return identity }
        return realtime.presenceLabel(for: peer.id) ?? identity
    }
    private var statusLine: String {
        connected && !connectivityError
            ? (peerActivityText.isEmpty ? peerPresenceLabel : UnyriL10n.string(peerActivityText))
            : UnyriL10n.string(realtime.state == .connecting ? "Connecting…" : "Waiting for network…")
    }
    private var peerActivityText: String {
        guard allowsPeerActivity, !blocked, scenePhase == .active, let item = realtime.activity[chatId], activityNow.timeIntervalSince(item.at) < 8 else { return "" }
        return item.kind == "typing" ? UnyriL10n.string("Typing…") : item.kind == "recording" ? UnyriL10n.string("Recording a voice message…") : item.kind == "uploading" ? UnyriL10n.string("Sending attachment…") : ""
    }

    var body: some View {
        HStack(spacing: 8) {
            // The app-wide Back stays outside the header's glass group: inside the
            // container its glass merged with the islands and the arrow looked dim.
            UnyriBackButton(action: onBack).accessibilityIdentifier("chat-navigation-back")
            HStack(spacing: 8) {
            identity
                .padding(.leading, 4).padding(.trailing, 14)
                .frame(minHeight: 44, alignment: .leading)
                .modifier(ChatHeaderGlass(solid: solidChrome))
                .layoutPriority(1)
                .contentShape(Capsule())
                .onTapGesture { onProfile?() }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(onProfile == nil ? .isHeader : [.isHeader, .isButton])
                .accessibilityHint(onProfile == nil ? "" : UnyriL10n.string("Open profile"))
                .accessibilityIdentifier("chat-header-identity")
            Spacer(minLength: 0)
            actions
                .modifier(ChatHeaderGlass(solid: solidChrome))
            }
            .modifier(ChatHeaderGlassGroup())
            .foregroundColor(UnyriColors.accentSoft)
        }
        .padding(.horizontal, 12).frame(minHeight: UnyriSavedChatStyle.headerHeight)
        .background { chromeBackground }
        .task(id: realtime.activity[chatId]?.at) {
            activityNow = Date()
            guard let activity = realtime.activity[chatId] else { return }
            let remaining = max(0, min(8, 8 - Date().timeIntervalSince(activity.at)))
            guard remaining > 0 else { return }
            do { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) } catch { return }
            activityNow = Date()
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
            let next = ProcessInfo.processInfo.isLowPowerModeEnabled
            if lowPower != next { lowPower = next }
        }
        .onAppear {
            // Settles once per push; an avatar that appears later (after a call island) stays still.
            guard !landed else { return }
            guard arrival, !isSaved, callIsland == nil else { landed = true; return }
            withAnimation(.spring(response: 0.46, dampingFraction: 0.82).delay(0.05)) { landed = true }
        }
    }

    @ViewBuilder private var identity: some View {
        HStack(spacing: 8) {
            if let callIsland {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.caption).foregroundColor(UnyriColors.primaryText).lineLimit(1)
                    CallIslandTitle(presentation: callIsland)
                }.padding(.leading, 10)
            } else {
                if isSaved {
                    Image(systemName: "bookmark.fill").frame(width: UnyriSavedChatStyle.avatarSize, height: UnyriSavedChatStyle.avatarSize)
                } else {
                    UserAvatar(url: avatarURL, name: title, size: UnyriSavedChatStyle.avatarSize, vividFallback: !colorStyle.isMonochrome).overlay(alignment: .bottomTrailing) {
                        UnyriPresenceDot(state: presenceState, diameter: 8, color: Color(red: 0.26, green: 0.79, blue: 0.58), cutout: UnyriColors.chatPanel)
                    }
                    // Zoom open: the row's avatar lands in the header.
                    .scaleEffect(arrival && !landed ? 1.3 : 1)
                    .opacity(arrival && !landed ? 0.5 : 1)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.headline).foregroundColor(UnyriColors.primaryText).lineLimit(1)
                    // Saved Messages has no peer status; an empty line pushed the title up.
                    if !(statusLine.isEmpty) {
                    UnyriStatusText(text: statusLine)
                        .foregroundColor(peerOnline && connected && peerActivityText.isEmpty && !connectivityError ? ChatControlPalette.mint : UnyriColors.secondaryText)
                    }
                }
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 0) {
            if let onCall {
                Button(action: onCall) {
                    Image(systemName: "phone").font(.system(size: UnyriSavedChatStyle.iconSize)).foregroundStyle(UnyriSavedChatStyle.topInk)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.accessibilityLabel("Call")
            }
            Button(action: onMenu) {
                Image(systemName: "gearshape").font(.system(size: UnyriSavedChatStyle.iconSize)).foregroundStyle(UnyriSavedChatStyle.topInk)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }.accessibilityLabel("Chat options").accessibilityIdentifier("chat-options")
        }.padding(.horizontal, 4)
    }

    /// Solid bar when transparency is unwanted (Reduce Transparency, Increase
    /// Contrast, Low Power); otherwise a cheap gradient that keeps the status bar
    /// and island edges legible over scrolled messages without a full-width blur.
    @ViewBuilder private var chromeBackground: some View {
        if solidChrome {
            UnyriSavedChatStyle.topColor.ignoresSafeArea(edges: .top)
        } else {
            LinearGradient(stops: [
                .init(color: Color.black.opacity(0.82), location: 0),
                .init(color: Color.black.opacity(0.5), location: 0.65),
                .init(color: Color.black.opacity(0), location: 1),
            ], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
        }
    }
}

extension View {
    /// The chat's top bar. iOS 26: `safeAreaBar` keeps the same layout as
    /// `safeAreaInset` but lets the system blur messages scrolling beneath the
    /// glass header (scroll edge effect), so names stay legible over content.
    @ViewBuilder func chatTopBar<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        if #available(iOS 26.0, *) {
            safeAreaBar(edge: .top, spacing: 0, content: bar)
        } else {
            safeAreaInset(edge: .top, spacing: 0, content: bar)
        }
    }
}

/// Round glass for the jump-to-latest button (iOS 26 Liquid Glass; material before).
private struct ChatJumpGlass: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: Circle())
        } else {
            content
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.12), lineWidth: 0.5))
                .shadow(color: .black.opacity(0.25), radius: 8, y: 2)
        }
    }
}

private struct ChatHeaderGlass: ViewModifier {
    let solid: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if solid {
            content
                .background(UnyriColors.deepPanel.opacity(0.92), in: Capsule())
                .overlay(Capsule().stroke(UnyriColors.primaryText.opacity(0.08), lineWidth: 0.5))
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: Capsule())
        } else {
            content
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.10), lineWidth: 0.5))
        }
    }
}

/// iOS 26: render the header islands in one glass pass. The spacing is below the
/// HStack spacing so the islands never morph into one another.
private struct ChatHeaderGlassGroup: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 4) { content }
        } else {
            content
        }
    }
}

private struct ChatVoicePlayerKey: EnvironmentKey { static var defaultValue: VoicePlayerController? { nil } }
private struct ChatVoiceRecorderKey: EnvironmentKey { static var defaultValue: VoiceRecorder? { nil } }
private struct ChatMediaItemsKey: EnvironmentKey { static var defaultValue: (() -> [UnyriMediaItem])? { nil } }
private extension EnvironmentValues {
    var chatVoicePlayer: VoicePlayerController? {
        get { self[ChatVoicePlayerKey.self] }
        set { self[ChatVoicePlayerKey.self] = newValue }
    }
    var chatVoiceRecorder: VoiceRecorder? {
        get { self[ChatVoiceRecorderKey.self] }
        set { self[ChatVoiceRecorderKey.self] = newValue }
    }
    var chatMediaItems: (() -> [UnyriMediaItem])? {
        get { self[ChatMediaItemsKey.self] }
        set { self[ChatMediaItemsKey.self] = newValue }
    }
}
private extension EnvironmentValues {
    var chatBubbleTailed: Bool {
        get { self[ChatBubbleTailedKey.self] }
        set { self[ChatBubbleTailedKey.self] = newValue }
    }
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
    // Tall portrait media gets a Telegram-like minimum width (cropped to fill) instead
    // of a thin strip; the caption under it then has room too.
    let h = min(320, available / ratio)
    return CGSize(width: min(available, max(170, h * ratio)), height: h)
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
    /// The message's id when its receipt may animate a live status change; nil for previews.
    var liveID: String? = nil
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    private static let formatter: DateFormatter = { let value = DateFormatter(); value.dateFormat = "HH:mm"; return value }()
    var body: some View {
        HStack(spacing: UnyriSavedChatStyle.metaGap) {
            Text(Self.formatter.string(from: date)).fixedSize()
            if outgoing { UnyriMessageReceipt(status: status, size: UnyriSavedChatStyle.timeSize * scale, liveID: liveID) }
            if edited { Text("edited") }
        }
        .font(.system(size: UnyriSavedChatStyle.timeSize * scale))
        .foregroundStyle(foreground ?? UnyriSavedChatStyle.muted)
    }
}

/// When a receipt change may play the ink fill: only a forward change seen live. A
/// pending message is always live; otherwise the cell must have been on screen for a
/// moment, so the receipt sync right after opening a chat stays still.
enum UnyriReceiptInkPolicy {
    static func rank(_ status: MessageStatus) -> Int? {
        switch status {
        case .pending: return 0
        case .sent: return 1
        case .delivered: return 2
        case .read: return 3
        case .failed, .decryptFailed, .identityChanged: return nil
        }
    }
    /// Statuses drawn by UnyriMessageReceipt (the ink slot) instead of a glyph run.
    static func drawsSeparately(_ status: MessageStatus) -> Bool { rank(status) != nil }
    /// `watchedSending`: this view showed the message while it was sending, so every next
    /// step of that send (sent, then a quick delivered) is live even inside the mount gate.
    static func animates(from: MessageStatus, to: MessageStatus, mountedFor: TimeInterval, appActive: Bool, calm: Bool,
                         watchedSending: Bool = false) -> Bool {
        guard !calm, appActive, let old = rank(from), let new = rank(to), new > old else { return false }
        return from == .pending || watchedSending || mountedFor >= UnyriReceiptInk.mountGate
    }
    /// Live changes that land in one batch window are decided together: a few ink, a
    /// bigger burst ("read through", a reconnect flush) is a catch-up and lands still.
    static let maxBatch = 3
    static func batchAnimates(count: Int) -> Bool { count <= maxBatch }
}

private enum UnyriReceiptInk {
    static let mountGate: TimeInterval = 0.8
    static let batchWindow = 0.08
    static let clockDuration = 0.12
    static let clockOut = Animation.easeIn(duration: clockDuration)
    static let tickDuration = 0.22
    static let fullTickDuration = 0.3
    static let pendingDelay = 0.05
    static let readDuration = 0.32
    static let readDelay = 0.12
    static let feather: CGFloat = 1.5

    struct Metrics { let slot: CGFloat; let firstFrac: CGFloat }
    private static var cache: [CGFloat: Metrics] = [:]
    /// The "✓✓" slot width and the share of it the first tick covers, per font size.
    static func metrics(_ size: CGFloat) -> Metrics {
        if let cached = cache[size] { return cached }
        let font = UIFont.systemFont(ofSize: size)
        func width(_ value: String) -> CGFloat { (value as NSString).size(withAttributes: [.font: font]).width }
        let slot = max(1, width("✓✓"))
        let value = Metrics(slot: slot, firstFrac: min(1, width("✓") / slot))
        cache[size] = value
        return value
    }
    static func reveal(_ status: MessageStatus, firstFrac: CGFloat) -> CGFloat {
        switch status {
        case .sent: return firstFrac
        case .delivered, .read: return 1
        default: return 0
        }
    }
    /// The run's length: the last of the clock fade, the tick fill and the read wash.
    static func duration(from: MessageStatus, to: MessageStatus, firstFrac: CGFloat) -> TimeInterval {
        let target = reveal(to, firstFrac: firstFrac)
        let revealMoves = reveal(from, firstFrac: firstFrac) != target
        let tick = !revealMoves ? 0 : (from == .pending ? pendingDelay : 0)
            + (from == .pending && target >= 1 ? fullTickDuration : tickDuration)
        let read = (from == .read) == (to == .read) ? 0 : (revealMoves ? readDelay : 0) + readDuration
        let clock = (from == .pending) == (to == .pending) ? 0 : clockDuration
        return max(tick, read, clock)
    }
}

/// Live receipt changes from one network event reach the cells within a frame or two;
/// they are collected for a short window and decided as one batch. Main thread only.
@MainActor
private final class UnyriReceiptInkBatch {
    static let shared = UnyriReceiptInkBatch()
    private var waiting: [(Bool) -> Void] = []

    func request(_ start: @escaping (Bool) -> Void) {
        waiting.append(start)
        guard waiting.count == 1 else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + UnyriReceiptInk.batchWindow) { [self] in
            let batch = waiting
            waiting = []
            let animate = UnyriReceiptInkPolicy.batchAnimates(count: batch.count)
            batch.forEach { $0(animate) }
        }
    }
}

/// The delivery receipt in a fixed "✓✓" slot, so its width never changes between
/// statuses. At rest it is one glyph in its final colour; only a live forward change
/// mounts the ink run (ticks fill left to right, the read colour washes over them) for
/// that change's length. A rebind, a scroll-back or a reload just shows the state.
private struct UnyriMessageReceipt: View {
    let status: MessageStatus
    var size: CGFloat = UnyriSavedChatStyle.timeSize
    var liveID: String? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The last status seen: where the next change starts for the live decision.
    @State private var shown: MessageStatus
    @State private var owner: String?
    @State private var bornAt = CACurrentMediaTime()
    /// This view showed the message while it was sending (see `watchedSending`).
    @State private var watchedSending: Bool
    /// The drawn state; it trails `status` only while a live change waits for its batch.
    @State private var settled: MessageStatus
    @State private var run: UnyriReceiptInkRun.Spec?
    @State private var runToken = 0

    init(status: MessageStatus, size: CGFloat = UnyriSavedChatStyle.timeSize, liveID: String? = nil) {
        self.status = status
        self.size = size
        self.liveID = liveID
        _shown = State(initialValue: status)
        _owner = State(initialValue: liveID)
        _settled = State(initialValue: status)
        _watchedSending = State(initialValue: status == .pending)
    }

    var body: some View {
        Group {
            switch status {
            case .failed:
                Image(systemName: "exclamationmark.circle").foregroundStyle(UnyriSavedChatStyle.muted)
                    .accessibilityLabel("Failed to send")
            case .decryptFailed, .identityChanged:
                EmptyView()
            case .pending, .sent, .delivered, .read:
                Group {
                    if let run { UnyriReceiptInkRun(spec: run, size: size) }
                    else { UnyriReceiptGlyph(status: settled) }
                }
                .accessibilityElement(children: .ignore).accessibilityLabel(Text(LocalizedStringKey(label)))
            }
        }
        .font(.system(size: size))
        .fixedSize()
        .onChange(of: status) { next in advance(to: next) }
    }

    private var label: String {
        switch status {
        case .pending: return "Sending…"
        case .sent: return "Sent"
        case .delivered: return "Delivered"
        default: return "Read"
        }
    }

    private func advance(to next: MessageStatus) {
        let from = shown
        shown = next
        // A view handed to another message is a rebind, never a live change.
        let sameMessage = owner == liveID
        if !sameMessage { owner = liveID; bornAt = CACurrentMediaTime(); watchedSending = false }
        if sameMessage && from == .pending { watchedSending = true }
        let calm = reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled
        let live = liveID != nil && sameMessage && UnyriReceiptInkPolicy.animates(from: from, to: next,
            mountedFor: CACurrentMediaTime() - bornAt, appActive: UIApplication.shared.applicationState == .active, calm: calm,
            watchedSending: watchedSending)
        runToken += 1
        let token = runToken
        guard live else { settle(next); return }
        UnyriReceiptInkBatch.shared.request { animate in
            guard token == runToken else { return }   // a newer change took over
            // Ink from what is drawn now; a burst, or anything not forward, just lands.
            let start = settled
            guard animate, let old = UnyriReceiptInkPolicy.rank(start), let new = UnyriReceiptInkPolicy.rank(next), new > old else {
                settle(next); return
            }
            settled = next
            // A run still on screen is retargeted in place (it carries on from its current
            // frame); otherwise a new one mounts from the drawn state.
            run = UnyriReceiptInkRun.Spec(from: start, to: next, token: token)
            let length = UnyriReceiptInk.duration(from: start, to: next, firstFrac: UnyriReceiptInk.metrics(size).firstFrac)
            // The run's last frame is the settled glyph, so handing back is invisible.
            DispatchQueue.main.asyncAfter(deadline: .now() + length + 0.05) {
                if runToken == token { run = nil }
            }
        }
    }

    /// Shows a state at once and ends any run.
    private func settle(_ next: MessageStatus) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { settled = next; run = nil }
    }
}

/// One live receipt change, mounted only for its length; a next live step that lands while
/// it is on screen retargets it from its current frame. A clear "✓✓" holds the slot and
/// the baseline; the clock and two masked tick layers are drawn over it. The ink front
/// slides by offset (a geometry effect), so its frames need no layout pass.
private struct UnyriReceiptInkRun: View {
    struct Spec: Equatable { let from: MessageStatus; let to: MessageStatus; let token: Int }
    let spec: Spec
    let size: CGFloat
    @State private var tickReveal: CGFloat
    @State private var readInk: CGFloat
    @State private var clockOn: Bool

    init(spec: Spec, size: CGFloat) {
        self.spec = spec
        self.size = size
        _tickReveal = State(initialValue: UnyriReceiptInk.reveal(spec.from, firstFrac: UnyriReceiptInk.metrics(size).firstFrac))
        _readInk = State(initialValue: spec.from == .read ? 1 : 0)
        _clockOn = State(initialValue: spec.from == .pending)
    }

    var body: some View {
        let slot = UnyriReceiptInk.metrics(size).slot
        Text(verbatim: "✓✓").foregroundStyle(Color.clear)
            .overlay(alignment: .leading) {
                Image(systemName: "clock").foregroundStyle(UnyriSavedChatStyle.muted)
                    .opacity(clockOn ? 1 : 0).scaleEffect(clockOn ? 1 : 0.6)
            }
            .overlay(alignment: .leading) {
                Text(verbatim: "✓✓").foregroundStyle(UnyriSavedChatStyle.ticks)
                    .mask(alignment: .leading) { wipe(slot: slot, reveal: tickReveal) }
            }
            .overlay(alignment: .leading) {
                Text(verbatim: "✓✓").foregroundStyle(UnyriSavedChatStyle.readTicks)
                    .mask(alignment: .leading) { wipe(slot: slot, reveal: readInk) }
            }
            .onAppear(perform: play)
            .onChange(of: spec) { _ in play() }
    }

    /// A fixed leading mask with a soft 1.5 pt edge, slid in from the leading side: the
    /// ink front ends 1 pt past `slot * reveal`; 0 hides it.
    private func wipe(slot: CGFloat, reveal: CGFloat) -> some View {
        let width = slot + 1
        return HStack(spacing: 0) {
            Rectangle().frame(width: width - UnyriReceiptInk.feather)
            LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: UnyriReceiptInk.feather)
        }
        .offset(x: reveal > 0 ? -slot * (1 - reveal) : -width)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func play() {
        let from = spec.from, next = spec.to
        let targetReveal = UnyriReceiptInk.reveal(next, firstFrac: UnyriReceiptInk.metrics(size).firstFrac)
        let targetRead: CGFloat = next == .read ? 1 : 0
        if clockOn != (next == .pending) { withAnimation(UnyriReceiptInk.clockOut) { clockOn = next == .pending } }
        let revealMoves = tickReveal != targetReveal
        if revealMoves {
            let full = from == .pending && targetReveal >= 1
            withAnimation(.timingCurve(0.3, 0, 0.2, 1, duration: full ? UnyriReceiptInk.fullTickDuration : UnyriReceiptInk.tickDuration)
                .delay(from == .pending ? UnyriReceiptInk.pendingDelay : 0)) { tickReveal = targetReveal }
        }
        if readInk != targetRead {
            withAnimation(.easeInOut(duration: UnyriReceiptInk.readDuration).delay(revealMoves ? UnyriReceiptInk.readDelay : 0)) { readInk = targetRead }
        }
    }
}

/// The settled receipt over the same "✓✓" slot the ink run uses: the clock, or the ticks
/// as one Text in their final colours (the run's last frame), so the swap is invisible.
private struct UnyriReceiptGlyph: View {
    let status: MessageStatus
    var body: some View {
        if status == .pending {
            Text(verbatim: "✓✓").foregroundStyle(Color.clear)
                .overlay(alignment: .leading) { Image(systemName: "clock").foregroundStyle(UnyriSavedChatStyle.muted) }
        } else {
            Text(Self.ticks[status] ?? Self.blank)
        }
    }

    private static let blank: AttributedString = {
        var value = AttributedString("✓✓")
        value.foregroundColor = Color.clear
        return value
    }()
    private static let ticks: [MessageStatus: AttributedString] = {
        var map: [MessageStatus: AttributedString] = [:]
        for status in [MessageStatus.sent, .delivered, .read] {
            let ink = status == .read ? UnyriSavedChatStyle.readTicks : UnyriSavedChatStyle.ticks
            var first = AttributedString("✓")
            var second = AttributedString("✓")
            first.foregroundColor = ink
            // "Sent" keeps the second tick's room clear, as the run leaves it.
            second.foregroundColor = status == .sent ? Color.clear : ink
            first.append(second)
            map[status] = first
        }
        return map
    }()
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

/// One compact row (Telegram-like, our own): play button, waveform, and under it the
/// duration with the time and ticks on the same line. The speed chip appears only
/// while this message is playing or paused (it did nothing otherwise).
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
    /// Time + ticks shown inline at the end of the duration line.
    var metadata: AnyView? = nil
    /// Incoming and never played: a small dot after the duration.
    var unplayed = false
    /// "To text" on this device; nil for queued rows and fixtures.
    var transcript: UnyriVoiceTranscriptSource? = nil
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            row
            if let transcript { UnyriVoiceTranscriptText(entry: transcript.entry, path: transcript.path, outgoing: transcript.outgoing) }
        }
    }
    private var row: some View {
        HStack(alignment: .center, spacing: 9) {
            if let transferControl { transferControl }
            else { Button { if UnyriMessageMenuPresenter.canActivateMessage { action() } } label: {
                Group {
                    if downloading { ProgressView().tint(UnyriSavedChatStyle.buttonInk) }
                    else {
                        Image(systemName: available ? playing ? "pause.fill" : "play.fill" : "arrow.down")
                            .font(.system(size: 18, weight: .semibold))
                            .offset(x: available && !playing ? 1.5 : 0)
                    }
                }.foregroundStyle(UnyriSavedChatStyle.buttonInk)
                    .frame(width: UnyriSavedChatStyle.playSize, height: UnyriSavedChatStyle.playSize)
                    .background(UnyriSavedChatStyle.accent, in: Circle())
                    .contentShape(Rectangle().inset(by: -6))
            }.buttonStyle(.plain).accessibilityLabel(playing ? "Pause voice message" : available ? "Play voice message" : "Download voice message")
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .center, spacing: 6) {
                    waveform.frame(height: UnyriSavedChatStyle.waveHeight)
                    if let transcript { UnyriVoiceTranscriptButton(entry: transcript.entry, path: transcript.path) }
                }
                HStack(alignment: .center, spacing: 5) {
                    Text(VoiceFormat.durationText(active ? Int64(position) : duration)).monospacedDigit().lineLimit(1)
                    if unplayed && !active {
                        Circle().fill(UnyriSavedChatStyle.accent).frame(width: 5, height: 5)
                            .accessibilityLabel(Text(UnyriL10n.string("Not listened")))
                    }
                    if active {
                        Button(action: cycleSpeed) {
                            Text(speed == 1 ? "1×" : speed == 1.5 ? "1,5×" : "2×")
                                .font(.system(size: (UnyriSavedChatStyle.timeSize - 1) * scale, weight: .semibold))
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(UnyriSavedChatStyle.entry, in: Capsule())
                                .contentShape(Rectangle().inset(by: -8))
                        }.buttonStyle(.plain).accessibilityLabel("Playback speed")
                    }
                    Spacer(minLength: 4)
                    if let metadata { metadata.fixedSize() }
                }.font(.system(size: UnyriSavedChatStyle.timeSize * scale)).foregroundStyle(UnyriSavedChatStyle.muted)
                    .frame(minHeight: 16 * scale)
            }
        }
    }
}

/// "To text" of one voice bubble. `path` is nil until the audio is on this device.
private struct UnyriVoiceTranscriptSource {
    let entry: VoiceTranscriptEntry
    let path: String?
    let outgoing: Bool
}

/// The "to text" chip at the end of the waveform. It observes only this message's
/// transcript entry, so recognising one voice message re-renders no other bubble.
private struct UnyriVoiceTranscriptButton: View {
    @ObservedObject var entry: VoiceTranscriptEntry
    let path: String?
    var body: some View {
        // Offered only while the audio is on this device; it stays to hide an open transcript.
        if path != nil || entry.shown {
            Button {
                guard UnyriMessageMenuPresenter.canActivateMessage else { return }
                UnyriHaptics.selection()
                VoiceTranscriber.shared.toggle(entry, fileURL: path.map { URL(fileURLWithPath: $0) })
            } label: {
                Group {
                    if entry.isWorking { ProgressView().controlSize(.small).scaleEffect(0.8).tint(UnyriSavedChatStyle.muted) }
                    else { Image(systemName: "text.bubble").font(.system(size: 12, weight: .semibold)) }
                }
                .foregroundStyle(entry.shown ? UnyriSavedChatStyle.accent : UnyriSavedChatStyle.muted)
                .frame(width: 30, height: UnyriSavedChatStyle.waveHeight)
                .background(UnyriSavedChatStyle.entry, in: Capsule())
                // 44 pt target around the 30x20 chip; it grows away from the waveform (seek taps).
                .contentShape(Rectangle().size(width: 44, height: 44).offset(x: 0, y: (UnyriSavedChatStyle.waveHeight - 44) / 2))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(UnyriL10n.string(entry.isWorking ? "Cancel" : entry.shown ? "Hide text" : "Show as text")))
            .accessibilityIdentifier("voice-transcribe")
        }
    }
}

/// The transcript, or one short error line, under the waveform inside the same bubble.
private struct UnyriVoiceTranscriptText: View {
    @ObservedObject var entry: VoiceTranscriptEntry
    let path: String?
    let outgoing: Bool
    @Environment(\.unyriChatAppearance) private var appearance
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    var body: some View {
        if entry.shown {
            switch entry.phase {
            case .done(let text):
                // Copied from the message menu ("Copy text"): selectable text would
                // compete with the bubble's own long press.
                Text(text)
                    .font(appearance.messageFont(scale: scale)).lineSpacing(appearance.extraLineSpacing * scale)
                    .foregroundColor(outgoing ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 3).padding(.top, 6).padding(.bottom, 2)
                    .accessibilityIdentifier("voice-transcript")
            case .failed(let error, let alternatives):
                VStack(alignment: .leading, spacing: 0) {
                    Text(UnyriL10n.string(error.messageKey))
                        .font(.system(size: 12 * scale)).foregroundStyle(UnyriSavedChatStyle.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("voice-transcript-error")
                    if error == .languageUnavailable, !alternatives.isEmpty, let path {
                        Menu {
                            ForEach(alternatives, id: \.self) { language in
                                Button(VoiceTranscriber.languageName(language)) {
                                    VoiceTranscriber.shared.transcribe(entry, fileURL: URL(fileURLWithPath: path), language: language)
                                }
                            }
                        } label: { action("Try another language", symbol: "globe") }
                            .accessibilityIdentifier("voice-transcript-language")
                    } else if error == .notAuthorized {
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        } label: { action("Open app settings", symbol: "gear") }.buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 3).padding(.top, 6)
            case .idle, .working:
                EmptyView()
            }
        }
    }
    private func action(_ key: String, symbol: String) -> some View {
        Label(UnyriL10n.string(key), systemImage: symbol)
            .font(.system(size: 12 * scale, weight: .semibold)).foregroundStyle(UnyriSavedChatStyle.accent)
            .frame(minHeight: 44).contentShape(Rectangle())
    }
}

/// Voice messages this phone has played (attachment IDs, newest last). Local only.
@MainActor enum UnyriVoicePlayedStore {
    private static let key = "voice-played-v1"
    // In-memory set: rows ask on every playback-progress render.
    private static var ids: [String] = UserDefaults.standard.stringArray(forKey: key) ?? []
    private static var lookup = Set(ids)
    static func isPlayed(_ id: String) -> Bool { lookup.contains(id) }
    static func markPlayed(_ id: String) {
        guard lookup.insert(id).inserted else { return }
        ids.append(id)
        if ids.count > 3000 { let dropped = ids.prefix(ids.count - 3000); ids.removeFirst(dropped.count); lookup.subtract(dropped) }
        UserDefaults.standard.set(ids, forKey: key)
    }
    static func eraseAll() { ids = []; lookup = []; UserDefaults.standard.removeObject(forKey: key) }
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
            icon.frame(width: 40, height: 40)
            Group {
                if let action { UnyriMessageActionButton(action: action) { labels }.buttonStyle(.plain) }
                else { labels }
            }
        }.foregroundStyle(UnyriSavedChatStyle.text).frame(maxWidth: .infinity, minHeight: 44)
    }
    private var labels: some View {
            VStack(alignment: .leading, spacing: 1) {
                Text(chatPresentationText(filename, inline: true)).font(.system(size: 15 * scale, weight: .semibold))
                    .lineLimit(1).truncationMode(.middle)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(details).lineLimit(1).truncationMode(.tail)
                    if let metadata { Spacer(minLength: 0); metadata.fixedSize() }
                }.font(.system(size: 12 * scale)).foregroundStyle(UnyriSavedChatStyle.muted)
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

/// Day header above the first timeline row of each calendar day.
private struct ChatDateSeparator: View {
    let date: Date
    var body: some View {
        Text(Self.label(for: date))
            .font(.caption.weight(.semibold)).foregroundStyle(UnyriSavedChatStyle.muted)
            .padding(.horizontal, 10).padding(.vertical, 4)
            // Black 45% was invisible on the black background, so the label floated.
            .background(Color.white.opacity(0.10), in: Capsule())
            .frame(maxWidth: .infinity)
            .padding(.top, 10).padding(.bottom, 4)
            .allowsHitTesting(false)
            .accessibilityAddTraits(.isHeader)
    }
    private static var formatters: [String: DateFormatter] = [:]
    static func label(for date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return UnyriL10n.string("Today") }
        if calendar.isDateInYesterday(date) { return UnyriL10n.string("Yesterday") }
        let sameYear = calendar.isDate(date, equalTo: Date(), toGranularity: .year)
        let language = UnyriL10n.language
        let key = language + (sameYear ? "|md" : "|ymd")
        let formatter: DateFormatter
        if let cached = formatters[key] { formatter = cached } else {
            formatter = DateFormatter()
            formatter.locale = Locale(identifier: language)
            formatter.setLocalizedDateFormatFromTemplate(sameYear ? "dMMMM" : "dMMMMy")
            formatters[key] = formatter
        }
        return formatter.string(from: date)
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

@MainActor private final class ChatForwardProgress {
    var delivered = Set<String>()
    var commented = Set<String>()
}

private struct AttachmentAutoDownloadKey: Hashable {
    let id: String
    let pending: Bool
    let allowed: Bool
    let connected: Bool
    let wifi: Bool
    let automatic: Bool
}

/// Environment closures created once per chat screen. Each body pass only swaps
/// the targets they forward to, so SwiftUI sees unchanged environment values.
@MainActor
private final class ChatEnvironmentActions {
    private weak var repository: MessagesRepository?
    private var forwardTarget: ((String) -> Void)?
    lazy var pendingLookup: (String, String) -> QueuedMediaPreview? = { [weak self] chat, id in
        MainActor.assumeIsolated { self?.repository?.queuedMediaPreview(chatId: chat, id: id) }
    }
    lazy var cancelDownload: (String) -> Void = { [weak self] id in
        MainActor.assumeIsolated { self?.repository?.cancelDownloadAttachment(id) }
    }
    private lazy var forward: (String) -> Void = { [weak self] id in
        MainActor.assumeIsolated { self?.forwardTarget?(id) }
    }
    private var editedPhotoTarget: ((UIImage) -> Void)?
    private lazy var editedPhoto: (UIImage) -> Void = { [weak self] image in
        MainActor.assumeIsolated { self?.editedPhotoTarget?(image) }
    }
    func bind(_ repository: MessagesRepository) -> Self { self.repository = repository; return self }
    func forwardMedia(perform: @escaping (String) -> Void) -> ((String) -> Void)? { forwardTarget = perform; return forward }
    func sendEditedPhoto(perform: @escaping (UIImage) -> Void) -> ((UIImage) -> Void)? { editedPhotoTarget = perform; return editedPhoto }
    private var mediaItemsTarget: (() -> [UnyriMediaItem])?
    private lazy var mediaItemsProvider: () -> [UnyriMediaItem] = { [weak self] in
        MainActor.assumeIsolated { self?.mediaItemsTarget?() ?? [] }
    }
    /// The viewer items are built when a viewer opens, from the latest screen.
    func mediaItems(build: @escaping () -> [UnyriMediaItem]) -> (() -> [UnyriMediaItem])? { mediaItemsTarget = build; return mediaItemsProvider }
}


/// iOS 18+: the voice hold is a UIKit long press on the composer. SwiftUI's
/// LongPress→Drag sequence did not start on a perfectly still hold and never
/// reported a system cancellation (leaving a recording with no controls).
private struct ChatVoiceHoldHost: ViewModifier {
    let began: (CGPoint) -> Void
    let changed: (CGSize) -> Void
    let ended: () -> Void
    let cancelled: () -> Void
    let legacy: AnyGesture<Void>
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.gesture(ChatVoiceHoldRecognizer(began: began, changed: changed, ended: ended, cancelled: cancelled))
        } else {
            content.simultaneousGesture(legacy)
        }
    }
}

@available(iOS 18.0, *)
private struct ChatVoiceHoldRecognizer: UIGestureRecognizerRepresentable {
    let began: (CGPoint) -> Void
    let changed: (CGSize) -> Void
    let ended: () -> Void
    let cancelled: () -> Void
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var start: CGPoint = .zero
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }
    func makeUIGestureRecognizer(context: Context) -> UILongPressGestureRecognizer {
        let press = UILongPressGestureRecognizer()
        press.minimumPressDuration = 0.15
        press.allowableMovement = .greatestFiniteMagnitude
        press.cancelsTouchesInView = false
        press.delaysTouchesBegan = false
        press.delegate = context.coordinator
        return press
    }
    func handleUIGestureRecognizerAction(_ recognizer: UILongPressGestureRecognizer, context: Context) {
        // Translation in window space: the composer swaps to the taller recording bar
        // right after the hold starts, which shifted local coordinates by ~8 pt and
        // read as an upward slide (slide-to-cancel then did nothing).
        let location = context.converter.location(in: .global)
        switch recognizer.state {
        case .began:
            context.coordinator.start = location
            began(context.converter.localLocation)
        case .changed:
            let start = context.coordinator.start
            changed(CGSize(width: location.x - start.x, height: location.y - start.y))
        case .ended: ended()
        case .cancelled, .failed: cancelled()
        default: break
        }
    }
}

/// Pressed highlight for menu rows (no system blue flash).
private struct UnyriMenuRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.background(Color.white.opacity(configuration.isPressed ? 0.08 : 0))
    }
}

/// Highlight for a message reached from search or a reply: radial glow behind the
/// bubble's side plus a single spring bounce. Two cheap layers, only while active.
private struct UnyriFoundMessageGlow: ViewModifier {
    let active: Bool
    let trailing: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        // Just the bubble growing and settling: the soft glow read as a white
        // rectangle behind it on device (report 0.4.4).
        content
            .scaleEffect(active && !reduceMotion ? 1.07 : 1, anchor: trailing ? .trailing : .leading)
            .animation(reduceMotion ? .easeOut(duration: 0.3) : .spring(response: 0.32, dampingFraction: 0.55), value: active)
    }
}


/// Scroll-position flags read by the chat's logic without re-rendering the chat.
@MainActor final class ChatBottomState: ObservableObject {
    var nearBottom = true { willSet { if newValue != nearBottom { objectWillChange.send() } } }
    var newCount = 0 { willSet { if newValue != newCount { objectWillChange.send() } } }
}

/// Entry timestamp for the chat_open metric. A plain reference on purpose: as @State,
/// setting and clearing it cost two extra whole-chat render passes on every open.
private final class ChatOpenClock { var startedAt: TimeInterval? }

/// The jump-to-latest button: the only view that redraws when the flags change.
private struct ChatJumpOverlay: View {
    @ObservedObject var state: ChatBottomState
    let hasMessages: Bool
    let positioned: Bool
    let resizing: Bool
    let pendingPosition: Bool
    let action: () -> Void
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if ChatViewportPolicy.showsJump(hasMessages: hasMessages, positioned: positioned,
                nearBottom: state.nearBottom, resizing: resizing, pendingPosition: pendingPosition) {
                NewMessagesPill(count: state.newCount, onTap: action)
                    .padding(.trailing, 16)
                    .padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: state.nearBottom)
    }
}


/// "Delete 8 messages?" with proper Russian/Ukrainian plural forms.
enum UnyriDeletionText {
    static func title(count: Int) -> String {
        if count <= 1 { return UnyriL10n.string("Delete message?") }
        let language = UnyriL10n.language
        if language.hasPrefix("ru") || language.hasPrefix("uk") {
            let tens = count % 100, ones = count % 10
            let form: String
            if tens >= 11 && tens <= 14 { form = "many" }
            else if ones == 1 { form = "one" }
            else if ones >= 2 && ones <= 4 { form = "few" }
            else { form = "many" }
            let words: [String: [String: String]] = [
                "ru": ["one": "сообщение", "few": "сообщения", "many": "сообщений"],
                "uk": ["one": "повідомлення", "few": "повідомлення", "many": "повідомлень"]]
            let key = language.hasPrefix("ru") ? "ru" : "uk"
            let verb = key == "ru" ? "Удалить" : "Видалити"
            return "\(verb) \(count) \(words[key]![form]!)?"
        }
        return "Delete \(count) messages?"
    }
}

// MARK: - Inverted UIKit timeline

/// The chat screen's side of the inverted UIKit timeline (ChatTimelineHost): rows, their
/// fingerprints and height estimates, and what the timeline reports back (read receipts,
/// reading position, older pages, the jump button).
extension ChatScreen {
    /// Newest-first row ids and each id's index in one presentation. A value: every
    /// closure handed to the timeline keeps the map of the presentation it captured
    /// (a shared reference let an older pass read new indices into its old rows, so
    /// rows briefly showed a neighbour's date separator after a history page arrived).
    private struct HostedRows {
        var ids: [String] = []
        var index: [String: Int] = [:]
    }
    /// Rebuilds `HostedRows` once per timeline revision.
    private final class HostedRowsCache {
        var revision: TimelineRevision?
        private(set) var current = HostedRows()
        func update(_ revision: TimelineRevision, items: [TimelineItem]) -> HostedRows {
            guard self.revision != revision else { return current }
            self.revision = revision
            var map: [String: Int] = [:]
            map.reserveCapacity(items.count)
            for (offset, item) in items.enumerated() where map[item.id] == nil { map[item.id] = offset }
            current = HostedRows(ids: items.reversed().map(\.id), index: map)
            return current
        }
    }

    /// Everything a hosted row shows. A row is reconfigured only when this changes;
    /// downloads, playback and previews update inside the row by themselves.
    private struct HostedRowPrint: Equatable {
        var layout: TimelineRowLayout
        var model: MessageRowModel?
        var hidden = false
        var highlighted = false
        var selecting = false
        var members: [DecryptedChatMessage] = []
        var memberReactions: [[ReactionSummary]] = []
        var selected: [String] = []
        var expanded = false
        var call: CallDTO?
        var earlierCall = false
    }

    /// Settings every row depends on.
    private struct HostedEnvironmentToken: Equatable {
        let appearance: UnyriChatAppearanceSettings
        let style: UnyriAppColorStyle
        let textScale: CGFloat
    }

    /// Everything a row fingerprint reads besides the scope and the row ids. Equal keys
    /// mean no row changed, so the timeline skips all per-row work for that pass.
    private struct HostedRowsKey: Equatable {
        let timeline: TimelineRevision
        let selection: Set<String>
        let highlighted: String?
        let pinned: Set<String>
        let quickReaction: String
        let expanded: Set<String>
        let reactions: UInt64
        let schedules: UInt64
    }

    private var hostedScope: ChatTimelineScope {
        ChatTimelineScope(generation: auth.sessionGeneration, owner: auth.currentUser?.id,
                          server: auth.authenticatedServerURL, chat: chat.chatId, nonce: timelineRootNonce)
    }

    @ViewBuilder
    var hostedMessageList: some View {
        let revision = timelineRevision
        let presentation = timelinePresentation
        let rows = hostedRows.update(revision, items: presentation.items)
        // One action snapshot per pass for every row (not one screen copy per row).
        let _ = bubbleActions.refresh(self)
        let hasMessages = presentation.hasMessages
        let empty = presentation.items.isEmpty
        Color.clear
            .overlay {
                if empty { hostedEmptyState }
            }
            .overlay(alignment: .bottomTrailing) {
                ChatJumpOverlay(state: bottomState, hasMessages: hasMessages, positioned: true,
                                resizing: false, pendingPosition: false) { hostedJumpTapped() }
            }
            .background {
                // Full size under the keyboard too: the timeline moves its list up by the
                // keyboard instead of shrinking (ChatTimelineHost), so showing or hiding the
                // keyboard builds and measures no rows.
                hostedTimelineView(presentation, rows: rows, revision: revision)
                    .ignoresSafeArea([.container, .keyboard], edges: [.top, .bottom])
            }
            .task(id: jumpRequest) { await hostedJump() }
            .onChange(of: presentation.arrivalRevision) { _ in hostedArrival() }
            // Unfreezing reports what is on screen again, so it is read once uncovered.
            .onChange(of: readable) { isReadable in hostedTimeline.setFrozen(!isReadable) }
            .onReceive(NotificationCenter.default.publisher(for: .unyriCallScreenVisibility)) { note in
                let covering = (note.object as? Bool) ?? false
                if callScreenCovering != covering { callScreenCovering = covering }
            }
            .onAppear {
                _ = bubbleActions.refresh(self)
                UnyriKeyboardMetrics.startObserving()
                _ = arrivalTracker.update(tail: presentation.arrivalRevision.tail, ids: presentation.arrivalIDs)
                hostedTimeline.setFrozen(!readable)
            }
            .onDisappear {
                bubbleActions.snapshot = nil
            }
    }

    @ViewBuilder
    private var hostedEmptyState: some View {
        if messagesRepository.isLoading && realtimeState == .connected {
            ProgressView()
        } else if !messagesRepository.isLoading {
            Text("No messages yet").font(.subheadline).foregroundColor(.secondary).padding()
        }
    }

    private func hostedTimelineView(_ presentation: TimelinePresentation, rows: HostedRows,
                                    revision: TimelineRevision) -> ChatTimelineHost {
        let context = timelineRowPassContext
        let rowsKey = HostedRowsKey(timeline: revision, selection: context.selection, highlighted: context.highlighted,
            pinned: context.pinned, quickReaction: context.quickReaction, expanded: expandedDirectAlbums,
            reactions: messagesRepository.reactionsRevision, schedules: messagesRepository.scheduledDatesRevision)
        return ChatTimelineHost(controller: hostedTimeline, scope: hostedScope, ids: rows.ids,
            initialPosition: { hostedInitialPosition(presentation, rows: rows) },
            estimatedHeight: { id, width in
                guard let index = rows.index[id], index < presentation.items.count else { return 64 }
                return hostedEstimate(presentation.items[index], layout: presentation.layouts[index], width: width)
            },
            content: { id, width in
                guard let index = rows.index[id], index < presentation.items.count else { return AnyView(EmptyView()) }
                return AnyView(hostedRow(presentation.items[index], layout: presentation.layouts[index], width: width, context: context))
            },
            fingerprint: { id in hostedFingerprint(id, presentation: presentation, rows: rows, context: context) },
            rowsRevision: ChatTimelineFingerprint(rowsKey),
            environmentToken: ChatTimelineFingerprint(HostedEnvironmentToken(appearance: appearance.settings, style: appColorStyle, textScale: composerTextScale)),
            onVisibility: { handleHostedVisibility($0) },
            // Enabled only while the composer has focus (setTapEndsEditing).
            onBackgroundTap: { [model = composerModel] in model.blur() },
            onFirstPresented: { recordOpenVisible(items: $0) },
            remembersHeight: { id in
                // Only rows whose look is fully in their fingerprint: no transfer cards (live
                // progress and phase), voice (transcripts), or messages still being sent.
                guard let index = rows.index[id], index < presentation.items.count else { return false }
                let item = presentation.items[index]
                if item.call != nil { return true }
                return !item.members.isEmpty && item.members.allSatisfy { member in
                    member.content.directFile == nil && member.status != .pending && member.status != .failed &&
                        member.content.attachment?.mediaType != AttachmentMetadata.mediaTypeVoice
                }
            },
            arrival: { id in
                guard let index = rows.index[id], index < presentation.items.count,
                      let message = presentation.items[index].message else { return .other }
                return message.senderId == auth.currentUser?.id ? .outgoing : .incoming
            })
    }

    /// Where the chat opens: the reading position kept from the last visit, else the end.
    private func hostedInitialPosition(_ presentation: TimelinePresentation, rows: HostedRows) -> ChatTimelineInitialPosition {
        guard initialMessageId == nil,
              let bookmark = messagesRepository.scrollBookmark(chatId: chat.chatId), !bookmark.followsLatest,
              let anchor = bookmark.anchor, let row = timelineRowID(anchor, items: presentation.items) else { return .latest }
        return .row(row, originY: bookmark.offset.map { CGFloat($0.originY) })
    }

    private func hostedRow(_ item: TimelineItem, layout: TimelineRowLayout, width: CGFloat, context: TimelineRowPassContext) -> some View {
        VStack(spacing: 0) {
            if let day = layout.dateSeparator {
                ChatDateSeparator(date: day)
            }
            if let message = item.message {
                if item.members.count > 1 {
                    albumGroup(item, viewport: CGSize(width: width, height: 0), firstInGroup: layout.firstInGroup)
                } else {
                    messageRow(message, rowID: item.id, firstInGroup: layout.firstInGroup, context: context)
                }
            } else if let call = item.call {
                hostedCallRow(call, context: context).id(item.id)
            }
        }
        .environment(\.chatBubbleTailed, layout.lastInRun)
        .padding(.horizontal, UnyriSavedChatStyle.side)
        .frame(maxWidth: .infinity)
        .environment(\.chatViewportWidth, max(1, width - UnyriSavedChatStyle.side * 2))
    }

    private func hostedCallRow(_ call: CallDTO, context: TimelineRowPassContext) -> some View {
        UnyriCallHistoryMenuRow(call: call, owner: context.ownerID.flatMap { UUID(uuidString: $0) },
            earlierHistory: call.endedAt.map { end in auth.historyContext.map { end < $0.beforeDate } ?? false } ?? false,
            selecting: !context.selection.isEmpty, selected: context.selection.contains("call:\(call.id.uuidString)"),
            onSelect: { [actions = bubbleActions] () -> Void in
                guard let screen = actions.screen else { return }
                screen.toggleCallSelection(call)
            },
            onDelete: { [actions = bubbleActions] (everyone: Bool) -> Void in
                guard let screen = actions.screen else { return }
                screen.deleteCallRecord(call, everyone: everyone)
            },
            callBack: chat.otherUser.map { other in { [actions = bubbleActions] () -> Void in
                guard let screen = actions.screen else { return }
                screen.callBack(other, call: call)
            } })
    }

    private func hostedFingerprint(_ id: String, presentation: TimelinePresentation, rows: HostedRows,
                                   context: TimelineRowPassContext) -> ChatTimelineFingerprint {
        guard let index = rows.index[id], index < presentation.items.count else { return ChatTimelineFingerprint(id) }
        let item = presentation.items[index]
        var print = HostedRowPrint(layout: presentation.layouts[index])
        print.selecting = !context.selection.isEmpty
        if let message = item.message {
            if item.members.count > 1 {
                print.members = item.members
                print.memberReactions = item.members.map { messagesRepository.reactions(forMessageServerId: $0.serverId) }
                print.selected = item.members.map(\.clientMessageId).filter { context.selection.contains($0) }
                print.highlighted = item.members.contains { $0.clientMessageId == context.highlighted }
                print.expanded = expandedDirectAlbums.contains(item.id)
            } else {
                let built = messageRowModel(message, context: context)
                print.model = built.model
                print.hidden = built.hidden
                print.highlighted = context.highlighted == message.clientMessageId
            }
        } else if let call = item.call {
            print.call = call
            print.selected = context.selection.contains("call:\(call.id.uuidString)") ? [id] : []
            print.earlierCall = call.endedAt.map { end in auth.historyContext.map { end < $0.beforeDate } ?? false } ?? false
        }
        return ChatTimelineFingerprint(print)
    }

    /// A height guess close enough that unmeasured rows rarely move anything (traced at
    /// debug level with the row's shape, to tune against measured heights).
    private func hostedEstimate(_ item: TimelineItem, layout: TimelineRowLayout, width: CGFloat) -> CGFloat {
        let estimate = hostedEstimateValue(item, layout: layout, width: width)
        ChatTimelineTrace.sizes.debug("estimate \(String(item.id.suffix(12)), privacy: .public) \(Double(estimate), privacy: .public) \(hostedRowKind(item), privacy: .public)")
        return estimate
    }
    /// Debug description of a row for estimate tuning (no content, only its shape).
    private func hostedRowKind(_ item: TimelineItem) -> String {
        if item.call != nil { return "call" }
        if item.members.count > 1 { return "album\(item.members.count)" }
        guard let message = item.message else { return "other" }
        var kind: String
        if message.content.directFile != nil { kind = "direct" }
        else if let attachment = message.content.attachment {
            kind = attachment.mediaType == AttachmentMetadata.mediaTypeVoice ? "voice" : attachment.mimeType.hasPrefix("image/") ? "image" : attachment.mimeType.hasPrefix("video/") ? "video" : "file"
        } else { kind = "text\((message.content.text ?? "").count)" }
        if message.content.forwarded { kind += "+fwd" }
        if message.content.replySnapshot != nil || message.targetMessageId != nil { kind += "+reply" }
        if (message.content.text ?? "").contains("http") { kind += "+link" }
        if !messagesRepository.reactions(forMessageServerId: message.serverId).isEmpty { kind += "+react" }
        return kind
    }
    /// First-pass row heights before SwiftUI measures a row. Fitted to real measurements
    /// (default density, 17 pt medium text, 1.3 line height); every miss above the anchor
    /// shows up as a hop while scrolling history, so each shape follows its real layout.
    private func hostedEstimateValue(_ item: TimelineItem, layout: TimelineRowLayout, width: CGFloat) -> CGFloat {
        var height: CGFloat = layout.firstInGroup ? appearance.groupGap : appearance.gap
        if layout.dateSeparator != nil { height += 34 }
        let typeScale = ChatRowEstimateMetrics.typeScale
        if item.call != nil { return height + 48 * typeScale }
        let bubble = max(120, (width - UnyriSavedChatStyle.side * 2) * UnyriSavedChatStyle.width)
        if item.members.count > 1 { return height + hostedAlbumEstimate(item, viewportWidth: width) }
        guard let message = item.message else { return height + 44 }
        let text = message.content.text ?? ""
        // Reaction chips, with the geometry the bubble lays them out with.
        let reactions = visibleReactions(message)
        let chip = ChatReactionMetrics.chip(lineHeight: appearance.messageLineHeight * typeScale)
        // A row of chips inside the bubble, under a card or text.
        func chipRow(_ limit: CGFloat) -> CGFloat {
            reactions.isEmpty ? 0 : ChatReactionMetrics.rowTop + ChatReactionMetrics.rowsHeight(reactions, chip, limit: limit)
        }
        // Chips on a card's or caption's time line: only what they add to the 12 pt time.
        func timeLineChips(_ limit: CGFloat) -> CGFloat {
            guard !reactions.isEmpty else { return 0 }
            let room = limit - hostedMetadataWidth(message) - ChatReactionMetrics.timeGap
            return max(0, ChatReactionMetrics.rowsHeight(reactions, chip, limit: room) - 12 * typeScale)
        }
        // Chips on the wallpaper under bare media or big emoji.
        func outsideChips(_ limit: CGFloat) -> CGFloat {
            reactions.isEmpty ? 0 : 2 + UnyriSavedChatStyle.reactionOffset + ChatReactionMetrics.rowsHeight(reactions, chip, limit: limit)
        }
        if message.content.directFile != nil {
            let caption = DirectFileOffer.caption(in: message.content.text) ?? ""
            return height + 70 * typeScale + (caption.isEmpty ? 0 : hostedTextHeight(caption, width: bubble, metadata: 0) - 8)
                + chipRow(bubble - appearance.padX * 2)
        }
        if let attachment = message.content.attachment {
            let caption = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if attachment.mimeType.hasPrefix("image/") || attachment.mimeType.hasPrefix("video/") {
                let size = unyriChatVisualSize(width: attachment.image?.width ?? 4, height: attachment.image?.height ?? 3,
                                               cellWidth: nil, cellHeight: nil)
                if caption.isEmpty { return height + size.height + 6 + outsideChips(size.width) }
                // A media caption keeps its time, with the chips, on a line of its own under the text.
                return height + size.height + hostedTextHeight(caption, width: size.width, metadata: 0) + 14 * typeScale
                    + timeLineChips(size.width - max(0, appearance.padX - 2) * 2)
            }
            let voice = attachment.mediaType == AttachmentMetadata.mediaTypeVoice
            let card: CGFloat = voice ? 54 : 64
            // Voice cards are narrower, with 8 + 11 pt side padding.
            let inner = voice ? VoiceFormat.bubbleWidth(attachment.voice?.durationMs ?? 0, limit: bubble) - 19
                : min(bubble, UnyriSavedChatStyle.fileWidth) - appearance.padX * 2
            return height + card * typeScale + (caption.isEmpty ? 0 : hostedTextHeight(caption, width: bubble, metadata: 0) - 8)
                + chipRow(inner)
        }
        if let emoji = hostedBigEmoji(message) {
            let scale = min(1.4, max(0.85, appearance.fontSize / 17 * typeScale))
            return height + ChatBigEmojiMessage.pointSize(emoji.count) * scale * 1.193 + (UnyriSavedChatStyle.showTimes ? 18 : 0)
                + outsideChips(bubble)
        }
        var extra: CGFloat = 0
        if message.content.forwarded { extra += 20 * typeScale }
        if message.content.replySnapshot != nil { extra += 44 * typeScale }
        if let address = chatLinkAddress(text) {
            // Host line, an optional path line, then the time (and chips) under the card.
            extra += (address.path.isEmpty || address.path == "/" ? 27 : 44) * typeScale
            return height + extra + hostedTextHeight(text, width: bubble, metadata: 0) + timeLineChips(bubble - appearance.padX * 2)
        }
        let metadata = UnyriSavedChatStyle.metadataMode == "inline" ? hostedMetadataWidth(message) : 0
        guard !reactions.isEmpty else { return height + extra + hostedTextHeight(text, width: bubble, metadata: metadata) }
        if message.content.conferenceInvite != nil {
            // A call invite carries the chips on its card's time line.
            return height + extra + hostedTextHeight(text, width: bubble, metadata: metadata) + timeLineChips(bubble - appearance.padX * 2)
        }
        let available = max(40, bubble - appearance.padX * 2)
        let reserve = metadata + ChatReactionMetrics.chipsWidth(reactions, chip) + ChatReactionMetrics.timeGap
        if metadata > 0, reserve <= available, ChatReactionMetrics.endsRightToLeft(text) == ChatRowEstimateMetrics.rightToLeft {
            // The chips share the last line with the time; a full last line moves both to a
            // line of their own, at least a chip tall.
            let line = max(appearance.fontSize * typeScale * appearance.lineHeight, chip.height + appearance.extraLineSpacing * typeScale)
            return height + extra + hostedTextHeight(text, width: bubble, metadata: reserve, wrap: line)
        }
        return height + extra + hostedTextHeight(text, width: bubble, metadata: metadata) + chipRow(available)
    }

    /// Reactions a row shows: none while reactions are off, none on a deleted message.
    private func visibleReactions(_ message: DecryptedChatMessage) -> [ReactionSummary] {
        guard UnyriSavedChatStyle.showReactions, !message.isDeletedForEveryone else { return [] }
        return messagesRepository.reactions(forMessageServerId: message.serverId).filter { $0.count > 0 }
    }

    /// A text bubble: padding plus wrapped lines. Glyph advances are averaged per script
    /// (17 pt medium: Latin 9, Cyrillic and others 10.5, space 5, emoji 22) because word wrap
    /// wastes part of each line. `metadata` is the inline time run (with the reaction chips'
    /// room before it): when the last line has no room for it, it wraps onto a line of its
    /// own, `wrap` tall (a short 14 pt line for the time alone).
    private func hostedTextHeight(_ text: String, width: CGFloat, metadata: CGFloat, wrap: CGFloat? = nil) -> CGFloat {
        let typeScale = ChatRowEstimateMetrics.typeScale
        let size = appearance.fontSize * typeScale
        let line = max(size * 1.2, size * appearance.lineHeight)
        let available = max(40, width - appearance.padX * 2)
        var lines: CGFloat = 0
        var last: CGFloat = 0
        for paragraph in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var advance: CGFloat = 0
            for scalar in paragraph.unicodeScalars {
                if scalar == " " { advance += 0.3 }
                else if scalar.isASCII { advance += 0.53 }
                else if scalar.properties.isEmojiPresentation { advance += 1.3 }
                else if scalar.properties.generalCategory == .nonspacingMark || scalar.value == 0xFE0F || scalar.value == 0x200D { continue }
                else { advance += 0.62 }
            }
            let measured = advance * size
            let count = max(1, (measured / available).rounded(.up))
            lines += count
            last = measured - (count - 1) * available
        }
        lines = max(1, lines)
        // Line spacing sits between lines only: one line is the font's own line height.
        var height = appearance.padY * 2 + lines * line - appearance.extraLineSpacing * typeScale
        if metadata > 0, last + metadata > available { height += wrap ?? 14 * typeScale }
        return height
    }

    /// Width of the inline time run: time, ticks for own messages, "edited".
    private func hostedMetadataWidth(_ message: DecryptedChatMessage) -> CGFloat {
        guard UnyriSavedChatStyle.showTimes else { return 0 }
        let typeScale = ChatRowEstimateMetrics.typeScale
        var width: CGFloat = message.senderId == auth.currentUser?.id ? 50 : 34
        if message.isEdited { width += 40 }
        return width * typeScale
    }

    /// The same rule as the row's big-emoji check, from the message alone.
    private func hostedBigEmoji(_ message: DecryptedChatMessage) -> ChatEmojiOnlyCache.Entry? {
        guard message.content.kind == MessageContent.kindText, !message.content.forwarded,
              message.content.replySnapshot == nil, message.content.attachment == nil,
              message.content.conferenceInvite == nil, message.content.directFile == nil,
              !message.isDeletedForEveryone, message.status != .decryptFailed, message.status != .identityChanged,
              message.status != .pending, let text = message.content.text else { return nil }
        return ChatEmojiOnlyCache.shared.entry(id: message.clientMessageId, text: text)
    }

    /// An album: its mosaic runs (the same geometry the row draws), file rows, the caption bubble.
    private func hostedAlbumEstimate(_ item: TimelineItem, viewportWidth: CGFloat) -> CGFloat {
        let members = item.members
        let typeScale = ChatRowEstimateMetrics.typeScale
        let directOffers = members.compactMap(\.content.directFile)
        if directOffers.count > 1, directOffers.count == members.count, !expandedDirectAlbums.contains(item.id) {
            return 129 * typeScale + hostedAlbumReactions(members, width: min(270, max(200, (viewportWidth - UnyriSavedChatStyle.side * 2) * 0.88)))
        }
        let runs = albumRuns(members)
        let documents = runs.allSatisfy { !$0.visual }
        let width = min(max(80, (viewportWidth - UnyriSavedChatStyle.side * 2) * UnyriSavedChatStyle.width),
                        documents ? UnyriSavedChatStyle.fileWidth : UnyriSavedChatStyle.mediaWidth)
        var height: CGFloat = 0
        for run in runs {
            if run.visual, (1...10).contains(run.members.count) {
                height += CGFloat(UnyriAlbumMosaic.make(ratios: run.members.map(albumRatio), width: Double(width),
                                                        gap: Double(UnyriSavedChatStyle.albumGap)).height)
            } else {
                // Album file rows are compact (3 pt vertical padding instead of the bubble's).
                height += CGFloat(run.members.count) * 52 * typeScale
            }
        }
        height += CGFloat(max(0, runs.count - 1)) * UnyriSavedChatStyle.albumGap
        if let caption = members.lazy.compactMap({ self.albumCaption($0) }).first {
            height += hostedTextHeight(caption, width: width, metadata: 0) + 14 * typeScale
        } else if documents {
            height += 21 * typeScale
        }
        return height + hostedAlbumReactions(members, width: width)
    }

    /// The chips under an album, on the wallpaper: one per member and emoji.
    private func hostedAlbumReactions(_ members: [DecryptedChatMessage], width: CGFloat) -> CGFloat {
        let reactions = members.flatMap { visibleReactions($0) }
        guard !reactions.isEmpty else { return 0 }
        let chip = ChatReactionMetrics.chip(lineHeight: appearance.messageLineHeight * ChatRowEstimateMetrics.typeScale)
        return UnyriSavedChatStyle.reactionOffset + ChatReactionMetrics.rowsHeight(reactions, chip, limit: width)
    }

    // MARK: What the timeline reports

    private func handleHostedVisibility(_ visibility: ChatTimelineVisibility) {
        let near = visibility.distanceFromLatest <= 120
        if isNearBottom != near { isNearBottom = near }
        if visibility.following {
            if newMessagesCount != 0 { newMessagesCount = 0 }
            if returnAnchor != nil { returnAnchor = nil }
        }
        guard readable else { return }
        let presentation = timelinePresentation
        let rows = hostedRows.update(timelineRevision, items: presentation.items)
        let bounds = CGRect(origin: .zero, size: visibility.viewport)
        let onScreen = visibility.rows.filter { $0.frame.intersects(bounds) && $0.frame.intersection(bounds).height > 1 }
        if PerformanceRecorder.shared.isEnabled {
            PerformanceRecorder.shared.recordScroll(visibleCount: onScreen.count)
            PerformanceMonitor.shared.activity()
        }
        if scenePhase == .active {
            let viewed = Set(onScreen.compactMap { row -> UUID? in
                guard row.id.hasPrefix("call:") else { return nil }
                return UUID(uuidString: String(row.id.dropFirst(5)))
            })
            if !viewed.isEmpty { callsHistory.acknowledgeViewed(viewed) }
        }
        // Read receipts: the newest sequence of another person's message on screen.
        let cutoff = auth.historyBeforeSequence ?? 0
        let owner = auth.currentUser?.id
        var sequence: Int64 = 0
        for row in onScreen {
            guard let index = rows.index[row.id], index < presentation.items.count else { continue }
            for member in presentation.items[index].members where member.senderId != owner || isUnavailableHistory(member, cutoff: cutoff) {
                if let value = member.sequence { sequence = max(sequence, value) }
            }
        }
        if sequence > max(readTracker.sequence, readTracker.pending) {
            readTracker.pending = sequence
            readTracker.task?.cancel()
            readTracker.task = Task { @MainActor in
                do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
                guard !Task.isCancelled else { return }
                submitVisibleRead()
            }
        }
        // Reading position: the topmost row on screen, unless the newest one is visible.
        if let first = onScreen.min(by: { $0.frame.minY < $1.frame.minY }) {
            let anchor = presentation.bookmarkAnchors[first.id] ?? first.id
            let tailVisible = presentation.items.last.map { last in onScreen.contains { $0.id == last.id } } ?? false
            messagesRepository.rememberScroll(chatId: chat.chatId, anchor: anchor,
                followsLatest: visibility.following || near || tailVisible,
                offset: ChatReaderBookmarkOffset(originY: Double(first.frame.minY), rowHeight: Double(first.frame.height),
                                                 viewportWidth: Double(visibility.viewport.width)))
        }
        // Older pages before the reader reaches the top.
        if let topmost = visibility.topmostIndexFromOldest {
            if messagesRepository.hasMore, topmost < 8 { loadOlderAnchored() }
            if topmost < 3, let cursor = nextCallCursor, attemptedCallCursor != cursor, !loadingOlderCalls {
                attemptedCallCursor = cursor
                loadingOlderCalls = true
                Task {
                    defer { loadingOlderCalls = false }
                    do {
                        nextCallCursor = try await callsHistory.refreshHistory(
                            peer: chat.otherUser.flatMap { UUID(uuidString: $0.id) }, before: cursor)
                    } catch { /* Retried after scrolling away and back. */ }
                }
            } else if topmost >= 3 {
                attemptedCallCursor = nil
            }
        }
    }

    /// Hands the pending sequence to the repository. The tracker moves only when it is
    /// accepted: a chat locked or left meanwhile refuses it, and the visibility report
    /// after the timeline unfreezes offers it again. Never cancels the debounce task
    /// it may run in: the repository saves the read in the caller's task.
    private func submitVisibleRead() {
        let sequence = readTracker.pending
        readTracker.pending = 0
        guard sequence > readTracker.sequence,
              messagesRepository.setVisibleReadSequence(chatId: chat.chatId, sequence: sequence) else { return }
        readTracker.sequence = sequence
    }

    private func hostedArrival() {
        let presentation = timelinePresentation
        let arrived = arrivalTracker.arrivals(tail: presentation.arrivalRevision.tail, ids: presentation.arrivalIDs)
        guard arrived > 0, conversationVisible, let last = presentation.items.last else { return }
        // The timeline already keeps a reader at the end there.
        guard !hostedTimeline.isFollowing else { return }
        if last.message?.senderId == auth.currentUser?.id {
            // Own message: go to it, as every messenger does.
            hostedTimeline.scrollToLatest(animated: true)
            newMessagesCount = 0
        } else if presentation.hasMessages {
            newMessagesCount += arrived
        }
    }

    private func hostedJumpTapped() {
        // After a jump to a quote, search hit or pin, the first tap returns to where you
        // were reading; the next goes to the end.
        if let anchor = returnAnchor, !inChatSearch {
            returningToAnchor = true; jumpRequest = anchor; returnAnchor = nil
            return
        }
        messagesRepository.rememberScroll(chatId: chat.chatId, anchor: nil, followsLatest: true)
        hostedTimeline.scrollToLatest(animated: true)
        newMessagesCount = 0
    }

    private func hostedJump() async {
        guard let request = jumpRequest else { return }
        if !returningToAnchor { returnAnchor = messagesRepository.currentReadingAnchor(chatId: chat.chatId) }
        returningToAnchor = false
        let locatedID: String?
        if timelineRowID(request, items: timelineItems) != nil { locatedID = request }
        else { locatedID = await messagesRepository.locateMessage(chatId: chat.chatId, id: request) }
        guard !Task.isCancelled else { return }
        if let id = locatedID {
            // An older page may still be on its way into the timeline.
            var landed = false
            for _ in 0..<20 {
                await Task.yield()
                if let row = timelineRowID(id, items: timelineItems), hostedTimeline.scroll(to: row, animated: true) { landed = true; break }
                do { try await Task.sleep(nanoseconds: 50_000_000) } catch { return }
            }
            guard landed else { return }
            highlightedMessage = id
            do { try await Task.sleep(nanoseconds: 1_500_000_000) } catch { return }
            if highlightedMessage == id { highlightedMessage = nil }
        } else if !Task.isCancelled {
            messagesRepository.reportError(UnyriL10n.string("Original message is not available in loaded history.", comment: ""))
        }
        if !Task.isCancelled, jumpRequest == request { jumpRequest = nil }
    }
}
