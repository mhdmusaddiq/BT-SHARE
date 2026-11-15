import 'dart:typed_data';

/// Diagnostic-only, energy-based microphone speech activity monitor.
/// Not ASR, not a production VAD, and not correlated to provider audio or
/// translated text. Operates on 16kHz mono PCM16LE in 100ms windows.
/// Stores only counters, never audio content.
class SpeechActivityMonitor {
  SpeechActivityMonitor({
    this.threshold = 370,
    this.voiceFramesToStart = 2,
    this.silentFramesToPause = 7,
  });

  final int threshold;
  final int voiceFramesToStart;
  final int silentFramesToPause;

  int utterancesDetected = 0;
  int pausesDetected = 0;
  int resumesDetected = 0;
  bool speaking = false;

  int _partialLowByte = -1;
  int _samples = 0;
  int _absoluteSum = 0;
  int _voiceFrames = 0;
  int _silenceFrames = 0;

  void reset() {
    utterancesDetected = 0;
    pausesDetected = 0;
    resumesDetected = 0;
    speaking = false;
    _partialLowByte = -1;
    _samples = 0;
    _absoluteSum = 0;
    _voiceFrames = 0;
    _silenceFrames = 0;
  }

  void observe(Uint8List pcm) {
    for (final octet in pcm) {
      if (_partialLowByte == -1) {
        _partialLowByte = octet;
        continue;
      }
      final unsigned = _partialLowByte | (octet << 8);
      final signed = unsigned >= 32768 ? unsigned - 65536 : unsigned;
      _absoluteSum += signed.abs();
      _samples++;
      _partialLowByte = -1;
      if (_samples == 1600) { // 100ms at 16 kHz.
        final voice = _absoluteSum >= threshold * _samples;
        _observeFrame(voice);
        _samples = 0;
        _absoluteSum = 0;
      }
    }
  }

  void _observeFrame(bool voice) {
    if (voice) {
      _silenceFrames = 0;
      _voiceFrames++;
      if (!speaking && _voiceFrames >= voiceFramesToStart) {
        speaking = true;
        utterancesDetected++;
        if (utterancesDetected > 1) resumesDetected++;
      }
    } else {
      _voiceFrames = 0;
      _silenceFrames++;
      if (speaking && _silenceFrames >= silentFramesToPause) {
        speaking = false;
        pausesDetected++;
      }
    }
  }

  String get summary =>
      'Energy speech windows $utterancesDetected · '
      'Pauses $pausesDetected · Resumes $resumesDetected';
}
