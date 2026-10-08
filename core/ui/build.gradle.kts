plugins {
    id("studioone.android.library")
    id("studioone.android.compose")
}

android {
    namespace = "com.studioone.core.ui"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
}
