"""Execute production owner vault, SessionStore and rejoin selector on Windows.

Keychain is an injected in-memory seam. SDK/media/UI and real iPhone are not
tested by this Foundation program. No account credentials or live API is used.
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'runtime-logs/ios-rooms-ui-rejoin-20261030/owned-contracts'
OUT.mkdir(parents=True, exist_ok=True)

source = (ROOT / 'ios/UNYRI/Auth/SessionStore.swift').read_text(encoding='utf-8')
session = 'import Foundation\n' + source[source.index('final class SessionStore {'):source.index('/// Erases all account material')]
(OUT / 'SessionStore.swift').write_text(session, encoding='utf-8')

controller = (ROOT / 'ios/UNYRI/Conferences/ConferenceController.swift').read_text(encoding='utf-8')
def member(marker):
    start = controller.index(marker)
    brace = controller.index('{', start); end = brace + 1; depth = 1
    while depth:
        depth += (controller[end] == '{') - (controller[end] == '}'); end += 1
    return controller[start:end]

members = '\n'.join(member(marker) for marker in [
    'func hasSavedAccess(to value:', 'func rejoinSavedDirectoryRoom(', 'func rejoinOwnedRoom(', 'func forgetOwnedRoom('])
auth_source = (ROOT / 'ios/UNYRI/Auth/AuthRepository.swift').read_text(encoding='utf-8')
def auth_member(marker):
    start = auth_source.index(marker); brace = auth_source.index('{', start); end = brace + 1; depth = 1
    while depth:
        depth += (auth_source[end] == '{') - (auth_source[end] == '}'); end += 1
    return auth_source[start:end]
auth_members = '\n'.join(auth_member(marker) for marker in ['func ownedRoomAccessSnapshot(', 'func saveOwnedRoomAccess('])
editor = (ROOT / 'ios/UNYRI/Conferences/ConferenceDirectoryView.swift').read_text(encoding='utf-8')
def editor_member(marker):
    start = editor.index(marker); brace = editor.index('{', start); end = brace + 1; depth = 1
    while depth:
        depth += (editor[end] == '{') - (editor[end] == '}'); end += 1
    return editor[start:end].replace('private var', 'var', 1)
editor_members = '\n'.join(editor_member(marker) for marker in ['private var draft:', 'private var validDraft:', 'private var dirty:'])
adapter = r'''
import Foundation
enum AppConfig {
    static let publicDefaultOrigin = "https://public-test.invalid"
    static let apiBaseURL = URL(string: "https://owned-test.invalid")!
}
enum APIError: Error { case missingSession }
enum KeychainStore {
    static func checkedValue(forKey: String) throws -> String? { nil }
    static func set(_ value: String, forKey: String) -> Bool { false }
    static func remove(_ key: String) {}
}
struct ConferenceInvitationMetadata {
    let server: String; let roomID: String; let invite: String
    let hasValidShape = true
}
struct EnvelopeInput: Decodable, Equatable {
    let messageType: Int; let ciphertext: String; let recipientDeviceId: String
}
@MainActor enum ConferenceMediaOwnership { static var isBusy = false }
@MainActor enum NonCallCaptureOwnership { static var isBusy = false }
@MainActor final class AuthProxy {
    enum State { case loggedIn(User), loggedOut }
    var state: State
    var sessionGeneration: UInt64 = 1
    let store: SessionStore
    init(store: SessionStore, user: User) { self.store = store; state = .loggedIn(user) }
    __AUTH_METHODS__
}
struct EditorFixture {
    var configuration = ConferenceRoomConfiguration(title: "")
    var existing: Bool?
    var initialConfiguration: ConferenceRoomConfiguration?
    var limit = 30, password = ""
    var initialMode = ConferenceAccessMode.invite
    __EDITOR_GETTERS__
}
@MainActor final class SelectorAuth { var currentUser: User? }
struct SelectorAPI { var isCurrentSession = true }
struct ConferenceDTO {
    var state = "active"; var expiresAt = Date().addingTimeInterval(3600)
    var ownerUserId: UUID; var id: UUID; var accessMode: String? = "invite"
}
@MainActor final class Selector {
    var api = SelectorAPI(), retired = false, isBusy = false, minimized = true
    var conference: ConferenceDTO?, auth: SelectorAuth? = SelectorAuth()
    var error: String?, offeredInvite: ConferenceInvite?, showLobby = false
    var canUseMedia: () -> Bool = { true }
    var vault: ConferenceOwnedAccess?
    var joined: ConferenceInvite?, requestedMic = true, requestedCamera = true
    func ownerAccess() -> ConferenceOwnedAccess? { api.isCurrentSession && !retired ? vault : nil }
    func join(_ invite: ConferenceInvite, microphone: Bool, camera: Bool) {
        joined = invite; requestedMic = microphone; requestedCamera = camera
    }
    __SELECTORS__
}
'''.replace('__SELECTORS__', members).replace('__AUTH_METHODS__', auth_members).replace('__EDITOR_GETTERS__', editor_members)
(OUT / 'Adapters.swift').write_text(adapter, encoding='utf-8')

exe = OUT / ('contracts.exe' if os.name == 'nt' else 'contracts')
sources = [OUT / 'Adapters.swift', OUT / 'SessionStore.swift',
           ROOT / 'ios/UNYRI/Models/AuthModels.swift', ROOT / 'ios/UNYRI/Conferences/ConferenceInvite.swift',
           ROOT / 'ios/UNYRI/Conferences/ConferenceDirectoryModels.swift', ROOT / 'ios/UNYRI/Conferences/ConferenceOwnedAccess.swift',
           Path(__file__).with_name('main.swift')]
subprocess.run(['swiftc', '-parse-as-library', *map(str, sources), '-o', str(exe)], check=True, timeout=100)
subprocess.run([str(exe)], check=True, timeout=30)
