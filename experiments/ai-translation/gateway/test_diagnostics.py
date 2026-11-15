"""Phase 2.2 gateway timing tests: no cloud calls or private data."""
import pytest

from gateway.diagnostics import RealtimeGatewayMetrics


def test_first_events_record_relative_to_first_upstream_input():
    now = [100.0]
    metrics = RealtimeGatewayMetrics(clock=lambda: now[0])
    metrics.mark("provider_first_audio")  # Ignore before first input send
    assert metrics.timings_ms == {}
    metrics.mark_first_input_send()
    now[0] += 0.085
    metrics.mark("provider_first_target_caption")
    now[0] += 0.215
    metrics.mark("provider_first_audio")
    now[0] += 0.012
    metrics.mark("gateway_first_audio_sent")
    now[0] += 0.500
    metrics.mark("provider_first_target_caption")  # First event only
    metrics.mark_first_input_send()  # No restart on subsequent frames
    assert metrics.timings_ms == {
        "first_input_send": 0,
        "provider_first_target_caption": 85,
        "provider_first_audio": 300,
        "gateway_first_audio_sent": 312,
        "provider_caption_minus_audio": -215,
    }


def test_disabled_metrics_do_not_write_logs(monkeypatch):
    entries = []
    monkeypatch.setattr(
        "gateway.diagnostics.LOGGER.info",
        lambda *args: entries.append(args),
    )
    m = RealtimeGatewayMetrics(enabled=False, clock=lambda: 1.0)
    m.mark_first_input_send()
    m.mark("provider_first_audio")
    assert entries == []
    assert m.timings_ms["provider_first_audio"] == 0


def test_enabled_diagnostics_emit_only_fixed_stage_and_time(monkeypatch):
    entries = []
    monkeypatch.setattr(
        "gateway.diagnostics.LOGGER.info",
        lambda *args: entries.append(args),
    )
    t = [42.0]
    m = RealtimeGatewayMetrics(enabled=True, clock=lambda: t[0])
    m.mark_first_input_send()
    t[0] = 42.12
    m.mark("provider_first_audio")
    assert entries == [
        ("BT_SHARE_METRIC stage=first_input_send elapsed_ms=0",),
        ("BT_SHARE_METRIC stage=%s elapsed_ms=%d",
         "provider_first_audio", 120),
    ]


def test_diagnostic_stage_allowlist_rejects_untrusted_names():
    m = RealtimeGatewayMetrics()
    m.mark_first_input_send()
    with pytest.raises(ValueError, match="Unsupported diagnostic stage"):
        m.mark("mic_transcript_or_token_goes_here")

def test_provider_audio_before_caption_gap_is_positive_and_logged_once(monkeypatch):
    entries = []
    monkeypatch.setattr(
        "gateway.diagnostics.LOGGER.info", lambda *args: entries.append(args)
    )
    t = [500.0]
    m = RealtimeGatewayMetrics(enabled=True, clock=lambda: t[0])
    m.mark_first_input_send()
    t[0] = 500.74
    m.mark("provider_first_audio")
    t[0] = 503.31
    m.mark("provider_first_target_caption")
    m.mark("provider_first_target_caption")
    assert m.timings_ms["provider_caption_minus_audio"] == 2570
    assert sum("stage=caption_minus_audio" in call[0] for call in entries) == 1
    assert entries[-1] == (
        "BT_SHARE_METRIC stage=caption_minus_audio gap_ms=%d", 2570
    )
