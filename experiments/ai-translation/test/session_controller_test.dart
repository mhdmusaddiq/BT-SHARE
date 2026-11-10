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
    expect(c.localAudioStatus, LocalAudioLinkStatus.connected);
    c.dispose();
  });
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
