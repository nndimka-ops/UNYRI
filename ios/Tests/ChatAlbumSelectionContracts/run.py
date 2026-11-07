from pathlib import Path
import subprocess, tempfile

root = Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='coco-album-actions-') as temporary:
    executable = Path(temporary) / ('album-actions.exe' if __import__('os').name == 'nt' else 'album-actions')
    subprocess.run(['swiftc', str(root/'ios/UNYRI/Support/ChatAlbumSelection.swift'),
                    str(root/'ios/UNYRI/Support/MediaPreparationState.swift'),
                    str(root/'ios/Tests/ChatAlbumSelectionContracts/main.swift'), '-o', str(executable)], check=True, timeout=120)
    subprocess.run([str(executable)], check=True, timeout=20)
