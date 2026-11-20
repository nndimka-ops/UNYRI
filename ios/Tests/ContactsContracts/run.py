"""Execute the real ContactsStore (end of Users/UsersRepository.swift) with
controlled page/write races: the ticket fence, single flight per id, failures
that keep state, cursor guards, staleness and the WS hint. Only the concrete
UsersRepository conformance is left out. Off Apple platforms Combine is
replaced by the two stand-ins the store uses, so this also runs on Windows.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[3]
users = (root / 'ios/UNYRI/Users/UsersRepository.swift').read_text(encoding='utf-8').replace('\r\n', '\n')
store = users[users.index('@MainActor\nprotocol ContactsService'):].replace('extension UsersRepository: ContactsService {}', '')
api = (root / 'ios/UNYRI/Networking/UsersAPI.swift').read_text(encoding='utf-8').replace('\r\n', '\n')
models = api[api.index('/// Account address book entry'):]
if sys.platform != 'darwin':
    store = store.replace(': ObservableObject {', ' {').replace('@Published ', '')

with tempfile.TemporaryDirectory(prefix='unyri-contacts-') as temp:
    temp = Path(temp)
    (temp / 'Store.swift').write_text('import Foundation\n#if canImport(Combine)\nimport Combine\n#endif\n' + store + '\n' + models, encoding='utf-8')
    sources = [root / 'ios/UNYRI/Support/DisplayNameSanitizer.swift', temp / 'Store.swift', root / 'ios/UNYRI/Models/UserModels.swift', Path(__file__).resolve().parent / 'main.swift']
    binary = temp / ('checks.exe' if os.name == 'nt' else 'checks')
    subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *map(str, sources), '-o', str(binary)], check=True, timeout=300)
    subprocess.run([str(binary)], check=True, timeout=60)
