import Foundation
import SwiftUI

/// Selected language is independent of the account and survives sign-out.
enum UnyriL10n {
    static let key = "coco.language"
    static var language: String {
        let saved = UserDefaults.standard.string(forKey: key) ?? "system"
        if ["en", "ru", "uk"].contains(saved) { return saved }
        return systemLanguage
    }
    private static let systemLanguage: String = {
        let preferred = Locale.preferredLanguages.first ?? "en"
        return preferred.hasPrefix("uk") ? "uk" : preferred.hasPrefix("ru") ? "ru" : "en"
    }()
    /// The .lproj bundle is resolved once per language. Looking it up on every call
    /// (a file-system path lookup plus Bundle creation) ran dozens of times per chat
    /// render on the main thread and showed up as scroll micro-stutters.
    private final class BundleCache: @unchecked Sendable {
        let lock = NSLock()
        var language = ""
        var bundle: Bundle = .main
    }
    private static let cache = BundleCache()
    static func syncExtensionLanguage() {
        #if os(iOS)
        let group = UnyriShareStore.groupIdentifier
        guard FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil else { return }
        UserDefaults(suiteName: group)?.set(language, forKey: key)
        #endif
    }
    static var bundle: Bundle {
        let current = language
        cache.lock.lock(); defer { cache.lock.unlock() }
        if cache.language == current { return cache.bundle }
        let resolved = Bundle.main.path(forResource: current, ofType: "lproj").flatMap { Bundle(path: $0) } ?? .main
        cache.language = current; cache.bundle = resolved
        return resolved
    }
    static func string(_ key: String, comment: String = "") -> String { bundle.localizedString(forKey: key, value: key, table: nil) }

    /// The saved picker value ("system", "en", "ru" or "uk").
    static var choice: String { UserDefaults.standard.string(forKey: key) ?? "system" }
    /// The language iOS itself runs the app in (fixed for the process lifetime).
    private static let nativeLanguage: String = Bundle.main.preferredLocalizations.first ?? "en"
    /// The locale SwiftUI must be given, or nil when iOS already runs the app in the
    /// chosen language. With an explicit `\.locale`, SwiftUI resolves every Text through
    /// the per-localization bundle lookup, which re-reads the strings table from disk on
    /// each call: ~0.7 s of main-thread time in a 75 s chat profile, part of every
    /// chat open and scroll frame.
    static func localeOverride(for choice: String) -> Locale? {
        let wanted = ["en", "ru", "uk"].contains(choice) ? choice : systemLanguage
        return nativeLanguage.hasPrefix(wanted) ? nil : Locale(identifier: wanted)
    }
}

extension View {
    /// Applies the in-app language only where it differs from the app's native one.
    /// transformEnvironment keeps view identity, so switching language live does not
    /// rebuild the screen stack.
    func unyriLocale(_ choice: String = UnyriL10n.choice) -> some View {
        let override = UnyriL10n.localeOverride(for: choice)
        return transformEnvironment(\.locale) { locale in
            if let override { locale = override }
        }
    }
}
