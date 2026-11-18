# OpenChamber PTT (Android)

An Android push-to-talk voice companion for [OpenChamber](https://github.com/openchamber/openchamber) and OpenCode. Talk to coding sessions from a Bluetooth headset, lock screen, or floating Android Bubble—without always-on hotword listening.

## Install

### Download a signed APK (recommended)

Grab the latest APK directly from [GitHub Releases](https://github.com/chen-ye/openchamber-ptt-android/releases) on your Android device (Android 10+ / API 29+):

- **Tagged releases (`v*`):** Tested milestone builds (`openchamber-ptt-<version>.apk`).
- **Rolling prerelease (`prerelease`):** Latest signed build from `main` (`openchamber-ptt-prerelease.apk`).

Both release channels use the same signing key, so you can update directly in place without uninstalling.

### Or install over ADB

If you already have an APK locally and ADB connected over USB or wireless debugging:

```bash
adb connect <device-ip>:<port>
adb install -r path/to/openchamber-ptt.apk
```

*(CI debug artifacts use an ephemeral runner keystore, so switching between a local debug build, a CI debug artifact, and a signed release APK requires uninstalling the earlier package first.)*

---

## Quick start

1. **Grant permissions:** Open **OpenChamber PTT** and tap **Grant Permissions** (`Microphone`, `Nearby devices` on Android 12+, and `Notifications` on Android 13+). Optionally tap **Battery Exempt** so Android does not suspend lock-screen headset audio.
2. **Connect a server:** Enter your OpenChamber **Server URL** (Tailscale `http://` or `https://` works) and password, then tap **Connect**. You can add more hosts later from **Settings** or **`+ Server`** in the session picker.
3. **Choose a session:** Tap the session bar at the top to open **Select Session**, search by session name or project path, or filter to **Unread** sessions.
4. **Arm and talk:** Tap **Arm PTT**, then tap the central mic orb—or your Bluetooth headset play/pause button—to start and stop speaking.
5. **Listen or open on screen:** Replies read aloud automatically while armed. Use **Read aloud** / **Stop** on **Latest reply** anytime, or tap **Open in OpenChamber** to jump straight into the session in the OpenChamber app.

---

## Everyday workflows

### Bluetooth headset & lock-screen controls

Once armed, the service keeps media-button focus active so you can run turns with the screen off:

| Current state | `1x` tap | `2x` tap or `Next` | `3x` tap or `Previous` |
|---|---|---|---|
| **Armed & idle** | Start recording | Replay latest reply | — |
| **Recording** | Stop into 2.5s send grace | Stage, organize & read back draft | Discard recording |
| **Send grace (2.5s)** | Undo send into staged draft | — | — |
| **Staged draft / preview** | Interrupt preview or append voice | Send draft to agent | Discard draft |
| **Speaking reply** | Stop speech immediately | — | — |

Distinct sound cues (`studio` earcons, `CC0-1.0`) let you tell apart arming, recording start, staged pause, send grace, sent, turn complete, barge-in, discard, permission needed, and expired login without looking at your phone.

### Quick turns vs. staged drafts

- **Quick turns (`1x` tap):** Stopping enters a 2.5-second **Tap to Undo** grace window before sending. Tap the orb or headset button again during that window to pull the transcript into an editable draft instead of sending.
- **Staged drafts (`2x` tap / `Next`):** Useful for longer, multi-part thoughts. Your recording is cleaned up, smart-merged into any existing draft, and read back aloud. You can append more voice bursts, tweak the text in **Prompt draft**, tap **Re-organize**, or discard.
- **Safety cap:** Recordings longer than 5 minutes play a warning cue at 4:45 and automatically stage as a draft at 5:00 rather than sending unreviewed text.
- **Per-session persistence:** Drafts stay saved per server and session across app restarts, **Disarm**, and session switches until sent or discarded.

### Sessions, unread indicators & multi-server

- **Cross-host switcher:** View sessions across every signed-in OpenChamber server in one sheet, narrow with server filter chips, and re-authenticate expired hosts individually without losing other connections.
- **Hierarchical subagents:** Subagent sessions appear nested and collapsed under their parent session by default; tap a parent row's right-side counter to expand or collapse its subagents.
- **Search by name or path:** Filter across session titles, IDs, and project directories—parents automatically expand when a nested subagent matches your query.
- **Read-state preservation:** Top-level sessions with unseen replies (including failed unseen turns) display an unread dot and work with the **Unread** filter. Selecting or listening to a session here **does not** mark it read in OpenChamber desktop or web.

### Floating Bubble & headset mic routing

- **Floating Bubble:** Enable Bubbles via **Settings** → **Bubble Settings** to keep a compact PTT overlay floating over other apps.
- **`Headset mic` toggle:** Leaves playback on high-quality A2DP media audio by default (using the phone mic). Toggle **Headset mic** on when you want Bluetooth HFP/SCO to capture through your headset microphone instead.

---

## Voice, speech & server extensions

Tap the voice chip (for example `Gemini · Kore`) for quick voice selection, or open **Settings** to customize the full pipeline globally or per server:

- **Speech Input Mode:**
  - **Spoken:** Rewrites technical markdown, code, and file paths into natural spoken prose at **Essentials**, **Balanced**, or **Detailed** depth, with a collapsible **Spoken summary** readout under the reply.
  - **Sanitized:** Strips markdown, code blocks, and URLs.
  - **Raw:** Speaks the exact reply text (also used automatically for staged draft readback).
- **Text-to-Speech Order:** Toggle and reorder **Gemini Bridge**, **Local Kokoro**, **ML Kit GenAI TTS**, **Android System TTS**, and **OpenAI Cloud**—the app automatically falls back down your enabled list.
- **Speech-to-Text:** Choose **OpenChamber Parakeet** (host transcription + cleanup) or on-device **ML Kit GenAI** (Advanced or Basic) with optional automatic fallback to Parakeet.
- **Tool permission alerts:** When an agent waits on a tool approval, alerts hold for 4 seconds before announcing so server-side auto-approvers can handle routine approvals silently.

### Optional server extensions & device requirements

- **Core OpenChamber server:** Session switching, Parakeet STT, Kokoro/OpenAI TTS, `/api/small-model/generate` spoken rewriting/draft cleanup, and turn events work against a standard OpenChamber server.
- **`gemini-tts-bridge`:** Required only for the **Gemini Bridge** TTS voice catalog and synthesis.
- **`gemini-decisions-bridge`:** Optional quick-turn router (`gemini-decision-1`) that lets short voice commands adjust spoken detail/mode live or ask quick follow-ups about the latest reply without disturbing the main session (fails open to normal prompting when absent).
- **On-device ML Kit GenAI (STT/TTS):** Requires physical Android hardware with AICore and Google Speech Services support (such as Pixel 8+ or Galaxy S25+ with a locked bootloader); unsupported devices and emulators report `Unavailable` in Settings diagnostics.

---

## Troubleshooting

- **Headset button stops responding with screen off:** Open **Settings** → **Battery** (or tap **Battery Exempt** during initial setup) to exempt OpenChamber PTT from background battery restrictions, and make sure the app is **Armed** (not Disarmed).
- **Audio sounds muffled when armed:** Turn **Headset mic** off to return Bluetooth output to A2DP media audio and use the phone microphone.
- **ML Kit GenAI shows `Unavailable`:** Use **OpenChamber Parakeet** for STT and **Gemini Bridge**, **Local Kokoro**, **Android System TTS**, or **OpenAI Cloud** for TTS—or tap **Copy ML Kit diagnostics** in **Settings** to inspect device service availability.
- **Telemetry export:** Open **Settings** → **View Voice UX Telemetry Log** to inspect or copy local timing and UX logs (`JSONL`) for debugging latency or headset tap detection.

---

## Building from source & CI

### Prerequisites

- JDK 21 (for example, `/home/linuxbrew/.linuxbrew/opt/openjdk@21`)
- Android SDK (`compileSdk = 35`, `minSdk = 29`) and `adb`

### Build and test

```bash
JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew testDebugUnitTest --rerun-tasks assembleDebug --console=plain
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

For offline Android emulator UI QA (hierarchical sessions, unread/failed indicators, search, and multi-server chips), run the local mock OpenChamber server and connect the emulator to `http://10.0.2.2:18401` (and `http://10.0.2.2:18402`) with any non-empty password:

```bash
bun scripts/mock-openchamber-server.mjs --help
```

### CI and releases

- [CI workflow](.github/workflows/ci.yml) runs unit tests, `lintDebug`, and `assembleDebug` on every pull request and push to `main`.
- [Release workflow](.github/workflows/release.yml) builds and verifies a signed release APK on every `push` to `main` (updating the rolling `prerelease` tag and `openchamber-ptt-prerelease.apk`) and on `v*` tag pushes or manual `workflow_dispatch` runs (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

Contributors and coding agents should also read [engineering invariants](AGENTS.md) and [design system tokens](DESIGN.md) before modifying lifecycle, audio, protocol, or UI code.
