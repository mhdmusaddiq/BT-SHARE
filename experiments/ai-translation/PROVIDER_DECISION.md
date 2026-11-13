# C0 — Gemini provider migration decision (2026-11-13)

## Selected C2 architecture

User explicitly requested replacing OpenAI with **Google Gemini** while
preserving the Flutter integration and WebSocket contract.

- Client input: MIC-only PCM16 mono @ 16 kHz; no MEDIA capture.
- Gateway: existing Python FastAPI /v1/stream with the same temporary token.
- Gemini transcription+translation: `gemini-3.8-flash` via native
  `v1beta/models/{model}:generateContent`, short WAV audio embedded as
  inlineData, structured JSON containing original_text/translated_text.
- Gemini voice: `gemini-3.8-flash-lite-tts` via `v1beta/interactions`,
  output `audio/wav` with voice `Kore`. Gemini TTS supports Tamil.
- Mobile output: unchanged segment JSON and playable WAV file.
- Runtime configuration: `GEMINI_API_KEY` server-side. Optionally
  `GEMINI_TEXT_MODEL`, `GEMINI_TTS_MODEL`, `GEMINI_TTS_VOICE`.
- No OpenAI API key, host or billable OpenAI call remains in active code.
- Only the experimental Member 3 branch and owned scope changed.

## Constraints and tradeoffs

The two-call segmented Gemini pipeline is simpler and easier to test
than native simultaneous voice streaming, but adds utterance buffering
(~0.5s silence) and two sequential request latencies. It may miss short
speech; threshold-based voice activity segmentation is NOT production VAD.
Gemini model availability may vary by region/account and prices may apply.
Do not report live Tamil transcription accuracy or AI latency before tests.

This test gateway uses a dev-only shared token, insecure WebSocket on
trusted LAN and no durable recording. It needs TLS/WSS, authenticated
per-user session tokens, stronger quotas and secure audit/consent for
production. Vendor API keys must NOT be embedded in Flutter or committed.
TTS audio and text are processed remotely; explain this clearly to users.

## Vendor documentation (checked 2026-11-13)

- https://ai.google.dev/gemini-api/docs/models
- https://ai.google.dev/api/generate-content
- https://ai.google.dev/gemini-api/docs/speech-generation
- https://ai.google.dev/gemini-api/docs/api-key

## Evidence
Earlier 7 Python + 7 Flutter automated tests were reported PASS by the
tester **before** this Gemini provider migration. New Gemini contract tests
have been authored but remain NOT_EXECUTED until the user's next
`python -m pytest gateway -q` output and live test. Reviewer sign-off pending.
