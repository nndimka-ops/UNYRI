# COCO conference rooms v1

Implementation contract, 2026-10-29. This is independent of the existing 1:1 call API.

## User flow

Sidebar -> Rooms -> Create room / Join by invitation. Create generates two
independent CSPRNG 32-byte values on the phone: an invitation secret and a media
encryption key. No microphone/camera starts before permission and explicit join.
Sharing an invitation is an explicit user action. Joining requires an authenticated
account on the same selected server. Do not retarget a logged-in account from a link.

An invitation is an absolute URL:

`coco://conference?server=<percent-encoded canonical HTTPS origin>&room=<UUID>#v=1&i=<secret>&k=<key>`

Both secret and key are canonical unpadded base64url encodings of exactly 32 bytes.
Reject duplicate/unknown security fields, credentials, non-HTTPS server origins,
noncanonical encodings, unexpected path/port changes, and mismatched account origin.
Do not fetch a link or send its fragment to a server. Never log the full invitation,
authorization headers, SFU tokens, invitation secret, or media key. Active room keys
used by the active SDK remain in memory, scoped to the account, selected server,
and conference; erase those active copies on leave/logout/origin change. An explicit
chat invitation may retain its bearer capability inside the ordinary Signal-
encrypted message and the account-private message/history store, just like a file
decryption key. It never enters diagnostics, generic message previews or REST.

## API (authenticated existing device session)

* `GET /conferences/capabilities` -> `{enabled,max_participants:30,e2ee_required:true,host_controls:true}`.
* `POST /conferences` body `{title,max_participants,invite_secret_hash}` -> Conference.
  Max participants is 2..30; hash is lowercase hex SHA-256 of the decoded invitation
  secret (32 bytes), not of its base64 spelling. Creation does not join the owner.
* `GET /conferences/:id` -> Conference, owner/current member only.
* `PATCH /conferences/:id/settings` body `{title?,admission_locked?}` -> Conference,
  owner only; see the owner room controls section for compatibility and limits.
* `POST /conferences/:id/join` body `{invite_secret}` -> JoinResult.
* `POST /conferences/:id/leave` -> `{ok:true}`.
* `POST /conferences/:id/end` -> `{ok:true}`, owner only.

Conference: `{id,title,owner_user_id,max_participants,admission_locked,state,created_at,expires_at,participants}`.
State is `active` or `ended`, dates are RFC3339. A participant is
`{user_id,device_id,display_name}`. New rooms have a hard lifetime of 24 hours from creation. Leaving does not extend the deadline; older rooms retain their original server-issued expiry.

JoinResult: `{conference,livekit_url,participant_token,participant_identity,token_expires_at,e2ee_required:true}`.
Never accept `e2ee_required:false` or a public/insecure SFU URL. The room key is not
part of any API request/response. Participant tokens grant only joining their one
room, publishing/subscribing audio/video and required SDK data, no administration,
recording, arbitrary room creation, or metadata edits.

## Media interoperability

Use the LiveKit SDK frame encryption on both platforms. The input to the SDK's
shared-key provider is the exact canonical base64url `k` STRING, in UTF-8, using
matching PBKDF2/key-provider defaults and salt `LKFrameEncryptionKey`. Do not use
decoded raw bytes on one platform and the string on the other. Encryption must be
configured before connect/publication and must fail closed. Do not publish media
until the key provider is ready; reject unencrypted remote media.

## Access, lifecycle, and layout

Existing 1:1 and group calls share microphone/camera exclusivity on the device and
server admission policy. One account/device admission per room; admission identities
include a fresh generation, preventing replay after voluntary leave/rejoin.
Joining is transactional with the 30-user cap and pairwise account/installation
block checks. Native media participant IDs, not display names, are stable grid IDs.

Ordinary leave disconnects only that participant; the invitation remains usable.
The initial v1 has no individual removal; the optional participant moderation
extension below adds account-scoped transport removal, not media-key rotation.
Owner end revokes the whole room. Security events (logout/device revocation/block/suspension) close the
affected room until a future group rekey protocol can exclude a participant safely.
The UI explains closure without leaking blocked identities. No recording is enabled.

LiveKit must enforce `room.auto_create=false`. Its signaling is exposed only through
a gateway authenticating every initial/reconnect `/rtc` request against the backend
`/conferences/authorize-sfu`; the Twirp administration API remains loopback-only.
Forward query tokens to an overwritten `X-Conference-Token` header, never to a traced
backend query string. Server flags acknowledging these controls alone are not proof;
deployment tests must check denied stale tokens, ended rooms, and replay attempts.

Show at most six tiles per portrait page and nine per landscape page initially;
remaining participants are reachable by pages. Support a pinned participant and
speaker highlight without moving tiles on every audio level. Adaptive subscriptions
only decode visible video; use simulcast/dynacast and constrain camera resolution.
Full 30-camera real-device capacity requires a separate network/thermal/load test.

## Controlled transport recovery

An active COCO controller retains its invitation, media key, account/server scope,
media intent and exclusive call lease in RAM during transport loss. Native SDK
automatic republishing is disabled for this app. Recovery fully stops/disposes the
old media session, obtains a new authenticated `/join` grant, creates a fresh Room
and key provider, installs frame encryption, then restores authorized capture.
There is no `/leave` between recovery attempts. Intentional leave/logout waits for
pending admission and media teardown before releasing the final membership/lease.

Both clients bound an outage to eight attempts and at most 120 seconds. Ten seconds
of stable connection resets that shared budget; rapid connect/drop cycles do not.
Closed/expired/revoked admissions and crypto failures are terminal. Transient HTTP
or network failures may retry; no recovery path may enable plaintext fallback.
On iOS camera restoration additionally requires the app to be foreground-active.

A successful same-device/same-session-family `/join` refreshes handshake grace
atomically, preserving the current participant identity. Reaping serializes with
signaling authorization. An authoritative SFU RoomService `not_found` closes the
lost room and releases its slots instead of silently recreating it. Ambiguous
proxy errors, authentication failures or network failures are not evidence that
the media room has vanished.

## Compact invitation v2 (2026-10-29 UI follow-up)

Keep the strict legacy v1 reader. New producers emit `coco://c#<payload>`; the
payload is canonical unpadded base64url. It remains in the URL fragment, never
the path or query. No URL fetch, link preview or automatic account/server switch
is permitted. The full legacy invitation remains an explicit fallback export.

Binary layout (network byte order):

- Byte 0: version `2`.
- Byte 1: origin mode, `0` or `1`; reject any other value.
- Mode 0: full 32-byte SHA-256 of the ASCII canonical shared public default origin.
  Resolve only that known origin and verify the fingerprint; no server directory.
- Mode 1: unsigned 16-bit big-endian origin byte length (1..256), then that many
  strictly decoded UTF-8 bytes forming the canonical ASCII HTTPS origin.
- Both modes continue with RFC4122/network-order UUID bytes (16), decoded invite
  secret (32), decoded media key (32). Require the exact remaining length.

Mode 0 has exactly 114 bytes, producing a 161-character link. Reject malformed
UTF-8, noncanonical origin/base64, padding, additional URI fields, unknown modes,
truncation and trailing bytes. V2 additionally rejects an all-zero secret or media
key and equal keys; retain legacy v1 behavior. Neither 256-bit secret is shortened.
Clients still compare the reconstructed origin with the explicitly selected
account server before admission. Shared synthetic vectors live in
`shared/conference-v2-vectors.json`.

## Encrypted chat invitation card

Use existing authenticated `kind: "text"` and the existing Signal outbox/store.
Do not introduce a transport message kind or server invitation table. Android may
add a nullable local `conferenceInviteJson` column with a tested additive Room
19-to-20 migration; all existing rows remain unchanged with NULL. iOS retains its
existing encoded-content persistence. Never overload the frozen outbox/edit payload
text to smuggle invitation JSON. Account cleanup and encrypted history export/import
must include the new local field without exposing it in generic previews.
The harmless `text` fallback is a localized invitation label (no capability URL,
secret or media key). Add one optional `conference_invite` object to content:

```json
{
  "version": 1,
  "id": "canonical-lowercase-UUID-generated-once-per-logical-send",
  "title": "Conference title",
  "server": "https://canonical-origin.example",
  "room_id": "canonical-lowercase-room-UUID",
  "invite": "coco://c#canonical-v2-payload",
  "expires_at": 1793289600
}
```

`expires_at` is the server room's expiry as integer Unix seconds, only a UI hint;
the existing join API remains authoritative. Title is a trimmed single line,
1..80 characters, <=320 UTF-8 bytes, with control characters rejected. Validate
the metadata version, canonical IDs/origin, size bounds, positive expiry and the
embedded link. Its reconstructed room/origin must equal the outer metadata.
Count title length as Unicode scalar values/codepoints (the server's character
count), not UTF-16 units or grapheme clusters; reject Cc controls and U+2028/U+2029.
Expiry must be an integer in 1..253402300799 (through year 9999), not a bool,
float or decimal string; never multiply unchecked seconds into milliseconds.
Past expiry is structurally valid; render an expired state without automatic join.
Both valid v1 and v2 embedded invitations may be read; new producers use v2.
An otherwise valid card for another selected server remains an inert card with
a clear server-mismatch instruction, not a malformed or auto-retargeted invitation.
Malformed/unknown metadata must show harmless fallback content, never a join
button or raw capability. Metadata debug descriptions must redact the invitation.

The optional object must survive enqueue, serialization, authenticated encoding,
network retry, receiving, local persistence, forwarding and encrypted backup.
Round-trip and actual outbox-composer tests must cover this (not just UI mocks).
Older clients may ignore it and display the fallback label. Replies, chat lists,
notifications, search and diagnostics use that label, not the capability. Explicit
Copy invitation/Forward are intentional bearer sharing. Disable text editing on
an invitation rather than retaining hidden metadata under unrelated edited text.

Receiver card: icon, title, encrypted-conference label, expiry/status and two
actions, **Accept / Decline**. Selection mode must select the message instead of
starting admission. Accept is an explicit action, validates current owner/origin
and enters with microphone and camera off; busy/full/expired/blocked failures
remain visible and retryable where appropriate. Preview/copy/render never joins.
Android retains its existing microphone foreground-service model: RECORD_AUDIO
permission is required for admission even when transmitting muted. Explain that
audio is not transmitted until the user enables the microphone. A denial must
leave the invitation retryable. Permission callbacks remain owner/epoch/origin
fenced. This pass does not claim permission-free Android listening and does not
change service types or the proven SDK publication/recovery sequence. Android's
[microphone foreground-service prerequisites](https://developer.android.com/about/versions/14/changes/fgs-types-required#microphone)
are a platform constraint, separate from the muted UI state.
If this is already the active room, Accept returns to it instead of admitting twice.
Do not persist success before admission succeeds.

Decline is an account/server/message-scoped private local decision, retained over
app relaunch and erased by the existing owner-fenced logout/account purge. It does
not revoke a bearer capability and must not claim to notify the inviter. Allow an
explicit later Join action. Never add server-side invitation tracking just for UI.
Sender card uses Sent / Invite again semantics and no receiver Accept buttons.
Legacy standalone valid v1/v2 links remain usable via explicit invitation preview.

The in-room Invite picker supports searching and selecting multiple existing chats,
then one Send(N) action. Enqueue one ordinary encrypted card per chat with bounded
work and owner/epoch checks; do not start 29 uncontrolled network sends. Distinguish
queued from server-confirmed delivery, retain failed targets and avoid duplicating
already queued cards when retrying the batch. Never automatically ring/join invitees.

## Shared compact conference presentation

Use the existing COCO dark theme and accent categories on both platforms. No live
blur or continuous decorative animation. Controls respond in 120..180 ms and obey
Reduce Motion/system animation settings; haptics are limited to deliberate actions.

Shared normal-size visual metrics (dp/pt): header 52 high, grid gap 8 and tile
corner 18; dock horizontal inset 10, corner 24, padding 8; control visual circle
44 with 22 icon and 10 short label in portrait. Hit areas remain >=44 and may be
48 on Android. Use adaptive gap <=8 and wrap/collapse secondary controls into More
on narrow/accessibility layouts instead of shrinking Leave/Mic/Camera hit areas.
Minimized return strip is 48 high with 8 outer inset. Own preview is 104x144 in
portrait or 112x84 landscape, corner 16, clamped to the video area; store normalized
position and clamp after geometry changes. No fixed pixel coordinate persistence.

- Header: minimize chevron on the left, title/status plus total count in the
  middle, participants on the right; More appears only in the dock (no duplicated
  action). Interactive targets >=44 dp/pt.
- Default grid: remote participants only, stable arrival order. At most six tiles
  per portrait page and nine landscape; accessibility large text reduces to two
  or three. One remote uses the available area; small groups use larger tiles.
  In portrait one/two remotes use one column, three or more use two. Landscape
  uses at most three columns, bounded by the visible count.
- Horizontal swipe plus accessible previous/next and a visible `1 / N` indicator.
  Use simple left/right chevrons, not a send/forward glyph. Key native tile views
  by participant identity so a single departure does not recreate every renderer.
  Preserve the selected remote anchor/page, pin and own-preview position across
  minimize/return and rotation; clamp safely on participant changes.
- Participants opens a searchable complete directory (including self), with mic/
  camera state. Tapping a remote can focus/pin it and return to its grid page.
  All 30 people are reachable without decoding all 30 cameras simultaneously.
- Own video is a separate draggable preview on both platforms; keep it within
  safe content bounds and away from paging/dock controls, preserve/clamp position.
- Compact dock: microphone, camera, audio route, invite, More and a prominent red
  leave control. Use icons, short labels where space permits; landscape remains
  compact. Switch camera and owner End for everyone belong in More; ending for all
  requires confirmation and is distinct from leaving. Accessibility may wrap rows.
- Minimized presentation is a small unobtrusive return chip/strip with conference
  name, count and connection/microphone status, >=44 dp/pt return target. Reserve
  layout space rather than covering chat/header/composer controls. Keep the same
  live controller, key and audio; subscribe to no remote video while minimized.
- Background/lifecycle cleanup is owner/presentation fenced: an old disappearing
  view cannot clear a newer view's subscriptions or release another capture owner.
  Camera restoration remains foreground-only on iOS. Keep-screen-on is owned only
  by a visible active video presentation and restored on dismissal/leave.

Preserve 8-attempt/120-second fail-closed recovery, call/capture exclusivity and
the ordinary 1:1 CallKit/audio paths. No claim of 30 physical cameras or hardware
thermal performance may be derived from layout tests or synthetic SDK streams.

## Owner room controls (2026-10-29, schema 52)

`GET /conferences/capabilities` adds `host_controls: true` on supporting servers.
Clients decode a missing flag as false and hide these controls on older servers.
`ConferenceDto` adds `admission_locked: false` by default; older responses without
the field are decoded as false. Existing fields, invite formats and media-token
grants are unchanged.

The active account that owns the room may send:

```http
PATCH /conferences/{id}/settings
Authorization: Bearer <existing account access token>
Content-Type: application/json

{"title":"Friends","admission_locked":true}
```

Either field, or both, is accepted. An empty/all-null request is rejected with
400; unknown fields and wrong JSON types use the existing JSON-extractor 422
response and are rejected before mutation. Title is
trimmed, 1..80 Unicode scalar values/codepoints, at most 320 UTF-8 bytes, without
Cc controls or U+2028/U+2029. Create and rename share these exact limits.
No participant limit, key, microphone, camera or member-removal field is accepted.
The response is the complete current `ConferenceDto` (same shape as details/join).
No new data is put into encrypted invitation cards; their title is a send-time
label, while admitted room metadata is authoritative for the current title.

Owner mutations share the room row lock with join/end and recheck the active
account session/read-only policy after waiting for locks. Non-owners, including
current members, receive 404. Expired/ended/first-join-timeout rooms return 410;
settings cannot revive a room or extend its expiry. Revoked sessions and read-only
accounts keep the existing 401/403 errors. Authorization/security changes return
the existing 409 room-closing error rather than weakening the room health gate.

When `admission_locked` is true, NEW non-owner admissions with an otherwise valid
capability return 409 with code `conflict` and the exact message
`Conference admission is locked.` Invalid invitation secrets still return 404.
The check occurs under the same room lock as insertion, preventing a stale-policy
join after the lock commits. A previously admitted same-device/session-family
member can refresh/reconnect with exactly the same participant identity, even at
capacity. The owner may return to a locked room. A member who explicitly leaves
or whose stale admission has been authoritatively reaped no longer holds that
admission and needs the room reopened to join anew.

Locking/renaming does not contact the SFU, remove participants, change invitation
verifiers, replace media keys, alter existing token grants or interrupt media.
This is admission control, not revocation of a previously shared bearer key.
Individual transport removal is separate from these settings and is described
below. Cryptographic exclusion of former key holders requires a separate
authenticated group-key rotation protocol. Forced remote unmute is unsupported.
The existing owner End for everyone remains the immediate whole-room revocation.

Migration `0052_conference_host_controls.sql` adds one non-null boolean defaulting
to false; all previous rooms retain their existing unlocked admission behavior.

## Participant moderation extension (schema 53)

Compatibility is explicit: capabilities adds `participant_moderation:true` only
on the new server. Old clients may ignore new fields; new clients default a missing
capability to false and hide owner actions. Participant objects include the exact
`participant_identity` (`user:device:admission`, canonical UUIDs) and a boolean
`audio_publish_blocked` (missing means false on legacy servers). Never derive an
identity from a display name or reuse an older admission generation.

Owner-only endpoints:

- `POST /conferences/:id/participants/:user_id/audio`
  body `{participant_identity,blocked:true|false}` -> latest Conference.
- `POST /conferences/:id/participants/:user_id/remove`
  body `{participant_identity}` -> latest Conference.

Unknown fields/types are rejected. Non-owner receives 404; self-target receives
400 (own Mic/Leave remain available). Stale target generation receives 409;
removed account, ended or expired room receives terminal 410. A durable pending
policy returns retryable 503 (not terminal 409) so bounded client recovery can
wait for the worker/sweep to finish. A repeated removal
of the same already-removed identity is idempotent. A valid current account
session, write permission, room health and owner checks remain mandatory. These
actions cannot alter a participant in another room or a new admission generation.

Global audio restriction is a room/account publishing policy, not a one-time
UI command: persist it before SFU I/O, apply LiveKit `UpdateParticipant` microphone
source restriction, and mint camera-only participant grants on every rejoin.
Camera/subscription permission remains available; data publishing remains denied.
The signaling gateway rejects an unrestricted old grant while the policy is
blocked. Empty/omitted source lists must NOT be interpreted as no permission:
LiveKit treats them as all sources. Clients clear desired mic state and stop
local capture upon authoritative block or SDK permission revocation. Removing
the restriction only permits the user to explicitly enable their microphone;
the server never calls remote unmute and the client never restores mic
automatically simply because permission returned.

Removal persists a room/account ban before removing its exact SFU identity. It
denies the same account's later device/session login and old invitation, removes
its current membership only after confirmed SFU removal, and rejects old/refreshed
SFU tokens at every gateway connect/reconnect. The owner and other participants
remain connected on the normal success path. A newly registered different account
is not the same account ban; this does not claim device-wide identity tracking.

The already-shared E2EE media key and invitation secret do not rotate in this
extension. Server-enforced access removal stops future transport delivery through
the trusted COCO gateway/SFU; it cannot erase earlier recordings, received media,
or the key already known to a removed device. It is NOT forward secrecy or
cryptographic exclusion against an SFU that deliberately supplies ciphertext to
that former key holder. No plaintext fallback or key material is sent to REST.
Logout/device revocation/account block still retains the existing stronger
whole-room closure policy.

A durable pending flag survives a crashed API worker; pending/banned admissions
cannot obtain a new grant or pass signaling authorization. The five-second sweep
retries pending operations and checks the SFU roster for an old handshake that
was authorized immediately before a policy commit. Such an in-flight handshake
may finish after the operation and is removed by reconciliation; there is no
claim of instantaneous atomicity spanning PostgreSQL and SFU transport. An SFU
mutation/removal failure returns 503, persists the policy and moves the room to
`ending`; existing closure reconciliation retains busy slots until DeleteRoom
is confirmed. If roster verification fails, re-enforce every durable removed
identity and every blocked user's CURRENT admission directly regardless of
`needs_sync`. Successful direct enforcement preserves the room through an ordinary
roster read failure; inability to confirm that enforcement triggers the same
fail-closed closure. A failed roster read must not silently forget a previously
successful ban or apply a block only to an old admission generation. No success
is shown for a failed moderation RPC.

Local actions require no endpoint: mute received audio / hide received video
affect only that viewer's subscriptions/playback, preserve everyone else's mic
and camera, and remain separate from owner actions. Both clients reset these
local choices with their existing account/server/room owner lifecycle.

## Owner room management extension

Capabilities adds `owner_rooms:true` (missing means false on older servers).
`GET /conferences/owned` returns a direct array `[Conference,...]` of at most
three non-ended rooms created by the authenticated account, newest first,
including previously used but currently empty rooms. It does not require a
current media admission and preserves blocked-profile visibility restrictions.
No invite secret/hash, media key, capability link or participant token is returned.
The client may show **My rooms / End for everyone** with confirmation, using the
existing owner-only `POST /conferences/:id/end`. It must not invent a Resume/Join
action from metadata when it no longer has the capability/key.

Room creation still caps three non-ended rooms and returns the existing 409
`End an existing conference before creating another.` The UI explains that an
old room needs ending and offers the list. Ordinary Leave does not silently
invalidate shared invitations; the new list resolves owner quota without weakening
the cap, shortening invitations or replacing encryption keys.
