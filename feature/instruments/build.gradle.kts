plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.instruments"
}

dependencies {
    implementation(projects.audio)
    implementation(projects.midi)
}
