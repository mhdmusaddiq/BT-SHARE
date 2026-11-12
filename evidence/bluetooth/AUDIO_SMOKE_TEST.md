# Two-phone Bluetooth audio smoke test

## Status

`NOT_RUN_PHYSICAL`

No claim of successful phone-to-phone playback is made until the debug APK is installed and
tested on the HONOR X6c and A17. Automated protocol tests and APK assembly are development
checks only; they do not replace the physical smoke test.

## Intended devices and roles

- Phone A: HONOR X6c — record and send the 5-second voice sample.
- Phone B: A17 — listen as RFCOMM server and play the received sample through its speaker.
- Reverse the roles after the first successful run.

## Preconditions

1. Pair the phones in Android system Bluetooth settings.
2. Install the same `app-debug.apk` on both devices.
3. Grant Nearby devices permission on both phones and microphone permission on the recording
   phone.
4. Set the receiver's media volume to an audible level and avoid Bluetooth audio accessories
   during the first test.

## Smoke procedure

1. On Phone B, open BT-SHARE and tap **Listen as server**.
2. On Phone A, tap **Refresh paired**, select Phone B, and tap **Connect**.
3. Send a B0 text message in each direction. Verify matching DATA/ACK sequence values and an
   RTT entry on each sender.
4. On Phone A, tap **Record Voice**, speak normally for 5 seconds, and wait for `Recording
   ready` in the log.
5. Tap **Play Local Recording**. Verify the captured voice is intelligible on Phone A.
6. Tap **Send Audio**. Verify Phone B reports receiver playback and emits the voice through
   its speaker.
7. On both phones, record the AUDIO_START/AUDIO_END stream ID, chunks, bytes, media duration,
   missing chunks, duplicates, out-of-order chunks, underruns, and any errors.
8. Repeat once while tapping **Stop Audio** mid-stream; verify playback stops without freezing
   the UI or losing the Bluetooth connection, then send a text message again.
9. Disconnect, reverse phone roles, and repeat steps 2–8.

## Pass criteria

- Both directions preserve B0 text, ACK, and RTT behavior before and after audio.
- A normal 5-second stream is audible and intelligible on the receiving phone.
- Normal completion reports 160,000 PCM bytes, 40 chunks at the default 4,096-byte chunk size
  (39 full chunks plus one 256-byte chunk), and zero missing/duplicate/out-of-order chunks.
- The read loop and UI remain responsive during playback.
- Stop, disconnect, and truncated-stream paths release microphone and speaker resources and
  allow reconnecting.
- No one-way latency claim is derived from clocks on different phones.

## Observed results

Physical execution on the HONOR X6c and A17 is pending.

Development verification on 2026-11-12 with JDK 21:

- `:protocol:test`: PASS — 22 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug`: PASS.
- APK: `experiments/bluetooth/app/build/outputs/apk/debug/app-debug.apk`
- APK size: 928,056 bytes.
- APK SHA-256: `AA43FDC75A70EAC6A2B0ED20C44C91D633B2AEC408A2F525D6DB10D7D9464E9E`
