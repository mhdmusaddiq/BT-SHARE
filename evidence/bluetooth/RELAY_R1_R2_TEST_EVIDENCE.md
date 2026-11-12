# Member 2: R1/R2 Three-Phone Relay POC Evidence

**Prepared:** 2026-11-12
**Status:** NOT_RUN_PHYSICAL — Do not mark PASS before measuring on 3 phones.
**Git branch:** poc/bluetooth-relay
**Owner:** Member 2

## Authoring environment (external, not user's Windows)

- Kotlin 21 runtime/compiler: pure portable `RelayWire.kt` + `PcmClipProtocol.kt` compiled successfully.
- Self-test: `PASS: 18 Kotlin protocol assertions` (wire encode/decode, adjacent frames, hop/TTL, dedup, corrupted/truncated frame rejection, 160 KB audio reassembly, out-of-order and CRC rejection).
- No Android SDK in authoring environment; APK compilation NOT RUN here.
- RFCOMM and 3-device audio test NOT RUN physically here.

## Local Windows build

- Date/time: 2026-11-12 07:08:07 +05:30 (Asia/Colombo)
- JDK/Android SDK: Microsoft OpenJDK 21.0.12.1; Android SDK Platform 36; Build Tools 36.0.0.
- Verification command: `gradlew.bat --no-daemon --offline --rerun-tasks :protocol:test :app:assembleDebug` using the existing Gradle 8.13 cache.
- `:protocol:test` outcome: **PASS** — 22 tests, 0 failures, 0 errors, 0 skipped (PacketCodec 11, RelaySession 7, RttTracker 4).
- `:app:assembleDebug` outcome: **PASS** — `BUILD SUCCESSFUL in 1m 47s`; 39 actionable tasks executed.
- APK: `experiments/bluetooth/app/build/outputs/apk/debug/app-debug.apk`; 962,152 bytes; SHA256 `27FE4A92A5B9921D8AD21933F52CB318ABF04BEB9E1801A2D8ACC76117DB6A58`.
- Packaged-manifest verification: original `MainActivity` and separate `meshpoc.RelayDemoActivity` are both present; `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and `RECORD_AUDIO` are present.
- Compilation/fix details: added the Android 12+ `BLUETOOTH_SCAN` manifest/runtime request required by `BluetoothAdapter.cancelDiscovery()`; no compile errors. One non-blocking warning remains for the deprecated legacy `AudioTrack` constructor.
- Verification note: the kit helper's first attempt was blocked from downloading Gradle inside the sandbox and printed metadata for a stale pre-existing APK. That output was rejected. The results above are from the subsequent direct offline command with an explicit exit-code check and a newly written APK.
- Relay physical behavior: **NOT_RUN_PHYSICAL**. Android compilation and JVM tests do not establish A→B→C RFCOMM or audible three-phone relay success.

## Physical test (fill with actual measurements)

- Phone A model / Android / role HOST:
- Phone B model / Android / role RELAY:
- Phone C model / Android / role LISTENER:
- A paired with B: YES/NO
- B paired with C: YES/NO
- C server LISTENING log:
- B upstream+downstream concurrent socket log:
- A to B socket connected log:
- TEXT sent / B forwarded / C received:
- E2E ACK received at A / RTT (same A clock):
- 5-second PCM recording local audible at A:
- B forwarded AUDIO_START / AUDIO_CHUNK (40) / AUDIO_END:
- C complete clip bytes/CRC/chunk count:
- C speaker clearly audible: YES/NO (tester initials / time)
- Missing/duplicate/out-of-order chunk count (verified):
- Disconnect/recovery observation:
- Audio delay measurement method and actual values (do not infer from separate device clocks):
- Evidence screenshot/log filenames (redact MACs):

**R1 Three-phone text physical PASS?** NOT_RUN_PHYSICAL
**R2 Three-phone audio physical PASS?** NOT_RUN_PHYSICAL
**Opus?** NOT_IMPLEMENTED
**Automatic mesh?** NOT_IMPLEMENTED
**Automatic backup route/failover?** NOT_IMPLEMENTED

## Physical Validation Update - 2026-11-12

Devices: Three Android phones, roles A (Host), B (Relay), C (Receiver).

- T1 Connection: PASS - A to B to C connected.
- T2 Text forwarding: PASS - C received text; B text display pending.
- T3 Recorded audio: PASS - C played received voice; B local playback pending.
- T4 Packet integrity: PASS - user-confirmed.
- T5 Distance: PASS reported by user; exact tested distances and maximum reliable range not yet documented.
- T6 Failure/recovery: DEFERRED to Build 2.

Validated: Three-phone text and recorded PCM audio forwarding.
Not validated: Live streaming, relay local playback, Opus, automatic mesh routing, automatic failover.
Earlier NOT_RUN_PHYSICAL records describe the pre-test state.
