# Roadmap (post-1.0)

## 1.1 — Pro audio depth
* Native offline bounce through the live graph (full-FX export/freeze on-device)
* Convolution reverb + cabinet IR loader (FFT partitioned convolution)
* True 4-op FM synth & wavetable scanning (shared voice infra exists)
* WSOLA/phase-vocoder time-stretch replacing granular for clip stretch
* MIDI peripheral mode (phone as controller), Ableton Link
* Comp lanes (takes UI) on recorded tracks

## 1.2 — Creator economy
* Distribution (DSP delivery) for Pro+ with ISRC/UPC flows
* Stripe Connect payouts for tips & paid posts
* Play RTDN webhook for real-time refund/revocation
* Challenges platform ops (entry validation, voting)

## 1.3 — Platform
* Plugin hosting sandbox (FxUnit ABI over isolated process + shared-memory rings)
* ARA-style region effects, video track (Media3 compositing) for content creators
* Desktop companion (KMP: core:model/realtime/domain are already JVM-pure)
* Widget: quick-record + transport control

## Continuous
* Loudness-normalized monitoring references, room correction assist
* AI assist (opt-in, on-device): auto-gain staging, vocal tuning preview,
  stem separation for remix imports — always destructive-edit-free.
