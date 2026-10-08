plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.mixer"
}

dependencies {
    implementation(projects.audio)
}
