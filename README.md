# OpenChamber PTT (Android)

An Android Push-to-Talk (PTT) voice companion for OpenChamber and OpenCode. Talk to coding sessions from a Bluetooth headset or floating Bubble, even with the screen locked, without always-on hotword detection.

## Design goals and principles

- **Explicit PTT over open-mic.** Audio capture starts and stops only on an explicit tap from the screen button, notification action, or Bluetooth headset play/pause button, capped at 90 seconds per turn.
- **Background and lock-screen audio.** Armed PTT and standalone **Read aloud** run inside a foreground service so switching apps or locking the phone does not cut off responses.
- **Multiple servers with shared defaults.** Save multiple OpenChamber hosts, view all sessions in one switcher, and switch servers in one tap. Voice and STT settings use shared global defaults, with optional per-server overrides when a host differs.
- **Configurable TTS and STT fallbacks.** Set a custom TTS order across Gemini Bridge, Local Kokoro, ML Kit GenAI, Android System TTS, and OpenAI Cloud, and fall back from ML Kit STT to host Parakeet so voice turns work online or offline.
- **Delayed permission alerts.** Tool-approval speech and alerts wait 4 seconds before interrupting, giving server-side auto-approvers time to resolve safe calls silently.

## Features

- **Screen-off Bluetooth headset PTT.** Tap play/pause to start recording, tap again to send, or tap during playback to interrupt TTS.
- **Multi-server session switcher.** Browse sessions across all signed-in servers at once, filter by host, sign back into expired servers individually, and open the matching session in the official OpenChamber app (`com.openchamber.app`).
- **Unified `Voices` picker.** Configure voices per TTS provider in one sheet, including a searchable Gemini Bridge catalog, downloadable on-device ML Kit GenAI voices, OpenAI presets, Android System TTS voices, and host-default Kokoro.
- **Android Bubbles.** Open a floating Bubble overlay for PTT controls while using other apps.
- **`Headset mic` toggle.** Use A2DP media playback by default, or pin Bluetooth HFP/SCO for headset microphone capture.

## Prerequisites

- JDK 21 (for example, `/home/linuxbrew/.linuxbrew/opt/openjdk@21`)
- Android SDK (`ANDROID_HOME=$HOME/Android/Sdk`, `compileSdk = 35`, `minSdk = 29`)
- `adb` (Android Platform Tools)

## Build and test

```bash
JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew testDebugUnitTest --rerun-tasks assembleDebug --console=plain
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install via ADB

Connect over wireless ADB using the IP and pairing or debugging port shown in Android Developer Options, then install the APK:

```bash
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Quick start

1. Open **OpenChamber PTT** and grant `Microphone`, `Bluetooth Connect` on Android 12+, and `Notifications` on Android 13+. Optionally enable **Floating Bubbles** and **Unrestricted battery** in **Settings**.
2. Enter your OpenChamber server URL and password, then tap **Connect**. Add extra servers anytime from **Settings**, then **Servers**, or from **`+ Server`** in the session picker.
3. Tap the session bar to pick a session on any saved server.
4. Tap the voice chip, such as `Gemini · Kore`, to pick voices across providers, or open **Settings** to change STT engines, TTS fallback order, or per-server voice overrides.
5. Toggle **Headset mic** if you want Bluetooth SCO mic routing, tap **Arm PTT**, and use your headset media button or the on-screen PTT button to talk.
6. Use **Read aloud** or **Stop** on the **Latest reply** card to replay or interrupt the most recent assistant message, even after locking the screen.
