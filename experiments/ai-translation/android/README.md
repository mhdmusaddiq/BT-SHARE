# Android scaffold handoff

Flutter source controlled in this folder currently contains an Android manifest expressing required microphone permissions. Generate the remaining standard Android Gradle/Kotlin resources using the installed Flutter stable SDK:

```
flutter create --platforms=android --org com.btshare --project-name bt_share_ai_translation_poc .
```

Run from `experiments/ai-translation/`. Check that the resulting Android manifest preserves runtime `RECORD_AUDIO`, `INTERNET`, TTS queries and the `MainActivity` package/namespace. No background recording / foreground service is claimed. Recheck platform toolchain versions with `flutter doctor -v`. Review generated files rather than committing blindly.
