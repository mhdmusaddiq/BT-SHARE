# Phase 2.3.1 — Server-paced realtime audio relay

**Reason:** A Redmi Note 9 Pro live test showed 1,215 translated audio
packets, no player underruns, and native PCM backlog 2,020ms. The Flutter
player intentionally stopped when its bufferedDuration() exceeded 2 seconds.
This is evidence of a growing playback buffer, not a lack of AI audio.

The `audio_stream_player` v1.1.0 documentation states its PCM buffer is
unbounded and recommends throttling when a source outruns playback. Merely
raising the phone's backlog threshold would conceal increasing translation
delay.

## Change

- Server now creates a **dedicated outbound audio pacing task** per active
  realtime voice session. The upstream AI event reader never awaits PCM
  playback pacing; it continues forwarding original/translated captions.
- First 24kHz mono PCM16 audio frame is delivered as soon as queued.
  Subsequent frames are sent no faster than their true PCM duration
  (9,600 bytes = 200ms). When a provider produces audio slower than realtime,
  the relay sends the next chunk promptly instead of doing catch-up bursts.
- PCM content and per-chunk ordering are preserved. Each phone frame retains
  the same protocol, format, sequence and validation contract.
- A bounded queue holds up to 60 frames (12 seconds of audio); persistent
  overload triggers an explicit error instead of deleting words or allowing
  unbounded memory growth. Backend overrun is a possible remaining condition.
- A sender lock serializes captions/audio WebSocket sends.
- On graceful upstream close, queued speech is drained (max 14 seconds).
  If the user explicitly stops/disconnects, teardown may discard pending
  audio intentionally. This is not a guaranteed gapless phone-call engine.

## Mode and rollback

Enabled by default for realtime voice:
```powershell
$env:BT_AI_PACE_OUTPUT_AUDIO = "1"
```
Rollback to the old immediate-burst relay:
```powershell
$env:BT_AI_PACE_OUTPUT_AUDIO = "0"
```
Apply the flag **before starting Uvicorn**. The ready handshake now reports
`output_audio_paced: true|false` (Flutter ignores the optional metadata).

For a fair test keep Flutter's prebuffer at 320ms:
```powershell
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_ADAPTIVE_STARTUP=true --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=320
```

## Verification

From project root, **after git pull**:
```powershell
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
```

Restart Python gateway with existing private credentials and the pacing
flag. Keep USB ADB reverse active. In the Flutter debug panel check
`Underruns`, `Audio→player`, red error text and subjective continuity
over at least 30–60s of speech including pauses. Compare against baseline
under similar conditions. Gateway timing for `gateway_first_audio_sent`
will now represent the first paced chunk (still prompt), not the whole burst.

**Limitations:** There is no production-grade audio/video clock sync or
voice rate conversion. A long, fast model output can still accumulate a
server-side queue and may eventually overload. Pacing avoids phone buffer
buildup but cannot make a 10-second generated utterance take less than
10 seconds to hear. This change does not implement consistent fixed-voice
TTS or speed up the provider's delayed caption text deltas.

## Tests

`gateway/test_audio_pacing.py` includes a virtual monotonic clock,
exact byte/sequence preservation, rollback and queue overload checks.
`gateway/test_realtime_translation.py` includes a fake 800ms PCM burst
followed by a caption and verifies that captions are not delayed behind
the final paced audio chunk.

These changes were pushed remotely; physical device and Windows test
execution are **pending user verification**.
