"""Actual Foundation-only client batch policy; no network, keys or renderer."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / 'ios/UNYRI/Messages/ForegroundMessageBannerController.swift').read_text(encoding='utf-8')
start = source.index('// BEGIN notification batch policy')
end = source.index('// END notification batch policy.')
policy = 'import Foundation\n' + source[start:end]
with tempfile.TemporaryDirectory(prefix='coco-notification-batch-') as temporary:
    path = Path(temporary)
    (path / 'Policy.swift').write_text(policy, encoding='utf-8')
    executable = path / 'contracts'
    subprocess.run(['swiftc', '-parse-as-library', str(path / 'Policy.swift'), str(Path(__file__).with_name('main.swift')),
                    '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
