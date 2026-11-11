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
            return httpx.Response(200, content=b"RIFF" + bytes(100))
        return httpx.Response(404)
    async with httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(respond),
    ) as http:
        pipeline = TranslationPipeline("fake-test-token", http)
        original, translated, audio = await pipeline.translate(
            array("h", [1500] * 32000).tobytes(), "ta", "en")
    assert (original, translated) == ("வணக்கம்", "Hello")
    assert audio.startswith(b"RIFF")
    assert paths == [
        "/v1/audio/transcriptions", "/v1/chat/completions", "/v1/audio/speech"
    ]
