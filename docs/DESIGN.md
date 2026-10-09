# Design system

Material 3 base, extended with audio-workspace primitives. Tokens live in
`core:designsystem/theme`; shared components in `core:designsystem/component`.

## Color

- Dark-first (studio context). `DarkBackground #0E1116`, surfaces step up in
  three elevation tiers.
- Brand: teal `#00C2B8` (primary), violet `#8C7BFF` (secondary), amber
  `#FFB547` (tertiary).
- Semantics: record red `#E5484D`, arm red `#FF6363`, solo amber `#FFC53D`,
  play green `#46A758` — used identically in light theme.
- 8-color rotating track palette (`trackColor(index)`) shared by timeline,
  mixer and pads.
- **Color-blind assist** swaps the most-confusable hue pair rather than
  filtering the whole UI; record/arm never rely on color alone (shape +
  icon + label always present).

## Type

Material scale with monospaced readouts (`MonoTextStyle`) for timecodes and
meter numbers — tabular stability prevents layout jitter at 30fps updates.

## Audio components

| Component | Contract |
| --- | --- |
| `Knob` | Vertical drag; 270° arc; optional log scaling; a11y label |
| `Fader` | Gain 0..4 mapped to -60..+12 dB travel; unity tick; jump-to-touch |
| `LevelMeter` | dB-segmented stereo lanes, peak hold, clip latch |
| `StudioSegmentedControl` | Tool switching (arrange tools, snap, categories) |

## Layout & adaptivity

`AdaptiveLayout` buckets width into COMPACT (<600dp), MEDIUM (600–839),
EXPANDED (840+):

- COMPACT: bottom-tab workspace, single timeline pane.
- MEDIUM: wider track rows, optional side mixer on foldables.
- EXPANDED: timeline + mixer side-by-side, larger rows.

Touch targets: 48dp baseline, 60dp in accessibility mode
(`LocalStudioSpacing.touchTarget`).

## Accessibility checklist (per screen)

- Every interactive control: `contentDescription` + 48dp minimum.
- Meters duplicate level as text on focus (TalkBack).
- No information carried by color alone.
- RTL: layouts use start/end; timeline direction stays LTR (musical
  convention) with mirrored chrome.

## Motion

- Transitions < 200ms; transport feedback is instant (no animation on
  play/record state color).
- Playhead updates at 30fps via state, not animation, to stay in sync with
  the engine.
