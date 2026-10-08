# MIDI Subsystem

* **Devices:** Android MIDI framework (`android.media.midi`) covers USB
  class-compliant gear and system BLE-MIDI; `MidiDeviceManager` tracks
  attach/detach, exposes per-device streams, and feeds a running-status-aware
  `MidiParser`.
* **Routing:** `MidiInputRoute` maps (device, channel) → instrument strip with
  transpose/velocity scaling. Notes go to the native `VoiceManager` via the
  lock-free MIDI queue (sample-offset field reserved for intra-block timing).
* **MPE:** channels 1–15 voices; per-note pressure→filter, timbre(CC74)→osc
  mix, slide→±48 st bend in `SubtractiveVoice` (see `setMpeEnabled`).
* **MIDI Learn:** any knob/fader binds to a CC; mappings persist per project,
  support ranges/inversion, and route through `MidiEngineRouter` to engine
  parameter commands (never through the UI thread for playback-critical moves).
* **Clock:** 24 ppq beat clock generator/consumer (`MidiClock`) for hardware
  sync; tempo comes from the transport's frames-per-beat.
* **Editor math:** quantize/swing/humanize/legato/strum/chord-detect/scale-snap
  are pure functions (`MidiQuantizer`), shared by piano roll, record quantize,
  and step sequencer — all unit-tested.
* **Peripheral mode** (phone as a MIDI controller surface via
  `MidiDeviceService`) is designed (docs here) and scheduled for 1.1.
