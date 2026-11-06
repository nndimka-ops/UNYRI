// swift-tools-version:6.1
// COCO: pinned LiveKit Swift 2.19.0 with pre-attachment frame encryption.
import PackageDescription

let package = Package(
    name: "LiveKit",
    platforms: [.iOS(.v13), .macOS(.v10_15), .macCatalyst(.v14), .tvOS(.v17)],
    products: [.library(name: "LiveKit", targets: ["LiveKit"])],
    dependencies: [
        .package(url: "https://github.com/livekit/webrtc-xcframework.git", exact: "150.7871.04"),
        .package(url: "https://github.com/livekit/livekit-ffi-xcframework.git", exact: "0.13.1"),
    ],
    targets: [
        .target(name: "CLiveKitProto", exclude: ["LICENSE-nanopb.txt", "module.modulemap"],
            publicHeadersPath: "include", cSettings: [.headerSearchPath("include")]),
        .target(name: "LiveKitNanopb", dependencies: ["CLiveKitProto"]),
        .target(name: "LKObjCHelpers", publicHeadersPath: "include"),
        .target(name: "LiveKit", dependencies: [
            .product(name: "LiveKitWebRTC", package: "webrtc-xcframework"),
            .product(name: "LiveKitFFI", package: "livekit-ffi-xcframework"),
            "LiveKitNanopb", "CLiveKitProto", "LKObjCHelpers",
        ], exclude: ["Broadcast/NOTICE"], resources: [.process("PrivacyInfo.xcprivacy")]),
    ],
    swiftLanguageModes: [.v5, .v6]
)
