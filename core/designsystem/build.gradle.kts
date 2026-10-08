plugins {
    id("studioone.android.library.compose")
}

dependencies {
    api(libs.compose.material3.window)
    api(libs.compose.material.icons.extended)
    api(libs.compose.animation)
    implementation(libs.androidx.core.ktx)
}
