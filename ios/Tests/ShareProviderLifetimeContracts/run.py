"""Execute the production URL-provider bridge against callback-scoped files."""
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
from ios_portable_swift import _block

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--swiftc', default=os.environ.get('SWIFTC') or shutil.which('swiftc'))
args = parser.parse_args()
if not args.swiftc:
    parser.error('Swift compiler unavailable; set SWIFTC or --swiftc')
composer = (ROOT / 'ios/UNYRIShare/ShareComposerView.swift').read_text(encoding='utf-8')
bridge = _block(composer, '    nonisolated private static func loadURLItem(')
fixture = Path(__file__).with_name('main.swift').read_text(encoding='utf-8').replace('// PRODUCTION_BRIDGE', bridge)
flags = ['-swift-version', '5', '-parse-as-library']
if platform.system() == 'Darwin':
    flags += ['-target', platform.machine() + '-apple-macosx13.0']
with tempfile.TemporaryDirectory(prefix='unyri-provider-lifetime-') as temporary:
    folder = Path(temporary)
    harness = folder / 'Harness.swift'
    harness.write_text(fixture, encoding='utf-8')
    binary = folder / ('contracts.exe' if os.name == 'nt' else 'contracts')
    subprocess.run([args.swiftc, *flags, str(ROOT / 'ios/Shared/UnyriShareStore.swift'), str(harness), '-o', str(binary)],
                   check=True, timeout=120)
    subprocess.run([str(binary)], check=True, timeout=20)
