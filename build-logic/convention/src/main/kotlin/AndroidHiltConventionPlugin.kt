package com.studioone.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Applies Hilt + KSP with the standard annotation processor wiring. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("dagger.hilt.android.plugin")
            }

            dependencies {
                add("implementation", "com.google.dagger:hilt-android:2.52")
                add("ksp", "com.google.dagger:hilt-android-compiler:2.52")
                add("implementation", "androidx.hilt:hilt-work:1.2.0")
                add("ksp", "androidx.hilt:hilt-compiler:1.2.0")
            }
        }
    }
}
