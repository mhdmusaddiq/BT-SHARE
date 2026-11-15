# Phase 2.2 — Realtime Audio Timing Diagnostics

**Status:** Implementation committed to `Sihan-Member-3`; Flutter/Android
hardware tests **not run by the assistant**. This is instrumentation, not a
voice consistency/latency optimization. Does not modify API routing, AI
provider models, existing audio chunk sizes, or authentication.

## Scope / safety

- Python gateway metrics are OFF by default. Set `BT_AI_DIAGNOSTICS=1`
  before starting Uvicorn to enable timing-only server logs.
- Flutter debug build displays a `DEBUG AUDIO TIMING` row in VOICE OUTPUT.
  It also emits stage-only console lines `BT_SHARE_METRIC device_stage=...`.
  Release builds do not display the debug panel or emit these messages.
- Metrics contain event names, counts, monotonic elapsed milliseconds only.
  Never log tokens, API keys, microphone PCM, transcripts or translated text.
- Existing `BT_AI_REALTIME_VOICE=1` and `BT_AI_CAPTION_ENGINE=realtime`
  remain required for translated audio. In-app **Translated voice output**
  and **Speak automatically** must both be enabled to *play* audio.
  Phone will still count audio packets with auto-speak OFF.
- No user-facing claim about acoustic first-sound timing: Android
  `AudioStreamPlayer.play()` completion means **playback accepted**, not
  sound reached the ear or phone speaker.

## Timing semantics

**Gateway clock origin** = first upstream MIC audio frame *send begins*.
The gateway logs at most one event per stage:

| Python stage | Meaning |
|---|---|
| `first_input_send` | Origin: 0 ms |
| `provider_first_target_caption` | First English/Tamil target text delta received from AI |
| `provider_first_audio` | First validated generated PCM delta received from AI |
| `gateway_first_audio_sent` | First PCM frame handed to phone WebSocket |

**Flutter clock origin** = successful start of microphone PCM capture.
The Flutter debug panel shows:

| Flutter stage | Meaning |
|---|---|
| `MIC first frame` | First local 16k PCM capture chunk |
| `English caption` | First *target* caption (regardless of target language label in current UI) |
| `Audio received` | First live PCM chunk received at Flutter provider |
| `Player accepted` | First native player.play() Future completed |
| `Caption→audio` | First phone audio chunk receipt minus first target caption, can be negative |
| `Audio→player` | Player acceptance minus first phone audio chunk receipt |
| `Caption→player` | Local first player acceptance minus first target caption, can be negative |
| `Chunks` | Number of translated PCM chunks delivered to Flutter |
| `Underruns` | Number of underrun events reported by native PCM player |

A blank/— value means the stage was not observed. The clocks are independent:
**do not subtract raw Python milliseconds from Flutter milliseconds**.
The origins differ and this implementation does not measure speech onset,
acoustic speaker output, p50/p95 distributions, or complete end-to-end delay.
For timing after an intentional pause, start a fresh test session; these
are first-event measurements, not per-utterance measurements.

## Start / tests (Windows PowerShell)

Use a trusted USB device with ADB Reverse, and leave the Uvicorn terminal
running. Your local token and OpenAI key must already be available in
Terminal 1; do not print them.

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
git switch Sihan-Member-3
git pull --ff-only origin Sihan-Member-3

# Terminal 1 — STOP old Uvicorn with Ctrl+C first.
$env:BT_AI_CAPTION_ENGINE = "realtime"
$env:BT_AI_REALTIME_VOICE = "1"
$env:BT_AI_DIAGNOSTICS = "1"
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
& "G:\BT-Share\.venv\Scripts\python.exe" -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098

# Terminal 2 — keep Python running; use this in a separate terminal.
& "C:\Android\Sdk\platform-tools\adb.exe" -s f7d152a reverse tcp:8098 tcp:8098
flutter analyze
flutter test
flutter run -d f7d152a --dart-define-from-file=local_gateway.json
```

If you have reset/replaced terminals, configure the existing
`BT_DEV_GATEWAY_TOKEN` and `OPENAI_API_KEY` privately in Terminal 1
before running Uvicorn; keep `local_gateway.json` ignored/uncommitted.
ADB Reverse requires `BT_AI_WS_URL=ws://127.0.0.1:8098/v1/stream` in
the private local config and debug-only cleartext access enabled.
No credentials should be pasted into an issue or chat.

**Device test:** Speak one 20–30 second Tamil paragraph, pause for 2 seconds,
then speak again; check English captions, translated voice playback, the
Python `BT_SHARE_METRIC stage=...` messages, and Flutter debug timing panel.
Compare caption versus audio send and player acceptance. Repeat with a
short 2–4 second sentence and with automatic voice OFF (for diagnostics
only). Log stage timings only.

**Interpretation:** If provider first audio follows provider target caption
by a large margin, upstream generation is a major candidate bottleneck.
If backend audio is sent promptly but phone audio arrives/plays late,
investigate transport, queueing and Android playback. If Android underruns
rise, investigate delivery jitter and adaptive buffer controls. Do not
assume this test proves why fixed voices change across pauses.

## Rollback

`BT_AI_DIAGNOSTICS=0` turns off Python timing logs without code rollback;
Flutter timing panel appears only in debug builds. To roll back code, revert
the Phase 2.2 commits on the same branch; do not alter main/development.

## Test isolation fix — segmented tests under a realtime PowerShell session

The segmented WebSocket tests in `gateway/test_fast_websocket.py` now set
`BT_AI_CAPTION_ENGINE=segmented` explicitly per test using pytest's
`monkeypatch`. This prevents accidental inheritance of the parent
PowerShell terminal's `BT_AI_CAPTION_ENGINE=realtime` setting. The previous
symptom was two failing segmented contract assertions (`error` instead of
`ready`, and missing `tts_audio_format`) while other tests passed.
This changes **only test configuration**, not gateway runtime engine selection.
Two informational Flutter lints in `voice_output.dart` were also cleaned:
redundant `dart:typed_data` import and conditional timer assignment.
