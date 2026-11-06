import Darwin
import Foundation
import UIKit

/// Client-supplied, display-only device metadata matching the backend's
/// `device_platform` / `installation_id` / `device_name` / `os_version` /
/// `app_version` fields. Nothing here requires extra permissions.
struct DeviceInfo {
    private let store: SessionStore
    var installationId: String { get throws { try store.durableInstallationId() } }
    let platform = "ios"
    let deviceName: String
    let osVersion: String
    let appVersion: String

    init(store: SessionStore) {
        self.store = store
        deviceName = Self.machineName()
        osVersion = UIDevice.current.systemVersion
        appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    /// Hardware model identifier (e.g. "iPhone15,2"), analogous to Android's
    /// `Build.MODEL`, without requiring any permission.
    private static func machineName() -> String {
        var size = 0
        guard sysctlbyname("hw.machine", nil, &size, nil, 0) == 0, size > 0 else {
            return UIDevice.current.model
        }
        var machine = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.machine", &machine, &size, nil, 0) == 0 else {
            return UIDevice.current.model
        }
        let identifier = String(cString: machine)
        return identifier.isEmpty ? UIDevice.current.model : identifier
    }
}
