# Android real-time media dependencies

This additive map covers the two Android media dependencies pinned by UNYRI and the native WebRTC artifact declared by LiveKit. It is not the complete resolved APK dependency graph. No binaries are included here. The application keeps its AGPL license; third-party components retain their own licenses.

| Maven coordinate | Source | License scope |
|---|---|---|
| io.getstream:stream-webrtc-android:1.3.10 | [release source](https://github.com/GetStream/webrtc-android/tree/5d388e533d87f857e7de4a729630200cf9766369) | Apache-2.0 wrapper; upstream WebRTC is BSD-3-Clause with the included patent grant. The upstream README also identifies Sora Apache-2.0 and webrtc-sdk BSD patches. |
| io.livekit:livekit-android:2.28.2 | [release source](https://github.com/livekit/client-sdk-android/tree/855100f1cef51a8e857f7c02cd1a2c27a43d9746) | Apache-2.0. Its AAR contains no JNI; its POM/module declares android-prefixed below. |
| io.github.webrtc-sdk:android-prefixed:144.7559.14 | [packaging release](https://github.com/webrtc-sdk/android/tree/v144.7559.14), [WebRTC commit named by release](https://github.com/webrtc-sdk/webrtc/tree/df1011beabae993c555f7c11a7a4b8e8fa62480d) | Maven POM names BSD-3-Clause; packaging repository LICENSE is MIT; native WebRTC LICENSE is BSD-3-Clause. Both notices and the native patent grant are retained separately. |

Full notices are in `licenses/`; exact cached AAR and JNI SHA-256 values, primary URLs and POM-declared dependencies are in `DEPENDENCIES.json`. The two native libraries are distinct: Stream supplies libjingle_peerconnection_so.so; LiveKit uses liblkjingle_peerconnection_so.so through the prefixed artifact. Neither cached WebRTC AAR contains a LICENSE/NOTICE file. These added files preserve the identified upstream notices instead.

For source builds, resolve the exact Maven coordinates using the existing Gradle project. LiveKit's source tag is fixed above. The Stream wrapper build packages its committed native libraries; its generic native-build README does not pin the exact native C++ revision/toolchain for this AAR. A Git blob match, where recorded, identifies released binary bytes, not a native source-rebuild proof. Do not describe this map as a complete native corresponding-source bundle or bit-identical reproduction. Do not reuse the iOS WebRTC notices as Android artifact provenance.

The POM-declared dependency list is an input, not a fresh Gradle resolution: version alignment may select other transitive versions. Before distributing an APK, bind its actual resolved dependency/JNI inventory and preserve all component notices; JNI-source rebuild completeness remains explicitly separate. First-party source publication does not imply that an APK has been qualified or published.
