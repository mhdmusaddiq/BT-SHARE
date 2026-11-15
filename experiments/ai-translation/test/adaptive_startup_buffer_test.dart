import 'package:bt_share_ai_translation_poc/translation/adaptive_startup_buffer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('disabled policy preserves existing 320ms buffer', () {
    final policy = AdaptiveStartupBuffer(initialMs: 320);
    policy.beginStream();
    for (var i = 0; i < 25; i++) {
      policy.recordChunk();
    }
    policy.finishStream();
    expect(policy.targetMs, 320);
  });

  test('clean long stream gradually lowers next-session startup target', () {
    final policy = AdaptiveStartupBuffer(
      initialMs: 320, enabled: true, minimumMs: 160, maximumMs: 700,
    );
    policy.beginStream();
    for (var i = 0; i < 20; i++) {
      policy.recordChunk();
    }
    policy.finishStream();
    expect(policy.targetMs, 280);
    policy.finishStream(); // Idempotent stop.
    expect(policy.targetMs, 280);

    for (var i = 0; i < 6; i++) {
      policy.beginStream();
      for (var j = 0; j < 20; j++) {
        policy.recordChunk();
      }
      policy.finishStream();
    }
    expect(policy.targetMs, 160);
  });

  test('underrun raises future target while bounding adjustment', () {
    final policy = AdaptiveStartupBuffer(
      initialMs: 200, enabled: true, minimumMs: 160, maximumMs: 700,
    );
    policy.beginStream();
    policy.recordUnderrun();
    policy.finishStream();
    expect(policy.targetMs, 320);

    policy.beginStream();
    for (var i = 0; i < 10; i++) {
      policy.recordUnderrun();
    }
    policy.finishStream();
    expect(policy.targetMs, 560);
    policy.beginStream();
    policy.recordUnderrun();
    policy.finishStream();
    expect(policy.targetMs, 680);
  });

  test('short and failed streams do not decrease target', () {
    final policy = AdaptiveStartupBuffer(initialMs: 240, enabled: true);
    policy.beginStream();
    policy.recordChunk();
    policy.finishStream();
    expect(policy.targetMs, 240);
    policy.beginStream();
    for (var i = 0; i < 30; i++) {
      policy.recordChunk();
    }
    policy.discardStream();
    policy.finishStream();
    expect(policy.targetMs, 240);
  });
}
