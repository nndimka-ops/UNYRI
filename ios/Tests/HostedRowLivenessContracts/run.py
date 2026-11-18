"""Hosted scene/visibility/cache-key invariants plus shared attachment-image recovery.

The old per-thumbnail retry tracker has been replaced by the shared visible-consumer
source. Keep the original scene/recycling/key regressions here and execute the new
quality, finite retry and file-revision contracts as part of this same gate.
"""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--swiftc", default="swiftc")
parser.add_argument("--source-only", action="store_true", help="Skip the executable Swift retry contracts")
args = parser.parse_args()

root = Path(__file__).resolve().parents[3]


def read(rel):
    return (root / rel).read_text(encoding="utf-8").replace("\r\n", "\n")


def section(text, start, end, name):
    try:
        a = text.index(start)
        b = text.index(end, a + len(start))
    except ValueError:
        sys.exit(f"FAIL: marker missing for {name}: {start!r} .. {end!r}")
    return text[a:b]


count = 0


def check(ok, message):
    global count
    if not ok:
        sys.exit("FAIL: " + message)
    count += 1


host = read("ios/UNYRI/UI/Chats/Timeline/ChatTimelineHost.swift")
chat = read("ios/UNYRI/UI/Chats/ChatScreen.swift")
viewer = read("ios/UNYRI/UI/Chats/UnyriMediaViewer.swift")
notes = read("ios/UNYRI/UI/Chats/VideoNoteViews.swift")

# 1. Live scene phase inside the configure-time environment copy.
cell_root = section(host, "private struct ChatTimelineCellRoot", "// MARK: - Collection view", "cell root")
live = cell_root.find(".modifier(ChatHostedLivePhase(scene: scene))")
snapshot = cell_root.find(r".environment(\.self, environment)")
check(live >= 0, "the cell root applies the live scene phase")
check(snapshot >= 0, "the cell root still applies the configure-time environment")
check(live < snapshot, "the live phase sits inside the environment copy (applied before it), so it wins")
scene_class = section(host, "final class ChatHostedScene", "struct ChatHostedLivePhase", "scene class")
check("DispatchQueue.main.async" in scene_class, "the phase is published on the next turn, never inside a SwiftUI update")
check("guard next != (scheduled ?? phase)" in scene_class, "an unchanged phase publishes nothing")
check("Timer" not in scene_class and "asyncAfter" not in scene_class, "no timer or delay: it follows real phase changes only")
follow = section(scene_class, "func follow(_ next: ScenePhase) {", "scheduled = next", "follow")
follow_code = "\n".join(line for line in follow.split("\n") if not line.strip().startswith("//"))
inactive_at = follow_code.find("guard next != .inactive else { return }")
unchanged_at = follow_code.find("guard next != (scheduled ?? phase)")
check(0 <= inactive_at < unchanged_at,
      "a transient .inactive never reaches rows: running downloads and a playing video note go on")
state_guards = ["if next == .background, state == .active { return }",
                "if next == .active, state == .background { return }"]
check(all(g in follow_code for g in state_guards) and "let state = UIApplication.shared.applicationState" in follow_code,
      "a late SwiftUI phase that contradicts the app's real state is ignored (owner report 14 Nov: rows stuck at .background)")
rest = follow_code
for g in state_guards:
    rest = rest.replace(g, "")
check(".background" not in rest and ".active" not in rest.replace(".inactive", ""),
      "otherwise only .inactive is held: .background still stops row work and .active still starts it")
init = section(scene_class, "init(phase: ScenePhase) {", "func follow(", "init")
check("didBecomeActiveNotification" in init and "self?.follow(.active)" in init,
      "UIKit's didBecomeActive drives rows to .active even when SwiftUI does not update the timeline")
check("didEnterBackgroundNotification" in init and "self?.follow(.background)" in init,
      "UIKit's didEnterBackground stops row work even when SwiftUI does not update the timeline")
update = section(host, "func update(_ host: ChatTimelineHost, environment: EnvironmentValues)", "private func attach(", "update")
check("hostedScene.follow(environment.scenePhase)" in update, "every update forwards the scene phase to rows already on screen")
check("scene: hostedScene" in host, "new cells get the same live phase object")
token = section(chat, "private struct HostedEnvironmentToken", "}", "environment token")
check("scenePhase" not in token, "the scene phase is bridged live, never through the token (that would re-host every row)")
readers = sum(t.count(r"@Environment(\.scenePhase)") for t in (chat, viewer, notes))
check(readers > 0, "rows still read the scene phase (otherwise this contract is stale)")

# 2. Media visibility re-syncs from the cell after a cover.
visibility = section(viewer, "struct UnyriMediaVisibility", "struct UnyriMediaViewer", "media visibility")
hosted = section(visibility, "if let hosted {", "} else if #available(iOS 18.0, *)", "hosted branch")
if "changed(false)" in hosted:
    check(".onAppear { changed(hosted.displayed) }" in hosted, "every disappear has a matching re-sync from the cell's truth on appear")

# 3. One key for generated video frames; the bubble prefers the real frame.
images = section(chat, "static func cacheVideoFrame(", "static func cached(path:", "video frame cache")
writer = section(images, "static func cacheVideoFrame(", "/// A generated video frame", "writer")
check("videoFrameKey(path: path, pixelEdge: pixelEdge)" in writer, "the writer stores under the shared key helper")
check("previewKey(\"video-frame:" in images, "the key helper hashes like every other preview key")
literal_lines = [line.strip() for text in (chat, viewer) for line in text.split("\n") if '"video-frame:' in line]
allowed = [r'previewKey("video-frame:\(pixelEdge):" + path, pixelEdge: 768)',
           'if mime.hasPrefix("video/") { return "video-frame:768:" + url.path }']
check(all(any(a in line for a in allowed) for line in literal_lines),
      "no raw video-frame key outside the helper and the poster policy: " + repr(literal_lines))
poster = section(viewer, "enum UnyriMediaPosterPolicy", "private struct UnyriForwardMediaKey", "poster policy")
check("\"video-frame:768:\" + url.path" in poster, "the poster policy key matches the helper's 768 key through cached(path:)")

# Retry/quality ownership moved out of individual video and note renderers.
shared = read("ios/UNYRI/UI/Chats/AttachmentImages.swift")
check("AttachmentImagePolicy.allowsWork" in shared, "all surfaces use the common work gate")
check("effective?.url != lastURL" in shared, "an unrelated download does not restart this attachment")
check("UnyriAttachmentImageBinding(source: attachmentImage" in chat, "timeline/menu/album use the shared observable source")
check("menuPoster: transitionImage" in chat, "video notes consume the same best image as ordinary media")
check("UnyriVideoFrameFailures" not in viewer, "the superseded per-thumbnail failure owner is gone")
check("UnyriAttachmentImages.changes.send(.path(path))" in writer, "frame writes publish upgrades to mounted consumers")
check("self.ticket == expected" in shared, "recycled/cancelled consumers reject late results")
check("flight.consumers.isEmpty" in shared and "flight.task.cancel()" in shared,
      "last-consumer cancellation stops a shared decode/backoff")
print(f"PASS: {count} hosted scene/visibility/cache-key and shared source wiring invariants")
command = [sys.executable, str(root / "scripts/check_ios_attachment_images.py"), "--swiftc", args.swiftc]
if args.source_only:
    command.append("--source-only")
subprocess.run(command, check=True)
