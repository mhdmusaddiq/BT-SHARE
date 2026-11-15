import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:audio_stream_player/audio_stream_player.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:flutter_tts/flutter_tts.dart';

import 'adaptive_startup_buffer.dart';
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

/// Explicit categories for failures in the Android PCM streaming pipeline.
/// Never attach raw exception messages, samples, transcripts, or credentials.
enum LivePcmFailureStage {
  initialization,
  bufferQuery,
  excessiveBacklog,
  feed,
  playbackStart,
}

class LivePcmPlaybackFailure implements Exception {
  const LivePcmPlaybackFailure(this.stage, {this.bufferedMs});

  final LivePcmFailureStage stage;
  final int? bufferedMs;
}

/// Native PCM playback events. 'Playback accepted' is NOT proof that
/// sound has reached the speaker; AudioTrack hardware delay is unmeasured.
enum LiveVoiceEvent { playbackAccepted, underrun }

abstract class LiveVoiceEventSource {
  Stream<LiveVoiceEvent> get liveVoiceEvents;
}

/// Debug info about the startup buffer; dynamically adjustable only between
/// live streams, never by interrupting speech already playing.
abstract class LiveVoiceBufferInfo {
  bool get adaptiveStartupEnabled;
  int get currentStartupBufferMs;
}

class DeviceTranslatedVoiceOutput
    implements TranslatedVoiceOutput, LiveTranslatedVoiceOutput,
        LiveVoiceEventSource, LiveVoiceBufferInfo {
  final FlutterTts _tts = FlutterTts();
  final AudioPlayer _player = AudioPlayer();
  final StreamController<LiveVoiceEvent> _events =
      StreamController<LiveVoiceEvent>.broadcast();

  @override
  Stream<LiveVoiceEvent> get liveVoiceEvents => _events.stream;

  AudioStreamPlayer? _live;
  StreamSubscription<void>? _liveUnderrunSubscription;
  Timer? _liveStartupTimer;
  bool _livePlaybackStarted = false;
  int _liveUnderrunCount = 0;

  // Client-side jitter protection. 24kHz mono PCM16, input from an already
  // authenticated realtime gateway. Start after some audio is buffered.
  // Override with a dart-define to A/B test the latency/quality trade-off.
  static const int _requestedPrebufferMs = int.fromEnvironment(
    'BT_LIVE_VOICE_PREBUFFER_MS',
    defaultValue: 320,
  );
  static const int _requestedMaxWaitMs = int.fromEnvironment(
    'BT_LIVE_VOICE_MAX_WAIT_MS',
    defaultValue: 600,
  );
  static const bool _adaptiveEnabled = bool.fromEnvironment(
    'BT_LIVE_VOICE_ADAPTIVE_STARTUP',
    defaultValue: false,
  );
  late final AdaptiveStartupBuffer _startupPolicy = AdaptiveStartupBuffer(
    initialMs: _requestedPrebufferMs.clamp(100, 750).toInt(),
    enabled: _adaptiveEnabled,
    minimumMs: _adaptiveEnabled ? 160 : 100,
    maximumMs: _adaptiveEnabled ? 700 : 750,
  );

  @override
  bool get adaptiveStartupEnabled => _adaptiveEnabled;

  @override
  int get currentStartupBufferMs => _startupPolicy.targetMs;
  static final Duration _maximumStartupWait = Duration(
    milliseconds: _requestedMaxWaitMs.clamp(200, 1200).toInt(),
  );

  @visibleForTesting
  int get liveUnderrunCount => _liveUnderrunCount;

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
    final live = await AudioStreamPlayer.create(
      sampleRate: 24000,
      channels: 1,
      format: PcmFormat.s16le,
    );
    _live = live;
    _startupPolicy.beginStream();
    _livePlaybackStarted = false;
    _liveUnderrunCount = 0;
    _liveUnderrunSubscription = live.onUnderrun.listen((_) {
      if (_live != live) return;
      _liveUnderrunCount++;
      _startupPolicy.recordUnderrun();
      if (!_events.isClosed) _events.add(LiveVoiceEvent.underrun);
      if (kDebugMode) {
        // No microphone content, translations, identifiers or tokens logged.
        debugPrint('BT SHARE realtime audio underrun: #$_liveUnderrunCount');
      }
    });
    // Playback does NOT begin here: wait for a small PCM jitter buffer.
  }

  Future<void> _beginLivePlayback(AudioStreamPlayer live) async {
    if (_live != live || _livePlaybackStarted) return;
    _liveStartupTimer?.cancel();
    _liveStartupTimer = null;
    _livePlaybackStarted = true;
    try {
      await live.play();
      if (_live == live && !_events.isClosed) {
        _events.add(LiveVoiceEvent.playbackAccepted);
      }
    } on Object {
      _livePlaybackStarted = false;
      rethrow;
    }
  }

  @override
  Future<void> pushLive(Uint8List pcm16) async {
    if (pcm16.isEmpty || pcm16.length.isOdd) {
      throw const LivePcmPlaybackFailure(LivePcmFailureStage.feed);
    }
    var stage = LivePcmFailureStage.initialization;
    int? lastBufferedMs;
    try {
      await startLive();
      final live = _live;
      if (live == null) return;

      stage = LivePcmFailureStage.bufferQuery;
      final before = await live.bufferedDuration();
      lastBufferedMs = before.inMilliseconds;
      if (_live != live) return; // Muted or stopped while native call pending.

      // audio_stream_player buffers PCM without a fixed capacity. Never allow
      // a delayed output track to accumulate minutes of stale translation.
      if (before > const Duration(seconds: 2)) {
        _startupPolicy.discardStream();
        try {
          await stopLive();
        } on Object {
          // Keep the original backlog cause even if cleanup also fails.
        }
        throw LivePcmPlaybackFailure(
          LivePcmFailureStage.excessiveBacklog,
          bufferedMs: before.inMilliseconds,
        );
      }

      stage = LivePcmFailureStage.feed;
      await live.feed(pcm16);
      if (_live != live) return;
      _startupPolicy.recordChunk();
      if (_livePlaybackStarted) return;

      stage = LivePcmFailureStage.bufferQuery;
      final ready = await live.bufferedDuration();
      if (_live != live || _livePlaybackStarted) return;
      if (ready >= Duration(milliseconds: _startupPolicy.targetMs)) {
        stage = LivePcmFailureStage.playbackStart;
        await _beginLivePlayback(live);
      } else {
        _liveStartupTimer ??= Timer(_maximumStartupWait, () {
          _liveStartupTimer = null;
          if (_live == live && !_livePlaybackStarted) {
            unawaited(_beginLivePlayback(live).catchError((Object error) {
              if (kDebugMode) {
                // Report only the stage and exception type, not raw messages.
                debugPrint('BT_SHARE_PCM stage=playbackStart '
                    'failure_type=${error.runtimeType}');
              }
            }));
          }
        });
      }
    } on LivePcmPlaybackFailure {
      rethrow;
    } on Object {
      throw LivePcmPlaybackFailure(stage, bufferedMs: lastBufferedMs);
    }
  }

  @override
  Future<void> stopLive() async {
    _liveStartupTimer?.cancel();
    _liveStartupTimer = null;
    _livePlaybackStarted = false;
    final live = _live;
    _live = null;
    if (live != null) _startupPolicy.finishStream();
    await _liveUnderrunSubscription?.cancel();
    _liveUnderrunSubscription = null;
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
    await _events.close();
  }
}
