import Foundation

private func recorder() -> PerformanceRecorder {
    let value = PerformanceRecorder(maximumEvents: 80)
    value.configure(enabled: true, app: "fixture", build: "fixture", os: "fixture", hardware: "fixture")
    return value
}

private func events(_ recorder: PerformanceRecorder) -> [PerformanceEvent] {
    guard let snapshot = recorder.snapshot(preferProblemWindow: false) else {
        preconditionFailure("Synthetic recorder has no events")
    }
    return snapshot.events
}

@main
struct ScreenAttributionChecks {
    static func main() {
        let name = CommandLine.arguments.dropFirst().first ?? "current"
        let value = recorder()
        let parent = UUID(), child = UUID()
        switch name {
        case "parent":
            value.pushScreen(.chats, token: parent)
            value.pushScreen(.chat, token: child)
            value.popScreen(token: parent)
            value.record(.action)
            let recorded = events(value)
            precondition(recorded.filter { $0.kind == .screenClose }.map(\.screen) == [.chats],
                         "Closing the parent must identify that parent, not the active child")
            precondition(recorded.last?.kind == .action && recorded.last?.screen == .chat,
                         "Closing a parent must preserve the active child screen")
            value.popScreen(token: child)
            value.record(.action)
            precondition(events(value).last?.screen == .app)
        case "unknown":
            value.pushScreen(.chat, token: child)
            value.popScreen(token: UUID())
            value.record(.action)
            precondition(events(value).filter { $0.kind == .screenClose }.isEmpty,
                         "An unknown token must not fabricate a screen-close event")
            precondition(events(value).last?.screen == .chat)
        case "repeated":
            value.pushScreen(.chat, token: child)
            value.popScreen(token: child)
            value.popScreen(token: child)
            value.record(.action)
            precondition(events(value).filter { $0.kind == .screenClose }.map(\.screen) == [.chat],
                         "A repeated pop must not fabricate a second screen-close event")
            precondition(events(value).last?.screen == .app)
        case "current":
            value.pushScreen(.chat, token: child)
            value.popScreen(token: child)
            value.record(.action)
            precondition(events(value).filter { $0.kind == .screenClose }.map(\.screen) == [.chat])
            precondition(events(value).last?.screen == .app)
        default:
            preconditionFailure("Unknown synthetic case")
        }
        print("PASS: actual Foundation recorder screen attribution: \(name)")
    }
}
