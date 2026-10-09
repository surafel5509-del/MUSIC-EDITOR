// SPDX-License-Identifier: MIT
// The DSP graph: tracks (audio/MIDI) -> inserts -> sends -> buses -> master.
//
// Threading model:
//   - prepare()/mutators run on a background "graph" thread while the stream
//     is paused (see AudioEngine::withGraphLocked),
//   - process() runs exclusively on the realtime audio callback,
//   - parameter changes use the lock-free ParamFifo.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "../dsp/effect_base.h"
#include "../dsp/metering.h"
#include "../dsp/param_fifo.h"
#include "../dsp/ring_buffer.h"
#include "../engine/WavFile.h"
#include "../instruments/DrumSynth.h"
#include "../instruments/Sampler.h"
#include "../instruments/SubtractiveSynth.h"

namespace studioone::graph {

constexpr int kMaxChannels = 2;
constexpr int kMaxTracks = 64;
constexpr int kMaxInsertsPerTrack = 8;
constexpr int kMaxSendsPerTrack = 4;

enum class TransportState : int32_t { Stopped = 0, Playing = 1, Recording = 2 };

/** Sample-accurate transport shared by all sources. */
struct Transport {
    std::atomic<int64_t> playheadFrame{0};
    std::atomic<int32_t> state{static_cast<int32_t>(TransportState::Stopped)};
    std::atomic<bool> loopEnabled{false};
    std::atomic<int64_t> loopStartFrame{0};
    std::atomic<int64_t> loopEndFrame{0};
    std::atomic<double> tempo{120.0};
    std::atomic<bool> metronomeEnabled{false};
    std::atomic<int32_t> countInBeats{0};
};

/** A block of audio content rendered into a track. */
class AudioClipSource {
public:
    AudioClipSource(std::shared_ptr<engine::PcmBuffer> pcm, int64_t clipStartFrame,
                    int64_t sourceOffsetFrames, float gain, bool reversed)
        : pcm_(std::move(pcm)), clipStart_(clipStartFrame),
          sourceOffset_(sourceOffsetFrames), gain_(gain), reversed_(reversed) {}

    /** Renders `numFrames` of this clip starting at transport frame `at`. */
    void render(float* const* out, int numChannels, int numFrames, int64_t at) const noexcept {
        if (!pcm_ || pcm_->frames == 0) return;
        const int64_t clipEnd = clipStart_ + lengthFrames();
        if (at >= clipEnd || at + numFrames <= clipStart_) return;

        const float* src = pcm_->interleaved.data();
        const int srcChannels = pcm_->channels;
        for (int i = 0; i < numFrames; ++i) {
            const int64_t frame = at + i;
            if (frame < clipStart_ || frame >= clipEnd) continue;
            int64_t readPos = frame - clipStart_ + sourceOffset_;
            if (reversed_) readPos = pcm_->frames - 1 - readPos;
            if (readPos < 0 || readPos >= pcm_->frames) continue;
            for (int c = 0; c < numChannels; ++c) {
                const int srcCh = std::min(c, srcChannels - 1);
                out[c][i] += src[readPos * srcChannels + srcCh] * gain_;
            }
        }
    }

    int64_t clipStart() const noexcept { return clipStart_; }
    int64_t lengthFrames() const noexcept { return pcm_ ? pcm_->frames : 0; }

private:
    std::shared_ptr<engine::PcmBuffer> pcm_;
    int64_t clipStart_;
    int64_t sourceOffset_;
    float gain_;
    bool reversed_;
};

/** Instrument source fed by MIDI events queued from the UI thread. */
class MidiInstrumentSource {
public:
    enum class Kind { SubtractiveSynth, Sampler, DrumSynth };

    explicit MidiInstrumentSource(Kind kind) : kind_(kind) {}

    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        synth_.prepare(sampleRate);
        sampler_.prepare(sampleRate);
        drums_.prepare(sampleRate);
    }

    /** UI thread: queue a MIDI channel message. */
    void enqueueEvent(uint8_t status, uint8_t d1, uint8_t d2) noexcept {
        const uint32_t packed = status | (d1 << 8) | (d2 << 16);
        midiIn_.write(&packed, 1);
    }

    /** RT: drain queued events whose sample offset falls in this buffer. */
    void render(float* const* out, int numChannels, int numFrames) noexcept {
        uint32_t packed;
        while (midiIn_.read(&packed, 1) == 1) {
            const uint8_t status = packed & 0xF0;
            const uint8_t key = (packed >> 8) & 0x7F;
            const uint8_t vel = (packed >> 16) & 0x7F;
            if (status == 0x90 && vel > 0) noteOn(key, vel);
            else if (status == 0x80 || (status == 0x90 && vel == 0)) noteOff(key);
        }
        float mono[2048];
        if (numFrames > 2048) numFrames = 2048;  // bounded by stream framesPerCallback
        std::fill(mono, mono + numFrames, 0.f);
        switch (kind_) {
            case Kind::SubtractiveSynth: synth_.render(mono, numFrames); break;
            case Kind::Sampler: sampler_.render(mono, numFrames); break;
            case Kind::DrumSynth: drums_.render(mono, numFrames); break;
        }
        for (int c = 0; c < numChannels; ++c) {
            for (int i = 0; i < numFrames; ++i) out[c][i] += mono[i];
        }
    }

    instruments::Sampler& sampler() { return sampler_; }
    instruments::SubtractiveSynth& synth() { return synth_; }

private:
    void noteOn(int key, int vel) noexcept {
        switch (kind_) {
            case Kind::SubtractiveSynth: synth_.noteOn(key, vel); break;
            case Kind::Sampler: sampler_.noteOn(key, vel); break;
            case Kind::DrumSynth: drums_.noteOn(key, vel); break;
        }
    }
    void noteOff(int key) noexcept {
        switch (kind_) {
            case Kind::SubtractiveSynth: synth_.noteOff(key); break;
            case Kind::Sampler: sampler_.noteOff(key); break;
            case Kind::DrumSynth: drums_.noteOff(key); break;
        }
    }

    Kind kind_;
    double sampleRate_{44100.0};
    dsp::RingBuffer<uint32_t> midiIn_{1024};
    instruments::SubtractiveSynth synth_;
    instruments::Sampler sampler_;
    instruments::DrumSynth drums_;
};

/** One track channel: source -> insert chain -> gain/pan -> bus send. */
struct TrackChannel {
    uint32_t id{0};
    bool inUse{false};
    float gain{0.8f};
    float pan{0.f};
    bool muted{false};
    bool soloed{false};
    bool armed{false};
    uint32_t outputBusId{0};  // 0 = master

    std::vector<std::unique_ptr<AudioClipSource>> clips;
    std::unique_ptr<MidiInstrumentSource> instrument;
    std::vector<std::unique_ptr<dsp::EffectBase>> inserts;
    float sendLevels[kMaxSendsPerTrack]{};
    uint32_t sendBusIds[kMaxSendsPerTrack]{};
};

/** A bus: mix of routed tracks + its own insert chain. */
struct BusChannel {
    uint32_t id{0};
    bool inUse{false};
    std::vector<std::unique_ptr<dsp::EffectBase>> inserts;
    dsp::Meter meter;
};

class AudioGraph {
public:
    AudioGraph();

    /** Called off the RT thread before the stream starts. */
    void prepare(double sampleRate, int32_t maxFrames);

    /** The realtime entry point. `in` may be null for output-only streams. */
    void process(const float* const* in, float* const* out, int numChannels, int numFrames) noexcept;

    // ---- Graph mutation (graph thread only, stream paused) ----------------
    uint32_t addTrack(bool withInstrument, MidiInstrumentSource::Kind instrumentKind);
    void removeTrack(uint32_t trackId);
    void addClipToTrack(uint32_t trackId, std::unique_ptr<AudioClipSource> clip);
    void clearClips(uint32_t trackId);
    uint32_t addBus();
    void addInsert(uint32_t nodeId, std::unique_ptr<dsp::EffectBase> effect);
    void setTrackBasic(uint32_t trackId, float gain, float pan, bool mute, bool solo, bool armed);

    // ---- Parameter updates (any thread; lock-free) ------------------------
    void postParam(uint32_t nodeId, uint32_t paramId, float value) noexcept {
        paramFifo_.post(nodeId, paramId, value);
    }

    /** Routes a MIDI channel message to a track's instrument (lock-free). */
    bool sendMidi(uint32_t trackId, uint8_t status, uint8_t d1, uint8_t d2) noexcept {
        for (auto& track : tracks_) {
            if (track.id == trackId && track.inUse && track.instrument) {
                track.instrument->enqueueEvent(status, d1, d2);
                return true;
            }
        }
        return false;
    }

    Transport& transport() noexcept { return transport_; }
    dsp::MeterSnapshot masterMeterSnapshot() const noexcept { return masterMeter_.snapshot(); }
    dsp::MeterSnapshot trackMeterSnapshot(uint32_t trackId) const noexcept;

    /** Recording: audio-thread tap fills this ring; a writer thread drains. */
    dsp::RingBuffer<float>& recordRing() noexcept { return recordRing_; }

    /** Node id helper: tracks 1..511, buses 512..1023, master 1024. */
    static constexpr uint32_t kMasterNodeId = 1024;
    static constexpr uint32_t busNodeBase() { return 512; }

private:
    void applyParams() noexcept;
    int32_t totalInsertLatency(const std::vector<std::unique_ptr<dsp::EffectBase>>& chain) const noexcept;

    double sampleRate_{44100.0};
    int32_t maxFrames_{512};

    Transport transport_;
    dsp::ParamFifo paramFifo_;

    std::array<TrackChannel, kMaxTracks> tracks_;
    std::vector<BusChannel> buses_;

    std::vector<std::unique_ptr<dsp::EffectBase>> masterInserts_;
    dsp::Meter masterMeter_;
    std::array<dsp::Meter, kMaxTracks> trackMeters_;

    dsp::RingBuffer<float> recordRing_;

    // Preallocated scratch buffers: zero allocation in process().
    std::vector<float> scratchA_[kMaxChannels];
    std::vector<float> busBuffer_[kMaxChannels];
    std::vector<float> masterBuffer_[kMaxChannels];
    std::vector<std::vector<float>> busMixBuffers_;

    // Metronome state (RT).
    double metronomePhase_{0.0};
    int lastBeat_{-1};

    uint32_t nextTrackId_{1};
    uint32_t nextBusId_{busNodeBase()};
};

}  // namespace studioone::graph
