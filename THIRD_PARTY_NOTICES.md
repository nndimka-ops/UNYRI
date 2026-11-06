# Third-party source and notices

## Signal v0.86.5

Signal Messenger, LLC; AGPL-3.0-only. Preserve `ios/LibSignalClient/LICENSE`.
The full official source archive, exact commit/hash, wrapper comparison,
Cargo.lock source references and upstream iOS acknowledgment-generation inputs
are under `third_party/libsignal`. The 167 upstream acknowledgment entries are
a reference, not a measured final linked-crate count. Source and build commands
are in SOURCE_BUILD.md.

## LiveKit Swift 2.19.0 and Rust FFI 0.13.1

Preserve the Apache-2.0 LICENSE/NOTICE in `ios/ConferenceLiveKit`, the nanopb
license and broadcast NOTICE even when excluded from a compiled target. Exact
official Swift and Rust source archives, wrapper archives, their source hashes
and retained upstream notices are under `third_party/upstream-source` and
`third_party/upstream-notices`. The Rust Swift package uses core-modules; its
default room-apis/libwebrtc surface is not the selected Swift framework.

## Two distinct WebRTC distributions

The app uses Stasel WebRTC152.0.0 and LiveKitWebRTC150.7871.04. They have different
source commits and build configurations; neither is substituted for the other.
Their BSD-style root licenses and exact DEPS source maps are under
`third_party/webrtc`, with wrapper/builder notices retained separately. Follow
each pinned source tree's third_party license files and upstream license
generation scripts when rebuilding; a wrapper LICENSE alone is not a complete
notice inventory for all enabled codecs/dependencies. Source retrieval, patches
and target-specific recipes are documented in SOURCE_BUILD.md.

## Icons and Unicode

Retain `third_party/lucide/LICENSE`, its source note, `third_party/unicode16/LICENSE`
and its provenance record. Runtime asset notices remain in the app catalog.
The approved UNYRI artwork is a first-party source asset. Third-party attribution
is not renamed.

DEPENDENCY_SOURCES.json identifies the official source commits and binary
checksums; third_party/DEVICE_FRAMEWORK_SOURCE_BINDING.json ties the actual unsigned device
frameworks to the pinned cache bytes. Code-signing changes binary hashes, so a
final signed archive comparison uses its own manifest and UUIDs. This index
does not grant new licenses or assert a blanket Apple distribution exception.


## Android real-time media additions

[Exact bounded Android RTC source/notices map](third_party/android-rtc/README.md)
records Stream1.3.10, LiveKitAndroid2.28.2 and its declared prefixedWebRTC144.7559.14.
Identified Apache2, MIT, native BSD3 and patent notices are retained separately.
This is not a complete freshly resolved APK dependency inventory or a proof
of rebuilding Stream native C++ to the published binary bytes.
