import 'dart:typed_data';

/// The C track exclusively handles microphone audio; MEDIA is not an option.
const String translationSourceMode = 'MIC';

enum TranslationLanguage {
  tamil('Tamil', 'ta', 'ta-IN'),
  english('English', 'en', 'en-US');

  const TranslationLanguage(this.label, this.code, this.ttsLocale);
  final String label;
  final String code;
  final String ttsLocale;
}

enum AiSessionStatus {
  stopped,
  permissionDenied,
  connecting,
  listening,
  translating,
  delayed,
  unavailable,
}

enum LocalAudioLinkStatus { notIntegrated, connected, degraded, disconnected }

class TranslationSegment {
  const TranslationSegment({
    required this.id,
    required this.original,
    required this.translated,
    required this.receivedAt,
    required this.isMock,
    this.measuredLatencyMs,
    this.voiceBytes,
    this.audioMimeType,
  });

  final String id;
  final String original;
  final String translated;
  final DateTime receivedAt;

  /// Always shown in the UI when the result is fixture-generated.
  final bool isMock;

  /// Null unless an actual gateway supplies a measured value.
  final int? measuredLatencyMs;
  final Uint8List? voiceBytes;
  final String? audioMimeType;
}
