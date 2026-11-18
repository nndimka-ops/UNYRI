import XCTest

/// Production SwiftUI views with account/network seams, not a media or real
/// 30-device call test. Native core suites separately exercise real state/data.
final class ConferencePresentationUITests: XCTestCase {
    private var app: XCUIApplication!
    override func setUp() { continueAfterFailure = false }
    private func launch(_ flags: [String]) {
        app?.terminate()
        app = XCUIApplication(bundleIdentifier: "com.coco.qa.conference.presentation")
        let russian = flags.contains("--russian")
        app.launchArguments = flags + ["-AppleLanguages", russian ? "(ru)" : "(en)", "-AppleLocale", russian ? "ru_RU" : "en_US"]
        app.launch()
    }
    private func capture(_ name: String) {
        let value = XCTAttachment(screenshot: app.screenshot()); value.name = name; value.lifetime = .keepAlways; add(value)
        let window = app.windows.firstMatch.frame
        if window.width > window.height {
            // App-scoped XCTest crops can carry a stale portrait crop with a
            // landscape EXIF tag. Keep the unmodified whole-screen evidence as
            // well; never infer a production layout defect from that crop alone.
            let whole = XCUIScreen.main.screenshot()
            let screen = XCTAttachment(screenshot: whole); screen.name = name + "-whole-screen"
            screen.lifetime = .keepAlways; add(screen)
            let bounds = XCTAttachment(string: "app=\(app.frame); window=\(window); screenshot=\(whole.image.size); orientation=\(XCUIDevice.shared.orientation.rawValue)")
            bounds.name = name + "-capture-bounds"; bounds.lifetime = .keepAlways; add(bounds)
        }
    }
    private func toggleSwitch(_ control: XCUIElement) {
        let thumb = control.switches.firstMatch
        (thumb.exists ? thumb : control).tap()
    }
    func testRootReturnBannerDoesNotCoverNavigation() {
        for flags in [["--root-banner", "--minimized"], ["--root-banner", "--minimized", "--large"], ["--root-banner", "--minimized", "--landscape"]] {
            XCUIDevice.shared.orientation = flags.contains("--landscape") ? .landscapeLeft : .portrait
            launch(flags)
            let banner = app.buttons["conference-return"]
            let navigation = app.navigationBars["Rooms"]
            XCTAssertTrue(banner.waitForExistence(timeout: 8))
            XCTAssertTrue(navigation.waitForExistence(timeout: 8))
            let bounds = XCTAttachment(string: "banner=\(banner.frame); navigation=\(navigation.frame)")
            bounds.name = "root-return-banner-bounds"; bounds.lifetime = .keepAlways; add(bounds)
            capture("root-return-banner-\(flags.last!)")
            XCTAssertLessThanOrEqual(banner.frame.maxY, navigation.frame.minY + 1,
                                     "The room return banner must reserve space above the navigation bar")
            XCTAssertTrue(app.buttons["Open menu"].isHittable)
            XCTAssertTrue(app.buttons["rooms-join-invitation"].isHittable)
            XCTAssertTrue(app.buttons["rooms-create"].isHittable)
        }
    }
    func testInvitationIsNarrowWithoutInlineShareAndKeepsSingleJoinAction() {
        XCUIDevice.shared.orientation = .portrait
        for flags in [["--invite-card"], ["--invite-card", "--large"]] {
            launch(flags)
            let card = app.descendants(matching: .any)["conference-outgoing-invitation"].firstMatch
            XCTAssertTrue(card.waitForExistence(timeout: 5))
            XCTAssertLessThanOrEqual(card.frame.width, 210.5)
            XCTAssertFalse(app.buttons["conference-invitation-share"].exists)
            XCTAssertEqual(app.buttons.matching(identifier: "conference-invite-join").count, 1)
            XCTAssertFalse(app.buttons["conference-invite-accept"].exists)
            XCTAssertFalse(app.buttons["conference-invite-decline"].exists)
            capture(flags.count == 1 ? "compact-invitation-no-inline-share" : "compact-invitation-accessibility-text")
        }
    }
    private func cancelConfirmation(_ title: String) {
        let cancel = app.buttons["Cancel"]
        if cancel.exists {
            cancel.tap()
        } else {
            // Native popovers may omit the cancel row; tapping outside dismisses them.
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.15)).tap()
        }
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts[title])
        waitForExpectations(timeout: 5)
    }
    private func settleLandscape() {
        XCUIDevice.shared.orientation = .landscapeRight
        let landscape = NSPredicate { _, _ in
            let window = self.app.windows.firstMatch.frame
            return window.width > window.height
        }
        expectation(for: landscape, evaluatedWith: nil)
        waitForExpectations(timeout: 8)
    }
    private func reachableControls() {
        let leave = app.buttons["conference-leave"]
        XCTAssertTrue(leave.waitForExistence(timeout: 10)); XCTAssertTrue(leave.isHittable)
        XCTAssertGreaterThanOrEqual(leave.frame.width, 44); XCTAssertGreaterThanOrEqual(leave.frame.height, 44)
        let window = app.windows.firstMatch.frame
        XCTAssertTrue(window.contains(leave.frame), "Leave target must be fully inside the app window")
        let grid = app.descendants(matching: .any)["conference-paged-grid"].firstMatch
        if grid.exists {
            XCTAssertGreaterThanOrEqual(grid.frame.height, 44)
            XCTAssertTrue(window.contains(grid.frame), "Entire grid must stay within the app window")
            XCTAssertTrue(grid.frame.intersection(leave.frame).isNull, "Grid and leave target must not overlap: \(grid.frame), \(leave.frame)")
        }
    }
    func testSingleListenerHasCompactStageAndCameraRestoresGrid() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--empty"])
        let idle = app.descendants(matching: .any)["conference-single-listener-stage"].firstMatch
        XCTAssertTrue(idle.waitForExistence(timeout: 10)); reachableControls()
        capture("single-camera-off-compact-idle")
        app.buttons["Enable microphone"].tap()
        XCTAssertTrue(app.buttons["Mute microphone"].waitForExistence(timeout: 3))
        XCTAssertTrue(idle.exists)
        XCTAssertFalse(app.staticTexts["Camera and microphone off on entry"].exists,
                       "A live microphone must never be described as disabled")
        capture("single-microphone-on-no-false-off-caption")
        app.buttons["Mute microphone"].tap()
        XCTAssertTrue(app.buttons["Enable microphone"].waitForExistence(timeout: 3))
        app.buttons["Enable camera"].tap()
        XCTAssertFalse(idle.exists)
        XCTAssertTrue(app.descendants(matching: .any)["conference-paged-grid"].firstMatch.exists)
        reachableControls(); capture("single-camera-on-own-grid")
        app.buttons["Disable camera"].tap()
        XCTAssertTrue(idle.waitForExistence(timeout: 5)); reachableControls()
    }
    func testOwnedRoomWithoutSavedKeyOffersExplicitInvitation() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--rooms"])
        app.buttons["Mine"].tap()
        let rejoin = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "room-owned-rejoin-")).firstMatch
        XCTAssertTrue(rejoin.waitForExistence(timeout: 10)); XCTAssertTrue(rejoin.isHittable)
        rejoin.tap()
        XCTAssertTrue(app.secureTextFields["conference-invite"].waitForExistence(timeout: 5))
        capture("owned-without-key-explicit-invitation")
    }
    func testSingleListenerStageRemainsReachableWithLargeRussianText() {
        for flags in [["--empty", "--russian", "--large"], ["--empty", "--russian", "--large", "--landscape", "--small"]] {
            XCUIDevice.shared.orientation = flags.contains("--landscape") ? .landscapeRight : .portrait
            launch(flags)
            if flags.contains("--landscape") { settleLandscape() }
            let stage = app.descendants(matching: .any)["conference-single-listener-stage"].firstMatch
            XCTAssertTrue(stage.waitForExistence(timeout: 10)); reachableControls()
            let invite = app.buttons.matching(NSPredicate(format:
                "label IN %@ AND identifier != %@", ["Invite participants", "Пригласить участников"], "conference-invite-participants")).firstMatch
            XCTAssertTrue(invite.exists, "The actual idle card, not the dock, must offer an invitation")
            for _ in 0..<6 {
                if invite.isHittable && app.frame.contains(invite.frame) { break }
                app.scrollViews.firstMatch.swipeUp()
            }
            XCTAssertTrue(invite.isHittable)
            XCTAssertTrue(app.frame.contains(invite.frame))
            XCTAssertGreaterThanOrEqual(invite.frame.height, 44)
            XCTAssertTrue(invite.frame.intersection(app.buttons["conference-leave"].frame).isNull)
            reachableControls(); capture("single-listener-russian-large-\(flags.count)")
            invite.tap()
            XCTAssertTrue(app.buttons["Copy invitation"].waitForExistence(timeout: 5) ||
                          app.buttons["Скопировать приглашение"].waitForExistence(timeout: 5),
                          "The visible idle action must really open the invitation sheet")
        }
    }
    func testNativeConferenceViewsAndControls() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--preview"]); reachableControls(); capture("portrait-30-own-preview")
        let next = app.buttons["Next participants"]
        for _ in 0..<4 { XCTAssertTrue(next.isEnabled); next.tap() }
        XCTAssertFalse(next.isEnabled); capture("portrait-last-page-30")
        app.buttons["30 participants"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("30")
        let person = app.buttons["conference-participant-fixture-29"]
        XCTAssertTrue(person.waitForExistence(timeout: 5)); person.tap()
        let grid = app.buttons["conference-back-to-grid"]
        XCTAssertTrue(grid.waitForExistence(timeout: 5)); capture("directory-focus-30"); grid.tap()
        app.buttons["Invite participants"].firstMatch.tap()
        let destination = app.buttons.containing(.staticText, identifier: "Участник 1").firstMatch
        XCTAssertTrue(destination.waitForExistence(timeout: 5)); destination.tap()
        app.buttons.containing(.staticText, identifier: "Участник 2").firstMatch.tap()
        capture("invite-multi-selected")
        let send = app.buttons["conference-send-selected"]
        XCTAssertTrue(send.isEnabled); XCTAssertTrue(send.label.contains("2")); send.tap()
        XCTAssertTrue(app.buttons["conference-leave"].waitForExistence(timeout: 5))
    }
    func testLandscapeAccessibilityCardsAndMinimize() {
        launch(["--preview", "--landscape"])
        settleLandscape()
        reachableControls(); capture("landscape-30-own-preview")
        launch(["--large", "--landscape", "--small", "--error", "--reconnecting"])
        settleLandscape()
        reachableControls(); capture("landscape-568x320-accessibility3-error")

        XCUIDevice.shared.orientation = .portrait
        launch(["--invite-card"])
        let join = app.buttons["conference-invite-join"]
        XCTAssertTrue(join.waitForExistence(timeout: 5)); XCTAssertTrue(join.isHittable)
        XCTAssertFalse(app.buttons["conference-invite-decline"].exists)
        capture("encrypted-invite-cards-single-join")
        launch(["--minimized"])
        let mini = app.buttons["conference-return"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5)); XCTAssertGreaterThanOrEqual(mini.frame.height, 48)
        capture("minimized-reserved-strip"); mini.tap()
        reachableControls(); app.buttons["Minimize conference"].tap()
        XCTAssertTrue(mini.waitForExistence(timeout: 5)); capture("return-minimize-same-room")
    }
    func testRussianPresentationFrames() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--russian", "--preview"]); reachableControls(); capture("russian-portrait-30")
        launch(["--russian", "--landscape", "--large", "--small", "--error", "--reconnecting"])
        settleLandscape()
        reachableControls(); capture("russian-small-landscape-accessibility3-error")
    }
    func testCustomDensityOverviewAndHostControls() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--preview", "--presentation"]); reachableControls()
        app.buttons["More conference actions"].tap()
        app.buttons["Grid layout"].tap()
        let automatic = app.switches["conference-auto-grid"]
        XCTAssertTrue(automatic.waitForExistence(timeout: 5)); toggleSwitch(automatic)
        XCTAssertEqual(automatic.value as? String, "0")
        app.buttons["Fewer visible participants"].tap()
        XCTAssertEqual(app.sliders["conference-visible-count"].value as? String, "5")
        app.buttons["Done"].tap()
        let grid = app.scrollViews["conference-scroll-grid"]
        XCTAssertTrue(grid.waitForExistence(timeout: 5)); capture("scroll-grid-five-of-thirty")
        for _ in 0..<5 { grid.swipeUp() }
        capture("scroll-grid-lower-participants")
        app.buttons["Minimize conference"].tap()
        let mini = app.buttons["conference-return"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5)); mini.tap()
        XCTAssertTrue(app.scrollViews["conference-scroll-grid"].waitForExistence(timeout: 5))
        app.buttons["More conference actions"].tap(); app.buttons["Grid layout"].tap()
        toggleSwitch(app.switches["conference-fit-everyone"]); app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["conference-overview-fixture-29"].waitForExistence(timeout: 5))
        reachableControls(); capture("fit-everyone-thirty")
        app.buttons["More conference actions"].tap()
        XCTAssertTrue(app.buttons["Room settings"].exists)
        app.buttons["Room settings"].tap()
        XCTAssertTrue(app.switches["Pause new entry"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        launch(["--legacy-server"])
        app.buttons["More conference actions"].tap()
        XCTAssertFalse(app.buttons["Room settings"].exists)
    }

    func testOwnGridCellLocalMenuAndOwnerActions() {
        XCUIDevice.shared.orientation = .portrait
        launch(["--preview"]); reachableControls()
        let own = app.buttons["conference-own-grid-tile"]
        let peer = app.buttons["conference-grid-fixture-1"]
        XCTAssertTrue(own.waitForExistence(timeout: 5)); XCTAssertTrue(peer.exists)
        XCTAssertTrue(own.frame.intersection(peer.frame).isNull, "Own camera must occupy a separate cell, never overlay another participant")
        capture("own-camera-first-grid-cell")
        peer.press(forDuration: 0.7)
        XCTAssertTrue(app.buttons["Mute participant for me"].waitForExistence(timeout: 5))
        app.buttons["Mute participant for me"].tap()
        peer.press(forDuration: 0.7)
        XCTAssertTrue(app.buttons["Hear participant"].waitForExistence(timeout: 5))
        app.buttons["Hide participant video"].tap()
        peer.press(forDuration: 0.7)
        XCTAssertTrue(app.buttons["Show participant video"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Mute for everyone"].exists)
        app.buttons["Mute for everyone"].tap()
        peer.press(forDuration: 0.7)
        XCTAssertTrue(app.buttons["Allow microphone"].waitForExistence(timeout: 5))
        capture("native-participant-actions-local-and-room")
        app.buttons["Remove from room"].tap()
        XCTAssertTrue(app.staticTexts["Remove participant from this room?"].waitForExistence(timeout: 5))
        cancelConfirmation("Remove participant from this room?")
        peer.tap()
        let strip = app.descendants(matching: .any)["conference-own-focus-strip"].firstMatch
        XCTAssertTrue(strip.waitForExistence(timeout: 5)); capture("own-camera-in-separate-focus-strip")
        launch(["--legacy-server"])
        app.buttons["conference-grid-fixture-1"].press(forDuration: 0.7)
        XCTAssertTrue(app.buttons["Mute participant for me"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Mute for everyone"].exists)
        XCTAssertFalse(app.buttons["Remove from room"].exists)
    }

    func testOwnedRoomsEndRequiresConfirmationAndLegacyGate() {
        launch(["--rooms"])
        let entry = app.segmentedControls.buttons["Mine"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
        XCTAssertTrue(app.staticTexts["Unused room 1"].waitForExistence(timeout: 5))
        let end = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "End room")).firstMatch
        XCTAssertTrue(end.isHittable); end.tap()
        XCTAssertTrue(app.staticTexts["End this room for everyone?"].waitForExistence(timeout: 5))
        cancelConfirmation("End this room for everyone?")
        XCTAssertTrue(app.staticTexts["Unused room 1"].exists)
        end.tap(); app.buttons["End for everyone"].tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["Unused room 1"])
        waitForExpectations(timeout: 5)
        capture("owned-rooms-confirmed-end")
        launch(["--rooms", "--legacy-server"])
        XCTAssertTrue(app.buttons["rooms-create"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["rooms-create"].isHittable)
        XCTAssertTrue(app.buttons["rooms-join-invitation"].isHittable)
        XCTAssertFalse(app.staticTexts["Unused room 1"].exists)
    }

    func testBlockedMicrophoneExplainsRestrictionOnFirstTap() {
        launch(["--host-blocked"])
        let microphone = app.buttons["Microphone disabled by the room creator. Tap for details."]
        XCTAssertTrue(microphone.waitForExistence(timeout: 5)); XCTAssertTrue(microphone.isHittable)
        XCTAssertGreaterThanOrEqual(microphone.frame.width, 44)
        capture("host-microphone-lock-before-tap"); microphone.tap()
        let explanation = app.alerts.staticTexts["The room creator disabled your microphone. You can enable it yourself after the restriction is lifted."]
        XCTAssertTrue(explanation.waitForExistence(timeout: 5)); capture("host-microphone-first-tap-info")
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(microphone.exists)
    }
    func testDirectoryAccessModesAndCompactCreateForm() {
        launch(["--rooms"])
        XCTAssertTrue(app.staticTexts["Room 1"].waitForExistence(timeout: 5)); capture("rooms-server-directory-four-access-modes")
        app.buttons["rooms-create"].tap()
        XCTAssertTrue(app.textFields["Title"].waitForExistence(timeout: 5))
        app.buttons["Room access"].tap()
        XCTAssertTrue(app.buttons["Open room"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Invitation and password"].exists)
        app.buttons["Invitation and password"].tap()
        XCTAssertTrue(app.secureTextFields["Room password"].exists)
        capture("rooms-native-create-protected-access")
        app.buttons["Cancel"].tap()
    }
    func testCreateValidatesPasswordBeforeSubmission() {
        launch(["--rooms"])
        let create = app.buttons["rooms-create"]
        XCTAssertTrue(create.waitForExistence(timeout: 10)); create.tap()
        let submit = app.buttons["Create"]
        XCTAssertFalse(submit.isEnabled)
        let title = app.textFields["Title"]; title.tap(); title.typeText("My room")
        XCTAssertTrue(submit.isEnabled)
        app.buttons["Room access"].tap(); app.buttons["Invitation and password"].tap()
        XCTAssertFalse(submit.isEnabled)
        let password = app.secureTextFields["Room password"]; password.tap(); password.typeText("123")
        XCTAssertFalse(submit.isEnabled)
        password.typeText("456")
        XCTAssertTrue(submit.isEnabled); capture("create-password-live-validation")
    }

    func testCatalogEntryExplainsOfflinePeerAndHandsOffToInvitation() {
        launch(["--rooms"])
        func visibleButton(_ label: String) -> XCUIElement {
            let buttons = app.buttons.matching(NSPredicate(format: "label == %@", label))
                .allElementsBoundByIndex.filter { $0.isEnabled && $0.isHittable }
            XCTAssertEqual(buttons.count, 1, "Use the actual visible sheet action: " + label)
            return buttons[0]
        }
        for title in ["Room 1", "Room 3"] {
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 5)); app.staticTexts[title].tap()
            XCTAssertTrue(app.navigationBars["Join room"].waitForExistence(timeout: 5))
            let request = app.buttons["room-catalog-request-key"]
            if title == "Room 1" {
                // An open room may queue a request while its provider is
                // temporarily offline; availability changes its honest label,
                // not the existing authorization/timeout semantics.
                XCTAssertTrue(request.exists); XCTAssertTrue(request.isEnabled)
                XCTAssertEqual(request.label, "Wait for a participant")
                XCTAssertTrue(app.staticTexts["Nobody can connect you right now. Wait for a participant to come online, or use an invitation."].exists)
            } else { XCTAssertFalse(request.exists, "Invite-only entry cannot request an open-room key") }
            capture("catalog-entry-before-invitation-" + title)
            visibleButton("Join by invitation").tap()
            XCTAssertTrue(app.secureTextFields["conference-invite"].waitForExistence(timeout: 8),
                          "Dismiss the catalog sheet before opening the real invitation lobby")
            capture("catalog-entry-invitation-lobby-" + title)
            visibleButton("Close").tap()
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 5))
        }
        app.staticTexts["Room 2"].tap()
        let password = app.secureTextFields["Room password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        let join = app.buttons["room-catalog-request-key"]
        XCTAssertFalse(join.isEnabled); password.tap(); password.typeText("synthetic-password")
        XCTAssertTrue(join.isEnabled); join.tap()
        let cancelRequest = app.buttons["Cancel request"]
        XCTAssertTrue(cancelRequest.waitForExistence(timeout: 8))
        capture("catalog-password-key-handoff-waiting"); cancelRequest.tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: cancelRequest)
        waitForExpectations(timeout: 5)
    }

}
