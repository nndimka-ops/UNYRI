# Rooms directory and protected admission v1

Implementation contract, 2026-10-30. Additive to `conference-contract.md` and
schema 53. Existing invite rooms default to hidden/invite. Existing 1:1 calls,
media encryption, moderation and eight-attempt recovery are preserved.

## User interface

Sidebar Rooms opens a full screen, not the old lobby dialog. Two compact tabs:
On this server / Mine. Search, refresh, create, join by invitation. A card shows
title, optional description, creator display name, count/capacity and quiet access
icons. Directory data contains no device roster, secret, media key or SFU token.
Only authenticated accounts on the selected server see the directory.

Creation and owner editing separate visibility (`listed`, boolean) from access
(`open`, `password`, `invite`, `invite_password`). Defaults remain false/invite.
An invite-only listed card explains that an invitation is required; knowing its
UUID is insufficient. Hidden rooms are absent from the directory. Hiding does
not revoke existing invitations. Password fields are secure and never prefilled
from a server value. Max participants is set at creation, 2..30. Entry always
starts with microphone/camera off. Existing admission_locked temporarily pauses
new entry. Title/description/visibility/access/password can be edited by creator.
Settings do not forcibly remove current participants or enable any media.

## Compatibility and metadata

Capabilities add `room_directory`, `room_access_v2`, `room_key_handoff` booleans.
Clients default absent flags to false; never send new fields to an old server.
Conference DTO adds description (default empty), listed (false), access_mode
(invite), settings_revision/access_revision (positive integers, default 1).
Unknown access modes fail closed, not open. Existing owned endpoint remains.

Create adds optional description/listed/access_mode/password. Description is at
most 240 Unicode scalars / 960 UTF-8 bytes with no controls; title rules unchanged.
Password is 6..128 Unicode scalars, at most 512 UTF-8 bytes, no controls. Preserve
password bytes exactly, including spaces; do not trim or normalize. Keep only
Argon2id hash server-side; password is never a media key or an encryption KDF.

PATCH settings adds expected_revision, description, listed, access_mode and
password_action (`keep`, `set`, `remove`), password only for set. New clients send
expected_revision; concurrent edits return 409 and reload. Legacy title/lock-only
patch remains allowed and cannot change access fields. Mode/changed password/
admission lock increments access_revision and cancels pending handoffs; other
edits increment settings_revision only. Access modes requiring a password must
have a hash. Leaving password modes clears the hash. Password hashes never leave
server. Changing access does not revoke already disclosed keys cryptographically.

GET /conferences/directory?q=&cursor=&limit= (default 20, max 40) returns
`{items:[DirectoryRoom],next_cursor:null|string}`. Stable keyset order; query bound
to cursor, bounded search, no offset scan. DirectoryRoom:
`{id,title,description,owner_user_id,owner_display_name,participant_count,
max_participants,access_mode,admission_locked,key_available,created_at,expires_at}`.
Exclude ended/expired/unhealthy/hidden and rooms blocked for this account/device,
including removed accounts. Never return invite_secret_hash or participant IDs.

## Separate Signal key mailbox: no chat messages

Public/password entry uses ONLY an exact-recipient Signal envelope, not ordinary
chat outbox, no DM creation and no new unknown message kind. Reuse normal Signal
identity checks, namespace and account/origin/epoch fences. Add an exact-device
encrypt helper; do not encrypt a room key for every device of the account.
Signal wire types are the existing 1=PreKey and 2=Whisper, standard padded base64.
GET /keys/bundles/:user_id/:device_id returns the existing UserBundlesResponse
shape with exactly the one active matching device. Apply existing authentication,
block and rate checks; filter BEFORE consuming any one-time prekey. Normal batch
and nonconsuming device-discovery endpoints remain unchanged. Exact-target helpers
observe the fresh nonconsuming target identity and establish only its session if
needed; never fall back to consuming/encrypting for the whole account on 404.
The target UUID, Signal device ID and identity key must remain identical between
nonconsuming discovery and the targeted prekey response; fail on a mid-operation
identity change rather than silently switching first-contact identities.
Conference HTTP clients reject redirects locally, as authentication/history
clients already do; a307/308 must never forward a password/capability to a new
origin. Media-download signed-URL redirect behavior is not changed. Password
inputs use secure keyboard configuration as well as visual masking.

Public recipients receive media key only, NEVER the shared invitation secret.
Their admission credential is a per-device/server-bound request ID. Otherwise a
public visitor could retain the secret and bypass a later invite-only setting.
Controllers therefore distinguish invitation credentials from catalog credentials;
never invent an empty/fake ConferenceInvite. Catalog credentials cannot be exported
as an invite card. The invite/share action requires an actual retained invitation.

POST /conferences/:id/key-requests body `{request_id,nonce,password?}`.
request_id is a fresh UUID; nonce is canonical unpadded base64url of 32 CSPRNG bytes.
Device/user/session-family come from authentication, never input. Allowed only
for listed open/password rooms with admission open and existing block/removed
checks. A valid invitation can still enter hidden rooms via ordinary /join.
Idempotent same ID/body; a different ID binding is rejected. Per-request TTL 60 s,
per-user/device/room limits, at most one live request for that device/room. A request
does not reserve a member slot or acquire local camera/microphone/call leases.

GET /conferences/:id/key-requests (provider poll) returns `{items:[KeyRequest]}`
max 8; only current authenticated membership with exact current admission identity.
New clients advertise supports_key_handoff=true in /join. Poll only after E2EE
connect success; every 2 s foreground / 5 s background with no overlapping calls.
This poll marks key-service heartbeat. Only opted-in, session-healthy members with
heartbeat in last 15 s may be providers; prefer creator, then stable oldest member.
Never assign legacy/nonready members. Provider assignment is bound to that admission
generation, not merely account. If provider departs/expires before fulfillment,
requester polling may reassign with a new assignment generation. No key on server
and no online key holder means waiting, then timeout with Cancel/Retry.

KeyRequest:
`{id,room_id,requester_user_id,requester_device_id,provider_user_id?,
provider_device_id?,provider_identity?,assignment_revision,nonce,access_revision,
expires_at,status,envelope?}`. Status pending/ready/consumed/cancelled/expired/revoked.
Only assigned provider sees pending target; only original authenticated requesting
device/session family can read the result. No envelope fan-out to its other devices.

POST /conferences/:id/key-requests/:request_id/fulfill body
`{assignment_revision,access_revision,envelope:{recipient_device_id,message_type,
ciphertext}}`. Authenticate exact assigned live provider/admission, recheck all
bindings, policy and expiry under lock. Recipient must exactly equal requester.
Only first fulfillment succeeds; identical retry is idempotent, different ciphertext
conflicts. Ciphertext <=8192 decoded bytes, canonical standard base64, types 1/2;
endpoint-only bounded body <=16 KiB (do not raise ordinary API limit globally).

GET /conferences/:id/key-requests/:request_id reads requester state/result;
DELETE same endpoint cancels that device's pending request. Poll at 2 s, bounded
60 s, cancel on account/origin/session change or explicit user cancellation.
Cancellation of an as-yet-absent request creates a short-lived tombstone bound to
that exact room/request/account/device/session family and returns idempotent OK.
POST checks this fence under the room lock both before and AFTER any async KDF,
so a late create cannot resurrect a cancelled request. Expire these tombstones;
do not use fake key-request nonce/ciphertext rows. Cancelling a consumed request
does not remove an already admitted member; ordinary /leave owns that teardown.
Do not decrypt same ciphertext twice: cache verified plaintext in RAM through
admission/recovery; restart a new request after process restart. Stale responses
may not change another screen's state or start a room after Cancel.
On a lost POST response, retry the SAME request ID/nonce/body or recover its GET,
not a fresh request. Bound network/5xx retries and respect429 backoff within the
same monotonic60 s deadline. Never retry Signal decryption of one ciphertext.

Signal-encrypted payload has EXACT fields:
`{version:1,origin,room_id,request_id,requester_user_id,requester_device_id,
provider_user_id,provider_device_id,provider_identity,assignment_revision,nonce,
access_revision,expires_at,media_key}`. media_key is the existing canonical
base64url 32-byte key STRING used by the SDK. Verify every binding, version,
canonical origin/IDs/base64, positive revision, expiry (<= request TTL), exact
selected account and sender device from authenticated mailbox before media setup.
REST expires_at uses the existing RFC3339 format. Encrypted payload expires_at
is integer Unix seconds and must equal floor(REST expiry seconds). It must be
strictly later than the local current second; its client-side upper bound is
local now +90 s (60 s TTL plus up to30 s clock skew). This does NOT extend the
server's actual60 s deadline or local monotonic60 s wait. Revisions are
positive signed 64-bit integers; first provider assignment has revision 1. A
pending request without a provider may have assignment_revision=0 and cannot be
fulfilled/decrypted yet.
Never log payload, envelope, password, SFU token or media key. Identity changes or
untrusted keys fail closed; never automatically accept changed identities.

## Join and recovery

POST /conferences/:id/join accepts exactly ONE credential: invite_secret OR
key_request_id. Adds optional password and supports_key_handoff (default false).
Invite entry still checks correct secret; password modes require password for a
NEW admission including old clients. Catalog entry requires ready request bound
to this account/device/session family, unexpired revision and open/password mode;
consume it atomically with membership/normal quota/block/session checks. A consumed
request may retry only the same existing admission/session family, not create a
fresh one after Leave. Catalog callers never obtain an invitation secret.
Persist the current catalog_request_id on membership without a foreign key, so
cleanup of temporary mailbox rows cannot break a legitimate refresh. Old consumed
IDs do not refresh a later/rebound admission. After process loss a different new
request B may rebind an existing same-device/family admission ONLY if B is freshly
ready and passes the complete current access/revision/TTL checks. Consume B and
replace the member's credential atomically; preserve its admission identity. A
former consumed/revoked ID is not such a fresh request and cannot rebind back.

Successful existing same-device/session-family membership refresh may reconnect
without re-entering a changed password or reusing an expired handoff. It must still
pass session/room/moderation checks and preserve exact admission identity. Ordinary
leave deletes this right; a fresh entry obeys current access policy. Do not bypass
new-admission password checks merely because invite_secret is known. Password KDF
work is bounded and outside room locks, then revision/hash rechecked under lock.

409 settings_changed reloads/restarts. Password-required/incorrect-password uses
403 and stable safe messages, never 401 (which means session authentication and
could spuriously log out the account). A ready catalog request already proves the
password at that access revision; do not require/store it a second time at join.
410 removed/ended remains terminal; transient 503 does not revoke live media.
Expired/cancelled/used handoffs and ciphertext are cleaned up. End/logout/revocation
invalidates pending handoffs. Existing media encryption precedes connection and no
public/password path ever allows plaintext media or server escrow.

## Validation and rollout

Additive migration 0054 only; bytes of 0001..0053 remain unchanged. Isolated real
PostgreSQL/Redis tests cover policy matrix, old /join bypass, session/device binding,
replay, revision race, blocked/removed peers, password rate/KDF bounds, stale provider,
expiry/cancel, handoff slot race and existing recovery/moderation regressions.
Include deterministic DELETE-before-INSERT/KDF races: A never becomes live after
cancel, fresh B succeeds, and no request belonging to another device is cancelled.
Both clients need payload vectors and state/late-response tests; Android real UI,
Signal and migration regressions. iOS portable tests are not Apple SDK evidence;
native build/render tests wait for the user-supplied Mac. No paid Actions/Mac rental.
Live rollout requires previous source provenance, verified DB backup and migration
guard; never auto-start a schema53 binary after 0054 or auto-restore a live database.
