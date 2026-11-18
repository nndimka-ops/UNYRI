import Foundation

/// Commitment exchange travels in opaque SDP attributes and an existing call.ice
/// frame. No server API change. Unknown/legacy peers show no verified code.
struct CallSASHandshake {
    private(set) var callID: String?
    private var localIsOfferer = false
    private var commit: String?
    private var offerNonce: String?
    private var answerNonce: String?
    private var offeredFingerprint: String?
    private var answeredFingerprint: String?
    static let revealPrefix = "unyri-sas-2:"

    private static func attribute(_ name: String, in sdp: String) -> String? {
        let prefix = "a=x-unyri-sas-" + name + ":"
        let values = sdp.split(whereSeparator: \.isNewline).filter { $0.hasPrefix(prefix) }
        guard values.count == 1 else { return nil }
        return String(values[0].dropFirst(prefix.count))
    }
    mutating func offer(_ sdp: String, callID: String, nonce: String = CallVerificationCode.newNonce()) -> String {
        self = Self(); self.callID = callID; localIsOfferer = true
        guard let fingerprint = CallDtlsFingerprints.fingerprint(inSdp: sdp) else { return sdp }
        guard let value = CallVerificationCode.commitment(fingerprint: fingerprint, nonce: nonce) else { return sdp }
        offeredFingerprint = fingerprint; offerNonce = nonce; commit = value
        return sdp + "a=x-unyri-sas-commit:" + value + "\r\n"
    }
    mutating func receiveOffer(_ sdp: String, callID: String) {
        self = Self(); self.callID = callID
        guard let value = Self.attribute("commit", in: sdp), CallVerificationCode.isHex32(value),
              let fingerprint = CallDtlsFingerprints.fingerprint(inSdp: sdp) else { return }
        commit = value; offeredFingerprint = fingerprint
    }
    mutating func answer(_ sdp: String, callID: String, nonce: String = CallVerificationCode.newNonce()) -> String {
        guard self.callID == callID, !localIsOfferer, let commit,
              let fingerprint = CallDtlsFingerprints.fingerprint(inSdp: sdp) else { return sdp }
        guard CallVerificationCode.isHex32(nonce) else { return sdp }
        answeredFingerprint = fingerprint; answerNonce = nonce
        return sdp + "a=x-unyri-sas-answer:" + commit + ":" + (answerNonce ?? "") + "\r\n"
    }
    mutating func receiveAnswer(_ sdp: String, callID: String) -> String? {
        guard self.callID == callID, localIsOfferer, let commit, let offerNonce,
              let value = Self.attribute("answer", in: sdp) else { answerNonce = nil; return nil }
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, String(parts[0]) == commit, CallVerificationCode.isHex32(String(parts[1])),
              let fingerprint = CallDtlsFingerprints.fingerprint(inSdp: sdp) else { answerNonce = nil; return nil }
        answeredFingerprint = fingerprint; answerNonce = String(parts[1])
        return Self.revealPrefix + commit + ":" + offerNonce
    }
    mutating func receiveReveal(_ candidate: String, callID: String) {
        guard self.callID == callID, !localIsOfferer, answerNonce != nil, let commit, let offeredFingerprint else { return }
        let parts = candidate.dropFirst(Self.revealPrefix.count).split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, String(parts[0]) == commit,
              CallVerificationCode.commitment(fingerprint: offeredFingerprint, nonce: String(parts[1])) == commit else { return }
        offerNonce = String(parts[1])
    }
    func transcript(callID: String, fingerprints: CallDtlsFingerprints) -> CallVerificationTranscript? {
        guard self.callID == callID, let commit, let offerNonce, let answerNonce,
              offeredFingerprint == (localIsOfferer ? fingerprints.local : fingerprints.remote),
              answeredFingerprint == (localIsOfferer ? fingerprints.remote : fingerprints.local) else { return nil }
        return CallVerificationTranscript(fingerprints: fingerprints, localIsOfferer: localIsOfferer,
            offererCommitment: commit, offererNonce: offerNonce, answererNonce: answerNonce)
    }
}
