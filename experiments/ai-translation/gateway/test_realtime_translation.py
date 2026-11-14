"""Realtime caption contract tests. No cloud calls or paid usage."""
import asyncio
import base64
import json
from array import array

from fastapi.testclient import TestClient

from gateway.app import app
from gateway.realtime_translation import LiveCaptionState
import gateway.realtime_translation as realtime


class FakeRealtimeUpstream:
    def __init__(self):
        self.events = asyncio.Queue()
        self.sent = []
        self.audio_frames = 0
        # Invalid provider voice delta intentionally tests that caption-only
        # mode ignores audio. The audio-streaming fixture overrides this.
        self.send_dummy_invalid_audio = True

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def send(self, message):
        event = json.loads(message)
        self.sent.append(event)
        if event["type"] == "session.update":
            assert event["session"]["audio"]["output"]["language"] in ("ta", "en")
            assert event["session"]["audio"]["input"]["transcription"]["model"] == "gpt-realtime-whisper"
            await self.events.put(json.dumps({
                "type": "session.updated", "session": {"type": "translation"}
            }))
        elif event["type"] == "session.input_audio_buffer.append":
            assert len(base64.b64decode(event["audio"])) == 9600
            self.audio_frames += 1
            if self.audio_frames == 1:
                await self.events.put(json.dumps({
                    "type": "session.input_transcript.delta",
                    "delta": "வண", "elapsed_ms": 200,
                }))
                await self.events.put(json.dumps({
                    "type": "session.output_transcript.delta",
                    "delta": "Hel", "elapsed_ms": 200,
                }))
                await self.events.put(json.dumps({
                    "type": "session.input_transcript.delta",
                    "delta": "க்கம்", "elapsed_ms": 400,
                }))
                await self.events.put(json.dumps({
                    "type": "session.output_transcript.delta",
                    "delta": "lo", "elapsed_ms": 400,
                }))
                # Phase 1 must discard even invalid audio rather than
                # interpreting it; Phase 2 fake sends only valid PCM16.
                if self.send_dummy_invalid_audio:
                    await self.events.put(json.dumps({
                        "type": "session.output_audio.delta", "delta": "AA=="
                    }))
        elif event["type"] == "session.close":
            await self.events.put(json.dumps({"type": "session.closed"}))

    async def recv(self):
        return await self.events.get()


def test_append_only_delta_without_extra_spaces_and_bounded_memory():
    s = LiveCaptionState(max_chars=20)
    assert s.accept("session.input_transcript.delta", "வண")
    assert s.accept("session.input_transcript.delta", "க்கம்")
    assert s.original == "வணக்கம்"
    s.accept("session.output_transcript.delta", "Hel")
    s.accept("session.output_transcript.delta", "lo")
    assert s.translated == "Hello"
    assert s.payload(400)["segment_id"] == "live-current"
    assert s.payload(400)["caption_only"] is True
    assert s.payload(400)["audio_elapsed_ms"] == 400
    assert s.accept("session.output_audio.delta", "AA==") is False
    s.accept("session.output_transcript.delta", "abcdefghijklmnopqrstuvwxyz")
    # Reject oversized chunks, even though normal provider deltas are smaller.
    assert len(s.translated) == 20
    assert s.translated == "ghijklmnopqrstuvwxyz"


def test_fake_realtime_ws_deltas_flow_during_microphone_capture(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-provider-key")
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "realtime")
    monkeypatch.setenv("BT_AI_REALTIME_VOICE", "0")
    fake = FakeRealtimeUpstream()

    def fake_connect(url, **kwargs):
        assert "gpt-realtime-translate" in url
        assert kwargs["additional_headers"]["Authorization"] == "Bearer fake-provider-key"
        return fake

    monkeypatch.setattr(realtime, "connect", fake_connect)
    with TestClient(app) as client:
        with client.websocket_connect("/v1/stream") as ws:
            ws.send_json({
                "type": "start", "source_mode": "MIC",
                "source_language": "ta", "target_language": "en",
                "format": "pcm_s16le", "sample_rate_hz": 16000,
                "channels": 1, "ephemeral_session_token": "unit-test-token",
            })
            ready = ws.receive_json()
            assert ready["type"] == "ready"
            assert ready["caption_only"] is True
            assert ready["provider"] == "realtime-translation-captions"
            assert ready["audio_frame_ms"] == 200

            # The stateful interpolator may be one sample short after 200ms,
            # so send 300ms to guarantee the first complete 24kHz frame.
            mic_pcm = array("h", [1000] * 1600).tobytes()
            for sequence in (1, 2, 3):
                ws.send_json({
                    "type": "audio", "source_mode": "MIC",
                    "sequence": sequence,
                    "pcm16_base64": base64.b64encode(mic_pcm).decode("ascii"),
                })
            updates = []
            for _ in range(5):
                message = ws.receive_json()
                assert message["type"] == "segment"
                updates.append(message)
                if message["translated_text"] == "Hello":
                    break
            assert updates[-1]["translated_text"] == "Hello"
            assert updates[-1]["original_text"] == "வணக்கம்"
            assert updates[-1]["caption_only"] is True
            assert all(u["segment_id"] == "live-current" for u in updates)
            assert not any("translated_audio_base64" in u for u in updates)
            # We have not stopped; caption arrived while mic was active.
            assert fake.audio_frames >= 1
            ws.send_json({"type": "stop"})
    assert any(msg["type"] == "session.close" for msg in fake.sent)


def test_realtime_rejects_media_even_when_realtime_enabled(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-provider-key")
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "realtime")
    monkeypatch.setenv("BT_AI_REALTIME_VOICE", "0")
    with TestClient(app) as client:
        with client.websocket_connect("/v1/stream") as ws:
            ws.send_json({
                "type": "start", "source_mode": "MEDIA",
                "source_language": "ta", "target_language": "en",
                "format": "pcm_s16le", "sample_rate_hz": 16000,
                "channels": 1, "ephemeral_session_token": "unit-test-token",
            })
            error = ws.receive_json()
            assert error["type"] == "error"
            assert "Unauthorized MIC" in error["message"]


class FakeRealtimeWithAudio(FakeRealtimeUpstream):
    def __init__(self):
        super().__init__()
        self.send_dummy_invalid_audio = False

    async def send(self, message):
        event = json.loads(message)
        # Append audio after the first frame while connection remains active.
        if event["type"] == "session.input_audio_buffer.append":
            await super().send(message)
            if self.audio_frames == 1:
                await self.events.put(json.dumps({
                    "type": "session.output_audio.delta",
                    "format": "pcm16", "sample_rate": 24000, "channels": 1,
                    "elapsed_ms": 200,
                    "delta": base64.b64encode(bytes(19200)).decode("ascii"),
                }))
            return
        await super().send(message)


def test_realtime_streams_real_pcm_audio_as_bounded_frames(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-provider-key")
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "realtime")
    monkeypatch.setenv("BT_AI_REALTIME_VOICE", "1")
    fake = FakeRealtimeWithAudio()
    monkeypatch.setattr(realtime, "connect", lambda *a, **k: fake)

    with TestClient(app) as client:
        with client.websocket_connect("/v1/stream") as ws:
            ws.send_json({
                "type": "start", "source_mode": "MIC",
                "source_language": "ta", "target_language": "en",
                "format": "pcm_s16le", "sample_rate_hz": 16000,
                "channels": 1, "ephemeral_session_token": "unit-test-token",
            })
            ready = ws.receive_json()
            assert ready["streaming_audio"] is True
            assert ready["caption_only"] is False
            mic_pcm = array("h", [1000] * 1600).tobytes()
            for seq in (1, 2, 3):
                ws.send_json({
                    "type": "audio", "source_mode": "MIC", "sequence": seq,
                    "pcm16_base64": base64.b64encode(mic_pcm).decode("ascii"),
                })
            audio = []
            for _ in range(8):
                payload = ws.receive_json()
                if payload["type"] == "audio_delta":
                    audio.append(payload)
                    if len(audio) == 2:
                        break
            assert [a["sequence"] for a in audio] == [1, 2]
            assert all(a["sample_rate_hz"] == 24000 for a in audio)
            assert all(a["channels"] == 1 and a["encoding"] == "pcm_s16le"
                       for a in audio)
            assert all(len(base64.b64decode(a["pcm16_base64"])) == 9600
                       for a in audio)
            ws.send_json({"type": "stop"})


def test_realtime_off_remains_caption_only(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-provider-key")
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "realtime")
    monkeypatch.setenv("BT_AI_REALTIME_VOICE", "0")
    fake = FakeRealtimeWithAudio()
    monkeypatch.setattr(realtime, "connect", lambda *a, **k: fake)
    with TestClient(app) as client:
        with client.websocket_connect("/v1/stream") as ws:
            ws.send_json({
                "type": "start", "source_mode": "MIC",
                "source_language": "ta", "target_language": "en",
                "format": "pcm_s16le", "sample_rate_hz": 16000,
                "channels": 1, "ephemeral_session_token": "unit-test-token",
            })
            ready = ws.receive_json()
            assert ready["streaming_audio"] is False
            assert ready["caption_only"] is True
            mic_pcm = array("h", [1000] * 1600).tobytes()
            for seq in (1, 2, 3):
                ws.send_json({
                    "type": "audio", "source_mode": "MIC", "sequence": seq,
                    "pcm16_base64": base64.b64encode(mic_pcm).decode("ascii"),
                })
            updates = []
            for _ in range(6):
                msg = ws.receive_json()
                assert msg["type"] != "audio_delta"
                updates.append(msg)
                if msg.get("translated_text") == "Hello":
                    break
            assert any(m.get("translated_text") == "Hello" for m in updates)
            ws.send_json({"type": "stop"})
