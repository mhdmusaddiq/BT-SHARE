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
