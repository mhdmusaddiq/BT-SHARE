import 'dart:async';

import 'package:flutter/foundation.dart';

import 'microphone_input.dart';
import 'models.dart';
import 'translation_provider.dart';
import 'voice_output.dart';

typedef ProviderFactory = TranslationProvider Function();

class TranslationSessionController extends ChangeNotifier {
  TranslationSessionController({
    required this.microphone,
    required this.providerFactory,
    required this.voice,
  });

  final MicrophoneInput microphone;
  final ProviderFactory providerFactory;
  final TranslatedVoiceOutput voice;

  TranslationLanguage source = TranslationLanguage.tamil;
  TranslationLanguage target = TranslationLanguage.english;
  AiSessionStatus status = AiSessionStatus.stopped;
  LocalAudioLinkStatus localAudioStatus =
      LocalAudioLinkStatus.notIntegrated;

  /// This demo NEVER manages or changes the transport/local room connection.
  bool get roomConnected => localAudioStatus == LocalAudioLinkStatus.connected;

  bool voiceEnabled = true;
  bool captionsEnabled = true;
  bool autoSpeak = false;
  bool mockMode = true;
  String? errorMessage;
  String? playbackError;
  DateTime? startedAt;
  DateTime? endedAt;
  final List<TranslationSegment> _segments = [];
  List<TranslationSegment> get segments => List.unmodifiable(_segments);
  TranslationSegment? get latest => _segments.isEmpty ? null : _segments.last;
  int? get latestMeasuredLatencyMs => latest?.measuredLatencyMs;
  int receivedMicChunks = 0;

  TranslationProvider? _provider;
  StreamSubscription<Uint8List>? _micSubscription;
  StreamSubscription<TranslationSegment>? _segmentSubscription;
  Timer? _pulse;
  int _generation = 0;
  bool _disposed = false;
  bool _microphoneStarted = false;
  bool _starting = false;

  bool get active =>
      status == AiSessionStatus.connecting ||
      status == AiSessionStatus.listening ||
      status == AiSessionStatus.translating ||
      status == AiSessionStatus.delayed;

  Duration get elapsed =>
      startedAt == null
          ? Duration.zero
          : (endedAt ?? DateTime.now()).difference(startedAt!);

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void setSource(TranslationLanguage value) {
    if (active) return;
    source = value;
    if (target == value) {
      target = value == TranslationLanguage.english
          ? TranslationLanguage.tamil
          : TranslationLanguage.english;
    }
    _notify();
  }

  void setTarget(TranslationLanguage value) {
    if (active) return;
    target = value;
    if (source == value) {
      source = value == TranslationLanguage.english
          ? TranslationLanguage.tamil
          : TranslationLanguage.english;
    }
    _notify();
  }

  void swapLanguages() {
    if (active) return;
    final previous = source;
    source = target;
    target = previous;
    _notify();
  }

  void setVoiceEnabled(bool enabled) {
    voiceEnabled = enabled;
    if (!enabled) unawaited(voice.stop());
    _notify();
  }

  void setAutoSpeak(bool enabled) {
    autoSpeak = enabled;
    _notify();
  }

  void setCaptionsEnabled(bool enabled) {
    captionsEnabled = enabled;
    _notify();
  }

  Future<void> start() async {
    if (_disposed || active || _starting) return;
    _starting = true;
    final run = ++_generation;
    _segments.clear();
    errorMessage = null;
    playbackError = null;
    endedAt = null;
    startedAt = null;
    receivedMicChunks = 0;
    status = AiSessionStatus.connecting;
    _notify();

    try {
      final authorized = await microphone.requestPermission();
      if (!_current(run)) return;
      if (!authorized) {
        status = AiSessionStatus.permissionDenied;
        errorMessage = 'Microphone permission is required to begin.';
        _notify();
        return;
      }

      final provider = providerFactory();
      _provider = provider;
      mockMode = provider.isMock;
      _segmentSubscription = provider.segments.listen(
        (segment) {
          if (!_current(run)) return;
          _segments.add(segment);
          status = AiSessionStatus.translating;
          errorMessage = null;
          _notify();
          if (voiceEnabled && autoSpeak) {
            unawaited(playSegment(segment));
          }
          // A transcript is available; go back to receiving live MIC samples.
          status = AiSessionStatus.listening;
          _notify();
        },
        onError: (Object e) {
          if (_current(run)) {
            unawaited(_fail(run, 'AI service unavailable.'));
          }
        },
      );
      await provider.connect(source: source, target: target);
      if (!_current(run)) return;

      // Only start capturing AFTER the AI gateway is available. The local
      // room's own audio path remains separate and is never modified here.
      final input = await microphone.startPcm16();
      if (!_current(run)) {
        await microphone.stop();
        return;
      }
      _microphoneStarted = true;
      startedAt = DateTime.now();
      status = AiSessionStatus.listening;
      _pulse = Timer.periodic(const Duration(seconds: 1), (_) {
        if (_current(run)) _notify();
      });
      _micSubscription = input.listen(
        (bytes) {
          if (!_current(run)) return;
          receivedMicChunks++;
          unawaited(provider.sendMicrophonePcm(bytes).catchError((Object e) {
            if (_current(run)) {
              unawaited(_fail(run, 'Translation gateway send failed.'));
            }
          }));
        },
        onError: (Object e) {
          if (_current(run)) {
            unawaited(_fail(run, 'Microphone capture failed.'));
          }
        },
        onDone: () {
          if (_current(run) && active) {
            unawaited(_fail(run, 'Microphone capture stopped.'));
          }
        },
      );
      _notify();
    } on Object {
      if (_current(run)) {
        await _fail(run, 'Unable to start microphone translation.');
      }
    } finally {
      _starting = false;
    }
  }

  bool _current(int run) => !_disposed && run == _generation;

  Future<void> _cleanup() async {
    _pulse?.cancel();
    _pulse = null;
    await _micSubscription?.cancel();
    _micSubscription = null;
    if (_microphoneStarted) {
      _microphoneStarted = false;
      try {
        await microphone.stop();
      } on Object {
        // Best-effort cleanup, preserve the primary session error.
      }
    }
    await _segmentSubscription?.cancel();
    _segmentSubscription = null;
    final provider = _provider;
    _provider = null;
    try {
      await provider?.close();
    } on Object {
      // Best-effort cleanup.
    }
    await voice.stop();
  }

  Future<void> _fail(int run, String message) async {
    if (!_current(run)) return;
    ++_generation;
    endedAt = startedAt == null ? null : DateTime.now();
    status = AiSessionStatus.unavailable;
    errorMessage = message;
    _notify();
    await _cleanup();
  }

  Future<void> stop() async {
    ++_generation;
    endedAt = startedAt == null ? null : DateTime.now();
    status = AiSessionStatus.stopped;
    errorMessage = null;
    _notify();
    await _cleanup();
  }

  Future<void> retry() async {
    await stop();
    await start();
  }

  Future<void> playSegment(TranslationSegment segment) async {
    if (!voiceEnabled) return;
    try {
      playbackError = null;
      await voice.play(segment, target);
    } on Object {
      playbackError = segment.isMock
          ? 'Device text-to-speech is not available for this language.'
          : 'Provider voice unavailable. Configure its audio format.';
      _notify();
    }
  }

  Future<void> stopPlayback() => voice.stop();

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _pulse?.cancel();
    unawaited(_cleanup().then((_) => microphone.dispose()).then(
          (_) => voice.dispose(),
        ));
    super.dispose();
  }
}
