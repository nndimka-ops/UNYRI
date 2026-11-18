import Foundation
#if canImport(Darwin)
import CoreFoundation
#endif

enum ShareSignal {
    static var name: String { UnyriShareStore.group + ".share.job" }
    static func post() {
        #if canImport(Darwin)
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(rawValue: name as CFString), nil, nil, true)
        #endif
    }
}
