plugins {
    id("studioone.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM module: the CRDT kernel must be testable without Robolectric and
// is a candidate for future KMP reuse (desktop companion app roadmap).
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okhttp.core)
    implementation(libs.timber)

    testImplementation(projects.core.domain)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
