"""Compile production product validation with explicit publisher fixtures; no network."""
from pathlib import Path
import argparse
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    validation = ROOT / 'ios/UNYRI/Support/Validation.swift'
    text = validation.read_text(encoding='utf-8')
    assert 'publisherURL: Bundle.main.object(forInfoDictionaryKey: "UNYRI_UPDATE_URL")' in text
    assert 'candidate.product == publisher.product' in text
    # Both mandatory and recommended update surfaces use this one policy.
    app = (ROOT / 'ios/UNYRI/UNYRIApp.swift').read_text(encoding='utf-8')
    assert 'AppVersion.trustedUpdateURL(entry.updateURL)' in app
    with tempfile.TemporaryDirectory(prefix='unyri-update-products-') as temp:
        folder = Path(temp)
        stub = folder / 'L10n.swift'
        stub.write_text('enum UnyriL10n { static var language = "en"; '
                        'static func string(_ key: String) -> String { key } }\n', encoding='utf-8')
        files = [stub, validation, HERE / 'main.swift']
        assert all(path.is_file() for path in files)
        if args.generate_only:
            print('PASS: UpdateProductURLContracts source gates/dependencies (Swift NOT executed)')
            return
        target = ['-target', platform.machine() + '-apple-macos14.2'] if platform.system() == 'Darwin' else []
        binary = folder / 'contracts'
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
                        *map(str, files), '-o', str(binary)], check=True, timeout=180)
        subprocess.run([str(binary)], check=True, timeout=60)


if __name__ == '__main__':
    main()
