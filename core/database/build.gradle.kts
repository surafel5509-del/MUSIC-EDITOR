plugins {
    id("studioone.android.library")
    id("studioone.android.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.studioone.core.database"
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.room.testing)
}
