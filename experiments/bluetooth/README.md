# BT-SHARE Bluetooth Relay — two-phone audio POC

This experiment preserves the B0 text/ACK/RTT workflow and adds a two-phone microphone audio
proof of concept over the same secure Bluetooth Classic RFCOMM connection. It records only a
user-initiated voice clip; it does not capture system audio or Spotify/YouTube playback.

## Protocol

Every record uses network byte order:

| Field | Size | Meaning |
|---|---:|---|
| Frame body length | 4 bytes | Bytes after this prefix; validated before allocation |
| Version | 1 byte | `1` |
| Type | 1 byte | `1` DATA, `2` ACK, `3` AUDIO_START, `4` AUDIO_CHUNK, `5` AUDIO_END |
| Sequence | 8 bytes | Positive sender-local sequence number |
| Payload | variable | Type-specific content |

Frame bodies are capped at 64 KiB. A DATA receiver immediately returns an ACK carrying the
same sequence. The sender measures RTT from a monotonic local clock, so phone clocks do not
need to be synchronized. Audio packets do not change DATA or ACK behavior and are never sent
through the UTF-8 decoder.

### Audio format and payloads

The initial format is 16,000 Hz, mono, signed 16-bit little-endian PCM (32,000 bytes/second).
The recording target is 5 seconds or 160,000 bytes. PCM chunks default to 4,096 bytes and are
limited to 8,192 bytes.

- `AUDIO_START`: 19-byte metadata payload containing metadata version (`1`), sample rate,
  channel count, encoding (`1` = PCM16LE), declared PCM byte count, and chunk size.
- `AUDIO_CHUNK`: stream ID, zero-based chunk index, then an even non-empty PCM payload.
- `AUDIO_END`: stream ID, chunk count, transmitted PCM byte count, and completion reason.

The receiver validates metadata and lengths, counts missing/duplicate/out-of-order chunks,
and offers chunks to a bounded queue. A dedicated `AudioTrack` worker uses `MODE_STREAM`, so
speaker playback never blocks the RFCOMM read loop or Android UI thread.

## Build and test

Prerequisites: JDK 17 or newer and Android SDK Platform 36 with Build Tools 36.x.

```powershell
./gradlew.bat :protocol:test :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Two-phone text and audio procedure

1. Pair both phones in Android system Bluetooth settings.
2. Install the same debug APK on both phones and grant Nearby devices and microphone
   permissions when requested.
3. On phone A, tap **Listen as server**.
4. On phone B, tap **Refresh paired**, select phone A, then tap **Connect**.
5. Send text in either direction. Confirm DATA, ACK, sequence, and RTT entries in each log.
6. On the sending phone, tap **Record Voice**, speak for 5 seconds, then tap
   **Play Local Recording** to verify capture.
7. Raise the receiving phone's media volume and tap **Send Audio**. Confirm audible playback
   and `AUDIO_START`/`AUDIO_END` logs with chunk, byte, duration, discontinuity, and underrun
   counts. Use **Stop Audio** to test cancellation and cleanup.
8. Disconnect, reconnect with roles reversed, and repeat the text and audio checks.

The app deliberately does not discover or pair devices. Pairing stays in trusted Android
system UI, and all application sockets use the secure authenticated RFCOMM APIs.
