# UNYRI — iOS 0.3.15 and self-hosted backend source

This source snapshot contains the app, share extension, local Swift packages,
resources, shared schemas/vectors and retained licenses. The selected production
inputs match the qualified 0.3.15 / build 15 source manifest exactly. The APNs and
PushKit client implementation and the accepted chat hotfix are included.

There are no signing keys, provider keys, profiles, account data, private QA
receipts, Git history, app/IPA or compiled framework/static-library distributions.
Images and the upstream source archives are source inputs. Some upstream source
archives include normal example/model assets and CI recipes; they are not app
distribution binaries. No CI workflow is activated at this repository root.

- [Build and dependency-source recipes](SOURCE_BUILD.md)
- [Exact versions, source commits and binary checksums](DEPENDENCY_SOURCES.json)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Local modifications](LOCAL_MODIFICATIONS.md)
- [Shipping inputs and explicit QA exclusions](SOURCE_SELECTION.json)
- [AGPL-3.0-only](LICENSE), [copyright](COPYRIGHT)
- [Original iOS-release public file hashes](PUBLIC_FILE_MANIFEST.json)
- [Backend source/build scope](server/README.md)
- [Свой сервер для семьи и друзей](docs/SELF_HOSTING.md)
- [Backend source binding](SERVER_SOURCE_BINDING.json)
- [Server additions file hashes](SERVER_PUBLIC_FILE_MANIFEST.json)

The 773 iOS shipping inputs remain exact. A separately bound backend source
addition is now included; provider credentials remain operator-private. See
[the self-hosting guide](docs/SELF_HOSTING.md). The existing ios-0.3.15 release
asset remains the original iOS-only source package; server additions need their
own reviewed publication.
Source publication does not prove physical push delivery, large-file background
grants or a complete physical-device test matrix. The exact signed15 source build, exported IPA and compiled framework UUIDs
are bound in [SIGNED_RELEASE_BINDING.json](SIGNED_RELEASE_BINDING.json). Source
publication and physical delivery qualification remain separate owner actions.

WebRTC source versions and recipes are pinned, rather than inferred from wrapper
names. WebRTC DEPS trees and Cargo dependency sources require network retrieval;
they are not hidden private build inputs. The package is not an offline build or
a claim of byte-for-byte recreation of upstream prebuilt frameworks. Third-party
licenses/notices remain in force; the root license does not replace them.
