# BT SHARE Phase 2.5 — Prefetched Fixed Voice (caption-safe)

**Scope:** Voice / TTS only. Frozen Tamil and English caption processing,
WebSocket segment payloads, 80ms UI flush and Flutter caption rendering were
NOT changed. See `docs/CAPTION_BASELINE_FROZEN.md`.

## Problem and proposed improvement

The user reports fully correct Tamil and translated English captions, while
spoken English has audible pauses and occasional word gaps. The earlier TTS
worker sent TTS requests serially: the next request only started **after**
the previous HTTP audio stream had been completely consumed and forwarded.
This adds an avoidable network/model start gap between known phrases.

Phase 2.5 starts synthesizing the *next* known phrase while the previous
phrase is streaming to the phone. It uses the same single fixed voice
(`alloy`) across phrases, preserves exactly the phrase submission order,
and sends PCM via the existing 24kHz mono PCM16/audio_delta transport.

The in-memory safety limits are:
- At most **two concurrent Speech API requests** (default).
- At most **12 queued phrase references**; each phrase has a private
  bounded 12-frame queue (100ms frames, about 1.2 seconds).
- Only the FIFO consumer sends PCM. Completion order of HTTP requests
  cannot reorder audible words.
- Every accepted PCM chunk is delivered unchanged in byte order. HTTP
  failures propagate to the gateway rather than silently skipping text.
- Cancel stops the active player and outstanding synthesis requests.
- No new Flutter dependencies, caption text edits, recorded voice files,
  raw transcripts or API secrets in logs.

**Important limitations:** Prefetch can reduce *inter-phrase HTTP startup*
gaps only when the next caption phrase already exists. It cannot guarantee
word-for-word model TTS output, eliminate Android AudioTrack underruns,
fix live caption segmentation delays, or assure a target latency.
Parallel requests can count toward concurrent Speech API rate limits.
The speech audio may still have audible phrase boundaries.

## A/B test and rollback

Default: parallel requests = 2. Opt back into serial behavior *without
changing code* using `BT_AI_TTS_PREFETCH_PARALLEL=1`, then restart Uvicorn.
Use `2` for the new behavior. The gateway must also have
`BT_AI_CAPTION_TTS_ENABLED=1` and
`BT_AI_CAPTION_ENGINE=realtime`.

**Server terminal** (after securing the API key and local dev token):

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
git pull --ff-only origin Sihan-Member-3
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
$env:BT_AI_TTS_PREFETCH_PARALLEL = "2"
powershell -ExecutionPolicy Bypass -File .\scripts\start_caption_tts_server.ps1
```

**Flutter terminal** (keep gateway running):

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
powershell -ExecutionPolicy Bypass -File .\scripts\run_caption_tts_flutter.ps1
```

Phone settings: Caption → Fixed Voice TTS, Translated Voice Output ON,
Speak Automatically ON. Listen for voice breaks while reading the correct
English captions. Compare `1` vs `2` under the same network and test
sentence. Report delay, underruns, audio gap counter, and phrase omissions.

## Tests

Regression tests cover concurrent HTTP synthesis (the second request starts
before the first completes), strict FIFO PCM byte order, a consistent voice,
odd HTTP chunk boundaries, error propagation, and cancelling stalled
requests. The implementation was separately exercised with four isolated
local Python tests; the complete project test suite and Android audio quality
require verification on the user's configured Windows machine.
