# Storage components

The published storage images contain these unmodified upstream components:

- MinIO server: `RELEASE.2025-10-15T17-29-55Z`, GNU Affero General Public License v3.0 (AGPL-3.0), https://github.com/minio/minio/tree/RELEASE.2025-10-15T17-29-55Z
- MinIO Client (mc): `RELEASE.2025-08-13T08-35-41Z`, GNU Affero General Public License v3.0 (AGPL-3.0), https://github.com/minio/mc/tree/RELEASE.2025-08-13T08-35-41Z

Each storage image carries the upstream LICENSE and CREDITS in `/licenses`.
The UNYRI repository's `scripts/selfhost/storage.Dockerfile` records the build
recipe. This runtime bundle contains no compiler or source-build recipe.
