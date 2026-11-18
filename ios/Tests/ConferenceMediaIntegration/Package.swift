// swift-tools-version:6.1
import PackageDescription
let package = Package(name: "ConferenceMediaProbe", platforms: [.macOS(.v13)],
    dependencies: [.package(path: "../../ConferenceLiveKit")],
    targets: [.executableTarget(name: "ConferenceMediaProbe", dependencies: [
        .product(name: "LiveKit", package: "ConferenceLiveKit"),
    ])])
