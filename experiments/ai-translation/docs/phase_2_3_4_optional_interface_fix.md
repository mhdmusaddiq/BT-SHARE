# Phase 2.3.4 — Flutter optional interface compiler fix

A user ran the latest Phase 2.3.4 source on Windows and reported:

- Python: 31 passed, 1 third-party Starlette deprecation warning.
- `flutter analyze`: two errors in `session_controller.dart` reading
  `provider.outputAudioPaced` and `provider.audioQueueMetrics`.
- `flutter test`: compilation failed from the same two errors.

Root cause: `TranslationProvider`, `GatewayAudioPacingInfo`, and
`GatewayAudioQueueInfo` are independent interfaces. Flow promotion on an
instance typed `TranslationProvider` did not enable direct access to the
additional interface getters.

Fix: explicitly typed, nullable references are obtained through Dart 3
switch-pattern matching, matching the existing `StreamingTranslationProvider`
pattern. The code accesses only `pacingInfo?.outputAudioPaced` and
`queueInfo.audioQueueMetrics` when the optional interface is supported.
The app continues to support mock providers and older server handshakes.

No changes were made to microphone data flow, audio generation, PCM pacing,
translation captions, user auth, or voice settings.

Test locally from `experiments/ai-translation`:

```powershell
git pull --ff-only origin Sihan-Member-3
flutter analyze
flutter test
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
```

The assistant verified the committed source patch, but has not executed
Flutter on the user's PC. Report actual test results before device retesting.
