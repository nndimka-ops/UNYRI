import Foundation

typealias Quality = AttachmentImagePolicy.Quality
for sharp in [AttachmentImagePolicy.Origin.localImage, .videoFrame, .notePoster, .viewerFrame] {
    for placeholder in [AttachmentImagePolicy.Origin.tiny, .inline] {
        precondition(AttachmentImagePolicy.prefers(Quality(origin: sharp, pixels: 128), over: Quality(origin: placeholder, pixels: 2048)))
        precondition(!AttachmentImagePolicy.prefers(Quality(origin: placeholder, pixels: 2048), over: Quality(origin: sharp, pixels: 128)))
    }
}
precondition(AttachmentImagePolicy.prefers(Quality(origin: .videoFrame, pixels: 768), over: Quality(origin: .notePoster, pixels: 480)))
precondition(AttachmentImagePolicy.prefers(Quality(origin: .viewerFrame, pixels: 768), over: Quality(origin: .videoFrame, pixels: 768)))
precondition(!AttachmentImagePolicy.prefers(Quality(origin: .localImage, pixels: 256), over: Quality(origin: .localImage, pixels: 768)))
precondition(!AttachmentImagePolicy.prefers(Quality(origin: .localImage, pixels: 768), over: Quality(origin: .localImage, pixels: 768)))
precondition(AttachmentImagePolicy.retryDelay(failures: 1, persistent: false) == 0.5)
precondition(AttachmentImagePolicy.retryDelay(failures: 2, persistent: false) == 2)
precondition(AttachmentImagePolicy.retryDelay(failures: 3, persistent: false) == 8)
precondition(AttachmentImagePolicy.retryDelay(failures: 4, persistent: false) == nil)
precondition(AttachmentImagePolicy.retryDelay(failures: 1, persistent: true) == nil)
for failure in [AttachmentImagePolicy.Failure.unsupported, .invalidFile, .protectedContent] {
    precondition(AttachmentImagePolicy.persistent(failure, steady: true))
    precondition(!AttachmentImagePolicy.persistent(failure, steady: false), "Inactive attempts cannot condemn a file")
}
for failure in [AttachmentImagePolicy.Failure.interrupted, .resourceUnavailable, .unknown] {
    precondition(!AttachmentImagePolicy.persistent(failure, steady: true))
}
for visible in [false, true] {
    for menu in [false, true] {
        precondition(!AttachmentImagePolicy.allowsWork(visible: visible, menu: menu, active: false, viewerOpen: false, viewerSurface: false))
        precondition(!AttachmentImagePolicy.allowsWork(visible: visible, menu: menu, active: true, viewerOpen: true, viewerSurface: false))
    }
}
precondition(!AttachmentImagePolicy.allowsWork(visible: false, menu: false, active: true, viewerOpen: false, viewerSurface: false))
precondition(AttachmentImagePolicy.allowsWork(visible: false, menu: true, active: true, viewerOpen: false, viewerSurface: false))
precondition(AttachmentImagePolicy.allowsWork(visible: true, menu: false, active: true, viewerOpen: true, viewerSurface: true))

let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let file = directory.appendingPathComponent("same-path.jpg")
try Data([1]).write(to: file)
let first = try AttachmentImagePolicy.FileRevision.read(file)
let unchanged = try AttachmentImagePolicy.FileRevision.read(file)
precondition(unchanged == first)
try Data([1, 2]).write(to: file)
let replacement = try AttachmentImagePolicy.FileRevision.read(file)
precondition(first != replacement, "Failure keys must change when the file changes at the same path")
print("PASS: quality monotonicity, note/frame sharing, finite retry/work gates and file-revision identity")
