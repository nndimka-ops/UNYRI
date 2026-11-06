# Build UNYRI for iOS 0.3.17

Use macOS, Xcode26.5/iOS SDK26.5, Python3, Bash and Xcode command-line tools.
The deployment target remains iOS16; guarded iOS26 APIs require the newer SDK.
This is a network-capable source recipe. Apple SDKs, credentials and dependency
caches are not included. Use a new build directory for each target.

## Checked-in package resolution

The project includes the exact public `Package.resolved` used by the qualified
0.3.16 builds and unchanged in the actual17 Device/Simulator source exports (SHA256 91216ad6c5a0e871ea89b6c46b0f2b4f45426a510b9e8c110ea6dfbfa39ba727).
It pins three public GitHub dependencies. It is one build auxiliary, listed
separately from the773 shipping-source inputs and original source939 manifest.
Dependency source/artifact downloads and Apple SDKs are still not bundled; this
does not turn the package into an offline or bit-identical reproduction claim.
Preserve the lockfile when using `-onlyUsePackageVersionsFromResolvedFile`.

## App and share extension

Restore the exact target-specific official Signal library, or use the source
build below. From the repository root, choose one:

```sh
bash ios/scripts/restore-signal-ffi.sh --target arm64-simulator
# In a separate source/build tree for an iPhone archive:
bash ios/scripts/restore-signal-ffi.sh --target arm64-device
```

The helper verifies the fixed official archive checksum and selected regular
member, and refuses to overwrite a conflicting library. The device and Simulator
arm64 libraries are different. See the helper and ios/README.md for bounds and
the Intel Simulator alternative. Then:

```sh
xcodebuild -resolvePackageDependencies   -project ios/UNYRI.xcodeproj -scheme UNYRI   -clonedSourcePackagesDirPath build/SourcePackages
xcodebuild -project ios/UNYRI.xcodeproj -scheme UNYRI   -configuration Debug -sdk iphonesimulator   -destination 'generic/platform=iOS Simulator'   -clonedSourcePackagesDirPath build/SourcePackages   -derivedDataPath build/DerivedData -jobs 1   "ARCHS=$(uname -m)" ONLY_ACTIVE_ARCH=YES   CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=- build
```

The exact package tags are declared in the project/local manifests. Public
wrapper source archives record the resolved tag commits and checksums. The
Simulator ad-hoc path above is normal Xcode signing, not a device-signing bypass.
Device distribution requires your own team, distinct app/share IDs, matching
App Group, Push Notifications capability and profiles. Configure the base/group
variables and the TWO target-specific profile variables from the project; do
not apply one global bundle identifier or provisioning profile to both targets.
New bundle IDs create a separate installation; no automatic old-keychain/history
migration is promised. Never commit credentials, P8/P12 files or profiles.

The selected ios/README.md is retained byte-for-byte from the product source.
Its old statement about unfinished push preparation predates this15 client
implementation. Client code alone does not configure a self-hosted APNs provider
or prove production delivery. Use this versioned guide for the release boundary.

## Signal

Included full source: `third_party/libsignal/libsignal-v0.86.5-source.tar.gz`,
commit b39e93f1a5e6531044dfcdf5876585cbcf08f884, SHA256
7956a7af562b5142f80933f42ff7cb2aaa0ed077382ce06580d5eb9bf6651814.
Verify the digest, extract to a new directory, and follow the included upstream
README, swift/README.md and swift/build_ffi.sh. The source pins
nightly-2025-09-24 and Cargo.lock. From the extracted root choose one:

```sh
CARGO_BUILD_TARGET=aarch64-apple-ios swift/build_ffi.sh --release
CARGO_BUILD_TARGET=aarch64-apple-ios-sim swift/build_ffi.sh --release
CARGO_BUILD_TARGET=x86_64-apple-ios swift/build_ffi.sh --release
```

Use `target/<target>/release/libsignal_ffi.a` only for that platform. Preserve the
included FFI headers/module map. Registry versions/checksums and Git-fork commits
are in the archived Cargo.lock and CARGO_SOURCE_REFERENCES.json. To collect source
dependencies into an independent offline workspace, use `cargo vendor --locked
--versioned-dirs vendor` with Cargo's documented config output; this packaging
task did not vendor or rebuild them. Do not infer source-to-official-prebuild
byte equality from the official prebuild checksum.

## Stasel WebRTC152.0.0

Included wrapper/build source: stasel-webrtc-1d04692697cb642bfebf6ad2dd99fe52649c3d6d.tar.gz.
The official release maps branch-heads/7977 to source
6f37672d358475cd17544121a12494da454d85fb at
https://webrtc.googlesource.com/src.
The exact LICENSE and DEPS files are bundled under third_party/webrtc.
After extracting the builder into a fresh directory on a suitably sized Mac:

```sh
BRANCH=6f37672d358475cd17544121a12494da454d85fb IOS=true   MACOS=false MAC_CATALYST=false DEBUG=false sh scripts/build.sh
```

The pinned script fetches depot_tools, WebRTC and DEPS, applies its GN arguments,
and produces `out/WebRTC.xcframework`. The release workflow selects Xcode26.5.
For manual retrieval use depot_tools `fetch --nohooks webrtc_ios`, checkout the
EXACT commit in src, and `gclient sync --with_branch_heads --with_tags`; never
substitute the moving branch head. Full source/dependencies may require many
gigabytes; they were not downloaded here. Keep all third_party source licenses
and the upstream license-generation tooling. The pinned builder's depot_tools
bootstrap is not a reproducible toolchain lock, so no identical-binary claim is
made. The official binary checksum is in DEPENDENCY_SOURCES.json.

## LiveKitWebRTC150.7871.04

Included builder: webrtc-build-717ec1dc5a94389d691e09cccaf3d7b3364fb4b7.tar.gz.
Its actual `build/VERSION` pins a545de45259b47dfac5f3635863e47cb601f1bb2
from https://github.com/webrtc-sdk/webrtc. Use the CURRENT build/ subtree and
top-level .github/workflows/build.yml; the legacy root webrtc_version.yaml is
not this release's version source. In the extracted root:

```sh
bash build/build.apple_prefixed.sh   a545de45259b47dfac5f3635863e47cb601f1bb2 release
```

This is the LiveKit-prefixed variant, not build.apple.sh or stripped output.
The builder applies its apple_prefix patch, synchronizes the pinned WebRTC DEPS,
uses build/apple/xcframework.sh and produces
`build/_package/apple_prefixed/LiveKitWebRTC.xcframework`. The release workflow
selects Xcode26.0. Its recipe includes codecs/options distinct from Stasel;
preserve the source patch, notices and DEPS licenses. It also bootstraps tools,
so the recipe is source-pinned but not an independently reproduced binary.

## RustLiveKitFFI0.13.1

Included full source: livekit-rust-sdks-7187f6a42ca6382b73c631b75a9d560253f5b90f.tar.gz.
The official binary release names this exact source commit and tag
livekit-ffi/v0.13.1. The archived rust-toolchain.toml pins Rust1.97.1; Cargo.lock,
.github/actions/uniffi-deps and .github/workflows/uniffi-swift.yml give the tools.
Install upstream prerequisites (including Protobuf tooling where requested),
cargo-make0.37 and Apple Rust targets. From `livekit-ffi`:

```sh
SPM_VERSION=0.13.1 cargo make --profile release swift-package
```

This selects `LK_PLATFORM=swift` and core-modules, not the crate's default
room-apis. It does not link a second embedded libwebrtc into RustLiveKitFFI.
The recipe pins cargo-swift c92be034d15129321fb08da0416df4b376930748;
outputs are in `livekit-ffi/packages/swift/LiveKitFFI`. The historical hosting
repository is livekit/livekit-ffi-xcframework; the pinned generated template now
uses livekit/livekit-uniffi-xcframework. The exact0.13.1 binary checksum is the
same; preserve the actual wrapper sources from the included wrapper archive.
The full workspace source-reference JSON lists versions/checksums/Git commits;
it is not a claim that every workspace crate is in the Swift core framework.
Use Cargo vendor --locked if collecting transitive source for offline use.

## Using rebuilt dependencies and notices

The app's included local LiveKit Swift source is the actual build input. Its
verified upstream source archive and precise content delta are also included.
To use a locally rebuilt XCFramework, make a deliberate fork of the corresponding
Swift binary package and change its binaryTarget to a local path, or package it
with a new URL/checksum. Keep target/module/product names distinct and correct;
do not silently replace an existing official archive while retaining its hash.
An independently rebuilt dependency requires renewed app build/testing.

The source checkouts retrieved by gclient/Cargo contain dependency LICENSE,
COPYING and NOTICE files. Follow the pinned upstream license-generation recipes;
retain generated target-specific notices with a distributed rebuild. No single
root WebRTC LICENSE_THIRD_PARTY file exists at these two exact commits (404 was
observed); this is not a license waiver. DEPS plus the retrieved third_party
license files are the source-of-truth paths. The included wrappers/builders and
Rust/Swift source archives preserve their own notices unchanged.

## Resources and backend

App resources are exact selected source bytes; normal builds need no image or
theme regeneration. design/ retains the approved icon source/exporter (Pillow
only if regenerating). A server can be selected in the app; UNYRI_API_BASE_URL
sets a public default. This iOS-only source packet does not include a server
deployment or push credentials. HTTPS validation remains enabled.
