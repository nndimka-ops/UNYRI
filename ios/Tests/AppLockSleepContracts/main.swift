import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@main struct Main {
    static func main() {
        let start = ContinuousClock.now
        for timeout in [60.0, 300.0] {
            var lock = AppLockPolicy(); lock.enabled = true; lock.timeout = timeout; lock.unlock()
            lock.background(at: 1000, continuous: start)
            lock.activate(at: 1001, continuous: start + .seconds(timeout + 1))
            check(lock.locked) // Sleep elapsed, wall clock was moved backwards by almost all elapsed time.
            lock.unlock(); lock.background(at: 1000, continuous: start)
            lock.activate(at: 999, continuous: start + .seconds(1)); check(lock.locked)
            lock.unlock(); lock.background(at: 1000, continuous: start)
            lock.activate(at: 1001, continuous: start + .seconds(1)); check(!lock.locked)
            lock.invalidate(); check(lock.locked)
        }
        print("PASS L2: continuous sleep, wall rollback, protected-data invalidation, short honest background")
    }
}
