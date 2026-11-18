"""Execute production thumbnail writer methods with a Foundation-only encoder seam."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / 'ios/UNYRI/Users/UsersRepository.swift').read_text(encoding='utf-8')


def block(marker):
    start = source.index(marker)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    # The fixture supplies an isolated temp store instead of device storage.
    return source[start:end].replace('AccountAvatarStore.device', 'store')


methods = '\n'.join(block(marker) for marker in [
    '    private func copyOwnAccountAvatar(', '    private func enqueueAccountAvatarWrite(',
])
# Capture the injected immutable store before entering the detached closure.
methods = methods.replace('        let ticketID = UUID()',
                          '        let store = self.store\n        let ticketID = UUID()')
harness = r'''
import Foundation
enum AccountAvatarImage {
    // Real file failure/success, without UIKit/ImageIO in the Foundation test.
    static func jpeg(file: URL) -> Data? { try? Data(contentsOf: file) }
    @MainActor static func didChange(slot: String) {}
}
@MainActor final class Fixture {
    let store: AccountAvatarStore
    let directoryOwner = "self"
    var directoryIdentityIsCurrent = true
    var accountAvatarTicket: AccountAvatarStore.Ticket?
    var accountAvatarWritten: String?
    var cacheWrites: [UUID: Task<Void, Error>] = [:]
    init(root: URL) { store = AccountAvatarStore(root: root) }
    func resolve(id: String = "self", version: Int64 = 1, file: URL?) async {
        await copyOwnAccountAvatar(id: id, raw: "avatars/\(version)", version: version, file: file)
    }
__METHODS__
}
@main struct WriterChecks {
    @MainActor static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("avatar-writer-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let f = Fixture(root: root.appendingPathComponent("thumbnails"))
        let owner = AccountAvatarStore.owner(origin: "https://qa.invalid", user: "self")
        f.accountAvatarTicket = try f.store.begin(slot: "primary", owner: owner, revision: "1|avatars/1")
        let missing = root.appendingPathComponent("evicted.jpg")
        await f.resolve(file: missing)
        precondition(f.accountAvatarWritten == nil, "Failed encode marked the revision complete")
        precondition(f.cacheWrites.isEmpty, "Failed encode leaked a pending write")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: missing)
        await f.resolve(id: "peer", file: missing)
        let afterPeer = try f.store.read(slot: "primary", owner: owner)
        precondition(afterPeer == nil, "Peer avatar was retained")
        await f.resolve(version: 2, file: missing)
        precondition(f.accountAvatarWritten == nil, "Wrong revision was retained")
        await f.resolve(file: missing)
        precondition(f.accountAvatarWritten == "1|avatars/1", "Same revision did not retry")
        let saved = try f.store.read(slot: "primary", owner: owner)
        precondition(saved?.jpeg == Data([1, 2, 3]))
        let wrongOwner = try f.store.read(slot: "primary", owner: "https://qa.invalid|peer")
        precondition(wrongOwner == nil)
        precondition(f.cacheWrites.isEmpty)
        try f.store.purge(slot: "primary")
        f.accountAvatarWritten = nil
        await f.resolve(file: missing)
        let afterPurge = try f.store.read(slot: "primary", owner: owner)
        precondition(afterPurge == nil, "Purged ticket recreated a thumbnail")
        print("Production thumbnail writer: owner/revision binding, failed-encode retry and late purge passed")
    }
}
'''.replace('__METHODS__', methods)
with tempfile.TemporaryDirectory(prefix='unyri-avatar-writer-') as temporary:
    folder = Path(temporary)
    fixture = folder / 'Writer.swift'
    fixture.write_text(harness, encoding='utf-8')
    executable = folder / 'writer.exe'
    subprocess.run(['swiftc', '-parse-as-library', str(root / 'ios/UNYRI/Auth/AccountAvatarStore.swift'),
                    str(fixture), '-o', str(executable)], check=True, timeout=90)
    subprocess.run([str(executable)], check=True, timeout=30)
