import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
@MainActor final class VoiceWaveformMemory {
    static let shared = VoiceWaveformMemory()
    func get(_ path: String) -> [Double]? { nil }
}
@MainActor final class VoiceWaveformCache {
    static let shared = VoiceWaveformCache(); var decodes = 0
    func cached(path: String) async -> [Double] { [] }
    func load(path: String) async -> [Double] { decodes += 1; return [0.3, 0.8] }
}
@main struct Main {
    @MainActor static func main() async {
        let subject = EnvelopeSubject()
        await subject.update(path: "/unplayed.m4a", embedded: nil, decodeOnPlay: false, isMenuPreview: false)
        check(subject.samples.isEmpty && VoiceWaveformCache.shared.decodes == 0)
        await subject.update(path: nil, embedded: nil, decodeOnPlay: false, isMenuPreview: false)
        check(VoiceWaveformCache.shared.decodes == 0)
        await subject.update(path: "/playing.m4a", embedded: nil, decodeOnPlay: true, isMenuPreview: false)
        check(subject.samples == [0.3, 0.8] && VoiceWaveformCache.shared.decodes == 1)
        await subject.update(path: "/wire.m4a", embedded: [0.5], decodeOnPlay: false, isMenuPreview: false)
        check(subject.samples == [0.5] && VoiceWaveformCache.shared.decodes == 1)
        print("PASS I3: actual waveform task never decodes unplayed audio")
    }
}
