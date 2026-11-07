> Historical engineering note from the COCO development period. Current UNYRI build instructions are in [README.md](README.md); recorded legacy identifiers and results below are preserved.

# iOS remaining-feature completion

## Profile bio editor
iOS now edits and clears bio with a 160 Unicode-scalar limit matching Rust, a live counter, and tri-state PATCH semantics (unchanged/clear/set). Profile forms are disabled while saving. Profile/search/avatar responses are rejected after logout or account replacement. CI covers bio boundaries, composite emoji, and JSON PATCH encoding. Avatar upload/editor and full audio-route selection remain outstanding; this is not full Android parity.

## Manual server
Login and registration share an editable server field. A validated submitted address is saved across relaunch/logout. Bare hostnames default to HTTPS; local HTTP requires an explicit http:// address. Credentials, query strings, fragments and base paths are rejected because the backend routes are root-based. Changing server clears session tokens/cursor; Signal storage adds a server prefix except for the original server's legacy namespace. SQLite and attachment caches already include server identity.

## Call effect ordering
Async reducer batches use one FIFO queue across suspension points. Teardown cancels queued work and releases media immediately. Successor batches wait for prior SDK callbacks to return, and stale/cancelled SDP is not transmitted. Dedicated async tests exercise blocked initialization, queued offer cancellation, and successor call ordering.

## Android attachment v2 reader
Supports COCO Android's per-attachment single enabled RAW AES256_GCM_HKDF_1MB keyset (Tink Android 1.8.0). Uses CryptoKit AES-GCM/HKDF-SHA256 with Tink framing according to https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming and Tink protobuf definitions. Not a general Tink keyset implementation: other templates/multi-key keysets are rejected. iOS uploads remain v1, already supported by Android.

Checks every segment tag, final-segment flag and ciphertext SHA-256; stages plaintext privately until full success and deletes partial output on failure. Bounded segment memory. Synthetic test fixtures were generated using the project's actual Tink Android 1.8.0 jar and Generate.java. No fixture keys belong to users. Fixtures are test-only, not part of the IPA. CI decrypts 0/1-byte, exact first-segment boundary, boundary+1 and multi-segment samples; rejects corruption, wrong keys, malformed keysets, trailing data and truncation at a complete nonfinal segment.

## Still requires external setup / acceptance
PushKit/APNs cannot be deployed with the user's current free Sideloadly signing. A push-capable provisioning profile and server APNs credentials are required; none were fabricated. Physical Android/iPhone regression is still required. Avatar upload/editor, comprehensive audio-route selection, and WebRTC diagnostic stats are outside these three fixes and are not claimed complete.
