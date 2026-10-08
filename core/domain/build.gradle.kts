plugins {
    id("studioone.android.library")
}

android {
    namespace = "com.studioone.core.domain"
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    testImplementation(libs.bundles.testing.unit)
}
