import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'models.dart';
import 'translation_provider.dart';

/// BT SHARE MIC-only segmented translation adapter. Vendor credentials
/// remain on the gateway server, never inside the Flutter application.
class GatewayTranslationProvider implements TranslationProvider {
  GatewayTranslationProvider({
    required this.endpoint,
    this.ephemeralSessionToken = '',
    this.allowInsecureDevLan = false,
  });

  final Uri endpoint;
  final String ephemeralSessionToken;
  final bool allowInsecureDevLan;

  final StreamController<TranslationSegment> _events =
      StreamController<TranslationSegment>.broadcast();
  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  bool _connected = false;
  Completer<void>? _handshake;
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
    final host = endpoint.host;
    final localDevelopment = host == 'localhost' ||
        host == '127.0.0.1' ||
        host == '10.0.2.2' ||
        host.startsWith('192.168.') ||
        host.startsWith('10.') ||
        RegExp(r'^172\.(1[6-9]|2[0-9]|3[01])\.').hasMatch(host);
    final unsafeDevConnection =
        kDebugMode && allowInsecureDevLan && localDevelopment;
    if (endpoint.scheme != 'wss' &&
        !(endpoint.scheme == 'ws' && unsafeDevConnection)) {
      throw const TranslationProviderFailure(
        'Use wss:// or explicitly enable debug-only private-LAN ws://.',
      );
    }
    var connectionPhase = 'websocket';
    try {
      final channel = WebSocketChannel.connect(endpoint);
      _channel = channel;
      await channel.ready.timeout(const Duration(seconds: 12));
      connectionPhase = 'handshake';
      _handshake = Completer<void>();
      _subscription = channel.stream.listen(
        _onMessage,
        onError: (Object error, StackTrace stackTrace) {
          _connected = false;
          if (_handshake case final waiter?) {
            if (!waiter.isCompleted) waiter.completeError(error);
          }
          if (!_events.isClosed) {
            _events.addError(
              const TranslationProviderFailure('AI gateway connection failed.'),
            );
          }
        },
        onDone: () {
          if (_handshake case final waiter?) {
            if (!waiter.isCompleted) {
              waiter.completeError(
                const TranslationProviderFailure('Gateway closed before ready.'),
              );
            }
          }
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
      // No microphone capture before authenticated AI session is ready.
      await _handshake!.future.timeout(const Duration(seconds: 20));
      _connected = true;
    } on TranslationProviderFailure {
      _connected = false;
      rethrow;
    } on TimeoutException {
      _connected = false;
      throw TranslationProviderFailure(
        connectionPhase == 'websocket'
            ? 'Gateway WebSocket connection timed out. Check mobile hotspot and port 8098.'
            : 'Gateway handshake timed out. Check the Python server and dev session token.',
      );
    } on Object {
      _connected = false;
      throw TranslationProviderFailure(
        connectionPhase == 'websocket'
            ? 'Gateway WebSocket connection failed. Check hotspot IP, port 8098 and debug network access.'
            : 'Gateway handshake failed. Check local_gateway.json token and Python server logs.',
      );
    }
  }

  void _onMessage(dynamic raw) {
    try {
      if (raw is! String) return;
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) return;
      if (decoded['type'] == 'ready') {
        if (decoded['source_mode'] != translationSourceMode) {
          throw const TranslationProviderFailure('Invalid source mode.');
        }
        if (_handshake case final waiter?) {
          if (!waiter.isCompleted) waiter.complete();
        }
        return;
      }
      if (decoded['type'] == 'error') {
        final reason = TranslationProviderFailure(
          (decoded['message'] as String?) ?? 'AI service error.',
        );
        if (_handshake case final waiter?) {
          if (!waiter.isCompleted) waiter.completeError(reason);
        }
        if (!_events.isClosed) _events.addError(reason);
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
    // Bound individual messages to the gateway's frame size.
    for (var offset = 0; offset < data.length; offset += 32000) {
      final end = (offset + 32000 < data.length) ? offset + 32000 : data.length;
      final bytes = Uint8List.sublistView(data, offset, end);
      _channel?.sink.add(jsonEncode({
        'type': 'audio',
        'source_mode': translationSourceMode,
        'sequence': ++_sequence,
        'pcm16_base64': base64Encode(bytes),
      }));
    }
  }

  @override
  Future<void> close() async {
    _connected = false;
    // A stop signal permits the backend to flush translated voice.
    if (_channel != null) {
      try {
        _channel!.sink.add(jsonEncode({'type': 'stop'}));
      } on StateError {
        // The connection may already be closed following a provider failure.
      }
    }
    await _subscription?.cancel();
    await _channel?.sink.close();
    await _events.close();
  }
}
