# UNYRI backend — source of a31

This is the first-party source corresponding to backend image
`sha256:a31d0ace751ac7882380202d768430cb5eb8b65d41bc79dbdcb75a484728bce4`.
That is a recorded local runtime imageID, not a public registry download address.
Build your own image from the repository root:

```sh
docker build --pull -f server/Dockerfile -t unyri-server:a31-source .
```

The exact Cargo.lock hash is
`ae3b754518e820de04e7a690cdcf0abcc8662d6eb755a2dca1b7ff4f864eddf2`.
The normal release builds `messenger-server` and `coco-admin` with `--locked`.
The release/profile, isolated migration/router/provider tests and bounded lane
tests were qualified for this source. This package does not claim a fresh
self-host installation, physical push delivery or a complete device matrix.

See the [Russian beginner/operator guide](../docs/SELF_HOSTING.md) and
[fresh-install configuration examples](../docs/self-hosting/compose.example.yaml).
The examples require actual TLS/DNS, generated secrets, reviewed upstream image
pins, S3 principal provisioning and operator verification. No real env, account,
provider key, TLS/private signing material, database, media or QA history is included.

[SERVER_SOURCE_BINDING.json](../SERVER_SOURCE_BINDING.json) records all 249
selected deployed files. All 248 other files and every Docker/Cargo build input
are byte-exact. Only this publication's development `.env.example` replaces
four private-LAN example addresses by loopback; it is not production configuration.
The 32 shared files and root license/copyright already existed byte-exact in the
iOS publication. The 773 iOS shipping inputs remain unchanged.

This exact version uses `COCO_ENV=production`, the 32-byte hex `COCO_ADMIN_KEY`,
and `/usr/local/bin/coco-admin`. Do not assume unimplemented rename aliases.
Persisted/wire/crypto identifiers and applied migrations retain compatibility
spellings. A server's public name/contact/rules are editable through its existing
owner policy UI; changing seed SQL is not a rename procedure.

Native push is optional: FCM and typed APNs alert/VoIP require credentials
authorized for the installed client's project/bundle/environment/topics.
There is no universal public push gateway in this source. A provider's accepted
HTTP request is distinct from a physical OS notification or incoming call.

## Source, license and updates

First-party code uses the root [AGPL-3.0-only](../LICENSE) and
[copyright](../COPYRIGHT). Third-party components retain their own licenses.
[DEPENDENCY_SOURCE_LOCK.json](DEPENDENCY_SOURCE_LOCK.json) lists exact Cargo
registry versions/checksums and sources; Cargo retrieves them over the network.
Dependency source trees/license texts are not vendored here, and this inventory
is not a complete binary-distribution notices or legal-clearance claim.
The existing iOS notices/source recipes remain separately applicable to iOS.
The retained shared SVGs carry the unmodified
[Lucide ISC/Feather MIT notices](../third_party/lucide/LICENSE).

No production-only private build input is required for this Rust source build.
Do not commit credentials when adding a deployment, and do not enable a root CI
workflow or signing job merely by copying this source.

Back up data/objects/configuration before updates. Normal startup applies SQLx
migrations. After 0062, use schema-compatible code; old `ON CONFLICT(device_id)`
backends are incompatible with typed `(device_id,provider)` slots. Disabling
APNs retains the new code/schema and all rows; never delete slots to force a
binary downgrade.

Windows admin-console source/installer is outside this packet. Use the existing
web administration UI and the local operator CLI; no public Windows download
is asserted. Project contact: `bbdimka@gmail.com`.
