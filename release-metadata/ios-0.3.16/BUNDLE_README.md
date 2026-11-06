# UNYRI for iOS — 0.3.16 source

This source snapshot contains the app, share extension, local Swift packages,
resources, shared schemas/vectors and retained licenses. The selected production
inputs match the qualified 0.3.16 / build 16 source manifest exactly. The APNs and
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
- [Public file hashes](PUBLIC_FILE_MANIFEST.json)

This is an iOS client source package. The hosted backend and its provider
credentials are separate; see [the self-hosting scope](docs/SELF_HOSTING.md).
Source publication does not prove physical push delivery, large-file background
grants or a complete physical-device test matrix. The prepared 0.3.16 source and unsigned device SDK framework outputs are bound
in [RELEASE_SOURCE_BINDING.json](RELEASE_SOURCE_BINDING.json). The final source is matched to an actual locally signed16 archive and
exported IPA. Apple16 validation/upload, owner publication and physical delivery
qualification remain separate actions; this binding performs none of them.

WebRTC source versions and recipes are pinned, rather than inferred from wrapper
names. WebRTC DEPS trees and Cargo dependency sources require network retrieval;
they are not hidden private build inputs. The package is not an offline build or
a claim of byte-for-byte recreation of upstream prebuilt frameworks. Third-party
licenses/notices remain in force; the root license does not replace them.
