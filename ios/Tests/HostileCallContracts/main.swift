import Foundation
#if canImport(CryptoKit)
@main struct Main {
    static func main() {
        let a = "sha-256 " + Array(repeating: "11", count: 32).joined(separator: ":")
        let b = "sha-256 " + Array(repeating: "22", count: 32).joined(separator: ":")
        let attacker = "sha-256 " + Array(repeating: "33", count: 32).joined(separator: ":")
        func sdp(_ fingerprint: String) -> String { "v=0\r\na=fingerprint:" + fingerprint + "\r\n" }
        let af = CallDtlsFingerprints(local: a, remote: b), bf = CallDtlsFingerprints(local: b, remote: a)
        var alice = CallSASHandshake(), bob = CallSASHandshake()
        let offer = alice.offer(sdp(a), callID: "call", nonce: String(repeating: "01", count: 32))
        bob.receiveOffer(offer, callID: "call")
        precondition(alice.transcript(callID: "call", fingerprints: af) == nil)
        let answer = bob.answer(sdp(b), callID: "call", nonce: String(repeating: "02", count: 32))
        precondition(bob.transcript(callID: "call", fingerprints: bf) == nil)
        guard let reveal = alice.receiveAnswer(answer, callID: "call") else { preconditionFailure("No reveal") }
        bob.receiveReveal(reveal, callID: "other-call")
        precondition(bob.transcript(callID: "call", fingerprints: bf) == nil)
        bob.receiveReveal(reveal + "00", callID: "call")
        precondition(bob.transcript(callID: "call", fingerprints: bf) == nil)
        bob.receiveReveal(reveal, callID: "call")
        let ac = alice.transcript(callID: "call", fingerprints: af).flatMap(CallVerificationCode.indices)
        let bc = bob.transcript(callID: "call", fingerprints: bf).flatMap(CallVerificationCode.indices)
        precondition(ac != nil && ac == bc && ac?.count == 4)
        precondition(alice.transcript(callID: "call", fingerprints: CallDtlsFingerprints(local: a, remote: attacker)) == nil)
        // Stripped/legacy or conflicting attributes never manufacture a code.
        var legacy = CallSASHandshake()
        legacy.receiveOffer(sdp(a), callID: "call")
        _ = legacy.answer(sdp(b), callID: "call")
        precondition(legacy.transcript(callID: "call", fingerprints: bf) == nil)
        var duplicate = CallSASHandshake()
        duplicate.receiveOffer(offer + offer, callID: "call")
        _ = duplicate.answer(sdp(b), callID: "call")
        duplicate.receiveReveal(reveal, callID: "call")
        precondition(duplicate.transcript(callID: "call", fingerprints: bf) == nil)
        _ = alice.offer(sdp(a), callID: "call", nonce: String(repeating: "03", count: 32))
        precondition(alice.transcript(callID: "call", fingerprints: af) == nil)
        print("Hostile call commitment exchange, both-side code, downgrade and renegotiation contracts passed")
    }
}
#else
#error("Call verification contracts require the system CryptoKit module")
#endif
