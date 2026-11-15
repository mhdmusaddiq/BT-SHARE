/// Conservative per-session startup buffering. No audio is saved.
/// Each *new* live stream uses the previous stream's quality observations.
/// This does not rebuffer or change playback of an already-running stream.
class AdaptiveStartupBuffer {
  AdaptiveStartupBuffer({
    required int initialMs,
    this.enabled = false,
    this.minimumMs = 160,
    this.maximumMs = 700,
    this.increaseMs = 120,
    this.decreaseMs = 40,
    this.minimumCleanChunks = 20,
  }) : assert(minimumMs > 0 && maximumMs >= minimumMs),
       _targetMs = initialMs.clamp(minimumMs, maximumMs).toInt();

  final bool enabled;
  final int minimumMs;
  final int maximumMs;
  final int increaseMs;
  final int decreaseMs;
  final int minimumCleanChunks;

  int _targetMs;
  int _chunks = 0;
  int _underruns = 0;
  bool _active = false;

  int get targetMs => _targetMs;

  void beginStream() {
    _chunks = 0;
    _underruns = 0;
    _active = true;
  }

  void recordChunk() {
    if (_active) _chunks++;
  }

  void recordUnderrun() {
    if (_active) _underruns++;
  }

  /// Call when an audio stream is discarded for failure/backlog. Do not
  /// reward a failed session as if it completed cleanly.
  void discardStream() {
    _active = false;
    _chunks = 0;
    _underruns = 0;
  }

  void finishStream() {
    if (!_active) return;
    _active = false;
    if (!enabled) return;
    if (_underruns > 0) {
      final increase = (_underruns * increaseMs).clamp(0, 240).toInt();
      _targetMs = (_targetMs + increase).clamp(minimumMs, maximumMs).toInt();
    } else if (_chunks >= minimumCleanChunks) {
      _targetMs = (_targetMs - decreaseMs).clamp(minimumMs, maximumMs).toInt();
    }
  }
}
