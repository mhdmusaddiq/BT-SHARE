import base64
import io
import wave

import pytest
from gateway.app import CaptionState


class FakeSocket:
    def __init__(self):
        self.messages = []

    async def send_json(self, message):
        self.messages.append(message)


@pytest.mark.asyncio
async def test_caption_delta_and_audio_wav():
    transport = FakeSocket()
    state = CaptionState(transport)
    await state.receive_event({
        "type": "session.input_transcript.delta",
        "delta": "வணக்கம்",
    })
    await state.receive_event({
        "type": "session.output_transcript.delta",
        "delta": "Hello",
    })
    await state.receive_event({
        "type": "session.output_audio.delta",
        "delta": base64.b64encode(bytes(48000)).decode(),
        "sample_rate": 24000,
    })
    audio_msg = transport.messages[-1]
    assert audio_msg["type"] == "segment"
    assert audio_msg["original_text"] == "வணக்கம்"
    assert audio_msg["translated_text"] == "Hello"
    assert audio_msg["segment_id"] == "realtime-current"
    content = base64.b64decode(audio_msg["translated_audio_base64"])
    with wave.open(io.BytesIO(content), "rb") as sound:
        assert sound.getframerate() == 24000
        assert sound.getnframes() == 24000
