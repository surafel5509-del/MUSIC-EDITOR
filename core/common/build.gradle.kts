plugins {
    id("studioone.android.library")
}

android {
    namespace = "com.studioone.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    testImplementation(libs.bundles.testing.unit)
}
