import XCTest
import UIKit
import CoreImage

/// Synthetic fixture pixels, independent of accessibility and UIKit. The menu
/// intentionally hides its duplicated preview from VoiceOver; only rendered
/// pixels in the bounded preview region prove its image/poster is present.
private enum UnyriMenuFixturePixels {
    enum Kind: String { case photo, video, avatar }
    struct Evidence {
        let matches: Bool
        let detail: String
    }
    static func inspect(_ rgba: [UInt8], width: Int, height: Int, kind: Kind) -> Evidence {
        guard width > 0, height > 0, width <= 4096, height <= 4096,
              rgba.count == width * height * 4 else { return Evidence(matches: false, detail: "Invalid RGBA crop") }
        let palettes = [(65,126,190),(195,73,91),(56,149,108),(139,96,195),(202,154,54)]
        var dark = Array(repeating: 0, count: palettes.count)
        var light = dark
        var white = 0, cyan = 0, magenta = 0, total = 0
        for y in stride(from: 0, to: height, by: 2) {
            for x in stride(from: 0, to: width, by: 2) {
                let i = (y * width + x) * 4
                let r = Int(rgba[i]), g = Int(rgba[i+1]), b = Int(rgba[i+2])
                total += 1
                if r > 220 && g > 220 && b > 225 && max(r,g,b)-min(r,g,b) < 25 { white += 1 }
                for (index, color) in palettes.enumerated() {
                    if abs(r-color.0) <= 10 && abs(g-color.1) <= 10 && abs(b-color.2) <= 10 { dark[index] += 1 }
                    if abs(r-color.0-22) <= 10 && abs(g-color.1-22) <= 10 && abs(b-color.2-22) <= 10 { light[index] += 1 }
                }
                if r < 65 && g > 120 && b > 160 && b > g+10 { cyan += 1 }
                if r > 140 && b > 110 && g < 160 && r > g+45 && b > g+20 { magenta += 1 }
            }
        }
        let count = Double(max(1,total))
        let fraction = kind == .avatar ? 0.02 : 0.15
        let stripes = zip(dark,light).contains { Double($0.0)/count > fraction && Double($0.1)/count > fraction }
        let matches = kind == .video ? Double(cyan)/count > 0.20 && Double(magenta)/count > 0.006
            : stripes && (kind == .avatar || Double(white)/count > 0.002)
        return Evidence(matches: matches,
            detail: "\(kind.rawValue) RGBA \(width)x\(height): dark=\(dark), light=\(light), white=\(white), cyan=\(cyan), magenta=\(magenta), samples=\(total)")
    }
}

/// SwiftUI can put an identifier on an Image or a containing accessibility
/// element instead of a Button. Prefer the actual per-tile photo pixels;
/// preserve hierarchy order, and never treat a video poster alias as a UUID
/// control. Viewer page identity remains the final end-to-end assertion.
private enum UnyriChatMediaTestLookup {
    enum Kind { case photo, video }
    struct Candidate {
        let identifier: String
        let label: String
        var width: Double = 0
        var height: Double = 0
        var area: Double { max(0, width) * max(0, height) }
    }
    static func indices(_ candidates: [Candidate], kind: Kind) -> [Int] {
        func priority(_ candidate: Candidate) -> Int? {
            switch kind {
            case .photo:
                if candidate.identifier == "chat-photo-preview" { return 0 }
                if candidate.identifier == "chat-album-photo" { return 1 }
                return candidate.label == "Photo" ? 2 : nil
            case .video:
                if candidate.identifier.hasPrefix("chat-video-") && candidate.identifier != "chat-video-preview" { return 0 }
                if candidate.identifier == "chat-video-preview" { return 1 }
                return candidate.label == "Open" ? 2 : nil
            }
        }
        let ranked = candidates.enumerated().compactMap { index, candidate in
            priority(candidate).map { (index, $0) }
        }
        guard let best = ranked.map(\.1).min() else { return [] }
        let matches = ranked.filter { $0.1 == best }.map(\.0)
        guard kind == .video, best == 0 else { return matches }
        // The native tree repeats a UUID on duration/play glyphs and the full
        // video surface. Keep the largest actual surface for each UUID while
        // retaining source attachment order, rather than picking a tiny glyph.
        var positions: [String: Int] = [:]
        var surfaces: [Int] = []
        for index in matches {
            let id = candidates[index].identifier
            if let position = positions[id] {
                if candidates[index].area > candidates[surfaces[position]].area { surfaces[position] = index }
            } else { positions[id] = surfaces.count; surfaces.append(index) }
        }
        return surfaces
    }
}

/// Geometry assertions use native screen coordinates. They cannot establish
/// frame rate; they detect covered controls and a second scroll after settling.
private enum UnyriKeyboardTestGeometry {
    enum KeyboardExposure: String { case hidden, shown, partial, unknown }
    static func valid(_ rect: CGRect) -> Bool {
        rect.minX.isFinite && rect.minY.isFinite && rect.width.isFinite && rect.height.isFinite && rect.width > 0 && rect.height > 0
    }
    static func issues(app: CGRect, keyboard: CGRect?, controls: [CGRect]) -> [String] {
        guard valid(app), controls.count == 3, controls.allSatisfy(valid) else { return ["Missing/invalid native composer geometry"] }
        var issues: [String] = []
        for (index, rect) in controls.enumerated() {
            if !app.insetBy(dx: -2, dy: -2).contains(rect) { issues.append("Control \(index) is outside the app") }
            if let keyboard, valid(keyboard), rect.maxY > keyboard.minY + 2 { issues.append("Control \(index) is covered by the keyboard") }
        }
        if let keyboard {
            if !valid(keyboard) || keyboard.width < app.width * 0.85 { issues.append("Expected the real docked iPhone keyboard") }
        } else if let bottom = controls.map(\.maxY).max(), app.maxY - bottom > 132 {
            issues.append("Composer remained at the old keyboard height after dismissal")
        }
        return issues
    }
    static func anchorDelta(before: CGRect, after: CGRect, beforeViewport: CGRect, afterViewport: CGRect) -> CGFloat? {
        guard valid(before), valid(after), valid(beforeViewport), valid(afterViewport) else { return nil }
        return (after.minY - afterViewport.minY) - (before.minY - beforeViewport.minY)
    }
    static func sameLayout(_ before: [CGRect], _ after: [CGRect], tolerance: CGFloat = 1) -> Bool {
        guard before.count == after.count, !before.isEmpty else { return false }
        return zip(before, after).allSatisfy { first, second in
            let values = [first.minX, first.minY, first.width, first.height, second.minX, second.minY, second.width, second.height]
            return values.allSatisfy(\.isFinite) && abs(first.minX-second.minX) <= tolerance &&
                abs(first.minY-second.minY) <= tolerance && abs(first.width-second.width) <= tolerance && abs(first.height-second.height) <= tolerance
        }
    }
    static func keyboardExposure(app: CGRect, keyplane: CGRect?, accessories: [CGRect]) -> KeyboardExposure {
        keyboardExposure(app: app, keyplanes: keyplane.map { [$0] } ?? [], accessories: accessories)
    }
    static func keyboardExposure(app: CGRect, keyplanes: [CGRect], accessories: [CGRect]) -> KeyboardExposure {
        guard valid(app) else { return .unknown }
        let spans = keyplanes + accessories
        guard spans.allSatisfy({ frame in
            [frame.minX, frame.minY, frame.width, frame.height].allSatisfy(\.isFinite) && frame.width >= 0 && frame.height >= 0
        }) else { return .unknown }
        let visible = spans.filter {
            let clipped = $0.intersection(app)
            return !clipped.isNull && clipped.width > 1 && clipped.height > 1
        }
        if visible.isEmpty { return .hidden }
        guard keyplanes.contains(where: { valid($0) && app.insetBy(dx: -1, dy: -1).contains($0) }),
              visible.allSatisfy({ app.insetBy(dx: -1, dy: -1).contains($0) }) else { return .partial }
        return .shown
    }
    static func visibleKeyboardBounds(app: CGRect, keyplane: CGRect?, accessories: [CGRect]) -> CGRect? {
        visibleKeyboardBounds(app: app, keyplanes: keyplane.map { [$0] } ?? [], accessories: accessories)
    }
    static func visibleKeyboardBounds(app: CGRect, keyplanes: [CGRect], accessories: [CGRect]) -> CGRect? {
        let frames = keyplanes + accessories
        let visible = frames.filter(valid).map { $0.intersection(app) }.filter { !$0.isNull && $0.width > 1 && $0.height > 1 }
        guard let first = visible.first else { return nil }
        return visible.dropFirst().reduce(first) { $0.union($1) }
    }
    static func closedBottomGapShift(referenceSize: CGSize, referenceGap: CGFloat, app: CGRect, controls: [CGRect]) -> CGFloat? {
        guard valid(app), referenceGap.isFinite, abs(referenceSize.width-app.width) <= 1,
              abs(referenceSize.height-app.height) <= 1, controls.count == 3, controls.allSatisfy(valid),
              let bottom = controls.map(\.maxY).max() else { return nil }
        return app.maxY - bottom - referenceGap
    }
    static func upperFullyVisibleMessageIndex(_ frames: [CGRect], viewport: CGRect) -> Int? {
        guard valid(viewport), viewport.width > 4, viewport.height > 4 else { return nil }
        let visible = viewport.insetBy(dx: 2, dy: 2)
        return frames.indices.filter { valid(frames[$0]) && visible.contains(frames[$0]) }
            .min { frames[$0].minY < frames[$1].minY }
    }
}

private enum UnyriChatTestRevealGeometry {
    struct Drag { let start: CGPoint; let end: CGPoint }
    static func drag(target: CGRect?, viewport: CGRect) -> Drag? {
        guard viewport.width.isFinite, viewport.height.isFinite,
              viewport.width > 32, viewport.height > 40 else { return nil }
        let range = viewport.insetBy(dx: 4, dy: 4)
        var delta = min(100, viewport.height * 0.25)
        var x = range.midX, y = range.midY
        if let target {
            guard target.minX.isFinite, target.minY.isFinite, target.width.isFinite, target.height.isFinite,
                  target.width > 0, target.height > 0, target.height <= viewport.height else { return nil }
            if target.minY < viewport.minY { delta = viewport.minY - target.minY + 2 }
            else if target.maxY > viewport.maxY { delta = viewport.maxY - target.maxY - 2 }
            else { return nil }
            delta = delta > 0 ? min(100, max(12, delta)) : max(-100, min(-12, delta))
            let minimumFit = viewport.minY-target.minY, maximumFit = viewport.maxY-target.maxY
            if minimumFit <= 100 && maximumFit >= -100 { delta = min(maximumFit, max(minimumFit, delta)) }
            let visible = target.intersection(range)
            x = min(range.maxX, max(range.minX, target.midX))
            if !visible.isNull, visible.width > 0, visible.height > 0 { x = visible.midX; y = visible.midY }
        }
        let endY = min(range.maxY, max(range.minY, y + delta))
        // A sub-slop motion could become an accidental media tap instead of a
        // pan. Fail with the geometry/tree rather than opening another screen.
        guard abs(endY-y) >= 8 else { return nil }
        return Drag(start: CGPoint(x: x, y: y), end: CGPoint(x: x, y: endY))
    }
}

private enum UnyriRotatedAttachmentActionPolicy {
    static func permits(stage: String, shown: Bool, hidden: Bool, geometryValid: Bool,
        gapMatches: Bool, hits: [Bool], attachCount: Int, enabled: Bool,
        captionMatches: Bool, selectionMatches: Bool) -> Bool {
        stage == "rotated-keyboard-hidden" && !shown && hidden && geometryValid && gapMatches &&
        hits == [true, false, true] && attachCount == 1 && enabled && captionMatches && selectionMatches
    }
}

/// An opt-in cumulative scenario against the ephemeral CI loopback server.
/// Requires a fresh simulator with ten synthetic photos and Photos permission.
/// Authentication and attachment preparation use the installed Release app UI.
@MainActor
final class UNYRIAuthenticatedUITests: XCTestCase {
    private var app: XCUIApplication!
    private var closedKeyboardBaseline: (size: CGSize, gap: CGFloat)?
    private let loopbackServer = "http://127.0.0.1:8080"
    private let serverPlaceholder = "https://server.example or http://192.168.1.10:8080"

    override func setUpWithError() throws {
        if name.contains("testCrashReportOfferAndConsentWithSyntheticArchive") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_CRASH_UI_ENABLED"] == "1",
                              "Test-only archive fixture; never an OS MetricKit delivery claim")
        }
        if name.contains("testHEVCGalleryVideoSendsAndPlays") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_HEVC_ENABLED"] == "1",
                              "Requires the dedicated verified HEVC-only synthetic video fixture")
        }
        if name.contains("testChatNativeBackSwipePreservesDraft") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_BACK_SWIPE_ENABLED"] == "1",
                              "Opt-in native navigation-pop gesture and draft audit")
        }
        if name.contains("testSidebarAvatarLoadsAfterColdLaunch") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_AVATAR_ENABLED"] == "1",
                              "Opt-in real avatar upload and cold drawer pixel audit")
        }
        if name.contains("testRoomCreateMinimizeLeaveAndRejoin") || name.contains("testRoomBusyCallExplainsWithoutAbandoningRoom") || name.contains("testProtectedOwnerRoomWrongPasswordThenRejoin") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_ROOMS_ENABLED"] == "1",
                              "Opt-in real encrypted room admission and re-entry audit")
        }
        if name.contains("testQuickReactionRowsRespondToSingleTapAndPersist") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_QUICK_REACTION_ENABLED"] == "1",
                              "Independent opt-in native quick-reaction tap and persistence audit")
        }
        if name.contains("testDrawerSwipesRespectNavigationAndScrolling") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_DRAWER_ENABLED"] == "1",
                              "Independent opt-in native drawer gesture audit")
        }
        if name.contains("testAuthenticatedMenusAndSettingsRemainUsable") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_ALL_SCREENS_ENABLED"] == "1",
                              "Independent opt-in authenticated menu audit")
        }
        if name.contains("testBlockedAccountMutationsUseOwnedPeer") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_ALL_SCREENS_ENABLED"] == "1",
                              "Registers and deletes one disposable peer; never uses an existing account")
        }
        if name.contains("testComposerReplacementsPreserveExactCaption") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_CAPTION_ENABLED"] == "1",
                              "Independent native composer selection gate")
        }
        if name.contains("testRotatedComposerAttachmentAction") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_ROTATED_ATTACHMENT_ENABLED"] == "1",
                              "Independent native rotated-composer functional probe")
        }
        if name.contains("testKeyboardComposerBoundsAndScrollAnchors") || name.contains("testFreshRegistrationOpensSavedWithOneTap") {
            try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_KEYBOARD_ENABLED"] == "1",
                              "Dedicated opt-in keyboard gate; not the 16-message media scenario")
        }
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_BACKEND_ENABLED"] == "1",
                          "Requires --backend-qa and the isolated CI backend; never use a private account")
        continueAfterFailure = false
        closedKeyboardBaseline = nil
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication(bundleIdentifier: "com.messenger.unyri.X7G7M9PFJ3")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if name.contains("testChatListMonochromeMenuAndBulkActions") && ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_FRESH_HEADER"] == "1" {
            app.launchEnvironment["UNYRI_QA_SAVED_ROUTE_TRACE"] = "1"
        }
        if name.contains("testChatListMonochromeMenuAndBulkActions") && ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_RESUME"] == "1" {
            app.launchEnvironment["UNYRI_QA_SAVED_ROUTE_TRACE"] = "1"
            app.launch()
            XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 15), "Resume only the already authenticated owned chat-list fixture")
            return
        }
        if name.contains("testFreshRegistrationOpensSavedWithOneTap") {
            app.launchEnvironment["UNYRI_QA_SAVED_ROUTE_TRACE"] = "1"
        }
        app.launch()
        XCTAssertTrue(app.textFields["Your @handle"].waitForExistence(timeout: 15),
                      "A fresh simulator must start at real COCO login")
    }

    override func tearDownWithError() throws {
        if let app {
            capture("authenticated-final-state")
            if name.contains("testKeyboardComposerBoundsAndScrollAnchors") { captureAccessibilityTree("keyboard-final-state-tree") }
            if name.contains("testFreshRegistrationOpensSavedWithOneTap") { captureAccessibilityTree("first-saved-final-state-tree") }
            if name.contains("testAuthenticatedMenusAndSettingsRemainUsable") { captureAccessibilityTree("screen-audit-final-state-tree") }
            if name.contains("testComposerReplacementsPreserveExactCaption") { captureAccessibilityTree("caption-final-state-tree") }
            if name.contains("testRotatedComposerAttachmentAction") { captureAccessibilityTree("rotated-attachment-final-state-tree") }
            if name.contains("testQuickReactionRowsRespondToSingleTapAndPersist") { captureAccessibilityTree("quick-reaction-final-state-tree") }
            app.terminate()
        }
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func captureAccessibilityTree(_ name: String) {
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name; tree.lifetime = .keepAlways; add(tree)
    }

    private func chatMediaElements(_ kind: UnyriChatMediaTestLookup.Kind) -> [XCUIElement] {
        let predicate: NSPredicate
        switch kind {
        case .photo:
            predicate = NSPredicate(format: "identifier IN %@ OR label == %@", ["chat-photo-preview", "chat-album-photo"], "Photo")
        case .video:
            predicate = NSPredicate(format: "identifier BEGINSWITH %@ OR label == %@", "chat-video-", "Open")
        }
        let elements = app.descendants(matching: .any).matching(predicate).allElementsBoundByIndex
        let candidates = elements.map {
            UnyriChatMediaTestLookup.Candidate(identifier: $0.identifier, label: $0.label,
                width: Double($0.frame.width), height: Double($0.frame.height))
        }
        return UnyriChatMediaTestLookup.indices(candidates, kind: kind).map { elements[$0] }
    }

    private func assertMenuFixturePreview(_ kind: UnyriMenuFixturePixels.Kind, file: StaticString = #filePath, line: UInt = #line) {
        let reply = app.buttons["Reply"]
        XCTAssertTrue(reply.exists, "The message menu must be present", file: file, line: line)
        let frame = app.frame
        let screenshot = app.screenshot()
        guard let image = screenshot.image.cgImage, frame.width > 0, frame.height > 0 else {
            XCTFail("The native menu screenshot must contain actual pixels", file: file, line: line); return
        }
        // Actual menu layout: trailing preview, 8pt gap, then 4pt menu inset.
        // The 220pt crop includes the complete 4:3 photo or bounded portrait
        // poster, and excludes all menu icons/text and the composer below.
        let width = min(CGFloat(220), reply.frame.width)
        let points = CGRect(x: reply.frame.maxX-width, y: reply.frame.minY-12-220, width: width, height: 220)
        let sx = CGFloat(image.width)/frame.width, sy = CGFloat(image.height)/frame.height
        let pixels = CGRect(x: (points.minX-frame.minX)*sx, y: (points.minY-frame.minY)*sy,
                            width: points.width*sx, height: points.height*sy).integral
            .intersection(CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height)))
        guard !pixels.isNull, pixels.width >= 60, pixels.height >= 60, let crop = image.cropping(to: pixels),
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else {
            XCTFail("The actual menu preview region must be on screen: \(pixels)", file: file, line: line); return
        }
        var rgba = [UInt8](repeating: 0, count: crop.width * crop.height * 4)
        let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: crop.width, height: crop.height,
                bitsPerComponent: 8, bytesPerRow: crop.width * 4, space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(crop, in: CGRect(x: 0, y: 0, width: CGFloat(crop.width), height: CGFloat(crop.height)))
            return true
        }
        let evidence = UnyriMenuFixturePixels.inspect(rgba, width: crop.width, height: crop.height, kind: kind)
        if !drawn || !evidence.matches {
            let attachment = XCTAttachment(image: UIImage(cgImage: crop))
            attachment.name = "missing-\(kind.rawValue)-menu-preview-pixel-crop"; attachment.lifetime = .keepAlways; add(attachment)
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "missing-\(kind.rawValue)-menu-preview-tree"; tree.lifetime = .keepAlways; add(tree)
        }
        XCTAssertTrue(drawn && evidence.matches,
            "The actual menu must retain decoded fixture pixels, not a placeholder or blurred backdrop. \(evidence.detail); crop=\(pixels)", file: file, line: line)
    }

    private func scrollVisible(up: Bool, inside container: XCUIElement? = nil) {
        var bounds = (container?.frame ?? app.frame).intersection(app.frame)
        let appearanceControls = container?.identifier == "chat-appearance-controls"
        if appearanceControls {
            // SwiftUI reports the ScrollView frame beneath safeAreaInset as
            // well. Begin a drag above the fixed footer, inside visible content.
            let footer = [app.buttons["chat-appearance-apply"], app.buttons["chat-appearance-cancel"]]
                .filter(\.exists).map { $0.frame.minY }.min()
            if let footer { bounds.size.height = max(0, min(bounds.maxY, footer - 12) - bounds.minY) }
        }
        let keyboard = app.keyboards.firstMatch
        let bottom = keyboard.exists ? min(bounds.maxY - 32, keyboard.frame.minY - 24) : bounds.maxY - 64
        let navigation = app.navigationBars.firstMatch
        let top = max(bounds.minY + 30, navigation.exists ? navigation.frame.maxY + 16 : app.frame.minY + 90)
        guard bottom > top + 40 else { return }
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Registration's padded ScrollView does not receive gestures on the
        // outer wallpaper margin. Keep the drag inside visible content.
        // The appearance form contains native horizontal sliders. Use its
        // content gutter so a vertical drag cannot accidentally adjust a slider.
        let x = (appearanceControls ? bounds.minX + 8 : bounds.midX) - app.frame.minX
        let upper = origin.withOffset(CGVector(dx: x, dy: top - app.frame.minY))
        let lower = origin.withOffset(CGVector(dx: x, dy: bottom - app.frame.minY))
        (up ? lower : upper).press(forDuration: 0.01, thenDragTo: up ? upper : lower)
    }

    private func reveal(_ element: XCUIElement) {
        for direction in [true, false] {
            for _ in 0..<6 {
                if element.exists && element.isHittable { return }
                scrollVisible(up: direction)
            }
        }
        capture("authenticated-unreachable-control")
        XCTAssertTrue(element.exists && element.isHittable, "Control must be reachable by scrolling")
    }

    private func revealChatContent(_ element: XCUIElement) {
        let attach = app.buttons["Attach photo or file"]
        let timeline = app.scrollViews["chat-timeline"]
        XCTAssertTrue(timeline.exists)
        for _ in 0..<20 {
            let top = max(timeline.frame.minY + 12, app.frame.minY + 140)
            let bottom = attach.exists ? min(timeline.frame.maxY - 12, attach.frame.minY - 12) : timeline.frame.maxY - 24
            if element.exists {
                let frame = element.frame
                if element.isHittable && frame.minY >= top && frame.maxY <= bottom { return }
            }
            // Correct only the measured overflow. A fixed 99pt fast drag
            // overshot a video needing 38pt and oscillated past both limits.
            let viewport = CGRect(x: timeline.frame.minX, y: top, width: timeline.frame.width, height: bottom-top)
            let frame = element.exists ? element.frame : nil
            guard let drag = UnyriChatTestRevealGeometry.drag(target: frame, viewport: viewport) else { break }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: drag.start.x-app.frame.minX, dy: drag.start.y-app.frame.minY))
            let end = origin.withOffset(CGVector(dx: drag.end.x-app.frame.minX, dy: drag.end.y-app.frame.minY))
            start.press(forDuration: 0.05, thenDragTo: end,
                        withVelocity: XCUIGestureVelocity(rawValue: 100), thenHoldForDuration: 0.3)
        }
        capture("authenticated-chat-content-not-visible")
        captureAccessibilityTree("authenticated-chat-content-not-visible-tree")
        XCTFail("Chat element must be inside the unobscured viewport, not behind the transparent header")
    }

    private func replace(_ field: XCUIElement, with text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        let keyboard = app.keyboards.firstMatch
        let bounds = field.frame
        let secureVisible = field.elementType == .secureTextField && field.isEnabled &&
            !bounds.isEmpty && app.frame.contains(bounds) &&
            (!keyboard.exists || bounds.maxY < keyboard.frame.minY)
        if !field.isHittable && secureVisible {
            // Secure-input redaction may omit AX's visible point. Require
            // current on-screen bounds, then perform actual native focus/type.
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        } else {
            reveal(field)
            field.tap()
        }
        let current = (field.value as? String) ?? ""
        if current != field.placeholderValue && !current.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(text)
    }

    private func multilineField(_ label: String) -> XCUIElement {
        let textView = app.textViews[label]
        return textView.exists ? textView : app.textFields[label]
    }

    private func replaceComposerWithSelection(_ text: String, stage: String) {
        let field = multilineField("chat-composer-text")
        XCTAssertTrue(field.waitForExistence(timeout: 5)); reveal(field); field.tap()
        func record(_ phase: String) {
            let value = self.multilineField("chat-composer-text").value as? String ?? ""
            let evidence = ["stage": stage, "phase": phase, "actualSyntheticValue": value, "expectedSyntheticValue": text]
            let data = try! JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys])
            let item = XCTAttachment(string: String(decoding: data, as: UTF8.self))
            item.name = "composer-replacement-\(stage)-\(phase).json"; item.lifetime = .keepAlways; add(item)
            print("UNYRI_COMPOSER_REPLACEMENT " + String(decoding: data, as: UTF8.self))
        }
        keyboardWait(stage + "-focused-keyboard", timeout: 8) {
            self.observeNativeKeyboard().exposure == .shown && !self.app.scrollViews["gallery-grid"].exists
        }
        record("before-selection")
        let current = field.value as? String ?? ""
        if !current.isEmpty && current != field.placeholderValue {
            // Fixture preparation uses Apple's public iOS XCTest keyboard API.
            // Touching the random suffix can open native spelling replacements;
            // that menu is unrelated to the keyboard/scroll geometry under test.
            // These are real Select All / Cut edit commands, never draft-state
            // injection or repeated deletes. One Cut must really empty the field.
            field.typeKey("a", modifierFlags: .command)
            record("native-select-all-keyboard")
            field.typeKey("x", modifierFlags: .command)
            record("after-native-cut")
            keyboardWait(stage + "-selection-cleared", timeout: 5) {
                let fresh = self.multilineField("chat-composer-text")
                let value = fresh.value as? String ?? ""
                return value.isEmpty || value == fresh.placeholderValue
            }
            let fresh = multilineField("chat-composer-text")
            let cleared = fresh.value as? String ?? ""
            XCTAssertTrue(cleared.isEmpty || cleared == fresh.placeholderValue,
                          "Native selection and one Cut must empty the composer before replacement")
            guard cleared.isEmpty || cleared == fresh.placeholderValue else { return }
            record("empty-confirmed")
        }
        field.typeText(text)
        keyboardWait(stage + "-exact-value", timeout: 5) {
            (self.multilineField("chat-composer-text").value as? String) == text
        }
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, text,
                       "Fixture replacement must be exact BEFORE keyboard, media or rotation assertions")
        record("exact-replacement")
    }

    private func playbackPercent(_ slider: XCUIElement) -> Double {
        // Auto-hidden transport is absent from AX. Callers must reveal/pause
        // before sampling; a missing control must not throw a snapshot error.
        guard slider.exists else { return -1 }
        let raw = (slider.value as? String ?? "").filter { $0.isNumber || $0 == "." || $0 == "," }
        return Double(raw.replacingOccurrences(of: ",", with: ".")) ?? -1
    }
    private func pauseIncomingVideo() {
        // Native AX lookups can outlive the control auto-hide interval. Start
        // a fresh visible interval and use a captured screen coordinate so a
        // later tap cannot fail while resolving a now-hidden button again.
        let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        func toggleControlsAndWaitForTapRecognition() {
            center.tap()
            // Single tap waits for the double-tap recognizer to fail. XCTest
            // can return before that gesture changes SwiftUI visibility.
            Thread.sleep(forTimeInterval: 0.75)
        }
        for _ in 0..<3 {
            if app.buttons["Play"].exists { break }
            toggleControlsAndWaitForTapRecognition()
            if !app.buttons["Pause"].exists && !app.buttons["Play"].exists { toggleControlsAndWaitForTapRecognition() }
            let pause = app.buttons["Pause"]
            if pause.exists {
                let frame = pause.frame
                let viewport = app.frame
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                    dx: frame.midX - viewport.minX, dy: frame.midY - viewport.minY)).tap()
            }
            if app.buttons["Play"].waitForExistence(timeout: 1) { break }
        }
        XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["media-page-counter"].waitForExistence(timeout: 5))
    }

    private func submitKeyboard(_ label: String) {
        // iOS 26 exposes the native return key as identifier "Next:" and
        // lowercase label "next", rather than a button named "Next".
        let key = app.keyboards.buttons.matching(NSPredicate(format: "label ==[c] %@", label)).firstMatch
        XCTAssertTrue(key.waitForExistence(timeout: 5))
        key.tap()
    }

    private func waitUntil(_ description: String, timeout: TimeInterval = 90, _ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed, description)
    }

    private func terminalStatusVisible() -> Bool {
        ["Sent", "Delivered", "Read"].contains {
            app.staticTexts.matching(identifier: $0).firstMatch.exists || app.images.matching(identifier: $0).firstMatch.exists
        }
    }

    private var galleryVisibleBounds: CGRect {
        let grid = app.scrollViews["gallery-grid"]
        var bounds = grid.frame.intersection(app.frame)
        let footer = app.otherElements["gallery-footer"]
        if footer.exists { bounds.size.height = max(0, min(bounds.maxY, footer.frame.minY) - bounds.minY) }
        return bounds
    }

    private func selectGalleryAsset(_ identifier: String, expectedCount: Int) {
        let grid = app.scrollViews["gallery-grid"]
        XCTAssertTrue(grid.waitForExistence(timeout: 10))
        for _ in 0..<14 {
            let bounds = galleryVisibleBounds
            let candidates = app.buttons.matching(identifier: identifier)
                .matching(NSPredicate(format: "label == %@", "Select")).allElementsBoundByIndex
            if let next = candidates.first(where: { $0.isHittable && bounds.insetBy(dx: -1, dy: -1).contains($0.frame) }) {
                XCTAssertEqual(next.elementType, .button, "The accessible selection control must retain its button role")
                // On this portrait iPhone, the gallery fills the logical sheet
                // width. iOS26's floating medium sheet transforms440pt content
                // into424pt on screen; AX frames include that UIKit transform.
                // Compare local points without mistaking44*424/440=42.4 for a
                // smaller SwiftUI hit target. Large detent has scale1 instead.
                let sheetScale = grid.frame.width / app.frame.width
                let localWidth = next.frame.width / sheetScale
                let localHeight = next.frame.height / sheetScale
                if !sheetScale.isFinite || sheetScale <= 0.9 || sheetScale > 1.01 ||
                   abs(localWidth - 44) > 1 || abs(localHeight - 44) > 1 {
                    capture("gallery-hit-geometry-mismatch")
                    let geometry = XCTAttachment(string: "app=\(app.frame) grid=\(grid.frame) control=\(next.frame) scale=\(sheetScale)\n\(app.debugDescription)")
                    geometry.name = "gallery-hit-geometry-accessibility-tree"; geometry.lifetime = .keepAlways; add(geometry)
                }
                XCTAssertGreaterThan(sheetScale, 0.9, "The phone gallery must occupy its expected native sheet width")
                XCTAssertLessThanOrEqual(sheetScale, 1.01)
                XCTAssertEqual(localWidth, 44, accuracy: 1, "Selection hit target must stay44 local points wide")
                XCTAssertEqual(localHeight, 44, accuracy: 1, "Selection hit target must stay44 local points tall")
                let thumbnails = app.buttons.matching(NSPredicate(format: "label IN %@", ["Photo", "Video"]))
                    .allElementsBoundByIndex.filter { $0.isHittable && bounds.intersects($0.frame) }
                XCTAssertFalse(thumbnails.isEmpty, "Visible fixtures must expose thumbnail buttons")
                for thumbnail in thumbnails {
                    XCTAssertEqual(thumbnail.frame.width, thumbnail.frame.height, accuracy: 1,
                                   "Photo/video artwork must not expand its square tile or overlap nearby selection controls")
                }
                // Target the visible44pt circle, not an AX activation point
                // that may lie on the underlying full-size preview button.
                next.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                let preview = app.descendants(matching: .any).matching(identifier: "gallery-preview").firstMatch
                XCTAssertFalse(preview.exists, "A selection-circle tap must select rather than open a preview")
                let send = app.buttons["gallery-send"]
                waitUntil("Each tap must increase the retained selection", timeout: 5) {
                    send.exists && send.label.contains(String(expectedCount))
                }
                return
            }
            guard bounds.height > 60 else { break }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let lower = origin.withOffset(CGVector(dx: bounds.midX - app.frame.minX, dy: bounds.maxY - app.frame.minY - 16))
            let upper = origin.withOffset(CGVector(dx: bounds.midX - app.frame.minX, dy: bounds.minY - app.frame.minY + 16))
            lower.press(forDuration: 0.01, thenDragTo: upper)
        }
        capture("authenticated-gallery-selection-unreachable-\(expectedCount)")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "gallery-selection-accessibility-tree"; tree.lifetime = .keepAlways; add(tree)
        XCTFail("All synthetic fixtures must have reachable selection controls inside the gallery viewport")
    }

    /// Exercises the real persisted editor, not an alternate test-only screen.
    private func verifyAppearanceRoundTrip() {
        let category = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Appearance")).firstMatch
        reveal(category); category.tap()
        let open = app.buttons["chat-appearance-open"]
        func enter() {
            reveal(open); open.tap()
            let available = app.buttons["chat-appearance-apply"].waitForExistence(timeout: 10)
            if !available {
                capture("appearance-editor-action-missing")
                let tree = XCTAttachment(string: app.debugDescription)
                tree.name = "appearance-editor-accessibility-tree"; tree.lifetime = .keepAlways; add(tree)
            }
            XCTAssertTrue(available, "Real Apply button must be exposed in the accessibility tree")
        }
        func choice(_ identifier: String) -> XCUIElement {
            let item = app.buttons[identifier]
            let controls = app.descendants(matching: .any).matching(identifier: "chat-appearance-controls").firstMatch
            func fullyVisible() -> Bool {
                guard controls.exists, item.exists, item.isHittable else { return false }
                var bounds = controls.frame.intersection(app.frame)
                let footerTop = [app.buttons["chat-appearance-apply"], app.buttons["chat-appearance-cancel"]]
                    .filter(\.exists).map { $0.frame.minY }.min()
                if let footerTop {
                    bounds.size.height = max(0, min(bounds.maxY, footerTop - 12) - bounds.minY)
                }
                // A partially clipped SwiftUI row can be AX-hittable even when
                // its activation point lies under the fixed Cancel/Apply bar.
                // Require the complete option to be in the unobscured form.
                return bounds.insetBy(dx: -1, dy: -1).contains(item.frame)
            }
            for direction in [true, false] {
                for _ in 0..<8 {
                    if fullyVisible() { return item }
                    scrollVisible(up: direction, inside: controls.exists ? controls : nil)
                }
            }
            capture("appearance-option-unreachable")
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "appearance-option-accessibility-tree"; tree.lifetime = .keepAlways; add(tree)
            XCTAssertTrue(fullyVisible(), "Appearance option must remain fully reachable above the fixed footer")
            return item
        }
        func selected(_ identifier: String) {
            let item = choice(identifier)
            XCTAssertTrue(item.isSelected || (item.value as? String) == "Selected", "Saved option must be selected: \(identifier)")
        }
        enter()
        selected("chat-appearance-font-system")
        choice("chat-appearance-font-serif").tap()
        selected("chat-appearance-font-serif")
        capture("appearance-draft-serif")
        app.buttons["chat-appearance-cancel"].tap()
        enter()
        selected("chat-appearance-font-system")
        choice("chat-appearance-font-serif").tap()
        choice("chat-appearance-color-blue").tap()
        selected("chat-appearance-color-blue")
        capture("appearance-draft-blue-serif")
        app.buttons["chat-appearance-apply"].tap()
        enter()
        selected("chat-appearance-font-serif")
        selected("chat-appearance-color-blue")
        capture("appearance-applied-reopened")
        choice("chat-appearance-reset").tap()
        selected("chat-appearance-font-system")
        app.buttons["chat-appearance-cancel"].tap()
        enter()
        selected("chat-appearance-font-serif")
        choice("chat-appearance-reset").tap()
        selected("chat-appearance-font-system")
        app.buttons["chat-appearance-apply"].tap()
        enter()
        selected("chat-appearance-font-system")
        selected("chat-appearance-color-default")
        capture("appearance-approved-default-restored")
        let attachmentTab = app.buttons["chat-appearance-preview-attachments"]
        if attachmentTab.exists {
            attachmentTab.tap(); capture("appearance-default-attachments")
            app.buttons["chat-appearance-preview-messages"].tap()
        }
        app.buttons["chat-appearance-cancel"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    private func verifyRussianAccessibilityFooter() {
        let normalArguments = app.launchArguments
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU", "-coco.language", "ru",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); menu.tap()
        let settings = app.buttons["drawer-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10)); settings.tap()
        let category = app.buttons["settings-appearance"]
        reveal(category); category.tap()
        let open = app.buttons["chat-appearance-open"]
        reveal(open); open.tap()
        let apply = app.buttons["chat-appearance-apply"]
        let cancel = app.buttons["chat-appearance-cancel"]
        XCTAssertTrue(apply.waitForExistence(timeout: 10)); XCTAssertTrue(cancel.exists)
        XCTAssertEqual(apply.label, "Применить", "Must exercise the actual Russian labels")
        XCTAssertEqual(cancel.label, "Отмена")
        for button in [apply, cancel] {
            XCTAssertTrue(button.isHittable, "Footer actions stay reachable at maximum accessibility size")
            XCTAssertTrue(app.frame.insetBy(dx: -1, dy: -1).contains(button.frame), "Footer must remain inside the viewport")
            XCTAssertGreaterThanOrEqual(button.frame.height, 44)
        }
        XCTAssertFalse(apply.frame.intersects(cancel.frame), "Footer hit targets must remain separate")
        capture("appearance-russian-accessibility-xxxl-footer")
        let sections = app.segmentedControls.firstMatch
        if sections.exists {
            sections.buttons.element(boundBy: 0).tap()
            let attachments = app.buttons["chat-appearance-preview-attachments"]
            if attachments.exists { attachments.tap() }
            capture("appearance-russian-accessibility-xxxl-preview")
        }
        cancel.tap()
        app.terminate(); app.launchArguments = normalArguments; app.launch()
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
    }

    private func exactMessageText(_ text: String) -> XCUIElement {
        // XCTest's identifier subscript rejects payloads beyond 128 UTF-16
        // units. Bind the complete literal label, including multiline text.
        app.staticTexts.matching(NSPredicate(format: "label == %@", text)).firstMatch
    }

    private func verifyFirstTapReactions(to text: String) {
        let message = exactMessageText(text)
        revealChatContent(message)
        message.doubleTap()
        let thumb = app.buttons.matching(identifier: "message-reaction-👍").firstMatch
        XCTAssertTrue(thumb.waitForExistence(timeout: 10), "One double-tap must enqueue the quick reaction")
        thumb.tap()
        waitUntil("One chip tap must remove its own reaction", timeout: 10) { !thumb.exists }
        message.press(forDuration: 0.65)
        XCTAssertTrue(app.buttons["Reply"].waitForExistence(timeout: 10))
        let heart = app.buttons.matching(identifier: "❤️").firstMatch
        XCTAssertTrue(heart.waitForExistence(timeout: 5)); heart.tap()
        let chip = app.buttons.matching(identifier: "message-reaction-❤️").firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 10), "One menu tap must enqueue the selected reaction")
        capture("authenticated-reaction-first-menu-tap")
        chip.tap()
        waitUntil("One chip tap must remove the menu reaction", timeout: 10) { !chip.exists }
    }

    private var keyboardComposerControls: [XCUIElement] {
        let send = app.buttons["chat-composer-send"]
        return [multilineField("chat-composer-text"), app.buttons["Attach photo or file"],
                send.exists ? send : app.buttons["Record voice message"]]
    }

    private struct NativeKeyboardObservation {
        let keyplanes: [CGRect]
        let accessories: [CGRect]
        let exposure: UnyriKeyboardTestGeometry.KeyboardExposure
        let bounds: CGRect?
    }

    private func observeNativeKeyboard() -> NativeKeyboardObservation {
        let keyplanes = app.keyboards.allElementsBoundByIndex.map(\.frame)
        // Native before3 AX exposed these actual system views. Do not infer
        // coverage from a generic inputView container or a guessed bar height.
        let candidates = app.otherElements.matching(NSPredicate(format: "identifier IN %@ OR label == %@",
            ["SystemInputAssistantView"], "Typing Predictions")).allElementsBoundByIndex
        let accessories = candidates.map(\.frame)
        let bounds = app.frame
        return NativeKeyboardObservation(keyplanes: keyplanes, accessories: accessories,
            exposure: UnyriKeyboardTestGeometry.keyboardExposure(app: bounds, keyplanes: keyplanes, accessories: accessories),
            bounds: UnyriKeyboardTestGeometry.visibleKeyboardBounds(app: bounds, keyplanes: keyplanes, accessories: accessories))
    }

    private func keyboardGeometryEvidence(_ stage: String) {
        func rect(_ value: CGRect) -> [String: Double] {
            func number(_ value: CGFloat) -> Double { value.isFinite ? Double(value) : -1 }
            return ["x": number(value.minX), "y": number(value.minY), "width": number(value.width),
                    "height": number(value.height), "valid": UnyriKeyboardTestGeometry.valid(value) ? 1 : 0]
        }
        let controls = keyboardComposerControls
        let timeline = app.scrollViews["chat-timeline"]
        let keyboard = observeNativeKeyboard()
        let gap = app.frame.maxY - (controls.filter(\.exists).map { $0.frame.maxY }.max() ?? app.frame.maxY)
        let baseline = closedKeyboardBaseline.map {
            ["appWidth": Double($0.size.width), "appHeight": Double($0.size.height), "gap": Double($0.gap)]
        } ?? [:]
        // A missing composer is itself useful failure evidence. Accessing its
        // label/identifier before checking exists raises NoMatches in XCTest
        // and would replace the original failing stage with a recorder error.
        let controlEvidence: [[String: Any]] = controls.map { control in
            let exists = control.exists
            return ["identifier": exists ? control.identifier : "",
                    "label": exists ? control.label : "", "exists": exists,
                    "hittable": exists && control.isHittable,
                    "frame": rect(exists ? control.frame : .zero)]
        }
        let evidence: [String: Any] = ["stage": stage, "app": rect(app.frame),
            "keyboard": keyboard.bounds.map { rect($0) } ?? [:],
            "rawKeyplanes": keyboard.keyplanes.map { rect($0) },
            "nativeAccessoryFrames": keyboard.accessories.map { rect($0) },
            "keyboardExposure": keyboard.exposure.rawValue,
            "composerBottomGap": Double(gap), "closedKeyboardBaseline": baseline,
            "timeline": rect(timeline.exists ? timeline.frame : .zero),
            "controls": controlEvidence,
            "keyboardBoundsScope": "Raw AX keyplane plus native SystemInputAssistantView/Typing Predictions; no guessed accessory inset",
            "scope": "Native control bounds; not a frame-rate measurement"]
        let data = try! JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys, .prettyPrinted])
        let attachment = XCTAttachment(string: String(decoding: data, as: UTF8.self))
        attachment.name = "keyboard-geometry-\(stage).json"; attachment.lifetime = .keepAlways; add(attachment)
    }

    private func keyboardWait(_ stage: String, timeout: TimeInterval = 12, _ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        let outcome = XCTWaiter.wait(for: [expectation], timeout: timeout)
        if outcome != .completed {
            keyboardGeometryEvidence(stage + "-failed"); capture("keyboard-\(stage)-failed")
            captureAccessibilityTree("keyboard-\(stage)-failed-tree")
        }
        XCTAssertEqual(outcome, .completed, stage)
    }

    private func assertKeyboardComposer(_ shown: Bool, stage: String, expectedCaption: String? = nil) {
        let expected = shown ? UnyriKeyboardTestGeometry.KeyboardExposure.shown : .hidden
        keyboardWait(stage + "-visibility") { self.observeNativeKeyboard().exposure == expected }
        var previous: [CGRect]?
        var previousHits: [Bool]?
        var stableSince: Date?
        // Wait for the native animation to finish changing AX geometry. Do not
        // wait for a *correct* layout: a stable covered/stuck composer must fail.
        keyboardWait(stage + "-settled", timeout: 8) {
            let controls = self.keyboardComposerControls
            let timeline = self.app.scrollViews["chat-timeline"]
            let observation = self.observeNativeKeyboard()
            let current = [self.app.frame, timeline.exists ? timeline.frame : .zero]
                + observation.keyplanes
                + observation.accessories
                + controls.map { $0.exists ? $0.frame : .zero }
            let hits = controls.map { $0.exists && $0.isHittable }
            let now = Date()
            if let previous, UnyriKeyboardTestGeometry.sameLayout(previous, current), previousHits == hits {
                return stableSince.map { now.timeIntervalSince($0) >= 0.3 } ?? false
            }
            previous = current; previousHits = hits; stableSince = now
            return false
        }
        // v17 synchronization-only change: settled geometry can precede the
        // native AX tree after closing a SwiftUI menu. Never wait for a bad
        // layout to become correct and never replace a false flag with bounds.
        let readinessControls = keyboardComposerControls
        let readinessIssues = UnyriKeyboardTestGeometry.issues(app: app.frame,
            keyboard: observeNativeKeyboard().bounds,
            controls: readinessControls.map { $0.exists ? $0.frame : .zero })
        if !readinessIssues.isEmpty {
            keyboardGeometryEvidence(stage + "-readiness-failed")
            capture("keyboard-" + stage + "-readiness-failed")
            captureAccessibilityTree("keyboard-" + stage + "-readiness-failed-tree")
            print("UNYRI_KEYBOARD_GEOMETRY_FAILURE \(stage): \(readinessIssues)")
        }
        XCTAssertTrue(readinessIssues.isEmpty, "Geometry must already be correct before AX readiness: " + stage)
        XCTAssertEqual(observeNativeKeyboard().exposure, expected)
        if !readinessControls.allSatisfy({ $0.exists && $0.isHittable }) {
            let readiness = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                self.keyboardComposerControls.allSatisfy { $0.exists && $0.isHittable }
            }, object: nil)
            let outcome = XCTWaiter.wait(for: [readiness], timeout: 8)
            print("UNYRI_NATIVE_AX_READINESS stage=\(stage) budgetSeconds=8 outcome=\(outcome.rawValue)")
            // The original assertions below remain authoritative on timeout.
            // The pre-existing exact rotated Attach functional probe is kept.
        }
        XCTAssertEqual(observeNativeKeyboard().exposure, expected)
        let controls = keyboardComposerControls
        var issues = UnyriKeyboardTestGeometry.issues(app: app.frame, keyboard: observeNativeKeyboard().bounds,
                                                     controls: controls.map { $0.exists ? $0.frame : .zero })
        if !shown, issues.isEmpty, controls.allSatisfy({ $0.exists && $0.isHittable }) {
            let bounds = app.frame
            let frames = controls.map(\.frame)
            if let baseline = closedKeyboardBaseline {
                if let shift = UnyriKeyboardTestGeometry.closedBottomGapShift(referenceSize: baseline.size,
                    referenceGap: baseline.gap, app: bounds, controls: frames), abs(shift) > 8 {
                    issues.append("Fully hidden composer bottom differs from its original position by \(shift)pt")
                }
            } else {
                closedKeyboardBaseline = (bounds.size, bounds.maxY - (frames.map(\.maxY).max() ?? bounds.maxY))
            }
        }
        keyboardGeometryEvidence(stage)
        if stage == "seed-0-focused" { captureAccessibilityTree("keyboard-first-focused-full-tree") }
        if !issues.isEmpty || !controls.allSatisfy({ $0.exists && $0.isHittable }) {
            capture("keyboard-\(stage)-covered"); captureAccessibilityTree("keyboard-\(stage)-covered-tree")
        }
        var attachmentActionVerified = false
        if stage == "rotated-keyboard-hidden", !shown, let expectedCaption {
        let hits = controls.map { $0.exists && $0.isHittable }
        let attachMatches = app.buttons.matching(NSPredicate(format: "label == %@", "Attach photo or file"))
        let selectedIDs = Set(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "composer-remove-"))
            .allElementsBoundByIndex.map(\.identifier))
        let gapShift = closedKeyboardBaseline.flatMap {
            UnyriKeyboardTestGeometry.closedBottomGapShift(referenceSize: $0.size, referenceGap: $0.gap,
                app: app.frame, controls: controls.map { $0.exists ? $0.frame : .zero })
        }
        if UnyriRotatedAttachmentActionPolicy.permits(stage: stage, shown: shown,
            hidden: observeNativeKeyboard().exposure == .hidden, geometryValid: issues.isEmpty,
            gapMatches: gapShift.map { abs($0) <= 8 } ?? false, hits: hits,
            attachCount: attachMatches.count, enabled: controls[1].exists && controls[1].isEnabled,
            captionMatches: expectedCaption.components(separatedBy: "\n").count == 4 &&
                (multilineField("chat-composer-text").value as? String) == expectedCaption,
            selectionMatches: selectedIDs == Set(["composer-remove-0", "composer-remove-1"])) {
            attachmentActionVerified = verifyRotatedAttachmentAction(attachMatches.element(boundBy: 0),
                expectedCaption: expectedCaption, stage: stage)
        }
        }
        XCTAssertTrue(issues.isEmpty, "\(stage): \(issues.joined(separator: "; "))")
        XCTAssertTrue(controls.enumerated().allSatisfy { index, control in
            control.exists && (control.isHittable || (index == 1 && attachmentActionVerified))
        }, "Every composer action must accept a real tap; only the exact rotated Attach case may use its verified real gallery action")
    }

    private func verifyRotatedAttachmentAction(_ attach: XCUIElement, expectedCaption: String, stage: String) -> Bool {
        // Preserve the original false AX observation. Do not force focus,
        // rotate, retry the tap, or change any production state to make it pass.
        let originallyHittable = attach.isHittable
        attach.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        guard app.scrollViews["gallery-grid"].waitForExistence(timeout: 10) else {
            capture("rotated-attach-real-action-failed"); captureAccessibilityTree("rotated-attach-real-action-failed-tree")
            XCTFail("One visible Attach tap did not open the real gallery"); return false
        }
        capture("rotated-attach-real-gallery"); captureAccessibilityTree("rotated-attach-real-gallery-tree")
        let close = app.buttons.matching(NSPredicate(format: "label == %@", "Close"))
        guard close.count == 1, close.element(boundBy: 0).isHittable else {
            XCTFail("The actual gallery must expose one reachable Close action"); return false
        }
        close.element(boundBy: 0).tap()
        keyboardWait(stage + "-gallery-cancelled") { !self.app.scrollViews["gallery-grid"].exists }
        let ids = Set(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "composer-remove-"))
            .allElementsBoundByIndex.map(\.identifier))
        let controls = keyboardComposerControls
        let captionMatches = (multilineField("chat-composer-text").value as? String) == expectedCaption
        let selectionMatches = ids == Set(["composer-remove-0", "composer-remove-1"])
        let hidden = observeNativeKeyboard().exposure == .hidden
        let geometryMatches = UnyriKeyboardTestGeometry.issues(app: app.frame, keyboard: observeNativeKeyboard().bounds,
            controls: controls.map { $0.exists ? $0.frame : .zero }).isEmpty
        let gapMatches = closedKeyboardBaseline.flatMap {
            UnyriKeyboardTestGeometry.closedBottomGapShift(referenceSize: $0.size, referenceGap: $0.gap,
                app: app.frame, controls: controls.map { $0.exists ? $0.frame : .zero })
        }.map { abs($0) <= 8 } ?? false
        let otherActionsMatch = controls[0].exists && controls[0].isHittable && controls[2].exists && controls[2].isHittable
        keyboardGeometryEvidence(stage + "-after-real-gallery")
        let evidence: [String: Any] = ["stage": stage, "originalAttachAXHittable": originallyHittable,
            "actualGalleryOpened": true, "singleCoordinateTap": true, "galleryCancelled": !app.scrollViews["gallery-grid"].exists,
            "exactCaptionPreserved": captionMatches, "sameTwoAttachments": selectionMatches,
            "keyboardFullyHidden": hidden, "geometryMatches": geometryMatches, "closedGapMatches": gapMatches,
            "otherActionsHittable": otherActionsMatch, "attachAXAfterCancel": controls[1].exists && controls[1].isHittable]
        let data = try! JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys])
        let proof = XCTAttachment(string: String(decoding: data, as: UTF8.self))
        proof.name = "rotated-attach-functional-proof.json"; proof.lifetime = .keepAlways; add(proof)
        guard captionMatches && selectionMatches && hidden && geometryMatches && gapMatches && otherActionsMatch else {
            capture("rotated-attach-preservation-failed"); captureAccessibilityTree("rotated-attach-preservation-failed-tree")
            XCTFail("Real gallery action must retain exact caption, same two attachments and all composer geometry/other controls"); return false
        }
        return true
    }

    private var keyboardChatViewport: CGRect {
        let timeline = app.scrollViews["chat-timeline"].frame
        let header = app.buttons["Chat options"].frame
        let top = max(timeline.minY + 4, header.maxY + 8)
        let bottom = min(timeline.maxY, keyboardComposerControls.filter(\.exists).map { $0.frame.minY }.min() ?? timeline.maxY) - 8
        return CGRect(x: timeline.minX + 8, y: top, width: timeline.width - 16, height: bottom - top)
    }

    private func recordKeyboardHistoryFixture(_ rows: [String], stage: String, anchor: String? = nil) {
        let viewport = keyboardChatViewport
        let observed: [[String: Any]] = rows.enumerated().compactMap { index, text in
            let item = exactMessageText(text)
            guard item.exists, UnyriKeyboardTestGeometry.valid(item.frame) else { return nil }
            let frame = item.frame
            return ["fixtureIndex": index, "y": Double(frame.minY), "height": Double(frame.height),
                    "visible": viewport.intersects(frame), "isChosenAnchor": text == anchor]
        }
        let tops = observed.compactMap { $0["y"] as? Double }
        let bottoms = observed.compactMap { row -> Double? in
            guard let y = row["y"] as? Double, let height = row["height"] as? Double else { return nil }
            return y + height
        }
        let evidence: [String: Any] = ["stage": stage, "fixtureCount": rows.count, "observedCount": observed.count,
            "observedTextSpan": (bottoms.max() ?? 0) - (tops.min() ?? 0),
            "observedTextSpanComplete": observed.count == rows.count,
            "viewportTop": Double(viewport.minY), "viewportHeight": Double(viewport.height),
            "jumpAvailable": app.buttons["Latest messages"].exists,
            "sourceNearBottomThresholdAtReview": 120, "rows": observed]
        let record = XCTAttachment(string: String(decoding: try! JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys]), as: UTF8.self))
        record.name = "keyboard-history-fixture-\(stage).json"; record.lifetime = .keepAlways; add(record)
    }

    private func assertKeyboardTail(_ text: String, stage: String) {
        let tail = exactMessageText(text)
        keyboardWait(stage + "-tail") {
            tail.exists && tail.isHittable && self.keyboardChatViewport.insetBy(dx: -2, dy: -2).contains(tail.frame)
        }
        capture("keyboard-\(stage)")
    }

    private func chatOptionsHeaderCloseButton() -> XCUIElement {
        // Actual native menu has two legitimate Close buttons: its full-screen
        // backdrop and this44pt header control. Never choose the backdrop by
        // firstMatch or weaken the requirement that the header accepts a tap.
        let matches = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "xmark", "Close"))
        XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(matches.count, 1, "The observed chat-options header Close must be unique")
        let close = matches.element(boundBy: 0)
        XCTAssertTrue(close.isHittable, "The real chat-options header Close must accept a tap")
        return close
    }

    private func hideKeyboardInteractively(_ stage: String) {
        XCTAssertEqual(observeNativeKeyboard().exposure, .shown)
        let viewport = keyboardChatViewport
        XCTAssertGreaterThan(viewport.height, 100)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX,
                                               dy: viewport.maxY - 24 - app.frame.minY))
        // Start on the real timeline above the composer, then drag through the
        // native keyboard's old area. Slow movement/hold avoids a fling test.
        let end = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX,
                                             dy: app.frame.maxY - 2 - app.frame.minY))
        print("UNYRI_KEYBOARD_INTERACTIVE_BEGIN \(stage)")
        start.press(forDuration: 0.05, thenDragTo: end,
                    withVelocity: XCUIGestureVelocity(rawValue: 140), thenHoldForDuration: 0.3)
        assertKeyboardComposer(false, stage: stage)
        print("UNYRI_KEYBOARD_INTERACTIVE_END \(stage)")
    }

    private func assertPostPanPositionStable(_ texts: [String], stage: String) {
        // A real downward timeline pan can intentionally reveal older history.
        // Verify the resulting readable position, not the pre-pan latest row.
        let candidates = app.staticTexts.matching(NSPredicate(format: "label IN %@", texts)).allElementsBoundByIndex
        let frames = candidates.map(\.frame)
        let viewport = keyboardChatViewport
        let index = UnyriKeyboardTestGeometry.upperFullyVisibleMessageIndex(frames, viewport: viewport)
        XCTAssertNotNil(index, "The completed pan must leave a fully readable fixture message")
        guard let index else {
            capture("keyboard-\(stage)-no-readable-anchor")
            captureAccessibilityTree("keyboard-\(stage)-no-readable-anchor-tree")
            return
        }
        let anchor = candidates[index]
        XCTAssertTrue(anchor.isHittable, "The post-pan anchor must remain genuinely readable")
        let text = anchor.label
        let before = anchor.frame, timeline = app.scrollViews["chat-timeline"].frame
        let evidence: [String: Any] = ["stage": stage, "intent": "user-timeline-pan",
            "anchorText": text, "anchorY": Double(before.minY), "anchorHeight": Double(before.height),
            "viewportTop": Double(viewport.minY), "viewportBottom": Double(viewport.maxY),
            "fixedObservationSeconds": 0.6, "tailRequiredBeforeExplicitLatestTap": false,
            "jumpAvailable": app.buttons["Latest messages"].exists]
        let record = XCTAttachment(string: String(decoding: try! JSONSerialization.data(withJSONObject: evidence,
            options: [.sortedKeys]), as: UTF8.self))
        record.name = "keyboard-post-pan-\(stage).json"; record.lifetime = .keepAlways; add(record)
        // Observe once after a fixed delay. Never poll until the anchor returns
        // to a desired position: a second, unrequested jump must fail.
        Thread.sleep(forTimeInterval: 0.6)
        assertKeyboardAnchor(text, before: before, viewport: timeline, stage: stage + "-fixed-delay")
    }

    private func returnToKeyboardBottom(_ text: String, stage: String) {
        let latest = app.buttons["Latest messages"]
        if latest.exists {
            XCTAssertTrue(latest.isHittable, "Returning to bottom is an explicit, usable user action")
            print("UNYRI_KEYBOARD_EXPLICIT_LATEST_TAP \(stage)")
            latest.tap()
        }
        assertKeyboardTail(text, stage: stage)
    }

    private func assertKeyboardAnchor(_ text: String, before: CGRect, viewport: CGRect, stage: String) {
        let anchor = exactMessageText(text)
        keyboardGeometryEvidence(stage)
        let delta = anchor.exists ? UnyriKeyboardTestGeometry.anchorDelta(before: before, after: anchor.frame,
            beforeViewport: viewport, afterViewport: app.scrollViews["chat-timeline"].frame) : nil
        let anchorEvidence: [String: Any] = ["stage": stage, "exists": anchor.exists,
            "before_y": Double(before.minY), "after_y": anchor.exists ? Double(anchor.frame.minY) : -1,
            "before_viewport_y": Double(viewport.minY),
            "after_viewport_y": Double(app.scrollViews["chat-timeline"].frame.minY),
            "delta": delta.map { Double($0) } ?? -9999, "maximum_unrequested_shift": 12]
        let encoded = try? JSONSerialization.data(withJSONObject: anchorEvidence, options: [.sortedKeys])
        let record = XCTAttachment(string: encoded.map { String(decoding: $0, as: UTF8.self) } ?? "Invalid/nonfinite anchor geometry")
        record.name = "keyboard-anchor-\(stage).json"; record.lifetime = .keepAlways; add(record)
        if delta == nil || abs(delta!) > 12 {
            capture("keyboard-\(stage)-anchor-jump"); captureAccessibilityTree("keyboard-\(stage)-anchor-jump-tree")
        }
        XCTAssertNotNil(delta, "The same visible message must survive the layout publication")
        XCTAssertLessThanOrEqual(abs(delta ?? .infinity), 12, "\(stage): no unrequested scroll to a different history position")
    }

    private func setPublicTestServer() {
        let field = app.textFields[serverPlaceholder]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        // The production build already contains this long public origin.
        // Tapping its middle and deleting backwards only cleared the prefix,
        // creating a duplicated hostname in the fixture itself.
        if field.value as? String != loopbackServer {
            reveal(field); field.tap()
            field.typeKey("a", modifierFlags: .command)
            field.typeText(loopbackServer)
        }
        XCTAssertEqual(field.value as? String, loopbackServer,
                       "The exact server must be set before any authentication request")
    }

    private func registerKeyboardAccount(_ username: String) {
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(server); server.tap()
        setPublicTestServer(); app.buttons["Done"].tap()
        reveal(app.buttons["Create an account"]); app.buttons["Create an account"].tap()
        replace(app.textFields["Your name"], with: "Synthetic Keyboard QA"); submitKeyboard("next")
        replace(app.textFields["Choose an @handle"], with: username)
        let showPassword = app.buttons["Show password"]; reveal(showPassword); showPassword.tap()
        let password = app.textFields["Password"]; replace(password, with: "uiqa-keyboard-fixture-123"); password.typeText("\n")
        let confirmation = app.textFields["Confirm password"]
        replace(confirmation, with: "uiqa-keyboard-fixture-123"); confirmation.typeText("\n")
        app.buttons["Hide password"].tap()
        let agreement = app.switches["I accept the alpha test agreement"]; reveal(agreement); agreement.tap()
        let register = app.buttons["Register"]; reveal(register); XCTAssertTrue(register.isEnabled); register.tap()
        let history = app.buttons["Start without restored history"]
        keyboardWait("registration", timeout: 40) { history.exists || self.app.buttons["Open menu"].exists }
        if history.exists { history.tap() }
        let menu = app.buttons["Open menu"]; XCTAssertTrue(menu.waitForExistence(timeout: 20)); menu.tap()
        // Keep this long keyboard fixture on its existing bootstrap route.
        // The independent first-registration test requires a direct Saved tap
        // with no warm-up or fallback; this helper does not stand in for it.
        let settings = app.buttons["drawer-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5)); settings.tap()
        keyboardWait("bootstrap-settings-ready") { self.app.buttons["settings-appearance"].exists }
        capture("keyboard-bootstrap-settings")
        menu.tap()
        let calls = app.buttons.matching(identifier: "Calls").firstMatch
        XCTAssertTrue(calls.waitForExistence(timeout: 5)); calls.tap()
        keyboardWait("bootstrap-calls-ready") { self.app.staticTexts["No recent calls"].exists }
        capture("keyboard-bootstrap-calls")
        menu.tap()
        let saved = app.buttons.matching(NSPredicate(format: "label == %@", "Saved Messages")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        let frame = saved.frame, bounds = app.frame
        let target: [String: Any] = ["route": "Settings-Calls-Saved", "tapCount": 1,
            "frame": ["x": Double(frame.minX), "y": Double(frame.minY), "width": Double(frame.width), "height": Double(frame.height)],
            "hittable": saved.isHittable, "appWidth": Double(bounds.width),
            "directFreshSavedNotExercisedHere": true]
        let record = XCTAttachment(string: String(decoding: try! JSONSerialization.data(withJSONObject: target, options: [.sortedKeys]), as: UTF8.self))
        record.name = "keyboard-bootstrap-saved-target.json"; record.lifetime = .keepAlways; add(record)
        capture("keyboard-bootstrap-saved-target"); captureAccessibilityTree("keyboard-bootstrap-saved-target-tree")
        XCTAssertTrue(saved.isHittable && bounds.contains(frame))
        XCTAssertGreaterThanOrEqual(frame.height, 44)
        XCTAssertLessThan(frame.midX, bounds.minX + bounds.width * 0.60, "The real Saved target must be inside the visible drawer")
        saved.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        keyboardWait("saved-chat-visible") { self.app.buttons["Attach photo or file"].exists }
    }

    /// Run only after reinstalling the same built app into a fresh sandbox.
    /// No Settings/Calls warm-up, hidden route, second tap or fallback is used.
    func testFreshRegistrationOpensSavedWithOneTap() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let username = "uiqa_firstsaved_\(suffix)"
        print("UNYRI_FIRST_SAVED_QA_BEGIN \(username)")
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(server); server.tap()
        setPublicTestServer(); app.buttons["Done"].tap()
        XCTAssertFalse(app.textFields[serverPlaceholder].waitForExistence(timeout: 1))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", loopbackServer)).firstMatch.exists)
        reveal(app.buttons["Create an account"]); app.buttons["Create an account"].tap()
        replace(app.textFields["Your name"], with: "Synthetic First Saved QA"); submitKeyboard("next")
        replace(app.textFields["Choose an @handle"], with: username)
        let showPassword = app.buttons["Show password"]; reveal(showPassword); showPassword.tap()
        let password = app.textFields["Password"]; replace(password, with: "uiqa-firstsaved-fixture-123"); password.typeText("\n")
        let confirmation = app.textFields["Confirm password"]
        replace(confirmation, with: "uiqa-firstsaved-fixture-123"); confirmation.typeText("\n")
        app.buttons["Hide password"].tap()
        let agreement = app.switches["I accept the alpha test agreement"]; reveal(agreement); agreement.tap()
        let register = app.buttons["Register"]; reveal(register); XCTAssertTrue(register.isEnabled); register.tap()
        let history = app.buttons["Start without restored history"]
        keyboardWait("first-saved-registration", timeout: 40) { history.exists || self.app.buttons["Open menu"].exists }
        if history.exists { history.tap() }
        let menu = app.buttons["Open menu"]; XCTAssertTrue(menu.waitForExistence(timeout: 20)); menu.tap()
        let saved = app.buttons.matching(NSPredicate(format: "label == %@", "Saved Messages")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        // The full visual drawer must expose its actual controls, not only
        // the trailing version text observed on the iOS27.2 cloud device.
        captureAccessibilityTree("first-saved-drawer-semantic-controls-tree")
        for label in ["Search", "People", "Chats", "Calls", "Saved Messages", "Settings", "Close menu"] {
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch.exists,
                          "The modal drawer must expose the native \(label) button")
        }
        var previous: CGRect?
        var stableSince: Date?
        keyboardWait("first-saved-target-stable") {
            let frame = saved.frame
            let now = Date()
            if let previous, UnyriKeyboardTestGeometry.sameLayout([previous], [frame]) {
                return stableSince.map { now.timeIntervalSince($0) >= 0.3 } ?? false
            }
            previous = frame; stableSince = now; return false
        }
        let frame = saved.frame, bounds = app.frame
        let evidence: [String: Any] = ["username": username, "route": "FreshRegistration-Chats-Saved", "tapCount": 1,
            "frame": ["x": Double(frame.minX), "y": Double(frame.minY), "width": Double(frame.width), "height": Double(frame.height)],
            "hittable": saved.isHittable, "appWidth": Double(bounds.width), "appHeight": Double(bounds.height),
            "priorFailureRun": "40495162140", "priorFailureCauseProven": false, "fpsMeasured": false]
        let proof = XCTAttachment(string: String(decoding: try! JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys]), as: UTF8.self))
        proof.name = "first-saved-native-target.json"; proof.lifetime = .keepAlways; add(proof)
        capture("first-saved-before-one-tap"); captureAccessibilityTree("first-saved-before-one-tap-tree")
        XCTAssertTrue(saved.isHittable && bounds.contains(frame))
        XCTAssertGreaterThanOrEqual(frame.height, 44)
        XCTAssertLessThan(frame.midX, bounds.minX + bounds.width * 0.60)
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: frame.midX - bounds.minX, dy: frame.midY - bounds.minY)).tap()
        keyboardWait("first-saved-chat-visible") {
            self.app.buttons["Attach photo or file"].exists && self.exactMessageText("Saved Messages").exists
        }
        assertKeyboardComposer(false, stage: "first-saved-composer-visible")
        capture("first-saved-complete"); captureAccessibilityTree("first-saved-complete-tree")
        print("UNYRI_FIRST_SAVED_QA_COMPLETE")
    }

    private func screenAuditButton(_ label: String) -> XCUIElement {
        // SwiftUI navigation rows can append their explanatory subtitle.
        let matches = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ","))
        return matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
    }

    private func screenAuditReach(_ element: XCUIElement, _ purpose: String, enabled: Bool = true) {
        XCTAssertTrue(element.waitForExistence(timeout: 8), purpose + " must be present on this real screen")
        waitUntil(purpose + " must finish its prerequisite/loading state", timeout: 8) { element.isEnabled == enabled }
        // Disabled prerequisites remain visible controls; they are not expected
        // to accept a tap. Reveal both enabled and disabled items by geometry.
        outer: for direction in [true, false] {
            for _ in 0..<6 {
                if element.exists, UnyriKeyboardTestGeometry.valid(element.frame),
                   app.frame.insetBy(dx: -2, dy: -2).contains(element.frame),
                   !enabled || element.isHittable { break outer }
                scrollVisible(up: direction)
            }
        }
        let frame = element.frame
        if !UnyriKeyboardTestGeometry.valid(frame) || !app.frame.insetBy(dx: -2, dy: -2).contains(frame) {
            capture("screen-audit-unusable-" + purpose)
            captureAccessibilityTree("screen-audit-unusable-" + purpose + "-tree")
        }
        XCTAssertTrue(element.exists && (!enabled || element.isHittable), purpose + " enabled controls must accept a real native tap")
        XCTAssertTrue(UnyriKeyboardTestGeometry.valid(frame) && app.frame.insetBy(dx: -2, dy: -2).contains(frame),
                      purpose + " must fit inside the actual app, not underneath its boundary")
        XCTAssertEqual(element.isEnabled, enabled, purpose + " enabled state must match its prerequisites")
    }

    private func screenAuditCheckpoint(_ route: String, controls: [XCUIElement] = []) {
        let frames: [[String: Any]] = controls.map { item in
            let r = item.frame
            return ["identifier": item.identifier, "label": item.label, "exists": item.exists,
                    "hittable": item.exists && item.isHittable, "enabled": item.isEnabled,
                    "x": Double(r.minX), "y": Double(r.minY), "width": Double(r.width), "height": Double(r.height)]
        }
        let data = try! JSONSerialization.data(withJSONObject: ["route": route, "controls": frames,
            "physicalFPSMeasured": false, "scope": "Real native navigation and control bounds"], options: [.sortedKeys])
        let attachment = XCTAttachment(string: String(decoding: data, as: UTF8.self))
        attachment.name = "screen-audit-" + route + ".json"; attachment.lifetime = .keepAlways; add(attachment)
        capture("screen-audit-" + route); captureAccessibilityTree("screen-audit-" + route + "-tree")
        print("UNYRI_SCREEN_AUDIT_CHECKPOINT " + route)
    }

    private func screenAuditOpen(_ label: String, title: String) {
        let button = screenAuditButton(label)
        screenAuditReach(button, label); button.tap()
        XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 12), "Real navigation must reach " + title)
    }

    private func screenAuditDiscoverLazyFooter(_ element: XCUIElement) {
        // History's Refresh is the final List row after conditional sections.
        // A lazy row need not exist in AX until a real scroll materializes it.
        // Native v8 displayed the real loaded screen while the List container
        // failed its AX isHittable/type heuristic. A background container is not
        // an action. Verify the current screen, then perform the actual scroll
        // in its visible viewport; the footer Button still needs strict AX,
        // enabled and complete-bounds checks after it materializes.
        XCTAssertTrue(app.navigationBars["History and backups"].exists)
        for _ in 0..<8 {
            if element.exists { break }
            scrollVisible(up: true)
        }
        if !element.exists {
            capture("history-backup-footer-not-materialized")
            captureAccessibilityTree("history-backup-footer-not-materialized-tree")
        }
        XCTAssertTrue(element.waitForExistence(timeout: 3), "The last backup row must materialize after bounded real List scrolling")
    }

    private func screenAuditBack(from title: String, to parent: String) {
        let bar = app.navigationBars[title]
        XCTAssertTrue(bar.exists, "Back action belongs to the current screen: " + title)
        // Native iOS27 evidence identifies the system navigation control as
        // BackButton. Do not select an unrelated toolbar action by order.
        let matches = bar.buttons.matching(NSPredicate(format: "identifier == %@", "BackButton"))
        XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(matches.count, 1, "The current native navigation bar must have one Back control")
        let back = matches.element(boundBy: 0)
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertTrue(app.navigationBars[parent].waitForExistence(timeout: 10))
    }

    private func screenAuditSwitchPrimitive(_ labelledToggle: XCUIElement) -> XCUIElement {
        // Actual iOS27 AX exposes a wide labelled Toggle wrapper and a narrow
        // child UISwitch. A coordinate tap at the wrapper's centre hits text.
        let children = labelledToggle.descendants(matching: .switch)
        guard children.count > 0 else { return labelledToggle }
        XCTAssertEqual(children.count, 1, "A labelled Toggle must expose one native switch primitive")
        let primitive = children.element(boundBy: 0)
        XCTAssertTrue(primitive.exists && primitive.isHittable)
        XCTAssertTrue(labelledToggle.frame.insetBy(dx: -3, dy: -3).contains(primitive.frame),
                      "Use only the actual switch inside this labelled control")
        return primitive
    }

    private func screenAuditSwitchRoundtrip(_ label: String) {
        let toggle = app.switches.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ",")).firstMatch
        screenAuditReach(toggle, label)
        let primitive = screenAuditSwitchPrimitive(toggle)
        let original = primitive.value as? String
        XCTAssertTrue(original == "0" || original == "1", "The real native switch must expose its current state")
        primitive.tap()
        waitUntil(label + " must change on one tap", timeout: 5) { (primitive.value as? String) != original }
        primitive.tap()
        waitUntil(label + " must restore its original setting", timeout: 5) { (primitive.value as? String) == original }
    }

    // Independent opt-in native Russian maximum-text menu audit.
    func testSettingsRussianXXXLReachability() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_ALL_SCREENS_ENABLED"] == "1",
                          "Independent opt-in RU/AccessibilityXXXL settings navigation")
        XCUIDevice.shared.orientation = .portrait
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_settingsru_\(suffix)")
        let normalArguments = app.launchArguments
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU", "-coco.language", "ru",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        defer { app.launchArguments = normalArguments }

        func valid(_ r: CGRect) -> Bool {
            [r.minX, r.minY, r.width, r.height].allSatisfy { $0.isFinite } && r.width > 0 && r.height > 0
        }
        func viewport(_ title: String?, drawer: Bool = false) -> CGRect {
            let bounds = app.frame
            var top = bounds.minY + 12
            if let title {
                let bar = app.navigationBars[title]
                if bar.exists, valid(bar.frame) { top = max(top, bar.frame.maxY + 4) }
            }
            // Coordinates use the actual visible app, not an AX container's
            // isHittable/type heuristic. Drawer gestures stay inside its lane.
            let width = drawer ? bounds.width * 0.58 : bounds.width - 8
            return CGRect(x: bounds.minX + 4, y: top, width: width,
                          height: max(1, bounds.maxY - 36 - top))
        }
        func snapshot(_ name: String, _ elements: [XCUIElement]) {
            let rows: [[String: Any]] = elements.map { e in
                guard e.exists else { return ["exists": false] }
                let r = e.frame
                return ["exists": true, "identifier": e.identifier, "label": e.label,
                    "hittable": e.isHittable, "enabled": e.isEnabled,
                    "x": Double(r.minX), "y": Double(r.minY), "width": Double(r.width), "height": Double(r.height)]
            }
            let data = try? JSONSerialization.data(withJSONObject: ["stage": name, "controls": rows,
                "requestedLanguage": "ru", "requestedContentSize": "AccessibilityXXXL",
                "physicalFPSMeasured": false, "actualLogoutPerformed": false], options: [.sortedKeys])
            let a = XCTAttachment(string: data.map { String(decoding: $0, as: UTF8.self) } ?? "Invalid geometry")
            a.name = "settings-ru-xxxl-\(name).json"; a.lifetime = .keepAlways; add(a)
            capture("settings-ru-xxxl-" + name)
        }
        func fail(_ message: String) throws {
            capture("settings-ru-xxxl-failure"); captureAccessibilityTree("settings-ru-xxxl-failure-tree")
            XCTFail(message)
            throw NSError(domain: "COCOSettingsRUReachability", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: message])
        }
        func reach(_ element: XCUIElement, title: String?, drawer: Bool = false, action: Bool = true) throws {
            for _ in 0..<16 {
                let range = viewport(title, drawer: drawer)
                guard range.height >= 120 else { try fail("No usable visible settings viewport"); return }
                var delta = -min(260, range.height * 0.44)
                if element.exists {
                    let r = element.frame
                    if valid(r), range.insetBy(dx: -1, dy: -1).contains(r) {
                        if !action || (element.isHittable && element.isEnabled) {
                            if action && (r.width < 43.5 || r.height < 43.5) {
                                try fail("Custom control is smaller than44pt: " + element.identifier); return
                            }
                            return
                        }
                        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                            element.exists && element.isHittable && element.isEnabled
                        }, object: nil)
                        if XCTWaiter.wait(for: [ready], timeout: 1) == .completed { continue }
                        if !drawer { try fail("Fully visible control does not accept a native tap: " + element.identifier); return }
                        // A drawer footer can cover the geometric row while
                        // that row remains in the scroll tree. Pan its content.
                        delta = -min(200, range.height * 0.3)
                    }
                    if valid(r) {
                        if r.height > range.height + 2 { try fail("Control cannot fit within its visible viewport"); return }
                        if r.minY < range.minY { delta = min(260, range.minY - r.minY + 20) }
                        else if r.maxY > range.maxY { delta = -min(260, r.maxY - range.maxY + 20) }
                    }
                }
                // Adaptive short drags prevent overshooting a footer that is
                // only slightly clipped. This is a pan, never an attempted tap.
                let x = range.midX
                let startY = drawer ? range.midY + (delta < 0 ? 70 : -70) : (delta < 0 ? range.maxY - 24 : range.minY + 24)
                let endY = min(range.maxY - 12, max(range.minY + 12, startY + delta))
                guard abs(endY - startY) >= 8 else { try fail("No safe pan distance remains"); return }
                let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                origin.withOffset(CGVector(dx: x - app.frame.minX, dy: startY - app.frame.minY))
                    .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: x - app.frame.minX, dy: endY - app.frame.minY)))
            }
            try fail("Control did not materialize or become fully reachable after bounded native scrolling: " + element.identifier)
        }
        func byLabel(_ label: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ",")).firstMatch
        }

        let open = app.buttons["main-open-menu"]
        guard open.waitForExistence(timeout: 30) else { try fail("Restored synthetic account did not reach the authenticated root"); return }
        XCTAssertEqual(open.label, "Открыть меню", "Exercise the real Russian resources")
        let window = app.frame.insetBy(dx: -1, dy: -1)
        XCTAssertTrue(open.isHittable && window.contains(open.frame)); XCTAssertGreaterThanOrEqual(open.frame.height, 43.5); XCTAssertGreaterThanOrEqual(open.frame.width, 43.5)
        open.tap()
        let close = app.buttons["drawer-close"]
        guard close.waitForExistence(timeout: 8) else { try fail("Drawer did not open on one native tap"); return }
        XCTAssertTrue(close.isHittable && window.contains(close.frame)); XCTAssertGreaterThanOrEqual(close.frame.height, 43.5); XCTAssertGreaterThanOrEqual(close.frame.width, 43.5)
        let drawerLabels = [("drawer-search", "Поиск"), ("drawer-people", "Люди"), ("drawer-chats", "Чаты"),
            ("drawer-calls", "Звонки"), ("drawer-rooms", "Комнаты"), ("drawer-saved", "Избранное"),
            ("drawer-server", "Сервер"), ("drawer-devices", "Устройства"), ("drawer-help", "Помощь"),
            ("drawer-report", "Сообщить о проблеме"), ("drawer-logout", "Выйти из аккаунта")]
        // Stable control identities plus the current translated labels. Do not
        // search for the superseded "Найти переписку" quick-action wording.
        for (identifier, label) in drawerLabels {
            let item = app.buttons[identifier]
            try reach(item, title: nil, drawer: true)
            XCTAssertTrue(item.label.hasPrefix(label), "The real \(identifier) action must remain localized and reachable")
        }
        let settings = app.buttons["drawer-settings"]
        try reach(settings, title: nil, drawer: true); snapshot("drawer", [close, settings, byLabel("Выйти из аккаунта")])
        settings.tap()
        guard app.navigationBars["Настройки"].waitForExistence(timeout: 12) else { try fail("Settings navigation oracle missing"); return }

        let routes: [(String, String, String)] = [
            ("settings-account", "Аккаунт", "Аккаунт"),
            ("settings-privacy", "Конфиденциальность", "Конфиденциальность и безопасность"),
            ("settings-appearance", "Внешний вид", "Внешний вид"),
            ("settings-chats", "Чаты", "Чаты"),
            ("settings-data", "Связь и память", "Данные и хранилище"),
            ("settings-help", "Помощь и приложение", "О приложении и поддержка")]
        for (identifier, russianPrefix, destination) in routes {
            let row = app.buttons[identifier]
            try reach(row, title: "Настройки")
            XCTAssertTrue(row.label.hasPrefix(russianPrefix), "Localized category must retain its real identifier")
            snapshot(identifier, [row]); row.tap()
            guard app.navigationBars[destination].waitForExistence(timeout: 12) else { try fail("Native route did not open: " + identifier); return }
            snapshot(identifier + "-opened", [])
            let back = app.navigationBars[destination].buttons.matching(identifier: "BackButton")
            guard back.count == 1, back.firstMatch.isHittable else { try fail("Missing unique native Back control"); return }
            // Native system Back is checked by real activation; its AX glyph
            // frame is not used to infer its UIKit-expanded touch region.
            back.firstMatch.tap()
            guard app.navigationBars["Настройки"].waitForExistence(timeout: 10) else { try fail("Native Back did not return Settings"); return }
        }
        let logout = app.buttons["settings-logout"]
        try reach(logout, title: "Настройки")
        let version = app.descendants(matching: .any).matching(identifier: "settings-build-version").firstMatch
        try reach(version, title: "Настройки", action: false)
        try reach(logout, title: "Настройки")
        Thread.sleep(forTimeInterval: 0.6) // Test runner only; allow the final pan to settle.
        let before = logout.frame
        Thread.sleep(forTimeInterval: 0.35)
        XCTAssertTrue(logout.isHittable && viewport("Настройки").insetBy(dx: -1, dy: -1).contains(logout.frame))
        XCTAssertLessThanOrEqual(abs(logout.frame.minY - before.minY), 2, "Idle footer must not drift after settled scrolling")
        XCTAssertTrue((version.label + " " + (version.value as? String ?? "")).contains("0.3.4"))
        snapshot("logout-footer", [logout, version])
        logout.tap() // Opens the confirmation only; NEVER activates its destructive action.
        let cancel = byLabel("Отмена"), backup = byLabel("История и резервная копия")
        try reach(cancel, title: nil); try reach(backup, title: nil)
        snapshot("logout-confirmation", [cancel, backup])
        XCTAssertFalse(cancel.frame.intersects(backup.frame))
        try reach(cancel, title: nil); cancel.tap()
        XCTAssertTrue(app.navigationBars["Настройки"].waitForExistence(timeout: 10), "Cancelling retains the signed-in Settings screen")
        print("UNYRI_SETTINGS_RU_XXXL_COMPLETE noLogout=true physicalFPSMeasured=false")
    }

    /// Independent utility-sheet smoke against a disposable synthetic Saved chat.
    /// Never schedules, creates a notification, reports, blocks or contacts a peer.
    /// Insert inside COCOAuthenticatedUITests; no normal cumulative body changes.
    func testSavedUtilityMenusPreserveDraftAndCancel() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_UTILITIES_ENABLED"] == "1",
                          "Opt-in own-Saved utility menu audit")
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        print("UNYRI_UTILITIES_QA_BEGIN \(suffix)")
        registerKeyboardAccount("uiqa_utilities_\(suffix)")
        let seed = "Utility seed \(suffix)"
        replaceComposerWithSelection(seed, stage: "utilities-seed")
        let send = app.buttons["chat-composer-send"]
        XCTAssertTrue(send.isEnabled); send.tap()
        keyboardWait("utilities-seed-visible", timeout: 20) { self.exactMessageText(seed).exists }
        waitUntil("Own seed must finish server acknowledgement before Forward is available", timeout: 25) {
            self.terminalStatusVisible()
        }
        let draft = "Unsent utility draft \(suffix)"
        replaceComposerWithSelection(draft, stage: "utilities-retained-draft")

        func requireDraft(_ stage: String) {
            let field = multilineField("chat-composer-text")
            XCTAssertTrue(field.waitForExistence(timeout: 8), stage + ": return to the actual Saved chat")
            XCTAssertEqual(field.value as? String, draft, stage + ": cancel/close must preserve the unsent draft")
            XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", seed)).count, 1,
                           stage + ": no duplicate seed is sent by utility navigation")
        }
        func button(_ identifier: String, minHeight: CGFloat = 44) -> XCUIElement {
            let matches = app.buttons.matching(identifier: identifier)
            waitUntil("Exactly one semantic utility control: " + identifier, timeout: 8) { matches.count == 1 }
            XCTAssertEqual(matches.count, 1, "Exactly one semantic utility control: " + identifier)
            let item = matches.element(boundBy: 0)
            screenAuditReach(item, identifier)
            // UIKit scales floating phone sheets as a whole (e.g. 424/440).
            // Compare the actual control in the current native bar coordinate
            // scale, preserving the established 44pt intrinsic target contract.
            let bars = app.navigationBars.allElementsBoundByIndex
            XCTAssertEqual(bars.count, 1, "A utility sheet exposes its own unique native navigation bar")
            let scale = bars[0].frame.width / app.frame.width
            XCTAssertTrue(scale >= 0.90 && scale <= 1.01, "Native sheet scale must be measured, not guessed")
            XCTAssertGreaterThanOrEqual(item.frame.height / scale, minHeight - 1,
                                        "Actual utility target at native sheet scale: " + identifier)
            return item
        }
        func openOptions(_ label: String) {
            let options = app.buttons["Chat options"]
            XCTAssertTrue(options.exists && options.isHittable); options.tap()
            let row = app.buttons.matching(NSPredicate(format: "label == %@", label))
            waitUntil("The real Chat options menu must expose " + label, timeout: 8) { row.count == 1 }
            XCTAssertEqual(row.count, 1, "The real Chat options menu must expose " + label)
            let item = row.element(boundBy: 0)
            screenAuditReach(item, label); item.tap()
        }

        openOptions("Schedule message")
        let scheduleDate = app.descendants(matching: .any).matching(identifier: "schedule-date").firstMatch
        XCTAssertTrue(scheduleDate.waitForExistence(timeout: 8))
        let schedule = button("schedule-confirm")
        let scheduleCancel = button("schedule-cancel")
        screenAuditCheckpoint("utility-schedule", controls: [scheduleDate, schedule, scheduleCancel])
        scheduleCancel.tap()
        keyboardWait("utilities-schedule-cancel") { !self.app.buttons["schedule-cancel"].exists }
        requireDraft("Schedule cancelled")

        openOptions("Reminders")
        let remindersClose = button("reminders-close", minHeight: 30)
        XCTAssertTrue(app.staticTexts["Hold a message and choose Remind me to set a reminder."].exists)
        screenAuditCheckpoint("utility-reminders-empty", controls: [remindersClose])
        remindersClose.tap()
        keyboardWait("utilities-reminders-close") { !self.app.buttons["reminders-close"].exists }
        requireDraft("Reminders closed")

        openOptions("Search messages")
        let query = app.searchFields.firstMatch
        XCTAssertTrue(query.waitForExistence(timeout: 8)); query.tap(); query.typeText(String(suffix))
        let searchResult = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chat-search-message-"))
        keyboardWait("utilities-search-result", timeout: 10) { searchResult.count == 1 }
        XCTAssertTrue(searchResult.element(boundBy: 0).isHittable)
        // Native searchable temporarily owns the navigation bar while editing.
        // iOS27 renders a trailing X beside the bottom search field, while
        // older releases call it Cancel. Resolve the actual adjacent native
        // control, then require the sheet's independent Close to return.
        if !app.buttons["chat-message-tools-close"].exists {
            captureAccessibilityTree("utility-active-search-controls-tree")
            let searchFrame = query.frame
            let visible = app.buttons.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
            let adjacent = visible.filter {
                $0.frame.minX >= searchFrame.maxX - 3 &&
                    abs($0.frame.midY - searchFrame.midY) <= max(20, searchFrame.height * 0.5) &&
                    $0.frame.width >= 25 && $0.frame.width <= 70
            }
            let named = visible.filter {
                ["Cancel", "Close Search", "Close search", "Dismiss Search", "Dismiss search", "Close"].contains($0.label)
            }
            let dismissSearch = adjacent.count == 1 ? adjacent : named
            XCTAssertEqual(dismissSearch.count, 1, "Active native search exposes one actual dismissal control")
            guard let dismiss = dismissSearch.first else { return }
            dismiss.tap()
            XCTAssertTrue(app.buttons["chat-message-tools-close"].waitForExistence(timeout: 8),
                          "Dismissing native search must restore the sheet's own Close")
        }
        let searchClose = button("chat-message-tools-close", minHeight: 30)
        screenAuditCheckpoint("utility-search", controls: [query, searchResult.element(boundBy: 0), searchClose])
        searchClose.tap()
        keyboardWait("utilities-search-close") { !self.app.buttons["chat-message-tools-close"].exists }
        requireDraft("Search closed")

        openOptions("Pinned messages")
        let pinsClose = button("chat-message-tools-close", minHeight: 30)
        XCTAssertTrue(app.staticTexts["No pinned messages"].exists)
        screenAuditCheckpoint("utility-pins-empty", controls: [pinsClose])
        pinsClose.tap()
        keyboardWait("utilities-pins-close") { !self.app.buttons["chat-message-tools-close"].exists }
        requireDraft("Pinned closed")

        let bubble = exactMessageText(seed)
        revealChatContent(bubble); bubble.press(forDuration: 0.6)
        let more = app.buttons.matching(NSPredicate(format: "label == %@", "More options"))
        waitUntil("Own message menu must expose More options", timeout: 8) { more.count == 1 }
        XCTAssertEqual(more.count, 1); more.element(boundBy: 0).tap()
        let remind = app.buttons.matching(NSPredicate(format: "label == %@", "Remind me"))
        waitUntil("More options must expose Remind me", timeout: 8) { remind.count == 1 }
        XCTAssertEqual(remind.count, 1); remind.element(boundBy: 0).tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "reminder-date").firstMatch.waitForExistence(timeout: 8))
        let reminderSave = button("reminder-save")
        let reminderCancel = button("reminder-cancel")
        screenAuditCheckpoint("utility-reminder-cancel", controls: [reminderSave, reminderCancel])
        reminderCancel.tap()
        keyboardWait("utilities-reminder-cancel") { !self.app.buttons["reminder-cancel"].exists }
        requireDraft("Reminder cancelled without creating a notification")

        revealChatContent(bubble); bubble.press(forDuration: 0.6)
        let forward = app.buttons.matching(NSPredicate(format: "label == %@", "Forward"))
        waitUntil("Acknowledged own message must expose Forward", timeout: 8) { forward.count == 1 }
        XCTAssertEqual(forward.count, 1); forward.element(boundBy: 0).tap()
        let targets = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "forward-target-"))
        keyboardWait("utilities-forward-self-target", timeout: 15) { targets.count == 1 }
        let target = targets.element(boundBy: 0)
        screenAuditReach(target, "Only synthetic Saved destination"); target.tap()
        keyboardWait("utilities-forward-selection") { target.isSelected }
        let forwardConfirm = button("forward-confirm")
        let forwardCancel = button("forward-cancel", minHeight: 30)
        screenAuditCheckpoint("utility-forward-self-cancel", controls: [target, forwardConfirm, forwardCancel])
        forwardCancel.tap()
        keyboardWait("utilities-forward-cancel") { !self.app.buttons["forward-confirm"].exists }
        requireDraft("Forward cancelled without sending")
        screenAuditCheckpoint("utility-complete", controls: [multilineField("chat-composer-text"), app.buttons["chat-composer-send"]])
        let proof = XCTAttachment(string: "{\"syntheticSavedOnly\":true,\"seedMessagesSent\":1,\"scheduledMessages\":0,\"remindersCreated\":0,\"forwardedMessages\":0,\"peerIdentityOrSafetyVerified\":false,\"fpsMeasured\":false}")
        proof.name = "utility-native-proof.json"; proof.lifetime = .keepAlways; add(proof)
        print("UNYRI_UTILITIES_QA_COMPLETE")
    }

    /// Real Settings entry and one disposable account only. The new phone's
    /// QR is decoded from rendered pixels, then checked through the public
    /// API; no app keychain, backup or transfer state is injected.
    func testHistoryTransferDirectSettingsEntryAndCancellation() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_TRANSFER_ENTRY_ENABLED"] == "1",
                          "Opt-in history transfer UI against one owned disposable account")
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(10)
        let name = "uiqa_transferui_" + suffix, password = UUID().uuidString + "-history-ui"
        let result = try await ownedPeerRequest("/auth/register", body: ["username": name,
            "password": password, "display_name": "Owned transfer UI QA", "installation_id": UUID().uuidString,
            "device_platform": "ios", "device_name": "Owned native transfer UI probe"])
        guard let token = result["access_token"] as? String,
              let user = result["user"] as? [String: Any], let id = user["id"] as? String,
              user["username"] as? String == name, UUID(uuidString: id) != nil else {
            throw NSError(domain: "OwnedTransferUIFixture", code: 1)
        }
        print("UNYRI_TRANSFER_UI_FIXTURE name=\(name) id=\(id) at=\(ISO8601DateFormatter().string(from: Date()))")
        addTeardownBlock { [self] in
            do {
                _ = try await self.ownedPeerRequest("/users/me/delete", token: token, body: ["password": password])
                print("UNYRI_TRANSFER_UI_CLEANUP deletedDeclaredOwner=true id=\(id)")
            } catch {
                XCTFail("Could not remove the one declared transfer UI fixture; no unrelated account may be touched")
            }
        }
        func tap(_ element: XCUIElement, _ stage: String) throws {
            guard element.waitForExistence(timeout: 12), element.isEnabled else {
                captureAccessibilityTree(stage + "-unavailable"); throw NSError(domain: "OwnedTransferUI", code: 2)
            }
            reveal(element)
            let f = element.frame, b = app.frame
            guard !f.isEmpty, b.contains(f) else {
                captureAccessibilityTree(stage + "-offscreen"); throw NSError(domain: "OwnedTransferUI", code: 3)
            }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX-b.minX, dy: f.midY-b.minY)).tap()
        }
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(server); server.tap(); setPublicTestServer(); app.buttons["Done"].tap()
        replace(app.textFields["Your @handle"], with: name)
        replace(app.secureTextFields["Password"], with: password)
        app.secureTextFields["Password"].typeText("\n")
        reveal(app.buttons["Log in"]); app.buttons["Log in"].tap()
        let history = app.buttons["Start without restored history"]
        keyboardWait("transfer-ui-login", timeout: 40) { history.exists || self.app.buttons["main-open-menu"].exists }
        if history.exists { history.tap() }
        dismissOwnedPasswordSavePrompt()
        try tap(app.buttons["main-open-menu"], "transfer-ui-menu")
        try tap(app.buttons["drawer-settings"], "transfer-ui-settings")
        func openTransfer() throws {
            try tap(app.buttons["Transfer"], "transfer-ui-direct-entry")
            XCTAssertTrue(app.buttons["history-transfer-start-source"].waitForExistence(timeout: 12))
            XCTAssertTrue(app.buttons["history-transfer-start-receive"].exists)
            XCTAssertTrue(app.staticTexts["history-transfer-direction"].exists)
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "@"+name)).firstMatch.exists)
        }
        try openTransfer()
        capture("transfer-ui-phone-role-choice"); captureAccessibilityTree("transfer-ui-phone-role-choice-tree")
        try tap(app.buttons["history-transfer-start-source"], "transfer-ui-source-prepare")
        let missingKey = app.staticTexts.matching(NSPredicate(format: "label == %@",
            "This phone needs its history recovery key first. Go back to History and backups and finish preparing this phone. Your messages stay here.")).firstMatch
        XCTAssertTrue(missingKey.waitForExistence(timeout: 20), "A fresh account must show its true missing prerequisite, not a false network/account error")
        XCTAssertFalse(app.buttons["history-transfer-scan"].exists)
        XCTAssertFalse(app.images["history-transfer-qr"].exists)
        capture("transfer-ui-source-prerequisite"); captureAccessibilityTree("transfer-ui-source-prerequisite-tree")
        try tap(app.buttons["history-transfer-done"], "transfer-ui-close-source")
        XCTAssertTrue(app.buttons["Transfer"].waitForExistence(timeout: 10))
        try openTransfer()
        try tap(app.buttons["history-transfer-start-receive"], "transfer-ui-receive-role")
        XCTAssertTrue(app.staticTexts["history-transfer-phone-role"].waitForExistence(timeout: 5))
        capture("transfer-ui-receiver-instructions")
        try tap(app.buttons["history-transfer-show-qr"], "transfer-ui-create-qr")
        let qr = app.images["history-transfer-qr"]
        XCTAssertTrue(qr.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["history-transfer-waiting-source"].exists)
        reveal(qr)
        capture("transfer-ui-receiver-qr"); captureAccessibilityTree("transfer-ui-receiver-qr-tree")
        let image = CIImage(image: qr.screenshot().image)
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(), options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        guard let image, let feature = detector?.features(in: image).compactMap({ $0 as? CIQRCodeFeature }).first,
              let text = feature.messageString, let data = text.data(using: .utf8),
              let challenge = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              challenge["type"] as? String == "coco-history-transfer", challenge["owner"] as? String == id,
              let transfer = challenge["transfer"] as? String, UUID(uuidString: transfer) != nil else {
            throw NSError(domain: "OwnedTransferUIQRCodePixels", code: 4)
        }
        let waiting = try await ownedPeerRequest("/history-transfer/" + transfer, token: token)
        XCTAssertEqual(waiting["status"] as? String, "waiting")
        XCTAssertEqual(waiting["owner_id"] as? String, id)
        try tap(app.buttons["history-transfer-done"], "transfer-ui-close-receiver")
        XCTAssertTrue(app.buttons["Transfer"].waitForExistence(timeout: 10))
        var cancelled = false
        for _ in 0..<12 {
            let state = try await ownedPeerRequest("/history-transfer/" + transfer, token: token)
            if state["status"] as? String == "cancelled" { cancelled = true; break }
            try await Task.sleep(nanoseconds: 300_000_000)
        }
        XCTAssertTrue(cancelled, "Closing the native transfer screen must cancel the actual waiting server request")
        capture("transfer-ui-cancelled-return-settings")
        print("UNYRI_TRANSFER_UI_COMPLETE directSettingsEntry=true sourceMissingKeyPrerequisite=true renderedQRMatchesOwnedAccount=true serverWaiting=true screenDoneServerCancelled=\(cancelled) noRecoveryKeyCreated=true physicalQRorFaceIDVerified=false")
    }

    /// Real Release app and disposable accounts only. No cache, chat rows or
    /// selection state is injected; server tokens are held by the runner only.
    func testChatListMonochromeMenuAndBulkActions() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_ENABLED"] == "1",
                          "Opt-in native chat-list actions against own disposable accounts")
        if ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_FRESH_HEADER"] == "1" {
            try await inspectFreshOwnedLoginHeader()
            return
        }
        if ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_SAVED_RESUME"] == "1" {
            try resumeOwnedSavedChatHeader()
            return
        }
        if ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_RESUME"] == "1" {
            try resumeOwnedChatListUI()
            return
        }
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(10)
        let ownerName = "uiqa_list_\(suffix)", password = UUID().uuidString + "-list-qa"
        var fixtures: [(token: String, password: String)] = []
        addTeardownBlock { [self] in
            for fixture in fixtures.reversed() {
                do {
                    _ = try await self.ownedPeerRequest("/users/me/delete", token: fixture.token,
                                                       body: ["password": fixture.password])
                } catch {
                    XCTFail("Could not remove an owned chat-list fixture; continue cleaning the remaining fixtures")
                }
            }
        }
        func register(_ name: String, display: String) async throws -> (String, String) {
            let result = try await ownedPeerRequest("/auth/register", body: ["username": name,
                "password": password, "display_name": display, "installation_id": UUID().uuidString,
                "device_platform": "ios", "device_name": "Owned chat-list native QA"])
            guard let token = result["access_token"] as? String,
                  let user = result["user"] as? [String: Any], let id = user["id"] as? String,
                  user["username"] as? String == name, UUID(uuidString: id) != nil else {
                throw NSError(domain: "OwnedChatListFixture", code: 1)
            }
            fixtures.append((token, password)); return (token, id)
        }
        let owner = try await register(ownerName, display: "Owned chat-list owner")
        var peers: [(name: String, token: String, id: String)] = []
        for index in 1...2 {
            let name = "uiqa_listpeer\(index)_\(suffix)"
            let peer = try await register(name, display: index == 1 ? "Анна" : "Максим")
            peers.append((name, peer.0, peer.1))
        }
        func activateVisible(_ element: XCUIElement, stage: String, nativePoint: Bool = false) throws {
            guard element.waitForExistence(timeout: 10), element.isEnabled else {
                captureAccessibilityTree(stage + "-missing-control")
                XCTFail("Required real control is unavailable: " + stage)
                throw NSError(domain: "OwnedChatListNavigation", code: 1)
            }
            let frame = element.frame
            guard !frame.isEmpty, app.frame.contains(frame) else {
                captureAccessibilityTree(stage + "-offscreen-control")
                XCTFail("Required control must be inside the current application: " + stage)
                throw NSError(domain: "OwnedChatListNavigation", code: 2)
            }
            // This runtime reports {-1,-1} as the AX hit point of native
            // transparent toolbar controls. Use their verified on-screen
            // centre, and prove the resulting destination below.
            if nativePoint || !element.isHittable {
                app.coordinate(withNormalizedOffset: .zero).withOffset(
                    CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY)).tap()
            } else { element.tap() }
        }
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(server); server.tap(); setPublicTestServer(); app.buttons["Done"].tap()
        replace(app.textFields["Your @handle"], with: ownerName)
        replace(app.secureTextFields["Password"], with: password)
        app.secureTextFields["Password"].typeText("\n")
        reveal(app.buttons["Log in"]); app.buttons["Log in"].tap()
        let history = app.buttons["Start without restored history"]
        keyboardWait("chat-list-login", timeout: 40) { history.exists || self.app.buttons["main-open-menu"].exists }
        if history.exists { history.tap() }
        dismissOwnedPasswordSavePrompt()
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 20))
        var chatIDs: [String] = []
        var requiredMenuButtonFailures = 0
        for peer in peers {
            try activateVisible(app.buttons["main-open-menu"], stage: "open-root-menu", nativePoint: true)
            let people = app.descendants(matching: .any).matching(identifier: "drawer-people").firstMatch
            if !people.waitForExistence(timeout: 10) {
                capture("chat-list-drawer-not-open"); captureAccessibilityTree("chat-list-drawer-not-open")
                let filter = app.descendants(matching: .any).matching(identifier: "chats-filter").firstMatch
                if filter.exists {
                    try activateVisible(filter, stage: "diagnostic-filter", nativePoint: true)
                    let filterOpened = app.buttons["chats-filter-select"].waitForExistence(timeout: 4)
                    print("UNYRI_CHAT_LIST_TOOLBAR_DIAGNOSTIC filterOpened=\(filterOpened)")
                    capture("chat-list-filter-diagnostic"); captureAccessibilityTree("chat-list-filter-diagnostic")
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65)).tap()
                }
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4)).press(forDuration: 0.05,
                    thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4)))
                let edgeOpened = people.waitForExistence(timeout: 4)
                print("UNYRI_CHAT_LIST_TOOLBAR_DIAGNOSTIC edgeOpened=\(edgeOpened)")
                capture("chat-list-drawer-edge-diagnostic"); captureAccessibilityTree("chat-list-drawer-edge-diagnostic")
                guard edgeOpened else {
                    XCTFail("Neither the visible menu button nor the edge gesture exposed the People action")
                    throw NSError(domain: "OwnedChatListNavigation", code: 3)
                }
                // Collect the requested real list/menu screenshots through a
                // working user gesture, while retaining a failing assertion
                // for the broken header button at the end of the scenario.
                requiredMenuButtonFailures += 1
            }
            try activateVisible(people, stage: "open-people")
            let query = app.textFields["search-query"]
            XCTAssertTrue(query.waitForExistence(timeout: 10)); replace(query, with: peer.name)
            let result = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", peer.name)).firstMatch
            XCTAssertTrue(result.waitForExistence(timeout: 20)); result.tap()
            XCTAssertTrue(app.buttons["Attach photo or file"].waitForExistence(timeout: 20))
            let message = peer.name == peers[0].name ? "Давай созвонимся вечером" : "Фото получил, спасибо"
            replace(multilineField("chat-composer-text"), with: message)
            try activateVisible(app.buttons["chat-composer-send"], stage: "send-owned-chat-list-message")
            XCTAssertTrue(app.staticTexts[message].waitForExistence(timeout: 20))
            // These API-created peers have no on-device Signal bundle. The
            // real composer creates the local list preview; delivery itself
            // is intentionally outside this list-action scenario.
            try activateVisible(app.buttons["Back"], stage: "return-to-chat-list", nativePoint: true)
            let closeSearch = app.buttons["search-close"]
            if closeSearch.waitForExistence(timeout: 3) {
                try activateVisible(closeSearch, stage: "close-people-search", nativePoint: true)
            }
            XCTAssertFalse(app.textFields["search-query"].exists, "Return to the actual chat list before its next action")
            let chat = try await ownedPeerRequest("/chats/direct", token: owner.0, body: ["other_user_id": peer.id])
            guard let id = chat["chat_id"] as? String, UUID(uuidString: id) != nil else {
                XCTFail("Own peer chat must have a real server UUID"); return
            }
            chatIDs.append(id)
        }
        for id in chatIDs {
            let row = app.descendants(matching: .any).matching(identifier: "chat-list-row-" + id).firstMatch
            guard row.waitForExistence(timeout: 15), !row.frame.isEmpty, app.frame.contains(row.frame) else {
                capture("chat-list-missing-real-row"); captureAccessibilityTree("chat-list-missing-real-row-tree")
                XCTFail("The actual root list must expose both owned chat rows")
                throw NSError(domain: "OwnedChatListNavigation", code: 4)
            }
        }
        capture("chat-list-main-two"); captureAccessibilityTree("chat-list-main-two-tree")
        let liveMainProof = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("chat-list-main-two-\(suffix).png")
        try app.screenshot().pngRepresentation.write(to: liveMainProof)
        print("UNYRI_CHAT_LIST_MAIN_SCREENSHOT_PATH=" + liveMainProof.path)
        let firstRow = app.descendants(matching: .any).matching(identifier: "chat-list-row-" + chatIDs[0]).firstMatch
        XCTAssertTrue(firstRow.waitForExistence(timeout: 10))
        let rowFrame = firstRow.frame, appFrame = app.frame
        XCTAssertFalse(rowFrame.isEmpty); XCTAssertTrue(appFrame.contains(rowFrame))
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: rowFrame.midX - appFrame.minX, dy: rowFrame.midY - appFrame.minY)).press(forDuration: 0.6)
        let menu = app.descendants(matching: .any).matching(identifier: "chat-actions-menu").firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["Select chats"].exists, "Selection belongs to the header filter menu")
        for label in ["Open chat", "Menu pin chat", "Menu sound off", "Menu delete chat"] {
            // Labels are localized through the production catalog; real button
            // counts/geometry are retained in the screenshot and native tree.
            if app.buttons[label].exists { XCTAssertTrue(app.buttons[label].isHittable) }
        }
        capture("chat-list-monochrome-menu"); captureAccessibilityTree("chat-list-monochrome-menu-tree")
        let menuFrame = app.descendants(matching: .any).matching(identifier: "chat-actions-menu")
            .allElementsBoundByIndex.map(\.frame).filter { !$0.isEmpty }
            .reduce(CGRect.null) { $0.union($1) }.intersection(app.frame)
        XCTAssertGreaterThan(menuFrame.width, 200); XCTAssertLessThan(menuFrame.height, app.frame.height - 60)
        if let image = app.screenshot().image.cgImage,
           let space = CGColorSpace(name: CGColorSpace.sRGB) {
            let scale = CGFloat(image.width) / app.frame.width
            let area = CGRect(x: menuFrame.minX * scale, y: menuFrame.minY * scale,
                              width: menuFrame.width * scale, height: menuFrame.height * scale).integral
            if let crop = image.cropping(to: area) {
                var pixels = [UInt8](repeating: 0, count: crop.width * crop.height * 4)
                let drawn = pixels.withUnsafeMutableBytes { data -> Bool in
                    guard let context = CGContext(data: data.baseAddress, width: crop.width, height: crop.height,
                        bitsPerComponent: 8, bytesPerRow: crop.width * 4, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                    context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height)); return true
                }
                var colored = 0, samples = 0
                for y in stride(from: 8, to: crop.height - 8, by: 4) {
                    for x in stride(from: 8, to: crop.width - 8, by: 4) {
                        let offset = (y * crop.width + x) * 4
                        let r = Int(pixels[offset]), g = Int(pixels[offset + 1]), b = Int(pixels[offset + 2])
                        let semanticRed = r > 140 && g < 140 && b < 150 && r > g + 30 && r > b + 20
                        if max(r, g, b) - min(r, g, b) > 24 && !semanticRed { colored += 1 }
                        samples += 1
                    }
                }
                XCTAssertTrue(drawn); XCTAssertGreaterThan(samples, 1000)
                XCTAssertLessThan(Double(colored) / Double(max(1, samples)), 0.025,
                                  "Monochrome menu must not retain decorative blue/green/purple cards")
                print("UNYRI_CHAT_LIST_MONO_PIXELS samples=\(samples) chromaticExceptSafetyRed=\(colored)")
            } else { XCTFail("Real menu crop must exist") }
        } else { XCTFail("Native screenshot must contain menu pixels") }
        try activateVisible(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "chat-actions-menu", "Close")).firstMatch,
                            stage: "close-chat-menu", nativePoint: true)
        func selectBoth() throws {
            let filter = app.descendants(matching: .any).matching(identifier: "chats-filter").firstMatch
            try activateVisible(filter, stage: "open-filter", nativePoint: true)
            let select = app.buttons["chats-filter-select"]
            try activateVisible(select, stage: "enter-selection")
            let all = app.buttons["chats-select-all"]
            XCTAssertTrue(all.waitForExistence(timeout: 5)); all.tap()
            let count = app.descendants(matching: .any).matching(identifier: "chats-selection-count").firstMatch
            XCTAssertEqual(count.label, "2", "Only the two locally opened owned chats are selected")
            for id in ["chats-selection-pin", "chats-selection-mute", "chats-selection-delete"] {
                let button = app.buttons[id]; XCTAssertTrue(button.isEnabled && button.isHittable)
                XCTAssertGreaterThanOrEqual(button.frame.height, 44)
            }
        }
        try selectBoth(); capture("chat-list-selection-two"); app.buttons["chats-selection-pin"].tap()
        keyboardWait("chat-list-pin-finished", timeout: 15) { !self.app.buttons["chats-selection-pin"].exists }
        var pinsAcknowledged = false
        for _ in 0..<20 {
            let pins = try await ownedPeerRequest("/chats/pins", token: owner.0)
            if Set(pins["chat_ids"] as? [String] ?? []) == Set(chatIDs) { pinsAcknowledged = true; break }
            try await Task.sleep(nanoseconds: 500_000_000)
        }
        XCTAssertTrue(pinsAcknowledged, "Real bulk pin must reach the server through durable preferences")
        try selectBoth(); app.buttons["chats-selection-mute"].tap()
        keyboardWait("chat-list-mute-finished", timeout: 15) { !self.app.buttons["chats-selection-mute"].exists }
        for id in chatIDs {
            var muted = false
            for _ in 0..<20 {
                let chat = try await ownedPeerRequest("/chats/" + id, token: owner.0)
                if chat["is_muted"] as? Bool == true { muted = true; break }
                try await Task.sleep(nanoseconds: 500_000_000)
            }
            XCTAssertTrue(muted, "Each selected chat must really be muted")
        }
        try selectBoth(); app.buttons["chats-selection-delete"].tap()
        let confirmation = app.sheets.firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.buttons["Delete only for me"].exists)
        XCTAssertTrue(confirmation.buttons["Delete for both"].exists)
        capture("chat-list-bulk-delete-confirmation")
        if confirmation.buttons["Cancel"].exists {
            confirmation.buttons["Cancel"].tap()
        } else {
            let outside = CGPoint(x: app.frame.minX + 8, y: app.frame.midY)
            XCTAssertFalse(confirmation.frame.contains(outside))
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                dx: outside.x - app.frame.minX, dy: outside.y - app.frame.minY)).tap()
        }
        keyboardWait("chat-list-bulk-delete-cancelled") { !confirmation.exists }
        XCTAssertTrue(app.buttons["chats-selection-delete"].exists, "Cancel preserves selection and chats")
        for id in chatIDs { XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "chat-list-row-" + id).firstMatch.exists) }
        app.buttons["chats-selection-delete"].tap()
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5)); confirmation.buttons["Delete only for me"].tap()
        keyboardWait("chat-list-owned-chats-deleted", timeout: 20) {
            !chatIDs.contains { self.app.descendants(matching: .any).matching(identifier: "chat-list-row-" + $0).firstMatch.exists }
        }
        for (index, peer) in peers.enumerated() {
            let chat = try await ownedPeerRequest("/chats/" + chatIDs[index], token: peer.token)
            XCTAssertEqual(chat["chat_id"] as? String, chatIDs[index], "Only-for-me must preserve the peer's conversation")
        }
        capture("chat-list-owned-deletion-complete")
        XCTAssertEqual(requiredMenuButtonFailures, 0, "The visible menu button must open the drawer; edge fallback only preserves additional diagnostic coverage")
        print("UNYRI_CHAT_LIST_NATIVE_COMPLETE monoPixels=true filterSelect=true selected=2 pinServer=true muteServer=true deleteCancel=true onlyMe=true syntheticAccountsOnly=true physicalDevice=false")
    }

    /// Continues only the two already materialized disposable rows. No account
    /// registration, login, backend token, cache injection or cleanup request.
    private func resumeOwnedChatListUI() throws {
        let environment = ProcessInfo.processInfo.environment
        let suffix = environment["UNYRI_UIQA_CHAT_LIST_OWNED_SUFFIX"] ?? ""
        let ids = (environment["UNYRI_UIQA_CHAT_LIST_OWNED_IDS"] ?? "").split(separator: ",").map(String.init)
        guard suffix.count == 10, suffix.allSatisfy({ $0.isHexDigit }), ids.count == 2,
              Set(ids).count == 2, ids.allSatisfy({ UUID(uuidString: $0) != nil }) else {
            XCTFail("Resume requires exactly the two declared owned fixture chats"); return
        }
        func point(_ element: XCUIElement) throws -> XCUICoordinate {
            guard element.waitForExistence(timeout: 8), element.isEnabled,
                  !element.frame.isEmpty, app.frame.contains(element.frame) else {
                captureAccessibilityTree("resume-missing-visible-control")
                throw NSError(domain: "OwnedChatListResume", code: 1)
            }
            return app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                dx: element.frame.midX - app.frame.minX, dy: element.frame.midY - app.frame.minY))
        }
        func tap(_ element: XCUIElement) throws { try point(element).tap() }
        func row(_ id: String) -> XCUIElement {
            app.descendants(matching: .any).matching(identifier: "chat-list-row-" + id).firstMatch
        }
        func saveLive(_ name: String) throws {
            capture(name); captureAccessibilityTree(name + "-tree")
            let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(name + "-" + suffix + ".png")
            try app.screenshot().pngRepresentation.write(to: url)
            print("UNYRI_CHAT_LIST_LIVE_SCREENSHOT " + name + "=" + url.path)
        }
        dismissOwnedPasswordSavePrompt()
        // Inspect the real visible toolbar action first; edge recovery permits
        // more coverage, but never turns a broken visible button into PASS.
        try tap(app.buttons["main-open-menu"])
        let closeDrawer = app.buttons["drawer-close"]
        let headerOpened = closeDrawer.waitForExistence(timeout: 3)
        if !headerOpened {
            try saveLive("chat-list-resume-header-not-open")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4)).press(forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4)))
        }
        guard closeDrawer.waitForExistence(timeout: 8) else {
            XCTFail("Neither actual header nor edge opens the existing fixture drawer"); return
        }
        let ownedIdentity = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "uiqa_list_" + suffix)).firstMatch
        guard ownedIdentity.waitForExistence(timeout: 5) else {
            XCTFail("Resume must prove it is the previously declared synthetic owner"); return
        }
        try saveLive("chat-list-resume-owned-drawer")
        try tap(closeDrawer)
        for id in ids { XCTAssertTrue(row(id).waitForExistence(timeout: 10)) }
        try saveLive("chat-list-resume-main-two")
        try point(row(ids[0])).press(forDuration: 0.6)
        let cardQuery = app.descendants(matching: .any).matching(identifier: "chat-actions-menu")
        XCTAssertTrue(cardQuery.firstMatch.waitForExistence(timeout: 8))
        let cardFrame = cardQuery.allElementsBoundByIndex.map(\.frame).filter { !$0.isEmpty }
            .reduce(CGRect.null) { $0.union($1) }.intersection(app.frame)
        XCTAssertGreaterThan(cardFrame.width, 200); XCTAssertLessThan(cardFrame.height, app.frame.height - 60)
        XCTAssertFalse(app.buttons["Select chats"].exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "chat-actions-menu", "Archive chat")).firstMatch.exists)
        try saveLive("chat-list-resume-monochrome-menu")
        if let source = app.screenshot().image.cgImage, let space = CGColorSpace(name: CGColorSpace.sRGB) {
            let scale = CGFloat(source.width) / app.frame.width
            let area = CGRect(x: cardFrame.minX * scale, y: cardFrame.minY * scale,
                              width: cardFrame.width * scale, height: cardFrame.height * scale).integral
            guard let image = source.cropping(to: area) else { XCTFail("Actual menu pixels must exist"); return }
            var rgba = [UInt8](repeating: 0, count: image.width * image.height * 4)
            let drawn = rgba.withUnsafeMutableBytes { bytes -> Bool in
                guard let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                    bitsPerComponent: 8, bytesPerRow: image.width * 4, space: space,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height)); return true
            }
            var chromatic = 0, samples = 0
            for y in stride(from: 8, to: image.height - 8, by: 4) {
                for x in stride(from: 8, to: image.width - 8, by: 4) {
                    let i = (y * image.width + x) * 4, r = Int(rgba[(y * image.width + x) * 4])
                    let g = Int(rgba[i + 1]), b = Int(rgba[i + 2])
                    let safetyRed = r > 140 && g < 140 && b < 150 && r > g + 30 && r > b + 20
                    if max(r, g, b) - min(r, g, b) > 24 && !safetyRed { chromatic += 1 }
                    samples += 1
                }
            }
            XCTAssertTrue(drawn); XCTAssertGreaterThan(samples, 1000)
            XCTAssertLessThan(Double(chromatic) / Double(max(1, samples)), 0.025)
            print("UNYRI_CHAT_LIST_RESUME_MONO_PIXELS samples=\(samples) chromaticExceptSafetyRed=\(chromatic)")
        } else { XCTFail("Actual screenshot must contain a native image"); return }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.5)).press(forDuration: 0.05,
            thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.38, dy: 0.5)))
        XCTAssertFalse(closeDrawer.exists, "The open modal chat menu must block a root drawer edge gesture")
        XCTAssertTrue(cardQuery.firstMatch.exists)
        capture("chat-list-resume-modal-edge-blocked")
        try tap(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "chat-actions-menu", "Close")).firstMatch)
        keyboardWait("resume-menu-closed") { !cardQuery.firstMatch.exists }
        try tap(app.buttons["chats-filter"])
        let select = app.descendants(matching: .any).matching(identifier: "chats-filter-select").firstMatch
        try tap(select)
        try tap(app.buttons["chats-select-all"])
        XCTAssertEqual(app.staticTexts["chats-selection-count"].label, "2")
        for id in ["chats-selection-pin", "chats-selection-mute", "chats-selection-delete"] {
            let action = app.buttons[id]
            XCTAssertTrue(action.isEnabled && action.isHittable); XCTAssertGreaterThanOrEqual(action.frame.height, 44)
        }
        try saveLive("chat-list-resume-selection-two")
        let cancelSelection = app.buttons.matching(identifier: "Cancel").firstMatch
        if app.buttons["chats-selection-pin"].label == "Pin chats" {
            try tap(app.buttons["chats-selection-pin"])
        } else {
            XCTAssertEqual(app.buttons["chats-selection-pin"].label, "Unpin chats")
            try tap(cancelSelection)
        }
        keyboardWait("resume-pin-complete") { !self.app.buttons["chats-selection-pin"].exists }
        try tap(app.buttons["chats-filter"]); try tap(select); try tap(app.buttons["chats-select-all"])
        if app.buttons["chats-selection-mute"].label == "Mute chats" {
            try tap(app.buttons["chats-selection-mute"])
        } else {
            XCTAssertEqual(app.buttons["chats-selection-mute"].label, "Unmute chats")
            try tap(cancelSelection)
        }
        keyboardWait("resume-mute-complete") { !self.app.buttons["chats-selection-mute"].exists }
        try tap(app.buttons["chats-filter"]); try tap(select); try tap(app.buttons["chats-select-all"])
        try tap(app.buttons["chats-selection-delete"])
        let confirmation = app.sheets.firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.buttons["Delete only for me"].exists && confirmation.buttons["Delete for both"].exists)
        try saveLive("chat-list-resume-delete-confirmation")
        if confirmation.buttons["Cancel"].exists {
            try tap(confirmation.buttons["Cancel"])
        } else {
            // iOS 26 renders this confirmation as a native popover. Its
            // standard cancellation action is a touch outside its bounds.
            let outside = CGPoint(x: app.frame.minX + 8, y: app.frame.midY)
            XCTAssertFalse(confirmation.frame.contains(outside))
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                dx: outside.x - app.frame.minX, dy: outside.y - app.frame.minY)).tap()
        }
        keyboardWait("resume-delete-confirmation-cancelled") { !confirmation.exists }
        XCTAssertTrue(app.buttons["chats-selection-delete"].exists)
        for id in ids { XCTAssertTrue(row(id).exists) }
        // Leave the two own chats intact for independent read-only server
        // acknowledgement and an optional archive continuation.
        try tap(cancelSelection)
        if environment["UNYRI_UIQA_CHAT_LIST_ARCHIVE"] == "1" {
            try point(row(ids[0])).press(forDuration: 0.6)
            try tap(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "chat-actions-menu", "Archive chat")).firstMatch)
            keyboardWait("resume-archive-left-main", timeout: 15) { !row(ids[0]).exists }
            try tap(app.buttons["main-open-menu"])
            if !closeDrawer.waitForExistence(timeout: 3) {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4)).press(forDuration: 0.05,
                    thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4)))
            }
            try tap(app.buttons["drawer-archive"])
            XCTAssertTrue(app.buttons["archive-back"].waitForExistence(timeout: 10))
            XCTAssertTrue(row(ids[0]).waitForExistence(timeout: 10)); XCTAssertFalse(row(ids[1]).exists)
            try saveLive("chat-list-resume-archive-one")
            try point(row(ids[0])).press(forDuration: 0.6)
            try tap(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "chat-actions-menu", "Restore to chats")).firstMatch)
            keyboardWait("resume-archive-restored", timeout: 15) { !row(ids[0]).exists }
            try tap(app.buttons["archive-back"])
            for id in ids { XCTAssertTrue(row(id).waitForExistence(timeout: 10)) }
            try saveLive("chat-list-resume-archive-restored")
            print("UNYRI_CHAT_LIST_RESUME_ARCHIVE_UI_COMPLETE archivedOnlyDeclaredChat=true restored=true")
        }
        XCTAssertEqual(headerOpened, true, "The visible toolbar menu action must work; edge recovery is diagnostic only")
        print("UNYRI_CHAT_LIST_RESUME_UI_COMPLETE main=true monoPixels=true selected=2 pinUI=true muteUI=true deleteCancel=true noAccountChurn=true serverAcknowledgementRequiresSeparateReadOnlyProof=true")
    }

    /// A single declared disposable owner, no peers/messages, for the exact
    /// first tap following a real fresh-device login. Cleanup is deliberately
    /// handed to the canonical fixture cleanup after its normal rate window.
    private func inspectFreshOwnedLoginHeader() async throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(10)
        let name = "uiqa_listfresh_" + suffix, password = UUID().uuidString + "-fresh-header"
        let account = try await ownedPeerRequest("/auth/register", body: ["username": name,
            "password": password, "display_name": "Owned fresh header QA", "installation_id": UUID().uuidString,
            "device_platform": "ios", "device_name": "Owned native fresh header probe"])
        guard let user = account["user"] as? [String: Any], let id = user["id"] as? String,
              user["username"] as? String == name, UUID(uuidString: id) != nil else {
            XCTFail("Fresh login must register exactly its declared disposable owner"); return
        }
        print("UNYRI_CHAT_LIST_FRESH_FIXTURE name=\(name) id=\(id) at=\(ISO8601DateFormatter().string(from: Date())) cleanupDeferredToCanonicalRateWindow=true")
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(server); server.tap(); setPublicTestServer(); app.buttons["Done"].tap()
        replace(app.textFields["Your @handle"], with: name)
        replace(app.secureTextFields["Password"], with: password)
        app.secureTextFields["Password"].typeText("\n")
        reveal(app.buttons["Log in"]); app.buttons["Log in"].tap()
        let history = app.buttons["Start without restored history"]
        keyboardWait("fresh-header-login", timeout: 40) { history.exists || self.app.buttons["main-open-menu"].exists }
        if history.exists { history.tap() }
        dismissOwnedPasswordSavePrompt()
        let button = app.buttons["main-open-menu"], close = app.buttons["drawer-close"]
        guard button.waitForExistence(timeout: 20), button.isEnabled,
              !button.frame.isEmpty, app.frame.contains(button.frame) else {
            captureAccessibilityTree("fresh-header-unavailable"); XCTFail("A visible enabled menu button must exist after login"); return
        }
        let frame = button.frame, bounds = app.frame
        let centre = CGVector(dx: frame.midX - bounds.minX, dy: frame.midY - bounds.minY)
        print("UNYRI_CHAT_LIST_FRESH_HEADER_POINT enabled=\(button.isEnabled) hittable=\(button.isHittable) x=\(centre.dx) y=\(centre.dy) keyboard=\(app.keyboards.count) historyChoice=\(history.exists)")
        capture("chat-list-fresh-before-first-tap"); captureAccessibilityTree("chat-list-fresh-before-first-tap-tree")
        app.coordinate(withNormalizedOffset: .zero).withOffset(centre).tap()
        let firstOpened = close.waitForExistence(timeout: 3)
        capture("chat-list-fresh-after-first-tap"); captureAccessibilityTree("chat-list-fresh-after-first-tap-tree")
        var retryOpened = false, edgeOpened = false
        if !firstOpened {
            app.coordinate(withNormalizedOffset: .zero).withOffset(centre).tap()
            retryOpened = close.waitForExistence(timeout: 3)
            capture("chat-list-fresh-after-second-tap"); captureAccessibilityTree("chat-list-fresh-after-second-tap-tree")
            if !retryOpened {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4)).press(forDuration: 0.05,
                    thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4)))
                edgeOpened = close.waitForExistence(timeout: 5)
                capture("chat-list-fresh-after-edge"); captureAccessibilityTree("chat-list-fresh-after-edge-tree")
            }
        }
        print("UNYRI_CHAT_LIST_FRESH_HEADER_RESULT firstOpened=\(firstOpened) retryOpened=\(retryOpened) edgeOpened=\(edgeOpened) syntheticAccountsOnly=true noPeersOrChatsCreated=true")
        if firstOpened || retryOpened || edgeOpened {
            let saved = app.buttons["drawer-saved"]
            if saved.waitForExistence(timeout: 3), !saved.frame.isEmpty, app.frame.contains(saved.frame) {
                let f = saved.frame, b = app.frame
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX - b.minX, dy: f.midY - b.minY)).tap()
                if app.buttons["Attach photo or file"].waitForExistence(timeout: 15) {
                    let marker = "Проверка интерфейса чата COCO"
                    replace(multilineField("chat-composer-text"), with: marker)
                    let send = app.buttons["chat-composer-send"], f = send.frame, b = app.frame
                    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX - b.minX, dy: f.midY - b.minY)).tap()
                    if exactMessageText(marker).waitForExistence(timeout: 10) {
                        var acknowledged = false
                        for _ in 0..<20 {
                            if terminalStatusVisible() { acknowledged = true; break }
                            try await Task.sleep(nanoseconds: 500_000_000)
                        }
                        func saveBaseline(_ stage: String) throws {
                            capture(stage); captureAccessibilityTree(stage + "-tree")
                            let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(stage + "-" + suffix + ".png")
                            try app.screenshot().pngRepresentation.write(to: url)
                            print("UNYRI_CHAT_BASELINE_SCREENSHOT " + stage + "=" + url.path)
                        }
                        try saveBaseline("fresh-owned-saved-chat")
                        let bubble = exactMessageText(marker), f = bubble.frame, b = app.frame
                        if !f.isEmpty, b.contains(f) {
                            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX - b.minX, dy: f.midY - b.minY)).press(forDuration: 0.65)
                            if app.buttons["Reply"].waitForExistence(timeout: 8) {
                                try saveBaseline("fresh-owned-saved-longpress")
                            }
                        }
                        print("UNYRI_CHAT_FRESH_SAVED_BASELINE serverAcknowledged=\(acknowledged) noPeerMessages=true visualAuditOnly=true")
                    }
                }
            }
        }
        XCTAssertTrue(firstOpened, "The first actual native menu tap after fresh login must open the drawer")
    }

    /// Standard system credential UI is outside COCO's hit-testing. Never
    /// treat a tap consumed by Passwords' sheet as an app-button failure.
    private func dismissOwnedPasswordSavePrompt() {
        let prompt = app.sheets.matching(NSPredicate(format: "label == %@", "Save Password?")).firstMatch
        if prompt.waitForExistence(timeout: 2) {
            let decline = prompt.buttons["Not Now"]
            XCTAssertTrue(decline.waitForExistence(timeout: 3)); decline.tap()
            keyboardWait("owned-system-password-save-dismissed") { !prompt.exists }
        }
    }

    private func resumeOwnedSavedChatHeader() throws {
        let expected = ProcessInfo.processInfo.environment["UNYRI_UIQA_CHAT_LIST_EXPECTED_OWNER"] ?? ""
        guard expected.hasPrefix("uiqa_listfresh_"), expected.count == 25 else {
            XCTFail("Saved resume requires the explicitly declared disposable owner"); return
        }
        func tap(_ element: XCUIElement) throws {
            guard element.waitForExistence(timeout: 10), element.isEnabled,
                  !element.frame.isEmpty, app.frame.contains(element.frame) else {
                captureAccessibilityTree("saved-resume-missing-control")
                throw NSError(domain: "OwnedSavedResume", code: 1)
            }
            let f = element.frame, b = app.frame
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX - b.minX, dy: f.midY - b.minY)).tap()
        }
        dismissOwnedPasswordSavePrompt()
        try tap(app.buttons["main-open-menu"])
        XCTAssertTrue(app.buttons["drawer-close"].waitForExistence(timeout: 5), "One real header tap must open the drawer after system credential UI")
        guard app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected)).firstMatch.waitForExistence(timeout: 5) else {
            XCTFail("Only the declared existing disposable account may be inspected"); return
        }
        capture("final-owned-header-drawer")
        try tap(app.buttons["drawer-saved"])
        XCTAssertTrue(app.buttons["Attach photo or file"].waitForExistence(timeout: 15))
        let marker = "Проверка интерфейса чата COCO", bubble = exactMessageText("Проверка интерфейса чата COCO")
        XCTAssertTrue(bubble.waitForExistence(timeout: 15)); XCTAssertTrue(terminalStatusVisible())
        capture("final-owned-saved-chat"); captureAccessibilityTree("final-owned-saved-chat-tree")
        let f = bubble.frame, b = app.frame
        XCTAssertFalse(f.isEmpty); XCTAssertTrue(b.contains(f))
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX - b.minX, dy: f.midY - b.minY)).press(forDuration: 0.65)
        XCTAssertTrue(app.buttons["Reply"].waitForExistence(timeout: 8))
        capture("final-owned-saved-longpress"); captureAccessibilityTree("final-owned-saved-longpress-tree")
        XCTAssertTrue(exactMessageText(marker).exists)
        print("UNYRI_CHAT_LIST_FINAL_HEADER_SAVED_COMPLETE firstAppTap=true systemPasswordPromptHandled=true existingOwnMessageAcknowledged=true longpress=true noAccountChurn=true noPeerMessages=true")
    }

    func testNotificationSettingsPersistAndOwnTheirNavigation() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_notification_\(suffix)")
        app.buttons["Back"].tap()
        func openNotifications() {
            let menu = app.buttons["main-open-menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 15)); menu.tap()
            let settings = app.buttons["drawer-settings"]
            XCTAssertTrue(settings.waitForExistence(timeout: 8)); settings.tap()
            let row = app.buttons["settings-notifications"]
            reveal(row); row.tap()
            XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout: 10))
        }
        openNotifications()
        let master = app.switches["notifications-enabled"]
        let messages = app.switches["notifications-messages"]
        let preview = app.switches["notifications-previews"]
        XCTAssertEqual(master.value as? String, "1")
        XCTAssertEqual(preview.value as? String, "1")
        preview.tap()
        XCTAssertEqual(preview.value as? String, "0")
        master.tap()
        XCTAssertEqual(master.value as? String, "0")
        XCTAssertFalse(messages.isEnabled)
        let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
        edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.45)))
        XCTAssertFalse(app.buttons["drawer-close"].exists)
        // Settings auto-save, so a native edge Back may legitimately return
        // to Settings. It must never turn into opening the root drawer.
        if !master.exists {
            let row = app.buttons["settings-notifications"]
            XCTAssertTrue(row.waitForExistence(timeout: 8)); reveal(row); row.tap()
            XCTAssertTrue(master.waitForExistence(timeout: 8))
        }
        XCTAssertEqual(master.value as? String, "0", "A native Back must retain the saved preference")
        capture("notifications-disabled-native")
        app.terminate(); app.launch()
        openNotifications()
        XCTAssertEqual(master.value as? String, "0", "Device preferences must survive a process restart")
        master.tap()
        XCTAssertEqual(master.value as? String, "1")
        XCTAssertEqual(preview.value as? String, "0", "Master off/on must preserve the child's choice")
        preview.tap()
        let permission = app.descendants(matching: .any).matching(identifier: "notifications-system-status").firstMatch
        reveal(permission)
        XCTAssertTrue(permission.isHittable)
        let request = app.buttons["notifications-request-permission"]
        if request.exists { XCTAssertTrue(request.isHittable) }
        capture("notifications-system-status-native")
        captureAccessibilityTree("notifications-settings-final-tree")
        print("UNYRI_NOTIFICATIONS_UI_COMPLETE persisted=true childChoicePreserved=true OSpermissionNotChanged=true physicalPhone=false")
    }

    func testNotificationSettingsRussianLargeTextControls() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_notiflarge_\(suffix)")
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU", "-coco.language", "ru",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20)); menu.tap()
        app.buttons["drawer-settings"].tap()
        let row = app.buttons["settings-notifications"]
        reveal(row); row.tap()
        XCTAssertTrue(app.navigationBars["Уведомления"].waitForExistence(timeout: 10))
        for id in ["notifications-enabled", "notifications-messages", "notifications-previews", "notifications-reminders", "notifications-sounds"] {
            let control = app.switches[id]
            reveal(control)
            XCTAssertTrue(control.isHittable && control.isEnabled, "Large Russian toggle must remain reachable: " + id)
        }
        capture("notifications-russian-large-controls")
        let status = app.descendants(matching: .any).matching(identifier: "notifications-system-status").firstMatch
        reveal(status); XCTAssertTrue(status.isHittable)
        let request = app.buttons["notifications-request-permission"]
        if request.exists { reveal(request); XCTAssertTrue(request.isHittable) }
        capture("notifications-russian-large-system")
        print("UNYRI_NOTIFICATIONS_LARGE_TEXT_COMPLETE language=ru size=AccessibilityXXXL")
    }

    func testProfileEditorDraftDiscardAndSave() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_profile_\(suffix)")
        app.buttons["Back"].tap()
        func openEditor(expectedName: String? = nil) {
            let menu = app.buttons["main-open-menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
            let profile = app.buttons["drawer-profile"]
            XCTAssertTrue(profile.waitForExistence(timeout: 5))
            if let expectedName {
                XCTAssertTrue(profile.label.contains(expectedName), "Sidebar must show the acknowledged edited name")
                capture("profile-sidebar-updated-name")
            }
            profile.tap()
            XCTAssertTrue(app.textFields["edit-profile-name"].waitForExistence(timeout: 10))
        }
        openEditor()
        let name = app.textFields["edit-profile-name"]
        func insertNameMarker(_ marker: String) -> String? {
            // This scenario checks draft/save ownership, not selection shortcuts.
            // A real insertion at the tapped caret is sufficient. Prove that the
            // whole old value is retained, then use the exact resulting name in
            // every later field/sidebar assertion. This works without a logged-
            // in Mac GUI delivering Command-key selection to the simulator.
            reveal(name)
            name.tap()
            let before = (name.value as? String) ?? ""
            XCTAssertFalse(before.contains(marker))
            name.typeText(marker)
            let after = (name.value as? String) ?? ""
            let exactInsertion = after.count == before.count + marker.count &&
                after.replacingOccurrences(of: marker, with: "") == before && after.count <= 64
            if !exactInsertion { capture("profile-fixture-insertion-incomplete") }
            XCTAssertTrue(exactInsertion, "The complete old name plus exactly one typed marker is required")
            return exactInsertion ? after : nil
        }
        let originalName = name.value as? String
        let save = app.buttons["edit-profile-save"]
        XCTAssertTrue(save.isHittable && save.isEnabled)
        capture("profile-editor-new-layout")
        guard let draftName = insertNameMarker("_Draft") else { return }
        app.buttons["edit-profile-back"].tap()
        XCTAssertTrue(app.buttons["Keep editing"].waitForExistence(timeout: 5))
        app.buttons["Keep editing"].tap()
        XCTAssertEqual(name.value as? String, draftName)
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.4)))
        XCTAssertTrue(save.exists, "Dirty profile must survive an edge gesture")
        XCTAssertFalse(app.buttons["drawer-close"].exists, "An editor must not open the root drawer")
        let photoMenu = app.buttons["edit-profile-photo-menu"]
        reveal(photoMenu); photoMenu.tap()
        let gallery = app.buttons["edit-profile-gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 5)); gallery.tap()
        let pickerCancel = app.buttons["Cancel"]
        XCTAssertTrue(pickerCancel.waitForExistence(timeout: 10)); pickerCancel.tap()
        XCTAssertEqual(name.value as? String, draftName, "Cancelling the photo picker retains typed text")
        capture("profile-editor-draft-after-photo-cancel")
        app.buttons["edit-profile-back"].tap(); app.buttons["Discard changes"].tap()
        keyboardWait("profile-discard-return", timeout: 10) { !save.exists }
        openEditor()
        XCTAssertEqual(name.value as? String, originalName, "Discard must not patch the server")
        guard let savedName = insertNameMarker("_Saved") else { return }
        save.tap()
        keyboardWait("profile-save-confirmed", timeout: 25) { !save.exists }
        openEditor(expectedName: savedName)
        XCTAssertEqual(name.value as? String, savedName)
        capture("profile-editor-saved-name-reopened")
        print("UNYRI_PROFILE_EDITOR_QA_COMPLETE draftRetained=true discardPreservedServer=true savedNameReloaded=true")
    }

    func testProfileEditorRussianLargeTextReachability() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_profilelarge_\(suffix)")
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU", "-coco.language", "ru",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20)); menu.tap()
        app.buttons["drawer-profile"].tap()
        let name = app.textFields["edit-profile-name"], save = app.buttons["edit-profile-save"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        XCTAssertTrue(save.isHittable && save.isEnabled)
        capture("profile-editor-russian-large-top")
        for identifier in ["edit-profile-name", "edit-profile-bio", "edit-profile-username"] {
            let field = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            reveal(field)
            XCTAssertTrue(field.isHittable && field.isEnabled)
        }
        XCTAssertTrue(save.isHittable, "Save stays available while the large form scrolls")
        capture("profile-editor-russian-large-fields")
    }

    func testSidebarAvatarLoadsAfterColdLaunch() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_avatar_\(suffix)")
        app.buttons["Back"].tap()
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        app.buttons["drawer-profile"].tap()
        let photoMenu = app.buttons["edit-profile-photo-menu"]
        XCTAssertTrue(photoMenu.waitForExistence(timeout: 10)); photoMenu.tap()
        let gallery = app.buttons["edit-profile-gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10)); gallery.tap()
        let photo = app.images.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@",
            "PXGGridLayout-Info", "Photo,")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 15))
        let photoFrame = photo.frame, bounds = app.frame
        XCTAssertTrue(bounds.contains(photoFrame), "The selected synthetic photo must be visibly inside the system picker")
        // PhotoKit's remote service exposes image pixels but returns (-1,-1)
        // to XCTest's legacy hit-point query on this runtime. Touch the same
        // visible image and require the actual native crop confirmation.
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: photoFrame.midX-bounds.minX, dy: photoFrame.midY-bounds.minY)).tap()
        let choose = app.buttons.matching(NSPredicate(format: "label IN %@", ["Choose", "Use Photo"])).firstMatch
        XCTAssertTrue(choose.waitForExistence(timeout: 10)); choose.tap()
        let save = app.buttons["edit-profile-save"]
        XCTAssertTrue(save.waitForExistence(timeout: 10) && save.isEnabled); save.tap()
        keyboardWait("avatar-profile-saved", timeout: 40) { !save.exists }
        // Recreate the process: the UsersRepository URL dictionary and decoded
        // UIImage cache are now cold. Do not visit Profile after this launch.
        app.terminate(); app.launch()
        XCTAssertTrue(menu.waitForExistence(timeout: 25)); menu.tap()
        let profile = app.buttons["drawer-profile"]
        XCTAssertTrue(profile.waitForExistence(timeout: 10))
        func loadedPixels() -> Bool {
            let frame = profile.frame, bounds = app.frame
            guard let image = app.screenshot().image.cgImage, frame.width > 0, frame.height > 0 else { return false }
            let sx = CGFloat(image.width)/bounds.width, sy = CGFloat(image.height)/bounds.height
            let crop = CGRect(x: (frame.minX-bounds.minX)*sx, y: (frame.minY-bounds.minY)*sy,
                              width: frame.width*sx, height: frame.height*sy).integral
                .intersection(CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height)))
            guard !crop.isNull, let pixels = image.cropping(to: crop), let colors = CGColorSpace(name: CGColorSpace.sRGB) else { return false }
            var rgba = [UInt8](repeating: 0, count: pixels.width*pixels.height*4)
            let drawn = rgba.withUnsafeMutableBytes { bytes -> Bool in
                guard let context = CGContext(data: bytes.baseAddress, width: pixels.width, height: pixels.height,
                    bitsPerComponent: 8, bytesPerRow: pixels.width*4, space: colors,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                context.draw(pixels, in: CGRect(x: 0, y: 0, width: pixels.width, height: pixels.height)); return true
            }
            return drawn && UnyriMenuFixturePixels.inspect(rgba, width: pixels.width, height: pixels.height, kind: .avatar).matches
        }
        keyboardWait("avatar-cold-drawer-real-photo-pixels", timeout: 45) { loadedPixels() }
        capture("avatar-cold-drawer-real-photo"); captureAccessibilityTree("avatar-cold-drawer-tree")
        print("UNYRI_COLD_AVATAR_QA_COMPLETE")
    }

    func testRoomCreateMinimizeLeaveAndRejoin() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_room_\(suffix)")
        let back = app.buttons["Back"]
        XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap()
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        let rooms = app.buttons["drawer-rooms"]
        XCTAssertTrue(rooms.waitForExistence(timeout: 5)); rooms.tap()
        let create = app.buttons["rooms-create"]
        XCTAssertTrue(create.waitForExistence(timeout: 10)); create.tap()
        let title = app.textFields["Title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        replace(title, with: "Native room \(suffix)")
        title.typeText("\n")
        let listing = app.switches["Show in server directory"]
        reveal(listing); XCTAssertEqual(listing.value as? String, "0")
        // UIKit exposes the label and trailing UISwitch as one AX frame;
        // hit the visible switch, not the inert centre of its long label.
        listing.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        XCTAssertEqual(listing.value as? String, "1")
        app.buttons["Room access"].tap(); app.buttons["Open room"].tap()
        XCTAssertEqual(listing.value as? String, "1", "Choosing access must preserve the directory switch")
        capture("room-create-listed-open"); captureAccessibilityTree("room-create-listed-open-tree")
        app.buttons["Create"].tap()
        let invite = app.buttons["conference-invite-participants"]
        keyboardWait("room-real-encrypted-connection", timeout: 60) { invite.exists && invite.isEnabled }
        XCTAssertTrue(app.staticTexts["Encrypted conference"].exists)
        XCTAssertTrue(app.buttons["Enable microphone"].exists)
        XCTAssertTrue(app.buttons["Enable camera"].exists)
        capture("room-real-encrypted-connected"); captureAccessibilityTree("room-real-connected-tree")
        if ProcessInfo.processInfo.environment["UNYRI_UIQA_ROOM_AUDIO_MENU"] == "1" {
            let route = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Audio route:")).firstMatch
            XCTAssertTrue(route.waitForExistence(timeout: 5))
            route.tap()
            XCTAssertTrue(app.buttons["Loudspeaker"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["Phone earpiece"].exists)
            capture("room-audio-basic-options"); captureAccessibilityTree("room-audio-basic-options-tree")
            app.buttons["Loudspeaker"].tap()
            XCTAssertTrue(invite.exists && invite.isEnabled, "Choosing audio must keep the connected room")
            route.tap()
            XCTAssertTrue(app.buttons["Phone earpiece"].waitForExistence(timeout: 5))
            app.buttons["Phone earpiece"].tap()
            XCTAssertTrue(invite.exists && invite.isEnabled)
            // A simulator menu check does not establish physical audio routing.
        }
        app.buttons["Minimize conference"].tap()
        let banner = app.buttons["conference-return"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10))
        let navigation = app.navigationBars["Rooms"]
        XCTAssertTrue(navigation.exists)
        XCTAssertLessThanOrEqual(banner.frame.maxY, navigation.frame.minY + 1)
        XCTAssertTrue(menu.isHittable && create.isHittable)
        capture("room-real-minimized-root")
        banner.tap()
        keyboardWait("room-real-return-connected", timeout: 15) { invite.exists && invite.isEnabled }
        app.buttons["conference-leave"].tap()
        keyboardWait("room-real-left", timeout: 20) { !self.app.buttons["Minimize conference"].exists && !banner.exists }
        let automaticallyListedRoom = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "room-directory-", "Native room \(suffix)")).firstMatch
        let automaticallyListed = automaticallyListedRoom.waitForExistence(timeout: 12)
        let automaticProof = XCTAttachment(string: "automaticServerRefreshFoundOwnedOpenRoom=\(automaticallyListed)")
        automaticProof.name = "room-directory-after-leave.txt"; automaticProof.lifetime = .keepAlways; add(automaticProof)
        capture("room-directory-after-leave-without-tab-change")
        let mine = app.buttons["Mine"]
        XCTAssertTrue(mine.waitForExistence(timeout: 10)); mine.tap()
        let rejoin = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "room-owned-rejoin-")).firstMatch
        XCTAssertTrue(rejoin.waitForExistence(timeout: 15))
        XCTAssertTrue(rejoin.label.contains("Join again"), "The owner re-entry key must survive ordinary leave")
        rejoin.tap()
        keyboardWait("room-real-rejoined-encrypted", timeout: 60) { invite.exists && invite.isEnabled }
        XCTAssertTrue(app.staticTexts["Encrypted conference"].exists)
        capture("room-real-rejoined"); captureAccessibilityTree("room-real-rejoined-tree")
        app.buttons["conference-leave"].tap()
        keyboardWait("room-real-left-again", timeout: 20) { !self.app.buttons["Minimize conference"].exists }
        app.buttons["On this server"].tap()
        let listed = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "room-directory-", "Native room \(suffix)")).firstMatch
        XCTAssertTrue(listed.waitForExistence(timeout: 15)); listed.tap()
        keyboardWait("room-owner-directory-rejoin-with-existing-key", timeout: 60) { invite.exists && invite.isEnabled }
        XCTAssertFalse(app.buttons["room-catalog-request-key"].exists,
                       "A saved owner key must not be requested from another participant")
        capture("room-real-server-tab-rejoined")
        app.buttons["More conference actions"].tap()
        let end = app.buttons["End for everyone"]
        XCTAssertTrue(end.waitForExistence(timeout: 5)); end.tap()
        let confirm = app.buttons["End for everyone"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
        keyboardWait("room-real-ended", timeout: 20) { !self.app.buttons["Minimize conference"].exists && !banner.exists }
        capture("room-real-ended")
        XCTAssertTrue(automaticallyListed, "Leaving a listed open room must refresh the current Server list without switching tabs")
        print("UNYRI_REAL_ROOM_QA_COMPLETE \(suffix)")
    }

    func testMultilineComposerRemainsAboveKeyboard() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_multiline_\(suffix)")
        assertKeyboardComposer(false, stage: "multiline-initial")
        let text = "Keyboard row 1 \(suffix)\nRead older messages without changing this position.\nThe composer must stay above the native keyboard."
        replace(multilineField("chat-composer-text"), with: text)
        assertKeyboardComposer(true, stage: "multiline-first-focus")
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, text)
        capture("multiline-first-focus-correct")
        app.buttons["chat-composer-send"].tap()
        assertKeyboardTail(text, stage: "multiline-first-sent")
        print("UNYRI_MULTILINE_KEYBOARD_PROBE_COMPLETE")
    }

    func testProtectedOwnerRoomWrongPasswordThenRejoin() async throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let ownerName = "uiqa_roompw_\(suffix)", roomTitle = "Protected native \(suffix)"
        registerKeyboardAccount(ownerName)
        addTeardownBlock { [self] in
            let owner = try await self.ownedPeerRequest("/auth/login", body: ["username": ownerName,
                "password": "uiqa-keyboard-fixture-123", "installation_id": UUID().uuidString,
                "device_platform": "ios", "device_name": "Owned protected-room cleanup", "os_version": "QA", "app_version": "QA"])
            guard let token = owner["access_token"] as? String else { XCTFail("Owned cleanup login failed"); return }
            do {
                let owned = try await self.ownedPeerRequest("/conferences/owned", token: token)
                let matching = (owned["fixtureArrayItems"] as? [[String: Any]] ?? []).filter { $0["title"] as? String == roomTitle }
                let rows = matching.map { row in row.filter { ["title", "listed", "access_mode", "state"].contains($0.key) } }
                let metadata = String(decoding: try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]), as: UTF8.self)
                print("UNYRI_OWNED_ROOM_METADATA " + metadata)
                let proof = XCTAttachment(string: metadata)
                proof.name = "protected-room-independent-server-metadata"; proof.lifetime = .keepAlways; self.add(proof)
            } catch { XCTFail("Read-only owned-room metadata check failed") }
            _ = try await self.ownedPeerRequest("/users/me/delete", token: token, body: ["password": "uiqa-keyboard-fixture-123"])
        }
        app.buttons["Back"].tap(); app.buttons["main-open-menu"].tap(); app.buttons["drawer-rooms"].tap()
        guard app.buttons["rooms-create"].waitForExistence(timeout: 10) else { XCTFail("Rooms must be ready"); return }
        app.buttons["rooms-create"].tap()
        let title = app.textFields["Title"]; replace(title, with: roomTitle); title.typeText("\n")
        let listing = app.switches["Show in server directory"]
        reveal(listing); XCTAssertEqual(listing.value as? String, "0")
        listing.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        XCTAssertEqual(listing.value as? String, "1")
        app.buttons["Room access"].tap(); app.buttons["Password required"].tap()
        let password = app.secureTextFields["Room password"]
        reveal(password); password.tap(); password.typeText("correct-room-qa-123")
        if app.buttons["GenerateStrongPasswordButton"].waitForExistence(timeout: 2) {
            // iOS can interrupt the first typed character with its own saved-
            // password proposal. Dismiss that real system UI and replace the
            // partial fixture entry before testing the app's password flow.
            capture("protected-owner-native-password-proposal")
            app.buttons["Close"].tap(); password.tap()
            password.typeKey("a", modifierFlags: .command)
            password.typeText("correct-room-qa-123")
        }
        capture("protected-owner-create-ready")
        guard app.buttons["Create"].isEnabled else { capture("protected-owner-password-fixture-incomplete"); XCTFail("The complete synthetic password must enable creation"); return }
        app.buttons["Create"].tap()
        let invite = app.buttons["conference-invite-participants"]
        keyboardWait("protected-owner-first-connection", timeout: 60) { invite.exists && invite.isEnabled }
        guard invite.exists && invite.isEnabled else { return }
        app.buttons["conference-leave"].tap()
        let listed = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "room-directory-", roomTitle)).firstMatch
        guard listed.waitForExistence(timeout: 15) else {
            capture("protected-room-directory-not-refreshed")
            let collection = app.collectionViews.firstMatch
            if collection.exists { collection.swipeDown() } else { app.swipeDown() }
            let manualRefreshFoundRoom = listed.waitForExistence(timeout: 12)
            let evidence = XCTAttachment(string: "automaticRefreshFoundRoom=false; manualRefreshFoundRoom=\(manualRefreshFoundRoom)")
            evidence.name = "protected-room-directory-refresh-comparison"; evidence.lifetime = .keepAlways; add(evidence)
            capture("protected-room-directory-after-manual-refresh")
            XCTFail("The created listed room must appear after leave without switching tabs"); return
        }
        listed.tap()
        let reentryPassword = app.secureTextFields["Room password (if required)"]
        guard reentryPassword.waitForExistence(timeout: 8) else { capture("protected-owner-reentry-lobby-missing"); XCTFail("The saved owner invitation must open its password lobby"); return }
        XCTAssertFalse(app.buttons["room-catalog-request-key"].exists)
        reentryPassword.tap(); reentryPassword.typeText("incorrect-room-qa")
        app.buttons["conference-join-confirm"].tap()
        let error = app.alerts["Conference"]
        let incorrectPassword = exactMessageText("Incorrect room password. Try again.")
        keyboardWait("protected-room-wrong-password-feedback", timeout: 15) { incorrectPassword.exists }
        XCTAssertFalse(app.buttons["Minimize conference"].exists)
        capture("protected-owner-wrong-password-feedback")
        if error.exists { error.buttons["OK"].tap() }
        guard reentryPassword.waitForExistence(timeout: 10) else { capture("protected-owner-retry-lost-invitation"); XCTFail("A password error must reopen the lobby with the owner's local invitation"); return }
        XCTAssertTrue(app.buttons["conference-join-confirm"].isEnabled)
        XCTAssertTrue(["", reentryPassword.placeholderValue ?? ""].contains(reentryPassword.value as? String ?? ""), "Never reuse the previous failed password")
        reentryPassword.tap(); reentryPassword.typeText("correct-room-qa-123")
        app.buttons["conference-join-confirm"].tap()
        keyboardWait("protected-owner-correct-password-rejoin", timeout: 60) { invite.exists && invite.isEnabled }
        XCTAssertTrue(app.staticTexts["Encrypted conference"].exists)
        capture("protected-owner-rejoined-after-password-error")
        app.buttons["More conference actions"].tap(); app.buttons["End for everyone"].tap()
        app.buttons.matching(NSPredicate(format: "label == %@", "End for everyone")).firstMatch.tap()
        keyboardWait("protected-owner-ended", timeout: 20) { !self.app.buttons["Minimize conference"].exists }
        print("UNYRI_PROTECTED_OWNER_ROOM_COMPLETE wrongPasswordDenied=true localKeyPreserved=true")
    }

    func testRoomBusyCallExplainsWithoutAbandoningRoom() async throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let ownerName = "uiqa_roombusy_\(suffix)", peerName = "uiqa_roompeer_\(suffix)"
        registerKeyboardAccount(ownerName)
        let peerPassword = UUID().uuidString + "-owned-qa"
        let peer = try await ownedPeerRequest("/auth/register", body: ["username": peerName,
            "password": peerPassword, "display_name": "Owned room caller QA", "installation_id": UUID().uuidString,
            "device_platform": "ios", "device_name": "Owned call UI peer", "os_version": "QA", "app_version": "QA"])
        guard let peerToken = peer["access_token"] as? String else { XCTFail("Owned peer registration failed"); return }
        addTeardownBlock { [self] in
            _ = try await self.ownedPeerRequest("/users/me/delete", token: peerToken, body: ["password": peerPassword])
            // This exact fresh fixture account is also removed if the UI test
            // stops early, so a failed assertion cannot leave its room active.
            let owner = try await self.ownedPeerRequest("/auth/login", body: ["username": ownerName,
                "password": "uiqa-keyboard-fixture-123", "installation_id": UUID().uuidString,
                "device_platform": "ios", "device_name": "Owned room cleanup", "os_version": "QA", "app_version": "QA"])
            guard let ownerToken = owner["access_token"] as? String else { XCTFail("Owned cleanup login failed"); return }
            _ = try await self.ownedPeerRequest("/users/me/delete", token: ownerToken, body: ["password": "uiqa-keyboard-fixture-123"])
        }
        app.buttons["Back"].tap(); app.buttons["main-open-menu"].tap(); app.buttons["drawer-rooms"].tap()
        XCTAssertTrue(app.buttons["rooms-create"].waitForExistence(timeout: 10)); app.buttons["rooms-create"].tap()
        let title = app.textFields["Title"]; replace(title, with: "Owned busy UI \(suffix)"); title.typeText("\n")
        app.buttons["Create"].tap()
        let invite = app.buttons["conference-invite-participants"]
        keyboardWait("busy-room-real-connected", timeout: 60) { invite.exists && invite.isEnabled }
        app.buttons["Minimize conference"].tap()
        let roomBanner = app.buttons["conference-return"]
        XCTAssertTrue(roomBanner.waitForExistence(timeout: 10))
        app.buttons["main-open-menu"].tap(); app.buttons["drawer-people"].tap()
        let query = app.textFields["search-query"]
        guard query.waitForExistence(timeout: 10) else { capture("busy-room-people-not-opened"); XCTFail("Open the real People drawer destination"); return }
        query.tap(); query.typeText(peerName)
        let person = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", peerName)).firstMatch
        guard person.waitForExistence(timeout: 15) else { capture("busy-room-peer-not-found"); XCTFail("The disposable peer must appear in People"); return }
        person.tap()
        guard app.buttons["Call"].waitForExistence(timeout: 15) else { capture("busy-room-chat-not-opened"); XCTFail("The real peer chat must expose its call control"); return }
        XCTAssertTrue(roomBanner.exists); app.buttons["Call"].tap()
        capture("room-busy-after-call-tap")
        let alert = app.alerts["Another conversation is active"]
        guard alert.waitForExistence(timeout: 8) else { capture("room-busy-missing-explanation"); XCTFail("A real chat call tap during a room must explain why it cannot start"); return }
        XCTAssertTrue(alert.staticTexts["You are already in a room. Leave it before starting another conversation."].exists)
        alert.buttons["OK"].tap(); XCTAssertTrue(roomBanner.exists)
        XCTAssertFalse(app.buttons["call-return"].exists)
        roomBanner.tap(); keyboardWait("busy-room-restored", timeout: 15) { invite.exists && invite.isEnabled }
        app.buttons["More conference actions"].tap(); app.buttons["End for everyone"].tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "End for everyone")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
        keyboardWait("busy-room-ended", timeout: 20) { !self.app.buttons["Minimize conference"].exists && !roomBanner.exists }
        capture("room-busy-call-explained-and-ended")
        print("UNYRI_ROOM_BUSY_CALL_UI_COMPLETE connectedRoomPreserved=true blockedCallExplained=true")
    }

    private func ownedPeerRequest(_ path: String, token: String? = nil,
                                  body: [String: Any]? = nil) async throws -> [String: Any] {
        // Test-runner networking only. No app database, keychain or draft state
        // is injected. Tokens/passwords stay in memory and are never attached.
        guard loopbackServer == "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud",
              let url = URL(string: loopbackServer + path) else {
            throw NSError(domain: "OwnedPeerFixture", code: 1)
        }
        var request = URLRequest(url: url); request.timeoutInterval = 15
        request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        for attempt in 0..<3 {
            let (data, response) = try await URLSession.shared.data(for: request)
            let http = response as? HTTPURLResponse, status = http?.statusCode ?? 0
            if status == 429 && attempt < 2 {
                // The public server's admission limits also apply to cleanup.
                // Respect its pause rather than disabling limits or leaking a
                // disposable account when several native cases run in a row.
                let pause = min(120, max(61, Double(http?.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 61))
                print("UNYRI_OWNED_FIXTURE_RATE_PAUSE seconds=\(Int(pause))")
                try await Task.sleep(nanoseconds: UInt64(pause * 1_000_000_000))
                continue
            }
            guard (200..<300).contains(status) else { throw NSError(domain: "OwnedPeerFixtureHTTP", code: status) }
            if data.isEmpty { return [:] }
            let decoded = try JSONSerialization.jsonObject(with: data)
            if let object = decoded as? [String: Any] { return object }
            return ["fixtureArrayItems": decoded as? [[String: Any]] ?? []]
        }
        throw NSError(domain: "OwnedPeerFixtureHTTP", code: 429)
    }

    func testDevicesRevokesOnlyOwnedOtherSession() async throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let ownerName = "uiqa_devices_\(suffix)", deviceName = "Owned revoke QA \(suffix)"
        registerKeyboardAccount(ownerName)
        let draft = "Keep native owner draft \(suffix)"
        let composer = multilineField("chat-composer-text"); composer.tap(); composer.typeText(draft)
        addTeardownBlock { [self] in
            let owner = try await self.ownedPeerRequest("/auth/login", body: ["username": ownerName,
                "password": "uiqa-keyboard-fixture-123", "installation_id": UUID().uuidString,
                "device_platform": "ios", "device_name": "Owned device-fixture cleanup"])
            guard let token = owner["access_token"] as? String else { XCTFail("Device fixture cleanup admission failed"); return }
            _ = try await self.ownedPeerRequest("/users/me/delete", token: token, body: ["password": "uiqa-keyboard-fixture-123"])
        }
        let other = try await ownedPeerRequest("/auth/login", body: ["username": ownerName,
            "password": "uiqa-keyboard-fixture-123", "installation_id": UUID().uuidString,
            "device_platform": "ios", "device_name": deviceName, "os_version": "QA", "app_version": "QA"])
        guard let token = other["access_token"] as? String else { XCTFail("Owned second session admission failed"); return }
        app.buttons["Back"].tap(); app.buttons["main-open-menu"].tap(); app.buttons["drawer-devices"].tap()
        guard app.navigationBars["Devices"].waitForExistence(timeout: 10) else { capture("owned-device-menu-missing"); XCTFail("Devices must open"); return }
        let otherLabel = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", deviceName)).firstMatch
        guard otherLabel.waitForExistence(timeout: 15) else { capture("owned-device-row-missing"); XCTFail("The real new session must be shown"); return }
        XCTAssertTrue(app.staticTexts["This device"].exists)
        let revoke = app.buttons.matching(NSPredicate(format: "label == %@", "Revoke"))
        XCTAssertEqual(revoke.count, 1, "Only the other owned session has a revocation action")
        revoke.firstMatch.tap()
        let confirmation = app.alerts["Sign out this device?"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", deviceName)).firstMatch.exists)
        capture("owned-device-revoke-confirmation"); confirmation.buttons["Cancel"].tap()
        _ = try await ownedPeerRequest("/devices", token: token)
        XCTAssertTrue(otherLabel.exists, "Cancel leaves the other real session active")
        revoke.firstMatch.tap(); XCTAssertTrue(confirmation.waitForExistence(timeout: 5)); confirmation.buttons["Revoke"].tap()
        keyboardWait("owned-device-confirmed-removal", timeout: 15) { !otherLabel.exists }
        do {
            _ = try await ownedPeerRequest("/devices", token: token)
            XCTFail("A revoked session must no longer authenticate")
        } catch let error as NSError {
            XCTAssertEqual(error.domain, "OwnedPeerFixtureHTTP"); XCTAssertEqual(error.code, 401)
        }
        XCTAssertTrue(app.staticTexts["This device"].exists)
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label == %@", "Revoke")).count, 0)
        capture("owned-device-removed-current-session-retained")
        let settingsBack = app.navigationBars["Devices"].buttons["Settings"]
        XCTAssertTrue(settingsBack.exists && settingsBack.isHittable); settingsBack.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        app.buttons["main-open-menu"].tap(); app.buttons["drawer-saved"].tap()
        guard app.buttons["Attach photo or file"].waitForExistence(timeout: 10) else { XCTFail("The native owner's chat remains usable"); return }
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, draft)
        app.buttons["chat-composer-send"].tap()
        waitUntil("The current session still sends an authenticated message after revoking the other session", timeout: 30) {
            self.exactMessageText(draft).exists && self.terminalStatusVisible()
        }
        guard exactMessageText(draft).exists, terminalStatusVisible() else { return }
        XCTAssertFalse(app.staticTexts["Failed to send"].exists)
        capture("owned-device-current-session-message-acknowledged")
        print("UNYRI_OWNED_DEVICE_REVOCATION_COMPLETE cancelPreserved=true revokedToken401=true currentSessionAuthenticatedSend=true draftRetained=true")
    }

    func testBlockedAccountMutationsUseOwnedPeer() async throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let ownerName = "uiqa_blocker_\(suffix)", peerName = "uiqa_blocked_\(suffix)"
        registerKeyboardAccount(ownerName)
        let peerPassword = UUID().uuidString + "-owned-qa"
        let registered = try await ownedPeerRequest("/auth/register", body: ["username": peerName,
            "password": peerPassword, "display_name": "Owned blocked account QA", "installation_id": UUID().uuidString,
            "device_platform": "ios", "device_name": "Owned native UI peer", "os_version": "QA", "app_version": "QA"])
        guard let token = registered["access_token"] as? String,
              let peer = registered["user"] as? [String: Any], let peerID = peer["id"] as? String,
              peer["username"] as? String == peerName, UUID(uuidString: peerID) != nil else {
            XCTFail("The fixture must register exactly its own peer"); return
        }
        addTeardownBlock { [self] in
            _ = try await self.ownedPeerRequest("/users/me/delete", token: token, body: ["password": peerPassword])
        }
        func ownerVisibleToPeer() async throws -> Bool {
            let found = try await self.ownedPeerRequest("/users/search?q=" + ownerName, token: token)
            return (found["items"] as? [[String: Any]] ?? []).contains { $0["username"] as? String == ownerName }
        }
        let initiallyVisible = try await ownerVisibleToPeer(); XCTAssertTrue(initiallyVisible)
        app.buttons["Back"].tap(); app.buttons["main-open-menu"].tap(); app.buttons["drawer-settings"].tap()
        let privacy = app.buttons["settings-privacy"]; reveal(privacy); privacy.tap()
        let blocked = app.buttons["settings-blocked-accounts"]; reveal(blocked); blocked.tap()
        XCTAssertTrue(app.navigationBars["Blocked accounts"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["blocked-accounts-empty"].waitForExistence(timeout: 10))
        app.buttons["blocked-accounts-add"].tap()
        let search = app.textFields["blocked-accounts-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 8)); search.tap(); search.typeText(peerName)
        let row = app.buttons["blocked-account-" + peerID]
        XCTAssertTrue(row.waitForExistence(timeout: 15)); reveal(row); row.tap()
        XCTAssertTrue(app.buttons["blocked-account-confirm"].waitForExistence(timeout: 8))
        capture("blocked-owned-peer-confirmation"); app.buttons["Cancel"].tap()
        let visibleAfterCancel = try await ownerVisibleToPeer(); XCTAssertTrue(visibleAfterCancel)
        row.tap(); app.buttons["blocked-account-confirm"].tap()
        keyboardWait("owned-peer-blocked-row-removed", timeout: 15) { !row.exists }
        screenAuditBack(from: "Block account", to: "Blocked accounts")
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        let visibleAfterBlock = try await ownerVisibleToPeer(); XCTAssertFalse(visibleAfterBlock,
            "Independently querying with the disposable blocked peer must hide the owner")
        capture("blocked-owned-peer-server-confirmed")
        row.tap(); XCTAssertTrue(app.buttons["blocked-account-confirm"].waitForExistence(timeout: 8))
        capture("unblock-owned-peer-confirmation"); app.buttons["Cancel"].tap()
        XCTAssertTrue(row.exists)
        row.tap(); app.buttons["blocked-account-confirm"].tap()
        keyboardWait("owned-peer-unblocked-row-removed", timeout: 15) { !row.exists }
        XCTAssertTrue(app.staticTexts["blocked-accounts-empty"].waitForExistence(timeout: 10))
        let visibleAfterUnblock = try await ownerVisibleToPeer(); XCTAssertTrue(visibleAfterUnblock)
        capture("blocked-owned-peer-empty-after-unblock")
        print("UNYRI_BLOCKED_PEER_UI_COMPLETE realServerMutation=true cancelPreserved=true accountIsDisposable=true")
    }

    func testCrashReportOfferAndConsentWithSyntheticArchive() throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_crashui_\(suffix)")
        func seed(_ stage: Int) throws {
            app.terminate()
            let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let request = dir.appendingPathComponent("coco-crash-qa-\(UUID().uuidString).json")
            let receipt = request.appendingPathExtension("seeded")
            let input: [String: Any] = ["protocol": "coco-crash-ui-v1", "stage": stage,
                "receipt": receipt.path, "syntheticOnly": true, "appTerminated": true]
            try JSONSerialization.data(withJSONObject: input).write(to: request, options: .atomic)
            let announced = try JSONSerialization.data(withJSONObject: ["request": request.path])
            print("UNYRI_CRASH_QA_REQUEST " + String(decoding: announced, as: UTF8.self))
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                FileManager.default.fileExists(atPath: receipt.path)
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 40), .completed,
                           "The external owned-simulator fixture must acknowledge its scoped archive write")
            let evidence = try JSONSerialization.jsonObject(with: Data(contentsOf: receipt)) as? [String: Bool]
            XCTAssertEqual(evidence?["syntheticOnly"], true)
            XCTAssertEqual(evidence?["ownerScoped"], true)
            XCTAssertEqual(evidence?["osCallbackTested"], false)
            app.launch()
        }
        try seed(1)
        let offer = app.alerts["UNYRI closed unexpectedly"]
        XCTAssertTrue(offer.waitForExistence(timeout: 15)); capture("crash-fixture-first-offer")
        offer.buttons["Report a problem"].tap()
        XCTAssertTrue(app.navigationBars["Report a problem"].waitForExistence(timeout: 10),
                      "The alert action must really open the production report sheet")
        XCTAssertTrue(app.switches["Include crash diagnostics"].exists)
        let consent = app.switches["I agree to send this report and these screenshots"]
        reveal(consent); XCTAssertEqual(consent.value as? String, "0")
        let send = app.buttons["Send report"]; reveal(send); send.tap()
        XCTAssertTrue(app.staticTexts["Turn on consent above to send your report and screenshots."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Report received"].exists)
        capture("crash-fixture-report-requires-consent")
        app.navigationBars["Report a problem"].buttons["Done"].tap()
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 8))
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 15))
        XCTAssertFalse(offer.waitForExistence(timeout: 2), "An already offered record must not repeat after restart")
        try seed(2)
        XCTAssertTrue(offer.waitForExistence(timeout: 15)); offer.buttons["Later"].tap()
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 15))
        XCTAssertFalse(offer.waitForExistence(timeout: 2))
        app.buttons["main-open-menu"].tap(); app.buttons["drawer-report"].tap()
        XCTAssertTrue(app.navigationBars["Report a problem"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.switches["Include crash diagnostics"].exists, "Later keeps the diagnostic available in manual feedback")
        capture("crash-fixture-later-manual-report")
        print("UNYRI_CRASH_UI_FIXTURE_COMPLETE syntheticArchive=true osCallbackTested=false reportSubmitted=false")
    }

    func testHEVCGalleryVideoSendsAndPlays() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_hevc_\(suffix)")
        let caption = "Synthetic HEVC video \(suffix)"
        app.buttons["Attach photo or file"].tap()
        if app.buttons["Photo access"].waitForExistence(timeout: 2) {
            app.buttons["Photo access"].tap()
            let names = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let local = app.buttons.matching(NSPredicate(format: "label IN %@", names)).firstMatch
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(NSPredicate(format: "label IN %@", names)).firstMatch
            keyboardWait("hevc-photo-permission", timeout: 15) { local.exists || system.exists }
            (local.exists ? local : system).tap()
        }
        XCTAssertTrue(app.buttons["gallery-send-options"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.buttons.matching(identifier: "gallery-select-video").count, 1,
                       "This dedicated library contains exactly one video, the verified HEVC fixture")
        selectGalleryAsset("gallery-select-video", expectedCount: 1)
        app.buttons["gallery-send-options"].tap()
        app.buttons["gallery-mode-image"].tap()
        app.buttons["gallery-send"].tap()
        keyboardWait("hevc-composer-admission") { self.app.scrollViews["composer-attachments"].exists }
        replace(multilineField("chat-composer-text"), with: caption)
        capture("hevc-before-compression"); app.buttons["chat-composer-send"].tap()
        waitUntil("HEVC compression and encrypted upload reach the actual message", timeout: 90) {
            self.exactMessageText(caption).exists && !self.app.buttons["Cancel sending"].exists && self.terminalStatusVisible()
        }
        XCTAssertFalse(app.staticTexts["Failed to send"].exists)
        revealChatContent(exactMessageText(caption))
        let videos = chatMediaElements(.video); XCTAssertEqual(videos.count, 1)
        guard let video = videos.first else { return }
        // A single portrait video can be taller than a fully keyboard-exposed
        // viewport once its caption is included. Test its real visible Play
        // target, not an impossible extra scroll past this short history's end.
        let target = CGPoint(x: video.frame.midX, y: video.frame.midY)
        let headerBottom = max(app.buttons["Back"].frame.maxY, app.buttons["Chat options"].frame.maxY)
        XCTAssertTrue(video.isHittable && app.frame.contains(target))
        XCTAssertGreaterThan(target.y, headerBottom + 12)
        XCTAssertLessThan(target.y, app.buttons["Attach photo or file"].frame.minY - 12)
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let clock = app.sliders["Playback position"]
        XCTAssertTrue(clock.waitForExistence(timeout: 15))
        waitUntil("The converted HEVC source really plays", timeout: 15) { self.playbackPercent(clock) > 0 }
        pauseIncomingVideo()
        let rendered = app.descendants(matching: .any).matching(identifier: "media-video-surface").firstMatch
        waitUntil("A real decoded output frame is ready", timeout: 15) { rendered.value as? String == "Ready" }
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "media-video-loading-poster").firstMatch.exists)
        let playerControls = ["Close", "Actions", "Back 10 seconds", "Play", "Forward 10 seconds", "Playback speed", "Mute"].map { app.buttons[$0] }
        for control in playerControls {
            XCTAssertTrue(control.exists && control.isHittable)
            XCTAssertGreaterThanOrEqual(control.frame.width, 44, "Native player hit target must stay at least44pt wide")
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
            XCTAssertTrue(app.frame.contains(control.frame))
        }
        for first in playerControls.indices {
            for second in playerControls.indices where first < second {
                XCTAssertFalse(playerControls[first].frame.intersects(playerControls[second].frame), "Player hit regions must remain disjoint")
            }
        }
        capture("hevc-converted-video-playing"); app.buttons["Close"].tap()
        XCTAssertTrue(app.buttons["Attach photo or file"].waitForExistence(timeout: 10))
        print("UNYRI_HEVC_SEND_PLAY_COMPLETE \(suffix) synthetic8Bit12Seconds=true physicalHDR=false")
    }

    func testComposerNativeSelectionFixture() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_selection_\(suffix)")
        replace(multilineField("chat-composer-text"), with: "Keyboard media \(suffix)")
        replaceComposerWithSelection("Keyboard media \(suffix)\nSecond caption line\nThird caption line\nFourth caption line", stage: "fixture-multiline")
        replaceComposerWithSelection("Short replacement \(suffix)", stage: "fixture-short")
        print("UNYRI_COMPOSER_SELECTION_FIXTURE_COMPLETE")
    }

    func testComposerNativeSelectionWithMediaFixture() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_selectmedia_\(suffix)")
        replace(multilineField("chat-composer-text"), with: "Keyboard retained draft \(suffix)\nline two")
        app.buttons["Attach photo or file"].tap()
        if app.buttons["Photo access"].waitForExistence(timeout: 2) {
            app.buttons["Photo access"].tap()
            let names = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let local = app.buttons.matching(NSPredicate(format: "label IN %@", names)).firstMatch
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(NSPredicate(format: "label IN %@", names)).firstMatch
            keyboardWait("selection-fixture-photo-permission", timeout: 15) { local.exists || system.exists }
            (local.exists ? local : system).tap()
        }
        XCTAssertTrue(app.buttons.matching(identifier: "Photo").firstMatch.waitForExistence(timeout: 15))
        for count in 1...2 { selectGalleryAsset("gallery-select-photo", expectedCount: count) }
        app.buttons["gallery-send"].tap()
        keyboardWait("selection-fixture-media-tray") { self.app.scrollViews["composer-attachments"].exists }
        let caption = "Keyboard media \(suffix)"
        replaceComposerWithSelection(caption, stage: "media-fixture-caption")
        replaceComposerWithSelection(caption + "\nSecond caption line\nThird caption line\nFourth caption line", stage: "media-fixture-four-lines")
        assertKeyboardComposer(true, stage: "media-fixture-four-lines-geometry")
        replaceComposerWithSelection(caption, stage: "media-fixture-one-line")
        XCTAssertTrue(app.buttons["composer-remove-1"].exists)
        print("UNYRI_COMPOSER_MEDIA_SELECTION_FIXTURE_COMPLETE")
    }

    func testChatNativeBackSwipePreservesDraft() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_backswipe_\(suffix)")
        let text = "Retained native back-swipe draft \(suffix)"
        replace(multilineField("chat-composer-text"), with: text)
        let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
        origin.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.14, dy: 0.45)),
                     withVelocity: XCUIGestureVelocity(rawValue: 60), thenHoldForDuration: 0.3)
        XCTAssertTrue(app.buttons["Attach photo or file"].exists)
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, text)
        XCTAssertFalse(app.buttons["drawer-close"].exists)
        capture("chat-native-cancelled-pop-draft")
        for cycle in 1...3 {
            origin.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.45)))
            let menu = app.buttons["main-open-menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 10), "A full edge swipe must really return to the chat list")
            XCTAssertFalse(app.buttons["drawer-close"].exists)
            menu.tap()
            let saved = app.buttons.matching(NSPredicate(format: "label == %@", "Saved Messages")).firstMatch
            XCTAssertTrue(saved.waitForExistence(timeout: 5)); saved.tap()
            XCTAssertTrue(app.buttons["Attach photo or file"].waitForExistence(timeout: 10))
            XCTAssertEqual(multilineField("chat-composer-text").value as? String, text, "A cancelled or completed native Back transition cannot lose the draft")
            capture("chat-native-pop-reopen-retained-draft-\(cycle)")
        }
        print("UNYRI_NATIVE_BACK_SWIPE_QA_COMPLETE")
    }

    func testDrawerSwipesRespectNavigationAndScrolling() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_drawer_\(suffix)")
        let open = app.buttons["main-open-menu"]
        let close = app.buttons["drawer-close"]
        func drag(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
            app.coordinate(withNormalizedOffset: CGVector(dx: x1, dy: y1))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: x2, dy: y2)))
        }
        // A nested conversation must never open the root drawer. Back swipe is recorded separately.
        drag(0.012, 0.5, 0.47, 0.5)
        XCTAssertFalse(close.exists, "Conversation back navigation must not open the root drawer")
        let childSwipePopped = open.waitForExistence(timeout: 3)
        let childProof = XCTAttachment(string: "{\"childSwipePopped\":\(childSwipePopped),\"childOpenedDrawer\":false,\"scope\":\"drawer admission only; child Back swipe is a separate finding\"}")
        childProof.name = "drawer-child-gesture-observation.json"; childProof.lifetime = .keepAlways; add(childProof)
        if !childSwipePopped {
            let back = app.buttons["Back"]
            XCTAssertTrue(back.waitForExistence(timeout: 5) && back.isHittable)
            back.tap() // Explicit fixture bootstrap, never a claimed swipe success.
        }
        XCTAssertTrue(open.waitForExistence(timeout: 10), "Reach the root before testing its drawer")
        // Vertical motion at the left edge must remain ordinary scrolling.
        drag(0.012, 0.73, 0.018, 0.37)
        XCTAssertFalse(close.exists, "Vertical scrolling must not open the drawer")
        drag(0.012, 0.5, 0.48, 0.5)
        XCTAssertTrue(close.waitForExistence(timeout: 5), "One edge swipe must open the real drawer")
        // SwiftUI may omit a non-action container from AX or expose it with a
        // different role. Actual visible controls establish the panel lane.
        captureAccessibilityTree("drawer-edge-open-tree")
        let drawer = app.descendants(matching: .any).matching(identifier: "main-drawer").firstMatch
        if drawer.exists { XCTAssertLessThanOrEqual(drawer.frame.width, app.frame.width * 0.62) }
        for control in [close, app.buttons["drawer-settings"]] {
            XCTAssertTrue(control.exists && control.isHittable)
            XCTAssertGreaterThanOrEqual(control.frame.minX, app.frame.minX)
            XCTAssertLessThanOrEqual(control.frame.maxX, app.frame.minX + app.frame.width * 0.62)
        }
        capture("drawer-edge-open")
        drag(0.42, 0.5, 0.025, 0.5)
        waitUntil("One reverse swipe closes the drawer", timeout: 5) { !close.exists }
        open.tap(); XCTAssertTrue(close.waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.86, dy: 0.5)).tap()
        waitUntil("Backdrop closes the drawer", timeout: 5) { !close.exists }
        open.tap(); XCTAssertTrue(close.waitForExistence(timeout: 5))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        XCTAssertFalse(close.exists, "Returning from background must not reopen a stale drag or menu")
        open.tap(); XCTAssertTrue(close.waitForExistence(timeout: 5)); close.tap()
        waitUntil("Close button remains usable after background", timeout: 5) { !close.exists }
        capture("drawer-gesture-roundtrip")
        print("UNYRI_DRAWER_GESTURES_QA_COMPLETE")
    }

    /// Repeated real row taps against the shipped settings UI. No setting is
    /// injected; each new selected trait must follow exactly one actual tap.
    func testQuickReactionRowsRespondToSingleTapAndPersist() throws {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let username = "uiqa_quickreaction_\(suffix)"
        print("UNYRI_QUICK_REACTION_QA_BEGIN \(username)")
        registerKeyboardAccount(username)
        XCTAssertTrue(app.buttons["Attach photo or file"].exists)
        app.buttons["Back"].tap()
        let menu = app.buttons["Open menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        let settings = app.buttons["drawer-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5)); settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        let chats = app.buttons["settings-chats"]
        screenAuditReach(chats, "quick-reaction-chats-category"); chats.tap()
        XCTAssertTrue(app.navigationBars["Chats"].waitForExistence(timeout: 10))
        screenAuditOpen("Quick reaction", title: "Quick reaction")

        // Exact labels from CocoQuickReactionPolicy and its Off row. Do not
        // substitute element order if an emoji row is absent or ambiguous.
        let labels = ["👍", "❤️", "😂", "😮", "😢", "🙏", "Off"]
        func row(_ label: String) -> XCUIElement {
            let matches = app.buttons.matching(NSPredicate(format: "label == %@", label))
            XCTAssertEqual(matches.count, 1, "One actual quick-reaction row must expose " + label)
            return matches.element(boundBy: 0)
        }
        func selectedLabels() -> [String] {
            labels.filter { row($0).isSelected }
        }
        func record(_ event: [String: Any], name: String) {
            let data = try! JSONSerialization.data(withJSONObject: event, options: [.sortedKeys])
            let attachment = XCTAttachment(string: String(decoding: data, as: UTF8.self))
            attachment.name = name + ".json"; attachment.lifetime = .keepAlways; add(attachment)
            print("UNYRI_QUICK_REACTION_EVENT " + String(decoding: data, as: UTF8.self))
        }
        let initial = selectedLabels()
        XCTAssertEqual(initial.count, 1, "The real screen must expose exactly one stored selection")
        guard let original = initial.first, let originalIndex = labels.firstIndex(of: original) else {
            XCTFail("No unambiguous original quick reaction"); return
        }
        var proof: [[String: Any]] = []
        var trial = 0
        for position in ["emoji", "row-center", "trailing-checkmark"] {
            let current = selectedLabels()
            XCTAssertEqual(current.count, 1)
            guard let selected = current.first, let selectedIndex = labels.firstIndex(of: selected) else {
                XCTFail("No unique selected row before a tap round"); return
            }
            // Start at a different choice; even a pre-existing non-default
            // preference cannot turn a no-op into a false positive.
            let ordered = (1...labels.count).map { labels[(selectedIndex + $0) % labels.count] }
            for label in ordered {
                trial += 1
                let target = row(label)
                XCTAssertTrue(target.exists && target.isEnabled)
                let frame = target.frame, viewport = app.frame
                XCTAssertTrue(UnyriKeyboardTestGeometry.valid(frame) && viewport.contains(frame))
                XCTAssertGreaterThanOrEqual(frame.width, 120, "The whole row must own its tap area")
                XCTAssertGreaterThanOrEqual(frame.height, 44)
                XCTAssertFalse(target.isSelected, "Every trial must select a DIFFERENT reaction")
                // Shipped HStack uses16pt horizontal padding, a25pt emoji and
                // a20pt trailing checkmark. These points are measured from
                // the real row, never hard-coded screen coordinates.
                let localX: CGFloat = position == "emoji" ? 29 : position == "row-center" ? frame.width / 2 : frame.width - 26
                let point = CGPoint(x: frame.minX + localX, y: frame.midY)
                XCTAssertTrue(frame.contains(point) && viewport.contains(point))
                let start = ProcessInfo.processInfo.systemUptime
                record(["trial": trial, "phase": "before-one-tap", "position": position,
                        "choice": label, "selectedBefore": selectedLabels(), "tapCount": 1,
                        "axHittable": target.isHittable,
                        "row": ["x": Double(frame.minX), "y": Double(frame.minY), "width": Double(frame.width), "height": Double(frame.height)],
                        "point": ["x": Double(point.x), "y": Double(point.y)]], name: "quick-reaction-\(trial)-target")
                // A negative AX hit flag is evidence, not a substitute for
                // the actual user's tap. No second tap or fallback is used.
                target.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: localX, dy: frame.height / 2)).tap()
                waitUntil("Quick reaction \(label) must select after ONE \(position) tap", timeout: 4) { row(label).isSelected }
                XCTAssertEqual(selectedLabels(), [label], "Selection must move once, leaving no stale selected trait")
                let observedMs = (ProcessInfo.processInfo.systemUptime - start) * 1000

                // Recreate the settings destination through real navigation
                // after EVERY trial, not only after returning to a default.
                screenAuditBack(from: "Quick reaction", to: "Chats")
                screenAuditOpen("Quick reaction", title: "Quick reaction")
                XCTAssertEqual(selectedLabels(), [label], "The chosen reaction must persist after Back and reopening")
                let item: [String: Any] = ["trial": trial, "choice": label, "position": position,
                    "tapCount": 1, "selectedAfter": selectedLabels(), "persistedAfterReopen": true,
                    "observedTapAndAXMs": observedMs, "physicalFPSMeasured": false]
                proof.append(item); record(item, name: "quick-reaction-\(trial)-verified")
            }
            capture("quick-reaction-" + position); captureAccessibilityTree("quick-reaction-" + position + "-tree")
        }
        XCTAssertEqual(trial, 21)
        XCTAssertEqual(proof.count, 21)
        if selectedLabels() != [original] {
            let restore = row(original)
            restore.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            waitUntil("Restore the original quick reaction with one tap", timeout: 4) { row(original).isSelected }
            screenAuditBack(from: "Quick reaction", to: "Chats")
            screenAuditOpen("Quick reaction", title: "Quick reaction")
        }
        XCTAssertEqual(selectedLabels(), [labels[originalIndex]])
        record(["username": username, "trials": proof, "trialCount": 21,
                "positions": ["emoji", "row-center", "trailing-checkmark"], "originalRestored": true,
                "scope": "Native one-tap selected traits and Back/reopen preference persistence"], name: "quick-reaction-native-proof")
        capture("quick-reaction-complete"); captureAccessibilityTree("quick-reaction-complete-tree")
        print("UNYRI_QUICK_REACTION_QA_COMPLETE")
    }

    /// Independent menu audit against a fresh synthetic account. It deliberately
    /// cancels destructive confirmations and does not stand in for16/14 media QA.
    func testAuthenticatedMenusAndSettingsRemainUsable() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let menuGroup = ProcessInfo.processInfo.environment["UNYRI_UIQA_MENU_GROUP"] ?? "all"
        XCTAssertTrue(["all", "account-appearance", "privacy-connection", "help-history-search"].contains(menuGroup))
        print("UNYRI_SCREEN_AUDIT_BEGIN uiqa_screens_\(suffix) group=\(menuGroup)")
        registerKeyboardAccount("uiqa_screens_\(suffix)")
        XCTAssertTrue(app.buttons["Attach photo or file"].exists)
        app.buttons["Back"].tap()
        let menu = app.buttons["Open menu"]; XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        for label in ["Search", "People", "Chats", "Calls", "Saved Messages", "Settings", "Server", "Devices", "Help", "Report a problem", "Sign out of account"] {
            XCTAssertTrue(screenAuditButton(label).exists, "Drawer must expose the real " + label + " action")
        }
        screenAuditCheckpoint("drawer")
        screenAuditReach(screenAuditButton("Settings"), "drawer-settings"); screenAuditButton("Settings").tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        screenAuditCheckpoint("settings-root")

        if menuGroup == "all" || menuGroup == "account-appearance" {
        screenAuditOpen("Account", title: "Account")
        screenAuditOpen("Edit profile", title: "Edit Profile")
        for label in ["Display name", "Username", "Bio"] {
            let field = app.textFields[label].exists ? app.textFields[label] : app.textViews[label]
            screenAuditReach(field, "profile-" + label)
        }
        screenAuditReach(screenAuditButton("Gallery"), "profile-gallery")
        screenAuditReach(screenAuditButton("Save"), "profile-save")
        screenAuditCheckpoint("profile-editor", controls: [app.textFields["Display name"], screenAuditButton("Save")])
        // This editor is presented by Settings' root isShowingEdit destination,
        // not by Account's NavigationLink. Actual native Back returns Settings.
        screenAuditBack(from: "Edit Profile", to: "Settings") // Leave profile unchanged.
        screenAuditOpen("Account", title: "Account")
        screenAuditOpen("Server connection", title: "Server connection")
        screenAuditReach(screenAuditButton("Check connection"), "check-server"); screenAuditButton("Check connection").tap()
        XCTAssertTrue(exactMessageText("Server is reachable").waitForExistence(timeout: 15))
        screenAuditCheckpoint("server-connection"); screenAuditBack(from: "Server connection", to: "Account")
        screenAuditOpen("Server card", title: "Server card")
        XCTAssertTrue(exactMessageText("Server name").waitForExistence(timeout: 15), "Server card must finish loading real data")
        screenAuditCheckpoint("server-card"); screenAuditBack(from: "Server card", to: "Account")
        screenAuditOpen("Account recovery", title: "Account recovery")
        screenAuditReach(screenAuditButton("Create a new recovery code"), "account-recovery", enabled: false)
        screenAuditCheckpoint("account-recovery"); screenAuditBack(from: "Account recovery", to: "Account")
        screenAuditOpen("Delete account", title: "Delete account")
        let accountPassword = app.secureTextFields["Account password"]
        screenAuditReach(accountPassword, "delete-password")
        accountPassword.tap(); accountPassword.typeText("uiqa-keyboard-fixture-123")
        let delete = screenAuditButton("Delete account permanently"); screenAuditReach(delete, "delete-confirmation"); delete.tap()
        XCTAssertTrue(app.alerts["Delete account permanently?"].waitForExistence(timeout: 5))
        screenAuditCheckpoint("delete-account-confirmation")
        app.alerts.buttons["Cancel"].tap()
        screenAuditBack(from: "Delete account", to: "Account"); screenAuditBack(from: "Account", to: "Settings")

        screenAuditOpen("Appearance", title: "Appearance")
        let theme = app.buttons["chat-appearance-open"]; screenAuditReach(theme, "theme-editor"); theme.tap()
        XCTAssertTrue(app.navigationBars["Theme appearance"].waitForExistence(timeout: 10))
        for id in ["chat-appearance-preview-messages", "chat-appearance-preview-attachments"] {
            let button = app.buttons[id]; screenAuditReach(button, id); button.tap()
        }
        let cancelTheme = app.buttons["chat-appearance-cancel"], applyTheme = app.buttons["chat-appearance-apply"]
        screenAuditReach(cancelTheme, "theme-cancel"); screenAuditReach(applyTheme, "theme-apply")
        XCTAssertFalse(cancelTheme.frame.intersects(applyTheme.frame), "The independent theme footer actions must not overlap")
        screenAuditCheckpoint("theme-editor", controls: [cancelTheme, applyTheme]); cancelTheme.tap()
        XCTAssertTrue(app.navigationBars["Appearance"].waitForExistence(timeout: 10))
        let language = screenAuditButton("Language")
        screenAuditReach(language, "language-picker"); language.tap()
        let english = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "English")).firstMatch
        XCTAssertTrue(english.waitForExistence(timeout: 5)); english.tap()
        XCTAssertTrue(language.label.contains("English") || (language.value as? String ?? "").contains("English"))
        language.tap()
        let systemLanguage = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "System default")).firstMatch
        XCTAssertTrue(systemLanguage.waitForExistence(timeout: 5)); systemLanguage.tap()
        screenAuditCheckpoint("language-picker-roundtrip")
        screenAuditSwitchRoundtrip("Show Saved Messages in menu")
        screenAuditSwitchRoundtrip("Show Calls in menu")
        screenAuditCheckpoint("appearance-navigation"); screenAuditBack(from: "Appearance", to: "Settings")
        screenAuditOpen("Chats", title: "Chats"); screenAuditOpen("Quick reaction", title: "Quick reaction")
        let quickFooter = exactMessageText("Double-tap a message to react. In the photo viewer, double-tap still zooms.")
        reveal(quickFooter); XCTAssertTrue(quickFooter.isHittable)
        screenAuditCheckpoint("quick-reaction"); screenAuditBack(from: "Quick reaction", to: "Chats"); screenAuditBack(from: "Chats", to: "Settings")
        }

        if menuGroup == "all" || menuGroup == "privacy-connection" {
        let privacyCategory = app.buttons["settings-privacy"]
        screenAuditReach(privacyCategory, "privacy-category"); privacyCategory.tap()
        XCTAssertTrue(app.navigationBars["Privacy & Security"].waitForExistence(timeout: 12))
        screenAuditSwitchRoundtrip("Show my live draft"); screenAuditSwitchRoundtrip("Share typing and recording status")
        screenAuditCheckpoint("privacy-security")
        screenAuditOpen("Devices", title: "Devices")
        screenAuditReach(screenAuditButton("Refresh"), "devices-refresh"); screenAuditButton("Refresh").tap()
        XCTAssertTrue(exactMessageText("This device").waitForExistence(timeout: 15))
        screenAuditCheckpoint("devices"); screenAuditBack(from: "Devices", to: "Privacy & Security"); screenAuditBack(from: "Privacy & Security", to: "Settings")
        screenAuditOpen("Connection & storage", title: "Data & Storage")
        screenAuditSwitchRoundtrip("Download media automatically on Wi-Fi")
        screenAuditSwitchRoundtrip("Download media automatically on mobile data")
        screenAuditCheckpoint("automatic-download-settings")
        screenAuditOpen("Call quality", title: "Call quality")
        let saveData = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Save data")).firstMatch
        screenAuditReach(saveData, "call-quality-save-data"); saveData.tap()
        XCTAssertTrue(saveData.isSelected, "One call-quality tap must update its real selected trait")
        let auto = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", "Auto", "Auto,")).firstMatch
        screenAuditReach(auto, "call-quality-auto"); auto.tap(); XCTAssertTrue(auto.isSelected)
        screenAuditCheckpoint("call-quality"); screenAuditBack(from: "Call quality", to: "Data & Storage")
        screenAuditOpen("Storage Usage", title: "Storage")
        let clearCache = screenAuditButton("Clear Media Cache")
        XCTAssertTrue(clearCache.waitForExistence(timeout: 15)); screenAuditReach(clearCache, "storage-clear-confirmation"); clearCache.tap()
        XCTAssertTrue(app.alerts["Clear cached media?"].waitForExistence(timeout: 5))
        screenAuditCheckpoint("storage-clear-confirmation"); app.alerts.buttons["Cancel"].tap()
        screenAuditBack(from: "Storage", to: "Data & Storage"); screenAuditBack(from: "Data & Storage", to: "Settings")
        }

        if menuGroup == "all" || menuGroup == "help-history-search" {
        screenAuditOpen("Help & app", title: "About")
        screenAuditOpen("Report a problem", title: "Report a problem")
        let consent = app.switches["I agree to send this report and these screenshots"]
        screenAuditReach(consent, "report-consent")
        let sendReport = screenAuditButton("Send report"); screenAuditReach(sendReport, "report-send-without-consent")
        XCTAssertEqual(consent.value as? String, "0")
        sendReport.tap()
        XCTAssertTrue(exactMessageText("Describe the problem in at least 5 characters.").waitForExistence(timeout: 5))
        screenAuditCheckpoint("report-problem-footer", controls: [consent, sendReport])
        let description = app.textViews.firstMatch; screenAuditReach(description, "report-description")
        description.tap(); description.typeText("Synthetic screen audit draft; do not submit.")
        screenAuditReach(sendReport, "report-validate-consent"); sendReport.tap()
        XCTAssertTrue(exactMessageText("Turn on consent above to send your report and screenshots.").waitForExistence(timeout: 5))
        XCTAssertFalse(exactMessageText("Report received").exists, "Validation must not submit an unconsented report")
        screenAuditCheckpoint("report-problem-keyboard"); screenAuditBack(from: "Report a problem", to: "About")
        screenAuditOpen("Performance diagnostics", title: "Performance diagnostics")
        let recording = app.switches["performance-recording-toggle"]
        screenAuditReach(recording, "performance-recording")
        let recordingPrimitive = screenAuditSwitchPrimitive(recording)
        let wasRecording = recordingPrimitive.value as? String
        if wasRecording == "0" { recordingPrimitive.tap() }
        let mark = app.buttons["performance-mark-problem"]; screenAuditReach(mark, "performance-mark"); mark.tap()
        XCTAssertTrue(exactMessageText("Problem marked").exists || mark.label == "Problem marked")
        screenAuditCheckpoint("performance-diagnostics", controls: [recording, mark])
        if wasRecording == "0" { recordingPrimitive.tap() }
        screenAuditBack(from: "Performance diagnostics", to: "About")
        screenAuditOpen("About UNYRI", title: "About UNYRI")
        screenAuditCheckpoint("about-coco"); screenAuditOpen("User agreement", title: "User agreement")
        scrollVisible(up: true); scrollVisible(up: true)
        screenAuditCheckpoint("user-agreement"); screenAuditBack(from: "User agreement", to: "About UNYRI")
        screenAuditBack(from: "About UNYRI", to: "About"); screenAuditBack(from: "About", to: "Settings")

        screenAuditOpen("Transfer", title: "Transfer history by QR")
        let receiver = app.buttons["history-transfer-start-receive"]
        XCTAssertTrue(receiver.waitForExistence(timeout: 15)); screenAuditReach(receiver, "transfer-receiver"); receiver.tap()
        screenAuditReach(app.buttons["history-transfer-show-qr"], "transfer-receiver-next")
        screenAuditCheckpoint("transfer-receiver-entry"); app.buttons["history-transfer-done"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        screenAuditOpen("Backup", title: "History and backups")
        screenAuditCheckpoint("history-backup-entry")
        let refreshBackup = screenAuditButton("Refresh")
        screenAuditDiscoverLazyFooter(refreshBackup)
        screenAuditReach(refreshBackup, "backup-refresh")
        screenAuditCheckpoint("history-backup-footer"); screenAuditBack(from: "History and backups", to: "Settings")
        let logout = screenAuditButton("Sign out of account"); screenAuditReach(logout, "settings-bottom-logout"); logout.tap()
        let cancelLogout = app.buttons["Cancel"], backupLogout = screenAuditButton("History and backup")
        XCTAssertTrue(cancelLogout.waitForExistence(timeout: 8))
        screenAuditReach(cancelLogout, "logout-cancel"); screenAuditReach(backupLogout, "logout-backup")
        let confirmLogout = screenAuditButton("Sign out of account")
        screenAuditReach(confirmLogout, "logout-confirm")
        XCTAssertFalse(cancelLogout.frame.intersects(backupLogout.frame)); XCTAssertFalse(backupLogout.frame.intersects(confirmLogout.frame))
        screenAuditCheckpoint("logout-confirmation", controls: [cancelLogout, backupLogout, confirmLogout])
        cancelLogout.tap(); XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.textFields["Your @handle"].exists, "Cancelling logout must retain this synthetic session")
        screenAuditCheckpoint("logout-cancelled")

        let rootMenu = app.buttons["Open menu"]; XCTAssertTrue(rootMenu.waitForExistence(timeout: 8)); rootMenu.tap()
        screenAuditReach(screenAuditButton("Calls"), "drawer-calls"); screenAuditButton("Calls").tap()
        XCTAssertTrue(exactMessageText("No recent calls").waitForExistence(timeout: 10))
        screenAuditCheckpoint("calls-root")
        rootMenu.tap(); screenAuditReach(screenAuditButton("Search"), "drawer-search")
        screenAuditButton("Search").tap()
        let search = app.textFields["search-query"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); screenAuditReach(search, "search-field")
        search.tap(); search.typeText("no-match-" + String(suffix))
        // Root navigation also contains a Chats button. Target only the
        // uniquely identified search control and independently verify results.
        // Begin with Chats so the default All state cannot make a tap vacuous.
        for key in ["chats", "messages", "all"] {
            let matches = app.buttons.matching(identifier: "search-scope-" + key)
            XCTAssertEqual(matches.count, 1, "Each search scope must have one actual control")
            let scope = matches.firstMatch
            screenAuditReach(scope, "search-scope-" + key); scope.tap()
            waitUntil("Search must select " + key + " on one tap", timeout: 5) {
                scope.isSelected && (scope.value as? String) == "Selected"
                    && self.app.scrollViews["search-results-" + key].exists
            }
        }
        // Search is a root screen: the same drawer remains available while
        // editing, and People is a separate explicit server-directory entry.
        screenAuditReach(rootMenu, "search-open-menu"); rootMenu.tap()
        screenAuditReach(app.buttons["drawer-people"], "drawer-people"); app.buttons["drawer-people"].tap()
        XCTAssertTrue(app.scrollViews["search-results-people"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["search-scope-people"].exists)
        screenAuditReach(rootMenu, "people-open-menu"); rootMenu.tap()
        app.buttons["drawer-search"].tap()
        XCTAssertTrue(app.buttons["search-scope-all"].waitForExistence(timeout: 8))
        search.tap(); search.typeText("no-match-again")
        screenAuditCheckpoint("search-people-keyboard")
        screenAuditReach(app.buttons["search-clear-query"], "clear-search"); app.buttons["search-clear-query"].tap()
        XCTAssertFalse(app.buttons["search-clear-query"].exists)
        screenAuditReach(app.buttons["search-close"], "close-search"); app.buttons["search-close"].tap()
        XCTAssertTrue(rootMenu.waitForExistence(timeout: 10))
        screenAuditCheckpoint("search-closed")
        }
        print("UNYRI_MENU_GROUP_QA_COMPLETE \(menuGroup)")
        if menuGroup == "all" { print("UNYRI_ALL_SCREENS_QA_COMPLETE") }
    }

    /// Fast isolation of the exact native editing sequence used by the longer
    /// keyboard gate. This proves fixture selection, not all scroll anchors.
    func testComposerReplacementsPreserveExactCaption() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        print("UNYRI_CAPTION_QA_BEGIN \(suffix)")
        registerKeyboardAccount("uiqa_caption_\(suffix)")
        assertKeyboardComposer(false, stage: "caption-initial")
        let draft = "Keyboard retained draft \(suffix)\nline two"
        replaceComposerWithSelection(draft, stage: "caption-initial-draft")
        app.buttons["Attach photo or file"].tap()
        let photoAccess = app.buttons["Photo access"]
        if photoAccess.waitForExistence(timeout: 2) {
            photoAccess.tap()
            let labels = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let allowed = app.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            keyboardWait("caption-photo-permission", timeout: 15) { allowed.exists || system.exists }
            (allowed.exists ? allowed : system).tap()
        }
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Photo")).firstMatch.waitForExistence(timeout: 15))
        for count in 1...2 { selectGalleryAsset("gallery-select-photo", expectedCount: count) }
        app.buttons["gallery-send"].tap()
        keyboardWait("caption-selected-media") { self.app.scrollViews["composer-attachments"].exists }
        let caption = "Keyboard media \(suffix)"
        replaceComposerWithSelection(caption, stage: "caption-single-line")
        let fourLines = caption + "\nSecond caption line\nThird caption line\nFourth caption line"
        replaceComposerWithSelection(fourLines, stage: "caption-four-lines")
        assertKeyboardComposer(true, stage: "caption-four-lines-visible")
        XCUIDevice.shared.orientation = .landscapeLeft
        assertKeyboardComposer(true, stage: "caption-rotated-portrait-mask")
        XCTAssertGreaterThan(app.frame.height, app.frame.width)
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines)
        app.buttons["Chat options"].tap()
        let close = chatOptionsHeaderCloseButton()
        keyboardWait("caption-menu-hidden") { self.observeNativeKeyboard().exposure == .hidden }
        close.tap(); assertKeyboardComposer(false, stage: "caption-closed")
        multilineField("chat-composer-text").tap(); assertKeyboardComposer(true, stage: "caption-reopened")
        XCUIDevice.shared.orientation = .portrait
        keyboardWait("caption-portrait-return") { self.app.frame.height > self.app.frame.width }
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines)
        replaceComposerWithSelection(caption, stage: "caption-final-single-line")
        app.buttons["chat-composer-send"].tap()
        keyboardWait("caption-media-admitted", timeout: 30) {
            !self.app.scrollViews["composer-attachments"].exists && self.exactMessageText(caption).exists
        }
        capture("caption-complete"); captureAccessibilityTree("caption-complete-tree")
        print("UNYRI_CAPTION_QA_COMPLETE")
    }

    /// This functional discriminator does not waive a false AX flag in the
    /// complete keyboard gate. It records that flag, then tests the real action.
    func testRotatedComposerAttachmentAction() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_rotated_\(suffix)")
        assertKeyboardComposer(false, stage: "rotated-action-initial")
        for index in 1...16 {
            let text = "Keyboard row \(index) \(suffix)\nRead older messages without changing this position.\nThe composer must stay above the native keyboard."
            replace(multilineField("chat-composer-text"), with: text)
            app.buttons["chat-composer-send"].tap()
            XCTAssertTrue(exactMessageText(text).waitForExistence(timeout: 5))
        }
        replaceComposerWithSelection("Keyboard retained draft \(suffix)\nline two", stage: "rotated-action-retained-draft")
        app.buttons["Attach photo or file"].tap()
        let photoAccess = app.buttons["Photo access"]
        if photoAccess.waitForExistence(timeout: 2) {
            photoAccess.tap()
            let labels = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let allowed = app.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            keyboardWait("rotated-action-photo-permission", timeout: 15) { allowed.exists || system.exists }
            (allowed.exists ? allowed : system).tap()
        }
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Photo")).firstMatch.waitForExistence(timeout: 15))
        for count in 1...2 { selectGalleryAsset("gallery-select-photo", expectedCount: count) }
        app.buttons["gallery-send"].tap()
        keyboardWait("rotated-action-tray") { self.app.scrollViews["composer-attachments"].exists }
        let caption = "Keyboard media \(suffix)"
        replaceComposerWithSelection(caption, stage: "rotated-action-caption")
        let fourLines = caption + "\nSecond caption line\nThird caption line\nFourth caption line"
        replaceComposerWithSelection(fourLines, stage: "rotated-action-four-lines")
        assertKeyboardComposer(true, stage: "rotated-action-before-menu")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertGreaterThan(app.frame.height, app.frame.width)
        app.buttons["Chat options"].tap()
        let close = chatOptionsHeaderCloseButton()
        keyboardWait("rotated-action-menu-hides-keyboard") { self.observeNativeKeyboard().exposure == .hidden }
        close.tap()
        keyboardWait("rotated-action-closed-and-hidden") {
            self.observeNativeKeyboard().exposure == .hidden && !self.app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "xmark", "Close")).firstMatch.exists
        }
        let controls = keyboardComposerControls
        XCTAssertTrue(UnyriKeyboardTestGeometry.issues(app: app.frame, keyboard: observeNativeKeyboard().bounds,
            controls: controls.map { $0.exists ? $0.frame : .zero }).isEmpty)
        let attach = app.buttons["Attach photo or file"]
        XCTAssertTrue(attach.exists && attach.isEnabled)
        XCTAssertTrue(app.frame.contains(attach.frame))
        XCTAssertTrue(multilineField("chat-composer-text").isHittable && app.buttons["chat-composer-send"].isHittable)
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines)
        keyboardGeometryEvidence("rotated-action-before-real-tap")
        capture("rotated-action-before-real-tap"); captureAccessibilityTree("rotated-action-before-real-tap-tree")
        let proof = XCTAttachment(string: "{\"attachAXHittableBeforeTap\":\(attach.isHittable),\"attachEnabled\":\(attach.isEnabled),\"seedMessages\":16,\"selectedAttachments\":2,\"scope\":\"Real visible-control action; does not replace full-keyboard AX assertion\"}")
        proof.name = "rotated-attachment-action-proof.json"; proof.lifetime = .keepAlways; add(proof)
        attach.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.scrollViews["gallery-grid"].waitForExistence(timeout: 10), "One measured visible-paperclip tap must actually open the gallery")
        capture("rotated-action-gallery-opened"); captureAccessibilityTree("rotated-action-gallery-opened-tree")
        let galleryClose = app.buttons.matching(NSPredicate(format: "label == %@", "Close"))
        XCTAssertEqual(galleryClose.count, 1)
        let galleryCloseButton = galleryClose.element(boundBy: 0)
        XCTAssertTrue(galleryCloseButton.isHittable); galleryCloseButton.tap()
        keyboardWait("rotated-action-gallery-cancelled") { !self.app.scrollViews["gallery-grid"].exists }
        XCTAssertTrue(app.buttons["composer-remove-0"].exists && app.buttons["composer-remove-1"].exists)
        XCTAssertFalse(app.buttons["composer-remove-2"].exists)
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines)
        XCUIDevice.shared.orientation = .portrait
        capture("rotated-action-preserved"); captureAccessibilityTree("rotated-action-preserved-tree")
        print("UNYRI_ROTATED_ATTACHMENT_QA_COMPLETE")
    }

    func testComposerInteractiveDismissReopensWithTouchOnly() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        registerKeyboardAccount("uiqa_touchkeys_\(suffix)")
        let field = multilineField("chat-composer-text")
        let rows = (1...6).map { "Touch keyboard \($0) \(suffix)\nKeep this message readable during a native keyboard gesture." }
        for text in rows {
            field.tap(); field.typeText(text)
            XCTAssertEqual(field.value as? String, text)
            app.buttons["chat-composer-send"].tap()
            keyboardWait("touch-only-message-arrival") { self.exactMessageText(text).exists }
        }
        let draft = "Touch-only retained draft \(suffix)"
        field.tap(); field.typeText(draft)
        for cycle in 1...3 {
            assertKeyboardComposer(true, stage: "touch-only-\(cycle)-shown")
            hideKeyboardInteractively("touch-only-\(cycle)-hide")
            returnToKeyboardBottom(rows.last!, stage: "touch-only-\(cycle)-latest")
            field.tap()
            assertKeyboardComposer(true, stage: "touch-only-\(cycle)-reopened")
            XCTAssertEqual(field.value as? String, draft)
        }
        capture("touch-only-keyboard-three-reopen-cycles")
        print("UNYRI_TOUCH_ONLY_KEYBOARD_COMPLETE composerCommandKeysUsed=false reopenCycles=3")
    }

    /// An independent, bounded gate; normal media QA still checks exactly
    /// 16 messages/14 attachments. No real account or injected UI state is used.
    func testKeyboardComposerBoundsAndScrollAnchors() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let username = "uiqa_keyboard_\(suffix)"
        print("UNYRI_KEYBOARD_QA_BEGIN \(username)")
        let proof = XCTAttachment(string: "{\"username\":\"\(username)\",\"seedTextMessages\":16,\"mediaPhotos\":2,\"bottomKeyboardCycles\":3,\"historyKeyboardCycles\":2,\"incomingPeerVerified\":false,\"strictMediaScenario\":false,\"fpsMeasured\":false}")
        proof.name = "keyboard-native-proof.json"; proof.lifetime = .keepAlways; add(proof)
        registerKeyboardAccount(username)
        assertKeyboardComposer(false, stage: "initial")
        let rows = (1...16).map { index in
            "Keyboard row \(index) \(suffix)\nRead older messages without changing this position.\nThe composer must stay above the native keyboard."
        }
        for (index, text) in rows.enumerated() {
            replace(multilineField("chat-composer-text"), with: text)
            assertKeyboardComposer(true, stage: "seed-\(index)-focused")
            let send = app.buttons["chat-composer-send"]; XCTAssertTrue(send.isEnabled); send.tap()
            assertKeyboardTail(text, stage: "seed-\(index)-arrival")
        }
        let tail = rows.last!
        let draft = "Keyboard retained draft \(suffix)"
        replace(multilineField("chat-composer-text"), with: draft)
        for cycle in 1...3 {
            let field = multilineField("chat-composer-text")
            if observeNativeKeyboard().exposure == .hidden { field.tap() }
            assertKeyboardComposer(true, stage: "bottom-\(cycle)-show")
            assertKeyboardTail(tail, stage: "bottom-\(cycle)-show")
            if cycle == 1 {
                field.typeText("\nline two")
                XCTAssertTrue((field.value as? String ?? "").contains("\nline two"), "Return is a multiline composer action")
                assertKeyboardComposer(true, stage: "return-keeps-editing")
            }
            hideKeyboardInteractively("bottom-\(cycle)-hide")
            assertPostPanPositionStable(rows, stage: "bottom-\(cycle)-post-pan")
            returnToKeyboardBottom(tail, stage: "bottom-\(cycle)-explicit-latest")
            XCTAssertTrue((field.value as? String ?? "").contains(draft), "Keyboard dismissal must preserve the draft")
        }

        // Separate keyboard resizing from a deliberate history pan. Opening
        // options dismisses focus without moving a finger through the list.
        multilineField("chat-composer-text").tap()
        assertKeyboardComposer(true, stage: "bottom-focus-only-show")
        assertKeyboardTail(tail, stage: "bottom-focus-only-before")
        app.buttons["Chat options"].tap()
        let focusOnlyClose = chatOptionsHeaderCloseButton()
        keyboardWait("bottom-focus-only-menu-hidden") { self.observeNativeKeyboard().exposure == .hidden }
        focusOnlyClose.tap()
        assertKeyboardComposer(false, stage: "bottom-focus-only-hidden")
        assertKeyboardTail(tail, stage: "bottom-focus-only-retains-tail")

        // Move to older content with a real scroll. Choose an upper visible
        // message that will still fit after the keyboard reduces the viewport.
        recordKeyboardHistoryFixture(rows, stage: "before-history-scroll")
        var anchorText: String?
        for _ in 0..<7 {
            let viewport = keyboardChatViewport
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX, dy: viewport.minY + 42 - app.frame.minY))
            let end = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX, dy: min(viewport.maxY - 20, viewport.minY + 242) - app.frame.minY))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: XCUIGestureVelocity(rawValue: 140), thenHoldForDuration: 0.3)
            if app.buttons["Latest messages"].exists {
                anchorText = rows.first { text in
                    let item = exactMessageText(text)
                    return item.exists && item.isHittable && item.frame.minY > viewport.minY + 8 && item.frame.maxY < viewport.minY + 235
                }
            }
            if anchorText != nil { break }
        }
        capture("keyboard-scrolled-history"); captureAccessibilityTree("keyboard-scrolled-history-tree")
        XCTAssertNotNil(anchorText, "The test must actually read older messages, not exercise bottom-following again")
        guard let anchorText else { return }
        recordKeyboardHistoryFixture(rows, stage: "chosen-history-anchor", anchor: anchorText)
        let anchor = exactMessageText(anchorText)
        for cycle in 1...2 {
            let before = anchor.frame, viewport = app.scrollViews["chat-timeline"].frame
            multilineField("chat-composer-text").tap()
            assertKeyboardComposer(true, stage: "history-\(cycle)-show")
            assertKeyboardAnchor(anchorText, before: before, viewport: viewport, stage: "history-\(cycle)-show")
            XCTAssertTrue(app.buttons["Latest messages"].exists, "Showing the keyboard must not jump to the latest message")
            if cycle == 1 {
                // This publishes a pending reaction and its server ACK while
                // the user reads older history; it is not an incoming mock.
                let original = anchor.frame, originalViewport = app.scrollViews["chat-timeline"].frame
                anchor.doubleTap()
                let reaction = app.buttons.matching(identifier: "message-reaction-👍").firstMatch
                XCTAssertTrue(reaction.waitForExistence(timeout: 10))
                assertKeyboardComposer(true, stage: "history-reaction-settled")
                assertKeyboardAnchor(anchorText, before: original, viewport: originalViewport, stage: "history-reaction-snapshot")
                reaction.tap()
                keyboardWait("history-reaction-remove") { !reaction.exists }
                assertKeyboardComposer(true, stage: "history-reaction-removal-settled")
                assertKeyboardAnchor(anchorText, before: original, viewport: originalViewport, stage: "history-reaction-removed")
                app.buttons["Chat options"].tap()
                let historyOptionsClose = chatOptionsHeaderCloseButton()
                keyboardWait("options-dismiss-keyboard") { self.observeNativeKeyboard().exposure == .hidden }
                historyOptionsClose.tap()
                assertKeyboardComposer(false, stage: "history-options-dismissed")
                assertKeyboardAnchor(anchorText, before: original, viewport: originalViewport, stage: "history-options-anchor")
            } else {
                hideKeyboardInteractively("history-interactive-hide")
                XCTAssertTrue(anchor.exists && keyboardChatViewport.intersects(anchor.frame), "Interactive dismissal keeps the same history readable")
                let settled = anchor.frame, settledViewport = app.scrollViews["chat-timeline"].frame
                // Observe the post-gesture position separately from the
                // deliberate finger movement; this is not an FPS assertion.
                for sample in 1...3 {
                    Thread.sleep(forTimeInterval: 0.2)
                    assertKeyboardAnchor(anchorText, before: settled, viewport: settledViewport, stage: "history-post-dismiss-\(sample)")
                }
            }
        }

        let latest = app.buttons["Latest messages"]; XCTAssertTrue(latest.exists); latest.tap()
        assertKeyboardTail(tail, stage: "return-to-bottom")
        app.buttons["Attach photo or file"].tap()
        let photoAccess = app.buttons["Photo access"]
        if photoAccess.waitForExistence(timeout: 2) {
            photoAccess.tap()
            let labels = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let allowed = app.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            keyboardWait("photo-permission", timeout: 15) { allowed.exists || system.exists }
            (allowed.exists ? allowed : system).tap()
        }
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Photo")).firstMatch.waitForExistence(timeout: 15))
        for count in 1...2 { selectGalleryAsset("gallery-select-photo", expectedCount: count) }
        app.buttons["gallery-send"].tap()
        keyboardWait("media-composer") { self.app.scrollViews["composer-attachments"].exists }
        let caption = "Keyboard media \(suffix)"
        replaceComposerWithSelection(caption, stage: "media-caption-set")
        assertKeyboardComposer(true, stage: "media-caption-keyboard")
        let strip = app.scrollViews["composer-attachments"]
        XCTAssertTrue(app.frame.contains(strip.frame), "The selected-media strip remains inside the app")
        XCTAssertLessThanOrEqual(strip.frame.maxY, multilineField("chat-composer-text").frame.minY + 2)
        let fourLines = caption + "\nSecond caption line\nThird caption line\nFourth caption line"
        replaceComposerWithSelection(fourLines, stage: "media-four-lines-set")
        assertKeyboardComposer(true, stage: "media-four-lines-portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertEqual(XCUIDevice.shared.orientation, .landscapeLeft)
        // Ordinary chat intentionally supports portrait only. Media viewer
        // landscape has its own lease and remains covered by the full gate.
        assertKeyboardComposer(true, stage: "rotated-device-portrait-chat")
        XCTAssertGreaterThan(app.frame.height, app.frame.width, "A chat without a media-viewer lease remains portrait")
        capture("keyboard-rotated-device-portrait-chat")
        XCTAssertTrue(app.frame.contains(strip.frame), "Rotation must not push the selected-media strip outside the app")
        XCTAssertGreaterThanOrEqual(strip.frame.minY, app.buttons["Chat options"].frame.maxY,
                                    "The strip must not cover the chat header")
        XCTAssertLessThanOrEqual(strip.frame.maxY, multilineField("chat-composer-text").frame.minY + 2)
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines)
        // Exercise real focus dismissal while the physical device is rotated;
        // do not demand an unsupported landscape composer from the app.
        app.buttons["Chat options"].tap()
        let rotatedOptionsClose = chatOptionsHeaderCloseButton()
        keyboardWait("rotated-menu-hides-keyboard") { self.observeNativeKeyboard().exposure == .hidden }
        rotatedOptionsClose.tap()
        assertKeyboardComposer(false, stage: "rotated-keyboard-hidden", expectedCaption: fourLines)
        multilineField("chat-composer-text").tap()
        assertKeyboardComposer(true, stage: "rotated-keyboard-reopened")
        XCUIDevice.shared.orientation = .portrait
        keyboardWait("portrait-return") { self.app.frame.height > self.app.frame.width }
        assertKeyboardComposer(true, stage: "media-portrait-return")
        XCTAssertEqual(multilineField("chat-composer-text").value as? String, fourLines,
                       "Rotation and keyboard dismissal must preserve the unsent caption")
        replaceComposerWithSelection(caption, stage: "media-caption-restored-set")
        assertKeyboardComposer(true, stage: "media-caption-restored")
        app.buttons["chat-composer-send"].tap()
        keyboardWait("media-admitted", timeout: 30) { !self.app.scrollViews["composer-attachments"].exists && self.exactMessageText(caption).exists }
        assertKeyboardComposer(true, stage: "media-admission-keyboard")
        assertKeyboardTail(caption, stage: "media-admission-bottom")
        hideKeyboardInteractively("media-final-hide")
        assertPostPanPositionStable(rows + [caption], stage: "media-final-post-pan")
        returnToKeyboardBottom(caption, stage: "media-final-explicit-latest")
        keyboardGeometryEvidence("complete"); capture("keyboard-complete")
        print("UNYRI_KEYBOARD_QA_COMPLETE")
    }

    func testRegisterSavedTextAndTenGalleryPhotos() {
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16)
        let username = "uiqa_native_\(suffix)"
        let marker = "COCO UIQA text \(username)"
        let caption = "COCO UIQA album \(username)"
        let fixture = XCTAttachment(string: "{\"username\":\"\(username)\",\"text\":\"\(marker)\",\"caption\":\"\(caption)\",\"expectedPhotos\":10,\"expectedVideos\":3,\"expectedOriginals\":1,\"expectedMessages\":16}")
        fixture.name = "native-ui-proof.json"
        fixture.lifetime = .keepAlways
        add(fixture)

        let serverButton = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(serverButton)
        serverButton.tap()
        setPublicTestServer()
        app.buttons["Done"].tap()
        XCTAssertFalse(app.textFields[serverPlaceholder].waitForExistence(timeout: 1))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", loopbackServer)).firstMatch.exists)
        capture("authenticated-loopback-selection")

        reveal(app.buttons["Create an account"])
        app.buttons["Create an account"].tap()
        replace(app.textFields["Your name"], with: "Synthetic UI QA")
        submitKeyboard("next")
        replace(app.textFields["Choose an @handle"], with: username)
        // Choose manual entry using the actual Show password action. Preserve
        // native strong-password suggestions for the normal hidden mode.
        // Do this before advancing focus into SecureField: its native strong
        // password suggestion may temporarily cover the toggle's visible point.
        let showPassword = app.buttons["Show password"]
        reveal(showPassword); showPassword.tap()
        XCTAssertTrue(app.buttons["Hide password"].waitForExistence(timeout: 5))
        let password = app.textFields["Password"]
        replace(password, with: "uiqa-only-fixture-123")
        XCTAssertEqual(password.value as? String, "uiqa-only-fixture-123")
        password.typeText("\n")
        let confirmation = app.textFields["Confirm password"]
        replace(confirmation, with: "uiqa-only-fixture-123")
        XCTAssertEqual(confirmation.value as? String, "uiqa-only-fixture-123")
        confirmation.typeText("\n")
        app.buttons["Hide password"].tap()
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        let agreement = app.switches["I accept the alpha test agreement"]
        reveal(agreement)
        agreement.tap()
        let register = app.buttons["Register"]
        reveal(register)
        XCTAssertTrue(register.isEnabled)
        capture("authenticated-registration-ready")
        register.tap()

        let historyChoice = app.buttons["Start without restored history"]
        let saved = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Saved Messages")).firstMatch
        waitUntil("Registration must reach history choice or chats") { historyChoice.exists || self.app.buttons["Open menu"].exists }
        if historyChoice.exists {
            capture("authenticated-history-choice")
            historyChoice.tap()
        }
        let menu = app.buttons["Open menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30))
        capture("authenticated-chats")
        menu.tap()
        let settings = app.buttons.matching(identifier: "Settings").firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        verifyAppearanceRoundTrip()
        let signOut = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Sign out of account")).firstMatch
        reveal(signOut)
        XCTAssertTrue(signOut.isHittable, "Last settings action must be reachable above the system safe area")
        capture("authenticated-settings-bottom")
        let help = app.buttons["settings-help"]
        reveal(help); help.tap()
        let diagnostics = app.buttons["Performance diagnostics"]
        XCTAssertTrue(diagnostics.waitForExistence(timeout: 5)); diagnostics.tap()
        let recording = app.switches["performance-recording-toggle"]
        XCTAssertTrue(recording.waitForExistence(timeout: 5))
        XCTAssertEqual(recording.value as? String, "0", "Performance recording must be opt-in")
        // The outer AX Switch spans the whole Form row. Tap the actual
        // trailing UISwitch, rather than the non-interactive text/spacing.
        recording.coordinate(withNormalizedOffset: CGVector(dx: 0.90, dy: 0.5)).tap()
        XCTAssertEqual(recording.value as? String, "1", "The visible recording switch must turn on")
        let online = app.switches["performance-online-toggle"]
        XCTAssertTrue(online.waitForExistence(timeout: 5))
        XCTAssertEqual(online.value as? String, "0", "Local recording must not enable uploads")
        let mark = app.buttons["performance-mark-problem"]
        reveal(mark); mark.tap()
        XCTAssertEqual(mark.label, "Problem marked")
        capture("authenticated-diagnostics-enabled")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        menu.tap()
        let calls = app.buttons.matching(identifier: "Calls").firstMatch
        XCTAssertTrue(calls.waitForExistence(timeout: 5))
        calls.tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        capture("authenticated-drawer")
        saved.tap()
        XCTAssertTrue(app.buttons["Attach photo or file"].waitForExistence(timeout: 30))
        replace(multilineField("Message"), with: marker)
        app.buttons["Send message"].tap()
        XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 30))
        waitUntil("Synthetic text must reach a terminal native send status") { self.terminalStatusVisible() }
        verifyFirstTapReactions(to: marker)
        XCTAssertFalse(app.staticTexts.matching(identifier: "Failed to send").firstMatch.exists)
        capture("authenticated-saved-text-sent")

        app.buttons["Attach photo or file"].tap()
        let photoAccess = app.buttons["Photo access"]
        if photoAccess.waitForExistence(timeout: 3) {
            photoAccess.tap()
            let fullAccessLabels = ["Allow Full Access", "Allow Access to All Photos", "Allow All Photos"]
            let fullAccess = app.buttons.matching(NSPredicate(format: "label IN %@", fullAccessLabels)).firstMatch
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let systemFullAccess = springboard.buttons.matching(NSPredicate(format: "label IN %@", fullAccessLabels)).firstMatch
            waitUntil("The native Photos permission prompt must offer full access to this synthetic library", timeout: 15) {
                fullAccess.exists || systemFullAccess.exists
            }
            (fullAccess.exists ? fullAccess : systemFullAccess).tap()
        }
        XCTAssertTrue(app.buttons.matching(identifier: "Photo").firstMatch.waitForExistence(timeout: 15), "Fixture photos must load in the real gallery")
        capture("authenticated-gallery-fixtures")
        // Add photos without waiting for these videos to finish uploading.
        for index in 1...2 {
            selectGalleryAsset("gallery-select-video", expectedCount: index)
        }
        let videoSend = app.buttons["gallery-send"]
        reveal(videoSend); videoSend.tap()
        waitUntil("Both videos enter the composer") { self.app.scrollViews["composer-attachments"].exists }
        XCTAssertTrue(app.buttons["composer-remove-1"].exists)
        app.buttons["chat-composer-send"].tap()
        waitUntil("Two-video admission releases the composer") {
            !self.app.scrollViews["composer-attachments"].exists && self.app.buttons["Attach photo or file"].isEnabled
        }
        app.buttons["Attach photo or file"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "Photo").firstMatch.waitForExistence(timeout: 15))
        for index in 1...10 {
            selectGalleryAsset("gallery-select-photo", expectedCount: index)
        }
        let send = app.buttons["gallery-send"]
        XCTAssertTrue(send.exists && send.isEnabled)
        XCTAssertTrue(send.label.contains("10"), "Gallery must retain all ten selections")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Continue")).firstMatch.exists)
        reveal(send)
        XCTAssertTrue(send.isEnabled)
        send.tap()
        waitUntil("Selection moves into the composer before sending") {
            !self.app.buttons["gallery-send"].exists && self.app.buttons["Attach photo or file"].exists
        }
        XCTAssertTrue(app.scrollViews["composer-attachments"].exists)
        XCTAssertTrue(app.buttons["composer-remove-9"].exists, "All ten selected items remain removable")
        replace(multilineField("chat-composer-text"), with: caption)
        capture("authenticated-ten-photo-composer-caption")
        app.buttons["chat-composer-send"].tap()
        waitUntil("Sending releases the composer selection") { !self.app.scrollViews["composer-attachments"].exists }
        XCTAssertFalse(app.buttons["Media draft"].exists, "Direct sending must not require a media draft action")
        XCTAssertFalse(app.navigationBars["Selected: 10"].exists, "Direct sending must not open a second review screen")
        let scrollingDuringSend = Date()
        let sendingTimeline = app.scrollViews["chat-timeline"]
        for _ in 0..<3 { sendingTimeline.swipeDown(); sendingTimeline.swipeUp() }
        let sendingTiming = XCTAttachment(string: "UI automation scroll sequence while sending: \(Date().timeIntervalSince(scrollingDuringSend)) seconds. This includes gesture/idle waits and is not a frame-time measurement.")
        sendingTiming.name = "mixed-media-send-scroll-timing"; sendingTiming.lifetime = .keepAlways; add(sendingTiming)
        capture("authenticated-two-videos-ten-photos-scrolled")
        let latest = app.buttons["Latest messages"]
        if latest.exists && latest.isHittable { latest.tap() }
        waitUntil("The latest photo must finish sending without a visible pending or failed state") {
            self.terminalStatusVisible() && !self.app.staticTexts.matching(identifier: "Sending…").firstMatch.exists &&
                !self.app.images.matching(identifier: "Sending…").firstMatch.exists &&
                !self.app.staticTexts.matching(identifier: "Failed to send").firstMatch.exists &&
                !self.app.images.matching(identifier: "Failed to send").firstMatch.exists &&
                !self.app.buttons.matching(identifier: "Cancel sending").firstMatch.exists
        }
        capture("authenticated-ten-photo-batch-sent")
        // Captions now belong to the first item. Reveal that part of the batch
        // explicitly; a lazy list's last visible row is not the caption owner.
        revealChatContent(exactMessageText(caption))
        XCTAssertTrue(exactMessageText(caption).exists, "The first photo must preserve the inline album caption")
        capture("authenticated-ten-photo-caption-preserved")
        let captionText = exactMessageText(caption)
        revealChatContent(captionText)
        captionText.press(forDuration: 0.6)
        XCTAssertTrue(app.buttons["Reply"].waitForExistence(timeout: 10), "Long press must open the message menu")
        XCTAssertFalse(app.buttons["Actions"].exists, "Long press must not also open the media viewer")
        assertMenuFixturePreview(.photo)
        capture("authenticated-photo-long-press-preview")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).tap()
        waitUntil("Outside tap dismisses the message menu", timeout: 10) { !self.app.buttons["Reply"].exists }
        revealChatContent(captionText)
        captureAccessibilityTree("authenticated-before-first-photo-lookup-tree")
        let albumPhotos = chatMediaElements(.photo)
        XCTAssertFalse(albumPhotos.isEmpty, "The decoded first photo must have an image, exact tile identifier or semantic Photo element")
        guard let firstAlbumPhoto = albumPhotos.first else { return }
        revealChatContent(firstAlbumPhoto)
        XCTAssertTrue(firstAlbumPhoto.isHittable, "First tile of the complete ten-photo album must be reachable")
        firstAlbumPhoto.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["Actions"].waitForExistence(timeout: 10), "A single media tap must open the photo viewer")
        capture("authenticated-photo-viewer-open")
        XCTAssertTrue(app.images["media-viewer-photo"].waitForExistence(timeout: 10), "The selected viewer page must contain an actual visible photo image")
        let pageCounter = app.staticTexts["media-page-counter"]
        XCTAssertEqual(pageCounter.label, "3 / 12", "The clicked attachment, not a URL fallback, must be selected")
        let swipeStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.82, dy: 0.45))
        let swipeEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.18, dy: 0.45))
        swipeStart.press(forDuration: 0.05, thenDragTo: swipeEnd)
        waitUntil("Swiping selects the next photo") { pageCounter.label == "4 / 12" }
        XCTAssertTrue(app.images["media-viewer-photo"].waitForExistence(timeout: 10))
        capture("authenticated-photo-viewer-second-page")
        swipeEnd.press(forDuration: 0.05, thenDragTo: swipeStart)
        waitUntil("Swiping back returns to the first photo") { pageCounter.label == "3 / 12" }
        let firstPhotoID = pageCounter.value as? String
        print("UNYRI_QA_MIXED_PAGING_BEGIN")
        Thread.sleep(forTimeInterval: 1)
        // Crossing media types must preserve physical direction and identity.
        for pass in 0..<3 {
            swipeEnd.press(forDuration: 0.05, thenDragTo: swipeStart)
            pauseIncomingVideo()
            waitUntil("Right swipe selects preceding video", timeout: 10) { pageCounter.exists && pageCounter.label == "2 / 12" }
            let precedingVideoID = pageCounter.value as? String
            XCTAssertNotEqual(firstPhotoID, precedingVideoID)
            XCTAssertTrue(app.sliders["Playback position"].waitForExistence(timeout: 10))
            capture("mixed-paging-photo-to-video-\(pass)")
            if pass == 0 {
                swipeEnd.press(forDuration: 0.05, thenDragTo: swipeStart)
                pauseIncomingVideo()
                waitUntil("Right swipe also selects the preceding video", timeout: 10) { pageCounter.exists && pageCounter.label == "1 / 12" }
                XCTAssertNotEqual(pageCounter.value as? String, precedingVideoID)
                XCTAssertTrue(app.sliders["Playback position"].waitForExistence(timeout: 10))
                swipeStart.press(forDuration: 0.05, thenDragTo: swipeEnd)
                pauseIncomingVideo()
                waitUntil("Left swipe restores the second video's identity", timeout: 10) { pageCounter.exists && pageCounter.label == "2 / 12" }
                XCTAssertEqual(pageCounter.value as? String, precedingVideoID)
            }
            swipeStart.press(forDuration: 0.05, thenDragTo: swipeEnd)
            waitUntil("Left swipe returns to the same photo", timeout: 10) { pageCounter.exists && pageCounter.label == "3 / 12" }
            XCTAssertEqual(pageCounter.value as? String, firstPhotoID)
            XCTAssertTrue(app.images["media-viewer-photo"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.sliders["Playback position"].exists, "A departed video's controls must not remain above a photo")
            capture("mixed-paging-video-to-photo-\(pass)")
        }
        print("UNYRI_QA_MIXED_PAGING_END")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).doubleTap()
        capture("authenticated-photo-viewer-zoom")
        XCUIDevice.shared.press(.home); app.activate()
        XCTAssertTrue(app.images["media-viewer-photo"].waitForExistence(timeout: 10), "Foregrounding must retain the current photo")
        app.buttons["Close"].tap()
        waitUntil("Photo viewer closes and returns to chat", timeout: 10) {
            !self.app.buttons["Actions"].exists && self.app.buttons["Attach photo or file"].isHittable
        }
        capture("authenticated-photo-viewer-return")
        for (kind, original) in [("video", false), ("photo", true)] {
            app.buttons["Attach photo or file"].tap()
            selectGalleryAsset("gallery-select-\(kind)", expectedCount: 1)
            let extraCaption = "COCO UIQA \(original ? "original" : "video") \(username)"
            capture("authenticated-\(kind)-\(original ? "original" : "compressed")-ready")
            let options = app.buttons["gallery-send-options"]
            reveal(options); options.tap()
            let mode = app.buttons[original ? "gallery-mode-file" : "gallery-mode-image"]
            XCTAssertTrue(mode.waitForExistence(timeout: 5)); mode.tap()
            XCTAssertTrue(app.buttons["gallery-send"].exists, "Choosing a send mode must keep the selection open")
            let send = app.buttons["gallery-send"]; reveal(send); send.tap()
            waitUntil("Selection closes without a draft/review screen") { !self.app.buttons["gallery-send"].exists }
            XCTAssertTrue(app.scrollViews["composer-attachments"].exists)
            replace(multilineField("chat-composer-text"), with: extraCaption)
            capture("authenticated-\(kind)-composer-ready")
            app.buttons["chat-composer-send"].tap()
            if !original {
                replace(multilineField("chat-composer-text"), with: "COCO UIQA parallel text \(username)")
                XCTAssertTrue(app.buttons["chat-composer-send"].isEnabled)
                app.buttons["chat-composer-send"].tap()
                capture("authenticated-text-during-video-send")
            }
            XCTAssertFalse(app.buttons["Media draft"].exists)
            XCTAssertTrue(app.staticTexts[extraCaption].waitForExistence(timeout: 60))
            waitUntil("Extra attachment finishes sending") {
                !self.app.buttons.matching(identifier: "Cancel sending").firstMatch.exists &&
                    !self.app.staticTexts.matching(identifier: "Failed to send").firstMatch.exists
            }
            capture("authenticated-\(kind)-\(original ? "original" : "compressed")-sent")
            if !original {
                revealChatContent(app.staticTexts[extraCaption])
                captureAccessibilityTree("authenticated-before-sent-video-lookup-tree")
                waitUntil("The sent video's actual native preview must appear", timeout: 10) { !self.chatMediaElements(.video).isEmpty }
                let candidates = chatMediaElements(.video)
                XCTAssertFalse(candidates.isEmpty, "The video preview must remain available after its accessibility snapshot")
                guard let video = candidates.last else { return }
                revealChatContent(video)
                video.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.6)
                XCTAssertTrue(app.buttons["Reply"].waitForExistence(timeout: 10))
                assertMenuFixturePreview(.video)
                capture("authenticated-video-long-press-preview")
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).tap()
                waitUntil("Outside tap dismisses video message menu") { !self.app.buttons["Reply"].exists }
                revealChatContent(video)
                video.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                let slider = app.sliders["Playback position"]
                XCTAssertTrue(slider.waitForExistence(timeout: 15), "The just-sent video must open its video player, not an earlier photo")
                XCTAssertEqual(app.staticTexts["media-page-counter"].label, "13 / 13", "The clicked new video must be selected after the original two videos and ten photos")
                waitUntil("The actual AVPlayer clock advances", timeout: 15) {
                    self.playbackPercent(slider) > 0
                }
                pauseIncomingVideo()
                XCTAssertLessThan(playbackPercent(slider), 95, "Pause must stop active playback, not wait for the video to end naturally")
                // Inspect the rendered layer after pausing. Extra native AX
                // snapshots before Pause can consume its auto-hide interval.
                let renderedVideo = app.descendants(matching: .any).matching(identifier: "media-video-surface").firstMatch
                waitUntil("AVPlayerLayer has a real frame ready for display", timeout: 15) { renderedVideo.value as? String == "Ready" }
                XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "media-video-loading-poster").firstMatch.exists, "The poster must leave once the real video is ready")
                capture("authenticated-just-sent-video-paused-after-playback")
                let shortDragStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                let shortDragEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.39))
                shortDragStart.press(forDuration: 0.05, thenDragTo: shortDragEnd)
                XCTAssertTrue(slider.exists, "An insufficient vertical drag must return, not close the player")
                slider.adjust(toNormalizedSliderPosition: 0.05)
                let rightVideo = app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.4))
                let leftVideo = app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.4))
                rightVideo.doubleTap()
                waitUntil("Double tap on the right skips forward") { self.playbackPercent(slider) > 70 }
                leftVideo.doubleTap()
                waitUntil("Double tap on the left skips back") { self.playbackPercent(slider) < 25 }
                slider.adjust(toNormalizedSliderPosition: 0.5)
                waitUntil("Paused video seeks to the middle", timeout: 10) {
                    let value = self.playbackPercent(slider)
                    return value >= 40 && value <= 60
                }
                app.buttons["Mute"].tap()
                XCTAssertTrue(app.buttons["Unmute"].waitForExistence(timeout: 5)); app.buttons["Unmute"].tap()
                app.buttons["Playback speed"].tap()
                XCTAssertTrue(app.buttons["1.5×"].waitForExistence(timeout: 5)); app.buttons["1.5×"].tap()
                slider.adjust(toNormalizedSliderPosition: 0.05)
                app.buttons["Play"].tap()
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.4)).press(forDuration: 0.9)
                XCTAssertFalse(app.staticTexts["media-temporary-speed"].exists)
                XCTAssertEqual(app.buttons["Playback speed"].value as? String, "1.5×", "Releasing a temporary boost restores the chosen speed")
                XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 5)); app.buttons["Pause"].tap()
                slider.adjust(toNormalizedSliderPosition: 0.5)
                XCUIDevice.shared.orientation = .landscapeLeft
                waitUntil("Landscape player controls remain reachable") {
                    self.app.buttons["Play"].isHittable && self.app.buttons["Playback speed"].isHittable && self.app.buttons["Close"].isHittable
                }
                let viewport = app.frame
                XCTAssertGreaterThan(viewport.width, viewport.height, "The app must finish rotating to landscape")
                for control in [app.buttons["Play"], app.buttons["Playback speed"], app.buttons["Close"], slider] {
                    XCTAssertTrue(viewport.insetBy(dx: -1, dy: -1).contains(control.frame), "Landscape controls must be fully inside the app viewport: \(control.frame), viewport \(viewport)")
                }
                capture("authenticated-video-landscape-paused-controls")
                let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                screen.name = "authenticated-video-landscape-full-screen"
                screen.lifetime = .keepAlways
                add(screen)
                app.buttons["Play"].tap()
                waitUntil("Playback resumes after seeking and selecting speed") {
                    self.playbackPercent(slider) > 60
                }
                XCUIDevice.shared.orientation = .portrait
                waitUntil("The app finishes rotating to portrait before the vertical dismiss gesture", timeout: 10) {
                    self.app.frame.height > self.app.frame.width
                }
                // Long hold temporarily boosts playback; release restores the
                // chosen speed rather than leaving a sticky fast-forward mode.
                let dismissStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                let dismissEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.78))
                dismissStart.press(forDuration: 0.05, thenDragTo: dismissEnd)
                waitUntil("Closing video restores chat") { self.app.buttons["Attach photo or file"].isHittable }
            }
        }
        // Measure the already-uploaded media chat separately from sending.
        let metrics = XCTMeasureOptions()
        metrics.iterationCount = 1
        measure(metrics: [XCTClockMetric(), XCTCPUMetric(application: app), XCTMemoryMetric(application: app), XCTOSSignpostMetric.scrollDecelerationMetric], options: metrics) {
            let timeline = app.scrollViews["chat-timeline"]
            for _ in 0..<3 { timeline.swipeDown(); timeline.swipeUp() }
        }
        capture("authenticated-existing-media-chat-scrolled")
        // A separate sampling pass follows the metrics; sampling overhead must
        // not contaminate the reported CPU/memory measurement above.
        print("UNYRI_QA_STEADY_SCROLL_SAMPLE_BEGIN")
        Thread.sleep(forTimeInterval: 1)
        let sampledTimeline = app.scrollViews["chat-timeline"]
        for _ in 0..<3 { sampledTimeline.swipeDown(); sampledTimeline.swipeUp() }
        verifyRussianAccessibilityFooter()
        // The backend probe must separately count persisted attachment
        // messages; lazy UI rows cannot establish all ten server receipts.
    }
}
