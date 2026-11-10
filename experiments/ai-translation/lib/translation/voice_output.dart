import 'package:audioplayers/audioplayers.dart';
import 'package:flutter_tts/flutter_tts.dart';

import 'models.dart';

abstract class TranslatedVoiceOutput {
  Future<void> play(TranslationSegment segment, TranslationLanguage language);
  Future<void> stop();
  Future<void> dispose();
}

class DeviceTranslatedVoiceOutput implements TranslatedVoiceOutput {
  final FlutterTts _tts = FlutterTts();
  final AudioPlayer _player = AudioPlayer();

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
    await _player.play(BytesSource(bytes));
  }

  @override
  Future<void> stop() async {
    await _tts.stop();
    await _player.stop();
  }

  @override
  Future<void> dispose() async {
    await stop();
    await _player.dispose();
  }
}
