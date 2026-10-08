// SPDX-License-Identifier: MIT
// Multisample sampler instrument: region lookup, linear interpolation,
// ADSR, choke groups (for drums) and voice stealing.
#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

#include "../dsp/envelope.h"

namespace studioone::instruments {

/** One decoded sample region (mono float PCM). */
struct SampleRegion {
    std::vector<float> data;
    int sampleRate{44100};
    int rootKey{60};
    int lowKey{0};
    int highKey{127};
    int chokeGroup{0};   // 0 = none; same-group notes cut each other
    double gain{1.0};
};

class Sampler {
public:
    static constexpr int kMaxVoices = 24;

    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        for (auto& v : voices_) v.env.prepare(sampleRate);
    }

    /** Adds a region; takes ownership of sample data. Regions should be
     *  added sorted by rootKey for cheap lookup. Called off the RT thread. */
    void addRegion(SampleRegion region) {
        regions_.push_back(std::move(region));
    }

    void noteOn(int key, int velocity) noexcept {
        const SampleRegion* region = findRegion(key);
        if (!region) return;
        // Steal the oldest releasing/idle voice, else the oldest active.
        int victim = -1;
        for (int i = 0; i < kMaxVoices; ++i) {
            if (voices_[i].env.isIdle()) { victim = i; break; }
        }
        if (victim < 0) {
            for (int i = 0; i < kMaxVoices; ++i) {
                if (voices_[i].env.isReleasing()) { victim = i; break; }
            }
        }
        if (victim < 0) victim = stealIndex_++ % kMaxVoices;

        // Choke: release any sounding voice in the same choke group.
        if (region->chokeGroup != 0) {
            for (auto& v : voices_) {
                if (v.active && v.chokeGroup == region->chokeGroup) v.env.noteOff();
            }
        }

        auto& v = voices_[victim];
        v.active = true;
        v.region = region;
        v.key = key;
        v.chokeGroup = region->chokeGroup;
        v.rate = std::pow(2.0, (key - region->rootKey) / 12.0) *
                 (static_cast<double>(region->sampleRate) / sampleRate_);
        v.position = 0.0;
        v.velocityGain = velocity / 127.0;
        v.env.noteOn();
    }

    void noteOff(int key) noexcept {
        for (auto& v : voices_) {
            if (v.active && v.key == key) v.env.noteOff();
        }
    }

    void allNotesOff() noexcept {
        for (auto& v : voices_) v.env.noteOff();
    }

    /** Renders mono into out[0..numFrames). RT-safe: no allocation. */
    void render(float* out, int numFrames) noexcept {
        for (auto& v : voices_) {
            if (!v.active || v.region == nullptr) continue;
            const auto& data = v.region->data;
            if (data.empty()) { v.active = false; continue; }
            const double last = static_cast<double>(data.size() - 1);
            for (int i = 0; i < numFrames; ++i) {
                if (v.env.isIdle()) { v.active = false; break; }
                const double env = v.env.next();
                const size_t idx = static_cast<size_t>(v.position);
                if (idx >= data.size() - 1) { v.active = false; break; }
                const double frac = v.position - idx;
                const double sample = data[idx] + frac * (data[idx + 1] - data[idx]);
                out[i] += static_cast<float>(sample * env * v.velocityGain * v.region->gain);
                v.position += v.rate;
                if (v.position > last) { v.active = false; break; }
            }
        }
    }

    int activeVoiceCount() const noexcept {
        int count = 0;
        for (const auto& v : voices_) if (v.active) ++count;
        return count;
    }

private:
    const SampleRegion* findRegion(int key) const noexcept {
        const SampleRegion* best = nullptr;
        int bestDistance = 1000;
        for (const auto& r : regions_) {
            if (key >= r.lowKey && key <= r.highKey) return &r;
            const int distance = key < r.lowKey ? r.lowKey - key : key - r.highKey;
            if (distance < bestDistance) { bestDistance = distance; best = &r; }
        }
        return best;  // fall back to nearest region
    }

    struct Voice {
        bool active{false};
        const SampleRegion* region{nullptr};
        int key{0};
        int chokeGroup{0};
        double position{0.0};
        double rate{1.0};
        double velocityGain{1.0};
        studioone::dsp::AdsrEnvelope env;
    };

    double sampleRate_{44100.0};
    std::vector<SampleRegion> regions_;
    std::array<Voice, kMaxVoices> voices_;
    int stealIndex_{0};
};

}  // namespace studioone::instruments
