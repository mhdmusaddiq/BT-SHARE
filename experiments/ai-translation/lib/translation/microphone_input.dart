import 'dart:typed_data';

import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';

abstract class MicrophoneInput {
  Future<bool> requestPermission();
  Future<Stream<Uint8List>> startPcm16();
  Future<void> stop();
  Future<void> dispose();
}

/// Captures ONLY live microphone PCM; no media/audio-playback capture APIs.
class RecordMicrophoneInput implements MicrophoneInput {
  final AudioRecorder _recorder = AudioRecorder();

  @override
  Future<bool> requestPermission() async {
    final status = await Permission.microphone.request();
    return status.isGranted;
  }

  @override
  Future<Stream<Uint8List>> startPcm16() {
    return _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
        echoCancel: true,
        noiseSuppress: true,
      ),
    );
  }

  @override
  Future<void> stop() async {
    await _recorder.stop();
  }

  @override
  Future<void> dispose() async {
    await _recorder.dispose();
  }
}

Future<bool> openMicrophoneSettings() => openAppSettings();
