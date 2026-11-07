"""Run actual controller presentation/page methods across lifecycle orderings.

Only SDK transport and resource observers are seams. No copied presentation
algorithm or UI mock decides whether subscriptions should remain active.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "ios/UNYRI/Conferences/ConferenceController.swift").read_text(encoding="utf-8")
def member(marker):
    start = source.index(marker)
    brace = source.index("{", start); end = brace + 1; depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}"); end += 1
    return source[start:end].replace("private func", "func", 1).replace("@Published ", "", 1)

harness = r'''
import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
class Participant { var camera = true; func isCameraEnabled() -> Bool { camera } }
final class LocalParticipant: Participant {}
struct Tile {
    let id: String; let participant: Participant
    init(id: String) { self.id = id; participant = id == "self" ? LocalParticipant() : Participant() }
}
final class Room {}
enum Phase { case connected, leaving }
@MainActor final class Owner {
    var tiles = [Tile(id: "self")] + (0..<29).map { Tile(id: "peer-\($0)") }
    var expectedIdentity: String? = "self"
    var remotePageAnchorID: String?
    var selectedVisibleCount = 5, fitEveryoneOnScreen = false
    var locallyHiddenParticipantIDs = Set<String>()
    var pinnedID: String?
    var videoPresentationOwner: UUID?
    var previewViewport: CGSize?
    var ownPreviewPosition = CGPoint(x: 0.96, y: 0.05)
    var desiredPresentationIDs = Set<String>()
    var presentationActive = true
    var visibleVideoIDs = Set<String>()
    var foreground = true, cameraEnabled = true, isBusy = true
    var phase = Phase.connected
    var attempt = UUID()
    var room: Room? = Room()
    var subscriptionHistory: [Set<String>] = []
    var mediaReconciles = 0
    func updateSubscriptions(_ room: Room) { subscriptionHistory.append(visibleVideoIDs) }
    func reconcileMedia() { mediaReconciles += 1 }
    func startMonitor(_ id: UUID, immediately: Bool) {}
    __MEMBERS__
}
var count = 0
func check(_ condition: Bool, _ label: String) { precondition(condition, label); count += 1 }
@main struct PresentationContracts {
    @MainActor static func main() {
        let value = Owner(), first = UUID(), second = UUID()
        let page = Set((0..<6).map { "peer-\($0)" }), next = Set((9..<18).map { "peer-\($0)" })
        check(value.remoteTiles.count == 29 && !value.remoteTiles.contains(where: { $0.id == "self" }), "remote subscription list excludes own capture")
        check(value.stageTiles.count == 30 && value.stageTiles.first?.id == "self", "own camera takes first real grid cell including camera-off mode")
        value.cameraEnabled = false
        check(value.stageTiles.first?.id == "self", "camera-off never removes the own tile or shifts everyone else's position")
        value.cameraEnabled = true
        value.expectedIdentity = nil
        check(value.remoteTiles.count == 29 && value.ownTile?.id == "self", "cleared recovery crypto authority never reclassifies own video as a remote participant")
        value.selectRemotePage(4, size: 6)
        check(value.remotePageIndex(size: 6) == 4 && value.remotePageIndex(size: 9) == 2, "remote anchor survives rotation rather than jumping to page one")
        value.focusParticipant("peer-27")
        check(value.pinnedID == "peer-27" && value.remotePageIndex(size: 6) == 4, "directory focuses remote and records its grid page")
        value.focusParticipant("self")
        check(value.pinnedID == "peer-27", "directory cannot treat own capture as remote media")
        value.selectRemotePage(-9, size: 6)
        check(value.remotePageIndex(size: 6) == 0, "negative page clamps")
        value.selectRemotePage(999, size: 6)
        check(value.remotePageIndex(size: 6) == 4 && value.remotePageIndex(size: 0) == 0, "overflow page and empty page size are bounded")
        value.beginVideoPresentation(first); value.presentVideo(page, owner: first)
        value.pinnedID = nil
        value.recordScrollAnchor(["peer-10", "peer-11"], owner: first)
        check(value.remotePageAnchorID == "peer-10", "scroll records first stable visible identity")
        value.recordScrollAnchor(["peer-20"], owner: second)
        check(value.remotePageAnchorID == "peer-10", "stale presentation cannot change scroll restoration anchor")
        check(value.visibleVideoIDs == page && value.shouldKeepScreenOn, "visible live video owns screen-on and exact bounded subscriptions")
        value.cameraEnabled = false; value.locallyHiddenParticipantIDs = page
        check(value.shouldKeepScreenOn, "visible audio-only room stays awake even when all remote video is locally hidden")
        value.presentVideo(page, owner: first, active: false)
        check(value.shouldKeepScreenOn, "participants/settings presentation does not enable auto-lock")
        value.phase = .leaving
        check(!value.shouldKeepScreenOn, "leaving relinquishes display ownership immediately")
        value.phase = .connected
        value.presentVideo(page, owner: first, active: true)
        value.locallyHiddenParticipantIDs = []; value.cameraEnabled = true
        value.setForeground(false)
        check(value.visibleVideoIDs.isEmpty && value.desiredPresentationIDs == page, "background drops remote video without forgetting selected page")
        value.presentVideo(page, owner: first, active: false)
        value.setForeground(true)
        check(value.visibleVideoIDs.isEmpty, "root resumes first while view remains inactive")
        value.presentVideo(page, owner: first, active: true)
        check(value.visibleVideoIDs == page, "child activation completes root-first resume")
        value.setForeground(false)
        value.presentVideo(page, owner: first, active: true)
        check(value.visibleVideoIDs.isEmpty, "child resumes first while root remains background")
        value.setForeground(true)
        check(value.visibleVideoIDs == page, "root activation restores child-first selected page")
        let anchor = value.remotePageAnchorID, pin = value.pinnedID
        value.minimized = true
        check(value.visibleVideoIDs.isEmpty && !value.shouldKeepScreenOn, "minimized strip never retains remote video or visible-video idle lease")
        value.minimized = false
        check(value.visibleVideoIDs == page && value.remotePageAnchorID == anchor && value.pinnedID == pin,
            "canceled UIKit dismissal restores same presentation without a second onAppear")
        value.beginVideoPresentation(second); value.presentVideo(next, owner: second)
        value.endVideoPresentation(first)
        check(value.visibleVideoIDs == next && value.videoPresentationOwner == second, "old disappearing view cannot clear successor subscriptions")
        value.presentVideo(["wrong"], owner: first)
        check(value.visibleVideoIDs == next, "old scene callback cannot restore stale page")
        let viewport = CGSize(width: 320, height: 500), rotated = CGSize(width: 600, height: 200)
        value.registerPreviewViewport(viewport, owner: second)
        let original = value.ownPreviewPosition
        value.moveOwnPreview(CGPoint(x: 0.1, y: 0.2), owner: first, viewport: viewport)
        check(value.ownPreviewPosition == original, "late old-view drag cannot move successor own video")
        value.moveOwnPreview(CGPoint(x: -2, y: 3), owner: second, viewport: viewport)
        check(value.ownPreviewPosition == CGPoint(x: 0, y: 1), "valid drag stores bounded normalized position")
        value.registerPreviewViewport(rotated, owner: second)
        value.moveOwnPreview(CGPoint(x: 0.5, y: 0.5), owner: second, viewport: viewport)
        check(value.ownPreviewPosition == CGPoint(x: 0, y: 1), "late pre-rotation drag cannot overwrite normalized position")
        value.moveOwnPreview(CGPoint(x: CGFloat.nan, y: 0), owner: second, viewport: rotated)
        check(value.ownPreviewPosition == CGPoint(x: 0, y: 1), "nonfinite gesture coordinates rejected")
        value.minimized = true
        value.moveOwnPreview(CGPoint(x: 0.4, y: 0.4), owner: second, viewport: rotated)
        check(value.ownPreviewPosition == CGPoint(x: 0, y: 1), "hidden preview cannot apply delayed drag")
        value.minimized = false; value.endVideoPresentation(second)
        check(value.visibleVideoIDs.isEmpty && value.desiredPresentationIDs.isEmpty && !value.shouldKeepScreenOn, "last presentation releases only owned video demand")
        value.setForeground(false); value.setForeground(true)
        check(value.visibleVideoIDs.isEmpty, "foreground cannot revive a completed presentation")
        print("PASS: \(count) actual-method page/minimize/resume/foreground/preview ownership assertions")
    }
}
'''
markers = ["var remoteTiles: [Tile]", "var ownTile: Tile?", "var stageTiles: [Tile]", "var shouldKeepScreenOn: Bool", "@Published var minimized = false",
           "func remotePageIndex", "func selectRemotePage", "func focusParticipant", "func beginVideoPresentation",
           "func recordScrollAnchor",
           "func registerPreviewViewport", "func moveOwnPreview", "func endVideoPresentation", "func presentVideo",
           "private func applyPresentedVideo", "func showVideoParticipants", "func setForeground"]
harness = harness.replace("__MEMBERS__", "\n".join(member(marker) for marker in markers))
with tempfile.TemporaryDirectory(prefix="coco-conference-presentation-") as directory:
    folder = Path(directory); entry = folder / "main.swift"; executable = folder / ("checks.exe" if os.name == "nt" else "checks")
    entry.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(entry), "-o", str(executable)], check=True, timeout=60)
    subprocess.run([str(executable)], check=True, timeout=20)
