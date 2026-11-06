# Android 0.3.17 source addition — proposed

This is an alpha client for a server you operate or trust. The Android source
is a new addition to the existing iOS/server/Windows repository. It does not
replace the installed app or promise data migration for a differently signed APK.

Use JDK 17, Android SDK platform 36 and the checked-in Gradle 8.11.1 wrapper.
The minimum Android API is 26. Set ANDROID_HOME or create your own untracked
android/local.properties with sdk.dir. Dependency retrieval requires network
access to the repositories in android/settings.gradle.kts. From android/:

```sh
./gradlew --no-daemon --max-workers=1 :app:assembleDebug
```

This builds with your local debug certificate. It cannot update an official
installation signed with a different certificate. Do not include signing keys,
local.properties or generated outputs in source publication. Release signing
and distribution are separate steps; no publisher key is supplied.

Normal Debug and Release defaults use the shared public HTTPS origin; local
LAN mode is an explicit alphaLan option. Preserve the shared/ files next to
android/. Do not rewrite existing protocol, database or cryptographic names.

The publisher's Firebase configuration is excluded. Gradle applies the Google
Services plugin only when android/app/google-services.json exists. A build
without it leaves FCM inactive. To enable your own Firebase project, supply
your own configuration locally and configure your trusted backend accordingly.
The source-only no-Firebase recipe has not been newly compiled by this plan.

Dependencies, including the six native libraries seen in the qualified arm64
APK baseline, are retrieved through Maven/Gradle. No APK-extracted JNI files,
Gradle cache or prebuilt app is copied into this source addition. The existing
iOS WebRTC provenance is not evidence for Android's different Stream WebRTC
1.3.10 or LiveKit Android 2.28.2 artifacts. Record Android dependency sources,
licenses and the actual built APK binding separately. No offline or identical
binary reproduction is claimed.

The proposed public selection excludes the 331 private test/instrumentation
files. It contains the full 801 production/build inputs, including ten shared
files already present in the repository. It does not publish private QA receipts
or assert that omitted tests ran in this public tree.

Optional public checks, from the repository root:

```sh
python scripts/check_source_manifest.py release-metadata/android-0.3.17/PRODUCT_INPUTS.json
python scripts/check_android_core_localization.py
python scripts/check_android_package.py path/to/your-arm64.apk --abi arm64-v8a
```

The first checks exact source hashes; the second checks the recorded RU/UK
localization debt; the last checks ZIP CRC and native ABI coverage only.
They do not certify signing compatibility, physical performance or push delivery.
The pinned icon exporter in android/README.md is optional: checked-in PNGs are
already build inputs. Its approved master and linked approval file are included.

[Android RTC notices and pinned source links](third_party/android-rtc/README.md)
cover the two pinned media dependencies and the declared native prefixed AAR;
read the precise native-source and transitive-resolution limits there.
