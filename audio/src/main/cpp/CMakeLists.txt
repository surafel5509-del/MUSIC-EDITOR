cmake_minimum_required(VERSION 3.22...3.31)
project(studioone_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ----------------------------------------------------------------------------
# Oboe: pulled from GitHub so the build always uses the audio fixes for
# current Android releases. Falls back to a local checkout when offline
# (set OBOE_LOCAL_PATH).
# ----------------------------------------------------------------------------
include(FetchContent)
if(DEFINED OBOE_LOCAL_PATH)
    add_subdirectory(${OBOE_LOCAL_PATH} oboe)
else()
    FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG        1.9.0
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(oboe)
endif()

add_library(studioone-audio SHARED
    engine/AudioEngine.cpp
    engine/jni_bridge.cpp
    engine/WavFile.cpp
    graph/AudioGraph.cpp
    dsp/effects.cpp
    dsp/metering.cpp
    instruments/Sampler.cpp
    instruments/SubtractiveSynth.cpp
    instruments/DrumSynth.cpp
)

target_include_directories(studioone-audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Real-time safety is enforced by design (see docs/AUDIO_ENGINE.md); enable
# extra warnings so regressions are caught in CI. -fno-rtti is NOT enabled
# because Oboe relies on RTTI.
target_compile_options(studioone-audio PRIVATE
    -Wall -Wextra -Werror=return-type
)

target_link_libraries(studioone-audio oboe log atomic)

# ----------------------------------------------------------------------------
# Host unit tests (ring buffer, biquad, dynamics) via GoogleTest. Enabled with
#   cmake -DBUILD_AUDIO_TESTS=ON ..
# ----------------------------------------------------------------------------
option(BUILD_AUDIO_TESTS "Build native audio unit tests" OFF)
if(BUILD_AUDIO_TESTS)
    FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.zip
    )
    FetchContent_MakeAvailable(googletest)
    enable_testing()

    add_executable(audio_tests
        tests/test_ring_buffer.cpp
        tests/test_biquad.cpp
        tests/test_envelope.cpp
        dsp/effects.cpp
        dsp/metering.cpp
    )
    target_include_directories(audio_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(audio_tests gtest_main)
    include(GoogleTest)
    gtest_discover_tests(audio_tests)
endif()
