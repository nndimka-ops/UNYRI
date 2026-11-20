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

signal=read('ios/UNYRI/Signal/SignalSessionManager.swift'); stores=read('ios/UNYRI/Signal/UnyriSignalStores.swift')
assert 'localSecurity?.isRevoked' in block(signal,'    nonisolated private static func requireTrustedRecipientsStored(')
assert 'security.isRevoked' in block(signal,'    private func trustedBundles(')
assert 'isRevoked' in block(signal,'    func isDeviceTrusted(')
assert 'isRevoked' in block(stores,'    func isTrustedIdentity(')
assert 'claimRelistingWarning' in signal and 'onSecurityWarning?()' in signal
profile=read('ios/UNYRI/UI/Profile/ProfileView.swift'); method=block(profile,'    private func revokeDevice(')
assert method.index('try await revokeLocally(id)') < method.index('client.deleteNoContent')

sources = ["ios/UNYRI/Support/LocalSecurityDecisions.swift"]
with tempfile.TemporaryDirectory(prefix='unyri-localdevicerevocationcontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: LocalDeviceRevocationContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
