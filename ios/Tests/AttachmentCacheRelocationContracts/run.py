"""Run actual account SQLite cache migration with a real relocated container."""
from pathlib import Path
import os
import platform
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root.parent / 'scripts'))
from ios_portable_swift import write_direct_file_policy_shim
flags = ['-swift-version', '5', '-parse-as-library']
if platform.system() == 'Darwin':
    flags += ['-target', platform.machine() + '-apple-macosx13.0']
with tempfile.TemporaryDirectory(prefix='coco-cache-relocation-contracts-') as directory:
    folder = Path(directory)
    executable = folder / ('contracts.exe' if os.name == 'nt' else 'contracts')
    subprocess.run(['swiftc', *flags, *map(str, [
        write_direct_file_policy_shim(root.parent, folder),
        root / 'UNYRI/Support/AppLanguage.swift',
        root / 'UNYRI/DirectTransfer/DirectManualTransferState.swift',
        root / 'UNYRI/DirectTransfer/DirectFileQueueState.swift',
        root / 'UNYRI/DirectTransfer/DirectFileExecutionPermit.swift',
        root / 'UNYRI/DirectTransfer/DirectFileModels.swift',
        root / 'Shared/MessageTextPolicy.swift',
        root / 'UNYRI/Signal/MessageContentCodec.swift',
        root / 'UNYRI/Models/AttachmentModels.swift',
        root / 'UNYRI/Data/AttachmentStore.swift',
        root / 'UNYRI/Attachments/AttachmentCacheBoundary.swift',
        Path(__file__).with_name('main.swift')]), '-o', str(executable)], check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=60)
