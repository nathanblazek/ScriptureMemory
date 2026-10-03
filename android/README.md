# Scripture Memory for Android

The Android version of Scripture Memory, written in Kotlin with Jetpack Compose. It has the same features as the Windows app:

- **Collections** of passages, added by book/chapter/verses or by typing any reference (`Romans 8:28-39`, `John 3:16-4:2`).
- **ESV text** from the [ESV API](https://api.esv.org/) (set your key under ⋮ › ESV API key; it's stored encrypted with the Android Keystore).
- **Practice views**: Show all, First letters, Blur. Tap a hidden word to peek; Reset hides everything again.
- **Speak**: recite out loud and words are revealed as you say them, using the same forgiving matcher as the Windows app (dropped small words, near-misses, restarts).
- **Calibrate voice**: read a passage aloud with the text showing so words the recognizer finds hard to hear in your voice get extra leeway.
- **Progress**: completion counts and a "mastered" flag per passage.
- **Android Auto**: hands-free practice while driving (see below).

## Android Auto

Practice hands-free while driving. On the car screen, pick a collection and a passage. Each verse is read aloud, then it's your turn to recite it back. The screen shows only which verse you're on. A skipped or wrong word plays a blip and shows that word large for a few seconds, and you carry on from the next word. **Repeat** reads the verse again and **Next verse** skips ahead.

Android Auto only shows car apps in a real car when they're installed from the Play Store (even with developer mode and unknown sources on, a sideloaded APK stays hidden). Until the app is on a Play testing track, try the driving mode with Google's Desktop Head Unit (DHU), a car-screen simulator that runs on your PC.

**One-time setup (Windows)**

1. Install [Android Studio](https://developer.android.com/studio). Open **Tools › SDK Manager › SDK Tools**, tick **Android Auto Desktop Head Unit Emulator** and click **Apply**. It installs to `%LOCALAPPDATA%\Android\Sdk\extras\google\auto\`.
2. On the phone, open Android Auto's settings (Settings › Connected devices › Connection preferences › Android Auto), scroll to the bottom and tap **Version** about ten times to unlock developer settings. In the ⋮ menu, open **Developer settings** and turn on **Unknown sources**.

**Each time**

1. Plug the phone into the PC with USB debugging on.
2. On the phone, in Android Auto's ⋮ menu, tap **Start head unit server**.
3. In PowerShell, from a `platform-tools` folder: `.\adb forward tcp:5277 tcp:5277`
4. Then: `cd "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto"` and `.\desktop-head-unit.exe`

A car screen opens on the PC. Scripture Memory is in its app launcher. Speech uses the PC's microphone and the PC speakers.

The microphone permission has to be granted on the phone (the first time you press Speak in the app). On Android 13+ the car's microphone is used where Android Auto supports it; otherwise the phone's.

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

Every push that touches `android/` also builds the debug APK in GitHub Actions and publishes it as the `android-latest` pre-release, so the newest build is always at <https://github.com/nathanblazek/ScriptureMemory/releases/download/android-latest/ScriptureMemory.apk>.
