import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:web_socket_channel/web_socket_channel.dart';

import 'models.dart';
import 'translation_provider.dart';

/// Illustrative BT SHARE gateway protocol. No real backend is included.
/// A secure backend must validate auth, perform ASR/translation/TTS and return
/// timestamped segments. Never ship a persistent AI API secret in a mobile app.
class GatewayTranslationProvider implements TranslationProvider {
  GatewayTranslationProvider({
    required this.endpoint,
    this.ephemeralSessionToken = '',
  });

  final Uri endpoint;
  final String ephemeralSessionToken;

  final StreamController<TranslationSegment> _events =
      StreamController<TranslationSegment>.broadcast();
  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  bool _connected = false;
  int _sequence = 0;

  @override
  bool get isMock => false;

  @override
  Stream<TranslationSegment> get segments => _events.stream;

  @override
  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  }) async {
    if (_events.isClosed) {
      throw const TranslationProviderFailure('Gateway provider is closed.');
    }
    if (source == target) {
      throw const TranslationProviderFailure('Source and target must differ.');
    }
    final localDevelopment = endpoint.host == 'localhost' ||
        endpoint.host == '127.0.0.1' ||
        endpoint.host == '10.0.2.2';
    if (endpoint.scheme != 'wss' &&
        !(endpoint.scheme == 'ws' && localDevelopment)) {
      throw const TranslationProviderFailure(
        'Use a secure wss:// endpoint (ws:// only for local development).',
      );
    }
    try {
      final channel = WebSocketChannel.connect(endpoint);
      _channel = channel;
      await channel.ready.timeout(const Duration(seconds: 12));
      _connected = true;
      _subscription = channel.stream.listen(
        _onMessage,
        onError: (Object error, StackTrace stackTrace) {
          _connected = false;
          if (!_events.isClosed) {
            _events.addError(
              const TranslationProviderFailure('AI gateway connection failed.'),
            );
          }
        },
        onDone: () {
          if (_connected && !_events.isClosed) {
            _events.addError(
              const TranslationProviderFailure('AI gateway disconnected.'),
            );
          }
          _connected = false;
        },
        cancelOnError: false,
      );
      channel.sink.add(jsonEncode({
        'type': 'start',
        'source_mode': translationSourceMode,
        'source_language': source.code,
        'target_language': target.code,
        'format': 'pcm_s16le',
        'sample_rate_hz': 16000,
        'channels': 1,
        if (ephemeralSessionToken.isNotEmpty)
          'ephemeral_session_token': ephemeralSessionToken,
      }));
    } on Object catch (_) {
      _connected = false;
      throw const TranslationProviderFailure(
        'Unable to connect to the translation gateway.',
      );
    }
  }

  void _onMessage(dynamic raw) {
    try {
      if (raw is! String) return;
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) return;
      if (decoded['type'] == 'error') {
        _events.addError(TranslationProviderFailure(
          (decoded['message'] as String?) ?? 'AI service error.',
        ));
        return;
      }
      if (decoded['type'] != 'segment') return;
      final original = decoded['original_text'];
      final translated = decoded['translated_text'];
      if (original is! String || translated is! String) return;
      final base64Voice = decoded['translated_audio_base64'];
      _events.add(TranslationSegment(
        id: (decoded['segment_id'] as String?) ?? 'gateway-${++_sequence}',
        original: original,
        translated: translated,
        receivedAt: DateTime.now(),
        isMock: false,
        measuredLatencyMs: decoded['latency_ms'] is int
            ? decoded['latency_ms'] as int
            : null,
        voiceBytes: base64Voice is String
            ? base64Decode(base64Voice)
            : null,
        audioMimeType: decoded['audio_mime_type'] as String?,
      ));
    } on Object {
      if (!_events.isClosed) {
        _events.addError(
          const TranslationProviderFailure('Invalid gateway response.'),
        );
      }
    }
  }

  @override
  Future<void> sendMicrophonePcm(Uint8List data) async {
    if (!_connected) return;
    // Audio is transmitted to the configured endpoint only in gateway mode.
    _channel?.sink.add(jsonEncode({
      'type': 'audio',
      'source_mode': translationSourceMode,
      'sequence': ++_sequence,
      'pcm16_base64': base64Encode(data),
    }));
  }

  @override
  Future<void> close() async {
    _connected = false;
    await _subscription?.cancel();
    await _channel?.sink.close();
    await _events.close();
  }
}
