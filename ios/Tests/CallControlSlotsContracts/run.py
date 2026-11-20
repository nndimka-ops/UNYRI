"""Measure the production SwiftUI control slot across all outgoing call phases."""
from pathlib import Path
import argparse
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def extracted_slot():
    source = (ROOT / 'ios/UNYRI/UI/Calls/CallScreen.swift').read_text(encoding='utf-8')
    start = source.index('struct CallControlSlot<Content: View>: View {')
    end = source.index('\n}\n', start) + 3
    # Both public control builders must keep this production slot even when
    # disabled. The runtime test measures that exact component below.
    route = source[source.index('private func routeButton('):source.index('/// One avatar with its halo.')]
    circle = source[source.index('private func controlCircle('):start]
    assert 'CallControlSlot(size: size, enabled: enabled)' in route
    assert 'CallControlSlot(size: size, enabled: enabled)' in circle
    row = source[source.index('private func controlsRow('):source.index('private func end(')]
    assert row.count('controlCircle(') == 5  # Two incoming actions; four outgoing slots include route.
    assert row.count('routeButton(') == 1
    models = (ROOT / 'ios/UNYRI/Calls/CallModels.swift').read_text(encoding='utf-8')
    p_start = models.index('enum CallControlRowPolicy {')
    p_end = models.index('\n}\n', p_start) + 3
    return 'import SwiftUI\n' + source[start:end] + '\n' + models[p_start:p_end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    component = extracted_slot()
    if args.generate_only:
        print('PASS: call control slot and row policy source wiring (SwiftUI geometry NOT executed)')
        return
    if platform.system() != 'Darwin':
        raise SystemExit('SwiftUI geometry requires macOS; use --generate-only for source validation.')
    with tempfile.TemporaryDirectory(prefix='unyri-call-slots-') as temp:
        temp = Path(temp)
        slot = temp / 'CallControlSlot.swift'
        slot.write_text(component, encoding='utf-8')
        binary = temp / 'contracts'
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library',
                        '-target', platform.machine() + '-apple-macos14.2',
                        str(slot), str(HERE / 'main.swift'), '-o', str(binary)], check=True, timeout=180)
        subprocess.run([str(binary)], check=True, timeout=60)


if __name__ == '__main__':
    main()
