plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.datastore)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)
}
