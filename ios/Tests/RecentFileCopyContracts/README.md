Run `python3 ios/Tests/RecentFileCopyContracts/run.py` on a host with Swift.
The runner compiles the actual Recent files worker, bounded document copier and
ownership scope without UIKit. Only account-folder lookup is stubbed; all file
copying and cancellation code is production code.

Contracts cover copying off the main thread, index reads while a copy is blocked,
the captured destination account, bump-before-copy ordering, byte preservation,
source ownership until worker completion, cancellation acknowledgement, errors,
and cancellation before work starts. `WorkspaceContracts` separately uses the
real SQLite store to verify source references prevent retirement during copying
and cancellation, survive conversation purge, and expire after process restart.

On Mac, rerun both suites and build the iOS target. On iPhone, enable direct
sending, paste up to ten Original videos below 100 MiB each, and tap Send.
Verify the UI and cancellation stay responsive, Recent files retain valid copies,
removing a tile while copying causes no missing-source error, and account switching
drains the old copy without writing into the new account or resuming a cancelled Send.
