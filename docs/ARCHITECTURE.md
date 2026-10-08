# Architecture

## 1. Layering

```
┌────────────────────────────────────────────────────────────────────┐
│ :app — NavHost, MainActivity, PlaybackService, DI composition root │
├────────────────────────────────────────────────────────────────────┤
│ :feature:* — Compose screens + MVI ViewModels (no domain logic)    │
│   auth onboarding projects arranger mixer pianoroll instruments    │
│   effects loops collab social export settings paywall              │
├────────────────────────────────────────────────────────────────────┤
│ :core:domain — use cases, EditorActions (pure), UndoRedo, GridMath │
│                repository INTERFACES (dependency inversion)        │
├────────────────────────────────────────────────────────────────────┤
│ :core:data — repository IMPLs, SyncEngine workers, export pipeline │
│ :core:database (Room) · :core:datastore (prefs) · :core:network    │
│ :core:realtime (CRDT) · :core:audio (JNI) · :core:midi · analytics │
├────────────────────────────────────────────────────────────────────┤
│ :core:model — pure Kotlin domain language (JVM module, zero deps)  │
│ :core:common — DataResult, dispatchers, retry, TimeMath            │
│ :core:designsystem + :core:ui — theme tokens, widgets, canvases    │
└────────────────────────────────────────────────────────────────────┘
```

Rules (CI-enforced where possible):
* **features never depend on features** — cross-feature navigation lives in `:app`'s NavHost.
* **domain never imports Android** — it is a JVM module; repositories are interfaces.
* **data depends on domain, never the reverse** (dependency inversion).
* `core:model` is the shared language: serializable, allocation-conscious data classes.

## 2. UI architecture: MVVM + MVI intents

Screens render a single immutable `*UiState` data class from a `StateFlow`.
User actions are `Intent` sealed types dispatched to the ViewModel
(`onIntent(...)`), which runs use cases and emits new state. One-shot effects
(navigation, sheets) ride on dedicated state fields consumed by `LaunchedEffect`.

```
UI ──intent──▶ ViewModel ──use case──▶ Repository ──▶ Room / Network / Engine
   ◀──state──            ◀──DataResult──          ◀──Flow──
```

## 3. Editing pipeline (the critical path)

```
gesture ─▶ ViewModel ─▶ ProjectEditorSession.mutate { EditorActions (pure) }
                             │
                             ├─▶ UndoRedoManager (snapshot stack, coalescing)
                             ├─▶ StateFlow<Project> (UI recomposes)
                             ├─▶ EngineGraphCompiler ─▶ JNI commands ─▶ audio thread
                             ├─▶ CollaborationEngine ─▶ OpEnvelope ─▶ outbox(Room) + socket
                             └─▶ debounced autosave ─▶ Room ─▶ SyncEngine (WorkManager)
```

Every mutation is: pure transform → snapshot → broadcast → persist. The same
`EditorActions` functions back undo, collab ops, and offline edits, so all
three paths can never diverge.

## 4. Threading model

| Thread | Owner | Rules |
|---|---|---|
| Audio callback (SCHED_FIFO via AAudio) | Oboe | **No allocation, no locks, no JNI, no I/O.** Applies command queue, renders graph. |
| Prefetch feeders | `Dispatchers.audio` pool | Read PCM cache → SPSC rings per strip |
| Record writer | dedicated `s1-rec-writer` | Drain capture rings → streaming WAV |
| Control/sync | `Dispatchers.IO` | Retrofit, Room, WorkManager, socket |
| UI | Main | Compose only; polls meters at 30 Hz |

Cross-thread primitives: `SpscRingBuffer` (audio data), `LockFreeQueue`
(MPSC commands/MIDI), atomics + seqlocks (meters), `ReclaimQueue` (deferred
free of payloads retired by the audio thread).

## 5. Persistence strategy

Projects are **documents** (serialized `Project` JSON) plus denormalized
metadata columns for indexed listing — matching the CRDT wire format 1:1.
Normalized clip/note tables buy nothing on mobile (sessions always load
whole) and would complicate merge. Autosave = debounced document upsert +
outbox ops. WAL journaling keeps readers (project browser) unblocked during
editor writes.

## 6. Module count & build

26 Android/JVM modules + `build-logic` convention plugins
(`studioone.android.library`, `.compose`, `.feature`, `.hilt`, `.jni.library`,
`.application`). Feature module boilerplate is 30 lines; all shared config
lives in conventions. Config cache + parallel builds keep clean builds
~4-6 min on CI (32-core runner), incremental < 30 s.

## 7. Sizing against device classes

`DeviceProfile.classify()` (RAM, cores, platform media perf class) drives:
engine strip/voice pools (32/64/96 strips), FX pool sizes, waveform LOD,
feed ring depth, default buffer size, and analytics sampling. See
[PERFORMANCE.md](PERFORMANCE.md).
