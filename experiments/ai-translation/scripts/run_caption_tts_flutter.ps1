# BT SHARE — device reconnect and Flutter test
# Run AFTER starting Python gateway in a separate terminal.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location $project

$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "ADB not found: $adb" }
if (-not (Test-Path '.\local_gateway.json')) { throw 'Missing local_gateway.json' }
$devices = & $adb devices
Write-Host ($devices -join [Environment]::NewLine)
if ($LASTEXITCODE -ne 0) { throw 'ADB failed' }
$serial = 'f7d152a'
if (-not ($devices -match "^$serial\s+device$")) {
    throw 'Device not online. Connect USB, unlock phone, approve USB debugging.'
}
& $adb -s $serial reverse tcp:8098 tcp:8098
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed' }

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8098/health/live' -TimeoutSec 6
    if ($health.status -ne 'live') { throw 'Unexpected gateway health response' }
} catch {
    throw 'Python gateway health check failed. Run server script in first terminal.'
}

Write-Host 'Gateway healthy. Starting Flutter...' -ForegroundColor Green
Write-Host 'In app Settings: Caption -> Fixed Voice TTS; voice ON; Speak Automatically ON.'
flutter run -d $serial --dart-define-from-file=local_gateway.json --dart-define=BT_LIVE_VOICE_PREBUFFER_MS=450
