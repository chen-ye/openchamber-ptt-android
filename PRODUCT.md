# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Primary users:** Away-from-desk developers (power users) steering OpenChamber and OpenCode coding sessions hands-free and screen-off over a Bluetooth headset, or via quick glances at their phone or floating Bubble overlay.

## Product Purpose

OpenChamber PTT provides an explicit Push-to-Talk (PTT) Android voice companion for OpenChamber and OpenCode coding sessions without always-on hotword detection. Success is defined by three outcomes:

1. **Eyes-free safety & trust:** Zero accidental sends (via the 2.5-second undoable `SEND_GRACE` window, durable staged drafts, and the 5-minute capture ceiling), unmistakable earcon feedback, and reliable screen-off Bluetooth headset gesture handling.
2. **Glanceable quick review:** Fast state readouts, readable latest reply alongside the collapsible spoken readout, and one-tap handoff to the floating Bubble or official OpenChamber app (`com.openchamber.app`).
3. **Focused cockpit surface:** Keep the primary surface centered on the active session, PTT state/control, audio/voice toggles, active draft, and latest turn, while tucking deeper configuration and telemetry into bottom sheets.

## Positioning

Unlike always-on voice assistants or full mobile coding clients, OpenChamber PTT is a dedicated, eyes-free Push-to-Talk voice companion built natively around OpenChamber/OpenCode agent turns—pairing lock-screen Bluetooth headset tap cadences, undoable send grace, durable per-session smart-merged voice drafts, multi-engine STT/TTS fallback pipelines, and optional Voice Control Plane routing (`gemini-decisions-bridge`).

## Operating Context

- **Primary surfaces & lifecycle:** `MainActivity` (setup flow + single/two-pane responsive voice cockpit), `BubbleActivity` (floating Android Bubble multitasking cockpit), ongoing foreground notification (`PttNotificationBuilder`), and `PttForegroundService` (`mediaPlayback | microphone` when armed; `mediaPlayback` when promoted for standalone **Read aloud**).
- **Hands-free headset workflow:** `MediaButtonGestureDetector` maps single/double/triple taps and AVRCP `Next`/`Previous`/`Stop` buttons to talk, replay latest reply, stage/organize/readback draft, discard, undo send grace, or barge-in interrupt TTS. `Headset mic` toggles between A2DP media (`USAGE_MEDIA` / `VOICE_RECOGNITION`) and pinned Bluetooth HFP/SCO (`USAGE_VOICE_COMMUNICATION` / `VOICE_COMMUNICATION`).
- **Quick turns & durable staged drafts:** Single-tap stop enters `SEND_GRACE` (`2.5s`) before sending a `[voice]`-prefixed prompt; re-tapping pulls the utterance into a per-session `StagedDraft` persisted in `TokenStore` across restarts, disarming, and session switches.
- **Glanceable review & permission handoff:** `TurnTranscriptPanel` displays the stripped latest prompt, Markdown-rendered reply, collapsible spoken summary/readout, TTS engine info, and **Open in OpenChamber**. Tool approvals wait `4000ms` (`PERMISSION_ALERT_DELAY_MS`) before announcing and posting a high-priority notification so server-side auto-approvers can resolve safe calls silently.
- **Multi-host & extension ecosystem:** Supports multiple saved OpenChamber servers (`TokenStore`) with Tailscale/local dev HTTP & user CA trust, per-server `401` isolation, cross-server session filtering, shared global defaults with optional per-server voice overrides, and integration with `gemini-tts-bridge` and `gemini-decisions-bridge` server extensions.

## Capabilities and Constraints

- **Confirmed capabilities:**
  - Explicit PTT audio capture with `300_000ms` safety ceiling (`285_000ms` warning earcon) that auto-stages unfinished captures rather than auto-sending.
  - Configurable STT (`OpenChamber Parakeet` WebSocket + small-model cleanup, `ML Kit GenAI` Advanced/Basic with optional Parakeet fallback).
  - Ordered, toggleable TTS fallback pipeline (`Gemini Bridge`, `Local Kokoro`, `ML Kit GenAI`, `Android System TTS`, `OpenAI Cloud`) with a unified multi-provider `Voices` sheet.
  - Speech Input Modes (`Spoken` written-to-spoken translation at `Essentials`, `Balanced`, or `Detailed` levels; `Sanitized`; `Raw`), with staged draft readback forcing `Raw`.
  - Voice Control Plane (`gemini-decision-1`) classifying quick turns into `PASS_THROUGH`, `LIVE_TUNING`, or `ANSWER_DIRECTLY`, failing open to `PASS_THROUGH`.
  - Local JSONL Voice UX telemetry (`filesDir/voice_ux_log.jsonl`) viewable and exportable from Settings.
- **Durable technical & engineering constraints:**
  - Native Android (`minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`), Jetpack Compose + Material 3, MIT License.
  - Keep APK size lean: avoid `material-icons-extended` and use minimal vector drawables in `res/drawable/`.
  - Retain `network_security_config.xml` cleartext HTTP permission and `system` + `user` CA trust for Tailscale and local dev endpoints.
  - Preserve ML Kit GenAI AAR ABI workarounds (`SpeechSynthesizerOptions.builder(locale)...build()`, explicit `SpeechSynthesizerRequest`, `Locale.US` normalization, full unavailability diagnostics) and `MediaSessionCompat` + `EarconPlayer` `AudioTrack` (`USAGE_MEDIA`, `MODE_STATIC`) lock-screen media button ownership.
  - Keep the primary cockpit focused and push configuration, multi-server editing, voice browsing, and telemetry into `ModalBottomSheet`s (`skipPartiallyExpanded = true`).

## Brand Commitments

- **Product name & identity:** **OpenChamber PTT** (`com.openchamber.ptt`; app bar displays `OpenChamber`, bubble displays `OpenChamber Voice`).
- **Companion pairing:** Designed alongside the official OpenChamber Android client (`com.openchamber.app`).
- **Audio identity:** Uses bundled `romainsimon/uisfx` `studio` earcon cues (`CC0-1.0`) across 13 distinct operational states (`armed`, `rec_start`, `staged_pause`, `send_grace`, `rec_sent`, `turn_complete`, `turn_interrupted`, `cancel_discard`, `undo`, `permission_needed`, `auth_expired`, `error`, `disarmed`).
- **Visual scope:** Follow standard Android native Material 3 expectations and existing repository conventions; no extra brand style constraints were added.

## Evidence on Hand

- **Codebase & tests:** Full Android Kotlin/Compose implementation under `app/src/main/java/com/openchamber/ptt/`, unit test suite under `app/src/test/java/com/openchamber/ptt/`, and spoken-translation evaluation corpus (`app/src/test/resources/spoken_corpus/corpus.json`, `scripts/eval-spoken-corpus.mjs`).
- **Bundled assets:** 13 `studio` OGG earcons (`app/src/main/res/raw/earcon_*.ogg`) and minimal XML vector icons (`app/src/main/res/drawable/ic_*.xml`).
- **Project documentation & CI:** `README.md`, `AGENTS.md`, `LICENSE`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`.
- **Verification boundaries & absences:** No marketing copy, user testimonials, or external usability studies exist in the repo. `openchamber_api35` emulator lacks `com.google.android.tts` (`GoogleAsrService`) and `com.google.android.aicore`, so ML Kit GenAI STT/TTS requires physical Pixel 8+ or Galaxy S25+ hardware with a locked bootloader for end-to-end verification.

## Product Principles

1. **Explicit intent over ambient capture:** Recording, sending, staging, and barge-in happen only on deliberate triggers, backed by `SEND_GRACE` and durable drafts so nothing fires unintentionally.
2. **Eyes-free confidence first:** Lock-screen Bluetooth media-button ownership, distinct earcons, foreground playback promotion, and spoken prose translation keep away-from-desk sessions trustworthy without looking at the screen.
3. **Glanceable, focused cockpit:** When the user looks at the app or Bubble, surface session, PTT state, active draft, and latest turn immediately while relegating tuning and logs to sheets.
4. **Fail open across every external dependency:** Fall back cleanly across STT, TTS, small-model rewriting, and Voice Control Plane bridges, and isolate auth failures per server.
5. **Respect Android platform discipline:** Follow Material 3 idioms, `>=48dp` targets, predictive back, edge-to-edge insets, and lean APK packaging.

## Accessibility & Inclusion

- Standard Android native accessibility commitments preserved from the codebase and `AGENTS.md`: minimum `48dp` interactive touch targets (including wrapping `FlowRow` action bars), `ContentType.Password` + `LocalAutofillManager` on credential inputs, `LiveRegionMode.Polite` and `stateDescription` semantics for TalkBack, `skipPartiallyExpanded = true` on multi-section `ModalBottomSheet`s, and reduced-motion compliance via `Settings.Global.ANIMATOR_DURATION_SCALE` (`shouldAnimateMotion`).
