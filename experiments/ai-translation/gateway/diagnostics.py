"""Safe, opt-in first-event timing measurements for a single realtime session.

Only monotonic timings and fixed stage names are logged. No user speech,
transcript, token, network address or other identifiable content is collected.
These server timings begin at the *first upstream input send*, not the
speaker's first audible syllable; they do not measure phone playback.
"""
import logging
import time
from collections.abc import Callable

LOGGER = logging.getLogger("uvicorn.error")
STAGES = frozenset((
    "provider_first_target_caption",
    "provider_first_audio",
    "gateway_first_audio_sent",
))


class RealtimeGatewayMetrics:
    def __init__(
        self,
        *,
        enabled: bool = False,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.enabled = enabled
        self._clock = clock
        self._origin: float | None = None
        self._seen: set[str] = set()
        self.timings_ms: dict[str, int] = {}
        self._gap_recorded = False

    def mark_first_input_send(self) -> None:
        if self._origin is not None:
            return
        self._origin = self._clock()
        self.timings_ms["first_input_send"] = 0
        if self.enabled:
            LOGGER.info("BT_SHARE_METRIC stage=first_input_send elapsed_ms=0")

    def mark(self, stage: str) -> None:
        if stage not in STAGES:
            raise ValueError("Unsupported diagnostic stage")
        if self._origin is None or stage in self._seen:
            return
        self._seen.add(stage)
        elapsed_ms = max(0, round((self._clock() - self._origin) * 1000))
        self.timings_ms[stage] = elapsed_ms
        if self.enabled:
            LOGGER.info(
                "BT_SHARE_METRIC stage=%s elapsed_ms=%d", stage, elapsed_ms
            )
        # Derived server-side ordering metric. This compares FIRST provider
        # events only; neither event is guaranteed to correspond to the
        # same spoken phrase or to actual device speaker onset.
        caption = self.timings_ms.get("provider_first_target_caption")
        audio = self.timings_ms.get("provider_first_audio")
        if not self._gap_recorded and caption is not None and audio is not None:
            self._gap_recorded = True
            gap = caption - audio
            self.timings_ms["provider_caption_minus_audio"] = gap
            if self.enabled:
                LOGGER.info(
                    "BT_SHARE_METRIC stage=caption_minus_audio gap_ms=%d", gap
                )
