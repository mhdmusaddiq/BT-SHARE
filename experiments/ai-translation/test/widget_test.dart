import 'dart:async';
import 'dart:typed_data';

import 'package:bt_share_ai_translation_poc/main.dart';
import 'package:bt_share_ai_translation_poc/translation/microphone_input.dart';
import 'package:bt_share_ai_translation_poc/translation/models.dart';
import 'package:bt_share_ai_translation_poc/translation/session_controller.dart';
import 'package:bt_share_ai_translation_poc/translation/translation_provider.dart';
import 'package:bt_share_ai_translation_poc/translation/voice_output.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class TestMic implements MicrophoneInput {
  final frames = StreamController<Uint8List>.broadcast();
  @override
  Future<bool> requestPermission() async => true;
  @override
  Future<Stream<Uint8List>> startPcm16() async => frames.stream;
  @override
  Future<void> stop() async {}
  @override
  Future<void> dispose() async => frames.close();
}

class TestVoice implements TranslatedVoiceOutput {
  @override
  Future<void> play(TranslationSegment segment, TranslationLanguage lang) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> dispose() async {}
}

void main() {
  testWidgets('Dashboard clearly labels source and mock behavior', (tester) async {
    final c = TranslationSessionController(
      microphone: TestMic(),
      providerFactory: MockTranslationProvider.new,
      voice: TestVoice(),
    );
    await tester.pumpWidget(BtShareTranslationApp(controller: c));
    expect(find.text('Live AI Translation'), findsOneWidget);
    expect(find.text('MIC ONLY'), findsOneWidget);
    expect(find.text('MOCK'), findsWidgets);
    expect(find.text('Start Translation'), findsOneWidget);
    expect(find.text('MEDIA translation'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Start changes session to listening', (tester) async {
    final mic = TestMic();
    final c = TranslationSessionController(
      microphone: mic,
      providerFactory: MockTranslationProvider.new,
      voice: TestVoice(),
    );
    await tester.pumpWidget(BtShareTranslationApp(controller: c));
    await tester.tap(find.text('Start Translation'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));
    expect(find.text('Stop Translation'), findsOneWidget);
    await tester.tap(find.text('Stop Translation'));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
  });
}
