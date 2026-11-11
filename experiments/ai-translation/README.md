# BT SHARE · Member 3 · MIC-only Flutter Translation POC

**Branch:** `Sihan-Member-3` · **Owner:** Member 3 · **Scope:** `experiments/ai-translation/**`

This is a **proof of concept**, not a shipping app. The Dart and Android implementation sources are committed; generated Flutter toolchain and Android template files must be bootstrapped on a machine with Flutter/Android SDK. The user reported successfully building a debug APK and testing the MOCK application on Redmi Note 9 Pro for 03:56 with no errors. No live C2 AI-provider test has yet been observed; Flutter build and device tooling are unavailable inside the assistant runtime.

## Features and truth in UI

- MIC ONLY, Tamil ↔ English, original transcript and translated caption history.
- Captures PCM16 mono 16 kHz microphone chunks with runtime permission. No playback capture, no MEDIA input, no video capture.
- `mock` demo is functional without cloud credentials, **but deliberately does not transcribe the user's words**. Every ~48,000 input PCM bytes trigger a pre-scripted fixture. All mock captions are labelled **MOCK**; device text-to-speech reading the fixture is **not cloud-generated model speech**.
- `gateway` mode connects to **your independently deployed** secure WebSocket gateway; the gateway is NOT included here. Supports translated audio returned as encoded WAV/MP3 and captions.
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

Local development example:
```powershell
flutter run --dart-define=BT_AI_PROVIDER=gateway --dart-define=BT_AI_WS_URL=ws://10.0.2.2:8080/v1/stream
```
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
- A backend has not been integrated or validated; real translation, latency, Tamil speech quality and model TTS are **not verified**.
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

Evidence C0–C5 lives at `../../evidence/ai-translation/`. All are **NOT_RUN_PHYSICAL** until reproducible device observations exist.

## Contribution policy

Work only on `Sihan-Member-3` and these two owned paths. Verify active branch and diff; never push to `development` or `main`; do not open/merge a PR without explicit request. Real integration must be reviewed by central coordinator.


## C2: Test real Tamil/English speech with a local Python gateway

The new gateway/ server uses three billable API calls per utterance:
gpt-transcribe -> gpt-4.1-mini -> gpt-4o-mini-tts. This is segmented
near-live output, not gpt-realtime-translate (no Tamil output). Device
audio and transcripts are not saved by the POC.

From the Flutter project directory, PowerShell:
```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r gateway\requirements.txt
.\.venv\Scripts\python.exe -m pytest gateway -q
$env:OPENAI_API_KEY = Read-Host "OpenAI API key (keep private)"
$env:BT_DEV_GATEWAY_TOKEN = Read-Host "Set a temporary dev gateway token"
.\.venv\Scripts\python.exe -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098
```

Use the SAME trusted private Wi-Fi on PC and Android. Obtain PC LAN IP
from ipconfig. In a second terminal, create local_gateway.json in
experiments/ai-translation/ (Git-ignored):
```json
{
  "BT_AI_PROVIDER":"gateway",
  "BT_AI_WS_URL":"ws://192.168.1.20:8098/v1/stream",
  "BT_AI_ALLOW_INSECURE_DEV_LAN":"true",
  "BT_AI_SESSION_TOKEN":"MATCH_THE_TEMP_TEST_TOKEN"
}
```
Replace 192.168.1.20 with the actual PC IPv4; 127.0.0.1 on the phone
means the phone itself and will not reach the PC. The mobile dev token
is NOT secure for production, so do not distribute this APK. Allow
port 8098 only on a private LAN, never the public Internet.

Run local manifest update **only for debug builds**:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\enable_debug_lan_ws.ps1
flutter run -d f7d152a --dart-define-from-file=local_gateway.json
```
Consent to cloud mic processing in the app before pressing Start.
Speak a clear sentence and pause at least 0.5s to trigger a chunk.
Try both directions. Use headphones to prevent feedback. If the
upstream API key/quota/model access fails the app should report
AI unavailable independently from local room status. MOCK remains:
```powershell
flutter run -d f7d152a --dart-define=BT_AI_PROVIDER=mock
```

TTS and accuracy in Tamil MUST be reviewed on-device: server processing
milliseconds exclude microphone buffering and network. No cloud AI
calls, mobile builds or physical C2 observations were executed here.
