# Export & Render Pipeline

## Paths

1. **Device offline render** (`core:data/export/OfflineRenderer`): clip-accurate
   mixdown of audio tracks (gain, fades, pan, mute/solo, stems via track
   filter) into `WavEncoder` (16/24/32-bit PCM + float) or `MediaCodecEncoder`
   (AAC-LC with ADTS framing, FLAC). Constant memory — block streaming.
2. **Native graph render mode** (1.1): the same C++ graph pumped faster than
   realtime for full FX/instrument fidelity bounces and track freeze; uses the
   Recorder writer threads. Contract designed, engine hooks reserved.
3. **Cloud render** (`export-render` edge fn + worker): premium formats on
   constrained devices, publishing flows, and video-muxing exports. Entitlement
   checked server-side (402 on free-tier attempts).

## Formats & licensing
WAV/FLAC/AAC/OGG device-side; **MP3 requires LAME (LGPL)** — shipped as a
dynamically-loaded native module in release builds with attribution, or
routed to the cloud job. This keeps the core app license clean.

## Loudness
Master exports measure ITU-R BS.1770-4 (native `LoudnessMeter`) and offer
one-tap targets: −14 LUFS (Spotify/YouTube), −16 (podcasts), −9 (club).
True-peak ceiling −1 dBTP enforced by the master limiter during render.

## Stems & metadata
Stem export renders each selected track soloed through the same path. ID3/
VORBIS/RIFF-INFO tags written from `AudioMetadata` (title, artist, BPM via
TKEY-style comment, artwork when container supports). MediaStore registration
puts exports in system music apps under `Music/StudioOne`.
