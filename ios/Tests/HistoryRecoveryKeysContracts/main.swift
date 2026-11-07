import Foundation

private let server = "11111111-1111-4111-8111-111111111111"
private let owner = "22222222-2222-4222-8222-222222222222"
private let other = "33333333-3333-4333-8333-333333333333"
private let key = Data((0..<32).map(UInt8.init))
private enum Failure: Error { case unavailable }
private final class Storage {
    var values: [String: Data] = [:]
    var writes = 0
    var locked = false
    var loseWrite = false
    func keys(_ server: String = server, _ owner: String = owner) throws -> HistoryRecoveryKeys {
        try HistoryRecoveryKeys(server: server, owner: owner, read: { name in
            guard !self.locked else { throw Failure.unavailable }
            return self.values[name]
        }, insert: { name, data in
            self.writes += 1
            guard self.values[name] == nil else { throw Failure.unavailable }
            if !self.loseWrite { self.values[name] = data }
        }, replace: { name, data in
            guard !self.locked else { throw Failure.unavailable }
            self.writes += 1
            if !self.loseWrite { self.values[name] = data }
        })
    }
}
private func rejects(_ operation: () throws -> Void) {
    do { try operation(); fatalError("Expected rejection") } catch {}
}
private func check(_ value: Bool) { precondition(value) }
@main struct Main {
    static func main() throws {
        let storage = Storage(), keys = try storage.keys()
        check(try keys.load() == nil)
        try keys.saveConfirmed(key)
        var copy = try keys.load()!
        copy.resetBytes(in: copy.startIndex..<copy.endIndex)
        check(try keys.load() == key)
        try keys.saveConfirmed(key)
        precondition(storage.writes == 1)
        rejects { try keys.saveConfirmed(Data(repeating: 9, count: 32)) }
        check(try keys.load() == key)
        check(try storage.keys(other, owner).load() == nil)
        check(try storage.keys(server, other).load() == nil)
        storage.locked = true
        rejects { _ = try keys.load() }
        rejects { try keys.saveConfirmed(key) }
        precondition(storage.writes == 1)
        storage.locked = false
        storage.values["history-v1:\(server):\(owner)"] = Data([1, 2, 3])
        rejects { try keys.saveConfirmed(key) }
        precondition(storage.writes == 1)
        let lost = Storage(); lost.loseWrite = true
        rejects { try lost.keys().saveConfirmed(key) }
        rejects { _ = try lost.keys("../server", owner) }
        let invalid = Storage()
        rejects { try invalid.keys().saveConfirmed(Data(repeating: 0, count: 16)) }
        precondition(invalid.writes == 0)
        let adoption = Storage(), adopted = try adoption.keys()
        try adopted.saveConfirmed(key)
        let candidate = Data(repeating: 7, count: 32)
        rejects { try adopted.replaceVerified(candidate, expected: nil) }
        check(try adopted.load() == key)
        adoption.locked = true
        rejects { try adopted.replaceVerified(candidate, expected: key) }
        adoption.locked = false
        adoption.loseWrite = true
        rejects { try adopted.replaceVerified(candidate, expected: key) }
        check(try adopted.load() == key)
        adoption.loseWrite = false
        try adopted.replaceVerified(candidate, expected: key)
        check(try adopted.load() == candidate)
        rejects { try adopted.replaceVerified(key, expected: key) }
        check(try adopted.load() == candidate)
        print("History recovery key contracts passed: scope, protected read failures, atomic adoption, stale compare-and-set")
    }
}
