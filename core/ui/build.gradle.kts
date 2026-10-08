plugins {
    id("studioone.android.library.compose")
    id("studioone.android.hilt")
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
