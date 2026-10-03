# Scripture Memory for Android

The Android version of Scripture Memory, written in Kotlin with Jetpack Compose. It has the same features as the Windows app:

- **Collections** of passages, added by book/chapter/verses or by typing any reference (`Romans 8:28-39`, `John 3:16-4:2`).
- **ESV text** from the [ESV API](https://api.esv.org/) (set your key under ⋮ › ESV API key; it's stored encrypted with the Android Keystore).
- **Practice views**: Show all, First letters, Blur. Tap a hidden word to peek; Reset hides everything again.
- **Speak**: recite out loud and words are revealed as you say them, using the same forgiving matcher as the Windows app (dropped small words, near-misses, restarts).
- **Calibrate voice**: read a passage aloud with the text showing so words the recognizer finds hard to hear in your voice get extra leeway.
- **Progress**: completion counts and a "mastered" flag per passage.

## Moving your data from Windows

The Android app reads and writes the same `data.json` format as the Windows app.

1. Copy `%LOCALAPPDATA%\ScriptureMemory\data.json` from your PC to your phone (Drive, email, USB…).
2. In the app, open ⋮ › **Import data…** and pick the file. Collections and passages are merged in; nothing is overwritten.
3. Set your ESV API key again (the Windows copy is encrypted for your Windows account and can't be read on the phone).

**Export data…** writes a file the Windows app can load: put it at `%LOCALAPPDATA%\ScriptureMemory\data.json` while the app is closed.

## Speech recognition

Speak uses Android's built-in speech recognizer (usually Google's). Android hears one utterance at a time, so the app restarts listening after each pause; on some phones that plays a short tone. Recognition may need an internet connection unless an offline English speech pack is installed (Settings › System › Languages › Speech or the Google app's offline speech settings). On Android 13+ the recognizer is biased toward the passage's upcoming words.

## Build

Requires JDK 17+ and the Android SDK (Android Studio installs both). Open the `android` folder in Android Studio, or:

```
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # tokenizer, matcher, practice logic, data.json format
```

Every push that touches `android/` also builds the debug APK in GitHub Actions; download it from the run's **Artifacts**.
