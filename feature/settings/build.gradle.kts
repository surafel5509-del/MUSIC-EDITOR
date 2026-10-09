plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.settings"
}

dependencies {
    // Settings displays the live output-latency readout from the engine.
    implementation(projects.audio)
}
