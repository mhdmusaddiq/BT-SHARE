import 'dart:typed_data';

import 'package:audio_stream_player/audio_stream_player.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:flutter_tts/flutter_tts.dart';

import 'models.dart';

abstract class TranslatedVoiceOutput {
  Future<void> play(TranslationSegment segment, TranslationLanguage language);
  Future<void> stop();
  Future<void> dispose();
}

/// Optional 24kHz mono PCM16 playback stream for Realtime Translation.
abstract class LiveTranslatedVoiceOutput {
  Future<void> startLive();
  Future<void> pushLive(Uint8List pcm16);
  Future<void> stopLive();
}

class DeviceTranslatedVoiceOutput
    implements TranslatedVoiceOutput, LiveTranslatedVoiceOutput {
  final FlutterTts _tts = FlutterTts();
  final AudioPlayer _player = AudioPlayer();
  AudioStreamPlayer? _live;

  @override
  Future<void> play(
    TranslationSegment segment,
    TranslationLanguage language,
  ) async {
    await stop();
    if (segment.isMock) {
      // Device synthesizer reading MOCK translation fixture. NOT cloud TTS.
      await _tts.setLanguage(language.ttsLocale);
      await _tts.setSpeechRate(0.47);
      await _tts.speak(segment.translated);
      return;
    }
    final bytes = segment.voiceBytes;
    if (bytes == null || bytes.isEmpty) {
      throw StateError('Gateway supplied no generated translated audio.');
    }
    if (segment.audioMimeType != 'audio/wav' &&
        segment.audioMimeType != 'audio/mpeg') {
      throw StateError('Unsupported gateway audio format.');
    }
    // Voice capture is still active. Request Android media/speaker routing
    // rather than relying on the current recorder or communication route.
    await _player.setAudioContext(AudioContext(
      android: AudioContextAndroid(
        isSpeakerphoneOn: true,
        audioMode: AndroidAudioMode.normal,
        usageType: AndroidUsageType.media,
        contentType: AndroidContentType.speech,
        audioFocus: AndroidAudioFocus.gain,
      ),
    ));
    await _player.setVolume(1.0);
    await _player.play(BytesSource(bytes));
  }

  @override
  Future<void> startLive() async {
    if (_live != null) return;
    _live = await AudioStreamPlayer.create(
      sampleRate: 24000, channels: 1, format: PcmFormat.s16le,
    );
    await _live!.play();
  }

  @override
  Future<void> pushLive(Uint8List pcm16) async {
    if (pcm16.isEmpty || pcm16.length.isOdd) {
      throw StateError('Invalid realtime PCM16 data.');
    }
    await startLive();
    final live = _live!;
    // Bounded playback buffering; never store minutes of stale speech.
    final buffered = await live.bufferedDuration();
    if (buffered > const Duration(seconds: 2)) {
      await stopLive();
      throw StateError('Translated audio buffer exceeded 2 seconds.');
    }
    await live.feed(pcm16);
  }

  @override
  Future<void> stopLive() async {
    final live = _live;
    _live = null;
    if (live != null) {
      await live.stop();
      await live.dispose();
    }
  }

  @override
  Future<void> stop() async {
    await stopLive();
    await _tts.stop();
    await _player.stop();
  }

  @override
  Future<void> dispose() async {
    await stop();
    await _player.dispose();
  }
}
