import Foundation

var failures = 0
func require(_ condition: Bool, _ message: String) {
    if !condition { failures += 1; print("FAIL: \(message)") }
}
func near(_ a: Double, _ b: Double, _ tolerance: Double = 1e-6) -> Bool { abs(a - b) <= tolerance }

struct Run {
    var meter = VideoNoteHaloMeter()
    var now = 0.0
    var steps: [VideoNoteHaloMeter.Step] = []
    var stepTimes: [Double] = []

    /// Feeds levels at a fixed tick; remaining time counts down from 60 s.
    mutating func feed(_ levels: [Double], dt: Double = 0.1, calm: Bool = false, ripples: Bool = true) {
        for level in levels {
            now += dt
            steps.append(meter.step(level: level, dt: dt, now: now, remaining: 60 - now, calm: calm,
                                    ripplesAllowed: ripples, warnAt: VideoNoteCountdown.warningSeconds))
            stepTimes.append(now)
        }
    }

    func rippleTimes(in range: Range<Int>? = nil) -> [Double] {
        let indices = range ?? steps.indices
        return indices.filter { steps[$0].ripple != nil }.map { stepTimes[$0] }
    }
}

func repeated(_ level: Double, _ count: Int) -> [Double] { Array(repeating: level, count: count) }

// 1. Silence commits once, then nothing; no ripples.
do {
    var run = Run()
    run.feed(repeated(0.2, 50))
    require(run.steps[0].apply == 0, "silence applies 0 once")
    require(run.steps.dropFirst().allSatisfy { $0.apply == nil }, "silence commits nothing afterwards")
    require(run.rippleTimes().isEmpty, "silence never ripples")
}

// 2. Steady noise: at most one ripple, none once the baseline has caught up.
do {
    var run = Run()
    run.feed(repeated(0.5, 100))
    require(run.rippleTimes().count <= 1, "steady noise ripples at most once")
    require(run.rippleTimes(in: 30..<100).isEmpty, "steady noise stops rippling")
}

// 3. An onset after a pause sends exactly one ripple at once.
do {
    var run = Run()
    run.feed(repeated(0.2, 30))
    run.feed(repeated(0.9, 10))
    require(run.rippleTimes(in: 30..<33).count == 1, "onset ripples within three steps")
    require(run.rippleTimes(in: 30..<40).count == 1, "one ripple per onset")
}

// 4. Bursts every 0.5 s: ripples stay at least 1.2 s apart.
do {
    var run = Run()
    for _ in 0..<20 { run.feed(repeated(0.95, 2) + repeated(0.1, 3)) }
    let times = run.rippleTimes()
    require(times.count >= 2, "bursts ripple more than once")
    require(zip(times, times.dropFirst()).allSatisfy { $1 - $0 >= VideoNoteHaloMeter.rippleGap - 1e-9 }, "ripple gap")
}

// 5. The final-seconds warning fires exactly once, inside the window.
do {
    var meter = VideoNoteHaloMeter()
    var warnings: [Double] = []
    var remaining = 10.0
    var now = 0.0
    while remaining >= 0 {
        let step = meter.step(level: 0.5, dt: 0.1, now: now, remaining: remaining, calm: false,
                              ripplesAllowed: true, warnAt: VideoNoteCountdown.warningSeconds)
        if step.warn { warnings.append(remaining) }
        remaining -= 0.1
        now += 0.1
    }
    require(warnings.count == 1, "one warning")
    require(warnings.allSatisfy { $0 <= 5 + 1e-9 && $0 > 0.25 }, "warning window")
}

// 6. Calm smoothing releases more slowly.
do {
    var normal = Run(), calm = Run()
    normal.feed(repeated(1.0, 30))
    calm.feed(repeated(1.0, 30), calm: true)
    normal.feed(repeated(0.2, 5))
    calm.feed(repeated(0.2, 5), calm: true)
    require(calm.meter.energy > normal.meter.energy, "calm release is slower")
}

// 7. A 5 Hz tick behaves like a 10 Hz one.
do {
    var fast = Run(), slow = Run()
    fast.feed(repeated(0.8, 10), dt: 0.1)
    slow.feed(repeated(0.8, 5), dt: 0.2)
    require(abs(fast.meter.energy - slow.meter.energy) < 0.03, "rates scale with dt")
}

// 8. Ripples can be switched off (Reduce Motion, lean mode).
do {
    var run = Run()
    run.feed(repeated(0.2, 30) + repeated(0.9, 10) + repeated(0.2, 30) + repeated(0.9, 10), ripples: false)
    require(run.rippleTimes().isEmpty, "no ripples when not allowed")
}

// 9. Geometry.
do {
    require(VideoNoteHaloGeometry.outset(side: 297) == 22, "outset 297")
    require(VideoNoteHaloGeometry.outset(side: 100) == 14, "outset floor")
    require(VideoNoteHaloGeometry.outset(side: 500) == 28, "outset cap")
    for side in [140.0, 297, 420] {
        let stops = VideoNoteHaloGeometry.auraStops(side: side)
        require(zip(stops, stops.dropFirst()).allSatisfy { $0.location < $1.location }, "stops increase at \(side)")
        let radius = side / 2 + VideoNoteHaloGeometry.outset(side: side)
        require(stops.contains { near($0.location, (side / 2 - 0.5) / radius) && $0.alpha == 0.20 }, "peak at the ring edge, \(side)")
        require(stops.first?.alpha == 0 && stops.last?.alpha == 0, "clear centre and rim at \(side)")
    }
}

// 10. Countdown marks and pulses.
do {
    require(VideoNoteCountdown.marks(total: 60) == [56, 57, 58, 59], "marks 60")
    require(VideoNoteCountdown.marks(total: 10) == [6, 7, 8, 9], "marks 10")
    let early = VideoNoteCountdown.pulses(elapsed: 55.03, total: 60)
    require(near(early.delay, 0.97) && early.count == 4, "pulses from 55.03")
    let late = VideoNoteCountdown.pulses(elapsed: 58.6, total: 60)
    require(near(late.delay, 0.4) && late.count == 1, "pulses from 58.6")
    require(VideoNoteCountdown.pulses(elapsed: 59.5, total: 60).count == 0, "no pulse in the last second")
}

if failures > 0 {
    print("video note halo contracts: \(failures) failed")
    exit(1)
}
print("video note halo contracts: ok")
