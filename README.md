# StudioOne Mobile

**A production-grade mobile DAW for Android** — multitrack recording, MIDI,
virtual instruments, pro effects, mixing/mastering, real-time collaboration,
and social sharing. Built for musicians, producers, and podcasters on devices
from mid-range phones to foldables and tablets.

> Working title: **StudioOne Mobile** · Kotlin · Jetpack Compose (M3) ·
> C++17 NDK audio engine (Oboe/AAudio + OpenSL ES fallback) · Supabase backend ·
> Clean Architecture + MVVM/MVI · Hilt · Coroutines/Flow

---

## Feature overview

| Area | Capabilities |
|---|---|
| **Account** | Email, Google, Facebook, Apple (PKCE), offline Guest mode with later upgrade; profile, genres, links |
| **Projects** | Templates (beat/song/podcast/live/remix/vocal), autosave, version history, archive/favorite, offline-first cloud sync |
| **Arranger** | Unlimited-model tracks (device-class pooled), zoomable timeline, waveform clips, trim/split/fades/crossfades/loop/reverse/normalize, drag & drop, ripple, automation lanes (volume/pan/sends/FX/tempo), punch & loop recording, count-in, freeze/bounce, undo/redo with coalescing |
| **Recording** | <10 ms round-trip on MMAP-capable devices, 44.1/48/96 kHz, 16/24/32-bit, buffers 64–512, input gain/gate/limiter + monitoring FX, multitrack simultaneous capture (hardware permitting), take management |
| **MIDI** | Piano roll (draw/select/erase, snap 1/1–1/64 & triplets, scale lock, velocity lane), quantize/swing/humanize/legato/strum, chord tools, arpeggiator, MIDI learn, USB MIDI, Bluetooth LE MIDI, MPE |
| **Instruments** | SP-16 drum machine (pads + step sequencer + choke groups), multisample sampler, polyBLEP subtractive/FM/wavetable synths, factory presets + macro controls |
| **Effects** | Parametric 8-band EQ, compressor/expander/limiter/gate/de-esser, Freeverb-style reverb, stereo & ping-pong delay, chorus/flanger/phaser/tremolo/autopan/auto-filter, distortion/overdrive/bitcrusher/amp-sim/tape saturation, pitch shift/time stretch, gain/width utility — chains, sends, buses, wet-mix parallel, presets |
| **Mixer** | Channel strips (fader/pan/mute/solo/arm/IO), inserts + 4 sends per strip, master bus with look-ahead limiter, **ITU-R BS.1770-4 loudness (M/S/I, LRA, true peak)** and 64-band spectrum analyzer |
| **Library** | Royalty-free loops/one-shots/MIDI packs, BPM & key detection (local Goertzel/autocorrelation + cloud refinement), search/filter/preview, offline packs, SAF/MediaStore import |
| **Collaboration** | Real-time co-editing over a **CRDT** (HLC-stamped LWW fields, add-wins OR-sets), durable op outbox, comments anchored to the timeline, scoped share links, fork/remix, conflict review |
| **Social** | Feed (For You/Following/Trending/New/Challenges), profiles/follows/likes/comments, publish with metadata, embeddable player, DM schema |
| **Export** | WAV/FLAC/AAC device-side, MP3/OGG via LAME module or cloud render, stems, mixdown/master with loudness targets, MediaStore tagging, share sheet, cloud export jobs |
| **Monetization** | Free/Pro/Pro+ tiers with server-verified Play Billing, sound-pack IAPs, non-intrusive ad placements (never during creation), artist tips & paid posts |
| **Accessibility** | TalkBack semantics on every control, high-contrast + color-blind palettes, large touch targets, RTL, per-app languages |

## Repository layout

```
├── app/                        # Application module: nav host, DI root, media session, manifest
├── build-logic/                # Gradle convention plugins (module types, JNI, Hilt, Compose)
├── gradle/libs.versions.toml   # Single version catalog
├── core/
│   ├── model/                  # Pure-Kotlin domain language (Project, Track, Clip, Op envelopes…)
│   ├── common/                 # Dispatchers, DataResult, retry/backoff, TimeMath, device profile
│   ├── audio/                  # C++ engine (cpp/) + Kotlin controller/JNI/feeder/decoder
│   ├── midi/                   # USB/BLE MIDI, parser, quantizer, MIDI learn, engine router
│   ├── realtime/               # CRDT kernel (HLC, LWW, OR-Set), collab socket, session engine
│   ├── database/               # Room schema (document-store projects, outbox, op log, caches)
│   ├── datastore/              # Typed preferences (audio settings, UI, onboarding, consent)
│   ├── network/                # Retrofit/OkHttp API surface + auth interceptor (Supabase)
│   ├── domain/                 # Use cases, repository contracts, EditorActions, UndoRedo, GridMath
│   ├── data/                   # Repository implementations, sync workers, export pipeline, billing
│   ├── designsystem/           # Theme, type, color-blind palettes, Knob/Fader/Meter components
│   ├── ui/                     # Shared canvas rendering (waveform, ruler, clip drawing)
│   └── analytics/              # Consent-gated analytics facade + Firebase sink
├── feature/                    # 14 feature modules (screens + MVI ViewModels only)
│   ├── auth/ onboarding/ projects/ arranger/ mixer/ pianoroll/
│   ├── instruments/ effects/ loops/ collab/ social/
│   └── export/ settings/ paywall/
├── backend/supabase/           # SQL migrations, RLS, realtime, edge functions, seed
├── assets → app/src/main/assets/factory   # Factory presets, project templates, starter pack manifest
├── docs/                       # Architecture & subsystem deep-dives (start here)
├── .github/workflows/          # CI (tests, native DSP tests, builds) + release (Fastlane/Supply)
└── fastlane/                   # Lanes: test, beta, promote, production rollout
```

**Dependency rule:** `feature → core → model`. Features never depend on each
other; `core:domain` never depends on Android. Enforced in CI
(`.github/workflows/ci.yml` module-graph check).

## Getting started

### Prerequisites

* Android Studio Ladybug+ (AGP 8.7, Kotlin 2.0)
* JDK 17
* Android SDK 35 + **NDK 27.1.12297006** + CMake 3.22.1
* (backend) Supabase CLI, Deno 2.x

### Build & run

```bash
git clone <repo> && cd MUSIC-EDITOR
# Backend endpoints (optional for offline-first dev):
echo 'S1_SUPABASE_URL="https://<your-project>.supabase.co"'  >> local.properties
echo 'S1_SUPABASE_ANON_KEY="<anon key>"'                     >> local.properties
./gradlew :app:assembleDebug        # first build fetches Oboe 1.9.0 via CMake
./gradlew installDebug
```

The app is fully functional offline as a guest; cloud features activate after
sign-in and backend configuration.

### Run tests

```bash
./gradlew test                      # JVM unit tests (all modules)
./gradlew connectedDebugAndroidTest # instrumented + Compose UI tests
cd core/audio/src/main/cpp/tests && cmake -B build && cmake --build build && ./build/s1_dsp_tests
```

### Deploy the backend

```bash
cd backend && supabase link --project-ref <ref>
supabase db push && supabase functions deploy
```

See [backend/README.md](backend/README.md) and [docs/API.md](docs/API.md).

## Documentation map

| Doc | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, layers, data flows, threading model |
| [AUDIO_ENGINE.md](docs/AUDIO_ENGINE.md) | Native engine: real-time contract, graph, latency, devices |
| [COLLABORATION.md](docs/COLLABORATION.md) | CRDT design, op protocol, offline merge, presence |
| [API.md](docs/API.md) | Every backend endpoint with request/response contracts |
| [MIDI.md](docs/MIDI.md) | USB/BLE MIDI, MPE, learn, clock |
| [EXPORT.md](docs/EXPORT.md) | Render pipeline, encoders, loudness targets |
| [SECURITY.md](docs/SECURITY.md) | Auth, RLS, encryption, GDPR compliance |
| [TESTING.md](docs/TESTING.md) | Test pyramid, what runs where, coverage goals |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | 60fps budget, startup, memory, battery |
| [DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) | Tokens, components, accessibility rules |
| [MONETIZATION.md](docs/MONETIZATION.md) | Tiers, billing security, ad policy |
| [PLAY_STORE.md](docs/PLAY_STORE.md) | Listing copy, asset specs, content rating |
| [PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) · [TERMS_OF_SERVICE.md](docs/TERMS_OF_SERVICE.md) | Legal templates |
| [ROADMAP.md](docs/ROADMAP.md) | Post-1.0 plan (plugin hosting, ARA, video, distribution) |

## Release process

1. Tag `vX.Y.Z` → `.github/workflows/release.yml` builds the signed AAB
   (upload keystore from CI secrets), runs Fastlane Supply to the **internal**
   track as a draft.
2. QA pass → `fastlane promote_beta` → staged production rollout (`fastlane production`, 10% start).
3. Crashlytics + Play vitals monitored; rollout halted on ANR/crash regression.

## License & attribution

* App code: proprietary (this repository).
* [Oboe](https://github.com/google/oboe) (Apache-2.0) — audio backend.
* Freeverb tuning constants — public domain algorithm, original implementation here.
* No third-party trademarks are used; BandLab is not affiliated with or
  referenced by this project beyond market positioning.
