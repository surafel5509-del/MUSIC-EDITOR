# ─────────────────────────────────────────────────────────────────────────────
# StudioOne native audio engine
#
# Backends: AAudio (API 26+, MMAP exclusive when available) with automatic
# OpenSL ES fallback — handled inside Oboe, so a single code path serves both.
#
# Real-time contract (enforced in code review + docs/AUDIO_ENGINE.md):
#   * No heap allocation inside the audio callback (pools pre-sized at init)
#   * No locks (only atomics + SPSC/MPSC ring buffers)
#   * No JNI calls, no file I/O, no logging on the audio thread
#   * Bounded, branch-predictable work per block; denormals disabled
# ─────────────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project("studioone-audio" LANGUAGES CXX C VERSION 1.0.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── Oboe (fetched from GitHub at configure time; pinned tag) ─────────────────
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        ${OBOE_VERSION}
)
# Build Oboe with the same STL as the app module (c++_shared, set by convention plugin).
set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(oboe)

add_library(studioone-audio SHARED
    # dsp primitives
    dsp/Biquad.cpp
    dsp/Fft.cpp
    dsp/Loudness.cpp
    dsp/WavFile.cpp
    # effects
    fx/Compressor.cpp
    fx/Reverb.cpp
    fx/Modulation.cpp
    fx/PitchShift.cpp
    # graph & instruments
    graph/AudioGraph.cpp
    graph/FxChainRuntime.cpp
    instrument/VoiceManager.cpp
    instrument/SubtractiveSynth.cpp
    instrument/SamplerVoice.cpp
    instrument/DrumMachine.cpp
    # engine & transport
    engine/AudioEngine.cpp
    engine/Transport.cpp
    engine/Recorder.cpp
    engine/EngineCommands.cpp
    # JNI
    jni/jni_bridge.cpp
)

target_include_directories(studioone-audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${oboe_SOURCE_DIR}/include
)

target_link_libraries(studioone-audio
    PRIVATE
    oboe
    log
    atomic
)

# Hardened warnings; the audio hot path compiles with -O3 (set in gradle convention).
target_compile_options(studioone-audio PRIVATE
    -Wall -Wextra -Werror=return-type -Wno-unused-parameter
)

# Release: LTO where supported; Debug keeps symbols for simpleperf/asan.
if (CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    include(CheckIPOSupported)
    check_ipo_supported(RESULT IPO_OK)
    if (IPO_OK)
        set_property(TARGET studioone-audio PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
    endif ()
endif ()
