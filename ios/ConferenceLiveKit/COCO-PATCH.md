# Pinned LiveKit media encryption patch

This package contains the production sources of LiveKit Swift 2.19.0, upstream
commit `43c1c0870e51eedbfd95b20b1b5c43a767886786`.
`UPSTREAM.json` records each original source checksum; `COCO-E2EE.patch` is the
complete source difference. LICENSE and NOTICE are preserved. The package
manifest includes only production targets and the exact upstream binary versions.

Upstream attaches frame cryptors through asynchronous publication delegates. A
source can therefore reach an already active sender before its cryptor exists.
COCO creates an RTP transceiver with a disabled inert placeholder, installs and retains an
enabled required GCM cryptor on that sender, and only then assigns the capture source
track. All these steps run synchronously on the RTC executor before negotiation.
The placeholder has the real track ID for SDP correlation and no real capture
source. Native RTCFrameCryptor needs it to determine audio/video kind; a nil
track is rejected by its constructor. The SDK's initial publish, new microphone/camera publication and full reconnect
republish paths share this code. Failure to create/enable a cryptor throws before
any source is assigned. The existing private-call WebRTC module is unchanged.
Secondary backup-codec senders are rejected before creation in encrypted rooms;
the conference uses universal VP8 with its normal primary sender and simulcast.

On reception, the native onTrack callback disables the new media track before
its asynchronous delegate handoff. The subscription path installs an enabled
GCM receiver cryptor before `Track.start()` enables reception. Later publication
delegates reuse the existing cryptors, avoiding a second unprotected replacement.
Plaintext magic suffixes are forbidden and unready cryptors discard frames.

The application configures shared-key PBKDF2, salt `LKFrameEncryptionKey`, index
zero and the canonical base64url key string as UTF-8. There is no plaintext
fallback. The source patch needs to be reevaluated when updating LiveKit.

The executable synthetic fixture is in `../Tests/ConferenceMediaIntegration`.
It sends generated color buffers and a generated sine wave, republishes the
video source, and triggers a full SDK reconnect. A correct-key and wrong-key
receiver must be observed concurrently; compilation alone does not verify media
confidentiality or cross-platform interoperability.

The conference controller now owns network recovery and foreground capture.
`ConnectOptions.reconnectAttempts = 0` closes a failed transport without running
the SDK's detached full-reconnect republication. Upstream `AsyncRetry` otherwise
invokes its final operation even for zero attempts. The SDK default of ten
attempts and the original full-reconnect probe remain unchanged. Each app
recovery obtains a fresh authenticated grant, creates a fresh Room/key provider,
and restores user media choices through one serialized capture task.

Capture startup and its cancellation check now run inside `_publish` rollback,
with another cancellation check before publication is committed. A camera or
microphone that finishes starting after cancellation is stopped even when no
publication exists yet for app teardown to enumerate. These changes are included
in the complete six-file patch and retain the original upstream checksums.
