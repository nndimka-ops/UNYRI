# UNYRI — iOS 0.3.16, self-hosted server and Windows admin alpha

A space for family and friends, on a server you choose and control. UNYRI brings
encrypted conversations and self-hosting together, with a Windows console for
server administration.

UNYRI is alpha software. Choose a server that you operate or trust, and use the
guides below to understand the setup and current package scope.

## Start here

- **Family and friends server:** [Свой сервер для семьи и друзей](docs/SELF_HOSTING.md).
  Start with its prerequisites and backup/storage plan. It covers HTTPS/TLS,
  Docker, public ports/CGNAT and TURN/SFU requirements. Use your own generated
  secrets and provider credentials; no shared private push or signing keys are
  supplied. A self-hosted deployment is not a promise of universal push setup.
- **iOS 0.3.16 / build 16:** [build recipe](SOURCE_BUILD.md),
  [release source binding](RELEASE_SOURCE_BINDING.json) and
  [shipping selection](SOURCE_SELECTION.json). All 773 selected production inputs
  match the qualified source. [Bundle metadata](release-metadata/ios-0.3.16/README.md)
  accounts for the original 829-file source ZIP without replacing the operator guide.
- **Windows Admin Console 0.3.16 alpha:** [source and package scope](windows-admin/README.md).
  It is an unsigned .NET Framework 4.8 remote-admin alpha; optional local-stack/AI
  controls require additional setup and are not qualified by the portable package.

## Alpha, trust and testing

Message content encryption does not hide every IP address, connection or
account-routing metadata item from networks and services. This repository makes
the selected source and build instructions available; it is not a claim that
every physical push, large-file background or device combination has passed testing.

## Source and compatibility

- [Server build scope](server/README.md), [deployed-source binding](SERVER_SOURCE_BINDING.json)
  and [server publication file hashes](SERVER_PUBLIC_FILE_MANIFEST.json).
- [Dependency sources](DEPENDENCY_SOURCES.json), [third-party notices](THIRD_PARTY_NOTICES.md),
  [local modifications](LOCAL_MODIFICATIONS.md), [AGPL-3.0-only](LICENSE) and [copyright](COPYRIGHT).
- [Original iOS 0.3.15 bundle manifest](PUBLIC_FILE_MANIFEST.json) and
  [signed15 binding](SIGNED_RELEASE_BINDING.json) are historical records. Existing
  release tags/assets retain their original source versions.

The server/operator guide and compatibility names are preserved. Third-party
licenses remain in force. Pinned WebRTC DEPS/Cargo sources can require network
retrieval; this source bundle does not promise a fully offline or byte-identical
rebuild of upstream prebuilt frameworks.

No credentials, provider/signing keys, profiles, account data, private QA receipts
or app/IPA/framework binaries are included in this source tree. The Windows
portable package belongs in release assets. No root CI workflow is activated.
