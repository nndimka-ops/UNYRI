import XCTest

/// Drives the installed Release COCO app, not the dummy runner host.
/// Synthetic values only. Every network attempt is forced to unused loopback port 1.
@MainActor
final class UNYRIUnauthenticatedUITests: XCTestCase {
    private var app: XCUIApplication!
    private let serverPlaceholder = "https://server.example or http://192.168.1.10:8080"
    private let unreachableServer = "http://127.0.0.1:1"
    private var loginButton: XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Log in")).firstMatch
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(bundleIdentifier: "com.messenger.unyri.X7G7M9PFJ3")
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.textFields["Your @handle"].waitForExistence(timeout: 15), "Real COCO login screen must appear")
    }

    override func tearDownWithError() throws {
        app?.terminate()
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func reveal(_ element: XCUIElement) {
        for _ in 0..<6 {
            if element.exists && element.isHittable { return }
            scrollVisible(up: true)
        }
        for _ in 0..<6 {
            if element.exists && element.isHittable { return }
            scrollVisible(up: false)
        }
        capture("unreachable-control")
        XCTAssertTrue(element.exists && element.isHittable, "Control must be reachable by scrolling")
    }

    private func scrollVisible(up: Bool) {
        let bounds = app.frame
        let keyboard = app.keyboards.firstMatch
        let bottom = keyboard.exists ? min(bounds.maxY - 32, keyboard.frame.minY - 24) : bounds.maxY - 64
        let navigation = app.navigationBars.firstMatch
        let top = max(bounds.minY + 90, navigation.exists ? navigation.frame.maxY + 16 : bounds.minY + 90)
        guard bottom > top + 40 else { return }
        // Root swipeUp starts inside the keyboard on a focused Form. Drag the
        // visible content margin instead, avoiding text-selection gestures.
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let upper = origin.withOffset(CGVector(dx: bounds.width * 0.96, dy: top - bounds.minY))
        let lower = origin.withOffset(CGVector(dx: bounds.width * 0.96, dy: bottom - bounds.minY))
        (up ? lower : upper).press(forDuration: 0.01, thenDragTo: up ? upper : lower)
    }

    private func replace(_ field: XCUIElement, with text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        let keyboard = app.keyboards.firstMatch
        let secureFrame = field.frame
        let secureVisible = field.elementType == .secureTextField && field.isEnabled &&
            !secureFrame.isEmpty && app.frame.contains(secureFrame) &&
            (!keyboard.exists || secureFrame.maxY < keyboard.frame.minY)
        if !field.isHittable && secureVisible {
            // iOS secure-input redaction can omit AX's visible point while its
            // current on-screen frame remains valid. Real focus/type actions
            // still must succeed; never tap through a keyboard or outside view.
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

    private func openServerEditor() {
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Server")).firstMatch
        reveal(button)
        button.tap()
        XCTAssertTrue(app.textFields[serverPlaceholder].waitForExistence(timeout: 5))
    }

    private func selectLoopback() {
        openServerEditor()
        replace(app.textFields[serverPlaceholder], with: unreachableServer)
        app.buttons["Done"].tap()
        XCTAssertFalse(app.textFields[serverPlaceholder].waitForExistence(timeout: 1), "Valid server selection closes the sheet")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", unreachableServer)).firstMatch.exists)
    }

    func testLoginRequiredFieldsAndPasswordVisibility() {
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        reveal(loginButton)
        loginButton.tap()
        XCTAssertTrue(app.staticTexts["Enter your @handle and password."].waitForExistence(timeout: 5))
        capture("login-required-fields")
        replace(app.secureTextFields["Password"], with: "uiqa-only-no-account")
        app.buttons["Show password"].tap()
        XCTAssertEqual(app.textFields["Password"].value as? String, "uiqa-only-no-account")
        app.buttons["Hide password"].tap()
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        XCTAssertFalse(app.textFields["Password"].exists)
        capture("login-password-hidden")
    }

    func testServerValidationWithKeyboardAndValidSelection() {
        openServerEditor()
        replace(app.textFields[serverPlaceholder], with: "https://bad server.example")
        XCTAssertTrue(app.keyboards.firstMatch.exists, "Editing server must show the native keyboard")
        capture("server-invalid-keyboard")
        app.buttons["Done"].tap()
        XCTAssertTrue(app.textFields[serverPlaceholder].exists, "Invalid address must keep editor open")
        XCTAssertTrue(app.staticTexts["Invalid server address."].waitForExistence(timeout: 5))
        capture("server-validation-error")
        replace(app.textFields[serverPlaceholder], with: unreachableServer)
        app.buttons["Done"].tap()
        XCTAssertFalse(app.textFields[serverPlaceholder].waitForExistence(timeout: 1))
        capture("server-valid-selection")
    }

    func testRegistrationAgreementAndHandleValidation() {
        reveal(app.buttons["Create an account"])
        app.buttons["Create an account"].tap()
        XCTAssertTrue(app.textFields["Your name"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["Choose an @handle"].exists)
        let register = app.buttons["Register"]
        reveal(register)
        XCTAssertFalse(register.isEnabled, "Registration requires the agreement")
        let agreement = app.switches["I accept the alpha test agreement"]
        reveal(agreement)
        agreement.tap()
        XCTAssertTrue(register.isEnabled)
        register.tap()
        XCTAssertTrue(app.staticTexts["The handle must contain 3–32 Latin letters, digits, underscores or dots."].waitForExistence(timeout: 5))
        capture("registration-local-validation")
    }

    func testRecoveryNavigationAndPasswordMismatchGuard() {
        selectLoopback()
        reveal(app.buttons["Account recovery"])
        app.buttons["Account recovery"].tap()
        XCTAssertTrue(app.secureTextFields["Recovery code"].waitForExistence(timeout: 5))
        let reset = app.buttons["Reset password"]
        reveal(reset)
        XCTAssertFalse(reset.isEnabled)
        replace(app.textFields["Your @handle"], with: "uiqa_nonexistent")
        replace(app.secureTextFields["Recovery code"], with: "uiqa-not-a-real-code")
        replace(app.secureTextFields["New password (8–256 characters)"], with: "uiqa-password-123")
        replace(app.secureTextFields["Repeat new password"], with: "uiqa-password-123")
        reveal(reset)
        XCTAssertTrue(reset.isEnabled, "Matching populated fields must enable recovery")
        replace(app.secureTextFields["Repeat new password"], with: "different-uiqa-password")
        reveal(reset)
        XCTAssertFalse(reset.isEnabled, "Mismatching passwords must prevent recovery")
        capture("recovery-password-mismatch")
        // Never submit a reset; this test covers navigation and local validation.
    }

    func testUnreachableLoopbackReturnsToEditableLogin() {
        selectLoopback()
        replace(app.textFields["Your @handle"], with: "uiqa_nonexistent")
        replace(app.secureTextFields["Password"], with: "uiqa-only-no-account")
        let login = loginButton
        reveal(login)
        login.tap()
        XCTAssertTrue(app.staticTexts["Could not reach the server. Check your network connection and server address."].waitForExistence(timeout: 40))
        XCTAssertTrue(login.isEnabled, "Failed request must release the loading state")
        XCTAssertTrue(app.textFields["Your @handle"].isEnabled)
        capture("login-loopback-connection-error")
    }

    func testDiagnosticsDoesNotConsumeLoginTouchesOrKeyboard() {
        app.terminate()
        app.launchArguments += ["-performanceDiagnosticsEnabled", "YES", "-performanceDiagnosticsOnline", "NO"]
        app.launch()
        XCTAssertTrue(app.textFields["Your @handle"].waitForExistence(timeout: 15))
        selectLoopback()
        replace(app.textFields["Your @handle"], with: "uiqa_diagnostics")
        replace(app.secureTextFields["Password"], with: "uiqa-only-no-account")
        let login = loginButton
        reveal(login); XCTAssertTrue(login.isHittable); login.tap()
        XCTAssertTrue(app.staticTexts["Could not reach the server. Check your network connection and server address."].waitForExistence(timeout: 40))
        XCTAssertTrue(login.isEnabled)
        capture("diagnostics-login-touches")
    }
}
