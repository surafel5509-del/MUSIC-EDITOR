# Architecture

StudioOne Mobile is a modular, offline-first DAW. This document is the map;
per-subsystem deep dives live in sibling docs.

## Layering

```
┌────────────────────────────────────────────────────────────┐
│ feature:*        Screens + MVI ViewModels (Compose)        │
├────────────────────────────────────────────────────────────┤
│ app              DI wiring, navigation, services           │
├────────────────────────────────────────────────────────────┤
│ core:domain      Entities, use cases, EditorSession,       │
│                  TempoMap, UndoRedoManager, theory         │
├───────────────────────────┬────────────────────────────────┤
│ core:data                 │ audio (NDK)    midi            │
│ repositories, sync engine │ C++ engine     MIDI I/O        │
├───────────────────────────┼────────────────────────────────┤
│ core:database (Room)      │ core:network (Supabase/CRDT)   │
│ core:datastore            │                                │
├────────────────────────────────────────────────────────────┤
│ core:designsystem / core:ui / core:common                  │
└────────────────────────────────────────────────────────────┘
```

Dependency direction is strictly downward; features never depend on each
other. Cross-tab coordination (e.g. mixer editing the same session the
editor opened) goes through `EditorSessionHolder`, a dependency-free holder
provided as a singleton — features stay decoupled.

## Module responsibilities

| Module | Owns | Never does |
| --- | --- | --- |
| `:core:domain` | Pure model + editing semantics | Android framework APIs (except `javax.inject`) |
| `:core:data` | Offline-first persistence + sync | UI, native audio |
| `:core:database` | Room schema/DAOs | Business rules |
| `:core:network` | Supabase clients, CRDT engine, transports | Local persistence |
| `:audio` | Native engine lifecycle, analysis DSP | UI |
| `:midi` | MIDI device scanning + parsing | Audio rendering |
| `:feature:*` | One workspace surface each | Direct Room/Supabase access |

## Data flow for a typical edit

1. User drags a clip in `TimelinePane` → `EditorViewModel.moveClips()`.
2. `EditorSession.moveClips()` wraps the mutation in a `Command` and runs it
   through `UndoRedoManager` (coalescing drags into one undo step).
3. The session updates its `StateFlow<EditorState>` and schedules a debounced
   autosave (1.5s) into `ProjectRepository`.
4. `ProjectRepositoryImpl` writes Room entities and appends an op to the sync
   outbox; `SyncWorker` pushes when online.
5. In a live session the same mutation is expressed as a `CollabOp` and
   broadcast; remote ops are merged by `CrdtEngine.reduce()` and applied via
   `EditorSession.applyRemoteState()` (remote ops bypass local undo).
6. Engine-visible state (track gain/pan/mute, clips) is mirrored into the
   native graph by `AudioEngineController.syncTracks()` under a mutex.

## Undo/redo

Every mutation is a `Command { apply(); revert(); label; groupKey }`.
Consecutive commands sharing a `groupKey` (continuous gestures) coalesce so
"drag fader" is one undo step. Capacity: 128 steps, per open project.

## Offline-first guarantees

- All reads serve from Room; network only fills gaps.
- Writes commit locally first; the outbox drains opportunistically.
- Collaboration ops persist to `collab_ops` (server) **and** broadcast
  (best-effort), so reconnecting clients rebuild missed state.

## Performance budgets

| Metric | Target | Mechanism |
| --- | --- | --- |
| UI frame time | < 16 ms | Compose, no measure thrash, cached waveform peaks |
| Audio glitch rate | < 1 per hour of playback | RT-safe callback, preallocated buffers |
| Round-trip latency | < 10 ms on pro-audio devices | AAudio exclusive + matched sample rate |
| Cold start | < 2 s | Baseline profiles (milestone 2) |
| Metering overhead | < 0.5 % CPU | Atomic snapshots, 30 fps UI polling |
