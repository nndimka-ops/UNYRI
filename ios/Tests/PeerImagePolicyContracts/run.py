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

policy=read('ios/Shared/PeerImagePolicy.swift')
assert 'kCGImageSourceAllowableTypes' not in policy
for name in ['ios/UNYRI/UNYRIApp.swift','ios/UNYRIShare/ShareViewController.swift','ios/UNYRINotificationService/NotificationService.swift']:
    assert 'PeerImagePolicy.install()' in read(name)
for base in ['ios/UNYRI','ios/UNYRIShare']:
    for path in (ROOT/base).rglob('*.swift'):
        text=path.read_text(encoding='utf-8')
        assert 'CGImageSourceCreateWithURL(' not in text and 'CGImageSourceCreateWithData(' not in text, str(path)
        assert 'UIImage(data:' not in text and 'UIImage(contentsOfFile:' not in text, str(path)
for name in ['ios/UNYRI/UI/Chats/ChatScreen.swift','ios/UNYRI/UI/Chats/UnyriMediaViewer.swift','ios/UNYRI/DirectTransfer/DirectFileMediaPreviewGenerator.swift']:
    assert 'jpegOnly: true' in read(name)

sources = ["ios/Shared/PeerImagePolicy.swift"]
with tempfile.TemporaryDirectory(prefix='unyri-peerimagepolicycontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: PeerImagePolicyContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
