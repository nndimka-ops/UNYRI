import Combine
import Foundation
import UIKit

/// Reads a full stats report once a second while the call is connected or
/// reconnecting and grades the connection (`CallQualityGrader`), in the
/// foreground only; in the background it reads only while diagnostics are on,
/// for the ICE diagnostic's history (`iceReads`) and nothing else. Publishes
/// only when what is shown changes (the level, the route, the words), so the
/// status line, the video timer and the collapsed banner re-render at most
/// once a second and usually far less. The QA numbers live in `details`,
/// observed only by the stats sheet.
///
/// "Reconnecting" comes from the call state after `reconnectingAfter`, the
/// same for the full screen and the collapsed banner. After a drop that was
/// shown the bars measure again from scratch instead of keeping the drop's
/// grade; a shorter blip leaves them alone.
@MainActor
final class CallQualityMonitor: ObservableObject {
    @Published private(set) var indicator = CallQualityIndicator()
    let details = CallQualityDetails()

    var sample: () async -> CallStatsSample? = { nil }
    var context: () -> CallQualityContext = { CallQualityContext() }
    /// Each second's metrics, for decisions that are not on screen (pausing
    /// outgoing video on a starved link, a pair that carries no voice, the
    /// media diagnostics).
    var onMetrics: (CallQualityMetrics) -> Void = { _ in }
    /// This call's recent stats reads, with when each arrived (ICE
    /// diagnostics: did media still flow, did STUN responses still come back).
    private(set) var iceReads = CallIceReadHistory()

    private var grader = CallQualityGrader()
    private var task: Task<Void, Never>?
    private var callId: String?
    /// Media is down, as the call state last said.
    private var mediaDown = false
    /// The drop has lasted `reconnectingAfter`: it is on screen.
    private var reconnectingShown = false
    private var reconnectingTask: Task<Void, Never>?
    private var networkChanges: AnyCancellable?
    private var lastAnnounced: String?

    init() {
        // "Waiting for network…" follows the device's own path while shown.
        networkChanges = CallNetworkMonitor.shared.paths.sink { [weak self] _ in
            guard let self, self.reconnectingShown else { return }
            self.publish()
        }
    }

    func update(callId: String?, active: Bool, reconnecting: Bool = false, privateRelay: Bool = false) {
        if callId != self.callId {
            stop()
            self.callId = callId
            grader.reset()
            details.reset()
            iceReads.reset()
            mediaDown = false
            reconnectingShown = false
            reconnectingTask?.cancel(); reconnectingTask = nil
            lastAnnounced = nil
            let fresh = CallQualityIndicator(privateRelay: privateRelay)
            if indicator != fresh { indicator = fresh }
        } else if indicator.privateRelay != privateRelay {
            indicator.privateRelay = privateRelay
        }
        setMediaDown(active && reconnecting)
        if active { start() } else { stop() }
    }

    private func setMediaDown(_ down: Bool) {
        guard down != mediaDown else { return }
        mediaDown = down
        reconnectingTask?.cancel()
        reconnectingTask = nil
        if down {
            reconnectingTask = Task { @MainActor [weak self] in
                let delay = UInt64(CallQualityPresentation.reconnectingAfter * 1_000_000_000)
                do { try await Task.sleep(nanoseconds: delay) } catch { return }
                guard let self, self.mediaDown else { return }
                self.reconnectingShown = true
                self.publish()
            }
        } else {
            // A drop that was shown ends in measuring and a fresh grade (no
            // tail). A blip shorter than `reconnectingAfter` was never shown,
            // so the bars stay as they were instead of dimming for seconds.
            if reconnectingShown { grader.restartMeasuring() }
            reconnectingShown = false
            publish()
        }
    }

    private func start() {
        guard task == nil else { return }
        task = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let monitor = self else { return }
                if UIApplication.shared.applicationState == .active {
                    if let sample = await monitor.sample(), !Task.isCancelled {
                        var context = monitor.context()
                        context.reconnecting = monitor.mediaDown
                        monitor.grader.update(sample, context: context)
                        monitor.iceReads.add(sample, at: ProcessInfo.processInfo.systemUptime)
                        monitor.publish()
                        monitor.details.set(monitor.grader.metrics)
                        monitor.onMetrics(monitor.grader.metrics)
                    }
                } else if CallDiagnostics.isEnabled, let sample = await monitor.sample(), !Task.isCancelled {
                    // Nothing on screen reads the grade in the background (PiP
                    // shows none), and the grader, the video pause and the
                    // bars stay as they are. With diagnostics on, the reads go
                    // on for the ICE diagnostic alone, so a drop with the
                    // screen locked or in PiP is measured like one on screen.
                    monitor.iceReads.add(sample, at: ProcessInfo.processInfo.systemUptime)
                }
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
        }
    }

    private func stop() {
        task?.cancel()
        task = nil
    }

    private func publish() {
        let level: CallQualityIndicator.Level
        if reconnectingShown {
            level = .reconnecting(waitingForNetwork: !CallNetworkMonitor.shared.current.satisfied)
        } else if let grade = grader.grade {
            level = .graded(grade)
        } else {
            level = .measuring
        }
        let now = Date().timeIntervalSinceReferenceDate
        let settled = grader.gradeSince.map { now - $0 >= CallQualityPresentation.captionAfter } ?? false
        let next = CallQualityIndicator(level: level, route: grader.metrics.route ?? indicator.route,
                                        privateRelay: indicator.privateRelay, settled: settled)
        guard next != indicator else { return }
        indicator = next
        announce(next.caption)
    }

    /// VoiceOver hears only entering weak, poor or a shown drop, never the
    /// bars moving.
    private func announce(_ caption: String?) {
        defer { lastAnnounced = caption }
        guard let caption, caption != lastAnnounced, UIAccessibility.isVoiceOverRunning,
              UIApplication.shared.applicationState == .active else { return }
        UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(caption))
    }
}

/// Per-second numbers for the QA stats sheet only.
@MainActor
final class CallQualityDetails: ObservableObject {
    @Published private(set) var metrics = CallQualityMetrics()
    func set(_ value: CallQualityMetrics) { if value != metrics { metrics = value } }
    func reset() { set(CallQualityMetrics()) }
}

/// The halo's level source: polls receiver energy at ~7 Hz while the
/// connected call is on screen and hands each smoothed level straight to the
/// halo layer. Nothing here is `@Published`: the voice never re-renders SwiftUI.
@MainActor
final class CallVoiceMeter: ObservableObject {
    weak var sink: CallVoiceLevelSink?
    private var estimator = CallVoiceLevelEstimator()

    func push(_ sample: CallAudioEnergySample?) {
        let level = estimator.update(sample)
        sink?.setVoiceLevel(level)
    }

    func silence() {
        estimator.reset()
        sink?.setVoiceLevel(0)
    }

    /// Runs until the calling task is cancelled (the halo's `.task(id:)`).
    func run(source: @escaping () async -> CallAudioEnergySample?) async {
        silence()
        while !Task.isCancelled {
            let active = UIApplication.shared.applicationState == .active
            let sample = active ? await source() : nil
            if Task.isCancelled { break }
            push(sample)
            do { try await Task.sleep(nanoseconds: CallVoiceLevelEstimator.pollIntervalNs) } catch { break }
        }
        silence()
    }
}

@MainActor
protocol CallVoiceLevelSink: AnyObject {
    func setVoiceLevel(_ level: Double)
}
