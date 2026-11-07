# Focused iOS CallKit audit

Baseline audited: 9f27a55. GitHub Actions run 34305063076 completed successfully: 10 named contract verification steps, unsigned Xcode build, IPA packaging and artifact upload. This is build evidence, not physical call acceptance or a percentage of parity.

Fixed in this pass:
- CallController and CallKitCoordinator session guards compared a captured generation to itself. They now read the live auth generation through CallsRepository.
- End/mute actions from a previous system call could operate on the current controller state. Validate the action's mapped backend call ID; add regression contract cases.
- In-app Answer bypassed CallKit despite WebRTC manual audio mode. Route it through CXAnswerCallAction.
- Account view teardown did not explicitly release call media/provider/timers. Add local shutdown and discard late callbacks after teardown.
- Reject a second queued outgoing transaction while the first is pending.

Confirmed remaining gaps:
- No PushKit/APNs VoIP integration; a foreground CallKit build does not establish killed-process/locked-screen incoming delivery.
- iOS attachment v2 decryption explicitly throws unsupported; Android's default Tink v2 interoperability is incomplete.

Further call risks requiring another implementation pass and device testing:
- CallController effect batches are independent Tasks; ordering inside a batch does not serialize separate batches.
- CallKit unsupported held/group/DTMF and timed-out actions currently fulfill without implementing the action; outgoing connected/connecting reporting and video-update reporting need review.
- System incoming-report errors are ignored; the in-app fallback with manual audio needs an explicit failure strategy.
- WebRTC audio-session ownership and background signaling must be checked on an actual iPhone, including Bluetooth, interruption, logout, and camera teardown.

This is a focused source audit with targeted fixes, not a full Android/iOS parity acceptance. Read the new workflow result before installing; perform Android-to-iPhone incoming/outgoing audio/video, in-app/system answer, old-call actions, logout midcall, and permission-denial tests.

## Follow-up fixes

- Report outgoing connecting/connected timestamps to CallKit once per call; ICE reconnection preserves the system duration.
- Advertise negotiated video mode and disable unsupported hold/group/DTMF controls; reject unsupported actions.
- Do not fulfill an already timed-out CallKit action, including late async completions (Apple CXProviderDelegate contract).
- Remove the second POST cancel previously scheduled by the reducer alongside the direct request.
- Ask for camera permission before outgoing audio-to-video upgrade, and recheck call identity/session after permission prompts for incoming accept and video upgrades.
- Speaker changes no longer activate AVAudioSession independently when CallKit owns activation.

User confirmed free Sideloadly signing only. PushKit/APNs deployment remains blocked by the missing push-capable signing configuration; no unsupported push entitlement was added to the sideload build.

## File UI follow-up

Ordinary file bubbles previously rendered a download icon without a working action. Added download buttons, cached local paths, Quick Look preview by tapping the filename, and ShareLink for decrypted files. Concurrent taps no longer start duplicate UI download tasks. Only image attachments are loaded into UIImage; arbitrary downloaded files are not decoded as images. This does not add Tink v2 support.

## Quick iOS regression pass

Fixed terminal-state identity loss on microphone/camera permission denial, failed incoming acceptance and answered_elsewhere. These paths now publish an ended state so CallKit can close its system call; permission denial also rejects the server call. A stale accept-success cannot replace another incoming call. Added reducer regression checks.

Fixed overflow-prone voice waveform arithmetic and randomized hash-based bars with stable bounded wrapping arithmetic; added extreme-index checks. Corrected Int64 duration printf formatting.

Still outstanding: Tink v2 interop, PushKit/APNs signing/deployment, effect-batch serialization and complete device regression. This increment must not be described as completing those larger items.

## Nonresponsive call buttons — device-reported regression
The built Info.plist lacked UIBackgroundModes/voip. Apple documents this as a common cause of CallKit's unentitled transaction error. Start/answer/report errors were silently discarded, matching the user's report of buttons doing nothing. Added voip/audio modes, observable error alerts, explicit CallKit injection into the full-screen call UI, and permission preflight before the outgoing CallKit transaction. Packaging now asserts voip/audio in the built Info.plist. These modes do not replace APNs or enable incoming wake-up after process termination.

The suspected root cause is grounded in the missing configuration and Apple's documentation; confirmation on the user's iPhone remains necessary.
