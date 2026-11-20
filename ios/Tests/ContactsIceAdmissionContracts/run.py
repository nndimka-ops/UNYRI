"""Compile the real account wiring and relay policy with in-memory account state."""
from pathlib import Path
import argparse
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def block(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def sources():
    app = (ROOT / 'ios/UNYRI/UNYRIApp.swift').read_text(encoding='utf-8')
    binding = block(app, 'callController.isContact =')
    file_binding = block(app, 'directFiles.isContact =')
    for admission in [binding, file_binding]:
        assert 'callerUsers?.isKnownPeerContact(userId) ?? false' in admission
        assert 'hasDirectChat' not in admission
    users = (ROOT / 'ios/UNYRI/Users/UsersRepository.swift').read_text(encoding='utf-8')
    scope = block(users, 'var blockedAccountScope: String?')
    for fence in ['generation == auth.sessionGeneration', 'auth.currentUser?.id == directoryOwner',
                  'AppConfig.storageOrigin == directoryOrigin']:
        assert fence in scope
    assert 'accountUsers.startContactMembershipSync()' in app
    assert 'callerUsers?.refreshContactMembership()' in app
    startup = block(users, 'func startContactMembershipSync()')
    assert 'publisher(for: .login2ContactChanged)' in startup
    assert 'owner == self.directoryOwner' in startup and 'self.blockedAccountScope != nil' in startup
    assert 'self.contacts.markStale()' in startup
    gate = block(users, 'func isKnownPeerContact(_ userID: String) -> Bool')
    assert 'blockedAccountScope != nil' in gate and '!ownProfileRetired' in gate
    store_gate = block(users[users.index('final class ContactsStore:'):], 'func isKnownPeerContact(_ userID: String) -> Bool')
    assert 'loadedAt != nil' in store_gate and 'identityIsCurrent' in store_gate
    controller = (ROOT / 'ios/UNYRI/Calls/CallController.swift').read_text(encoding='utf-8')
    assert 'peerIsContact: isContact(call.remoteUserId)' in controller
    files = (ROOT / 'ios/UNYRI/DirectTransfer/DirectFileTransfers.swift').read_text(encoding='utf-8')
    assert 'UnyriDeviceSettings.shared.callPeerToPeer, peerIsContact: isContact(peerUserId)' in files
    actual = '@MainActor func bind(_ callController: CallController, _ directFiles: CallController, users: UsersRepository) {\n'
    actual += 'let callerUsers = users\n' + binding + '\n' + file_binding + '\n}\n'
    actual += 'extension UsersRepository {\n' + gate + '\n}\n'
    return actual


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    actual = sources()
    with tempfile.TemporaryDirectory(prefix='unyri-contacts-ice-') as temp:
        folder = Path(temp)
        adapter = folder / 'Actual.swift'
        adapter.write_text(actual, encoding='utf-8')
        files = [ROOT / 'ios/UNYRI/Calls/CallNetworkPolicy.swift', adapter, HERE / 'main.swift']
        assert all(path.is_file() for path in files)
        if args.generate_only:
            print('PASS: ContactsIceAdmissionContracts source gates/extraction (Swift NOT executed)')
            return
        target = ['-target', platform.machine() + '-apple-macos14.2'] if platform.system() == 'Darwin' else []
        binary = folder / 'contracts'
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
                        *map(str, files), '-o', str(binary)], check=True, timeout=180)
        subprocess.run([str(binary)], check=True, timeout=60)


if __name__ == '__main__':
    main()
