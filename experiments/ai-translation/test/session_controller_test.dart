import 'dart:async';
import 'dart:typed_data';

import 'package:bt_share_ai_translation_poc/translation/microphone_input.dart';
import 'package:bt_share_ai_translation_poc/translation/models.dart';
import 'package:bt_share_ai_translation_poc/translation/session_controller.dart';
import 'package:bt_share_ai_translation_poc/translation/translation_provider.dart';
import 'package:bt_share_ai_translation_poc/translation/voice_output.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeMicrophone implements MicrophoneInput {
  FakeMicrophone({this.allowed = true});
  bool allowed;
  bool recording = false;
  final stream = StreamController<Uint8List>.broadcast();

  @override
  Future<bool> requestPermission() async => allowed;

  @override
  Future<Stream<Uint8List>> startPcm16() async {
    recording = true;
    return stream.stream;
  }

  @override
  Future<void> stop() async => recording = false;

  @override
  Future<void> dispose() async => stream.close();
}

class FakeVoice implements TranslatedVoiceOutput {
  int played = 0;
  @override
  Future<void> play(TranslationSegment segment, TranslationLanguage language) async {
    played++;
  }

  @override
  Future<void> stop() async {}

  @override
  Future<void> dispose() async {}
}

Future<void> flushEvents() => Future<void>.delayed(const Duration(milliseconds: 20));

void main() {
  test('phase 2.3 known backlog has actionable error, captions remain',
      () async {
    final mic = FakeMicrophone();
    final provider = StreamingProgressProvider();
    final voice = FailingLiveVoice(
      const LivePcmPlaybackFailure(
        LivePcmFailureStage.excessiveBacklog, bufferedMs: 2250,
      ),
    );
    final c = TranslationSessionController(
      microphone: mic, providerFactory: () => provider, voice: voice,
    );
    c.setCloudConsentGiven(true);
    c.setAutoSpeak(true);
    await c.start();
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(c.playbackError, contains('2250ms'));
    expect(c.playbackError, contains('buffer'));
    expect(c.status, AiSessionStatus.listening);
    provider.send(TranslationSegment(
      id: 'live-current',
      original: 'வணக்கம்',
      translated: 'Hello',
      receivedAt: DateTime.now(),
      isMock: false,
      streamingAudio: true,
    ));
    await flushEvents();
    expect(c.latest?.translated, 'Hello');
    await c.stop();
    c.dispose();
  });

  test('phase 2.3 native feed failure is distinguished from backlog',
      () async {
    final mic = FakeMicrophone();
    final provider = StreamingProgressProvider();
    final voice = FailingLiveVoice(
      const LivePcmPlaybackFailure(LivePcmFailureStage.feed),
    );
    final c = TranslationSessionController(
      microphone: mic, providerFactory: () => provider, voice: voice,
    );
    c.setCloudConsentGiven(true);
    c.setAutoSpeak(true);
    await c.start();
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(c.playbackError, contains('audio feed failed'));
    expect(c.playbackError, isNot(contains('buffer exceeded')));
    await c.stop();
    c.dispose();
  });

  test('phase 2.2 audio diagnostics reset, count and maintain monotonic origin',
      () async {
    final d = LiveSessionDiagnostics();
    d.markTargetCaption();
    expect(d.firstTargetCaptionMs, isNull);
    d.start();
    d.markMicFrame();
    d.markTargetCaption();
    d.markAudioPacket();
    d.markAudioPacket();
    d.markPlaybackAccepted();
    d.markUnderrun();
    expect(d.firstTargetCaptionMs, isNotNull);
    expect(d.firstAudioPacketMs, isNotNull);
    expect(d.firstPlaybackAcceptedMs, isNotNull);
    expect(d.audioChunksReceived, 2);
    expect(d.underruns, 1);
    expect(d.captionToAudioReceivedMs, isNotNull);
    expect(d.audioReceivedToPlaybackAcceptedMs, isNotNull);
    expect(d.captionToPlaybackAcceptedMs, isNotNull);
    expect(d.summary, contains('Caption→audio'));
    expect(d.summary, contains('Audio→player'));
    d.stop();
    d.reset();
    expect(d.firstAudioPacketMs, isNull);
    expect(d.audioChunksReceived, 0);
    expect(d.underruns, 0);
  });

  test('phase 2.2 controller sees first audio even when auto-speak OFF',
      () async {
    final mic = FakeMicrophone();
    final provider = StreamingProgressProvider();
    final voice = FakeLiveVoice();
    final c = TranslationSessionController(
      microphone: mic, providerFactory: () => provider, voice: voice,
    );
    c.setCloudConsentGiven(true);
    await c.start();
    mic.stream.add(Uint8List(3200));
    provider.send(TranslationSegment(
      id: 'live-current', original: 'வணக்கம்', translated: 'Hello',
      receivedAt: DateTime.now(), isMock: false, streamingAudio: true,
    ));
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(c.liveDiagnostics.firstTargetCaptionMs, isNotNull);
    expect(c.liveDiagnostics.firstAudioPacketMs, isNotNull);
    expect(c.liveDiagnostics.audioChunksReceived, 1);
    expect(voice.liveChunks, 0);
    c.setAutoSpeak(true);
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    voice.emitEvent(LiveVoiceEvent.playbackAccepted);
    voice.emitEvent(LiveVoiceEvent.underrun);
    await flushEvents();
    expect(c.liveDiagnostics.firstPlaybackAcceptedMs, isNotNull);
    expect(c.liveDiagnostics.underruns, 1);
    await c.stop();
    expect(c.liveDiagnostics.audioChunksReceived, 2);
    c.dispose();
  });

  test('permission denial does not start MIC capture', () async {
    final mic = FakeMicrophone(allowed: false);
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: MockTranslationProvider.new,
      voice: FakeVoice(),
    );
    await c.start();
    expect(c.status, AiSessionStatus.permissionDenied);
    expect(mic.recording, isFalse);
    c.dispose();
  });

  test('MIC-only mock creates captions and stop releases capture', () async {
    final mic = FakeMicrophone();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: MockTranslationProvider.new,
      voice: FakeVoice(),
    );
    c.swapLanguages();
    await c.start();
    expect(c.source, TranslationLanguage.english);
    expect(c.status, AiSessionStatus.listening);
    mic.stream.add(Uint8List(48000));
    await flushEvents();
    expect(c.latest?.isMock, isTrue);
    expect(c.latest?.translated, contains('வணக்கம்'));
    await c.stop();
    expect(c.status, AiSessionStatus.stopped);
    expect(mic.recording, isFalse);
    c.dispose();
  });

  test('gateway success then recorder error shows microphone-specific reason', () async {
    final mic = FailingRecorderMicrophone();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: GatewayReadyProvider.new,
      voice: FakeVoice(),
    );
    c.setCloudConsentGiven(true);
    await c.start();
    expect(c.status, AiSessionStatus.unavailable);
    expect(c.errorMessage, contains('Gateway connected, but microphone'));
    expect(c.errorMessage, isNot(contains('Android hardware-specific')));
    c.dispose();
  });

  test('progress caption updates with later voice without duplicate cards',
      () async {
    final mic = FakeMicrophone();
    final provider = ProgressProvider();
    final voice = FakeVoice();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: () => provider,
      voice: voice,
    );
    c.setCloudConsentGiven(true);
    c.setAutoSpeak(true);
    await c.start();
    expect(c.status, AiSessionStatus.listening);
    provider.send(
      TranslationSegment(
        id: 'cloud-1',
        original: 'வணக்கம்',
        translated: 'Hello',
        receivedAt: DateTime.now(),
        isMock: false,
      ),
    );
    await flushEvents();
    expect(c.segments.length, 1);
    expect(voice.played, 0);
    provider.send(
      TranslationSegment(
        id: 'cloud-1',
        original: 'வணக்கம்',
        translated: 'Hello',
        receivedAt: DateTime.now(),
        isMock: false,
        voiceBytes: Uint8List.fromList([82, 73, 70, 70]),
        audioMimeType: 'audio/wav',
      ),
    );
    await flushEvents();
    expect(c.segments.length, 1);
    expect(c.latest?.voiceBytes, isNotNull);
    expect(voice.played, 1);
    await c.stop();
    expect(mic.recording, isFalse);
    c.dispose();
  });

  test('early caption cannot play until generated voice arrives', () async {
    final mic = FakeMicrophone();
    final provider = ProgressProvider();
    final voice = FakeVoice();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: () => provider,
      voice: voice,
    );
    c.setCloudConsentGiven(true);
    await c.start();
    final early = TranslationSegment(
      id: 'cloud-7', original: 'வணக்கம்', translated: 'Hello',
      receivedAt: DateTime.now(), isMock: false,
    );
    provider.send(early);
    await flushEvents();
    expect(c.canPlaySegment(c.latest!), isFalse);
    await c.playSegment(early);
    expect(voice.played, 0);
    expect(c.playbackError, contains('still generating'));
    provider.send(TranslationSegment(
      id: 'cloud-7', original: 'வணக்கம்', translated: 'Hello',
      receivedAt: DateTime.now(), isMock: false,
      voiceBytes: Uint8List.fromList([73, 68, 51]),
      audioMimeType: 'audio/mpeg',
    ));
    await flushEvents();
    expect(c.canPlaySegment(c.latest!), isTrue);
    // Even if a caller retains the earlier caption object, play the new audio.
    await c.playSegment(early);
    expect(voice.played, 1);
    expect(c.playbackError, isNull);
    await c.stop();
    c.dispose();
  });

  test('realtime caption deltas update a single card without voice', () async {
    final mic = FakeMicrophone();
    final provider = ProgressProvider();
    final voice = FakeVoice();
    final c = TranslationSessionController(
      microphone: mic, providerFactory: () => provider, voice: voice,
    );
    c.setCloudConsentGiven(true);
    c.setAutoSpeak(true);
    await c.start();
    provider.send(TranslationSegment(
      id: 'live-current', original: 'வண', translated: 'Hel',
      receivedAt: DateTime.now(), isMock: false, captionOnly: true,
    ));
    await flushEvents();
    expect(c.segments.length, 1);
    expect(c.latest?.translated, 'Hel');
    expect(c.latest?.captionOnly, isTrue);
    expect(c.canPlaySegment(c.latest!), isFalse);
    provider.send(TranslationSegment(
      id: 'live-current', original: 'வணக்கம்', translated: 'Hello',
      receivedAt: DateTime.now(), isMock: false, captionOnly: true,
    ));
    await flushEvents();
    expect(c.segments.length, 1);
    expect(c.latest?.original, 'வணக்கம்');
    expect(c.latest?.translated, 'Hello');
    expect(voice.played, 0);
    await c.playSegment(c.latest!);
    expect(c.playbackError, contains('Live caption mode'));
    await c.stop();
    expect(mic.recording, isFalse);
    c.dispose();
  });

  test('realtime live PCM plays only with auto-speak enabled and stops safely',
      () async {
    final mic = FakeMicrophone();
    final provider = StreamingProgressProvider();
    final voice = FakeLiveVoice();
    final c = TranslationSessionController(
      microphone: mic, providerFactory: () => provider, voice: voice,
    );
    c.setCloudConsentGiven(true);
    await c.start();
    expect(c.streamingAudioAvailable, isTrue);
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(voice.liveChunks, 0); // default auto-speak OFF
    c.setAutoSpeak(true);
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(voice.liveChunks, 1);
    c.setAutoSpeak(false);
    provider.emitAudio(Uint8List(9600));
    await flushEvents();
    expect(voice.liveChunks, 1);
    await c.stop();
    expect(mic.recording, isFalse);
    expect(voice.stopped, isTrue);
    c.dispose();
  });

  test('AI failure never changes local room-link status', () async {
    final mic = FakeMicrophone();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: () => FailingProvider(),
      voice: FakeVoice(),
    );
    // The integration layer retains responsibility for local audio state.
    c.localAudioStatus = LocalAudioLinkStatus.connected;
    await c.start();
    expect(c.status, AiSessionStatus.unavailable);
    expect(c.errorMessage, 'offline');
    expect(c.localAudioStatus, LocalAudioLinkStatus.connected);
    c.dispose();
  });
}

class ProgressProvider implements TranslationProvider {
  final _segments = StreamController<TranslationSegment>.broadcast();

  void send(TranslationSegment segment) => _segments.add(segment);

  @override
  bool get isMock => false;

  @override
  Stream<TranslationSegment> get segments => _segments.stream;

  @override
  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  }) async {}

  @override
  Future<void> sendMicrophonePcm(Uint8List data) async {}

  @override
  Future<void> close() async => _segments.close();
}

class StreamingProgressProvider extends ProgressProvider
    implements StreamingTranslationProvider {
  final _pcm = StreamController<Uint8List>.broadcast();
  @override
  bool get supportsStreamingAudio => true;
  @override
  Stream<Uint8List> get translatedPcmChunks => _pcm.stream;
  void emitAudio(Uint8List bytes) => _pcm.add(bytes);
  @override
  Future<void> close() async {
    await _pcm.close();
    await super.close();
  }
}

class FakeLiveVoice extends FakeVoice
    implements LiveTranslatedVoiceOutput, LiveVoiceEventSource {
  final _liveEvents = StreamController<LiveVoiceEvent>.broadcast();
  @override
  Stream<LiveVoiceEvent> get liveVoiceEvents => _liveEvents.stream;
  void emitEvent(LiveVoiceEvent event) => _liveEvents.add(event);
  int liveChunks = 0;
  bool stopped = false;
  @override
  Future<void> startLive() async {}
  @override
  Future<void> pushLive(Uint8List pcm16) async {
    liveChunks++;
  }
  @override
  Future<void> stopLive() async { stopped = true; }
  @override
  Future<void> stop() async { stopped = true; }
  @override
  Future<void> dispose() async { await _liveEvents.close(); }
}

class FailingLiveVoice extends FakeLiveVoice {
  FailingLiveVoice(this.failure);
  final LivePcmPlaybackFailure failure;

  @override
  Future<void> pushLive(Uint8List pcm16) async => throw failure;
}

class FailingRecorderMicrophone extends FakeMicrophone {
  @override
  Future<Stream<Uint8List>> startPcm16() {
    throw StateError('Android hardware-specific exception');
  }
}

class GatewayReadyProvider implements TranslationProvider {
  @override
  bool get isMock => false;
  @override
  Stream<TranslationSegment> get segments => const Stream.empty();
  @override
  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  }) async {}
  @override
  Future<void> sendMicrophonePcm(Uint8List data) async {}
  @override
  Future<void> close() async {}
}

class FailingProvider implements TranslationProvider {
  @override
  bool get isMock => false;
  @override
  Stream<TranslationSegment> get segments => const Stream.empty();
  @override
  Future<void> connect({
    required TranslationLanguage source,
    required TranslationLanguage target,
  }) async => throw const TranslationProviderFailure('offline');

  @override
  Future<void> sendMicrophonePcm(Uint8List data) async {}

  @override
  Future<void> close() async {}
}
