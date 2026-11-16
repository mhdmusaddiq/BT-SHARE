# Phase 2.4 — Caption → Fixed Voice Streaming TTS (opt-in)

## Purpose

User-device evidence: Tamil and English realtime text captions are correct,
but the previous direct translated voice begins ~2s later and occasionally
breaks or leaves audible words missing.

This experimental phase uses the *actual realtime target-language caption
deltas* to generate speech using the OpenAI Speech API. It preserves the
existing direct AI voice mode and existing microphone-only transport.

The user explicitly selected Caption → TTS implementation. This version is
a testable prototype, NOT yet verified to cure audible gaps or delay on a
physical phone. Text-to-speech generation introduces additional latency
and separate API usage charges.

## Audio flow and safeguards

1. Flutter Settings → Realtime Voice Engine:
   - Direct AI Voice (existing; default / rollback)
   - Caption → Fixed Voice TTS (new)
2. On authenticated WebSocket start, Flutter adds
   "voice_engine": "caption_tts" or "direct".
3. Realtime translator still emits original and translated text, unmodified.
4. In TTS mode, the gateway ignores model audio and forwards only the
   target-language append-only caption deltas to CaptionPhraseAccumulator.
5. Stable punctuation, whitespace-bounded long phrases, and 800ms idle
   tails are committed only once; final tail is flushed on normal close.
6. A single FIFO StreamingCaptionTts worker synthesizes each phrase with
   the *same configured voice* via POST /v1/audio/speech with
   model=gpt-4o-mini-tts, voice=alloy, response_format=pcm.
7. The response bytes are 24kHz, mono, little-endian signed PCM16; they are
   split on 4800-byte boundaries into 100ms frames, preserving sample
   order even if HTTP chunk boundaries split a sample.
8. Existing auth-bound WebSocket "audio_delta" messages, bounded PCM relay
   and Flutter audio player are reused without a dependency change.
9. Text and audio queues are bounded; PCM enqueue employs async
   backpressure, never intentionally discards an accepted phrase.
   Errors are surfaced, not silently ignored.
10. MIC-only privacy rules apply: gateway credentials and token are not
    sent to TTS clients, and transcripts/PCM are not written to logs or disk.

The TTS voice may still pause between independently synthesized phrases,
and the generated voice does not have word-for-word correctness guarantees.
TTS cannot repair incorrectly translated text. Network, synthesis and
Android device buffering can still produce gaps or extra latency.

## Local setup: Windows / Redmi Note 9 Pro

First stop the old Uvicorn and Flutter run processes. Pull the new branch:

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
git pull --ff-only origin Sihan-Member-3
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
```

The backend command needs the existing privately configured
OPENAI_API_KEY and BT_DEV_GATEWAY_TOKEN. If a new terminal is used, load
them securely. Do not commit/paste local_gateway.json or credentials.

In the server terminal:

```powershell
$env:BT_AI_CAPTION_ENGINE = "realtime"
$env:BT_AI_REALTIME_VOICE = "1"
$env:BT_AI_PACE_OUTPUT_AUDIO = "1"
$env:BT_AI_CAPTION_TTS_ENABLED = "1"
$env:BT_AI_DIAGNOSTICS = "1"
& "G:\BT-Share\.venv\Scripts\python.exe" -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098
```

In a second terminal:

```powershell
& "C:\Android\Sdk\platform-tools\adb.exe" -s f7d152a reverse tcp:8098 tcp:8098
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=450
```

On phone: open Translation settings, select Caption → Fixed Voice TTS,
enable Translated voice output and Speak automatically, then start
translation. Changing mode requires stopping/restarting the session.
Use wired headphones to avoid microphone feedback.

Compare 30 seconds of Tamil speech (with pauses) against Direct AI Voice:
- Does English caption content exactly match what is spoken?
- Does voice remain recognizably consistent?
- Are all words audible, or are there seams after phrases?
- Note first-audio delay, gateway pending PCM, native buffer,
  audio gaps >400ms and underruns.

To rollback without new code: switch Flutter voice engine to
Direct AI Voice and restart session. To block extra Speech API requests,
set BT_AI_CAPTION_TTS_ENABLED=0 and restart Uvicorn.

## Scope and tests

This branch introduces unit tests for stable prefix/no-duplicate phrase
handling, stream sample fidelity, fixed-voice FIFO, errors and opt-in;
gateway WebSocket contract tests for the two modes; and a Flutter
session selection test. Offline fake provider tests require no paid API
access.

These tests have been committed but **have not been executed on the
user's Windows machine**. Device/audio quality needs an actual trial.

The model can generate natural voice, but identical speaker timbre and
complete spoken word accuracy are goals, not verified guarantees.
