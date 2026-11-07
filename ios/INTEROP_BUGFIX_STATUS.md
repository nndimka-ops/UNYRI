# Android / iOS interoperability bugfix status

This is the first critical bugfix stage from the user's 15-point report.
Physical acceptance is pending: no phone was attached during this pass.

## Implemented in this stage

- Voice envelope: Android writes `durationMs`; iOS incorrectly required `duration_ms`. iOS now writes the Android spelling, and both clients accept both spellings. Contract tests exercise voice metadata with encryption versions 1 and 2. Tink v2 ciphertext reading was already implemented and its real Android-generated fixtures remain in CI.
- Malformed attachment envelopes no longer fall back to raw text containing encryption metadata. Old voice envelopes saved as text are repaired when encountered again in history/sync, without replaying Signal ciphertext. This does not repair every offline cached row eagerly.
- iOS ingestion is serialized across WebSocket/history/sync to prevent simultaneous duplicate decryption. An unavailable token no longer advances the cursor past an unpersisted message. Partial successful batches still publish changes. Receipt requests no longer hold up the ingest queue and respect decryption gaps.
- WebSocket connection state waits for a successful ping/frame. Failed handshakes retain exponential backoff. Chat shows connection status and an empty state instead of a disconnected loading spinner.
- Unified Plan remote video callbacks now attach receiver/transceiver video tracks, in addition to legacy stream callbacks. Renderer reattachment is supported; nil frames do not count as a first frame and first-frame state is locked across callbacks.
- Search opens the real ChatScreen. Search rows already omit UUIDs. Caller names resolve through existing chat summaries; unknown callers show a generic label rather than a UUID on the production fallback paths. No call protocol change.

## Existing functionality verified by code inspection

- iOS already has a system file importer and the encrypted file upload path.
- iOS already has file preview/share, voice bubbles, retry states and a durable text outbox. These still need device-level acceptance, not just compilation.
- No application-owned FPS/GPU HUD was found in iOS sources. The reported overlay needs a screenshot/device inspection to distinguish an OS Metal HUD from app UI.

## Remaining subsequent stages

- Photo multiselect with preview, reordering, caption and explicit Send; camera attachment flow; replace immediate photo sending.
- Video message type and Android/iOS rendering together.
- Foreground in-app notification banner with chat navigation.
- Full runtime acceptance: voice v1/v2 both directions, live open-chat updates and lifecycle events, audio/video both directions, first frames, reconnect, offline pending sends, and file picker on iPhone.
- APNs for messages and PushKit/CallKit cold-start delivery remain unimplemented and require appropriate Apple provisioning and backend credentials to activate/test.

No claim of full Android/iOS parity or successful physical video delivery is made by this document.
