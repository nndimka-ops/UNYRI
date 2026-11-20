import Foundation
func check(_ condition: Bool, _ message: String) { if !condition { fatalError(message) } }
for status in ["sent", "delivered", "read", "pending", "failed", "decrypt_failed", "identity_changed"] {
    check(UnyriQuickReactionPolicy.eligible(status: status, serverId: "id", deleted: false, selecting: false) == ["sent", "delivered", "read"].contains(status), status)
}
check(!UnyriQuickReactionPolicy.eligible(status: "sent", serverId: nil, deleted: false, selecting: false), "unacknowledged target")
check(!UnyriQuickReactionPolicy.eligible(status: "read", serverId: "id", deleted: true, selecting: false), "deleted target")
check(!UnyriQuickReactionPolicy.eligible(status: "read", serverId: "id", deleted: false, selecting: true), "selection mode")
check(UnyriQuickReactionPolicy.normalize("") == "", "disabled preference")
check(UnyriQuickReactionPolicy.normalize("invalid") == "👍", "invalid preference")
for choice in UnyriQuickReactionPolicy.choices { check(UnyriQuickReactionPolicy.normalize(choice) == choice, "supported preference") }

// Reaction landing ("drop and ripple").
typealias Burst = UnyriReactionBurstPolicy
check(Burst.popScale(0, from: 0.8) == CGSize(width: 0.8, height: 0.8), "pop starts at its entry scale")
check(Burst.popScale(1, from: 0.3) == CGSize(width: 1, height: 1), "pop rests at identity")
check(Burst.popScale(1.5, from: 0.8) == CGSize(width: 1, height: 1), "pop past its end stays at identity")
for entry in [0.8, 0.3] as [CGFloat] {
    var peak: CGFloat = 0, settled = true, continuous = true
    for step in 0...200 {
        let p = CGFloat(step) * 0.005, size = Burst.popScale(p, from: entry)
        peak = max(peak, size.width, size.height)
        if p >= 0.66 { settled = settled && [size.width, size.height].allSatisfy { $0 >= 0.96 && $0 <= 1.0 } }
    }
    for step in 0..<1000 {
        let a = Burst.popScale(CGFloat(step) * 0.001, from: entry), b = Burst.popScale(CGFloat(step + 1) * 0.001, from: entry)
        continuous = continuous && abs(a.width - b.width) < 0.02 && abs(a.height - b.height) < 0.02
    }
    check(peak >= 1.15 && peak <= 1.25, "pop overshoot from \(entry)")
    check(settled, "pop settles within 0.96...1 from \(entry)")
    check(continuous, "pop is continuous from \(entry)")
}
check(Burst.bumpScale(0) == 1 && Burst.bumpScale(1) == 1, "bump rests at both ends")
check(Burst.dipScale(0) == 1 && Burst.dipScale(1) == 1, "dip rests at both ends")
let bumpPeak = (1..<1000).map { Burst.bumpScale(CGFloat($0) / 1000) }.max() ?? 0
let dipLow = (1..<1000).map { Burst.dipScale(CGFloat($0) / 1000) }.min() ?? 1
check(bumpPeak >= 1.10 && bumpPeak <= 1.14, "bump peak")
check(dipLow >= 0.89 && dipLow <= 0.93, "dip trough")
check(Burst.flightDuration(distance: 0) == 0.26, "shortest flight")
check(Burst.flightDuration(distance: 10_000) == 0.35, "longest flight is bounded to 350 ms")
check((0..<100).allSatisfy { Burst.flightDuration(distance: CGFloat($0) * 10) <= Burst.flightDuration(distance: CGFloat($0 + 1) * 10) }, "flight grows with distance")
for (from, to) in [(CGPoint(x: 200, y: 300), CGPoint(x: 80, y: 600)), (CGPoint(x: 80, y: 600), CGPoint(x: 300, y: 200)),
                   (CGPoint(x: 100, y: 400), CGPoint(x: 104, y: 402)), (CGPoint(x: 10, y: 10), CGPoint(x: 390, y: 800)),
                   (CGPoint(x: 300, y: 500), CGPoint(x: 300, y: 500))] {
    check(Burst.arcControl(from: from, to: to).y <= min(from.y, to.y) - 12, "flight bows upward \(from) -> \(to)")
}
check(Burst.glyphCenterX(width: 27, lead: 5, slot: 17, rtl: false) == 27 / 2, "an emoji alone is centred")
check(Burst.glyphCenterX(width: 37, lead: 5, slot: 17, rtl: false) == 13.5, "with a count the emoji stays in its slot")
check(Burst.glyphCenterX(width: 37, lead: 5, slot: 17, rtl: true) == 37 - 13.5, "right to left mirrors the slot")
let tiny = CGRect(x: 0, y: 0, width: 20, height: 20), huge = CGRect(x: 0, y: 0, width: 1000, height: 1000)
let chipRect = CGRect(x: 5, y: 5, width: 10, height: 10)
check(Burst.rippleRadius(chip: chipRect, bounds: tiny, own: true, grew: false) == 60, "ripple floor")
check(Burst.rippleRadius(chip: chipRect, bounds: huge, own: true, grew: false) == 220, "ripple ceiling")
check(Burst.rippleRadius(chip: chipRect, bounds: huge, own: true, grew: true) <= 180, "own ripple in a grown bubble")
check(Burst.rippleRadius(chip: chipRect, bounds: huge, own: false, grew: false) <= 140, "peer ripple")
check(Burst.rippleRadius(chip: chipRect, bounds: huge, own: false, grew: true) <= 100, "peer ripple in a grown bubble")
check(abs(Burst.rippleDuration(radius: 60) - 0.4629) < 0.0001, "shortest ripple")
check(abs(Burst.rippleDuration(radius: 220) - 0.5771) < 0.0001, "longest ripple")
check(Burst.isLightSurface(red: 212 / 255, green: 212 / 255, blue: 212 / 255), "#D4D4D4 is light")
check(!Burst.isLightSurface(red: 28 / 255, green: 28 / 255, blue: 30 / 255), "#1C1C1E is dark")
struct Vote: Equatable { let emoji: String; let count: Int; let mine: Bool }
func peerAdds(_ old: [String: [Vote]], _ new: [String: [Vote]], within: Set<String> = ["m"]) -> [String] {
    Burst.peerAdds(old: old, new: new, within: within, emoji: \.emoji, others: { $0.count - ($0.mine ? 1 : 0) }).map { "\($0.target) \($0.emoji)" }
}
check(peerAdds(["m": [Vote(emoji: "👍", count: 1, mine: false)]], ["m": [Vote(emoji: "👍", count: 2, mine: false)]]) == ["m 👍"], "another person joins an emoji")
check(peerAdds([:], ["m": [Vote(emoji: "❤️", count: 1, mine: false)]]) == ["m ❤️"], "a new emoji from someone else")
check(peerAdds(["m": [Vote(emoji: "👍", count: 1, mine: false)]], ["m": [Vote(emoji: "👍", count: 2, mine: true)]]).isEmpty, "my own reaction is not a peer's")
check(peerAdds(["m": [Vote(emoji: "👍", count: 2, mine: false)]], ["m": [Vote(emoji: "👍", count: 1, mine: false)]]).isEmpty, "a removal is not an arrival")
check(peerAdds([:], ["x": [Vote(emoji: "👍", count: 1, mine: false)]]).isEmpty, "targets outside the window are ignored")
check(peerAdds(["m": [Vote(emoji: "👍", count: 1, mine: false), Vote(emoji: "👍", count: 2, mine: false)]],
               ["m": [Vote(emoji: "👍", count: 2, mine: false)]]).isEmpty, "duplicate emoji entries are tolerated")
check(Burst.key(chat: "c", server: "s", emoji: "👍") == "c|s|👍", "reaction key")
check(Burst.messageKey(chat: "c", server: "s") == "c|s", "message key")
print("Quick reactions: 59 contracts passed")
