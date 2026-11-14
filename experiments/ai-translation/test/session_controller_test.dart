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
