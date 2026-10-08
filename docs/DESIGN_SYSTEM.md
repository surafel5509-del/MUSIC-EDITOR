# Design System

## Tokens
* **Color:** "midnight console" dark-first (bg `#0E1116`), electric cyan
  accent, magenta social accent, amber automation/tertiary. Light theme +
  high-contrast variants for both. Functional colors: record red, meter
  green/yellow/red, waveform fill.
* **Track palette:** 9 hues; a **color-blind-safe remap** (IBM-style
  lightness-separated palette) swaps in via `LocalTrackPalette` when the
  accessibility setting is on. Every state is ALSO encoded non-colorfully
  (M/S/R letters, icons, labels) per WCAG 1.4.1.
* **Type:** Material 3 scale; numeric HUD readouts use tabular monospace
  (`NumericReadout`) so transport positions never jitter.
* **Shape:** 4/8/12/16 radii; clips 6, pads 10, transport pills.
* **Dimensions:** `S1Dimensions` (touch targets 48 → 64 dp with large-targets
  setting; fader/knob/ruler sizes scale together).

## Components (`core:designsystem`)
`Knob` (vertical-drag, taper system Linear/Log/Decibel, double-tap reset,
full TalkBack state), `Fader` (audio-tapered dB law, unity tick), `LevelMeter`
(segmented LED, peak-hold 1.2 s, clip latch), `S1PrimaryButton` /
`S1SecondaryButton` / `S1RecordButton`, `StatusChip` (sync/presence),
`EmptyState`, `TutorialTooltip` (coach marks).

## Canvas language (`core:ui`)
`TimelineRuler` (1-2-5 adaptive divisions, loop region, markers),
`drawWaveformForClip` / `drawMidiNotesPreview` (shared by arranger lanes,
loop browser, feed cards).

## Layout adaptation
Phone: bottom nav + stacked editor. Tablet/foldable (WindowWidthSizeClass ≥
Medium): navigation rail, dual-pane (arranger + collab/mixer side panel),
hinge-aware via `WindowSizeClass`. Landscape keeps the transport bar
thumb-reachable; portrait shows a compact transport.

## Motion
Purposeful only: 150–250 ms transitions, no animation on meter/playhead paths
(direct state). `reduceMotion` setting disables decorative transitions.

## Accessibility acceptance (per screen)
TalkBack traversal order sane · every control has contentDescription + state ·
48 dp targets (64 with setting) · contrast ≥ 4.5:1 (7:1 high-contrast mode) ·
RTL mirrored (icons with directional meaning auto-mirror) · per-app language
picker (12 launch locales).
