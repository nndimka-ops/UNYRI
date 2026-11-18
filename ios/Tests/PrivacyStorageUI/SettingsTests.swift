import XCTest

final class SettingsTests: XCTestCase {
    func launch(_ scenario: String = "normal", language: String = "en", large: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["SETTINGS_SCENARIO"] = scenario
        app.launchEnvironment["SETTINGS_LANGUAGE"] = language
        app.launchEnvironment["SETTINGS_LARGE"] = large ? "1" : "0"
        app.launch(); return app
    }
    func capture(_ name: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    func reveal(_ element: XCUIElement, _ app: XCUIApplication) {
        for _ in 0..<7 { if element.isHittable { return }; app.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }
    func revealAbove(_ element: XCUIElement, _ app: XCUIApplication) {
        for _ in 0..<7 { if element.isHittable { return }; app.swipeDown() }
        XCTAssertTrue(element.isHittable)
    }
    func openAutoDelete(_ app: XCUIApplication) {
        let entry = app.buttons["open-auto-delete"]; reveal(entry, app); entry.tap()
        XCTAssertTrue(app.switches["auto-delete-enabled"].waitForExistence(timeout: 5))
    }
    func prepareAutoDelete(_ app: XCUIApplication, preset: Int) {
        let enabled = app.switches["auto-delete-enabled"]
        revealAbove(enabled, app); enabled.tap()
        let choice = app.buttons["auto-delete-preset-\(preset)"]; reveal(choice, app); choice.tap()
        let password = app.secureTextFields["auto-delete-password"]; reveal(password, app)
        password.tap(); password.typeText("synthetic-settings-password")
        let done = app.toolbars.buttons["Done"]; if done.exists { done.tap() }
        let save = app.buttons["auto-delete-save"]; reveal(save, app); XCTAssertTrue(save.isEnabled); save.tap()
        XCTAssertTrue(app.buttons["auto-delete-confirm"].waitForExistence(timeout: 5))
    }
    func testServerAcknowledgedReceiptSwitchAndConflictRecovery() {
        let app = launch("conflict")
        app.buttons["open-privacy"].tap()
        let receipt = app.switches["privacy-read-receipts"]
        XCTAssertTrue(receipt.waitForExistence(timeout: 5)); reveal(receipt, app)
        XCTAssertEqual(receipt.value as? String, "1")
        receipt.tap()
        XCTAssertTrue(app.staticTexts["Privacy changed on another device. Refresh and try again."].waitForExistence(timeout: 5))
        XCTAssertEqual(receipt.value as? String, "1", "A rejected write cannot optimistically change receipt state")
        reveal(receipt, app); receipt.tap()
        let acknowledged = NSPredicate(format: "value == %@", "0")
        expectation(for: acknowledged, evaluatedWith: receipt); waitForExpectations(timeout: 5)
        capture("privacy-server-acknowledged", app)
    }
    func testDetailedDownloadRulesAndCacheControls() {
        let app = launch()
        app.buttons["open-downloads"].tap()
        let photos = app.switches["download-wifi-photo"]
        XCTAssertTrue(photos.waitForExistence(timeout: 4)); photos.tap()
        XCTAssertEqual(photos.value as? String, "1")
        XCTAssertEqual(app.switches["download-wifi-video"].value as? String, "0")
        capture("downloads-per-kind", app)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["open-cache"].tap()
        XCTAssertTrue(app.buttons["cache-maximum"].waitForExistence(timeout: 4))
        capture("cache-policy-per-chat", app)
        let chat = app.buttons["cache-chat-fixture"]
        XCTAssertTrue(chat.exists); reveal(chat, app); chat.tap()
        let clear = app.buttons["Clear"]
        XCTAssertTrue(clear.waitForExistence(timeout: 4)); capture("cache-clear-confirmation", app); clear.tap()
    }
    func testRussianLargeTextSettings() {
        let app = launch(language: "ru", large: true)
        let downloads = app.buttons["open-downloads"]; reveal(downloads, app); downloads.tap()
        let photo = app.switches["download-wifi-photo"]
        XCTAssertTrue(photo.waitForExistence(timeout: 4)); reveal(photo, app)
        capture("downloads-russian-accessibility2", app)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let cache = app.buttons["open-cache"]; reveal(cache, app); cache.tap()
        XCTAssertTrue(app.buttons["cache-maximum"].waitForExistence(timeout: 4))
        capture("cache-russian-accessibility2", app)
    }
    func testPrivacyExceptionsSearchAndAcknowledgedRemoval() {
        let app = launch(); app.buttons["open-privacy"].tap()
        let allow = app.buttons["privacy-always-allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 4)); reveal(allow, app); allow.tap()
        let search = app.textFields["Search by name or username"]
        XCTAssertTrue(search.waitForExistence(timeout: 4)); search.tap(); search.typeText("alex")
        let person = app.buttons.containing(.staticText, identifier: "Alexandra With A Long Display Name").firstMatch
        XCTAssertTrue(person.waitForExistence(timeout: 5)); reveal(person, app); person.tap()
        let remove = app.buttons["Remove"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5)); reveal(remove, app)
        capture("privacy-exceptions-selected", app); remove.tap()
        let gone = NSPredicate(format: "exists == false"); expectation(for: gone, evaluatedWith: remove); waitForExpectations(timeout: 4)
    }
    func testColdLockShieldKeepsNativeCallActions() {
        let app = launch("lock")
        XCTAssertTrue(app.staticTexts["COCO is locked"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["private-content"].isHittable)
        XCTAssertFalse(app.staticTexts["private-modal"].isHittable)
        XCTAssertTrue(app.buttons["Answer"].exists); XCTAssertTrue(app.buttons["Decline"].exists)
        app.buttons["Answer"].tap()
        XCTAssertTrue(app.buttons["End call"].waitForExistence(timeout: 4))
        capture("lock-shield-native-call-controls", app)
        app.buttons["End call"].tap()
        XCTAssertTrue(app.buttons["Unlock"].exists)
        XCTAssertFalse(app.staticTexts["private-content"].isHittable)
    }
    func testAutoDeleteStartsOffAndRequiresExplicitConfirmation() {
        let app = launch()
        let entry = app.buttons["open-auto-delete"]; reveal(entry, app); entry.tap()
        let enabled = app.switches["auto-delete-enabled"]
        XCTAssertTrue(enabled.waitForExistence(timeout: 5)); XCTAssertEqual(enabled.value as? String, "0")
        XCTAssertFalse(app.secureTextFields["auto-delete-password"].exists)
        let save = app.buttons["auto-delete-save"]; reveal(save, app); XCTAssertFalse(save.isEnabled)
        for _ in 0..<3 { app.swipeDown() }
        reveal(enabled, app); enabled.tap()
        let preset = app.buttons["auto-delete-preset-1095"]; reveal(preset, app); preset.tap()
        let days = app.textFields["auto-delete-days"]; reveal(days, app)
        XCTAssertEqual(days.value as? String, "1095")
        let password = app.secureTextFields["auto-delete-password"]; reveal(password, app)
        password.tap(); password.typeText("synthetic-settings-password")
        let done = app.toolbars.buttons["Done"]; if done.exists { done.tap() }
        reveal(save, app); XCTAssertTrue(save.isEnabled); save.tap()
        let confirm = app.buttons["auto-delete-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Separately saved copies and screenshots cannot be erased remotely."].exists)
        capture("account-auto-delete-confirmation", app)
        app.buttons["Cancel"].tap()
        XCTAssertEqual(app.staticTexts["auto-delete-current"].label, "Off", "Cancel cannot persist a draft")
        reveal(save, app); XCTAssertFalse(save.isEnabled, "Cancel clears the password proof")
    }
    func testAutoDeleteRussianLargeTextKeepsExactDaysAndPresetsReachable() {
        let app = launch(language: "ru", large: true)
        let entry = app.buttons["open-auto-delete"]; reveal(entry, app); entry.tap()
        let enabled = app.switches["auto-delete-enabled"]
        XCTAssertTrue(enabled.waitForExistence(timeout: 5)); reveal(enabled, app); enabled.tap()
        for value in [1, 3, 7, 365, 1095] {
            let preset = app.buttons["auto-delete-preset-\(value)"]; reveal(preset, app)
            XCTAssertTrue(preset.isHittable)
        }
        capture("account-auto-delete-russian-accessibility2", app)
    }
    func testAutoDeleteConfirmsMinimumAndMaximumPeriods() {
        for count in [1, 1095] {
            let app = launch(); openAutoDelete(app); prepareAutoDelete(app, preset: count)
            let confirm = app.buttons["auto-delete-confirm"]; reveal(confirm, app); confirm.tap()
            let current = app.staticTexts["auto-delete-current"]
            let expected = "After \(count) days of inactivity"
            expectation(for: NSPredicate(format: "label == %@", expected), evaluatedWith: current)
            waitForExpectations(timeout: 5)
            XCTAssertFalse(app.buttons["auto-delete-save"].isEnabled)
            capture("account-auto-delete-saved-\(count)-days", app)
            app.terminate()
        }
    }
    func testAutoDeleteConflictRefreshesLatestPolicyAndClearsPassword() {
        let app = launch("auto-delete-conflict"); openAutoDelete(app); prepareAutoDelete(app, preset: 3)
        let confirm = app.buttons["auto-delete-confirm"]; reveal(confirm, app); confirm.tap()
        XCTAssertTrue(app.staticTexts["Account auto-delete changed on another device. Review the latest setting and try again."].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["auto-delete-current"].label, "After 7 days of inactivity")
        XCTAssertFalse(app.buttons["auto-delete-save"].isEnabled, "Conflict needs a new explicit draft and password proof")
        capture("account-auto-delete-conflict-refetched", app)
    }
}
