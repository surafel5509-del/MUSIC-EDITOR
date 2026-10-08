plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.audio)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
