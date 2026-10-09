package studioone

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Applies KSP + Room and configures the schema export directory. */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            extensions.configure(com.google.devtools.ksp.gradle.KspExtension::class.java) {
                arg("room.schemaLocation", "$projectDir/schemas")
                arg("room.incremental", "true")
            }

            dependencies {
                "implementation"(libs.findLibrary("room-runtime").get())
                "implementation"(libs.findLibrary("room-ktx").get())
                "ksp"(libs.findLibrary("room-compiler").get())
            }
        }
    }
}
