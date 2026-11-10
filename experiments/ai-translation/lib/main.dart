import 'package:flutter/material.dart';

import 'translation/gateway_provider.dart';
import 'translation/microphone_input.dart';
import 'translation/session_controller.dart';
import 'translation/translation_provider.dart';
import 'translation/voice_output.dart';
import 'ui/translation_home.dart';

const providerChoice = String.fromEnvironment(
  'BT_AI_PROVIDER',
  defaultValue: 'mock',
);
const gatewayUrl = String.fromEnvironment('BT_AI_WS_URL');
const devSessionToken = String.fromEnvironment('BT_AI_SESSION_TOKEN');

TranslationProvider createTranslationProvider() {
  if (providerChoice == 'mock') return MockTranslationProvider();
  if (providerChoice == 'gateway') {
    if (gatewayUrl.isEmpty) {
      throw const TranslationProviderFailure(
        'BT_AI_WS_URL is required for gateway mode.',
      );
    }
    return GatewayTranslationProvider(
      endpoint: Uri.parse(gatewayUrl),
      ephemeralSessionToken: devSessionToken,
    );
  }
  throw const TranslationProviderFailure('Unknown AI provider configuration.');
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(BtShareTranslationApp(
    controller: TranslationSessionController(
      microphone: RecordMicrophoneInput(),
      providerFactory: createTranslationProvider,
      voice: DeviceTranslatedVoiceOutput(),
    ),
  ));
}

class BtShareTranslationApp extends StatefulWidget {
  const BtShareTranslationApp({super.key, required this.controller});

  final TranslationSessionController controller;

  @override
  State<BtShareTranslationApp> createState() => _BtShareTranslationAppState();
}

class _BtShareTranslationAppState extends State<BtShareTranslationApp> {
  @override
  void dispose() {
    widget.controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const ink = Color(0xFF121B2A);
    const violet = Color(0xFF6455E8);
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'BT SHARE · Live Translation',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: violet,
          surface: const Color(0xFFF6F7FB),
        ),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF6F7FB),
        textTheme: Theme.of(context).textTheme.apply(
              bodyColor: ink,
              displayColor: ink,
            ),
        cardTheme: const CardThemeData(
          color: Colors.white,
          elevation: 0,
          margin: EdgeInsets.zero,
        ),
      ),
      home: TranslationHome(controller: widget.controller),
    );
  }
}
