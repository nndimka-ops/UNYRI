import AVFoundation
import SwiftUI
import UIKit

// Building blocks of the 1:1 call screen. Each one observes only what it
// shows, so a voice level, a connection grade or a route change never
// re-renders the whole screen.

// MARK: - Outgoing call path

/// The graphic has no accessibility element: the capsule speaks the same state.
/// Only phase/lifecycle changes reach UIKit; all motion lives on the render server.
struct CallRingIndicatorView: View {
    let phase: CallRingIndicator.Phase
    let connectedAt: Date?
    let ringbackStartedAt: TimeInterval?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    @State private var visible = false

    /// 25% larger than the first version: under the large name it read as a
    /// faint dotted line.
    static let size = CGSize(width: 165, height: 40)

    var body: some View {
        CallRingIndicatorLayers(phase: phase, connectedAt: connectedAt, ringbackStartedAt: phase == .ringing ? ringbackStartedAt : nil,
                                active: visible && scenePhase == .active, staticOnly: reduceMotion || lowPower)
            .frame(width: Self.size.width, height: Self.size.height)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onAppear { visible = true }
            .onDisappear { visible = false }
            .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
                lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
            }
    }
}

private struct CallRingIndicatorLayers: UIViewRepresentable {
    let phase: CallRingIndicator.Phase
    let connectedAt: Date?
    let ringbackStartedAt: TimeInterval?
    let active: Bool
    let staticOnly: Bool

    func makeUIView(context: Context) -> CallRingIndicatorNativeView { CallRingIndicatorNativeView() }
    func updateUIView(_ view: CallRingIndicatorNativeView, context: Context) {
        view.configure(phase: phase, connectedAt: connectedAt, ringbackStartedAt: ringbackStartedAt,
                       active: active, staticOnly: staticOnly)
    }
    static func dismantleUIView(_ view: CallRingIndicatorNativeView, coordinator: ()) { view.tearDown() }
}

/// No masks, blur, display link, timer or frame-driven SwiftUI state.
/// At most the line or the phone and two waves animate repeatedly.
private final class CallRingIndicatorNativeView: UIView {
    private struct Configuration: Equatable {
        let phase: CallRingIndicator.Phase
        let connectedAt: Date?
        let ringbackStartedAt: TimeInterval?
        let active: Bool
        let staticOnly: Bool
    }
    private var configuration: Configuration?
    private let drawing = CALayer()
    private let mine = CALayer()
    private let theirs = CALayer()
    private let line = CAShapeLayer()
    private let waves = [CAShapeLayer(), CAShapeLayer()]
    private let badge = CALayer()
    private let lock = CALayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
        isAccessibilityElement = false
    }
    required init?(coder: NSCoder) { nil }

    func configure(phase: CallRingIndicator.Phase, connectedAt: Date?, ringbackStartedAt: TimeInterval?,
                   active: Bool, staticOnly: Bool) {
        let next = Configuration(phase: phase, connectedAt: connectedAt, ringbackStartedAt: ringbackStartedAt,
                                 active: active, staticOnly: staticOnly)
        guard configuration != next else { return }
        let previous = configuration
        configuration = next
        guard active, window != nil else { tearDown(); return }
        render(previous: previous)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil, configuration?.active == true else { tearDown(); return }
        render(previous: nil)
    }

    func tearDown() {
        for item in [drawing, mine, theirs, line, badge, lock] + waves {
            item.removeAllAnimations()
            assert(item.animationKeys() == nil, "Call path animation survived teardown")
            item.removeFromSuperlayer()
        }
    }

    /// `cgImage` of a tinted SF Symbol is the untinted black glyph, which
    /// vanished on the dark call screen; drawing the tinted image bakes the colour.
    private func symbol(_ name: String, size: CGFloat, color: UIColor) -> CGImage? {
        guard let glyph = UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .regular))?
            .withTintColor(color, renderingMode: .alwaysOriginal) else { return nil }
        let format = UIGraphicsImageRendererFormat(for: traitCollection)
        format.opaque = false
        return UIGraphicsImageRenderer(size: glyph.size, format: format).image { _ in glyph.draw(at: .zero) }.cgImage
    }

    // Geometry in points (165 x 40): phone centres, the merge point, the line ends.
    private static let mineX: CGFloat = 14, theirsX: CGFloat = 151, middleX: CGFloat = 82.5, midY: CGFloat = 20
    private static let dashPeriod: CGFloat = 8.85

    private func render(previous: Configuration?) {
        guard let config = configuration, config.active, window != nil else { return }
        let phaseChanged = previous?.phase != config.phase
        tearDown()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.addSublayer(drawing)
        drawing.frame = CGRect(origin: .zero, size: CallRingIndicatorView.size)
        drawing.opacity = 1
        // Symbols remain in screen order even in right-to-left layouts: mine is left.
        for (phone, x) in [(mine, Self.mineX), (theirs, Self.theirsX)] {
            phone.bounds = CGRect(x: 0, y: 0, width: 28, height: 35)
            phone.position = CGPoint(x: x, y: Self.midY)
            phone.contents = symbol("iphone", size: 28, color: UIColor(UnyriColors.primaryText))
            phone.contentsGravity = .resizeAspect
            phone.contentsScale = traitCollection.displayScale
            phone.transform = CATransform3DIdentity
            phone.opacity = 1
            drawing.addSublayer(phone)
        }
        let path = UIBezierPath()
        path.move(to: CGPoint(x: 38, y: Self.midY))
        path.addLine(to: CGPoint(x: 127, y: Self.midY))
        line.frame = drawing.bounds
        line.path = path.cgPath
        line.fillColor = UIColor.clear.cgColor
        line.strokeColor = UIColor(UnyriColors.accent).cgColor
        line.lineWidth = 3
        line.lineCap = .round
        line.lineDashPhase = 0
        line.lineDashPattern = nil
        line.opacity = 1
        drawing.insertSublayer(line, at: 0)
        badge.bounds = CGRect(x: 0, y: 0, width: 18, height: 18)
        badge.position = CGPoint(x: 155, y: 29)
        badge.contentsGravity = .resizeAspect
        badge.contentsScale = traitCollection.displayScale
        badge.opacity = 0
        drawing.addSublayer(badge)
        lock.bounds = CGRect(x: 0, y: 0, width: 28, height: 33)
        lock.position = CGPoint(x: Self.middleX, y: Self.midY)
        lock.contents = symbol(config.connectedAt == nil ? "lock" : "lock.fill", size: 28,
                               color: UIColor(UnyriColors.primaryText))
        lock.contentsGravity = .resizeAspect
        lock.contentsScale = traitCollection.displayScale
        lock.opacity = 0
        lock.transform = CATransform3DIdentity
        drawing.addSublayer(lock)
        for wave in waves {
            wave.bounds = CGRect(x: 0, y: 0, width: 40, height: 40)
            wave.position = theirs.position
            let centre = CGPoint(x: 20, y: 20), radius: CGFloat = 17.5
            let arc = UIBezierPath()
            arc.addArc(withCenter: centre, radius: radius, startAngle: -.pi / 3, endAngle: .pi / 3, clockwise: true)
            // Move to the second arc's own start, or the path draws a chord to it.
            arc.move(to: CGPoint(x: centre.x + radius * cos(2 * .pi / 3), y: centre.y + radius * sin(2 * .pi / 3)))
            arc.addArc(withCenter: centre, radius: radius, startAngle: 2 * .pi / 3, endAngle: 4 * .pi / 3, clockwise: true)
            wave.path = arc.cgPath
            wave.fillColor = UIColor.clear.cgColor
            wave.strokeColor = UIColor(UnyriColors.accent).cgColor
            wave.lineWidth = 1.75
            wave.lineCap = .round
            wave.opacity = 0
            wave.transform = CATransform3DIdentity
            drawing.addSublayer(wave)
        }
        switch config.phase {
        case .sending:
            line.lineDashPattern = [0.1, NSNumber(value: Double(Self.dashPeriod - 0.1))]
            theirs.opacity = 0.4
        case .ringing:
            // Static accessibility/battery state retains visible ring waves.
            waves[0].opacity = config.staticOnly ? 0.65 : 0
        case .notOnline:
            line.lineDashPattern = [6, 5]
            theirs.opacity = 0.4
            badge.contents = symbol("wifi.slash", size: 11, color: UIColor(UnyriColors.primaryText))
            badge.opacity = 1
        case .answered:
            line.opacity = 0
            mine.position.x = Self.middleX
            theirs.position.x = Self.middleX
            mine.opacity = 0
            theirs.opacity = 0
            lock.opacity = 1
        case .busy, .declined:
            line.opacity = 0
            badge.contents = symbol("phone.down.fill", size: 11, color: UIColor(UnyriColors.danger))
            badge.opacity = 1
        case .noAnswer:
            line.lineDashPattern = [6, 5]
            theirs.opacity = 0.4
        case .failed:
            line.opacity = 0
            badge.contents = symbol("exclamationmark.triangle.fill", size: 11, color: UIColor(UnyriColors.warning))
            badge.opacity = 1
        case .cancelled:
            drawing.opacity = 0
        }
        CATransaction.commit()

        if phaseChanged, previous != nil, config.staticOnly {
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0
            fade.toValue = drawing.opacity
            fade.duration = 0.2
            drawing.add(fade, forKey: "stateCrossfade")
        }
        if !config.staticOnly {
            switch config.phase {
            case .sending:
                let travel = CABasicAnimation(keyPath: "lineDashPhase")
                travel.fromValue = 0
                travel.toValue = -Self.dashPeriod
                travel.duration = 0.35
                travel.repeatCount = .infinity
                line.add(travel, forKey: "sending")
            case .ringing:
                startRinging(anchor: config.ringbackStartedAt)
            case .answered where phaseChanged && previous != nil:
                mergePhones()
            case .cancelled where phaseChanged:
                let fade = CABasicAnimation(keyPath: "opacity")
                fade.fromValue = 1
                fade.toValue = 0
                fade.duration = 0.2
                drawing.add(fade, forKey: "cancelled")
            default: break
            }
        }
        if config.phase == .answered, let connectedAt = config.connectedAt {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            let elapsed = max(0, Date().timeIntervalSince(connectedAt))
            if elapsed >= 1.2 {
                drawing.opacity = 0
            } else {
                let wait = max(0, 1 - elapsed)
                let fade = CAKeyframeAnimation(keyPath: "opacity")
                fade.values = [1, 1, 0]
                fade.keyTimes = [0, NSNumber(value: wait / (wait + 0.2)), 1]
                fade.duration = wait + 0.2
                drawing.opacity = 0
                drawing.add(fade, forKey: "connected")
            }
            CATransaction.commit()
        }
    }

    private func startRinging(anchor: TimeInterval?) {
        // systemUptime and CACurrentMediaTime are monotonic. Convert the shared
        // tone start into layer time so reopening the screen preserves its phase.
        let now = CACurrentMediaTime()
        let elapsed = anchor.map { max(0, ProcessInfo.processInfo.systemUptime - $0) } ?? 0
        let began = drawing.convertTime(now - elapsed, from: nil)
        let vibration = CAKeyframeAnimation(keyPath: "transform.rotation.z")
        vibration.values = [0, 0.08, -0.08, 0.08, -0.08, 0, 0]
        vibration.keyTimes = [0, 0.03, 0.06, 0.09, 0.12, 0.15, 1]
        vibration.duration = 3
        let group = CAAnimationGroup()
        group.animations = [vibration]
        group.duration = 3
        group.beginTime = began
        group.repeatCount = .infinity
        theirs.add(group, forKey: "ringing")
        for (index, wave) in waves.enumerated() {
            let offset = Double(index) * 0.25 / 3
            let opacity = CAKeyframeAnimation(keyPath: "opacity")
            opacity.values = [0, 0, 0.8, 0, 0]
            opacity.keyTimes = [0, NSNumber(value: offset), NSNumber(value: offset + 0.001), NSNumber(value: offset + 0.3), 1]
            opacity.duration = 3
            let scale = CAKeyframeAnimation(keyPath: "transform.scale")
            scale.values = [0.8, 0.8, 1.3, 1.3]
            scale.keyTimes = [0, NSNumber(value: offset + 0.001), NSNumber(value: offset + 0.3), 1]
            scale.duration = 3
            let waveGroup = CAAnimationGroup()
            waveGroup.animations = [opacity, scale]
            waveGroup.beginTime = began
            waveGroup.duration = 3
            waveGroup.repeatCount = .infinity
            wave.add(waveGroup, forKey: "ringWave")
        }
    }

    /// Sample the capsule's spring once at the transition; CA interpolates
    /// these values on the render server without app work per frame.
    private func mergeSpring(from start: Double, to end: Double) -> [NSNumber] {
        let response = 0.34, damping = 0.86
        let omega = 2 * Double.pi / response
        let damped = omega * sqrt(1 - damping * damping)
        return (0...24).map { step in
            if step == 24 { return NSNumber(value: end) }
            let time = 0.35 * Double(step) / 24
            let remaining = exp(-damping * omega * time) *
                (cos(damped * time) + damping * omega / damped * sin(damped * time))
            return NSNumber(value: end - (end - start) * remaining)
        }
    }

    private func mergePhones() {
        for (phone, x) in [(mine, Self.mineX), (theirs, Self.theirsX)] {
            let move = CAKeyframeAnimation(keyPath: "position.x")
            move.values = mergeSpring(from: Double(x), to: Double(Self.middleX))
            move.duration = 0.35
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 1
            fade.toValue = 0
            fade.duration = 0.35
            let group = CAAnimationGroup()
            group.animations = [move, fade]
            group.duration = 0.35
            group.timingFunction = CAMediaTimingFunction(name: .linear)
            phone.add(group, forKey: "merge")
        }
        let scale = CAKeyframeAnimation(keyPath: "transform.scale")
        scale.values = mergeSpring(from: 0.6, to: 1)
        scale.duration = 0.35
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0
        fade.toValue = 1
        fade.duration = 0.35
        lock.add(scale, forKey: "mergeScale")
        lock.add(fade, forKey: "mergeOpacity")
    }
}

// MARK: - Voice halo

/// Rings around the avatar. While calling or connecting they ripple gently
/// and constantly; in a connected call they follow the other person's voice
/// and stand still in silence. Reduce Motion shows a still, stepped halo.
struct CallVoiceHalo: View {
    enum Mode: Equatable { case off, ambient, voice }
    let mode: Mode
    /// Not observed: only asked for energy samples while polling.
    let controller: CallController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var meter = CallVoiceMeter()

    private var polling: Bool { mode == .voice && scenePhase == .active }

    var body: some View {
        CallHaloLayerView(mode: mode, reduceMotion: reduceMotion, meter: meter)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task(id: polling) {
                guard polling else { meter.silence(); return }
                await meter.run { [weak controller = self.controller] in await controller?.remoteAudioEnergySample() }
            }
    }
}

private struct CallHaloLayerView: UIViewRepresentable {
    let mode: CallVoiceHalo.Mode
    let reduceMotion: Bool
    let meter: CallVoiceMeter

    func makeUIView(context: Context) -> CallHaloView { CallHaloView() }

    func updateUIView(_ view: CallHaloView, context: Context) {
        view.configure(mode: mode, reduceMotion: reduceMotion)
        if meter.sink !== view { meter.sink = view }
    }
}

/// Core Animation only: each level moves the layers through an implicit
/// animation over the poll interval, so the halo glides between reads and
/// SwiftUI never re-renders for the voice.
final class CallHaloView: UIView, CallVoiceLevelSink {
    private let glow = CALayer()
    private let voiceRings: [CAShapeLayer] = (0..<3).map { _ in CAShapeLayer() }
    private let replicator = CAReplicatorLayer()
    private let wave = CAShapeLayer()
    private var mode: CallVoiceHalo.Mode = .off
    private var reduceMotion = false
    private var configured = false
    private var level: Double = 0
    private var shownStep = -1
    private var laidOutSize: CGSize = .zero
    private static let ambientPeriod: CFTimeInterval = 2.6

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        glow.backgroundColor = UIColor.white.withAlphaComponent(0.22).cgColor
        glow.opacity = 0
        layer.addSublayer(glow)
        for ring in voiceRings {
            ring.fillColor = UIColor.clear.cgColor
            ring.strokeColor = UIColor.white.withAlphaComponent(0.55).cgColor
            ring.lineWidth = 1.2
            ring.opacity = 0
            layer.addSublayer(ring)
        }
        wave.fillColor = UIColor.clear.cgColor
        wave.strokeColor = UIColor.white.withAlphaComponent(0.55).cgColor
        wave.lineWidth = 1.2
        wave.opacity = 0
        replicator.addSublayer(wave)
        replicator.instanceCount = 3
        replicator.opacity = 0
        layer.addSublayer(replicator)
    }

    required init?(coder: NSCoder) { nil }

    func configure(mode: CallVoiceHalo.Mode, reduceMotion: Bool) {
        guard !configured || mode != self.mode || reduceMotion != self.reduceMotion else { return }
        configured = true
        self.mode = mode
        self.reduceMotion = reduceMotion
        level = 0
        shownStep = -1
        render(animated: true)
    }

    func setVoiceLevel(_ level: Double) {
        // Silence arrives as an exact 0 (the estimator floors tiny values), so
        // repeated silent polls set nothing and start no animations.
        guard mode == .voice, abs(level - self.level) > 0.001 else { return }
        self.level = level
        renderVoice(animated: true)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Only a real size change re-lays the layers and restarts the ripple.
        guard bounds.size != laidOutSize, bounds.width > 0 else { return }
        laidOutSize = bounds.size
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let diameter = bounds.width / 1.9
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        glow.bounds = CGRect(x: 0, y: 0, width: diameter, height: diameter)
        glow.position = center
        glow.cornerRadius = diameter / 2
        let ringPath = UIBezierPath(ovalIn: CGRect(x: center.x - diameter / 2, y: center.y - diameter / 2,
                                                   width: diameter, height: diameter)).cgPath
        // Bounds and position, never `frame`: the rings carry a scale transform
        // (the voice level), and a frame set through a transform is undefined —
        // after a resize the rings sat off the avatar's centre.
        for ring in voiceRings {
            ring.bounds = bounds
            ring.position = center
            ring.path = ringPath
        }
        replicator.bounds = bounds
        replicator.position = center
        // The replicator's bounds equal this view's, so `center` holds there too.
        wave.bounds = bounds
        wave.position = center
        let inset = bounds.width * 0.24
        wave.path = UIBezierPath(ovalIn: bounds.insetBy(dx: inset, dy: inset)).cgPath
        CATransaction.commit()
        restartWave(force: true)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        restartWave(force: false)
    }

    private var ambientRipple: Bool { mode == .ambient && !reduceMotion }

    private func render(animated: Bool) {
        let ripple = ambientRipple
        CATransaction.begin()
        CATransaction.setAnimationDuration(animated ? 0.45 : 0)
        if !ripple {
            // Let the rings in flight fade out with the replicator, then stop them.
            CATransaction.setCompletionBlock { [weak self] in
                guard let self, !self.ambientRipple else { return }
                self.wave.removeAnimation(forKey: "wave")
            }
        }
        replicator.opacity = ripple ? 1 : 0
        CATransaction.commit()
        if ripple { restartWave(force: false) }
        renderVoice(animated: animated)
    }

    private func renderVoice(animated: Bool) {
        CATransaction.begin()
        switch mode {
        case .voice where reduceMotion:
            // Still steps, swapped without motion and only when the step changes.
            let step = CallVoiceLevelEstimator.step(level)
            guard step != shownStep else { CATransaction.commit(); return }
            shownStep = step
            CATransaction.setDisableActions(true)
            glow.opacity = step > 0 ? 0.8 : 0
            glow.transform = CATransform3DMakeScale(1.08, 1.08, 1)
            for (index, ring) in voiceRings.enumerated() {
                let scale = 1 + 0.16 * CGFloat(index + 1)
                ring.transform = CATransform3DMakeScale(scale, scale, 1)
                ring.opacity = index < step ? Float(0.5 - 0.12 * Double(index)) : 0
            }
        case .voice:
            CATransaction.setAnimationDuration(animated ? Double(CallVoiceLevelEstimator.pollIntervalNs) / 1_000_000_000 + 0.03 : 0)
            CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeOut))
            let l = CGFloat(level)
            glow.opacity = level > 0 ? Float(min(1, 0.35 + 0.65 * level)) : 0
            let glowScale = 1 + 0.22 * l
            glow.transform = CATransform3DMakeScale(glowScale, glowScale, 1)
            for (index, ring) in voiceRings.enumerated() {
                let scale = 1 + (0.14 + 0.16 * CGFloat(index)) * l
                ring.transform = CATransform3DMakeScale(scale, scale, 1)
                ring.opacity = Float(level * (0.6 - 0.17 * Double(index)))
            }
        case .ambient where reduceMotion:
            CATransaction.setDisableActions(true)
            glow.opacity = 0
            for (index, ring) in voiceRings.enumerated() {
                ring.transform = CATransform3DMakeScale(1.22, 1.22, 1)
                ring.opacity = index == 0 ? 0.25 : 0
            }
        case .ambient, .off:
            CATransaction.setAnimationDuration(animated ? 0.3 : 0)
            glow.opacity = 0
            for ring in voiceRings { ring.opacity = 0; ring.transform = CATransform3DIdentity }
        }
        CATransaction.commit()
    }

    /// Adds the ambient ripple only when it is missing (or the size changed):
    /// SwiftUI updates and layout passes no longer restart it from scratch.
    private func restartWave(force: Bool) {
        guard ambientRipple, window != nil, bounds.width > 0 else { return }
        guard force || wave.animation(forKey: "wave") == nil else { return }
        wave.removeAnimation(forKey: "wave")
        replicator.instanceDelay = Self.ambientPeriod / 3
        let scale = CABasicAnimation(keyPath: "transform.scale")
        scale.fromValue = 1.0
        scale.toValue = 1.55
        let fade = CAKeyframeAnimation(keyPath: "opacity")
        fade.values = [0, 0.6, 0]
        fade.keyTimes = [0, 0.15, 1]
        let group = CAAnimationGroup()
        group.animations = [scale, fade]
        group.duration = Self.ambientPeriod
        group.repeatCount = .infinity
        group.timingFunction = CAMediaTimingFunction(name: .easeOut)
        wave.add(group, forKey: "wave")
    }
}

// MARK: - Connection quality

/// Four signal bars in an 18×14 frame, monochrome like the rest of the call:
/// lit bars in the text colour, the others dim. Measuring shows four dim
/// bars; a shown drop sweeps a soft light across them (static under Reduce
/// Motion) instead of keeping the last grade.
struct CallQualityBars: View {
    let level: CallQualityIndicator.Level
    /// The call screen's capsule passes false: its words already say the
    /// drop, and nothing there may animate without end.
    var sweeps = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private static let sweepStep: TimeInterval = 0.4

    var body: some View {
        Group {
            if case .reconnecting = level, sweeps, !reduceMotion {
                TimelineView(.periodic(from: .now, by: Self.sweepStep)) { context in
                    bars(sweep: Int(context.date.timeIntervalSinceReferenceDate / Self.sweepStep) % 4)
                }
            } else {
                bars(sweep: nil)
            }
        }
        .frame(width: 18, height: 14, alignment: .bottom)
        .animation(.easeInOut(duration: 0.3), value: level)
        .accessibilityElement()
        .accessibilityLabel(Text("Connection quality"))
        .accessibilityValue(Text(LocalizedStringKey(Self.levelName(level))))
    }

    private func bars(sweep: Int?) -> some View {
        let lit: Int = { if case .graded(let grade) = level { return grade.bars }; return 0 }()
        return HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .fill(index < lit ? UnyriColors.primaryText : Color.white.opacity(index == sweep ? 0.5 : 0.22))
                    .frame(width: 3, height: 5 + CGFloat(index) * 3)
            }
        }
        .animation(.easeInOut(duration: Self.sweepStep), value: sweep)
    }

    static func levelName(_ level: CallQualityIndicator.Level) -> String {
        switch level {
        case .graded(.excellent): return "Excellent"
        case .graded(.good): return "Good"
        case .graded(.weak): return "Weak"
        case .graded(.poor): return "Poor"
        case .measuring: return "Waiting for data…"
        case .reconnecting(let waiting): return waiting ? "Waiting for network…" : "Reconnecting…"
        }
    }

    /// Relay-only calls ("Peer-to-peer calls") say why they go via the server.
    static let privateRelayName = "Via server · IP hidden"
}

/// The collapsed call banner: the bars and the timer, or the reconnecting
/// words after the same ~1.2 s as the call screen. Observes only the monitor,
/// so the app root never re-renders for the connection.
struct CallBannerStatus: View {
    @ObservedObject var monitor: CallQualityMonitor
    let connectedAt: Date

    var body: some View {
        let indicator = monitor.indicator
        HStack(spacing: 6) {
            CallQualityBars(level: indicator.level)
            if indicator.isReconnecting, let caption = indicator.caption {
                Text(UnyriL10n.string(caption))
            } else {
                TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                    Text(formatCallDuration(context.date.timeIntervalSince(connectedAt))).monospacedDigit()
                }
            }
        }
    }
}

// MARK: - Glass, press and the top bar

/// The call's one glass surface: Liquid Glass on iOS 26, a thin material with
/// a hairline before it, and a solid fill with a stronger hairline under
/// Reduce Transparency, Increase Contrast or Low Power Mode (no blur pass
/// over the video there). `glass: false` is the rest look for surfaces that
/// stay on screen for the whole call over the drifting aurora: a translucent
/// fill with a hairline, as the call's flat pills were, so nothing re-blurs
/// a moving backdrop at rest. The switch between the two crossfades once
/// (opacity only, so Reduce Motion keeps it).
struct CallGlassSurface<S: InsettableShape>: View {
    let shape: S
    var glass = true
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        surface
            .animation(.easeInOut(duration: 0.25), value: glass)
            .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
                let next = ProcessInfo.processInfo.isLowPowerModeEnabled
                if lowPower != next { lowPower = next }
            }
    }

    @ViewBuilder private var surface: some View {
        if reduceTransparency || contrast == .increased || lowPower {
            shape.fill(UnyriColors.callBackground)
                .overlay(shape.strokeBorder(UnyriColors.outlineSecondary, lineWidth: 1))
        } else if !glass {
            shape.fill(UnyriColors.glassFloating.opacity(0.75))
                .overlay(shape.strokeBorder(UnyriColors.outlineSubtle, lineWidth: 1))
        } else if #available(iOS 26.0, *) {
            shape.fill(.clear).glassEffect(.regular, in: shape)
        } else {
            shape.fill(.thinMaterial)
                .overlay(shape.strokeBorder(UnyriColors.outlineSubtle, lineWidth: 1))
        }
    }
}

/// `CallGlassSurface` applied to the content itself: on iOS 26 `.glassEffect`
/// sits on the icon or the capsule's row, never on an empty shape behind it
/// (inside the top bar's GlassEffectContainer a background glass hid the
/// arrow, the lock and the text, report 12 Nov). Glass on and off is
/// `.regular` and `.identity` on the same view, so the timer's TimelineView
/// never remounts. `visible: false` turns the glass off too: a hidden surface
/// leaves no empty glass behind its faded content. Flat, solid and
/// pre-iOS 26 looks as `CallGlassSurface`.
struct CallGlassBackground<S: InsettableShape>: ViewModifier {
    let shape: S
    let glass: Bool
    var visible = true
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    func body(content: Content) -> some View {
        let solid = reduceTransparency || contrast == .increased || lowPower
        let real = glass && !solid
        surfaced(content, real: real, solid: solid)
            .animation(.easeInOut(duration: 0.25), value: real)
            .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
                let next = ProcessInfo.processInfo.isLowPowerModeEnabled
                if lowPower != next { lowPower = next }
            }
    }

    @ViewBuilder private func surfaced(_ content: Content, real: Bool, solid: Bool) -> some View {
        if #available(iOS 26.0, *) {
            content
                .background { flat(solid: solid).opacity(visible && !real ? 1 : 0) }
                .glassEffect(visible && real ? .regular : .identity, in: shape)
        } else {
            // Only the background branches: the content keeps its identity.
            content.background {
                if real {
                    shape.fill(.thinMaterial)
                        .overlay(shape.strokeBorder(UnyriColors.outlineSubtle, lineWidth: 1))
                        .opacity(visible ? 1 : 0)
                } else {
                    flat(solid: solid).opacity(visible ? 1 : 0)
                }
            }
        }
    }

    @ViewBuilder private func flat(solid: Bool) -> some View {
        if solid {
            shape.fill(UnyriColors.callBackground)
                .overlay(shape.strokeBorder(UnyriColors.outlineSecondary, lineWidth: 1))
        } else {
            shape.fill(UnyriColors.glassFloating.opacity(0.75))
                .overlay(shape.strokeBorder(UnyriColors.outlineSubtle, lineWidth: 1))
        }
    }
}

/// Soft static shades over the peer's picture, so the clock and battery, the
/// top bar and the tray stay legible over bright video. Plain gradients drawn
/// once; the bottom one leaves with the chrome, the top one stays with the
/// status bar.
struct CallVideoScrims: View {
    let bottomVisible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [Color.black.opacity(0.38), Color.black.opacity(0.16), .clear],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: 150)
            Spacer(minLength: 0)
            LinearGradient(colors: [.clear, Color.black.opacity(0.16), Color.black.opacity(0.4)],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: 200)
                .opacity(bottomVisible ? 1 : 0)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: bottomVisible)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The flip on the self-view's corner (FaceTime): a 30 pt glass circle with a
/// 44 pt target. The caller shows it only while our camera runs and another
/// camera exists.
struct CallFlipCameraButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.triangle.2.circlepath.camera")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(UnyriColors.primaryText)
                .frame(width: 30, height: 30)
                .modifier(CallGlassBackground(shape: Circle(), glass: true))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(CallGlassPressStyle())
        .accessibilityLabel("Switch camera")
        .accessibilityIdentifier("call-flip")
    }
}

/// The torch beside the flip, the same size, only while a camera with a torch
/// runs (owner, 12 Nov): a bare bolt, no caption. Lit, it inverts (light
/// circle, dark bolt) so the state reads at a glance in a monochrome screen.
struct CallTorchButton: View {
    let isOn: Bool
    let action: () -> Void
    var accessibilityID = "call-torch"

    var body: some View {
        Button(action: action) {
            Image(systemName: "bolt.fill")
                .font(.system(size: 13, weight: .semibold))
                // The dark of the other inverted call controls.
                .foregroundColor(isOn ? UnyriColors.callBackground : UnyriColors.primaryText)
                .frame(width: 30, height: 30)
                .background(Circle().fill(UnyriColors.primaryText).opacity(isOn ? 1 : 0))
                .modifier(CallGlassBackground(shape: Circle(), glass: true, visible: !isOn))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(CallGlassPressStyle())
        // The value says the state; a selected trait as well read it twice
        // ("On, Selected").
        .accessibilityLabel("Flashlight")
        .accessibilityValue(isOn ? Text("On") : Text("Off"))
        .accessibilityIdentifier(accessibilityID)
    }
}

/// A soft squish for the call's glass controls: springs in, overshoots a
/// little on release. Opacity only under Reduce Motion, a short ease without
/// a spring in Low Power Mode. Animates only while pressed.
struct CallGlassPressStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.86

    func makeBody(configuration: Configuration) -> some View {
        CallPressBody(label: configuration.label, pressed: configuration.isPressed, pressedScale: pressedScale)
    }
}

private struct CallPressBody: View {
    let label: ButtonStyleConfiguration.Label
    let pressed: Bool
    let pressedScale: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        label
            .scaleEffect(pressed && !reduceMotion ? pressedScale : 1)
            .opacity(pressed && reduceMotion ? 0.6 : 1)
            .animation(animation, value: pressed)
    }

    private var animation: Animation {
        if reduceMotion { return .easeOut(duration: 0.1) }
        // Read at render: a press is rare enough not to need observing.
        if ProcessInfo.processInfo.isLowPowerModeEnabled { return .easeOut(duration: 0.12) }
        return pressed ? .spring(response: 0.18, dampingFraction: 0.7) : .spring(response: 0.32, dampingFraction: 0.52)
    }
}

/// iOS 26: the top bar's two surfaces in one glass pass instead of one
/// sampling pass each. The spacing stays below the bar's smallest gap
/// between the button and the capsule (8 pt, less the capsule's alert
/// pulse), so the two never merge. Always applied, never toggled: a
/// changing wrapper would reset the capsule's state and close its sheet.
/// Members apply their glass to their content (`CallGlassBackground`); a
/// glass placed in a `.background` inside this container hides what sits
/// on it.
struct CallTopBarGlassGroup: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 2) { content }
        } else {
            content
        }
    }
}

/// Collapses the call into the bar: a glass circle with converging corners
/// ("shrink to a window"), not a chevron that reads as "close".
struct CallMinimizeButton: View {
    /// Real glass only over the peer's live picture (`CallGlassBackground`).
    let glass: Bool
    /// Hidden with the chrome or before a call exists: the glass goes with
    /// the arrow.
    var visible = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.down.right.and.arrow.up.left")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(UnyriColors.primaryText)
                .opacity(visible ? 1 : 0)
                .frame(width: 44, height: 44)
                .modifier(CallGlassBackground(shape: Circle(), glass: glass, visible: visible))
                .contentShape(Circle())
        }
        .buttonStyle(CallGlassPressStyle())
        .accessibilityLabel("Minimize call")
        .accessibilityIdentifier("call-minimize")
    }
}

/// The one status capsule at the top of the call screen, in both layouts:
/// the lock, what the call is doing (with the timer once encrypted) and the
/// bars; a second line names the route or a weak or poor link. The width
/// springs between texts and the words crossfade; alerts pulse once. A tap
/// opens the encryption sheet. Observes only the quality monitor, and the
/// timer text ticks on its own outside any animation.
/// Owner, 14 Nov: "вместо напрямую или через сервер ... маленькие иконки: телефон-телефон,
/// а в другом случае сервер посередине". Static glyphs, no animation.
struct CallRouteGlyph: View {
    let route: CallStatusCapsulePolicy.RouteGlyph
    let privateRelay: Bool
    var body: some View {
        HStack(spacing: 2) {
            phone
            link
            if route == .relay {
                Image(systemName: "server.rack").font(.system(size: 9, weight: .semibold))
                link
            }
            phone
        }
        .foregroundColor(UnyriColors.secondaryText)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(LocalizedStringKey(route == .direct ? "Direct"
            : privateRelay ? "Via server · IP hidden" : "Via server")))
        .accessibilityIdentifier(route == .direct ? "call-route-direct" : "call-route-relay")
    }
    private var phone: some View { Image(systemName: "iphone").font(.system(size: 9, weight: .semibold)) }
    private var link: some View { Capsule().frame(width: 5, height: 1.5) }
}

/// Glass pill only for alerts; otherwise the words sit on the call with a soft shadow
/// for legibility over bright video.
struct CallStatusSurface: ViewModifier {
    let alert: Bool
    let glass: Bool
    let visible: Bool
    func body(content: Content) -> some View {
        if alert { content.modifier(CallGlassBackground(shape: Capsule(), glass: glass, visible: visible)) }
        else { content.shadow(color: .black.opacity(0.55), radius: 3, x: 0, y: 1) }
    }
}

struct CallStatusCapsule: View {
    let phase: CallStatusPhase?
    let ring: CallRingIndicator.Phase?
    let callId: String?
    /// `connectedAt`; it turns nil in the same pass the call ends.
    let start: Date?
    @ObservedObject var quality: CallQualityMonitor
    /// Read on demand by the sheet only.
    let verification: () -> CallVerificationTranscript?
    let chromeHidden: Bool
    /// Real glass only over the peer's live picture (`CallGlassBackground`).
    let glass: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lastStart: Date?
    @State private var endedDuration: Int?
    /// Read from the monitor on appear (it is main-actor state).
    @State private var pathSatisfied = true
    @State private var offline = false
    @State private var weakExpired = false
    @State private var pulse = false
    @State private var showingDetails = false
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    private static let titleFont = Font.system(size: 13, weight: .semibold)

    var body: some View {
        let indicator = quality.indicator
        let caption = indicator.caption
        let kind = CallStatusCapsulePolicy.kind(phase: phase, ring: ring,
            reconnectingShown: indicator.isReconnecting,
            waitingForNetwork: indicator.level == .reconnecting(waitingForNetwork: true),
            offline: offline, endedDuration: endedDuration)
        let subtitle = CallStatusCapsulePolicy.subtitleKey(kind: kind, routeIsRelay: indicator.route?.isRelay,
            privateRelay: indicator.privateRelay, caption: caption, weakExpired: weakExpired)
        // Alerts stay on screen with the video chrome hidden.
        let visible = kind != .hidden && !(chromeHidden && !kind.isAlert)
        let ended = phase?.isEnded ?? false
        let resize: Animation = reduceMotion ? .easeInOut(duration: 0.2)
            : lowPower ? .easeOut(duration: 0.2) : .spring(response: 0.34, dampingFraction: 0.86)
        Button {
            UnyriHaptics.press()
            showingDetails = true
        } label: {
            capsule(kind, subtitle: subtitle, level: indicator.level, visible: visible,
                    route: CallStatusCapsulePolicy.routeGlyph(kind: kind, routeIsRelay: indicator.route?.isRelay),
                    privateRelay: indicator.privateRelay)
        }
        .buttonStyle(CallGlassPressStyle(pressedScale: 0.96))
        .opacity(visible ? 1 : 0)
        // The title crossfades inside this transaction while the width springs.
        .animation(resize, value: kind)
        .animation(resize, value: subtitle)
        // Nothing left to verify once the call has ended (the press style
        // draws no disabled look, so the ended capsule reads as before).
        .disabled(ended)
        .allowsHitTesting(visible)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text("Shows how the call is protected"))
        .accessibilityIdentifier("call-status")
        .accessibilityShowsLargeContentViewer()
        .accessibilityHidden(!visible)
        .onAppear {
            lastStart = start ?? lastStart
            pathSatisfied = CallNetworkMonitor.shared.current.satisfied
        }
        .onChange(of: start) { value in if let value { lastStart = value } }
        .onChange(of: ended) { ended in
            // Frozen here: `start` is already nil in the pass that ends the call.
            endedDuration = ended ? lastStart.flatMap { Int(exactly: max(0, Date().timeIntervalSince($0)).rounded(.towardZero)) } : nil
        }
        .onChange(of: callId) { [callId] _ in
            lastStart = start
            endedDuration = nil
            // The instant "starting" screen turning into the real call is the
            // same call: an open sheet stays (it reads the new id).
            if callId != CallScreen.startingMinimizeKey { showingDetails = false }
        }
        .onReceive(CallNetworkMonitor.shared.paths) { path in
            if path.satisfied != pathSatisfied { pathSatisfied = path.satisfied }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
            let next = ProcessInfo.processInfo.isLowPowerModeEnabled
            if lowPower != next { lowPower = next }
        }
        .task(id: pathSatisfied) {
            // One-shot hold: a short loss, and the monitor's unknown start
            // value, never flash "Waiting for network…" before media runs.
            offline = false
            guard !pathSatisfied else { return }
            do { try await Task.sleep(nanoseconds: UInt64(CallQualityPresentation.reconnectingAfter * 1_000_000_000)) } catch { return }
            offline = true
        }
        .task(id: caption) {
            // Weak is said for a few seconds, then the route returns; poor stays.
            weakExpired = false
            guard caption == "Weak connection" else { return }
            do { try await Task.sleep(nanoseconds: UInt64(CallQualityPresentation.weakCaptionFor * 1_000_000_000)) } catch { return }
            weakExpired = true
        }
        .task(id: kind.isAlert ? kind.titleKey : nil) {
            // One pulse per alert change, never a loop.
            guard kind.isAlert else { pulse = false; return }
            withAnimation(.easeOut(duration: 0.16)) { pulse = true }
            do { try await Task.sleep(nanoseconds: 160_000_000) } catch { pulse = false; return }
            withAnimation(.easeOut(duration: 0.44)) { pulse = false }
        }
        .sheet(isPresented: $showingDetails) {
            CallEncryptionSheet(callId: callId, quality: quality, details: quality.details, verification: verification)
        }
    }

    /// 32 pt for one line and 44 pt for two, plus 6 pt above and below so the
    /// touch target is 44 pt or more. The pulse lies under the words, inside
    /// the surface, so the glass never covers it.
    private func capsule(_ kind: CallStatusCapsuleKind, subtitle: String?, level: CallQualityIndicator.Level,
                         visible: Bool, route: CallStatusCapsulePolicy.RouteGlyph?, privateRelay: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(UnyriColors.primaryText)
                .opacity(kind.isSecured ? 1 : 0.55)
                .accessibilityHidden(true)
            VStack(spacing: 1) {
                HStack(spacing: 0) {
                    Text(LocalizedStringKey(kind.titleKey)).contentTransition(.opacity)
                    if kind.showsTimer, let start {
                        Text(verbatim: " · ")
                        // Hidden with the chrome: no tick behind an invisible capsule.
                        CallDurationText(start: start, font: Self.titleFont.monospacedDigit(), paused: !visible)
                    }
                    if case .ended(_, let duration?) = kind {
                        Text(verbatim: " · " + formatCallDuration(TimeInterval(duration))).monospacedDigit()
                    }
                }
                .font(Self.titleFont)
                .foregroundColor(UnyriColors.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                if let subtitle {
                    Text(LocalizedStringKey(subtitle))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(UnyriColors.secondaryText)
                        .lineLimit(1)
                        .transition(.opacity)
                }
            }
            if let route { CallRouteGlyph(route: route, privateRelay: privateRelay).transition(.opacity) }
            if CallStatusCapsulePolicy.showsBars(kind: kind, linkHasProblem: level.hasProblem) {
                CallQualityBars(level: level, sweeps: false).transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, subtitle == nil ? 8 : 6)
        .background(Capsule().fill(Color.white.opacity(pulse ? 0.14 : 0)).allowsHitTesting(false))
        // Owner, 14 Nov ("эта часть не очень, сделать чтобы не мешать"): a plain line over
        // the call; the glass pill appears only for alerts so they still stand out.
        .modifier(CallStatusSurface(alert: kind.isAlert, glass: glass, visible: visible))
        .scaleEffect(pulse && !reduceMotion && !lowPower ? 1.05 : 1)
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

/// The timer: `TimelineView` re-renders only this text once a second, and
/// only while it can be seen. `paused` (a hidden capsule) leaves a still text
/// and no schedule; showing again starts the timeline at the current second.
/// The two swap at once, never crossfade: the chrome toggles inside an
/// animation, and two copies a second apart would show through each other
/// while the capsule around them fades anyway.
struct CallDurationText: View {
    let start: Date?
    var font: Font = UnyriTypography.callDuration
    var paused = false
    var body: some View {
        ZStack {
            // Reserves the width of the longest common value.
            Text(verbatim: "00:00").font(font).hidden()
            if let start {
                if paused {
                    duration(at: Date(), since: start)
                } else {
                    TimelineView(.periodic(from: start, by: 1)) { context in
                        duration(at: context.date, since: start)
                    }
                }
            }
        }
        .animation(nil, value: paused)
    }

    private func duration(at date: Date, since start: Date) -> some View {
        Text(formatCallDuration(date.timeIntervalSince(start)))
            .font(font)
            .foregroundColor(UnyriColors.primaryText)
            .lineLimit(1)
    }
}

// MARK: - Encryption sheet

private struct CallStageSizeKey: EnvironmentKey { static let defaultValue = CGSize.zero }
extension EnvironmentValues {
    var callStageSize: CGSize {
        get { self[CallStageSizeKey.self] }
        set { self[CallStageSizeKey.self] = newValue }
    }
}

/// What the status capsule opens: how the call is protected, the four-symbol
/// code both people compare, the route and quality, and the QA statistics
/// folded away. The code card exists only once the commitment exchange has
/// verified; without it there is no card, never a code that only looks
/// safe. No timers: the code is looked up on appear and on each metrics
/// update (about 1 Hz while connected) until found, then never again,
/// because the transcript stays fixed for the peer connection (ICE restarts
/// included).
struct CallEncryptionSheet: View {
    let callId: String?
    @ObservedObject var quality: CallQualityMonitor
    @ObservedObject var details: CallQualityDetails
    let verification: () -> CallVerificationTranscript?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.callStageSize) private var stageSize
    @State private var code: [Int]?
    @State private var comparedCode = false
    @State private var showsDetails = false
    #if DEBUG
    private static var symbolsChecked = false
    #endif

    var body: some View {
        let metrics = details.metrics
        let level = quality.indicator.level
        ScrollView {
            if CallLayoutPolicy.encryptionColumns(availableWidth: stageSize.width, availableHeight: stageSize.height) == 2 {
                sheetContent(metrics, level: level)
                    .frame(maxWidth: 900)
                    .frame(maxWidth: .infinity)
            } else {
                // Preserve the original portrait ScrollView -> padded VStack.
                sheetContent(metrics, level: level)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .modifier(CallSheetBackground())
        .preferredColorScheme(.dark)
        .accessibilityIdentifier("call-encryption-sheet")
        .onAppear {
            refresh(animated: false)
            Self.checkSymbols()
        }
        .onReceive(details.$metrics) { _ in if code == nil { refresh(animated: false) } }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOCallVerificationChanged"))) { note in
            if note.object == nil || note.object as? String == callId { refresh(animated: false) }
        }
        .onChange(of: callId) { _ in
            code = nil; comparedCode = false
            refresh(animated: false)
        }
    }

    private func sheetContent(_ metrics: CallQualityMetrics, level: CallQualityIndicator.Level) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            header
            Text(LocalizedStringKey(comparedCode ? "You confirmed that the verification codes match. Recompare after any code change."
                 : "Compare the verification code with the other person through a trusted channel. Until it matches, this call's endpoints are not verified and the server may intercept audio or video."))
                .font(.subheadline).foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            if code == nil {
                Text("Verification code unavailable. This peer may need an update. Call endpoints are not verified.")
                    .font(.footnote).foregroundColor(UnyriColors.danger)
            }
            // A portrait medium detent can itself be wider than tall;
            // use the measured call stage, not the detent's shape.
            if CallLayoutPolicy.encryptionColumns(availableWidth: stageSize.width, availableHeight: stageSize.height) == 2 {
                HStack(alignment: .top, spacing: 20) {
                    if let code { codeCard(code).transition(.opacity) }
                    VStack(spacing: 20) {
                        connectionCard(metrics, level: level)
                        statistics(metrics)
                    }
                }
            } else {
                if let code { codeCard(code).transition(.opacity) }
                connectionCard(metrics, level: level)
                statistics(metrics)
            }
        }
        .padding(20)
    }

    private func refresh(animated: Bool) {
        let next = verification().flatMap(CallVerificationCode.indices)
        guard next != code else { return }
        comparedCode = false
        // Opacity only, so it needs no Reduce Motion variant; scoped to the
        // card, so the stats rows updating in the same pass never animate.
        if animated { withAnimation(.easeInOut(duration: 0.25)) { code = next } } else { code = next }
    }

    /// Every code symbol must exist on the oldest supported iOS.
    private static func checkSymbols() {
        #if DEBUG
        guard !symbolsChecked else { return }
        symbolsChecked = true
        for entry in CallVerificationCode.symbols {
            assert(UIImage(systemName: entry.symbol) != nil, "Missing SF Symbol \(entry.symbol)")
        }
        #endif
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(UnyriColors.primaryText)
                .frame(width: 44, height: 44)
                .background(CallGlassSurface(shape: Circle()))
                .accessibilityHidden(true)
            Text("End-to-end encryption")
                .font(.title3.weight(.semibold))
                .foregroundColor(UnyriColors.primaryText)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(UnyriColors.primaryText)
                    .frame(width: 32, height: 32)
                    .background(CallGlassSurface(shape: Circle()))
                    .padding(6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(CallGlassPressStyle())
            .accessibilityLabel("Close")
        }
    }

    private func codeCard(_ code: [Int]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Verification code")
                .font(.footnote.weight(.semibold))
                .foregroundColor(UnyriColors.secondaryText)
            HStack(spacing: 10) {
                ForEach(Array(code.enumerated()), id: \.offset) { _, index in codeTile(index) }
            }
            Button("I compared the codes — they match") { comparedCode = true }.disabled(comparedCode)
            Text("Compare these symbols with the other person through a trusted channel. A mismatch can mean interception. End the call if they differ.")
                .font(.footnote)
                .foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CallGlassSurface(shape: RoundedRectangle(cornerRadius: 20, style: .continuous)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("call-verification-code")
    }

    /// The glyph over its name.
    private func codeTile(_ index: Int) -> some View {
        // Six-bit indices into the 64 entries (contract-tested).
        let entry = CallVerificationCode.symbols[index & 63]
        return VStack(spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.08))
                Image(systemName: entry.symbol)
                    .font(.system(size: 28, weight: .medium))
                    .foregroundColor(UnyriColors.primaryText)
            }
            .frame(maxWidth: 64)
            .frame(height: 56)
            Text(LocalizedStringKey(entry.nameKey))
                .font(.caption2)
                .foregroundColor(UnyriColors.secondaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(LocalizedStringKey(entry.nameKey)))
    }

    private func connectionCard(_ m: CallQualityMetrics, level: CallQualityIndicator.Level) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            infoRow("Route") { Text(verbatim: routeSummary(m)) }
            infoRow("Connection quality") {
                HStack(spacing: 6) {
                    CallQualityBars(level: level, sweeps: false).accessibilityHidden(true)
                    Text(LocalizedStringKey(CallQualityBars.levelName(level)))
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CallGlassSurface(shape: RoundedRectangle(cornerRadius: 20, style: .continuous)))
    }

    private func infoRow<Value: View>(_ title: String, @ViewBuilder value: () -> Value) -> some View {
        HStack(spacing: 12) {
            Text(LocalizedStringKey(title)).foregroundColor(UnyriColors.secondaryText)
            Spacer(minLength: 12)
            value().foregroundColor(UnyriColors.primaryText).multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
        .accessibilityElement(children: .combine)
    }

    /// In words: "Direct · UDP", "Via server · TLS", "Via server · IP hidden · UDP".
    private func routeSummary(_ m: CallQualityMetrics) -> String {
        guard let route = m.route ?? quality.indicator.route else { return "—" }
        switch route {
        case .direct:
            return ([UnyriL10n.string("Direct")] + (m.localProtocol.map { [$0.uppercased()] } ?? [])).joined(separator: " · ")
        case .relay(let transport):
            return UnyriL10n.string(quality.indicator.privateRelay ? CallQualityBars.privateRelayName : "Via server")
                + (transport.map { " · " + $0.uppercased() } ?? "")
        }
    }

    private func statistics(_ m: CallQualityMetrics) -> some View {
        DisclosureGroup(isExpanded: $showsDetails) {
            VStack(spacing: 10) {
                row("Route", technicalRoute(m))
                row("Audio codec", m.audioCodec)
                row("Video codec in / out", m.videoInCodec == nil && m.videoOutCodec == nil
                    ? nil : "\(m.videoInCodec ?? "—") / \(m.videoOutCodec ?? "—")")
                row("Incoming video", video(m.videoInResolution, m.videoInFps))
                row("Outgoing video", video(m.videoOutResolution, m.videoOutFps))
                row("Camera", video(m.cameraResolution, m.cameraFps))
                row("Encoder QP", m.videoOutQp.flatMap { value in Int(exactly: value.rounded()).map { "\($0)" } })
                row("Encode time", m.videoEncodeMs.map { String(format: "%.1f ms", $0) })
                row("Pacer delay", m.videoPacerMs.map { String(format: "%.1f ms", $0) })
                row("Bitrate in / out", pair(m.incomingKbps, m.outgoingKbps, unit: "kbps"))
                row("Available upload", m.availableOutgoingKbps.flatMap { value in Int(exactly: value.rounded()).map { "\($0) kbps" } })
                row("Round trip", m.rttMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("Round trip STUN / RTCP", pair(m.stunRttMs, m.rtcpRttMs, unit: "ms"))
                row("Packet loss in / out", pair(m.incomingLoss.map { $0 * 100 }, m.outgoingLoss.map { $0 * 100 }, unit: "%", decimals: 1))
                row("Jitter", m.jitterMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("Audio playout delay", m.audioPlayoutDelayMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("Video playout delay", m.videoPlayoutDelayMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("A/V sync delay", m.avSyncDelayMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("Audio packet time", m.audioPacketTimeMs.flatMap { value in Int(exactly: value.rounded()).map { "\($0) ms" } })
                row("Concealed audio", m.concealment.map { String(format: "%.1f %%", $0 * 100) })
                row("Quality limited by", m.qualityLimitationReason)
            }
            .padding(.top, 12)
        } label: {
            Text("Call statistics")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(UnyriColors.primaryText)
        }
        .tint(UnyriColors.secondaryText)
        .padding(16)
        .background(CallGlassSurface(shape: RoundedRectangle(cornerRadius: 20, style: .continuous)))
    }

    private func row(_ title: String, _ value: String?) -> some View {
        HStack {
            Text(LocalizedStringKey(title)).foregroundColor(UnyriColors.secondaryText)
            Spacer(minLength: 12)
            Text(value ?? "—").monospacedDigit().foregroundColor(UnyriColors.primaryText).multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }

    /// Technical tokens, not words: the candidate types and our interface
    /// (Direct · UDP · host↔srflx · wifi).
    private func technicalRoute(_ m: CallQualityMetrics) -> String? {
        guard let route = m.route else { return nil }
        switch route {
        case .direct:
            var parts = [UnyriL10n.string("Direct")]
            if let transport = m.localProtocol { parts.append(transport.uppercased()) }
            if let local = m.localCandidateType, let remote = m.remoteCandidateType { parts.append("\(local)↔\(remote)") }
            if let network = m.localNetworkType { parts.append(network) }
            return parts.joined(separator: " · ")
        case .relay(let transport):
            return UnyriL10n.string(quality.indicator.privateRelay ? CallQualityBars.privateRelayName : "Via server")
                + (transport.map { " · " + $0.uppercased() } ?? "")
        }
    }

    private func video(_ resolution: String?, _ fps: Double?) -> String? {
        guard resolution != nil || fps != nil else { return nil }
        return [resolution, fps.flatMap { value in Int(exactly: value.rounded()).map { "\($0) fps" } }].compactMap { $0 }.joined(separator: " · ")
    }

    private func pair(_ a: Double?, _ b: Double?, unit: String, decimals: Int = 0) -> String? {
        guard a != nil || b != nil else { return nil }
        func text(_ v: Double?) -> String { v.map { String(format: "%.\(decimals)f", $0) } ?? "—" }
        return "\(text(a)) / \(text(b)) \(unit)"
    }
}

/// The sheet's background: the system Liquid Glass sheet on iOS 26 (solid
/// under Reduce Transparency), a thin material with rounder corners on
/// iOS 16.4-18, the default before that.
private struct CallSheetBackground: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if reduceTransparency {
                content.presentationBackground(UnyriColors.callBackground)
            } else {
                content
            }
        } else if #available(iOS 16.4, *) {
            content
                .presentationBackground(reduceTransparency ? AnyShapeStyle(UnyriColors.callBackground) : AnyShapeStyle(Material.ultraThinMaterial))
                .presentationCornerRadius(28)
        } else {
            content
        }
    }
}

// MARK: - Toast

/// Notices float over the layout and clear themselves (the reducer drops
/// them after 3 s), instead of being inserted into the stack for good.
struct CallNoticeToast: View {
    let notice: CallNotice?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if let notice {
                Text(LocalizedStringKey(Self.text(notice)))
                    .font(UnyriTypography.caption)
                    .foregroundColor(UnyriColors.primaryText)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Capsule().fill(UnyriColors.glassSecondary))
                    .overlay(Capsule().stroke(UnyriColors.outlineSubtle, lineWidth: 1))
                    .transition(.opacity)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: notice)
        .allowsHitTesting(false)
    }

    static func text(_ notice: CallNotice) -> String {
        switch notice {
        case .videoRequestDeclined: return "Video request declined"
        case .videoRequestTimeout: return "Video request timed out"
        case .videoFailed: return "Video unavailable"
        case .cameraDenied: return "Camera permission denied"
        case .cameraFailed: return "Camera unavailable"
        case .videoPausedWeakConnection: return "Video paused: weak connection"
        }
    }
}

/// The message banner floats over the call; it never takes layout height.
struct CallMessageBannerOverlay: View {
    let controller: ForegroundMessageBannerController
    let open: (String) -> Void
    var body: some View {
        ForegroundMessageBannerHost(controller: controller) { banner in
            controller.dismiss()
            if let owner = banner.ownerID {
                UnyriAccountManager.shared.openFromNotification(owner: owner, chat: banner.chatId, message: banner.messageID ?? "")
            } else { open(banner.chatId) }
        }
    }
}

// MARK: - Audio output button

/// The output control. Without accessories a tap toggles receiver and
/// speaker; with Bluetooth or wired headphones a tap opens a menu of every
/// available route (iPhone receiver only on iPhone). Routes are read from
/// AVAudioSession when the menu opens, never while rendering. White only
/// where the sound is unusual for the call type (`CallAudioOutputPolicy`):
/// the speaker in an audio call, the receiver in a video call; headphones
/// stay dark with their own icon.
struct CallAudioRouteButton: UIViewRepresentable {
    /// Nil until the engine reports a route: the call type's own is shown.
    let route: AudioRouteKind?
    let video: Bool
    let size: CGFloat
    let enabled: Bool
    let routes: () -> [AudioRoute]
    let current: () -> AudioRoute?
    let select: (AudioRoute) -> Void

    func makeUIView(context: Context) -> CallAudioRouteControl { CallAudioRouteControl(frame: .zero) }

    func updateUIView(_ view: CallAudioRouteControl, context: Context) {
        view.configure(route: route, video: video, size: size, enabled: enabled,
                       routes: routes, current: current, select: select)
    }
}

final class CallAudioRouteControl: UIButton {
    private var kind: AudioRouteKind?
    private var video = false
    private var symbolSize: CGFloat = 0
    private var hasAccessory = false
    private var bluetoothName: String?
    private var routes: () -> [AudioRoute] = { [] }
    private var current: () -> AudioRoute? = { nil }
    private var select: (AudioRoute) -> Void = { _ in }
    private var observer: NSObjectProtocol?

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.borderWidth = 1
        layer.cornerCurve = .circular
        clipsToBounds = true
        accessibilityIdentifier = "call-output"
        addAction(UIAction { [weak self] _ in self?.toggle() }, for: .primaryActionTriggered)
        menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] completion in
            completion(self?.menuItems() ?? [])
        }])
        observer = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refreshAccessories() }
        }
        refreshAccessories()
    }

    required init?(coder: NSCoder) { nil }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.width / 2
    }

    override var isHighlighted: Bool {
        didSet { alpha = isEnabled ? (isHighlighted ? 0.7 : 1) : 0.45 }
    }

    override var intrinsicContentSize: CGSize { CGSize(width: symbolSize / 0.38, height: symbolSize / 0.38) }

    /// Only an iPhone has a receiver.
    private static var hasReceiver: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    func configure(route: AudioRouteKind?, video: Bool, size: CGFloat, enabled: Bool,
                   routes: @escaping () -> [AudioRoute], current: @escaping () -> AudioRoute?,
                   select: @escaping (AudioRoute) -> Void) {
        self.routes = routes
        self.current = current
        self.select = select
        if isEnabled != enabled { isEnabled = enabled; alpha = enabled ? 1 : 0.45 }
        let pointSize = size * 0.38
        let route = route ?? CallAudioOutputPolicy.assumed(video: video, hasReceiver: Self.hasReceiver)
        guard route != kind || video != self.video || pointSize != symbolSize else { return }
        kind = route
        self.video = video
        symbolSize = pointSize
        applyAppearance()
    }

    private func applyAppearance() {
        let route = kind ?? CallAudioOutputPolicy.assumed(video: video, hasReceiver: Self.hasReceiver)
        let highlighted = CallAudioOutputPolicy.highlighted(route, video: video, hasReceiver: Self.hasReceiver)
        backgroundColor = highlighted ? .white : UIColor.white.withAlphaComponent(0.18)
        let glyph = highlighted ? UIColor(UnyriColors.callBackground) : UIColor.white
        layer.borderColor = (highlighted ? UIColor(UnyriColors.outlineActive) : UIColor(UnyriColors.outlineSecondary)).cgColor
        let config = UIImage.SymbolConfiguration(pointSize: symbolSize, weight: .medium)
        // Without accessories this is the speaker toggle (FaceTime/Telegram):
        // the speaker glyph, slashed when a video call turns it off. With
        // accessories it shows the route.
        let symbol: String
        if hasAccessory || route != .earpiece {
            symbol = Self.symbol(route, name: bluetoothName)
        } else {
            symbol = video ? "speaker.slash.fill" : "speaker.wave.3.fill"
        }
        let image = UIImage(systemName: symbol, withConfiguration: config)
        setImage(image?.withTintColor(glyph, renderingMode: .alwaysOriginal), for: .normal)
        if hasAccessory {
            accessibilityLabel = UnyriL10n.string("Audio output")
            accessibilityValue = Self.title(route, label: bluetoothName ?? "")
        } else {
            accessibilityLabel = UnyriL10n.string("Speaker")
            accessibilityValue = UnyriL10n.string(route == .speaker ? "On" : "Off")
        }
        accessibilityTraits = .button
        invalidateIntrinsicContentSize()
    }

    /// Accessory presence decides tap-to-toggle versus tap-for-menu; it is
    /// re-read only when iOS reports a route change.
    private func refreshAccessories() {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs
        bluetoothName = outputs.first { [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE].contains($0.portType) }?.portName
        let accessoryInput = (session.availableInputs ?? []).contains { $0.portType == .bluetoothHFP || $0.portType == .headsetMic }
        let accessoryOutput = outputs.contains { [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE, .headphones].contains($0.portType) }
        hasAccessory = accessoryInput || accessoryOutput || !Self.hasReceiver
        showsMenuAsPrimaryAction = hasAccessory
        if kind != nil { applyAppearance() }
    }

    private func toggle() {
        guard !hasAccessory else { return }
        let available = routes()
        let target = kind == .speaker ? available.first { $0.kind == .earpiece } : available.first { $0.kind == .speaker }
        if let target { select(target) }
    }

    private func menuItems() -> [UIMenuElement] {
        let now = current()
        return routes().map { route in
            // By port uid: two headsets with the same name are two routes.
            let isCurrent = now.map { $0.isSameOutput(as: route) } ?? false
            return UIAction(title: Self.title(route.kind, label: route.label),
                            image: UIImage(systemName: Self.symbol(route.kind, name: route.label)),
                            state: isCurrent ? .on : .off) { [weak self] _ in self?.select(route) }
        }
    }

    static func title(_ kind: AudioRouteKind, label: String) -> String {
        switch kind {
        case .earpiece: return "iPhone"
        case .speaker: return UnyriL10n.string("Speaker")
        case .bluetooth: return label.isEmpty || label == "Bluetooth" ? "Bluetooth" : label
        case .wiredHeadset: return label.isEmpty || label == "Headphones" ? UnyriL10n.string("Headphones") : label
        }
    }

    static func symbol(_ kind: AudioRouteKind, name: String?) -> String {
        switch kind {
        case .earpiece: return "iphone"
        case .speaker: return "speaker.wave.3.fill"
        case .wiredHeadset: return "headphones"
        case .bluetooth:
            let name = name ?? ""
            if name.localizedCaseInsensitiveContains("AirPods Max") { return "airpodsmax" }
            if name.localizedCaseInsensitiveContains("AirPods Pro") { return "airpodspro" }
            if name.localizedCaseInsensitiveContains("AirPods") { return "airpods" }
            return "headphones"
        }
    }
}

// MARK: - Avatar

/// Resolved avatar URLs by user id, so a recreated avatar shows the photo in
/// its first frame instead of flashing initials while it resolves again.
final class CallPeerAvatarCache: @unchecked Sendable {
    static let shared = CallPeerAvatarCache()
    /// About one call: resolved once per call, yet a signed URL never outlives it.
    static let lifetime: TimeInterval = 10 * 60
    private let lock = NSLock()
    private var entries: [String: (url: URL?, resolvedAt: Date)] = [:]

    func url(_ userId: String) -> URL? {
        lock.lock(); defer { lock.unlock() }
        return entries[userId]?.url
    }

    func needsRefresh(_ userId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let entry = entries[userId] else { return true }
        return Date().timeIntervalSince(entry.resolvedAt) > Self.lifetime
    }

    func set(_ url: URL?, for userId: String) {
        lock.lock(); defer { lock.unlock() }
        entries[userId] = (url, Date())
    }
}

/// Keeps the scroll view still when the call layout already fits.
struct CallScrollBounce: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.4, *) {
            content.scrollBounceBehavior(.basedOnSize)
        } else {
            content
        }
    }
}
