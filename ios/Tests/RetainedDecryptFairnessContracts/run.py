"""Run verbatim retry/store methods with real disposable SQLite and a decrypt spy."""
from pathlib import Path
import argparse
import platform
import re
import sqlite3
import subprocess
import sys
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'scripts'))
from ios_portable_swift import _block

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--generate-only', action='store_true')
args = parser.parse_args()
store = (ROOT / 'ios/UNYRI/Data/MessageStore.swift').read_text(encoding='utf-8')
ingestor = (ROOT / 'ios/UNYRI/Messages/MessageIngestor.swift').read_text(encoding='utf-8')
models = (ROOT / 'ios/UNYRI/Models/MessageModels.swift').read_text(encoding='utf-8')
signal = (ROOT / 'ios/UNYRI/Models/SignalModels.swift').read_text(encoding='utf-8')
fixture = (HERE / 'main.swift').read_text(encoding='utf-8')
fixture = fixture.replace('// PRODUCTION_STORE_ERROR', _block(store, '    enum StoreError:'))
fixture = fixture.replace('// PRODUCTION_ERASURE_CHECKPOINT',
    _block(store, 'private final class MessageErasureCheckpoint:'))
fixture = fixture.replace('// PRODUCTION_MODELS', '\n'.join(_block(models, marker) for marker in (
    'struct MessageDto:', 'struct PendingDecrypt:', 'enum MessageStatus:')) + '\n' +
    _block(signal, 'enum SignalDecryptionError:'))
fixture = fixture.replace('// PRODUCTION_STORE', '\n'.join(_block(store, marker) for marker in (
    '    func insertPendingDecrypt(', '    func deletePendingDecrypt(', '    func bumpPendingDecrypt(',
    '    func pendingDecrypts()', '    func pendingDecrypts(senderId:',
    '    private struct PendingDecryptRetryCursor:', '    func pendingDecryptRetryBatch(',
    '    func advancePendingDecryptRetryCursor(', '    private func decodePendingDecrypt(',
    '    func chatValue(', '    func saveChatValue(', '    private func writeChatValue(',
    '    private func sqliteFailure(', '    private func execute(', '    private func withStatement(', '    private func columnOptionalText(',
    '    private func bindText(', '    private func bindOptionalText(')))
fixture = fixture.replace('// PRODUCTION_RETRY', '\n'.join(_block(ingestor, marker) for marker in (
    '    private struct IngestOutcome',
    '    func retryPendingDecrypts(', '    private func retryRetainedDecrypts(',
    '    private func errorClass(', '    private func requireSession(')))
schema = '\n'.join(textwrap.dedent(re.search(r'            CREATE TABLE IF NOT EXISTS ' + table + r' \(.*?\n            \);', store, re.S)[0])
    for table in ('pending_decrypts', 'chat_workspace'))
indexes = '\n'.join(re.findall(r'CREATE INDEX IF NOT EXISTS idx_pending_decrypts[^;]+;', store))
fixture = fixture.replace('// PRODUCTION_SCHEMA', textwrap.indent(schema + '\n' + indexes, '        '))
retry = _block(ingestor, '    private func retryRetainedDecrypts(')
assert 'store.pendingDecryptRetryBatch(senderId:' in retry, 'ios-033 regression: automatic retries still select an unbounded prefix'
assert retry.index('advancePendingDecryptRetryCursor(') < retry.index('await ingestOne(')
assert 'retainedError: retainedError' in _block(ingestor, '    private func ingestOne(')
assert 'outcome.retainedError.map { errorClass($0) }' in retry
# Validate the exact production schema, partial indexes and range query plans
# even on hosts where Swift cannot run. No application DB is opened.
with sqlite3.connect(':memory:') as db:
    db.executescript(schema + indexes)
    eligible = re.search(r'let eligible = "([^"]+)"', store)[1]
    for sender in (False, True):
        query = 'SELECT client_message_id FROM pending_decrypts WHERE ' + eligible
        if sender:
            query += ' AND sender_id = ?'
        query += ' AND (sequence,client_message_id) > (?,?) ORDER BY sequence ASC,client_message_id ASC LIMIT ?'
        params = (['peer'] if sender else []) + [0, '', 128]
        plan = str(db.execute('EXPLAIN QUERY PLAN ' + query, params).fetchall())
        expected = 'idx_pending_decrypts_sender_retry' if sender else 'idx_pending_decrypts_retry'
        assert expected in plan and 'TEMP B-TREE' not in plan, plan
with tempfile.TemporaryDirectory(prefix='unyri-retained-decrypt-fairness-') as temp:
    harness = Path(temp) / 'Harness.swift'
    harness.write_text(fixture, encoding='utf-8')
    if args.generate_only:
        print('PASS: RetainedDecryptFairnessContracts source extraction and SQLite schema/query plans (Swift NOT executed)')
    else:
        binary = Path(temp) / 'contracts'
        target = ['-target', platform.machine() + '-apple-macos13.0'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
            str(ROOT / 'ios/UNYRI/Support/ServerWorkBudget.swift'), str(harness), '-o', str(binary)], check=True, timeout=120)
        subprocess.run([str(binary)], check=True, timeout=30)
