# BT SHARE — gateway startup / disconnect diagnosis

## Server stops vs WebSocket closes

- If PowerShell returns to its prompt, the **Uvicorn process stopped** (for example Ctrl+C, Python exception, port conflict or terminal closure).
- If the Python server still prints "Uvicorn running ..." and logs "WebSocket connection closed", only **one WebSocket session closed**. This can happen normally when Stop Translation is pressed, the phone is disconnected or the gateway rejects a request.
- The 10-minute experimental session limit can close a long-running translation; it is not an unlimited always-on session.
- A genuine TTS HTTP API failure, invalid token, caption-text backlog, PCM queue timeout or Android network loss can also terminate a session. The exact cause requires a sanitized error log; do not guess based on "closed" alone.

## Full restart

Open PowerShell at `G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation` and run:
```powershell
git pull --ff-only origin Sihan-Member-3
& "G:\BT-Share\.venv\Scripts\python.exe" -m pytest gateway -q
flutter analyze
flutter test
powershell -ExecutionPolicy Bypass -File .\scripts\start_caption_tts_server.ps1
```

Leave that server terminal open, then open a second terminal:
```powershell
cd "G:\BT-Share\BT-SHARE\BT-SHARE\experiments\ai-translation"
powershell -ExecutionPolicy Bypass -File .\scripts\run_caption_tts_flutter.ps1
```

The scripts neither store the API key nor echo it; the key is entered
secretly when needed. `local_gateway.json` must remain private. Do not paste
its contents in chat or commit it. This uses the existing local ephemeral
token and physical Android device, not production auth.

## Evidence for debugging

When the mobile app says WebSocket disconnected, copy only the terminal
lines containing the failure category and status code. Redact keys,
session tokens, IPs or any transcribed text. No API credentials are
required to analyze connection status.

The server script does not kill any existing process. If port 8098 is
already occupied, inspect and stop only the known BT SHARE server.
