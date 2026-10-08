# StudioOne Mobile

A production-grade Android music production app (mobile DAW) for musicians,
producers and podcasters: multitrack recording, MIDI editing, virtual
instruments, effects, mixing/mastering, a content library and realtime cloud
collaboration.

> Working title **StudioOne Mobile** — original code, no affiliation with any
> existing product.

---

## Feature overview

| Area | Highlights |
| --- | --- |
| Projects | Create/open/duplicate/delete/archive, templates (beat, song, podcast, live, remix), tempo/key/time signature, version history, autosave |
| Timeline | Multitrack arrange view, zoom/scroll, clips with fades, split/trim/reverse, automation lanes, undo/redo, snap grid |
| Recording | Oboe/AAudio engine with OpenSL ES fallback, 44.1/48/96 kHz, 16/24-bit, buffer sizes 64–512, input monitoring, count-in |
| MIDI | Piano roll (draw/select/erase, quantize, velocity), USB + BLE MIDI input, MPE fields, chord tools, arpeggiator |
| Instruments | Subtractive synth (polyBLEP, SVF), multisample sampler with choke groups, synthesized drum kit, drum pads + step sequencer |
| Effects | Parametric/graphic EQ, compressor, gate, limiter, reverb, delay, chorus/flanger/phaser/tremolo, distortion, bitcrusher, amp/cab sim |
| Mixer | Channel strips (fader/pan/mute/solo/arm), inserts + sends + buses, master bus with LUFS-I/M metering |
| Library | Loops/one-shots/presets, search/filter, preview (Media3), SAF import, BPM + key detection |
| Collaboration | Supabase Realtime channels, CRDT op merge (LWW + add-wins OR-set), presence, invite links, offline outbox sync |
| Accessibility | TalkBack labels, high contrast, color-blind-safe palette swap, large touch targets, RTL-ready layouts |

## Repository layout

```
StudioOneMobile/
├── app/                     Application shell: DI, navigation, services, manifest
├── audio/                   C++ NDK engine (Oboe) + Kotlin JNI facade + analysis DSP
├── midi/                    Android MIDI + BLE MIDI input, parser, arpeggiator
├── build-logic/             Gradle convention plugins (shared build config)
├── core/
│   ├── common/              Dispatchers, Result, audio math
│   ├── domain/              Entities, use cases, EditorSession, tempo map, undo
│   ├── data/                Repository impls, offline-first sync engine
│   ├── database/            Room schema (projects/tracks/clips/outbox/versions)
│   ├── datastore/           Settings (DataStore)
│   ├── network/             Supabase auth/storage/postgrest + realtime + CRDT
│   ├── designsystem/        Material 3 theme + Knob/Fader/LevelMeter components
│   └── ui/                  Permissions, device capability probe
├── feature/
│   ├── home/                Project list + template picker
│   ├── editor/              Arrange timeline + transport
│   ├── recorder/            Capture screen + engine config
│   ├── pianoroll/           MIDI note editing
│   ├── mixer/               Channel strips, master metering, FX sheet
│   ├── instruments/         Pads, keys, step sequencer
│   ├── effects/             Parameter editor + preset browser
│   ├── library/             Loop browser, import, preview
│   ├── collab/              Live session screen
│   └── settings/            Audio/appearance/accessibility settings
├── docs/                    Architecture, audio engine, collaboration, ADRs
├── fastlane/                Play Store release lanes
├── scripts/                 Sample-pack generator (stdlib-only WAV synthesis)
└── .github/workflows/       CI + release pipelines
```

## Requirements

- Android Studio (latest stable) or command-line SDK
- JDK 17
- Android SDK: `minSdk 26`, `compileSdk 35`, NDK `27.1.12297006`, CMake `3.22.1`
- Gradle `8.11.1` (wrapper generated on first open, or use `gradle` via SDK manager)

## Build

```bash
# Clone and open in Android Studio, or from CLI:
gradle :app:assembleDebug            # debug APK
gradle testDebugUnitTest             # Kotlin/JVM unit tests
gradle :app:lintDebug                # lint
```

### Native engine tests (host)

```bash
cd audio/src/main/cpp
cmake -B build -DBUILD_AUDIO_TESTS=ON
cmake --build build && cd build && ctest --output-on-failure
```

### Demo content

```bash
python3 scripts/generate_sample_pack.py --out samples/generated
```

Creates 120 BPM drum/bass/keys loops and one-shots (pure-Python synthesis,
no binaries committed).

## Backend configuration

Supabase is the backend (Auth, Postgres, Storage, Realtime). Supply keys via
`local.properties` (never commit):

```properties
studioone.supabase.url=https://<project-ref>.supabase.co
studioone.supabase.anonKey=<anon key>
```

### Required schema (Postgres)

```sql
create table projects (
  id uuid primary key,
  name text not null,
  description text default '',
  owner_id uuid references auth.users,
  tempo double precision default 120,
  time_sig_num int default 4,
  time_sig_den int default 4,
  musical_key text,
  sample_rate int default 44100,
  version int default 1,
  collab_session_id uuid,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create table project_snapshots (
  project_id uuid references projects(id),
  version int,
  payload_json jsonb not null,
  created_at timestamptz default now(),
  primary key (project_id, version)
);

create table collab_ops (
  id bigint generated always as identity primary key,
  project_id uuid not null,
  op_json jsonb not null,
  lamport bigint not null,
  created_at timestamptz default now()
);
```

Enable Realtime for `collab_ops` and configure storage buckets
`samples`, `stems`, `exports` with authenticated access policies.

## Deployment

Release builds run through Fastlane (see `fastlane/Fastfile`):

```bash
fastlane internal    # build AAB + upload to Play internal track
fastlane promote     # staged rollout internal -> production
```

Signing: `keystore.properties` (see `.github/workflows/release.yml` for the
CI secret contract). Tag `v*` pushes trigger the release workflow.

## Architecture in 60 seconds

- **Clean Architecture + MVVM/MVI.** `core:domain` is pure Kotlin (entities,
  use cases, `EditorSession`, `TempoMap`, `UndoRedoManager`); `core:data`
  implements repositories with Room + offline-first sync; features expose
  MVI view-models over `StateFlow`.
- **Realtime-safe audio.** A single C++ engine (`audio/src/main/cpp`) owns
  the Oboe stream. No allocations, no locks on the callback: SPSC ring
  buffers, parameter FIFOs, preallocated scratch buffers. Full rules in
  [`docs/AUDIO_ENGINE.md`](docs/AUDIO_ENGINE.md).
- **Offline-first collaboration.** Every local write is applied to Room and
  enqueued in a sync outbox; live sessions exchange CRDT ops over Supabase
  Realtime with Lamport-clock LWW merge. Design in
  [`docs/COLLABORATION.md`](docs/COLLABORATION.md).

## Testing

| Layer | Tools | Location |
| --- | --- | --- |
| Domain (tempo map, quantizer, arpeggiator, chords, undo) | JUnit + Truth | `core/domain/src/test` |
| CRDT merge + op serialization | JUnit + Truth | `core/network/src/test` |
| MIDI parser | JUnit + Truth | `midi/src/test` |
| BPM/key analysis | JUnit + Truth (synthesized audio) | `audio/src/test` |
| ViewModels | Turbine + coroutines-test | `feature/*/src/test` |
| Native DSP | GoogleTest via CMake | `audio/src/main/cpp/tests` |
| UI/navigation | Compose UI test, Espresso | `app/src/androidTest` |

## Contributing

1. Branch from `main`; keep modules' dependency direction (feature → core,
   never the reverse).
2. Any new effect/instrument: add a `ParamSpec` entry in `EffectCatalog`,
   a native factory case in `effects.cpp`, and unit tests.
3. Audio-thread changes must honor `docs/AUDIO_ENGINE.md` (no allocs/locks).
4. Run `gradle testDebugUnitTest` and native tests before opening a PR.

## License

MIT — see [LICENSE](LICENSE).
