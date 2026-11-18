import Foundation

func copy(_ status: String = "delivered", kind: String = "content", hidden: Bool = false,
          deleted: Bool = false, direct: Bool = false, media: String? = "image",
          mime: String? = "image/jpeg", local: Bool = true, server: String? = "server-message") -> UnyriMessageMediaCopyPolicy.Kind? {
    UnyriMessageMediaCopyPolicy.kind(messageKind: kind, status: status, serverID: server, hidden: hidden,
        deleted: deleted, directFile: direct, mediaType: media, mime: mime, localAvailable: local)
}

for status in ["sent", "delivered", "read"] {
    precondition(copy(status) == .image)
    precondition(copy(status, mime: "image/gif") == .gif)
    precondition(copy(status, media: "file", mime: "video/mp4") == .movie)
    precondition(copy(status, mime: "video/quicktime") == .movie)
    precondition(copy(status, local: false) == nil, "Inline/cached previews are not copyable files")
    precondition(copy(status, hidden: true) == nil)
    precondition(copy(status, deleted: true) == nil)
    precondition(copy(status, direct: true) == nil)
}
for status in ["pending", "failed", "decrypt_failed", "identity_changed", "unknown", ""] { precondition(copy(status) == nil) }
precondition(copy(server: nil) == nil)
for kind in ["delete", "reaction", "edit", ""] { precondition(copy(kind: kind) == nil) }
precondition(copy(mime: "IMAGE/GIF") == .gif)
precondition(copy(media: "file", mime: "image/png") == nil, "Image documents are out of scope")
precondition(copy(media: "voice", mime: "audio/ogg") == nil)
precondition(copy(media: nil, mime: nil) == nil)
precondition(copy(mime: "application/octet-stream") == nil)

print("PASS: media Copy eligibility, confirmed/local-file requirement and export exclusions")
