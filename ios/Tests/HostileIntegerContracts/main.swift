import Foundation
private func check(_ value: Bool) { precondition(value) }

private func rejects(_ operation: () throws -> Void) {
    do { try operation(); fatalError("Expected checked numeric error") } catch {}
}
@main struct Main {
    static func main() throws {
        for value in [Int32.min, -1, 0, 128, Int32.max] { rejects { _ = try HostileInteger.unsigned(value, device: true) } }
        check(try HostileInteger.unsigned(127, device: true) == 127)
        rejects { _ = try HostileInteger.signedKey(UInt32.max) }
        for value in [Int.min, -1, 17, Int.max, Int(Int32.max), Int(Int32.max) + 1] { rejects { _ = try HostileInteger.iceIndex(value) } }
        check(try HostileInteger.iceIndex(16) == 16)
        for value in [Double.nan, .infinity, -.infinity, Double(Int64.max), -Double.greatestFiniteMagnitude] {
            rejects { _ = try HostileInteger.int64(value) }
            rejects { _ = try HostileInteger.int(value) }
        }
        check(try HostileInteger.int64(-1.5) == -1)
        rejects { _ = try OneTimePreKeyAllocation.nextBatch(nextId: Int32.max, count: 1) }
        rejects { _ = try OneTimePreKeyAllocation.nextBatch(nextId: 1, count: -1) }
        rejects { _ = try OneTimePreKeyAllocation.nextBatch(nextId: 1, count: Int.max) }
        check(try OneTimePreKeyAllocation.nextBatch(nextId: 1, count: 2).ids == [1, 2])
        func bundle(_ device: Int32 = 1, registration: Int32 = 1, signed: Int32 = 1, kyber: Int32 = 1, oneTime: Int32? = nil) -> DeviceBundle {
            DeviceBundle(deviceId: "device", signalDeviceId: device, registrationId: registration, identityKey: "YQ==", signedPrekeyId: signed,
                signedPrekeyPublic: "YQ==", signedPrekeySignature: "YQ==", kyberPrekeyId: kyber, kyberPrekeyPublic: "YQ==", kyberPrekeySignature: "YQ==",
                oneTimePrekeyId: oneTime, oneTimePrekeyPublic: oneTime == nil ? nil : "YQ==")
        }
        for value in [Int32.min, -1] {
            for dto in [bundle(value), bundle(registration: value), bundle(signed: value), bundle(kyber: value), bundle(oneTime: value)] {
                rejects { _ = try SignalBundleDecoder.decode(dto) }
            }
        }
        _ = try SignalBundleDecoder.decode(bundle())
        for index in ["-1", "17", "2147483647", "2147483648", "9223372036854775807", "1e100", "1.5"] {
            let json = "{\"type\":\"call.ice\",\"call_id\":\"a\",\"candidate\":\"x\",\"sdp_m_line_index\":\(index)}"
            precondition(CallSignalingEventParser.parse(Data(json.utf8)) == nil)
        }
        let valid = Data("{\"type\":\"call.ice\",\"call_id\":\"a\",\"candidate\":\"x\",\"sdp_m_line_index\":16}".utf8)
        precondition(CallSignalingEventParser.parse(valid) != nil)
        try HostileInteger.multipart(encryptedSize: 10, partSize: 5 * 1024 * 1024, numbers: [1], sizes: [10])
        for size in [Int64.min, -1, 0, Int64.max] {
            rejects { try HostileInteger.multipart(encryptedSize: size, partSize: 5 * 1024 * 1024, numbers: [1], sizes: [10]) }
            rejects { try HostileInteger.multipart(encryptedSize: 10, partSize: 5 * 1024 * 1024, numbers: [1], sizes: [size]) }
        }
        for number in [Int.min, -1, 0, Int.max] {
            rejects { try HostileInteger.multipart(encryptedSize: 10, partSize: 5 * 1024 * 1024, numbers: [number], sizes: [10]) }
        }
        rejects { try HostileInteger.multipart(encryptedSize: 10, partSize: Int64.max, numbers: [1], sizes: [10]) }
        rejects { try HostileInteger.multipart(encryptedSize: 10, partSize: 5 * 1024 * 1024, numbers: Array(1...22), sizes: Array(repeating: 10, count: 22)) }
        print("Hostile F11 numeric ingress contracts passed")
    }
}
