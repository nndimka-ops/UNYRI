"""Exercise actual route menu filtering and selection with AVAudioSession seams."""
from pathlib import Path
import subprocess, tempfile, os, json

root = Path(__file__).resolve().parents[3]
source = (root / 'ios/UNYRI/Conferences/ConferenceRoomView.swift').read_text(encoding='utf-8')

def member(marker):
    start = source.index(marker)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end].replace('private ', '', 1)

assert 'ForEach(externalAudioInputs, id:' in source
assert 'ForEach(AVAudioSession.sharedInstance().availableInputs' not in source
for key, expected in [('Loudspeaker', 'Громкая связь'), ('Phone earpiece', 'Разговорный динамик')]:
    catalog = json.loads((root / 'ios/UNYRI/Localizable.xcstrings').read_text(encoding='utf-8'))
    assert catalog['strings'][key]['localizations']['ru']['stringUnit']['value'] == expected

harness = r'''
import Foundation
enum Failure: Error { case route }
struct AVAudioSessionPortDescription {
    let uid: String
    let portType: AVAudioSession.Port
}
@MainActor final class AVAudioSession {
    enum Port { case builtInMic, headsetMic, bluetoothHFP, usbAudio, carAudio, lineIn, other }
    enum PortOverride: Equatable { case speaker, none }
    static let shared = AVAudioSession()
    static func sharedInstance() -> AVAudioSession { shared }
    var availableInputs: [AVAudioSessionPortDescription]?
    var preferred: AVAudioSessionPortDescription?
    var output: PortOverride?
    var writes = 0
    var fail = false
    func setPreferredInput(_ input: AVAudioSessionPortDescription?) throws {
        if fail { throw Failure.route }
        preferred = input; writes += 1
    }
    func overrideOutputAudioPort(_ value: PortOverride) throws { output = value; writes += 1 }
}
@MainActor final class AudioManager {
    static let shared = AudioManager()
    var isSpeakerOutputPreferred = false
}
@MainActor final class Controller {
    enum Phase { case connected, connecting }
    var phase = Phase.connected
    var error: String?
}
@MainActor final class Subject {
    var controller = Controller()
    __FILTER__
    __ROUTE__
}
var passed = 0
func check(_ value: Bool, _ name: String) {
    if !value { print("FAIL: " + name); exit(1) }
    passed += 1; print("PASS: " + name)
}
@main struct Run {
    @MainActor static func main() {
        let session = AVAudioSession.shared, subject = Subject()
        let mic = AVAudioSessionPortDescription(uid: "phone-mic", portType: .builtInMic)
        let wired = AVAudioSessionPortDescription(uid: "wired", portType: .headsetMic)
        let bt = AVAudioSessionPortDescription(uid: "bt", portType: .bluetoothHFP)
        session.availableInputs = [mic]
        check(subject.externalAudioInputs.isEmpty, "phone alone adds no third output for its microphone")
        session.availableInputs = [mic, wired, bt,
            AVAudioSessionPortDescription(uid: "usb", portType: .usbAudio),
            AVAudioSessionPortDescription(uid: "car", portType: .carAudio),
            AVAudioSessionPortDescription(uid: "line", portType: .lineIn),
            AVAudioSessionPortDescription(uid: "unknown", portType: .other)]
        check(subject.externalAudioInputs.map(\.uid) == ["wired", "bt", "usb", "car"],
              "only attached communication routes appear; input-only and unknown ports stay hidden")
        session.availableInputs = [mic, wired]
        check(subject.externalAudioInputs.map(\.uid) == ["wired"], "connected headset adds exactly one menu item")
        session.availableInputs = [mic]
        check(subject.externalAudioInputs.isEmpty, "unplugged headset immediately leaves the menu")
        session.availableInputs = nil
        check(subject.externalAudioInputs.isEmpty, "missing input inventory creates no phantom devices")
        session.availableInputs = [mic, wired, bt]
        subject.route(speaker: true, input: nil)
        check(session.preferred?.uid == mic.uid && session.output == .speaker &&
              AudioManager.shared.isSpeakerOutputPreferred, "loudspeaker keeps the existing built-in mic/speaker routing")
        subject.route(speaker: false, input: nil)
        check(session.preferred?.uid == mic.uid && session.output == AVAudioSession.PortOverride.none &&
              !AudioManager.shared.isSpeakerOutputPreferred, "earpiece action releases the speaker override with built-in input")
        subject.route(speaker: false, input: bt)
        check(session.preferred?.uid == bt.uid && session.output == AVAudioSession.PortOverride.none,
              "Bluetooth selection uses the actual connected device")
        subject.route(speaker: false, input: wired)
        check(session.preferred?.uid == wired.uid && session.output == AVAudioSession.PortOverride.none,
              "wired headset selection preserves its actual device")
        let writes = session.writes
        subject.controller.phase = .connecting
        subject.route(speaker: true, input: nil)
        check(session.writes == writes, "connecting room cannot change the route")
        subject.controller.phase = .connected; session.fail = true
        subject.route(speaker: false, input: bt)
        check(subject.controller.error != nil && session.writes == writes,
              "failed system selection is surfaced without continuing output changes")
        print("Conference audio route actual-method contracts: \(passed) passed; no Apple SDK or physical audio proof")
    }
}
'''.replace('__FILTER__', member('    private var externalAudioInputs:')).replace('__ROUTE__', member('    private func route(speaker:'))

with tempfile.TemporaryDirectory(prefix='coco-conference-audio-routes-') as temp:
    folder = Path(temp)
    swift = folder / 'main.swift'
    swift.write_text(harness, encoding='utf-8')
    binary = folder / ('contracts.exe' if os.name == 'nt' else 'contracts')
    subprocess.run(['swiftc', '-parse-as-library', str(swift), '-o', str(binary)], check=True, timeout=100)
    subprocess.run([str(binary)], check=True, timeout=20)
