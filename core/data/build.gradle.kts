plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.studioone.core.data"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.database)
    implementation(projects.core.network)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.work.runtime.ktx)
    testImplementation(libs.bundles.testing.unit)
}
