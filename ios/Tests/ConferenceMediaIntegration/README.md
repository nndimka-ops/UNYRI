# Native synthetic conference media probes

These macOS SwiftPM probes use the same patched local LiveKit SDK as the iOS
application. They open no camera or microphone and save only aggregate metadata,
frame counts and audio signal energy. They are independent of physical phone,
SwiftUI rendering, Bluetooth and 30-HD-camera performance checks.

Build with Xcode's Swift toolchain:

```
swift build --package-path ios/Tests/ConferenceMediaIntegration --jobs 4
```

Run `ConferenceMediaProbe /protected/config.json /owned/result.json`. Keep token
and key configuration files private (0600); never print their contents. Each
receiver writes `result.json.ready` after connecting. The benchmark writes that
marker after all 30 participants have published. Coordinate sender startup with
these markers rather than depending on chat/model timing. Test fixtures may use
longer-lived synthetic tokens; application admission tokens retain server policy.

Publisher configuration has `url`, `token`, `key`, optional `phaseSeconds` (4 by
default), and `audio` (true by default). It produces 160×120 BGRA buffers and a
1 kHz sine wave, publishes, republishes video, then requests full SDK reconnect.
The reconnect check requires a new publication SID. A simultaneous correct-key
and wrong-key receiver must count source content; decoder black/silent teardown
placeholders do not count as leaked media.

Optional `forceRelay: true` installs the SDK's explicit `.relay` transport policy
before connecting, without overriding ICE servers or credentials returned by
the SFU. The publisher requires a succeeded selected pair with a matching local
candidate of type `relay` during initial publication, republication and full
reconnect. Results include only candidate types/protocols, nomination, byte
counts and RTT; candidate addresses, ports, IDs, ICE URLs, credentials and
certificate material are omitted. The receiver mode supports the same option
and verifies a selected relay path before reporting completion. Bench mode is
not used for this network-isolation probe.

Receiver configuration adds `mode: "receiver"` and `durationSeconds` (60 by
default). It records nonblack I420 video frames, sine-correlated audio buffers,
maximum PCM RMS and GCM/plaintext publication counts. Correct-key content must
arrive; a wrong key must yield no source video or tone. The receiver uses the
actual SDK receiver-cryptor and track-enable sequence.

Benchmark configuration is:

```
{
  "mode": "bench",
  "url": "wss://owned-qa-host.example",
  "key": "[canonical 32-byte base64url test key]",
  "participants": [{"token": "[test grant]", "identity": "[grant identity]"}],
  "durationSeconds": 15,
  "remoteSubscriptions": 6
}
```

The participants array must contain exactly 30 distinct admitted identities. The
probe connects and publishes four clients at a time, produces video only at
160×120/5 fps with VP8, no simulcast, and an 80 kbps ceiling. Each room subscribes
to the next six (optionally up to eight) participants in a ring. Counters use
native video callbacks without GPU rendering. Success requires all 30 connected
and publishing, every expected remote peer delivering at least five nonblack
decoded frames, and zero plaintext publications or subscription failures. This
is an SFU/native SDK capacity layer, not a physical iPhone frame-rate benchmark.

Fresh-admission recovery uses a protected config with `mode: "fresh"`, `apiURL`
(HTTPS origin), `accessToken` (fixture user's app API token), `inviteSecret`,
`roomID`, `key`, optional `phaseSeconds` (default five), `audio`, and `startFile`.
It performs a real authenticated `/join`, publishes generated media, invokes the
real SDK recovery entry point with zero retry attempts, requires disconnection
without republication, then performs another real `/join` and publishes through
a new Room and key provider. Output includes only ordering flags/counts, never
credentials, identities or URLs. Observe simultaneous correct/wrong-key readers
across both publication generations. This is simulated transport recovery over
real native SDK media; it is not a physical iPhone background or camera test.
