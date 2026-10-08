plugins {
    id("studioone.android.jni.library")
    id("studioone.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Ship the shared STL alongside our engine .so
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.hilt.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
