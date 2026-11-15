"""MIC-only live Tamil/English caption bridge.

OpenAI realtime translation emits original and translated text deltas while
microphone audio is still arriving. Phase 1 deliberately ignores translated
PCM output; live voice playback is a separate milestone.
No private text, audio, or OpenAI credentials are logged or persisted.
"""
import asyncio
import base64
import binascii
import json
import os
import time

from fastapi import WebSocket, WebSocketDisconnect
from websockets.asyncio.client import connect

from .audio import Pcm16To24
from .audio_pacing import AudioRelayOverloaded, PacedPcmRelay
from .diagnostics import RealtimeGatewayMetrics
from .protocol import ProtocolError, decode_frame

OPENAI_TRANSLATION_URL = (
    "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate"
)
INPUT_FRAME_BYTES = 9600  # 200ms, 24 kHz, PCM16 mono.
MAX_SESSION_SECONDS = 600
MAX_CAPTION_CHARS = 1400
UI_UPDATE_SECONDS = 0.08
MAX_OUTPUT_DELTA_BYTES = 192_000  # max 4s of audio in one upstream event
OUTPUT_FRAME_BYTES = 9600  # relay 200ms PCM16 chunks, prevent huge phone JSON


class LiveCaptionState:
    """Append-only transcript accumulator with bounded memory and stable ID."""

    def __init__(self, max_chars: int = MAX_CAPTION_CHARS):
        self.original = ""
        self.translated = ""
        self.max_chars = max_chars
        self.dirty = False

    def accept(self, kind: str, delta: object) -> bool:
        if kind not in (
            "session.input_transcript.delta",
            "session.output_transcript.delta",
        ):
            return False
        if not isinstance(delta, str) or len(delta) > 2000:
            raise ProtocolError("Invalid live transcript fragment")
        if not delta:
            return False
        # Deltas are append-only; never insert artificial spaces.
        if kind == "session.input_transcript.delta":
            self.original = (self.original + delta)[-self.max_chars:]
        else:
            self.translated = (self.translated + delta)[-self.max_chars:]
        self.dirty = True
        return True

    def payload(self, elapsed_ms: int | None = None) -> dict:
        data = {
            "type": "segment",
            "segment_id": "live-current",
            "original_text": self.original,
            "translated_text": self.translated,
            "caption_only": True,
        }
        # Model alignment metadata, not server processing latency.
        if type(elapsed_ms) is int and elapsed_ms >= 0:
            data["audio_elapsed_ms"] = elapsed_ms
        return data


async def _send_safe_error(phone: WebSocket, message: str) -> None:
    try:
        await phone.send_json({"type": "error", "message": message})
    except (WebSocketDisconnect, RuntimeError, OSError):
        pass


async def realtime_caption_session(
    phone: WebSocket,
    *,
    key: str,
    source: str,
    target: str,
) -> None:
    """Called only after the existing MIC-only token and start validation."""
    receiver = None
    captions = None
    audio_relay = None
    # Opt-in per Python gateway process. Default remains proven caption-only.
    voice_flag = os.getenv("BT_AI_REALTIME_VOICE", "0").lower()
    if voice_flag not in ("0", "1"):
        await _send_safe_error(phone, "Invalid BT_AI_REALTIME_VOICE; use 0 or 1")
        await phone.close(code=1011)
        return
    streaming_voice = voice_flag == "1"
    pace_flag = os.getenv("BT_AI_PACE_OUTPUT_AUDIO", "1").lower()
    if pace_flag not in ("0", "1"):
        await _send_safe_error(phone, "Invalid BT_AI_PACE_OUTPUT_AUDIO; use 0 or 1")
        await phone.close(code=1011)
        return
    paced_audio = pace_flag == "1"
    metrics = RealtimeGatewayMetrics(
        enabled=os.getenv("BT_AI_DIAGNOSTICS", "0") == "1"
    )
    try:
        async with connect(
            OPENAI_TRANSLATION_URL,
            additional_headers={"Authorization": f"Bearer {key}"},
            open_timeout=12,
            close_timeout=5,
            max_size=2_000_000,
            ping_interval=20,
            ping_timeout=20,
        ) as upstream:
            # Set the target language, and opt into source-caption deltas.
            await upstream.send(json.dumps({
                "type": "session.update",
                "session": {
                    "audio": {
                        "input": {
                            "transcription": {"model": "gpt-realtime-whisper"},
                            "noise_reduction": {"type": "near_field"},
                        },
                        "output": {"language": target},
                    },
                },
            }))
            # Do not declare the phone ready until OpenAI accepts settings.
            deadline = time.monotonic() + 15
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("Realtime session setup timed out")
                event = json.loads(
                    await asyncio.wait_for(upstream.recv(), timeout=remaining)
                )
                if not isinstance(event, dict):
                    raise ProtocolError("Invalid upstream response")
                if event.get("type") == "error":
                    raise RuntimeError("OpenAI realtime session rejected")
                if event.get("type") == "session.updated":
                    break
                if event.get("type") != "session.created":
                    raise ProtocolError("Unexpected realtime handshake event")

            # Single serialized phone sender for independent captions/audio.
            send_lock = asyncio.Lock()

            async def send_locked(payload: dict) -> None:
                async with send_lock:
                    await phone.send_json(payload)

            if streaming_voice:
                async def send_pcm(sequence: int, chunk: bytes) -> None:
                    await send_locked({
                        "type": "audio_delta",
                        "source_mode": "MIC",
                        "sequence": sequence,
                        "sample_rate_hz": 24000,
                        "channels": 1,
                        "encoding": "pcm_s16le",
                        "pcm16_base64": base64.b64encode(chunk).decode("ascii"),
                    })
                    metrics.mark("gateway_first_audio_sent")

                audio_relay = PacedPcmRelay(send_pcm, paced=paced_audio)
                audio_relay.start()

            await send_locked({
                "type": "ready",
                "source_mode": "MIC",
                "source_language": source,
                "target_language": target,
                "provider": "realtime-translation-captions-and-voice"
                    if streaming_voice else "realtime-translation-captions",
                "caption_only": not streaming_voice,
                "streaming_audio": streaming_voice,
                "audio_sample_rate_hz": 24000 if streaming_voice else None,
                "audio_frame_ms": 200,
                "output_audio_paced": streaming_voice and paced_audio,
            })

            async def microphone_to_upstream() -> None:
                previous_sequence = 0
                bytes_received = 0
                began = time.monotonic()
                pcm_resampler = Pcm16To24()
                pending = bytearray()
                async for raw in phone.iter_text():
                    if len(raw) > 100_000:
                        raise ProtocolError("Oversized microphone event")
                    event = json.loads(raw)
                    if isinstance(event, dict) and event.get("type") == "stop":
                        break
                    previous_sequence, mic_pcm = decode_frame(
                        event, previous_sequence
                    )
                    bytes_received += len(mic_pcm)
                    if bytes_received > (time.monotonic() - began + 5) * 64000:
                        raise ProtocolError("Unexpected microphone sample rate")
                    pending.extend(pcm_resampler.process(mic_pcm))
                    while len(pending) >= INPUT_FRAME_BYTES:
                        frame = bytes(pending[:INPUT_FRAME_BYTES])
                        del pending[:INPUT_FRAME_BYTES]
                        metrics.mark_first_input_send()
                        await asyncio.wait_for(upstream.send(json.dumps({
                            "type": "session.input_audio_buffer.append",
                            "audio": base64.b64encode(frame).decode("ascii"),
                        })), timeout=3)
                if pending:
                    # Preserve trailing speech without rounding down samples.
                    padded = bytes(pending) + bytes(INPUT_FRAME_BYTES - len(pending))
                    await upstream.send(json.dumps({
                        "type": "session.input_audio_buffer.append",
                        "audio": base64.b64encode(padded).decode("ascii"),
                    }))
                # The realtime server flushes any final captions before close.
                await upstream.send(json.dumps({"type": "session.close"}))

            async def upstream_to_captions() -> None:
                state = LiveCaptionState()
                last_emit = 0.0
                last_audio_ms = None
                while True:
                    # Coalesce bursts to ~12 UI updates/second, but flush
                    # the trailing fragment even if no new events arrive.
                    wait = (
                        max(0, UI_UPDATE_SECONDS - (time.monotonic() - last_emit))
                        if state.dirty else None
                    )
                    try:
                        raw = (
                            await asyncio.wait_for(upstream.recv(), timeout=wait)
                            if wait is not None else await upstream.recv()
                        )
                    except asyncio.TimeoutError:
                        if state.dirty:
                            await send_locked(state.payload(last_audio_ms))
                            state.dirty = False
                            last_emit = time.monotonic()
                        continue
                    if len(raw) > 2_000_000:
                        raise ProtocolError("Oversized realtime event")
                    event = json.loads(raw)
                    if not isinstance(event, dict):
                        raise ProtocolError("Invalid realtime event")
                    kind = event.get("type")
                    if kind == "error":
                        raise RuntimeError("Realtime AI rejected audio stream")
                    if kind == "session.closed":
                        if state.dirty:
                            await send_locked(state.payload(last_audio_ms))
                        if audio_relay is not None:
                            # Drain queued speech on graceful upstream close.
                            # The 12s queue is bounded; 14s permits normal
                            # output pacing without dropping samples.
                            await audio_relay.drain()
                        return
                    if kind == "session.output_audio.delta":
                        if not streaming_voice:
                            continue  # Phase 1 caption-only preserves old path
                        if (event.get("sample_rate", 24000) != 24000
                                or event.get("channels", 1) != 1
                                or event.get("format", "pcm16") != "pcm16"):
                            raise ProtocolError("Unsupported realtime output audio")
                        encoded = event.get("delta")
                        if not isinstance(encoded, str) or len(encoded) > 260_000:
                            raise ProtocolError("Oversized realtime audio delta")
                        try:
                            pcm = base64.b64decode(encoded, validate=True)
                        except (binascii.Error, ValueError) as exc:
                            raise ProtocolError("Invalid realtime audio delta") from exc
                        if not pcm or len(pcm) % 2 or len(pcm) > MAX_OUTPUT_DELTA_BYTES:
                            raise ProtocolError("Invalid realtime PCM16 samples")
                        metrics.mark("provider_first_audio")
                        # Never await playback-time pacing in the upstream
                        # receive loop: captions must not wait for PCM output.
                        for offset in range(0, len(pcm), OUTPUT_FRAME_BYTES):
                            chunk = pcm[offset:offset + OUTPUT_FRAME_BYTES]
                            if audio_relay is None:
                                raise ProtocolError("PCM relay unavailable")
                            audio_relay.enqueue(chunk)
                        continue
                    if state.accept(kind, event.get("delta")):
                        if kind == "session.output_transcript.delta":
                            metrics.mark("provider_first_target_caption")
                        if type(event.get("elapsed_ms")) is int:
                            last_audio_ms = event["elapsed_ms"]
                        if time.monotonic() - last_emit >= UI_UPDATE_SECONDS:
                            await send_locked(state.payload(last_audio_ms))
                            state.dirty = False
                            last_emit = time.monotonic()

            receiver = asyncio.create_task(microphone_to_upstream())
            captions = asyncio.create_task(upstream_to_captions())
            async with asyncio.timeout(MAX_SESSION_SECONDS):
                done, _ = await asyncio.wait(
                    (receiver, captions), return_when=asyncio.FIRST_COMPLETED
                )
                for task in done:
                    if not task.cancelled() and task.exception() is not None:
                        raise task.exception()
                if receiver in done and not captions.done():
                    # Flush on stop, bounded so a provider cannot hold
                    # the mic session open forever.
                    await asyncio.wait_for(captions, timeout=16)
                elif captions in done and not receiver.done():
                    raise RuntimeError("Realtime AI closed unexpectedly")
    except AudioRelayOverloaded:
        # Do not silently skip translated words when the caller falls behind.
        if audio_relay is not None:
            await audio_relay.cancel()
        await _send_safe_error(
            phone, "Translated speech delivery exceeded the safe backlog. "
            "Stop and restart this translation session."
        )
    except ProtocolError:
        await _send_safe_error(phone, "Invalid microphone frame or live caption event")
    except (TimeoutError, asyncio.TimeoutError):
        await _send_safe_error(phone, "Live caption service timed out")
    except Exception:
        # No untrusted provider messages, audio or secrets in client errors.
        await _send_safe_error(
            phone,
            "Live captions unavailable. Check realtime model access, "
            "quota or hotspot; use segmented fallback.",
        )
    finally:
        if audio_relay is not None:
            await audio_relay.cancel()
        for task in (receiver, captions):
            if task is not None and not task.done():
                task.cancel()
        await asyncio.gather(
            *(task for task in (receiver, captions) if task is not None),
            return_exceptions=True,
        )
        try:
            await phone.close()
        except (WebSocketDisconnect, RuntimeError, OSError):
            pass
