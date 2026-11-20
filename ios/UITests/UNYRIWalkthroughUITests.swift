import XCTest
import UIKit

/// Screen-by-screen visual walkthrough against an isolated loopback QA backend.
/// Opt-in only (UNYRI_WALK_ROLE); never targets a real account. Each step writes
/// a PNG into UNYRI_WALK_OUT so a reviewer can inspect the screens in order.
///
/// Roles (run on separate simulators so each has its own Signal device):
///   register  — create the account UNYRI_WALK_HANDLE, capture list/drawer/saved
///   message   — open a chat with UNYRI_WALK_PEER and send a varied conversation
///   inbox     — open the chat from UNYRI_WALK_PEER, reply, react, open menus
///   sharephotos — share HEIC + PNG + MOV + MP4 from the Photos app to Saved
///   search — inline header, sections/tabs, message anchor, Cancel/edge-swipe restoration
///   inappnotify (WalkBoris) + inappnotify-send (WalkAnya) — concurrent QA pair;
///     receiver requires UNYRI_WALK_NOTIFY_PAIRED=1 in the test runner environment
///   diagnostics / diagnostics-help — signed-in QA report from drawer / Settings > Help
///   drawer / drawerc — A (default) / select C in Settings; run both at normal and accessibility-extra-large text
///   accountavatars — two isolated QA accounts with different photos, warmed once each; inspect A/C/Settings shots
///   reactionmore / reactionslide / reactionsearch / reactionrecent — picker regressions on a fresh final bubble
///   reactionpicker — instrumented picker latency and open/closed idle windows for Mac profiling
@MainActor
final class UNYRIWalkthroughUITests: XCTestCase {
    private var app: XCUIApplication!
    private let env = ProcessInfo.processInfo.environment
    private var out: URL { URL(fileURLWithPath: env["UNYRI_WALK_OUT"] ?? NSTemporaryDirectory()) }
    private var step = 0
    private let serverPlaceholder = "https://server.example or http://192.168.1.10:8080"

    override func setUpWithError() throws {
        try XCTSkipUnless(env["UNYRI_WALK_ROLE"] != nil, "Opt-in walkthrough against the isolated QA backend")
        if env["UNYRI_WALK_ROLE"] == "inappnotify" {
            try XCTSkipUnless(env["UNYRI_WALK_NOTIFY_PAIRED"] == "1",
                "inappnotify needs a concurrent peer sender: run inappnotify on WalkBoris with UNYRI_WALK_NOTIFY_PAIRED=1 and inappnotify-send on WalkAnya. Single-simulator battery: skipped (no paired sender declared).")
        }
        continueAfterFailure = true
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication(bundleIdentifier: "com.messenger.unyri.X7G7M9PFJ3")
        app.launchArguments = ["-AppleLanguages", "(\(env["UNYRI_WALK_LANG"] ?? "ru"))", "-AppleLocale", env["UNYRI_WALK_LOCALE"] ?? "ru_RU"]
        app.launchEnvironment["UNYRI_FAKE_MIC"] = "1"   // simulator-only tone recorder
        if ["reactionmore", "reactionslide", "reactionsearch", "reactionrecent", "reactionpicker"].contains(env["UNYRI_WALK_ROLE"] ?? "") {
            app.launchEnvironment["UNYRI_QA_REACTION_PICKER"] = "1"
        }
        if let callKit = env["UNYRI_WALK_CALLKIT"] { app.launchEnvironment["UNYRI_WALK_CALLKIT"] = callKit }
        // Simulators have no live peer picture: this shows the call's real glass look (alpha builds only).
        if env["UNYRI_WALK_FORCE_CALL_GLASS"] == "1" { app.launchEnvironment["UNYRI_WALK_FORCE_CALL_GLASS"] = "1" }
        if env["UNYRI_WALK_ROLE"] == "videonote" || env["UNYRI_WALK_ROLE"] == "videonotefx" {
            app.launchEnvironment["UNYRI_FAKE_CAMERA"] = "1"   // synthetic frames
        }
        // A 10 s limit reaches the final seconds and the limit within one walkthrough.
        if env["UNYRI_WALK_ROLE"] == "videonotefx" { app.launchEnvironment["UNYRI_VIDEO_NOTE_MAX_SECONDS"] = "10" }
        if env["UNYRI_WALK_ROLE"] == "pushtap" { app.launchEnvironment["UNYRI_QA_PUSH_TAP_PEER"] = env["UNYRI_WALK_PEER_DISPLAY"] ?? "" }
        // Shake reports on whatever the build's default (B-047 checks both paths).
        if env["UNYRI_WALK_ROLE"] == "callshake" { app.launchArguments += ["-diagnosticReports.shake.v1", "YES"] }
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        app.launch()
    }

    func testWalkthrough() throws {
        switch env["UNYRI_WALK_ROLE"] {
        case "register": try register()
        case "login2": try register(login2: true)
        case "loginglass": try loginGlassProbe()
        case "signin": try signInExisting()
        case "message": try message()
        case "inbox": try inbox()
        case "media": try media()
        case "keyboard": try keyboardProbe()
        case "drawer": try drawerProbe()
        case "accountavatars": try accountAvatarsProbe()
        case "drawerc":
            selectDrawer("c")
            try drawerProbe(card: true)
            selectDrawer("a") // Leave the default for subsequent walkthrough roles.
        case "perf": try perfProbe()
        case "flood": try floodProbe()
        case "voice": try voiceProbe()
        case "files": try filesProbe()
        case "sharephotos": try photosShareProbe()
        case "emoji": try emojiProbe()
        case "callstart": try callStartProbe()
        case "callbuttons": try callButtonsProbe()
        case "callshake": try callShakeProbe()
        case "callrotate": try callRotateProbe()
        case "chatmenu":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            app.buttons["chat-options"].firstMatch.tap()
            sleep(1); shot("chatmenu-open")
        case "listswipelock": try listSwipeLockProbe()
        case "listmenu":
            sleep(3)
            let row = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", env["UNYRI_WALK_PEER_DISPLAY"] ?? "QA")).firstMatch
            if row.waitForExistence(timeout: 10) { row.press(forDuration: 0.9); sleep(1); shot("listmenu-open") }
        case "forward":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(2)
            send("Переслать это сообщение")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.45)).tap(); sleep(1)
            longPress(lastBubble(containing: "Переслать это")); shot("forward-menu")
            let forwardItem = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Переслать", "Forward")).firstMatch
            guard forwardItem.waitForExistence(timeout: 4) else { shot("forward-no-item"); break }
            forwardItem.tap(); sleep(2); shot("forward-sheet")
            let tile = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "forward-target-")).firstMatch
            guard tile.waitForExistence(timeout: 8) else { shot("forward-no-targets"); break }
            tile.tap(); sleep(1); shot("forward-selected")
            let comment = app.textFields["forward-comment"].exists ? app.textFields["forward-comment"] : app.textViews["forward-comment"]
            if comment.waitForExistence(timeout: 3) { comment.tap(); sleep(1); comment.typeText("Смотри"); sleep(1); shot("forward-comment") }
            app.buttons["forward-confirm"].tap(); sleep(3); shot("forward-sent")
        case "forwardvideo":
            // B-022 (owner, iPhone 0.4.18, 14 Nov): forwarding a video to a person showed
            // "The operation couldn't be completed. (Swift.CancellationError error 1.)" in the
            // Forward sheet and as a banner in the chat, and nothing was sent.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(2)
            let videos = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR (identifier BEGINSWITH %@ AND NOT identifier BEGINSWITH %@)", "chat-album-video", "chat-video-", "chat-video-note"))
            var video = videos.allElementsBoundByIndex.last(where: { $0.isHittable }); var tries = 0
            while video == nil && tries < 10 { app.swipeDown(); tries += 1; usleep(400_000); video = videos.allElementsBoundByIndex.last(where: { $0.isHittable }) }
            guard let video else { XCTFail("Seed a video in the QA chat for B-022"); break }
            video.press(forDuration: 0.8); sleep(1); shot("forwardvideo-menu")
            let forwardVideoItem = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Переслать", "Forward")).firstMatch
            guard forwardVideoItem.waitForExistence(timeout: 4) else { XCTFail("No Forward in the video menu"); break }
            forwardVideoItem.tap(); sleep(2); shot("forwardvideo-sheet")
            let targets = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "forward-target-"))
            guard targets.firstMatch.waitForExistence(timeout: 8) else { XCTFail("No forward targets"); break }
            // A person, like the owner's report (the first tile is Saved Messages).
            targets.element(boundBy: targets.count > 1 ? 1 : 0).tap(); sleep(1); shot("forwardvideo-selected")
            app.buttons["forward-confirm"].tap()
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["forward-confirm"])
            let outcome = XCTWaiter().wait(for: [closed], timeout: 90)
            shot("forwardvideo-after")
            let rawError = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "CancellationError", "couldn")).firstMatch
            XCTAssertFalse(rawError.exists, "B-022: forwarding a video shows a raw system error")
            XCTAssertEqual(outcome, .completed, "B-022: the Forward sheet closes after the video is queued")
        case "editor":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            func editorPhoto() -> XCUIElement? {
                // Only photos fully above the composer: a photo under the glass composer is
                // "hittable" but the tap lands on the composer.
                let limit = composer.exists ? composer.frame.minY - 12 : app.frame.maxY
                func visible(_ e: XCUIElement) -> Bool { e.isHittable && e.frame.maxY < limit && e.frame.minY > 120 }
                let album = app.descendants(matching: .any).matching(identifier: "chat-album-photo").allElementsBoundByIndex.last(where: visible)
                let single = app.descendants(matching: .any).matching(identifier: "chat-photo").allElementsBoundByIndex.last(where: visible)
                return album ?? single
            }
            sleep(1); shot("editor-opened")
            var picture = editorPhoto(); var tries = 0
            while picture == nil && tries < 8 { app.swipeDown(); tries += 1; usleep(400_000); picture = editorPhoto() }
            guard let picture else { shot("editor-no-photo"); break }
            picture.tap(); sleep(2); shot("editor-viewer")
            let markup = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Разметка", "Markup")).firstMatch
            guard markup.waitForExistence(timeout: 5) else { shot("editor-no-markup"); break }
            markup.tap(); sleep(2); shot("editor-open")
            let canvasCenter = app.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.45))
            canvasCenter.press(forDuration: 0.05, thenDragTo: canvasCenter.withOffset(CGVector(dx: 90, dy: 60)), withVelocity: 400, thenHoldForDuration: 0.1)
            sleep(1); shot("editor-drawn")
            let addText = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Добавить текст", "Add text")).firstMatch
            if addText.exists {
                addText.tap(); sleep(1)
                app.typeText("UNYRI"); sleep(1); shot("editor-text-entry")
                let done = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Готово", "Done")).firstMatch
                if done.exists { done.tap() }; sleep(1); shot("editor-text")
            }
            app.buttons["photo-editor-send"].tap(); sleep(3); shot("editor-sent-to-composer")
        case "viewer":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            let latest = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Latest messages", "К последним сообщениям")).firstMatch
            if latest.exists { latest.tap(); sleep(1) }
            func findPhoto() -> XCUIElement? {
                let album = app.descendants(matching: .any).matching(identifier: "chat-album-photo").allElementsBoundByIndex.last(where: { $0.isHittable })
                let single = app.descendants(matching: .any).matching(identifier: "chat-photo").allElementsBoundByIndex.last(where: { $0.isHittable })
                return album ?? single
            }
            var found = findPhoto()
            var swipes = 0
            while found == nil && swipes < 8 { app.swipeDown(); swipes += 1; usleep(400_000); found = findPhoto() }
            let photo = found ?? app.descendants(matching: .any)["chat-photo"]
            guard photo.exists else { shot("viewer-no-photo"); break }
            // This role is a new app process after "media". Never let opening the
            // viewer warm caches and conceal a broken cold timeline mount.
            let albumPhotos = app.descendants(matching: .any).matching(identifier: "chat-album-photo")
            let photoReady = NSPredicate(format: "value IN %@", ["Photo", "Фото"])
            let videoReady = NSPredicate(format: "value IN %@", ["Video", "Видео", "Відео"])
            let localVideos = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "chat-video-"))
            let readyUntil = Date().addingTimeInterval(10)
            while Date() < readyUntil {
                let photos = albumPhotos.allElementsBoundByIndex.filter { $0.isHittable }
                let videos = localVideos.allElementsBoundByIndex.filter { $0.isHittable }
                if photos.allSatisfy({ photoReady.evaluate(with: $0) }) && videos.allSatisfy({ videoReady.evaluate(with: $0) }) { break }
                usleep(100_000)
            }
            for tile in albumPhotos.allElementsBoundByIndex where tile.isHittable {
                XCTAssertTrue(photoReady.evaluate(with: tile), "Persisted album photo did not render after relaunch, before opening the viewer")
            }
            for bubble in localVideos.allElementsBoundByIndex where bubble.isHittable {
                XCTAssertTrue(videoReady.evaluate(with: bubble), "Persisted video poster did not render after relaunch, before opening the viewer")
            }
            shot("viewer-chat")
            photo.tap(); usleep(150_000); shot("viewer-opening"); sleep(1); shot("viewer-open")
            app.doubleTap(); sleep(1); shot("viewer-zoomed")
            app.doubleTap(); sleep(1)
            let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            center.press(forDuration: 0.1, thenDragTo: center.withOffset(CGVector(dx: 0, dy: 120)), withVelocity: 200, thenHoldForDuration: 0.6)
            sleep(1); shot("viewer-partial-drag-released")
            app.swipeLeft(); sleep(1); shot("viewer-next")
            center.press(forDuration: 0.05, thenDragTo: center.withOffset(CGVector(dx: 0, dy: 380)), withVelocity: 1500, thenHoldForDuration: 0)
            usleep(200_000); shot("viewer-dismissing"); sleep(1); shot("viewer-closed")
        case "pdf":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            app.buttons["chat-composer-attach"].tap(); sleep(1)
            app.buttons["Файлы"].firstMatch.tap(); sleep(2)
            let pdfRow = app.buttons.matching(identifier: "files-recent-row").matching(NSPredicate(format: "label CONTAINS %@", "Contract")).firstMatch
            guard pdfRow.waitForExistence(timeout: 5) else { shot("pdf-no-row"); break }
            pdfRow.tap(); app.buttons["gallery-add"].tap(); sendTray("pdf"); sleep(5); shot("pdf-sent")
            let card = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Contract signed")).allElementsBoundByIndex.last
            card?.tap(); sleep(3); shot("pdf-reader-open")
            app.swipeUp(); sleep(1); app.swipeUp(); sleep(1); shot("pdf-reader-scrolled")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); sleep(1); shot("pdf-reader-chrome-hidden")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); sleep(1)
            app.buttons["Close"].firstMatch.exists ? app.buttons["Close"].firstMatch.tap() : app.buttons["Закрыть"].firstMatch.tap()
            sleep(1); shot("pdf-reader-closed")
        case "banner":
            try callBannerProbe()
        case "topscroll": try topScrollProbe()
        case "accounts": try accountsProbe()
        case "callswitch": try callSwitchProbe()
        case "callanswer": try callAnswerProbe()
        case "callpair": try callPairProbe()
        case "switchchats": try switchChatsProbe()
        case "directsend": try directSendProbe()
        case "directaccept": try directAcceptProbe()
        case "directhold": try directHoldProbe()
        case "directaccepthide": try directAcceptProbe(hideMidway: true)
        case "stripprobe": try stripProbe()
        case "callsettings": try callSettingsProbe()
        case "serverknow": try serverKnowledgeProbe()
        case "qrorigin": try qrOriginProbe()
        case "drawerheader": try drawerHeaderProbe()
        case "chatmediagrid": try chatMediaGridProbe()

        case "selectionbar": try selectionBarProbe()
        case "contactsnumber": try contactsNumberProbe()
        case "callstranger": try callStrangerProbe()
        case "screens": try screensProbe()
        case "pushtap": try pushTapProbe()
        case "diagnostics": try diagnosticsProbe()
        case "diagnostics-help": try diagnosticsProbe(fromDrawer: false)
        case "search": try inlineSearchProbe()
        case "inappnotify": try inAppNotificationProbe()
        case "inappnotify-send": try inAppNotificationSender()
        case "settings": try settingsProbe()
        case let r? where r.hasPrefix("settings_"): try settingsVisit(r)
        case "jitter":
            let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
            XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
            let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(3)
            // Slow drags near the end of the chat, then slow flings: content must move monotonically.
            for _ in 0..<4 {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.45))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.70)),
                           withVelocity: 250, thenHoldForDuration: 0.3)
                sleep(1)
            }
            for _ in 0..<4 {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.70))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.45)),
                           withVelocity: 250, thenHoldForDuration: 0.3)
                sleep(1)
            }
            for _ in 0..<3 {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.40))
                    .press(forDuration: 0.02, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.75)),
                           withVelocity: 900, thenHoldForDuration: 0)
                sleep(2)
            }
            shot("jitter-end")
        case "attachprobe":
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            let attach = app.buttons["chat-composer-attach"]
            shot("attach-exists-\(attach.exists)-hittable-\(attach.isHittable)")
            attach.tap(); sleep(2); shot("attach-after-tap")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.92)).tap(); sleep(2); shot("attach-after-coordinate-tap")
        case "scroll": try scrollProbe(search: false)
        case "scrollsearch": try scrollProbe(search: true)
        case "videonote": try videoNoteProbe()
        case "videonotefx": try videoNoteEffectProbe()
        case "reactfx": try reactionEffectProbe()
        case "reactionmore": try reactionMoreProbe()
        case "reactionslide": try reactionSlideProbe()
        case "reactionsearch": try reactionSearchProbe()
        case "reactionrecent": try reactionRecentProbe()
        case "reactionpicker": try reactionPickerTimingProbe()
        case "holddrag": try holdDragProbe()
        case "videoplayer": try videoPlayerProbe()
        case "bgresume":
            // Media must stay on screen through background/foreground (owner, 13 Nov:
            // after unlocking, photos appeared only after about a second).
            openChat(with: env["UNYRI_WALK_PEER"]!)
            sleep(3); shot("bgresume-before")
            XCUIDevice.shared.press(.home)
            sleep(5)
            app.activate()
            shot("bgresume-0")
            usleep(300_000); shot("bgresume-300ms")
            sleep(1); shot("bgresume-1300ms")
        case "relaunch":
            // Cold start straight back into a chat with media: nothing may show a placeholder
            // that was sharp before the restart.
            openChat(with: env["UNYRI_WALK_PEER"]!)
            sleep(3); shot("relaunch-before")
            app.terminate()
            app.launch()
            openChat(with: env["UNYRI_WALK_PEER"]!)
            shot("relaunch-0")
            usleep(300_000); shot("relaunch-300ms")
            sleep(1); shot("relaunch-1300ms")
        case "reopen":
            // Leave and re-enter the same chat quickly: rows and media must come back at once.
            openChat(with: env["UNYRI_WALK_PEER"]!)
            sleep(2); shot("reopen-first")
            let handle = env["UNYRI_WALK_PEER_DISPLAY"]!
            for pass in 1...3 {
                let back = app.buttons["chat-navigation-back"].firstMatch
                guard back.waitForExistence(timeout: 5) else { shot("reopen-no-back"); break }
                back.tap()
                usleep(400_000)
                let result = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", handle)).firstMatch
                guard result.waitForExistence(timeout: 10) else { shot("reopen-no-result"); break }
                result.tap()
                _ = composer.waitForExistence(timeout: 10)
                shot("reopen-\(pass)")
            }
        case "scrollback":
            // Scroll far up through history and back: media rows re-enter without flashing.
            openChat(with: env["UNYRI_WALK_PEER"]!)
            sleep(2); shot("scrollback-bottom")
            for _ in 0..<6 { app.swipeDown(velocity: .fast) }
            sleep(1); shot("scrollback-top")
            for _ in 0..<8 { app.swipeUp(velocity: .fast) }
            shot("scrollback-back-0")
            sleep(1); shot("scrollback-back-1s")
        case "killsend": try killSendProbe()
        case "peoplefx", "contactsfx": try contactsMotionProbe()
        case "contacts": try contactsProbe()
        case "bgcycle":
            // Background and foreground five times with growing pauses: media and rows must be
            // on screen in the first frame back every time.
            openChat(with: env["UNYRI_WALK_PEER"]!)
            sleep(3); shot("bgcycle-before")
            for (pass, pause) in [0.5, 1, 2, 4, 8].enumerated() {
                XCUIDevice.shared.press(.home)
                usleep(UInt32(pause * 1_000_000))
                app.activate()
                shot("bgcycle-\(pass)-0")
                usleep(300_000); shot("bgcycle-\(pass)-300ms")
            }
        case "viewerbg":
            // The media viewer survives background/foreground with its photo, then closes cleanly.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            guard let photo = visibleMediaTile() else { shot("viewerbg-no-photo"); break }
            photo.tap(); sleep(1); shot("viewerbg-open")
            XCUIDevice.shared.press(.home); sleep(3)
            app.activate()
            shot("viewerbg-back-0"); usleep(300_000); shot("viewerbg-back-300ms"); sleep(1); shot("viewerbg-back-1300ms")
            let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            center.press(forDuration: 0.05, thenDragTo: center.withOffset(CGVector(dx: 0, dy: 380)), withVelocity: 1500, thenHoldForDuration: 0)
            sleep(1); shot("viewerbg-closed")
        case "draftbg":
            // A typed draft and the open keyboard survive background/foreground and a cold relaunch.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            let draft = "Черновик \(Int(Date().timeIntervalSince1970) % 10000)"
            composer.tap(); composer.typeText(draft); sleep(1); shot("draftbg-typed")
            XCUIDevice.shared.press(.home); sleep(3)
            app.activate()
            shot("draftbg-back-0"); sleep(1); shot("draftbg-back-1s")
            XCTAssertTrue((composer.value as? String ?? "").contains(draft), "draft lost after background")
            app.terminate(); app.launch()
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1); shot("draftbg-relaunched")
            print("DRAFTBG after relaunch=\((composer.value as? String ?? "").contains(draft))")
            // Leave nothing for the next role.
            composer.tap()
            composer.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: draft.count + 4))
            sleep(1); shot("draftbg-cleared")
        case "voicebg":
            // A voice message playing when the app goes to the background: the bubble's state is
            // consistent when it returns (no stuck spinner, no second player).
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            // Voice messages may sit above the latest rows: scroll up until one is on screen.
            func findVoice() -> XCUIElement? {
                app.buttons.matching(NSPredicate(format: "label == %@", "Play voice message")).allElementsBoundByIndex.last(where: { $0.isHittable })
            }
            var play = findVoice()
            var voiceSwipes = 0
            while play == nil && voiceSwipes < 10 { dragTimeline(from: 0.35, to: 0.75); voiceSwipes += 1; usleep(400_000); play = findVoice() }
            guard let play else { shot("voicebg-no-voice"); break }
            play.tap(); usleep(500_000); shot("voicebg-playing")
            XCUIDevice.shared.press(.home); sleep(3)
            app.activate()
            shot("voicebg-back-0"); sleep(1); shot("voicebg-back-1s"); sleep(3); shot("voicebg-back-4s")
        case "videonotebg":
            // A video message playing when the app goes to the background and comes back.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
            let note = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label == %@", "Video message", "Видеосообщение"))
                .allElementsBoundByIndex.last(where: { $0.isHittable })
            guard let note else { shot("videonotebg-no-note"); break }
            note.tap(); usleep(600_000); shot("videonotebg-playing")
            XCUIDevice.shared.press(.home); sleep(3)
            app.activate()
            shot("videonotebg-back-0"); usleep(300_000); shot("videonotebg-back-300ms"); sleep(2); shot("videonotebg-back-2s")
            note.tap(); usleep(600_000); shot("videonotebg-replay")
        case "appswitch":
            // Owner, 13 Nov (0.4.16 on a phone): keyboard open in a chat with photos, switch to
            // another app and back a few times (app switcher, home screen): the photos turned into
            // gray placeholders and stayed gray, even after scrolling up. Every return must show
            // them again, and so must the rows scrolled into view afterwards.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(2)
            _ = visibleMediaTile()
            composer.tap(); sleep(1)
            print("APPSWITCH keyboard=\(app.keyboards.count)")
            shot("appswitch-before")
            var blankStages: [String] = []
            func check(_ stage: String) {
                let tiles = onScreenMediaTiles()
                let blank = tiles.filter { looksBlank($0) }.count
                print("APPSWITCH \(stage) tiles=\(tiles.count) blank=\(blank)")
                if blank > 0 { blankStages.append(stage) }
            }
            check("before")
            let other = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
            for (pass, pause) in [1.0, 0.5, 3, 1, 10, 30, 2].enumerated() {
                if pass % 2 == 0 { other.activate() } else { XCUIDevice.shared.press(.home) }
                usleep(UInt32(pause * 1_000_000))
                app.activate()
                shot("appswitch-\(pass)-0"); usleep(500_000); shot("appswitch-\(pass)-500ms")
                sleep(2); shot("appswitch-\(pass)-2500ms"); check("pass\(pass)")
            }
            for index in 0..<4 { dragTimeline(from: 0.35, to: 0.75); sleep(2); shot("appswitch-up-\(index)"); check("up\(index)") }
            XCTAssertTrue(blankStages.isEmpty, "photos still blank 2.5 s after returning: \(blankStages)")
        case "topgap":
            // Owner, 14 Nov (recording): pushing the list up, the gap between a video message
            // and the call row under it grows as they reach the top of the screen. A row's
            // height must not depend on where it is on screen: every cell's frame is logged at
            // each small step; the analysis compares each row's height near the top and below.
            openChat(with: env["UNYRI_WALK_PEER"]!); sleep(2)
            for _ in 0..<3 { dragTimeline(from: 0.30, to: 0.80); usleep(600_000) }
            sleep(1)
            let timeline = app.collectionViews["chat-timeline"]
            // Each cell: its frame and the frame of its first text inside (the bubble's content).
            // Content that moves inside a cell that keeps its place is the effect.
            func dump(_ tag: String) {
                var line = "TOPGAP \(tag)"
                for cell in timeline.cells.allElementsBoundByIndex where cell.exists {
                    let frame = cell.frame
                    let text = cell.staticTexts.firstMatch
                    let inner = text.exists ? text.frame.minY - frame.minY : -1
                    let label = String((text.exists ? text.label : "").prefix(12)).replacingOccurrences(of: " ", with: "_")
                    line += " [\(Int(frame.minY.rounded())),\(Int(frame.height.rounded())),\(Int(inner.rounded())),\(label)]"
                }
                print(line)
            }
            func nudge(_ points: CGFloat) {
                // A slow drag that stops before release: no fling, the list moves by about `points`.
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.62))
                start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -points)),
                            withVelocity: 120, thenHoldForDuration: 0.4)
            }
            dump("start"); shot("topgap-start")
            for step in 0..<24 {
                nudge(24); usleep(500_000)
                dump("s\(step)")
                if step % 6 == 5 { shot("topgap-\(step)") }
            }
        default: XCTFail("Unknown role")
        }
        shot("final")
    }

    // MARK: - Roles

    /// A round video message with the simulator's synthetic camera: the recording effect is
    /// read from the screen recording (recordwalk.sh), then the sent note must show a poster
    /// at once, after the upload and after reopening the chat.
    private func videoNoteProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        // A draft left by another role turns the record button into Send: send it first.
        if app.buttons["chat-composer-send"].exists { app.buttons["chat-composer-send"].tap(); sleep(2) }
        let record = app.buttons["chat-composer-record"]
        guard record.waitForExistence(timeout: 5) else { shot("videonote-no-record-button"); return }
        let center = record.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        if (record.value as? String) != "video" { center.press(forDuration: 0.05); sleep(2) }
        print("VIDEONOTE mode=\(record.value as? String ?? "?")")
        shot("videonote-mode")
        center.press(forDuration: 5.0)
        usleep(200_000); shot("videonote-released")
        sleep(1); shot("videonote-1s")
        sleep(3); shot("videonote-4s")
        sleep(6); shot("videonote-10s")
        reopenPeerChat(); shot("videonote-reopened")
        // Other roles expect holds to record voice.
        if record.waitForExistence(timeout: 3), (record.value as? String) == "video" { center.press(forDuration: 0.05); sleep(1) }
    }

    /// The recording halo with a 10 s limit (UNYRI_VIDEO_NOTE_MAX_SECONDS): live, final
    /// seconds, the limit's send, a discard, a too-short hold and a send by button. Frames
    /// around each exit are read from the screen recording; the log stream has the stages.
    private func videoNoteEffectProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        let record = app.buttons["chat-composer-record"]
        guard record.waitForExistence(timeout: 5) else { shot("videonotefx-no-record-button"); return }
        let center = record.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        if (record.value as? String) != "video" { center.press(forDuration: 0.05); sleep(2) }
        // Hold, slide up onto the lock, release: the take keeps recording.
        func lockedTake() {
            center.press(forDuration: 0.8, thenDragTo: center.withOffset(CGVector(dx: 0, dy: -140)),
                         withVelocity: 500, thenHoldForDuration: 0.3)
        }
        lockedTake(); shot("videonotefx-live")                  // ~1.2 s: glow, head, ignite ripple
        sleep(2); shot("videonotefx-voice")                      // ~3.3 s
        sleep(3); shot("videonotefx-final")                      // ~6.4 s: marks, brighter track
        sleep(2); shot("videonotefx-final-late")                 // ~8.5 s: marks under the stroke
        sleep(3); shot("videonotefx-after-limit")                // the limit sent it: seal and flight
        lockedTake(); sleep(2)
        app.buttons["video-note-cancel"].tap(); usleep(120_000); shot("videonotefx-discarding")
        sleep(1); shot("videonotefx-discarded")
        center.press(forDuration: 0.4); usleep(150_000); shot("videonotefx-too-short")
        sleep(2)
        lockedTake(); sleep(2)
        app.buttons["video-note-send"].tap(); usleep(150_000); shot("videonotefx-sending")
        sleep(2); shot("videonotefx-sent")
        // Other roles expect holds to record voice.
        if record.waitForExistence(timeout: 3), (record.value as? String) == "video" { center.press(forDuration: 0.05); sleep(1) }
    }

    /// The owner's report (11 Nov, 0.4.12): holding a finger on the chat and dragging up
    /// slowly without letting go, the whole timeline blanks for a few frames. Frames come
    /// from the screen recording; the timeline trace (com.unyri.timeline) says why.
    private func holdDragProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
        for i in 0..<12 {
            // Slow drags that end held, then a short pause: history pages load while held.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3))
                .press(forDuration: 0.15, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.82)),
                       withVelocity: i % 2 == 0 ? 220 : 600, thenHoldForDuration: 1.2)
            usleep(300_000)
        }
        sleep(1); shot("holddrag-end")
    }

    /// Back to the people search the chat was opened from, and into the chat again.
    private func reopenPeerChat() {
        back(); sleep(2)
        let display = env["UNYRI_WALK_PEER_DISPLAY"] ?? env["UNYRI_WALK_PEER"]!
        let result = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", display)).firstMatch
        if result.waitForExistence(timeout: 10) { result.tap() }
        sleep(2)
    }

    /// Reactions: a quick reaction by double tap, another from the menu, then removal.
    /// Frames right after each action catch the effect; the screen recording has the rest.
    private func reactionEffectProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        // UNYRI_WALK_REACT_TEXT: a longer text, to see the chips and time wrap onto a line of
        // their own when its last line is full. The number keeps each run's message unique.
        let text = "\(env["UNYRI_WALK_REACT_TEXT"] ?? "Реакция") \(Int(Date().timeIntervalSince1970) % 1000)"
        type(into: composer, text)
        app.buttons["chat-composer-send"].tap(); sleep(2)
        // Leave and reopen the chat: the keyboard is closed and the new message is at the end
        // (a tap while the keyboard is up only dismisses it).
        reopenPeerChat()
        shot("reactfx-sent")
        let label = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        guard label.waitForExistence(timeout: 5) else { shot("reactfx-no-message"); return }
        let bubble = label.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        bubble.doubleTap()
        usleep(120_000); shot("reactfx-quick-a")
        usleep(200_000); shot("reactfx-quick-b")
        sleep(1); shot("reactfx-quick-done")
        bubble.press(forDuration: 0.7); sleep(1); shot("reactfx-menu")
        let candidates = ["😂", "😮", "❤️", "😢", "👍"]
        let existing = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-reaction-")).firstMatch
        let current = existing.exists ? existing.identifier : ""
        func menuButton(_ emoji: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "label == %@ OR identifier == %@", emoji, emoji)).firstMatch
        }
        if let pick = candidates.first(where: { !current.hasSuffix($0) && menuButton($0).exists }) {
            menuButton(pick).tap()
            usleep(120_000); shot("reactfx-menu-pick-a")
            usleep(200_000); shot("reactfx-menu-pick-b")
            sleep(1); shot("reactfx-menu-pick-done")
        } else { shot("reactfx-menu-no-candidate") }
        let chip = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message-reaction-")).firstMatch
        if chip.waitForExistence(timeout: 3) {
            chip.tap(); usleep(150_000); shot("reactfx-removed-a"); sleep(1); shot("reactfx-removed-done")
        }
    }

    // MARK: - Reaction picker (B-057)

    private func reactionElement(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// A unique last bubble keeps a previous run's reactions from satisfying an assertion.
    /// Reopening also closes the keyboard, matching the owner's recorded B-057 steps.
    private func seedReactionBubble(_ purpose: String) throws -> XCUIElement {
        let host = URL(string: env["UNYRI_WALK_SERVER"] ?? "")?.host ?? ""
        guard ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host) else {
            XCTFail("Reaction walkthroughs may only send to the isolated loopback QA fixture")
            throw NSError(domain: "UNYRIWalk", code: 57)
        }
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let text = "QA \(purpose) \(UUID().uuidString.prefix(8))"
        send(text)
        sleep(2)
        reopenPeerChat()
        let bubble = lastBubble(containing: text)
        XCTAssertTrue(bubble.waitForExistence(timeout: 10), "The last sent walk bubble must be visible")
        XCTAssertTrue(bubble.isHittable)
        return bubble
    }

    @discardableResult
    private func openReactionPanel(on bubble: XCUIElement) -> XCUIElement {
        bubble.press(forDuration: 0.8)
        let expand = app.buttons["reaction-expand"]
        if !expand.waitForExistence(timeout: 5) {
            // Run the same owner gesture on 0.4.25 too: its old More button has no ID.
            let oldMore = app.buttons.matching(NSPredicate(format: "label IN %@", ["More", "Ещё", "Еще", "Ще"])).firstMatch
            if oldMore.exists { oldMore.tap() }
        } else {
            XCTAssertTrue(expand.isHittable)
            expand.tap()
        }
        let panel = reactionElement("reaction-panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 5), "B-057: expand must grow an anchored, safe-area panel")
        return panel
    }

    /// The probe is a real UIKit view at window.safeAreaLayoutGuide.layoutFrame.
    /// No phone model/status-bar-height guesses are used for the home-indicator inset.
    private func assertReactionPanelInsideSafeArea(_ panel: XCUIElement) {
        let safe = reactionElement("reaction-safe-area")
        XCTAssertTrue(safe.waitForExistence(timeout: 3), "App must expose the actual overlay window safe area")
        guard panel.exists, safe.exists else { return }
        let window = app.windows.firstMatch.frame
        let bounds = safe.frame.intersection(window)
        let frame = panel.frame
        XCTAssertGreaterThan(bounds.height, 0)
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertGreaterThan(frame.height, 0)
        XCTAssertGreaterThanOrEqual(frame.minY, bounds.minY + 7.5, "B-057: panel must clear the status bar by 8 pt")
        XCTAssertLessThanOrEqual(frame.maxY, bounds.maxY - 7.5, "B-057: panel must clear the home indicator by 8 pt")
        XCTAssertGreaterThanOrEqual(frame.minX, bounds.minX + 15.5)
        XCTAssertLessThanOrEqual(frame.maxX, bounds.maxX - 15.5)
        XCTAssertLessThanOrEqual(frame.height, window.height * 0.6 + 0.5)
        XCTAssertLessThanOrEqual(frame.width, 420.5)
        let search = app.textFields["reaction-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(search.isHittable, "B-057: search must be tappable below the status bar")
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "reaction-grid-")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 5), "The first emoji row must be visible immediately")
        if first.exists {
            XCTAssertTrue(first.isHittable)
            XCTAssertGreaterThanOrEqual(first.frame.height, 43.5)
            XCTAssertGreaterThanOrEqual(first.frame.minY, frame.minY)
            XCTAssertLessThanOrEqual(first.frame.maxY, frame.maxY - 8, "The first row must be fully above the bottom fade")
        }
        print("WALK_PROBE reactionmore safe=\(bounds) panel=\(frame) search=\(search.frame) first=\(first.frame)")
    }

    private func dismissReactionPicker() {
        // The left 16 pt gutter is outside both the pill and the expanded panel.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.45)).withOffset(CGVector(dx: 4, dy: 0)).tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: reactionElement("reaction-pill"))
        XCTAssertEqual(XCTWaiter().wait(for: [closed], timeout: 5), .completed)
        XCTAssertFalse(reactionElement("reaction-panel").exists)
    }

    private func reactionMoreProbe() throws {
        let bubble = try seedReactionBubble("reactionmore")
        let panel = openReactionPanel(on: bubble)
        assertReactionPanelInsideSafeArea(panel)
        shot("reactionmore-safe-area-first-row")
        if panel.exists { dismissReactionPicker() }
    }

    /// Find the mine chip beside this exact last bubble, rather than an older emoji match.
    private func waitForReactionChip(_ emoji: String, beside bubble: XCUIElement) {
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
            guard let self, bubble.exists else { return false }
            let text = bubble.frame
            return self.app.buttons.matching(identifier: "message-reaction-\(emoji)").allElementsBoundByIndex.contains {
                $0.exists && $0.isSelected && $0.isHittable && $0.frame.minY >= text.minY - 2 && $0.frame.maxY <= text.maxY + 90
            }
        }, object: app)
        XCTAssertEqual(XCTWaiter().wait(for: [appeared], timeout: 10), .completed, "The chosen \(emoji) chip must belong to the last walk bubble")
        XCTAssertFalse(reactionElement("reaction-pill").exists, "Choosing must close the menu")
        XCTAssertFalse(reactionElement("reaction-panel").exists)
    }

    private func reactionSlideProbe() throws {
        let bubble = try seedReactionBubble("reactionslide")
        // Measure the real third slot, then repeat the long-press without lifting the finger.
        // Personal ranking and left/right message alignment can both change its position.
        bubble.press(forDuration: 0.8)
        let third = app.buttons["reaction-pill-2"]
        XCTAssertTrue(third.waitForExistence(timeout: 5))
        guard third.exists else { shot("reactionslide-missing-pill"); return }
        let emoji = third.value as? String ?? ""
        XCTAssertFalse(emoji.isEmpty, "Pill slot exposes its actual emoji as its accessibility value")
        let target = third.frame
        shot("reactionslide-third-slot")
        dismissReactionPicker()
        let destination = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: target.midX, dy: target.midY))
        bubble.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.8, thenDragTo: destination, withVelocity: 180, thenHoldForDuration: 0.15)
        waitForReactionChip(emoji, beside: bubble)
        shot("reactionslide-sent")
        // Releasing over the bubble itself must leave the menu available for taps.
        bubble.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["reaction-pill-2"].waitForExistence(timeout: 5))
        shot("reactionslide-release-outside-keeps-menu")
        dismissReactionPicker()
    }

    @discardableResult
    private func searchReaction(_ query: String, expecting emoji: String) -> XCUIElement {
        let field = app.textFields["reaction-search"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let current = field.value as? String ?? ""
        if !current.isEmpty && current != field.placeholderValue {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(query)
        XCTAssertEqual(field.value as? String, query)
        let result = app.buttons["reaction-grid-\(emoji)"]
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true"), object: result)
        XCTAssertEqual(XCTWaiter().wait(for: [visible], timeout: 5), .completed, "\(query) must find \(emoji)")
        return result
    }

    private func reactionSearchProbe() throws {
        let bubble = try seedReactionBubble("reactionsearch")
        let panel = openReactionPanel(on: bubble)
        guard panel.exists else { return }
        searchReaction("кошка", expecting: "🐈")
        shot("reactionsearch-cat")
        searchReaction("дякую", expecting: "🙏")
        shot("reactionsearch-thanks-uk")
        let rocket = searchReaction("ракета", expecting: "🚀")
        shot("reactionsearch-rocket")
        guard rocket.exists && rocket.isHittable else { return }
        rocket.tap()
        waitForReactionChip("🚀", beside: bubble)
        shot("reactionsearch-chosen-chip")
    }

    private func reactionRecentProbe() throws {
        let bubble = try seedReactionBubble("reactionrecent")
        let panel = openReactionPanel(on: bubble)
        guard panel.exists else { return }
        let rocket = searchReaction("ракета", expecting: "🚀")
        guard rocket.exists && rocket.isHittable else { return }
        rocket.tap()
        waitForReactionChip("🚀", beside: bubble)
        openReactionPanel(on: bubble)
        let recent = app.buttons["reaction-recent-🚀"]
        XCTAssertTrue(recent.waitForExistence(timeout: 5))
        XCTAssertTrue(recent.isHittable, "A successful send intent must put rocket in the local recent row")
        shot("reactionrecent-rocket-in-recent")
        dismissReactionPicker()
        // The isolated walk accounts have bounded fixture history. Each pair toggles this
        // bubble off then on; only the add intent is eligible to increase local usage.
        var ranked = false
        for use in 0...8 {
            bubble.press(forDuration: 0.8)
            XCTAssertTrue(app.buttons["reaction-pill-0"].waitForExistence(timeout: 5))
            ranked = (0..<7).contains { (app.buttons["reaction-pill-\($0)"].value as? String) == "🚀" }
            if ranked { shot("reactionrecent-rocket-in-pill-\(use)"); dismissReactionPicker(); break }
            if use == 8 { dismissReactionPicker(); break }
            let expand = app.buttons["reaction-expand"]
            guard expand.exists else { XCTFail("Missing expand button"); return }
            expand.tap()
            let pick = searchReaction("ракета", expecting: "🚀")
            guard pick.exists else { return }
            pick.tap() // remove mine
            bubble.press(forDuration: 0.8)
            app.buttons["reaction-expand"].tap()
            let add = searchReaction("ракета", expecting: "🚀")
            guard add.exists else { return }
            add.tap() // add mine: counts once
            waitForReactionChip("🚀", beside: bubble)
        }
        XCTAssertTrue(ranked, "After repeated send intents rocket must enter the personal top seven of the QA fixture")
    }

    /// XCTest wall timings include its accessibility/idling overhead. Compare the same
    /// runner on baseline/candidate; use the 5 s windows with Mac CPU sampling as well.
    private func reactionPickerTimingProbe() throws {
        let bubble = try seedReactionBubble("reactionpicker")
        var start = Date()
        bubble.press(forDuration: 0.8)
        let expand = app.buttons["reaction-expand"]
        let legacyMore = app.buttons.matching(NSPredicate(format: "label IN %@", ["More", "Ещё", "Еще", "Ще"])).firstMatch
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in expand.exists || legacyMore.exists }, object: app)
        XCTAssertEqual(XCTWaiter().wait(for: [ready], timeout: 5), .completed, "A candidate pill or the release's More button must appear")
        let candidate = expand.exists
        let mode = candidate ? "candidate" : "legacy"
        if candidate {
            XCTAssertTrue(reactionElement("reaction-pill").exists, "Candidate IDs are required once its expand button is present")
        } else {
            guard legacyMore.exists else { shot("reactionpicker-no-pill"); return }
        }
        print("WALK_PROBE reactionpicker mode=\(mode) longpress_to_pill_ms=\(Date().timeIntervalSince(start) * 1000)")
        start = Date()
        (candidate ? expand : legacyMore).tap()
        let field: XCUIElement
        if candidate {
            XCTAssertTrue(reactionElement("reaction-panel").waitForExistence(timeout: 5))
            field = app.textFields["reaction-search"]
        } else {
            // The shipped picker exposes its real placeholder, but no reaction IDs.
            field = textField(["Найти эмодзи", "Find emoji"])
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        guard field.exists else { shot("reactionpicker-no-search"); return }
        XCTAssertTrue(field.isHittable)
        print("WALK_PROBE reactionpicker mode=\(mode) expand_to_settled_ms=\(Date().timeIntervalSince(start) * 1000)")
        field.tap()
        start = Date()
        field.typeText("кошка")
        let result = candidate ? app.buttons["reaction-grid-🐈"] : app.buttons.matching(NSPredicate(format: "label == %@", "🐈")).firstMatch
        let empty = candidate ? reactionElement("reaction-no-results")
            : app.staticTexts.matching(NSPredicate(format: "label IN %@", ["Ничего не найдено", "No results"])).firstMatch
        let answered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in result.exists || empty.exists }, object: app)
        let answer = XCTWaiter().wait(for: [answered], timeout: 5)
        let outcome = result.exists ? "match" : empty.exists ? "empty" : "timeout"
        if candidate {
            XCTAssertEqual(answer, .completed)
            XCTAssertTrue(result.exists, "Candidate search must expose the matching emoji ID")
        }
        print("WALK_PROBE reactionpicker mode=\(mode) query_to_results_ms=\(Date().timeIntervalSince(start) * 1000) outcome=\(outcome)")
        field.typeText("\n")
        shot("reactionpicker-results")
        print("WALK_PROBE reactionpicker mode=\(mode) idle_open_begin=\(Date().timeIntervalSince1970)")
        sleep(5)
        print("WALK_PROBE reactionpicker mode=\(mode) idle_open_end=\(Date().timeIntervalSince1970)")
        if candidate {
            dismissReactionPicker()
        } else {
            let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["Close", "Закрыть", "Закрити"]))
                .allElementsBoundByIndex.first { $0.isHittable }
            if let close { close.tap() }
            else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.8)).tap() }
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: field)
            let didClose = XCTWaiter().wait(for: [closed], timeout: 5)
            XCTAssertEqual(didClose, .completed, "The release picker must close before measuring closed idle")
            guard didClose == .completed else { shot("reactionpicker-legacy-close-failed"); return }
        }
        print("WALK_PROBE reactionpicker mode=\(mode) idle_closed_begin=\(Date().timeIntervalSince1970)")
        sleep(5)
        print("WALK_PROBE reactionpicker mode=\(mode) idle_closed_end=\(Date().timeIntervalSince1970)")
    }

    /// Signs the simulator back in to its existing QA walk account (UNYRI_WALK_HANDLE /
    /// UNYRI_WALK_PASSWORD from the private walk config) when the app shows the sign-in screen.
    /// 19 Nov: a session bound by the device-bound build was refused to the older 0.4.24 app
    /// (bound sessions never downgrade), which ended the session on WalkAnya.
    private func signInExisting() throws {
        let menu = app.buttons["main-open-menu"]
        if menu.waitForExistence(timeout: 8) { print("WALK_PROBE signin already signed in"); return }
        let handle = env["UNYRI_WALK_HANDLE"]!, password = env["UNYRI_WALK_PASSWORD"]!
        shot("signin-screen")
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Server", "Сервер")).firstMatch
        let serverURL = env["UNYRI_WALK_SERVER"] ?? "http://127.0.0.1:18080"
        if server.waitForExistence(timeout: 10), !server.label.contains(serverURL.replacingOccurrences(of: "http://", with: "")) {
            server.tap()
            let field = app.textFields[serverPlaceholder].exists ? app.textFields[serverPlaceholder] : app.textFields.firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap()
            let current = (field.value as? String) ?? ""
            if current != serverURL {
                if !current.isEmpty && current != field.placeholderValue {
                    field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
                }
                field.typeText(serverURL)
            }
            for _ in 0..<3 where app.textFields[serverPlaceholder].exists {
                tapFirst(["Done", "Готово"], required: false); sleep(1)
            }
        }
        type(into: textField(["Private login", "Личный логин", "Особистий логін"]), handle)
        type(into: textField(["Password", "Пароль"]), password)
        app.typeText("\n")
        tapFirst(["Log in", "Войти", "Увійти"], required: false)
        let fresh = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "without restored", "без восстановлен")).firstMatch
        for _ in 0..<45 where !menu.exists && !fresh.exists { sleep(1) }
        if fresh.exists { shot("signin-history-choice"); fresh.tap() }
        XCTAssertTrue(menu.waitForExistence(timeout: 40), "sign-in did not reach the chat list")
        shot("signin-chat-list")
        print("WALK_PROBE signin signed in")
    }

    /// B-060 (owner, 19 Nov, on a screenshot of the sign-in screen: «Я увидел этот полукруг или что это?
    /// С логином и паролем»): the login and password fields sat in a glass capsule - semicircular sides
    /// (a shape made for 44 pt drawer controls stretched over a ~150 pt card) with the fields' own corners
    /// showing outside it. The card must be a rounded rectangle: 16 pt inside each of its corners is still
    /// glass, not the dark background. Checks the sign-in card, then the sign-up card.
    private func loginGlassProbe() throws {
        let login = [app.textFields["Private login"], app.textFields["Личный логин"]].first { $0.exists } ?? app.textFields["Личный логин"]
        XCTAssertTrue(login.waitForExistence(timeout: 30), "the sign-in screen")
        sleep(2)
        let password = [app.secureTextFields["Password"], app.secureTextFields["Пароль"], app.textFields["Password"], app.textFields["Пароль"]].first { $0.exists }
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Server", "Сервер")).firstMatch
        let card = app.otherElements["login-credentials"]
        let frame: CGRect
        if card.exists { frame = card.frame }
        else {
            // Builds before the fix have no card identifier: the card spans the Server button's width
            // and the two fields plus their 12 pt row padding and the 16 pt card padding.
            let bottom = password?.frame.maxY ?? login.frame.maxY + 60
            frame = CGRect(x: server.frame.minX, y: login.frame.minY - 28, width: server.frame.width, height: bottom + 28 - (login.frame.minY - 28))
        }
        shot("loginglass-signin")
        let signInResult = glassCornerFailures(frame)
        let signIn = signInResult.failures
        XCTAssertEqual(signInResult.checked.count, 4, "B-060: every sign-in card corner must be measured on screen")
        print("WALK_PROBE loginglass signin card=\(Int(frame.minX)),\(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height)) identified=\(card.exists) corners=\(signIn)")
        XCTAssertTrue(signIn.isEmpty, "B-060: the sign-in card is not a rounded rectangle: " + signIn.joined(separator: "; "))
        tapFirst(["Create an account", "Создать аккаунт"], required: false); sleep(2)
        let registerCard = app.otherElements["register-credentials"]
        if registerCard.waitForExistence(timeout: 5) {
            shot("loginglass-signup")
            // The sign-up card is taller than the screen (queue80: its bottom corners were off screen and read
            // as black): measure the corners on screen, scroll the card's bottom into view, measure the rest.
            var checked = Set<String>()
            var signUp: [String] = []
            func measure() {
                let result = glassCornerFailures(registerCard.frame)
                for name in result.checked where checked.insert(name).inserted {
                    signUp += result.failures.filter { $0.hasPrefix(name + " ") }
                }
            }
            measure()
            for _ in 0..<5 where checked.count < 4 { app.swipeUp(); sleep(1); measure() }
            shot("loginglass-signup-bottom")
            print("WALK_PROBE loginglass signup checked=\(checked.sorted()) corners=\(signUp)")
            XCTAssertEqual(checked.count, 4, "B-060: every sign-up card corner must be measured on screen")
            XCTAssertTrue(signUp.isEmpty, "B-060: the sign-up card is not a rounded rectangle: " + signUp.joined(separator: "; "))
        } else { print("WALK_PROBE loginglass signup card not identified (build before the fix)") }
    }

    /// Mean brightness (0...1) of a small square of the current screen, in points. The crop is drawn
    /// into an 8-bit RGBA context like looksBlank, so wide-colour screenshots read the same.
    private func screenBrightness(_ image: UIImage, around point: CGPoint, side: CGFloat = 6) -> CGFloat {
        guard let cg = image.cgImage else { return 0 }
        let scale = CGFloat(cg.width) / max(1, image.size.width)
        let rect = CGRect(x: (point.x - side / 2) * scale, y: (point.y - side / 2) * scale,
                          width: side * scale, height: side * scale).integral
        guard let crop = cg.cropping(to: rect) else { return 0 }
        let cells = 4
        var pixels = [UInt8](repeating: 0, count: cells * cells * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: cells, height: cells, bitsPerComponent: 8,
                bytesPerRow: cells * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(crop, in: CGRect(x: 0, y: 0, width: cells, height: cells))
            return true
        }
        guard drawn else { return 0 }
        var total: Double = 0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let red = Double(pixels[i]), green = Double(pixels[i + 1]), blue = Double(pixels[i + 2])
            total += (red + green + blue) / (3 * 255)
        }
        return CGFloat(total / Double(cells * cells))
    }

    /// Corners of a glass card whose 16 pt inset area shows the dark page instead of the glass.
    /// Only corners whose sample point is on screen (clear of the status bar and the home indicator) are
    /// measured; `checked` names them.
    private func glassCornerFailures(_ frame: CGRect) -> (checked: [String], failures: [String]) {
        let image = XCUIScreen.main.screenshot().image
        let window = app.windows.firstMatch.frame
        let visible = CGRect(x: window.minX, y: window.minY + 60, width: window.width, height: max(0, window.height - 100))
        let inset: CGFloat = 16
        let corners: [(String, CGPoint)] = [
            ("top-left", CGPoint(x: frame.minX + inset, y: frame.minY + inset)),
            ("top-right", CGPoint(x: frame.maxX - inset, y: frame.minY + inset)),
            ("bottom-left", CGPoint(x: frame.minX + inset, y: frame.maxY - inset)),
            ("bottom-right", CGPoint(x: frame.maxX - inset, y: frame.maxY - inset)),
        ]
        // The card glass is about 0.13-0.18; the page is near black with thin pattern lines.
        let onScreen = corners.filter { visible.contains($0.1) }
        let failures: [String] = onScreen.compactMap { name, point in
            let value = screenBrightness(image, around: point)
            return value < 0.08 ? name + String(format: " %.3f", value) : nil
        }
        return (onScreen.map(\.0), failures)
    }

    private func register(login2: Bool = false) throws {
        if login2 {
            let origin = URL(string: env["UNYRI_WALK_SERVER"] ?? "http://127.0.0.1:18080")
            try XCTSkipUnless(["localhost", "127.0.0.1", "[::1]", "::1"].contains(origin?.host ?? ""), "login2 registration only uses an isolated loopback fixture")
        }
        // login2 signs up a brand-new account on every run (the first run's login is taken
        // afterwards; 18 Nov walk). Logins allow 3-32 latin letters, digits, '_' and '.'.
        let baseHandle = env["UNYRI_WALK_HANDLE"]!
        let handle = login2 ? String((baseHandle + "_" + String(Int(Date().timeIntervalSince1970) % 1_000_000)).prefix(32)) : baseHandle
        let display = env["UNYRI_WALK_DISPLAY"] ?? baseHandle
        let password = env["UNYRI_WALK_PASSWORD"]!
        shot("login")
        let server = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Server", "Сервер")).firstMatch
        XCTAssertTrue(server.waitForExistence(timeout: 20)); server.tap()
        let field = app.textFields[serverPlaceholder].exists ? app.textFields[serverPlaceholder] : app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap()
        // Cmd-A needs a connected hardware keyboard; with the software keyboard it
        // appended the URL to a prefilled one (18 Nov). Delete what is there instead.
        let serverURL = env["UNYRI_WALK_SERVER"] ?? "http://127.0.0.1:18080"
        let current = (field.value as? String) ?? ""
        if current != serverURL {
            if !current.isEmpty && current != field.placeholderValue {
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
            }
            field.typeText(serverURL)
        }
        shot("server")
        for _ in 0..<3 where app.textFields[serverPlaceholder].exists {
            tapFirst(["Done", "Готово"], required: false); sleep(1)
        }
        tapFirst(["Create an account", "Создать аккаунт"])
        shot("register-form")
        type(into: textField(["Your name", "Ваше имя"]), display); app.typeText("\n")
        type(into: app.textFields["register-login"], handle)
        tapFirst(["Show password", "Показать пароль"])
        type(into: textField(["Password", "Пароль"]), password); app.typeText("\n")
        type(into: textField(["Confirm password", "Повторите пароль", "Подтвердите пароль"]), password); app.typeText("\n")
        tapFirst(["Hide password", "Скрыть пароль"])
        let agreement = app.switches.firstMatch
        if agreement.exists {
            let knob = agreement.switches.firstMatch
            (knob.exists ? knob : agreement).tap()
        }
        shot("register-filled")
        tapFirst(["Register", "Зарегистрироваться"])
        try expectNoSignupRecoveryGate()
        let fresh = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "without restored", "без восстановления")).firstMatch
        _ = fresh.waitForExistence(timeout: 40)
        if fresh.exists { shot("history-choice"); fresh.tap() }
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        shot("chat-list-empty")
        app.buttons["main-open-menu"].tap(); sleep(1); shot("drawer")
        if login2 {
            XCTAssertTrue(app.buttons["drawer-public-number"].waitForExistence(timeout: 15))
            XCTAssertFalse(app.staticTexts["@" + handle].exists, "The private login must not appear on the drawer card")
            shot("login2-drawer-number")
            return
        }
        let saved = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Saved Messages", "Избранное")).firstMatch
        if saved.waitForExistence(timeout: 5) {
            saved.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 20)); shot("saved-empty")
            send("Заметка для себя 📝"); send("Список дел:\n1. Проверить UI\n2. Проверить скорость\n3. Залить в TestFlight")
            shot("saved-with-notes")
            back()
        }
        shot("chat-list-after-saved")
    }

    /// B-049 (owner 19 Nov, «зачем это сразу после регистрации, убери»; «после удачной
    /// регистрации пользователь попадал в приложение»): a successful sign-up lands on the chat
    /// list, with no recovery key gate and no history choice. The key is created later in
    /// Settings -> «Восстановление аккаунта». Fails on any screen in between.
    private func expectNoSignupRecoveryGate() throws {
        // The first-run notification prompt can appear right after sign-up (18 Nov walk);
        // answer it on the next interaction so it cannot cover the app.
        addUIInterruptionMonitor(withDescription: "Notification permission") { alert in
            for label in ["Allow", "Разрешить", "Дозволити"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
        let consent = app.buttons.matching(NSPredicate(format: "label IN %@", ["Use this HTTP server", "Использовать этот HTTP-сервер", "Використовувати цей HTTP-сервер"])).firstMatch
        if consent.waitForExistence(timeout: 2) { consent.tap() }
        let gate = [app.staticTexts["login2-signup-recovery"], app.buttons["login2-recovery-confirm"], app.switches["login2-recovery-saved"]]
        let landed = app.buttons["main-open-menu"]
        let fresh = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "without restored", "без восстановления")).firstMatch
        let deadline = Date().addingTimeInterval(45)
        while Date() < deadline {
            if gate.contains(where: { $0.exists }) {
                shot("signup-recovery-gate")
                XCTFail("sign-up showed the recovery key gate (B-049)")
                return
            }
            // A brand-new account has no earlier history: the history choice must not appear either
            // (owner 19 Nov: «после удачной регистрации пользователь попадал в приложение»).
            if fresh.exists {
                shot("signup-history-choice")
                XCTFail("sign-up showed the history choice before the app (B-049)")
                return
            }
            if landed.exists { shot("signup-landed"); return }
            usleep(300_000)
        }
        shot("signup-not-landed")
        XCTFail("sign-up did not reach the app")
    }

    private func message() throws {
        let peer = env["UNYRI_WALK_PEER"]!
        openChat(with: peer)
        shot("chat-new-empty")
        send("Привет! 👋")
        send("Как дела? Смотрю новый интерфейс чата")
        send("Это длинное сообщение, чтобы проверить, как пузырь переносит строки и где оказывается время отправки. Должно выглядеть аккуратно и читаемо, как в нормальных мессенджерах.")
        send("😂")
        send("Ссылка: https://telegram.org/blog")
        shot("chat-outgoing-sent")
        composer.tap(); sleep(1); shot("chat-keyboard-open")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.45)).tap(); sleep(1); shot("chat-tap-dismissed")
        longPress(lastBubble(containing: "Как дела")); shot("chat-message-menu")
        dismissMenu()
        back(); shot("chat-list-with-chat")
    }

    private func inbox() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        sleep(4); shot("inbox-list")
        let row = chatRow(peerDisplay)
        XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(3)
        shot("inbox-chat-incoming")
        send("Привет! Всё отлично 🙂")
        send("Отвечаю на несколько сообщений подряд, чтобы проверить группировку")
        send("И ещё одно короткое")
        shot("inbox-chat-mixed")
        let incoming = lastBubble(containing: "Как дела")
        if incoming.exists { longPress(incoming); shot("inbox-incoming-menu"); tapFirst(["❤️", "👍"], required: false); sleep(1) }
        shot("inbox-after-reaction")
        let options = app.buttons["chat-options"]
        if options.exists { options.tap(); sleep(1); shot("inbox-chat-menu"); tapFirst(["Close", "Закрыть"], required: false) }
        if options.exists {
            options.tap(); sleep(1)
            let search = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Search messages", "Поиск сообщений")).firstMatch
            if search.waitForExistence(timeout: 5) {
                search.tap(); sleep(1)
                let field = app.textFields["chat-inline-search-field"]
                if field.waitForExistence(timeout: 5) {
                    field.typeText("дела"); sleep(2); shot("inbox-search-results")
                    let older = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Older result", "Предыдущее совпадение")).firstMatch
                    if older.exists && older.isEnabled { older.tap(); sleep(2); shot("inbox-search-older") }
                    app.buttons["chat-inline-search-cancel"].tap(); sleep(1); shot("inbox-search-closed")
                }
            }
        }
        let identity = app.descendants(matching: .any).matching(identifier: "chat-header-identity").firstMatch
        if identity.waitForExistence(timeout: 5) {
            identity.tap(); sleep(2); shot("inbox-peer-profile")
            let close = app.buttons["peer-profile-close"]
            if close.waitForExistence(timeout: 5) { close.tap(); sleep(1) }
        }
        // Coordinate drags: fast XCUITest swipes from the centre land on bubbles and do not scroll.
        for _ in 0..<4 { dragTimeline(from: 0.3, to: 0.8) }
        sleep(1); shot("inbox-scrolled-top")
        for _ in 0..<12 { dragTimeline(from: 0.75, to: 0.25) }
        sleep(1); shot("inbox-scrolled-bottom")
        back(); sleep(1); shot("inbox-list-after")
        let again = chatRow(peerDisplay)
        if again.waitForExistence(timeout: 10) {
            again.tap()
            // A burst during entry catches visible jumps that a settled screenshot hides.
            for i in 0..<6 { shot("inbox-reopen-burst-\(i)"); usleep(60_000) }
            sleep(2); shot("inbox-reopened-position")
        }
    }

    /// Voice hold: tap shows the hint, hold+release sends, hold+slide-left cancels.
    private func voiceProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        // A draft left by another role (the emoji role leaves three emoji) is sent:
        // that also produces an emoji-only message for the big-emoji check.
        if composer.exists && (composer.value as? String ?? "").isEmpty == false && app.buttons["chat-composer-send"].exists {
            app.buttons["chat-composer-send"].tap(); sleep(2); shot("voice-big-emoji-sent")
        }
        let staleDraft = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Delete recording", "Удалить запись")).firstMatch
        if staleDraft.waitForExistence(timeout: 2) { staleDraft.tap(); sleep(1) }
        let mic = app.buttons["chat-composer-record"]
        guard mic.waitForExistence(timeout: 5) else { shot("voice-no-mic"); return }
        let center = mic.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // A tap switches voice <-> video message; make sure holds record voice.
        if (mic.value as? String) == "video" { center.press(forDuration: 0.05); sleep(2) }
        center.press(forDuration: 0.05); usleep(300_000); shot("voice-tap-hint")
        sleep(2)
        center.press(forDuration: 0.05); sleep(2)   // back to voice
        print("VOICE mode after taps=\(mic.value as? String ?? "?")")
        center.press(forDuration: 0.5); usleep(500_000); shot("voice-short-hold-discarded")
        sleep(3)
        center.press(forDuration: 2.5); sleep(3); shot("voice-hold-release-sent")
        // Partial slides (frames captured from outside by simctl while held): both still send.
        center.press(forDuration: 2.0, thenDragTo: center.withOffset(CGVector(dx: -60, dy: 0)), withVelocity: 80, thenHoldForDuration: 2.0)
        sleep(2)
        center.press(forDuration: 1.0, thenDragTo: center.withOffset(CGVector(dx: 0, dy: -50)), withVelocity: 80, thenHoldForDuration: 2.0)
        sleep(2); shot("voice-partial-slides-sent")
        center.press(forDuration: 1.2, thenDragTo: center.withOffset(CGVector(dx: -200, dy: 0)), withVelocity: 600, thenHoldForDuration: 0.3)
        sleep(1); shot("voice-slide-cancel")
        center.press(forDuration: 1.0, thenDragTo: center.withOffset(CGVector(dx: 0, dy: -140)), withVelocity: 500, thenHoldForDuration: 0.3)
        sleep(2); shot("voice-locked")
        let done = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Review", "Прослуш")).firstMatch
        if done.waitForExistence(timeout: 2) { done.tap(); sleep(1); shot("voice-locked-review") }
        if staleDraft.waitForExistence(timeout: 2) { staleDraft.tap(); sleep(1); shot("voice-review-deleted") }
    }

    /// Call button: the call screen must appear at once; a double tap must not
    /// claim another call is active.
    /// B-067 (owner screenshot 20 Nov, outgoing call «Пока не в сети»): microphone, End and output sat ~38 pt right of
    /// centre because the not-yet-usable video button kept an empty square on the left. While calling, the visible
    /// buttons must be centred on the screen (±2 pt) and no invisible video slot may exist.
    private func callButtonsProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        XCTAssertTrue(call.waitForExistence(timeout: 5), "the chat offers a call"); call.tap()
        let end = app.buttons["call-end"]
        XCTAssertTrue(end.waitForExistence(timeout: 5), "the call screen shows End")
        sleep(2); shot("callbuttons-calling")
        let screen = app.windows.firstMatch.frame
        let row = ["call-mute", "call-end", "call-output"].map { app.descendants(matching: .any)[$0].firstMatch }
            .filter { $0.exists && $0.isHittable }.map(\.frame)
        let left = row.map(\.minX).min() ?? end.frame.minX, right = row.map(\.maxX).max() ?? end.frame.maxX
        let offset = (left + right) / 2 - screen.midX
        print("WALK_PROBE callbuttons visible=\(row.count) left=\(Int(left)) right=\(Int(right)) offset=\(String(format: "%.1f", offset))")
        XCTAssertLessThanOrEqual(abs(offset), 2, "B-067: the call buttons must be centred while calling (offset \(offset) pt)")
        end.tap(); sleep(2)
        app.buttons["chat-navigation-back"].firstMatch.tap(); sleep(1)
    }

    private func callStartProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        guard call.waitForExistence(timeout: 3) else { shot("callstart-no-button"); return }
        let latest = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Latest messages", "К последним сообщениям")).firstMatch
        if latest.exists { latest.tap(); sleep(1) }
        shot("callstart-before")
        call.tap(); usleep(250_000); shot("callstart-instant")
        // The screen must show its controls, not only the backdrop (0.4.12 build 56 drew none).
        let endCall = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        XCTAssertTrue(endCall.waitForExistence(timeout: 3), "call screen shows no controls")
        XCTAssertTrue(app.staticTexts[env["UNYRI_WALK_PEER_DISPLAY"] ?? ""].exists, "call screen shows no name")
        sleep(7); shot("callstart-after-7s")
        guard requireLiveCall() else { return }
        let end = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "End", "Заверш")).firstMatch
        if end.waitForExistence(timeout: 3) { end.tap() }
        sleep(3); shot("callstart-ended")
        app.buttons["chat-navigation-back"].firstMatch.tap(); sleep(1)
        openChat(with: env["UNYRI_WALK_PEER"]!); sleep(2); shot("callstart-reopened")
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", "звонок")).allElementsBoundByIndex.last
        if let row, row.isHittable { row.tap(); usleep(300_000); shot("callstart-row-callback") }
    }

    /// B-047 (0.4.22 build 68, both iPhones of a call): shaking the phone
    /// during a call aborted the app. The owner's steps: call, shake while
    /// the call is live; the app must stay on the call. After the call the
    /// same shake must still open the report (the feature itself works).
    /// Needs walk.py with the host shake service (Simulator's Device → Shake).
    private func callShakeProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        guard call.waitForExistence(timeout: 5) else { XCTFail("call button missing"); return }
        let latest = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Latest messages", "К последним сообщениям")).firstMatch
        if latest.exists { latest.tap(); sleep(1) }
        call.tap()
        guard requireLiveCall() else { return }
        shot("callshake-live")
        guard hostShake("in-call") else { return }
        // 0.4.22 left for the home screen a few seconds after the shake while an
        // immediate state read still said foreground (19 Nov run): wait for it.
        if app.wait(for: .notRunning, timeout: 6) || app.state != .runningForeground {
            shot("callshake-app-gone")
            XCTFail("the app quit after a shake during the call (B-047), state \(app.state.rawValue)")
            return
        }
        shot("callshake-after-shake")
        XCTAssertTrue(requireLiveCall(), "the call ended after a shake during it (B-047)")
        let end = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        if end.waitForExistence(timeout: 3) { end.tap() }
        sleep(4)
        shot("callshake-ended")
        guard hostShake("after-call") else { return }
        let report = app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", "Report a problem", "Сообщить о проблеме")).firstMatch
        XCTAssertTrue(report.waitForExistence(timeout: 6), "a shake outside a call no longer opens the report")
        XCTAssertEqual(app.state, .runningForeground, "the app quit after a shake outside a call")
        shot("callshake-report")
        let done = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Done", "Готово")).firstMatch
        if done.waitForExistence(timeout: 3) { done.tap(); sleep(1) }
    }

    /// Asks walk.py on the Mac to shake this simulator (Simulator's Device →
    /// Shake): a request file in the capture folder, answered with a `.done`
    /// file once `notifyutil` delivered the shake.
    private func hostShake(_ name: String) -> Bool {
        let request = out.appendingPathComponent("host-shake-" + name + ".request")
        let done = out.appendingPathComponent("host-shake-" + name + ".done")
        guard FileManager.default.createFile(atPath: request.path, contents: Data()) else {
            XCTFail("could not write the host shake request"); return false
        }
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if FileManager.default.fileExists(atPath: done.path) { return true }
            usleep(200_000)
        }
        XCTFail("the Mac did not shake the simulator (walk.py without the host shake service?)")
        return false
    }

    /// Inspect only the call capsule: ended calls can retain a duration too,
    /// but their capsule is disabled. Chat message timestamps are never used.
    private func liveCallStatus(_ status: XCUIElement, connectedOnly: Bool = false) -> Bool {
        guard status.exists, status.isEnabled else { return false }
        if status.label.range(of: "[0-9]{1,2}:[0-9]{2}", options: .regularExpression) != nil { return true }
        guard !connectedOnly else { return false }
        return ["Calling", "Ringing", "Not online yet", "Вызов", "Звонит", "Пока не в сети",
                "Виклик", "Телефонує", "Поки не в мережі"].contains { status.label.contains($0) }
    }

    @discardableResult
    private func requireLiveCall(connectedOnly: Bool = false, timeout: TimeInterval = 5) -> Bool {
        let status = app.descendants(matching: .any)["call-status"]
        let live = NSPredicate { _, _ in self.liveCallStatus(status, connectedOnly: connectedOnly) }
        let ready = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: live, object: status)], timeout: timeout) == .completed
        if !ready {
            shot("call-not-live")
            if connectedOnly && liveCallStatus(status) { XCTFail("paired call did not connect before rotation") }
            else { XCTFail("call ended before rotation (B-006)") }
        }
        return ready
    }

    /// Pair with WalkAnya callanswer. Ringing must be live first; rotation
    /// measurements begin only after the call capsule shows a running timer.
    private func callRotateProbe() throws {
        defer { XCUIDevice.shared.orientation = .portrait }
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        guard call.waitForExistence(timeout: 5) else { XCTFail("call button missing"); return }
        call.tap()
        guard requireLiveCall() else { return }
        shot("callrotate-ringing-or-connected")
        guard requireLiveCall(connectedOnly: true, timeout: 40) else { return }
        print("CALL rotate connected=true")
        shot("callrotate-portrait")
        guard rotateCall(.landscapeLeft, shotName: "callrotate-landscape-left"),
              rotateCall(.landscapeRight, shotName: "callrotate-landscape-right"),
              rotateCall(.portrait, shotName: "callrotate-back-portrait"),
              rotateCall(.landscapeLeft, shotName: "callrotate-before-minimize") else { return }
        let minimize = app.buttons["call-minimize"]
        guard minimize.isHittable else { XCTFail("minimize is unreachable"); return }
        minimize.tap()
        sleep(2); shot("callrotate-minimized-portrait")
        XCTAssertLessThan(app.frame.width, app.frame.height, "Chats must be portrait before the morph uncovers them")
        let expand = app.buttons["call-return"]
        guard expand.waitForExistence(timeout: 5) else { XCTFail("call ended before rotation (B-006)"); return }
        expand.tap()
        usleep(200_000); shot("callrotate-expanding-portrait")
        sleep(2)
        guard requireLiveCall(connectedOnly: true) else { return }
        shot("callrotate-expanded-landscape")
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        guard rotateCall(.portrait, shotName: "callrotate-final-portrait"),
              rotateCall(.landscapeRight, shotName: "callrotate-before-end-landscape") else { return }
        app.buttons["call-end"].tap()
        usleep(250_000); shot("callrotate-ended-landscape")
        sleep(4); shot("callrotate-ended-portrait-chats")
        XCTAssertLessThan(app.frame.width, app.frame.height, "Closing a landscape audio call must restore portrait")
    }

    private func rotateCall(_ orientation: UIDeviceOrientation, shotName: String) -> Bool {
        guard requireLiveCall(connectedOnly: true) else { return false }
        XCUIDevice.shared.orientation = orientation
        sleep(2)
        guard requireLiveCall(connectedOnly: true) else { return false }
        shot(shotName)
        if orientation == .portrait { XCTAssertLessThan(app.frame.width, app.frame.height) }
        else { XCTAssertGreaterThan(app.frame.width, app.frame.height) }
        XCTAssertTrue(app.buttons["call-end"].isHittable, "End stays reachable after rotation")
        return true
    }

    /// The banner walkthrough now minimizes a real call instead of injecting
    /// a synthetic island. A paired callee can keep it connected for inspection.
    private func callBannerProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        guard call.waitForExistence(timeout: 5) else { XCTFail("call button missing"); return }
        call.tap()
        guard requireLiveCall() else { return }
        app.buttons["call-minimize"].tap()
        sleep(2); shot("banner-chat")
        app.buttons["chat-navigation-back"].firstMatch.tap()
        let contactsBack = app.buttons["contacts-back"]
        if contactsBack.exists { contactsBack.tap() }
        sleep(2); shot("banner-chats")
        let expand = app.buttons["call-return"]
        guard expand.exists else { XCTFail("real call banner missing"); return }
        expand.tap()
        if app.buttons["call-end"].waitForExistence(timeout: 5) { app.buttons["call-end"].tap() }

    }

    private func assertScreenOrientation(landscape: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let size = XCUIScreen.main.screenshot().image.size
        if landscape { XCTAssertGreaterThan(size.width, size.height, "Screenshot must really be landscape", file: file, line: line) }
        else { XCTAssertLessThan(size.width, size.height, "Screenshot must really be portrait", file: file, line: line) }
    }

    /// Run only once video is negotiated. Existing pair roles may start as
    /// audio and upgrade later; inspect the real camera control each pass.
    private func rotateVideoCallIfPresent(_ prefix: String) {
        guard app.buttons["call-camera"].exists else { return }
        rotateCall(.landscapeLeft, shotName: prefix + "-video-landscape-left")
        rotateCall(.landscapeRight, shotName: prefix + "-video-landscape-right")
        rotateCall(.portrait, shotName: prefix + "-video-portrait")
    }

    /// Emoji panel in the keyboard's place: focus, 🙂, insert, ⌨️ back, blur.
    private func emojiProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        composer.tap(); sleep(1); shot("emoji-keyboard-up")
        app.buttons["chat-composer-emoji"].tap(); sleep(1); shot("emoji-panel")
        let grid = app.descendants(matching: .any)["emoji-keyboard"]
        if grid.waitForExistence(timeout: 2) {
            app.buttons["😀"].firstMatch.tap(); app.buttons["😍"].firstMatch.tap(); app.buttons["😂"].firstMatch.tap()
            usleep(400_000); shot("emoji-inserted")
            app.buttons["Флаги"].firstMatch.tap(); sleep(1); shot("emoji-flags")
        }
        app.buttons["chat-composer-emoji"].tap(); sleep(1); shot("emoji-back-to-keyboard")
        app.buttons["chat-composer-emoji"].tap(); sleep(1)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)).tap()
        sleep(1); shot("emoji-blurred")
        composer.tap(); sleep(1); shot("emoji-refocus-keyboard")
    }

    /// Attach sheet Files tab: actions, recents (seeded by the runner), tick two, send.
    /// Requires a dedicated Photos library seeded with HEIC, PNG, MOV and MP4
    /// fixtures; see docs/SHARE_PHOTOS_WALKTHROUGH.md. No in-app ShareLink shortcut.
    private func photosShareProbe() throws {
        let savedNames = NSPredicate(format: "label == %@ OR label == %@ OR label == %@", "Saved Messages", "Избранное", "Збережене")
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); menu.tap()
        let saved = app.buttons.matching(savedNames).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10)); saved.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20))
        send("Photos share walkthrough anchor")
        sleep(3) // let the event-driven 2 s target snapshot publish
        let imageTiles = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "chat-album-photo", "chat-photo"))
        let videoTiles = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR (identifier BEGINSWITH %@ AND NOT identifier BEGINSWITH %@)", "chat-album-video", "chat-video-", "chat-video-note"))
        let beforeImages = imageTiles.count, beforeVideos = videoTiles.count
        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.launchArguments = app.launchArguments
        photos.launch()
        let library = photos.buttons.matching(NSPredicate(format: "label == %@ OR label == %@ OR label == %@", "Library", "Медиатека", "Медіатека")).firstMatch
        if library.waitForExistence(timeout: 3) { library.tap() }
        let select = photos.buttons.matching(NSPredicate(format: "label == %@ OR label == %@ OR label == %@", "Select", "Выбрать", "Вибрати")).firstMatch
        guard select.waitForExistence(timeout: 10) else {
            shot("photos-no-select", application: photos); XCTFail("Photos library is not ready; inspect the captured Photos tree"); return
        }
        select.tap()
        let mediaLabels = NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Photo", "Video", "Screenshot", "Фото", "Видео", "Відео", "Снимок", "Знімок", "Фотограф")
        var thumbnails = photos.images.matching(mediaLabels).allElementsBoundByIndex.filter(\.isHittable)
        if thumbnails.count < 4 { thumbnails = photos.cells.matching(mediaLabels).allElementsBoundByIndex.filter(\.isHittable) }
        guard thumbnails.count == 4 else {
            shot("photos-wrong-fixtures", application: photos); XCTFail("Use a dedicated Photos library with exactly HEIC + PNG + MOV + MP4 fixtures"); return
        }
        for thumbnail in thumbnails { thumbnail.tap() }
        shot("photos-four-selected", application: photos)
        let share = photos.buttons.matching(NSPredicate(format: "label == %@ OR label == %@ OR label == %@", "Share", "Поделиться", "Поділитися")).firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 5)); share.tap()
        let service = XCUIApplication(bundleIdentifier: "com.apple.UIKit.activityviewservice")
        let host = service.state != .notRunning && service.buttons["UNYRI"].exists ? service : photos
        let unyri = host.buttons["UNYRI"].firstMatch
        guard unyri.waitForExistence(timeout: 10) else {
            shot("photos-unyri-not-favorited", application: host); XCTFail("Enable/favorite UNYRI in the system share sheet before this walkthrough"); return
        }
        unyri.tap()
        let extensionHost = photos.buttons["share-close"].waitForExistence(timeout: 5) ? photos : service
        guard extensionHost.buttons["share-close"].waitForExistence(timeout: 10) else {
            shot("photos-extension-missing", application: extensionHost); XCTFail("Share extension UI did not appear"); return
        }
        shot("photos-extension-picker", application: extensionHost)
        let target = extensionHost.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "share-target-")).matching(savedNames).firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 20)); target.tap()
        let caption = "Photos mix " + UUID().uuidString.lowercased()
        let field = extensionHost.textFields["share-caption"].waitForExistence(timeout: 3) ? extensionHost.textFields["share-caption"] : extensionHost.textViews["share-caption"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText(caption)
        shot("photos-extension-caption", application: extensionHost)
        let confirm = extensionHost.buttons["share-confirm"]
        let ready = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: confirm)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
        confirm.tap()
        sleep(2)
        app.activate()
        XCTAssertTrue(composer.waitForExistence(timeout: 20))
        XCTAssertFalse(app.descendants(matching: .any)["import-file-sheet"].exists, "A committed share job must not show the import picker")
        let correctKinds = expectation(for: NSPredicate { _, _ in imageTiles.count >= beforeImages + 2 && videoTiles.count >= beforeVideos + 2 }, evaluatedWith: app)
        XCTAssertEqual(XCTWaiter.wait(for: [correctKinds], timeout: 60), .completed, "HEIC/PNG must appear as photos and MOV/MP4 as videos")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", caption)).firstMatch.waitForExistence(timeout: 10))
        shot("photos-share-correct-kinds")
    }

    private func filesProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        app.buttons["chat-composer-attach"].tap(); sleep(1); shot("files-attach-menu")
        app.buttons["Файлы"].firstMatch.tap(); sleep(2); shot("files-tab")
        let rows = app.buttons.matching(identifier: "files-recent-row")
        if rows.count >= 2 {
            rows.element(boundBy: 0).tap(); rows.element(boundBy: 1).tap(); usleep(400_000); shot("files-two-ticked")
            let caption = app.textFields["gallery-caption"].exists ? app.textFields["gallery-caption"] : app.textViews["gallery-caption"]
            caption.tap(); caption.typeText("Два файла"); shot("files-caption")
            app.buttons["gallery-add"].tap(); sendTray("files"); sleep(4); shot("files-sent")
            app.buttons["chat-composer-attach"].tap(); sleep(1)
            app.buttons["Файлы"].firstMatch.tap(); sleep(2); shot("files-tab-after-send")
        }
        app.buttons["files-media-as-files"].tap(); sleep(1); shot("files-media-as-files")
    }

    /// Fills the chat with many messages of different lengths (history paging, perf).
    private func floodProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let lines = ["Ок", "Да, согласен", "Сейчас посмотрю и напишу подробнее, подожди пару минут",
                     "👍", "А что насчёт завтрашней встречи? Мы успеваем всё подготовить к десяти утра или лучше перенести на обед?",
                     "Хорошо", "Отправил файлы на почту", "Длинное сообщение, чтобы проверить перенос строк: пузырь должен аккуратно расти вниз, время оставаться внутри справа, а лента — прокручиваться плавно без рывков"]
        for i in 0..<150 {
            let field = composer
            field.tap()
            field.typeText("\(i + 1). " + lines[i % lines.count])
            app.buttons["chat-composer-send"].tap()
        }
        sleep(5); shot("flood-end")
    }

    /// Scroll to the very top of a long chat, with a burst of frames while older pages load.
    private func topScrollProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
        for i in 0..<14 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.25))
                .press(forDuration: 0.02, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.9)),
                       withVelocity: 3000, thenHoldForDuration: 0)
            if i >= 10 { for j in 0..<4 { shot("top-\(i)-\(j)"); usleep(120_000) } } else { usleep(400_000) }
        }
        sleep(2); shot("top-final")
    }

    /// Load for profiling: open the chat three times, then fast flings both ways.
    private func perfProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(6)
        for _ in 0..<3 {
            let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
            back(); sleep(2)
        }
        let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
        func fling(_ from: CGFloat, _ to: CGFloat) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: from))
                .press(forDuration: 0.02, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: to)),
                       withVelocity: 2500, thenHoldForDuration: 0)
        }
        for _ in 0..<10 { fling(0.3, 0.85); usleep(350_000) }
        for _ in 0..<10 { fling(0.8, 0.25); usleep(350_000) }
        shot("perf-end")
    }

    /// Slow edge pull of the side panel (device feedback: it "froze" when pulled slowly).
    private func drawerProbe(card: Bool = false) throws {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        shot("drawer-closed")
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: 120, thenHoldForDuration: 0.3)
        sleep(1); shot("drawer-slow-open")
        let close = app.buttons["drawer-close"]
        if close.exists { close.tap() } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap() }
        sleep(1); shot("drawer-closed-again")
        app.buttons["main-open-menu"].tap(); sleep(1); shot("drawer-button-open")
        XCTAssertTrue(app.descendants(matching: .any)[card ? "drawer-layout-c" : "drawer-layout-a"].firstMatch.waitForExistence(timeout: 3))
        XCTAssertFalse(app.descendants(matching: .any)[card ? "drawer-layout-a" : "drawer-layout-c"].exists)
        let status = revealDrawerControl("drawer-status")
        if status.waitForExistence(timeout: 5) {
            status.tap(); sleep(1); shot("drawer-status-menu")
            let invisible = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Невидимка", "Invisible")).firstMatch
            if invisible.waitForExistence(timeout: 3) { invisible.tap(); sleep(2); shot("drawer-status-invisible") }
            status.tap(); sleep(1)
            // Menu items carry their symbol as identifier; "В сети" is also a chat-list filter tab.
            let online = app.buttons["circle.fill"]
            if online.waitForExistence(timeout: 3) { online.tap(); sleep(2); shot("drawer-status-online") }
        }
        if card { XCTAssertTrue(app.descendants(matching: .any)["drawer-profile-card"].exists) }
        let add = app.buttons["drawer-add-account"]
        let other = app.buttons["drawer-account-1"]
        XCTAssertTrue(add.exists || other.exists, "A signed-in drawer must expose its account action")
        XCTAssertFalse(add.exists && other.exists, "Accounts must show the other avatar OR Add")
        for id in ["drawer-server", "drawer-chats", "drawer-contacts", "drawer-calls", "drawer-rooms",
                   "drawer-saved", "drawer-archive", "drawer-transfers", "drawer-settings", "drawer-help", "drawer-report"] {
            let item = revealDrawerControl(id)
            XCTAssertTrue(item.exists && item.isHittable, id)
        }
        shot("drawer-secondary-scrolled")
        XCTAssertTrue(revealDrawerControl("drawer-version").exists)
        _ = revealDrawerControl("drawer-profile")
        shot("drawer-profile-restored")
        // One search (owner, 14 Nov): the chat list's search button, not the drawer.
        XCTAssertFalse(app.buttons["drawer-search"].exists, "the drawer has no search field")
        revealDrawerControl("drawer-chats").tap(); sleep(1)
        app.buttons["chats-search"].tap()
        XCTAssertTrue(app.textFields["search-query"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5)); shot("chats-search-keyboard")
        app.buttons["search-close"].tap()
        app.buttons["main-open-menu"].tap()
        revealDrawerControl("drawer-report").tap(); sleep(1); shot("drawer-report-screen")
        back(); sleep(1)
        app.buttons["main-open-menu"].tap()
        revealDrawerControl("drawer-server").tap(); sleep(1); shot("drawer-server-link")
        back(); sleep(1)
        app.buttons["main-open-menu"].tap()
        let qr = revealDrawerControl("drawer-qr")
        if qr.waitForExistence(timeout: 5) {
            qr.tap(); sleep(2); shot("drawer-qr-sheet")
            XCTAssertTrue(app.images["profile-qr-image"].waitForExistence(timeout: 5))
            let done = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Done", "Готово")).firstMatch
            if done.waitForExistence(timeout: 3) { done.tap(); sleep(1) }
        }
    }

    /// Use the actual Settings path; Cancel verifies that the layout choice is already saved.
    private func selectDrawer(_ choice: String) {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        app.buttons["main-open-menu"].tap()
        revealDrawerControl("drawer-settings").tap()
        func reveal(_ identifier: String) -> XCUIElement {
            let element = app.buttons[identifier]
            for _ in 0..<10 {
                if element.exists && element.isHittable { return element }
                app.swipeUp(); usleep(250_000)
            }
            XCTFail("Settings control not reachable: " + identifier)
            return element
        }
        reveal("settings-appearance").tap()
        reveal("sidebar-appearance-open").tap(); sleep(1); shot("drawer-choice-editor-" + choice)
        let option = reveal("sidebar-appearance-choice-" + choice)
        option.tap(); sleep(1)
        shot("drawer-choice-" + choice)
        // Selection must keep the editor and the chosen control on screen, including XL text.
        let selectedOption = app.buttons["sidebar-appearance-choice-" + choice]
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "sidebar-appearance-editor").firstMatch.exists)
        XCTAssertTrue(selectedOption.isHittable, "The chosen layout must remain visible after its preview changes")
        XCTAssertTrue(selectedOption.isSelected)
        app.buttons["sidebar-appearance-cancel"].tap()
        back(); back()
        app.terminate(); app.launch() // Confirm per-device persistence across a relaunch.
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
    }

    /// At the bottom of a chat, opening the keyboard must keep the last message above the composer
    /// (device report 0.3.25). Checked on first entry and after re-entering the chat.
    private func keyboardProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        func probe(_ label: String) {
            let row = chatRow(peerDisplay)
            XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
            XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
            shot("kb-\(label)-closed")
            composer.tap(); sleep(1)
            // The simulator raises its software keyboard once text is typed.
            composer.typeText("Проверка клавиатуры \(label)"); sleep(2)
            shot("kb-\(label)-open")
            let texts = app.staticTexts.allElementsBoundByIndex.filter { $0.frame.height > 10 && $0.frame.minY > 120 }
            let lowest = texts.map(\.frame.maxY).max() ?? 0
            print("UNYRI_KB \(label) composerTop=\(composer.frame.minY) keyboardTop=\(app.keyboards.firstMatch.frame.minY) kbExists=\(app.keyboards.firstMatch.exists) lowestText=\(lowest)")
            app.buttons["chat-composer-send"].tap(); sleep(2)
            shot("kb-\(label)-sent-open")
            let after = app.staticTexts.allElementsBoundByIndex.filter { $0.frame.height > 10 && $0.frame.minY > 120 }
            print("UNYRI_KB \(label) afterSend composerTop=\(composer.frame.minY) keyboardTop=\(app.keyboards.firstMatch.frame.minY) lowestText=\(after.map(\.frame.maxY).max() ?? 0)")
            shot("kb-\(label)-sent")
            back(); sleep(1)
        }
        probe("first")
        probe("reopen")
        probe("third")
        // Scrolled up in history: Telegram moves the visible messages up with the keyboard.
        let row = chatRow(peerDisplay)
        XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2)
        let list = app.collectionViews.firstMatch
        for _ in 0..<2 { list.swipeDown(velocity: .slow); sleep(1) }
        sleep(1); shot("kb-mid-closed")
        let visible = app.staticTexts.allElementsBoundByIndex.filter { $0.frame.height > 10 && $0.frame.minY > 150 && $0.frame.maxY < composer.frame.minY - 4 && !$0.label.isEmpty }
        let ref = visible.max(by: { $0.frame.maxY < $1.frame.maxY })
        let refLabel = ref?.label ?? ""
        let before = ref?.frame.maxY ?? 0
        composer.tap(); sleep(1)
        composer.typeText("Середина"); sleep(2)
        shot("kb-mid-open")
        let same = app.staticTexts.matching(NSPredicate(format: "label == %@", refLabel)).allElementsBoundByIndex.map(\.frame.maxY)
        print("UNYRI_KBMID ref=\(refLabel.prefix(30)) before=\(before) after=\(same) composerTop=\(composer.frame.minY) keyboardTop=\(app.keyboards.firstMatch.frame.minY)")
        composer.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap(); sleep(2)
        shot("kb-mid-closed-again")
        let again = app.staticTexts.matching(NSPredicate(format: "label == %@", refLabel)).allElementsBoundByIndex.map(\.frame.maxY)
        print("UNYRI_KBMID closed again=\(again) composerTop=\(composer.frame.minY)")
        back(); sleep(1)
    }

    /// Sends a photo album and a video through the app's own gallery and composer.
    private func media() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1); shot("media-chat")
        func openGallery() {
            app.buttons["chat-composer-attach"].tap()
            let photos = app.buttons["chat-attach-photos"]
            if photos.waitForExistence(timeout: 5) { shot("media-attach-menu"); photos.tap() }
            sleep(2)
            for label in ["Photo access", "Доступ к фото"] where app.buttons[label].exists { app.buttons[label].tap(); sleep(1) }
        }
        func select(_ id: String, count: Int) {
            for i in 0..<count {
                let all = app.buttons.matching(identifier: id).allElementsBoundByIndex.filter { $0.isHittable }
                guard i < all.count else { break }
                all[i].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); usleep(400_000)
            }
        }
        openGallery()
        XCTAssertTrue(app.scrollViews["gallery-grid"].waitForExistence(timeout: 10)); shot("media-gallery")
        select("gallery-select-photo", count: 3); shot("media-gallery-selected")
        let caption = app.textFields["gallery-caption"].exists ? app.textFields["gallery-caption"] : app.textViews["gallery-caption"]
        if caption.exists { caption.tap(); caption.typeText("Три фото одним альбомом") }
        shot("media-gallery-captioned")
        app.buttons["gallery-add"].tap(); sendTray("media-album"); sleep(8); shot("media-album-sent")
        openGallery()
        if app.scrollViews["gallery-grid"].waitForExistence(timeout: 10) {
            select("gallery-select-video", count: 1)
            let videoCaption = app.textFields["gallery-caption"].exists ? app.textFields["gallery-caption"] : app.textViews["gallery-caption"]
            if videoCaption.exists { videoCaption.tap(); videoCaption.typeText("Видео с подписью") }
            app.buttons["gallery-add"].tap(); sendTray("media-video"); sleep(10); shot("media-video-sent")
        }
        for _ in 0..<3 { dragTimeline(from: 0.3, to: 0.7) }
        sleep(1); shot("media-scrolled-up")
    }

    /// Kill the app while a photo is uploading. After the relaunch the text sent just before
    /// must show once, and the photo must either arrive or show its retry state, never vanish
    /// silently or appear twice (frames are inspected; the text count is asserted).
    private func killSendProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
        let marker = "KILL\(Int(Date().timeIntervalSince1970) % 100000)"
        send(marker)
        app.buttons["chat-composer-attach"].tap()
        let photos = app.buttons["chat-attach-photos"]
        if photos.waitForExistence(timeout: 5) { photos.tap() }
        sleep(2)
        for label in ["Photo access", "Доступ к фото"] where app.buttons[label].exists { app.buttons[label].tap(); sleep(1) }
        guard app.scrollViews["gallery-grid"].waitForExistence(timeout: 10) else { shot("killsend-no-gallery"); return }
        guard let tile = app.buttons.matching(identifier: "gallery-select-photo").allElementsBoundByIndex.first(where: { $0.isHittable }) else {
            shot("killsend-no-photo"); return
        }
        tile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); usleep(400_000)
        app.buttons["gallery-add"].tap(); sendTray("killsend")
        usleep(300_000); shot("killsend-in-flight")
        app.terminate()
        app.launch()
        openChat(with: env["UNYRI_WALK_PEER"]!)
        shot("killsend-relaunch-0")
        sleep(2); shot("killsend-relaunch-2s")
        sleep(8); shot("killsend-relaunch-10s")
        let copies = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).count
        print("KILLSEND marker copies=\(copies)")
        XCTAssertLessThanOrEqual(copies, 1, "the text sent before the kill shows more than once")
    }

    /// The Contacts screen in motion (owner, 13 Nov: "not smooth", the search header could be
    /// glass): dense frames while it opens from the drawer, while the keyboard comes up, after
    /// every typed letter as local and server results arrive, while clearing and scrolling, and
    /// while it closes. The frames show layout jumps; the profiler run beside it shows the cost.
    private func contactsMotionProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(3)
        menu.tap(); sleep(1)
        let people = revealDrawerControl("drawer-contacts")
        guard people.waitForExistence(timeout: 5) else { shot("contactsfx-no-entry"); return }
        people.tap()
        // Back to back: each capture itself takes roughly 0.1-0.3 s.
        for frame in 0..<6 { shot("contactsfx-open-\(frame)") }
        let query = app.textFields["contacts-query"]
        guard query.waitForExistence(timeout: 5) else { shot("contactsfx-no-field"); return }
        query.tap(); usleep(150_000); shot("contactsfx-keyboard-150ms"); sleep(1); shot("contactsfx-keyboard-1s")
        for letter in ["u", "i", "q", "a"] {
            query.typeText(letter); shot("contactsfx-typed-\(letter)-0"); usleep(250_000); shot("contactsfx-typed-\(letter)-250ms")
        }
        sleep(1); shot("contactsfx-results-1s")
        app.swipeUp(); usleep(200_000); shot("contactsfx-scrolled"); app.swipeDown(); usleep(300_000); shot("contactsfx-scrolled-back")
        let clear = app.buttons["contacts-clear-query"]
        if clear.exists { clear.tap(); shot("contactsfx-cleared-0"); usleep(300_000); shot("contactsfx-cleared-300ms") }
        let close = app.buttons["contacts-back"]
        if close.exists { close.tap(); shot("contactsfx-closing-0"); usleep(300_000); shot("contactsfx-closed-300ms") }
    }

    /// Native layout/performance evidence. Run with a pre-seeded 300-contact QA
    /// account and Instruments beside it; screenshots alone do not measure hitches.
    private func contactsProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); menu.tap()
        let entry = revealDrawerControl("drawer-contacts")
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); shot("contacts-drawer")
        entry.tap()
        let field = app.textFields["contacts-query"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); sleep(2); shot("contacts-header")
        XCTAssertFalse(app.buttons["contacts-add-identity"].exists, "B-038: Contacts has one search field and no separate add form")
        // A login2 fixture can supply its peer's public number or @username.
        // Reuse the search row's add button; the removed sheet is never opened.
        if let identity = env["UNYRI_WALK_CONTACT_IDENTITY"], !identity.isEmpty {
            // The walk peer is normally a saved contact: take him out first so the exact search has
            // someone to add; the add below restores the pair.
            let display = env["UNYRI_WALK_PEER_DISPLAY"] ?? ""
            let saved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@", "contacts-person-", display)).firstMatch
            if !display.isEmpty, saved.waitForExistence(timeout: 5) {
                XCTAssertTrue(removeFromContacts(saved), "the walk peer left Contacts before the exact add")
            }
            type(into: field, identity)
            field.typeText("\n")
            let add = contactAddButton()
            XCTAssertTrue(add.waitForExistence(timeout: 20), "Seed a discoverable peer that is not yet a contact")
            shot("contacts-exact-identity-row")
            add.tap()
            XCTAssertTrue(add.waitForNonExistence(timeout: 10), "The existing row adds the exact identity")
            XCTAssertFalse(app.textFields["contacts-add-username"].exists)
            shot("contacts-exact-identity-added")
            app.buttons["contacts-clear-query"].tap()
        } else {
            let reason = env["UNYRI_WALK_CONTACT_IDENTITY_SKIP_REASON"] ?? "Set UNYRI_WALK_CONTACT_IDENTITY to a discoverable peer's public number or @username."
            XCTContext.runActivity(named: "SKIP contacts exact-add: " + reason) { _ in }
            print("SKIP contacts exact-add: " + reason)
        }
        app.swipeUp(); usleep(300_000); shot("contacts-glass-scrolled")
        for _ in 0..<5 { app.swipeUp(); usleep(150_000) }
        shot("contacts-scroll-end")
        for _ in 0..<6 { app.swipeDown(); usleep(100_000) }
        // The menu gate needs the saved contact row. 19 Nov: an exact number query lists the person
        // only in the server section (the local filter does not match numbers), under the keyboard.
        let handle = env["UNYRI_WALK_PEER_DISPLAY"] ?? env["UNYRI_WALK_CONTACT_IDENTITY"] ?? env["UNYRI_WALK_PEER_NUMBER"] ?? env["UNYRI_WALK_PEER"] ?? "uiqa"
        type(into: field, handle); sleep(2); shot("contacts-typed")
        let server = app.staticTexts["contacts-server"]
        for _ in 0..<12 {
            if server.exists && server.isHittable { break }
            app.swipeUp(); usleep(150_000)
        }
        XCTAssertTrue(server.waitForExistence(timeout: 5)); shot("contacts-server-section")
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "contacts-person-"))
        if let row = rows.allElementsBoundByIndex.first(where: { $0.isHittable }) {
            row.press(forDuration: 0.8); sleep(1); shot("contacts-long-press")
            for labels in [["Write message", "Написать"], ["Call", "Позвонить"], ["Video call", "Видеозвонок"]] {
                XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", labels[0], labels[1])).firstMatch.exists)
            }
            // Dismiss outside the menu: at accessibility sizes the menu covers the top
            // of the screen and a fixed tap there pressed "Написать" (opened the chat).
            let menuItem = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Write message", "Написать")).firstMatch
            let menuMid = menuItem.frame.midY / max(app.frame.height, 1)
            // Normal text: the menu sits mid-screen, the proven header tap (0.9, 0.15) is free.
            // Large text: the menu covers the top, tap the empty area under it instead.
            app.coordinate(withNormalizedOffset: menuMid < 0.5 ? CGVector(dx: 0.5, dy: 0.85) : CGVector(dx: 0.9, dy: 0.15)).tap()
            XCTAssertTrue(menuItem.waitForNonExistence(timeout: 5))
            _ = app.buttons["contacts-back"].waitForExistence(timeout: 3); sleep(1)
        } else { XCTFail("Seed a QA contact or directory match for the Contacts menu gate") }
        app.buttons["contacts-back"].tap()
        XCTAssertTrue(app.buttons["chats-search"].waitForExistence(timeout: 10)); shot("contacts-back")
    }

    /// The last hittable photo or album tile in the open chat (scrolling up a little if needed).
    private func visibleMediaTile() -> XCUIElement? {
        func find() -> XCUIElement? {
            let album = app.descendants(matching: .any).matching(identifier: "chat-album-photo").allElementsBoundByIndex.last(where: { $0.isHittable })
            let single = app.descendants(matching: .any).matching(identifier: "chat-photo").allElementsBoundByIndex.last(where: { $0.isHittable })
            return album ?? single
        }
        var found = find()
        var swipes = 0
        while found == nil && swipes < 8 { app.swipeDown(); swipes += 1; usleep(400_000); found = find() }
        return found
    }

    /// Photo and album tiles fully inside the window and not covered (by the keyboard or chrome).
    private func onScreenMediaTiles() -> [XCUIElement] {
        let window = app.windows.firstMatch.frame
        return ["chat-photo", "chat-album-photo"].flatMap {
            app.descendants(matching: .any).matching(identifier: $0).allElementsBoundByIndex
        }.filter { $0.exists && $0.isHittable && window.contains($0.frame) }
    }

    /// True when a tile is one flat tone (a gray placeholder with a small icon), false for a
    /// photo. The tile is averaged down to 24×24 and the luma spread measured.
    private func looksBlank(_ element: XCUIElement) -> Bool {
        guard let image = element.screenshot().image.cgImage else { return false }
        let side = 24
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return false }
        // Explicit types: Xcode 26.5 (Swift 6.2) times out type-checking the inline form.
        let lumas: [Double] = stride(from: 0, to: pixels.count, by: 4).map { (i: Int) -> Double in
            let red = Double(pixels[i]), green = Double(pixels[i + 1]), blue = Double(pixels[i + 2])
            return 0.299 * red + 0.587 * green + 0.114 * blue
        }
        let count = Double(lumas.count)
        let mean: Double = lumas.reduce(0, +) / count
        let variance: Double = lumas.map { (value: Double) -> Double in (value - mean) * (value - mean) }.reduce(0, +) / count
        let spread: Double = variance.squareRoot()
        return spread < 6
    }

    /// Isolates timeline scrolling (optionally after an in-chat search jump).
    private func scrollProbe(search: Bool) throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        let row = chatRow(peerDisplay); XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20)); sleep(2); shot("probe-open")
        if search {
            app.buttons["chat-options"].tap(); sleep(1)
            let item = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Search messages", "Поиск сообщений")).firstMatch
            if item.waitForExistence(timeout: 5) { item.tap(); sleep(1) }
            app.textFields["chat-inline-search-field"].typeText("дела"); usleep(250_000); shot("probe-search-hit"); sleep(2); shot("probe-search")
            app.buttons["chat-inline-search-cancel"].tap(); sleep(1); shot("probe-search-closed")
        }
        func drag(_ from: CGFloat, _ to: CGFloat) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: from))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: to)))
        }
        for _ in 0..<3 { drag(0.3, 0.8) }
        sleep(1); shot("probe-drag-up-history")
        drag(0.75, 0.3); sleep(1); shot("probe-drag-toward-end-1")
        for _ in 0..<5 { drag(0.75, 0.25) }
        sleep(1); shot("probe-drag-toward-end-6")
    }

    // MARK: - Helpers

    /// Chat list rows are text groups identified "chat-list-row-…", not buttons.
    private func chatRow(_ name: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chat-list-row-", name)).firstMatch
    }

    private func dragTimeline(from: CGFloat, to: CGFloat) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: from))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: to)))
    }

    private var composer: XCUIElement {
        let view = app.textViews["chat-composer-text"]
        return view.exists ? view : app.textFields["chat-composer-text"]
    }

    /// The attach sheet's Add puts the picks in the composer tray (owner decision of
    /// 12 Nov 2026); the composer's own send button sends tray and text. Waits for the
    /// tray and for Send to enable (files are still being staged right after Add).
    private func sendTray(_ stage: String) {
        let tray = app.scrollViews["composer-attachments"]
        guard tray.waitForExistence(timeout: 10) else {
            print("UNYRI_WALK \(stage) tray missing; send skipped")
            shot("\(stage)-tray-missing")
            return
        }
        usleep(800_000); shot("\(stage)-tray")
        let send = app.buttons["chat-composer-send"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND enabled == true AND hittable == true"), object: send)
        guard XCTWaiter().wait(for: [ready], timeout: 30) == .completed, tray.exists else {
            print("UNYRI_WALK \(stage) tray send not ready; send skipped")
            shot("\(stage)-tray-send-not-ready")
            return
        }
        send.tap()
    }

    /// A long press on the sheet's Add offers Send now: the immediate send, caption
    /// included. Falls back to Add and the composer send after dismissing the menu.
    private func sendNowFromSheet(_ stage: String) {
        let add = app.buttons["gallery-add"]
        guard add.waitForExistence(timeout: 5), add.isEnabled, add.isHittable else {
            print("UNYRI_WALK \(stage) add unavailable; send skipped")
            return
        }
        add.press(forDuration: 1.0)
        let now = app.buttons["gallery-send-now"]
        let labeledNow = app.buttons.matching(NSPredicate(format: "label IN %@", ["Send now", "Отправить сейчас", "Надіслати зараз"])).firstMatch
        if now.waitForExistence(timeout: 3), now.isHittable {
            shot("\(stage)-send-now-menu"); now.tap(); return
        }
        if labeledNow.waitForExistence(timeout: 1), labeledNow.isHittable {
            shot("\(stage)-send-now-menu"); labeledNow.tap(); return
        }
        print("UNYRI_WALK \(stage) send-now action missing; dismissing menu before Add")
        // A context menu can omit its accessibility identifier. Tap outside it before
        // touching Add; a tap through an open menu may only dismiss the menu.
        let header = app.otherElements["gallery-source-header"]
        guard header.exists else {
            print("UNYRI_WALK \(stage) sheet header missing; send skipped")
            return
        }
        // QuickReaction places its decorative 21-point handle row directly above
        // this header. Dismiss onto that handle, away from content and navigation.
        header.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0))
            .withOffset(CGVector(dx: 0, dy: -10)).tap()
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND enabled == true AND hittable == true"), object: add)
        guard XCTWaiter().wait(for: [ready], timeout: 5) == .completed else {
            print("UNYRI_WALK \(stage) add unavailable after menu dismissal; send skipped")
            return
        }
        add.tap()
        sendTray(stage)
    }

    private func openChat(with handle: String) {
        // Local contacts match display names; first-contact discovery uses an exact public number.
        // The sign-in login is private on login2 servers.
        let display = handle == env["UNYRI_WALK_PEER"] ? (env["UNYRI_WALK_PEER_DISPLAY"] ?? handle) : handle
        let search = handle == env["UNYRI_WALK_PEER"] ? (env["UNYRI_WALK_PEER_NUMBER"] ?? display) : display
        let contactsBack = app.buttons["contacts-back"]
        if contactsBack.exists { contactsBack.tap(); sleep(1) }
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        app.buttons["main-open-menu"].tap()
        let people = revealDrawerControl("drawer-contacts")
        XCTAssertTrue(people.waitForExistence(timeout: 10)); people.tap()
        let query = app.textFields["contacts-query"]
        let lookup = handle == env["UNYRI_WALK_PEER"] ? (env["UNYRI_WALK_CONTACT_IDENTITY"] ?? search) : search
        XCTAssertTrue(query.waitForExistence(timeout: 10)); type(into: query, lookup)
        sleep(2); shot("contacts-search")
        // Public identities are exact; legacy fixtures can still use a display-name directory search.
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND (label CONTAINS[c] %@ OR label CONTAINS[c] %@)",
            "contacts-person-", handle, display)).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 20)); result.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20))
    }

    private func send(_ text: String) {
        let field = composer
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap(); field.typeText(text)
        let button = app.buttons["chat-composer-send"]
        XCTAssertTrue(button.waitForExistence(timeout: 5)); button.tap()
        usleep(700_000)
    }

    /// Authenticated QA walkthrough: drawer pill (default) or Settings > Help,
    /// then one tap with no comment, screenshots or manual consent interaction.
    private func diagnosticsProbe(fromDrawer: Bool = true) throws {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        app.buttons["main-open-menu"].tap()
        if fromDrawer {
            let report = revealDrawerControl("drawer-report")
            XCTAssertTrue(report.exists && report.isHittable); report.tap()
        } else {
            let settings = revealDrawerControl("drawer-settings")
            XCTAssertTrue(settings.exists && settings.isHittable); settings.tap()
            let help = app.buttons["settings-help"]
            for _ in 0..<5 { if help.exists && help.isHittable { break }; app.swipeUp() }
            XCTAssertTrue(help.isHittable); help.tap()
            let report = app.buttons["settings-report-problem"]
            for _ in 0..<5 { if report.exists && report.isHittable { break }; app.swipeUp() }
            XCTAssertTrue(report.isHittable); report.tap()
        }
        let send = app.buttons["report-send-diagnostics"]
        XCTAssertTrue(send.waitForExistence(timeout: 10)); XCTAssertTrue(send.isEnabled); send.tap()
        XCTAssertTrue(app.staticTexts["report-diagnostics-sent"].waitForExistence(timeout: 45))
        shot("diagnostics-sent")
    }

    /// One settings screen per fresh launch: settings_<sectionId>_<rowIndex> (index -1 = section only).
    private func settingsVisit(_ role: String) throws {
        let parts = role.split(separator: "_").map(String.init)
        guard parts.count >= 3, let index = Int(parts[2]) else { return }
        let rows: [String: [String]] = [
            "account": ["Редактировать профиль", "Подключение к серверу", "Карточка сервера", "Восстановление аккаунта", "Удалить аккаунт", "Свой сервер"],
            "privacy": ["Блокировка приложения", "Статус в сети", "Устройства", "Автоудаление аккаунта", "Заблокированные"],
            "notifications": [],
            "appearance": ["Оформление темы", "Оформление боковой панели"],
            "chats": ["Быстрая реакция"],
            "data": ["Качество звонков", "Использование хранилища", "Автозагрузка", "Лимиты кэша"],
            "help": ["Сообщить о проблеме", "Диагностика производительности", "О UNYRI", "Условия", "Политика", "Поддержка"],
        ]
        let section = parts[1]
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        app.buttons["main-open-menu"].tap(); sleep(1)
        let settings = revealDrawerControl("drawer-settings")
        guard settings.waitForExistence(timeout: 5) else { shot("no-settings"); return }
        settings.tap(); sleep(2)
        let entry = app.buttons["settings-" + section]
        var tries = 0
        while !(entry.exists && entry.isHittable) && tries < 4 { app.swipeUp(); tries += 1; usleep(300_000) }
        guard entry.exists else { shot("no-section"); return }
        entry.tap(); sleep(2)
        if index < 0 { shot(section); app.swipeUp(); usleep(600_000); shot(section + "-more"); return }
        guard let label = rows[section].flatMap({ index < $0.count ? $0[index] : nil }) else { return }
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
        tries = 0
        while !(row.exists && row.isHittable) && tries < 4 { app.swipeUp(); tries += 1; usleep(300_000) }
        if label == "Свой сервер" { XCTAssertTrue(row.exists && row.isHittable, "The own-server guide must be reachable from Account") }
        guard row.exists else { shot("missing-" + label); return }
        row.tap(); sleep(2); shot(section + "-" + label)
        if label == "Свой сервер" {
            XCTAssertTrue(app.buttons["selfhosting-guide-downloads"].waitForExistence(timeout: 5), "The own-server guide must offer the official guide and downloads")
        }
        app.swipeUp(); usleep(600_000); shot(section + "-" + label + "-more")
    }

    /// Settings audit: every section and every screen inside it gets a screenshot.
    private func settingsProbe() throws {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30)); sleep(2)
        app.buttons["main-open-menu"].tap(); sleep(1)
        let settings = revealDrawerControl("drawer-settings")
        guard settings.waitForExistence(timeout: 5) else { shot("settings-no-entry"); return }
        settings.tap(); sleep(2); shot("settings-main")
        app.swipeUp(); sleep(1); shot("settings-main-bottom"); app.swipeDown(); app.swipeDown(); sleep(1)
        func goBack() {
            let button = app.navigationBars.buttons.firstMatch
            if button.exists && button.isHittable { button.tap() } else {
                let alt = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Назад", "Back")).firstMatch
                if alt.exists { alt.tap() } else { app.swipeRight() }
            }
            sleep(1)
        }
        func visit(_ label: String, prefix: String) {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
            var tries = 0
            while !(row.exists && row.isHittable) && tries < 3 { app.swipeUp(); tries += 1; usleep(300_000) }
            if label == "Свой сервер" { XCTAssertTrue(row.exists && row.isHittable, "The own-server guide must be reachable from Account") }
            guard row.exists else { shot(prefix + "-missing-" + label); return }
            row.tap(); sleep(2); shot(prefix + "-" + label)
            if label == "Свой сервер" {
                XCTAssertTrue(app.buttons["selfhosting-guide-downloads"].waitForExistence(timeout: 5), "The own-server guide must offer the official guide and downloads")
            }
            app.swipeUp(); usleep(500_000); shot(prefix + "-" + label + "-more")
            goBack()
        }
        let sections: [(String, String, [String])] = [
            ("settings-account", "account", ["Редактировать профиль", "Подключение к серверу", "Карточка сервера", "Свой сервер", "Восстановление аккаунта", "Удалить аккаунт"]),
            ("settings-privacy", "privacy", ["Блокировка приложения", "Статус в сети", "Устройства", "Автоудаление аккаунта", "Заблокированные"]),
            ("settings-notifications", "notifications", []),
            ("settings-appearance", "appearance", ["Оформление темы", "Оформление боковой панели"]),
            ("settings-chats", "chats", ["Быстрая реакция"]),
            ("settings-data", "data", ["Звонки", "Использование хранилища", "Автозагрузка", "Лимиты кэша"]),
            ("settings-help", "help", ["Сообщить о проблеме", "Диагностика производительности", "О UNYRI", "Условия", "Конфиденциальность", "Поддержка"]),
        ]
        for (identifier, prefix, rows) in sections {
            let entry = app.buttons[identifier]
            var tries = 0
            while !(entry.exists && entry.isHittable) && tries < 4 { app.swipeUp(); tries += 1; usleep(300_000) }
            guard entry.exists else { shot("settings-missing-" + prefix); continue }
            entry.tap(); sleep(2); shot(prefix)
            app.swipeUp(); usleep(500_000); shot(prefix + "-more"); app.swipeDown(); app.swipeDown(); usleep(300_000)
            for row in rows { visit(row, prefix: prefix) }
            goBack()
            app.swipeDown(); app.swipeDown(); usleep(300_000)
        }
        shot("settings-final")
    }

    /// Notification tap from the background (device 0.4.5: tapping a push while Settings
    /// was the open tab left the whole app ignoring taps). The app's QA hook takes the
    /// notification route on every return to the foreground.
    private func pushTapProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(3)
        func toBackgroundAndBack() { XCUIDevice.shared.press(.home); sleep(3); app.activate(); sleep(4) }
        func closeDrawer() {
            if app.buttons["drawer-close"].exists { app.buttons["drawer-close"].tap() }
            else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap() }
            sleep(1)
        }
        // 1. Settings tab open.
        menu.tap(); sleep(1)
        let settings = revealDrawerControl("drawer-settings")
        if settings.waitForExistence(timeout: 5) { settings.tap(); sleep(2) }
        shot("pushtap-1-settings")
        toBackgroundAndBack()
        shot("pushtap-2-after-tap-from-settings")
        print("PUSHTAP settings->chat opened=\(composer.waitForExistence(timeout: 8))")
        back(); sleep(2)
        let menuOk = menu.waitForExistence(timeout: 5) && menu.isHittable
        if menuOk { menu.tap(); sleep(1) }
        shot("pushtap-3-menu-after-back")
        print("PUSHTAP menu responds=\(menuOk) drawer=\(app.buttons["drawer-close"].exists || app.otherElements["main-drawer"].exists)")
        closeDrawer()
        // 2. The same chat already open.
        let row = chatRow(env["UNYRI_WALK_PEER_DISPLAY"]!)
        if row.waitForExistence(timeout: 10) { row.tap(); sleep(2) }
        toBackgroundAndBack()
        shot("pushtap-4-same-chat")
        print("PUSHTAP same chat still open=\(composer.exists)")
        composer.tap(); sleep(1)
        shot("pushtap-5-same-chat-composer-tap")
        print("PUSHTAP keyboard after tap=\(app.keyboards.count > 0)")
        back(); sleep(2)
        // 3. Chat list open.
        toBackgroundAndBack()
        shot("pushtap-6-from-list")
        print("PUSHTAP list->chat opened=\(composer.waitForExistence(timeout: 8))")
        back(); sleep(2)
        shot("pushtap-7-end")
    }

    /// Screens audit: Calls, Search, Contacts, History backup and transfer. Every screen
    /// gets a screenshot, scrolled once, and its main controls are opened.
    private func screensProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(3)
        func drawerItem(_ labels: [String]) -> XCUIElement {
            let format = labels.map { _ in "label == %@" }.joined(separator: " OR ")
            return app.buttons.matching(NSPredicate(format: format, argumentArray: labels)).firstMatch
        }
        func openFromDrawer(_ labels: [String], _ name: String) -> Bool {
            if !menu.exists || !menu.isHittable { back(); sleep(1) }
            guard menu.waitForExistence(timeout: 5) else { shot(name + "-no-menu"); return false }
            menu.tap(); sleep(1)
            let item = drawerItem(labels)
            guard item.waitForExistence(timeout: 4) else { shot(name + "-missing"); app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); return false }
            if !item.isHittable { _ = revealDrawerControl(item.identifier) }
            item.tap(); sleep(2)
            return true
        }
        func scrollShot(_ name: String) {
            shot(name); app.swipeUp(); usleep(600_000); shot(name + "-more"); app.swipeDown(); app.swipeDown(); usleep(400_000)
        }
        // Calls
        if openFromDrawer(["Звонки", "Calls"], "calls") {
            scrollShot("calls")
            let row = app.cells.firstMatch
            if row.exists { row.press(forDuration: 0.8); sleep(1); shot("calls-row-menu"); app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap(); sleep(1) }
            let filters = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Пропущ", "Missed")).firstMatch
            if filters.exists { filters.tap(); sleep(1); shot("calls-missed") }
        }
        // Search
        if openFromDrawer(["Чаты", "Chats"], "chats-for-search"), app.buttons["chats-search"].waitForExistence(timeout: 5) {
            app.buttons["chats-search"].tap(); sleep(1)
            shot("search-empty")
            let field = app.searchFields.firstMatch.exists ? app.searchFields.firstMatch : app.textFields.firstMatch
            if field.waitForExistence(timeout: 3) {
                field.tap(); field.typeText("Привет"); sleep(2); shot("search-messages")
                if app.keyboards.count > 0 { app.swipeDown(); usleep(500_000) }
                shot("search-results-no-keyboard")
                let clear = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Очист", "Clear")).firstMatch
                if clear.exists { clear.tap(); sleep(1) }
                field.tap(); field.typeText("zzzqqq"); sleep(2); shot("search-nothing-found")
            }
            back(); sleep(1)
        }
        // Contacts
        if openFromDrawer(["Контакты", "Contacts"], "contacts") {
            scrollShot("contacts")
            let field = app.searchFields.firstMatch.exists ? app.searchFields.firstMatch : app.textFields.firstMatch
            if field.waitForExistence(timeout: 3) {
                field.tap(); field.typeText(env["UNYRI_WALK_PEER_NUMBER"] ?? env["UNYRI_WALK_PEER_DISPLAY"] ?? "QA"); sleep(2); shot("contacts-search")
                if app.keyboards.count > 0 { app.swipeDown(); usleep(500_000) }
            }
            back(); sleep(1)
        }
        // History: backup and transfer (Settings top card)
        if openFromDrawer(["Настройки", "Settings"], "settings") {
            let backup = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "Резервная", "Backup")).firstMatch
            if backup.waitForExistence(timeout: 4) {
                backup.tap(); sleep(2); scrollShot("backup")
                app.swipeUp(); usleep(500_000); app.swipeUp(); usleep(500_000); shot("backup-bottom")
                back(); sleep(2)
            } else { shot("backup-missing") }
            let transfer = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "Перенести", "Transfer")).firstMatch
            if transfer.waitForExistence(timeout: 4) {
                transfer.tap(); sleep(2); scrollShot("transfer")
                let send = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "с этого", "from this")).firstMatch
                if send.exists { send.tap(); sleep(3); shot("transfer-send"); app.swipeUp(); usleep(500_000); shot("transfer-send-more"); back(); sleep(2) }
                let receive = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "на этом", "on this")).firstMatch
                if receive.exists { receive.tap(); sleep(3); shot("transfer-receive"); back(); sleep(2) }
                back(); sleep(1)
            } else { shot("transfer-missing") }
        }
        shot("screens-end")
    }

    /// Settings > Privacy & Security > What the server knows: every section gets a shot.
    private func serverKnowledgeProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(3)
        menu.tap(); sleep(1)
        let settings = revealDrawerControl("drawer-settings")
        XCTAssertTrue(settings.waitForExistence(timeout: 5)); settings.tap(); sleep(2)
        let privacy = app.buttons["settings-privacy"]
        var tries = 0
        while !(privacy.exists && privacy.isHittable) && tries < 4 { app.swipeUp(); tries += 1; usleep(300_000) }
        XCTAssertTrue(privacy.exists); privacy.tap(); sleep(2); shot("privacy-page")
        let row = app.buttons["settings-server-knowledge"]
        XCTAssertTrue(row.waitForExistence(timeout: 5)); row.tap(); sleep(2); shot("server-knowledge-1")
        for index in 2...6 { app.swipeUp(); usleep(700_000); shot("server-knowledge-\(index)") }
    }

    /// B-030 (owner, 18 Nov): «Мой QR-код» showed http://127.0.0.1:8080 while the phone was
    /// connected to his server. The address under the QR must be the server this app uses.
    /// Finds the caption by its text so the shipped release can run the same role.
    private func qrOriginProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(2)
        menu.tap(); sleep(1)
        revealDrawerControl("drawer-qr").tap()
        XCTAssertTrue(app.staticTexts["profile-qr-number"].waitForExistence(timeout: 15), "My QR code shows the number")
        let caption = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'http://' OR label BEGINSWITH 'https://'")).firstMatch
        XCTAssertTrue(caption.waitForExistence(timeout: 10), "My QR code names its server")
        let expected = env["UNYRI_WALK_SERVER"] ?? "http://127.0.0.1:18080"
        // The sheet first shows the cached identity, then the server's current answer.
        let deadline = Date().addingTimeInterval(10)
        while caption.label != expected && Date() < deadline { usleep(300_000) }
        shot("qr-origin")
        print("QRORIGIN shown=\(caption.label) expected=\(expected)")
        XCTAssertEqual(caption.label, expected, "The QR must name the server this phone is connected to")
    }

    /// B-044 (owner screenshot, iPhone, 18 Nov): in «Вложения чата» → «Фото» the tiles did not
    /// stay in their cells — wide photos and videos covered neighbours and ran past the screen
    /// edge, tall ones stretched their row, and a video's duration was cut off. Every visible
    /// tile must be a square inside the screen, and no two tiles may overlap. The tiles are
    /// found by position under the Фото/Файлы/Голос control, so the role also runs on builds
    /// without the tile identifiers (before/after). It first sends two library photos (wide or
    /// tall, never square): square seed videos alone lay out fine even on the broken build.
    /// The system photo prompt after «Доступ к фото»: allow full access. The
    /// simulator's own language may be English while the app runs in Russian.
    private func allowSystemPhotoPrompt() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let labels = ["Allow Full Access", "Разрешить полный доступ", "Allow Access to All Photos",
                      "Разрешить доступ ко всем фото", "Allow", "Разрешить"]
        for (index, label) in labels.enumerated() {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: index == 0 ? 3 : 0.5) { button.tap(); sleep(2); return }
        }
    }

    private func chatMediaGridProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
        app.buttons["chat-composer-attach"].tap()
        let library = app.buttons["chat-attach-photos"]
        if library.waitForExistence(timeout: 5) { library.tap() }
        sleep(2)
        for label in ["Photo access", "Доступ к фото"] where app.buttons[label].exists {
            app.buttons[label].tap(); sleep(1)
            allowSystemPhotoPrompt()
        }
        XCTAssertTrue(app.scrollViews["gallery-grid"].waitForExistence(timeout: 10), "photo library grid for the seed photos")
        let picks = app.buttons.matching(identifier: "gallery-select-photo").allElementsBoundByIndex.filter { $0.isHittable }
        // 18 Nov v4: the simulator's photo grant was not honoured, the grid stayed
        // empty and the sheet stayed open over the chat, so every later step failed
        // for an unrelated reason. Stop here with the real cause instead.
        guard picks.count >= 2 else {
            shot("chatmedia-no-library-photos")
            XCTFail("no library photos to seed (photo access not granted?); B-044 not measured")
            return
        }
        for pick in picks.prefix(2) { pick.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); usleep(400_000) }
        app.buttons["gallery-add"].tap(); sendTray("chatmedia-seed"); sleep(8)
        let options = app.buttons["chat-options"]
        XCTAssertTrue(options.waitForExistence(timeout: 10), "chat options button")
        options.tap(); sleep(1)
        let media = [app.buttons["chat-menu-media"], app.buttons["Медиа"], app.buttons["Media"]].first { $0.exists }
        XCTAssertNotNil(media, "Media row in the chat menu"); media?.tap()
        let tabs = app.segmentedControls.firstMatch
        XCTAssertTrue(tabs.waitForExistence(timeout: 10), "chat media tabs"); sleep(3)
        shot("chatmedia-grid")
        let screen = app.windows.firstMatch.frame
        let grid = app.scrollViews["chat-media-grid"].exists ? app.scrollViews["chat-media-grid"]
            : app.scrollViews.allElementsBoundByIndex.first { $0.frame.minY >= tabs.frame.maxY - 2 }
        XCTAssertNotNil(grid, "chat media grid")
        let tiles = (grid?.buttons.allElementsBoundByIndex ?? []).map(\.frame)
            .filter { $0.height > 40 && $0.minY < screen.maxY - 40 && $0.maxY > tabs.frame.maxY }
        print("WALK_PROBE chatmedia screen=\(Int(screen.width)) tiles=\(tiles.count) "
              + tiles.prefix(12).map { "\(Int($0.minX)),\(Int($0.minY)) \(Int($0.width))x\(Int($0.height))" }.joined(separator: "; "))
        XCTAssertGreaterThanOrEqual(tiles.count, 4, "needs at least 4 photos or videos in this chat (run role media first)")
        var failures: [String] = []
        for tile in tiles {
            if abs(tile.width - tile.height) > 1.5 { failures.append("not square \(Int(tile.width))x\(Int(tile.height))") }
            if tile.minX < screen.minX + 8 || tile.maxX > screen.maxX - 8 { failures.append("past the screen edge x \(Int(tile.minX))…\(Int(tile.maxX))") }
        }
        for (index, first) in tiles.enumerated() {
            for second in tiles.dropFirst(index + 1) {
                let overlap = first.intersection(second)
                if !overlap.isNull && overlap.width > 0.5 && overlap.height > 0.5 {
                    failures.append("overlap \(Int(first.minX)),\(Int(first.minY)) with \(Int(second.minX)),\(Int(second.minY))")
                }
            }
        }
        print("WALK_PROBE chatmedia failures=\(failures.count) " + failures.prefix(8).joined(separator: "; "))
        XCTAssertTrue(failures.isEmpty, "chat media tiles leave their cells: " + failures.prefix(8).joined(separator: "; "))
        [app.buttons["Готово"], app.buttons["Done"]].first { $0.exists }?.tap()
    }

    /// Removes a saved contact through its row menu and reports whether the person is no longer
    /// a saved contact. 19 Nov: a tap during the menu's appear animation left the menu open and
    /// removed nothing, so the tap is retried until the menu closes.
    @discardableResult
    private func removeFromContacts(_ row: XCUIElement) -> Bool {
        let id = row.identifier.replacingOccurrences(of: "contacts-person-", with: "")
        row.press(forDuration: 0.8); sleep(1)
        let remove = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Remove from contacts", "Удалить из контактов")).firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 5), "Remove from contacts in the row menu")
        for _ in 0..<3 where remove.exists {
            sleep(1)
            if remove.isHittable { remove.tap() }
            _ = remove.waitForNonExistence(timeout: 3)
        }
        sleep(2)
        return !row.exists || contactAddButton(id).exists
    }

    /// The add button of a listed person who is not a contact. 19 Nov: the row's own
    /// identifier (`contacts-person-<id>`) replaces the button's `contacts-add-<id>` (SwiftUI
    /// applies a container's identifier to its children), so the button is also matched by its
    /// label inside the row.
    private func contactAddButton(_ id: String? = nil) -> XCUIElement {
        let en = "Add to contacts", ru = "Добавить в контакты"
        if let id {
            return app.buttons.matching(NSPredicate(format: "identifier == %@ OR (identifier == %@ AND (label == %@ OR label == %@))",
                "contacts-add-" + id, "contacts-person-" + id, en, ru)).firstMatch
        }
        return app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR (identifier BEGINSWITH %@ AND (label == %@ OR label == %@))",
            "contacts-add-", "contacts-person-", en, ru)).firstMatch
    }

    /// Opens Contacts from the drawer and returns the peer's saved-contact row (added first by
    /// the exact public identity when it is missing, so both builds measure the same state).
    private func peerContactRow(addIfMissing: Bool) -> XCUIElement {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); menu.tap()
        let entry = revealDrawerControl("drawer-contacts")
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
        let field = app.textFields["contacts-query"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); sleep(3)
        let display = env["UNYRI_WALK_PEER_DISPLAY"]!
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@", "contacts-person-", display)).firstMatch
        if !row.waitForExistence(timeout: 8), addIfMissing, let identity = env["UNYRI_WALK_CONTACT_IDENTITY"] {
            type(into: field, identity); sleep(3)
            let add = contactAddButton()
            let found = add.waitForExistence(timeout: 10)
            shot("contacts-identity-search")
            print("WALK_PROBE contactsadd found=\(found) buttons=\(app.buttons.allElementsBoundByIndex.filter { $0.identifier.hasPrefix("contacts-") }.map { $0.identifier + "|" + $0.label }.prefix(8))")
            if found { add.tap(); sleep(3) }
            if app.buttons["contacts-clear-query"].exists { app.buttons["contacts-clear-query"].tap() }
            sleep(2)
        }
        return row
    }

    /// B-051 (owner, iPhone 0.4.22, 19 Nov: «почему я не вижу персональный номер людей в
    /// контактах»): a saved contact's row must name the person's public number, grouped by three.
    private func contactsNumberProbe() throws {
        let row = peerContactRow(addIfMissing: true)
        XCTAssertTrue(row.waitForExistence(timeout: 10), "the peer is a saved contact")
        sleep(2); shot("contacts-number")
        let label = row.label
        let grouped = label.range(of: "[0-9]{3}[^0-9][0-9]{3}[^0-9][0-9]{3}", options: .regularExpression) != nil
        print("WALK_PROBE contactsnumber grouped=\(grouped) label=\"\(label)\"")
        XCTAssertTrue(grouped, "B-051: the contact row shows no public number: \(label)")
    }

    /// B-052 (owner, iPhone 0.4.22, 19 Nov: removed a person from contacts on one iPhone, then
    /// called — the call connected directly, exposing the IP; owner decision 13 Nov: direct
    /// connections only with contacts). Caller side of pair `stranger`: remove the peer from
    /// Contacts (skipped when an earlier run already did), call from the chat, and watch the
    /// call route. A direct route fails; a relayed route passes; a call that cannot connect
    /// because the QA server has no TURN relay also passes (nothing direct was offered).
    private func callStrangerProbe() throws {
        let display = env["UNYRI_WALK_PEER_DISPLAY"]!
        let row = peerContactRow(addIfMissing: false)
        if row.exists {
            let removed = removeFromContacts(row)
            shot("stranger-removed")
            print("WALK_PROBE callstranger removed the contact=\(removed)")
            XCTAssertTrue(removed, "the peer is no longer a saved contact")
        } else {
            print("WALK_PROBE callstranger the peer was already not a contact")
        }
        // A menu dismissal can swallow the first tap on Back.
        for _ in 0..<2 where app.buttons["contacts-back"].exists { app.buttons["contacts-back"].tap(); sleep(2) }
        let chat = chatRow(display)
        XCTAssertTrue(chat.waitForExistence(timeout: 20), "chat with the peer"); chat.tap(); sleep(2)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        XCTAssertTrue(call.waitForExistence(timeout: 5), "call button"); call.tap()
        let direct = app.descendants(matching: .any)["call-route-direct"]
        let relay = app.descendants(matching: .any)["call-route-relay"]
        var sawDirect = false, sawRelay = false
        for second in 0..<50 {
            if direct.exists { sawDirect = true }
            if relay.exists { sawRelay = true }
            if sawDirect || sawRelay { break }
            if second % 10 == 0 { shot("stranger-call-\(second)") }
            sleep(1)
        }
        shot("stranger-route")
        print("WALK_PROBE callstranger direct=\(sawDirect) relay=\(sawRelay)")
        let end = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        if end.exists { end.tap() }
        sleep(3); shot("stranger-ended")
        restorePeerContact()
        XCTAssertFalse(sawDirect, "B-052: after removing the contact the call connected directly (IP exposed)")
    }

    /// The rest of the battery expects the walk pair to be mutual contacts. 19 Nov: after this
    /// role ran, contactsnumber, message and chatmediagrid failed on 0.4.24 (empty Contacts).
    /// Adds the peer back by the exact public number (UNYRI_WALK_CONTACT_IDENTITY).
    private func restorePeerContact() {
        for _ in 0..<3 where !app.buttons["main-open-menu"].isHittable { back(); sleep(1) }
        let row = peerContactRow(addIfMissing: true)
        var restored = false
        if row.waitForExistence(timeout: 10) {
            // A listed non-contact still shows its own add button: that is not restored yet.
            let add = contactAddButton(row.identifier.replacingOccurrences(of: "contacts-person-", with: ""))
            if add.exists { add.tap(); sleep(3) }
            restored = row.exists && !add.exists
        }
        print("WALK_PROBE callstranger contact restored=\(restored)")
        XCTAssertTrue(restored, "the peer is a contact again after the stranger call")
        if app.buttons["contacts-back"].exists { app.buttons["contacts-back"].tap(); sleep(1) }
    }

    /// B-045 (owner screenshot, iPhone, 18 Nov): in chat selection mode the action bar
    /// (Открепить · Без звука · В архив · Удалить) was a flat full-width strip — a row showed
    /// through it and list rows showed under it, in the home-indicator area. It must be one
    /// glass island with side margins, above the home indicator, holding all four actions.
    private func selectionBarProbe() throws {
        let filter = app.descendants(matching: .any).matching(identifier: "chats-filter").firstMatch
        XCTAssertTrue(filter.waitForExistence(timeout: 10), "chat list filter menu"); filter.tap()
        let select = app.buttons["chats-filter-select"]
        XCTAssertTrue(select.waitForExistence(timeout: 5), "Select chats"); select.tap()
        XCTAssertTrue(app.buttons["chats-selection-pin"].waitForExistence(timeout: 5), "selection actions")
        let all = app.buttons["chats-select-all"]
        if all.waitForExistence(timeout: 3) { all.tap() }
        sleep(1); shot("selectionbar")
        let screen = app.windows.firstMatch.frame
        let bar = app.otherElements["chats-selection-bar"]
        let actions = ["chats-selection-pin", "chats-selection-mute", "chats-selection-archive", "chats-selection-delete"]
            .map { app.buttons[$0] }.filter { $0.exists }.map(\.frame)
        print("WALK_PROBE selectionbar screen=\(Int(screen.width))x\(Int(screen.height)) bar="
              + (bar.exists ? "\(Int(bar.frame.minX)),\(Int(bar.frame.minY)) \(Int(bar.frame.width))x\(Int(bar.frame.height))" : "none")
              + " actions=" + actions.map { "\(Int($0.minX))…\(Int($0.maxX))" }.joined(separator: ","))
        XCTAssertTrue(bar.exists, "the selection actions sit on one island (chats-selection-bar)")
        if bar.exists {
            let island = bar.frame
            XCTAssertGreaterThanOrEqual(island.minX - screen.minX, 10, "the island keeps a left margin")
            XCTAssertGreaterThanOrEqual(screen.maxX - island.maxX, 10, "the island keeps a right margin")
            XCTAssertLessThanOrEqual(island.maxY, screen.maxY - 34, "the island sits above the home indicator")
            XCTAssertEqual(actions.count, 4, "four actions")
            for action in actions { XCTAssertTrue(island.insetBy(dx: -0.5, dy: -0.5).contains(action), "action \(action) inside the island") }
        }
        [app.buttons["Cancel"], app.buttons["Отмена"]].first { $0.exists }?.tap()
    }

    /// B-031 (owner, 18 Nov): in drawer A the name and the number sat ~43 pt apart (44 pt
    /// tap frames around small text). They must read as one caption under the avatar, for
    /// the account's own name and for a 32-character name that wraps to two lines.
    /// Span = avatar bottom to number top: 8 + name + 2 - 1 pt after the fix, 60 pt before.
    /// The account's original name is restored at the end.
    private func drawerHeaderProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(2)
        func openDrawer() {
            for _ in 0..<4 where !(menu.exists && menu.isHittable) {
                let editorBack = app.buttons["edit-profile-back"], back = app.navigationBars.buttons.firstMatch
                if editorBack.exists && editorBack.isHittable { editorBack.tap() }
                else if back.exists && back.isHittable { back.tap() } else { app.swipeRight() }
                sleep(1)
            }
            menu.tap(); sleep(1)
            _ = revealDrawerControl("drawer-profile")
        }
        func measure(_ label: String, nameLines: CGFloat) -> String {
            openDrawer()
            let avatar = app.buttons["drawer-profile"], number = app.buttons["drawer-public-number"]
            XCTAssertTrue(number.waitForExistence(timeout: 10), "The drawer shows the public number")
            let name = app.buttons["drawer-name"].exists ? app.buttons["drawer-name"]
                : app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", avatar.label, "drawer-profile")).firstMatch
            let username = ((name.value as? String) ?? "").hasPrefix("@")
            let span = number.frame.minY - avatar.frame.maxY
            let limit = 8 + 21 * nameLines + 2 - 1 + (username ? 17 : 0) + 3
            shot("drawer-header-" + label)
            print("DRAWERHEADER \(label) chars=\(avatar.label.count) username=\(username) span=\(span) limit=\(limit)")
            XCTAssertLessThanOrEqual(span, limit, "Name and number must sit together under the avatar (\(label))")
            return avatar.label
        }
        func rename(_ value: String) {
            app.buttons["drawer-profile"].tap()
            let field = app.textFields["edit-profile-name"]
            XCTAssertTrue(field.waitForExistence(timeout: 15), "The drawer avatar opens the profile editor")
            field.tap(); sleep(1)
            let current = (field.value as? String) ?? ""
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
            field.typeText(value)
            let save = app.buttons["edit-profile-save"]
            XCTAssertTrue(save.waitForExistence(timeout: 5)); save.tap(); sleep(3)
        }
        let original = measure("own", nameLines: 1)
        // The server limit is 32 characters; wide Cyrillic letters wrap onto a second line.
        let long = env["UNYRI_WALK_LONG_NAME"] ?? "Максимилиана Константинопольская"
        rename(long)
        _ = measure("long", nameLines: 2)
        rename(original)
        _ = measure("restored", nameLines: 1)
    }

    /// Reproduce the owner's inactive-account placeholder in both layouts and Settings.
    /// Seed two QA accounts with distinct photos and activate each once before running.
    /// Rendered photo accessibility is required; the initials-only release fails.
    private func accountAvatarsProbe() throws {
        func assertPhoto(_ row: XCUIElement) {
            let photo = NSPredicate { element, _ in
                guard let value = (element as? XCUIElement)?.value as? String else { return false }
                return ["Profile photo", "Фото профиля", "Фото профілю"].contains { value.contains($0) }
            }
            expectation(for: photo, evaluatedWith: row)
            waitForExpectations(timeout: 5)
        }
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30))
        for choice in ["a", "c"] {
            selectDrawer(choice)
            menu.tap()
            let other = revealDrawerControl("drawer-account-1")
            XCTAssertTrue(other.waitForExistence(timeout: 5), "Seed two signed-in QA accounts first")
            assertPhoto(other)
            XCTAssertFalse(app.buttons["drawer-add-account"].exists)
            let originalLabel = other.label
            shot("account-avatar-" + choice + "-other")
            other.tap()
            XCTAssertTrue(menu.waitForExistence(timeout: 30))
            menu.tap()
            let previous = revealDrawerControl("drawer-account-1")
            XCTAssertTrue(previous.waitForExistence(timeout: 5))
            assertPhoto(previous)
            XCTAssertNotEqual(previous.label, originalLabel, "Account switch must change the other person's identity")
            shot("account-avatar-" + choice + "-switched")
            previous.tap()
            XCTAssertTrue(menu.waitForExistence(timeout: 30))
            app.terminate(); app.launch()
            XCTAssertTrue(menu.waitForExistence(timeout: 30))
            menu.tap()
            XCTAssertEqual(revealDrawerControl("drawer-account-1").label, originalLabel)
            assertPhoto(revealDrawerControl("drawer-account-1"))
            shot("account-avatar-" + choice + "-relaunch")
            revealDrawerControl("drawer-settings").tap()
            let account = app.buttons["settings-account"]
            for _ in 0..<8 where !account.isHittable { app.swipeUp() }
            XCTAssertTrue(account.isHittable); account.tap()
            let row = app.buttons["settings-account-1"]
            for _ in 0..<8 where !row.isHittable { app.swipeUp() }
            XCTAssertTrue(row.isHittable)
            assertPhoto(row)
            shot("account-avatar-" + choice + "-settings")
            back(); back()
        }
        selectDrawer("a")
    }

    /// Two accounts: add a freshly registered second account, check that it does not see
    /// the first account's chats, switch back, then remove the added account.
    /// Adds a second account on this simulator and switches back to the first one.
    private func addSecondAccountAndReturn() {
        let menu = app.buttons["main-open-menu"]
        let add = revealDrawerControl("drawer-add-account")
        XCTAssertTrue(add.waitForExistence(timeout: 5), "add account in the menu"); add.tap(); sleep(2)
        tapFirst(["Create an account", "Создать аккаунт"]); sleep(1)
        let handle = "uiqa_sw2_" + String(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(8))
        let password = env["UNYRI_WALK_PASSWORD"]!
        type(into: textField(["Your name", "Ваше имя"]), "Второй QA"); app.typeText("\n")
        type(into: app.textFields["register-login"], handle)
        tapFirst(["Show password", "Показать пароль"])
        type(into: textField(["Password", "Пароль"]), password); app.typeText("\n")
        type(into: textField(["Confirm password", "Повторите пароль", "Подтвердите пароль"]), password); app.typeText("\n")
        tapFirst(["Hide password", "Скрыть пароль"])
        let agreement = app.switches.firstMatch
        if agreement.exists {
            let knob = agreement.switches.firstMatch
            (knob.exists ? knob : agreement).tap()
        }
        tapFirst(["Register", "Зарегистрироваться"])
        XCTAssertTrue(menu.waitForExistence(timeout: 40)); sleep(3)
        switchToWalkAccount()
    }

    /// Makes the walk account (the one that has the peer chat) active again.
    private func switchToWalkAccount() {
        let menu = app.buttons["main-open-menu"]
        let peerRow = chatRow(env["UNYRI_WALK_PEER_DISPLAY"]!)
        for _ in 0..<2 where !peerRow.waitForExistence(timeout: 8) {
            XCTAssertTrue(menu.waitForExistence(timeout: 40)); menu.tap(); sleep(1)
            let other = app.buttons["drawer-account-1"]
            guard other.waitForExistence(timeout: 5) else { break }
            other.tap(); sleep(6)
        }
        XCTAssertTrue(peerRow.waitForExistence(timeout: 30), "the walk account with the peer chat is active")
    }

    /// B-055 (owner, iPhone 0.4.23, 19 Nov): during a room (and the same in a call) he tapped his
    /// other account in the side menu; the refusal line made the menu wider than its panel and
    /// every label was cut at the left edge. Needs a second account here (added once if missing).
    /// Starts an outgoing call nobody answers, minimizes it, opens the menu, taps the other account.
    private func callSwitchProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(2)
        switchToWalkAccount()
        menu.tap(); sleep(1)
        if app.buttons["drawer-account-1"].waitForExistence(timeout: 4) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); sleep(1)
        } else {
            addSecondAccountAndReturn()
        }
        let peerRow = chatRow(env["UNYRI_WALK_PEER_DISPLAY"]!)
        XCTAssertTrue(peerRow.waitForExistence(timeout: 20), "the peer chat on the walk account"); peerRow.tap(); sleep(2)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        XCTAssertTrue(call.waitForExistence(timeout: 5), "call button"); call.tap(); sleep(3)
        shot("callswitch-calling")
        let minimize = app.buttons["call-minimize"]
        if minimize.waitForExistence(timeout: 5) { minimize.tap(); sleep(2) }
        if !menu.exists { back(); sleep(2) }
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "chat list with the call minimized"); menu.tap(); sleep(1)
        let other = app.buttons["drawer-account-1"]
        XCTAssertTrue(other.waitForExistence(timeout: 5), "the other account in the menu")
        other.tap(); sleep(2)
        shot("callswitch-after-tap")
        let screen = app.windows.firstMatch.frame
        let ids = ["drawer-name", "drawer-public-number", "drawer-qr", "drawer-contacts", "drawer-account-1"]
        let present = ids.compactMap { id -> (String, CGRect)? in
            let element = app.descendants(matching: .any)[id]
            return element.exists ? (id, element.frame) : nil
        }
        let cut = present.filter { $0.1.minX < screen.minX - 1 }
        let dialog = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End and switch", "Завершить и переключить")).firstMatch
        let dialogShown = dialog.waitForExistence(timeout: 3)
        print("WALK_PROBE callswitch rows=\(present.count) minX=\(present.map { "\($0.0)=\(Int($0.1.minX))" }.joined(separator: ",")) cut=\(cut.count) dialog=\(dialogShown)")
        XCTAssertTrue(cut.isEmpty, "B-055: menu rows cut at the left edge: " + cut.map { "\($0.0) x=\(Int($0.1.minX))" }.joined(separator: ", "))
        XCTAssertTrue(dialogShown, "B-055: no «End the call and switch» confirmation")
        // Cancel (a popover has no Cancel button: tap outside it), then close the drawer.
        let cancel = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Cancel", "Отмена")).firstMatch
        if cancel.exists { cancel.tap() } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.96)).tap() }
        _ = dialog.waitForNonExistence(timeout: 4); sleep(1)
        shot("callswitch-cancelled")
        if app.buttons["drawer-account-1"].isHittable {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap(); sleep(1)
        }
        // Leave no call ringing: open the call from its bar and end it.
        let bar = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "Нажмите, чтобы вернуться", "Tap to return")).firstMatch
        let end = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        if !end.exists, bar.waitForExistence(timeout: 4) { bar.tap(); sleep(2) }
        // An unanswered outgoing call may already have ended by itself (no bar, no call screen).
        if end.waitForExistence(timeout: 6) { end.tap(); sleep(3) } else { print("WALK_PROBE callswitchend the call had already ended") }
        // Owner, 19 Nov: after leaving the room the broken menu stayed. After the call ends the
        // menu must be whole again (rows found, none past the left edge).
        if !menu.isHittable { back(); sleep(1) }
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap(); sleep(1)
        shot("callswitch-after-call")
        let after = ids.compactMap { id -> (String, CGRect)? in
            let element = app.descendants(matching: .any)[id]
            return element.exists ? (id, element.frame) : nil
        }
        let cutAfter = after.filter { $0.1.minX < screen.minX - 1 }
        print("WALK_PROBE callswitchafter rows=\(after.count) cut=\(cutAfter.count) error=\(app.staticTexts["drawer-account-error"].exists) bar=\(bar.exists)")
        XCTAssertGreaterThanOrEqual(after.count, 3, "the menu opened after the call")
        XCTAssertTrue(cutAfter.isEmpty, "B-055: the menu stays broken after the call ended")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap(); sleep(1)
    }

    private func accountsProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(2)
        print("ACC first has peer chat=\(chatRow(peerDisplay).waitForExistence(timeout: 10))")
        menu.tap(); sleep(1); shot("acc-drawer-one")
        let add = revealDrawerControl("drawer-add-account")
        guard add.waitForExistence(timeout: 5) else { shot("acc-no-add"); return }
        add.tap(); sleep(2); shot("acc-login")
        print("ACC cancel bar=\(app.buttons["add-account-cancel"].exists)")
        // The add-account sign-in keeps the current server.
        tapFirst(["Create an account", "Создать аккаунт"])
        sleep(1); shot("acc-register-form")
        for f in app.textFields.allElementsBoundByIndex { print("ACC field tf label=\(f.label) ph=\(f.placeholderValue ?? "-") id=\(f.identifier)") }
        for f in app.secureTextFields.allElementsBoundByIndex { print("ACC field sf label=\(f.label) ph=\(f.placeholderValue ?? "-") id=\(f.identifier)") }
        let handle = "uiqa_acc2_" + String(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(8))
        let password = env["UNYRI_WALK_PASSWORD"]!
        type(into: textField(["Your name", "Ваше имя"]), "Второй QA"); app.typeText("\n")
        type(into: app.textFields["register-login"], handle)
        tapFirst(["Show password", "Показать пароль"])
        type(into: textField(["Password", "Пароль"]), password); app.typeText("\n")
        type(into: textField(["Confirm password", "Повторите пароль", "Подтвердите пароль"]), password); app.typeText("\n")
        tapFirst(["Hide password", "Скрыть пароль"])
        let agreement = app.switches.firstMatch
        if agreement.exists {
            let knob = agreement.switches.firstMatch
            (knob.exists ? knob : agreement).tap()
        }
        shot("acc-register-filled")
        tapFirst(["Register", "Зарегистрироваться"])
        try expectNoSignupRecoveryGate()
        let fresh = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "without restored", "без восстановления")).firstMatch
        _ = fresh.waitForExistence(timeout: 40)
        if fresh.exists { fresh.tap() }
        XCTAssertTrue(menu.waitForExistence(timeout: 40)); sleep(3)
        shot("acc-second-list")
        print("ACC second sees first's peer chat=\(chatRow(peerDisplay).waitForExistence(timeout: 5))")
        menu.tap(); sleep(1); shot("acc-drawer-two")
        let other = revealDrawerControl("drawer-account-1")
        print("ACC drawer other row=\(other.waitForExistence(timeout: 5)) add hidden=\(!app.buttons["drawer-add-account"].exists)")
        guard other.exists else { return }
        other.tap(); sleep(4)
        XCTAssertTrue(menu.waitForExistence(timeout: 40)); sleep(2)
        shot("acc-back-first")
        print("ACC back on first has peer chat=\(chatRow(peerDisplay).waitForExistence(timeout: 15))")
        // Remove the added (now inactive) account from its inline row.
        menu.tap(); sleep(1)
        let added = revealDrawerControl("drawer-account-1")
        if added.waitForExistence(timeout: 5) {
            added.press(forDuration: 1.0); sleep(1); shot("acc-row-menu")
            let remove = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Убрать аккаунт", "Remove account")).firstMatch
            if remove.waitForExistence(timeout: 4) {
                remove.tap(); sleep(1); shot("acc-remove-confirm")
                let confirm = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Убрать аккаунт", "Remove account")).firstMatch
                if confirm.waitForExistence(timeout: 4) { confirm.tap(); sleep(3) }
            }
        }
        shot("acc-after-remove")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); sleep(1)
        print("ACC after remove first has peer chat=\(chatRow(peerDisplay).waitForExistence(timeout: 15))")
        menu.tap(); sleep(1)
        print("ACC after remove add visible=\(app.buttons["drawer-add-account"].waitForExistence(timeout: 5)) other row=\(app.buttons["drawer-account-1"].exists)")
        shot("acc-final-drawer")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap(); sleep(1)
    }

    /// Two-simulator call, callee side: waits for the incoming call and answers it,
    /// either in the app or on the CallKit banner, then stays until the caller hangs up.
    private func callAnswerProbe() throws {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let accept = NSPredicate(format: "label == %@ OR label == %@ OR label == %@", "Accept", "Принять", "Ответить")
        var answered = false
        for _ in 0..<90 where !answered {
            let inApp = app.buttons.matching(accept).firstMatch
            let banner = springboard.buttons.matching(accept).firstMatch
            if inApp.exists { shot("callee-ringing"); inApp.tap(); answered = true }
            else if banner.exists { shot("callee-ringing-banner"); banner.tap(); answered = true }
            else { sleep(1) }
        }
        print("CALL callee answered=\(answered)")
        guard answered else { return }
        var rotatedVideo = false
        for i in 0..<8 {
            sleep(4); shot("callee-\(i)")
            if !rotatedVideo && app.buttons["call-camera"].exists {
                rotateVideoCallIfPresent("callee"); rotatedVideo = true
            }
        }
        let end = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        for _ in 0..<30 where end.exists { sleep(1) }
        shot("callee-ended")
    }

    /// Two-simulator call, caller side: calls the peer, records the screen through
    /// ringing, connecting and the connected call, then hangs up.
    private func callPairProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        let call = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Call", "Позвонить")).firstMatch
        guard call.waitForExistence(timeout: 5) else { shot("callpair-no-button"); return }
        call.tap()
        // The call screen's status capsule, not any text: message times such as
        // 16:14 matched the old timer pattern and reported a call that had ended.
        let status = app.descendants(matching: .any)["call-status"]
        for i in 0..<10 {
            usleep(500_000); shot("caller-start-\(i)")
            print("CALL caller status[\(i)]=\(status.exists ? status.label : "-")")
        }
        let running = NSPredicate(format: "exists == true AND enabled == true AND label MATCHES %@", ".*[0-9]{1,2}:[0-9]{2}.*")
        let connected = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: running, object: status)], timeout: 40) == .completed
        print("CALL caller connected=\(connected)")
        XCTAssertTrue(connected, "paired call did not connect (B-006)")
        guard connected else { return }
        var rotatedVideo = false
        for i in 0..<6 {
            sleep(4); shot("caller-\(i)")
            if !rotatedVideo && app.buttons["call-camera"].exists {
                rotateVideoCallIfPresent("caller"); rotatedVideo = true
            }
        }
        let end = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "End call", "Завершить звонок")).firstMatch
        if end.exists { end.tap() }
        sleep(3); shot("caller-ended")
    }

    /// Entering a chat other than the last one: Saved Messages, then the peer's chat
    /// (lots of media), back and forth. Each entry is followed by 2.5 s of stillness so
    /// a screen recording shows any element that changes after the chat appeared.
    private func switchChatsProbe() throws {
        let peerDisplay = env["UNYRI_WALK_PEER_DISPLAY"]!
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(2)
        func openSaved() {
            menu.tap(); sleep(1)
            let saved = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Saved Messages", "Избранное")).firstMatch
            if saved.waitForExistence(timeout: 5) { saved.tap() }
            print("SWITCH saved at \(Date().timeIntervalSince1970)")
            sleep(3)
            back(); sleep(2)
        }
        func openPeer(_ label: String) {
            let row = chatRow(peerDisplay)
            XCTAssertTrue(row.waitForExistence(timeout: 20))
            print("SWITCH peer \(label) at \(Date().timeIntervalSince1970)")
            row.tap()
            sleep(3)
            shot("switch-\(label)")
            back(); sleep(2)
        }
        openPeer("first")
        openPeer("same-again")
        openSaved()
        openPeer("after-other")
        openSaved()
        openPeer("after-other-2")
    }

    /// Lines of the screen that talk about a transfer, from one accessibility snapshot
    /// (querying elements one by one took seconds per pass).
    private func directLines() -> String {
        let keys = ["МБ", "MB", "%", "очеред", "Queued", "Подготов", "Preparing", "передач", "transfer", "напрямую", "directly", "Принять", "Accept", "Ожида", "Waiting", "Получ", "Отправ"]
        var seen = Set<String>()
        var out: [String] = []
        for line in app.debugDescription.components(separatedBy: "\n") {
            guard let range = line.range(of: "label: '") else { continue }
            let rest = line[range.upperBound...]
            guard let close = rest.firstIndex(of: "'") else { continue }
            let label = String(rest[..<close])
            if keys.contains(where: { label.contains($0) }), seen.insert(label).inserted { out.append(label) }
        }
        return out.joined(separator: " | ")
    }

    /// Direct (phone-to-phone) file, sender side: "Large file, directly", the first
    /// recent file, send. Then the transfer state for up to 150 s.
    private func directSendProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(1)
        app.buttons["chat-composer-attach"].tap(); sleep(1)
        let direct = app.buttons["chat-attach-direct"]
        guard direct.waitForExistence(timeout: 5) else { shot("direct-no-row"); print("DIRECT no direct row"); return }
        direct.tap(); sleep(2); shot("direct-files-tab")
        let row = app.buttons.matching(identifier: "files-recent-row").firstMatch
        guard row.waitForExistence(timeout: 5) else { shot("direct-no-recent"); print("DIRECT no recent file"); return }
        row.tap(); usleep(400_000)
        app.buttons["gallery-add"].tap(); sleep(1); shot("direct-after-gallery-add")
        let send = app.buttons["chat-composer-send"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND enabled == true"), object: send)
        if XCTWaiter().wait(for: [ready], timeout: 30) == .completed { print("DIRECT composer send tap at \(Date().timeIntervalSince1970)"); send.tap() }
        else { print("DIRECT composer send not enabled") }
        directWatch("send", seconds: 150)
    }

    /// Keeps the sender's chat open (the transfer needs the app in the foreground).
    private func directHoldProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        directWatch("hold", seconds: 150)
    }

    /// Direct file, receiver side: waits for the offer card in the sender's chat,
    /// taps Accept (or Accept all), then watches progress.
    private func directAcceptProbe(hideMidway: Bool = false) throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        let accept = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "Accept", "Принять")).firstMatch
        var accepted = false
        for i in 0..<120 where !accepted {
            if accept.exists { shot("direct-offer"); accept.tap(); accepted = true; print("DIRECT accepted at t=\(i) \(Date().timeIntervalSince1970)") }
            else { sleep(1) }
        }
        guard accepted else { shot("direct-no-offer"); print("DIRECT no offer"); return }
        if hideMidway {
            // Wait for bytes to flow, hide the app for 15 s, come back: the transfer must
            // pause and then continue by itself from where it stopped.
            let started = Date()
            while Date().timeIntervalSince(started) < 60 {
                let line = directLines()
                if line.contains(" из ") || line.contains(" of ") { print("DIRECT hiding at \(line)"); break }
                usleep(300_000)
            }
            shot("direct-before-hide")
            XCUIDevice.shared.press(.home)
            print("DIRECT hidden at \(Date().timeIntervalSince1970)")
            sleep(15)
            app.activate()
            print("DIRECT back at \(Date().timeIntervalSince1970)")
            sleep(1); shot("direct-after-hide")
        }
        directWatch("recv", seconds: 150)
    }

    private func directWatch(_ tag: String, seconds: Int) {
        let start = Date()
        var last = ""
        var shots = 0
        while Date().timeIntervalSince(start) < Double(seconds) {
            let line = directLines()
            let t = Int(Date().timeIntervalSince(start))
            if line != last { print("DIRECT \(tag) t=\(t) \(line)"); last = line }
            if t >= shots * 10 { shot("direct-\(tag)-\(t)"); shots += 1 }
            usleep(700_000)
        }
        shot("direct-\(tag)-end")
    }

    /// Picked media in the composer glass while they are prepared, then the sent
    /// bubbles arriving: a burst of screenshots right after Send (and a text message
    /// for the arrival motion). Pair with a screen recording.
    private func stripProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(2)
        composer.tap(); composer.typeText("Проверка появления"); app.buttons["chat-composer-send"].tap()
        print("STRIP text sent at \(Date().timeIntervalSince1970)")
        sleep(2)
        app.buttons["chat-composer-attach"].tap()
        let photos = app.buttons["chat-attach-photos"]
        if photos.waitForExistence(timeout: 5) { photos.tap() }
        sleep(2)
        guard app.scrollViews["gallery-grid"].waitForExistence(timeout: 10) else { shot("strip-no-gallery"); return }
        for id in ["gallery-select-photo", "gallery-select-photo", "gallery-select-video"] {
            let all = app.buttons.matching(identifier: id).allElementsBoundByIndex.filter { $0.isHittable && !$0.isSelected }
            if let first = all.first { first.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); usleep(400_000) }
        }
        // Send now (long press on Add) keeps the immediate send this probe times.
        sendNowFromSheet("strip")
        print("STRIP media sent at \(Date().timeIntervalSince1970)")
        for i in 0..<10 { shot("strip-\(i)"); usleep(150_000) }
        sleep(6); shot("strip-end")
    }

    /// Settings > Data & Storage > Calls: the "Use less data" and "Peer-to-peer calls" page.
    private func callSettingsProbe() throws {
        let menu = app.buttons["main-open-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); sleep(1)
        menu.tap(); sleep(1)
        let settings = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Settings", "Настройки")).firstMatch
        guard settings.waitForExistence(timeout: 5) else { shot("callsettings-no-settings"); return }
        settings.tap(); sleep(2)
        let data = app.buttons["settings-data"]
        var tries = 0
        while !(data.exists && data.isHittable) && tries < 5 { app.swipeUp(); tries += 1; usleep(300_000) }
        guard data.exists else { shot("callsettings-no-data"); return }
        data.tap(); sleep(2); shot("callsettings-data")
        // The hidden drawer also has a "Calls" entry: take the visible settings row.
        let rows = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "Звонки", "Calls")).allElementsBoundByIndex
        guard let calls = rows.first(where: { $0.isHittable && $0.frame.minX >= 0 }) else { shot("callsettings-no-calls-row"); return }
        calls.tap(); sleep(2); shot("callsettings-page")
        app.swipeUp(); usleep(600_000); shot("callsettings-page-more")
    }

    /// Video player (plan Batches A and B, steps 1-14) in the chat with UNYRI_WALK_PEER,
    /// which needs videos already sent (the media role sends one); the opened video should
    /// be a landscape one of 60 s or more. Step 7 pages back UNYRI_WALK_SILENT_BACK items
    /// (default 1) to a silent clip of 30 s or less, step 8 pages back one item. Run it next
    /// to `log stream --predicate 'subsystem == "com.unyri.media"'` for the acquire, release,
    /// pause, playing, "scrub seeks=" and "hold rate=" lines: XCTest cannot take a
    /// screenshot while a finger is still down.
    private func videoPlayerProbe() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!); sleep(1)
        let latest = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Latest messages", "К последним сообщениям")).firstMatch
        if latest.exists { latest.tap(); sleep(1) }
        let videos = app.descendants(matching: .any).matching(NSPredicate(format: "(identifier BEGINSWITH %@ AND NOT identifier BEGINSWITH %@) OR identifier == %@", "chat-video-", "chat-video-note", "chat-album-video"))
        func findVideo() -> XCUIElement? { videos.allElementsBoundByIndex.last(where: { $0.isHittable }) }
        var found = findVideo()
        var swipes = 0
        while found == nil && swipes < 8 { app.swipeDown(); swipes += 1; usleep(400_000); found = findVideo() }
        guard let video = found else { shot("vp-00-no-video"); return }
        let positionLabels = ["Playback position", "Позиция воспроизведения"]
        // The scrubber is a custom adjustable control, not a UISlider.
        let slider = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", positionLabels)).firstMatch
        let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        // Hittable first: voice bubbles in the chat under the viewer have Play buttons too.
        func button(_ english: String, _ russian: String) -> XCUIElement {
            let query = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", english, russian))
            return query.allElementsBoundByIndex.last(where: { $0.isHittable }) ?? query.firstMatch
        }
        func isPositionSlider(_ line: Substring) -> Bool {
            // The scrubber is a custom adjustable view (UIKit), listed as "Other", not "Slider".
            positionLabels.contains { line.contains("label: '\($0)'") }
        }
        func field(_ name: String, _ line: Substring?) -> String {
            guard let line, let start = line.range(of: name + ": ") else { return "-" }
            let rest = line[start.upperBound...]
            if rest.first == "'" { return String(rest.dropFirst().prefix { $0 != "'" }) }
            return String(rest.prefix { $0 != "," })
        }
        // The scrubber reads "1:23 of 4:10" ("1:23 из 4:10"): elapsed and total seconds.
        func times(_ value: String) -> (elapsed: Double, total: Double)? {
            let parsed = value.split(separator: " ").filter { $0.contains(":") }.map { token in
                token.split(separator: ":").compactMap { Double($0) }.reduce(0) { $0 * 60 + $1 }
            }
            return parsed.count == 2 ? (parsed[0], parsed[1]) : nil
        }
        func snapshot() -> (counter: String, scrub: String) {
            let lines = app.debugDescription.split(separator: "\n")
            return (field("label", lines.first { $0.contains("media-page-counter") }), field("value", lines.first(where: isPositionSlider)))
        }
        // One accessibility snapshot per report: the page counter and the position in percent.
        @discardableResult func report(_ step: Int) -> Double {
            let now = snapshot()
            print("VIDEOPLAYER step=\(String(format: "%02d", step)) counter=\(now.counter) scrub=\(now.scrub)")
            guard let position = times(now.scrub), position.total > 0 else { return -1 }
            return position.elapsed / position.total * 100
        }
        func elapsed() -> Double? { times(snapshot().scrub)?.elapsed }
        func clipSeconds() -> Double? { times(snapshot().scrub)?.total }
        func revealControls() {
            guard !app.buttons["media-return-to-chat"].exists else { return }  // the scrubber stays in the tree when the chrome hides
            center.tap(); usleep(300_000)   // the centre answers at once; give SwiftUI a frame
        }
        func pauseIfPlaying() {
            revealControls()
            let pause = button("Pause", "Пауза")
            if pause.exists { pause.tap(); usleep(300_000) }
        }
        // A short tap on the bar jumps there; one on the thumb stays put, so steps measure
        // from report() rather than from the fraction tapped.
        func jump(to fraction: CGFloat) {
            guard slider.exists else { return }
            slider.coordinate(withNormalizedOffset: CGVector(dx: fraction, dy: 0.5)).tap()
            usleep(600_000)
        }

        // 1: open, close, open again. The log shows one acquire and one release per visit.
        video.tap(); sleep(2); shot("vp-01-open"); report(1)
        revealControls()
        let close = app.buttons["media-return-to-chat"]
        // The chrome hides itself while playing: tap the centre until the back button shows.
        for _ in 0..<3 where !close.exists { center.tap(); usleep(400_000) }
        if close.exists { close.tap() }
        sleep(1); shot("vp-01-closed")
        // The viewer must be gone (not just transparent) before the chat is touched again.
        let surface = app.descendants(matching: .any)["media-video-surface"]
        var waited = 0
        while surface.exists && waited < 8 { usleep(500_000); waited += 1 }
        print("VIDEOPLAYER surface-after-close=\(surface.exists) waited=\(waited)")
        guard let again = findVideo() else { shot("vp-01-no-video"); return }
        again.tap(); sleep(2); shot("vp-01-reopened"); report(1)

        // 2: Control Center over the playing video and back: playback went on.
        revealControls()
        let before = report(2)
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.005))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.6)))
        sleep(2); shot("vp-02-control-center")
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.97))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
        sleep(1); revealControls(); shot("vp-02-back")
        XCTAssertNotEqual(report(2), before, "Control Center must not pause the video")

        // 3: the first Save to Photos raises the permission alert; the video keeps playing.
        revealControls()
        let actions = button("Actions", "Действия")
        if actions.waitForExistence(timeout: 3) {
            actions.tap(); sleep(1)
            let device = button("Save to device", "Сохранить на устройство")
            if device.waitForExistence(timeout: 3) { device.tap(); sleep(1) }
            let photos = button("Save to Photos", "Сохранить в Фото")
            if photos.waitForExistence(timeout: 3) { photos.tap(); sleep(2); shot("vp-03-permission") }
            let allow = springboard.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Allow", "Разрешить")).firstMatch
            if allow.waitForExistence(timeout: 5) { allow.tap() }
            sleep(2); revealControls(); shot("vp-03-saved"); report(3)
        } else { shot("vp-03-no-actions") }

        // 4: the end shows Replay and keeps the chrome; a neighbour and back starts at 0:00.
        revealControls()
        // Inside the bar's frame: its slop past the track's end still means the end.
        jump(to: 0.99)
        sleep(1)
        let play = button("Play", "Воспроизвести")
        if !button("Replay", "Повторить").exists && play.exists { play.tap(); sleep(1) }
        shot("vp-04-ended"); sleep(4); shot("vp-04-ended-after-4s"); report(4)
        XCTAssertTrue(button("Replay", "Повторить").exists, "The end shows Replay and the chrome stays")
        app.swipeRight(); sleep(1); shot("vp-04-neighbour")
        app.swipeLeft(); sleep(2); revealControls(); shot("vp-04-back")
        let restarted = report(4)
        XCTAssertTrue(restarted >= 0 && restarted < 50, "A video left at its end starts over")

        // 5: ten paused seconds for the profiler: no clock or display-link work may run.
        revealControls()
        let pause = button("Pause", "Пауза")
        if pause.exists { pause.tap() }
        shot("vp-05-paused")
        print("VIDEOPLAYER pause-window start")
        sleep(10)
        print("VIDEOPLAYER pause-window end")
        report(5)

        // 6: a drag that starts on the bar away from the thumb scrubs, relative to where the
        // thumb was, and never flips the page.
        pauseIfPlaying()
        jump(to: 0.1)
        let pageBefore = snapshot().counter
        let dragStart = report(6)
        if slider.exists {
            slider.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
                .press(forDuration: 0.05, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        }
        usleep(800_000); shot("vp-06-drag-from-track")
        let dragEnd = report(6)
        XCTAssertEqual(snapshot().counter, pageBefore, "A drag on the bar must not flip the page")
        XCTAssertTrue(dragEnd - dragStart > 35 && dragEnd - dragStart < 65, "Dragging half the bar moves about half the video")

        // 7: a silent clip of 30 s or less loops instead of ending.
        let back = Int(env["UNYRI_WALK_SILENT_BACK"] ?? "") ?? 1
        for _ in 0..<back { app.swipeRight(); usleep(800_000) }
        sleep(1); revealControls(); shot("vp-07-silent")
        if let length = clipSeconds(), length > 0, length <= 30 {
            sleep(UInt32(length + 2)); revealControls(); shot("vp-07-looped")
            let value = report(7)
            XCTAssertTrue(value >= 0 && value < 50, "A silent short clip loops instead of ending")
        } else {
            print("VIDEOPLAYER step=7 skipped: no clip of 30 s or less there")
        }
        for _ in 0..<back { app.swipeLeft(); usleep(800_000) }
        sleep(2)

        // 8: 1.5× picked on this video carries over: the previous video keeps it if it is
        // 30 s or longer and starts at 1× if shorter. Mute carries over too.
        revealControls()
        let speed = button("Playback speed", "Скорость воспроизведения")
        if speed.waitForExistence(timeout: 3) {
            speed.tap(); sleep(1)
            let faster = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "1.5×", "1,5×")).firstMatch
            if faster.waitForExistence(timeout: 3) { faster.tap(); sleep(1) }
            shot("vp-08-speed-picked")
            print("VIDEOPLAYER step=08 speedA=\(speed.value as? String ?? "-") lengthA=\(clipSeconds() ?? -1)")
            let mute = button("Mute", "Выключить звук")
            if mute.exists { mute.tap(); usleep(300_000) }
            app.swipeRight(); sleep(2); revealControls(); shot("vp-08-neighbour")
            let unmute = button("Unmute", "Включить звук")
            let speedB = speed.exists ? speed.value as? String ?? "-" : "-"
            print("VIDEOPLAYER step=08 speedB=\(speedB) lengthB=\(clipSeconds() ?? -1) muted=\(unmute.exists)")
            if let lengthB = clipSeconds(), lengthB > 0 {
                XCTAssertEqual(speedB.filter(\.isNumber), lengthB >= 30 ? "15" : "1", "Only videos of 30 s or more take the remembered speed")
                XCTAssertTrue(unmute.exists, "Mute carries over to the next video")
            } else {
                print("VIDEOPLAYER step=08 skipped: the neighbour is not a video")
            }
            app.swipeLeft(); sleep(2); revealControls()
            // Back to 1× and sound on, so the next runs start clean.
            if speed.exists {
                speed.tap(); sleep(1)
                let normal = app.buttons.matching(NSPredicate(format: "label == %@", "1×")).firstMatch
                if normal.waitForExistence(timeout: 3) { normal.tap(); sleep(1) }
            }
            if unmute.exists { unmute.tap(); usleep(300_000) }
        } else { shot("vp-08-no-speed") }

        // 9: a slow drag with a hold: the frame follows the finger while it moves, so the log
        // shows "scrub seeks=" above 1 for this drag.
        pauseIfPlaying()
        jump(to: 0.2)
        let scrubBefore = report(9)
        if slider.exists {
            slider.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
                .press(forDuration: 0.3, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)),
                       withVelocity: XCUIGestureVelocity(rawValue: 80), thenHoldForDuration: 1.0)
        }
        usleep(600_000); shot("vp-09-after-live-scrub")
        XCTAssertNotEqual(report(9), scrubBefore, "A drag with a hold scrubs")

        // 10: the same drag ending 160 pt above the bar moves less: finer scrubbing.
        jump(to: 0.2)
        let plainStart = report(10)
        if slider.exists {
            slider.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
                .press(forDuration: 0.05, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)))
        }
        usleep(800_000)
        let plain = report(10) - plainStart
        jump(to: 0.2)
        let fineStart = report(10)
        if slider.exists {
            let from = slider.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
            from.press(forDuration: 0.05, thenDragTo: from.withOffset(CGVector(dx: slider.frame.width * 0.3, dy: -160)))
        }
        usleep(800_000); shot("vp-10-fine-scrub")
        let fine = report(10) - fineStart
        print("VIDEOPLAYER step=10 plain=\(plain) fine=\(fine)")
        XCTAssertTrue(fine > 0 && fine < plain * 0.8, "Moving the finger away from the bar scrubs finer")

        // 11: a double tap on the right and two more taps within the window: +30 s.
        pauseIfPlaying()
        jump(to: 0.05)
        let streakStart = elapsed()
        let right = app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.45))
        right.doubleTap(); right.tap(); right.tap()
        shot("vp-11-ripple")
        sleep(1); revealControls(); report(11)
        if let begin = streakStart, let end = elapsed(), let length = clipSeconds(), length >= begin + 32 {
            XCTAssertTrue(abs(end - begin - 30) <= 2, "A double tap and two taps seek 30 s")
        } else {
            print("VIDEOPLAYER step=11 skipped: the video is too short for +30 s")
        }

        // 12: holding while playing shows the "2×" pill and hides the chrome; the release
        // removes the pill (the log shows "hold rate=2.0" and "hold end").
        revealControls()
        let resume = button("Play", "Воспроизвести")
        if resume.exists { resume.tap(); usleep(300_000) }
        center.press(forDuration: 1.2)
        shot("vp-12-after-hold")
        print("VIDEOPLAYER step=12 pill=\(app.staticTexts["media-temporary-speed"].exists) chrome=\(slider.exists)")
        XCTAssertFalse(app.staticTexts["media-temporary-speed"].exists, "Releasing the hold restores the chosen speed")

        // 13: a centre tap toggles the chrome at once, without waiting for a double tap.
        let chromeBefore = slider.exists
        center.tap(); usleep(100_000)
        shot("vp-13-centre-tap")
        XCTAssertNotEqual(slider.exists, chromeBefore, "A centre tap toggles the chrome within 100 ms")

        // 14: a pinch fills the screen with a video of another shape; pinching back fits it.
        app.pinch(withScale: 1.6, velocity: 1)
        let gravity = app.staticTexts["media-gravity-notice"]
        let filled = gravity.waitForExistence(timeout: 1)
        shot("vp-14-fill")
        print("VIDEOPLAYER step=14 notice=\(filled ? gravity.label : "-")")
        XCTAssertTrue(filled, "A pinch fills the screen")
        sleep(2)
        app.pinch(withScale: 0.6, velocity: -1)
        usleep(300_000); shot("vp-14-fit")
    }

    /// B-027 (owner recordings 18 Nov 11:55/11:56): a sideways drag on the Chats screen — a chat
    /// row swiped left for its actions, or the drawer pulled open — also scrolled the whole list
    /// up/down (up to 32 pt and 72 pt). Telegram-like: the list must stay still. B-028: no
    /// position strip (scroll indicator) at the list's right edge.
    private func listSwipeLockProbe() throws {
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 30))
        // Measure the list by the first row's name label: it keeps its vertical place while a
        // row tray is open, unlike the row container's identifier. One chat is enough.
        let peer = env["UNYRI_WALK_PEER_DISPLAY"] ?? "QA"
        let name = app.staticTexts.matching(NSPredicate(format: "label == %@", peer)).firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 20), "Seed a chat with " + peer)
        func listY() -> CGFloat? { name.exists ? name.frame.minY : nil }
        guard let beforeY = listY() else { return }
        shot("swipelock-01-before")
        // 1. Drawer pulled open from inside the list with a downward drift, while no row tray
        // is open (an open tray blocks the drawer and would make this step meaningless).
        let screen = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        let from = screen.withOffset(CGVector(dx: 10, dy: beforeY + 160)) // inside the 22 pt opening strip
        from.press(forDuration: 0.02, thenDragTo: from.withOffset(CGVector(dx: 260, dy: 90)))
        usleep(900_000); shot("swipelock-02-drawer-open")
        XCTAssertTrue(app.descendants(matching: .any)["main-drawer"].exists, "The drag opens the drawer")
        let back = screen.withOffset(CGVector(dx: 360, dy: beforeY + 240))
        back.press(forDuration: 0.02, thenDragTo: back.withOffset(CGVector(dx: -300, dy: -60)))
        usleep(900_000); shot("swipelock-03-drawer-closed")
        XCTAssertTrue(app.buttons["main-open-menu"].waitForExistence(timeout: 5))
        guard let settledY = listY() else { XCTFail("The chat row disappeared after the drawer"); return }
        XCTAssertLessThan(abs(settledY - beforeY), 4, "B-027: a drawer drag must not scroll the chat list")
        // 2. Row swipe left with a downward drift, like the owner's thumb.
        let start = name.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).withOffset(CGVector(dx: 180, dy: 0))
        start.press(forDuration: 0.02, thenDragTo: start.withOffset(CGVector(dx: -220, dy: 70)))
        usleep(900_000); shot("swipelock-04-row-swiped")
        if let afterRow = listY() { XCTAssertLessThan(abs(afterRow - settledY), 4, "B-027: a row swipe must not scroll the chat list") }
        // 3. B-028: drag the list and look for the scroll indicator element.
        app.swipeUp(); usleep(300_000)
        let strip = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "scroll bar", "полоса прокрутки")).firstMatch
        shot("swipelock-05-scrolled")
        XCTAssertFalse(strip.exists, "B-028: the chat list shows no position strip")
        app.swipeDown(); usleep(600_000)
    }

    /// Use a seeded local history with UNYRI_WALK_SEARCH_QUERY (default Привет).
    /// Capture the same visible row's y before/after; a push/pop is not search.
    private func inlineSearchProbe() throws {
        let search = app.buttons["chats-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "chat-list-row-"))
        if rows.count > 6 { app.swipeUp(); usleep(400_000) }
        let visible = rows.allElementsBoundByIndex.first { $0.isHittable && $0.frame.minY > 130 }
        let rowID = visible?.identifier
        let beforeY = visible?.frame.minY
        shot("search-01-list-position")
        let headerY = search.frame.midY
        search.tap()
        let field = app.textFields["search-query"]
        XCTAssertTrue(field.waitForExistence(timeout: 8))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertLessThan(abs(field.frame.midY - headerY), 30, "Search replaces the existing header in place")
        XCTAssertFalse(app.buttons["main-open-menu"].exists)
        XCTAssertFalse(app.buttons["chat-navigation-back"].exists)
        shot("search-02-inline-empty-keyboard")
        // No field tap before typing: opening search must acquire the native keyboard itself.
        field.typeText("qa")
        app.buttons["search-clear-query"].tap()
        XCTAssertFalse(app.buttons["search-clear-query"].exists)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "Clear keeps the keyboard available")
        let searchQuery = env["UNYRI_WALK_SEARCH_QUERY"] ?? "Привет"
        field.typeText(searchQuery)
        XCTAssertEqual(field.value as? String, searchQuery, "The native input must preserve every typed character")
        shot("search-03-typing")
        let hit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "search-message-")).firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 15), "Seed message history before running search")
        shot("search-04-sections")
        let results = app.scrollViews["search-results-all"]
        XCTAssertTrue(results.exists)
        results.swipeUp()
        let keyboardHidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter().wait(for: [keyboardHidden], timeout: 5), .completed, "Scrolling dismisses the keyboard")
        // A new search snapshot after dismissal must not replay the consumed autofocus request.
        sleep(1)
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        shot("search-04-scroll-dismisses-keyboard")
        results.swipeDown()
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText("\n")
        let submitted = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter().wait(for: [submitted], timeout: 5), .completed, "Search submit closes the keyboard")
        XCTAssertEqual(field.value as? String, searchQuery)
        XCTAssertTrue(app.otherElements["search-scope-controls"].exists || app.buttons["search-scope-chats"].exists)
        for name in ["media", "links", "files", "voice"] {
            let tab = app.buttons["search-scope-" + name]
            if tab.exists {
                tab.tap(); shot("search-05-tab-" + name)
                app.buttons["search-scope-chats"].tap()
            }
        }
        hit.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 10)); sleep(1)
        shot("search-06-message-anchor")
        // Search's Cancel remains mounted behind the conversation. Explicitly
        // target the conversation back button, not the generic back helper.
        let chatBack = app.buttons["chat-navigation-back"]
        XCTAssertTrue(chatBack.waitForExistence(timeout: 5)); chatBack.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.buttons["search-close"].tap(); usleep(800_000)
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        let cancelled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter().wait(for: [cancelled], timeout: 5), .completed, "Cancel dismisses the native input")
        if let rowID, let beforeY {
            let restored = app.descendants(matching: .any).matching(identifier: rowID).firstMatch
            XCTAssertTrue(restored.exists)
            XCTAssertLessThan(abs(restored.frame.minY - beforeY), 2, "Cancel restores the original native scroll offset")
        }
        shot("search-07-cancel-restores-position")
        search.tap(); XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "A new search session autofocuses again")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.45)))
        XCTAssertTrue(search.waitForExistence(timeout: 5)); shot("search-08-edge-swipe-restores")
    }

    /// Run on WalkBoris with UNYRI_WALK_NOTIFY_PAIRED=1 in the test runner environment;
    /// launch inappnotify-send concurrently on WalkAnya. setUp skips an unpaired battery.
    private func inAppNotificationProbe() throws {
        XCTAssertTrue(app.buttons["chats-search"].waitForExistence(timeout: 20))
        let banner = app.descendants(matching: .any).matching(identifier: "inapp-notification-banner").firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 40)); shot("inappnotify-01-list-banner")
        banner.swipeUp(); usleep(300_000)
        XCTAssertFalse(banner.exists); shot("inappnotify-02-swipe-dismiss")
        XCTAssertTrue(banner.waitForExistence(timeout: 15)); shot("inappnotify-03-next-banner")
        sleep(4)
        XCTAssertFalse(banner.exists); shot("inappnotify-04-one-shot-expiry")
        XCTAssertTrue(banner.waitForExistence(timeout: 15)); shot("inappnotify-05-tap")
        banner.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.5)).tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 10)); shot("inappnotify-06-open-chat")
        XCTAssertFalse(banner.exists, "Opening the target consumes the banner")
        let sameChat = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "QA foreground same chat")).firstMatch
        XCTAssertTrue(sameChat.waitForExistence(timeout: 15))
        XCTAssertFalse(banner.exists, "The fourth peer message arrives in the open conversation without a banner")
        shot("inappnotify-07-same-chat-message-only")
        back()
        XCTAssertFalse(composer.exists, "One banner tap must create only one chat destination")
        XCTAssertTrue(app.buttons["chats-search"].exists)
    }

    private func inAppNotificationSender() throws {
        openChat(with: env["UNYRI_WALK_PEER"]!)
        sleep(8)
        send("QA foreground swipe"); sleep(6)
        send("QA foreground expiry"); sleep(6)
        send("QA foreground tap"); sleep(6)
        send("QA foreground same chat"); shot("inappnotify-sender-complete")
    }

    private func back() {
        for id in ["contacts-back", "search-close"] {
            if app.buttons[id].exists { app.buttons[id].tap(); sleep(1); return }
        }
        let button = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@ OR label == %@", "chat-navigation-back", "Back", "Назад")).firstMatch
        if button.waitForExistence(timeout: 5) { button.tap() }
        else {
            let settingsBack = app.navigationBars.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Settings", "Настройки")).firstMatch
            if settingsBack.exists { settingsBack.tap() }
        }
        sleep(1)
    }

    private func lastBubble(containing text: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).allElementsBoundByIndex.last
            ?? app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func longPress(_ element: XCUIElement) {
        guard element.waitForExistence(timeout: 10) else { return }
        element.press(forDuration: 0.8); sleep(1)
    }

    private func dismissMenu() {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap(); sleep(1)
    }

    private func textField(_ labels: [String]) -> XCUIElement {
        for label in labels {
            for query in [app.textFields, app.secureTextFields] where query[label].exists { return query[label] }
        }
        return app.textFields[labels[0]]
    }

    private func type(into field: XCUIElement, _ text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 10), "missing field")
        field.tap(); field.typeText(text)
    }

    private func tapFirst(_ labels: [String], required: Bool = true) {
        for label in labels {
            let button = app.buttons.matching(identifier: label).firstMatch.exists ? app.buttons.matching(identifier: label).firstMatch
                : app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
            if button.waitForExistence(timeout: 2) { button.tap(); return }
        }
        if required { shot("missing-" + labels[0].replacingOccurrences(of: " ", with: "-")); XCTFail("Missing button \(labels)") }
    }

    private func shot(_ name: String, application: XCUIApplication? = nil) {
        step += 1
        let role = env["UNYRI_WALK_ROLE"] ?? "walk"
        let file = out.appendingPathComponent(String(format: "%@-%02d-%@.png", role, step, name))
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: file)
        let tree = out.appendingPathComponent(String(format: "%@-%02d-%@.txt", role, step, name))
        try? (application ?? app!).debugDescription.write(to: tree, atomically: true, encoding: .utf8)
    }
    /// Both layouts scroll at large text sizes; scroll inside the panel, never across its edge.
    @discardableResult private func revealDrawerControl(_ identifier: String) -> XCUIElement {
        let element = identifier == "drawer-version" ? app.staticTexts[identifier] : app.buttons[identifier]
        let panel = app.descendants(matching: .any).matching(identifier: "main-drawer").firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 8), "Open the drawer before revealing " + identifier)
        for _ in 0..<16 {
            let viewport = panel.exists ? panel.frame.intersection(app.frame).insetBy(dx: 0, dy: 6) : app.frame
            if element.exists && element.isHittable && viewport.contains(element.frame) { return element }
            let down = element.exists && element.frame.minY < viewport.minY
            let x = viewport.midX
            let distance = min(250, viewport.height * 0.4)
            let start = CGPoint(x: x, y: viewport.midY + (down ? -distance / 2 : distance / 2))
            let end = CGPoint(x: x, y: viewport.midY + (down ? distance / 2 : -distance / 2))
            let origin = app.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: start.x - app.frame.minX, dy: start.y - app.frame.minY))
                .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: end.x - app.frame.minX, dy: end.y - app.frame.minY)))
        }
        XCTAssertTrue(element.exists && element.isHittable, "Drawer control not reachable: " + identifier)
        return element
    }

    private func dismissDrawerBackdrop() {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.99, dy: 0.5)).tap()
    }

}
