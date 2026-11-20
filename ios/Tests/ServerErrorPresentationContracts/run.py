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

app=read('ios/UNYRI/UNYRIApp.swift')
start=app.index('OpenURLAction { url in'); end=app.index('})',start)
body=app[start:end].split('url in',1)[1].strip()
adapter='import Foundation\nenum URLResult { case systemAction, discarded }\nfunc decide(_ url: URL) -> URLResult {\n'+body+'\n}\n'
for name in ['ios/UNYRI/Conferences/ConferenceRoomView.swift','ios/UNYRI/Conferences/ConferenceViews.swift','ios/UNYRI/UI/Profile/HistoryKeySettingsView.swift']:
    text=read(name)
    assert 'Text(LocalizedStringKey(error))' not in text and 'Text(LocalizedStringKey(controller.error' not in text
    assert 'Text(verbatim:' in text
assert 'UnyriMediaNetwork.openAppSettings()' in read('ios/UNYRI/UI/Profile/HistoryTransferView.swift')

sources = []
with tempfile.TemporaryDirectory(prefix='unyri-servererrorpresentationcontracts-') as temp:
    folder=Path(temp); files=[ROOT/n for n in sources]
    if adapter:
        actual=folder/'Actual.swift'; actual.write_text(adapter,encoding='utf-8'); files.append(actual)
    files.append(HERE/'main.swift')
    assert all(p.is_file() for p in files)
    if args.generate_only:
        print('PASS: ServerErrorPresentationContracts exact source extraction/dependencies (Swift NOT executed)')
    else:
        binary=folder/'contracts'
        target = ['-target', platform.machine()+'-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc','-swift-version','5','-parse-as-library',*target,*map(str,files),'-o',str(binary)],check=True,timeout=180)
        subprocess.run([str(binary)],check=True,timeout=60)
