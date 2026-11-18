"""Exercise the production recent-file worker and ownership scope without UIKit."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
swift = os.environ.get('SWIFTC') or shutil.which('swiftc')
if not swift and os.name == 'nt':
    candidate = Path(os.environ['LOCALAPPDATA']) / 'Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe'
    if candidate.is_file():
        swift = str(candidate)
if not swift:
    raise SystemExit('Swift compiler unavailable; set SWIFTC.')
picker = (root / 'ios/UNYRI/UI/Chats/UnyriFolderAttachmentPicker.swift').read_text(encoding='utf-8')
recent = picker[picker.index('enum UnyriRecentFiles {'):picker.index('/// System Files with multi-select')]
models = (root / 'ios/UNYRI/Models/AttachmentModels.swift').read_text(encoding='utf-8')
bounded_copy = models[models.index('enum AttachmentFileCopy {'):models.index('// MARK: - Backend DTOs')]
fixture = '''import Foundation
#if !canImport(Darwin)
extension URL {
    func startAccessingSecurityScopedResource() -> Bool { false }
    func stopAccessingSecurityScopedResource() {}
}
#endif
// Only account-folder lookup is substituted. Worker, scope, file IO and copy
// cancellation are compiled verbatim from production sources.
struct UnyriAccountSlot { let id = UUID().uuidString; let isPrimary = false }
enum UnyriActiveAccountSlot { static let current = UnyriAccountSlot() }
'''
with tempfile.TemporaryDirectory(prefix='unyri-recent-copy-') as directory:
    temporary = Path(directory)
    source = temporary / 'Production.swift'
    source.write_text(fixture + bounded_copy + recent, encoding='utf-8')
    binary = temporary / ('contracts.exe' if os.name == 'nt' else 'contracts')
    subprocess.run([swift, '-parse-as-library', str(source),
                    str(root / 'ios/UNYRI/Support/ChatComposerPastePolicy.swift'),
                    str(Path(__file__).with_name('main.swift')), '-o', str(binary)], check=True, timeout=120)
    subprocess.run([str(binary)], check=True, timeout=30)
