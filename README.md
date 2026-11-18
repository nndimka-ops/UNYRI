English · [Русский](README.ru.md) · [Українська](README.uk.md)

# UNYRI

**UNYRI** is an end-to-end encrypted messenger for iPhone and Android with a server you can run yourself. Messages, files and calls are encrypted on the phones (Signal protocol, via libsignal); the server stores and relays ciphertext, accounts and delivery metadata.

## Why UNYRI

Your people. Your server. Your rules. UNYRI keeps the connection in your hands: run a server at home, at a friend's place or on a rented machine in any country, and talk to the people you care about through it. No single company decides who may talk to whom or can switch everyone off at once: stay in touch without depending on big platforms.

## What it can do

- Private chats with end-to-end encryption, delivery and read marks, replies, forwarding, reactions and saved messages.
- Photos, videos, files, voice messages and round video notes; large files can also go directly from phone to phone.
- Audio and video calls, and group rooms.
- A permanent public number and an optional @username, a QR code for your profile, contacts and privacy settings.
- Several accounts in one app, including accounts on different servers.
- History backup and transfer to a new phone, a recovery key, an app lock.
- Your own server: an installer for Windows and Linux (Docker) and an owner console in the app.

## Repository layout

| Path | What it is |
| --- | --- |
| `server/` | Rust server, its admin console, database migrations and tests |
| `ios/` | iPhone app (Swift/SwiftUI), share extension and notification service |
| `android/` | Android app (Kotlin, Jetpack Compose) |
| `shared/` | Fixtures and test vectors shared by the apps and the server |
| `scripts/selfhost/`, `docker-compose.selfhost.yml` | Installer and compose file for your own server |
| `docs/` | Self-hosting guide |

## Get the apps

- **Android:** signed APKs are attached to the `android-vX.Y.Z` releases on this page.
- **iPhone:** through Apple (App Store / TestFlight). This repository contains the source code only; it does not publish IPA files.
- **Server:** each `server-vX.Y.Z` release ships ready-made images (`ghcr.io/nndimka-ops/unyri-server`, `unyri-minio`, `unyri-mc`) and checksummed bundles, so a server needs Docker but no compiler.

## Run your own server

- [Guide in English](docs/SELF_HOSTING_EN.md)
- [Инструкция на русском](docs/SELF_HOSTING.md)
- [Інструкція українською](docs/SELF_HOSTING_UK.md)

## Build from source

- **Server:** `cargo build --release --manifest-path server/Cargo.toml`; tests: [server/tests/README.md](server/tests/README.md); image: `docker build -f server/Dockerfile .`; release packaging check: `python3 scripts/check_selfhost_release.py`.
- **Android:** JDK 17 and the Android SDK, then `cd android && ./gradlew assembleDebug` (details: [android/README.md](android/README.md)). Push notifications need your own Firebase project: put its `google-services.json` into `android/app/`; without it the app builds and works, push stays off.
- **iPhone:** a Mac with Xcode 26.5; open `ios/UNYRI.xcodeproj` (dependencies and the Signal library download: [ios/README.md](ios/README.md)). Signing with your own Apple team is required to run on a device.

## Security

Please do not report vulnerabilities in public issues. Use GitHub's private vulnerability reporting (the **Security** tab → **Report a vulnerability**).

## A hobby project

UNYRI is a small non-commercial hobby project developed in spare time. It is free and has no ads. The apps and the server are provided as is, without any warranty: errors, outages and data loss are possible. Use it at your own risk, keep important things elsewhere and do not rely on it for emergency communication or anything where a failure could cause harm.

## Responsibility

- The software is provided "as is", without any warranty, as stated in the AGPL-3.0 (sections 15 and 16).
- Servers run by other people are run by their operators. The UNYRI developer does not administer them and is not responsible for their operation, availability, security, content, moderation, or how they store and process data. Whoever runs a server is its operator and is responsible for it, its users and their data, and for following the law where it runs.
- Apps and servers built or modified by other people from this source code are not official. The UNYRI developer does not publish or check them and is not responsible for them. Official apps come only from the app stores and from the releases of this repository.
- If you distribute a modified version, give it your own name and icon and say clearly that it is not the official UNYRI app. The UNYRI name and logo identify the official apps; this license does not grant rights to use them (AGPL-3.0 section 7(e)).
- These limitations apply to the extent permitted by applicable law.

## License

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE). If you run a modified server for other people, the AGPL requires you to offer them its source. Bundled third-party components keep their own licenses (for example the LiveKit Swift SDK under Apache-2.0 in `ios/ConferenceLiveKit`, libsignal under AGPL-3.0, Lucide icons under ISC).
