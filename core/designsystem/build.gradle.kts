plugins {
    id("studioone.android.library")
    id("studioone.android.compose")
}

android {
    namespace = "com.studioone.core.designsystem"
}

dependencies {
    implementation(projects.core.common)
    testImplementation(libs.bundles.testing.unit)
}
