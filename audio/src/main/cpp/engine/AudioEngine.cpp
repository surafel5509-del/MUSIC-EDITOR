// SPDX-License-Identifier: MIT
#include "AudioEngine.h"

#include <android/log.h>

#define LOG_TAG "StudioOneAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace studioone::engine {

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(const EngineConfig& config) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) return true;
    config_ = config;

    graph_.prepare(config.sampleRate, config.framesPerCallback);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setPerformanceMode(config.useLowLatency ? oboe::PerformanceMode::LowLatency
                                                  : oboe::PerformanceMode::None)
        ->setSampleRate(config.sampleRate)
        ->setFramesPerCallback(config.framesPerCallback)
        ->setChannelCount(config.inputEnabled ? oboe::ChannelCount::Stereo : oboe::ChannelCount::Stereo)
        ->setFormat(oboe::AudioFormat::Float)
        ->setDataCallback(this);

    if (config.forceOpenSles) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
    }

    // NOTE: duplex (input+output in one stream) is enabled once the device
    // capability probe confirms a low-latency input path; until then input
    // capture runs through a second stream mixed via the record ring.
    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK || !stream_) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        // Retry on OpenSL ES if AAudio failed (pre-P devices / quirk list).
        if (!config.forceOpenSles) {
            oboe::AudioStreamBuilder fallback;
            fallback.setDirection(oboe::Direction::Output)
                ->setAudioApi(oboe::AudioApi::OpenSLES)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSampleRate(config.sampleRate)
                ->setFramesPerCallback(config.framesPerCallback)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setFormat(oboe::AudioFormat::Float)
                ->setDataCallback(this);
            if (fallback.openStream(stream_) != oboe::Result::OK) return false;
        } else {
            return false;
        }
    }

    LOGI("Stream opened: sampleRate=%d api=%s", stream_->getSampleRate(),
         stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES");

    staging_.resize(static_cast<size_t>(config.framesPerCallback) * 2);

    if (stream_->requestStart() != oboe::Result::OK) {
        LOGE("Failed to start stream");
        stream_.reset();
        return false;
    }
    return true;
}

void AudioEngine::stop() {
    stopRecording();
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) return;
    stream_->stop();
    stream_->close();
    stream_.reset();
}

AudioEngine::StreamInfo AudioEngine::streamInfo() const noexcept {
    StreamInfo info;
    if (stream_) {
        info.sampleRate = stream_->getSampleRate();
        info.framesPerBurst = stream_->getFramesPerBurst();
        info.bufferSizeInFrames = stream_->getBufferSizeInFrames();
        info.usingAAudio = stream_->getAudioApi() == oboe::AudioApi::AAudio;
        const auto latency = stream_->calculateLatencyMillis();
        info.outputLatencyMs = latency ? latency.value() : 0.0;
    }
    return info;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    if (numFrames <= 0) return oboe::DataCallbackResult::Continue;
    auto* out = static_cast<float*>(audioData);
    const int channels = stream->getChannelCount();

    // Interleaved stereo output buffer -> channel pointers for the graph.
    // staging_ is preallocated in start(); no allocation here (RT-safe).
    float* channelPtrs[graph::kMaxChannels];
    channelPtrs[0] = staging_.data();
    channelPtrs[1] = channels > 1 ? staging_.data() + numFrames : staging_.data();

    graph_.process(nullptr, channelPtrs, channels, numFrames);

    for (int i = 0; i < numFrames; ++i) {
        for (int c = 0; c < channels; ++c) {
            out[i * channels + c] = staging_[c * numFrames + i];
        }
    }
    return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------------------
// Recording writer
// ---------------------------------------------------------------------------

bool AudioEngine::startRecording(const std::string& path, int bitDepth) {
    if (recordWriterRunning_.load()) return false;
    recordChannels_ = 2;  // stereo capture; mono mic pipeline lands with duplex stream
    if (!recordWriter_.open(path, config_.sampleRate, recordChannels_, bitDepth)) {
        return false;
    }
    graph_.recordRing().clear();
    recordWriterRunning_.store(true);
    recordThread_ = std::thread(&AudioEngine::recordWriterLoop, this);
    return true;
}

void AudioEngine::stopRecording() {
    if (!recordWriterRunning_.exchange(false)) return;
    if (recordThread_.joinable()) recordThread_.join();
    recordWriter_.close();
}

void AudioEngine::recordWriterLoop() {
    constexpr size_t kChunk = 4096;
    std::vector<float> staging(kChunk);
    while (recordWriterRunning_.load()) {
        const size_t read = graph_.recordRing().read(staging.data(), kChunk);
        if (read > 0) {
            recordWriter_.write(staging.data(), static_cast<int64_t>(read) / recordChannels_);
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
    // Drain remainder.
    size_t read;
    do {
        read = graph_.recordRing().read(staging.data(), kChunk);
        if (read > 0) recordWriter_.write(staging.data(), static_cast<int64_t>(read) / recordChannels_);
    } while (read > 0);
}

}  // namespace studioone::engine
