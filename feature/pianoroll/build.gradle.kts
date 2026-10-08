plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.pianoroll"
}

dependencies {
    implementation(projects.audio)
    implementation(projects.midi)
}
