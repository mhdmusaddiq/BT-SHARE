"""Caption-to-PCM TTS for the MIC-only realtime translation experiment.

Consumes only committed, append-only translated caption fragments. Speech is
synthesized with a fixed voice, one phrase at a time, and delivered as 24kHz
mono signed little-endian PCM16. No transcripts/audio are persisted or logged.
"""
import asyncio
import re
import time
from collections.abc import Awaitable, Callable

import httpx

from .protocol import ProtocolError

_PCM_FRAME_BYTES = 4800  # 100ms at 24kHz mono 16-bit
_SENTENCE_BOUNDARY = re.compile(r"[.!?।。;]\s*|\n+")


class CaptionPhraseAccumulator:
    """Commit stable prefix only; never replay earlier caption deltas."""

    def __init__(self, *, clock: Callable[[], float] = time.monotonic):
        self._pending = ""
        self._clock = clock
        self._updated_at = clock()
        self._total_chars = 0

    @property
    def has_pending(self) -> bool:
        return bool(self._pending.strip())

    def add(self, delta: str) -> list[str]:
        if not isinstance(delta, str) or len(delta) > 2000:
            raise ProtocolError("Invalid TTS caption delta")
        if not delta:
            return []
        self._total_chars += len(delta)
        if self._total_chars > 25000:
            raise ProtocolError("TTS session caption limit reached")
        self._pending += delta
        self._updated_at = self._clock()
        return self._pop_complete()

    def _pop_complete(self) -> list[str]:
        phrases = []
        while self._pending:
            sentence = _SENTENCE_BOUNDARY.search(self._pending)
            if sentence and sentence.end() <= 360:
                cut = sentence.end()
            elif len(self._pending) >= 110:
                # A bounded, complete-word prefix; never split within a word.
                cut = self._pending.rfind(" ", 50, 110)
                if cut < 0:
                    if len(self._pending) > 360:
                        raise ProtocolError("Caption contains overlong word")
                    break
                cut += 1
            else:
                break
            phrase = self._pending[:cut].strip()
            self._pending = self._pending[cut:]
            if phrase:
                phrases.append(phrase)
        return phrases

    def flush_idle(self, *, after: float = 0.8) -> list[str]:
        if not self.has_pending or self._clock() - self._updated_at < after:
            return []
        # Once the provider pauses, treat its current fragment as an utterance.
        # A model that sends late word suffixes can still cause a cut; that is
        # why idle cuts are less eager than punctuation-based cuts.
        return self.finish()

    def finish(self) -> list[str]:
        phrase = self._pending.strip()
        self._pending = ""
        return [phrase] if phrase else []


class _PrefetchedPhrase:
    """Only the next 1.2 seconds of synthesized PCM can buffer per phrase."""

    def __init__(self):
        self.frames: asyncio.Queue[bytes | BaseException | None] = (
            asyncio.Queue(maxsize=12)
        )


class StreamingCaptionTts:
    """Two-request TTS prefetch with strictly ordered, bounded PCM playback.

    Synthesize the upcoming phrase while the previous phrase is played.
    The playback task drains one phrase completely before the next, so audio
    is never interleaved even if HTTP responses complete out of order.
    """

    def __init__(
        self,
        *,
        key: str,
        language: str,
        send_pcm: Callable[[bytes], Awaitable[None]],
        voice: str = "alloy",
        client: httpx.AsyncClient | None = None,
    ):
        if language not in ("ta", "en"):
            raise ValueError("Unsupported TTS language")
        if voice not in ("alloy", "coral", "marin", "cedar"):
            raise ValueError("Unsupported fixed TTS voice")
        self._voice = voice
        self._language = language
        self._send_pcm = send_pcm
        self._client = client or httpx.AsyncClient(
            base_url="https://api.openai.com/v1",
            headers={"Authorization": f"Bearer {key}"},
            timeout=httpx.Timeout(15.0, read=35.0),
        )
        self._owns_client = client is None
        # At most 12 submitted phrases pending consumption. Each prefetch
        # buffers at most 12 x 100ms PCM frames, never unbounded audio.
        self._queue: asyncio.Queue[_PrefetchedPhrase | None] = (
            asyncio.Queue(maxsize=12)
        )
        self._prefetch_limit = asyncio.Semaphore(2)
        self._producers: set[asyncio.Task[None]] = set()
        self._task: asyncio.Task[None] | None = None
        self._closing = False

    def start(self) -> None:
        if self._task is not None:
            raise RuntimeError("TTS worker already running")
        self._task = asyncio.create_task(self._run())

    def submit(self, phrase: str) -> None:
        if not phrase or len(phrase) > 600 or self._closing or self._task is None:
            raise ProtocolError("Invalid caption phrase")
        self.check()
        state = _PrefetchedPhrase()
        try:
            self._queue.put_nowait(state)
        except asyncio.QueueFull as exc:
            raise ProtocolError("TTS text backlog full") from exc
        producer = asyncio.create_task(self._prepare(phrase, state))
        self._producers.add(producer)
        producer.add_done_callback(self._producers.discard)

    def check(self) -> None:
        if self._task is not None and self._task.done():
            self._task.result()

    async def _prepare(self, phrase: str, state: _PrefetchedPhrase) -> None:
        try:
            async with self._prefetch_limit:
                await self._speak(phrase, state.frames.put)
        except Exception as error:
            # Preserve failure as data only inside this private worker queue;
            # surface it at the correct phrase position, never skip a phrase.
            await state.frames.put(error)
        await state.frames.put(None)

    async def _run(self) -> None:
        while True:
            state = await self._queue.get()
            if state is None:
                return
            while True:
                frame = await state.frames.get()
                if frame is None:
                    break
                if isinstance(frame, BaseException):
                    raise frame
                await self._send_pcm(frame)

    async def _speak(
        self,
        phrase: str,
        deliver: Callable[[bytes], Awaitable[None]],
    ) -> None:
        prompt = (
            "Speak the text exactly, with a consistent calm voice. "
            "Do not add words or introductions. Use natural pacing."
        )
        pcm = bytearray()
        total = 0
        async with self._client.stream(
            "POST",
            "/audio/speech",
            json={
                "model": "gpt-4o-mini-tts",
                "voice": self._voice,
                "input": phrase,
                "response_format": "pcm",
                "stream_format": "audio",
                "instructions": prompt,
            },
        ) as response:
            response.raise_for_status()
            async for chunk in response.aiter_bytes():
                total += len(chunk)
                if total > 2_400_000:  # 50 seconds of PCM per phrase.
                    raise ProtocolError("TTS PCM response exceeds safe limit")
                pcm.extend(chunk)
                while len(pcm) >= _PCM_FRAME_BYTES:
                    frame = bytes(pcm[:_PCM_FRAME_BYTES])
                    del pcm[:_PCM_FRAME_BYTES]
                    await deliver(frame)
        if total == 0 or len(pcm) % 2:
            raise ProtocolError("TTS supplied empty or invalid PCM16")
        if pcm:
            await deliver(bytes(pcm))

    async def finish(self, *, timeout: float = 45) -> None:
        if self._task is None:
            return
        if not self._closing:
            self._closing = True
            self.check()
            await asyncio.wait_for(self._queue.put(None), timeout=timeout)
        await asyncio.wait_for(self._task, timeout=timeout)

    async def cancel(self) -> None:
        self._closing = True
        if self._task is not None and not self._task.done():
            self._task.cancel()
        for producer in tuple(self._producers):
            if not producer.done():
                producer.cancel()
        await asyncio.gather(
            *(task for task in (self._task, *self._producers) if task is not None),
            return_exceptions=True,
        )
        if self._owns_client:
            await self._client.aclose()
