// SPDX-License-Identifier: MIT
#include "AudioGraph.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace studioone::graph {

AudioGraph::AudioGraph() : recordRing_(1 << 20) {}

void AudioGraph::prepare(double sampleRate, int32_t maxFrames) {
    sampleRate_ = sampleRate;
    maxFrames_ = maxFrames;

    for (auto& buf : scratchA_) buf.assign(static_cast<size_t>(maxFrames), 0.f);
    for (auto& buf : busBuffer_) buf.assign(static_cast<size_t>(maxFrames), 0.f);
    for (auto& buf : masterBuffer_) buf.assign(static_cast<size_t>(maxFrames), 0.f);
    busMixBuffers_.assign(4, std::vector<float>(static_cast<size_t>(maxFrames) * kMaxChannels, 0.f));

    masterMeter_.prepare(sampleRate);
    for (auto& meter : trackMeters_) meter.prepare(sampleRate);
    for (auto& bus : buses_) bus.meter.prepare(sampleRate);

    for (auto& track : tracks_) {
        if (track.instrument) track.instrument->prepare(sampleRate);
        for (auto& effect : track.inserts) effect->prepare(sampleRate);
    }
    for (auto& bus : buses_) {
        for (auto& effect : bus.inserts) effect->prepare(sampleRate);
    }
    for (auto& effect : masterInserts_) effect->prepare(sampleRate);
}

// ---------------------------------------------------------------------------
// Realtime process
// ---------------------------------------------------------------------------

void AudioGraph::process(const float* const* in, float* const* out, int numChannels, int numFrames) noexcept {
    applyParams();

    const auto state = static_cast<TransportState>(transport_.state.load(std::memory_order_relaxed));
    const bool playing = state != TransportState::Stopped;
    int64_t framePos = transport_.playheadFrame.load(std::memory_order_relaxed);

    // Loop handling: wrap the transport within the loop range.
    const bool looping = transport_.loopEnabled.load(std::memory_order_relaxed);
    const int64_t loopEnd = transport_.loopEndFrame.load(std::memory_order_relaxed);

    // Clear output + scratch.
    for (int c = 0; c < numChannels; ++c) {
        std::memset(out[c], 0, sizeof(float) * numFrames);
    }

    const bool anySolo = std::any_of(tracks_.begin(), tracks_.end(),
                                     [](const TrackChannel& t) { return t.inUse && t.soloed; });

    for (auto& track : tracks_) {
        if (!track.inUse) continue;
        const bool audible = !track.muted && (!anySolo || track.soloed || track.armed);

        // Source render into scratch.
        for (int c = 0; c < numChannels; ++c) {
            std::memset(scratchA_[c].data(), 0, sizeof(float) * numFrames);
        }
        if (playing && audible) {
            float* ptrs[kMaxChannels];
            for (int c = 0; c < numChannels; ++c) ptrs[c] = scratchA_[c].data();
            for (const auto& clip : track.clips) {
                clip->render(ptrs, numChannels, numFrames, framePos);
            }
            if (track.instrument) track.instrument->render(ptrs, numChannels, numFrames);
        }

        // Insert chain.
        float* ptrs[kMaxChannels];
        for (int c = 0; c < numChannels; ++c) ptrs[c] = scratchA_[c].data();
        for (auto& effect : track.inserts) {
            effect->process(ptrs, numChannels, numFrames);
        }

        // Track meter tap.
        trackMeters_[track.id % kMaxTracks].process(ptrs, numChannels, numFrames);

        // Gain/pan (equal-power).
        float gainL, gainR;
        {
            const float pan = std::clamp(track.pan, -1.f, 1.f);
            const float angle = (pan + 1.f) * 0.25f * static_cast<float>(M_PI);
            gainL = std::cos(angle) * track.gain;
            gainR = std::sin(angle) * track.gain;
        }

        const bool isMaster = track.outputBusId == 0;
        for (int i = 0; i < numFrames; ++i) {
            const float l = scratchA_[0][i] * gainL;
            const float r = numChannels > 1 ? scratchA_[1][i] * gainR : scratchA_[0][i] * gainR;
            if (isMaster) {
                masterBuffer_[0][i] += l;
                if (numChannels > 1) masterBuffer_[1][i] += r;
            }
        }

        // Sends (simplified: summed into master FX buses when configured).
        for (int s = 0; s < kMaxSendsPerTrack; ++s) {
            if (track.sendLevels[s] <= 0.f || track.sendBusIds[s] == 0) continue;
            const int busIndex = static_cast<int>(track.sendBusIds[s] - busNodeBase());
            if (busIndex < 0 || busIndex >= static_cast<int>(busMixBuffers_.size())) continue;
            auto& busMix = busMixBuffers_[busIndex];
            for (int i = 0; i < numFrames; ++i) {
                busMix[i * kMaxChannels] += scratchA_[0][i] * track.sendLevels[s];
                busMix[i * kMaxChannels + 1] += (numChannels > 1 ? scratchA_[1][i] : scratchA_[0][i]) * track.sendLevels[s];
            }
        }
    }

    // FX buses -> master.
    for (size_t b = 0; b < buses_.size(); ++b) {
        auto& bus = buses_[b];
        if (!bus.inUse) continue;
        auto& busMix = busMixBuffers_[b < busMixBuffers_.size() ? b : 0];
        float* ptrs[kMaxChannels];
        for (int c = 0; c < numChannels; ++c) ptrs[c] = busMix.data() + c;  // interleaved view
        // Deinterleave into scratch for the effect chain.
        for (int c = 0; c < numChannels; ++c) {
            for (int i = 0; i < numFrames; ++i) busBuffer_[c][i] = busMix[i * kMaxChannels + c];
        }
        float* busPtrs[kMaxChannels];
        for (int c = 0; c < numChannels; ++c) busPtrs[c] = busBuffer_[c].data();
        for (auto& effect : bus.inserts) effect->process(busPtrs, numChannels, numFrames);
        bus.meter.process(busPtrs, numChannels, numFrames);
        for (int i = 0; i < numFrames; ++i) {
            masterBuffer_[0][i] += busBuffer_[0][i];
            if (numChannels > 1) masterBuffer_[1][i] += busBuffer_[1][i];
        }
        std::memset(busMix.data(), 0, sizeof(float) * numFrames * kMaxChannels);
    }

    // Master insert chain + metering.
    {
        float* ptrs[kMaxChannels];
        for (int c = 0; c < numChannels; ++c) ptrs[c] = masterBuffer_[c].data();
        for (auto& effect : masterInserts_) effect->process(ptrs, numChannels, numFrames);
        masterMeter_.process(ptrs, numChannels, numFrames);
        for (int c = 0; c < numChannels; ++c) {
            std::memcpy(out[c], masterBuffer_[c].data(), sizeof(float) * numFrames);
        }
    }

    // Metronome click (band-limited square pip on beat boundaries).
    if (playing && transport_.metronomeEnabled.load(std::memory_order_relaxed)) {
        const double bpm = transport_.tempo.load(std::memory_order_relaxed);
        const double beatsPerBuffer = numFrames * bpm / (60.0 * sampleRate_);
        const int startBeat = static_cast<int>(metronomePhase_);
        metronomePhase_ += beatsPerBuffer;
        const int endBeat = static_cast<int>(metronomePhase_);
        if (endBeat > startBeat && endBeat != lastBeat_) {
            // Render a 3ms 1kHz pip at the start of the buffer.
            const int pipLen = std::min(numFrames, static_cast<int>(sampleRate_ * 0.003));
            const float level = (endBeat % 4 == 1) ? 0.5f : 0.35f;
            for (int i = 0; i < pipLen; ++i) {
                const float env = 1.f - static_cast<float>(i) / pipLen;
                const float click = std::sin(2.f * static_cast<float>(M_PI) * 1000.f * i / sampleRate_) * level * env;
                out[0][i] += click;
                if (numChannels > 1) out[1][i] += click;
            }
        }
        lastBeat_ = endBeat;
    } else {
        metronomePhase_ = 0.0;
        lastBeat_ = -1;
    }

    // Record tap: copy input into the record ring for the writer thread.
    if (state == TransportState::Recording && in != nullptr) {
        // Interleave into a small stack-friendly staging then single write().
        // (Interleaving into the ring sample-by-sample is allocation-free.)
        for (int i = 0; i < numFrames; ++i) {
            for (int c = 0; c < numChannels; ++c) {
                const float s = in[c][i];
                recordRing_.write(&s, 1);
            }
        }
    }

    // Advance the transport.
    if (playing) {
        framePos += numFrames;
        if (looping && loopEnd > 0 && framePos >= loopEnd) {
            framePos = transport_.loopStartFrame.load(std::memory_order_relaxed);
        }
        transport_.playheadFrame.store(framePos, std::memory_order_relaxed);
    }
}

// ---------------------------------------------------------------------------
// Graph mutation (graph thread, stream paused)
// ---------------------------------------------------------------------------

uint32_t AudioGraph::addTrack(bool withInstrument, MidiInstrumentSource::Kind instrumentKind) {
    for (auto& track : tracks_) {
        if (track.inUse) continue;
        track = TrackChannel{};
        track.id = nextTrackId_++;
        track.inUse = true;
        if (withInstrument) {
            track.instrument = std::make_unique<MidiInstrumentSource>(instrumentKind);
            track.instrument->prepare(sampleRate_);
        }
        return track.id;
    }
    return 0;  // graph full
}

void AudioGraph::removeTrack(uint32_t trackId) {
    for (auto& track : tracks_) {
        if (track.id == trackId) {
            track.inUse = false;
            track.clips.clear();
            track.instrument.reset();
            track.inserts.clear();
            return;
        }
    }
}

void AudioGraph::addClipToTrack(uint32_t trackId, std::unique_ptr<AudioClipSource> clip) {
    for (auto& track : tracks_) {
        if (track.id == trackId) {
            track.clips.push_back(std::move(clip));
            return;
        }
    }
}

void AudioGraph::clearClips(uint32_t trackId) {
    for (auto& track : tracks_) {
        if (track.id == trackId) {
            track.clips.clear();
            return;
        }
    }
}

uint32_t AudioGraph::addBus() {
    BusChannel bus;
    bus.id = nextBusId_++;
    bus.inUse = true;
    bus.meter.prepare(sampleRate_);
    buses_.push_back(std::move(bus));
    return buses_.back().id;
}

void AudioGraph::addInsert(uint32_t nodeId, std::unique_ptr<dsp::EffectBase> effect) {
    effect->prepare(sampleRate_);
    if (nodeId == kMasterNodeId) {
        masterInserts_.push_back(std::move(effect));
        return;
    }
    if (nodeId >= busNodeBase()) {
        for (auto& bus : buses_) {
            if (bus.id == nodeId) {
                bus.inserts.push_back(std::move(effect));
                return;
            }
        }
        return;
    }
    for (auto& track : tracks_) {
        if (track.id == nodeId && track.inserts.size() < kMaxInsertsPerTrack) {
            track.inserts.push_back(std::move(effect));
            return;
        }
    }
}

void AudioGraph::setTrackBasic(uint32_t trackId, float gain, float pan, bool mute, bool solo, bool armed) {
    for (auto& track : tracks_) {
        if (track.id == trackId) {
            track.gain = gain;
            track.pan = pan;
            track.muted = mute;
            track.soloed = solo;
            track.armed = armed;
            return;
        }
    }
}

dsp::MeterSnapshot AudioGraph::trackMeterSnapshot(uint32_t trackId) const noexcept {
    return trackMeters_[trackId % kMaxTracks].snapshot();
}

void AudioGraph::applyParams() noexcept {
    paramFifo_.drain([this](uint32_t nodeId, uint32_t paramId, float value) {
        // Master chain params use paramId ranges partitioned by insert index.
        if (nodeId == kMasterNodeId) {
            const size_t insertIndex = paramId >> 8;
            if (insertIndex < masterInserts_.size()) {
                masterInserts_[insertIndex]->setParameter(paramId & 0xFF, value);
            }
            return;
        }
        if (nodeId >= busNodeBase()) {
            for (auto& bus : buses_) {
                if (bus.id == nodeId) {
                    const size_t insertIndex = paramId >> 8;
                    if (insertIndex < bus.inserts_.size()) {
                        bus.inserts_[insertIndex]->setParameter(paramId & 0xFF, value);
                    }
                    return;
                }
            }
            return;
        }
        for (auto& track : tracks_) {
            if (track.id != nodeId) continue;
            const size_t insertIndex = paramId >> 8;
            if (insertIndex < track.inserts.size()) {
                track.inserts[insertIndex]->setParameter(paramId & 0xFF, value);
            }
            return;
        }
    });
}

int32_t AudioGraph::totalInsertLatency(const std::vector<std::unique_ptr<dsp::EffectBase>>& chain) const noexcept {
    int32_t total = 0;
    for (const auto& effect : chain) total += effect->latencyFrames();
    return total;
}

}  // namespace studioone::graph
