import SwiftUI
import UIKit
import Combine
import Network
import Darwin

/// Sampling is independent of SwiftUI state: collecting frames never publishes
/// changes into the chat's view tree. Only the settings screen observes status.
final class PerformanceMonitor: NSObject, ObservableObject {
    static let shared = PerformanceMonitor()
    @Published private(set) var onlineStatus = "Diagnostics are local only."
    private let recorder = PerformanceRecorder.shared
    private let workerQueue = DispatchQueue(label: "unyri.performance.sample", qos: .utility)
    private let fileQueue = DispatchQueue(label: "unyri.performance.file", qos: .utility)
    private let bindingLock = NSLock()
    private var bindingVersion: UInt64 = 0
    private var owner: String?
    private var writePending = false
    private var workers: DispatchSourceTimer?
    private var pathMonitor: NWPathMonitor?
    private var observers: [NSObjectProtocol] = []
    private var link: CADisplayLink?
    private var pulse = Pulse()
    private var lastFrame: TimeInterval?
    private var summaryAt: TimeInterval = 0
    private var lastGapAt: TimeInterval = 0
    private var frames = 0, lateFrames = 0
    private var maximumGap: Double = 0
    private var keyboardStart: TimeInterval?
    private var probes: [PerformanceTouchProbe] = []
    private var active = false
    private struct LocalCapture: Codable { let owner: String; let snapshot: PerformanceSnapshot }
    private static var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCO/Performance/capture.json")
    }
    private final class Pulse: @unchecked Sendable {
        let lock = NSLock()
        var pending: TimeInterval?
        var reportedAt: TimeInterval = 0
        var ticks = 0
        var cancelled = false
    }
    func configure(enabled: Bool, active: Bool) {
        precondition(Thread.isMainThread)
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        let revision = (Bundle.main.object(forInfoDictionaryKey: "UNYRI_GIT_REVISION") ?? Bundle.main.object(forInfoDictionaryKey: "COCO_GIT_REVISION")) as? String ?? "local"
        let source = revision.count == 40 && revision.allSatisfy(\.isHexDigit) ? String(revision.prefix(12)) : "local"
        recorder.configure(enabled: enabled,
            app: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            build: build + "-" + source, os: UIDevice.current.systemVersion, hardware: Self.hardware())
        self.active = enabled && active
        if self.active { start() } else {
            stop()
            if !enabled {
                bind(owner: nil)
                fileQueue.async { try? FileManager.default.removeItem(at: Self.fileURL) }
            }
        }
    }
    func bind(owner newOwner: String?) {
        precondition(Thread.isMainThread)
        bindingLock.lock()
        if owner == newOwner { bindingLock.unlock(); return }
        owner = newOwner; bindingVersion += 1
        let version = bindingVersion
        bindingLock.unlock()
        recorder.reset()
        guard let newOwner, recorder.isEnabled else {
            fileQueue.async { try? FileManager.default.removeItem(at: Self.fileURL) }
            return
        }
        fileQueue.async { [weak self] in
            guard let self else { return }
            guard self.bindingMatches(newOwner, version) else { return }
            if let bytes = try? Data(contentsOf: Self.fileURL), bytes.count <= 256 * 1024,
               let saved = try? JSONDecoder().decode(LocalCapture.self, from: bytes), saved.owner == newOwner {
                self.bindingLock.lock()
                if self.owner == newOwner && self.bindingVersion == version { self.recorder.restore(saved.snapshot) }
                self.bindingLock.unlock()
            } else { try? FileManager.default.removeItem(at: Self.fileURL) }
        }
    }
    private func bindingMatches(_ owner: String, _ version: UInt64) -> Bool {
        bindingLock.lock(); defer { bindingLock.unlock() }
        return self.owner == owner && bindingVersion == version && recorder.isEnabled
    }
    func clear() {
        bindingLock.lock(); bindingVersion += 1; bindingLock.unlock()
        recorder.reset()
        fileQueue.async { try? FileManager.default.removeItem(at: Self.fileURL) }
    }
    /// Called before the existing account-cache purger. The asynchronous fence
    /// ensures an old pending write cannot recreate data after logout completes.
    @MainActor func eraseForLogout() async {
        stop(); bind(owner: nil); recorder.reset()
        await withCheckedContinuation { continuation in
            fileQueue.async { try? FileManager.default.removeItem(at: Self.fileURL); continuation.resume() }
        }
    }
    func setOnlineStatus(_ status: String) { if onlineStatus != status { onlineStatus = status } }
    func activity() {
        guard active, recorder.isEnabled else { return }
        if link?.isPaused == true { lastFrame = nil; link?.isPaused = false }
    }
    func memoryCheckpoint(screen: PerformanceScreen, phase: Double) {
        guard recorder.isEnabled else { return }
        let expected = recorder.recordingId
        workerQueue.async { [weak self] in
            guard let self, self.recorder.isEnabled, self.recorder.recordingId == expected else { return }
            var info = mach_task_basic_info()
            var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
            let result = withUnsafeMutablePointer(to: &info) { pointer in
                pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                    task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
                }
            }
            if result == KERN_SUCCESS {
                self.recorder.record(.memory, ["memory_mb": Double(info.resident_size) / 1_048_576, "phase": phase], screen: screen)
            }
        }
    }
    private func start() {
        guard workers == nil else { attachWindows(); return }
        recorder.record(.appActive)
        pulse = Pulse(); let samplingPulse = pulse
        let timer = DispatchSource.makeTimerSource(queue: workerQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(500), leeway: .milliseconds(50))
        timer.setEventHandler { [weak self] in self?.sample(samplingPulse) }; workers = timer; timer.resume()
        let display = CADisplayLink(target: self, selector: #selector(frame(_:)))
        display.add(to: .main, forMode: .common); link = display
        let network = NWPathMonitor(); pathMonitor = network
        network.pathUpdateHandler = { path in
            let kind = path.status != .satisfied ? 0 : path.usesInterfaceType(.wifi) ? 1 : path.usesInterfaceType(.cellular) ? 2 : 3
            PerformanceRecorder.shared.record(.network, ["network": Double(kind)])
        }; network.start(queue: workerQueue)
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIWindow.didBecomeVisibleNotification, object: nil, queue: .main) { [weak self] _ in self?.attachWindows() })
        observers.append(center.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { _ in PerformanceRecorder.shared.record(.memoryWarning) })
        observers.append(center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main) { _ in Self.conditions() })
        observers.append(center.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { _ in Self.conditions() })
        for name in [UIResponder.keyboardWillShowNotification, UIResponder.keyboardWillHideNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in self?.keyboardStart = ProcessInfo.processInfo.systemUptime; self?.activity() })
        }
        for (name, kind) in [(UIResponder.keyboardDidShowNotification, PerformanceKind.keyboardShow), (UIResponder.keyboardDidHideNotification, .keyboardHide)] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                guard let start = self?.keyboardStart else { return }
                PerformanceRecorder.shared.record(kind, ["duration_ms": max(0, ProcessInfo.processInfo.systemUptime - start) * 1000]); self?.keyboardStart = nil
            })
        }
        Self.conditions(); attachWindows(); activity()
    }
    private func stop() {
        pulse.lock.lock(); pulse.cancelled = true; pulse.lock.unlock()
        if workers != nil { recorder.record(.appInactive) }
        workers?.setEventHandler {}; workers?.cancel(); workers = nil
        pathMonitor?.cancel(); pathMonitor = nil
        link?.invalidate(); link = nil; lastFrame = nil; frames = 0; lateFrames = 0; maximumGap = 0
        for observer in observers { NotificationCenter.default.removeObserver(observer) }; observers.removeAll()
        for probe in probes { probe.view?.removeGestureRecognizer(probe) }; probes.removeAll()
        persist()
    }
    private func attachWindows() {
        guard active else { return }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows where !window.isHidden && !String(describing: type(of: window)).contains("Keyboard") {
                guard !(window.gestureRecognizers ?? []).contains(where: { $0 is PerformanceTouchProbe }) else { continue }
                let probe = PerformanceTouchProbe(); window.addGestureRecognizer(probe); probes.append(probe)
            }
        }
    }
    @objc private func frame(_ display: CADisplayLink) {
        let now = ProcessInfo.processInfo.systemUptime
        defer { lastFrame = display.timestamp }
        guard let previous = lastFrame else { summaryAt = now; return }
        let gap = max(0, display.timestamp - previous) * 1000
        let budget = max(1, (display.targetTimestamp - display.timestamp) * 1000)
        frames += 1; maximumGap = max(maximumGap, gap)
        if gap > budget * 1.6 && gap > 12 {
            lateFrames += 1
            if now - lastGapAt >= 0.5 { recorder.record(.frameGap, ["gap_ms": gap, "budget_ms": budget]); lastGapAt = now }
        }
        if now - summaryAt >= 1 {
            recorder.record(.frameSummary, ["frames": Double(frames), "late_frames": Double(lateFrames), "max_gap_ms": maximumGap, "budget_ms": budget, "fps": Double(frames) / max(0.001, now - summaryAt), "maximum_fps": Double(UIScreen.main.maximumFramesPerSecond)])
            frames = 0; lateFrames = 0; maximumGap = 0; summaryAt = now
        }
    }
    private func sample(_ state: Pulse) {
        guard recorder.isEnabled else { return }
        let now = ProcessInfo.processInfo.systemUptime
        state.lock.lock()
        guard !state.cancelled else { state.lock.unlock(); return }
        state.ticks += 1; let ticks = state.ticks
        if let pending = state.pending {
            if now - pending > 0.2 && now - state.reportedAt > 1 {
                state.reportedAt = now; recorder.record(.mainStall, ["duration_ms": (now - pending) * 1000, "phase": 0])
            }
            state.lock.unlock()
        } else {
            state.pending = now; state.lock.unlock()
            let recording = recorder.recordingId
            DispatchQueue.main.async {
                let elapsed = ProcessInfo.processInfo.systemUptime - now
                state.lock.lock(); let cancelled = state.cancelled; state.pending = nil; state.lock.unlock()
                if !cancelled, elapsed > 0.1, PerformanceRecorder.shared.recordingId == recording { PerformanceRecorder.shared.record(.mainStall, ["duration_ms": elapsed * 1000, "phase": 1]) }
            }
        }
        if ticks % 4 == 0 {
            var info = mach_task_basic_info(); var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
            let result = withUnsafeMutablePointer(to: &info) { pointer in
                pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count) }
            }
            if result == KERN_SUCCESS { recorder.record(.memory, ["memory_mb": Double(info.resident_size) / 1_048_576]) }
        }
        if ticks % 20 == 0 {
            persist()
        }
    }
    private func persist() {
        bindingLock.lock()
        guard let current = owner, !writePending, recorder.isEnabled else { bindingLock.unlock(); return }
        let version = bindingVersion; writePending = true; bindingLock.unlock()
        fileQueue.async { [weak self] in
            guard let self else { return }
            defer { self.bindingLock.lock(); self.writePending = false; self.bindingLock.unlock() }
            guard self.bindingMatches(current, version), let snapshot = self.recorder.snapshot(preferProblemWindow: false) else { return }
            let value = LocalCapture(owner: current, snapshot: snapshot)
            guard let data = try? JSONEncoder().encode(value), data.count <= 256 * 1024 else { return }
            do {
                let directory = Self.fileURL.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var excluded = URLResourceValues(); excluded.isExcludedFromBackup = true
                var mutable = directory; try mutable.setResourceValues(excluded)
                try data.write(to: Self.fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } catch { /* The bounded in-memory log remains available for a report. */ }
        }
    }
    private static func conditions() {
        PerformanceRecorder.shared.record(.thermal, ["thermal": Double(ProcessInfo.processInfo.thermalState.rawValue)])
        PerformanceRecorder.shared.record(.lowPower, ["low_power": ProcessInfo.processInfo.isLowPowerModeEnabled ? 1 : 0])
    }
    private static func hardware() -> String {
        var info = utsname(); uname(&info)
        let capacity = MemoryLayout.size(ofValue: info.machine)
        return withUnsafePointer(to: &info.machine) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: capacity) { String(cString: $0) }
        }
    }
}

/// Observes only the start of a non-text-input touch, then fails immediately.
/// It never delays, cancels or competes with the app's real gestures.
private final class PerformanceTouchProbe: UIGestureRecognizer {
    init() { super.init(target: nil, action: nil); cancelsTouchesInView = false; delaysTouchesBegan = false; delaysTouchesEnded = false }
    required init?(coder: NSCoder) { super.init(target: nil, action: nil); cancelsTouchesInView = false; delaysTouchesBegan = false; delaysTouchesEnded = false }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        defer { state = .failed }
        guard PerformanceRecorder.shared.isEnabled, let touch = touches.first else { return }
        var candidate = touch.view
        while let view = candidate {
            if view is UITextInput || String(describing: type(of: view)).contains("Keyboard") { return }
            candidate = view.superview
        }
        PerformanceRecorder.shared.record(.touch, ["duration_ms": max(0, ProcessInfo.processInfo.systemUptime - touch.timestamp) * 1000, "count": Double(touches.count)])
        PerformanceMonitor.shared.activity()
    }
}

private struct PerformanceScreenModifier: ViewModifier {
    let screen: PerformanceScreen
    @State private var token = UUID()
    func body(content: Content) -> some View {
        content.onAppear {
            PerformanceRecorder.shared.pushScreen(screen, token: token); PerformanceMonitor.shared.activity()
            PerformanceMonitor.shared.memoryCheckpoint(screen: screen, phase: 1)
        }
            .onDisappear {
                PerformanceMonitor.shared.memoryCheckpoint(screen: screen, phase: 2)
                PerformanceRecorder.shared.popScreen(token: token)
            }
    }
}
extension View { func performanceScreen(_ screen: PerformanceScreen) -> some View { modifier(PerformanceScreenModifier(screen: screen)) } }
