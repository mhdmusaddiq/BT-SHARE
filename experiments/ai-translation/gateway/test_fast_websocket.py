"""Local WebSocket contract tests; uses no provider API calls or credentials."""
import base64
from array import array

from fastapi.testclient import TestClient

from gateway.app import app, TranslationPipeline


def test_fast_websocket_sends_caption_before_voice(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-no-charge-key")
    # Explicitly select the mode under test; do not inherit a running
    # realtime gateway setting from the developer PowerShell process.
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "segmented")
    monkeypatch.setenv("BT_AI_LATENCY_MODE", "fast")
    monkeypatch.setenv("BT_AI_TTS_FORMAT", "mp3")
    calls = []

    async def fake_translate(self, pcm, source, target, on_text=None, metrics=None):
        assert len(pcm) >= 16000
        assert (source, target) == ("ta", "en")
        assert on_text is not None
        calls.append("translation")
        await on_text("வணக்கம்", "Hello")
        calls.append("voice")
        if metrics is not None:
            metrics.update({"stt": 1, "translation": 2, "tts": 3})
        return "வணக்கம்", "Hello", b"ID3" + bytes(100)

    monkeypatch.setattr(TranslationPipeline, "translate", fake_translate)

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
            assert ready["latency_mode"] == "fast"
            assert ready["tts_audio_format"] == "mp3"

            voiced = array("h", [1500] * 1600).tobytes()
            for sequence in range(1, 16):
                # First 12 frames voice, then three frames of silence.
                pcm = voiced if sequence <= 12 else bytes(3200)
                ws.send_json({
                    "type": "audio", "source_mode": "MIC",
                    "sequence": sequence,
                    "pcm16_base64": base64.b64encode(pcm).decode("ascii"),
                })

            caption = ws.receive_json()
            voice = ws.receive_json()
            assert caption["type"] == voice["type"] == "segment"
            assert caption["segment_id"] == voice["segment_id"] == "cloud-1"
            assert caption["original_text"] == "வணக்கம்"
            assert caption["translated_text"] == "Hello"
            assert "translated_audio_base64" not in caption
            assert voice["audio_mime_type"] == "audio/mpeg"
            assert base64.b64decode(voice["translated_audio_base64"]).startswith(b"ID3")
            assert voice["stage_timings_ms"] == {
                "stt": 1, "translation": 2, "tts": 3
            }
            ws.send_json({"type": "stop"})
    assert calls == ["translation", "voice"]


def test_wav_rollback_mode_keeps_original_audio_contract(monkeypatch):
    monkeypatch.setenv("BT_DEV_GATEWAY_TOKEN", "unit-test-token")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-no-charge-key")
    # Explicitly select the mode under test; do not inherit a running
    # realtime gateway setting from the developer PowerShell process.
    monkeypatch.setenv("BT_AI_CAPTION_ENGINE", "segmented")
    monkeypatch.setenv("BT_AI_LATENCY_MODE", "stable")
    monkeypatch.setenv("BT_AI_TTS_FORMAT", "wav")

    async def fake_translate(self, pcm, source, target, on_text=None, metrics=None):
        assert self.audio_format == "wav"
        assert on_text is None
        return "வணக்கம்", "Hello", b"RIFF" + bytes(100)

    monkeypatch.setattr(TranslationPipeline, "translate", fake_translate)

    with TestClient(app) as client:
        with client.websocket_connect("/v1/stream") as ws:
            ws.send_json({
                "type": "start", "source_mode": "MIC",
                "source_language": "ta", "target_language": "en",
                "format": "pcm_s16le", "sample_rate_hz": 16000,
                "channels": 1, "ephemeral_session_token": "unit-test-token",
            })
            ready = ws.receive_json()
            assert ready["tts_audio_format"] == "wav"
            assert ready["latency_mode"] == "stable"
            voiced = array("h", [1500] * 1600).tobytes()
            for sequence in range(1, 18):
                pcm = voiced if sequence <= 12 else bytes(3200)
                ws.send_json({
                    "type": "audio", "source_mode": "MIC",
                    "sequence": sequence,
                    "pcm16_base64": base64.b64encode(pcm).decode("ascii"),
                })
            voice = ws.receive_json()
            assert voice["audio_mime_type"] == "audio/wav"
            assert "translated_audio_base64" in voice
            ws.send_json({"type": "stop"})
