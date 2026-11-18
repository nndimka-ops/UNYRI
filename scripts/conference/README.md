# Native conference infrastructure

The clients use native LiveKit SDKs, not a web view or an embedded meeting site.
The existing private-call WebRTC engines and TURN deployment remain independent.

`prepare_isolated_sfu.py` provisions an owned QA instance on the Linux test host;
it does not enable the feature on the primary backend. Its generated credentials
The pinned LiveKit image is v1.13.11, repository digest
`sha256:25cd902de7cacbd5f1f779f6a6ac1eae20ba8f56b0935934ce6b0ab80d1e3d20`.

`Caddyfile.fragment` is a template, not a command to replace a site's full config.
The `/rtc` initial/reconnect gateway must deny stale/revoked participant admission
before forwarding WebSocket requests; the SFU administration API stays internal.
Disable API-created implicit rooms (`room.auto_create=false`) so an ended room
cannot be recreated with a previously issued/automatically refreshed SDK token.

Required checks before enabling the main backend:

* SFU image/config and loopback HTTP binding verified;
* backend tests including 30-user cap and private-call mutual exclusion;
* gateway denies missing, wrong, expired, left, revoked, and wrong-generation tokens;
* deleted rooms reject valid pre-deletion tokens, even without the gateway;
* microphone/camera media remain encrypted with client-only room keys;
* a second native/media client actually receives and decrypts audio/video;
* original HTTPS certificate and legacy `/ws`/attachment/call routes still work.

Synthetic room tests do not establish phone thermal performance or 30-camera
capacity over residential/mobile networks. Keep that distinction in release notes.
