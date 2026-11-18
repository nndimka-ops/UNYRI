import Foundation

// Pure voice-message contract checks — no network, no libsignal, no AVFoundation.
// Mirrors Android's VoiceInfoDto (duration_ms), the voice AttachmentMetadata
// shape (media_type=voice, mime_type=audio/mp4), and the "m:ss" duration
// formatting, caught on macOS CI without an iPhone.

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

func expect<T: Equatable>(_ actual: T, _ expected: T, _ description: String) {
    if actual == expected {
        print("PASS: \(description)")
    } else {
        fail("\(description) (expected \(expected), got \(actual))")
    }
}

// MARK: - VoiceInfo (duration_ms)

let voiceInfo = VoiceInfo(durationMs: 12_345)
let voiceInfoJSON = String(decoding: try! JSONEncoder().encode(voiceInfo), as: UTF8.self)
check(voiceInfoJSON.contains("durationMs"), "VoiceInfo uses Android durationMs key")
let voiceInfoRound = try! JSONDecoder().decode(VoiceInfo.self, from: Data(voiceInfoJSON.utf8))
expect(voiceInfoRound.durationMs, 12_345, "VoiceInfo round-trips duration_ms")

// MARK: - Voice AttachmentMetadata (media_type=voice, mime_type=audio/mp4)

let voiceMeta = AttachmentMetadata(
    attachmentId: "att-voice",
    mediaType: AttachmentMetadata.mediaTypeVoice,
    objectKey: "obj-voice",
    size: 20_000,
    encryptedSize: 20_050,
    mimeType: "audio/mp4",
    encryption: EncryptionInfo(version: 1, key: "a2V5", nonce: "bm9uY2U=", keyset: nil, sha256: "c2hh"),
    filename: nil,
    image: nil,
    voice: VoiceInfo(durationMs: 8_000),
    thumbnail: nil
)
let metaJSON = String(decoding: try! JSONEncoder().encode(voiceMeta), as: UTF8.self)
check(metaJSON.contains("media_type"), "voice metadata uses media_type")
check(metaJSON.contains("mime_type"), "voice metadata uses mime_type key")
check(metaJSON.contains("durationMs"), "voice metadata carries Android durationMs")
let metaRound = try! JSONDecoder().decode(AttachmentMetadata.self, from: Data(metaJSON.utf8))
expect(metaRound, voiceMeta, "voice AttachmentMetadata round-trips exactly")
expect(metaRound.mimeType, "audio/mp4", "voice metadata mime_type is audio/mp4")
expect(metaRound.voice?.durationMs, 8_000, "voice duration_ms preserved")
check(metaRound.filename == nil, "voice metadata has no filename (Android parity)")

// MARK: - MessageContent round-trip (kind=attachment, voice)

let content = MessageContent(kind: MessageContent.kindAttachment, attachment: voiceMeta)
let contentJSON = String(decoding: try! JSONEncoder().encode(content), as: UTF8.self)
let contentRound = try! JSONDecoder().decode(MessageContent.self, from: Data(contentJSON.utf8))
expect(contentRound.kind, MessageContent.kindAttachment, "voice content kind=attachment")
expect(contentRound.attachment?.mediaType, AttachmentMetadata.mediaTypeVoice, "voice content media_type=voice")

// The codec path (encodeAttachment/decode) preserves voice metadata end-to-end.
let encoded = MessageContentCodec.encodeAttachment(voiceMeta)
let decoded = MessageContentCodec.decode(encoded)
expect(decoded.attachment?.voice?.durationMs, 8_000, "codec preserves voice duration")

// MARK: - AttachmentRecord -> metadata mapping (durationMs)

let record = AttachmentRecord(
    attachmentId: "att-voice", clientMessageId: "m1", chatId: "c1",
    mediaType: "voice", mimeType: "audio/mp4", filename: nil,
    size: 20_000, width: nil, height: nil, durationMs: 8_000,
    thumbnailBase64: nil, localPath: nil,
    downloadState: "not_downloaded", uploadState: "uploaded", progress: 0,
    objectKey: "obj-voice", encryptedSize: 20_050, encryptionVersion: 1,
    keyBase64: "a2V5", nonceBase64: "bm9uY2U=", keysetBase64: nil, sha256Base64: "c2hh"
)
let mapped = attachmentMetadata(from: record)
expect(mapped.mediaType, AttachmentMetadata.mediaTypeVoice, "record->metadata mediaType voice")
expect(mapped.voice?.durationMs, 8_000, "record->metadata durationMs")
expect(mapped.mimeType, "audio/mp4", "record->metadata mime audio/mp4")

// MARK: - Duration formatting (Android "%d:%02d")

expect(VoiceFormat.durationText(0), "0:00", "duration 0 -> 0:00")
expect(VoiceFormat.durationText(1_000), "0:01", "duration 1s -> 0:01")
expect(VoiceFormat.durationText(59_000), "0:59", "duration 59s -> 0:59")
expect(VoiceFormat.durationText(65_000), "1:05", "duration 65s -> 1:05")
expect(VoiceFormat.durationText(600_000), "10:00", "duration 10min -> 10:00")
expect(VoiceFormat.durationText(-5), "0:00", "negative duration clamps to 0:00")

for index in [Int.min, -1, 0, 1, 21, Int.max] {
    let height = VoiceFormat.waveformHeight(id: "voice-test", bar: index)
    check(height >= 6 && height < 28, "waveform stays bounded at extreme indices")
}
expect(VoiceFormat.waveformHeight(id: "voice-test", bar: 7), VoiceFormat.waveformHeight(id: "voice-test", bar: 7), "waveform is stable")

// MARK: - Played fraction, bar count, hour formatting (Phase 5)

expect(VoiceFormat.playedFraction(positionMs: 0, durationMs: 5_000), 0.0, "fraction at start")
expect(VoiceFormat.playedFraction(positionMs: 5_000, durationMs: 5_000), 1.0, "fraction at end")
expect(VoiceFormat.playedFraction(positionMs: 2_500, durationMs: 5_000), 0.5, "fraction mid")
expect(VoiceFormat.playedFraction(positionMs: -100, durationMs: 5_000), 0.0, "fraction clamps low")
expect(VoiceFormat.playedFraction(positionMs: 10_000, durationMs: 5_000), 1.0, "fraction clamps high")
check(VoiceFormat.playedFraction(positionMs: 0, durationMs: 0) == 0.0, "zero duration cannot divide by zero")
expect(VoiceFormat.barCount(availableWidth: 60), 20, "bar count floors at 20")
expect(VoiceFormat.barCount(availableWidth: 200), 32, "bar count caps at 32")
check(VoiceFormat.barCount(availableWidth: 118) >= 20 && VoiceFormat.barCount(availableWidth: 118) <= 32, "bar count stays in signature range")
expect(VoiceFormat.durationText(3_600_000), "1:00:00", "hour duration formats h:mm:ss")

if failures > 0 {
    print("\(failures) voice contract check(s) failed")
    exit(1)
}
print("Voice contract checks passed")
