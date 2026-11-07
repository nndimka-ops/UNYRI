"""Run actual account SQLite cache migration with a real relocated container."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='coco-cache-relocation-contracts-') as directory:
    executable = Path(directory) / 'contracts'
    subprocess.run(['swiftc', '-parse-as-library', *map(str, [
        root / 'COCO/Support/AppLanguage.swift',
        root / 'COCO/Signal/MessageContentCodec.swift',
        root / 'COCO/Models/AttachmentModels.swift',
        root / 'COCO/Data/AttachmentStore.swift',
        Path(__file__).with_name('main.swift')]), '-o', str(executable)], check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=60)
