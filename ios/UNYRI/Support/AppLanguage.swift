import Foundation

/// Selected language is independent of the account and survives sign-out.
enum UnyriL10n {
    static let key = "coco.language"
    static var language: String {
        let saved = UserDefaults.standard.string(forKey: key) ?? "system"
        if ["en", "ru", "uk"].contains(saved) { return saved }
        let preferred = Locale.preferredLanguages.first ?? "en"
        return preferred.hasPrefix("uk") ? "uk" : preferred.hasPrefix("ru") ? "ru" : "en"
    }
    static func syncExtensionLanguage() {
        #if os(iOS)
        let group = UnyriShareStore.groupIdentifier
        guard FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil else { return }
        UserDefaults(suiteName: group)?.set(language, forKey: key)
        #endif
    }
    static var bundle: Bundle {
        guard let path = Bundle.main.path(forResource: language, ofType: "lproj"), let bundle = Bundle(path: path) else { return .main }
        return bundle
    }
    static func string(_ key: String, comment: String = "") -> String { bundle.localizedString(forKey: key, value: key, table: nil) }
}
