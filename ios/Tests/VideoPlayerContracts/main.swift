import Foundation

func check(_ condition: Bool, _ message: String) { if !condition { fatalError(message) } }
var passed = 0
func expect(_ condition: Bool, _ message: String) { check(condition, message); passed += 1 }

// Restore clamp: the end and the first seconds start over; anything else resumes.
expect(UnyriVideoPolicy.restorePosition(saved: 60, duration: 60) == 0, "end restarts")
expect(UnyriVideoPolicy.restorePosition(saved: 59.2, duration: 60) == 0, "last second restarts")
expect(UnyriVideoPolicy.restorePosition(saved: 59, duration: 60) == 0, "exactly one second before the end restarts")
expect(UnyriVideoPolicy.restorePosition(saved: 58.9, duration: 60) == 58.9, "near the end resumes")
expect(UnyriVideoPolicy.restorePosition(saved: 2, duration: 60) == 0, "two seconds restart")
expect(UnyriVideoPolicy.restorePosition(saved: 3, duration: 60) == 3, "three seconds resume")
expect(UnyriVideoPolicy.restorePosition(saved: 30, duration: 60) == 30, "middle resumes")
expect(UnyriVideoPolicy.restorePosition(saved: 3.5, duration: 4) == 0, "short clip near its end restarts")
expect(UnyriVideoPolicy.restorePosition(saved: 30, duration: 0) == 0, "unknown duration restarts")
expect(UnyriVideoPolicy.restorePosition(saved: .nan, duration: 60) == 0, "invalid position restarts")
expect(UnyriVideoPolicy.restorePosition(saved: 30, duration: .infinity) == 0, "invalid duration restarts")
print("Restore clamp: end, last second, first 3 s, middle and invalid values")

// Loop rule: only silent clips up to 30 s.
expect(UnyriVideoPolicy.loops(duration: 10, hasAudio: false), "silent 10 s loops")
expect(UnyriVideoPolicy.loops(duration: 30, hasAudio: false), "silent 30 s loops")
expect(!UnyriVideoPolicy.loops(duration: 30.5, hasAudio: false), "silent 30.5 s ends")
expect(!UnyriVideoPolicy.loops(duration: 10, hasAudio: true), "a clip with sound ends")
expect(!UnyriVideoPolicy.loops(duration: 0, hasAudio: false), "unknown duration ends")
expect(!UnyriVideoPolicy.loops(duration: .nan, hasAudio: false), "invalid duration ends")
print("Loop rule: silent clips up to 30 s only")

// Interruption end: only an interruption pause in the foreground without a call resumes;
// an inactive app (Siri's card still up) resumes once it is active again.
let reasons: [UnyriVideoPauseReason?] = [nil, .user, .background, .interruption, .route, .call, .ended, .failed]
for reason in reasons {
    for mask in 0..<16 {
        let shouldResume = mask & 1 != 0, foreground = mask & 2 != 0, active = mask & 4 != 0, blocked = mask & 8 != 0
        let allowed = reason == .interruption && shouldResume && foreground && !blocked
        let expected: UnyriVideoPolicy.InterruptionEnd = !allowed ? .stay : active ? .resume : .resumeWhenActive
        expect(UnyriVideoPolicy.interruptionEnd(shouldResume: shouldResume, reason: reason,
            foreground: foreground, active: active, audioBlocked: blocked) == expected,
            "interruption end \(String(describing: reason)) mask \(mask)")
    }
}
print("Interruption end: resumes only an interruption pause in the foreground without a call; waits while inactive")

// System pauses: never replace a recorded reason; only a resumable interruption gives way.
let systemReasons: [UnyriVideoPauseReason] = [.interruption, .route, .background, .call]
for reason in systemReasons {
    for current in reasons {
        let upgrade = current == .interruption && reason != .interruption
        for playing in [false, true] {
            let fresh = current == nil && playing
            expect(UnyriVideoPolicy.recordsSystemPause(reason, over: current, playing: playing, unexplainedStopAgo: nil) == (fresh || upgrade),
                "system pause \(reason) over \(String(describing: current)) playing \(playing)")
        }
        let stoppedJustNow = current == nil
        expect(UnyriVideoPolicy.recordsSystemPause(reason, over: current, playing: false, unexplainedStopAgo: 0.1) == (stoppedJustNow || upgrade),
            "system pause \(reason) over \(String(describing: current)) after a stop just now")
    }
}
expect(!UnyriVideoPolicy.recordsSystemPause(.interruption, over: .call, playing: true, unexplainedStopAgo: nil),
    "a call pause seen while the rate change is pending stays a call")
expect(!UnyriVideoPolicy.recordsSystemPause(.interruption, over: nil, playing: false, unexplainedStopAgo: 2),
    "an old unexplained stop is not an interruption")
expect(!UnyriVideoPolicy.recordsSystemPause(.interruption, over: nil, playing: false, unexplainedStopAgo: -1),
    "a stop in the future is not trusted")
expect(!UnyriVideoPolicy.recordsSystemPause(.route, over: nil, playing: false, unexplainedStopAgo: .nan),
    "an invalid stop time is ignored")
expect(!UnyriVideoPolicy.recordsSystemPause(.background, over: nil, playing: false, unexplainedStopAgo: nil),
    "a video that never played gets no reason")
print("System pauses: playing or stopped within 2 s; recorded reasons stay; an interruption gives way")

// Display-link rate: one pixel per frame, 4...60 fps.
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 200, scale: 3, duration: 3) == 60, "3 s clip runs at 60")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 320, scale: 3, duration: 60) == 16, "60 s video runs at 16")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 320, scale: 3, duration: 600) == 4, "10 min video runs at 4")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 320, scale: 3, duration: 0.5) == 60, "never above 60 on ProMotion")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 0, scale: 3, duration: 60) == 4, "unlaid track runs at the minimum")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 320, scale: 3, duration: 0) == 4, "unknown duration runs at the minimum")
expect(UnyriVideoPolicy.displayLinkFPS(trackWidth: 320, scale: .nan, duration: 60) == 4, "invalid scale runs at the minimum")
print("Display-link fps: 60 for 3 s, 16 for 60 s, 4 for 10 min; invalid input at the minimum")

// Time format: floored, minutes and hours.
expect(UnyriVideoPolicy.time(0) == "0:00", "zero")
expect(UnyriVideoPolicy.time(7.9) == "0:07", "floored seconds")
expect(UnyriVideoPolicy.time(59.99) == "0:59", "below a minute")
expect(UnyriVideoPolicy.time(60) == "1:00", "a minute")
expect(UnyriVideoPolicy.time(754) == "12:34", "minutes")
expect(UnyriVideoPolicy.time(3723) == "1:02:03", "hours")
expect(UnyriVideoPolicy.time(-5) == "0:00", "negative")
expect(UnyriVideoPolicy.time(.nan) == "0:00", "invalid")
expect(UnyriVideoPolicy.time(.infinity) == "0:00", "infinite")
expect(UnyriVideoPolicy.time(1e300) == "99:59:59", "huge values never trap")
print("Time format: floored m:ss and h:mm:ss; invalid values")

// Total and remaining: the total rounds, and the remaining time is that total minus the
// floored elapsed time, so tapping the label between the two modes never disagrees.
expect(UnyriVideoPolicy.total(9.6) == "0:10", "total rounds up from .6")
expect(UnyriVideoPolicy.total(9.4) == "0:09", "total rounds down from .4")
expect(UnyriVideoPolicy.total(.nan) == "0:00", "invalid total")
expect(UnyriVideoPolicy.remaining(elapsed: 1.3, duration: 10) == "\u{2212}0:09", "remaining meets the floored elapsed time")
expect(UnyriVideoPolicy.remaining(elapsed: 0, duration: 65) == "\u{2212}1:05", "remaining at the start")
expect(UnyriVideoPolicy.remaining(elapsed: 0.2, duration: 61.43) == "\u{2212}1:01", "a fractional length starts at the rounded total")
expect(UnyriVideoPolicy.remaining(elapsed: 12, duration: 10) == "\u{2212}0:00", "remaining never negative")
expect(UnyriVideoPolicy.remaining(elapsed: -0.5, duration: 10) == "\u{2212}0:10", "a negative clock reads as the start")
expect(UnyriVideoPolicy.remaining(elapsed: .nan, duration: 10) == "\u{2212}0:00", "invalid remaining")
func labelSeconds(_ label: String) -> Int {
    label.replacingOccurrences(of: "\u{2212}", with: "").split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) }
}
for duration in [10, 10.4, 9.6, 61.43, 0.4, 3723.5] {
    let total = labelSeconds(UnyriVideoPolicy.total(duration))
    for elapsed in stride(from: 0.0, through: duration, by: duration / 300) {
        let shown = labelSeconds(UnyriVideoPolicy.time(elapsed))
        let left = labelSeconds(UnyriVideoPolicy.remaining(elapsed: elapsed, duration: duration))
        expect(shown + left == total, "elapsed \(elapsed) and remaining add up to the total of \(duration)")
    }
}
print("Total and remaining: rounded total, elapsed plus remaining is the total, never negative")

// Speed: the menu, the 30 s rule, stored values, the VoiceOver cycle and the label.
expect(UnyriVideoPolicy.speeds == [0.5, 0.75, 1, 1.25, 1.5, 2], "speed menu")
expect(!UnyriVideoPolicy.speedApplies(duration: 29.9), "a short clip starts at 1x")
expect(UnyriVideoPolicy.speedApplies(duration: 30), "30 s takes the remembered speed")
expect(!UnyriVideoPolicy.speedApplies(duration: .nan), "unknown length starts at 1x")
expect(UnyriVideoPolicy.storedSpeed(1.5) == 1.5, "stored 1.5x")
expect(UnyriVideoPolicy.storedSpeed(0.75) == 0.75, "stored 0.75x")
expect(UnyriVideoPolicy.storedSpeed(0) == 1, "nothing stored is 1x")
expect(UnyriVideoPolicy.storedSpeed(3) == 1, "a rate outside the menu is 1x")
expect(UnyriVideoPolicy.storedSpeed(.nan) == 1, "an invalid stored rate is 1x")
expect(UnyriVideoPolicy.nextSpeed(after: 1) == 1.25, "cycle from 1x")
expect(UnyriVideoPolicy.nextSpeed(after: 2) == 0.5, "cycle wraps after 2x")
expect(UnyriVideoPolicy.nextSpeed(after: 0.6) == 0.75, "cycle from an odd rate")
let english = Locale(identifier: "en_US"), russian = Locale(identifier: "ru_RU")
expect(UnyriVideoPolicy.speedLabel(1, locale: english) == "1×", "1x label")
expect(UnyriVideoPolicy.speedLabel(1.5, locale: english) == "1.5×", "English decimal point")
expect(UnyriVideoPolicy.speedLabel(1.5, locale: russian) == "1,5×", "Russian decimal comma")
expect(UnyriVideoPolicy.speedLabel(0.75, locale: russian) == "0,75×", "two decimals")
expect(UnyriVideoPolicy.speedLabel(2, locale: russian) == "2×", "whole rates have no decimals")
print("Speed: menu, 30 s rule, stored values, cycle, localized labels")

// Hold ladder: 2x, slide left 1.5x when faster, slide right 3x when the item can fast forward.
expect(UnyriVideoPolicy.holdRate(base: 1, dx: 0, canFastForward: true) == 2, "hold starts at 2x")
expect(UnyriVideoPolicy.holdRate(base: 1, dx: 39, canFastForward: true) == 2, "under 40 pt keeps 2x")
expect(UnyriVideoPolicy.holdRate(base: 1, dx: 40, canFastForward: true) == 3, "slide right picks 3x")
expect(UnyriVideoPolicy.holdRate(base: 1, dx: 40, canFastForward: false) == 2, "no fast forward stays at 2x")
expect(UnyriVideoPolicy.holdRate(base: 1, dx: -40, canFastForward: true) == 1.5, "slide left picks 1.5x")
expect(UnyriVideoPolicy.holdRate(base: 1.5, dx: -80, canFastForward: true) == 2, "1.5x is not faster than a chosen 1.5x")
expect(UnyriVideoPolicy.holdRate(base: 0.5, dx: -40, canFastForward: false) == 1.5, "slow speeds also get 1.5x")
expect(UnyriVideoPolicy.holdRate(base: 2, dx: 0, canFastForward: true) == 3, "over a chosen 2x the hold is 3x")
expect(UnyriVideoPolicy.holdRate(base: 2, dx: 0, canFastForward: false) == 2, "without fast forward a chosen 2x has nothing faster")
expect(UnyriVideoPolicy.holdRate(base: 2, dx: -50, canFastForward: true) == 3, "slide left over 2x keeps 3x")
expect(UnyriVideoPolicy.holdRate(base: 1, dx: .nan, canFastForward: true) == 2, "invalid travel keeps the start rate")
for base in UnyriVideoPolicy.speeds {
    for dx in [-100.0, -40, 0, 40, 100] {
        for fastForward in [false, true] {
            let rate = UnyriVideoPolicy.holdRate(base: base, dx: dx, canFastForward: fastForward)
            expect(rate <= (fastForward ? 3 : 2), "hold \(base) \(dx) stays within the allowed rates")
            expect(rate >= 1.5, "hold \(base) \(dx) is never slower than 1.5x")
        }
    }
}
print("Hold ladder: 2x, 1.5x left, 3x right with fast forward")

// Scrub precision and taps on the bar.
expect(UnyriVideoPolicy.scrubFactor(distance: 0) == 1, "on the bar")
expect(UnyriVideoPolicy.scrubFactor(distance: 49.9) == 1, "within 50 pt")
expect(UnyriVideoPolicy.scrubFactor(distance: 50) == 0.5, "50 pt halves")
expect(UnyriVideoPolicy.scrubFactor(distance: -75) == 0.5, "below the bar counts the same")
expect(UnyriVideoPolicy.scrubFactor(distance: 100) == 0.25, "100 pt quarters")
expect(UnyriVideoPolicy.scrubFactor(distance: 150) == 0.125, "150 pt eighths")
expect(UnyriVideoPolicy.scrubFactor(distance: 900) == 0.125, "never finer than an eighth")
expect(UnyriVideoPolicy.scrubFactor(distance: .nan) == 1, "invalid distance is full speed")
expect(!UnyriVideoPolicy.tapSeeks(from: 5, to: 6.5, thumbDistance: 5), "a tap on the thumb grabs it, even seconds away on a long video")
expect(!UnyriVideoPolicy.tapSeeks(from: 5, to: 6, thumbDistance: -11), "11 pt from the thumb's centre is still the thumb")
expect(UnyriVideoPolicy.tapSeeks(from: 5, to: 6, thumbDistance: 11.5), "just off the thumb seeks")
expect(!UnyriVideoPolicy.tapSeeks(from: 5, to: 5.02, thumbDistance: 30), "a tap within a frame of the playhead does not seek")
expect(UnyriVideoPolicy.tapSeeks(from: 5, to: 5.04, thumbDistance: 30), "a tap off the thumb and one frame away seeks")
expect(UnyriVideoPolicy.tapSeeks(from: 5, to: 0, thumbDistance: -40), "a tap at the start seeks")
expect(!UnyriVideoPolicy.tapSeeks(from: .nan, to: 3, thumbDistance: 40), "invalid times do not seek")
expect(!UnyriVideoPolicy.tapSeeks(from: 5, to: 3, thumbDistance: .nan), "an invalid distance does not seek")
print("Scrubber: precision by distance, taps seek off the thumb and one frame or more away")

// Tap zones.
expect(UnyriVideoPolicy.zone(0) == .left, "left edge")
expect(UnyriVideoPolicy.zone(0.299) == .left, "left zone")
expect(UnyriVideoPolicy.zone(0.30) == .centre, "centre starts at 30 %")
expect(UnyriVideoPolicy.zone(0.5) == .centre, "centre")
expect(UnyriVideoPolicy.zone(0.70) == .centre, "centre ends at 70 %")
expect(UnyriVideoPolicy.zone(0.701) == .right, "right zone")
expect(UnyriVideoPolicy.zone(1) == .right, "right edge")
expect(UnyriVideoPolicy.zone(.nan) == .centre, "invalid fraction is the centre")
print("Tap zones: 30 / 40 / 30")

// Seek streak: start, same side, turn round, both ends.
let start = UnyriVideoPolicy.nextStreak(nil, forward: true, at: 20, duration: 120)
expect(start == UnyriVideoPolicy.SeekStreak(forward: true, base: 20, steps: 1), "a double tap starts a streak")
expect(UnyriVideoPolicy.streakTarget(start, duration: 120) == 30, "first step +10 s")
let third = UnyriVideoPolicy.nextStreak(UnyriVideoPolicy.nextStreak(start, forward: true, at: 99, duration: 120), forward: true, at: 99, duration: 120)
expect(UnyriVideoPolicy.streakTarget(third, duration: 120) == 50, "two more taps reach +30 s from the start, not from the playhead")
expect(UnyriVideoPolicy.streakSeconds(third, duration: 120) == 30, "ripple shows 30 s")
let turned = UnyriVideoPolicy.nextStreak(third, forward: false, at: 99, duration: 120)
expect(turned == UnyriVideoPolicy.SeekStreak(forward: false, base: 50, steps: 1), "the other side turns round where the streak got to")
expect(UnyriVideoPolicy.streakTarget(turned, duration: 120) == 40, "turned streak goes back 10 s")
expect(UnyriVideoPolicy.streakSeconds(turned, duration: 120) == 10, "turned ripple restarts at 10 s")
let nearEnd = UnyriVideoPolicy.nextStreak(nil, forward: true, at: 115, duration: 120)
expect(UnyriVideoPolicy.streakTarget(nearEnd, duration: 120) == 120, "the end clamps")
expect(UnyriVideoPolicy.streakSeconds(nearEnd, duration: 120) == 5, "near the end the ripple shows what moved")
let nearStart = UnyriVideoPolicy.nextStreak(nil, forward: false, at: 3, duration: 120)
expect(UnyriVideoPolicy.streakTarget(nearStart, duration: 120) == 0, "the start clamps")
expect(UnyriVideoPolicy.streakSeconds(nearStart, duration: 120) == 3, "near the start the ripple shows what moved")
let pastEnd = UnyriVideoPolicy.nextStreak(UnyriVideoPolicy.nextStreak(nearEnd, forward: true, at: 0, duration: 120), forward: false, at: 0, duration: 120)
expect(UnyriVideoPolicy.streakTarget(pastEnd, duration: 120) == 110, "turning round at the end goes back from the end")
expect(UnyriVideoPolicy.nextStreak(nil, forward: true, at: .nan, duration: 120).base == 0, "an invalid playhead starts at 0")
expect(UnyriVideoPolicy.streakTarget(UnyriVideoPolicy.SeekStreak(forward: true, base: 5, steps: 2), duration: 0) == 25, "unknown length only clamps at 0")
print("Seek streak: +10 s per tap from the start, turn round, clamped ends")

// Fit and fill.
expect(UnyriVideoPolicy.offersFill(videoAspect: 16.0 / 9, screenAspect: 393.0 / 852), "landscape video on a portrait phone")
expect(!UnyriVideoPolicy.offersFill(videoAspect: 9.0 / 16, screenAspect: 9.0 / 16), "same shape")
expect(!UnyriVideoPolicy.offersFill(videoAspect: 0.55, screenAspect: 0.5), "10 % apart is not worth it")
expect(UnyriVideoPolicy.offersFill(videoAspect: 0.57, screenAspect: 0.5), "14 % apart offers fill")
expect(!UnyriVideoPolicy.offersFill(videoAspect: 0, screenAspect: 0.5), "unknown video shape")
expect(!UnyriVideoPolicy.offersFill(videoAspect: 1, screenAspect: .nan), "invalid screen shape")
expect(UnyriVideoPolicy.pinchFill(scale: 1.09) == true, "spreading fills")
expect(UnyriVideoPolicy.pinchFill(scale: 1.08) == nil, "1.08 is not enough")
expect(UnyriVideoPolicy.pinchFill(scale: 0.91) == false, "pinching fits")
expect(UnyriVideoPolicy.pinchFill(scale: 0.92) == nil, "0.92 is not enough")
expect(UnyriVideoPolicy.pinchFill(scale: .nan) == nil, "invalid scale")
print("Fit and fill: offered beyond 12 %, pinch thresholds 1.08 and 0.92")

print("Video player contracts: \(passed) passed")
