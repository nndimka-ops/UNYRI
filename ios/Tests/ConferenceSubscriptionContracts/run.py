"""Execute production subscription reconciliation across page/room races."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")


def method(marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func", "func", 1)


harness = r'''
import Foundation
enum Kind { case audio, video }
enum Track { enum Source: Int { case microphone = 1, camera = 2 } }
final class ParticipantPermissions { var canPublish = true; var canPublishSources = Set<Int>() }
struct ConferenceMetadata {
    struct Member { let participantIdentity: String?; let audioPublishBlocked: Bool? }
    var participants: [Member]
}
enum Encryption { case gcm, none }
enum SignalingFailure: Error { case offline }
struct Identity { let stringValue: String }
@MainActor final class Gate {
    var continuation: CheckedContinuation<Void, Never>?
    var entered = false
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class RemoteAudioTrack {
    var writes = 0
    var volume: Double = 1 { didSet { writes += 1 } }
}
@MainActor class Publication { var track: RemoteAudioTrack? }
struct Tile { let id: String }

@MainActor final class RemoteTrackPublication: Publication {
    let sid: Identity
    let kind: Kind
    var source = Track.Source.camera
    var encryptionType = Encryption.gcm
    var enabled = false
    var gate: Gate?
    var calls = 0
    var preferred: Bool?
    var failNext = false
    init(_ id: String, _ kind: Kind) { sid = Identity(stringValue: id); self.kind = kind; super.init(); if kind == .audio { track = RemoteAudioTrack() } }
    func set(subscribed: Bool) async throws {
        guard preferred != subscribed else { return }
        preferred = subscribed
        calls += 1
        if failNext { failNext = false; throw SignalingFailure.offline }
        if let gate { self.gate = nil; await gate.wait() }
        enabled = subscribed
    }
}
@MainActor final class Participant {
    let identity: Identity?
    let permissions = ParticipantPermissions()
    let audio: RemoteTrackPublication
    let video: RemoteTrackPublication
    var audioTracks: [Publication] { [audio] }
    var videoTracks: [Publication] { [video] }
    init(_ id: Int, suffix: String = "") {
        identity = Identity(stringValue: "p\(id)")
        audio = RemoteTrackPublication("a\(id)" + suffix, .audio)
        video = RemoteTrackPublication("v\(id)" + suffix, .video)
    }
}
@MainActor final class Room {
    var remoteParticipants: [Int: Participant] = [:]
    init(_ count: Int = 30, suffix: String = "") {
        for id in 0..<count { remoteParticipants[id] = Participant(id, suffix: suffix) }
    }
}
@MainActor final class Owner {
    var room: Room?
    var isBusy = true
    var conference: ConferenceMetadata?
    var participantPermissionObservers: [Int: Int] = [:]
    var remoteTiles: [Tile] { room?.remoteParticipants.values.map { Tile(id: $0.identity!.stringValue) } ?? [] }
    var locallyMutedParticipantIDs = Set<String>(), locallyHiddenParticipantIDs = Set<String>()
    var locallySilencedAudioTracks = Set<ObjectIdentifier>()
    var visibleVideoIDs = Set<String>()
    var subscriptionRevision: UInt64 = 0
    var subscriptionEpoch = UUID()
    var subscriptionTask: Task<Void, Never>?
    var subscriptions = Set<String>()
    var error: String?
    var encryptionFailed = false
    func failEncryption(_ room: Room) { encryptionFailed = true }
    __SHOW__
    __UPDATE__
    __TOGGLE_AUDIO__
    __TOGGLE_VIDEO__
    __APPLY_AUDIO__
    __RESET__
    __BLOCKED__
    __PERMISSION_POLICY__
}
@main struct Checks {
    @MainActor static func main() async {
        var checks = 0
        func check(_ value: @autoclosure () -> Bool, _ message: String) {
            precondition(value(), message); checks += 1
        }
        func settle(_ owner: Owner) async {
            while let task = owner.subscriptionTask { await task.value }
        }
        let room = Room(), owner = Owner(); owner.room = room
        owner.showVideoParticipants(Set((0..<6).map { "p\($0)" })); await settle(owner)
        check(room.remoteParticipants.values.allSatisfy { $0.audio.enabled }, "all 30 audio publications remain subscribed")
        check(room.remoteParticipants.values.filter { $0.video.enabled }.count == 6, "only six visible videos subscribe initially")
        check(owner.subscriptions.count == 36, "off-page video never starts decoding")
        owner.toggleLocalAudio("p0")
        check(room.remoteParticipants[0]!.audio.track!.volume == 0, "local mute silences already-playing audio before any signaling await")
        await settle(owner)
        check(!room.remoteParticipants[0]!.audio.enabled && room.remoteParticipants[0]!.video.enabled,
              "local audio choice removes only that participant's audio subscription")
        let writes = room.remoteParticipants[0]!.audio.track!.writes
        owner.applyLocalAudioPreference("p0")
        check(room.remoteParticipants[0]!.audio.track!.writes == writes, "repeated refresh never repeats blocking WebRTC volume writes")
        owner.toggleLocalVideo("p1"); await settle(owner)
        check(!room.remoteParticipants[1]!.video.enabled && room.remoteParticipants[1]!.audio.enabled,
              "local video hide leaves participant audio and all other users untouched")
        let choices = owner.locallyMutedParticipantIDs
        owner.toggleLocalAudio("not-an-admitted-peer")
        check(owner.locallyMutedParticipantIDs == choices, "unknown and own identities cannot create local remote preferences")
        owner.toggleLocalAudio("p0"); owner.toggleLocalVideo("p1"); await settle(owner)
        check(room.remoteParticipants[0]!.audio.track!.volume == 1 && room.remoteParticipants[0]!.audio.enabled
              && room.remoteParticipants[1]!.video.enabled, "local explicit unmute/show restore only the requested subscriptions")
        let other = Owner(), otherRoom = Room(1); other.room = otherRoom
        other.showVideoParticipants(["p0"]); await settle(other)
        check(other.locallyMutedParticipantIDs.isEmpty && otherRoom.remoteParticipants[0]!.audio.track!.volume == 1,
              "preferences are scoped to this controller, not shared with another listener")
        other.toggleLocalAudio("p0"); await settle(other)
        other.participantPermissionObservers = [1: 1]
        other.resetSubscriptions()
        check(other.participantPermissionObservers.isEmpty, "transport replacement releases old participant permission observations")
        let replacement = Room(1, suffix: "-rejoined"); other.room = replacement
        other.applyLocalAudioPreference("p0"); other.updateSubscriptions(replacement); await settle(other)
        check(other.locallyMutedParticipantIDs.contains("p0") && !replacement.remoteParticipants[0]!.audio.enabled
              && replacement.remoteParticipants[0]!.audio.track!.volume == 0,
              "same-room transport recovery preserves the listener choice across new SDK publications")
        let plaintext = Owner(), plaintextRoom = Room(1); plaintext.room = plaintextRoom
        plaintext.toggleLocalAudio("p0"); await settle(plaintext)
        plaintextRoom.remoteParticipants[0]!.audio.encryptionType = .none
        plaintext.toggleLocalAudio("p0"); await settle(plaintext)
        check(plaintext.encryptionFailed && plaintextRoom.remoteParticipants[0]!.audio.track!.volume == 0,
              "local unmute never restores playout for a publication whose E2EE contract changed")
        let global = Owner(), globalRoom = Room(2); global.room = globalRoom
        global.showVideoParticipants(["p0", "p1"]); await settle(global)
        global.conference = ConferenceMetadata(participants: [.init(participantIdentity: "p0", audioPublishBlocked: true)])
        global.applyLocalAudioPreference("p0")
        check(globalRoom.remoteParticipants[0]!.audio.track!.volume == 0,
              "authoritative global block silences already-playing audio before signaling")
        global.updateSubscriptions(globalRoom); await settle(global)
        check(!globalRoom.remoteParticipants[0]!.audio.enabled && globalRoom.remoteParticipants[0]!.video.enabled
              && globalRoom.remoteParticipants[1]!.audio.enabled && globalRoom.remoteParticipants[0]!.audio.source == .camera,
              "all blocked AUDIO tracks unsubscribe regardless of claimed CAMERA source while real video and other peers stay enabled")
        global.toggleLocalAudio("p0"); await settle(global)
        global.toggleLocalAudio("p0"); await settle(global)
        check(!global.locallyMutedParticipantIDs.contains("p0") && globalRoom.remoteParticipants[0]!.audio.track!.volume == 0
              && !globalRoom.remoteParticipants[0]!.audio.enabled, "local unmute cannot override creator global restriction")
        global.conference = ConferenceMetadata(participants: [.init(participantIdentity: "p0", audioPublishBlocked: false)])
        globalRoom.remoteParticipants[0]!.permissions.canPublishSources = [Track.Source.camera.rawValue]
        global.applyLocalAudioPreference("p0"); global.updateSubscriptions(globalRoom); await settle(global)
        check(globalRoom.remoteParticipants[0]!.audio.track!.volume == 0 && !globalRoom.remoteParticipants[0]!.audio.enabled,
              "SDK microphone denial keeps all AUDIO off after an earlier DTO unblock")
        globalRoom.remoteParticipants[0]!.permissions.canPublishSources = []
        global.applyLocalAudioPreference("p0"); global.updateSubscriptions(globalRoom); await settle(global)
        check(globalRoom.remoteParticipants[0]!.audio.track!.volume == 1 && globalRoom.remoteParticipants[0]!.audio.enabled,
              "empty allowed-source list means all sources; authoritative unblock resumes only receiving audio")
        global.toggleLocalAudio("p0"); await settle(global)
        global.applyLocalAudioPreference("p0"); global.updateSubscriptions(globalRoom); await settle(global)
        check(globalRoom.remoteParticipants[0]!.audio.track!.volume == 0 && !globalRoom.remoteParticipants[0]!.audio.enabled,
              "creator allow never overrides an independent local listener mute")
        global.toggleLocalAudio("p0"); await settle(global)
        globalRoom.remoteParticipants[0]!.permissions.canPublish = false
        global.applyLocalAudioPreference("p0"); global.updateSubscriptions(globalRoom); await settle(global)
        check(globalRoom.remoteParticipants[0]!.audio.track!.volume == 0 && !globalRoom.remoteParticipants[0]!.audio.enabled,
              "SDK canPublish=false denies audio even with an empty source list")
        let gate = Gate(); room.remoteParticipants[0]!.video.gate = gate
        owner.showVideoParticipants(Set((6..<12).map { "p\($0)" }))
        while !gate.entered { await Task.yield() }
        owner.showVideoParticipants(Set((24..<30).map { "p\($0)" }))
        gate.open(); await settle(owner)
        check(room.remoteParticipants.values.allSatisfy { $0.video.enabled == (24..<30).contains(Int($0.identity!.stringValue.dropFirst())!) },
              "new page wins after an older subscription await resumes")
        check(room.remoteParticipants.values.allSatisfy { $0.audio.enabled }, "paging never interrupts remote audio")
        owner.showVideoParticipants(["p29"]); await settle(owner)
        check(room.remoteParticipants.values.filter { $0.video.enabled }.count == 1 && room.remoteParticipants[29]!.video.enabled,
              "pin retains only the focused video subscription")
        owner.showVideoParticipants([]); await settle(owner)
        check(room.remoteParticipants.values.allSatisfy { !$0.video.enabled && $0.audio.enabled },
              "minimize/background drops all video while audio continues")
        let previous = owner.subscriptions
        let staleGate = Gate(); room.remoteParticipants[0]!.video.gate = staleGate
        owner.showVideoParticipants(["p0"])
        while !staleGate.entered { await Task.yield() }
        let staleTask = owner.subscriptionTask!
        let successor = Room(1, suffix: "-next")
        owner.room = successor; owner.subscriptionTask?.cancel(); owner.subscriptionTask = nil
        owner.subscriptions.removeAll(); owner.visibleVideoIDs.removeAll()
        owner.showVideoParticipants(["p0"]); await settle(owner)
        let successorIDs = owner.subscriptions
        staleGate.open(); await staleTask.value
        check(owner.subscriptions == successorIDs && successorIDs == ["a0-next", "v0-next"],
              "late old-room completion cannot mutate successor subscriptions")
        check(!previous.contains("v0"), "test fixture actually suspended a fresh video subscription")
        let invalid = Room(1); invalid.remoteParticipants[0]!.audio.encryptionType = .none
        let failClosed = Owner(); failClosed.room = invalid
        failClosed.showVideoParticipants(["p0"]); await settle(failClosed)
        check(failClosed.encryptionFailed && invalid.remoteParticipants[0]!.audio.calls == 0,
              "plaintext publication fails before any subscription request")
        let retryRoom = Room(1), retry = Owner(); retry.room = retryRoom
        retryRoom.remoteParticipants[0]!.video.failNext = true
        retry.showVideoParticipants(["p0"]); await settle(retry)
        check(!retryRoom.remoteParticipants[0]!.video.enabled && !retry.subscriptions.contains("v0"),
              "failed signaling cannot be cached as a successfully received video")
        retry.updateSubscriptions(retryRoom); await settle(retry)
        check(retryRoom.remoteParticipants[0]!.video.enabled && retry.subscriptions.contains("v0"),
              "next reconciliation resends after SDK preferred-state mutation preceded a failed send")
        print("PASS: \(checks) actual-method conference subscription contracts")
    }
}
'''
harness = harness.replace("__SHOW__", method("func showVideoParticipants("))
harness = harness.replace("__UPDATE__", method("private func updateSubscriptions("))
harness = harness.replace("__TOGGLE_AUDIO__", method("func toggleLocalAudio("))
harness = harness.replace("__TOGGLE_VIDEO__", method("func toggleLocalVideo("))
harness = harness.replace("__APPLY_AUDIO__", method("private func applyLocalAudioPreference("))
harness = harness.replace("__RESET__", method("private func resetSubscriptions("))
harness = harness.replace("__BLOCKED__", method("func isParticipantAudioBlocked("))
harness = harness.replace("__PERMISSION_POLICY__", method("nonisolated private static func permissionsDenyMicrophone("))
with tempfile.TemporaryDirectory(prefix="coco-conference-subscriptions-") as temporary:
    folder = Path(temporary)
    swift = folder / "main.swift"
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    swift.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(swift), "-o", str(binary)], check=True, cwd=ROOT)
    subprocess.run([str(binary)], check=True, timeout=30, cwd=ROOT)
