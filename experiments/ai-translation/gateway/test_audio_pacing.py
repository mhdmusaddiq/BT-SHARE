"""Paced PCM unit tests, with virtual clock; no network/provider calls."""
import asyncio

import pytest

from gateway.audio_pacing import AudioRelayOverloaded, PacedPcmRelay


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    async def sleep(self, seconds):
        self.now += seconds
        await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_three_200ms_frames_are_paced_and_bytes_remain_identical():
    clk = Clock()
    messages = []

    async def send(seq, pcm):
        messages.append((seq, pcm, clk.monotonic()))

    relay = PacedPcmRelay(send, clock=clk.monotonic, sleep=clk.sleep)
    relay.start()
    frames = [bytes([n, n]) * 4800 for n in (1, 2, 3)]
    for pcm in frames:
        relay.enqueue(pcm)
    await relay.drain()
    assert [item[0] for item in messages] == [1, 2, 3]
    assert [item[1] for item in messages] == frames
    assert [round(item[2], 3) for item in messages] == [0.0, 0.2, 0.4]


@pytest.mark.asyncio
async def test_unpaced_rollback_keeps_back_to_back_delivery():
    clk = Clock()
    seen = []

    async def send(seq, pcm):
        seen.append(clk.monotonic())

    relay = PacedPcmRelay(send, paced=False, clock=clk.monotonic, sleep=clk.sleep)
    relay.start()
    relay.enqueue(bytes(9600))
    relay.enqueue(bytes(9600))
    await relay.drain()
    assert seen == [0.0, 0.0]


@pytest.mark.asyncio
async def test_queue_overflow_does_not_discard_or_reorder_pcm():
    clk = Clock()
    async def send(seq, pcm):
        pass
    relay = PacedPcmRelay(
        send, max_frames=2, clock=clk.monotonic, sleep=clk.sleep
    )
    relay.start()
    relay.enqueue(bytes(9600))
    relay.enqueue(bytes(9600))
    with pytest.raises(AudioRelayOverloaded):
        relay.enqueue(bytes(9600))
    await relay.cancel()


@pytest.mark.asyncio
async def test_pcm_frames_require_valid_format():
    clk = Clock()
    async def send(seq, pcm):
        pass
    relay = PacedPcmRelay(
        send, clock=clk.monotonic, sleep=clk.sleep
    )
    relay.start()
    with pytest.raises(ValueError):
        relay.enqueue(b"")
    # ProtocolError subclasses ValueError.
    with pytest.raises(ValueError):
        relay.enqueue(b"x")
    await relay.cancel()

@pytest.mark.asyncio
async def test_pending_audio_duration_tracks_bursts_and_sent_frames():
    """Bursts accumulate behind paced delivery; numbers cannot be confused
    with model latency or Android speaker onset."""
    clk = Clock()
    entered = asyncio.Event()
    release = asyncio.Event()

    async def send(seq, pcm):
        if seq == 1:
            entered.set()
            await release.wait()

    relay = PacedPcmRelay(send, clock=clk.monotonic, sleep=clk.sleep)
    relay.start()
    for _ in range(3):
        relay.enqueue(bytes(9600))
    assert relay.pending_audio_ms == 600
    await entered.wait()
    assert relay.pending_audio_ms == 600
    release.set()
    await relay.drain()
    assert relay.pending_audio_ms == 0
    assert relay.sent_audio_ms == 600
    assert relay.metrics() == {
        "pending_audio_ms": 0,
        "sent_audio_ms": 600,
    }


@pytest.mark.asyncio
async def test_queued_metric_only_counts_accepted_audio():
    async def send(seq, pcm):
        pass
    relay = PacedPcmRelay(send, max_frames=1)
    relay.start()
    relay.enqueue(bytes(9600))
    with pytest.raises(AudioRelayOverloaded):
        relay.enqueue(bytes(9600))
    assert relay.pending_audio_ms == 200
    await relay.cancel()

@pytest.mark.asyncio
async def test_reported_queue_snapshot_excludes_already_sent_chunk():
    clk = Clock()
    snapshots = []
    async def send(seq, pcm):
        return None
    async def progress(stats):
        snapshots.append(stats)

    relay = PacedPcmRelay(
        send, clock=clk.monotonic, sleep=clk.sleep,
        on_progress=progress,
    )
    relay.start()
    for _ in range(6):
        relay.enqueue(bytes(9600))
    await relay.drain()
    assert snapshots == [{
        "pending_audio_ms": 200,
        "sent_audio_ms": 1000,
    }]

@pytest.mark.asyncio
async def test_hundred_ms_pcm_frames_are_sent_at_hundred_ms_cadence():
    clock = Clock()
    sent = []

    async def send(sequence, pcm):
        sent.append((sequence, pcm, clock.monotonic()))

    relay = PacedPcmRelay(send, clock=clock.monotonic, sleep=clock.sleep)
    relay.start()
    payloads = [bytes([i, i]) * 2400 for i in (1, 2, 3, 4)]
    for frame in payloads:
        relay.enqueue(frame)
    await relay.drain()
    assert [seq for seq, _, _ in sent] == [1, 2, 3, 4]
    assert [frame for _, frame, _ in sent] == payloads
    assert [round(at, 3) for _, _, at in sent] == [0, 0.1, 0.2, 0.3]
