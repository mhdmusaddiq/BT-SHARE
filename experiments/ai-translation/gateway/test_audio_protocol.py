"""Does not use cloud credentials or synthetic human speech samples."""
import base64
import io
import wave
from array import array

import pytest

from gateway.audio import Pcm16To24, wav_from_pcm24
from gateway.protocol import ProtocolError, validate_start, decode_frame


def test_resampling_continues_across_chunk_boundaries():
    source = array("h", [1000] * 3200).tobytes()
    a = Pcm16To24()
    whole = a.process(source)
    b = Pcm16To24()
    parts = b.process(source[:371]) + b.process(source[371:3100]) + b.process(source[3100:])
    assert whole == parts
    assert 9596 <= len(whole) <= 9604
    assert max(array("h", whole)) == 1000


def test_pcm_wav_header():
    data = wav_from_pcm24(b"\0\0" * 24000)
    with wave.open(io.BytesIO(data), "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (24000, 1, 2)
        assert w.getnframes() == 24000


def test_mic_only_and_token_required():
    msg = {
        "type": "start", "source_mode": "MIC",
        "source_language": "ta", "target_language": "en",
        "format": "pcm_s16le", "sample_rate_hz": 16000,
        "channels": 1, "ephemeral_session_token": "dev-secret",
    }
    assert validate_start(msg, "dev-secret") == ("ta", "en")
    with pytest.raises(ProtocolError):
        validate_start({**msg, "source_mode": "MEDIA"}, "dev-secret")
    with pytest.raises(ProtocolError):
        validate_start(msg, "not-the-secret")
    with pytest.raises(ProtocolError):
        validate_start({**msg, "target_language": "ta"}, "dev-secret")


def test_monotonic_mic_frame_sequence():
    frame = {
        "type": "audio", "source_mode": "MIC", "sequence": 1,
        "pcm16_base64": base64.b64encode(b"\x00\x01").decode(),
    }
    assert decode_frame(frame, 0) == (1, b"\x00\x01")
    with pytest.raises(ProtocolError):
        decode_frame(frame, 1)
    with pytest.raises(ProtocolError):
        decode_frame({**frame, "source_mode": "MEDIA"}, 0)
