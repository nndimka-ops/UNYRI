import Foundation
import Security

// Exercise the real Keychain adapter, namespace, queue and journal together.
// Security calls are replaced at their call boundary; no real Keychain is used.
private final class KeychainProbe: @unchecked Sendable {
    struct Counts: Equatable {
        var matching = 0
        var update = 0
        var add = 0
        var delete = 0
        var total: Int { matching + update + add + delete }
    }
    var values: [String: Data] = ["fixture:session": Data([0])]
    var counts = Counts()

    private func account(_ query: [String: Any]) -> String {
        precondition(!Thread.isMainThread, "Security API reached the UI thread")
        return query[kSecAttrAccount as String] as! String
    }

    func operations() -> SignalKeychainOperations {
        SignalKeychainOperations(
            matching: { (query: [String: Any]) -> (OSStatus, CFTypeRef?) in
                precondition(!Thread.isMainThread, "Security lookup reached the UI thread")
                self.counts.matching += 1
                if query[kSecReturnAttributes as String] as? Bool == true {
                    let rows = self.values.keys.sorted().map { (key: String) -> [String: Any] in
                        [kSecAttrAccount as String: key]
                    }
                    return (errSecSuccess, rows as NSArray)
                }
                guard let data = self.values[self.account(query)] else { return (errSecItemNotFound, nil) }
                return (errSecSuccess, data as NSData)
            },
            update: { (query: [String: Any], attributes: [String: Any]) -> OSStatus in
                self.counts.update += 1
                let key = self.account(query)
                guard self.values[key] != nil else { return errSecItemNotFound }
                self.values[key] = attributes[kSecValueData as String] as? Data
                return errSecSuccess
            },
            add: { (query: [String: Any]) -> OSStatus in
                self.counts.add += 1
                let key = self.account(query)
                precondition(self.values[key] == nil)
                self.values[key] = query[kSecValueData as String] as? Data
                return errSecSuccess
            },
            delete: { (query: [String: Any]) -> OSStatus in
                self.counts.delete += 1
                let key = self.account(query)
                guard self.values.removeValue(forKey: key) != nil else { return errSecItemNotFound }
                return errSecSuccess
            })
    }
}

@main struct Main {
    @MainActor static func main() async throws {
        let probe = KeychainProbe()
        let keychain = SignalKeychainStore(operations: probe.operations())
        let state = SignalStateStore(keychain: keychain, namespace: { "fixture" })
        for index in 0..<3 {
            probe.counts = KeychainProbe.Counts()
            let id = "message-" + String(index)
            let recovered = try await state.perform { try state.recoveredReceive(id: id) }
            precondition(recovered == nil)
            let plaintext = try await state.perform {
                try state.receive(id: id, metadata: Data([1])) {
                    // Deterministic established-session receive: one session
                    // read and two callbacks writing that same session key.
                    let previous = try state.data(forKey: "session")
                    precondition(previous != nil)
                    try state.setData(Data([1]), forKey: "session")
                    try state.setData(Data([2]), forKey: "session")
                    return Data("message".utf8)
                }
            }
            precondition(plaintext == Data("message".utf8))
            try await state.perform { try state.finishReceive(id: id) }
            let expectedReads = index == 0 ? 4 : 2
            let expected = KeychainProbe.Counts(matching: expectedReads, update: 3, add: 2, delete: 2)
            precondition(probe.counts == expected, "Unexpected per-message Security API count: \(probe.counts)")
            print("Security calls/message: \(probe.counts.total) (matching=\(expectedReads), update=3, add=2, delete=2), main-thread=0")
        }
        probe.counts = KeychainProbe.Counts()
        for _ in 0..<500 {
            try await state.perform { try state.finishReceive(id: "message-2") }
        }
        precondition(probe.counts.total == 0, "Persisted history performed absent Keychain work")
        print("PASS: real adapter call count, coalesced callbacks, background I/O and zero-call duplicate acknowledgements")
    }
}
