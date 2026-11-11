# B0 Bluetooth Relay test evidence

Recorded on 2026-11-11 from branch `poc/bluetooth-relay` in
`C:\Users\User\Documents\BT-SHARE`.

## Environment

- Host: Windows 11
- Java: OpenJDK 21.0.12.1 LTS
- Gradle: 8.13, verified against the official distribution SHA-256
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`
- Android SDK: not installed on this host; a project-local empty SDK path was used to
  confirm the exact build prerequisite failure

## Automated results

### Protocol unit tests — PASS

Command executed from `experiments/bluetooth/`:

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user-home')
$env:ANDROID_USER_HOME = (Join-Path (Get-Location) '.android-user-home')
$env:ANDROID_HOME = (Join-Path (Get-Location) '.android-sdk')
& '.\.gradle-user-home\distribution\gradle-8.13\bin\gradle.bat' `
    --no-daemon --offline --rerun-tasks :protocol:test
```

Actual Gradle outcome: `BUILD SUCCESSFUL`.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `PacketCodecTest` | 7 | 0 | 0 | 0 |
| `RelaySessionTest` | 4 | 0 | 0 | 0 |
| `RttTrackerTest` | 4 | 0 | 0 | 0 |
| **Total** | **15** | **0** | **0** | **0** |

The tests exercised Unicode and adjacent frame round trips, clean EOF, invalid and
oversized lengths, truncated bodies, illegal ACK payloads, sequence tracking, duplicate
and unknown ACKs, RTT calculation, DATA-to-ACK behavior, and malformed UTF-8 rejection.

### Android debug assembly — BLOCKED_ENVIRONMENT

Command executed from `experiments/bluetooth/`:

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user-home')
$env:ANDROID_USER_HOME = (Join-Path (Get-Location) '.android-user-home')
$env:ANDROID_HOME = (Join-Path (Get-Location) '.android-sdk')
& '.\.gradle-user-home\distribution\gradle-8.13\bin\gradle.bat' --no-daemon --offline assembleDebug
```

Actual Gradle outcome: `BUILD FAILED` before Android source compilation because the host
does not contain SDK Platform 36:

```text
Failed to find target with hash string 'android-36'
```

This is an environment prerequisite failure, not a passing build. No APK was produced.

## Physical-device verification

**NOT_RUN_PHYSICAL** — no two-phone Bluetooth Classic RFCOMM test has been performed yet.

Required next physical checks:

- Install the debug APK on two Bluetooth Classic-capable Android phones.
- Pair the phones through Android system settings and grant Nearby devices permission.
- Verify server listen and paired-device client connect in both role directions.
- Exchange multiple messages in both directions and capture DATA sequence, matching ACK,
  and measured RTT log lines.
- Verify disconnect, reconnect, permission denial, Bluetooth-disabled, and peer-loss behavior.

## Physical B0 validation — 12 November 2026

Devices: HONOR X6c and A17.

- Secure RFCOMM connection established successfully.
- Bidirectional framed data and ACK exchange observed.
- Receiver displayed consecutive sequence numbers 1–51.
- A 5-minute stable connection was reported.
- One successful reconnect with message delivery was observed.
- RTT measurements were displayed.
- Earlier repeated connection failures were observed; reliability requires additional investigation.
- Full sender-side ACK reconciliation and exact packet-loss calculation remain pending.

Decision: PASS for basic B0 connection and data-transfer feasibility. Extended reliability validation remains open.