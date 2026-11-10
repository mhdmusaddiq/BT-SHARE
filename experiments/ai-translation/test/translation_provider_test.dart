import 'dart:typed_data';

import 'package:bt_share_ai_translation_poc/translation/models.dart';
import 'package:bt_share_ai_translation_poc/translation/translation_provider.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Mock labels fixtures and never fabricates latency or model audio',
      () async {
    final provider = MockTranslationProvider();
    final segments = <TranslationSegment>[];
    final subscription = provider.segments.listen(segments.add);
    await provider.connect(
      source: TranslationLanguage.tamil,
      target: TranslationLanguage.english,
    );
    await provider.sendMicrophonePcm(Uint8List(48000));
    await Future<void>.delayed(Duration.zero);
    expect(segments, hasLength(1));
    expect(segments.single.isMock, isTrue);
    expect(segments.single.original, contains('வணக்கம்'));
    expect(segments.single.translated, contains('Hello'));
    expect(segments.single.measuredLatencyMs, isNull);
    expect(segments.single.voiceBytes, isNull);
    await subscription.cancel();
    await provider.close();
  });

  test('Swapped direction returns English sample and Tamil translation',
      () async {
    final provider = MockTranslationProvider();
    final segments = <TranslationSegment>[];
    final subscription = provider.segments.listen(segments.add);
    await provider.connect(
      source: TranslationLanguage.english,
      target: TranslationLanguage.tamil,
    );
    await provider.sendMicrophonePcm(Uint8List(48000));
    await Future<void>.delayed(Duration.zero);
    expect(segments.single.original, contains('Hello'));
    expect(segments.single.translated, contains('வணக்கம்'));
    await subscription.cancel();
    await provider.close();
  });
}
