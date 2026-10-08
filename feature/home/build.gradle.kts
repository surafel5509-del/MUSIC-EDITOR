plugins {
    id("studioone.android.feature")
}

android {
    namespace = "com.studioone.feature.home"
}

dependencies {
    testImplementation(libs.robolectric)
}
