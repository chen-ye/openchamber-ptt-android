# OpenChamber PTT (Android)

An Android Push-to-Talk (PTT) voice companion for OpenChamber and OpenCode. Talk to coding sessions from a Bluetooth headset or floating Bubble, even with the screen locked, without always-on hotword detection.

## Design goals and principles

- **Explicit PTT over open-mic.** Audio capture starts and stops only on an explicit tap from the screen button, notification action, or Bluetooth headset media button, with a 5-minute safety ceiling that stages an unfinished capture as a draft instead of auto-sending.
- **Quick turns + durable staged drafts.** Single-tap stop enters a 2.5-second undoable send grace window (`SEND_GRACE`); double-tap (or `Next`) pauses to clean, smart-merge, and read back a durable per-session draft that survives restarts and session switches.
- **Background and lock-screen audio.** Armed PTT and standalone **Read aloud** run inside a foreground service so switching apps or locking the phone does not cut off responses.
- **Multiple servers with shared defaults.** Save multiple OpenChamber hosts, view all sessions in one switcher, and switch servers in one tap. Voice, STT, and Speech Input Mode settings use shared global defaults, with optional per-server overrides when a host differs.
- **Configurable TTS and STT fallbacks.** Set a custom TTS order across Gemini Bridge, Local Kokoro, ML Kit GenAI, Android System TTS, and OpenAI Cloud, choose `Spoken` (written-to-spoken translation at `Essentials`, `Balanced`, or `Detailed` abstraction level), `Sanitized`, or `Raw` speech output, and fall back from ML Kit STT to host Parakeet so voice turns work online or offline.
- **Voice Control Plane routing (`gemini-decisions-bridge`).** Quick turns can be routed by `gemini-decision-1` (via the `gemini-decisions-bridge` OpenChamber extension) to apply live voice tuning (`Spoken` / `Sanitized` / `Raw` and `Essentials` / `Balanced` / `Detailed`) or answer follow-ups directly from the latest reply without disturbing the coding session, failing open to a normal prompt when unavailable.
- **Delayed permission alerts.** Tool-approval speech and alerts wait 4 seconds before interrupting, giving server-side auto-approvers time to resolve safe calls silently.
- **Distinct `studio` earcons & telemetry.** Bundled `romainsimon/uisfx` `studio` cues (`CC0-1.0`) distinguish armed, recording, staged pause, send grace, sent, turn complete, interrupted, undo/discard, permission needed, and auth expiry, paired with a local JSONL Voice UX telemetry log in **Settings**.

## Features

- **Screen-off Bluetooth headset PTT.**
  - **Idle:** `1x` tap starts recording; `2x` tap / `Next` replays the latest reply.
  - **Recording:** `1x` tap stops into a 2.5s undoable send grace; `2x` tap / `Next` stages, organizes, and reads back a draft; `3x` tap / `Previous` discards the burst.
  - **Staged draft:** `1x` tap interrupts readback or appends another spoken burst (smart-merged with the existing draft); `2x` tap / `Next` sends; `3x` tap / `Previous` discards.
  - **Speaking reply:** `1x` tap interrupts TTS immediately.
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

## CI and releases

- **CI (`.github/workflows/ci.yml`)** runs `./gradlew testDebugUnitTest lintDebug assembleDebug` on every pull request and push to `main`, uploading `app-debug.apk` and test/lint reports as workflow artifacts.
- **Release APK (`.github/workflows/release.yml`)** builds and signs `app-release.apk`, verifies its signature with `apksigner`, and publishes GitHub Releases:
  - **Rolling prerelease (`push` to `main`, or default `workflow_dispatch`)**: updates the `prerelease` tag and GitHub Pre-release with `openchamber-ptt-prerelease.apk` + `.sha256` (`versionName` `<base>-dev.<commits>+<sha>` and monotonic `versionCode` so upgrades install cleanly across `v0.1.0` → `prerelease` → `v0.2.0`).
  - **Tagged release (`v*` tag push, or `workflow_dispatch` with a `v*` tag)**: publishes `openchamber-ptt-<version>.apk` + `.sha256` to a matching GitHub Release.

To enable signed releases in GitHub Actions, generate a signing keystore locally (if you don't already have one) and set four repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`):

```bash
keytool -genkeypair -v -keystore release.jks -alias openchamber-ptt \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.jks   # paste into KEYSTORE_BASE64 secret
```

*(CI debug APKs use an ephemeral runner debug keystore, so installing a CI `app-debug.apk` over a locally built debug APK requires uninstalling first or using the consistently signed release APK.)*

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
