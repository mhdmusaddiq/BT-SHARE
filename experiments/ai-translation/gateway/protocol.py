"""Unprivileged client framing; MEDIA must never reach the AI gateway."""
import base64
import binascii
import hmac


class ProtocolError(ValueError):
    pass


def validate_start(event: object, token: str) -> tuple[str, str]:
    if not isinstance(event, dict) or event.get("type") != "start":
        raise ProtocolError("Expected start message")
    if not token or not hmac.compare_digest(
        str(event.get("ephemeral_session_token", "")), token
    ):
        raise ProtocolError("Unauthorized gateway session")
    if event.get("source_mode") != "MIC":
        raise ProtocolError("Only microphone audio is allowed")
    if (
        event.get("format") != "pcm_s16le"
        or event.get("sample_rate_hz") != 16000
        or event.get("channels") != 1
    ):
        raise ProtocolError("Expected 16kHz mono PCM16 microphone audio")
    src, dst = event.get("source_language"), event.get("target_language")
    if src not in ("ta", "en") or dst not in ("ta", "en") or src == dst:
        raise ProtocolError("Only ta<->en is supported")
    return src, dst


def decode_frame(event: object, previous: int) -> tuple[int, bytes]:
    if not isinstance(event, dict) or event.get("type") != "audio":
        raise ProtocolError("Expected audio event")
    if event.get("source_mode") != "MIC":
        raise ProtocolError("MEDIA translation is forbidden")
    sequence = event.get("sequence")
    if type(sequence) is not int or sequence <= previous:
        raise ProtocolError("Invalid sequence")
    encoded = event.get("pcm16_base64")
    if not isinstance(encoded, str) or len(encoded) > 95000:
        raise ProtocolError("Audio payload too large")
    try:
        pcm = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ProtocolError("Invalid base64") from exc
    if len(pcm) > 65536:
        raise ProtocolError("PCM frame too large")
    return sequence, pcm
