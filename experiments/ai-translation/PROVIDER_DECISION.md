# C0 Provider decision — 2026-11-11

**Selection for C2 POC:** a segmented MIC-only chain: gpt-transcribe
(original text) -> gpt-4.1-mini (Tamil/English translation) ->
gpt-4o-mini-tts (generated WAV audio). This is NOT simultaneous translation.

## Language constraint
OpenAI gpt-realtime-translate has 13 OUTPUT languages and excludes Tamil;
using it as the only Tamil voice provider would fail for English->Tamil.
The text-to-speech API documents Tamil output among its supported languages.
GPT-4o-mini-tts is deprecated with a Jan 6, 2027 shutdown announced,
so replace the TTS leg before that date. Tamil ASR/voice quality remains
unverified on a real provider.

Docs (viewed 2026-11-11):
- https://developers.openai.com/cookbook/examples/voice_solutions/realtime_translation_guide
- https://developers.openai.com/api/docs/guides/text-to-speech
- https://developers.openai.com/api/docs/guides/speech-to-text

## Security and cost
Only the server holds OPENAI_API_KEY. A separate temporary, shared
BT_DEV_GATEWAY_TOKEN is a test-only private-LAN gate, NOT production user auth.
Client dart-defines/JSON values are extractable; never put the vendor API key
in Flutter. No secrets, PCM samples or transcripts are checked into Git.
The three stages incur vendor charges; verify active billing and quotas.
No live call or cost/latency measurement has been performed.

## Acceptance status
C1 user-reported 03:56 MIC capture works in MOCK mode. C2 remains
NOT_RUN_PHYSICAL until an authenticated provider call from a consented
Android device returns BOTH accurate text and generated audio.
