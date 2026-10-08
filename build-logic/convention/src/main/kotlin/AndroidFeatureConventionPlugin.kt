package com.studioone.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for :feature:* modules. Adds Compose, Hilt, navigation, and the
 * standard core module dependencies every feature needs. Feature modules may
 * depend on core modules but NEVER on each other (enforced in docs/ARCHITECTURE.md
 * and by the module dependency graph in CI).
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("studioone.android.library.compose")
                apply("studioone.android.hilt")
                apply("org.jetbrains.kotlin.plugin.serialization")
            }

            dependencies {
                add("implementation", project(":core:model"))
                add("implementation", project(":core:common"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:domain"))

                add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
                add("implementation", "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
                add("implementation", "androidx.hilt:hilt-navigation-compose:1.2.0")
                add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
    }
}
