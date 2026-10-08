# Audio engine

The engine lives in `audio/src/main/cpp` (C++20, Oboe 1.9) behind a JNI
facade (`com.studioone.audio.AudioEngine`).

## Real-time safety rules (hard requirements)

The Oboe callback (`AudioEngine::onAudioReady`) is the only realtime thread.
On that thread, the following are **forbidden**:

1. Heap allocation (`new`, `malloc`, `std::vector::resize`, `std::string` building)
2. Locks (`std::mutex`, atomics with blocking semantics)
3. I/O (disk, network, logging)
4. Exceptions crossing into the callback
5. JNI calls into the JVM

Mechanisms used instead:

- `dsp::RingBuffer<T>` — SPSC lock-free rings for record taps and MIDI events.
- `dsp::ParamFifo` — UI→RT parameter messages drained at callback top.
- Preallocated scratch buffers in `AudioGraph::prepare()` (per max buffer size).
- `std::atomic` snapshots for meters (relaxed ordering; UI polls at 30 fps).
- Transport state as atomics (`playheadFrame`, `state`, loop range).

Graph topology changes (add/remove tracks, clips, effects) happen on the
graph thread while the stream is paused or between renders; `AudioEngineController`
serializes them behind a mutex that the RT thread never touches.

## Graph

```
tracks[N] ── source (AudioClipSource | MidiInstrumentSource)
        ── insert chain (EffectBase*)
        ── meter tap
        ── gain/pan (equal-power)
        ──> master bus  |  FX buses (sends)
master ── insert chain ── Meter (peak/RMS + K-weighted LUFS-M/I)
```

- Node ids: tracks 1..511, buses 512..1023, master 1024.
- Parameter addressing: `(insertIndex << 8) | paramIndex`, matching
  `EffectCatalog` order on the Kotlin side.
- Latency compensation: each `EffectBase` reports `latencyFrames()`
  (e.g. lookahead limiter). The graph sums per-chain latency; track-level
  compensation delay lines are applied at clip render (milestone 2: full
  PDC matrix).

## Sources

- **AudioClipSource** renders decoded WAV PCM (shared `PcmBuffer`) with
  offset/gain/reverse; decode happens off-RT via `WavFile::read`.
- **MidiInstrumentSource** drains a lock-free MIDI queue and renders one of:
  `SubtractiveSynth` (polyBLEP oscs → SVF → ADSR), `Sampler` (multisample,
  choke groups, voice stealing), `DrumSynth` (synthesized GM kit).

## Recording

Record taps write input samples into a 1M-sample ring; a background writer
thread drains it into `WavFile::Writer` (16/24-bit PCM). Count-in and punch
are transport-level: the writer starts after `countInBeats`, punch range is
the loop range.

## Latency strategy

1. Request AAudio exclusive mode at the device's native sample rate and
   frames-per-burst (probe in `core:ui`'s `probeAudioCapability`).
2. Buffer size selectable 64/128/256/512; the engine clamps to burst size.
3. OpenSL ES fallback when AAudio is unavailable/quirky (device list).
4. Measured output latency exposed via `calculateLatencyMillis()` and shown
   in Settings → latency probe.

## Adding a new effect (checklist)

1. `EffectCatalog.specs` entry with `ParamSpec`s (id order = native order).
2. C++ class implementing `prepare/setParameter/process` (RT-safe).
3. Factory case in `dsp/effects.cpp::createEffect`.
4. `EffectType` enum entry **in the same ordinal position** as `EffectKind`.
5. Unit test in `audio/src/main/cpp/tests` (or Kotlin test if pure-Kotlin).
