import Foundation
import Combine

@MainActor
final class UnyriDeviceSettings: ObservableObject {
    static let shared = UnyriDeviceSettings()
    @Published var downloads: AutomaticMediaSettings { didSet { persist(downloads, key: "automaticMedia.v1") } }
    @Published var cache: MediaCachePolicy { didSet { persist(cache, key: "mediaCachePolicy.v1"); NotificationCenter.default.post(name: .unyriCachePolicyChanged, object: nil) } }
    /// "Use less data for calls": read live by the call in progress.
    @Published var callDataSaving: CallDataSaving { didSet { UserDefaults.standard.set(callDataSaving.rawValue, forKey: CallDataSaving.storageKey) } }
    /// "Peer-to-peer calls": decided when a call appears, applies from the next call.
    @Published var callPeerToPeer: CallPeerToPeer { didSet { UserDefaults.standard.set(callPeerToPeer.rawValue, forKey: CallPeerToPeer.storageKey) } }
    private init() {
        let defaults = UserDefaults.standard
        // Saved choices are kept; the legacy switches count only when stored.
        func storedBool(_ key: String) -> Bool? { defaults.object(forKey: key) == nil ? nil : defaults.bool(forKey: key) }
        downloads = .resolved(
            saved: defaults.data(forKey: "automaticMedia.v1").flatMap { try? JSONDecoder().decode(AutomaticMediaSettings.self, from: $0) },
            legacyWifi: storedBool("mediaAutoWifi"), legacyCellular: storedBool("mediaAutoMobile"))
        cache = defaults.data(forKey: "mediaCachePolicy.v1").flatMap { try? JSONDecoder().decode(MediaCachePolicy.self, from: $0) } ?? MediaCachePolicy()
        let storedSaving = defaults.string(forKey: CallDataSaving.storageKey)
        callDataSaving = .resolved(stored: storedSaving, legacyVideo: defaults.string(forKey: CallDataSaving.legacyVideoKey),
                                   legacyAudio: defaults.string(forKey: CallDataSaving.legacyAudioKey))
        callPeerToPeer = .resolved(stored: defaults.string(forKey: CallPeerToPeer.storageKey))
        // One-time move from the old "Call quality" pair; the old keys go.
        if storedSaving.flatMap(CallDataSaving.init(rawValue:)) == nil {
            defaults.set(callDataSaving.rawValue, forKey: CallDataSaving.storageKey)
            defaults.removeObject(forKey: CallDataSaving.legacyVideoKey)
            defaults.removeObject(forKey: CallDataSaving.legacyAudioKey)
        }
    }
    private func persist<T: Encodable>(_ value: T, key: String) { if let data = try? JSONEncoder().encode(value) { UserDefaults.standard.set(data, forKey: key) } }
}
extension Notification.Name { static let unyriCachePolicyChanged = Notification.Name("COCOCachePolicyChanged") }
