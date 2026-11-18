import Foundation

typealias Policy = ChatComposerPastePolicy
var checks = 0
func check(_ value: Bool, _ reason: String) {
    checks += 1
    if !value { fputs("FAIL: \(reason)\n", stderr); exit(1) }
}

// Explicit fixture hierarchy: the policy does not import UIKit or UTType. The
// device adapter supplies UTType.conforms(to:) for real and third-party types.
let parents: [String: String] = [
    "public.heic": "public.image", "public.heif": "public.image",
    "public.png": "public.image", "public.jpeg": "public.image",
    "com.compuserve.gif": "public.image", "public.tiff": "public.image",
    "public.mpeg-4": "public.movie", "com.apple.quicktime-movie": "public.movie",
    "public.movie": "public.video", "public.video": "public.audiovisual-content",
    "public.utf8-plain-text": "public.plain-text", "public.utf16-plain-text": "public.plain-text",
    "public.plain-text": "public.text", "public.rtf": "public.text", "public.html": "public.text",
    "public.url": "public.data", "public.file-url": "public.url",
    "org.example.camera-image": "public.heic", "org.example.camera-video": "public.movie"
]
func conforms(_ type: String, _ parent: String) -> Bool {
    var current: String? = type
    while let value = current {
        if value == parent { return true }
        current = parents[value]
    }
    return false
}
func media(_ types: [String]) -> Policy.Media? { Policy.media(in: types, conforms: conforms) }
func text(_ types: [String]) -> String? { Policy.textType(in: types, conforms: conforms) }

@MainActor final class ImportGate {
    var entered = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async {
        await withCheckedContinuation { continuation in
            self.continuation = continuation; entered = true
        }
    }
    func release() { continuation?.resume(); continuation = nil }
}
final class CancellationProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func cancel() { lock.lock(); value = true; lock.unlock() }
}

@main struct ComposerPasteContracts {
    @MainActor static func main() async {
        check(Set(Policy.acceptableTypes).count == Policy.acceptableTypes.count, "Configuration has no duplicate types")
        for type in Policy.imageTypes {
            check(Policy.acceptableTypes.contains(type), "Common image representation is advertised")
            check(media([type])?.kind == (type == "com.compuserve.gif" ? .animatedImage : .image), "Common images are routed")
        }
        for type in Policy.videoTypes {
            check(media([type])?.isVideo == true, "Movie and video families both use video preparation")
        }
        for type in Policy.plainTextTypes {
            check(text([type]) == type && media([type]) == nil, "Plain text stays text, including UTF-8/UTF-16")
        }
        check(media(["org.example.camera-image"])?.kind == .image, "Conforming image subtypes are accepted")
        check(media(["org.example.camera-video"])?.isVideo == true, "Conforming video subtypes are accepted")
        check(media(["public.tiff"])?.kind == .image, "Unlisted standard image subtypes are accepted")
        check(media(["public.png", "com.compuserve.gif", "public.jpeg"]) == Policy.Media(typeIdentifier: "com.compuserve.gif", kind: .animatedImage),
              "GIF wins over a still alternative regardless of registration order")
        check(media(["public.utf8-plain-text", "public.mpeg-4"])?.isVideo == true && text(["public.utf8-plain-text", "public.mpeg-4"]) != nil,
              "One provider can route both its media and its text without losing either")
        let mixed = [["public.utf8-plain-text"], ["public.jpeg", "public.png"], ["public.mpeg-4"], ["public.url"]]
        check(mixed.compactMap(media).count == 2 && mixed.compactMap(text).count == 1,
              "Multiple representations do not become multiple attachments; mixed text is preserved")
        check(text(["public.rtf", "public.utf8-plain-text", "public.png"]) == "public.utf8-plain-text", "Plain text wins over a rich alternative")
        check(text(["public.rtf", "public.html", "public.png"]) == "public.rtf", "Rich-only text keeps the existing UIKit text handling")
        check(text(["public.png", "public.mpeg-4"]) == nil, "Media bytes never enter the text paste path")
        for type in ["public.url", "public.file-url", "public.data", "public.audio", "public.html", "public.rtf", "org.example.unknown"] {
            check(media([type]) == nil, "Unsupported data and file URLs never become pasted media")
            check(!Policy.acceptableTypes.contains(type), "Added configuration types do not claim unrelated content; inherited text types stay unchanged")
        }
        check(media([]) == nil && text([]) == nil, "Empty clipboard is not actionable media")
        let first = Policy.identity(changeCount: 8, itemIndex: 0)
        check(first == Policy.identity(changeCount: 8, itemIndex: 0), "Repeated paste dedupes against loading and landed slots")
        check(first != Policy.identity(changeCount: 8, itemIndex: 1), "Distinct clipboard items retain their order and identity")
        check(first != Policy.identity(changeCount: 9, itemIndex: 0), "A newly copied item is a new selection")

        func route(_ types: [String]) -> Policy.UIKitRoute { Policy.uiKitRoute(in: types, conforms: conforms) }
        for types in [["public.url"], ["public.file-url"], ["public.rtf"], ["org.example.future-uikit-type"], []] {
            check(route(types) == .unchanged, "Every non-media provider reaches UIKit unchanged, including URL-only items")
        }
        check(route(["public.jpeg"]) == .representations([]), "A media-only provider never reaches the text storage")
        check(route(["public.jpeg", "public.url"]) == .representations(["public.url"]), "A media provider preserves its URL-only alternative")
        check(route(["public.jpeg", "public.rtf", "public.utf8-plain-text", "public.url", "public.file-url"]) ==
              .representations(["public.utf8-plain-text", "public.url", "public.file-url"]), "Mixed provider retains text and supported URLs without its image representation")
        check(route(["public.mpeg-4", "public.rtf"]) == .representations(["public.rtf"]), "Mixed video/rich text retains UIKit text semantics")

        let mib: Int64 = 1024 * 1024
        func plan(_ origin: Policy.FileOrigin, original: Bool = true, video: Bool = false, external: Bool = true) -> Policy.FilePlan {
            Policy.filePlan(origin: origin, original: original, video: video, retainExternalDocument: external,
                            ordinaryLimit: 100 * mib, videoLimit: 500 * mib, originalLimit: 64 * 1024 * mib)
        }
        for external in [false, true] {
            check(plan(.provider, external: external) == Policy.FilePlan(owned: true, maximumBytes: 64 * 1024 * mib),
                  "Pasted GIF/original always becomes an owned draft, including direct/unsupported-large routes")
            check(plan(.provider, video: true, external: external) == Policy.FilePlan(owned: true, maximumBytes: 64 * 1024 * mib),
                  "Pasted original video retains direct-original capacity and durable ownership")
            check(plan(.provider, original: false, video: true, external: external) == Policy.FilePlan(owned: true, maximumBytes: 500 * mib),
                  "Media videos retain the source-video limit, independently of transport")
            check(plan(.provider, original: false, external: external) == Policy.FilePlan(owned: true, maximumBytes: 100 * mib),
                  "Ordinary pasted images retain their bounded import")
        }
        check(plan(.document).owned == false, "External direct Files picks retain their existing document grant")
        check(plan(.document, external: false) == Policy.FilePlan(owned: true, maximumBytes: 100 * mib), "Ordinary Files staging keeps its existing limit")

        let imports = ChatComposerImportTasks()
        let loading = ImportGate(), copying = ImportGate(), next = ImportGate()
        let loadingCancel = CancellationProbe(), copyingCancel = CancellationProbe(), nextCancel = CancellationProbe()
        var loadingFinished = false, copyingFinished = false, nextFinished = false, duplicateFinished = false
        imports.start {
            await withTaskCancellationHandler(operation: { await loading.wait() }, onCancel: { loadingCancel.cancel() })
            loadingFinished = true
        }
        while !loading.entered { await Task.yield() }
        imports.start { duplicateFinished = true }
        check(imports.count == 2, "A duplicate request cannot replace the active provider task handle")
        while !duplicateFinished { await Task.yield() }
        check(imports.count == 1, "Finishing a duplicate only releases that duplicate's handle")
        imports.start {
            await withTaskCancellationHandler(operation: { await copying.wait() }, onCancel: { copyingCancel.cancel() })
            copyingFinished = true
        }
        while !copying.entered { await Task.yield() }
        imports.cancelAll()
        check(loadingCancel.cancelled && copyingCancel.cancelled && imports.isEmpty,
              "Tray clear/account switch cancels all active provider downloads and copies after a duplicate")
        imports.start {
            await withTaskCancellationHandler(operation: { await next.wait() }, onCancel: { nextCancel.cancel() })
            nextFinished = true
        }
        while !next.entered { await Task.yield() }
        loading.release(); copying.release()
        while !loadingFinished || !copyingFinished { await Task.yield() }
        check(imports.count == 1, "Late completion from the retired generation cannot release a new import's handle")
        imports.cancelAll()
        check(nextCancel.cancelled, "The new generation remains independently cancellable")
        next.release()
        while !nextFinished { await Task.yield() }
        var queuedStarted = false
        imports.start { queuedStarted = true }
        imports.cancelAll()
        await Task.yield()
        check(!queuedStarted && imports.isEmpty, "A request cancelled before its worker starts cannot reserve late tray slots")
        print("PASS: \(checks) composer paste routing, ownership, limits and cancellation contracts")
    }
}
