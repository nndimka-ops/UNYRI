# UNYRI server

The open-source server of **UNYRI**, an end-to-end encrypted messenger. Private messages, files and calls are encrypted on the phones; the server stores and relays ciphertext, accounts and delivery metadata.

This repository contains the Rust server (`server/`), the self-hosting installer for Windows and Linux (`scripts/selfhost/`, `docker-compose.selfhost.yml`) and the release workflow that publishes the server images and the self-hosting bundles.

## Run your own server

- Guide in Russian: [docs/SELF_HOSTING.md](docs/SELF_HOSTING.md)
- Guide in English: [docs/SELF_HOSTING_EN.md](docs/SELF_HOSTING_EN.md)

Each release (`server-vX.Y.Z`) ships ready-made images (`ghcr.io/<owner>/unyri-server`, `unyri-minio`, `unyri-mc`) and checksummed bundles, so a server needs Docker but no compiler.

## Build and test

- Server: `cargo build --release --manifest-path server/Cargo.toml`; tests are described in [server/tests/README.md](server/tests/README.md).
- Image: `docker build -f server/Dockerfile .`
- Release packaging checks: `python3 scripts/check_selfhost_release.py`

## License

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE). If you run a modified server for others, the AGPL requires you to offer them its source.
