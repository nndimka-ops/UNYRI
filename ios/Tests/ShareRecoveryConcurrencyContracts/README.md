Run `python3 ios/Tests/ShareRecoveryConcurrencyContracts/run.py` with Swift/SQLite
available (or pass `--swiftc`). CI runs it beside the share relocation contracts.
With Node 22.13+, `node ios/Tests/ShareRecoveryConcurrencyContracts/check_sql.mjs`
also executes the production SQL against separate live SQLite connections.

The runner extracts the production single-message ingestion, plaintext/metadata
persistence, MessagesRepository recovery/deletion callers and AttachmentManager
purge methods. It compiles the real
MessageStore, AttachmentStore, recovery owner and ShareJobProcessor. Signal is
the controlled boundary: each packet can be consumed only once.

Cases cover live metadata writes and ingestion while filesystem inspection or
pre-publication is held; a staged repair racing a live edit; real SQLite writer
contention and rejected writes after decryption; durable plaintext/journal replay
with reconstructed stores; and cancellation through the real share processor,
repository recovery entry point, composer staging and recent-copy entry point.
Cancelled consumers finish before the held pass is released. Another consumer
still uses the same recovery task. The migration contracts retain the explicit
retirement drain and atomic rollback/marker checks.

Deletion cases hold an incoming write in retry, then execute the production
MessagesRepository and AttachmentManager personal-clear and whole-chat purge
methods. Releasing the retry must leave no metadata/journal, including after
reopening the store. Newer messages still work after a personal clear. Other
cases hold a successfully inserted row and change the personal/server cutoff or
local visibility; ingestion must delete that late row before retiring its journal.
The SQLite supplement reproduces a stale chat check followed by deletion and
insertion on separate connections, using the production guarded insertion SQL.

Native Signal, UIKit background assertions and device SQLite latency still need
the iOS build and device walkthrough. These Foundation contracts are not that
native build evidence.

Fence compaction cases execute repeated production text-history clears and retain
one monotonic chat cutoff, preserve a single-message fence while its write/journal
is live, reclaim it after consumption, and let a whole-chat tombstone supersede
both message fences and cutoffs. The legacy launch case upgrades the previous
schema and sweeps 10,000 journal-free fences in bounded background batches. The
SQL supplement also repeats 10,000-ID clears and checks that stale replay stays
blocked after compaction while a new message may reuse the same attachment ID.
