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

    // Flutter widget tests use an 800x600 viewport by default.
    // This button is inside a scrollable session card below the fold.
    // Ensure it is actually visible before simulating a user tap.
    final startButton = find.text('Start Translation');
    await tester.ensureVisible(startButton);
    await tester.pump();
    await tester.tap(startButton);

    // Drain the controller's asynchronous permission/provider start.
    // Avoid pumpAndSettle() while the session has a periodic elapsed timer.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));

    final stopButton = find.text('Stop Translation');
    expect(stopButton, findsOneWidget);
    await tester.ensureVisible(stopButton);
    await tester.pump();
    await tester.tap(stopButton);
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
  });
}
