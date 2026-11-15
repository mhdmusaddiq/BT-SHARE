# Phase 2.3.4 — Distinguishing source output gaps from delivery backlog

## Device report

The user confirmed: live Tamil-to-English captions and English voice work,
the previous red buffer-overflow warning is absent, but voice still breaks
or changes and perceived delay varies from 2–6 seconds.

This report is **subjective device evidence**. The phase 2.2 first-event
timings do not measure acoustic speech-onset latency, repeated utterance
latency, or fixed voice identity.

## Implementation

The server now publishes optional, content-free `audio_metrics` events,
approximately once per second of output PCM, with:

- `pending_audio_ms`: duration of generated PCM still waiting for successful
  handoff to the phone socket. Includes a frame currently being delivered.
- `sent_audio_ms`: cumulative PCM duration successfully handed to the
  WebSocket. This is NOT heard/played duration.

Metrics are per authenticated session, use the same sender lock, and are
derived from a monotonic accounting of accepted vs sent PCM bytes. The
existing authenticated `audio_delta` message format and order are
unchanged. No audio bytes, speech transcripts or credentials are logged.

Flutter displays these values as `Gateway pending PCM` and `Sent PCM`
under its debug audio panel. It also counts `Audio gaps >400ms` and
their longest duration from consecutive phone PCM receipt times.

**Interpretation:**

1. `Gateway paced audio: ON` + growing `Gateway pending PCM` implies a
   generated-audio delivery backlog on the Python sender side. Even if
   Android's PCM buffer stays small, the voice may lag by seconds.
2. `Gateway pending PCM` close to 0 but `Native PCM Buffer` growing
   implies a phone audio consumption/backpressure problem.
3. Both queues small but `Audio gaps >400ms` increasing implies
   intermittent audio delivery. This can also happen during intentional
   silence / model pauses; it is not proof of packet loss.
4. Both queues small, no large gaps, but perceived delay remains 2–6s:
   investigate AI inference/phrase timing and speaker onset with additional
   per-utterance timestamps. The existing first-event chart does not isolate
   that delay.
5. Voice timbre changes across pauses are separate from buffering:
   the current direct speech-to-speech translation pipeline does not
   promise a fixed synthetic output voice. Fixed voice would require a
   separately evaluated text-to-speech path, with latency tradeoffs.

## Windows device test

Run from `experiments/ai-translation` after pulling
`Sihan-Member-3`. Stop old Uvicorn before restarting it in the terminal
containing existing privately configured key and session token:

```powershell
git pull --ff-only origin Sihan-Member-3
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test

$env:BT_AI_CAPTION_ENGINE = "realtime"
$env:BT_AI_REALTIME_VOICE = "1"
$env:BT_AI_DIAGNOSTICS = "1"
$env:BT_AI_PACE_OUTPUT_AUDIO = "1"
& "G:\BT-Share\.venv\Scripts\python.exe" -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098
```

In a second terminal, keep USB attached and re-enable ADB reverse:

```powershell
& "C:\Android\Sdk\platform-tools\adb.exe" -s f7d152a reverse tcp:8098 tcp:8098
flutter run -d f7d152a --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_ADAPTIVE_STARTUP=true --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=320
```

Speak continuously for 30 seconds; pause 2 seconds twice; then compare
`Gateway pending PCM`, `Native PCM Buffer`, `Audio gaps` and
`Underruns` while speaking. The metric does not require UI text content
or logs with sensitive values to be shared.

**Not yet verified on device or Windows**. No user-visible promise of
reduced latency: this is non-invasive telemetry. The next performance
change should be chosen from the actual queue and gap measurements.
The player still has its safety buffer cap. The Python PCM worker still
limits its queued frames to avoid silent word loss/unbounded memory.
