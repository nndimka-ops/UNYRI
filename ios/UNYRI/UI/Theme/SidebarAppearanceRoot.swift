import SwiftUI
import Combine

private struct UnyriSidebarAppearanceSnapshot {
    let settings: UnyriSidebarAppearanceSettings
    let style: UnyriResolvedSidebarStyle
    init(_ settings: UnyriSidebarAppearanceSettings) {
        self.settings = settings
        style = settings.resolved
    }
}

/// One device-owned immutable snapshot; no preference/JSON reads in rows.
@MainActor
final class UnyriSidebarAppearanceStore: ObservableObject {
    static let shared = UnyriSidebarAppearanceStore()
    @Published private var snapshot: UnyriSidebarAppearanceSnapshot
    var settings: UnyriSidebarAppearanceSettings { snapshot.settings }
    var style: UnyriResolvedSidebarStyle { snapshot.style }
    private let defaults: UserDefaults
    private var lastRaw: String?
    private var observation: NSObjectProtocol?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        lastRaw = defaults.object(forKey: UnyriSidebarAppearanceSettings.preferenceKey) as? String
        snapshot = UnyriSidebarAppearanceSnapshot(UnyriSidebarAppearanceCodec.decode(lastRaw))
        observation = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification,
            object: defaults, queue: .main) { [weak self] _ in
                Task { @MainActor [weak self] in self?.reloadIfChanged() }
            }
    }
    deinit { if let observation { NotificationCenter.default.removeObserver(observation) } }

    /// Apply is the only persistence boundary. Draft/reset/preview stay in UI.
    @discardableResult func apply(_ draft: UnyriSidebarAppearanceSettings) -> Bool {
        let chosen = draft.sanitized()
        let raw = UnyriSidebarAppearanceCodec.encode(chosen)
        guard raw.utf8.count <= UnyriSidebarAppearanceSettings.maxUtf8Bytes else { return false }
        lastRaw = raw
        defaults.set(raw, forKey: UnyriSidebarAppearanceSettings.preferenceKey)
        if chosen != snapshot.settings { snapshot = UnyriSidebarAppearanceSnapshot(chosen) }
        return true
    }
    private func reloadIfChanged() {
        let raw = defaults.object(forKey: UnyriSidebarAppearanceSettings.preferenceKey) as? String
        guard raw != lastRaw else { return }
        lastRaw = raw
        let chosen = UnyriSidebarAppearanceCodec.decode(raw)
        if chosen != snapshot.settings { snapshot = UnyriSidebarAppearanceSnapshot(chosen) }
    }
}

private struct UnyriSidebarAppearanceSettingsKey: EnvironmentKey {
    static let defaultValue = UnyriSidebarAppearanceSettings.defaults
}
private struct UnyriSidebarStyleKey: EnvironmentKey {
    static let defaultValue = UnyriSidebarAppearanceSettings.defaults.resolved
}
extension EnvironmentValues {
    var unyriSidebarAppearanceSettings: UnyriSidebarAppearanceSettings {
        get { self[UnyriSidebarAppearanceSettingsKey.self] }
        set { self[UnyriSidebarAppearanceSettingsKey.self] = newValue }
    }
    var unyriSidebarStyle: UnyriResolvedSidebarStyle {
        get { self[UnyriSidebarStyleKey.self] }
        set { self[UnyriSidebarStyleKey.self] = newValue }
    }
}

@MainActor
private struct UnyriSidebarAppearanceRoot: ViewModifier {
    @StateObject private var store = UnyriSidebarAppearanceStore.shared
    func body(content: Content) -> some View {
        content.environmentObject(store)
            .environment(\.unyriSidebarAppearanceSettings, store.settings)
            .environment(\.unyriSidebarStyle, store.style)
    }
}
extension View {
    @MainActor func unyriSidebarAppearanceRoot() -> some View { modifier(UnyriSidebarAppearanceRoot()) }
}
