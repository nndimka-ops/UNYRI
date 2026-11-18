# UNYRI for iOS

UNYRI is a native Swift/SwiftUI client for the UNYRI server. The display name is
UNYRI; its technical Xcode project is `UNYRI.xcodeproj`, and its scheme and executable are `UNYRI`.

The source includes authentication and profiles, encrypted chats and attachments,
calls and rooms, history backup/transfer, and a direct-file transfer manager. The
presence of a feature in source does not qualify every device or background case.
The iOS 26 continued-processing adapter is system-controlled: denial, expiration
or cancellation can require the user to return and explicitly continue. It is not
an unlimited background-transfer service. Physical-device background grants and
cross-platform large-file recovery need their own release tests.

## Toolchain and dependencies

- A Mac with **Xcode 26.5 / iOS SDK 26.5**, the toolchain used for the current
  Simulator build. Select that Xcode in Settings → Locations → Command Line Tools.
  Older Xcode 16 instructions do not cover the current iOS 26 APIs.
- The app deployment target remains **iOS 16**. Availability guards for newer APIs
  do not make an older SDK able to compile their symbols.
- Python 3, Bash, Xcode command-line tools, and curl 8.4+ for the bounded download
  helper. An already-downloaded, checksum-verified archive can be supplied instead.
- Network access for the first Swift package resolution and Signal archive fetch.
  A clean checkout is not an offline SDK/dependency cache.

The local `LibSignalClient` wrapper pins Signal **0.86.5**. The project also uses
the local `ConferenceLiveKit` package (Swift tools 6.1), its exact WebRTC/FFI
dependencies, and the project's exact Stasel WebRTC dependency. Keep the package
versions in the manifests; do not replace them with arbitrary latest versions.

## Restore Signal's target-specific static library

The large generated Rust static library is not included in the source checkout.
From the repository root, choose **one** target for this working copy:

```bash
# Apple Silicon Mac, iOS Simulator:
bash ios/scripts/restore-signal-ffi.sh --target arm64-simulator

# Intel Mac, iOS Simulator (alternative):
bash ios/scripts/restore-signal-ffi.sh --target x86_64-simulator

# Physical iPhone / iOS device archive (alternative):
bash ios/scripts/restore-signal-ffi.sh --target arm64-device
```

These are alternatives, not three successive commands. The current project links
the fixed path `ios/libsignal-ffi/libsignal_ffi.a`; use a **separate source checkout
for a different SDK target**. The helper refuses to replace a different existing
library. An arm64 Simulator library is not an arm64 iPhone library.

The helper downloads only Signal's versioned HTTPS archive, with a 1 GiB input
limit and a 300-second transfer limit, verifies this SHA256, and reads exactly the
requested regular archive member:

```text
URL: https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.86.5.tar.gz
SHA256: a3df979aa39b307ac4ef0f3fd59536daab5d6b3f8257c7aefe5f0470625f2582

arm64-simulator: target/aarch64-apple-ios-sim/release/libsignal_ffi.a
x86_64-simulator: target/x86_64-apple-ios/release/libsignal_ffi.a
arm64-device:    target/aarch64-apple-ios/release/libsignal_ffi.a
```

The checksum is pinned from the [official release checksum](https://github.com/signalapp/libsignal/releases/download/v0.86.5/libsignal-client-ios-build-v0.86.5.tar.gz.sha256).
For an existing archive, add `--archive /absolute/path/to/the/archive.tar.gz` to
avoid a download. No alternate URL or checksum override is accepted. The helper
checks the selected architecture and writes a provenance JSON beside the library.
Exact archive-member provenance distinguishes the two arm64 platforms; an
architecture check alone cannot. Hash verification is not an independent
source-to-binary reproduction or a physical-device qualification.

If interrupted, the helper removes its own temporary download/extraction files.
It never deletes an existing library or changes project/signing settings. A valid
new library can remain if receipt publication fails; rerunning verifies the same
official member before completing the receipt. A conflicting receipt is preserved
for inspection rather than overwritten.

## Build and run in Simulator

Run these commands from the repository root after restoring the correct
Simulator library:

```bash
xcodebuild -version
xcrun --sdk iphonesimulator --show-sdk-version

# This first resolution can download the pinned Swift package dependencies.
xcodebuild -resolvePackageDependencies \
  -project ios/UNYRI.xcodeproj -scheme UNYRI \
  -clonedSourcePackagesDirPath build/SourcePackages

xcodebuild -project ios/UNYRI.xcodeproj -scheme UNYRI \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -clonedSourcePackagesDirPath build/SourcePackages \
  -derivedDataPath build/DerivedData -jobs 1 \
  "ARCHS=$(uname -m)" ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=- \
  build
```

The app is `build/DerivedData/Build/Products/Debug-iphonesimulator/UNYRI.app`.
Open it in the selected Simulator through Xcode, or choose an existing compatible
device from `xcrun simctl list devices available`, then use `simctl install` and
`simctl launch` with that device's UUID and bundle ID `com.messenger.unyri.X7G7M9PFJ3`.

The command uses Xcode's normal local Simulator signing path; no Apple account is
needed for that local signature. Do not manually re-sign the app, strip its
entitlements, or treat an unsigned compile as proof that Keychain/App Groups and
native runtime behavior work. The local Simulator build is not an IPA for a
physical iPhone, TestFlight, or App Store distribution.

## Server address

The bundled default in both app build configurations is the public HTTPS origin:

```text
https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud
```

For your own server, use the server-address screen or build with
`UNYRI_API_BASE_URL=https://your-server.example`. An explicitly saved server
selection takes precedence over the bundled default. The address must be an
origin without credentials, a path, query, or fragment. HTTPS certificates must
validate normally. Release rejects plain HTTP; Debug/alpha permits only the
limited local-network HTTP forms enforced by `AppConfig`.

Changing a server address does not merge accounts, message databases, or Signal
identities between servers. Keep the existing server/workspace continuity logic;
do not fix a connection error by disabling TLS validation or clearing Keychain.
See [self-hosting](../docs/SELF_HOSTING.md) for the server setup and its limits.

## Physical iPhone and distribution

Restore the **device** Signal member in a separate working copy. Use Xcode's
Signing & Capabilities settings with valid app and share-extension provisioning,
the correct development team, and the App Group entitlement used by both targets.
Signing, provisioning, installation and a device launch must be checked separately
from compilation.

The current registered UNYRI defaults are `com.messenger.unyri.X7G7M9PFJ3`,
its `.share` extension and `group.com.messenger.unyri.X7G7M9PFJ3.share`. Both
targets inherit `UNYRI_APP_BUNDLE_IDENTIFIER` / `UNYRI_APP_GROUP_IDENTIFIER`;
the share target appends `.share` to the base bundle. An independent developer
must register their own identifiers, team, capabilities and profiles.

Legacy `com.messenger.coco` installations belong to a different app namespace.
The new app does not automatically inherit their Keychain, App Group, files or
history. Preserve the old installed app and use the supported history backup /
transfer flow; changing a display name alone never migrates those stores.
Legacy wire/cryptographic formats and stored preference keys are deliberately
retained as compatibility data, not as the public product name.

For a manually signed archive, use distinct target settings
`UNYRI_MAIN_PROVISIONING_PROFILE_SPECIFIER` and
`UNYRI_SHARE_PROVISIONING_PROFILE_SPECIFIER`, together with the correct team
and signing style. Do not globally override `PRODUCT_BUNDLE_IDENTIFIER` or
`PROVISIONING_PROFILE_SPECIFIER`: that can give the extension the main app's
identity/profile. Profiles and private signing keys remain outside source control.

No Apple certificate, private key, provisioning profile, account password, or
server secret belongs in this repository. CI/unsigned artifacts are compile aids;
they do not establish paid signing, push delivery, or App Store readiness.

## Push notifications

This iOS source currently has foreground realtime/CallKit integration, not a
finished APNs/PushKit provider/client path. A paid Apple membership alone does not
enable background incoming calls or messages. Do not promise offline local-network
wake-up on iOS from this build.

The iOS build does **not** need Firebase configuration or a `GoogleService-Info.plist`.
Optional Android FCM client configuration and server-side provider credentials are
separate self-hosting concerns; never copy a Firebase service-account private key
into either mobile app. Universal push for independent servers remains separate
work, not a prerequisite that this helper quietly supplies.

## Signal source and notices

The wrapper is based on official [libsignal v0.86.5, commit b39e93f1a5e6531044dfcdf5876585cbcf08f884](https://github.com/signalapp/libsignal/tree/b39e93f1a5e6531044dfcdf5876585cbcf08f884).
Retain [its license](LibSignalClient/LICENSE), the original notices, the local
`Package.swift` modifications, and release-matched third-party notices/source
information. Signal's AGPL-3.0-only license and other dependencies' terms remain
applicable; no Apple distribution exception or complete linked-notice inventory
is asserted here.

For an independent source build, follow the pinned upstream
[`swift/README.md`](https://github.com/signalapp/libsignal/blob/b39e93f1a5e6531044dfcdf5876585cbcf08f884/swift/README.md)
and `swift/build_ffi.sh` with the appropriate `CARGO_BUILD_TARGET`. That recipe has
its own Rust toolchain, Cargo dependencies and build prerequisites; the binary
restore helper does not install them or promise an offline/bit-for-bit rebuild.
