"""Exercise actual UI-demand/status methods without claiming Apple rendering proof."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def member(path, marker):
    text = (ROOT / path).read_text(encoding="utf-8")
    start = text.index(marker)
    brace = text.index("{", start)
    end, depth = brace + 1, 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end].replace("private func", "func", 1).replace("private var", "var", 1)


harness = r'''
import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
enum ScenePhase { case active, inactive, background }
struct DynamicType { var isAccessibilitySize = false }
struct Tile { let id: String }
enum ConferenceRoomMetrics { __DEMAND__ }
__GRID__
final class Controller {
    var stageTiles = [Tile(id: "self")] + (0..<29).map { Tile(id: "p\($0)") }
    var selectedVisibleCount = 0, fitEveryoneOnScreen = false
    var pinnedID: String?
    var lastIDs = Set<String>(), active = true
    var history: [(Set<String>, Bool)] = []
    func remotePageIndex(size: Int) -> Int { 0 }
    func presentVideo(_ ids: Set<String>, owner: UUID, active: Bool) {
        lastIDs = ids; self.active = active; history.append((ids, active))
    }
}
struct Screen {
    let controller: Controller
    var scenePhase = ScenePhase.active, dynamicType = DynamicType()
    var showLayout = false, showRoomSettings = false, participants = false
    var share: String?
    var scrolledVideoIDs = Set<String>(), presentationOwner = UUID()
    __UPDATE__
}
enum Status { case pending, failed, sent, read }
struct Message { var status = Status.sent }
struct Card {
    var message = Message(), expired = false
    __STATUS__
}
var checks = 0
func check(_ condition: Bool, _ label: String) { precondition(condition, label); checks += 1 }
let viewport = CGRect(x: 0, y: 0, width: 150, height: 100)
let visibleFrames: [String: CGRect] = [
    "full": CGRect(x: 0, y: 0, width: 100, height: 100),
    "partial": CGRect(x: 100, y: 0, width: 100, height: 100),
    "outside": CGRect(x: 300, y: 0, width: 100, height: 100),
    "sliver": CGRect(x: 149, y: 0, width: 10, height: 100),
]
check(ConferenceGridPolicy.visibleIDs(visibleFrames, viewport: viewport, limit: 1) == ["full"], "largest visible area receives the first video slot")
check(ConferenceGridPolicy.visibleIDs(visibleFrames, viewport: viewport, limit: 2) == ["full", "partial"], "offscreen tiles and one-point slivers consume no decode slots")
check(ConferenceGridPolicy.visibleIDs(visibleFrames, viewport: viewport, limit: 0) == ["full"], "zero requested slots retains the documented minimum")
check(ConferenceGridPolicy.visibleIDs([:], viewport: viewport, limit: 30).isEmpty, "empty geometry produces no subscriptions")
let tied = ["b": CGRect(x: 0, y: 0, width: 30, height: 30), "a": CGRect(x: 0, y: 0, width: 30, height: 30)]
check(ConferenceGridPolicy.visibleIDs(tied, viewport: viewport, limit: 1) == ["a"], "equal visibility uses a deterministic identity tie break")
let crowded = Dictionary(uniqueKeysWithValues: (0..<35).map { (String(format: "p%02d", $0), CGRect(x: 0, y: 0, width: 20, height: 20)) })
let bounded = ConferenceGridPolicy.visibleIDs(crowded, viewport: viewport, limit: 100)
check(bounded.count == 30 && bounded.contains("p29") && !bounded.contains("p30"), "even stale oversized geometry cannot exceed thirty video slots")
let controller = Controller()
var screen = Screen(controller: controller)
screen.updateVisible(landscape: false)
let page = controller.lastIDs
check(page.count == 6 && controller.active, "initial measured page")
screen.participants = true; screen.updateVisible(landscape: false)
check(controller.lastIDs == page && controller.active, "participant sheet keeps the same video demand")
screen.showLayout = true; screen.showRoomSettings = true; screen.share = "sheet"
screen.updateVisible(landscape: false)
check(controller.lastIDs == page && controller.active, "room menus do not stop video")
controller.pinnedID = "p1"; screen.updateVisible(landscape: false)
check(controller.lastIDs == page, "focus inside the page never unsubscribes its peers")
controller.pinnedID = "p28"; screen.updateVisible(landscape: false)
check(controller.lastIDs == page.union(["p28"]) && controller.lastIDs.count == 7, "only one out-of-page focus is added")
controller.pinnedID = nil; screen.updateVisible(landscape: false)
check(controller.lastIDs == page, "return restores existing page demand")
check(controller.history.allSatisfy { !$0.0.isEmpty && $0.1 }, "no transient stop across sheets and focus")
screen.scenePhase = .background; screen.updateVisible(landscape: false)
check(!controller.active, "real background still suspends the presentation")
screen.scenePhase = .active; controller.selectedVisibleCount = 5
screen.scrolledVideoIDs = Set((5..<10).map { "p\($0)" }); screen.updateVisible(landscape: false)
check(controller.lastIDs == screen.scrolledVideoIDs, "scroll demand stays bounded to its viewport")
controller.pinnedID = "p28"; screen.updateVisible(landscape: false)
check(controller.lastIDs.count == 6 && controller.lastIDs.isSuperset(of: screen.scrolledVideoIDs), "scroll focus keeps exactly its page plus focus")
controller.fitEveryoneOnScreen = true; screen.updateVisible(landscape: false)
check(controller.lastIDs.count == 30, "explicit fit-all never invents a thirty-first participant")
var card = Card()
check(card.outgoingStatus == "Room invitation", "sent card has a single compact caption")
card.expired = true
check(card.outgoingStatus == "Invitation expired", "expired invitation explains its disabled share")
card.message.status = .pending
check(card.outgoingStatus == "Waiting to send", "pending card remains distinguishable")
card.message.status = .failed
check(card.outgoingStatus == "Could not send", "failed card remains distinguishable")
print("PASS: \(checks) actual UI video-demand and outgoing-card status contracts; no native renderer/media claim")
'''
harness = harness.replace("__DEMAND__", member("ios/UNYRI/Conferences/ConferenceDirectoryModels.swift", "static func videoDemand"))
harness = harness.replace("__GRID__", member("ios/UNYRI/Conferences/ConferenceInvite.swift", "enum ConferenceGridPolicy"))
harness = harness.replace("__UPDATE__", member("ios/UNYRI/Conferences/ConferenceRoomView.swift", "private func updateVisible"))
harness = harness.replace("__STATUS__", member("ios/UNYRI/Conferences/ConferenceMessageViews.swift", "private var outgoingStatus"))
with tempfile.TemporaryDirectory(prefix="coco-video-continuity-") as directory:
    folder = Path(directory)
    entry = folder / "main.swift"
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    entry.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", str(entry), "-o", str(binary)], check=True, timeout=90)
    subprocess.run([str(binary)], check=True, timeout=20)
