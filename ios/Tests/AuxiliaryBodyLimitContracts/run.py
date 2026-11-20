"""Compile actual collectors and local URLProtocol fixtures; never opens a socket."""
from pathlib import Path
import argparse
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def block(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def sources():
    def read(name):
        return (ROOT / name).read_text(encoding='utf-8')
    api = read('ios/UNYRI/Networking/APIClient.swift')
    recovery = read('ios/UNYRI/UI/Auth/AccountRecoveryView.swift')
    profile = read('ios/UNYRI/UI/Profile/ProfileView.swift')
    notification = read('ios/UNYRINotificationService/NotificationService.swift')
    assert 'session.unyriBoundedData(for: request, maximumBytes: 64 * 1024)' in recovery
    assert 'URLSession.shared.unyriBoundedData(for: request, maximumBytes: 16 * 1024)' in profile
    assert 'session.unyriBoundedData(for: request, maximumBytes: 8 * 1024 * 1024)' in profile
    for text in [recovery, profile]:
        assert '.data(for:' not in text
    diagnostics = block(api, 'func report(baseURL:')
    assert 'task.delegate = DiscardedHTTPBody()' in diagnostics
    assert 'dataTask(with: request) {' not in diagnostics
    assert 'task.delegate = DeliveryResponseDiscarder' in notification
    assert 'dataTask(with: post) {' not in notification
    finish = block(notification, 'private func finish()')
    assert 'deliveryTask = nil' in finish and 'task?.cancel()' in finish
    parts = [block(api, marker) for marker in [
        'struct HTTPBodyLimitExceeded:', 'extension URLSession {',
        'private final class DirectJSONCollector:', 'private final class DiscardedHTTPBody:']]
    parts.append(block(notification, 'private final class DeliveryResponseDiscarder:'))
    # Internal visibility only in the harness; all method bodies stay unchanged.
    actual = 'import Foundation\n' + '\n'.join(parts).replace('private final class ', 'final class ')
    actual += '\nfinal class NotificationFinishHarness {\nlet lock = NSLock()\n'
    actual += 'var contentHandler: ((String) -> Void)?\nvar content: String?\nvar deliveryTask: URLSessionDataTask?\n'
    actual += finish.replace('private func finish()', 'func finish()') + '\n}\n'
    return actual


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    actual = sources()
    with tempfile.TemporaryDirectory(prefix='unyri-auxiliary-bodies-') as temp:
        folder = Path(temp)
        adapter = folder / 'Actual.swift'
        adapter.write_text(actual, encoding='utf-8')
        files = [adapter, HERE / 'main.swift']
        assert all(path.is_file() for path in files)
        if args.generate_only:
            print('PASS: AuxiliaryBodyLimitContracts source gates/extraction (Swift NOT executed)')
            return
        target = ['-target', platform.machine() + '-apple-macos14.2'] if platform.system() == 'Darwin' else []
        binary = folder / 'contracts'
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
                        *map(str, files), '-o', str(binary)], check=True, timeout=180)
        subprocess.run([str(binary)], check=True, timeout=60)


if __name__ == '__main__':
    main()
