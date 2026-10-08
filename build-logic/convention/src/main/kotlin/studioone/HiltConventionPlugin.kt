package studioone

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies Hilt + KSP and wires the annotation processors. Modules that only
 * declare @Inject constructors can depend on `javax.inject` instead and skip
 * this plugin entirely.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("dagger.hilt.android.plugin")
            }
            dependencies {
                "implementation"(libs.findLibrary("hilt-android").get())
                "ksp"(libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
