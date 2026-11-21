---
name: OpenChamber PTT
description: A quiet, purely tonal Material Design 3 voice cockpit inspired by Google Pixel Recorder for hands-free OpenCode coding sessions.
colors:
  deep-pine-teal: "#0e6b5e"
  mint-container: "#a2f2e2"
  deep-pine-ink: "#00201b"
  muted-sage: "#4a635e"
  sage-container: "#cce8e1"
  sage-ink: "#06201b"
  steel-slate: "#436278"
  steel-container: "#c8e6ff"
  steel-ink: "#001e2e"
  recording-crimson: "#ba1a1a"
  crimson-container: "#ffdad6"
  crimson-ink: "#410002"
  mineral-mist: "#f5faf8"
  surface-container-lowest: "#ffffff"
  surface-container-low: "#eff5f2"
  surface-container: "#e9efed"
  surface-container-high: "#e3e9e7"
  surface-container-highest: "#dae5e1"
  ink-charcoal: "#171d1b"
  ink-muted: "#3f4946"
  outline-hairline: "#bfc9c5"
  night-pine-bg: "#0e1513"
  night-surface-low: "#171d1b"
  night-surface: "#1b211f"
  night-teal: "#86d6c6"
  night-teal-container: "#005046"
  night-coral: "#ffb4ab"
typography:
  headline:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.33
    letterSpacing: "0em"
  title:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.35
    letterSpacing: "0em"
  body:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.43
    letterSpacing: "0.015em"
  label:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.33
    letterSpacing: "0.04em"
  mono:
    fontFamily: "ui-monospace, Roboto Mono, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.45
    letterSpacing: "0em"
rounded:
  xs: "4px"
  sm: "8px"
  md: "10px"
  lg: "12px"
  xl: "16px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "6px"
  md: "8px"
  base: "10px"
  lg: "12px"
  gutter: "14px"
  xl: "16px"
  xxl: "24px"
components:
  hero-ptt-idle:
    backgroundColor: "{colors.deep-pine-teal}"
    textColor: "{colors.surface-container-lowest}"
    rounded: "{rounded.full}"
    size: "104px"
  hero-ptt-armed:
    backgroundColor: "{colors.mint-container}"
    textColor: "{colors.deep-pine-ink}"
    rounded: "{rounded.full}"
    size: "104px"
  hero-ptt-recording:
    backgroundColor: "{colors.recording-crimson}"
    textColor: "{colors.surface-container-lowest}"
    rounded: "{rounded.full}"
    size: "104px"
  status-pill-armed:
    backgroundColor: "{colors.mint-container}"
    textColor: "{colors.deep-pine-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "6px 12px"
  button-primary:
    backgroundColor: "{colors.deep-pine-teal}"
    textColor: "{colors.surface-container-lowest}"
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "12px 24px"
    height: "48px"
  button-tonal:
    backgroundColor: "{colors.sage-container}"
    textColor: "{colors.sage-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "12px 24px"
    height: "48px"
  card-surface:
    backgroundColor: "{colors.surface-container-low}"
    textColor: "{colors.ink-charcoal}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "14px"
  list-row:
    backgroundColor: "{colors.surface-container-low}"
    textColor: "{colors.ink-charcoal}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
    height: "56px"
  list-row-selected:
    backgroundColor: "#c9dad7"
    textColor: "{colors.ink-charcoal}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
    height: "56px"
  input-outlined:
    backgroundColor: "{colors.mineral-mist}"
    textColor: "{colors.ink-charcoal}"
    typography: "{typography.body}"
    rounded: "{rounded.xs}"
    padding: "16px"
---

# Design System: OpenChamber PTT

## Overview

**Creative North Star: "The Quiet Console"**

OpenChamber PTT treats voice-driven coding like Google Pixel Recorder treats audio capture: a calm, purely tonal Material Design 3 instrument panel where resting surfaces step back into soft mineral tones and saturated color speaks only when the microphone, grace timer, or voice playback is live. On Android 12 and newer, `OpenChamberPttTheme` adopts Material You dynamic wallpaper color (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) while preserving exact semantic role separation; on older builds or fallback contexts, it settles into a cool pine-and-mineral palette in both light and dark themes.

Hierarchy relies on tonal contrast, generous `>=48dp` interactive targets, and progressive disclosure rather than drop shadows or heavy borders. The primary screen centers on four essentials—the active session pill, the circular Hero PTT orb, quick audio/voice toggles, and the latest turn transcript—while server connections, voice catalogs, session switching, and UX telemetry tuck cleanly into full-height `ModalBottomSheet` surfaces. Confirmed anti-references include decorative gradients, persistent card shadows, and non-native iOS chrome.

**Key Characteristics:**
- Purely tonal Material 3 surface hierarchy (`surfaceContainerLowest` through `surfaceContainerHighest`) with zero card drop shadows.
- Pixel Recorder-inspired central circular capture orb (`HeroPttControl`) anchored above the fold with clear 12-state color transitions.
- Semantic signal restraint: saturated primary, tertiary, and error tones appear only for live PTT state, active selection, and primary actions.
- Native Android discipline: edge-to-edge system bar/IME insets, predictive back handling, `FlowRow` wrapping, and strict `>=48dp` touch targets.

## Colors

The static fallback palette pairs cool mineral-tinted neutrals with deep pine teal, sage, steel slate, and high-visibility crimson across first-class light and dark schemes (`Theme.kt`), overridden automatically by Android 12+ dynamic color when enabled.

### Primary
- **Deep Pine Teal** (`deep-pine-teal`, dark pair `night-teal`): Primary interactive actions (`Arm`, `Connect`, `Save`), active switches, Markdown links, and Hero PTT ready state (`DISARMED`).
- **Mint Container** (`mint-container`, on-color `deep-pine-ink`; dark `night-teal-container`): Armed idle and staged-draft states (`ARMED_IDLE`, `STAGED_DRAFT`), selected voice rows (`VoiceOptionRow` in `VoicePickerSheet`), and active status pills; `SessionPickerSheet` instead uses a Soft Tonal Tint (`12%` `primary` composited over `surfaceContainerHigh` / `surfaceContainer`) with a `1dp` `38%` `primary` border.

### Secondary
- **Muted Sage** (`muted-sage` / `sage-container`, on-color `sage-ink`): Tonal secondary actions (`FilledTonalButton`), transcribing and waiting-for-agent states (`TRANSCRIBING_SEND`, `TRANSCRIBING_STAGE`, `WAITING_AGENT`), and supporting toggles (`Headset mic`).

### Tertiary
- **Steel Slate** (`steel-slate` / `steel-container`, on-color `steel-ink`): Playback and timing states—specifically the 2.5-second undoable send grace (`SEND_GRACE`), TTS playback (`SPEAKING_TTS`), and organized draft readback (`STAGED_DRAFT_SPEAKING`).

### Neutral
- **Mineral Mist & Night Pine** (`mineral-mist` light / `night-pine-bg` dark): Base canvas ground (`background` / `surface`).
- **Stepped Container Neutrals** (`surface-container-lowest`, `surface-container-low`, `surface-container`, `surface-container-high`, `surface-container-highest`; dark `night-surface-low`, `night-surface`): Tonal elevation stack separating primary cockpit cards (`low`), nested sheet cards (`container`), elevated sub-cards and inactive pills (`high`), and code/table headers (`highest`).
- **Charcoal & Muted Slate Ink** (`ink-charcoal`, `ink-muted`, `outline-hairline`): Primary copy, secondary path/telemetry subtitles, and `1dp` structural dividers (`outlineVariant`).

### Error & Live Capture
- **Recording Crimson** (`recording-crimson` / `crimson-container`, on-color `crimson-ink`; dark `night-coral`): Live microphone capture (`RECORDING` fills the Hero orb in solid error crimson with high-contrast `onError` icon), tool-permission warnings (`PermissionWarningBanner`, `PERMISSION_BLOCKED`), and recoverable `ERROR` states.

**The Semantic Signal Rule.** Saturated `primary`, `tertiary`, and `error` containers belong strictly to live operational state, selected items, and primary CTAs—resting cards stay within neutral `surfaceContainer*` steps.

**The Dynamic-First Fallback Rule.** Components always consume `MaterialTheme.colorScheme` roles rather than raw hex constants so Material You wallpaper theming and dark mode resolve consistently across every screen and sheet.

## Typography

**Display & UI Font:** Roboto (Android system sans-serif via Material 3 default `Typography`).
**Code & Telemetry Font:** Roboto Mono / system monospace (`FontFamily.Monospace`).

**Character:** Familiar, high-clarity utility typography matching Android system apps. Hierarchy leans on Material role sizing and weight shifts (`Regular` 400 vs `Medium` 500 vs `SemiBold` 600) rather than decorative display faces or tight uppercase tracking, with `sp` scaling respected everywhere.

### Hierarchy
- **Headline** (`headlineSmall`, `600`, `24sp`, `1.33` line height): Onboarding / setup title in `MainActivity`.
- **Title** (`titleLarge` / `titleMedium` / `titleSmall`, `500`–`600`, `14sp`–`22sp`, `1.35` line height): Bottom sheet headers (`Select Session`, `Voices`, `Settings`), Hero PTT primary action label, Markdown H1/H2 headings, `SessionPill` title, and card section headers.
- **Body** (`bodyLarge` / `bodyMedium` / `bodySmall`, `400`–`600`, `12sp`–`16sp`, `1.43` line height): Selector row titles (`SemiBold` when selected), user transcript and assistant Markdown replies (`bodyMedium`), and secondary metadata, directories, and inline error/helper copy (`bodySmall`).
- **Label** (`labelLarge` / `labelMedium` / `labelSmall`, `500`–`600`, `11sp`–`14sp`, `1.33` line height): `StatusPill` readouts, action buttons, server group headers, slider tick labels (`Essentials`, `Balanced`, `Detailed`), and `Active` / `You` tags.
- **Monospace** (`FontFamily.Monospace`, `400`, `12sp`, `1.45` line height): Markdown inline code, fenced code blocks on `surfaceContainerHighest`, and recent JSONL lines in `VoiceUxLogSheet`.

**The Scale-Role Discipline Rule.** Map every string to a Material 3 `MaterialTheme.typography` role (`sp` scaled) and avoid fixed pixel text sizes or fixed-height wrappers that clip under `font_scale = 1.3`.

## Layout

Both `MainActivity` and `BubbleActivity` render edge-to-edge (`enableEdgeToEdge()`) with safe-drawing and IME window insets so content clears system bars, display cutouts, and the soft keyboard.

- **Compact Portrait Cockpit**: Single centered column constrained to `560dp` max width (`padding(horizontal = 16dp, vertical = 8dp)`), spaced at `12dp` between `SessionPill`, `HeroPttControl`, toggle pills, `StagedDraftCard`, and `TurnTranscriptPanel`.
- **Wide / Landscape Two-Pane Cockpit**: Switches at `maxWidth >= 600dp` or compact landscape (`maxWidth >= 540dp && maxWidth > maxHeight`) when operated past setup into a balanced `Row` (`16dp` gap) splitting controls (`weight(0.95f)`) on the left and active draft / transcript (`weight(1.05f)`) on the right.
- **Floating Bubble Cockpit**: Condensed single-column flow (`BubbleActivity`, `DESIRED_HEIGHT_DP = 560dp`, `14dp` horizontal padding, `8dp` vertical spacing) using compact `76dp` Hero PTT sizing to fit beside host apps, with bottom sheets collapsing drag handles and tightening padding below `420dp` height.
- **Bottom Sheets**: Full-height-capable `ModalBottomSheet`s (`skipPartiallyExpanded = true`) with `16dp` horizontal padding, `24dp` bottom padding, `12dp` section gaps, and `6dp` item gaps in `LazyColumn`s.

**The Above-the-Fold Instrument Rule.** Keep session switching, live PTT capture, and primary audio/voice toggles visible without scrolling in compact portrait and Bubble layouts; relegate deep settings, voice catalogs, and logs to sheets.

## Elevation & Depth

OpenChamber PTT uses **purely tonal Material 3 layering** rather than card shadows. Base backgrounds (`mineral-mist` / `night-pine-bg`) step cleanly through `surfaceContainerLow` (top-level cards and inactive list rows), `surfaceContainer` (nested sheet cards and toggles), `surfaceContainerHigh` (elevated banners, spoken summary sub-card, and disabled controls), and `surfaceContainerHighest` (code blocks and table headers).

The circular `HeroPttControl` is the lone control with subtle Material surface depth (`2dp` shadow at rest, `6dp` during active `RECORDING`), reinforced during active capture, send grace, and TTS playback by a concentric `750ms` reverse-repeating pulse halo (`1.0x` to `1.08x` scale at `20%` container alpha, disabled automatically when system `ANIMATOR_DURATION_SCALE == 0`).

**The Tonal Step Over Shadow Rule.** Never stack drop shadows or thick borders on cards, sheets, or list rows; convey separation by stepping one `surfaceContainer*` level up or down.

## Shapes

Corners follow a tight concentric Material 3 progression from circular capture controls down to dense data blocks:

- **Full Circle & Pill** (`CircleShape` / `RoundedCornerShape(50)` / `9999px`): Circular `HeroPttControl` and pulse ring, status indicator dots, `StatusPill`, and all Material 3 action buttons.
- **Large Cockpit Cards** (`16dp` / `RoundedCornerShape(16.dp)`): `SessionPill`, `StagedDraftCard`, `PermissionWarningBanner`, `TurnTranscriptPanel`, and onboarding server/permission cards.
- **Sheet Containers & Interactive Rows** (`12dp` / `RoundedCornerShape(12.dp)`): `SpokenDetailSlider`, `SettingsBottomSheet` configuration cards, `VoiceOptionRow`, and `SessionPickerSheet` rows.
- **Sub-Containers, Child Rows & Code** (`10dp` / `RoundedCornerShape(10.dp)`): Ephemeral `Quick answer` callout, collapsible Spoken Readout box, and collapsible `Up next` queue strip inside `TurnTranscriptPanel`; nested `SessionChildRow` cards inside `SessionPickerSheet`; and Markdown fenced code blocks.
- **Tables & Inputs** (`8dp` / `4dp`): Markdown data tables (`8dp` with `1dp` `outlineVariant` border), Material `OutlinedTextField` (`4dp` default `ExtraSmall`), and `2dp`-rounded `3dp` blockquote accents.

## Components

### Hero PTT Control (`HeroPttControl`)
A Pixel Recorder-inspired circular focal control that anchors the cockpit and changes container color, label, icon, and pulse motion across all 12 `PttState` values.
- **Size & Shape:** `104dp` circle (`40dp` icon) on phone portrait, `76dp` circle (`32dp` icon) in wide landscape and Bubble layouts, inside a `buttonSize + 14dp` pulse wrapper (`CircleShape`).
- **Color Assignment:** `primary`/`onPrimary` when `DISARMED` and ready, `primaryContainer`/`onPrimaryContainer` when `ARMED_IDLE` or `STAGED_DRAFT`, `error`/`onError` while `RECORDING`, `tertiaryContainer`/`onTertiaryContainer` during `SEND_GRACE` and TTS speech, `secondaryContainer` with `3dp` spinner while `TRANSCRIBING_*` / `WAITING_AGENT` (disabled).
- **Motion:** `180ms` color transition (`animateColorAsState`) and `750ms` infinite reverse pulse (`1.0f..1.08f`) when recording, speaking, or in send grace, suppressed if `ANIMATOR_DURATION_SCALE == 0`.

### Buttons & Action Groups
- **Shape:** Full pill (`RoundedCornerShape(50)` via Material 3 `Button`, `FilledTonalButton`, `OutlinedButton`, `TextButton`).
- **Sizing & Wrapping:** Always `heightIn(min = 48.dp)` and grouped inside `FlowRow(horizontalArrangement = spacedBy(8dp), verticalArrangement = spacedBy(8dp))` so large accessibility text wraps cleanly instead of clipping.

### Status Pill & Filter Chips
- **Status Pill (`StatusPill`):** Pill-shaped (`RoundedCornerShape(50)`) tonal readout (`6dp` vertical, `12dp` horizontal padding) pairing an `8dp` circular status dot with a `labelMedium` `SemiBold` operational label and `LiveRegionMode.Polite` announcement.
- **Filter Chips (`FilterChip`):** Material 3 filter chips (`heightIn(min = 48.dp)`) in horizontal scrollable rows (`8dp` gap) inside `SessionPickerSheet` and `VoicePickerSheet`.

### Cockpit Cards & Selectable Rows
- **Session Pill (`SessionPill`):** `16dp` rounded `surfaceContainerLow` bar (`heightIn(min = 52.dp)`, `14dp × 10dp` padding) showing pinned session title, host + directory leaf subtitle, and trailing swap icon or progress spinner.
- **Transcript & Draft Cards (`TurnTranscriptPanel`, `StagedDraftCard`):** `16dp` rounded `surfaceContainerLow` cards (`14dp` padding) separated internally by `1dp` `outlineVariant` dividers. `TurnTranscriptPanel` includes an optional ephemeral `Quick answer` callout (`10dp` rounded `surfaceContainerHigh` with `ic_graphic_eq`, `primary` label, optional dismiss `IconButton`, 2-line question preview, and spoken answer), a multi-response stepper (`Response X of Y` with optional `· N unread` badge, chevron `IconButton`s, and a `FlowRow` of `Mark read`, copy, and contextual `Read all (N)` / `Read turn (N)` / `Read aloud` / `Stop` actions), a `10dp` `surfaceContainerHigh` collapsible Spoken Readout box, and a collapsible `Up next · N unread` queue strip (`10dp` rounded `surfaceContainerHigh`, `6dp` `primary` dot, `>=48dp` clickable queue rows).
- **Sheet Selection Rows (`SessionPickerSheet`, `VoiceOptionRow`):** `SessionPickerSheet` root rows are `12dp` rounded cards (`heightIn(min = 56.dp)`, `14dp × 10dp` padding) using `surfaceContainerHigh` at rest and a Soft Tonal Tint (`12%` `primary` composited over `surfaceContainerHigh` + `1dp` `38%` `primary` border) when selected, with `FontWeight.Normal` on read idle rows vs `FontWeight.SemiBold` on unread or selected rows, `6dp` `primary` unread indicator dots, `"Failed · "` `bodySmall` `FontWeight.Medium` prefix on failed unread sessions, and trailing `18dp` `primary` `ic_check`. Expandable parent rows pair a `.selectable` main zone with a sibling `>=48dp` expand button separated by a `1dp` `outlineVariant` divider; nested subagent child rows (`10dp` radius, `heightIn(min = 52.dp)`, `surfaceContainer` at rest and `12%` `primary` over `surfaceContainer` + `1dp` `38%` `primary` border when selected) indent at `16dp` / `28dp` beside a `1dp` `outlineVariant` guide rail. `VoiceOptionRow` (`VoicePickerSheet`) uses `12dp` rounded `surfaceContainerLow` at rest and `primaryContainer` / `onPrimaryContainer` when selected.

### Inputs, Switches & Discrete Sliders
- **Outlined Text Fields (`OutlinedTextField`):** Full-width fields (`4dp` default corner) with `ContentType.Password` + `LocalAutofillManager` on credentials and `singleLine = true` on search/server URLs.
- **Spoken Detail Slider (`SpokenDetailSlider`):** `12dp` rounded tonal card holding a 3-step Material 3 `Slider` (`Essentials`, `Balanced`, `Detailed`) whose tick labels each apply `minimumInteractiveComponentSize()` and `Role.RadioButton`.
- **Settings Switch Cards (`SettingsBottomSheet`):** `12dp` rounded `surfaceContainer` cards (`heightIn(min = 48.dp)`, `14dp × 10dp` padding, `toggleable(role = Role.Switch)`) including `Fallback to Parakeet` and `Local quick-turn answers` (gating local small-model answers without forwarding to the session, while keeping live tuning commands active).

### Bottom Sheets (`ModalBottomSheet`)
- All four sheets (`SettingsBottomSheet`, `VoicePickerSheet`, `SessionPickerSheet`, `VoiceUxLogSheet`) open directly to full content with `skipPartiallyExpanded = true`, `16dp` horizontal and `24dp` bottom padding, and API 33+ `OnBackInvokedCallback` + `BackHandler` support where sub-modes capture back navigation.

## Do's and Don'ts

### Do:
- **Do** keep `>=48dp` minimum touch targets (`heightIn(min = 48.dp)` or `minimumInteractiveComponentSize()`) on every interactive button, chip, slider tick, and list row.
- **Do** wrap multi-button toolbars in `FlowRow` so `font_scale = 1.3` wraps naturally without clipped labels.
- **Do** step surfaces tonally (`surfaceContainerLow` → `surfaceContainer` → `surfaceContainerHigh`) and consume `MaterialTheme.colorScheme` roles so light, dark, and dynamic wallpaper schemes stay coherent.
- **Do** use minimal local `24dp` vector drawables in `app/src/main/res/drawable/`, honor `Settings.Global.ANIMATOR_DURATION_SCALE` via `shouldAnimateMotion`, and open multi-section sheets with `skipPartiallyExpanded = true`.
- **Do** verify every UI change on `openchamber_api35` in both light and dark themes (and check `font_scale = 1.3`).

### Don't:
- **Don't** add `material-icons-extended` or third-party icon font dependencies that bloat APK size.
- **Don't** replace intrinsic line-height and padding with hardcoded fixed heights that truncate under large system font sizes.
- **Don't** add persistent drop shadows to cards/sheets, decorative gradients, or non-Android iOS controls.
- **Don't** block the main cockpit with full-screen modal interruptions when an inline banner or `ModalBottomSheet` belongs.
