package com.studioone.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Convention for non-UI Android library modules (core:*, data layers). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                // Module namespace derived from project path: :core:audio -> core.audio
                namespace = StudioOne.NAMESPACE_PREFIX + project.path.replace(":", ".").replace("-", "_")
                configureKotlinAndroid(this)

                defaultConfig {
                    consumerProguardFiles("consumer-rules.pro")
                }

                // Library modules only ship debug/release; the app aggregates.
                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = false // R8 runs once at the app level.
                    }
                }
            }

            dependencies {
                add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                add("implementation", "com.jakewharton.timber:timber:5.0.1")
            }
        }
    }
}
