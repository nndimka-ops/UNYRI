import Foundation

/// Voice level -> halo energy for the round video recorder. Pure, so it is contract-tested;
/// the preview view applies its output to Core Animation layers at 10 Hz.
struct VideoNoteHaloMeter {
    struct Step: Equatable {
        var apply: Double?
        var ripple: Double?
        var warn = false
    }

    /// The capture level is (dB + 50) / 50: room noise (-50...-40 dB) stays under the
    /// gate, speech (-30...-10 dB) maps to about 0.24...1.
    static let floor = 0.28, span = 0.50
    static let attack = 0.55, release = 0.20, calmAttack = 0.25, calmRelease = 0.08
    static let baselineRate = 0.05
    /// Smaller changes are not committed, so silence costs no layer work.
    static let applyDelta = 0.02
    static let rippleRise = 0.32, rippleGap = 1.2

    private(set) var energy = 0.0, baseline = 0.0, warned = false
    private var applied = -1.0, lastRipple = -Double.infinity

    /// Rates are per 0.1 s; a 5 Hz tick gets the equivalent rate.
    static func scaled(_ rate: Double, dt: Double) -> Double {
        1 - pow(1 - rate, min(0.35, max(0.05, dt)) / 0.1)
    }

    mutating func step(level: Double, dt: Double, now: Double, remaining: Double, calm: Bool,
                       ripplesAllowed: Bool, warnAt: Double) -> Step {
        let target = min(1, max(0, (level - Self.floor) / Self.span))
        let rate = target > energy ? (calm ? Self.calmAttack : Self.attack) : (calm ? Self.calmRelease : Self.release)
        energy += (target - energy) * Self.scaled(rate, dt: dt)
        baseline += (energy - baseline) * Self.scaled(Self.baselineRate, dt: dt)
        var out = Step()
        if abs(energy - applied) >= Self.applyDelta {
            applied = energy
            out.apply = energy
        }
        // An onset after a pause, not loudness: steady noise lifts the baseline with it.
        if ripplesAllowed, energy - baseline > Self.rippleRise, now - lastRipple >= Self.rippleGap {
            lastRipple = now
            out.ripple = energy
        }
        if !warned, remaining <= warnAt, remaining > 0.25 {
            warned = true
            out.warn = true
        }
        return out
    }
}

enum VideoNoteHaloGeometry {
    /// How far the glow reaches past the preview view's edge.
    static func outset(side: Double) -> Double { min(28, max(14, (side * 0.075).rounded())) }

    /// Fractions of the aura radius: clear under the picture edge (breathing never opens a
    /// gap), brightest at the ring's outer edge, gone at the rim.
    static func auraStops(side: Double, ringInset: Double = 7) -> [(location: Double, alpha: Double)] {
        let radius = side / 2 + outset(side: side)
        let hole = (side / 2 - ringInset - 2) / radius
        let peak = (side / 2 - 0.5) / radius
        return [(0, 0), (hole, 0), (peak, 0.20), (peak + 0.4 * (1 - peak), 0.07), (1, 0)]
    }
}

enum VideoNoteCountdown {
    static let warningSeconds = 5.0

    /// 56...59 of 60: the ring's start cap is the fifth mark.
    static func marks(total: Double) -> [Double] { (1...4).map { total - Double(5 - $0) } }

    /// Head pulses on the remaining whole seconds, each as the head reaches a mark.
    static func pulses(elapsed: Double, total: Double) -> (delay: Double, count: Int) {
        let next = elapsed.rounded(.down) + 1
        return (max(0, next - elapsed), max(0, Int((total - next).rounded(.down))))
    }
}
