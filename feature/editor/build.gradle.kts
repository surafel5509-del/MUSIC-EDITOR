plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.editor"
}

dependencies {
    implementation(projects.audio)
    implementation(projects.midi)
}
