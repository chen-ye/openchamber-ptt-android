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

<!-- impeccable:design-map -->
Name: OpenChamber PTT
Colors: primary: deep-pine-teal #0e6b5e; secondary: muted-sage #4a635e; tertiary: steel-slate #436278; neutral: mineral-mist #f5faf8; error: recording-crimson #ba1a1a; primary: mint-container #a2f2e2 (+21 more in the sidecar)
Fonts: Roboto (headline, title, body, label); ui-monospace (mono)
Spacing: xs 4px, sm 6px, md 8px, base 10px, lg 12px, gutter 14px, xl 16px, xxl 24px
Radius: xs 4px, sm 8px, md 10px, lg 12px, xl 16px, full 9999px
North Star: "The Quiet Console"
Rules:
- The Semantic Signal Rule. Saturated `primary`, `tertiary`, and `error` containers belong strictly to live operational state, selected items, and primary CTAs—resting cards stay within neutral `surfaceContainer*` steps.
- The Dynamic-First Fallback Rule. Components always consume `MaterialTheme.colorScheme` roles rather than raw hex constants so Material You wallpaper theming and dark mode resolve consistently across every screen and sheet.
- The Scale-Role Discipline Rule. Map every string to a Material 3 `MaterialTheme.typography` role (`sp` scaled) and avoid fixed pixel text sizes or fixed-height wrappers that clip under `font_scale = 1.3`.
- The Above-the-Fold Instrument Rule. Keep session switching, live PTT capture, and primary audio/voice toggles visible without scrolling in compact portrait and Bubble layouts; relegate deep settings, voice catalogs, and logs to sheets.
- The Tonal Step Over Shadow Rule. Never stack drop shadows or thick borders on cards, sheets, or list rows; convey separation by stepping one `surfaceContainer*` level up or down.
Don't:
- Don't add `material-icons-extended` or third-party icon font dependencies that bloat APK size.
- Don't replace intrinsic line-height and padding with hardcoded fixed heights that truncate under large system font sizes.
- Don't add persistent drop shadows to cards/sheets, decorative gradients, or non-Android iOS controls.
- Don't block the main cockpit with full-screen modal interruptions when an inline banner or `ModalBottomSheet` belongs.
Do:
- Do keep `>=48dp` minimum touch targets (`heightIn(min = 48.dp)` or `minimumInteractiveComponentSize()`) on every interactive button, chip, slider tick, and list row.
- (+4 more)
Map: app/src/main/res/drawable/
Full: .impeccable/design-full.md (read a section when you need its detail)
