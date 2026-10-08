plugins {
    id("studioone.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// core:model is a pure-Kotlin module (no Android dependencies) so it can be
// unit-tested on the JVM, shared with backend tooling, and kept allocation-free
// friendly for the audio layer.
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.coroutines.core)
}
