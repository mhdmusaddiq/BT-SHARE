"""MIC-only Tamil <-> English segmented cloud-translation POC gateway.

Uses Gemini audio understanding for bilingual transcription/translation,
followed by Gemini Flash-Lite TTS. This is segmented, not simultaneous voice.
Never persist private audio or transcript content or send API keys to phones.
"""
import asyncio
from array import array
import base64
import json
import os
import sys
import time

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
import httpx

from .protocol import ProtocolError, decode_frame, validate_start
from .gemini_provider import GeminiTranslationPipeline

AUDIO_BYTES_PER_SEC = 16000 * 2
VOICE_THRESHOLD = 370
SILENCE_FRAMES = 5
FRAME_BYTES = 3200  # 100ms at 16kHz PCM16, mono.
MIN_FRAMES = 8
MAX_FRAMES = 50
MAX_SESSION_SECONDS = 600

app = FastAPI(title="BT SHARE MIC-only AI translation POC")


@app.get("/health/live")
async def live():
    # This is a liveness check, not a claim of OpenAI availability.
    return {"status": "live", "scope": "MIC_ONLY"}


class SpeechChunker:
    """Simple bounded energy-based utterance chunking, not a production VAD."""

    def __init__(self):
        self.pending = bytearray()
        self.current = bytearray()
        self.spoke = False
        self.silent = 0
        self.count = 0

    def _reset(self):
        self.current.clear()
        self.spoke = False
        self.silent = 0
        self.count = 0

    def add(self, pcm: bytes) -> list[bytes]:
        self.pending.extend(pcm)
        results = []
        while len(self.pending) >= FRAME_BYTES:
            raw = bytes(self.pending[:FRAME_BYTES])
            del self.pending[:FRAME_BYTES]
            samples = array("h")
            samples.frombytes(raw)
            if sys.byteorder != "little":
                samples.byteswap()
            mean_volume = sum(abs(v) for v in samples) / len(samples)
            voiced = mean_volume >= VOICE_THRESHOLD
            self.current.extend(raw)
            self.count += 1
            if voiced:
                self.spoke = True
                self.silent = 0
            elif self.spoke:
                self.silent += 1
            if self.spoke and (
                (self.count >= MIN_FRAMES and self.silent >= SILENCE_FRAMES)
                or self.count >= MAX_FRAMES
            ):
                results.append(bytes(self.current))
                self._reset()
            elif not self.spoke and self.count >= MAX_FRAMES:
                self._reset()  # Drop silence; never send it to AI.
        return results

    def finish(self) -> bytes | None:
        # Ignore trailing incomplete PCM bytes to avoid malformed samples.
        if self.pending:
            complete = len(self.pending) // 2 * 2
            if complete:
                self.current.extend(self.pending[:complete])
            self.pending.clear()
        output = bytes(self.current) if self.spoke and len(self.current) >= 16000 else None
        self._reset()
        return output


async def send_error(mobile: WebSocket, reason: str):
    try:
        await mobile.send_json({"type": "error", "message": reason})
    except (WebSocketDisconnect, RuntimeError, OSError):
        pass


@app.websocket("/v1/stream")
async def translate_socket(mobile: WebSocket):
    await mobile.accept()
    token = os.getenv("BT_DEV_GATEWAY_TOKEN", "")
    key = os.getenv("GEMINI_API_KEY", "")
    try:
        start = await asyncio.wait_for(mobile.receive_json(), timeout=10)
        source, target = validate_start(start, token)
    except (ProtocolError, ValueError, WebSocketDisconnect, asyncio.TimeoutError):
        await send_error(mobile, "Unauthorized MIC session or invalid request")
        await mobile.close(code=1008)
        return

    if not key:
        await send_error(mobile, "GEMINI_API_KEY is not configured on the gateway")
        await mobile.close(code=1011)
        return

    pipeline = GeminiTranslationPipeline(key)
    queue = asyncio.Queue(maxsize=3)
    chunker = SpeechChunker()
    await mobile.send_json({
        "type": "ready",
        "source_mode": "MIC",
        "source_language": source,
        "target_language": target,
        "provider": "gemini-audio-translation-tts",
    })

    async def receive_microphone():
        previous_sequence = 0
        total_bytes = 0
        began = time.monotonic()
        async for raw in mobile.iter_text():
            if len(raw) > 100_000:
                raise ProtocolError("Oversized client event")
            event = json.loads(raw)
            if isinstance(event, dict) and event.get("type") == "stop":
                break
            previous_sequence, pcm = decode_frame(event, previous_sequence)
            total_bytes += len(pcm)
            if total_bytes > (time.monotonic() - began + 5) * 64000:
                raise ProtocolError("Microphone rate exceeds expected bounds")
            for segment in chunker.add(pcm):
                try:
                    queue.put_nowait(segment)
                except asyncio.QueueFull as exc:
                    raise ProtocolError("AI processing is behind microphone capture") from exc
        remaining = chunker.finish()
        if remaining is not None:
            await queue.put(remaining)
        await queue.put(None)

    async def produce_translation():
        next_id = 0
        while True:
            pcm = await queue.get()
            if pcm is None:
                return
            next_id += 1
            started = time.monotonic()
            original, translated, voice = await pipeline.translate(pcm, source, target)
            if not original:
                continue
            await mobile.send_json({
                "type": "segment",
                "segment_id": f"cloud-{next_id}",
                "original_text": original,
                "translated_text": translated,
                "audio_mime_type": "audio/wav",
                "translated_audio_base64": base64.b64encode(voice).decode("ascii"),
                # Measured only from server pipeline invocation, not end-to-end.
                "latency_ms": round((time.monotonic() - started) * 1000),
            })

    producer = asyncio.create_task(produce_translation())
    receiver = asyncio.create_task(receive_microphone())
    try:
        async with asyncio.timeout(MAX_SESSION_SECONDS):
            done, _ = await asyncio.wait((producer, receiver), return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if not task.cancelled() and task.exception():
                    raise task.exception()
            if receiver in done and not producer.done():
                # Permit tail of user speech to finish before closing the session.
                try:
                    await asyncio.wait_for(producer, timeout=30)
                except asyncio.TimeoutError:
                    pass
    except (ProtocolError, ValueError):
        await send_error(mobile, "Invalid audio or translation processing backlog")
    except httpx.HTTPStatusError:
        await send_error(mobile, "Gemini request rejected; check API key, quota and model access")
    except httpx.HTTPError:
        await send_error(mobile, "Gemini API connection failed")
    except asyncio.TimeoutError:
        await send_error(mobile, "Translation session time limit reached")
    except Exception:
        await send_error(mobile, "Gemini translation service unavailable")
    finally:
        for task in (producer, receiver):
            if not task.done():
                task.cancel()
        await asyncio.gather(producer, receiver, return_exceptions=True)
        await pipeline.close()
        try:
            await mobile.close()
        except (WebSocketDisconnect, RuntimeError, OSError):
            pass
