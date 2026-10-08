package com.studioone.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Convention for modules that ship native code (:core:audio).
 * Configures NDK, CMake, and ABI filters. The audio engine uses Oboe which
 * internally falls back to OpenSL ES on API < 27 devices — satisfying the
 * AAudio-first / OpenSL-fallback requirement without maintaining two backends.
 */
class AndroidJniLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("studioone.android.library")

            extensions.configure<LibraryExtension> {
                ndkVersion = "27.1.12297006"
                defaultConfig {
                    externalNativeBuild {
                        cmake {
                            // -O3 + fast math for the DSP hot path; exceptions off in audio thread code.
                            cppFlags += listOf("-std=c++17", "-ffast-math", "-fno-exceptions", "-fno-rtti")
                            cFlags += listOf("-std=c17", "-O3")
                            arguments += listOf(
                                "-DANDROID_STL=c++_shared",
                                "-DANDROID_ARM_NEON=ON",
                                "-DOBOE_VERSION=1.9.0",
                            )
                            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
                        }
                    }
                }
                externalNativeBuild {
                    cmake {
                        path = project.file("src/main/cpp/CMakeLists.txt")
                        version = "3.22.1"
                    }
                }
                buildTypes {
                    getByName("debug") {
                        externalNativeBuild {
                            cmake {
                                cppFlags += listOf("-fsanitize=undefined", "-fno-omit-frame-pointer")
                            }
                        }
                    }
                }
            }
        }
    }
}
