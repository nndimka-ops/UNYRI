import Foundation

let suite = "coco-gallery-send-mode-tests-" + UUID().uuidString
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }

precondition(MediaGallerySendChoice.saved(in: defaults) == .standard)
defaults.set(true, forKey: "mediaSendHighQuality")
precondition(MediaGallerySendChoice.saved(in: defaults) == .high)
defaults.set("obsolete", forKey: MediaGallerySendChoice.preferenceKey)
precondition(MediaGallerySendChoice.saved(in: defaults) == .high)

for mode in MediaGallerySendChoice.allCases {
    mode.save(in: defaults)
    let reopened = UserDefaults(suiteName: suite)!
    precondition(MediaGallerySendChoice.saved(in: reopened) == mode)
    // The new explicit choice takes precedence over a stale legacy quality.
    defaults.set(mode != .high, forKey: "mediaSendHighQuality")
    precondition(MediaGallerySendChoice.saved(in: defaults) == mode)
    let intent = mode.intent(caption: "Caption")
    precondition(intent.caption == "Caption")
    precondition(intent.kind(isVideo: false) == (mode == .original ? "file" : mode.rawValue))
    precondition(intent.kind(isVideo: true) == (mode == .original ? "file" : mode == .high ? "video_high" : "video"))
    precondition(intent.albumId != nil, "One explicit gallery selection groups originals and media equally")
    precondition(mode.intent().albumId != intent.albumId, "Independent sends never share a batch identifier")
    MediaGallerySendChoice.standard.save(in: defaults)
    precondition(MediaGallerySendChoice.saved(in: defaults) == .standard)
    precondition(intent.kind(isVideo: true) == (mode == .original ? "file" : mode == .high ? "video_high" : "video"),
                 "Changing the default must not mutate an admitted batch")
}
defaults.removeObject(forKey: MediaGallerySendChoice.preferenceKey)
defaults.set(false, forKey: "mediaSendHighQuality")
precondition(MediaGallerySendChoice.saved(in: defaults) == .standard)
print("Gallery send mode contracts passed: defaults, migration, persistence, immutable batch, photo/video pipelines")
