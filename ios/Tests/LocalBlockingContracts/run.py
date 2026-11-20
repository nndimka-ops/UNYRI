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

ingestor=read('ios/UNYRI/Messages/MessageIngestor.swift'); controller=read('ios/UNYRI/Calls/CallController.swift'); push=read('ios/UNYRI/Support/PushNotificationCoordinator.swift')
method=block(ingestor,'    private func excludeIncoming(').replace('private func','func',1)
assert ingestor.count('if try await excludeIncoming(dto, journalID: journalID) { return .securityExcluded }') == 2
assert 'if outcome.allowsDeliveryReceipt { deliveredWatermarks' in ingestor
assert 'allowsDeliveryReceipt: false' in ingestor
assert 'guard allowsUnresolvedMessageAlert(origin: record.origin, userID: record.userId)' in push
assert 'incomingPeerAllowed(callerUserId)' in controller and 'terminal(.failed)' in block(controller,'    func receiveIncomingPush(')
assert 'incomingPeerAllowed(peer)' in block(controller,'    func acceptIncoming(')
assert push.index('provider.reportPush(hint') < push.index('let blocked = try security.blockedUsers()') < push.index('receiveSessions[ticket.nonce] =')
assert 'locallyBlockedUsers.removeAll()' not in read('ios/UNYRI/Users/UsersRepository.swift')
assert 'accountBlockOverrides.removeAll()' not in read('ios/UNYRI/Chats/ChatsRepository.swift')
adapter='import Foundation\n@MainActor final class IngressSubject { let auth: AuthProbe; let store=StoreProbe(); let sessionManager=SignalProbe(); init(_ security:LocalSecurityDecisions) { auth=AuthProbe(security) }\n'+method+'\n}\n'

sources = ["ios/UNYRI/Support/LocalSecurityDecisions.swift"]
with tempfile.TemporaryDirectory(prefix='unyri-localblockingcontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: LocalBlockingContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
