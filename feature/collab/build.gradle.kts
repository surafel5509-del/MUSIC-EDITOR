plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.collab"
}

dependencies {
    implementation(projects.core.network)
}
