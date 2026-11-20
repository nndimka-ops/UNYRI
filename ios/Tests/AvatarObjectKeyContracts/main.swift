import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@main struct Main {
    static func main() {
        for value in ["", "https://attacker.invalid/avatar.jpg", "HTTP://attacker.invalid/a", "file:///tmp/a", "data:image/png,x", "avatars/", "avatars/../a", "avatars/%2e%2e/a", "avatars/a\\b", "avatars/http:x"] { check(!AvatarSubject.isAvatarObjectKey(value)) }
        check(AvatarSubject.isAvatarObjectKey("avatars/owner/random.jpg"))
        print("PASS S1/I1: external profile values cannot enter avatar resolution")
    }
}
