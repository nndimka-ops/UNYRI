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

engine=read('ios/UNYRI/WebRTC/WebRTCEngineImpl.swift'); direct=read('ios/UNYRI/DirectTransfer/DirectFilePeer.swift')
assert 'RemoteIcePolicy.filterSDP(sdp, relayOnly: pc.configuration.iceTransportPolicy == .relay)' in engine
assert 'RemoteIcePolicy.allows(candidate, relayOnly: pc.configuration.iceTransportPolicy == .relay)' in engine
assert 'RemoteIcePolicy.filterSDP(sdp, relayOnly: relayOnly)' in direct
assert 'RemoteIcePolicy.allows(value, relayOnly: relayOnly)' in direct
controller=read('ios/UNYRI/Calls/CallController.swift')
assert controller.index('if candidate.hasPrefix(CallSASHandshake.revealPrefix)') < controller.index('else { engine.addIceCandidate')

sources = ["ios/UNYRI/Calls/RemoteIcePolicy.swift","ios/UNYRI/Support/AppLanguage.swift","ios/UNYRI/Support/HistoryArchive.swift","ios/UNYRI/Calls/CallModels.swift","ios/UNYRI/Calls/CallSignaling.swift","ios/UNYRI/Calls/CallStateReducer.swift","ios/UNYRI/Calls/CallReconciliation.swift"]
with tempfile.TemporaryDirectory(prefix='unyri-remoteicesecuritycontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: RemoteIceSecurityContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
