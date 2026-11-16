# BT SHARE — full fresh-start server script (local development only)
# Run from project directory:
# powershell -ExecutionPolicy Bypass -File .\scripts\start_caption_tts_server.ps1
# Open a new PowerShell window for Flutter. Leave this one open.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location $project

$python = 'G:\BT-Share\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) {
    throw "Python virtualenv not found: $python"
}
$configPath = Join-Path $project 'local_gateway.json'
if (-not (Test-Path $configPath)) {
    throw 'Missing local_gateway.json. Use the existing private local config; never commit it.'
}
$config = Get-Content $configPath -Raw | ConvertFrom-Json
$token = $config.BT_AI_SESSION_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw 'BT_AI_SESSION_TOKEN is missing from local_gateway.json'
}
$env:BT_DEV_GATEWAY_TOKEN = $token
Remove-Variable token, config

if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    $secret = Read-Host 'Enter OpenAI API key (hidden)' -AsSecureString
    $env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
    Remove-Variable secret
}
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    throw 'OPENAI_API_KEY is required'
}

$env:BT_AI_CAPTION_ENGINE = 'realtime'
$env:BT_AI_REALTIME_VOICE = '1'
$env:BT_AI_PACE_OUTPUT_AUDIO = '1'
$env:BT_AI_CAPTION_TTS_ENABLED = '1'
$env:BT_AI_DIAGNOSTICS = '1'
$env:BT_AI_OUTPUT_FRAME_MS = '100'

$listener = Get-NetTCPConnection -LocalPort 8098 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $processName = if ($process) { $process.ProcessName } else { 'unknown' }
    throw "Port 8098 is already in use by PID $($listener.OwningProcess) ($processName). Stop only the old BT SHARE server with Ctrl+C, or inspect the PID."
}

Write-Host 'BT SHARE Caption -> Fixed Voice TTS' -ForegroundColor Cyan
Write-Host 'Python gateway: http://127.0.0.1:8098'
Write-Host 'WebSocket: ws://127.0.0.1:8098/v1/stream'
Write-Host 'API key/token are configured in this terminal only; never paste them into chat.'
Write-Host 'Keep this terminal open; Ctrl+C stops Uvicorn.' -ForegroundColor Yellow

& $python -m uvicorn gateway.app:app --host 0.0.0.0 --port 8098 --log-level info
