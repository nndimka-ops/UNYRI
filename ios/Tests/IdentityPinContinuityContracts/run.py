"""Compile production security boundaries against local in-memory seams; no network/Keychain."""
from pathlib import Path
import argparse, subprocess, tempfile, platform
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--generate-only', action='store_true')
args = parser.parse_args()
def read(name): return (ROOT/name).read_text(encoding='utf-8')
def block(text, marker):
    start=text.index(marker); end=text.index('{',start)+1; depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}'); end+=1
    return text[start:end]
adapter = ''

stores=read('ios/UNYRI/Signal/UnyriSignalStores.swift')
start=stores.index('// Adopt legacy approved pins once')
end=stores.index('        return UnyriSignalStoreContainer(stateStore: state)',start)
restore=stores[start:end].rstrip(); restore=restore[:restore.rfind('}')]
auth=read('ios/UNYRI/Auth/AuthRepository.swift')
preserve=block(auth,'    private static func preserveIdentityPins(').replace('private static func','static func',1)
factory=block(read('ios/UNYRI/Auth/SessionStore.swift'),'    static func account(origin: String, userID: String)')
adapter='import Foundation\nfunc restorePins(_ state:SignalStateStore, security:LocalSecurityDecisions) throws {\n'+restore+'\n}\nextension LocalSecurityDecisions {\n'+factory+'\n}\nenum SignOutSubject {\n'+preserve+'\n}\n'
finish=block(auth,'    private func finishLocalSession()')
assert finish.index('Self.preserveIdentityPins') < finish.index('store.clearSession()')
assert finish.index('await prepare?()') < finish.rindex('Self.preserveIdentityPins') < finish.index('accounts.finishSignOut')
assert 'eraseSecurityAfterSignOut = true' in block(auth,'    func deleteAccount(')
assert 'hasVerifiedIdentityChange(user: recipient.addressName)' in read('ios/UNYRI/Signal/SignalSessionManager.swift')
assert '.local-security' not in block(read('ios/UNYRI/Auth/SessionStore.swift'),'    static func erasePrivateKeychain()')

sources = ["ios/UNYRI/Support/LocalSecurityDecisions.swift","ios/UNYRI/Signal/SignalStateStore.swift","ios/UNYRI/Signal/SignalStorageKeys.swift","ios/UNYRI/Signal/SignalReceiveJournal.swift"]
with tempfile.TemporaryDirectory(prefix='unyri-identitypincontinuitycontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: IdentityPinContinuityContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
