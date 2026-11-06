# UNYRI Admin Console (Windows)

Native Windows application for UNYRI administration and monitoring. Admin screens
are Windows Forms controls that use the selected server's native admin API. The
optional **В браузере** action opens a same-origin browser console through a one-time
handoff; there is no embedded web view.

## Public alpha 0.3.16

This Windows console is an **unsigned x64 alpha**. The release package does not
install certificates or change Windows trust settings.

For remote administration, use 64-bit Windows with **.NET Framework 4.8**, the
selected server's public HTTPS address and an administrator account with the
factors required by that server. Docker, Rust and a browser runtime are not
required for native remote-admin pages. Roles are enforced by the server.

Keep these three runtime files together:

- `UNYRI-AdminConsole.exe`
- `UNYRI-AdminConsole.exe.config`
- `BouncyCastle.Cryptography.dll`

The release must also include `LICENSE`, `COPYRIGHT` and the Bouncy Castle MIT
and additional Apache notices. Its release manifest records source and binary
SHA-256 values; the corresponding source is linked on the same release page.
No saved administrator credentials, DPAPI files, server `.env`, TLS/provider
keys, logs, history, database backups or user data belong in that package.

Local server controls and the optional local AI observer require a matching
source checkout and its scripts. Put the console inside that checkout, or set
`COCO_WORKSPACE` to its root before launch. Docker Desktop/Compose, Git and
Rust/Cargo are prerequisites for the relevant local stack actions. They are not
bundled with the portable console, and the console is not a standalone server.

## Build from source

Use the matching source snapshot rather than an old executable. The command
below builds from the selected checkout. No administrator login is needed to
compile it. The pinned dependency may be restored from an already verified NuGet
package for an offline build, as shown below.

## Run

```powershell
cd C:\dev\messenger
powershell -NoProfile -ExecutionPolicy Bypass -File desktop\Build-CocoAdmin.ps1
.\desktop\UNYRI-AdminConsole.exe
```

The app uses .NET Framework 4.8. Keep `UNYRI-AdminConsole.exe.config` and
`BouncyCastle.Cryptography.dll` beside the executable. The interface is native
Windows Forms and does not require a browser runtime.

On the first build, `Restore-Dependencies.ps1` obtains Bouncy Castle 2.7.0 from
NuGet over HTTPS and verifies both the package and DLL SHA-256 before use.
An existing DLL with a different hash is rejected without replacement. An
offline build can restore from the same verified package first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File desktop\Restore-Dependencies.ps1 -PackagePath C:\Downloads\BouncyCastle.Cryptography.2.7.0.nupkg
```

When distributing the console, include the root `LICENSE` and `COPYRIGHT`,
along with the notices and license files in
[`third_party/bouncycastle`](../third_party/bouncycastle/README.md).

## Sign-in and sessions

Sign in with the selected server URL and administrator username/password. The
administrator chooses an authenticator, a paper signing key, or both, according
to the policy supported by the server. For a hosted server, use its public HTTPS
domain and keep certificate validation enabled. Local loopback endpoints are
for the local development stack; a phone needs an address it can reach.

The optional **Запомнить вход на этом компьютере** checkbox protects the saved
credentials, paper key when used, and active bearer/admin session with Windows
DPAPI for the current Windows user. Without explicit opt-in the session is not
kept after exit. Legacy sessions without the opt-in marker and sessions near
access-token expiry are rejected. Use **Выйти** to revoke the server session and
remove the local copy.

TOTP and recovery codes are never saved. Refresh tokens are deliberately not
persisted because refresh rotates the ordinary auth session and invalidates the
admin token; after expiry the operator signs in and unlocks again. Access-token
and refresh-token lifetimes are set by the selected server. The native admin session is
separately limited to **12 hours of inactivity** and **12 hours maximum**.
These values can be changed by server configuration.

The admin session now lasts up to 12 hours; after that, or after an account
change, reopening may require signing in again. The bearer token is still
limited by its own 12-hour lifetime.

## Native admin features

The in-app **Админ** section includes:

- server-side summary;
- user search; open a complete user profile by double-clicking a row; load and
  enlarge profile photos through the authenticated admin API; inspect devices,
  app/OS versions and last activity; end a single device session separately
  from persistently blocking future sign-in on an installation; account
  moderation, history and internal notes;
- owner-only permanent account deletion after exact username confirmation, with
  a required reason and an audit entry;
- report and feedback review, including feedback screenshots;
- multi-select in users, reports and feedback with bulk owner actions; comments
  are optional for review actions; bulk deletion requires an exact typed phrase
  and reason, and bulk suspension requires an explicit audit reason;
- owner-only action journal;
- owner-only storage overview/preview/confirmed cleanup and server policy;
- owner-only PostgreSQL reset with a verified custom-format backup, an exact
  confirmation phrase, a local-project identity check, and server restart.

Admin pages use native navigation and responsive action bars; labels wrap in
narrow windows instead of being clipped. Confirmation, warning, error, and
result dialogs share the same dark native-console style and use action-specific
button labels.

All changes use the server's admin API and audit trail. Admin role and session
permissions are enforced by the server. Existing local monitoring, dependency
checks, stack controls, and logs remain available in their own sections.

The reset operation is intentionally narrow: it resets only the `messenger`
PostgreSQL database after backing it up under `desktop/backups/`. Redis and
MinIO data are not deleted. It requires the Windows app to be elevated and
refuses to act unless the PostgreSQL container is labeled as belonging to this
checkout. The dependency screen also lists installed Docker Compose, Rust/Cargo,
Rust compiler, Git, Caddy, and local Docker image versions. Its update check is
read-only; it checks winget, rustup, and Cargo without installing packages or
changing `Cargo.lock`. It does not query registries for updated service images.
The separate confirmed tool update targets only Docker Desktop, Git, and the
stable Rust toolchain. It does not update service images, databases, or
`Cargo.lock`.
Missing Docker Desktop, Git, or Rustup can be installed from the dependency
screen via winget after confirmation. Restart the app after installing Git or
Rustup so the updated PATH is picked up.
The one-button server start performs a prerequisite check and can offer to
install the missing required tools before starting the local stack. Start,
rebuild, HTTPS setup, and database reset are blocked when any known
`messenger-*` container has a Compose `working_dir` different from the selected
workspace. Set `COCO_WORKSPACE` to an explicit repository root for portable
installations; otherwise the app discovers the checkout from its executable.

## AI observation (optional, test)

The **AI-наблюдение** tab is a read-only local observer. It reads recent
server errors, `/health`, Docker container status and recent `admin_audit`
metadata, redacts secrets and writes findings to
`runtime-logs/ai-findings.jsonl`. Without an API key it uses local heuristics;
with a DeepSeek key in `%LOCALAPPDATA%\COCO\ai-watcher.json` it asks the model
to explain anomalies. It cannot read E2E message contents and cannot perform
administrative actions. See `desktop/ai-watcher/README.md`.

## First administrator enrollment

For an account not yet enrolled, choose the protection method. For an
authenticator, scan the one-time QR with a compatible app and confirm its current
code. For a paper signing key, save it separately and confirm the requested
proof. If both methods are chosen, finish both steps. An existing TOTP secret
cannot be shown again by the server. The paper signing key is distinct from an
authenticator recovery code.
