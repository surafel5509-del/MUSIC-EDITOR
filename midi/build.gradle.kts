plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
}

android {
    namespace = "com.studioone.midi"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    testImplementation(libs.bundles.testing.unit)
}
