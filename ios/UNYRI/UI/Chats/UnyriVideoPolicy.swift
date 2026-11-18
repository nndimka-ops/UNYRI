import Foundation

/// Why the viewer's video stopped. Only a system interruption may resume on its own.
enum UnyriVideoPauseReason: String {
    case user, background, interruption, route, call, ended, failed
}

/// Pure rules of the full-screen video player. Foundation only: the viewer uses them
/// and ios/Tests/VideoPlayerContracts runs them without UIKit or a device.
enum UnyriVideoPolicy {
    /// Where a revisited video starts: the saved spot, except near the start (nothing
    /// worth resuming) or in the last second (it was watched: replay from 0:00).
    static func restorePosition(saved: Double, duration: Double) -> Double {
        guard saved.isFinite, duration.isFinite, duration > 0 else { return 0 }
        return saved < 3 || saved >= duration - 1 ? 0 : saved
    }

    /// Short clips without sound loop like GIFs and never show an ended state.
    static func loops(duration: Double, hasAudio: Bool) -> Bool {
        !hasAudio && duration.isFinite && duration > 0 && duration <= 30
    }

    /// How long a stop with no recorded reason waits for the system event that explains it.
    static let unexplainedStopWindow = 2.0

    /// Whether a system event (interruption, unplugged headphones, background, call) records
    /// its reason. AVPlayer pauses itself for some of them and that rate change can land
    /// before the event, so a stop moments ago without a reason counts as playing. A recorded
    /// reason is never replaced, so a user or call pause cannot become a resumable
    /// interruption; only a resumable interruption gives way to a reason that never resumes.
    static func recordsSystemPause(_ reason: UnyriVideoPauseReason, over current: UnyriVideoPauseReason?,
                                   playing: Bool, unexplainedStopAgo: Double?) -> Bool {
        switch current {
        case nil:
            guard !playing else { return true }
            guard let ago = unexplainedStopAgo, ago.isFinite else { return false }
            return ago >= 0 && ago < unexplainedStopWindow
        case .interruption?:
            return reason != .interruption
        default:
            return false
        }
    }

    enum InterruptionEnd: Equatable { case resume, resumeWhenActive, stay }

    /// Siri, an alarm or a short system sound paused the video: resume when the system
    /// says so and nothing else stopped it meanwhile. While the app is still inactive
    /// (Siri's card outlasts its audio) the resume waits for it to become active.
    /// Calls and recordings never resume.
    static func interruptionEnd(shouldResume: Bool, reason: UnyriVideoPauseReason?,
                                foreground: Bool, active: Bool, audioBlocked: Bool) -> InterruptionEnd {
        guard shouldResume, reason == .interruption, foreground, !audioBlocked else { return .stay }
        return active ? .resume : .resumeWhenActive
    }

    /// Scrubber display link: about one pixel of travel per frame, so a 3 s clip
    /// gets 60 fps and a 10 min video 4 fps instead of 120 Hz on ProMotion.
    static func displayLinkFPS(trackWidth: Double, scale: Double, duration: Double) -> Int {
        guard trackWidth.isFinite, scale.isFinite, duration.isFinite,
              trackWidth > 0, scale > 0, duration > 0 else { return 4 }
        return Int(min(60, max(4, (trackWidth * scale / duration).rounded(.up))))
    }

    /// Elapsed or total time, floored: "0:07", "12:34", "1:02:03".
    static func time(_ value: Double) -> String {
        let seconds = Int(value.isFinite ? min(max(0, value), 359_999) : 0)
        return seconds >= 3600 ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    /// The clip length, rounded to the nearest second.
    static func total(_ duration: Double) -> String {
        time(duration.isFinite ? duration.rounded() : 0)
    }

    /// Time left: the rounded total minus the floored elapsed time, so the two labels
    /// always add up to the total the other mode shows: "−1:05".
    static func remaining(elapsed: Double, duration: Double) -> String {
        let left = duration.isFinite && elapsed.isFinite ? max(0, duration.rounded() - max(0, elapsed).rounded(.down)) : 0
        return "\u{2212}" + time(left)
    }

    // MARK: Speed

    /// The speed menu, slowest first.
    static let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2]

    /// The remembered speed is for videos of 30 s or more; shorter clips start at 1×.
    static func speedApplies(duration: Double) -> Bool {
        duration.isFinite && duration >= 30
    }

    /// A stored value outside the menu (nothing stored reads as 0) means 1×.
    static func storedSpeed(_ value: Double) -> Float {
        speeds.first { Double($0) == value } ?? 1
    }

    /// The VoiceOver speed action steps through the menu and wraps around.
    static func nextSpeed(after rate: Float) -> Float {
        speeds.first { $0 > rate } ?? speeds[0]
    }

    /// "1×", "1.5×" or "1,5×": the locale's decimal separator, at most two decimals.
    static func speedLabel(_ rate: Float, locale: Locale) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        let value = Double(rate).isFinite ? Double(rate) : 1
        return (formatter.string(from: NSNumber(value: value)) ?? String(format: "%g", value)) + "×"
    }

    // MARK: Hold to speed up

    /// Sideways travel that picks the slower or the faster hold rate.
    static let holdSlide = 40.0

    /// Holding plays at 2×, or at 3× over a chosen 2×. Sliding 40 pt left picks 1.5× (only
    /// when that is faster than the chosen speed) and right 3×. AVPlayer allows rates
    /// above 2× only for items that can fast forward.
    static func holdRate(base: Float, dx: Double, canFastForward: Bool) -> Float {
        let top: Float = canFastForward ? 3 : 2
        let start: Float = base < 2 ? 2 : top
        guard dx.isFinite else { return start }
        if dx <= -holdSlide { return base < 1.5 ? 1.5 : start }
        if dx >= holdSlide { return top }
        return start
    }

    // MARK: Scrubber

    /// Finer scrubbing the further the finger is above or below the bar: full speed
    /// within 50 pt, then a half, a quarter and an eighth.
    static func scrubFactor(distance: Double) -> Double {
        guard distance.isFinite else { return 1 }
        let away = abs(distance)
        return away < 50 ? 1 : away < 100 ? 0.5 : away < 150 ? 0.25 : 0.125
    }

    /// Points from the thumb's centre that still count as a touch on it: half a fingertip,
    /// a little more than half the 20 pt touched thumb.
    static let thumbGrab = 11.0

    /// A tap on the bar seeks only off the thumb (a touch there was meant as a grab) and at
    /// least one frame (1/30 s) away, which matters for short clips on a long track.
    static func tapSeeks(from current: Double, to target: Double, thumbDistance: Double) -> Bool {
        guard current.isFinite, target.isFinite, thumbDistance.isFinite else { return false }
        return abs(thumbDistance) > thumbGrab && abs(target - current) >= 1.0 / 30
    }

    // MARK: Tap zones and seek streaks

    enum Zone: Equatable { case left, centre, right }

    /// Tap zones by the fraction of the width: 30 % on each side, 40 % in the centre.
    static func zone(_ fraction: Double) -> Zone {
        guard fraction.isFinite else { return .centre }
        return fraction < 0.30 ? .left : fraction > 0.70 ? .right : .centre
    }

    /// A double-tap seek streak: `steps` jumps of 10 s in one direction from `base`.
    struct SeekStreak: Equatable {
        var forward: Bool
        var base: Double
        var steps: Int
    }

    static let streakStep = 10.0
    /// A tap within this time after the last step continues the streak.
    static let streakWindow = 0.7

    /// The double tap starts at the current time, a tap on the same side adds 10 s, and
    /// the other side turns round from where the streak got to.
    static func nextStreak(_ streak: SeekStreak?, forward: Bool, at time: Double, duration: Double) -> SeekStreak {
        guard let streak else { return SeekStreak(forward: forward, base: time.isFinite ? max(0, time) : 0, steps: 1) }
        guard streak.forward != forward else { return SeekStreak(forward: forward, base: streak.base, steps: streak.steps + 1) }
        return SeekStreak(forward: forward, base: streakTarget(streak, duration: duration), steps: 1)
    }

    static func streakTarget(_ streak: SeekStreak, duration: Double) -> Double {
        let target = streak.base + (streak.forward ? 1 : -1) * Double(streak.steps) * streakStep
        guard duration.isFinite, duration > 0 else { return max(0, target) }
        return min(duration, max(0, target))
    }

    /// The seconds really moved, for the ripple and the announcement: less near either end.
    static func streakSeconds(_ streak: SeekStreak, duration: Double) -> Int {
        Int(abs(streakTarget(streak, duration: duration) - streak.base).rounded())
    }

    // MARK: Fit and fill

    /// Pinch to fill is offered only when the video's shape differs from the screen's by
    /// more than 12 %: below that, filling would crop almost nothing.
    static func offersFill(videoAspect: Double, screenAspect: Double) -> Bool {
        guard videoAspect.isFinite, screenAspect.isFinite, videoAspect > 0, screenAspect > 0 else { return false }
        return abs(videoAspect - screenAspect) / screenAspect > 0.12
    }

    /// A pinch decides once: spreading past 1.08 fills, pinching below 0.92 fits.
    static func pinchFill(scale: Double) -> Bool? {
        guard scale.isFinite else { return nil }
        return scale > 1.08 ? true : scale < 0.92 ? false : nil
    }
}
