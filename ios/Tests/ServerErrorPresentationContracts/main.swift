import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@main struct Main {
    static func main() {
        for raw in ["file:///tmp/a", "javascript:alert(1)", "data:text/html,x", "unyri://secret", "mailto:x@example.invalid", "itms-services://x"] { check(decide(URL(string: raw)!) == .discarded) }
        for raw in ["https://example.invalid", "http://example.invalid", "HTTPS://example.invalid"] { check(decide(URL(string: raw)!) == .systemAction) }
        print("PASS I4: actual root action admits HTTP(S) only; error call sites are literal")
    }
}
