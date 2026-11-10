# C0 Provider decision (provisional; no vendor selected)

The POC deliberately uses **MOCK** as the default. `GatewayTranslationProvider` is a replaceable WebSocket adapter, **not** a verified translation engine.

## Candidate architectures to evaluate

| Candidate | MIC stream | Tamil + English | Generated voice | Cost/latency evidence |
|---|---|---|---|---|
| Streaming audio-to-audio provider | Confirm per model/realtime API | Validate actual Tamil quality | Check supported output codecs | NOT_MEASURED |
| Streaming ASR -> translator -> speech synthesis | Check continuous streaming support | Validate both directions with fixed utterance set | TTS should provide playable WAV/MP3 | NOT_MEASURED |
| Self-hosted ASR + MT + TTS | Depends on deployment/models | Benchmarks required | Depends on installed TTS | NOT_MEASURED |

**Recommendation:** choose **no production vendor yet**. Select provider after live C1–C4 tests with consent and reproducible tokenized billing. Model prices, privacy retention, translation quality, real-time latency, voice quality and regional availability have **not** been verified here.

## Required cost model for C0 follow-up

Monthly cost = (source microphone minutes × speech recognition/streaming rate) + (translated characters/tokens × translation rate) + (synthesized characters/minutes × speech generation rate) + network/gateway storage/compute. Record sample rate, payload bitrate, real tokens, vendor billing region, free quotas and voice option at the **date of the experiment**; do not guess cost per minute.

## Security

Mobile client obtains ephemeral token from BT SHARE-owned authenticated backend. AI vendor secrets live server-side. Gateway enforces MIC-only semantics and consent, max minutes, token expiry and role authorization. Audio/log retention should be opt-in and minimal. `--dart-define` is **not a secure secret store** in shipped binaries.
