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
from .caption_tts import CaptionPhraseAccumulator, StreamingCaptionTts
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
OUTPUT_FRAME_BYTES = 9600  # default: 200ms 24kHz mono PCM16
# Configurable 100ms output frames for smoother Android jitter buffering.
# The upstream model's audio format and 200ms microphone frames do not change.


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
    voice_engine: str = "direct",
) -> None:
    """Called only after the existing MIC-only token and start validation."""
    receiver = None
    captions = None
    audio_relay = None
    tts_worker = None
    tts_phrases = None
    if voice_engine not in ("direct", "caption_tts"):
        await _send_safe_error(phone, "Invalid realtime voice mode")
        await phone.close(code=1008)
        return
    if (voice_engine == "caption_tts"
            and os.getenv("BT_AI_CAPTION_TTS_ENABLED", "0") != "1"):
        await _send_safe_error(phone, "Caption TTS is disabled on gateway")
        await phone.close(code=1008)
        return
    # Opt-in per Python gateway process. Default remains proven caption-only.
    voice_flag = os.getenv("BT_AI_REALTIME_VOICE", "0").lower()
    if voice_flag not in ("0", "1"):
        await _send_safe_error(phone, "Invalid BT_AI_REALTIME_VOICE; use 0 or 1")
        await phone.close(code=1011)
        return
    streaming_voice = voice_flag == "1"
    if voice_engine == "caption_tts" and not streaming_voice:
        await _send_safe_error(phone, "Enable realtime voice for caption TTS")
        await phone.close(code=1011)
        return
    pace_flag = os.getenv("BT_AI_PACE_OUTPUT_AUDIO", "1").lower()
    if pace_flag not in ("0", "1"):
        await _send_safe_error(phone, "Invalid BT_AI_PACE_OUTPUT_AUDIO; use 0 or 1")
        await phone.close(code=1011)
        return
    paced_audio = pace_flag == "1"
    output_frame_ms_flag = os.getenv("BT_AI_OUTPUT_FRAME_MS", "200")
    if output_frame_ms_flag not in ("100", "200"):
        await _send_safe_error(
            phone, "Invalid BT_AI_OUTPUT_FRAME_MS; use 100 or 200"
        )
        await phone.close(code=1011)
        return
    output_frame_ms = int(output_frame_ms_flag)
    output_frame_bytes = output_frame_ms * 48  # 24kHz mono PCM16
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

                async def send_audio_metrics(snapshot: dict[str, int]) -> None:
                    # Snapshot AFTER PCM handoff; exact pending vs sent duration.
                    await send_locked({
                        "type": "audio_metrics",
                        "source_mode": "MIC",
                        **snapshot,
                    })

                audio_relay = PacedPcmRelay(
                    send_pcm,
                    paced=paced_audio,
                    # Keep a fixed 12s bounded queue in both 100/200ms modes.
                    max_frames=120 if voice_engine == "caption_tts"
                        else 12000 // output_frame_ms,
                    on_progress=send_audio_metrics,
                )
                audio_relay.start()
                if voice_engine == "caption_tts":
                    # One serial generator per session = consistent voice,
                    # bounded request queue and no overlapping utterances.
                    tts_phrases = CaptionPhraseAccumulator()
                    tts_worker = StreamingCaptionTts(
                        key=key, language=target, voice="alloy",
                        send_pcm=audio_relay.enqueue_wait,
                    )
                    tts_worker.start()

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
                "audio_frame_ms": (
                    100 if voice_engine == "caption_tts"
                    else output_frame_ms if streaming_voice else 200
                ),
                "output_audio_paced": streaming_voice and paced_audio,
                "voice_engine": voice_engine if streaming_voice else "off",
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
                    if tts_worker is not None:
                        tts_worker.check()
                        # Wake periodically even when model has gone silent:
                        # commit the last caption phrase without waiting for
                        # a new transcript event or session.close.
                        wait = min(wait, 0.2) if wait is not None else 0.2
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
                        if tts_phrases is not None and tts_worker is not None:
                            for phrase in tts_phrases.flush_idle():
                                tts_worker.submit(phrase)
                            tts_worker.check()
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
                        if tts_phrases is not None and tts_worker is not None:
                            for phrase in tts_phrases.finish():
                                tts_worker.submit(phrase)
                            # Finish every queued phrase before finishing PCM;
                            # fail explicitly on timeout; no hidden word loss.
                            await tts_worker.finish(timeout=50)
                        if audio_relay is not None:
                            # Drain queued speech on graceful upstream close.
                            await audio_relay.drain()
                        return
                    if kind == "session.output_audio.delta":
                        if not streaming_voice or voice_engine == "caption_tts":
                            # In TTS mode the provider audio is ignored:
                            # never double-play two independent voice streams.
                            continue
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
                        for offset in range(0, len(pcm), output_frame_bytes):
                            chunk = pcm[offset:offset + output_frame_bytes]
                            if audio_relay is None:
                                raise ProtocolError("PCM relay unavailable")
                            audio_relay.enqueue(chunk)
                        continue
                    if state.accept(kind, event.get("delta")):
                        if kind == "session.output_transcript.delta":
                            metrics.mark("provider_first_target_caption")
                            if tts_phrases is not None and tts_worker is not None:
                                for phrase in tts_phrases.add(event["delta"]):
                                    tts_worker.submit(phrase)
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
                    await asyncio.wait_for(
                        captions, timeout=70 if tts_worker is not None else 16
                    )
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
        await _send_safe_error(phone, "Invalid microphone frame or TTS text backlog")
    except (TimeoutError, asyncio.TimeoutError):
        await _send_safe_error(phone, "Live caption or TTS queue timed out")
    except Exception:
        # Preserve the failed session without leaking text, provider responses,
        # keys, or transcripts. Errors remain visible, never silently skipped.
        if tts_worker is not None:
            await _send_safe_error(
                phone, "Streaming caption voice failed. Check TTS model "
                "access, quota and connection; restart the session."
            )
        else:
            await _send_safe_error(
                phone, "Live captions unavailable. Check realtime model access, "
                "quota or hotspot; use segmented fallback."
            )
        return
    finally:
        if tts_worker is not None:
            await tts_worker.cancel()
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
