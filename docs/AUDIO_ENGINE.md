# Native Audio Engine (C++17)

Location: `core/audio/src/main/cpp/`. Loaded as `libstudioone-audio.so`.

## 1. Backend strategy: AAudio-first, OpenSL ES fallback

We build on **Oboe 1.9** (fetched via CMake `FetchContent`, pinned tag).
Oboe selects AAudio on API 26+ and transparently falls back to OpenSL ES on
devices where AAudio is missing or vendor-blocklisted — one code path, both
backends. We request:

* `PerformanceMode::LowLatency` (fast mixer; MMAP when granted)
* `SharingMode::Exclusive` (opt-in "pro audio" setting)
* Float PCM, `framesPerCallback` = user buffer (64/128/256/512)
* Input preset `Unprocessed` (no system AGC/NS — we gate/limit ourselves)
* Full-duplex: output stream owns the callback; input is read non-blocking
  inside it (missed input → silent capture block, never a stalled output).

Device disconnects (`onErrorAfterClose → ErrorDisconnected`) trigger a
controlled `restart()` on a helper thread; graph state survives reopen. If
the device refuses the requested sample rate, the graph adapts to the native
rate and Kotlin state is re-synced.

**Latency budget (48 kHz, 128 frames):** input stream ~2.7 ms + output ~2.7 ms
+ callback 2.7 ms ≈ **8 ms round trip** on MMAP devices; < 20 ms typical
shared-mixer devices; Bluetooth A2DP paths warn the user (> 100 ms, monitor off).

## 2. Real-time contract

Inside `AudioGraph::render` (the callback):

* **Zero heap allocation** — strips, buses, FX units, delay lines, rings, and
  voice managers come from pools sized at `init()`.
* **Zero locks** — commands arrive via a Vyukov bounded MPSC queue; audio data
  via SPSC rings; meters publish via atomics/seqlocks.
* **Zero JNI** — Kotlin pushes data (feed rings, MIDI, commands) in; polls
  meters out at 30 Hz. The callback never touches the JVM.
* **Deferred reclamation** — retired FX units/payloads go to a retire window
  (4 callbacks) or the `ReclaimQueue`, freed by the control thread.
* Denormal protection via bias trick (`DenormalGuard`); branch-light inner
  loops; `-ffast-math -O3`, UBSan in debug builds only.

A violation of any rule is a review-blocking defect (checklist in
[TESTING.md](TESTING.md)).

## 3. Graph topology

```
inputs ─▶ input gain ─▶ monitor FX ─┬─▶ record rings ─▶ WAV writer thread
                                    └─▶ monitor sum ─────────────┐
                                                                 ▼
strip: feeder ring (Kotlin prefetch) ─▶ PDC delay ─▶ inserts ─▶ gain/pan/width ─┬─▶ sends ─▶ bus strips ─┐
       instrument voices (synth/sampler/drums) ────────────────────────────────┘                        │
                                                                                                        ▼
bus: sum ─▶ inserts ─▶ balance ─▶ master ─────────────────────────────────────────────────────▶ master inserts
automation lanes (baked [frame,value] arrays, binary-searched per block)                        + metronome
                                                                                                + limiter
                                                                                                + BS.1770 loudness
                                                                                                + FFT spectrum
                                                                                                ─▶ interleaved out
```

* **PDC (latency compensation):** per-render max chain latency is computed;
  every strip is delayed by `maxLatency − stripLatency` through fixed delay
  lines (≤ 4096 frames). Look-ahead limiter latency is included.
* **Pan law:** constant power (−3 dB center for stereo balance, √2-normalized
  so mono sources stay unity at center).
* **Solo:** cached once per block; soloed strips bypass the audible path but
  keep rendering into record rings (you can record a muted-in-PFL track).
* **Automation:** Kotlin bakes lanes into `[frame, value]` float arrays; the
  audio thread binary-searches + linearly interpolates per block and drives
  `SmoothedParam` targets (5–30 ms smoothing prevents zipper noise).

## 4. FX & instruments

Plugin units implement `FxUnit` (process/setParam/latency/tail). Catalog:
dynamics (FF compressors with soft knee + program-dependent release, 2 ms
look-ahead limiter, gate with hysteresis, split-band de-esser), Freeverb-style
reverb (8 damped combs + 4 allpasses, stereo spread), delay/ping-pong with
filtered feedback, polyBLEP-safe modulation set, waveshapers (tanh/asym/fuzz
+ tone stack), bitcrusher with TPDF dither, tape hysteresis saturation,
granular pitch-shift/time-stretch (dual Hann-crossfaded read heads).

Instruments: `SubtractiveVoice` (2 polyBLEP oscillators + sub, SVF 12/24 dB,
dual ADSR, LFO, glide, MPE), `SamplerVoice` (multisample zones from the RAM
`SamplePool`, loops, choke groups), `DrumMachine` (round-robin voices per pad).
FM/wavetable/granular presets map onto the subtractive topology with flavored
defaults in 1.0; true 4-op FM and wavetable scanning are roadmap items.

Polyphony steals voices: free → released → round-robin oldest. Panic
(all-notes-off) is one command.

## 5. Media pipeline

* Imports/recordings decode to **float PCM caches** via MediaCodec
  (`core:audio/PcmDecoder`), keyed by uri+rate; LRU-evicted at 512 MB.
* Playback: per-track Kotlin feeder coroutines read caches, apply clip
  gain/fades/loop, and write SPSC rings (~0.5 s depth) — the audio thread only
  consumes.
* Waveforms: multi-resolution peak pyramids (256/1024/4096/16384 frames per
  bucket) computed at import; the arranger draws min/max spans per pixel.
* Native WAV reader handles factory one-shots (assets); compressed user files
  never reach the native side.

## 6. Recording

`Recorder` owns up to 8 concurrent takes. Audio thread interleaves armed
strips into per-take SPSC rings (1 s depth); dedicated writer threads stream
WAV (24-bit default) with periodic flush — crash-loss window < 0.5 s. Take
files are imported as samples and clipped at the exact transport position;
punch/loop windows are transport-driven. Overruns are counted and surfaced as
telemetry (`takeStats`).

## 7. Extending: adding a plugin

1. Implement `FxUnit` subclass in `cpp/fx/`.
2. Register an id in `NativePluginId` + `createFxUnit()` + pool counts.
3. Add `FxPluginId` enum entry (model) and its `nativeId` mapping (controller).
4. Add `FxParamCatalog.specs()` entries — the FX rack UI renders itself.
5. DSP unit tests in `cpp/tests/test_main.cpp`.

Third-party plugin hosting (a sandboxed ABI over the same `FxUnit` contract)
is designed for but not shipped in 1.0 — see [ROADMAP.md](ROADMAP.md).

## 8. Known limitations (honest list)

* Live monitoring of **Bluetooth classic** input is disabled by policy (latency);
  BLE audio (API 33+) is allowed with a warning.
* Clip-level live time-stretch uses the granular shifter; full WSOLA/élastique-
  class stretching runs offline (bounce/cloud) — documented in EXPORT.md.
* Vocoder, cabinet IR convolution, and granular *synthesis* are catalog
  entries rendering passthrough until their native units land (tracked in ROADMAP).
