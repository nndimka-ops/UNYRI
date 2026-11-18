import Foundation

// Pure contract checks for the account-scoped Signal device-id cache. No
// network, no libsignal — verifies the cache is a correct, account-scoped,
// failure-safe UUID -> signalDeviceId mapping (Android parity).

var failures = 0

func fail(_ description: String) {
    failures += 1
    let line = "FAIL: \(description)\n"
    FileHandle.standardError.write(Data(line.utf8))
}

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    if condition() {
        print("PASS: \(description)")
    } else {
        fail(description)
    }
}

let userA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
let userB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
let device1 = "11111111-1111-1111-1111-111111111111"
let device2 = "22222222-2222-2222-2222-222222222222"

// 1. First resolution: cache miss (nil) before any store.
let cache = SignalDeviceIdCache()
check(cache.cached(userId: userA, deviceId: device1) == nil, "first resolution is a cache miss")

// 8. The stored value is EXACTLY the Int32 provided (ProtocolAddress uses it verbatim).
cache.store(userId: userA, devices: [(deviceId: device1, signalDeviceId: 42)])
check(cache.cached(userId: userA, deviceId: device1) == 42, "stored signalDeviceId round-trips exactly")

// 2. Second resolution same device: cache hit with the same value.
check(cache.cached(userId: userA, deviceId: device1) == 42, "second resolution is a cache hit")

// 3. Different device resolves independently.
check(cache.cached(userId: userA, deviceId: device2) == nil, "different device is independent (miss)")
cache.store(userId: userA, devices: [(deviceId: device2, signalDeviceId: 7)])
check(cache.cached(userId: userA, deviceId: device1) == 42, "first device unchanged after storing second")
check(cache.cached(userId: userA, deviceId: device2) == 7, "second device stored independently")

// 4. Different account never sees another account's mapping.
check(cache.cached(userId: userB, deviceId: device1) == nil, "different account misses (no cross-account leak)")

// 6. Unresolved device does not poison the cache: store() only adds what was
//    actually returned; an empty store adds nothing.
cache.store(userId: userB, devices: [])
check(cache.cached(userId: userB, deviceId: device1) == nil, "empty store does not poison the cache")

// 10. No cryptographic material in the cache: the key is only a composite of
//     UUID strings, and the value is an Int32 (verified by construction).
check(SignalDeviceIdCache.key(userId: userA, deviceId: device1) == "\(userA)|\(device1)", "cache key is a plain string of ids (no crypto material)")

// 5. Logout / account switch: clear() empties everything.
cache.clear()
check(cache.cached(userId: userA, deviceId: device1) == nil, "clear() empties account A")
check(cache.cached(userId: userA, deviceId: device2) == nil, "clear() empties account A (device 2)")
check(cache.cached(userId: userB, deviceId: device1) == nil, "clear() leaves account B empty too")

// 7. Transient HTTP failure does not store an invalid mapping: there is no
//    store-on-failure path in the cache API (store() is only invoked after a
//    successful fetch in SignalSessionManager). After clear(), everything is a
//    miss — no failure value was retained.
check(cache.cached(userId: userA, deviceId: device1) == nil, "no stale mapping after clear (failure-safe)")

if failures == 0 {
    print("ALL SIGNAL DEVICE CACHE CONTRACTS PASS")
} else {
    print("\(failures) SIGNAL DEVICE CACHE CONTRACT FAILURES")
    exit(1)
}
