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
    bool initialMockMode = true,
  }) : mockMode = initialMockMode;

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
  bool cloudConsentGiven = false;
  bool mockMode;
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
  StreamSubscription<Uint8List>? _liveAudioSubscription;
  Future<void> _liveAudioTail = Future<void>.value();
  int _queuedAudioBytes = 0;
  bool streamingAudioAvailable = false;
  bool _livePlaybackFailed = false;
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
    if (!enabled && streamingAudioAvailable) {
      unawaited(voice.stop());
    }
    _notify();
  }

  void setCloudConsentGiven(bool enabled) {
    if (active) return;
    cloudConsentGiven = enabled;
    _notify();
  }

  void setCaptionsEnabled(bool enabled) {
    captionsEnabled = enabled;
    _notify();
  }

  Future<void> start() async {
    if (_disposed || active || _starting) return;
    if (!mockMode && !cloudConsentGiven) {
      errorMessage = 'Cloud microphone consent is required.';
      _notify();
      return;
    }
    _starting = true;
    final run = ++_generation;
    _segments.clear();
    errorMessage = null;
    playbackError = null;
    endedAt = null;
    startedAt = null;
    receivedMicChunks = 0;
    streamingAudioAvailable = false;
    _livePlaybackFailed = false;
    _queuedAudioBytes = 0;
    _liveAudioTail = Future<void>.value();
    status = AiSessionStatus.connecting;
    _notify();

    var startupPhase = 'permission';
    try {
      final authorized = await microphone.requestPermission();
      if (!_current(run)) return;
      if (!authorized) {
        status = AiSessionStatus.permissionDenied;
        errorMessage = 'Microphone permission is required to begin.';
        _notify();
        return;
      }

      startupPhase = 'configuration';
      final provider = providerFactory();
      _provider = provider;
      mockMode = provider.isMock;
      _segmentSubscription = provider.segments.listen(
        (segment) {
          if (!_current(run)) return;
          // Realtime deltas update one stable caption rather than adding
          // hundreds of duplicate cards during a conversation.
          final previousIndex = _segments.indexWhere((s) => s.id == segment.id);
          TranslationSegment visible = segment;
          if (previousIndex >= 0) {
            final before = _segments[previousIndex];
            visible = TranslationSegment(
              id: segment.id,
              original: segment.original,
              translated: segment.translated,
              receivedAt: segment.receivedAt,
              isMock: segment.isMock,
              measuredLatencyMs: segment.measuredLatencyMs,
              voiceBytes: segment.voiceBytes ?? before.voiceBytes,
              audioMimeType: segment.audioMimeType ?? before.audioMimeType,
              captionOnly: segment.captionOnly,
              streamingAudio: segment.streamingAudio,
            );
            _segments[previousIndex] = visible;
          } else {
            _segments.add(visible);
          }
          status = AiSessionStatus.translating;
          errorMessage = null;
          if (visible.voiceBytes?.isNotEmpty ?? false) {
            // If the user tapped the early caption, clear its pending-audio
            // warning as soon as the generated audio becomes available.
            playbackError = null;
          }
          _notify();
          if (voiceEnabled && autoSpeak &&
              (segment.isMock || segment.voiceBytes != null)) {
            unawaited(playSegment(segment));
          }
          // A transcript is available; go back to receiving live MIC samples.
          status = AiSessionStatus.listening;
          _notify();
        },
        onError: (Object e) {
          if (_current(run)) {
            unawaited(_fail(run,
              e is TranslationProviderFailure ? e.message : 'AI service unavailable.'));

          }
        },
      );
      startupPhase = 'gateway';
      await provider.connect(source: source, target: target);
      if (!_current(run)) return;
      if (provider is StreamingTranslationProvider &&
          provider.supportsStreamingAudio) {
        streamingAudioAvailable = true;
        _liveAudioSubscription = provider.translatedPcmChunks.listen(
          (bytes) {
            if (!_current(run) || !voiceEnabled || !autoSpeak ||
                _livePlaybackFailed || voice is! LiveTranslatedVoiceOutput) {
              return;
            }
            // Bound future work queued on Dart isolate and Android audio sink.
            if (_queuedAudioBytes + bytes.length > 120000) {
              _livePlaybackFailed = true;
              playbackError = 'Live voice fell behind. Restart translation '
                  'or disable Speak automatically.';
              _notify();
              unawaited(voice.stop());
              return;
            }
            _queuedAudioBytes += bytes.length;
            _liveAudioTail = _liveAudioTail.then((_) async {
              if (!_current(run) || !voiceEnabled || !autoSpeak ||
                  _livePlaybackFailed) return;
              try {
                await (voice as LiveTranslatedVoiceOutput).pushLive(bytes);
              } on Object {
                _livePlaybackFailed = true;
                playbackError = 'Live PCM playback failed on this device. '
                    'Turn off automatic voice or switch to segmented mode.';
                _notify();
                await voice.stop();
              }
            }).whenComplete(() {
              _queuedAudioBytes -= bytes.length;
            });
          },
          onError: (Object error) {
            if (_current(run)) {
              _livePlaybackFailed = true;
              playbackError = 'Realtime voice packet error.';
              _notify();
              unawaited(voice.stop());
            }
          },
        );
      }

      // Only start capturing AFTER the AI gateway is available. The local
      // room's own audio path remains separate and is never modified here.
      startupPhase = 'microphone';
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
    } on Object catch (error) {
      if (_current(run)) {
        // Expose a safe, actionable startup stage. Never show raw platform
        // exceptions, vendor responses, API keys or gateway credentials.
        final message = switch (startupPhase) {
          'permission' => 'Unable to check microphone permission on Android.',
          'configuration' => error is TranslationProviderFailure
              ? error.message
              : 'Invalid AI provider configuration.',
          'gateway' => error is TranslationProviderFailure
              ? error.message
              : 'Unable to connect to the AI gateway.',
          'microphone' =>
            'Gateway connected, but microphone recording could not start. '
                'Check microphone permission and try again.',
          _ => 'Unable to start microphone translation.',
        };
        await _fail(run, message);
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
    await _liveAudioSubscription?.cancel();
    _liveAudioSubscription = null;
    await _liveAudioTail.catchError((Object _) {});
    _queuedAudioBytes = 0;
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

  bool canPlaySegment(TranslationSegment segment) =>
      voiceEnabled && !segment.streamingAudio &&
      (segment.isMock || (segment.voiceBytes?.isNotEmpty ?? false));

  Future<void> playSegment(TranslationSegment segment) async {
    if (!voiceEnabled) return;
    // An early-caption object can remain referenced in a tap callback while
    // the gateway updates that same ID with TTS bytes. Resolve the latest.
    final current = _segments.where((entry) => entry.id == segment.id);
    final playable = current.isNotEmpty ? current.first : segment;
    if (!canPlaySegment(playable)) {
      playbackError = playable.streamingAudio
          ? 'Live voice plays automatically while translating; '
              'enable Speak automatically in Settings.'
          : playable.captionOnly
              ? 'Live caption mode: no streaming voice enabled on server.'
              : 'AI voice is still generating. Try when Play voice is enabled.';
      _notify();
      return;
    }
    try {
      playbackError = null;
      _notify();
      await voice.play(playable, target);
    } on Object {
      playbackError = playable.isMock
          ? 'Device text-to-speech is unavailable for this language.'
          : 'Generated audio could not play on this phone. '
              'Check media volume, Bluetooth audio output and Android logs.';
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
