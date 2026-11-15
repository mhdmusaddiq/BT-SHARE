import 'dart:typed_data';

import 'package:bt_share_ai_translation_poc/translation/speech_activity_monitor.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List frame(int amplitude) {
  final unsigned = amplitude & 0xffff;
  final low = unsigned & 255;
  final high = unsigned >> 8;
  return Uint8List.fromList(List<int>.generate(
    3200, (index) => index.isEven ? low : high,
  ));
}

void main() {
  test('detects 700ms pause and subsequent resume without storing audio', () {
    final activity = SpeechActivityMonitor();
    final speech = frame(1200);
    final silence = frame(0);
    activity.observe(speech);
    expect(activity.utterancesDetected, 0);
    activity.observe(speech);
    expect(activity.utterancesDetected, 1);
    for (var i = 0; i < 7; i++) {
      activity.observe(silence);
    }
    expect(activity.pausesDetected, 1);
    expect(activity.speaking, isFalse);
    activity.observe(speech);
    activity.observe(speech);
    expect(activity.resumesDetected, 1);
    expect(activity.utterancesDetected, 2);
    expect(activity.summary, contains('Resumes 1'));
    activity.reset();
    expect(activity.utterancesDetected, 0);
  });

  test('handles odd-sized PCM chunks and frame boundaries', () {
    final activity = SpeechActivityMonitor();
    final both = Uint8List.fromList([...frame(1200), ...frame(1200)]);
    activity.observe(Uint8List.sublistView(both, 0, 17));
    activity.observe(Uint8List.sublistView(both, 17));
    expect(activity.utterancesDetected, 1);
    for (var i = 0; i < 6; i++) {
      activity.observe(frame(0));
    }
    expect(activity.pausesDetected, 0);
    activity.observe(frame(0));
    expect(activity.pausesDetected, 1);
  });
}
