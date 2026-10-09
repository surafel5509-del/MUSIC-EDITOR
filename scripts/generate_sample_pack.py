#!/usr/bin/env python3
"""Generates royalty-free demo WAV loops + one-shots for development.

Uses only the Python standard library (sine synthesis), so the repository
ships zero binary audio while still giving the timeline, waveform cache and
BPM/key detectors real material to work with.

Usage:
    python3 scripts/generate_sample_pack.py [--out samples/generated]
"""

import argparse
import math
import os
import struct
import wave

SAMPLE_RATE = 44100


def write_wav(path: str, frames: list[float], sample_rate: int = SAMPLE_RATE) -> None:
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        pcm = b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in frames
        )
        wav.writeframes(pcm)


def sine(freq: float, seconds: float, gain: float = 0.5, phase: float = 0.0) -> list[float]:
    n = int(seconds * SAMPLE_RATE)
    return [gain * math.sin(2 * math.pi * freq * i / SAMPLE_RATE + phase) for i in range(n)]


def envelope(frames: list[float], attack: float, release: float) -> list[float]:
    n = len(frames)
    a = max(1, int(attack * SAMPLE_RATE))
    r = max(1, int(release * SAMPLE_RATE))
    out = frames[:]
    for i in range(min(a, n)):
        out[i] *= i / a
    for i in range(min(r, n)):
        out[n - 1 - i] *= i / r
    return out


def silence(seconds: float) -> list[float]:
    return [0.0] * int(seconds * SAMPLE_RATE)


def mix_at(target: list[float], source: list[float], offset_seconds: float) -> None:
    offset = int(offset_seconds * SAMPLE_RATE)
    while len(target) < offset + len(source):
        target.append(0.0)
    for i, s in enumerate(source):
        target[offset + i] += s


def drum_loop(bpm: float, bars: int = 2) -> list[float]:
    beat = 60.0 / bpm
    total = silence(beat * 4 * bars)
    kick = envelope(sine(55, 0.18, 0.9), 0.001, 0.12)
    snare = envelope([max(-1.0, min(1.0, ((i * 7919) % 1000) / 500 - 1)) * math.exp(-i / 900)
                      for i in range(int(0.15 * SAMPLE_RATE))], 0.001, 0.05)
    hat = envelope([((i * 4351) % 1000) / 500 - 1 for i in range(int(0.04 * SAMPLE_RATE))], 0.0005, 0.02)
    for bar in range(bars):
        for b in range(4):
            mix_at(total, kick, (bar * 4 + b) * beat)
            if b in (1, 3):
                mix_at(total, snare, (bar * 4 + b) * beat)
            for eighth in range(2):
                mix_at(total, [h * 0.25 for h in hat], (bar * 4 + b + eighth * 0.5) * beat)
    return total


def bass_loop(bpm: float, bars: int = 2) -> list[float]:
    beat = 60.0 / bpm
    total = silence(beat * 4 * bars)
    pattern = [36, 36, 43, 36, 36, 41, 36, 34]  # C1 C1 G1 C1 C1 F1 C1 Bb0
    for i, midi in enumerate(pattern):
        freq = 440.0 * 2 ** ((midi - 69) / 12)
        note = envelope(sine(freq, beat * 0.45, 0.6), 0.005, 0.05)
        mix_at(total, note, i * beat * 0.5)
    return total


def keys_loop(bpm: float, bars: int = 2) -> list[float]:
    beat = 60.0 / bpm
    total = silence(beat * 4 * bars)
    chords = [[60, 63, 67], [58, 63, 67], [55, 60, 63], [56, 60, 65]]  # Cm Bb Gm Ab-ish
    for bar, chord in enumerate(chords * (bars // 2 + 1)):
        for midi in chord:
            freq = 440.0 * 2 ** ((midi - 69) / 12)
            tone = envelope(sine(freq, beat * 1.9, 0.18), 0.02, 0.3)
            mix_at(total, tone, bar * beat * 4)
    return total


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="samples/generated")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    bpm = 120.0
    write_wav(f"{args.out}/drum-loop-120.wav", drum_loop(bpm))
    write_wav(f"{args.out}/bass-loop-120.wav", bass_loop(bpm))
    write_wav(f"{args.out}/keys-loop-120.wav", keys_loop(bpm))
    write_wav(f"{args.out}/one-shot-kick.wav", envelope(sine(50, 0.25, 0.95), 0.001, 0.15))
    write_wav(f"{args.out}/one-shot-snare.wav", envelope(sine(190, 0.16, 0.7), 0.001, 0.08))
    write_wav(f"{args.out}/one-shot-c-sub.wav", envelope(sine(65.4, 0.6, 0.6), 0.005, 0.2))
    print(f"Wrote 6 files to {args.out}")


if __name__ == "__main__":
    main()
