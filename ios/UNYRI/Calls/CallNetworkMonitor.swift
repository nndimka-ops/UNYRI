import Combine
import Foundation
import Network

/// The network path as calls see it (`NWPathMonitor`): which ladder row the
/// media gets and when Wi-Fi gives way to cellular (or back). One monitor for
/// the app's lifetime, started with the first account's call controller, so
/// the path is already known when a call starts. Publishes on the main actor,
/// only real changes. Not observed by any view.
@MainActor
final class CallNetworkMonitor {
    static let shared = CallNetworkMonitor()

    private(set) var current: CallNetworkPath = .unknown
    let paths = PassthroughSubject<CallNetworkPath, Never>()
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let value = Self.snapshot(path)
            Task { @MainActor [weak self] in
                guard let self, value != self.current else { return }
                self.current = value
                self.paths.send(value)
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.unyri.calls.network", qos: .utility))
    }

    nonisolated private static func snapshot(_ path: NWPath) -> CallNetworkPath {
        let satisfied = path.status == .satisfied
        let interface: CallNetworkInterface?
        if !satisfied { interface = nil }
        else if path.usesInterfaceType(.wifi) { interface = .wifi }
        else if path.usesInterfaceType(.wiredEthernet) { interface = .wired }
        else if path.usesInterfaceType(.cellular) { interface = .cellular }
        else { interface = .other }
        return CallNetworkPath(satisfied: satisfied, interface: interface,
                               isExpensive: path.isExpensive, isConstrained: path.isConstrained)
    }
}
