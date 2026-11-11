"""Stateful 16 kHz PCM16 mono to 24 kHz PCM16 mono interpolation (POC)."""
from array import array
import io
import sys
import wave


class Pcm16To24:
    def __init__(self):
        self.partial = b""
        self.previous = None
        self.input_index = 0
        self.output_index = 0

    def process(self, source: bytes) -> bytes:
        source = self.partial + source
        self.partial = source[-1:] if len(source) % 2 else b""
        source = source[:len(source) - len(self.partial)]
        samples = array("h")
        samples.frombytes(source)
        if sys.byteorder != "little":
            samples.byteswap()
        output = array("h")
        for current in samples:
            if self.previous is None:
                self.previous = current
                output.append(current)
                self.output_index = 1
                continue
            self.input_index += 1
            while self.output_index * 2 <= self.input_index * 3:
                fraction3 = self.output_index * 2 - (self.input_index - 1) * 3
                value = round(
                    (self.previous * (3 - fraction3) + current * fraction3) / 3
                )
                output.append(max(-32768, min(32767, value)))
                self.output_index += 1
            self.previous = current
        if sys.byteorder != "little":
            output.byteswap()
        return output.tobytes()


def wav_from_pcm24(pcm: bytes) -> bytes:
    if len(pcm) % 2:
        raise ValueError("PCM16 bytes must be sample-aligned")
    output = io.BytesIO()
    with wave.open(output, "wb") as file:
        file.setnchannels(1)
        file.setsampwidth(2)
        file.setframerate(24000)
        file.writeframes(pcm)
    return output.getvalue()
