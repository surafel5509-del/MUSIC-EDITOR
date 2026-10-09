# ADR 0002: Oboe (AAudio + OpenSL ES) native engine

**Status:** accepted

## Context

A mobile DAW needs <10ms round-trip on supported devices, glitch-free
multitrack playback, and realtime-safe parameter control.

## Decision

C++ engine on Oboe (which wraps AAudio and falls back to OpenSL ES), exposed
through JNI. All DSP is custom, allocation-free, and runs on the Oboe
callback thread.

## Rationale

- Oboe negotiates exclusive-mode, native-rate streams and reports burst size
  and measured latency — the only path to pro-audio numbers on Android.
- Keeping the graph in C++ gives deterministic per-frame cost and lets us
  unit-test DSP on host machines (GoogleTest).
- Kotlin stays the source of truth for document state; the engine is a
  mirror controlled by lock-free queues (see docs/AUDIO_ENGINE.md).

## Consequences

- Strict RT-safety rules enforced by review + docs (no locks/allocs in the
  callback).
- WAV-only on the native read path; compressed formats are decoded by
  Kotlin/FFmpeg before reaching the engine.
