import XCTest

/// Run only after the authenticated scenario and a system uninstall/reinstall
/// on the same owned simulator. The application, not the runner, reads Keychain.
@MainActor
final class UNYRIReinstallUITests: XCTestCase {
    func testReinstalledAppRequiresLogin() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["UNYRI_UIQA_BACKEND_ENABLED"] == "1")
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.messenger.unyri.X7G7M9PFJ3")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.textFields["Your @handle"].waitForExistence(timeout: 20),
                      "Uninstall/reinstall must not restore authenticated UI from surviving Keychain")
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Log in")).firstMatch.exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Saved Messages")).firstMatch.exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "reinstalled-app-requires-login"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
