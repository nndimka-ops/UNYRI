# Fixture provenance

No fixture was captured from a live server. All identifiers, descriptions and credentials are synthetic.

- `users.json`: `server/src/admin.rs:1108–1159`, including legacy aliases `reason` for username and `description` for display name; unknown `sort_key` remains accepted.
- `reports.json`: `admin.rs:903–962`, with nullable review fields and reporter/subject identities.
- `feedback-detail.json`: `admin.rs:1172–1200`; descriptors, not inline screenshots.
- `summary-moderator.json`: `admin.rs:1381–1420`; storage is null for a moderator, never fake zero.
- `user-detail.json`: `admin.rs:1427–1575`; devices use id/display_name/last_active_at, not guessed wire names.
- `performance.json`: `server/src/performance.rs:live`; 24-hour retention.
- `server-info.json`: `server/src/server_management.rs:public_policy`; no server binary version exists in this response.
- `health-degraded.json`: `server/src/api/health.rs`; HTTP 200 can still mean degraded.
- `audit.json`: `admin.rs:1317`; cursor page with audit metadata.

`crypto-vector.json` was produced locally by `tools/DeriveCryptoVector.cs` linked to the **unchanged** `desktop/CocoAdminConsole/CryptoAdmin.cs` and its Bouncy Castle DLL. The first 30 alphabet symbols are deliberately public test material. The program derives the ten-bit checksum, SHA-256 seed, Ed25519 public key and signature for a nil-UUID/synthetic-nonce proof. The Kotlin test asserts every output, exact LF payload bytes and no trailing LF. Reproduce from the repository root (use absolute Windows paths for csc):

```powershell
$root=(Get-Location).Path
$csc=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
New-Item -ItemType Directory -Force admin-desktop/tools/build | Out-Null
& $csc /nologo /target:exe /codepage:65001 ('/out:'+(Join-Path $root 'admin-desktop\tools\build\DeriveCryptoVector.exe')) ('/reference:'+(Join-Path $root 'desktop\lib\BouncyCastle.Cryptography.dll')) /reference:System.Web.Extensions.dll (Join-Path $root 'desktop\CocoAdminConsole\CryptoAdmin.cs') (Join-Path $root 'admin-desktop\tools\DeriveCryptoVector.cs')
Copy-Item desktop/lib/BouncyCastle.Cryptography.dll admin-desktop/tools/build
& ./admin-desktop/tools/build/DeriveCryptoVector.exe (Join-Path $root 'admin-desktop\core\src\test\resources\fixtures\crypto-vector.json')
```

The independent Rust verifier vector is copied from `server/src/admin/signing.rs:199–202`. Tests verify it and reject a modified message. Full-proof tests also reject changed purpose, nonce and auth-session. This proves algorithm/payload compatibility, not a live enrollment or deployed server version.
