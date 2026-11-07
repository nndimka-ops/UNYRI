# iOS Rooms directory: local evidence and remaining native checks

Run `python ios/Tests/ConferenceDirectoryContracts/run.py` from the repository.
The executable compiles the actual Foundation models and REST request methods
against an explicit in-memory transport. It includes every shared payload vector
in `shared/conference-discovery-v1-vectors.json`.

Related bounded Windows runners:

- `ConferenceHandoffContracts/run.py`: the actual requester orchestration, with
  explicit API/Signal seams; cancellation, lost POST acknowledgements, same-ID
  retry, server cancellation tombstones, stale accounts/assignments, one decrypt.
- `ConferenceExactKeysContracts/run.py`: the actual repository and REST methods;
  strict non-consuming discovery, exact-device bundle consumption, no mailbox
  legacy fallback, cooldown, duplicate/missing target and stale-account guards.
- `ConferenceAuthTokenContracts/run.py`: actual HTTP error forwarding and auth
  token-fence methods. A late S0 rejection cannot erase refreshed S1 in the same
  local generation; matching-bearer revocation still clears the session. The
  in-memory seams explicitly exercise an in-flight refresh and absent account.
- Existing lifecycle, host/participant controls, recovery, presentation and
  subscription runners cover the preserved ownership/consent behavior.

These tests do **not** exercise libsignal, LiveKit, Apple frameworks, a real
server, microphone/camera capture, an iPhone or SwiftUI rendering. A syntax parse
also does not replace an Apple SDK build.

`ConferencePresentationHost/generate.py` prepares a native simulator fixture
using production SwiftUI views and explicit account/media/transport boundaries.
Eight UI tests are prepared, including the native access form and the first-tap
microphone restriction explanation. They remain unrun for this source revision.

Required Mac validation before publishing an iOS release:

1. Build the full application against its actual Apple SDK, libsignal and
   vendored LiveKit package, and run the presentation fixture in portrait,
   landscape and large text.
2. Verify directory/create/edit forms, four access modes, CAS conflict draft
   preservation, password-required/incorrect messages and invitation-only hints.
3. Run real exact-device encrypted handoff in both directions with Android;
   verify no chat is created, no invitation secret is delivered and all entry
   capture starts off. Test cancellation/retry, policy mutation, reassignment,
   identity change, disconnection and timeout against the deployed protocol.
4. Exercise owner restriction and lift, participant removal, fresh-card retry,
   catalog-admission recovery and returning after minimization.
5. Measure actual Signal work on the existing serialized main-actor stores;
   provider polls are sequential, bounded to eight and yield between requests.
   Do not move stores across actors without a separately validated ownership
   design.

No Actions job, Mac rental, signing or IPA creation is part of these local
source-level runners.
