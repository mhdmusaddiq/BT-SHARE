"""All network calls are intercepted: no API key, real speech or charges."""
from array import array
import httpx
import pytest
from gateway.app import SpeechChunker, TranslationPipeline

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

def test_fast_mode_releases_after_300ms_silence(monkeypatch):
    monkeypatch.setenv("BT_AI_LATENCY_MODE", "fast")
    chunker = SpeechChunker()
    voiced = array("h", [1500] * 1600).tobytes()
    for _ in range(12):
        assert chunker.add(voiced) == []
    for _ in range(2):
        assert chunker.add(bytes(3200)) == []
    assert len(chunker.add(bytes(3200))) == 1


def test_stable_mode_preserves_500ms_pause():
    chunker = SpeechChunker(mode="stable")
    voiced = array("h", [1500] * 1600).tobytes()
    for _ in range(12):
        assert chunker.add(voiced) == []
    for _ in range(4):
        assert chunker.add(bytes(3200)) == []
    assert len(chunker.add(bytes(3200))) == 1


def test_bad_latency_mode_fails_safe():
    with pytest.raises(ValueError):
        SpeechChunker(mode="extreme")


@pytest.mark.asyncio
async def test_text_reaches_client_before_tts():
    events = []

    def respond(request):
        if request.url.path.endswith("/audio/transcriptions"):
            events.append("stt")
            return httpx.Response(200, json={"text": "வணக்கம்"})
        if request.url.path.endswith("/chat/completions"):
            events.append("translation")
            return httpx.Response(200, json={
                "choices": [{"message": {"content": "Hello"}}]
            })
        if request.url.path.endswith("/audio/speech"):
            events.append("tts")
            return httpx.Response(200, content=b"ID3" + bytes(100))
        return httpx.Response(404)

    async def on_text(original, translated):
        events.append("caption")
        assert (original, translated) == ("வணக்கம்", "Hello")

    metrics = {}
    async with httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(respond),
    ) as client:
        p = TranslationPipeline("fake-test-token", client)
        original, translated, wav = await p.translate(
            array("h", [1500] * 32000).tobytes(), "ta", "en",
            on_text=on_text, metrics=metrics,
        )
    assert events == ["stt", "translation", "caption", "tts"]
    assert set(metrics) == {"stt", "translation", "tts"}
    assert all(value >= 0 for value in metrics.values())
    assert (original, translated) == ("வணக்கம்", "Hello")
    assert wav.startswith(b"ID3")


@pytest.mark.asyncio
async def test_fake_api_pipeline_returns_transcript_translation_and_voice():
    paths = []
    def respond(request):
        paths.append(request.url.path)
        if request.url.path.endswith("/audio/transcriptions"):
            return httpx.Response(200, json={"text":"வணக்கம்"})
        if request.url.path.endswith("/chat/completions"):
            return httpx.Response(200, json={
                "choices":[{"message":{"content":"Hello"}}]
            })
        if request.url.path.endswith("/audio/speech"):
            assert b'"response_format":"mp3"' in request.content
            return httpx.Response(200, content=b"ID3" + bytes(100))
        return httpx.Response(404)
    async with httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(respond),
    ) as http:
        pipeline = TranslationPipeline("fake-test-token", http)
        original, translated, audio = await pipeline.translate(
            array("h", [1500] * 32000).tobytes(), "ta", "en")
    assert (original, translated) == ("வணக்கம்", "Hello")
    assert audio.startswith(b"ID3")
    assert paths == [
        "/v1/audio/transcriptions", "/v1/chat/completions", "/v1/audio/speech"
    ]
