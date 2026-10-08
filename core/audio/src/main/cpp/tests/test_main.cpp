// Minimal assertion harness (no external deps — CI runs this on a bare
// ubuntu runner). Each TEST registers itself; main() runs all and reports.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "../common/RingBuffer.h"
#include "../common/LockFreeQueue.h"
#include "../common/FastMath.h"
#include "../dsp/Biquad.h"
#include "../dsp/Fft.h"
#include "../dsp/Loudness.h"
#include "../dsp/WavFile.h"
#include "../dsp/Envelope.h"
#include "../fx/Compressor.h"
#include "../fx/Reverb.h"
#include "../fx/Delay.h"
#include "../fx/Modulation.h"

namespace {
struct TestCase { std::string name; std::function<void()> fn; };
std::vector<TestCase>& registry() { static std::vector<TestCase> r; return r; }
int failures = 0;
int checks = 0;

void fail(const char* file, int line, const std::string& msg) {
    std::printf("  FAIL %s:%d %s\n", file, line, msg.c_str());
    failures++;
}

#define CHECK(cond) do { checks++; if (!(cond)) fail(__FILE__, __LINE__, #cond); } while (0)
#define CHECK_NEAR(a, b, eps) do { checks++; \
    if (std::fabs((double)(a) - (double)(b)) > (eps)) { \
        char buf[256]; std::snprintf(buf, sizeof(buf), "%s ~= %s (got %g vs %g)", #a, #b, (double)(a), (double)(b)); \
        fail(__FILE__, __LINE__, buf); } } while (0)

#define TEST(name) \
    static void name(); \
    static const bool reg_##name = (registry().push_back({#name, name}), true); \
    static void name()

using namespace s1::audio;

// ── Ring buffer ──────────────────────────────────────────────────────────────
TEST(spsc_ring_basic) {
    float storage[64];
    SpscRingBuffer ring;
    ring.init(storage, 64);
    float in[40];
    for (int i = 0; i < 40; ++i) in[i] = static_cast<float>(i);
    CHECK(ring.write(in, 40) == 40);
    CHECK(ring.readable() == 40);
    CHECK(ring.write(in, 40) == 24); // only capacity left
    float out[64];
    CHECK(ring.read(out, 64) == 64);
    for (int i = 0; i < 40; ++i) CHECK_NEAR(out[i], in[i], 1e-6);
    CHECK(ring.readable() == 0);
}

TEST(spsc_ring_wrap) {
    float storage[16];
    SpscRingBuffer ring;
    ring.init(storage, 16);
    for (int round = 0; round < 100; ++round) {
        float data[7];
        for (int i = 0; i < 7; ++i) data[i] = round * 7.0f + i;
        CHECK(ring.write(data, 7) == 7);
        float back[7];
        CHECK(ring.read(back, 7) == 7);
        for (int i = 0; i < 7; ++i) CHECK_NEAR(back[i], data[i], 1e-6);
    }
}

// ── Lock-free queue ──────────────────────────────────────────────────────────
TEST(mpsc_queue_capacity) {
    LockFreeQueue<int, 8> q;
    for (int i = 0; i < 8; ++i) CHECK(q.push(i));
    CHECK(!q.push(99)); // full
    int out;
    for (int i = 0; i < 8; ++i) { CHECK(q.pop(out)); CHECK(out == i); }
    CHECK(!q.pop(out)); // empty
}

// ── Biquad ───────────────────────────────────────────────────────────────────
TEST(biquad_lowpass_attenuates_highs) {
    dsp::Biquad f;
    f.setSampleRate(48000);
    f.setParams(dsp::BiquadType::LowPass, 1000.f, 0.707f, 0.f);
    // 12kHz sine should be heavily attenuated vs a 100Hz sine.
    auto rms = [&](float freq) {
        f.reset();
        double sum = 0;
        for (int i = 0; i < 48000; ++i) {
            const float x = std::sin(2.f * math::kPi * freq * i / 48000.f);
            const float y = f.process(x);
            if (i > 4800) sum += y * y; // skip transient
        }
        return std::sqrt(sum / (48000 - 4800));
    };
    const float lowRms = rms(100.f);
    const float highRms = rms(12000.f);
    CHECK(lowRms > 0.6f);            // passband ~unity
    CHECK(highRms < lowRms * 0.05f); // >26dB attenuation
}

TEST(biquad_peak_boost_at_center) {
    dsp::Biquad f;
    f.setSampleRate(48000);
    f.setParams(dsp::BiquadType::Peak, 1000.f, 2.f, 6.f);
    auto rms = [&](float freq) {
        f.reset();
        double sum = 0;
        for (int i = 0; i < 48000; ++i) {
            const float x = 0.5f * std::sin(2.f * math::kPi * freq * i / 48000.f);
            const float y = f.process(x);
            if (i > 4800) sum += y * y;
        }
        return std::sqrt(sum / (48000 - 4800));
    };
    const float center = rms(1000.f);
    const float away = rms(100.f);
    CHECK_NEAR(center / away, 2.0, 0.35); // ~+6dB at center
}

// ── FFT ──────────────────────────────────────────────────────────────────────
TEST(fft_sine_peak_bin) {
    dsp::Fft fft(1024);
    std::vector<float> time(1024), mags(512);
    const float freq = 440.f, sr = 48000.f;
    for (int i = 0; i < 1024; ++i) time[i] = std::sin(2.f * math::kPi * freq * i / sr);
    fft.forwardMagnitude(time.data(), mags.data());
    int peakBin = 0;
    for (int i = 1; i < 512; ++i) if (mags[i] > mags[peakBin]) peakBin = i;
    const float peakHz = peakBin * sr / 1024.f;
    CHECK(std::fabs(peakHz - freq) < sr / 1024.f * 1.5f);
}

TEST(fft_roundtrip) {
    dsp::Fft fft(256);
    std::vector<float> re(256), im(256), re0(256), im0(256, 0.f);
    for (int i = 0; i < 256; ++i) re[i] = re0[i] = std::sin(i * 0.1f) + 0.5f * std::cos(i * 0.37f);
    fft.forward(re.data(), im.data());
    fft.inverse(re.data(), im.data());
    for (int i = 0; i < 256; ++i) CHECK_NEAR(re[i], re0[i], 1e-4);
}

// ── Dynamics ─────────────────────────────────────────────────────────────────
TEST(compressor_reduces_loud_input) {
    fx::Compressor comp;
    comp.init(48000);
    comp.setParam(fx::dynparam::kThreshold, -20.f);
    comp.setParam(fx::dynparam::kRatio, 4.f);
    comp.setParam(fx::dynparam::kAttackMs, 1.f);
    comp.setParam(fx::dynparam::kReleaseMs, 50.f);
    comp.setParam(fx::dynparam::kMakeupDb, 0.f);
    comp.setParam(fx::dynparam::kMix, 1.f);
    std::vector<float> l(48000), r(48000);
    for (int i = 0; i < 48000; ++i) {
        l[i] = r[i] = 0.9f * std::sin(2.f * math::kPi * 220.f * i / 48000.f); // hot signal
    }
    comp.process(l.data(), r.data(), 48000);
    // After settling, output must be well below input amplitude.
    float tailPeak = 0.f;
    for (int i = 40000; i < 48000; ++i) tailPeak = std::max(tailPeak, std::fabs(l[i]));
    CHECK(tailPeak < 0.45f);
    CHECK(comp.gainReductionDb() < -3.f);
}

TEST(limiter_respects_ceiling) {
    fx::Limiter lim;
    lim.init(48000);
    lim.setParam(0, -1.f); // ceiling -1dBFS ~ 0.891
    std::vector<float> l(9600), r(9600);
    for (int i = 0; i < 9600; ++i) {
        const float burst = (i > 4800) ? 3.0f : 0.1f; // massive transient
        l[i] = r[i] = burst * std::sin(2.f * math::kPi * 440.f * i / 48000.f);
    }
    lim.process(l.data(), r.data(), 9600);
    for (int i = 5200; i < 9600; ++i) { // after look-ahead + attack
        CHECK(std::fabs(l[i]) <= 0.95f);
        CHECK(std::fabs(r[i]) <= 0.95f);
    }
}

TEST(gate_closes_below_threshold) {
    fx::Gate gate;
    gate.init(48000);
    gate.setParam(0, -40.f);
    std::vector<float> l(4800), r(4800);
    for (int i = 0; i < 4800; ++i) l[i] = r[i] = 0.001f * std::sin(i * 0.05f); // below threshold
    gate.process(l.data(), r.data(), 4800);
    float tailPeak = 0.f;
    for (int i = 4000; i < 4800; ++i) tailPeak = std::max(tailPeak, std::fabs(l[i]));
    CHECK(tailPeak < 0.0005f);
    CHECK(!gate.isOpen());
}

// ── Reverb & delay ───────────────────────────────────────────────────────────
TEST(reverb_produces_tail) {
    fx::Reverb rev;
    rev.init(48000);
    rev.setParam(fx::revparam::kSize, 0.8f);
    rev.setParam(fx::revparam::kWet, 0.f);
    rev.setParam(fx::revparam::kDry, -144.f);
    std::vector<float> l(48000, 0.f), r(48000, 0.f);
    l[0] = r[0] = 1.f; // impulse
    rev.process(l.data(), r.data(), 48000);
    // Energy must persist well past 100ms (diffuse tail), and decay.
    double early = 0, late = 0;
    for (int i = 4800; i < 9600; ++i) early += l[i] * l[i];
    for (int i = 43200; i < 48000; ++i) late += l[i] * l[i];
    CHECK(early > 1e-6);
    CHECK(late > 1e-9);
    CHECK(late < early);
}

TEST(delay_echo_at_set_time) {
    fx::DelayUnit delay(false);
    delay.init(48000);
    delay.setParam(fx::delayparam::kTimeMs, 100.f);
    delay.setParam(fx::delayparam::kFeedback, 0.f);
    delay.setParam(fx::delayparam::kDry, -144.f);
    delay.setParam(fx::delayparam::kWet, 0.f);
    std::vector<float> l(48000, 0.f), r(48000, 0.f);
    l[0] = r[0] = 1.f;
    // Process in engine-sized blocks so parameter smoothing converges
    // (mirrors real usage: setParam then continuous 128-frame callbacks).
    for (int off = 0; off < 48000; off += 128) {
        delay.process(l.data() + off, r.data() + off, 128);
    }
    // Echo should peak near sample 4800 (100ms @ 48k).
    int peak = 128;
    for (int i = 129; i < 48000; ++i) if (std::fabs(l[i]) > std::fabs(l[peak])) peak = i;
    CHECK(std::abs(peak - 4800) < 96);
}

// ── Loudness ─────────────────────────────────────────────────────────────────
TEST(loudness_sine_near_expected_lufs) {
    dsp::LoudnessMeter meter;
    meter.init(48000);
    // 1kHz sine at -3.01 dBFS (amplitude 0.707) should read roughly -3 LUFS
    // (K-weighting is ~flat at 1kHz; allow generous tolerance).
    std::vector<float> l(48000), r(48000);
    for (int i = 0; i < 48000; ++i) {
        l[i] = r[i] = 0.707f * std::sin(2.f * math::kPi * 1000.f * i / 48000.f);
    }
    // Feed in engine-sized blocks (the meter commits 10ms gating blocks
    // internally and assumes callback-sized calls, like the live engine).
    for (int off = 0; off < 48000; off += 480) {
        meter.process(l.data() + off, r.data() + off, 480);
    }
    dsp::LoudnessSnapshotNative snap;
    meter.snapshot(snap);
    CHECK(snap.momentaryLUFS > -8.f && snap.momentaryLUFS < 0.f);
    CHECK(snap.truePeakDbtp > -4.f);
}

// ── WAV round-trip ───────────────────────────────────────────────────────────
TEST(wav_roundtrip_24bit) {
    const char* path = "/tmp/s1_test_roundtrip.wav";
    dsp::WavFormat fmt; fmt.sampleRate = 48000; fmt.channels = 2; fmt.bitsPerSample = 24;
    {
        dsp::WavWriter w;
        CHECK(w.open(path, fmt));
        std::vector<float> data(4800 * 2);
        for (size_t i = 0; i < data.size(); ++i) {
            data[i] = 0.5f * std::sin(2.f * math::kPi * 440.f * (i / 2) / 48000.f);
        }
        CHECK(w.writeInterleaved(data.data(), 4800));
        CHECK(w.close());
    }
    {
        dsp::WavReader r;
        CHECK(r.open(path));
        CHECK(r.format().channels == 2);
        CHECK(r.format().sampleRate == 48000);
        CHECK(r.dataFrames() == 4800);
        std::vector<float> back;
        CHECK(r.readAll(back));
        CHECK(back.size() == 9600);
        // 24-bit quantization error bound.
        float maxErr = 0.f;
        for (size_t i = 0; i < back.size(); ++i) {
            const float expected = 0.5f * std::sin(2.f * math::kPi * 440.f * (i / 2) / 48000.f);
            maxErr = std::max(maxErr, std::fabs(back[i] - expected));
        }
        CHECK(maxErr < 1e-6f);
    }
    std::remove(path);
}

// ── FastMath ─────────────────────────────────────────────────────────────────
TEST(fast_tanh_accuracy) {
    for (float x = -4.f; x <= 4.f; x += 0.01f) {
        CHECK(std::fabs(math::fastTanh(x) - std::tanh(x)) < 2e-3f);
    }
}

TEST(db_gain_roundtrip) {
    for (float db = -100.f; db <= 20.f; db += 1.f) {
        const float lin = math::dbToGain(db);
        const float back = 20.f * std::log10(std::max(lin, 1e-9f));
        CHECK(std::fabs(back - db) < 0.01f);
    }
}

TEST(adsr_shape) {
    dsp::AdsrEnvelope env;
    env.setParams(10.f, 100.f, 0.5f, 200.f, 48000.f);
    env.noteOn();
    float peak = 0.f;
    for (int i = 0; i < 4800; ++i) peak = std::max(peak, env.process()); // 100ms
    CHECK_NEAR(peak, 1.f, 0.02f);
    for (int i = 0; i < 48000; ++i) env.process(); // settle into sustain
    float sus = env.process();
    CHECK_NEAR(sus, 0.5f, 0.02f);
    env.noteOff();
    for (int i = 0; i < 48000 * 2; ++i) env.process();
    CHECK(env.isIdle());
}

} // namespace

int main() {
    std::printf("StudioOne DSP tests: %zu cases\n", registry().size());
    int failedCases = 0;
    for (auto& tc : registry()) {
        const int before = failures;
        std::printf("- %s\n", tc.name.c_str());
        tc.fn();
        if (failures > before) failedCases++;
    }
    std::printf("\n%d checks, %d failed cases\n", checks, failedCases);
    return failedCases == 0 ? 0 : 1;
}
