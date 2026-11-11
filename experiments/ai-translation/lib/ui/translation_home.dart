import 'package:flutter/material.dart';

import '../translation/microphone_input.dart';
import '../translation/models.dart';
import '../translation/session_controller.dart';

const _violet = Color(0xFF6455E8);
const _muted = Color(0xFF697587);

class TranslationHome extends StatefulWidget {
  const TranslationHome({super.key, required this.controller});
  final TranslationSessionController controller;

  @override
  State<TranslationHome> createState() => _TranslationHomeState();
}

class _TranslationHomeState extends State<TranslationHome> {
  TranslationSessionController get controller => widget.controller;

  @override
  void initState() {
    super.initState();
    controller.addListener(_refresh);
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    controller.removeListener(_refresh);
    super.dispose();
  }

  String get _statusLabel {
    switch (controller.status) {
      case AiSessionStatus.stopped:
        return 'Ready to translate';
      case AiSessionStatus.permissionDenied:
        return 'Microphone permission denied';
      case AiSessionStatus.connecting:
        return 'Connecting to translation';
      case AiSessionStatus.listening:
        return 'Listening to your microphone';
      case AiSessionStatus.translating:
        return 'Translating speech';
      case AiSessionStatus.delayed:
        return 'Translation delayed';
      case AiSessionStatus.unavailable:
        return 'AI translation unavailable';
    }
  }

  String get _elapsedLabel {
    final d = controller.elapsed;
    final hours = d.inHours.toString().padLeft(2, '0');
    final minutes = (d.inMinutes % 60).toString().padLeft(2, '0');
    final seconds = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 850;
    final segments = controller.segments.reversed.toList();

    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFFF6F7FB),
        scrolledUnderElevation: 0,
        leading: const Padding(
          padding: EdgeInsets.only(left: 12),
          child: Icon(Icons.graphic_eq_rounded, color: _violet),
        ),
        title: const Text(
          'BT SHARE',
          style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 0.8),
        ),
        actions: [
          if (controller.mockMode)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 13),
              child: Chip(
                label: Text('MOCK', style: TextStyle(fontWeight: FontWeight.w900)),
                backgroundColor: Color(0xFFFFEFC0),
                side: BorderSide.none,
              ),
            ),
          IconButton(
            tooltip: 'Translation settings',
            onPressed: () => _showSettings(context),
            icon: const Icon(Icons.tune_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1120),
            child: ListView(
              padding: EdgeInsets.fromLTRB(wide ? 36 : 18, 14, wide ? 36 : 18, 36),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        'Live AI Translation',
                        style: TextStyle(
                          fontSize: wide ? 34 : 27,
                          fontWeight: FontWeight.w800,
                          height: 1.15,
                          letterSpacing: -0.6,
                        ),
                      ),
                    ),
                    const _Pill(icon: Icons.mic, text: 'MIC ONLY'),
                  ],
                ),
                const SizedBox(height: 8),
                const Text(
                  'Translate speech between Tamil and English in real time.',
                  style: TextStyle(color: _muted, fontSize: 14),
                ),
                const SizedBox(height: 20),
                if (controller.mockMode) _mockNotice(),
                if (controller.mockMode) const SizedBox(height: 16),
                if (!controller.mockMode) ...[
                  const Text(
                    'AI-generated translated voice · Not a human speaker. '
                    'Use headphones to avoid microphone feedback.',
                    style: TextStyle(color: _muted, fontSize: 12),
                  ),
                  const SizedBox(height: 16),
                ],
                _languagePanel(),
                const SizedBox(height: 16),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: _sessionPanel()),
                      const SizedBox(width: 18),
                      Expanded(child: _captionPanel(segments)),
                    ],
                  )
                else ...[
                  _sessionPanel(),
                  const SizedBox(height: 16),
                  _captionPanel(segments),
                ],
                const SizedBox(height: 16),
                _playbackPanel(segments),
                const SizedBox(height: 16),
                _footer(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _mockNotice() => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF5DB),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFFF7D985)),
        ),
        child: const Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.science_outlined, color: Color(0xFF8E6500)),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                'MOCK DEMO · Captions use prerecorded sample phrases. '
                'They are NOT recognized or translated from your speech. '
                'Voice uses device text-to-speech, not an AI model.',
                style: TextStyle(fontSize: 12, color: Color(0xFF755500)),
              ),
            ),
          ],
        ),
      );

  Widget _languagePanel() => _Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('LANGUAGES', style: _eyebrow),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(child: _languageSelector('From', controller.source, controller.setSource)),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 7),
                  child: IconButton.filledTonal(
                    tooltip: 'Swap languages',
                    onPressed: controller.active ? null : controller.swapLanguages,
                    icon: const Icon(Icons.swap_horiz_rounded),
                  ),
                ),
                Expanded(child: _languageSelector('To', controller.target, controller.setTarget)),
              ],
            ),
          ],
        ),
      );

  Widget _languageSelector(
    String label,
    TranslationLanguage selected,
    void Function(TranslationLanguage) onChanged,
  ) =>
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(color: _muted, fontSize: 12)),
          DropdownButtonFormField<TranslationLanguage>(
            key: ValueKey('$label-${selected.name}'),
            initialValue: selected,
            isExpanded: true,
            decoration: const InputDecoration(border: InputBorder.none),
            items: TranslationLanguage.values
                .map((v) => DropdownMenuItem(value: v, child: Text(v.label)))
                .toList(),
            onChanged: controller.active
                ? null
                : (value) {
                    if (value != null) onChanged(value);
                  },
          ),
        ],
      );

  Widget _sessionPanel() {
    final running = controller.active;
    return _Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Expanded(child: Text('TRANSLATION SESSION', style: _eyebrow)),
              _Pill(icon: Icons.shield_outlined, text: 'PRIVACY FIRST'),
            ],
          ),
          const SizedBox(height: 22),
          Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 350),
              width: 112,
              height: 112,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: running ? const Color(0xFFECEAFE) : const Color(0xFFF1F3F8),
                boxShadow: running
                    ? [BoxShadow(color: _violet.withValues(alpha: 0.12), blurRadius: 24, spreadRadius: 7)]
                    : [],
              ),
              child: Icon(
                running ? Icons.graphic_eq_rounded : Icons.mic_none_rounded,
                size: 47,
                color: running ? _violet : _muted,
              ),
            ),
          ),
          const SizedBox(height: 20),
          Center(
            child: Text(
              _statusLabel,
              textAlign: TextAlign.center,
              style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
            ),
          ),
          const SizedBox(height: 6),
          Center(
            child: Text(
              running
                  ? 'Microphone input is active'
                  : 'Your microphone is off until you start',
              style: const TextStyle(color: _muted, fontSize: 12),
            ),
          ),
          const SizedBox(height: 23),
          Row(
            children: [
              Expanded(child: _stat('SESSION', _elapsedLabel)),
              Expanded(
                child: _stat(
                  'AI LATENCY',
                  controller.latestMeasuredLatencyMs == null
                      ? 'Not measured'
                      : '${controller.latestMeasuredLatencyMs} ms',
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            height: 52,
            child: FilledButton.icon(
              onPressed: controller.status == AiSessionStatus.connecting
                  ? null
                  : running
                      ? controller.stop
                      : controller.start,
              icon: Icon(running ? Icons.stop_circle_outlined : Icons.mic_rounded),
              label: Text(
                running ? 'Stop Translation' : 'Start Translation',
                style: const TextStyle(fontWeight: FontWeight.w800),
              ),
            ),
          ),
          if (controller.status == AiSessionStatus.unavailable ||
              controller.status == AiSessionStatus.permissionDenied) ...[
            const SizedBox(height: 12),
            Text(
              controller.errorMessage ?? 'Unable to continue.',
              style: const TextStyle(color: Color(0xFFB42318)),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: controller.retry,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Retry'),
                ),
                if (controller.status == AiSessionStatus.permissionDenied)
                  TextButton(
                    onPressed: openMicrophoneSettings,
                    child: const Text('App settings'),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _stat(String label, String value) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: _eyebrow),
          const SizedBox(height: 6),
          Text(value, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        ],
      );

  Widget _captionPanel(List<TranslationSegment> segments) => _Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(child: Text('LIVE CAPTIONS', style: _eyebrow)),
                Icon(Icons.closed_caption_outlined, color: _violet),
              ],
            ),
            const SizedBox(height: 16),
            if (!controller.captionsEnabled)
              const _Empty('Captions are hidden in settings.')
            else if (segments.isEmpty)
              const _Empty('Start speaking to receive captions. In MOCK mode, sample phrases appear after microphone input.')
            else
              ...segments.take(8).map(
                (segment) => Container(
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.all(15),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF7F8FC),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text('ORIGINAL · ${controller.source.label.toUpperCase()}', style: _eyebrow),
                          const Spacer(),
                          if (segment.isMock)
                            const Text('MOCK', style: TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF8E6500))),
                        ],
                      ),
                      const SizedBox(height: 6),
                      SelectableText(segment.original),
                      const Divider(height: 22),
                      Text('TRANSLATED · ${controller.target.label.toUpperCase()}', style: _eyebrow),
                      const SizedBox(height: 7),
                      SelectableText(
                        segment.translated,
                        style: const TextStyle(fontSize: 17, height: 1.4, color: _violet, fontWeight: FontWeight.w700),
                      ),
                      Align(
                        alignment: Alignment.centerRight,
                        child: IconButton(
                          tooltip: 'Play translated voice',
                          onPressed: controller.voiceEnabled
                              ? () => controller.playSegment(segment)
                              : null,
                          icon: const Icon(Icons.volume_up_outlined),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      );

  Widget _playbackPanel(List<TranslationSegment> segments) => _Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('VOICE OUTPUT', style: _eyebrow),
            const SizedBox(height: 8),
            const Text(
              'Listen to translated audio',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                IconButton.filledTonal(
                  tooltip: 'Play latest translated segment',
                  onPressed: controller.voiceEnabled && segments.isNotEmpty
                      ? () => controller.playSegment(segments.first)
                      : null,
                  icon: const Icon(Icons.play_arrow_rounded),
                ),
                const SizedBox(width: 10),
                OutlinedButton.icon(
                  onPressed: controller.stopPlayback,
                  icon: const Icon(Icons.stop_rounded),
                  label: const Text('Stop audio'),
                ),
                const Spacer(),
                Text(controller.voiceEnabled ? 'Enabled' : 'Muted', style: const TextStyle(color: _muted)),
              ],
            ),
            if (controller.playbackError case final error?) ...[
              const SizedBox(height: 8),
              Text(error, style: const TextStyle(color: Color(0xFFB42318))),
            ],
            if (controller.mockMode)
              const Padding(
                padding: EdgeInsets.only(top: 10),
                child: Text('MOCK: voice is synthesized on-device, not by an AI provider.', style: TextStyle(fontSize: 12, color: _muted)),
              ),
          ],
        ),
      );

  Widget _footer() => const Center(
        child: Text(
          'MIC capture only  •  No MEDIA translation  •  Local room status is independent of AI',
          textAlign: TextAlign.center,
          style: TextStyle(color: _muted, fontSize: 11),
        ),
      );

  void _showSettings(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => AnimatedBuilder(
        animation: controller,
        builder: (context, child) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 30),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Translation settings', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 22)),
                const SizedBox(height: 12),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Translated voice output'),
                  subtitle: const Text('Enable speaking or playing translated segments'),
                  value: controller.voiceEnabled,
                  onChanged: controller.setVoiceEnabled,
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Speak automatically'),
                  subtitle: const Text('Avoid enabling near the microphone to reduce feedback'),
                  value: controller.autoSpeak,
                  onChanged: controller.voiceEnabled ? controller.setAutoSpeak : null,
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Show live captions'),
                  value: controller.captionsEnabled,
                  onChanged: controller.setCaptionsEnabled,
                ),
                const Divider(),
                const ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.mic_none_rounded),
                  title: Text('Audio source: MIC (locked)'),
                  subtitle: Text('Media playback is never sent to translation'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

const _eyebrow = TextStyle(
  fontSize: 10,
  fontWeight: FontWeight.w800,
  color: _muted,
  letterSpacing: 1.3,
);

class _Pill extends StatelessWidget {
  const _Pill({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: const Color(0xFFEDEAFF),
          borderRadius: BorderRadius.circular(30),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: _violet, size: 13),
            const SizedBox(width: 5),
            Text(text, style: const TextStyle(fontWeight: FontWeight.w800, color: _violet, fontSize: 9)),
          ],
        ),
      );
}

class _Panel extends StatelessWidget {
  const _Panel({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: const Color(0xFFE9EDF4)),
        ),
        child: child,
      );
}

class _Empty extends StatelessWidget {
  const _Empty(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 12),
          child: Center(
            child: Column(
              children: [
                const Icon(Icons.subtitles_off_outlined, color: _muted, size: 37),
                const SizedBox(height: 12),
                Text(text, textAlign: TextAlign.center, style: const TextStyle(color: _muted, height: 1.6)),
              ],
            ),
          ),
        ),
      );
}
