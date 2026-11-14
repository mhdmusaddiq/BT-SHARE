# C0 Provider decision — OpenAI restored on 2026-11-14

**Active selected provider:** OpenAI API. User chose to reuse purchased OpenAI
API credits instead of Google Gemini after a temporary Gemini migration.

## C2 segmented MIC-only pipeline
- `gpt-transcribe` 16kHz microphone WAV -> original transcript.
- `gpt-4.1-mini` original transcript -> Tamil/English translated text.
- `gpt-4o-mini-tts` translated text -> generated WAV for Flutter playback.
- FastAPI gateway `/v1/stream` and `/health/live` retained.
- Flutter code, MIC-only constraints, local token and consent flow retained.
- Runtime credentials: `OPENAI_API_KEY` server-side **only**.
- Temporary `BT_DEV_GATEWAY_TOKEN` equals the dev-only Flutter token.
- Former Gemini implementation removed; keep generated `local_gateway.json`
  from your Windows machine (it is Git-ignored).

## Limitations
This is segmented near-live speech, not simultaneous interpretation.
Current energy threshold has a ~0.5 sec silence trigger and lacks production
VAD, TLS/WSS, per-user auth, billing quota enforcement and detailed consent.
OpenAI API cost is distinct from a ChatGPT subscription. Model access and
credits must be verified on the user's own API organization.

The transcription model `gpt-transcribe` is current. The legacy
`gpt-4o-mini-tts` is scheduled for shutdown 2027-01-06 and `gpt-4.1-mini`
2027-01-14; an updated compatible speech-generation path will be needed.
A Realtime model cannot simply replace the Speech API `model` field.

**C2 actual cloud test:** NOT_RUN / NOT_VERIFIED by the assistant.
The earlier local 7/7 tests were reported by the user for this OpenAI
implementation, but must be rerun after pulling the restored code.
Tamil transcription quality and generated voice require physical testing.

References (checked 2026-11-14):
- https://developers.openai.com/api/docs/guides/speech-to-text
- https://developers.openai.com/api/docs/guides/text-to-speech
- https://developers.openai.com/api/docs/deprecations
- https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform
