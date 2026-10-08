plugins {
    id("studioone.android.library")
    id("studioone.android.hilt")
}

android {
    namespace = "com.studioone.core.datastore"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    api(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.testing.unit)
}
