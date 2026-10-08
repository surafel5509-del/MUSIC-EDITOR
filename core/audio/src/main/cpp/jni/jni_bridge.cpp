// JNI bridge for the StudioOne audio engine.
//
// Thread rules:
//  * All nativeXxx calls come from Kotlin control/UI/prefetch threads — never
//    from the audio callback.
//  * Bulk audio (feedTrackAudio, loadSample) uses GetPrimitiveArrayCritical:
//    shortest possible critical sections, no JNI calls inside, no blocking.
//  * Every call is cheap: builders + lock-free pushes. Heavy work (stream
//    open/close, file I/O) is explicitly documented per function.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <memory>

#include "../engine/AudioEngine.h"
#include "../engine/EngineCommands.h"
#include "../instrument/VoiceManager.h"

#define LOG_TAG "S1AudioJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace s1::audio;
using namespace s1::audio::engine;

namespace {

inline AudioEngine* engine(jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle);
}

/** Push a command; logs (control thread only) when the queue is saturated. */
inline bool push(AudioEngine* e, const EngineCommand& cmd) {
    if (!e->commands().push(cmd)) {
        e->commands().noteDrop();
        LOGE("command queue overflow, dropped type=%d", static_cast<int>(cmd.type));
        return false;
    }
    return true;
}

} // namespace

extern "C" {

// ── Lifecycle ────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeCreate(
        JNIEnv*, jobject,
        jint sampleRate, jint framesPerBlock, jint maxStrips, jint maxInstrumentStrips,
        jboolean lowLatency, jboolean exclusive, jboolean inputEnabled) {
    auto* e = new AudioEngine();
    const bool ok = e->start(sampleRate, framesPerBlock, maxStrips, maxInstrumentStrips,
                             lowLatency, exclusive, inputEnabled);
    if (!ok) {
        LOGE("engine start failed");
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    if (auto* e = engine(handle)) {
        e->stop();
        delete e;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeRestart(
        JNIEnv*, jobject, jlong handle) {
    auto* e = engine(handle);
    return e && e->restart() ? JNI_TRUE : JNI_FALSE;
}

// ── Commands (generic; Kotlin NativeAudioEngine mirrors typed wrappers) ─────

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeSendCommand(
        JNIEnv*, jobject, jlong handle,
        jint type, jint target, jint arg1, jint arg2, jfloat fval, jfloat fval2,
        jlong lval, jlong lval2) {
    auto* e = engine(handle);
    if (!e) return JNI_FALSE;
    EngineCommand cmd;
    cmd.type = static_cast<CommandType>(type);
    cmd.target = target;
    cmd.arg1 = arg1;
    cmd.arg2 = arg2;
    cmd.fval = fval;
    cmd.fval2 = fval2;
    cmd.lval = lval;
    cmd.lval2 = lval2;
    return push(e, cmd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeSetFxSlot(
        JNIEnv* env, jobject, jlong handle,
        jint target, jint slot, jint pluginId, jfloatArray paramPairs) {
    auto* e = engine(handle);
    if (!e) return JNI_FALSE;
    jfloat* pairs = nullptr;
    jsize len = 0;
    if (paramPairs) {
        len = env->GetArrayLength(paramPairs);
        pairs = env->GetFloatArrayElements(paramPairs, nullptr);
    }
    const bool ok = push(e, makeFxSlot(target, slot, pluginId, pairs, len / 2));
    if (pairs) env->ReleaseFloatArrayElements(paramPairs, pairs, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeUploadAutomation(
        JNIEnv* env, jobject, jlong handle,
        jint target, jint paramId, jfloatArray pointsPosValue) {
    auto* e = engine(handle);
    if (!e || !pointsPosValue) return JNI_FALSE;
    const jsize len = env->GetArrayLength(pointsPosValue);
    jfloat* pts = env->GetFloatArrayElements(pointsPosValue, nullptr);
    const bool ok = push(e, makeAutomationUpload(target, paramId, pts, len / 2));
    env->ReleaseFloatArrayElements(pointsPosValue, pts, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeSetInstrument(
        JNIEnv* env, jobject, jlong handle,
        jint target, jint instrumentType, jfloatArray presetBlob) {
    auto* e = engine(handle);
    if (!e) return JNI_FALSE;
    jfloat* blob = nullptr;
    jsize len = 0;
    if (presetBlob) {
        len = env->GetArrayLength(presetBlob);
        blob = env->GetFloatArrayElements(presetBlob, nullptr);
    }
    const bool ok = push(e, makeInstrument(target, instrumentType, blob, len));
    if (blob) env->ReleaseFloatArrayElements(presetBlob, blob, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── Sample pool ──────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeAllocSampleSlot(
        JNIEnv*, jobject, jlong handle) {
    auto* e = engine(handle);
    return e ? e->samplePool().firstFreeSlot() : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeLoadSample(
        JNIEnv* env, jobject, jlong handle,
        jint slot, jfloatArray interleaved, jint channels, jint sampleRate) {
    auto* e = engine(handle);
    if (!e || slot < 0 || !interleaved) return JNI_FALSE;
    const jsize len = env->GetArrayLength(interleaved);
    // Copy on the control thread into a malloc'd buffer; ownership transfers
    // to the pool, retirement flows through the ReclaimQueue.
    float* copy = static_cast<float*>(std::malloc(sizeof(float) * len));
    if (!copy) return JNI_FALSE;
    env->GetFloatArrayRegion(interleaved, 0, len, copy);
    const int64_t frames = channels > 0 ? len / channels : len;
    return push(e, makeSampleLoad(slot, copy, frames, channels, sampleRate))
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeUnloadSample(
        JNIEnv*, jobject, jlong handle, jint slot) {
    auto* e = engine(handle);
    if (!e) return;
    EngineCommand cmd;
    cmd.type = CommandType::kUnloadSample;
    cmd.lval = slot;
    push(e, cmd);
}

// ── Playback feed (prefetch thread) ─────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeFeedTrack(
        JNIEnv* env, jobject, jlong handle,
        jint stripHandle, jfloatArray left, jfloatArray right, jint frames) {
    auto* e = engine(handle);
    if (!e || !left || !right || frames <= 0) return 0;
    jfloat* l = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(left, nullptr));
    jfloat* r = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(right, nullptr));
    if (!l || !r) {
        if (l) env->ReleasePrimitiveArrayCritical(left, l, JNI_ABORT);
        if (r) env->ReleasePrimitiveArrayCritical(right, r, JNI_ABORT);
        return 0;
    }
    // No JNI calls between Get/ReleasePrimitiveArrayCritical.
    const size_t wrote = e->feedTrackAudio(stripHandle, l, r, static_cast<size_t>(frames));
    env->ReleasePrimitiveArrayCritical(left, l, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(right, r, JNI_ABORT);
    return static_cast<jint>(wrote);
}

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeFeedWritable(
        JNIEnv*, jobject, jlong handle, jint stripHandle) {
    auto* e = engine(handle);
    return e ? static_cast<jint>(e->feedTrackWritable(stripHandle)) : 0;
}

// ── MIDI ─────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeSendMidi(
        JNIEnv*, jobject, jlong handle,
        jint stripHandle, jint sampleOffset, jint type, jint channel,
        jint key, jint velocity, jint bend, jfloat fval) {
    auto* e = engine(handle);
    if (!e) return;
    instrument::MidiEvent ev;
    ev.stripHandle = stripHandle;
    ev.sampleOffset = sampleOffset;
    ev.type = static_cast<uint8_t>(type);
    ev.channel = static_cast<uint8_t>(channel);
    ev.key = static_cast<uint8_t>(key);
    ev.velocity = static_cast<uint8_t>(velocity);
    ev.bend = static_cast<int16_t>(bend);
    ev.fval = fval;
    e->sendMidi(ev);
}

// ── Recording takes ──────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeBeginTake(
        JNIEnv* env, jobject, jlong handle,
        jint stripHandle, jstring path, jint channels, jint bitsPerSample) {
    auto* e = engine(handle);
    if (!e || !path) return -1;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    const int take = e->recorder().beginTake(stripHandle, cpath, channels, bitsPerSample);
    env->ReleaseStringUTFChars(path, cpath);
    return take;
}

JNIEXPORT jlong JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeEndTake(
        JNIEnv*, jobject, jlong handle, jint takeIndex) {
    auto* e = engine(handle);
    if (!e) return -1;
    int64_t frames = 0;
    const bool ok = e->recorder().endTake(takeIndex, &frames);
    return ok ? frames : -frames - 1; // negative encodes failure + frames captured
}

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeTakeStats(
        JNIEnv* env, jobject, jlong handle, jint takeIndex, jlongArray out) {
    auto* e = engine(handle);
    if (!e || !out || env->GetArrayLength(out) < 3) return;
    const auto s = e->recorder().stats(takeIndex);
    const jlong values[3] = {s.framesWritten, s.overruns, s.ioError ? 1 : 0};
    env->SetLongArrayRegion(out, 0, 3, values);
}

// ── Meters / telemetry ───────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativePullMasterMeters(
        JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    auto* e = engine(handle);
    if (!e || !out || env->GetArrayLength(out) < 12) return;
    graph::MasterMeterSnapshot snap;
    e->graph().pullMasterSnapshot(snap);
    const float v[12] = {
        snap.peakL, snap.peakR, snap.rmsL, snap.rmsR,
        snap.clipL ? 1.f : 0.f, snap.clipR ? 1.f : 0.f,
        snap.loudness.momentaryLUFS, snap.loudness.shortTermLUFS,
        snap.loudness.integratedLUFS, snap.loudness.loudnessRangeLU,
        snap.loudness.truePeakDbtp, static_cast<float>(e->graph().poolPressure()),
    };
    env->SetFloatArrayRegion(out, 0, 12, v);
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativePullStripMeters(
        JNIEnv* env, jobject, jlong handle, jint stripHandle, jfloatArray out) {
    auto* e = engine(handle);
    if (!e || !out || env->GetArrayLength(out) < 6) return JNI_FALSE;
    float pl, pr, rl, rr;
    bool cl, cr;
    if (!e->graph().pullStripSnapshot(stripHandle, pl, pr, rl, rr, cl, cr)) return JNI_FALSE;
    const float v[6] = {pl, pr, rl, rr, cl ? 1.f : 0.f, cr ? 1.f : 0.f};
    env->SetFloatArrayRegion(out, 0, 6, v);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativePullSpectrum(
        JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    auto* e = engine(handle);
    if (!e || !out) return JNI_FALSE;
    const jsize bands = env->GetArrayLength(out);
    if (bands != e->graph().analyzerBandCount()) return JNI_FALSE;
    jfloat* buf = env->GetFloatArrayElements(out, nullptr);
    const bool fresh = e->graph().pullSpectrum(buf);
    env->ReleaseFloatArrayElements(out, buf, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeLatencyReport(
        JNIEnv* env, jobject, jlong handle, jfloatArray floatsOut, jintArray intsOut) {
    auto* e = engine(handle);
    if (!e) return;
    const auto r = e->latencyReport();
    if (floatsOut && env->GetArrayLength(floatsOut) >= 3) {
        const float v[3] = {r.inputLatencyMs, r.outputLatencyMs, r.roundTripMs};
        env->SetFloatArrayRegion(floatsOut, 0, 3, v);
    }
    if (intsOut && env->GetArrayLength(intsOut) >= 6) {
        const jint v[6] = {r.bufferFrames, r.sampleRate, r.underruns, r.xruns,
                           r.usingMmap ? 1 : 0, r.usingAaudio ? 1 : 0};
        env->SetIntArrayRegion(intsOut, 0, 6, v);
    }
}

JNIEXPORT jlong JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeTransportPosition(
        JNIEnv*, jobject, jlong handle) {
    auto* e = engine(handle);
    return e ? static_cast<jlong>(e->transport().positionFrames()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeTransportState(
        JNIEnv*, jobject, jlong handle) {
    auto* e = engine(handle);
    return e ? static_cast<jint>(e->transport().state()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_studioone_mobile_core_audio_NativeAudioEngine_nativeDrainReclaim(
        JNIEnv*, jobject, jlong handle) {
    auto* e = engine(handle);
    if (!e) return 0;
    e->drainReclaim();
    return 1;
}

} // extern "C"
