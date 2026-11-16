# Phase 2.3.5 — Controlled PCM continuity experiment

**User observation (16 November 2026):** Tamil and English captions are complete,
English translated voice starts about two seconds after speech, but voice has
audible gaps and some spoken English words are missing. Red overflow error no
longer appears. This means connection and translation work, not that audio
completeness is proven.

## What is changed

The existing Python realtime audio bridge may now split generated 24kHz
mono PCM16 into either **200ms frames (default, 9600 bytes)** or **100ms
frames (opt-in, 4800 bytes)**. Both settings send *the exact same PCM bytes*
in order, with a duration-aware pacing worker and bounded 12-second queue.
All existing original and English captions remain independent and unchanged.

- `BT_AI_OUTPUT_FRAME_MS=200`: existing behavior / rollback.
- `BT_AI_OUTPUT_FRAME_MS=100`: controlled shorter-frame streaming experiment.
- The phone's existing 9600-byte max-PCM-packet decoder accepts both.
- Ready handshake advertises `audio_frame_ms` appropriate to the
  *output* chunk size in realtime voice mode. This is not the microphone
  frame duration.
- Does not change AI voice/model, source audio capture, translated words,
  inference latency, or the plugin implementation.

**Why:** Shorter delivery frames can reduce head-of-line audio wait and help
smooth playback scheduling under modest jitter. The extra packet frequency
and plugin feed overhead may instead make things worse on some devices.
This is a reversible A/B experiment, not a proven fix or guarantee that the
model synthesized all English caption words.

## Device test — avoid adaptive buffer confounding

Use the same spoken sample and physical device for each test, and **do not
use `BT_LIVE_VOICE_ADAPTIVE_STARTUP=true`** for this controlled experiment.
A fixed 500ms start buffer prioritizes completeness over initial speed.

Server-side flag must be set **before** Uvicorn starts. Stop old server with
Ctrl+C; preserve the same secured API key and local session token:

```powershell
$env:BT_AI_CAPTION_ENGINE = "realtime"
$env:BT_AI_REALTIME_VOICE = "1"
$env:BT_AI_PACE_OUTPUT_AUDIO = "1"
$env:BT_AI_DIAGNOSTICS = "1"
$env:BT_AI_OUTPUT_FRAME_MS = "100"
& "G:\BT-Share\.venv\Scripts\python.exe" -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098
```

In a second terminal reconfigure ADB reverse and run Flutter:

```powershell
& "C:\Android\Sdk\platform-tools\adb.exe" -s f7d152a reverse tcp:8098 tcp:8098
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=500
```

Speak continuously for 20-30 seconds, pause two seconds, speak again.
Prefer headphones to avoid feedback from the translated voice entering MIC.
Record: `Gateway paced audio`, `Gateway pending PCM`, `Native PCM`,
`Buffer`, `Audio gaps >400ms`, and `Underruns` on the debug panel.

Repeat with `BT_AI_OUTPUT_FRAME_MS=200` for baseline; changing the Python
flag requires **restarting Uvicorn**. Keep Flutter prebuffer fixed at 500ms
for both runs. If both modes cause missing words with low underruns and no
transport errors, investigate native playback vs model-generated speech
completeness, rather than masking loss through a larger buffer.

## Verification

```powershell
git pull --ff-only origin Sihan-Member-3
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
```

The assistant committed source and test updates but did not execute
Windows/Python/Flutter or listen to mobile-device audio. Device performance
and audio completeness require the user's measurements.

## Safety and known limitations

- No PCM chunks or words are silently dropped by the new splitter.
- Server backpressure limit and phone's 2s safety limit are unchanged.
- A longer prebuffer adds initial latency; expect a tradeoff.
- Source-model translation can produce an audio stream that differs from
  transcript output, and pause-to-pause voice timbre may vary independently
  of network playback. Exact translated word coverage requires a separate
  phrase-aligned fixed-voice TTS architecture (future phase).
