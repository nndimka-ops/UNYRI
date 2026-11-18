import Foundation
import CoreGraphics

enum UnyriQuickReactionPolicy {
    static let choices = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    static func normalize(_ value: String) -> String { value.isEmpty || choices.contains(value) ? value : "👍" }
    static func eligible(status: String, serverId: String?, deleted: Bool, selecting: Bool) -> Bool {
        !selecting && !deleted && serverId != nil && ["sent", "delivered", "read"].contains(status)
    }
}

/// A reaction someone else just added to a message that is already loaded.
struct UnyriPeerReaction: Equatable {
    let chatId: String
    let targetServerId: String
    let emoji: String
}

/// Timing and shape of the reaction landing ("drop and ripple"). Pure math, so the
/// contracts run without UIKit; UnyriReactionBursts plays it.
enum UnyriReactionBurstPolicy {
    /// A tap's origin waits this long for the reaction it starts.
    static let armLifetime = 0.5
    /// An expected reaction waits this long for its chip; then the chip just shows.
    static let pendingLifetime = 2.0
    /// A flyer whose chip has not laid out by then dissolves where it hovers.
    static let flyerWait = 0.8
    /// The chip lands this long before the flyer's flight ends, so they overlap.
    static let crossfade = 0.035
    /// Reactions arriving right after the chat opens are a sync, not live: no effect.
    static let quietAfterAttach = 1.0
    /// The bubble keeps its effect surface this long after an effect starts.
    static let surfaceLinger = 1.0
    /// Point size of the flyer bitmap.
    static let flyerGlyph: CGFloat = 40
    static let maxEffects = 3, peerBatch = 3

    /// Emoji never contain "|", and tokens keep the emoji separately, so keys never split.
    static func key(chat: String, server: String, emoji: String) -> String { "\(chat)|\(server)|\(emoji)" }
    static func messageKey(chat: String, server: String) -> String { "\(chat)|\(server)" }

    static func flightDuration(distance d: CGFloat) -> Double { Double(min(0.38, max(0.26, 0.24 + d / 1600))) }

    /// The flight's quadratic control point: always bowed toward the top of the screen.
    static func arcControl(from o: CGPoint, to t: CGPoint) -> CGPoint {
        let lift = min(80, max(24, hypot(t.x - o.x, t.y - o.y) * 0.3))
        return CGPoint(x: (o.x + t.x) / 2, y: min(o.y, t.y) - lift / 2)
    }

    private static func smooth(_ t: CGFloat) -> CGFloat { let t = min(1, max(0, t)); return t * t * (3 - 2 * t) }

    /// A chip landing: s0 -> 1.16 -> 0.97 -> 1, wider than tall on impact, then a light rebound.
    static func popScale(_ p: CGFloat, from s0: CGFloat) -> CGSize {
        if p >= 1 { return CGSize(width: 1, height: 1) }
        if p <= 0 { return CGSize(width: s0, height: s0) }
        let base: CGFloat
        if p < 0.32 { base = s0 + (1.16 - s0) * (1 - pow(1 - p / 0.32, 3)) }
        else if p < 0.66 { base = 1.16 - 0.19 * smooth((p - 0.32) / 0.34) }
        else { base = 0.97 + 0.03 * smooth((p - 0.66) / 0.34) }
        var sx = base, sy = base
        if p < 0.24 { let b = sin(.pi * p / 0.24); sx *= 1 + 0.08 * b; sy *= 1 - 0.08 * b }
        else if p < 0.5 { let b = sin(.pi * (p - 0.24) / 0.26); sx *= 1 - 0.03 * b; sy *= 1 + 0.03 * b }
        return CGSize(width: sx, height: sy)
    }

    /// An existing chip taking one more: about 1.12, then 0.97, then rest.
    static func bumpScale(_ f: CGFloat) -> CGFloat { f <= 0 || f >= 1 ? 1 : 1 + 0.18 * sin(2 * .pi * f) * pow(1 - f, 1.5) }
    /// A chip losing my reaction while others keep it: about 0.91, then 1.03.
    static func dipScale(_ f: CGFloat) -> CGFloat { f <= 0 || f >= 1 ? 1 : 1 - 0.12 * sin(2 * .pi * f) * (1 - f) }

    /// The emoji's centre in a chip `w` wide whose emoji slot starts `lead` from the
    /// leading edge (the chip's own coordinates run left to right in either direction).
    static func glyphCenterX(width w: CGFloat, lead: CGFloat, slot: CGFloat, rtl: Bool) -> CGFloat {
        let x = lead + slot / 2
        return rtl ? w - x : x
    }

    /// How far the light ring travels: to the bubble's farthest corner, within 60...220;
    /// smaller when an existing chip grew by one (`grew`), and for someone else's reaction.
    static func rippleRadius(chip: CGRect, bounds b: CGRect, own: Bool, grew: Bool) -> CGFloat {
        let c = CGPoint(x: chip.midX, y: chip.midY)
        let far = [CGPoint(x: b.minX, y: b.minY), CGPoint(x: b.maxX, y: b.minY), CGPoint(x: b.minX, y: b.maxY), CGPoint(x: b.maxX, y: b.maxY)]
            .map { hypot($0.x - c.x, $0.y - c.y) }.max() ?? 0
        let base = min(220, max(60, far))
        return own ? (grew ? min(base, 180) : base) : min(base * 0.6, grew ? 100 : 140)
    }
    static func rippleDuration(radius r: CGFloat) -> Double { 0.42 + Double(r) / 1400 }

    static func isLightSurface(red: CGFloat, green: CGFloat, blue: CGFloat) -> Bool {
        0.2126 * red + 0.7152 * green + 0.0722 * blue > 0.55
    }

    /// (target, emoji) pairs where more people other than me hold an emoji than before.
    /// `others` excludes my own vote, so my changes (on any device) never count.
    static func peerAdds<R: Equatable>(old: [String: [R]], new: [String: [R]], within targets: Set<String>,
                                       emoji: (R) -> String, others: (R) -> Int) -> [(target: String, emoji: String)] {
        var out: [(target: String, emoji: String)] = []
        for (target, now) in new where targets.contains(target) && old[target] != now {
            let before = Dictionary((old[target] ?? []).map { (emoji($0), others($0)) }, uniquingKeysWith: max)
            for r in now where others(r) > (before[emoji(r)] ?? 0) { out.append((target, emoji(r))) }
        }
        return out
    }
}
