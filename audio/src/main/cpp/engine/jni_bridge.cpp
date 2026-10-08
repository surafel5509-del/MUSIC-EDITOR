// SPDX-License-Identifier: MIT
// JNI surface for com.studioone.audio.AudioEngine (Kotlin).
//
// Contract:
//  - all *mutation* calls run on the app's graph thread (stream paused when
//    the topology changes),
//  - parameter + transport calls are lock-free and may come from any thread,
//  - meter reads are lock-free snapshots.
#include <jni.h>
#include <memory>
#include <string>

#include "AudioEngine.h"
#include "../graph/AudioGraph.h"
#include "../dsp/effect_base.h"

using studioone::engine::AudioEngine;
using studioone::engine::EngineConfig;

namespace {

inline AudioEngine* engine(jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle);
}

std::string jstringToString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_studioone_audio_AudioEngine_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete engine(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_audio_AudioEngine_nativeStart(
    JNIEnv*, jclass, jlong handle, jint sampleRate, jint bufferSize,
    jboolean inputEnabled, jboolean useLowLatency, jboolean forceOpenSles) {
    EngineConfig config;
    config.sampleRate = sampleRate;
    config.framesPerCallback = bufferSize;
    config.inputEnabled = inputEnabled;
    config.useLowLatency = useLowLatency;
    config.forceOpenSles = forceOpenSles;
    return engine(handle)->start(config) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeStop(JNIEnv*, jclass, jlong handle) {
    engine(handle)->stop();
}

JNIEXPORT jdouble JNICALL
Java_com_studioone_audio_AudioEngine_nativeOutputLatencyMs(JNIEnv*, jclass, jlong handle) {
    return engine(handle)->streamInfo().outputLatencyMs;
}

JNIEXPORT jint JNICALL
Java_com_studioone_audio_AudioEngine_nativeFramesPerBurst(JNIEnv*, jclass, jlong handle) {
    return engine(handle)->streamInfo().framesPerBurst;
}

// ---- Transport ------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetTransportState(JNIEnv*, jclass, jlong handle, jint state) {
    engine(handle)->graph().transport().state.store(state, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSeek(JNIEnv*, jclass, jlong handle, jlong frame) {
    engine(handle)->graph().transport().playheadFrame.store(frame, std::memory_order_release);
}

JNIEXPORT jlong JNICALL
Java_com_studioone_audio_AudioEngine_nativeGetPlayhead(JNIEnv*, jclass, jlong handle) {
    return engine(handle)->graph().transport().playheadFrame.load(std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetLoop(
    JNIEnv*, jclass, jlong handle, jboolean enabled, jlong start, jlong end) {
    auto& t = engine(handle)->graph().transport();
    t.loopEnabled.store(enabled);
    t.loopStartFrame.store(start);
    t.loopEndFrame.store(end);
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetTempo(JNIEnv*, jclass, jlong handle, jdouble bpm) {
    engine(handle)->graph().transport().tempo.store(bpm);
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetMetronome(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    engine(handle)->graph().transport().metronomeEnabled.store(enabled);
}

// ---- Graph topology (call while stream paused or accept hot-add) ----------

JNIEXPORT jint JNICALL
Java_com_studioone_audio_AudioEngine_nativeAddTrack(
    JNIEnv*, jclass, jlong handle, jboolean withInstrument, jint instrumentKind) {
    return engine(handle)->graph().addTrack(
        withInstrument,
        static_cast<studioone::graph::MidiInstrumentSource::Kind>(instrumentKind));
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeRemoveTrack(JNIEnv*, jclass, jlong handle, jint trackId) {
    engine(handle)->graph().removeTrack(static_cast<uint32_t>(trackId));
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetTrackBasic(
    JNIEnv*, jclass, jlong handle, jint trackId, jfloat gain, jfloat pan,
    jboolean mute, jboolean solo, jboolean armed) {
    engine(handle)->graph().setTrackBasic(trackId, gain, pan, mute, solo, armed);
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_audio_AudioEngine_nativeAddAudioClip(
    JNIEnv* env, jclass, jlong handle, jint trackId, jstring path,
    jlong clipStartFrame, jlong sourceOffsetFrames, jfloat gain, jboolean reversed) {
    auto pcm = studioone::engine::WavFile::read(jstringToString(env, path));
    if (!pcm) return JNI_FALSE;
    engine(handle)->graph().addClipToTrack(
        static_cast<uint32_t>(trackId),
        std::make_unique<studioone::graph::AudioClipSource>(
            pcm, clipStartFrame, sourceOffsetFrames, gain, reversed));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeClearClips(JNIEnv*, jclass, jlong handle, jint trackId) {
    engine(handle)->graph().clearClips(static_cast<uint32_t>(trackId));
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSendMidi(
    JNIEnv*, jclass, jlong handle, jint trackId, jint status, jint data1, jint data2) {
    engine(handle)->graph().sendMidi(
        static_cast<uint32_t>(trackId),
        static_cast<uint8_t>(status),
        static_cast<uint8_t>(data1),
        static_cast<uint8_t>(data2));
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_audio_AudioEngine_nativeAddEffect(
    JNIEnv*, jclass, jlong handle, jint nodeId, jint effectKind) {
    auto effect = studioone::dsp::createEffect(static_cast<uint32_t>(effectKind));
    if (!effect) return JNI_FALSE;
    engine(handle)->graph().addInsert(static_cast<uint32_t>(nodeId), std::move(effect));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeSetParameter(
    JNIEnv*, jclass, jlong handle, jint nodeId, jint paramId, jfloat value) {
    engine(handle)->graph().postParam(static_cast<uint32_t>(nodeId),
                                      static_cast<uint32_t>(paramId), value);
}

// ---- Recording -------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_studioone_audio_AudioEngine_nativeStartRecording(
    JNIEnv* env, jclass, jlong handle, jstring path, jint bitDepth) {
    return engine(handle)->startRecording(jstringToString(env, path), bitDepth) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeStopRecording(JNIEnv*, jclass, jlong handle) {
    engine(handle)->stopRecording();
}

// ---- Meters (lock-free snapshots) ------------------------------------------

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeReadMasterMeter(
    JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    const auto snap = engine(handle)->graph().masterMeterSnapshot();
    float values[8] = {
        snap.peak[0], snap.peak[1], snap.rms[0], snap.rms[1],
        snap.lufsMomentary, snap.lufsIntegrated,
        snap.clip[0] ? 1.f : 0.f, snap.clip[1] ? 1.f : 0.f,
    };
    env->SetFloatArrayRegion(out, 0, 8, values);
}

JNIEXPORT void JNICALL
Java_com_studioone_audio_AudioEngine_nativeReadTrackMeter(
    JNIEnv* env, jclass, jlong handle, jint trackId, jfloatArray out) {
    const auto snap = engine(handle)->graph().trackMeterSnapshot(static_cast<uint32_t>(trackId));
    float values[8] = {
        snap.peak[0], snap.peak[1], snap.rms[0], snap.rms[1],
        snap.lufsMomentary, snap.lufsIntegrated,
        snap.clip[0] ? 1.f : 0.f, snap.clip[1] ? 1.f : 0.f,
    };
    env->SetFloatArrayRegion(out, 0, 8, values);
}

}  // extern "C"
