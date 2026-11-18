import SwiftUI
import ImageIO

@MainActor
private enum AvatarImages {
    static var generation: UInt64 = 0
    static let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = 16 * 1024 * 1024
        return cache
    }()
}

/// Reusable avatar: shows a remote image when a resolved URL is available,
/// otherwise a violet circle with the first letter of the name. Never shows a
/// broken-image placeholder.
struct UserAvatar: View {
    static func clearDecodedCache() {
        AvatarImages.generation &+= 1
        AvatarImages.cache.removeAllObjects()
    }
    let url: URL?
    let name: String
    var size: CGFloat = 44
    var vividFallback = false
    @State private var loadedImage: UIImage?
    @State private var loadedURL: URL?

    var body: some View {
        Group {
            if let url, let image = AvatarImages.cache.object(forKey: url as NSURL) ?? (loadedURL == url ? loadedImage : nil) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                initials
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .task(id: url) {
            loadedImage = nil
            loadedURL = nil
            guard let url else { return }
            if let image = AvatarImages.cache.object(forKey: url as NSURL) { loadedURL = url; loadedImage = image; return }
            let generation = AvatarImages.generation
            let decodeTask = Task.detached(priority: .utility) { () -> UIImage? in
                let data: Data
                if url.isFileURL {
                    guard let bytes = try? Data(contentsOf: url) else { return nil }
                    data = bytes
                } else {
                    guard let (bytes, response) = try? await URLSession.shared.unyriBoundedData(for: URLRequest(url: url), maximumBytes: 3 * 1024 * 1024),
                          let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
                    data = bytes
                }
                guard data.count <= 3 * 1024 * 1024,
                      let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 512,
                        kCGImageSourceShouldCacheImmediately: true
                      ] as CFDictionary) else { return nil }
                return UIImage(cgImage: thumbnail)
            }
            let image = await withTaskCancellationHandler(operation: { await decodeTask.value }, onCancel: { decodeTask.cancel() })
            guard !Task.isCancelled, generation == AvatarImages.generation, let image else { return }
            AvatarImages.cache.setObject(image, forKey: url as NSURL, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            loadedURL = url
            loadedImage = image
        }
    }

    private var initials: some View {
        ZStack {
            if vividFallback {
                Circle().fill(LinearGradient(colors: [Color(red: 0.714, green: 0.298, blue: 0.867), Color(red: 0.396, green: 0.22, blue: 0.725)], startPoint: .topLeading, endPoint: .bottomTrailing))
            } else { Circle().fill(UnyriColors.accent.opacity(0.14)) }
            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundColor(vividFallback ? .white : UnyriColors.accent)
        }
    }
}

// MARK: - Presence dot

/// What the client knows about a peer: no snapshot yet (or a reconnect cleared it),
/// an explicit offline, or online. Only an observed offline -> online is news.
enum UnyriPresenceState: Equatable {
    case unknown, offline, online
    init(_ observation: PresenceObservation?) {
        self = observation.map { $0.item.online ? .online : .offline } ?? .unknown
    }
}

private enum UnyriPresenceMotion {
    static let appear = Animation.spring(response: 0.34, dampingFraction: 0.6)
    static let appearCalm = Animation.easeOut(duration: 0.2)
    static let disappear = Animation.easeIn(duration: 0.2)
    static let rippleScale: CGFloat = 2.8
    static let rippleLifetime = 0.75
}

/// The online dot. It springs in with one ring ripple only when a peer is seen going
/// from offline to online; a first snapshot, a row scrolled into view, a reconnect or a
/// lapsed lease (anything involving `.unknown`) just shows the current state.
struct UnyriPresenceDot: View {
    let state: UnyriPresenceState
    var diameter: CGFloat = 10
    let color: Color
    let cutout: Color
    var cutoutWidth: CGFloat = 2
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var last: UnyriPresenceState
    @State private var shown: Bool
    @State private var ripple: UUID?

    init(state: UnyriPresenceState, diameter: CGFloat = 10, color: Color, cutout: Color, cutoutWidth: CGFloat = 2) {
        self.state = state
        self.diameter = diameter
        self.color = color
        self.cutout = cutout
        self.cutoutWidth = cutoutWidth
        _last = State(initialValue: state)
        _shown = State(initialValue: state == .online)
    }

    private var calm: Bool { reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled }

    var body: some View {
        ZStack {
            if let ripple {
                // Overflows the dot's frame without taking layout room.
                UnyriPresenceRipple(diameter: diameter, color: UIColor(color))
                    .frame(width: diameter * UnyriPresenceMotion.rippleScale, height: diameter * UnyriPresenceMotion.rippleScale)
                    .id(ripple)
                    .allowsHitTesting(false)
            }
            if shown {
                Circle().fill(color).frame(width: diameter, height: diameter)
                    .overlay(Circle().stroke(cutout, lineWidth: cutoutWidth))
                    .transition(calm ? .opacity : .scale(scale: 0.3).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .frame(width: diameter, height: diameter)
        .onChange(of: state) { next in apply(next) }
    }

    private func apply(_ next: UnyriPresenceState) {
        let previous = last
        last = next
        switch (previous, next) {
        case (.offline, .online):
            let quiet = calm
            withAnimation(quiet ? UnyriPresenceMotion.appearCalm : UnyriPresenceMotion.appear) { shown = true }
            guard !quiet else { return }
            let id = UUID()
            ripple = id
            DispatchQueue.main.asyncAfter(deadline: .now() + UnyriPresenceMotion.rippleLifetime) {
                if ripple == id { ripple = nil }
            }
        case (.online, .offline):
            withAnimation(UnyriPresenceMotion.disappear) { shown = false }
        default:
            // Unknown on either side is not an observed change: never animate it.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { shown = next == .online; ripple = nil }
        }
    }
}

/// One expanding ring on the render server: plays once when it enters the window and
/// removes its layer when done. No main-thread work per frame.
private struct UnyriPresenceRipple: UIViewRepresentable {
    let diameter: CGFloat
    let color: UIColor
    func makeUIView(context: Context) -> RippleView { RippleView(diameter: diameter, color: color) }
    func updateUIView(_ view: RippleView, context: Context) {}

    final class RippleView: UIView, CAAnimationDelegate {
        private let ring = CAShapeLayer()
        private let diameter: CGFloat
        private var played = false

        init(diameter: CGFloat, color: UIColor) {
            self.diameter = diameter
            super.init(frame: .zero)
            isUserInteractionEnabled = false
            ring.strokeColor = color.cgColor
            ring.fillColor = UIColor.clear.cgColor
            ring.lineWidth = 1.2
            ring.opacity = 0
            layer.addSublayer(ring)
        }
        required init?(coder: NSCoder) { nil }

        override func layoutSubviews() {
            super.layoutSubviews()
            ring.frame = bounds
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, !played else { return }
            played = true
            // The SwiftUI frame is 2.8 x diameter, so the centre is known before layout.
            let half = diameter * UnyriPresenceMotion.rippleScale / 2
            let center = CGPoint(x: half, y: half)
            func circle(_ radius: CGFloat) -> CGPath {
                UIBezierPath(arcCenter: center, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true).cgPath
            }
            let from = circle(diameter / 2), to = circle(diameter * 1.4)
            let grow = CABasicAnimation(keyPath: "path")
            grow.fromValue = from
            grow.toValue = to
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.6
            fade.toValue = 0
            let group = CAAnimationGroup()
            group.animations = [grow, fade]
            group.duration = 0.7
            group.beginTime = ring.convertTime(CACurrentMediaTime(), from: nil) + 0.05
            group.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.6, 0.3, 1)
            group.fillMode = .backwards
            group.delegate = self
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            ring.path = to
            ring.opacity = 0
            CATransaction.commit()
            ring.add(group, forKey: "ripple")
        }

        func animationDidStop(_ anim: CAAnimation, finished flag: Bool) {
            ring.removeAllAnimations()
            ring.removeFromSuperlayer()
        }
    }
}
