#include "AudioGraph.h"
#include "../engine/Transport.h"
#include "../engine/Recorder.h"
#include "../instrument/VoiceManager.h"
#include "../instrument/SamplePool.h"
#include "../fx/Compressor.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace s1::audio::graph {

using namespace math;
using engine::ReclaimQueue;

namespace {
constexpr float kFeedRingSeconds = 0.5f; // per-strip playback feeder depth
constexpr float kSqrtTwo = 1.41421356237f; // constant-power pan normalization
}

// ── StripMeters ──────────────────────────────────────────────────────────────

void StripMeters::init(float sr) {
    peakL.setParams(sr, 300.f); peakR.setParams(sr, 300.f);
    rmsL.setParams(sr, 100.f); rmsR.setParams(sr, 100.f);
}

void StripMeters::process(const float* l, const float* r, FrameCount n) {
    float pl = 0.f, pr = 0.f, sl = 0.f, sr2 = 0.f;
    for (FrameCount i = 0; i < n; ++i) {
        pl = peakL.process(l[i]);
        pr = peakR.process(r[i]);
        sl = rmsL.process(l[i]);
        sr2 = rmsR.process(r[i]);
    }
    peakLin.store(pl, std::memory_order_relaxed);
    peakRin.store(pr, std::memory_order_relaxed);
    rmsLin.store(sl, std::memory_order_relaxed);
    rmsRin.store(sr2, std::memory_order_relaxed);
    if (pl > 1.f) clipL.store(true, std::memory_order_relaxed);
    if (pr > 1.f) clipR.store(true, std::memory_order_relaxed);
}

void StripMeters::reset() {
    peakL.reset(); peakR.reset(); rmsL.reset(); rmsR.reset();
    peakLin.store(0.f); peakRin.store(0.f); rmsLin.store(0.f); rmsRin.store(0.f);
    clipL.store(false); clipR.store(false);
}

// ── AudioGraph lifecycle ─────────────────────────────────────────────────────

AudioGraph::~AudioGraph() {
    for (auto& strip : strips_) {
        for (auto& lane : strip.lanes) {
            std::free(lane.points);
            lane.points = nullptr;
        }
    }
    std::free(masterSumL_); std::free(masterSumR_);
    std::free(masterScratchL_); std::free(masterScratchR_);
    std::free(inScratchL_); std::free(inScratchR_);
    std::free(monoScratch_);
    std::free(monitorSumL_); std::free(monitorSumR_);
    std::free(monFxL_); std::free(monFxR_);
    std::free(metronomeL_); std::free(metronomeR_);
}

void AudioGraph::init(const EngineConfig& config, int32_t maxStrips, int32_t maxInstrumentStrips) {
    config = config;
    sr_ = static_cast<float>(config.sampleRate);
    maxStrips_ = std::min(maxStrips, static_cast<int32_t>(kMaxTracks));

    fxPool_.init(sr_);

    // Master buffers.
    const size_t blockFloats = kMaxFramesPerBlock;
    masterSumL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    masterSumR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    masterScratchL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    masterScratchR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    inScratchL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    inScratchR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    monoScratch_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));

    masterInserts_.init(&fxPool_, sr_);
    masterLimiter_ = createFxUnit(kPluginLimiter);
    if (masterLimiter_) masterLimiter_->init(sr_);
    masterGainDb_.snap(0.f);
    masterMeters_.init(sr_);
    loudness_.init(sr_);
    spectrum_.init(sr_, 2048, kSpectrumBands);

    // Buses: 1 master-summing bus is implicit; pool covers subgroup/aux returns.
    buses_.resize(kMaxBuses);
    for (int i = 0; i < kMaxBuses; ++i) {
        auto& b = buses_[i];
        b.handle = 1000 + i;
        b.sumL = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
        b.sumR = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
        b.inserts.init(&fxPool_, sr_);
        b.gainDb.snap(0.f);
        b.pan.snap(0.f);
        b.meters.init(sr_);
    }

    // Strips + per-strip pools.
    const size_t feedFloats = static_cast<size_t>(kFeedRingSeconds * config.sampleRate);
    const size_t feedPow2 = static_cast<size_t>(nextPow2(static_cast<int>(feedFloats)));
    strips_.resize(maxStrips_);
    voiceManagers_.reserve(maxInstrumentStrips);
    for (int i = 0; i < maxStrips_; ++i) {
        auto& s = strips_[i];
        s.handle = -1;
        s.scratchL = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
        s.scratchR = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
        s.pdcStorageL = static_cast<float*>(std::calloc(kMaxPdcFrames + blockFloats, sizeof(float)));
        s.pdcStorageR = static_cast<float*>(std::calloc(kMaxPdcFrames + blockFloats, sizeof(float)));
        s.feedStorageL = static_cast<float*>(std::calloc(feedPow2, sizeof(float)));
        s.feedStorageR = static_cast<float*>(std::calloc(feedPow2, sizeof(float)));
        s.feedL.init(s.feedStorageL, feedPow2);
        s.feedR.init(s.feedStorageR, feedPow2);
        s.pdcL.init(s.pdcStorageL, kMaxPdcFrames + static_cast<FrameCount>(blockFloats));
        s.pdcR.init(s.pdcStorageR, kMaxPdcFrames + static_cast<FrameCount>(blockFloats));
        s.inserts.init(&fxPool_, sr_);
        s.monitorFx.init(&fxPool_, sr_);
        s.gainDb.snap(0.f); s.pan.snap(0.f); s.width.snap(1.f); s.inputGainDb.snap(0.f);
        for (auto& send : s.sends) { send.levelDb.snap(kSilenceDb); send.pan.snap(0.f); }
        s.meters.init(sr_);
    }

    // Instrument voice managers (pre-allocated; assigned to strips on demand).
    for (int i = 0; i < maxInstrumentStrips; ++i) {
        auto vm = std::make_unique<instrument::VoiceManager>();
        vm->init(sr_, kMaxVoices);
        voiceManagers_.push_back(std::move(vm));
    }
    // Monitor & metronome scratch.
    monitorSumL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    monitorSumR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    monFxL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    monFxR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    metronomeL_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
    metronomeR_ = static_cast<float*>(std::calloc(blockFloats, sizeof(float)));
}

void AudioGraph::setSampleRate(float sr) {
    sr_ = sr;
    config.sampleRate = static_cast<int32_t>(sr);
    loudness_.init(sr);
    spectrum_.init(sr, 2048, kSpectrumBands);
    masterLimiter_->init(sr);
    for (auto& b : buses_) {
        b.meters.init(sr);
        // FX units re-init lazily when their params are touched; structural
        // re-init of every pooled unit is done by AudioEngine on device switch.
    }
    for (auto& s : strips_) s.meters.init(sr);
    masterMeters_.init(sr);
}

// ── Handle management ────────────────────────────────────────────────────────

int32_t AudioGraph::allocStripHandle() {
    static int32_t nextHandle = 1;
    for (int i = 0; i < maxStrips_; ++i) {
        if (!strips_[i].active && strips_[i].handle == -1) {
            strips_[i].handle = nextHandle++;
            strips_[i].active = true;
            return strips_[i].handle;
        }
    }
    return -1; // strip pool exhausted (entitlement limit should prevent this)
}

void AudioGraph::freeStripHandle(int32_t handle) {
    if (Strip* s = stripByHandle(handle)) {
        s->inserts.clearAll();
        s->monitorFx.clearAll();
        if (s->voices) { s->voices->allNotesOff(); s->voices = nullptr; }
        for (auto& lane : s->lanes) {
            if (lane.points) { std::free(lane.points); lane.points = nullptr; }
            lane = AutomationLaneNative{};
        }
        s->active = false;
        s->sourceActive = false;
        s->armed = false;
        s->handle = -1;
        s->feedL.drain(s->feedL.readable());
        s->feedR.drain(s->feedR.readable());
    }
}

Strip* AudioGraph::stripByHandle(int32_t handle) {
    for (auto& s : strips_) if (s.active && s.handle == handle) return &s;
    return nullptr;
}

BusStrip* AudioGraph::busByHandle(int32_t handle) {
    for (auto& b : buses_) if (b.active && b.handle == handle) return &b;
    return nullptr;
}

// ── Main render (audio thread) ───────────────────────────────────────────────

void AudioGraph::render(const float* inL, const float* inR,
                        float* outL, float* outR, FrameCount frames) {
    ++blockCounter_;
    fxPool_.processDeferred(blockCounter_);

    // 1. Input capture + monitoring (before transport advance so the recorded
    //    material aligns with the position it was heard at).
    renderInputPath(inL, inR, frames);

    // 2. Advance transport (position used by automation + metronome).
    if (transport) transport->advance(frames);

    // 3. Strips -> buses -> master (metronome added inside renderMaster so it
    //    survives the master-sum zeroing and lands post-fader, pre-limiter).
    renderStrips(frames);
    renderBuses(frames);
    renderMaster(outL, outR, frames);
}

bool AudioGraph::isAudible(const Strip& strip) const {
    if (strip.mute) return false;
    if (anySolo_ && !strip.solo) return false;
    return true;
}

float AudioGraph::computeStripGain(const Strip& strip) const {
    return dbToGain(strip.gainDb.value());
}

void AudioGraph::renderStrips(FrameCount frames) {
    // Cache solo state once per block.
    anySolo_ = false;
    for (const auto& s : strips_) if (s.active && s.solo) { anySolo_ = true; break; }

    // Zero bus sums.
    for (auto& b : buses_) {
        if (!b.active) continue;
        std::memset(b.sumL, 0, sizeof(float) * frames);
        std::memset(b.sumR, 0, sizeof(float) * frames);
    }
    std::memset(masterSumL_, 0, sizeof(float) * frames);
    std::memset(masterSumR_, 0, sizeof(float) * frames);
    for (FrameCount i = 0; i < frames; ++i) {
        masterSumL_[i] += monitorSumL_[i];
        masterSumR_[i] += monitorSumR_[i];
    }

    // PDC: compute max chain latency across audible strips.
    FrameCount maxLatency = 0;
    for (const auto& s : strips_) {
        if (!s.active) continue;
        maxLatency = std::max(maxLatency, s.inserts.totalLatency());
    }

    for (auto& s : strips_) {
        if (!s.active) continue;
        evaluateAutomation(s, frames);

        const bool audible = isAudible(s);
        float* L = s.scratchL;
        float* R = s.scratchR;
        std::memset(L, 0, sizeof(float) * frames);
        std::memset(R, 0, sizeof(float) * frames);

        // ── Source stage ────────────────────────────────────────────────────
        if (s.sourceActive) {
            // Consume from feeder rings; underrun => silence (feeder telemetry
            // counts gaps for the "buffer too small" diagnostic).
            const size_t gotL = s.feedL.read(L, frames);
            const size_t gotR = s.feedR.read(R, frames);
            (void)gotL; (void)gotR;
        }
        if (s.voices) {
            s.voices->render(L, R, frames); // additive to any feeder content
        }

        if (!audible) continue;

        // ── PDC delay (strips with LESS latency wait for the slowest). ─────
        const FrameCount stripLatency = s.inserts.totalLatency();
        const FrameCount delay = maxLatency - stripLatency;
        if (delay > 0 && delay <= kMaxPdcFrames) {
            for (FrameCount i = 0; i < frames; ++i) {
                s.pdcL.write(L[i]);
                s.pdcR.write(R[i]);
                L[i] = s.pdcL.readInt(delay);
                R[i] = s.pdcR.readInt(delay);
            }
        }

        // ── Inserts ─────────────────────────────────────────────────────────
        s.inserts.process(L, R, frames);

        // ── Gain / pan / width (constant-power pan law) ────────────────────
        const float gain = computeStripGain(s);
        const float pan = s.pan.value();
        const float width = s.width.value();
        const float angle = (pan + 1.f) * 0.25f * kPi;
        const float panL = std::cos(angle), panR = std::sin(angle);
        for (FrameCount i = 0; i < frames; ++i) {
            const float mid = (L[i] + R[i]) * 0.5f;
            const float side = (L[i] - R[i]) * 0.5f * width;
            const float l = (mid + side) * gain * panL * kSqrtTwo;
            const float r = (mid - side) * gain * panR * kSqrtTwo;
            L[i] = l; R[i] = r;
        }

        s.meters.process(L, R, frames);

        // ── Sends ───────────────────────────────────────────────────────────
        for (auto& send : s.sends) {
            if (!send.enabled || send.targetBus < 0) continue;
            BusStrip* bus = (send.targetBus < static_cast<int32_t>(buses_.size()) &&
                             buses_[send.targetBus].active)
                                ? &buses_[send.targetBus] : nullptr;
            if (!bus) continue;
            const float sendGain = dbToGain(send.levelDb.value());
            if (sendGain <= 1e-7f) continue;
            const float sAngle = (send.pan.value() + 1.f) * 0.25f * kPi;
            const float sL = std::cos(sAngle) * sendGain;
            const float sR = std::sin(sAngle) * sendGain;
            // Pre-fader sends tap the pre-gain signal: recompute cheaply by
            // dividing out the strip gain (guard against -inf gain).
            const float inv = gain > 1e-6f ? 1.f / gain : 0.f;
            for (FrameCount i = 0; i < frames; ++i) {
                const float tl = send.preFader ? L[i] * inv : L[i];
                const float tr = send.preFader ? R[i] * inv : R[i];
                bus->sumL[i] += tl * sL;
                bus->sumR[i] += tr * sR;
            }
        }

        // ── Direct out ──────────────────────────────────────────────────────
        if (s.outputBus >= 0) {
            BusStrip* bus = busByHandle(s.outputBus);
            if (bus) {
                for (FrameCount i = 0; i < frames; ++i) { bus->sumL[i] += L[i]; bus->sumR[i] += R[i]; }
                continue;
            }
        }
        for (FrameCount i = 0; i < frames; ++i) { masterSumL_[i] += L[i]; masterSumR_[i] += R[i]; }
    }
}

void AudioGraph::renderBuses(FrameCount frames) {
    for (auto& b : buses_) {
        if (!b.active || b.mute) continue;
        b.inserts.process(b.sumL, b.sumR, frames);
        const float gain = dbToGain(b.gainDb.value());
        const float pan = b.pan.value();
        const float angle = (pan + 1.f) * 0.25f * kPi;
        const float gL = std::cos(angle) * gain * kSqrtTwo;
        const float gR = std::sin(angle) * gain * kSqrtTwo;
        // Constant-power stereo balance: unity at center.
        for (FrameCount i = 0; i < frames; ++i) {
            masterSumL_[i] += b.sumL[i] * gL;
            masterSumR_[i] += b.sumR[i] * gR;
        }
        b.meters.process(b.sumL, b.sumR, frames);
    }
}

void AudioGraph::renderMaster(float* outL, float* outR, FrameCount frames) {
    std::memcpy(masterScratchL_, masterSumL_, sizeof(float) * frames);
    std::memcpy(masterScratchR_, masterSumR_, sizeof(float) * frames);

    masterInserts_.process(masterScratchL_, masterScratchR_, frames);

    renderMetronome(frames);
    for (FrameCount i = 0; i < frames; ++i) {
        masterScratchL_[i] += metronomeL_[i];
        masterScratchR_[i] += metronomeR_[i];
    }

    const float masterGain = dbToGain(masterGainDb_.value());
    for (FrameCount i = 0; i < frames; ++i) {
        masterScratchL_[i] *= masterGain;
        masterScratchR_[i] *= masterGain;
    }

    if (masterLimiterEnabled_ && masterLimiter_) {
        masterLimiter_->process(masterScratchL_, masterScratchR_, frames);
    }

    masterMeters_.process(masterScratchL_, masterScratchR_, frames);
    loudness_.process(masterScratchL_, masterScratchR_, frames);
    // Spectrum from mono sum.
    for (FrameCount i = 0; i < frames; ++i) {
        monoScratch_[i] = (masterScratchL_[i] + masterScratchR_[i]) * 0.5f;
    }
    spectrum_.push(monoScratch_, frames);

    std::memcpy(outL, masterScratchL_, sizeof(float) * frames);
    std::memcpy(outR, masterScratchR_, sizeof(float) * frames);
}

void AudioGraph::renderInputPath(const float* inL, const float* inR, FrameCount frames) {
    std::memset(monitorSumL_, 0, sizeof(float) * frames);
    std::memset(monitorSumR_, 0, sizeof(float) * frames);
    if (!inL || !inR) return;
    std::memcpy(inScratchL_, inL, sizeof(float) * frames);
    std::memcpy(inScratchR_, inR, sizeof(float) * frames);

    for (auto& s : strips_) {
        if (!s.active || !s.armed) continue;
        const float inGain = dbToGain(s.inputGainDb.value());
        std::memcpy(s.scratchL, inScratchL_, sizeof(float) * frames);
        std::memcpy(s.scratchR, inScratchR_, sizeof(float) * frames);
        for (FrameCount i = 0; i < frames; ++i) {
            s.scratchL[i] *= inGain;
            s.scratchR[i] *= inGain;
        }

        // The recorder always gets the dry + input-gain signal.
        if (recorder) {
            recorder->captureStrip(s.handle, s.scratchL, s.scratchR, frames);
        }

        // Monitoring: optional FX, rendered into a separate buffer so the
        // recorded take stays dry while the performer hears processing.
        const bool monitorOn = s.monitorMode == 1 ||
            (s.monitorMode == 2 && transport && !transport->isPlaying());
        if (monitorOn) {
            std::memcpy(monFxL_, s.scratchL, sizeof(float) * frames);
            std::memcpy(monFxR_, s.scratchR, sizeof(float) * frames);
            s.monitorFx.process(monFxL_, monFxR_, frames);
            for (FrameCount i = 0; i < frames; ++i) {
                monitorSumL_[i] += monFxL_[i];
                monitorSumR_[i] += monFxR_[i];
            }
        }
    }
}

void AudioGraph::renderMetronome(FrameCount frames) {
    std::memset(metronomeL_, 0, sizeof(float) * frames);
    std::memset(metronomeR_, 0, sizeof(float) * frames);
    if (!transport || !transport->metronomeEnabled()) return;
    transport->renderMetronome(metronomeL_, metronomeR_, frames);
}

void AudioGraph::evaluateAutomation(Strip& strip, FrameCount frames) {
    (void)frames;
    if (!transport) return;
    const FramePosition pos = transport->positionFrames();
    for (auto& lane : strip.lanes) {
        if (lane.paramId < 0 || lane.mode == 0 || lane.count == 0 || !lane.points) continue;
        // Binary search: last breakpoint with position <= pos.
        int lo = 0, hi = lane.count - 1;
        while (lo < hi) {
            const int mid = (lo + hi + 1) / 2;
            if (lane.points[mid * 2] <= static_cast<float>(pos)) lo = mid; else hi = mid - 1;
        }
        float value;
        if (lo >= lane.count - 1) {
            value = lane.points[lo * 2 + 1]; // past the last point: hold
        } else {
            const float p0 = lane.points[lo * 2], v0 = lane.points[lo * 2 + 1];
            const float p1 = lane.points[(lo + 1) * 2], v1 = lane.points[(lo + 1) * 2 + 1];
            const float t = p1 > p0 ? (static_cast<float>(pos) - p0) / (p1 - p0) : 0.f;
            value = v0 + (v1 - v0) * std::clamp(t, 0.f, 1.f);
        }
        lane.lastValue = value;
        switch (lane.paramId) {
            case 1000: strip.gainDb.set(value); break;      // TRACK_VOLUME
            case 1001: strip.pan.set(value); break;         // TRACK_PAN
            case 1002: strip.width.set(value); break;       // TRACK_WIDTH
            case 1003: strip.mute = value >= 0.5f; break;   // TRACK_MUTE
            case 1010: case 1011: case 1012: case 1013: {   // SENDS
                const int idx = lane.paramId - 1010;
                strip.sends[idx].levelDb.set(value);
                break;
            }
            default:
                if (lane.paramId >= 2000 && lane.paramId < 6000) {
                    const int slot = (lane.paramId - 2000) / 64;
                    const int param = (lane.paramId - 2000) % 64;
                    strip.inserts.setParam(slot, param, value);
                } else if (lane.paramId >= 6000 && strip.voices) {
                    strip.voices->setMacro(lane.paramId - 6000, value);
                }
                break;
        }
    }
}

// ── Command application (audio thread) ──────────────────────────────────────

void AudioGraph::applyCommand(const EngineCommand& cmd, uint64_t blockCounter) {
    blockCounter_ = blockCounter;
    Strip* s = stripByHandle(cmd.target);
    switch (cmd.type) {
        case CommandType::kAddStrip: {
            // target carries the desired handle from Kotlin; if a free strip exists, adopt.
            if (!stripByHandle(cmd.target)) {
                for (auto& st : strips_) {
                    if (!st.active) {
                        st.active = true;
                        st.handle = cmd.target;
                        break;
                    }
                }
            }
            break;
        }
        case CommandType::kRemoveStrip: freeStripHandle(cmd.target); break;
        case CommandType::kSetStripActive: if (s) s->sourceActive = cmd.arg1 != 0; break;
        case CommandType::kSetStripGainDb: if (s) s->gainDb.set(cmd.fval); break;
        case CommandType::kSetStripPan: if (s) s->pan.set(cmd.fval); break;
        case CommandType::kSetStripWidth: if (s) s->width.set(cmd.fval); break;
        case CommandType::kSetStripMute: if (s) s->mute = cmd.arg1 != 0; break;
        case CommandType::kSetStripSolo: if (s) s->solo = cmd.arg1 != 0; break;
        case CommandType::kSetStripOrder: if (s) s->order = cmd.arg1; break;
        case CommandType::kSetStripInputGain: if (s) s->inputGainDb.set(cmd.fval); break;
        case CommandType::kSetStripOutputBus: if (s) s->outputBus = cmd.arg1; break;
        case CommandType::kArmStrip: if (s) s->armed = cmd.arg1 != 0; break;
        case CommandType::kSetMonitorMode: if (s) s->monitorMode = cmd.arg1; break;
        case CommandType::kSetSourceActive: if (s) s->sourceActive = cmd.arg1 != 0; break;
        case CommandType::kSeekSource:
            if (s) { s->feedL.drain(s->feedL.readable()); s->feedR.drain(s->feedR.readable()); }
            break;
        case CommandType::kSetSendLevel:
            if (s && cmd.arg1 < kMaxSends) {
                s->sends[cmd.arg1].levelDb.set(cmd.fval);
                s->sends[cmd.arg1].enabled = cmd.fval > kSilenceDb + 1.f;
            }
            break;
        case CommandType::kSetSendEnabled:
            if (s && cmd.arg1 < kMaxSends) s->sends[cmd.arg1].enabled = cmd.fval >= 0.5f;
            break;
        case CommandType::kSetSendPan:
            if (s && cmd.arg1 < kMaxSends) s->sends[cmd.arg1].pan.set(cmd.fval);
            break;
        case CommandType::kSetFxSlot:
            if (s) {
                if (s->inserts.setSlot(cmd.arg1, cmd.arg2)) {
                    // Restore params from payload if present: [paramIndex, value] pairs, lval=count
                    if (cmd.payload && cmd.lval > 0) {
                        const float* p = static_cast<const float*>(cmd.payload);
                        for (int64_t i = 0; i < cmd.lval; ++i) {
                            s->inserts.setParam(cmd.arg1, static_cast<int32_t>(p[i * 2]), p[i * 2 + 1]);
                        }
                    }
                }
                if (cmd.payload) reclaim_.push(cmd.payload);
            }
            break;
        case CommandType::kSetFxParam: if (s) s->inserts.setParam(cmd.arg1, cmd.arg2, cmd.fval); break;
        case CommandType::kSetFxBypass: if (s) s->inserts.setBypass(cmd.arg1, cmd.fval >= 0.5f); break;
        case CommandType::kSetFxWetMix: if (s) s->inserts.setWetMix(cmd.arg1, cmd.fval); break;
        case CommandType::kMoveFxSlot: if (s) s->inserts.moveSlot(cmd.arg1, cmd.arg2); break;
        case CommandType::kSetMonitorFx:
            if (s) s->monitorFx.setSlot(cmd.arg1, cmd.arg2);
            if (cmd.payload) reclaim_.push(cmd.payload);
            break;
        case CommandType::kUploadAutomation:
            if (s) {
                AutomationLaneNative* lane = laneFor(*s, cmd.arg1);
                if (lane) {
                    if (lane->points) reclaim_.push(lane->points);
                    lane->points = static_cast<float*>(cmd.payload);
                    lane->count = static_cast<int32_t>(cmd.lval);
                } else if (cmd.payload) {
                    reclaim_.push(cmd.payload);
                }
            }
            break;
        case CommandType::kClearAutomation:
            if (s) {
                AutomationLaneNative* lane = laneFor(*s, cmd.arg1);
                if (lane && lane->points) { reclaim_.push(lane->points); lane->points = nullptr; lane->count = 0; }
            }
            break;
        case CommandType::kSetAutomationMode:
            if (s) {
                AutomationLaneNative* lane = laneFor(*s, cmd.arg1);
                if (lane) lane->mode = cmd.arg2;
            }
            break;
        case CommandType::kSetInstrument:
            if (s && samplePool) {
                if (!s->voices) {
                    for (auto& vm : voiceManagers_) {
                        if (!vm->assigned()) { s->voices = vm.get(); break; }
                    }
                }
                if (s->voices) s->voices->configure(cmd.arg1, cmd.payload, samplePool, sr_);
                if (cmd.payload) reclaim_.push(cmd.payload);
            }
            break;
        case CommandType::kRemoveInstrument:
            if (s && s->voices) { s->voices->allNotesOff(); s->voices->deassign(); s->voices = nullptr; }
            break;
        case CommandType::kSetInstrumentMacro:
            if (s && s->voices) s->voices->setMacro(cmd.arg1, cmd.fval);
            break;
        case CommandType::kLoadSample:
            if (samplePool) samplePool->loadFromCommand(cmd);
            break;
        case CommandType::kUnloadSample:
            if (samplePool) samplePool->unload(static_cast<int32_t>(cmd.lval), reclaim_);
            break;
        case CommandType::kSetBusGain:
            if (BusStrip* b = busByHandle(cmd.target)) b->gainDb.set(cmd.fval);
            break;
        case CommandType::kSetBusPan:
            if (BusStrip* b = busByHandle(cmd.target)) b->pan.set(cmd.fval);
            break;
        case CommandType::kSetBusMute:
            if (BusStrip* b = busByHandle(cmd.target)) b->mute = cmd.arg1 != 0;
            break;
        case CommandType::kSetMasterLimiter:
            masterLimiterEnabled_ = cmd.arg2 != 0;
            if (masterLimiter_) masterLimiter_->setParam(0, cmd.fval);
            break;
        case CommandType::kResetMeters:
            for (auto& st : strips_) st.meters.reset();
            for (auto& b : buses_) b.meters.reset();
            masterMeters_.reset();
            break;
        case CommandType::kResetLoudness: loudness_.reset(); break;
        case CommandType::kPanic:
            for (auto& vm : voiceManagers_) vm->allNotesOff();
            break;
        default:
            break;
    }
}

AutomationLaneNative* AudioGraph::laneFor(Strip& strip, int32_t paramId) {
    for (auto& lane : strip.lanes) if (lane.paramId == paramId) return &lane;
    for (auto& lane : strip.lanes) {
        if (lane.paramId < 0) { lane.paramId = paramId; return &lane; }
    }
    return nullptr; // lane pool exhausted
}

// ── Meter polling (control thread) ──────────────────────────────────────────

void AudioGraph::pullMasterSnapshot(MasterMeterSnapshot& out) {
    out.peakL = masterMeters_.peakLin.load(std::memory_order_relaxed);
    out.peakR = masterMeters_.peakRin.load(std::memory_order_relaxed);
    out.rmsL = masterMeters_.rmsLin.load(std::memory_order_relaxed);
    out.rmsR = masterMeters_.rmsRin.load(std::memory_order_relaxed);
    out.clipL = masterMeters_.clipL.exchange(false, std::memory_order_relaxed);
    out.clipR = masterMeters_.clipR.exchange(false, std::memory_order_relaxed);
    loudness_.snapshot(out.loudness);
}

bool AudioGraph::pullStripSnapshot(int32_t handle, float& peakL, float& peakR,
                                   float& rmsL, float& rmsR, bool& clipL, bool& clipR) {
    const Strip* s = stripByHandle(handle);
    if (!s) return false;
    peakL = s->meters.peakLin.load(std::memory_order_relaxed);
    peakR = s->meters.peakRin.load(std::memory_order_relaxed);
    rmsL = s->meters.rmsLin.load(std::memory_order_relaxed);
    rmsR = s->meters.rmsRin.load(std::memory_order_relaxed);
    clipL = s->meters.clipL.load(std::memory_order_relaxed);
    clipR = s->meters.clipR.load(std::memory_order_relaxed);
    return true;
}

bool AudioGraph::pullSpectrum(float* bandsDbOut) {
    return spectrum_.pull(bandsDbOut);
}

void AudioGraph::drainReclaim() {
    void* ptr = nullptr;
    while (reclaim_.pop(ptr)) {
        std::free(ptr);
    }
}

size_t AudioGraph::feedStrip(int32_t handle, const float* left, const float* right, size_t frames) {
    Strip* s = stripByHandle(handle);
    if (!s) return 0;
    const size_t wl = s->feedL.write(left, frames);
    const size_t wr = s->feedR.write(right, frames);
    return std::min(wl, wr);
}

size_t AudioGraph::stripWritable(int32_t handle) const {
    for (const auto& s : strips_) {
        if (s.active && s.handle == handle) return s.feedL.writable();
    }
    return 0;
}

void AudioGraph::routeMidiEvent(const instrument::MidiEvent& ev) {
    Strip* s = stripByHandle(ev.stripHandle);
    if (s && s->voices) s->voices->pushEvent(ev);
}

} // namespace s1::audio::graph
