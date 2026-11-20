"""Execute real ingestion, recovery callers and share processing during held migration.

Only Signal/native staging APIs are controlled. Stores, ingestion methods,
recovery caller methods and the share processor compile verbatim.
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
ingestor = (ROOT / 'ios/UNYRI/Messages/MessageIngestor.swift').read_text(encoding='utf-8')
repository = (ROOT / 'ios/UNYRI/Messages/MessagesRepository.swift').read_text(encoding='utf-8')
fixture = Path(__file__).with_name('main.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_INGESTION', '\n'.join(_block(ingestor, marker) for marker in (
    '    private func receiveID(', '    private struct IngestOutcome', '    private func excludeIncoming(', '    private func ingestOne(', '    private func liveIngestPreState(',
    '    private func persistDecryptedMessage(', '    private func finishAttachmentPersistence(', '    private func persistAttachment(')))
fixture = fixture.replace('// PRODUCTION_RECOVERY_CALLERS', '\n'.join(_block(repository, marker) for marker in (
    '    func waitForDraftSourceRecovery(', '    func stageDraftFile(', '    func copyRecentFiles(')))
manager = (ROOT / 'ios/UNYRI/Attachments/AttachmentManager.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_ATTACHMENT_PURGES', '\n'.join(_block(manager, marker) for marker in (
    '    private func uploadCacheURL(', '    func purgePersonalChat(', '    func purgeMessageMedia(', '    func purgeDeletedChat(', '    private func removeCachedFiles(')))
fixture = fixture.replace('// PRODUCTION_DELETION_CALLERS', '\n'.join(_block(repository, marker) for marker in (
    '    func clearPersonalChat(', '    func purgeDeletedChat(')))
sources = [
    'ios/UNYRI/Support/LocalSecurityDecisions.swift',
    'ios/UNYRI/Support/DirectChatBinding.swift', 'ios/UNYRI/Attachments/AttachmentCacheBoundary.swift',
    'ios/UNYRI/Support/AppLanguage.swift', 'ios/UNYRI/Support/HistoryArchive.swift',
    'ios/Shared/MessageTextPolicy.swift', 'ios/UNYRI/DirectTransfer/DirectFileModels.swift',
    'ios/UNYRI/DirectTransfer/DirectManualTransferState.swift', 'ios/UNYRI/DirectTransfer/DirectFileQueueState.swift',
    'ios/UNYRI/DirectTransfer/DirectFileExecutionPermit.swift', 'ios/UNYRI/Signal/MessageContentCodec.swift',
    'ios/UNYRI/Models/MessageModels.swift', 'ios/UNYRI/Models/SignalModels.swift',
    'ios/UNYRI/Models/AttachmentModels.swift', 'ios/UNYRI/Data/MessageStore.swift', 'ios/UNYRI/Data/AttachmentStore.swift',
    'ios/UNYRI/Support/HistoryAttachmentRestorer.swift', 'ios/UNYRI/Support/ChatComposerPastePolicy.swift',
    *['ios/Shared/' + name + '.swift' for name in ('UnyriShareStore', 'ShareContainer', 'ShareJob',
       'ShareRoutePolicy', 'ShareTargetSnapshot', 'ShareSignal', 'ShareItemClassification')],
    'ios/UNYRI/Share/ShareJobProcessor.swift',
]
with tempfile.TemporaryDirectory(prefix='unyri-share-recovery-concurrency-') as temporary:
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
