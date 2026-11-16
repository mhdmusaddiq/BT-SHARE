# FROZEN: BT SHARE realtime captions — 2026-11-17

**Status:** Functional freeze, explicitly requested after successful Android
user testing. Both **Tamil original captions** and **English translated captions**
were reported working perfectly. Do not optimize, rewrite, restructure, or
reconfigure them as part of voice/TTS experiments.

## Frozen baseline

- Repository: `mhdmusaddiq/BT-SHARE` (private)
- Branch: `Sihan-Member-3`
- Baseline source commit: `9d93d3d5dacdd9e1b0fcbf5793c954f8aaa99961`
- Gateway: `gateway/realtime_translation.py` caption sections:
  `LiveCaptionState`, delta acceptance, append-only accumulation, 80 ms
  caption UI flush, `segment_id="live-current"`, and JSON
  `original_text` / `translated_text` payload contracts.
- Flutter: `lib/translation/gateway_provider.dart` segment decoding,
  `lib/translation/session_controller.dart` stable-segment merge,
  `lib/ui/translation_home.dart` original/translated caption rendering.
- Source: MIC-only 16kHz PCM16; realtime captions should continue while mic
  runs regardless of voice mode.

## Allowed future work without new user approval

Voice/TTS-only playback quality, audio queue management, latency diagnostics,
model/audio voice configuration, and reconnect/error handling **provided the
frozen captions and their transport are untouched**.

## Not allowed without explicit approval

- Changing original/translated text contents, model/output language mapping,
  transcript delta processing, caption flush/update timing, UI caption text,
  segment IDs, or caption-only fallback.
- Replacing realtime caption source with the TTS-generated text.
- Making captions wait for TTS, speaker playback, audio queue drain, or
  voice generation.
- Editing the frozen caption sections as a "cleanup" during audio fixes.

If a future fix genuinely requires a caption-path change, explain the change
and get explicit permission before implementing it.

## Protection check

A regression suite in `gateway/test_caption_freeze_contract.py` preserves
append-only text, Tamil/English content, whitespace and punctuation, stable
IDs, bounded-memory behavior, and isolation from audio events. Existing
WebSocket tests in `gateway/test_realtime_translation.py` also cover live
caption delivery while microphone capture is active.

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway/test_caption_freeze_contract.py gateway/test_realtime_translation.py -q
flutter analyze
flutter test
```

**Note:** These are source-level guardrails and regression tests, not
GitHub-enforced path-level branch protection. The assistant cannot prevent
someone with repository write access from changing these files. The tests
were committed but must be run locally before claiming all green.
