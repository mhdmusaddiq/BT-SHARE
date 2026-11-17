"""Independent caption-to-TTS unit tests. No live cloud connections."""
import asyncio

import httpx
import pytest

from gateway.caption_tts import CaptionPhraseAccumulator, StreamingCaptionTts
from gateway.protocol import ProtocolError


def test_caption_deltas_are_spoken_once_with_no_missing_tail():
    now = [0.0]
    c = CaptionPhraseAccumulator(clock=lambda: now[0])
    assert c.add("Hel") == []
    assert c.add("lo friends. ") == ["Hello friends."]
    assert c.add("We are tes") == []
    assert c.add("ting a realtime translation service") == []
    now[0] = 0.9
    assert c.flush_idle() == ["We are testing a realtime translation service"]
    assert c.finish() == []


def test_long_phrases_split_only_at_whitespace_and_reassemble():
    c = CaptionPhraseAccumulator()
    text = ("The language translation service is working today and "
            "we are testing that no words are missing from its output. "
            "The next sentence should always remain in its original order.")
    chunks = []
    for step in range(0, len(text), 7):
        chunks.extend(c.add(text[step:step + 7]))
    chunks.extend(c.finish())
    assert " ".join(chunks).replace("  ", " ") == text


def test_empty_idle_and_text_length_rejection():
    now = [0.0]
    c = CaptionPhraseAccumulator(clock=lambda: now[0])
    assert c.flush_idle() == []
    c.add("Small ")
    now[0] = 0.5
    assert c.flush_idle() == []
    now[0] = 1.0
    assert c.flush_idle() == ["Small"]
    with pytest.raises(ProtocolError):
        c.add("x" * 2001)


class FragmentedPcm(httpx.AsyncByteStream):
    async def __aiter__(self):
        # Deliberately splits samples across transport boundaries.
        pcm = bytes([15, 2]) * 6000
        for n in (1, 199, 5901, 2899, 3000):
            if pcm:
                yield pcm[:n]
                pcm = pcm[n:]
        if pcm:
            yield pcm


@pytest.mark.asyncio
async def test_streaming_pcm_respects_all_samples_and_fixed_voice():
    requests = []
    async def mock_send(request):
        data = __import__("json").loads(request.content)
        requests.append(data)
        return httpx.Response(
            200, stream=FragmentedPcm(), headers={"content-type": "audio/pcm"}
        )
    client = httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(mock_send),
    )
    output = []
    worker = StreamingCaptionTts(
        key="fake", language="en", voice="alloy",
        send_pcm=lambda frame: _append_frame(output, frame),
        client=client,
    )
    worker.start()
    worker.submit("Hello friends.")
    worker.submit("We are translating.")
    await worker.finish()
    assert [req["input"] for req in requests] == [
        "Hello friends.", "We are translating."
    ]
    assert all(req["voice"] == "alloy" for req in requests)
    assert all(req["response_format"] == "pcm" for req in requests)
    assert len(b"".join(output)) == 24000
    assert all(0 < len(frame) <= 4800 and len(frame) % 2 == 0 for frame in output)
    await client.aclose()


async def _append_frame(output, frame):
    output.append(frame)


@pytest.mark.asyncio
async def test_tts_failures_propagate_instead_of_silently_skipping_words():
    async def mock_send(request):
        return httpx.Response(429, json={"error": "rate limited"})
    client = httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(mock_send),
    )
    worker = StreamingCaptionTts(
        key="fake", language="en", send_pcm=lambda _: asyncio.sleep(0),
        client=client,
    )
    worker.start()
    worker.submit("Must not be skipped")
    with pytest.raises(httpx.HTTPStatusError):
        await worker.finish()
    await worker.cancel()
    await client.aclose()


@pytest.mark.asyncio
async def test_prefetch_next_phrase_before_previous_http_request_finishes():
    """Proves overlapping TTS synthesis without reordering spoken PCM."""
    import json

    second_request_started = asyncio.Event()
    requests = []
    output = []

    async def mock_send(request):
        data = json.loads(request.content)
        phrase = data["input"]
        requests.append((phrase, data["voice"]))
        if phrase == "first":
            # A strictly sequential implementation times out here.
            await asyncio.wait_for(second_request_started.wait(), timeout=2)
        else:
            second_request_started.set()
        value = 1 if phrase == "first" else 2
        return httpx.Response(200, content=bytes([value, 0]) * 4800)

    client = httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(mock_send),
    )
    worker = StreamingCaptionTts(
        key="fake", language="en",
        send_pcm=lambda frame: _append_frame(output, frame),
        client=client,
    )
    worker.start()
    worker.submit("first")
    worker.submit("second")
    await worker.finish(timeout=4)

    assert [phrase for phrase, _ in requests] == ["first", "second"]
    assert all(voice == "alloy" for _, voice in requests)
    assert b"".join(output) == (
        bytes([1, 0]) * 4800 + bytes([2, 0]) * 4800
    )
    await client.aclose()


@pytest.mark.asyncio
async def test_cancel_stalled_synthesis_does_not_hang_websocket_cleanup():
    gate = asyncio.Event()

    async def blocked_request(request):
        await gate.wait()
        return httpx.Response(200, content=bytes(4800))

    client = httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(blocked_request),
    )
    worker = StreamingCaptionTts(
        key="fake", language="en",
        send_pcm=lambda frame: asyncio.sleep(0),
        client=client,
    )
    worker.start()
    worker.submit("first")
    worker.submit("second")
    await asyncio.wait_for(worker.cancel(), timeout=2)
    await client.aclose()


@pytest.mark.asyncio
async def test_prefetched_http_chunks_keep_pcm_sample_order():
    """Irregular HTTP boundaries must not create dropped or odd PCM frames."""
    class OddBoundaries(httpx.AsyncByteStream):
        async def __aiter__(self):
            raw = bytes([1, 0]) * 4810
            yield raw[:1]
            yield raw[1:4781]
            yield raw[4781:]

    async def mock_send(request):
        return httpx.Response(200, stream=OddBoundaries())

    client = httpx.AsyncClient(
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(mock_send),
    )
    frames = []
    worker = StreamingCaptionTts(
        key="fake", language="en",
        send_pcm=lambda frame: _append_frame(frames, frame),
        client=client,
    )
    worker.start()
    worker.submit("Whole audio, no missing samples.")
    await worker.finish(timeout=4)
    assert b"".join(frames) == bytes([1, 0]) * 4810
    assert all(0 < len(frame) <= 4800 and len(frame) % 2 == 0
               for frame in frames)
    await client.aclose()
