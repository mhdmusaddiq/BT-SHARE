"""Contract tests with a fully fake Gemini HTTP transport; no cloud calls."""
import base64
from array import array
import io
import json
import wave

import httpx
import pytest

from gateway.app import SpeechChunker
from gateway.gemini_provider import (
    GeminiTranslationPipeline,
    _interaction_audio,
    wav_16k,
)
from gateway.protocol import ProtocolError


def valid_wav() -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as sound:
        sound.setnchannels(1)
        sound.setsampwidth(2)
        sound.setframerate(24000)
        sound.writeframes(bytes(48000))
    return buffer.getvalue()


def test_speech_and_trailing_silence_yield_chunk():
    chunker = SpeechChunker()
    voiced = array("h", [1500] * 1600).tobytes()
    results = []
    for _ in range(12):
        results.extend(chunker.add(voiced))
    for _ in range(6):
        results.extend(chunker.add(bytes(3200)))
    assert len(results) == 1
    assert len(results[0]) >= 16000


def test_only_silence_is_not_uploaded():
    chunker = SpeechChunker()
    for _ in range(55):
        assert chunker.add(bytes(3200)) == []
    assert chunker.finish() is None


def test_wav_input_pcm_format():
    wav = wav_16k(array("h", [1024] * 16000).tobytes())
    with wave.open(io.BytesIO(wav), "rb") as sound:
        assert (sound.getframerate(), sound.getnchannels(), sound.getsampwidth()) == (16000, 1, 2)


@pytest.mark.parametrize("source,target,original,translated", [
    ("ta", "en", "வணக்கம்", "Hello"),
    ("en", "ta", "Good morning", "காலை வணக்கம்"),
])
@pytest.mark.asyncio
async def test_gemini_pipeline_bilingual_no_real_calls(
    source, target, original, translated, monkeypatch
):
    monkeypatch.setenv("GEMINI_TEXT_MODEL", "gemini-3.8-flash")
    monkeypatch.setenv("GEMINI_TTS_MODEL", "gemini-3.8-flash-lite-tts")
    requests = []

    def respond(request: httpx.Request):
        assert request.headers["x-goog-api-key"] == "fake-test-key"
        requests.append((request.url.path, json.loads(request.content)))
        if request.url.path.endswith(":generateContent"):
            body = requests[-1][1]
            encoded = body["contents"][0]["parts"][1]["inlineData"]["data"]
            with wave.open(io.BytesIO(base64.b64decode(encoded)), "rb") as wav:
                assert wav.getframerate() == 16000
            return httpx.Response(200, json={
                "candidates": [{"content": {"parts": [{"text": json.dumps({
                    "original_text": original,
                    "translated_text": translated,
                }, ensure_ascii=False)}]}}]
            })
        if request.url.path.endswith("/interactions"):
            body = requests[-1][1]
            assert body["input"][0]["content"][0]["text"] == translated
            assert body["response_format"]["mime_type"] == "audio/wav"
            return httpx.Response(200, json={
                "steps": [{"type": "model_output", "content": [
                    {"type": "audio", "data": base64.b64encode(valid_wav()).decode()}
                ]}]
            })
        return httpx.Response(404)

    async with httpx.AsyncClient(
        base_url="https://generativelanguage.googleapis.com",
        headers={"x-goog-api-key": "fake-test-key"},
        transport=httpx.MockTransport(respond),
    ) as client:
        pipeline = GeminiTranslationPipeline("fake-test-key", client)
        actual = await pipeline.translate(
            array("h", [1200] * 16000).tobytes(), source, target
        )
    assert actual[:2] == (original, translated)
    assert actual[2].startswith(b"RIFF")
    assert len(requests) == 2
    assert requests[0][0] == "/v1beta/models/gemini-3.8-flash:generateContent"
    assert requests[1][0] == "/v1beta/interactions"


def test_audio_rejects_invalid_response():
    with pytest.raises(ProtocolError):
        _interaction_audio({"steps": [{"type": "model_output", "content": [
            {"type": "audio", "data": base64.b64encode(b"not a wav").decode()}
        ]}]})


@pytest.mark.asyncio
async def test_gemini_empty_original_skips_tts():
    paths = []
    def respond(request):
        paths.append(request.url.path)
        return httpx.Response(200, json={
            "candidates": [{"content": {"parts": [{"text":
                '{"original_text":"","translated_text":""}'
            }]}}]
        })
    async with httpx.AsyncClient(
        base_url="https://generativelanguage.googleapis.com",
        transport=httpx.MockTransport(respond),
    ) as client:
        pipeline = GeminiTranslationPipeline("fake-test-key", client)
        assert await pipeline.translate(
            array("h", [0] * 16000).tobytes(), "en", "ta"
        ) == ("", "", b"")
    assert len(paths) == 1
