# OpenChamber PTT agent and engineering invariants

See `README.md` for the overview, prerequisites, build commands, and quick start. This file records non-obvious runtime, protocol, ABI, and verification invariants for contributors and agents.

## 1. Service lifecycle and audio (`PttForegroundService`, `BluetoothAudioRouter`)
- **Promote standalone TTS to foreground.** Never run active PTT or **Read aloud** (`readPreviousMessage`) solely under an Activity `bindService` binding. When starting `Read aloud` from `DISARMED`, set `promotedForStandaloneTts = true`, call `ContextCompat.startForegroundService(..., ACTION_ENSURE_MEDIA_FOREGROUND)` and `startForegroundWithState(SPEAKING_TTS, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`, and tear down via `stopForegroundAndSelf()` on `TtsFinished`, `Stop`, or `Error`. Armed PTT uses `mediaPlayback | microphone`.
- **Media button ownership on Android 14+.** Keep `MediaSessionCompat` active and play short `AudioTrack` (`USAGE_MEDIA`) earcons via `EarconPlayer` so Android routes headset `ACTION_MEDIA_BUTTON` events to `OpenChamberPttMediaSession` while screen-locked.
- **Audio usage coupling.** `pinBluetoothSco == false` uses `USAGE_MEDIA` and `VOICE_RECOGNITION`. `pinBluetoothSco == true` pins SCO and switches playback and capture to `USAGE_VOICE_COMMUNICATION` and `VOICE_COMMUNICATION`.

## 2. Protocol and timing contracts
- **WebSocket upgrades.** `OpenChamberHttp` must attach a canonical same-host `Origin` header (`canonicalOrigin`) on `WS /api/dictation/ws` and `WS /api/global/event/ws`.
- **Dictation handshake.** `DictationClient` must wait for the initial server `ack` (`ackSeq: -1`) before streaming PCM chunks.
- **Voice tag contract.** `PttForegroundService` prepends `[voice]` (`VOICE_PROMPT_PREFIX`) to outgoing PTT prompts, and `SessionClient.fetchLatestTurn` strips it before populating UI `lastTranscript`.
- **Permission alert grace period.** Keep `PERMISSION_ALERT_DELAY_MS = 4000L` before announcing or notifying tool approvals, and cancel pending alerts immediately on `TurnEvent.PermissionResolved`.

## 3. Multi-server storage and auth (`TokenStore`, `OpenChamberHttp`, `SessionRepository`)
- **Origin-scoped tokens.** `OpenChamberHttp.createClient` resolves bearer tokens per request URL (`tokenForUrlProvider`) so concurrent multi-server fetches (`SessionRepository.listAllServerSessions`) never send one server's token to another.
- **Per-server `401` isolation.** HTTP `401` in `MainActivity`, `BubbleActivity`, or `PttForegroundService` clears auth only for the failing `serverId` (`tokenStore.clearAuth(serverId)`). `/auth/session` always passes `trustDevice = true` with a persistent per-install `dedupeKey`.
- **Session switch continuity.** `loadedSessionKey` in `PttForegroundService` must include `server.id` (`${serverId}|${sessionId}|${directory}`). Switching servers or sessions preserves `ARMED_IDLE` and updates the notification, Bubble, and transcript; switching mid-turn (`RECORDING`, `TRANSCRIBING`, `WAITING_AGENT`, `SPEAKING_TTS`) disarms first.

## 4. STT, TTS, and ML Kit GenAI invariants
- **Global vs per-server voice config.** `mlKitTtsVoice` and `systemTtsVoice` stay global. `ttsPipeline`, `geminiVoice`, `openAiVoice`, `sttEngine`, and `sttFallbackToParakeet` resolve through `TokenStore.getEffective*` (per-server `ServerVoiceOverride` when enabled, otherwise global).
- **Provider voice isolation.** Never reuse `gemini-tts-bridge` voice IDs for `OPENAI_CLOUD` (`TtsPlayer` reads `openAiVoiceProvider`). Keep `MLKIT_GENAI` and `ANDROID_SYSTEM` as distinct pipeline entries.
- **External `gemini-tts-bridge` dependency.** `TtsClient.fetchVoices` calls `GET /v1/audio/voices` via `POST /api/guests/gemini-tts-bridge/service/request` (`category=featured`), provided by the `gemini-tts-bridge` OpenChamber extension.
- **ML Kit GenAI AAR `$default` ABI hazard.** `com.google.mlkit:genai-text-to-speech:1.0.0-alpha1` strips Kotlin `$default` synthetic helpers and constructors from its packaged AAR (`speechSynthesizerOptions$default`, `SpeechSynthesizerRequest.<init>(..., DefaultConstructorMarker)`).
  - Always use `SpeechSynthesizerOptions.builder(locale)...build()` and explicit `SpeechSynthesizerRequest(input, audioAttributes, queueMode)`.
  - Never collapse ML Kit `getClient` or `checkStatus` errors into a bare `"Unavailable"` string; include raw status or exception details plus `com.google.android.tts` (`>= 210665810`), `GoogleAsrService`, and `com.google.android.aicore` diagnostics.
  - Normalize English locales (`en*`) to `Locale.US` in `MlKitSttClient`.

## 5. Network, UI, and verification constraints
- **Network security.** `app/src/main/res/xml/network_security_config.xml` intentionally permits cleartext HTTP (`cleartextTrafficPermitted="true"`) and trusts both `system` and `user` CA anchors for Tailscale HTTP and local dev CAs.
- **APK size and Compose rules.** Avoid `material-icons-extended` and use minimal vector drawables in `res/drawable/`. Maintain `>=48dp` touch targets, `ContentType.Password` with `LocalAutofillManager` on password fields, `skipPartiallyExpanded = true` on multi-section `ModalBottomSheet`s, and `FlowRow` for wrapping action button groups.
- **Emulator vs hardware verification.**
  - The `openchamber_api35` emulator lacks `com.google.android.tts` (`GoogleAsrService`) and `com.google.android.aicore`, so ML Kit GenAI STT/TTS always reports `Unavailable` on the emulator and requires physical Pixel 8+ or Galaxy S25+ hardware with a locked bootloader for end-to-end verification.
  - Verify UI changes on `openchamber_api35` in dark and light modes, and verify service foreground promotion via `adb shell dumpsys activity services com.openchamber.ptt`.
