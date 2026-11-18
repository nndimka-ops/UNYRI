# Your own UNYRI server: Windows, a home PC or a VPS

## The easiest way

- **VPS:** after the repository and first `server-vX.Y.Z` release are published, use this one command. Replace `<owner>/<repo>` with the published repository. Prerequisites: Linux amd64/arm64, Docker Engine with Compose 2.24+, Bash 4+, curl, Python 3.8+, sha256sum and openssl; configure the domain and ports described below.

```bash
script=$(mktemp) && curl --fail --show-error --silent --location --proto '=https' --proto-redir '=https' 'https://raw.githubusercontent.com/<owner>/<repo>/HEAD/scripts/selfhost/get.sh' -o "$script" && sudo bash "$script" --repo '<owner>/<repo>'
```

The command saves the downloader to a file; you can review it before `sudo bash`. Trust only the owner's repository. The downloader selects the latest stable server release among the most recent 100 GitHub releases, verifies the archive SHA256 and every bundled file, extracts to `/opt/unyri` and starts the interactive installer. A checksum mismatch stops installation before changing the existing directory. A checksum from the same GitHub release detects damaged downloads; it does not replace trust in the publisher. Add `--version server-vX.Y.Z` to choose a release. This command is unavailable until the repository, GHCR images and first release are public.

- **Home PC:** a wizard in the desktop administration app is **coming soon**, as a separate job. It is intended to install without a terminal; the manual Windows/Linux steps below are available now.
- **Manual:** download `unyri-selfhost-X.Y.Z.zip` (Windows) or `.tar.gz` (Linux) and `SHA256SUMS` from the `server-vX.Y.Z` GitHub release. Verify the archive SHA256 (`Get-FileHash` / `sha256sum`), extract, then follow the guide below. Ready-made images are downloaded; no compilation is required. Rerunning updates to this bundle's version and prints the image/ID before and after. Back up before replacing the bundle with another version; preserve `.env`.

[Русская версия](SELF_HOSTING.md). The SH-1 guide and installers are ready for owner testing; fresh installation and physical phones still need the checks in sections 7–11 below. Run every command below **on your own new installation**, from its chosen release directory. These commands do not manage the developer's existing server.

A self-hosted server is a computer that stays on and accepts your phones' connections. You manage accounts, storage and updates. There is no federation between installations. Using the same nickname on another server does not move your account, history or keys. Choose an operator you trust: HTTPS verifies an address, not the operator's reliability.

## 1. Prepare these things

The lead measured this PC at idle on 17 Nov 2026: Caddy 16 MB, backend 25 MB, Postgres 74 MB, Redis 5 MB, MinIO 199 MB, Coturn 13 MB — about **330 MB RAM** total; images occupied about **1.2 GB on disk**. This is one tested stack; LiveKit is not included in these figures. This is not a user/attachment capacity limit: reserve headroom for the OS, peaks, updates, database, files and backups. The allowances below include headroom; published images require no Rust/Go compiler. Storage components: [licences and source tags](../scripts/selfhost/THIRD_PARTY_NOTICES.md).

- Windows 10/11 supporting WSL 2 and Docker Desktop, or 64-bit Linux/a VPS with Docker Engine. For initial testing, allow about 4 GB RAM to run the stack, preferably 8 GB to build it, and at least 20 GB free space **in addition to** attachments and backups. These are planning allowances, not measured capacity guarantees. Rust/Go builds may need more space and time.
- Disable sleep/hibernation while serving users; provide stable internet. Relayed calls use upload bandwidth. An off PC is an unavailable server. On Windows, Docker Desktop must start after reboot; test recovery with no terminal left open.
- A public IPv4, a domain or DDNS name, and router access for a home PC. A VPS usually has a cloud firewall/security group instead.
- Two phones and two test accounts. At least one phone must use mobile data for outside testing.
- Another disk or protected location for backups. A Docker volume on the same disk is not a backup.

Download a verified release bundle and extract it to, for example, `D:\UNYRI` or `/opt/unyri`. Do not copy a working developer folder, its `.env`, keys, database or user files. The bundle contains `docker-compose.selfhost.yml`, `SELFHOST_VERSION`, `SHA256SUMS`, `scripts/selfhost/` and guides. Compose defaults to this exact release's `ghcr.io/<owner>/unyri-server:X.Y.Z`, `unyri-minio:X.Y.Z` and `unyri-mc:X.Y.Z` tags. Internet is required to download images and obtain a certificate.

Set `UNYRI_SERVER_IMAGE` in the environment or your own `.env` to explicitly select another server image; `UNYRI_MINIO_IMAGE` and `UNYRI_MC_IMAGE` override storage images. Reruns preserve explicit overrides; remove them to use the bundle's version. Installers do not default to `latest`. Developers can use a full source checkout with `server/` and `shared/`: `bash scripts/selfhost/install.sh --from-source` or `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\install.ps1 -FromSource` (alias `-from-source`). PowerShell also accepts `--from-source`. This compiles Rust/Go, needs additional resources and cannot run from the compact bundle. Pass the flag again for each source update. `.selfhost-from-source` preserves the mode for backup/restore; a normal installer invocation switches to published images.

`docker-compose.selfhost.yml` is standalone; do not combine it with `docker-compose.production.yml`, `docker-compose.https.yml` or the ordinary `docker-compose.yml`. Its fixed project name is `unyri-selfhost`. Run one such installation per Docker Engine with these ports and volume names. Use another VM/machine for a second one.

## 2. Check your internet address and name first

### Home addressing and CGNAT

1. On Windows, open PowerShell and run `ipconfig`. Find the active Wi-Fi/Ethernet IPv4 and Default Gateway. The first is your PC's home-network address; the second is your router. On Linux use `ip -4 address` and `ip -4 route` (`default via`).
2. Open that gateway address in a browser. Common examples are `192.168.0.1`, `192.168.1.1`, `192.168.8.1`; use your actual result. The panel login/password may be on the sticker or in ISP paperwork. It may differ from the Wi-Fi password. Ask the router owner/ISP if you cannot log in; do not reset someone else's router.
3. In Status / Internet / WAN find **WAN IPv4**. On the same home internet connection, visit your ISP's address page or a “what is my IP” service and compare public IPv4. That service learns your IP; the installer contacts `https://api.ipify.org` only after separate consent.
4. `100.64.0.0/10` means `100.64.0.0–100.127.255.255`, ISP shared addressing (CGNAT). WAN addresses in `10.*`, `172.16.*–172.31.*`, `192.168.*` are also not public. A WAN/public IPv4 mismatch can mean CGNAT or another router in front of yours. Double NAT requires configuring both routers; forwarding your own router is insufficient with CGNAT.

Mobile internet routers very often use CGNAT — **verify with your ISP** (report item: «проверить»). Options are a public IPv4 from the ISP, a VPS, or a tunnel with a public entry point. A tunnel must carry the required TCP/UDP media ports as well as HTTPS; an ordinary HTTP tunnel does not establish call support. Tunnel setup is outside this installer.

### A permanent name for a changing IPv4

A permanent name is usually easier than a permanent IP. Point your domain's A record at the public IPv4. Remove an incorrect AAAA record if IPv6 is not configured, or some phones and certificate checks may use the wrong destination. Start with DNS without an HTTP/CDN proxy.

DDNS choices:

- Router → DDNS / Dynamic DNS: choose a supported provider and enable updates on the router.
- Free [DuckDNS and its official updater](https://www.duckdns.org/install.jsp): create a name, select `windows-powershell`/`windows-gui` on Windows or `linux-cron` on Linux, and follow the service's instructions. Use Windows Task Scheduler or Linux cron as described there. Enter its token locally into a protected updater file, never a chat, Git or a shared folder. Check the A record after an address change (`Resolve-DnsName NAME -Type A` or `getent ahostsv4 NAME`). The installer does not install a third-party updater automatically.
- A free UNYRI subdomain is **planned, pending an owner decision**; no live name-registration service is provided here.

**DDNS updates DNS, not the media server configuration.** After a public IPv4 change, use a local editor to change only `TURN_EXTERNAL_IP` in your `.env`, save it and rerun the installer. It recreates Coturn and, when enabled, LiveKit. Existing calls may drop. Fully automatic handling of this change is not part of SH-1.

## 3. Ports to allow

This table follows the new `docker-compose.selfhost.yml`, `start-turn.sh`, `Caddyfile` and LiveKit configuration. External and internal numbers must match. Forward to the **PC's LAN IPv4**, not a container/WSL address.

| Port | Protocol | Purpose | When blocked |
|---|---|---|---|
| 80 | TCP | HTTP → HTTPS; Let's Encrypt certificate validation/renewal | No HTTP redirect; one certificate-validation method unavailable |
| 443 | TCP | HTTPS API, `/ws` WebSocket, console, signed object URLs, `/rtc` room signaling | Phones cannot connect |
| 3478 | UDP | STUN and TURN for private calls | Some networks cannot establish media |
| 3478 | TCP | Alternative client connection to TURN | This fallback is unavailable; outgoing relay is still UDP |
| 49152–49200 | UDP | Coturn relay media packets | Calls may connect without audio/video or fail |
| 17881 | TCP | LiveKit ICE TCP, **rooms only** | Room media TCP path unavailable |
| 17882 | UDP | LiveKit UDP mux, **rooms only** | Main room media UDP path unavailable |

443/UDP (HTTP/3), 5349 (TURN TLS/DTLS) and LiveKit's 50000–60000 range are **not used** by this configuration. Caddy's certificate does not automatically enable `turns:`. A network allowing only 443/TCP may require different TURN/TLS infrastructure.

**Do not open** 5432/Postgres, 6379/Redis, 9000/MinIO API, 9001/MinIO console, 8080/backend, 7880/LiveKit API or local 8081/Caddy health. This stack does not publish them on the host. Objects use 443 at `/messenger-attachments/...`; no separate files domain is required.

Before pulling images or starting anything, the installer checks TCP 80, 443, 3478 and UDP 3478, 49152–49200; rooms add TCP 17881 and UDP 17882. Busy ports are grouped by owner, for example `3478 TCP, 3478 UDP, 49152-49200 UDP: Docker container messenger-coturn`; consecutive ports of one protocol form ranges, different owners remain on separate lines, then installation stops with code 1 before pulling/starting. The Docker `unyri-selfhost` project's own published ports are allowed on reruns; the name `com.docker.backend`/`wslrelay` alone does not establish ownership. Run socket checks on the Docker host: a container sees only its own sockets, although other Docker containers' published ports are still checked through Docker. Linux uses `ss` (iproute2), falling back to `/proc/net` with unknown program names. A socket change after preflight can still cause startup failure.

### Windows Firewall

The installer runs without administrator rights. It first shows these exact rules and requests elevation once only for `scripts/selfhost/firewall.ps1`. Declining that prompt continues installation and prints the same commands for manual use. The helper adds rules if their names are absent. To do it manually, use PowerShell **as Administrator**:

```powershell
New-NetFirewallRule -Name UNYRI-Selfhost-Web -DisplayName UNYRI-Selfhost-Web -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80,443 -Profile Any
New-NetFirewallRule -Name UNYRI-Selfhost-TURN-TCP -DisplayName UNYRI-Selfhost-TURN-TCP -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3478 -Profile Any
New-NetFirewallRule -Name UNYRI-Selfhost-TURN-UDP -DisplayName UNYRI-Selfhost-TURN-UDP -Direction Inbound -Action Allow -Protocol UDP -LocalPort 3478 -Profile Any
New-NetFirewallRule -Name UNYRI-Selfhost-TURN-Relay -DisplayName UNYRI-Selfhost-TURN-Relay -Direction Inbound -Action Allow -Protocol UDP -LocalPort 49152-49200 -Profile Any
# Only if you enable rooms:
New-NetFirewallRule -Name UNYRI-Selfhost-Rooms-TCP -DisplayName UNYRI-Selfhost-Rooms-TCP -Direction Inbound -Action Allow -Protocol TCP -LocalPort 17881 -Profile Any
New-NetFirewallRule -Name UNYRI-Selfhost-Rooms-UDP -DisplayName UNYRI-Selfhost-Rooms-UDP -Direction Inbound -Action Allow -Protocol UDP -LocalPort 17882 -Profile Any
```

Inspect existing rules with these names if they were edited: the installer does not overwrite them. Third-party firewall/antivirus and WSL/Docker Desktop rules can also block traffic.

### Linux: ufw and a cloud firewall

If using ufw, run on your own server:

```sh
# Before enabling ufw on a VPS, allow YOUR actual SSH port. Example for port 22:
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 49152:49200/udp
# Only with rooms:
sudo ufw allow 17881/tcp
sudo ufw allow 17882/udp
sudo ufw status verbose
```

If ufw is disabled, run `sudo ufw enable` only after allowing the real SSH port and checking VPS console access. The Linux installer does not change firewall policy. Allow the same ports/protocols in the VPS panel. Docker [published ports can bypass ufw](https://docs.docker.com/engine/network/packet-filtering-firewalls/); internal services are also protected by having no `ports`, and the outside filter is your router/cloud firewall. Do not disable Docker's firewall or publish the database for testing.

## 4. Router: reserve an address and forward ports

1. Find LAN / DHCP / Address reservation / Static lease. Reserve the PC's current LAN IPv4 for its network adapter. Moving from Wi-Fi to Ethernet may change its MAC/address. Save and recheck `ipconfig`.
2. Find Port Forwarding / Virtual Server / NAT (often under Advanced / Internet / Security).
3. For each table row, enter the external port/range, identical internal port/range, protocol and PC LAN IPv4. Create separate TCP/UDP rules for 3478, or use Both. Enter the 49152–49200 relay range as UDP.
4. Do not put the whole PC in DMZ or expose router management. If WAN router management occupies 80/443, configure it following the manufacturer's instructions.
5. The installer lists the exact UPnP ports, including room ports, before requesting consent. UPnP is an optional automatic attempt, requested separately by the installer. No software is installed. Windows uses available system UPnP COM; Linux uses an already installed miniupnpc `upnpc`. If unavailable, it is skipped. Available requests report their results; verify them in the router. UPnP may be disabled by its owner, cannot solve CGNAT and may lose mappings after reboot. Saved manual rules are preferable for a permanent server.

*Screenshot placeholders: “ipconfig — active adapter and Default Gateway”; “DHCP reservation”; “TCP 443 forwarded to PC LAN IPv4”; “UDP relay range”. Publish fictional example addresses without passwords.*

## 5. Install on Windows

1. Check WSL 2/virtualization support using the [official Docker Desktop instructions](https://docs.docker.com/desktop/setup/install/windows-install/). If needed, run `wsl --install` in elevated PowerShell, reboot and finish WSL setup. UNYRI does not install system software for you.
2. Install Docker Desktop, enable its WSL 2 backend and Linux containers, and start it. Check `docker version` and `docker compose version`: Compose **2.24+** is required. Use a local Docker Desktop context; the installer refuses remote SSH/TCP Docker contexts.
3. Prepare DNS and port forwarding. Open a normal PowerShell window, enter the release directory, and run **one command**:

```powershell
cd D:\UNYRI
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\install.ps1
```

`Bypass` applies to this process only. Run a reviewed local file. If organization policy forbids it, arrange access with its administrator.

If `.env` is missing but `unyri-selfhost` volumes remain, the installer exits 1 before creating new configuration: restore the old `.env` from a backup to keep the data, or explicitly run the printed removal commands for the listed old volumes/containers and rerun; nothing is deleted automatically.

4. Enter the domain without `https://`, then “Server name shown in the apps [UNYRI]”: 1–64 printable characters, no controls, default UNYRI. It is saved as `UNYRI_SERVER_NAME` and passed to the backend; coordinated server behavior applies it only while the database name is still the untouched COCO default, preserving an already configured name. Then choose manual public WAN/VPS IPv4 entry or optional detection via api.ipify.org: the service learns the address and the detected IPv4 requires confirmation; declining or a failed request falls back to manual entry. Choose whether rooms are needed. Agree to UPnP only if desired. Cryptographically generated secrets are saved in `.env` beside Compose and never printed. Its ACL is restricted to the current account and SYSTEM; use NTFS. Keep `.env` on reruns.
5. Wait for image downloads, local `/health`, and public `https://NAME/health`. The installer prints versions before/after, the address, admin command and QR if `qrencode` is already installed; otherwise it prints a copyable link, without a third-party QR service. With an SH-2 image, the one-time owner setup code is shown once in a private interactive terminal; `coco-admin grant-owner USERNAME` remains the fallback after registration (section 8).

*Screenshot placeholders: “Docker Desktop — Engine running and WSL 2”; “installer questions, excluding .env”; “server address and successful local health”.*

## 6. Install on Linux/a VPS

1. Install Docker Engine and Compose plugin using the [Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/) or those for your distribution. After adding Docker's official apt repository on Ubuntu, install with `sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin`. You also need `bash`, `curl`, `openssl` (Ubuntu: `sudo apt install curl openssl`). If Docker is missing, the installer explains and exits; there is no automatic `curl | sh`.
2. Check `docker version`, `docker compose version` (2.24+), and your account's access to the local Engine. If root is needed, use `sudo bash` below; `.env` will belong to root. Docker access gives broad machine privileges.
3. Configure the domain, router/cloud firewall, then run:

```sh
cd /opt/unyri
bash scripts/selfhost/install.sh
```

Questions and results match Windows. `.env` is created with mode 600. Optionally install `qrencode` for QR or miniupnpc for a consented UPnP attempt. Neither is required. Bash 4+ is supported; do not run `/bin/sh install.sh`.

*Screenshot placeholder: “Linux terminal — server address and health result, excluding secrets”.*

## 7. Certificate and phones

Exit codes for terminals and the future desktop wizard: **0** — local stack healthy and public HTTPS check passed; **1** — failure/busy port/startup error; **3** — “installed, not verified from outside”: local stack healthy, public HTTPS unconfirmed, data and running services preserved. Many home routers cannot reach their own public address (NAT loopback); the real test is a phone on mobile data with Wi-Fi off. For code 3, keep the installation/volumes and check the phone, DNS, certificate and forwarding.

Caddy obtains and renews a Let's Encrypt certificate automatically when the name points here, 80/443 TCP are reachable from outside, and outbound DNS/HTTPS work. Certificates persist in `caddy_data`; keep it during updates. See [Automatic HTTPS](https://caddyserver.com/docs/automatic-https). The private-CA certificate from `Start-CocoHttps.ps1` is for LAN development and is not used for this public installation.

On a phone **with Wi-Fi off**, open `https://NAME/health`. Expect HTTP 200 and JSON containing `status: "ok"`, `database: true`, `redis: true`, without a certificate warning. On home Wi-Fi the public name may fail because the router lacks NAT loopback/hairpin; that alone does not prove outside failure. Configure local DNS for the same domain and LAN IP if necessary, preserving the HTTPS hostname.

In UNYRI's sign-in/add-account screen, select “Server”, enter `https://NAME` **without** `/health`, `/api`, `/administration`, query or fragment. Check its server card and register there. Android (`ServerEndpoint.kt`) and iOS (`AppConfig.swift`) save this origin and derive `/ws`; ordinary builds do not accept external HTTP. TLS failures may appear as a secure connection/connection error; **verify the exact text in current builds** («проверить»). Do not disable certificate validation.

Login step 2 profile QR links carry their server separately; they are not registration invitations. Room invitations do: legacy `coco://conference?server=<encoded HTTPS origin>&room=...#...`; compact v2 stores a custom server origin in its fragment, or a fingerprint for the default origin. Invitations are checked against the selected server and do not move accounts. This Android source writes/reads `coco://c#...`; iOS writes `unyri://c#...` and reads both; **verify iOS → Android forwarding compatibility** («проверить»). Generate room invitations in the app and share only with participants: they contain access capability and media key.

### Phone: invitations and the first owner (SH-3, 18 November)

In iOS SH-3, open Server on sign-in, or Settings → Account → Server → “Scan server invitation”. An invitation QR contains `https://SERVER/#server=<encoded-origin>&invite_code=<encoded-code>`: the credential belongs only in the fragment, never a query. Confirm the displayed origin before registration opens with that server’s invitation prefilled. If already signed in, the app adds an account and keeps the previous account in Settings. The code field appears only for invite_required=true. Registration refusals refresh stale server policy.

The registered custom scheme also accepts `unyri://server/#server=<encoded-origin>&invite_code=<encoded-code>`. HTTPS app launching requires associated domains/AASA; use the in-app scanner for arbitrary self-host domains. Public invitations require HTTPS; only the existing debug allowance accepts local HTTP. The installer’s address-only QR opens a site for manual address entry rather than importing an invitation. Keep invitation codes private.

After sign-in, the app checks first-owner availability. On a fresh server, “Become the owner” appears beside the server card. Enter the 16-character installer code or use “Scan owner code”. Case, dashes and whitespace are ignored. The editor stores plain characters; a separate preview groups four without moving the cursor. After acceptance, the app refreshes the role and opens existing administrator enrollment/unlock. Established servers have no claim entry, including current QA. Codes clear when leaving/backgrounding and hide during recording; the existing iOS mechanism cannot block user screenshots.

In unlocked administration, open “Registration and invitations”. Owners change invitation requirements; owners/moderators create and revoke invitations. Defaults are one use and seven days, configurable to 1–10000 uses and 1–365 days. Copy/share the code, fragment link or QR immediately after creation: lists never return plaintext. Owners also see configuration checks. “Your own server” gives short instructions and opens existing connection checks. Separately test DNS/HTTPS/API/WebSocket, a cross-network call and push delivery: TURN configuration and an HTTP 200 do not prove all ports work. SH-3 still requires Mac build, CI and phone qualification.

## 8. Make yourself administrator

1. Register an account in the app on **your own** server. Registration is open by default.
2. On its first ownerless start, the backend generates a one-time code such as `ABCD-EFGH-JKLM-NPQR`. The installer or local desktop admin app should read the private `COCO_SETUP_CODE_FILE`, default `COCO_DATA_DIR/owner-setup-code` (`/app/data/owner-setup-code` in Docker). The backend also logs the code once on a clear `FIRST OWNER SETUP CODE` line. Keep that line private.
3. In a client supporting SH-2, sign in and enter the code on the “Become the owner” screen. The backend atomically grants ownership and destroys the code. It can never be used again, even after an owner is deleted. iOS 0.4.21 has this screen (described above) and Android SH-3 provides it in Profile; already installed older releases still need the CLI fallback.
4. Refresh the profile, configure administrative factors in the app, and open the web console from its protected administration section.

If the image supports SH-2 and `/app/data/owner-setup-code` exists, the installer shows the code once in a private interactive terminal: open UNYRI on the phone → sign in to your server → Profile → Become the owner. Redirected output never includes the code; it remains in private volume `backend_data`, and the shown marker survives updates and backups. Do not record the terminal session, publish the code or copy it into logs; code consumption is handled by the SH-2 server.

While the server is unclaimed, rerunning prints a private redisplay command even after the first showing: Linux — `docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml exec -T backend cat /app/data/owner-setup-code > /dev/tty`; in PowerShell use the installer's printed console guard and `[Console]::WriteLine` variant, and never paste the code elsewhere or record the terminal.

If the code is absent, the client lacks ownership claiming, or you need the fallback: first register your nickname in the app on your server. From the release directory run, replacing `my_nickname` without angle brackets:

```sh
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml exec backend coco-admin grant-owner my_nickname
```

This is the explicit-file form of `docker compose exec backend coco-admin grant-owner <nickname>`. The image includes `coco-admin` and `messenger-server`; this command does not need Rust installed on the PC. It does not create an account; `Account not found` means the nickname is wrong or registration used a different server. It also revokes existing administrative sessions.

Refresh your profile/sign in again, open administration in the app and set up the second factor: authenticator/TOTP, recovery codes and paper key as requested by the selected policy. The default policy uses a password and both additional factors; store the paper key separately from this PC. **Verify the current initial-setup screens on a phone** («проверить»). The web console is at `https://NAME/administration`; the protected app section can open it with a one-time handoff. Knowing its URL gives no permissions. `COCO_ADMIN_KEY` encrypts server-side factors; it is not a login password and the installer already generated it. Do not accidentally replace it.

Set the server name, operator contact, file limit, per-account quota and storage budget in the console. Under “Self-hosting”, the owner can switch between open registration and invitation-only registration at any time. Owners and moderators create, list and revoke codes; the default is one use and seven days. Copy/share the code or link when created; the plaintext is never listed again. The link carries server and invite code in its fragment; iOS and Android SH-3 import it after explicit server confirmation. Expired, revoked and exhausted codes cannot register accounts. Open mode needs no code; temporary total registration closure remains a separate control.

The owner also sees checks for public URL, TURN, rooms, push, bucket reachability, upload readiness, free disk space, version and migration. Pass matching coturn values to the **backend**: `TURN_EXTERNAL_IP`, `TURN_MIN_PORT=49152`, `TURN_MAX_PORT=49200`; the public address uses existing `PUBLIC_API_ORIGIN`. SH-1 currently forwards the external IP only to media services: coordinate installer/compose wiring when merging the phases. No public URL/TURN/LiveKit/push connection is attempted: `not_probed` explicitly means DNS, certificate and outside ports still need a phone/mobile-data test. Storage uses only HEAD, bounded to five seconds. Reserve space for OS, database, images, logs and backups: zero/“unlimited” does not protect the disk. See the API (docs/ADMIN_API_CONTRACT.md in the source repository), admin console (docs/COCO_ADMIN_CONSOLE.md in the source repository) and production checklist (docs/DEPLOYMENT_PRODUCTION.md in the source repository).

### Server addresses: phase 1a backend/installer (18 November, lead validation pending)

The authenticated owner/admin self-check now supplies the future Server addresses card with the public HTTPS origin/host and explicit port (including 443), configured call IP labelled unverified, detected home IP/port and time, effective call ports, room availability and hosting/ingress metadata. Client cards are a separate change. Moderators/users receive no address metadata; this snapshot never dials those addresses.

The PC detects its home IPv4 again on each managed start: install/rerun/update, backup recovery, and `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\start.ps1` or `bash scripts/selfhost/start.sh` from the release folder. Windows ranks physical default routes by route plus interface metric and skips virtual/VPN adapters; Linux uses the kernel route source and a physical interface (iproute2, GNU coreutils and util-linux `flock` required). Failed or ambiguous detection clears the old LAN snapshot. Compose `up --no-build` recreates backend when environment changes.

Only the six installer-owned fields `UNYRI_LAN_ADDRESS`, `UNYRI_LAN_PORT`, `UNYRI_LAN_DETECTED_AT`, `UNYRI_HOSTING_KIND`, `UNYRI_INGRESS_MODE`, `UNYRI_TUNNEL_PROVIDER` are replaced atomically under a lock, retaining other bytes and existing ACL/mode/ownership. Hosting defaults to `unknown`; explicitly set `home_pc` or `vps` locally, with LAN suppressed for a VPS. Ingress defaults to `direct`; `tunnel` with provider `tailscale_funnel` / `cloudflare` records an already configured ingress, without installing it.

An OS startup job must call the same wrapper as the installation account after network/Docker readiness; no task/service is registered automatically here. Raw `docker compose up`/`start`/`restart` and Engine auto-restart keep the last written value. Run the wrapper after a network change and verify OS boot integration locally. Keep the canonical HTTPS hostname and certificate validation; a private IP alone is not a safe login URL. Cargo, real installer/network/DHCP/reboot checks remain lead acceptance gates.

### Android SH-3: phone steps without a terminal

1. On phone A, open Settings → Server, enter and confirm your HTTPS server address. The selected server stays visible on its card. Own server contains the short guide. Check connection explicitly checks DNS, TLS, the public API and minimum app version, and shows the existing WebSocket connection state. Unknown fields do not mean success.
2. Register and complete the login2 recovery-key step. After normal sign-in the app checks whether ownership can be claimed. Only when available does Profile show Become the owner next to the server card.
3. Enter the installer’s one-time 16-character code in four groups, or scan a QR containing the plain code. Case and dashes do not matter. Success refreshes the role and opens the existing administrator protection enrollment/unlock. If limited, wait 15 minutes and retry manually. Established servers show no ownership entry.
4. Open Administration → Own server and require invitations. Open registration has no extra field. Owners and moderators create invites (1–10,000 uses, 1–365 days), see status, uses, expiry and creator, and revoke with confirmation. Only owners see registration settings and self-check.
5. At creation, copy/share the code or link or show its QR to phone B: the server never returns plaintext again. Links use `https://SERVER/#server=<encoded-origin>&invite_code=<encoded-code>`: the code is after `#`, never a query after `?`. Phone B can scan in Settings → Server or the existing Contacts scanner. Without a verified dynamic domain Android may require Open with or keep a tapped link in the browser; use QR in that case. Confirm the full displayed server address before switching; an account signed in on another server will be signed out. The code is prefilled for registration. Keep a live invitation link private.
6. Disable Wi-Fi on phone B. Test sign-in, messages both ways, a call, and a notification with the app closed. The owner opens Administration → Own server → Check server and reads each field’s explanation. `not_probed` means check from a phone on mobile data: the server checks configuration, not external DNS/TLS, TURN UDP/TCP, rooms or actual push delivery. Do not expect background delivery without a configured push provider.

Android SH-3 was prepared on 18 Nov 2026; lead two-phone QA is still required. iOS follows with the new Mac. Already installed releases do not gain these screens automatically.

## 9. Calls, rooms and notifications

For private calls, Coturn provides relay and the backend issues short-lived credentials. Their shared secret is never given to the phone. The relay is isolated from Postgres/Redis/MinIO; relaying to private/special networks is denied. Test audio/video between **different outside networks**, not just two phones on the same Wi-Fi.

LiveKit is enabled by answering `y` at first installation (profile `rooms`). It uses your public IPv4 and 17881/TCP plus 17882/UDP; API 7880 stays inside Docker. `room.auto_create=false`, and every public `/rtc` connection passes backend authorization. `/twirp/*` is blocked outside. See the [existing room architecture](../scripts/conference/README.md). **Verify** actual audio/video reception and decryption on two physical clients («проверить»); HTTP health does not establish this. Without rooms, private messages and private calls do not depend on LiveKit.

The official app on a new server receives messages through its open connection. Background notifications and incoming calls when an app is asleep/unloaded are **not promised**: no shared push relay exists yet. APNs alert/VoIP are already implemented in the server, but require authorized Apple credentials matching the installed iOS bundle/topic/environment; FCM requires a service account authorized for that Android client's Firebase project. A randomly created different-project credential will not work. Official keys are not distributed to operators. Custom mobile builds can use their own provider setup; that is outside SH-1.

The stack explicitly sets `UNYRI_OFFLINE=true`: this disables provider push sending, not the server/network. No push keys are included or need mounting. Even a provider-accepted HTTP request does not prove a notification appeared. See the push audit in the full source repository (`docs/PUSH_SECURITY_AUDIT_20261113.md`) for limitations and the proposed relay model. Android Force stop and OS restrictions need separate testing. Opening another inbound port cannot enable push.

## 10. Updates, backup and moving

### Update

There is no scheduled auto-update. Back up first and preserve the exact old release directory and images. Obtain a compatible server/client release, replace files with the verified bundle while keeping `.env`, then rerun `install.ps1`/`install.sh`. On a VPS, rerun the downloader command with the desired `--version`. The installer pulls this bundle's image versions, prints image/ID before and after and runs `up --no-build`; secrets and volumes persist. Release tags are not immutable registry digests: publishers must not reassign published versions. Local source updates require `--from-source` / `-FromSource` again. An older backend may run after SQL migrations only if its schema is compatible; otherwise restore the full previous snapshot on a separate machine. Changing `.env` `POSTGRES_PASSWORD` does not change the role password in an existing database volume.

### Complete cold backup

Schedule a short outage and check free space. Run:

```powershell
# Windows, from the release directory:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\backup.ps1
```

```sh
# Linux:
bash scripts/selfhost/backup.sh
```

The script obtains a small helper image before stopping **only project unyri-selfhost**, archives six volumes (Postgres, Redis, MinIO, Caddy data/config and private backend_data) and `.env`, checks archive readability and starts the stack again. A `selfhost-backups/DATE...` directory is complete only with its `COMPLETE` marker. Failures leave no marker; check the server returned to service. Copy a completed directory and its exact source release onto another protected disk. Restrict access: it contains personal data, certificates and secrets. Phone history backup and server backup solve different problems: a server cannot recover lost client decryption keys.

The old `scripts/Backup-CocoDatabase.ps1`/`Restore-CocoDatabase.ps1` verify the developer's `messenger-postgres` container and **do not apply** to selfhost. These new scripts preserve the whole stack, not SQL alone.

`backend_data` stores private SH-2 files (owner code and shown marker), mounts at `/app/data` owned by UID 10001, and is included as the sixth archive `backend_data.tgz`. Do not dump its files or archive contents into logs. `down --volumes` removes it too; old five-volume backups need a separate migration plan before restoring with the new script.

### Restore or move to another PC

Use a **fresh Docker Engine without any `unyri-selfhost_*` volumes**, for example a separate VM. Restore refuses any existing destination volume, protecting the current installation. For recovery on the same PC, preserve damaged volumes first and use a separate Engine; restore does not delete them.

1. Install Docker and extract **the same source release**. Transfer the completed snapshot to, for example, `E:\UNYRI-backup` or `/mnt/backup/unyri`. Before first running the installer, copy its `.env` to the release root. Do not generate new secrets.
2. Restore using your actual snapshot path:

```powershell
Copy-Item -LiteralPath E:\UNYRI-backup\.env -Destination .env
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\restore.ps1 -BackupPath E:\UNYRI-backup
```

```sh
install -m 600 /mnt/backup/unyri/.env .env
bash scripts/selfhost/restore.sh /mnt/backup/unyri
```

3. After successful restore, update `TURN_EXTERNAL_IP` and DNS A/DDNS for a move. Preserve the old domain: origin separates client data. A different name is not automatic client account migration. If the Docker subnet conflicts, change `UNYRI_SUBNET`, `UNYRI_PROXY_IP`, `UNYRI_SFU_IP` and `CONFERENCE_LIVEKIT_API_URL` (when rooms are enabled) consistently.
4. Stop the old server before directing users to the replacement. Run the normal installer on the replacement, check health, both accounts, an old attachment and a new message/photo/call. Plan another update only after validating restoration. On failure preserve partial restored volumes for investigation; do not merge two databases.

### Stop or remove

From the release directory:

```sh
# Stop/remove containers and networks, keep data:
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml down
# Permanent deletion of this stack's SIX volumes: ONLY after checking an independent backup:
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml --profile rooms down --volumes
```

`down --volumes` permanently deletes installation data and is unnecessary for updates. Afterwards remove only this release directory and its private `.env` if no longer needed; retain an independent backup according to your policy. On Windows remove our rules:

```powershell
Get-NetFirewallRule -Name 'UNYRI-Selfhost-*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
```

Remove only UNYRI's manual/UPnP router rules. On Linux use `sudo ufw delete allow 80/tcp` and the equivalent for other table entries **if no other service needs them**. Remove this installation's DDNS/updater and cloud rules. Do not use `docker system prune --volumes` to remove a single installation.

## 11. Troubleshooting

Start with safe commands. Do not publish `.env`, full `docker compose config` or environment-bearing `docker inspect` output:

```sh
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml ps -a
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml config --quiet
docker compose --project-name unyri-selfhost --env-file .env -f docker-compose.selfhost.yml logs --tail 80 backend caddy coturn
```

Inspect logs locally; remove tokens, user addresses and personal data before sharing. Caddy access logging is deliberately absent so signed S3/room URLs are not sent to shared logs. List local ports with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\selfhost\check-ports.ps1 -Domain family.example.org
```

```sh
bash scripts/selfhost/check-ports.sh
```

| Symptom | Action |
|---|---|
| Docker missing/Engine unavailable | Install manually/start Docker Desktop or Engine; check Linux containers and local context. No deletion is necessary |
| Download/build failed | Check internet, free space, GitHub/Cargo/Go/registry access. Rerun; keep `.env` and volumes |
| Address already in use | Find the other listener with check-ports; do not stop unknown services automatically |
| Pool overlaps with other one | Pick an unused Docker subnet and update the four related settings from the moving section |
| Works locally, fails on mobile data | Compare WAN/public IPv4, A/AAAA, forwarding, host/cloud firewalls and CGNAT. TCP tests do not prove UDP |
| Works on mobile data, fails at home via domain | Check NAT loopback/local DNS. Do not change the account to HTTP/LAN IP |
| Certificate error | Check domain, phone/PC clock, DNS, incoming 80/443 and Caddy logs. Keep TLS validation. Let Caddy retry after fixing; do not delete certificate volumes |
| Installer says local ready, public health failed | Stack remains running; fix DNS/TLS/routing. Test on mobile data: LAN hairpin may affect only the PC check |
| Health passes, attachment fails | Check storage-init (Exited 0 is expected), private bucket, write-only principal, S3 path/Host and quotas. Never enable bucket versioning: even Suspended is unsupported |
| Call fails/no audio | Check 3478 UDP/TCP, the full UDP relay range, current TURN_EXTERNAL_IP and DDNS. Test two outside networks; blocked UDP may require another TURN/TLS setup |
| Rooms fail | Check `COMPOSE_PROFILES=rooms`, all four CONFERENCE settings, public IP, 17881/TCP+17882/UDP and gateway. Do not expose 7880/Twirp |
| No push with closed app | Expected official-client limitation before the push relay; more inbound ports cannot fix it |
| Admin account not found/cannot enter | Check nickname and app-selected server; refresh profile and complete factors. Restore original COCO_ADMIN_KEY from your backup, not a newly generated replacement |
| Disk full | Free space outside installation data, set quotas/back up. Do not delete volumes to “clear cache” |

For TCP checks **from another internet connection** use `Test-NetConnection family.example.org -Port 443`, then 3478 and optional 17881. Test 80's HTTP redirect. Verify UDP through a real TURN call/authenticated TURN tester; TCP checks or an “open port” website are insufficient. Record release, OS, time, symptom and failing step. All «проверить» items are collected in the SH-1 report.
