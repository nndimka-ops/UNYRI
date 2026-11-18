import Foundation

protocol PermanentSignalInputError: Error {}

enum HostileInteger {
    enum Failure: PermanentSignalInputError { case outOfRange }
    static func unsigned(_ value: Int32, device: Bool = false) throws -> UInt32 {
        guard let result = UInt32(exactly: value), !device || (1...127).contains(result) else { throw Failure.outOfRange }
        return result
    }
    static func iceIndex(_ value: Int) throws -> Int32 {
        guard (0...16).contains(value), let result = Int32(exactly: value) else { throw Failure.outOfRange }
        return result
    }
    static func int64(_ value: Double) throws -> Int64 {
        guard let result = Int64(exactly: value.rounded(.towardZero)) else { throw Failure.outOfRange }
        return result
    }
    static func int(_ value: Double) throws -> Int {
        guard let result = Int(exactly: value.rounded(.towardZero)) else { throw Failure.outOfRange }
        return result
    }
    static func signedKey(_ value: UInt32) throws -> Int32 {
        guard let result = Int32(exactly: value) else { throw Failure.outOfRange }
        return result
    }
    static func multipart(encryptedSize: Int64, partSize: Int64, numbers: [Int], sizes: [Int64]) throws {
        guard (1...104_861_696).contains(encryptedSize), partSize == 5 * 1024 * 1024,
              !numbers.isEmpty, numbers.count <= 21, numbers.count == sizes.count else { throw Failure.outOfRange }
        var total: Int64 = 0
        for index in numbers.indices {
            let offset = Int64(index) * partSize // index <= 20, fixed part size
            guard numbers[index] == index + 1, offset < encryptedSize,
                  sizes[index] == min(partSize, encryptedSize - offset) else { throw Failure.outOfRange }
            total += sizes[index]
        }
        guard total == encryptedSize else { throw Failure.outOfRange }
    }
}
