Run `python3 ios/Tests/SharePhase0Contracts/run.py` from the repository root.
On Windows, the runner also finds the Swift 6.3.3 toolchain under LOCALAPPDATA.

The runner checks native call sites (including the real auth switch and both
account-purger paths), en/ru/uk catalog parity and the privacy manifest. It then
compiles the production shared store and extracted production picker/inbox methods
against Foundation-only native boundary fixtures. Contracts cover quarantine,
explicit Import/Discard, interrupted adoption, account switches versus logout,
late commits after removal/re-login, valid ready-slot limits, chunk cancellation,
synchronous staging removal and abandoned-stage recovery. No app data is accessed.

Last-account erasure includes a newly created root-level staging directory with
plaintext and no manifest. Async discovery uses the real serial executor and
extracted inbox model. A deterministic worker gate holds I/O in progress while
the main actor handles an account switch, same-account session-generation change,
device lock, Close, and logout. Contracts reject stale snapshots, check that the main actor
remains responsive, and ensure cancelled publication cannot recreate account
permissions after erasure. Tests inject only the temporary root and UIKit state;
discovery/recovery/store logic runs unchanged.

A second gate suspends after the worker has read an empty snapshot, before UI
publication. A real staged batch commits and a burst of refresh events must cause
exactly one rescan and publish the new batch. A failed protected read followed by
an unlock event must rescan too; a failure without a newer event must stop.
Extension contracts hold setup I/O on its own queue while the UI actor processes
Close, reject staging creation from a result cancelled before/after return, and
check synchronous cleanup after successful staging ownership. The runner inserts
only the deterministic after-read pause and existing copy hook into test sources.

One after-write hook cancels a real streaming copy without depending on scheduling
or disk speed. Darwin also unlinks its staging tree while the copy's handle is
open; Windows covers unlinking while a provider has not resumed. On Mac, a second
binary compiles the real ImageProcessor preflight and reads actual GIF/WebP,
mislabeled GIF, multi-frame TIFF and still PNG bytes at the 25/100 MB boundaries.

Mac/device QA must still build both targets, check image formats and provider
callbacks, share offline, close during a large copy or slow provider, switch A to B
and back after a partial import, log out/remove A while its extension is copying,
recover/discard legacy shares, send 30 MB GIF/WebP originals successfully, test
Ukrainian and mixed counts over 10, validate the privacy manifest in the archive,
and measure idle CPU in Instruments. The SHA-256 known
answer also runs on Mac (CryptoKit is unavailable in the Windows toolchain).
