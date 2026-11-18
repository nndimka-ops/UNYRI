# Running server tests locally

## Server security batch 3a

`server_security_batch3_test` requires a **fresh disposable** database named
`server_sec3_test`, explicit loopback TEST URLs and a separate Redis DB. It has
15 regressions covering call endpoints, request retries, expiry/lock waits,
transactional push rollback, read-only teardown, installation identity, key
publication, message retries, terminal revocation and owner deletion. It never
connects to object storage or a live push provider. Run it alone and serially;
one case installs a scoped failure-injection DB trigger, and owner cases sweep
due test accounts. Recreate the DB before repeating after a failed run.

From the repository root, after creating that disposable database:

```powershell
$env:CARGO_TARGET_DIR = 'D:/cargo-target-sec'
$env:TEST_DATABASE_URL = 'postgres://coco:test@127.0.0.1:55432/server_sec3_test'
$env:TEST_REDIS_URL = 'redis://127.0.0.1:56379/8'
cargo test --manifest-path server/Cargo.toml --test server_security_batch3_test -j 2 -- --test-threads=1
```

Batch 3a also extends `users_test` with stale admitted-session avatar completion
and compatibility PATCH coverage, and `admin_console_test` with real-factor
refresh/handoff continuity, logout refusal and validation across a rotation lock
wait. Give each suite its own fresh DB/Redis and use the storage setup below.
Exact lead commands and the affected existing regression suites are in
[SERVER_SEC3_REPORT.md](../../SERVER_SEC3_REPORT.md). They were written/compiled
in this worktree; Docker-backed execution is intentionally left to the lead.

Run commands from the repository root. Use a disposable PostgreSQL database and
Redis database: tests migrate and write data, and some suites sweep global rows.
Never point these commands at application data. Rust/Cargo, the platform C/C++
linker, and the locked crates are required. No running application server is needed:
the tests construct real routers in process and open local HTTP/WebSocket listeners.

## Existing local services and users_test

The test services used here are already running; these commands do **not** start
or stop containers. The setup script prefers host MinIO `mc` on PATH (or the path
given by `-McPath`); without it, it uses `docker exec ut-minio mc`. Docker and a
running container containing `mc` are required for that fallback. It fails clearly
if no client is available. The script creates only a test bucket, IAM user and policy.
In PowerShell:

```powershell
$env:MSYS_NO_PATHCONV = '1'
$env:CARGO_TARGET_DIR = 'D:\cargo-target\unyri-server'   # one target directory per worktree
$env:TEST_DATABASE_URL = 'postgres://coco:test@127.0.0.1:55432/coco_test'
$env:TEST_REDIS_URL = 'redis://127.0.0.1:56379/1'
. ./scripts/qa/Initialize-TestStorage.ps1
cargo test --locked --manifest-path server/Cargo.toml -j 4 --test users_test
```

Both client modes can also be selected explicitly:

```powershell
# Host client (if installed); supply its executable path as needed.
. ./scripts/qa/Initialize-TestStorage.ps1 -McPath 'C:\tools\mc.exe' -Endpoint 'http://127.0.0.1:59000'
# Force the existing container client, even if host mc is installed.
. ./scripts/qa/Initialize-TestStorage.ps1 -Container ut-minio -Endpoint 'http://127.0.0.1:59000'
```

In container mode, `mc` uses `http://127.0.0.1:9000` **inside the container**, with
`--config-dir /tmp/unyri-testhealth-mc`. The generated upload-only policy is copied
with `docker cp` to `/tmp/unyri-testhealth-upload-policy.json` before being applied.
`-Container <name>` selects a different already-running MinIO container. In both
modes, `-Endpoint` must remain a loopback HTTP host endpoint; the exported
`S3_ENDPOINT_URL` stays at that host URL (normally port 59000). No containers are
created, started or stopped. Versioning and upload-only user/policy checks are the same.

Dot-source the script so its `S3_ENDPOINT_URL`, `S3_UPLOAD_ACCESS_KEY_ID` and
`S3_UPLOAD_SECRET_ACCESS_KEY` remain in the test shell. If local PowerShell script
execution is disabled, use a fresh `powershell -NoProfile -ExecutionPolicy Bypass`
shell for these commands. MinIO is `127.0.0.1:59000`, with test admin credentials
`messenger_minio` / `messenger_minio_secret`. Its bucket is
`messenger-attachments-test`; the separate `unyri_test_upload` user has only the
repository's PutObject policy, with an explicit GetObject/GetObjectVersion deny.
Bucket versioning must never have been enabled; suspending it is insufficient.
The Rust fixture verifies this before serving requests and owns a real 30-second
HTTP ingress for presigned requests. Missing credentials fail setup explicitly.

Independent users fixtures serialize because the production avatar decoder has
two process-wide slots and correctly returns 503 when busy. Concurrent requests
within the race tests remain concurrent. Assertions and server capacity are unchanged.

## All ordinary integration suites

For the other upload suites, start a **local process** and retain its process
object so shutdown targets only this fixture. In PowerShell, from the repository root:

```powershell
$testStorageCaddy = Start-Process -FilePath caddy -ArgumentList @('run', '--config', 'scripts/qa/test-storage.caddy', '--adapter', 'caddyfile') -WindowStyle Hidden -PassThru
```

After testing (including failed runs), the exact stop command is:
`Stop-Process -InputObject $testStorageCaddy`. The suite loop below does this in
`finally`. For a foreground `caddy run --config scripts/qa/test-storage.caddy
--adapter caddyfile` on macOS/Linux, press Ctrl+C in that process's terminal.
This fixture forwards to the existing MinIO, preserves the signed Host header,
limits upload reads to 30 seconds and upstream response headers to 30 seconds.
It uses HTTP/1.1. See [Caddy timeout semantics](https://caddyserver.com/docs/caddyfile/options#timeouts).
Then, after the setup above, set:

```powershell
$env:S3_ENDPOINT_URL = 'http://127.0.0.1:59001'
$env:S3_MAX_UPLOAD_DURATION_SECONDS = '60'
```

Do not declare a bounded deadline against raw MinIO. Run suites sequentially and
use one test thread for these older fixtures, which share global policy/decoder
capacity. `-j 4` still controls build parallelism. This loop discovers every normal
suite and stops on failure:

```powershell
$special = @('self_hosting_test','auto_delete_test','blocked_users_test','chat_archive_test',
    'direct_transfers_test','history_backups_test','resource_bounds_test')
try {
    Get-ChildItem server/tests/*_test.rs | Where-Object BaseName -notin $special | ForEach-Object {
        cargo test --locked --manifest-path server/Cargo.toml -j 4 --test $_.BaseName -- --test-threads=1
        if ($LASTEXITCODE -ne 0) { throw "Failed: $($_.BaseName)" }
    }
}
finally {
    if (-not $testStorageCaddy.HasExited) { Stop-Process -InputObject $testStorageCaddy }
}
```

This covers `admin_auth_policy_test`, `admin_console_test`, `admin_pack2_test`,
`appearance_test`, `attachments_test`, `auth_test`, `calls_ringing_test`,
`calls_ringing_off_test`, `calls_test`, `chats_test`, `client_config_test`,
`contacts_test`, `devices_test`, `display_names_test`,
`first_contact_envelope_coverage_test`, `keys_test`, `lifecycle_test`, `messages_test`,
`presence_test`, `privacy_test`, all nine `push_*_test` suites, `quota_runtime_test`,
`receipts_test`, `saved_chats_test`, `scale_test`, `security_event_emission_test`,
`security_events_scope_filter_test`, `security_events_test`, `sync_test`, and `users_test`.
The two ringing suites are separate binaries because their kill switch is cached
per process. Push tests use fixture providers; the live OAuth suite skips by default.

## Suites with additional isolation requirements

Use the same Cargo command as above, replacing the suite name and setting the
following variables first. Create the named databases with `psql`, for example
`psql postgres://coco:test@127.0.0.1:55432/postgres -c 'CREATE DATABASE auto_delete_test'`.
Use separate disposable Redis database numbers and do not run these concurrently
with other suites.

| Suite | Required setup / exact command differences |
|---|---|
| `admin_auth_policy_test` | Explicit TEST URLs; database name must contain `test` or `qa`. The local `coco_test` URL above qualifies; its role needs CREATE DATABASE permission for the suite's extra isolated databases. |
| `auto_delete_test` | `TEST_DATABASE_URL=postgres://coco:test@127.0.0.1:55432/auto_delete_test`, `COCO_AUTO_DELETE_TEST_ISOLATED=1`, separate `TEST_REDIS_URL`; run with `--test-threads=1`. |
| `chat_archive_test` | Database suffix `/coco_chat_archive_qa`, `COCO_CHAT_ARCHIVE_TEST_ISOLATED=1`, separate Redis; run with `--test-threads=1`. |
| `history_backups_test` | Database suffix `/coco_history_backups_20260926`, separate Redis; run with `--test-threads=1`. The suite also owns fake HTTP storage servers. |
| `direct_transfers_test` | `DIRECT_QA_DATABASE_URL=postgres://coco:test@127.0.0.1:55432/coco_direct_qa_local`, `DIRECT_QA_REDIS_URL=redis://127.0.0.1:56379/2`; run with `--test-threads=1`. |
| `blocked_users_test` | **Container lifecycle:** `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qa/Test-BlockedUsers.ps1`. It creates randomly named PostgreSQL/Redis containers on dynamic ports, sets `COCO_BLOCKED_TEST_ISOLATED=1`, requires the `blocked_qa:fixture` user and `/coco_blocked_test` database, then stops only its owned containers in `finally`. `-Regression` additionally starts MinIO and runs related suites. Do not run this helper when container lifecycle is prohibited; do not spoof its isolation flag against the shared services. It was not run for test health. |
| `resource_bounds_test` | Dedicated PostgreSQL **55439**, database `/conference_test`; Redis **56439**; MinIO **9000**. Set `COCO_SECURITY_TESTS=1`, `DATABASE_URL`, `REDIS_URL`, `S3_ENDPOINT_URL=http://127.0.0.1:9000`, `S3_PUBLIC_ENDPOINT_URL` to the same URL, test root/upload credentials and ingress deadline. This suite uses `Config::from_env`, not TEST URLs, and deliberately varies storage faults/versioning. Run `cargo test --locked --manifest-path server/Cargo.toml -j 4 --test resource_bounds_test -- --test-threads=1` only on those dedicated fixtures. |

## Unit, ignored and live tests

`cargo test --locked --manifest-path server/Cargo.toml -j 4 --lib` runs all default
library tests; `--bins` checks binary test targets and `--doc` checks doctests.
Integration suites requiring isolation are **not** automatically skipped by
`cargo test`: the blanket command will fail their guards unless configured.

To enumerate ignored cases without running them, use the relevant command with
`-- --ignored --list`. Explicit opt-ins:

| Checks | How to run / requirements |
|---|---|
| 10k-message scale | Standard services; `cargo test --locked --manifest-path server/Cargo.toml -j 4 --test scale_test -- --ignored --test-threads=1`. |
| Runtime quotas | Fresh isolated test DB/Redis, bounded MinIO fixture and upload credentials above; `cargo test --locked --manifest-path server/Cargo.toml -j 4 --test quota_runtime_test -- --ignored --test-threads=1`. Mutates global server policy. |
| Firebase OAuth/send | `FCM_SERVICE_ACCOUNT_FILE` (or `GOOGLE_APPLICATION_CREDENTIALS`); send also requires `FCM_PROJECT_ID` and `FCM_TEST_DEVICE_TOKEN`. `cargo test --locked --manifest-path server/Cargo.toml -j 4 --test push_oauth_live_test -- --ignored --test-threads=1`. The send probe contacts a real device; opt in deliberately. |
| Conference library integration | `CONFERENCE_TEST_DATABASE_URL` on 127.0.0.1:**55439** with `/conference_test`, `CONFERENCE_TEST_REDIS_URL` on **56439**; `cargo test --locked --manifest-path server/Cargo.toml -j 4 --lib conferences::integration_tests -- --ignored --test-threads=1`. Deletes conference fixture rows. Python candidate runners under `scripts/conference/test_*candidate.py` additionally manage owned Docker services; review before use. |
| Payload database contracts | `PAYLOAD_TEST_DATABASE_URL` on **55449** with `/payload_test`, `PAYLOAD_TEST_REDIS_URL` on **56449**; `cargo test --locked --manifest-path server/Cargo.toml -j 4 --lib database_payload_tests -- --ignored --test-threads=1`. |
| Redis performance / key discovery | `scripts/qa/Test-PerformanceRedis.ps1` and `scripts/qa/Test-KeysDiscovery.ps1` create and stop owned Redis/PostgreSQL containers and set nonce-checked fixture variables. These library tests remain ignored outside their helpers. |

Expected skips are only the declared ignored cases above. Database, Redis, MinIO,
policy, credentials and migration failures must fail with evidence; do not turn
them into ignored tests or successful results.

## Security batch 2 (17 Nov)

Use `CARGO_TARGET_DIR=D:/cargo-target-sec` and `-j 2` for this worktree. New
regressions are in `chats_test`, `receipts_test`, `auth_test`,
`admin_auth_policy_test`, and `attachments_test`. They cover blocked clear/retry
scope, retained admin report context after real account deletion, non-content
receipts, every delivery emitter and HTTP reconciliation, independent read
receipts, distributed/concurrent account reservations and proved-installation
exemption, localized common-password rejection, factor lockout/audit, IP limits,
and actual storage headers/signed-query tampering. Run sequentially with
`--test-threads=1` using the isolated services and bounded storage fixture above.
Use a generated fixture-only `COCO_ADMIN_KEY` for administrator tests; never
load a deployment `.env` or the owner's key. The batch worktree's
`SERVER_SEC2_REPORT.md` lists the additional existing regression suites.

## Self-hosting SH-2 (17 Nov)

`self_hosting_test` is excluded from the ordinary loop: run it alone against a
fresh local database whose name starts with `sh2_`. It mutates global bootstrap
and registration policy. It explicitly refuses non-loopback PostgreSQL/Redis,
does not load dotenv, uses its own fixture-only admin key, disables push and
creates a loopback read-only storage stub (no MinIO needed for this suite).

```powershell
$env:CARGO_TARGET_DIR = 'D:/cargo-target-sec'
$env:SELFHOST_SH2_TESTS = '1'
# Create a fresh sh2_* database in your local test Postgres first.
$env:TEST_DATABASE_URL = 'postgres://coco:test@127.0.0.1:55432/sh2_contract'
$env:TEST_REDIS_URL = 'redis://127.0.0.1:56379/1'
cargo test --locked --manifest-path server/Cargo.toml -j 2 --test self_hosting_test -- --test-threads=1
```

Do not run this suite against an existing fixture database. Recreate the
disposable database before rerunning. No container is started/stopped by the
suite. It covers code generation/log-once/restart/rotation, setup file failure,
IP/account throttles, concurrent owner claims, CLI-path retirement, invite
defaults/permissions/last-use races/rollback/revoke/expiry, policy switching,
diagnostic success/outage and console authorization/CSRF. Offline console
interaction/localization tests need only Node: `node --test
server/console/self-hosting.test.cjs` (Node 18+; this PC has one under the Codex runtime folder).

Give every regression suite its own fresh database and Redis index. Running several suites in one
database makes later suites fail for reasons that are not theirs: on 17 Nov `users_test` failed 11
avatar cases with HTTP 503 after the admin suites had run in the same database, and passed 32/32 on
a fresh one. `lifecycle_test` fails 11 reaction/delete cases with 409 on a fresh database as well
(known since 7 Nov: empty-ciphertext delete bodies), so treat only new failures as regressions.
