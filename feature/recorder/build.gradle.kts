plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.recorder"
}

dependencies {
    implementation(projects.audio)
    implementation(projects.midi)
}
