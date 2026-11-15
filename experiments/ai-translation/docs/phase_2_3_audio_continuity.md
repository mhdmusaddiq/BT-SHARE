# Phase 2.3 — Realtime audio continuity (first implementation)

**Branch:** `Sihan-Member-3`
**Scope:** MIC-only diagnostic and playback-start policy only.
**No production fixed-voice TTS, phrase alignment, transport migration or
automatic AI caption acceleration has been implemented.**

## Empirical baseline: five user-supplied gateway sessions (2026-11-15)

From one user's debug gateway logs, measured relative to the first upstream
PCM audio send (not actual speech onset). All numbers in milliseconds:

| Session | First provider audio | First provider target caption | Caption minus audio |
|---|---:|---:|---:|
| 1 | 751 | 3298 | 2547 |
| 2 | 736 | 3631 | 2895 |
| 3 | 739 | 2958 | 2219 |
| 4 | 743 | 2467 | 1724 |
| 5 | 688 | 4666 | 3978 |
| **Mean** | **731** | **3404** | **2673** |

The median caption-minus-audio gap is **2547ms**, minimum **1724ms**,
maximum **3978ms**. Five sessions are a small sample and neither first event
necessarily represents the exact same spoken phrase. These measurements
indicate generated audio tends to be visible to the Python receiver before
target transcript deltas. They do **not** prove why the provider outputs
transcript deltas later or how long actual translated speech takes to start.

The backend now also emits exactly one derived log line per session,
when diagnostics are enabled:

```text
BT_SHARE_METRIC stage=caption_minus_audio gap_ms=2547
```

A positive gap means the FIRST caption event was observed after FIRST
provider audio. A negative gap means caption was earlier.
This derives from existing monotonic per-session measurements; clocks are
not combined across Python and Flutter. No text, audio or credentials logged.

## Adaptive startup buffer: opt-in

A session-local output player already uses a fixed 320ms start threshold
(unless overridden by `BT_LIVE_VOICE_PREBUFFER_MS`). The new policy
**only changes the threshold between playback stream instances**, using
successful chunks and native player underrun counts:

- `BT_LIVE_VOICE_ADAPTIVE_STARTUP=false` by default. Existing fixed behavior
  stays unchanged.
- When `true`, start at the chosen `BT_LIVE_VOICE_PREBUFFER_MS`
  (default 320), clamped between 160 and 700ms.
- On any native player underruns: increase NEXT stream threshold by
  120ms per underrun, capped to +240ms and total 700ms.
- On a clean stream of at least 20 PCM chunks: decrease NEXT stream
  threshold by 40ms, with a 160ms minimum.
- On a short stream or one explicitly discarded due to audio backlog:
  do not reward that stream with lower prebuffer.
- Keep the existing max-wait timer (default 600ms) for short utterances.
- This is **adaptive startup tuning**, not a full packet jitter buffer or
  per-pause dynamic rebuffer. It does not prevent all audio dropouts.

Suggested controlled A/B runs; keep the Python server and ADB Reverse
running. Stop each Flutter process with `q` before switching variants.

**Baseline (same as previous release):**

```powershell
flutter run -d f7d152a --dart-define-from-file=local_gateway.json
```

**Adaptive 320ms start:**

```powershell
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_ADAPTIVE_STARTUP=true
```

**Adaptive low-latency 200ms start (may increase underruns):**

```powershell
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_ADAPTIVE_STARTUP=true --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=200
```

In debug VOICE OUTPUT, inspect `Startup buffer: ... (adaptive|fixed)`,
`Audio→player`, `Underruns` and `Chunks`. Do not confuse
`Player accepted` with actual acoustic speaker onset.

## MIC activity diagnostic: pause/resume

A separate *debug-only* `SpeechActivityMonitor` operates on incoming
microphone PCM16LE (16kHz mono) in 100ms windows using mean absolute PCM
energy. It uses a 200ms voice threshold to enter the speaking state and
700ms silence threshold to register a pause. A later voice onset increments
`Resumes`. It stores only counters (no PCM, words or transcripts).

The debug UI shows `Energy speech windows`, `Pauses`, `Resumes`.
This is a **heuristic**: background noise, room echo and volume can produce
false transitions; these are not authoritative utterance boundaries, speech
timestamps or model-aligned per-phrase latency. It does not modify input
audio, drive provider VAD, mute output or change session lifecycle.

## Tests and verification

Run from `experiments/ai-translation` after pulling the branch:

```powershell
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
```

The assistant made the code changes through GitHub, but could not run
Flutter/Android tooling from its own runtime. **Python/Flutter/device
verification of this Phase 2.3 commit is pending on the user's Windows PC.**
Do not assume a lower latency until an A/B comparison confirms it.

## Next phases

Phase 2.3 follow-up: measured per-phrase alignment if provider supplies
usable timestamps/IDs; avoid inventing alignment from independent text and
audio event sequence. Phase 2.4: separate, user-selectable fixed voice TTS
pipeline with stable phrase commits; this is not yet built.

Rollback: remove `BT_LIVE_VOICE_ADAPTIVE_STARTUP` flag to get the
previous fixed 320ms startup threshold. Turn off `BT_AI_DIAGNOSTICS`
for fewer Python logs; MIC activity counters only show in debug builds.

## Follow-up: Redmi Note 9 Pro red PCM failure (2026-11-15)

User's Flutter debug screenshot: target caption 4336ms, first PCM received
1094ms, player accepted 1245ms, 1030 packets, 0 underruns, and a RED
`Live PCM playback failed on this device` banner. Initial play() acceptance
does not prove audible sound. The original banner was emitted for ALL
`pushLive` exceptions, including the intentional >2s buffered PCM limit.
The screenshot cannot distinguish buffer backlog from native Android errors.

This patch introduces stable error categories (no raw platform messages,
transcripts, PCM or provider credentials): `initialization`, `bufferQuery`,
`excessiveBacklog`, `feed`, `playbackStart`. In debug Flutter console,
look for `BT_SHARE_PCM failure_stage=... buffered_ms=...`.
A backlog displays its observed buffered duration; native failures display
their stage. Both stop the affected playback stream while keeping captions
running. The 2-second safety bound is retained; PCM samples are not silently
dropped, and no automatic voice stream reset is attempted.

**Verify**: pull latest `Sihan-Member-3`, run `flutter analyze` and
`flutter test`, then restart Flutter; run for a comparable 20–30 seconds
and share only the red error text and `BT_SHARE_PCM` console lines.
These are diagnostic changes, not a confirmed repair of physical audio
routing. A no-underrun result does not exclude buffer backlog.
