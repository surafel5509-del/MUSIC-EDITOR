package studioone

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for feature modules: Android library + Compose + Hilt plus the
 * core modules every feature needs (domain, designsystem, ui, common).
 */
class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("studioone.android.library")
                apply("studioone.android.compose")
                apply("studioone.android.hilt")
            }
            dependencies {
                "implementation"(project(":core:common"))
                "implementation"(project(":core:domain"))
                "implementation"(project(":core:designsystem"))
                "implementation"(project(":core:ui"))
                "implementation"(libs.findBundle("lifecycle").get())
                "implementation"(libs.findLibrary("navigation-compose").get())
                "implementation"(libs.findLibrary("hilt-navigation-compose").get())
                "testImplementation"(libs.findBundle("testing-unit").get())
            }
        }
    }
}
