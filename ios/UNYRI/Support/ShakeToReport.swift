import SwiftUI
import UIKit
import ObjectiveC

/// SwiftUI owns the UIWindow. Install the motionEnded hook on UIWindow itself,
/// copying its inherited implementation first so UIResponder is never swizzled.
/// The original handler still receives every shake we do not consume (Undo).
@MainActor private enum DiagnosticWindowMotion {
    static let install: Void = {
        let original = #selector(UIResponder.motionEnded(_:with:))
        let replacement = #selector(UIWindow.unyriReportMotionEnded(_:with:))
        guard let inherited = class_getInstanceMethod(UIWindow.self, original) else { return }
        class_addMethod(UIWindow.self, original, method_getImplementation(inherited), method_getTypeEncoding(inherited))
        guard let first = class_getInstanceMethod(UIWindow.self, original),
              let second = class_getInstanceMethod(UIWindow.self, replacement) else { return }
        method_exchangeImplementations(first, second)
    }()
}
extension UIWindow {
    @objc fileprivate func unyriReportMotionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake, ShakeReportPresenter.shared.present(from: self) { return }
        unyriReportMotionEnded(motion, with: event)
    }
}

@MainActor enum DiagnosticReportPresentation { static var visibleReports = 0 }

@MainActor private final class ShakeReportPresenter {
    static let shared = ShakeReportPresenter()
    weak var auth: AuthRepository?
    private weak var reportController: UIViewController?
    func present(from window: UIWindow) -> Bool {
        let defaults = UserDefaults.standard
        let enabled = defaults.object(forKey: DiagnosticReportPreferences.shakeKey) == nil
            ? DiagnosticReportPreferences.buildDefault : defaults.bool(forKey: DiagnosticReportPreferences.shakeKey)
        guard enabled, UIApplication.shared.applicationState == .active, window.isKeyWindow,
              window.windowLevel == .normal, reportController == nil, DiagnosticReportPresentation.visibleReports == 0,
              !NonCallCaptureOwnership.callsBusy(), let auth, let user = auth.currentUser else { return false }
        let owner = auth.authenticatedServerURL.absoluteString + "|" + user.id + "|" + String(auth.sessionGeneration)
        guard !UnyriAppLock.shared.requiresShield(owner: owner), !Self.editing(in: window),
              var presenter = window.rootViewController else { return false }
        while let presented = presenter.presentedViewController {
            guard !presented.isBeingDismissed else { return false }
            presenter = presented
        }
        guard !presenter.isBeingPresented else { return false }
        // Capture the current window BEFORE the report exists; never a cached
        // screenshot, never a screen recording. The user can remove it.
        let format = UIGraphicsImageRendererFormat()
        format.scale = min(window.screen.scale, 1280 / max(1, max(window.bounds.width, window.bounds.height)))
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        guard let screenshot = image.jpegData(compressionQuality: 0.7), screenshot.count <= 1024 * 1024 else { return false }
        PerformanceRecorder.shared.record(.markProblem)
        let snapshot = PerformanceMonitor.shared.reportSnapshot()
        let report = ShakeReportSheet(screenshot: screenshot, performance: snapshot)
            .unyriChatAppearanceRoot().unyriAppColorThemeRoot().environmentObject(auth)
            .unyriLocale(defaults.string(forKey: UnyriL10n.key) ?? "system").preferredColorScheme(.dark)
        let controller = UIHostingController(rootView: report)
        reportController = controller
        presenter.present(controller, animated: true)
        return true
    }
    private static func editing(in view: UIView) -> Bool {
        if view.isFirstResponder && (view is UITextInput || view is UIKeyInput) { return true }
        return view.subviews.contains(where: editing)
    }
}
@MainActor private struct ShakeReportSheet: View {
    let screenshot: Data
    let performance: PerformanceSnapshot?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            BugReportView(screenshots: [screenshot], performance: performance)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
@MainActor private struct ShakeToReportSession: ViewModifier {
    @EnvironmentObject private var auth: AuthRepository
    func body(content: Content) -> some View {
        content.onAppear { _ = DiagnosticWindowMotion.install; ShakeReportPresenter.shared.auth = auth }
    }
}
extension View {
    func shakeToReportSession() -> some View { modifier(ShakeToReportSession()) }
}
