#include "AudioEngine.h"
#include <algorithm>
#include <cstring>
#include <oboe/AudioStreamBuilder.h>

namespace s1::audio::engine {

using namespace instrument;

AudioEngine::~AudioEngine() { stop(); }

bool AudioEngine::start(int32_t sampleRate, FrameCount framesPerBlock,
                        int32_t maxStrips, int32_t maxInstrumentStrips,
                        bool lowLatency, bool exclusive, bool inputEnabled) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (running_.load()) return true;

    EngineConfig config;
    config.sampleRate = sampleRate;
    config.framesPerBlock = std::clamp(framesPerBlock, 64, kMaxFramesPerBlock);
    config.lowLatencyMode = lowLatency;
    config.exclusiveMmap = exclusive;
    inputEnabled_ = inputEnabled;

    transport_.init(static_cast<float>(sampleRate));
    recorder_.init(static_cast<float>(sampleRate));
    graph_.init(config, maxStrips, maxInstrumentStrips);
    graph_.transport = &transport_;
    graph_.recorder = &recorder_;
    graph_.samplePool = &samplePool_;

    // Wire metronome tempo into voice managers lazily through the graph:
    // VoiceManager::setTempo is called on tempo commands (see EngineCommands).

    inputMono_.resize(kMaxFramesPerBlock);
    renderL_.resize(kMaxFramesPerBlock);
    renderR_.resize(kMaxFramesPerBlock);
    silenceFrame_.assign(kMaxFramesPerBlock * 2, 0.f);

    if (!openStreams()) {
        closeStreams();
        return false;
    }
    running_.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::openStreams() {
    const EngineConfig& config = graph_.config;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(config.lowLatencyMode ? oboe::PerformanceMode::LowLatency
                                                   : oboe::PerformanceMode::None)
        ->setSharingMode(config.exclusiveMmap ? oboe::SharingMode::Exclusive
                                              : oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(false)
        ->setSampleRate(config.sampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setChannelCount(2)
        ->setFramesPerDataCallback(config.framesPerBlock)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(outStream_);
    if (result != oboe::Result::OK || !outStream_) return false;

    // Actual parameters may differ (device native rate). Re-read them.
    const int32_t actualRate = outStream_->getSampleRate();
    const FrameCount actualBurst = outStream_->getFramesPerBurst();
    if (actualRate != config.sampleRate) {
        // Device refused the requested rate: adapt the graph to the native rate.
        graph_.config.sampleRate = actualRate;
        graph_.setSampleRate(static_cast<float>(actualRate));
        transport_.setSampleRate(static_cast<float>(actualRate));
        recorder_.setSampleRate(static_cast<float>(actualRate));
    }
    (void)actualBurst;

    if (inputEnabled_) {
        oboe::AudioStreamBuilder inBuilder;
        inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(config.lowLatencyMode ? oboe::PerformanceMode::LowLatency
                                                       : oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared) // exclusive input is rare
            ->setFormat(oboe::AudioFormat::Float)
            ->setSampleRate(outStream_->getSampleRate()) // match output: full-duplex requirement
            ->setChannelCount(1)
            ->setFramesPerDataCallback(config.framesPerBlock)
            ->setInputPreset(oboe::InputPreset::Unprocessed) // no AGC/NS: we do our own
            ->setUsage(oboe::Usage::Media);
        result = inBuilder.openStream(inStream_);
        if (result != oboe::Result::OK || !inStream_) {
            inStream_.reset(); // record unavailable: playback-only mode
            inputEnabled_ = false;
        }
    }

    result = outStream_->requestStart();
    if (result != oboe::Result::OK) return false;
    if (inStream_) {
        inStream_->requestStart(); // ignore failure: monitor path degrades to silence
    }
    return true;
}

void AudioEngine::closeStreams() {
    if (inStream_) {
        inStream_->requestStop();
        inStream_->close();
        inStream_.reset();
    }
    if (outStream_) {
        outStream_->requestStop();
        outStream_->close();
        outStream_.reset();
    }
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    running_.store(false, std::memory_order_release);
    closeStreams();
    recorder_.shutdown();
    transport_.stop();
}

bool AudioEngine::restart() {
    restartRequested_.store(true);
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    restartRequested_.store(false);
    if (!running_.load()) return false;
    const bool wasRunning = true;
    closeStreams();
    (void)wasRunning;
    return openStreams();
}

// ── Audio callback ───────────────────────────────────────────────────────────

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData, int32_t numFrames) {
    (void)stream;
    if (!running_.load(std::memory_order_acquire)) return oboe::DataCallbackResult::Stop;
    ++blockCounter_;
    auto* out = static_cast<float*>(audioData);

    const FrameCount frames = std::min(numFrames, static_cast<int32_t>(kMaxFramesPerBlock));

    // 1. Apply queued engine commands & route MIDI (block boundary semantics).
    drainCommandQueues();

    // 2. Full-duplex input read (non-blocking). A missed input block yields
    //    silence on the capture/monitor path — output must never stall.
    const float* inL = nullptr;
    const float* inR = nullptr;
    if (inStream_) {
        const oboe::Result res = inStream_->read(inputMono_.data(), frames, 0);
        if (res == oboe::Result::OK) {
            inL = inputMono_.data();
            inR = inputMono_.data(); // mono capture duplicated inside the graph
        }
    }

    // 3. Render the mix.
    graph_.render(inL, inR, renderL_.data(), renderR_.data(), frames);

    // 4. Interleave to the output buffer.
    for (FrameCount i = 0; i < frames; ++i) {
        out[i * 2] = renderL_[i];
        out[i * 2 + 1] = renderR_[i];
    }
    // If the device requested more frames than kMaxFramesPerBlock (should not
    // happen with framesPerCallback set), pad with silence rather than noise.
    for (int32_t i = frames; i < numFrames; ++i) {
        out[i * 2] = 0.f;
        out[i * 2 + 1] = 0.f;
    }

    // Adaptive buffer management: request the burst-aligned size once.
    if (blockCounter_ == 4 && outStream_) {
        outStream_->setBufferSizeInFrames(outStream_->getFramesPerBurst() * 2);
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::drainCommandQueues() {
    EngineCommand cmd;
    while (commands_.pop(cmd)) {
        // Transport/engine-level commands are handled here; strip/bus/FX
        // commands are delegated to the graph.
        switch (cmd.type) {
            case CommandType::kPlay: transport_.play(); break;
            case CommandType::kRecordStart: transport_.record(); break;
            case CommandType::kStop: transport_.stop(); break;
            case CommandType::kPause: transport_.pause(); break;
            case CommandType::kSetPositionFrames: transport_.setPosition(cmd.lval); break;
            case CommandType::kSetLoopRegion:
                transport_.setLoop(cmd.arg1 != 0, cmd.lval, cmd.lval2);
                break;
            case CommandType::kSetTempo:
                transport_.setTempo(cmd.fval);
                for (auto& vm : graph_.voiceManagers()) vm->setTempo(transport_.framesPerBeat());
                break;
            case CommandType::kSetTimeSignature:
                transport_.setTimeSignature(cmd.arg1, cmd.arg2);
                break;
            case CommandType::kSetMetronome:
                transport_.setMetronome(cmd.arg1 != 0, cmd.fval);
                break;
            case CommandType::kSetCountIn:
                transport_.setCountInBars(cmd.arg1);
                break;
            default:
                graph_.applyCommand(cmd, blockCounter_);
                break;
        }
    }
    // Route MIDI events to their target strips.
    MidiEvent ev;
    while (midiQueue_.pop(ev)) {
        graph_.routeMidiEvent(ev);
    }
}

LatencyReportNative AudioEngine::latencyReport() const {
    LatencyReportNative r;
    if (outStream_) {
        r.outputLatencyMs = static_cast<float>(outStream_->calculateLatencyMillis());
        r.bufferFrames = outStream_->getFramesPerBurst();
        r.sampleRate = outStream_->getSampleRate();
        r.underruns = outStream_->getXRunCount().value();
        r.usingAaudio = outStream_->getAudioApi() == oboe::AudioApi::AAudio;
        r.usingMmap = r.usingAaudio &&
            outStream_->getSharingMode() == oboe::SharingMode::Exclusive;
    }
    if (inStream_) {
        r.inputLatencyMs = static_cast<float>(inStream_->calculateLatencyMillis());
    }
    r.roundTripMs = r.inputLatencyMs + r.outputLatencyMs;
    r.xruns = underruns_.load();
    return r;
}

size_t AudioEngine::feedTrackAudio(int32_t stripHandle, const float* left, const float* right, size_t frames) {
    return graph_.feedStrip(stripHandle, left, right, frames);
}

size_t AudioEngine::feedTrackWritable(int32_t stripHandle) const {
    return graph_.stripWritable(stripHandle);
}

void AudioEngine::sendMidi(const MidiEvent& ev) {
    if (!midiQueue_.push(ev)) midiQueue_.noteDrop();
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream*, oboe::Result) {}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Disconnected device (headphone pull, BT drop, USB unplug): rebuild
    // streams on a helper thread — never reopen synchronously inside the
    // error callback (Oboe holds its own lock there).
    if (error == oboe::Result::ErrorDisconnected && running_.load()) {
        std::thread([this]() { restart(); }).detach();
    }
}

} // namespace s1::audio::engine
