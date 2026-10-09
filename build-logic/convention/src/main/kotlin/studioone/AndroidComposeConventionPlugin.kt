package studioone

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Applies the Kotlin Compose compiler and adds the shared Compose dependencies. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val commonExtension = extensions.configure(CommonExtension::class.java) {
                buildFeatures { compose = true }
            }

            dependencies {
                val bom = libs.findLibrary("compose-bom").get()
                "implementation"(platform(bom))
                "implementation"(libs.findBundle("compose").get())
                "implementation"(libs.findLibrary("androidx-activity-compose").get())
                "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
                "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
                "androidTestImplementation"(platform(bom))
                "androidTestImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
            }
        }
    }
}
