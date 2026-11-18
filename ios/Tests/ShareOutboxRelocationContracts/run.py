"""Execute production outbox preparation and SQLite storage after real container moves.

Native IO/network are controlled: the uploader verifies the actual source bytes.
Forward admission, enqueueMedia, MediaJob, loadMediaJob, prepareMedia and the
entire MessageStore/model closure are production code.
"""
from pathlib import Path
import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from ios_portable_swift import _block, write_direct_file_policy_shim

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--swiftc', default=os.environ.get('SWIFTC') or shutil.which('swiftc'))
args = parser.parse_args()
if not args.swiftc:
    parser.error('Swift compiler unavailable; set SWIFTC or --swiftc')
outbox = (ROOT / 'ios/UNYRI/Messages/MessageOutbox.swift').read_text(encoding='utf-8')
visuals = (ROOT / 'ios/UNYRI/Support/MediaPreparationState.swift').read_text(encoding='utf-8')
production = '\n'.join(_block(outbox, marker) for marker in (
    '    private struct MediaJob:', '    func enqueueMedia(', '    func enqueueContent(',
    '    private func loadMediaJob(', '    private func prepareMedia(', '    private func releaseMediaSource('))
manager = (ROOT / 'ios/UNYRI/Attachments/AttachmentManager.swift').read_text(encoding='utf-8')
attachment_methods = '\n'.join(_block(manager, marker) for marker in (
    '    private var cacheBoundary:', '    private func uploadCacheURL(', '    func resumeUpload(', '    func referencesLocalFile(', '    func waitForDraftReferences(', '    func attachmentJournalConsumed('))
fixture = Path(__file__).with_name('main.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_OUTBOX', production)
fixture = fixture.replace('// PRODUCTION_VISUAL', _block(visuals, 'struct QueuedMediaVisual:'))
fixture = fixture.replace('// PRODUCTION_ATTACHMENT_METHODS', attachment_methods)
repository = (ROOT / 'ios/UNYRI/Messages/MessagesRepository.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_FORWARD', _block(repository, '    func forward(_ source:'))
sources = [
    'ios/UNYRI/Support/HistoryArchive.swift',
    'ios/Shared/MessageTextPolicy.swift', 'ios/UNYRI/DirectTransfer/DirectFileModels.swift',
    'ios/UNYRI/DirectTransfer/DirectManualTransferState.swift', 'ios/UNYRI/DirectTransfer/DirectFileQueueState.swift',
    'ios/UNYRI/DirectTransfer/DirectFileExecutionPermit.swift', 'ios/UNYRI/Signal/MessageContentCodec.swift',
    'ios/UNYRI/Models/MessageModels.swift', 'ios/UNYRI/Models/SignalModels.swift',
    'ios/UNYRI/Models/AttachmentModels.swift', 'ios/UNYRI/Data/MessageStore.swift', 'ios/UNYRI/Data/AttachmentStore.swift',
    'ios/UNYRI/Support/HistoryAttachmentRestorer.swift',
    'ios/UNYRI/Support/MediaCacheLease.swift',
    'ios/UNYRI/Attachments/AttachmentCacheBoundary.swift',
]
with tempfile.TemporaryDirectory(prefix='unyri-share-outbox-relocation-') as temporary:
    folder = Path(temporary)
    harness = folder / 'Harness.swift'
    harness.write_text(fixture, encoding='utf-8')
    binary = folder / ('contracts.exe' if os.name == 'nt' else 'contracts')
    flags = ['-swift-version', '5', '-parse-as-library']
    if platform.system() == 'Darwin':
        flags += ['-target', platform.machine() + '-apple-macosx13.0']
    if os.name == 'nt':
        sdk = Path(os.environ.get('ProgramFiles(x86)', 'C:/Program Files (x86)')) / 'Windows Kits/10'
        candidates = []
        for include in (sdk / 'Include').glob('*'):
            header = include / 'um/winsqlite/winsqlite3.h'
            library = sdk / 'Lib' / include.name / 'um/x64/winsqlite3.lib'
            if header.is_file() and library.is_file():
                candidates.append((tuple(int(n) for n in include.name.split('.')), header, library))
        assert candidates, 'Windows SDK WinSQLite3 headers/library required'
        _, header, library = max(candidates, key=lambda value: value[0])
        module = folder / 'sqlite-module'
        module.mkdir()
        (module / 'module.modulemap').write_text(
            'module SQLite3 [system] {\n header "' + header.as_posix() + '"\n link "winsqlite3"\n export *\n}\n', encoding='utf-8')
        flags += ['-I', str(module), '-L', str(library.parent)]
    shim = write_direct_file_policy_shim(ROOT, folder)
    subprocess.run([args.swiftc, *flags, str(shim), *[str(ROOT / value) for value in sources], str(harness), '-o', str(binary)],
                   check=True, timeout=120)
    subprocess.run([str(binary)], check=True, timeout=30)
