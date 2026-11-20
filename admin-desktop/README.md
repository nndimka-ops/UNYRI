# UNYRI Admin Desktop

Kotlin/JVM 17 + Compose Desktop for Windows and Linux. This is an administration app, with an isolated offline demo and no default server address. No server call occurs until an operator explicitly signs in or restores a session.

Read [the specification](../docs/ADMIN_DESKTOP_SPEC.md) and [implementation report](../ADMIN_DESKTOP_REPORT.md) for the current limits. No changes were made to the WinForms app, backend or mobile apps.

On this PC (checks free C: space first and skips below 2 GiB):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build.ps1 test
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build.ps1 createDistributable
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build.ps1 run
```

Manual environment:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
$env:JAVA_TOOL_OPTIONS="-Duser.home=$env:USERPROFILE"
.\gradlew.bat --no-daemon :app:run --args=--demo
# Omit --args=--demo for sign-in. Server origin starts blank.
```

Use `createDistributable` for a bundled-runtime app image; `packageDistributionForCurrentOS` for MSI (JDK 17 requires WiX 3.x). Linux must run `./gradlew --no-daemon test createDistributable packageDeb` **on Linux**, with JDK 17, fakeroot and dpkg. Linux remembered sessions require an unlocked Secret Service plus `/usr/bin/secret-tool` (`libsecret-tools`). There is no plain-file fallback.

Modules: `core` (HTTP, typed models, proof crypto, secure storage and paging; no UI dependency), `design-system` (reusable monochrome components), `app` (admin navigation and flows). Refresh is manual; no UI polling. The only deadline is the one-shot 15-minute inactivity lock. Ctrl+R refreshes; Ctrl+L locks.

## Local server wizard (SH-4, Windows / Linux)

The normal launch opens **Connect to a server / Create a server on this computer**. The existing `--demo` remains isolated from server calls. The wizard uses seven plain screens: Docker + release folder, name/DDNS, Internet/CGNAT, ports/router/firewall, rooms/registration, install, phones/owner claim. All text is in ru/en/uk. QR generation uses ZXing locally and contains only the HTTPS origin.

Use a separate unpacked reviewed release, containing this change's `scripts/selfhost/` and `docker-compose.selfhost.yml`. Git checkouts, remote Docker contexts, unknown selfhost stacks/volumes and preexisting environment files are refused. One `unyri-selfhost` stack per local Engine is supported. The app stores only its location/name in `~/.unyri-admin/local-server.properties`; the connection doctor reads only allowlisted nonsecret address settings from that owned installation’s environment file. Docker Desktop with running WSL 2 and Linux containers is required on Windows; Docker Engine + Compose 2.24+ on Linux. System software is installed manually.

SH-4 now drives the canonical SH-5 installer, preflight and firewall helper. Published images are the default; a reviewed full source release can use explicit -FromSource / --from-source consent. Exit 0 means installed with HTTPS health verified from this PC, 1 means failed, and 3 means installed with public access unverified. Owner-code reveal reads the private backend_data file only on request; no SQL bootstrap helper or first invite is generated. Register and claim ownership first, then enable invite-only through authenticated SH-2 web administration.

My server delegates backup/restore to the canonical scripts. Restore requires a fresh Engine and the matching backup configuration, and refuses existing volumes; the wizard never deletes volumes to force restore. Update delegates to install again. Desktop helpers only add UAC launch, opt-in router mapping and typed-confirmation cleanup. The bundle builder/downloader allowlists include those five helpers.

Local cached-image trial for developers: `tools/PrepareSelfhostTrial.ps1` makes a loopback copy (TCP 18080/18443, TCP+UDP 13478, UDP 59152-59200, Caddy `tls internal`, no rooms, no firewall or router changes, exit 3 without public verification). Free those ports first (a local QA tunnel on 18080 blocks the preflight). Preparation requires explicit reviewed server/MinIO/mc image references; it performs no Docker or network call. Public-IP/DuckDNS/UPnP remain separately consented in ordinary installs. No timers or background polling are added.

Reproduce the light build/tests from `admin-desktop/`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build.ps1 test
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build.ps1 createDistributable
# Normal home screen, with JAVA_HOME / GRADLE_USER_HOME from above:
.\gradlew.bat --no-daemon --max-workers=2 :app:run
```

Linux: `./gradlew --no-daemon --max-workers=2 test createDistributable` on Linux/JDK 17. Tests use synthetic inputs and loopback fixtures only; no install, firewall modification, DDNS update or public-IP call is performed. New QR dependency verification hashes are generated by Gradle and require lead review before release.

All tests are synthetic. `tools/DeriveCryptoVector.cs` compiles with the read-only legacy `CryptoAdmin.cs` to produce the deterministic fixture. See [vector derivation](core/src/test/resources/fixtures/README.md). Dependency verification metadata is trust-on-first-use and must be reviewed before distribution. Keep root LICENSE/COPYRIGHT and third-party notices in release bundles.

## Connection doctor phase 1b (18 November 2026)

My server and the signed-in owner overview show **Server addresses** (internet host, explicit port including 443, canonical HTTPS address, configured call IP, call/room ports, and automatically detected home address with its recorded time) and **How you are connected**. The connection card uses the authenticated account origin; it does not guess LAN, Wi-Fi or tunnel use. The local panel without an authenticated session makes no connected claim. Configured call IP is explicitly unverified; stale LAN metadata gets a warning. Home copy contains the canonical HTTPS name, host/port and bracketed LAN-IP/port, not a raw-IP HTTPS join URL. Secure LAN routing is not implemented.

Local metadata comes through HostService from the ownership-marked installation’s .env. DoctorEnvironment bounds and allowlists reads of UNYRI_LAN_ADDRESS, UNYRI_LAN_PORT, UNYRI_LAN_DETECTED_AT, UNYRI_HOSTING_KIND, UNYRI_INGRESS_MODE, UNYRI_TUNNEL_PROVIDER, PUBLIC_API_ORIGIN and TURN_EXTERNAL_IP. It also accepts the nonsecret compatibility keys UNYRI_DOMAIN, COMPOSE_PROFILES and UNYRI_SUBNET: the canonical installer/Compose derives the HTTPS origin, rooms profile and network exclusion from them. Fixed call ports are 3478 UDP+TCP and 49152–49200 UDP; enabled room ports are 17881 TCP and 17882 UDP. The special loopback trial does not advertise those public media defaults. Credential lines are discarded before their values are accumulated; neither shell sourcing nor a Compose config dump is used. Refresh reads the existing installer snapshot and does not change env or invent a fresh detection time. Updated installer LAN metadata must be integrated separately.

Remote owners use AdminApi GET /admin/self-check and its optional addresses object. Older servers show an update explanation; malformed fields are handled independently, and unknown enum values cannot imply success. Moderators do not receive these cards from the app. Values load on screen open and Refresh, with no doctor polling/timer. --demo shows home.example.test:443, configured 203.0.113.8, LAN 192.168.0.10:443 detected three minutes before the snapshot, canonical call ports and rooms off. Its My server panel is accessible above the workspace; local actions are disabled and no local installation or server is read.

Build and screenshot acceptance remains pending; see [the phase 1b report](../DOCTOR_DESKTOP_REPORT.md).
