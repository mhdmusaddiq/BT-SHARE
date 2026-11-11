"""MIC-only experimental bridge to OpenAI realtime speech translation.

Set OPENAI_API_KEY and BT_DEV_GATEWAY_TOKEN in the server environment.
This server intentionally never logs speech, transcripts or credentials.
"""
import asyncio
import base64
import json
import os
import time

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from websockets.asyncio.client import connect

from .audio import Pcm16To24, wav_from_pcm24
from .protocol import ProtocolError, decode_frame, validate_start

API_ENDPOINT = (
    "wss://api.openai.com/v1/realtime/translations?"
    "model=gpt-realtime-translate"
)
FRAME_SIZE = 9600  # 200ms of 24kHz mono PCM16.
AUDIO_REPLY_BYTES = 48000  # 1 second of 24kHz PCM16 generated speech.
MAX_SESSION_SECONDS = 600
app = FastAPI(title="BT SHARE MIC-only AI gateway")


@app.get("/health/live")
async def live():
    return {"status": "live", "mode": "MIC_ONLY"}


class CaptionState:
    """Bounded rolling caption snapshot plus playable 1s WAV audio chunks."""

    def __init__(self, mobile: WebSocket):
        self.mobile = mobile
        self.original = ""
        self.translation = ""
        self.audio = bytearray()
        self.last_sent = 0.0

    def snapshot(self, audio: bytes = b""):
        segment = {
            "type": "segment",
            "segment_id": "realtime-current",
            "original_text": self.original,
            "translated_text": self.translation,
            "is_partial": True,
        }
        if audio:
            segment["audio_mime_type"] = "audio/wav"
            segment["translated_audio_base64"] = base64.b64encode(
                wav_from_pcm24(audio)
            ).decode("ascii")
        return segment

    async def flush_audio(self):
        if self.audio:
            audio = bytes(self.audio)
            self.audio.clear()
            await self.mobile.send_json(self.snapshot(audio))

    async def receive_event(self, event: dict):
        kind = event.get("type")
        delta = event.get("delta")
        if not isinstance(delta, str):
            raise ProtocolError("Upstream response has invalid delta")
        if kind == "session.input_transcript.delta":
            self.original = (self.original + delta)[-1600:]
        elif kind == "session.output_transcript.delta":
            self.translation = (self.translation + delta)[-1600:]
        elif kind == "session.output_audio.delta":
            if event.get("sample_rate", 24000) != 24000:
                raise ProtocolError("Unsupported upstream PCM sample rate")
            try:
                pcm = base64.b64decode(delta, validate=True)
            except (ValueError, Exception) as exc:
                raise ProtocolError("Invalid upstream audio") from exc
            if len(pcm) > 96_000 or len(pcm) % 2:
                raise ProtocolError("Oversized or unaligned upstream audio")
            self.audio.extend(pcm)
            if len(self.audio) >= AUDIO_REPLY_BYTES:
                await self.flush_audio()
            return
        now = time.monotonic()
        if now - self.last_sent >= 0.1:
            self.last_sent = now
            await self.mobile.send_json(self.snapshot())


async def send_error(mobile: WebSocket, reason: str):
    try:
        await mobile.send_json({"type": "error", "message": reason})
    except (RuntimeError, WebSocketDisconnect, OSError):
        pass


@app.websocket("/v1/stream")
async def translate(mobile: WebSocket):
    await mobile.accept()
    session_token = os.environ.get("BT_DEV_GATEWAY_TOKEN", "")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    try:
        start = await asyncio.wait_for(mobile.receive_json(), timeout=10)
        source, target = validate_start(start, session_token)
    except (ProtocolError, ValueError, WebSocketDisconnect, asyncio.TimeoutError):
        await send_error(mobile, "Invalid MIC request or gateway token")
        await mobile.close(code=1008)
        return
    if not api_key:
        await send_error(mobile, "AI provider is not configured on the server")
        await mobile.close(code=1011)
        return
    try:
        async with connect(
            API_ENDPOINT,
            additional_headers={"Authorization": f"Bearer {api_key}"},
            open_timeout=12,
            max_size=2_000_000,
            ping_interval=20,
            ping_timeout=10,
        ) as ai:
            await ai.send(json.dumps({
                "type": "session.update",
                "session": {
                    "audio": {
                        "input": {
                            "transcription": {"model": "gpt-realtime-whisper"},
                            "noise_reduction": {"type": "near_field"},
                        },
                        "output": {"language": target},
                    }
                },
            }))
            async with asyncio.timeout(15):
                while True:
                    evt = json.loads(await ai.recv())
                    if evt.get("type") == "session.updated":
                        break
                    if evt.get("type") == "error":
                        raise ProtocolError("AI provider rejected session")
            await mobile.send_json({
                "type": "ready", "source_mode": "MIC",
                "source_language": source, "target_language": target,
                "provider": "gpt-realtime-translate",
            })
            captions = CaptionState(mobile)
            converter = Pcm16To24()

            async def audio_in():
                seq = 0
                carry = bytearray()
                total = 0
                began = time.monotonic()
                async for raw in mobile.iter_text():
                    if len(raw) > 100_000:
                        raise ProtocolError("Oversized incoming audio message")
                    event = json.loads(raw)
                    if isinstance(event, dict) and event.get("type") == "stop":
                        break
                    seq, pcm16 = decode_frame(event, seq)
                    total += len(pcm16)
                    if total > (time.monotonic() - began + 5) * 64_000:
                        raise ProtocolError("Audio input exceeds real-time limit")
                    carry.extend(converter.process(pcm16))
                    while len(carry) >= FRAME_SIZE:
                        pcm24 = bytes(carry[:FRAME_SIZE])
                        del carry[:FRAME_SIZE]
                        await ai.send(json.dumps({
                            "type": "session.input_audio_buffer.append",
                            "audio": base64.b64encode(pcm24).decode("ascii"),
                        }))
                if carry:
                    await ai.send(json.dumps({
                        "type": "session.input_audio_buffer.append",
                        "audio": base64.b64encode(carry).decode("ascii"),
                    }))
                await ai.send(json.dumps({"type": "session.close"}))

            async def audio_out():
                async for packet in ai:
                    evt = json.loads(packet)
                    kind = evt.get("type")
                    if kind in (
                        "session.input_transcript.delta",
                        "session.output_transcript.delta",
                        "session.output_audio.delta",
                    ):
                        await captions.receive_event(evt)
                    elif kind == "error":
                        raise ProtocolError("AI provider reported an error")
                    elif kind == "session.closed":
                        await captions.flush_audio()
                        return

            in_task = asyncio.create_task(audio_in())
            out_task = asyncio.create_task(audio_out())
            try:
                async with asyncio.timeout(MAX_SESSION_SECONDS):
                    done, _ = await asyncio.wait(
                        (in_task, out_task),
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    for task in done:
                        if not task.cancelled() and task.exception():
                            raise task.exception()
                    if in_task in done and not out_task.done():
                        # Allow the translation model to flush audio after Stop.
                        try:
                            await asyncio.wait_for(out_task, timeout=8)
                        except asyncio.TimeoutError:
                            pass
            finally:
                for task in (in_task, out_task):
                    if not task.done():
                        task.cancel()
                await asyncio.gather(in_task, out_task, return_exceptions=True)
    except (WebSocketDisconnect, OSError):
        pass
    except (ProtocolError, ValueError):
        await send_error(mobile, "Invalid stream or AI provider response")
    except asyncio.TimeoutError:
        await send_error(mobile, "Translation session timeout")
    except Exception:
        # Never log or send API secrets, transcription text or raw provider data.
        await send_error(mobile, "AI translation gateway unavailable")
    finally:
        try:
            await mobile.close()
        except (RuntimeError, WebSocketDisconnect, OSError):
            pass
