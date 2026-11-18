import Foundation
import Dispatch

func check(_ value: Bool, _ message: String) {
    guard value else { fatalError(message) }
}

let root = FileManager.default.temporaryDirectory.appendingPathComponent("account-avatar-contract-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = AccountAvatarStore(root: root)
let slot = "s0123456789abcdef"
let owner = AccountAvatarStore.owner(origin: "https://qa.invalid/", user: "USER-A")
let peer = AccountAvatarStore.owner(origin: "https://qa.invalid", user: "USER-B")
let a = try store.begin(slot: "primary", owner: owner, revision: "1")
let b = try store.begin(slot: slot, owner: peer, revision: "1")
try check(store.write(Data([1, 2]), ticket: a), "write")
try check(store.write(Data([9]), ticket: b), "other write")
try check(store.read(slot: "primary", owner: owner)?.jpeg == Data([1, 2]), "own read")
try check(store.read(slot: "primary", owner: peer) == nil, "owner isolation")
try check(store.read(slot: slot, owner: owner) == nil, "slot isolation")
let next = try store.begin(slot: "primary", owner: owner, revision: "2")
try check(!store.write(Data([8]), ticket: a), "old revision rejected")
try check(store.write(Data([3]), ticket: next), "replace")
try check(!store.write(Data([3]), ticket: next), "unchanged does not write")
let reopened = AccountAvatarStore(root: root)
try check(reopened.read(slot: "primary", owner: owner)?.jpeg == Data([3]), "persistent replace")
try check(reopened.read(slot: slot, owner: peer)?.jpeg == Data([9]), "persistent other slot")
try Data([7]).write(to: root.appendingPathComponent("primary/interrupted.tmp"))
try check(store.write(nil, ticket: next), "avatar removal")
try check(!store.write(Data([8]), ticket: next), "deleted avatar rejects late encoder")
check(!FileManager.default.fileExists(atPath: root.appendingPathComponent("primary").path), "interrupted writes erased")
try check(store.read(slot: "primary", owner: owner) == nil, "deleted")
try check(store.read(slot: slot, owner: peer)?.jpeg == Data([9]), "other survives deletion")
try store.purge(slot: "primary")
try check(!store.write(Data([5]), ticket: next), "purged encoder rejected")
let reused = try store.begin(slot: "primary", owner: peer, revision: "1")
try store.write(Data([4]), ticket: reused)
try check(store.read(slot: "primary", owner: owner) == nil, "reused slot owner")
try store.purgeAll()
try check(store.read(slot: slot, owner: peer) == nil, "full purge")
try check(!store.write(Data([5]), ticket: b), "full purge rejects late other encoder")
try check(!store.write(Data([5]), ticket: reused), "full purge rejects late reused encoder")
try store.purgeAll()
for invalid in ["../primary", "", "/tmp/avatar", "PRIMARY", slot + "/../primary"] {
    do { _ = try store.begin(slot: invalid, owner: owner, revision: "1"); fatalError("invalid slot") } catch {}
    do { _ = try store.read(slot: invalid, owner: owner); fatalError("invalid read") } catch {}
    do { try store.purge(slot: invalid); fatalError("invalid purge") } catch {}
}
let oversized = try store.begin(slot: "primary", owner: owner, revision: "3")
do { try store.write(Data(repeating: 1, count: 128 * 1024 + 1), ticket: oversized); fatalError("oversized") } catch {}
try FileManager.default.createDirectory(at: root.appendingPathComponent("primary"), withIntermediateDirectories: true)
try Data([1, 2, 3]).write(to: root.appendingPathComponent("primary/thumbnail.json"))
try check(store.read(slot: "primary", owner: owner) == nil, "corrupt file fallback")
print("Account avatar store contracts passed")

for invalidOwner in ["", "https://qa.invalid|", "https://qa.invalid| ", "missing-user-separator"] {
    do { _ = try store.begin(slot: "primary", owner: invalidOwner, revision: "1"); fatalError("empty owner user accepted") } catch {}
}

// Exercise the same memory cache the UI reads synchronously on its first frame.
let memory = AccountAvatarMemory<Data>()
var decodes = 0
let warm = try store.begin(slot: "primary", owner: owner, revision: "4")
try store.write(Data([4]), ticket: warm)
let first = try memory.load(slot: "primary", owner: owner, read: { try store.read(slot: "primary", owner: owner) },
    decode: { decodes += 1; return $0 })
check(first == Data([4]) && memory.peek(slot: "primary", owner: owner) == first, "warm first-render cache")
check(memory.peek(slot: "primary", owner: peer) == nil, "decoded owner binding")
check(memory.peek(slot: slot, owner: owner) == nil, "decoded slot binding")
_ = memory.load(slot: "primary", owner: owner, read: { fatalError("warm cache read disk") },
    decode: { _ in fatalError("warm cache decoded again") })
check(decodes == 1, "decode once")
memory.clear()
check(memory.peek(slot: "primary", owner: owner) == nil, "purge clears decoded image")
let staleDecode = try memory.load(slot: "primary", owner: owner, read: { try store.read(slot: "primary", owner: owner) },
    decode: { memory.clear(); return $0 })
check(staleDecode == nil && memory.peek(slot: "primary", owner: owner) == nil, "purge wins against running decoder")

// Suspend a real writer after encoding. begin/invalidate must remain responsive,
// and a superseded ticket must not replace the new generation's thumbnail.
let entered = DispatchSemaphore(value: 0), resume = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
let racing = AccountAvatarStore(root: root.appendingPathComponent("race"), beforeCommit: {
    entered.signal(); resume.wait()
})
let obsolete = try racing.begin(slot: "primary", owner: owner, revision: "1")
DispatchQueue.global().async {
    defer { done.signal() }
    do { let written = try racing.write(Data([1]), ticket: obsolete); check(!written, "stale prepared write rejected") }
    catch { fatalError("Unexpected writer failure: \(error)") }
}
check(entered.wait(timeout: .now() + 5) == .success, "writer reached barrier")
let reserved = DispatchSemaphore(value: 0)
DispatchQueue.global().async {
    _ = try! racing.begin(slot: "primary", owner: peer, revision: "2")
    racing.invalidate(slot: "primary")
    reserved.signal()
}
let responsive = reserved.wait(timeout: .now() + 2) == .success
resume.signal()
check(responsive, "ticket operations blocked behind disk preparation")
check(done.wait(timeout: .now() + 5) == .success, "writer finished")
try check(racing.read(slot: "primary", owner: owner) == nil, "obsolete bytes never published")
try racing.purgeAll()
print("Account avatar memory, owner hardening and concurrent ticket contracts passed")
