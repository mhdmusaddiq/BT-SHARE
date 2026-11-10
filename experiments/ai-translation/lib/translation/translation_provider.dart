import 'dart:async';
import 'dart:typed_data';

import 'models.dart';

abstract class TranslationProvider {
  bool get isMock;
  Stream<TranslationSegment> get segments;

  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  });

  /// Input is microphone PCM16 mono, sampled at 16kHz. Never MEDIA.
  Future<void> sendMicrophonePcm(Uint8List data);
  Future<void> close();
}

class TranslationProviderFailure implements Exception {
  const TranslationProviderFailure(this.message);
  final String message;

  @override
  String toString() => 'TranslationProviderFailure: $message';
}

/// Deterministic captions are NOT recognized from the microphone.
/// Mic bytes only pace fixture emission; user speech is NOT understood.
class MockTranslationProvider implements TranslationProvider {
  final StreamController<TranslationSegment> _events =
      StreamController<TranslationSegment>.broadcast();

  TranslationLanguage _source = TranslationLanguage.tamil;
  TranslationLanguage _target = TranslationLanguage.english;
  int _byteCount = 0;
  int _sequence = 0;
  bool _connected = false;

  static const _samples = [
    (
      tamil: 'வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்?',
      english: 'Hello, how are you?',
    ),
    (
      tamil: 'இந்த செயலியில் மொழிபெயர்ப்பை சோதிக்கிறோம்.',
      english: 'We are testing translation in this app.',
    ),
    (
      tamil: 'தயவுசெய்து மெதுவாக பேசுங்கள்.',
      english: 'Please speak slowly.',
    ),
    (
      tamil: 'எனக்கு உங்கள் குரல் கேட்கிறது.',
      english: 'I can hear your voice.',
    ),
  ];

  @override
  bool get isMock => true;

  @override
  Stream<TranslationSegment> get segments => _events.stream;

  @override
  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  }) async {
    if (_events.isClosed) {
      throw const TranslationProviderFailure('Mock provider is closed.');
    }
    if (source == target) {
      throw const TranslationProviderFailure('Source and target must differ.');
    }
    _source = source;
    _target = target;
    _byteCount = 0;
    _sequence = 0;
    _connected = true;
  }

  @override
  Future<void> sendMicrophonePcm(Uint8List data) async {
    if (!_connected) return;
    _byteCount += data.length;
    // Roughly 1.5 seconds of 16kHz, 16-bit, mono input per fixture segment.
    while (_byteCount >= 48000) {
      _byteCount -= 48000;
      final sample = _samples[_sequence % _samples.length];
      final original = _source == TranslationLanguage.tamil
          ? sample.tamil
          : sample.english;
      final translated = _target == TranslationLanguage.tamil
          ? sample.tamil
          : sample.english;
      _events.add(TranslationSegment(
        id: 'MOCK-${++_sequence}',
        original: original,
        translated: translated,
        receivedAt: DateTime.now(),
        isMock: true,
        // No fabricated latency or AI-generated audio bytes.
      ));
    }
  }

  @override
  Future<void> close() async {
    _connected = false;
    await _events.close();
  }
}
