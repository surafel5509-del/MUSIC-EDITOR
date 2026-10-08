package com.studioone.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** Compose-enabled Android library: enables buildFeatures.compose and adds the Compose BOM. */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("studioone.android.library")
                apply("org.jetbrains.kotlin.plugin.compose")
            }

            extensions.getByType<LibraryExtension>().apply {
                buildFeatures {
                    compose = true
                }
            }

            dependencies {
                val bom = platform("androidx.compose:compose-bom:2024.12.01")
                add("implementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", "androidx.compose.ui:ui")
                add("implementation", "androidx.compose.ui:ui-graphics")
                add("implementation", "androidx.compose.ui:ui-tooling-preview")
                add("implementation", "androidx.compose.foundation:foundation")
                add("implementation", "androidx.compose.material3:material3")
                add("debugImplementation", "androidx.compose.ui:ui-tooling")
                add("androidTestImplementation", "androidx.compose.ui:ui-test-junit4")
                add("debugImplementation", "androidx.compose.ui:ui-test-manifest")
            }
        }
    }
}
