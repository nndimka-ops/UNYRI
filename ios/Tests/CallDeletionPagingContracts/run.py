"""Exercise the production deletion loop and refresh cancellation with local seams."""
from pathlib import Path
import argparse
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'scripts'))
from ios_portable_swift import _block

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--generate-only', action='store_true')
args = parser.parse_args()
source = (ROOT / 'ios/UNYRI/Networking/CallsAPI.swift').read_text(encoding='utf-8')
fixture = (HERE / 'main.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_METHODS', '\n'.join(_block(source, marker) for marker in (
    '    private final class SharedHistoryRefresh', '    func refreshHistory(', '    private func syncHistoryDeletions(',
    '    private func requireSession(')))
models = (ROOT / 'ios/UNYRI/Calls/CallModels.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_MODELS', '\n'.join(_block(models, marker) for marker in (
    'struct CallDeletion:', 'struct CallDeletionPage:', 'struct CallDeletionState:')))
assert 'budget.accept(' in _block(source, '    private func syncHistoryDeletions('), 'ios-008 regression: deletion paging has no admission budget'
assert 'onCancel: { shared.leave(waiter, cancelled: true) }' in _block(source, '    func refreshHistory(')
with tempfile.TemporaryDirectory(prefix='unyri-call-deletion-paging-') as temp:
    harness = Path(temp) / 'Harness.swift'
    harness.write_text(fixture, encoding='utf-8')
    if args.generate_only:
        print('PASS: CallDeletionPagingContracts source extraction (Swift NOT executed)')
    else:
        binary = Path(temp) / 'contracts'
        target = ['-target', platform.machine() + '-apple-macos13.0'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
            str(ROOT / 'ios/UNYRI/Support/ServerWorkBudget.swift'), str(harness), '-o', str(binary)], check=True, timeout=120)
        subprocess.run([str(binary)], check=True, timeout=30)
