# BT-SHARE Bluetooth Relay — B0

B0 is a two-phone feasibility application for Bluetooth Classic RFCOMM. The same APK can
listen as a secure server or connect as a secure client to an already-paired Android phone.

## Protocol

Every record uses network byte order:

| Field | Size | Meaning |
|---|---:|---|
| Frame body length | 4 bytes | Bytes after this prefix; validated before allocation |
| Version | 1 byte | `1` |
| Type | 1 byte | `1` DATA, `2` ACK |
| Sequence | 8 bytes | Positive sender-local sequence number |
| Payload | variable | UTF-8 text for DATA; empty for ACK |

Frame bodies are capped at 64 KiB. A DATA receiver immediately returns an ACK carrying the
same sequence. The sender measures RTT from a monotonic local clock, so phone clocks do not
need to be synchronized.

## Build and test

Prerequisites: JDK 17 or newer and Android SDK Platform 36 with Build Tools 36.x.

```powershell
./gradlew.bat :protocol:test assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
The `protocol` Kotlin/JVM module contains the framing, ACK, sequence, and RTT logic so its
unit tests can also run on a development machine before an Android SDK is installed.

## Two-phone procedure

1. Pair both phones in Android system Bluetooth settings.
2. Install the same debug APK on both phones and grant Nearby devices permission.
3. On phone A, tap **Listen as server**.
4. On phone B, tap **Refresh paired**, select phone A, then tap **Connect**.
5. Send text in either direction. Confirm DATA, ACK, sequence, and RTT entries in each log.
6. Disconnect, reconnect with roles reversed, and repeat.

The app deliberately does not discover or pair devices. Pairing stays in trusted Android
system UI, and all application sockets use the secure authenticated RFCOMM APIs.
