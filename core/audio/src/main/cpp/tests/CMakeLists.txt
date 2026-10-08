# Host-side unit tests for the DSP core (no Android/Oboe deps).
# Run: cmake -B build && cmake --build build && ./build/s1_dsp_tests
cmake_minimum_required(VERSION 3.22)
project(s1_dsp_tests CXX)
set(CMAKE_CXX_STANDARD 17)

add_executable(s1_dsp_tests
    test_main.cpp
    ../dsp/Biquad.cpp
    ../dsp/Fft.cpp
    ../dsp/Loudness.cpp
    ../dsp/WavFile.cpp
    ../fx/Compressor.cpp
    ../fx/Reverb.cpp
    ../fx/Modulation.cpp
    ../fx/Delay.cpp
    ../fx/PitchShift.cpp
)
target_include_directories(s1_dsp_tests PRIVATE ..)
target_compile_options(s1_dsp_tests PRIVATE -Wall -Wextra -Wno-unused-parameter)
