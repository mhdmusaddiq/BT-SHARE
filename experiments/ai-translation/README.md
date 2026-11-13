# BT SHARE · Member 3 · MIC-only Flutter Translation POC

**Branch:** `Sihan-Member-3` · **Owner:** Member 3 · **Scope:** `experiments/ai-translation/**`

This is a **proof of concept**, not a shipping app. The Dart and Android implementation sources are committed; generated Flutter toolchain and Android template files must be bootstrapped on a machine with Flutter/Android SDK. The user reported successfully building a debug APK and testing the MOCK application on Redmi Note 9 Pro for 03:56 with no errors. No live C2 AI-provider test has yet been observed; Flutter build and device tooling are unavailable inside the assistant runtime.

## Features and truth in UI

- MIC ONLY, Tamil ↔ English, original transcript and translated caption history.
- Captures PCM16 mono 16 kHz microphone chunks with runtime permission. No playback capture, no MEDIA input, no video capture.
- `mock` demo is functional without cloud credentials, **but deliberately does not transcribe the user's words**. Every ~48,000 input PCM bytes trigger a pre-scripted fixture. All mock captions are labelled **MOCK**; device text-to-speech reading the fixture is **not cloud-generated model speech**.
- `gateway` mode connects to the included experimental Python gateway under `gateway/`; cloud API access and real speech quality remain unverified. Supports translated audio returned as encoded WAV/MP3 and captions.
- UI includes a duration timer, latency only when supplied as a measured gateway value, permission/error/retry states, voice/captions settings and accessible button labels.
- AI session state and local original-audio link are independent. **This isolated module does not implement or prove local room audio distribution.**

## Windows setup (Windows PowerShell)

Requirements: current Flutter stable, Dart, Android Studio SDK/toolchain, at least Android API 23. At minimum:

```powershell
flutter doctor -v
git clone https://github.com/mhdmusaddiq/BT-SHARE.git
cd BT-SHARE
git fetch --all
git switch Sihan-Member-3
git pull --ff-only origin Sihan-Member-3
cd experiments/ai-translation
# Generate missing standard Flutter Android/Gradle scaffolding.
# The source-controlled manifest and Dart files must remain unchanged.
flutter create --platforms=android --org com.btshare --project-name bt_share_ai_translation_poc .
git diff -- android/app/src/main/AndroidManifest.xml
flutter pub get
dart format --output=write lib test
flutter analyze
flutter test
flutter run --dart-define=BT_AI_PROVIDER=mock
flutter build apk --debug --dart-define=BT_AI_PROVIDER=mock
```

`flutter create` may generate a default Android manifest and Gradle files. **Reapply the tracked `android/app/src/main/AndroidManifest.xml`** if it changes, then check it contains `RECORD_AUDIO`, `INTERNET` and the TTS query. Verify `git status` before committing generated output. The Android template must have `minSdk >= 23` and the installed Flutter Gradle and Kotlin plugin versions (avoid hardcoding deprecated Gradle settings). Flutter-generated Android scaffold files should be reviewed and committed by Member 3 after a successful local build.

If an Android build reports unsupported `record`/permission/TTS platform API or missing permissions, treat as a build blocker, do not report it as tested.

## Real gateway mode

**Wire messages implemented by the experimental local gateway; production auth is NOT included:**

Connect to `wss://...` using a short-lived, authenticated gateway session. Initial text JSON:
```json
{"type":"start","source_mode":"MIC","source_language":"ta","target_language":"en","format":"pcm_s16le","sample_rate_hz":16000,"channels":1,"ephemeral_session_token":"..."}
```
Client audio frames:
```json
{"type":"audio","source_mode":"MIC","sequence":1,"pcm16_base64":"..."}
```
Gateway text response:
```json
{"type":"segment","segment_id":"s1","original_text":"...","translated_text":"...","latency_ms":820,"audio_mime_type":"audio/wav","translated_audio_base64":"..."}
```
Or error:
```json
{"type":"error","message":"Service not available"}
```

This protocol is **provisional**: no central contract or other member module has been modified. Audio is sent ONLY after explicit MIC permission and translation start. Prefer binary audio frames, authenticated session refresh and server-side VAD before production. The gateway rejects MEDIA and includes a test-only token, basic rate bounds and cloud STT/translation/TTS. Production authentication, consent records, stronger limits and retention policies are still required.

Use `local_gateway.json` with an existing temporary test token and the gateway's actual private-LAN IPv4 address (see C2 instructions below).
Use `wss://` outside localhost. **Never hardcode provider API credentials or long-lived tokens in `--dart-define`: build-time flags are recoverable from app binaries.** The optional `BT_AI_SESSION_TOKEN` is suitable only for throwaway/local development ephemeral sessions. Production needs a gateway-issued short-lived scoped token via a secure authentication flow.

## Layout

```
lib/
  main.dart                       app wiring and provider selection
  translation/
    models.dart                   enums, segment and mode
    microphone_input.dart         runtime microphone permission and PCM stream
    translation_provider.dart     provider abstraction + labelled fixture
    gateway_provider.dart         provisional WebSocket gateway adapter
    voice_output.dart             fixture device TTS or received WAV/MP3 audio
    session_controller.dart       lifecycle, timer, captions, retry, errors
  ui/
    translation_home.dart         responsive home/session, settings, captions
test/
  translation_provider_test.dart  mock direction and truth labels
  session_controller_test.dart    permission, start/stop, AI failure isolation
  widget_test.dart                visible MIC/MOCK controls, UI start/stop
android/app/src/main/AndroidManifest.xml    permission and TTS-query additions
```

## Privacy and limitations

- **Mock microphone data is counted, not recognized or stored.** No audio bytes or speech content are logged.
- Gateway mode streams user microphone data to the configured remote endpoint. Obtain participant consent and configure backend deletion/encryption and retention before real use.
- No foreground service or background capture yet; the app is for foreground testing.
- No cross-device sync, host-transfer, room networking, local-original-audio transport or model inference engine here.
- No VAD, jitter estimation, network QoS measurement, production audio feedback/echo control or continuous speech segmentation guaranteed.
- The Gemini backend is implemented but **not yet validated against a live Gemini account or a phone**; translation, latency, Tamil speech and TTS quality remain unverified.
- Device TTS availability varies for Tamil. When it is missing, playback error should be shown.
- Sample fixture every 1.5 seconds is a **test harness**, never a model accuracy benchmark.
- Do not persist, upload or commit recordings, transcripts or identifiers without consent and redaction.

## Test plan / honest evidence

Run `dart format --output=write lib test`, `flutter analyze`, `flutter test` and `flutter build apk --debug` on a configured Android workstation, capturing output with secrets and content redacted. Then test on consented Android phone(s):

1. C1: permission allow/deny/revoke, 1–2 min MIC capture and chunk throughput; check switching app/background appropriately stops recording.
2. C2: original caption, translated caption and audible generated voice through a **real** provider; measure per-segment original and playback times.
3. C3: 5 minutes with speech/silence, service disconnect and retry, count dropped segments.
4. C4: Tamil→English and English→Tamil real utterances, record translation errors without storing raw private utterances.
5. C5: API/Wi-Fi outage with independent original-local-audio stream installed by the integration owner. Without a working local-stream harness, independence is only unit-level, not hardware proof.

Evidence C0–C5 lives at `../../evidence/ai-translation/`. C1 has user-reported 03:56 physical MOCK observations; C2 Gemini cloud processing is NOT_RUN_PHYSICAL.

## Contribution policy

Work only on `Sihan-Member-3` and these two owned paths. Verify active branch and diff; never push to `development` or `main`; do not open/merge a PR without explicit request. Real integration must be reviewed by central coordinator.

## C2 — Gemini Provider Migration (2026-11-13)

**Provider:** Google Gemini API, NOT OpenAI. **Flutter protocol and code unchanged.**

Two Gemini API calls per speech segment:
1. Gemini Flash audio understanding (default `gemini-3.8-flash`) accepts
   16kHz WAV and returns JSON `original_text` + `translated_text`.
2. Gemini Flash-Lite TTS (default `gemini-3.8-flash-lite-tts`) returns
   a generated `audio/wav` clip (24kHz, mono) for the Flutter voice player.

The Python gateway retains MIC-only input, start/audio/stop framing,
source/target languages, consent, gateway token and /v1/stream endpoint.
This is **segmented near-live translation**, NOT simultaneous interpretation.
Speech is sent to Google only after user consent and a short pause.
Model access, quotas and translations have not yet been confirmed on-device.

### Windows: Restart existing gateway with Gemini (PowerShell)

Terminal 1, in the project directory:

```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"

# Stop the OLD server with Ctrl+C in its existing terminal.
# Activate the already-installed Python environment or use its full path.
& "G:\BT-Share\.venv\Scripts\Activate.ps1"

git pull --ff-only origin Sihan-Member-3
python -m pip install -r .\gateway\requirements.txt
python -m pytest gateway -q

# Reuse the existing Flutter dev token; do not invent a different value.
$config = Get-Content .\local_gateway.json -Raw | ConvertFrom-Json
$env:BT_DEV_GATEWAY_TOKEN = $config.BT_AI_SESSION_TOKEN

# Enter a NEW Gemini API key locally (not an OpenAI key).
$secureKey = Read-Host "Enter Gemini API key" -AsSecureString
$env:GEMINI_API_KEY = [System.Net.NetworkCredential]::new("", $secureKey).Password
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
Remove-Variable secureKey -ErrorAction SilentlyContinue

python -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098
```

Create a Gemini API key from https://aistudio.google.com/apikey. The API key
**must remain on the Python server**. Never enter it into `local_gateway.json`,
Flutter dart-defines, GitHub or chat. A short-lived test-only token is already
in the local JSON; avoid sharing the token or screenshotting the whole file.

Optional environment model overrides (set BEFORE starting the server):
```powershell
$env:GEMINI_TEXT_MODEL = "gemini-3.8-flash"
$env:GEMINI_TTS_MODEL = "gemini-3.8-flash-lite-tts"
$env:GEMINI_TTS_VOICE = "Kore"
```
These are defaults; model availability varies by key/account/region. Do not
guess model access: inspect HTTP error status and Google AI Studio.

Terminal 2, while server runs:
```powershell
Invoke-RestMethod http://127.0.0.1:8098/health/live
# Expected status live / scope MIC_ONLY. This is not an AI readiness test.
flutter analyze
flutter test
flutter devices
flutter run -d f7d152a --dart-define-from-file=local_gateway.json
```

If the phone already has the gateway-mode debug APK installed, it does not need
to be rebuilt just because the Python AI provider changed. The app's
`BT_AI_WS_URL` can continue to point at the same PC IP/port, e.g. the
previously used hotspot address `10.198.218.82:8098`, **only if current
`ipconfig` still shows that IPv4**. If it changed, update only the WS URL
in `local_gateway.json` and rebuild the debug app.

The phone and laptop must be connected on the same trusted hotspot/private
LAN. Debug cleartext HTTP/WS is **not production-safe**; restrict Windows
Firewall to the mobile hotspot gateway and do not publish port 8098.
For production use WSS/TLS and short-lived user-scoped authentication.

### Test and acceptance

- Test Tamil → English; speak 3–5 seconds; pause >= 0.5s.
- Confirm the **actual original transcript** and **English translation**,
  then tap the speaker to play generated English WAV.
- Stop, switch English → Tamil and repeat. Assess Tamil pronunciation.
- Confirm the Android microphone indicator turns off after Stop.
- Record measured server processing milliseconds separately from actual
  speech-end → caption/audio latency.
- Never present old MOCK fixture captions as real AI translations.

If Gemini returns HTTP 400/403/404/429, inspect text/TTS model access,
quota, API key and model name. The gateway intentionally does not expose
provider response bodies or secrets in mobile errors or logs.

**Status:** Python Gemini mock-transport tests are written but have not yet
been executed on the user's Windows machine. No cloud Gemini request or
real phone C2 PASS is claimed. The prior 7/7 pytest result belonged to
the earlier OpenAI-oriented implementation; rerun `pytest gateway -q`.

Reference:
- https://ai.google.dev/api/generate-content
- https://ai.google.dev/gemini-api/docs/speech-generation
- https://ai.google.dev/gemini-api/docs/api-key
