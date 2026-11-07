# iOS call foundation — bounded five-minute increment

Implemented: typed audio/video call REST transport for create, active lookup (including JSON null), per-call lookup, accept/reject/cancel/hangup/connected/failed, and temporary ICE credentials. Account-scoped repository checks cancellation and session generation before requests and after responses. Added microphone/camera permission handling; audio does not request camera permission. Added required usage descriptions and Xcode source membership.

Contract checks in GitHub Actions cover ringing/ended, nullable active call, audio/video, and STUN/TURN credentials. Compilation and execution results must be checked in Actions; there is no local Xcode on this Windows computer.

Not yet implemented: WebRTC media engine, WebSocket SDP/ICE transport, call UI, camera capture/switching, audio routing/mute, CallKit, PushKit/APNs. The new foundation is not invoked from the UI yet. It does not place or accept real calls and must not be presented as functioning audio/video calling.

No added third-party packages or binary media resources. Physical acceptance remains Android-to-iPhone audio/video, decline/cancel/hangup, permission denial, camera off/switch, speaker/Bluetooth, account changes, and background/locked-screen calls after APNs/CallKit integration.
