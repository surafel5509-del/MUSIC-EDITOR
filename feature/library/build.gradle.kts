plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.library"
}

dependencies {
    implementation(projects.audio)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
}
