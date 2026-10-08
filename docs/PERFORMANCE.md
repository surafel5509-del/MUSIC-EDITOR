# Performance & Reliability

## Frame budget (60 fps = 16.6 ms)
* Arranger draws culled clips only (visible frame window), waveform from peak
  pyramids at ≤ 4 buckets/px, one vertical span per 2 px — no per-sample work.
* Meters/spectrum poll at 30/23 Hz via lock-free triple buffers; recomposition
  is scoped to meter components (`derivedStateOf` + state hoisting).
* Canvas draw lambdas hoist theme colors (DrawScope is not @Composable).

## Audio budget (128-frame block @48k = 2.67 ms)
Target: ≤ 40 % CPU of one little core for 32 active strips × ≤ 4 FX each on a
mid-range SoC (SD 6-series class). Techniques: pooled buffers, branch-light
inner loops, denormal bias, per-block (not per-sample) coefficient updates,
FFT analysis every 4th window, meters published every 32 samples.

## Startup (< 300 ms to first frame, mid-range)
* Engine start is lazy (first editor screen), streams open async.
* MIDI discovery, library refresh, analytics consent — deferred/idle-gated.
* Baseline Profiles generated in CI (`:baselineprofile` macrobenchmark) and
  shipped via Play; profileinstaller dependency included.

## Memory
* Native pools: LOW/MID/HIGH device classes → 32/64/96 strips; feed rings
  0.5 s/strip; sample pool 512 slots (content-sized). Worst-case native heap
  ≈ 60 MB (HIGH) — bounded at init, never grows at runtime.
* PCM decode cache LRU 512 MB (disk); peak pyramids cached in Room.
* `onTrimMemory`/`onLowMemory`: stop engine, evict caches, release Media3.

## Battery
* Telemetry loop 30 Hz only while mixer/arranger visible (WhileSubscribed).
* Socket pulls 5 s while a session is open; WorkManager refreshes are
  unmetered+idle only. Metronome/click synthesis is O(1) per beat.

## Reliability
* Crashlytics (consent-gated) + Timber breadcrumbs; native crashes symbolized
  in CI (ndk-stack artifacts uploaded per build).
* Record durability: streaming WAV + 0.5 s flush + take overruns counted.
* Autosave 20 s debounce + save on backgrounding; version history prunes per
  tier. Offline ops never lost (Room outbox before network).
* ANR guards: no main-thread I/O (StrictMode fatal in debug), Room on IO
  dispatcher, billing/mediacodec off-main.
