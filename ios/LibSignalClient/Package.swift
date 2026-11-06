// swift-tools-version:5.9
//
// Copyright 2020-2021 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//
// Local Swift Package wrapper around the official signalapp/libsignal Swift
// sources (pinned to tag v0.86.5). This mirrors upstream `swift/Package.swift`
// but omits the DocC plugin dependency and the StrictConcurrency experiment so
// the module compiles in Swift 5 language mode, matching the official
// `LibSignalClient.podspec` (`s.swift_version = '5'`).
//
// The prebuilt Rust FFI (`libsignal_ffi.a`) is downloaded, checksum-verified,
// and extracted in CI (see `.github/workflows/ios-build.yml`); the `SignalFfi`
// system library target's module map declares `link "signal_ffi"`, so the app
// links it from `LIBRARY_SEARCH_PATHS`.
//

import PackageDescription

let package = Package(
    name: "LibSignalClient",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(name: "LibSignalClient", targets: ["LibSignalClient"])
    ],
    targets: [
        .systemLibrary(name: "SignalFfi"),
        .target(
            name: "LibSignalClient",
            dependencies: ["SignalFfi"],
            linkerSettings: [.linkedLibrary("z")]
        ),
    ]
)
