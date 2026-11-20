import AppKit
import SwiftUI

private struct SlotFrames: PreferenceKey {
    static var defaultValue: [Int: CGRect] = [:]
    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

/// The production row shape: only the policy's controls, each in the production slot, in a tray of four-control width.
private struct ProbeRow: View {
    let row: [CallControlRowPolicy.Control]
    let size: CGFloat
    let spacing: CGFloat
    let measured: ([Int: CGRect]) -> Void

    var body: some View {
        HStack(spacing: spacing) {
            ForEach(Array(row.enumerated()), id: \.element) { index, _ in
                CallControlSlot(size: size, enabled: true) {
                    Button("Control \(index)") {}.frame(width: size, height: size)
                }
                .background(GeometryReader { proxy in
                    Color.clear.preference(key: SlotFrames.self,
                        value: [index: proxy.frame(in: .named("row"))])
                })
            }
        }
        .frame(width: 4 * size + 3 * spacing, height: size)
        .coordinateSpace(name: "row")
        .onPreferenceChange(SlotFrames.self, perform: measured)
    }
}

/// B-067 (owner, 20 Nov: «почему не по центру?»): the tray shows only usable controls and they are centred.
@main struct CallControlSlotsContracts {
    @MainActor static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        typealias C = CallControlRowPolicy.Control
        let phases: [(String, [C])] = [
            ("starting", CallControlRowPolicy.audio(videoAvailable: false, live: false, ended: false)),
            ("calling", CallControlRowPolicy.audio(videoAvailable: false, live: true, ended: false)),
            ("connected", CallControlRowPolicy.audio(videoAvailable: true, live: true, ended: false)),
            ("upgrading", CallControlRowPolicy.audio(videoAvailable: false, live: true, ended: false)),
            ("ended", CallControlRowPolicy.audio(videoAvailable: true, live: true, ended: true)),
            ("video-starting", CallControlRowPolicy.videoMedia(live: false, ended: false) + CallControlRowPolicy.videoCall(live: false, ended: false)),
            ("video-live", CallControlRowPolicy.videoMedia(live: true, ended: false) + CallControlRowPolicy.videoCall(live: true, ended: false)),
        ]
        precondition(phases[0].1 == [.end], "before the call exists only End shows")
        precondition(phases[1].1 == [.mute, .end, .route], "calling: microphone, End, output (no empty video square)")
        precondition(phases[2].1 == [.video, .mute, .end, .route], "connected adds video first")
        precondition(phases[4].1.isEmpty, "an ended call shows no controls")
        precondition(phases[5].1 == [.end] && phases[6].1 == [.camera, .mute, .end, .route], "video tray")
        for size: CGFloat in [52, 60, 64] {
            for spacing: CGFloat in [8, 16, 24] {
                for (phase, row) in phases where !row.isEmpty {
                    var frames: [Int: CGRect] = [:]
                    let width = 4 * size + 3 * spacing
                    let host = NSHostingView(rootView: ProbeRow(row: row, size: size, spacing: spacing, measured: { frames = $0 }))
                    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: width, height: size),
                        styleMask: [.borderless], backing: .buffered, defer: false)
                    window.isReleasedWhenClosed = false
                    window.contentView = host
                    host.setFrameSize(NSSize(width: width, height: size))
                    host.layoutSubtreeIfNeeded()
                    let deadline = Date().addingTimeInterval(2)
                    while frames.count != row.count && Date() < deadline {
                        RunLoop.current.run(until: Date().addingTimeInterval(0.01))
                        host.layoutSubtreeIfNeeded()
                    }
                    precondition(frames.count == row.count, "Missing control geometry in \(phase)")
                    let first = frames[0]!, last = frames[row.count - 1]!
                    precondition(abs((first.minX + last.maxX) / 2 - width / 2) < 0.5, "Controls not centred in \(phase)")
                    for index in 0..<row.count {
                        let frame = frames[index]!
                        precondition(abs(frame.width - size) < 0.1 && abs(frame.height - size) < 0.1, "Control size changed in \(phase)")
                        if index > 0 { precondition(abs(frame.minX - frames[index - 1]!.maxX - spacing) < 0.1, "Uneven gap in \(phase)") }
                    }
                    window.close()
                }
            }
        }
        print("PASS: call controls show only usable buttons, centred, same size and gaps, across call phases")
    }
}
