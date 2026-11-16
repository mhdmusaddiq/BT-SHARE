"""FROZEN caption regression contract for BT SHARE realtime MIC translation.

These tests intentionally protect already-working original Tamil and translated
English caption behavior. Voice/TTS improvements MUST NOT alter these invariants.
No live network, OpenAI billing, audio processing, microphone access, or secrets.
"""
import pytest

from gateway.protocol import ProtocolError
from gateway.realtime_translation import LiveCaptionState


def test_frozen_caption_incremental_tamil_and_english_are_append_only():
    captions = LiveCaptionState()
    assert not captions.dirty

    # Real production protocol event kinds and the active stable segment ID.
    assert captions.accept("session.input_transcript.delta", "வண")
    assert captions.accept("session.output_transcript.delta", "Hel")
    assert captions.accept("session.input_transcript.delta", "க்கம்")
    assert captions.accept("session.output_transcript.delta", "lo friends.")

    assert captions.original == "வணக்கம்"
    assert captions.translated == "Hello friends."
    assert captions.payload() == {
        "type": "segment",
        "segment_id": "live-current",
        "original_text": "வணக்கம்",
        "translated_text": "Hello friends.",
        "caption_only": True,
    }


def test_frozen_caption_does_not_modify_whitespace_or_punctuation():
    captions = LiveCaptionState()
    deltas = ["Hello", ", ", "world", "! ", "Testing", "  ", "captions."]
    for delta in deltas:
        assert captions.accept("session.output_transcript.delta", delta)
    assert captions.translated == "Hello, world! Testing  captions."
    assert captions.payload(1250)["audio_elapsed_ms"] == 1250
    assert "audio_elapsed_ms" not in captions.payload(None)


def test_frozen_caption_ignores_pcm_events_and_empty_deltas():
    captions = LiveCaptionState()
    assert captions.accept("session.output_transcript.delta", "Hello")
    captions.dirty = False  # Reflect normal gateway UI flush.
    assert not captions.accept("session.output_audio.delta", "AA==")
    assert not captions.accept("session.input_transcript.delta", "")
    assert captions.translated == "Hello"
    assert not captions.dirty


def test_frozen_caption_preserves_current_bounded_memory_contract():
    captions = LiveCaptionState(max_chars=7)
    assert captions.accept("session.output_transcript.delta", "abcdef")
    assert captions.accept("session.output_transcript.delta", "gh")
    assert captions.translated == "bcdefgh"
    assert captions.original == ""


def test_frozen_caption_rejects_invalid_fragment_without_mutation():
    captions = LiveCaptionState()
    assert captions.accept("session.input_transcript.delta", "வணக்கம்")
    previous = captions.payload()
    with pytest.raises(ProtocolError, match="Invalid live transcript fragment"):
        captions.accept("session.output_transcript.delta", "a" * 2001)
    assert captions.payload() == previous


def test_frozen_caption_never_invents_spaces_between_text_deltas():
    captions = LiveCaptionState()
    for chunk in ["The", " trans", "lation", " is", " correct"]:
        captions.accept("session.output_transcript.delta", chunk)
    assert captions.translated == "The translation is correct"
