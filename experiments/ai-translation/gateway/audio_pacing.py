"""Bounded output pacing for generated 24kHz mono PCM16 speech.

The first PCM frame is delivered immediately. Following frames are released
at their natural audio duration. This isolates bursts from the AI provider
from the Android AudioTrack queue, without modifying/omitting samples.
Caption delivery runs on a separate task.

Queue is bounded: sustained >realtime synthesis eventually results in an
explicit overload rather than silently losing speech or allocating
unbounded memory. No audio/text/secrets are logged or persisted.
"""
import asyncio
import time
from collections.abc import Awaitable, Callable

from .protocol import ProtocolError


class AudioRelayOverloaded(ProtocolError):
    """Generated audio has exceeded the bounded realtime delivery queue."""


class PacedPcmRelay:
    def __init__(
        self,
        send: Callable[[int, bytes], Awaitable[None]],
        *,
        paced: bool = True,
        bytes_per_second: int = 48000,  # 24kHz mono PCM16LE
        max_frames: int = 60,  # 12 seconds at 200ms each
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        on_progress: Callable[[dict[str, int]], Awaitable[None]] | None = None,
    ):
        if bytes_per_second <= 0 or max_frames <= 0:
            raise ValueError("Invalid PCM pacing parameters")
        self.paced = paced
        self._send = send
        self._bytes_per_second = bytes_per_second
        self._queue: asyncio.Queue[bytes | None] = asyncio.Queue(
            maxsize=max_frames
        )
        self._clock = clock
        self._sleep = sleep
        self._on_progress = on_progress
        self._task: asyncio.Task[None] | None = None
        self._closed = False
        self._sequence = 0
        self._generated_bytes = 0
        self._sent_bytes = 0

    @property
    def pending_audio_ms(self) -> int:
        """Generated PCM not yet handed to phone WebSocket (not phone delay)."""
        pending = max(0, self._generated_bytes - self._sent_bytes)
        return round(pending * 1000 / self._bytes_per_second)

    @property
    def sent_audio_ms(self) -> int:
        return round(self._sent_bytes * 1000 / self._bytes_per_second)

    def metrics(self) -> dict[str, int]:
        return {
            "pending_audio_ms": self.pending_audio_ms,
            "sent_audio_ms": self.sent_audio_ms,
        }

    def start(self) -> None:
        if self._task is not None:
            raise RuntimeError("PCM pacing already started")
        self._task = asyncio.create_task(self._run())

    def enqueue(self, pcm: bytes) -> None:
        if not pcm or len(pcm) % 2:
            raise ProtocolError("Invalid PCM relay frame")
        if self._closed or self._task is None:
            raise ProtocolError("PCM relay not running")
        if self._task.done():
            self._task.result()  # Preserve transport failures.
            raise ProtocolError("PCM relay stopped unexpectedly")
        try:
            self._queue.put_nowait(pcm)
            self._generated_bytes += len(pcm)
        except asyncio.QueueFull as exc:
            raise AudioRelayOverloaded("Generated audio exceeds PCM queue cap") from exc

    async def enqueue_wait(self, pcm: bytes, *, timeout: float = 12) -> None:
        """Backpressure TTS producer; never discard an accepted speech frame."""
        if not pcm or len(pcm) % 2:
            raise ProtocolError("Invalid PCM relay frame")
        if self._closed or self._task is None:
            raise ProtocolError("PCM relay not running")
        if self._task.done():
            self._task.result()
            raise ProtocolError("PCM relay stopped unexpectedly")
        try:
            await asyncio.wait_for(self._queue.put(pcm), timeout=timeout)
        except asyncio.TimeoutError as exc:
            raise AudioRelayOverloaded("PCM relay timed out under load") from exc
        self._generated_bytes += len(pcm)

    async def _run(self) -> None:
        due: float | None = None
        while True:
            pcm = await self._queue.get()
            if pcm is None:
                return
            if self.paced:
                now = self._clock()
                if due is not None and due > now:
                    await self._sleep(due - now)
                # Do not accelerate to catch up after network stalls.
                now = self._clock()
                due = max(now, due if due is not None else now)
            self._sequence += 1
            await self._send(self._sequence, pcm)
            self._sent_bytes += len(pcm)
            if self._on_progress is not None and self._sequence % 5 == 0:
                await self._on_progress(self.metrics())
            if self.paced:
                due = max(due, self._clock()) + (
                    len(pcm) / self._bytes_per_second
                )

    async def drain(self, *, timeout: float = 14) -> None:
        if self._task is None:
            return
        if not self._closed:
            self._closed = True
            if self._task.done():
                await self._task  # Preserve a failed sender instead of hanging.
            await asyncio.wait_for(self._queue.put(None), timeout=timeout)
        await asyncio.wait_for(self._task, timeout=timeout)

    async def cancel(self) -> None:
        self._closed = True
        if self._task is not None:
            if not self._task.done():
                self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
