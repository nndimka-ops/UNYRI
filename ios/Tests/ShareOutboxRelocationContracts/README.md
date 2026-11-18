Run `python3 ios/Tests/ShareOutboxRelocationContracts/run.py` with Swift and SQLite
available (or supply `--swiftc`). CI runs it with the other share contracts.

The runner compiles the production `MediaJob`, `loadMediaJob` and `prepareMedia`
methods verbatim, with the complete production MessageStore and model sources.
It persists a legacy absolute upload URL in real SQLite, physically moves the
account folder, and runs actual outbox preparation. The controlled uploader
opens the selected file and checks both its bytes and that the URL repair was
persisted before that read. It interrupts after the read, moves the container
again, then completes preparation, retaining caption, album and client identity.
An obsolete-container decoy must never be read. All existing media routes run.

Prepared voice cases also compile the production `resumeUpload`,
`referencesLocalFile` and `releaseMediaSource` methods against the real
AttachmentStore. After an iOS-shaped container move, replay must retain the
attachment-owned recording while retiring its upload journal, whether job
metadata was written before the move or not. Cleanup after the final attachment
owner is removed must still delete the recording. A stale upsert must not restore
an old-container attachment reference.

Store opening is synchronous. Source preparation/resume and final source cleanup
use the lazy background draft-reference gate; replay with already-prepared
metadata does not touch the plaintext or hold delivery behind that pass. The
runner extracts the production gate adapter as well as the real store. Separate
AttachmentCacheRelocationContracts hold a worker before commit to check narrow
readiness, prompt consumer cancellation, account retirement drain, and rollback.
The relocated prepared-voice fixture also executes synchronous restore-journal
replay before the lazy scan, ensuring it preserves the known original locally.

This is Foundation/SQLite replay coverage. Native image/video conversion,
encryption, networking and iOS update-container behavior remain Mac/device gates.
