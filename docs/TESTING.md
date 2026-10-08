# Testing Strategy

## Pyramid

```
        ┌───────────────┐
        │ E2E (FTL/emu) │  instrumented flows: record→edit→export
        ├───────────────┤
        │ Compose UI    │  widget semantics, arranger gestures, a11y
        ├───────────────┤
        │ Native DSP    │  17 suites / ~11k assertions, host-compiled C++
        ├───────────────┤
        │ JVM unit      │  domain, CRDT, MIDI, serializers, ViewModels (fakes)
        └───────────────┘
```

## What runs where

| Suite | Location | Highlights |
|---|---|---|
| Native DSP | `core/audio/src/main/cpp/tests` | ring buffers, lock-free queues, biquad response, FFT round-trip & bin accuracy, compressor/limiter/gate behavior, reverb tail & decay, delay time accuracy, BS.1770 loudness vs reference sine, 24-bit WAV round-trip, ADSR shape, fast-math error bounds |
| Grid & time | `core:domain` GridMathTest | tempo-map integration, tick↔frame inversion, 3/4 & 6/8, snap strength |
| Editor ops | EditorActionsTest | split (audio + MIDI note surgery), ripple, frozen-track rejection, templates |
| Undo | UndoRedoManagerTest | coalescing window, redo invalidation, bounded depth |
| CRDT | `core:realtime` CrdtTest | HLC monotonicity & tie-break, LWW convergence under reordering, OR-Set add-wins, op dedupe, prune bounds |
| MIDI | MidiQuantizerTest, MidiParserTest | swing math, legato/strum/humanize determinism, chord classification, running status, realtime-byte non-interference |
| Serialization | ProjectSerializerTest | full-document round-trip, forward/backward compatibility (unknown fields, missing optionals), polymorphic clip discriminator |
| ViewModels | feature test sources | MVI intents with fake repositories on StandardTestDispatcher |
| Compose | `core:designsystem` androidTest | Knob semantics (TalkBack state), gesture direction, range clamping |

## Conventions
* Fakes over mocks for repositories; MockK only for Android framework seams.
* Every native plugin ships with a signal-level test before merge.
* Real-time-safety review checklist: no `new`/`malloc`/`std::vector` growth,
  no mutexes, no JNI, no `printf` in anything reachable from `render()`
  (grep-verified in CI: `git grep -n "malloc\|new \|std::mutex" cpp/graph cpp/engine | grep -v "// NOLINT"`).
* Coverage floor: 70 % lines on `core:domain`, `core:realtime`, `core:midi`.

## Performance regression gates (CI, main only)
* Macrobenchmark: cold start < 1.2 s (Pixel 6a emulator profile), arranger
  scroll jank < 1 % frames over 16 ms.
* Native bench (host): render 64 strips × 8 FX ≤ 40 % of a 128-frame budget
  on a throttled x86 runner (relative gate, catches O(n²) regressions).
